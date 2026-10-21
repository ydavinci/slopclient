//! Installs a version: version JSON, client jar, libraries, natives, assets.

mod loaders;

use std::{
    collections::HashSet,
    future::Future,
    path::{Path, PathBuf},
    pin::Pin,
};

use crate::{
    Error, Paths, ProgressFn, Result,
    download::{DownloadTask, download_all},
    meta::{
        MavenCoord, VersionInfo, VersionManifest,
        mojang::{self, AssetIndex, Library},
    },
    paths::join_rel,
    progress::{noop, stage},
    rules::{self, Features},
};

/// Everything the launch step needs, with all files present on disk.
#[derive(Debug, Clone)]
pub struct ResolvedVersion {
    pub info: VersionInfo,
    pub client_jar: PathBuf,
    /// Libraries in classpath order (without the client jar).
    pub libraries: Vec<PathBuf>,
    pub natives_dir: PathBuf,
    pub assets_root: PathBuf,
    pub asset_index_id: String,
    /// `${game_assets}`: the virtual asset dir for very old versions, else `assets_root`.
    pub game_assets: PathBuf,
    /// Pre-1.6 versions read assets from `<game dir>/resources`.
    pub map_to_resources: bool,
    pub logging_arg: Option<String>,
}

pub struct Installer<'a> {
    pub paths: &'a Paths,
    pub concurrency: usize,
    pub progress: ProgressFn,
}

impl<'a> Installer<'a> {
    pub fn new(paths: &'a Paths, concurrency: usize, progress: ProgressFn) -> Self {
        Self {
            paths,
            concurrency,
            progress,
        }
    }

    pub async fn install(&self, id: &str) -> Result<ResolvedVersion> {
        self.paths.ensure()?;
        stage(&self.progress, "Fetching version metadata");
        let manifest = mojang::fetch_manifest(self.paths).await.ok();
        let info = self.load_version(manifest.as_ref(), id).await?;
        let features = Features::new();

        let jar_id = info.jar_id().to_owned();
        let client_jar = self.paths.version_jar(&jar_id);
        let mut tasks = Vec::new();
        if let Some(client) = info.downloads.as_ref().and_then(|d| d.client.as_ref()) {
            tasks.push(
                DownloadTask::new(&client.url, &client_jar)
                    .sha1(client.sha1.clone())
                    .size(client.size),
            );
        } else if !client_jar.exists() {
            return Err(Error::Other(format!("no client jar for {id}")));
        }

        let libs = plan_libraries(self.paths, &info.libraries, &features);
        tasks.extend(libs.tasks);

        let mut logging_arg = None;
        if let Some(entry) = info.logging.as_ref().and_then(|l| l.client.as_ref()) {
            let path = self.paths.assets().join("log_configs").join(&entry.file.id);
            tasks.push(
                DownloadTask::new(&entry.file.url, &path)
                    .sha1(Some(entry.file.sha1.clone()))
                    .size(Some(entry.file.size)),
            );
            logging_arg = Some(entry.argument.replace("${path}", &path.to_string_lossy()));
        }

        stage(&self.progress, "Downloading game files");
        download_all(tasks, self.concurrency, &self.progress).await?;

        let natives_dir = self.paths.version_dir(&info.id).join("natives");
        if !libs.natives.is_empty() {
            stage(&self.progress, "Extracting natives");
            let dir = natives_dir.clone();
            tokio::task::spawn_blocking(move || extract_natives(&libs.natives, &dir)).await??;
        }

        let assets = self.install_assets(&info).await?;

        Ok(ResolvedVersion {
            client_jar,
            libraries: libs.classpath,
            natives_dir,
            assets_root: self.paths.assets(),
            asset_index_id: assets.index_id,
            game_assets: assets.game_assets,
            map_to_resources: assets.map_to_resources,
            logging_arg,
            info,
        })
    }

