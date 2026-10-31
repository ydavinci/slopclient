use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};

use super::{
    ContentEntry, ContentLock, ContentType, DISABLED_SUFFIX, Source,
    curseforge::{self, CurseForge},
    modrinth::{self, Project, Version},
};
use crate::{
    Error, Paths, ProgressFn, Result,
    download::{DownloadTask, sha1_file},
    store,
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
    /// CurseForge files the author only allows on the website.
    pub blocked: Vec<BlockedFile>,
}

/// A downloadable file of either platform.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteFile {
    /// `None` for CurseForge files with third-party downloads disabled.
    pub url: Option<String>,
    pub filename: String,
    pub sha1: Option<String>,
    pub size: u64,
}

/// A newer version of tracked content.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteVersion {
    pub id: String,
    pub project_id: String,
    pub version_number: String,
    pub version_type: String,
    pub file: RemoteFile,
}

#[derive(Debug, Clone, Serialize)]
pub struct UpdateInfo {
    pub entry: ContentEntry,
    pub new_version: RemoteVersion,
}

/// A CurseForge file the user has to download in the browser. Stored in
/// `blocked.json` until [`ContentManager::resolve_blocked`] finds it.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BlockedFile {
    pub kind: ContentType,
    pub name: String,
    pub file_name: String,
    pub project_id: u64,
    pub file_id: u64,
    #[serde(default)]
    pub slug: Option<String>,
    /// Download page on curseforge.com.
    pub page_url: String,
    pub sha1: Option<String>,
    pub size: u64,
    #[serde(default)]
    pub dependency: bool,
    #[serde(default)]
    pub icon_url: Option<String>,
}

impl BlockedFile {
    fn entry(&self) -> ContentEntry {
        ContentEntry {
            file_name: self.file_name.clone(),
            kind: self.kind,
            name: self.name.clone(),
            version: None,
            sha1: self.sha1.clone().unwrap_or_default(),
            source: Source::CurseForge {
                project_id: self.project_id,
                file_id: self.file_id,
                slug: self.slug.clone(),
            },
            enabled: true,
            dependency: self.dependency,
            icon_url: self.icon_url.clone(),
        }
    }
}

impl From<&Version> for RemoteVersion {
    fn from(v: &Version) -> Self {
        let file = v.primary_file().expect("caller checks for a file");
        Self {
            id: v.id.clone(),
            project_id: v.project_id.clone(),
            version_number: v.version_number.clone(),
            version_type: v.version_type.clone(),
            file: RemoteFile {
                url: Some(file.url.clone()),
                filename: file.filename.clone(),
                sha1: Some(file.hashes.sha1.clone()),
                size: file.size,
            },
        }
    }
}

impl From<&curseforge::File> for RemoteVersion {
    fn from(f: &curseforge::File) -> Self {
        Self {
            id: f.id.to_string(),
            project_id: f.mod_id.to_string(),
            version_number: f.display_name.clone(),
            version_type: f.release_label().into(),
            file: RemoteFile {
                url: f.url(),
                filename: f.file_name.clone(),
                sha1: f.sha1(),
                size: f.file_length,
            },
        }
    }
}

/// Content operations for one instance.
pub struct ContentManager<'a> {
    paths: &'a Paths,
    instance: Instance,
    concurrency: usize,
    curseforge: Option<CurseForge>,
}

fn detect_kind(name: &str, bytes: &[u8]) -> Result<ContentType> {
    let lower = name.to_ascii_lowercase();
    if lower.ends_with(".jar") {
        return Ok(ContentType::Mod);
    }
    if !lower.ends_with(".zip") {
        return Err(Error::Other(format!("{name}: nur .jar- und .zip-Dateien werden unterstützt")));
    }
    let archive = zip::ZipArchive::new(std::io::Cursor::new(bytes))?;
    let names: Vec<&str> = archive.file_names().collect();
    let has = |prefix: &str| names.iter().any(|n| n.starts_with(prefix));
    if has("shaders/") {
        Ok(ContentType::Shader)
    } else if has("pack.mcmeta") && has("assets/") {
        Ok(ContentType::ResourcePack)
    } else if has("pack.mcmeta") && has("data/") {
        Err(Error::Other(format!("{name} ist ein Datapack. Füge es im Tab „Welten“ einer Welt hinzu.")))
    } else if has("pack.mcmeta") {
        Ok(ContentType::ResourcePack)
    } else {
        Err(Error::Other(format!("{name} ist weder Resourcepack noch Shader")))
    }
}

