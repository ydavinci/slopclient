use std::{
    collections::{HashMap, HashSet},
    path::PathBuf,
};

use serde::Serialize;

use super::{
    ContentEntry, ContentLock, ContentType, DISABLED_SUFFIX, Source,
    modrinth::{self, Project, Version},
};
use crate::{
    Error, Paths, ProgressFn, Result,
    download::{DownloadTask, download_all, sha1_file},
    instance::Instance,
    meta::LoaderKind,
    progress::stage,
};

#[derive(Debug, Clone, Default, Serialize)]
pub struct InstallReport {
    pub installed: Vec<ContentEntry>,
    /// Projects that were already installed.
    pub skipped: Vec<String>,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct UpdateInfo {
    pub entry: ContentEntry,
    pub new_version: Version,
}

/// Content operations for one instance.
pub struct ContentManager<'a> {
    paths: &'a Paths,
    instance: Instance,
    concurrency: usize,
}

impl<'a> ContentManager<'a> {
    pub fn new(paths: &'a Paths, instance: Instance, concurrency: usize) -> Self {
        Self {
            paths,
            instance,
            concurrency,
        }
    }

    fn lock_path(&self) -> PathBuf {
        self.instance.dir(self.paths).join("content.lock.json")
    }

    fn folder(&self, kind: ContentType) -> PathBuf {
        self.instance.game_dir(self.paths).join(kind.folder())
    }

    fn file_path(&self, kind: ContentType, file_name: &str, enabled: bool) -> PathBuf {
        let name = if enabled {
            file_name.to_owned()
        } else {
            format!("{file_name}{DISABLED_SUFFIX}")
        };
        self.folder(kind).join(name)
    }