    /// Loads a version JSON (downloading it if Mojang knows it) and resolves `inheritsFrom`.
    fn load_version<'b>(
        &'b self,
        manifest: Option<&'b VersionManifest>,
        id: &'b str,
    ) -> Pin<Box<dyn Future<Output = Result<VersionInfo>> + Send + 'b>> {
        Box::pin(async move {
            let path = self.paths.version_json(id);
            if let Some(entry) = manifest.and_then(|m| m.find(id)) {
                let task = DownloadTask::new(&entry.url, &path).sha1(Some(entry.sha1.clone()));
                download_all(vec![task], 1, &noop()).await?;
            }
            let text = match tokio::fs::read_to_string(&path).await {
                Ok(text) => text,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                    return Err(Error::VersionNotFound(id.to_owned()));
                }
                Err(e) => return Err(e.into()),
            };
            let info: VersionInfo = serde_json::from_str(&text)?;
            match info.inherits_from.clone() {
                Some(parent_id) => {
                    let parent = self.load_version(manifest, &parent_id).await?;
                    Ok(VersionInfo::merge(parent, info))
                }
                None => Ok(info),
            }
        })
    }

    async fn install_assets(&self, info: &VersionInfo) -> Result<AssetsResult> {
        let assets_root = self.paths.assets();
        let Some(index_ref) = &info.asset_index else {
            let id = info.assets.clone().unwrap_or_else(|| "legacy".into());
            return Ok(AssetsResult {
                index_id: id,
                game_assets: assets_root,
                map_to_resources: false,
            });
        };

        stage(&self.progress, "Downloading assets");
        let index_path = assets_root.join("indexes").join(format!("{}.json", index_ref.id));
        let task = DownloadTask::new(&index_ref.url, &index_path)
            .sha1(Some(index_ref.sha1.clone()))
            .size(Some(index_ref.size));
        download_all(vec![task], 1, &noop()).await?;
        let index: AssetIndex = serde_json::from_str(&tokio::fs::read_to_string(&index_path).await?)?;

        let objects = assets_root.join("objects");
        let tasks = index
            .objects
            .values()
            .map(|obj| {
                let prefix = &obj.hash[..2];
                DownloadTask::new(
                    format!("{}/{prefix}/{}", mojang::RESOURCES_URL, obj.hash),
                    objects.join(prefix).join(&obj.hash),
                )
                .sha1(Some(obj.hash.clone()))
                .size(Some(obj.size))
            })
            .collect();
        download_all(tasks, self.concurrency, &self.progress).await?;

        let mut game_assets = assets_root.clone();
        if index.is_virtual {
            game_assets = assets_root.join("virtual").join(&index_ref.id);
            let (index, objects, target) = (index.clone(), objects.clone(), game_assets.clone());
            tokio::task::spawn_blocking(move || copy_assets(&index, &objects, &target)).await??;
        }

        Ok(AssetsResult {
            index_id: index_ref.id.clone(),
            game_assets,
            map_to_resources: index.map_to_resources,
        })
    }
}

struct AssetsResult {
    index_id: String,
    game_assets: PathBuf,
    map_to_resources: bool,
}

/// Copies hashed asset objects to their readable names (virtual / resources layout).
pub fn copy_assets(index: &AssetIndex, objects: &Path, target: &Path) -> Result<()> {
    for (name, obj) in &index.objects {
        let dest = target.join(name);
        if dest.metadata().is_ok_and(|m| m.len() == obj.size) {
            continue;
        }
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::copy(objects.join(&obj.hash[..2]).join(&obj.hash), dest)?;
    }
    Ok(())
}

/// Loads an asset index that was installed earlier.
pub fn read_asset_index(paths: &Paths, id: &str) -> Result<AssetIndex> {
    let path = paths.assets().join("indexes").join(format!("{id}.json"));
    Ok(serde_json::from_str(&std::fs::read_to_string(path)?)?)
}

struct LibraryPlan {
    tasks: Vec<DownloadTask>,
    classpath: Vec<PathBuf>,
    /// Native jars to extract, with their exclude prefixes.
    natives: Vec<(PathBuf, Vec<String>)>,
}