impl<'a> ContentManager<'a> {
    pub fn new(paths: &'a Paths, instance: Instance, concurrency: usize) -> Self {
        Self {
            paths,
            instance,
            concurrency,
            curseforge: None,
        }
    }

    /// Enables CurseForge operations (no-op without a key).
    pub fn with_curseforge(mut self, key: Option<String>) -> Self {
        self.curseforge = key.map(CurseForge::new);
        self
    }

    fn cf(&self) -> Result<&CurseForge> {
        self.curseforge.as_ref().ok_or(Error::MissingCurseForgeKey)
    }

    fn lock_path(&self) -> PathBuf {
        self.instance.dir(self.paths).join("content.lock.json")
    }

    fn blocked_path(&self) -> PathBuf {
        self.instance.dir(self.paths).join("blocked.json")
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

    /// Files still waiting for a manual download.
    pub fn blocked(&self) -> Result<Vec<BlockedFile>> {
        match std::fs::read_to_string(self.blocked_path()) {
            Ok(text) => Ok(serde_json::from_str(&text)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
            Err(e) => Err(e.into()),
        }
    }

    fn write_blocked(&self, blocked: &[BlockedFile]) -> Result<()> {
        if blocked.is_empty() {
            match std::fs::remove_file(self.blocked_path()) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
                _ => return Ok(()),
            }
        }
        std::fs::write(self.blocked_path(), serde_json::to_string_pretty(blocked)?)?;
        Ok(())
    }

    fn add_blocked(&self, new: &[BlockedFile]) -> Result<()> {
        if new.is_empty() {
            return Ok(());
        }
        let mut blocked = self.blocked()?;
        blocked.retain(|b| !new.iter().any(|n| n.file_id == b.file_id));
        blocked.extend_from_slice(new);
        self.write_blocked(&blocked)
    }

    /// Mod loaders whose content runs on this instance (Quilt also runs Fabric mods).
    fn mod_loaders(&self) -> Result<Vec<LoaderKind>> {
        match self.instance.loader.kind() {
            None => Err(Error::Other(
                "this is a vanilla instance; choose a mod loader to use mods".into(),
            )),
            Some(LoaderKind::Quilt) => Ok(vec![LoaderKind::Quilt, LoaderKind::Fabric]),
            Some(kind) => Ok(vec![kind]),
        }
    }

    /// Loader names Modrinth uses to filter versions of `kind` for this instance.
    fn loaders_for(&self, kind: ContentType) -> Result<Vec<String>> {
        Ok(match kind {
            ContentType::ResourcePack => vec!["minecraft".into()],
            ContentType::Shader => Vec::new(),
            ContentType::Mod => self
                .mod_loaders()?
                .into_iter()
                .map(|l| format!("{l:?}").to_lowercase())
                .collect(),
        })
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

    async fn best_cf_file(&self, mod_id: u64, kind: ContentType) -> Result<Option<curseforge::File>> {
        let loaders = match kind {
            ContentType::Mod => self.mod_loaders()?,
            _ => Vec::new(),
        };
        // A single loader can be filtered server-side; Quilt needs Fabric files too.
        let filter = match loaders.as_slice() {
            [one] => Some(*one),
            _ => None,
        };
        let files = self
            .cf()?
            .mod_files(mod_id, Some(&self.instance.mc_version), filter)
            .await?
            .into_iter()
            .filter(|f| loaders.is_empty() || curseforge::supports_loader(f, &loaders))
            .collect();
        Ok(curseforge::pick_best(files))
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
                // The SlopClient mod is managed by the launcher, not listed as content.
                let managed = kind == ContentType::Mod && crate::client_mod::is_mod_file(&file_name);
                if !relevant || managed || tracked.contains(&(kind, file_name.clone())) {
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
                            && let Some(e) = current.iter().find(|e| e.project_id() == Some(pid))
                        {
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
        store::download(self.paths, tasks, self.concurrency, progress).await?;
        self.write_lock(&lock)?;
        self.warn_missing_shader_mod(&lock, &mut report);
        Ok(report)
    }

    /// Installs a CurseForge project (numeric id or slug) plus its required dependencies.
    /// Files the author keeps off third-party launchers end up in `report.blocked`.
    pub async fn install_curseforge(&self, query: &str, progress: &ProgressFn) -> Result<InstallReport> {
        let cf = self.cf()?;
        stage(progress, "Resolving project");
        let mut report = InstallReport::default();
        let current = self.list().await?;
        let installed: HashSet<u64> = current
            .iter()
            .filter_map(|e| match e.source {
                Source::CurseForge { project_id, .. } => Some(project_id),
                _ => None,
            })
            .collect();

        let project = cf.find_mod(query).await?;
        let kind = project.kind().ok_or_else(|| {
            Error::Other(format!(
                "{} is not a mod, resource pack or shader; modpacks are imported as new instances",
                project.name
            ))
        })?;
        if installed.contains(&project.id) {
            report.skipped.push(project.name);
            return Ok(report);
        }
        let file = self.best_cf_file(project.id, kind).await?.ok_or_else(|| {
            Error::Other(format!(
                "{} has no file for Minecraft {} ({})",
                project.name, self.instance.mc_version, self.instance.loader
            ))
        })?;

        let mut visited = installed.clone();
        visited.insert(project.id);
        let mut queue = vec![(project, file, kind, false)];
        let mut plan = Vec::new();

        stage(progress, "Resolving dependencies");
        while let Some((proj, file, kind, is_dep)) = queue.pop() {
            for dep in &file.dependencies {
                match dep.relation_type {
                    curseforge::RELATION_REQUIRED => {
                        if !visited.insert(dep.mod_id) {
                            continue;
                        }
                        let dep_project = cf.get_mod(dep.mod_id).await?;
                        let dep_kind = dep_project.kind().unwrap_or(ContentType::Mod);
                        match self.best_cf_file(dep.mod_id, dep_kind).await? {
                            Some(f) => queue.push((dep_project, f, dep_kind, true)),
                            None => report.warnings.push(format!(
                                "required dependency {} of {} is not available for this instance",
                                dep_project.name, proj.name
                            )),
                        }
                    }
                    curseforge::RELATION_INCOMPATIBLE => {
                        if let Some(e) = current.iter().find(|e| {
                            matches!(e.source, Source::CurseForge { project_id, .. } if project_id == dep.mod_id)
                        }) {
                            report.warnings.push(format!(
                                "{} is incompatible with the installed {}",
                                proj.name, e.name
                            ));
                        }
                    }
                    _ => {}
                }
            }
            plan.push((proj, file, kind, is_dep));
        }

        let mut tasks = Vec::new();
        let mut lock = self.read_lock()?;
        for (proj, file, kind, is_dep) in plan {
            let blocked = BlockedFile {
                kind,
                name: proj.name.clone(),
                file_name: file.file_name.clone(),
                project_id: proj.id,
                file_id: file.id,
                slug: Some(proj.slug.clone()),
                page_url: format!("{}/files/{}", proj.website_url(), file.id),
                sha1: file.sha1(),
                size: file.file_length,
                dependency: is_dep,
                icon_url: proj.icon_url(),
            };
            let Some(url) = file.url() else {
                report.blocked.push(blocked);
                continue;
            };
            tasks.push(
                DownloadTask::new(url, self.file_path(kind, &file.file_name, true))
                    .sha1(file.sha1())
                    .size(Some(file.file_length)),
            );
            let mut entry = blocked.entry();
            entry.version = Some(file.display_name.clone());
            lock.entries.retain(|e| !(e.kind == kind && e.file_name == entry.file_name));
            lock.entries.push(entry.clone());
            report.installed.push(entry);
        }

        stage(progress, "Downloading");
        store::download(self.paths, tasks, self.concurrency, progress).await?;
        self.write_lock(&lock)?;
        self.add_blocked(&report.blocked)?;
        self.warn_missing_shader_mod(&lock, &mut report);
        Ok(report)
    }

    fn warn_missing_shader_mod(&self, lock: &ContentLock, report: &mut InstallReport) {
        if !report.installed.iter().any(|e| e.kind == ContentType::Shader) {
            return;
        }
        let has_shader_mod = lock.entries.iter().any(|e| match &e.source {
            Source::Modrinth { slug: Some(s), .. } | Source::CurseForge { slug: Some(s), .. } => {
                s.starts_with("iris") || s.starts_with("oculus")
            }
            _ => false,
        });
        if !has_shader_mod {
            report
                .warnings
                .push("shader packs need a shader mod: add `iris` to this instance".into());
        }
    }

    /// Looks for manually downloaded files in `dirs` (default: the Downloads folder)
    /// and moves matches into the instance. Returns the files that are still missing.
    pub async fn resolve_blocked(&self, dirs: &[PathBuf]) -> Result<(usize, Vec<BlockedFile>)> {
        let blocked = self.blocked()?;
        if blocked.is_empty() {
            return Ok((0, blocked));
        }
        let default_dirs: Vec<PathBuf> = dirs::download_dir().into_iter().collect();
        let dirs = if dirs.is_empty() { &default_dirs } else { dirs };

        let mut lock = self.read_lock()?;
        let mut remaining = Vec::new();
        let mut resolved = 0;
        for item in blocked {
            let mut found = None;
            for dir in dirs {
                let candidate = dir.join(&item.file_name);
                if candidate.is_file() && self.matches_blocked(&candidate, &item).await? {
                    found = Some(candidate);
                    break;
                }
            }
            let Some(source) = found else {
                remaining.push(item);
                continue;
            };
            let target = self.file_path(item.kind, &item.file_name, true);
            std::fs::create_dir_all(self.folder(item.kind))?;
            let mut entry = item.entry();
            entry.sha1 = if store::enabled(self.paths) {
                store::import(self.paths, &source, &target).await?
            } else {
                std::fs::copy(&source, &target)?;
                sha1_file(&target).await?
            };
            lock.entries.retain(|e| !(e.kind == item.kind && e.file_name == item.file_name));
            lock.entries.push(entry);
            resolved += 1;
        }
        self.write_lock(&lock)?;
        self.write_blocked(&remaining)?;
        Ok((resolved, remaining))
    }

    async fn matches_blocked(&self, path: &Path, item: &BlockedFile) -> Result<bool> {
        if std::fs::metadata(path)?.len() != item.size {
            return Ok(false);
        }
        Ok(match &item.sha1 {
            Some(expected) => sha1_file(path).await?.eq_ignore_ascii_case(expected),
            None => true,
        })
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

    /// Newer compatible versions for tracked Modrinth and CurseForge content.
    /// CurseForge is skipped without an API key.
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
                    && &new.id != version_id
                    && new.primary_file().is_some()
                {
                    updates.push(UpdateInfo {
                        entry: entry.clone(),
                        new_version: new.into(),
                    });
                }
            }
        }

        if self.curseforge.is_some() {
            for entry in &entries {
                let Source::CurseForge { project_id, file_id, .. } = entry.source else {
                    continue;
                };
                // File ids grow over time, so a higher id is a newer upload.
                if let Some(new) = self.best_cf_file(project_id, entry.kind).await?
                    && new.id > file_id
                    && new.url().is_some()
                {
                    updates.push(UpdateInfo {
                        entry: entry.clone(),
                        new_version: (&new).into(),
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
            let file = &u.new_version.file;
            let url = file
                .url
                .as_deref()
                .ok_or_else(|| Error::Other(format!("{} can only be downloaded on the website", u.entry.name)))?;
            tasks.push(
                DownloadTask::new(url, self.file_path(u.entry.kind, &file.filename, u.entry.enabled))
                    .sha1(file.sha1.clone())
                    .size(Some(file.size)),
            );
        }
        stage(progress, "Downloading updates");
        store::download(self.paths, tasks, self.concurrency, progress).await?;

        let mut lock = self.read_lock()?;
        for u in updates {
            let file = &u.new_version.file;
            let new_path = self.file_path(u.entry.kind, &file.filename, u.entry.enabled);
            if file.filename != u.entry.file_name {
                let old = self.file_path(u.entry.kind, &u.entry.file_name, u.entry.enabled);
                if old.exists() {
                    std::fs::remove_file(old)?;
                }
            }
            let sha1 = match &file.sha1 {
                Some(s) => s.clone(),
                None => sha1_file(&new_path).await?,
            };
            if let Some(e) = lock
                .entries
                .iter_mut()
                .find(|e| e.kind == u.entry.kind && e.file_name == u.entry.file_name)
            {
                e.file_name = file.filename.clone();
                e.sha1 = sha1;
                e.version = Some(u.new_version.version_number.clone());
                match &mut e.source {
                    Source::Modrinth { version_id, .. } => *version_id = u.new_version.id.clone(),
                    Source::CurseForge { file_id, .. } => {
                        *file_id = u.new_version.id.parse().unwrap_or(*file_id)
                    }
                    Source::Local => {}
                }
            }
        }
        self.write_lock(&lock)
    }

    /// Installs several Modrinth projects. A project without a version for this
    /// instance does not stop the others; it ends up in `warnings`.
    pub async fn install_many(&self, projects: &[&str], progress: &ProgressFn) -> Result<InstallReport> {
        let mut report = InstallReport::default();
        for project in projects {
            match self.install(project, progress).await {
                Ok(r) => {
                    report.installed.extend(r.installed);
                    report.skipped.extend(r.skipped);
                    report.warnings.extend(r.warnings);
                    report.blocked.extend(r.blocked);
                }
                Err(e) => report.warnings.push(format!("{project}: {e}")),
            }
        }
        Ok(report)
    }

    /// Adds a file the user dropped or picked. The kind comes from the file itself:
    /// `.jar` is a mod, a zip with `shaders/` a shader pack, one with `pack.mcmeta`
    /// a resource pack. The file is then looked up online so it can be updated.
    pub async fn add_local(&self, file_name: &str, bytes: &[u8]) -> Result<ContentType> {
        let name = Path::new(file_name)
            .file_name()
            .and_then(|n| n.to_str())
            .filter(|n| !n.starts_with('.'))
            .ok_or_else(|| Error::Other(format!("ungültiger Dateiname: {file_name}")))?;
        let kind = detect_kind(name, bytes)?;
        if kind == ContentType::Mod && self.instance.loader.kind().is_none() {
            return Err(Error::Other("Vanilla-Instanzen laden keine Mods".into()));
        }
        let target = self.file_path(kind, name, true);
        if target.exists() || self.file_path(kind, name, false).exists() {
            return Err(Error::Other(format!("{name} ist schon installiert")));
        }
        std::fs::create_dir_all(self.folder(kind))?;
        if store::enabled(self.paths) {
            store::write(self.paths, bytes, &target)?;
        } else {
            std::fs::write(&target, bytes)?;
        }
        // Recognizing it is a bonus; offline the file simply stays local.
        let _ = self.identify().await;
        Ok(kind)
    }

    /// Looks up untracked files by hash, first on Modrinth, then (with a key) on
    /// CurseForge, so they can be updated too. Returns how many were recognized.
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
        let mut unknown = Vec::new();
        for mut entry in local {
            let Some(version) = found.get(&entry.sha1) else {
                unknown.push(entry);
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

        if let Some(cf) = &self.curseforge
            && !unknown.is_empty()
        {
            let mut by_print = HashMap::new();
            for entry in unknown {
                let path = self.file_path(entry.kind, &entry.file_name, entry.enabled);
                by_print.insert(curseforge::fingerprint_file(&path).await?, entry);
            }
            let prints: Vec<u32> = by_print.keys().copied().collect();
            let files = cf.files_by_fingerprint(&prints).await?;
            let mod_ids: Vec<u64> = files
                .values()
                .map(|f| f.mod_id)
                .collect::<HashSet<_>>()
                .into_iter()
                .collect();
            let mods: HashMap<u64, curseforge::Mod> =
                cf.mods(&mod_ids).await?.into_iter().map(|m| (m.id, m)).collect();
            for (print, file) in files {
                let Some(mut entry) = by_print.remove(&print) else {
                    continue;
                };
                let project = mods.get(&file.mod_id);
                entry.name = project.map_or(entry.name, |p| p.name.clone());
                entry.icon_url = project.and_then(|p| p.icon_url());
                entry.version = Some(file.display_name.clone());
                entry.source = Source::CurseForge {
                    project_id: file.mod_id,
                    file_id: file.id,
                    slug: project.map(|p| p.slug.clone()),
                };
                lock.entries.push(entry);
                count += 1;
            }
        }
        self.write_lock(&lock)?;
        Ok(count)
    }
}