    fn read_lock(&self) -> Result<ContentLock> {
        match std::fs::read_to_string(self.lock_path()) {
            Ok(text) => Ok(serde_json::from_str(&text)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(ContentLock::default()),
            Err(e) => Err(e.into()),
        }
    }

    fn write_lock(&self, lock: &ContentLock) -> Result<()> {
        std::fs::write(self.lock_path(), serde_json::to_string_pretty(lock)?)?;
        Ok(())
    }

    /// Loader names Modrinth uses to filter versions of `kind` for this instance.
    fn loaders_for(&self, kind: ContentType) -> Result<Vec<String>> {
        let names: &[&str] = match kind {
            ContentType::ResourcePack => &["minecraft"],
            ContentType::Shader => &[],
            ContentType::Mod => match self.instance.loader.kind() {
                None => {
                    return Err(Error::Other(
                        "this is a vanilla instance; choose a mod loader to use mods".into(),
                    ));
                }
                Some(LoaderKind::Fabric) => &["fabric"],
                // Quilt runs most Fabric mods.
                Some(LoaderKind::Quilt) => &["quilt", "fabric"],
                Some(LoaderKind::NeoForge) => &["neoforge"],
                Some(LoaderKind::Forge) => &["forge"],
            },
        };
        Ok(names.iter().map(|s| s.to_string()).collect())
    }

    async fn best_version(&self, project_id: &str, kind: ContentType) -> Result<Option<Version>> {
        let versions = modrinth::project_versions(
            project_id,
            &self.loaders_for(kind)?,
            Some(&self.instance.mc_version),
        )
        .await?;
        Ok(modrinth::pick_best(versions))
    }

    /// Tracked and untracked content, with the lock file reconciled against the disk.
    pub async fn list(&self) -> Result<Vec<ContentEntry>> {
        let mut lock = self.read_lock()?;
        let before = lock.entries.len();
        let mut changed = false;
        lock.entries.retain_mut(|e| {
            let enabled = self.file_path(e.kind, &e.file_name, true).exists();
            let disabled = self.file_path(e.kind, &e.file_name, false).exists();
            if enabled != e.enabled && (enabled || disabled) {
                e.enabled = enabled;
                changed = true;
            }
            enabled || disabled
        });
        if changed || lock.entries.len() != before {
            self.write_lock(&lock)?;
        }

        let tracked: HashSet<(ContentType, String)> = lock
            .entries
            .iter()
            .map(|e| (e.kind, e.file_name.clone()))
            .collect();
        let mut out = lock.entries;

        for kind in ContentType::ALL {
            let Ok(dir) = std::fs::read_dir(self.folder(kind)) else {
                continue;
            };
            for item in dir.flatten() {
                let raw = item.file_name().to_string_lossy().into_owned();
                let (file_name, enabled) = match raw.strip_suffix(DISABLED_SUFFIX) {
                    Some(base) => (base.to_owned(), false),
                    None => (raw.clone(), true),
                };
                let is_dir = item.file_type().is_ok_and(|t| t.is_dir());
                let relevant = match kind {
                    ContentType::Mod => !is_dir && file_name.ends_with(".jar"),
                    _ => is_dir || file_name.ends_with(".zip"),
                };
                if !relevant || tracked.contains(&(kind, file_name.clone())) {
                    continue;
                }
                let sha1 = if is_dir { String::new() } else { sha1_file(&item.path()).await? };
                let name = file_name
                    .trim_end_matches(".jar")
                    .trim_end_matches(".zip")
                    .to_owned();
                out.push(ContentEntry {
                    file_name,
                    kind,
                    name,
                    version: None,
                    sha1,
                    source: Source::Local,
                    enabled,
                    dependency: false,
                    icon_url: None,
                });
            }
        }
        out.sort_by_key(|e| (e.kind as u8, e.name.to_lowercase()));
        Ok(out)
    }

    /// Installs a Modrinth project (id or slug) plus its required dependencies.
    pub async fn install(&self, query: &str, progress: &ProgressFn) -> Result<InstallReport> {
        stage(progress, "Resolving project");
        let mut report = InstallReport::default();
        let current = self.list().await?;
        let installed: HashSet<String> = current
            .iter()
            .filter_map(|e| e.project_id().map(str::to_owned))
            .collect();

        let project = modrinth::project(query).await?;
        let kind = ContentType::from_modrinth(&project.project_type).ok_or_else(|| {
            Error::Other(format!(
                "{} is a {}; only mods, resource packs and shaders can be added",
                project.title, project.project_type
            ))
        })?;
        if installed.contains(&project.id) {
            report.skipped.push(project.title);
            return Ok(report);
        }
        let version = self.best_version(&project.id, kind).await?.ok_or_else(|| {
            Error::Other(format!(
                "{} has no version for Minecraft {} ({})",
                project.title, self.instance.mc_version, self.instance.loader
            ))
        })?;

        let mut visited = installed.clone();
        visited.insert(project.id.clone());
        let mut queue = vec![(project, version, kind, false)];
        let mut plan: Vec<(Project, Version, ContentType, bool)> = Vec::new();

        stage(progress, "Resolving dependencies");
        while let Some((proj, ver, kind, is_dep)) = queue.pop() {
            for dep in &ver.dependencies {
                match dep.dependency_type.as_str() {
                    "required" => {
                        let pinned = match &dep.version_id {
                            Some(id) => Some(modrinth::version(id).await?),
                            None => None,
                        };
                        let Some(pid) = dep
                            .project_id
                            .clone()
                            .or_else(|| pinned.as_ref().map(|v| v.project_id.clone()))
                        else {
                            continue;
                        };
                        if !visited.insert(pid.clone()) {
                            continue;
                        }
                        let dep_project = modrinth::project(&pid).await?;
                        let dep_kind = ContentType::from_modrinth(&dep_project.project_type)
                            .unwrap_or(ContentType::Mod);
                        let dep_version = match pinned {
                            Some(v) => Some(v),
                            None => self.best_version(&pid, dep_kind).await?,
                        };
                        match dep_version {
                            Some(v) => queue.push((dep_project, v, dep_kind, true)),
                            None => report.warnings.push(format!(
                                "required dependency {} of {} is not available for this instance",
                                dep_project.title, proj.title
                            )),
                        }
                    }
                    "incompatible" => {
                        if let Some(pid) = &dep.project_id
                            && let Some(e) = current.iter().find(|e| e.project_id() == Some(pid)) {
                                report.warnings.push(format!(
                                    "{} is incompatible with the installed {}",
                                    proj.title, e.name
                                ));
                            }
                    }
                    _ => {}
                }
            }
            plan.push((proj, ver, kind, is_dep));
        }

        let mut tasks = Vec::new();
        let mut lock = self.read_lock()?;
        for (proj, ver, kind, is_dep) in plan {
            let file = ver
                .primary_file()
                .ok_or_else(|| Error::Other(format!("{} {} has no files", proj.title, ver.version_number)))?;
            tasks.push(
                DownloadTask::new(&file.url, self.file_path(kind, &file.filename, true))
                    .sha1(Some(file.hashes.sha1.clone()))
                    .size(Some(file.size)),
            );
            let entry = ContentEntry {
                file_name: file.filename.clone(),
                kind,
                name: proj.title.clone(),
                version: Some(ver.version_number.clone()),
                sha1: file.hashes.sha1.clone(),
                source: Source::Modrinth {
                    project_id: proj.id.clone(),
                    version_id: ver.id.clone(),
                    slug: Some(proj.slug.clone()),
                },
                enabled: true,
                dependency: is_dep,
                icon_url: proj.icon_url.clone(),
            };
            lock.entries.retain(|e| !(e.kind == kind && e.file_name == entry.file_name));
            lock.entries.push(entry.clone());
            report.installed.push(entry);
        }

        stage(progress, "Downloading");
        download_all(tasks, self.concurrency, progress).await?;
        self.write_lock(&lock)?;

        if report.installed.iter().any(|e| e.kind == ContentType::Shader) {
            let has_shader_mod = lock.entries.iter().any(|e| {
                matches!(&e.source, Source::Modrinth { slug: Some(s), .. } if s == "iris" || s == "oculus")
            });
            if !has_shader_mod {
                report
                    .warnings
                    .push("shader packs need a shader mod: add `iris` to this instance".into());
            }
        }
        Ok(report)
    }

    fn find_one(&self, entries: &[ContentEntry], query: &str) -> Result<ContentEntry> {
        let matches: Vec<&ContentEntry> = entries.iter().filter(|e| e.matches(query)).collect();
        match matches.as_slice() {
            [one] => Ok((*one).clone()),
            [] => Err(Error::Other(format!("nothing installed matches '{query}'"))),
            _ => Err(Error::Other(format!(
                "'{query}' is ambiguous: {}",
                matches.iter().map(|e| e.file_name.as_str()).collect::<Vec<_>>().join(", ")
            ))),
        }
    }

    pub async fn remove(&self, query: &str) -> Result<ContentEntry> {
        let entry = self.find_one(&self.list().await?, query)?;
        let path = self.file_path(entry.kind, &entry.file_name, entry.enabled);
        if path.is_dir() {
            std::fs::remove_dir_all(&path)?;
        } else {
            std::fs::remove_file(&path)?;
        }
        let mut lock = self.read_lock()?;
        lock.entries
            .retain(|e| !(e.kind == entry.kind && e.file_name == entry.file_name));
        self.write_lock(&lock)?;
        Ok(entry)
    }

    pub async fn set_enabled(&self, query: &str, enabled: bool) -> Result<ContentEntry> {
        let mut entry = self.find_one(&self.list().await?, query)?;
        if entry.enabled != enabled {
            std::fs::rename(
                self.file_path(entry.kind, &entry.file_name, entry.enabled),
                self.file_path(entry.kind, &entry.file_name, enabled),
            )?;
            entry.enabled = enabled;
            let mut lock = self.read_lock()?;
            if let Some(e) = lock
                .entries
                .iter_mut()
                .find(|e| e.kind == entry.kind && e.file_name == entry.file_name)
            {
                e.enabled = enabled;
                self.write_lock(&lock)?;
            }
        }
        Ok(entry)
    }

    /// Newer compatible versions for tracked Modrinth content.
    pub async fn check_updates(&self) -> Result<Vec<UpdateInfo>> {
        let entries = self.list().await?;
        let mut updates = Vec::new();
        for kind in ContentType::ALL {
            let tracked: Vec<&ContentEntry> = entries
                .iter()
                .filter(|e| e.kind == kind && matches!(e.source, Source::Modrinth { .. }))
                .collect();
            if tracked.is_empty() {
                continue;
            }
            let hashes: Vec<String> = tracked.iter().map(|e| e.sha1.clone()).collect();
            let latest =
                modrinth::latest_versions(&hashes, &self.loaders_for(kind)?, &self.instance.mc_version)
                    .await?;
            for entry in tracked {
                let Source::Modrinth { version_id, .. } = &entry.source else {
                    continue;
                };
                if let Some(new) = latest.get(&entry.sha1)
                    && &new.id != version_id && new.primary_file().is_some() {
                        updates.push(UpdateInfo {
                            entry: entry.clone(),
                            new_version: new.clone(),
                        });
                    }
            }
        }
        Ok(updates)
    }

    /// Replaces files with their new versions (disabled files stay disabled).
    pub async fn apply_updates(&self, updates: &[UpdateInfo], progress: &ProgressFn) -> Result<()> {
        let mut tasks = Vec::new();
        for u in updates {
            let file = u.new_version.primary_file().expect("checked in check_updates");
            tasks.push(
                DownloadTask::new(&file.url, self.file_path(u.entry.kind, &file.filename, u.entry.enabled))
                    .sha1(Some(file.hashes.sha1.clone()))
                    .size(Some(file.size)),
            );
        }
        stage(progress, "Downloading updates");
        download_all(tasks, self.concurrency, progress).await?;

        let mut lock = self.read_lock()?;
        for u in updates {
            let file = u.new_version.primary_file().expect("checked in check_updates");
            if file.filename != u.entry.file_name {
                let old = self.file_path(u.entry.kind, &u.entry.file_name, u.entry.enabled);
                if old.exists() {
                    std::fs::remove_file(old)?;
                }
            }
            if let Some(e) = lock
                .entries
                .iter_mut()
                .find(|e| e.kind == u.entry.kind && e.file_name == u.entry.file_name)
            {
                e.file_name = file.filename.clone();
                e.sha1 = file.hashes.sha1.clone();
                e.version = Some(u.new_version.version_number.clone());
                if let Source::Modrinth { version_id, .. } = &mut e.source {
                    *version_id = u.new_version.id.clone();
                }
            }
        }
        self.write_lock(&lock)
    }

    /// Looks up untracked files on Modrinth by hash so they can be updated too.
    /// Returns how many files were recognized.
    pub async fn identify(&self) -> Result<usize> {
        let local: Vec<ContentEntry> = self
            .list()
            .await?
            .into_iter()
            .filter(|e| e.source == Source::Local && !e.sha1.is_empty())
            .collect();
        let hashes: Vec<String> = local.iter().map(|e| e.sha1.clone()).collect();
        let found = modrinth::versions_by_hash(&hashes).await?;
        let project_ids: Vec<String> = found
            .values()
            .map(|v| v.project_id.clone())
            .collect::<HashSet<_>>()
            .into_iter()
            .collect();
        let projects: HashMap<String, Project> = modrinth::projects(&project_ids)
            .await?
            .into_iter()
            .map(|p| (p.id.clone(), p))
            .collect();

        let mut lock = self.read_lock()?;
        let mut count = 0;
        for mut entry in local {
            let Some(version) = found.get(&entry.sha1) else {
                continue;
            };
            let project = projects.get(&version.project_id);
            entry.name = project.map_or(entry.name, |p| p.title.clone());
            entry.icon_url = project.and_then(|p| p.icon_url.clone());
            entry.version = Some(version.version_number.clone());
            entry.source = Source::Modrinth {
                project_id: version.project_id.clone(),
                version_id: version.id.clone(),
                slug: project.map(|p| p.slug.clone()),
            };
            lock.entries.push(entry);
            count += 1;
        }
        self.write_lock(&lock)?;
        Ok(count)
    }
}