fn plan_libraries(paths: &Paths, libraries: &[Library], features: &Features) -> LibraryPlan {
    let mut plan = LibraryPlan {
        tasks: Vec::new(),
        classpath: Vec::new(),
        natives: Vec::new(),
    };
    let mut seen = HashSet::new();

    for lib in libraries {
        if lib.rules.as_ref().is_some_and(|r| !rules::allowed(r, features)) {
            continue;
        }
        let Some(coord) = MavenCoord::parse(&lib.name) else {
            continue;
        };
        if coord.classifier.as_deref().is_some_and(rules::is_foreign_native) {
            continue;
        }
        if !seen.insert(coord.key()) {
            continue;
        }

        if let Some((task, path)) = main_artifact(paths, lib, &coord) {
            plan.tasks.extend(task);
            plan.classpath.push(path);
        }

        // Legacy natives (LWJGL 2 era): a classifier jar that is extracted, not put on the classpath.
        let classifier = lib
            .natives
            .as_ref()
            .and_then(|n| n.get(rules::os_name()))
            .map(|c| c.replace("${arch}", rules::arch_bits()));
        if let Some(classifier) = classifier {
            let artifact = lib
                .downloads
                .as_ref()
                .and_then(|d| d.classifiers.as_ref())
                .and_then(|c| c.get(&classifier));
            if let Some(artifact) = artifact {
                let rel = artifact
                    .path
                    .clone()
                    .unwrap_or_else(|| coord.with_classifier(&classifier).path());
                let path = join_rel(&paths.libraries(), &rel);
                plan.tasks.push(
                    DownloadTask::new(&artifact.url, &path)
                        .sha1(artifact.sha1.clone())
                        .size(artifact.size),
                );
                let exclude = lib.extract.clone().unwrap_or_default().exclude;
                plan.natives.push((path, exclude));
            }
        }
    }
    plan
}

/// The library's classpath jar and how to download it (None if it can't be downloaded).
fn main_artifact(
    paths: &Paths,
    lib: &Library,
    coord: &MavenCoord,
) -> Option<(Option<DownloadTask>, PathBuf)> {
    match &lib.downloads {
        Some(downloads) => {
            let artifact = downloads.artifact.as_ref()?;
            let rel = artifact.path.clone().unwrap_or_else(|| coord.path());
            let path = join_rel(&paths.libraries(), &rel);
            let task = (!artifact.url.is_empty()).then(|| {
                DownloadTask::new(&artifact.url, &path)
                    .sha1(artifact.sha1.clone())
                    .size(artifact.size)
            });
            Some((task, path))
        }
        None if lib.natives.is_some() => None,
        None => {
            let mut base = lib.url.clone().unwrap_or_else(|| mojang::LIBRARIES_URL.into());
            if !base.ends_with('/') {
                base.push('/');
            }
            let rel = coord.path();
            let path = join_rel(&paths.libraries(), &rel);
            let task = DownloadTask::new(format!("{base}{rel}"), &path)
                .sha1(lib.sha1.clone())
                .size(lib.size);
            Some((Some(task), path))
        }
    }
}

fn extract_natives(jars: &[(PathBuf, Vec<String>)], dir: &Path) -> Result<()> {
    std::fs::create_dir_all(dir)?;
    for (jar, exclude) in jars {
        let mut zip = zip::ZipArchive::new(std::fs::File::open(jar)?)?;
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i)?;
            let name = entry.name().to_owned();
            if entry.is_dir()
                || name.starts_with("META-INF/")
                || exclude.iter().any(|e| name.starts_with(e.as_str()))
            {
                continue;
            }
            let Some(rel) = entry.enclosed_name() else {
                continue;
            };
            let out = dir.join(rel);
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut file = std::fs::File::create(out)?;
            std::io::copy(&mut entry, &mut file)?;
        }
    }
    Ok(())
}
