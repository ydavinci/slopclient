//! Modrinth modpacks (`.mrpack`): import into a new instance and export an instance.
//!
//! Format: a zip with `modrinth.index.json` (files to download + loader versions)
//! and `overrides/` / `client-overrides/` folders copied into the game directory.

use std::{
    collections::HashMap,
    io::Write,
    path::{Component, Path, PathBuf},
};

use serde::{Deserialize, Serialize};

use super::{ContentManager, ContentType, Source, modrinth};
use crate::{
    Error, Paths, ProgressFn, Result,
    download::{DownloadTask, download_all},
    instance::{Instance, Loader, ModpackInfo},
    meta::LoaderKind,
    progress::stage,
};

const INDEX: &str = "modrinth.index.json";

/// Hosts the format allows downloads from.
const ALLOWED_HOSTS: [&str; 4] = [
    "cdn.modrinth.com",
    "github.com",
    "raw.githubusercontent.com",
    "gitlab.com",
];

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackIndex {
    pub format_version: u32,
    pub game: String,
    pub version_id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
    pub files: Vec<PackFile>,
    pub dependencies: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackFile {
    pub path: String,
    pub hashes: HashMap<String, String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub env: Option<PackEnv>,
    pub downloads: Vec<String>,
    pub file_size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PackEnv {
    pub client: String,
    pub server: String,
}

/// Where a pack to import comes from.
pub enum PackSource<'a> {
    File(&'a Path),
    /// Modrinth modpack project (slug or id); the newest release is used.
    Modrinth(&'a str),
}

/// Rejects absolute paths and `..` so a pack can't write outside the game directory.
fn safe_relative(path: &str) -> Option<PathBuf> {
    let p = Path::new(path);
    let safe = !path.is_empty()
        && p.components().all(|c| matches!(c, Component::Normal(_)));
    safe.then(|| crate::paths::join_rel(Path::new(""), &path.replace('\\', "/")))
}

fn allowed_url(url: &str) -> bool {
    let Some(rest) = url.strip_prefix("https://") else {
        return false;
    };
    let host = rest.split('/').next().unwrap_or("");
    ALLOWED_HOSTS.contains(&host)
}

fn loader_from_deps(deps: &HashMap<String, String>) -> Loader {
    for (key, kind) in [
        ("fabric-loader", LoaderKind::Fabric),
        ("quilt-loader", LoaderKind::Quilt),
        ("neoforge", LoaderKind::NeoForge),
        ("forge", LoaderKind::Forge),
    ] {
        if let Some(version) = deps.get(key) {
            return Loader::new(kind, version.clone());
        }
    }
    Loader::Vanilla
}

fn loader_dep_key(kind: LoaderKind) -> &'static str {
    match kind {
        LoaderKind::Fabric => "fabric-loader",
        LoaderKind::Quilt => "quilt-loader",
        LoaderKind::NeoForge => "neoforge",
        LoaderKind::Forge => "forge",
    }
}

/// Creates a new instance from a modpack. `name` overrides the pack's name.
pub async fn import(
    paths: &Paths,
    source: PackSource<'_>,
    name: Option<&str>,
    concurrency: usize,
    progress: &ProgressFn,
) -> Result<Instance> {
    let (pack_path, origin) = match source {
        PackSource::File(path) => (path.to_path_buf(), None),
        PackSource::Modrinth(query) => {
            stage(progress, "Resolving modpack");
            let project = modrinth::project(query).await?;
            if project.project_type != "modpack" {
                return Err(Error::Other(format!("{} is not a modpack", project.title)));
            }
            let version = modrinth::pick_best(modrinth::project_versions(&project.id, &[], None).await?)
                .ok_or_else(|| Error::Other(format!("{} has no versions", project.title)))?;
            let file = version
                .primary_file()
                .ok_or_else(|| Error::Other("modpack version has no file".into()))?;
            let path = paths.cache().join("modpacks").join(&file.filename);
            stage(progress, "Downloading modpack");
            download_all(
                vec![
                    DownloadTask::new(&file.url, &path)
                        .sha1(Some(file.hashes.sha1.clone()))
                        .size(Some(file.size)),
                ],
                1,
                progress,
            )
            .await?;
            let origin = ModpackInfo {
                project_id: Some(project.id),
                version_id: Some(version.id),
                name: project.title,
                version: version.version_number,
            };
            (path, Some(origin))
        }
    };

    let index: PackIndex = {
        let path = pack_path.clone();
        tokio::task::spawn_blocking(move || -> Result<PackIndex> {
            let mut zip = zip::ZipArchive::new(std::fs::File::open(&path)?)?;
            Ok(serde_json::from_reader(zip.by_name(INDEX)?)?)
        })
        .await??
    };
    if index.game != "minecraft" {
        return Err(Error::Other(format!("unsupported game: {}", index.game)));
    }
    let mc = index
        .dependencies
        .get("minecraft")
        .ok_or_else(|| Error::Other("modpack does not specify a Minecraft version".into()))?;

    let mut instance = Instance::create(
        paths,
        name.unwrap_or(&index.name),
        mc,
        loader_from_deps(&index.dependencies),
    )?;
    instance.modpack = Some(origin.unwrap_or_else(|| ModpackInfo {
        project_id: None,
        version_id: None,
        name: index.name.clone(),
        version: index.version_id.clone(),
    }));
    instance.save(paths)?;

    let result = install_files(paths, &instance, &index, &pack_path, concurrency, progress).await;
    if let Err(e) = result {
        // Don't leave a half-installed instance behind.
        let _ = Instance::delete(paths, &instance.id);
        return Err(e);
    }

    stage(progress, "Linking files to Modrinth");
    ContentManager::new(paths, instance.clone(), concurrency)
        .identify()
        .await?;
    Ok(instance)
}

async fn install_files(
    paths: &Paths,
    instance: &Instance,
    index: &PackIndex,
    pack_path: &Path,
    concurrency: usize,
    progress: &ProgressFn,
) -> Result<()> {
    let game_dir = instance.game_dir(paths);
    let mut tasks = Vec::new();
    for file in &index.files {
        if file.env.as_ref().is_some_and(|e| e.client == "unsupported") {
            continue;
        }
        let rel = safe_relative(&file.path)
            .ok_or_else(|| Error::Other(format!("unsafe path in modpack: {}", file.path)))?;
        let url = file
            .downloads
            .iter()
            .find(|u| allowed_url(u))
            .ok_or_else(|| Error::Other(format!("no allowed download for {}", file.path)))?;
        tasks.push(
            DownloadTask::new(url, game_dir.join(rel))
                .sha1(file.hashes.get("sha1").cloned())
                .size(Some(file.file_size)),
        );
    }
    stage(progress, "Downloading modpack files");
    download_all(tasks, concurrency, progress).await?;

    stage(progress, "Applying overrides");
    let (pack, dir) = (pack_path.to_path_buf(), game_dir.clone());
    tokio::task::spawn_blocking(move || extract_overrides(&pack, &dir)).await??;
    Ok(())
}

/// Copies `overrides/` then `client-overrides/` (which wins) into the game directory.
fn extract_overrides(pack: &Path, game_dir: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(pack)?)?;
    for prefix in ["overrides/", "client-overrides/"] {
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i)?;
            let Some(rel) = entry.name().strip_prefix(prefix).map(str::to_owned) else {
                continue;
            };
            if entry.is_dir() || rel.is_empty() {
                continue;
            }
            let rel = safe_relative(&rel)
                .ok_or_else(|| Error::Other(format!("unsafe path in modpack: {rel}")))?;
            let out = game_dir.join(rel);
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::io::copy(&mut entry, &mut std::fs::File::create(out)?)?;
        }
    }
    Ok(())
}

/// What to put into an exported pack besides the mod list.
#[derive(Debug, Clone)]
pub struct ExportOptions {
    pub name: String,
    pub version: String,
    pub summary: Option<String>,
    /// Extra files/folders of the game directory to bundle, e.g. `config`, `options.txt`.
    pub include: Vec<String>,
}

/// Writes the instance as `.mrpack`. Modrinth content is referenced by URL;
/// local files and `include` paths are bundled under `overrides/`.
/// Returns the number of referenced and bundled files.
pub async fn export(
    paths: &Paths,
    instance: &Instance,
    options: &ExportOptions,
    out: &Path,
    concurrency: usize,
) -> Result<(usize, usize)> {
    let manager = ContentManager::new(paths, instance.clone(), concurrency);
    let entries: Vec<_> = manager.list().await?.into_iter().filter(|e| e.enabled).collect();

    let version_ids: Vec<String> = entries
        .iter()
        .filter_map(|e| match &e.source {
            Source::Modrinth { version_id, .. } => Some(version_id.clone()),
            Source::Local => None,
        })
        .collect();
    let versions: HashMap<String, modrinth::Version> = modrinth::versions(&version_ids)
        .await?
        .into_iter()
        .map(|v| (v.id.clone(), v))
        .collect();

    let game_dir = instance.game_dir(paths);
    let mut files = Vec::new();
    let mut bundled: Vec<(String, PathBuf)> = Vec::new();
    for entry in &entries {
        let rel = format!("{}/{}", entry.kind.folder(), entry.file_name);
        let remote = match &entry.source {
            Source::Modrinth { version_id, .. } => versions
                .get(version_id)
                .and_then(|v| v.files.iter().find(|f| f.hashes.sha1 == entry.sha1)),
            Source::Local => None,
        };
        match remote {
            Some(file) => {
                let mut hashes = HashMap::from([("sha1".to_owned(), file.hashes.sha1.clone())]);
                if let Some(sha512) = &file.hashes.sha512 {
                    hashes.insert("sha512".into(), sha512.clone());
                }
                // Shaders and resource packs are client-only.
                let server = if entry.kind == ContentType::Mod { "required" } else { "unsupported" };
                files.push(PackFile {
                    path: rel,
                    hashes,
                    env: Some(PackEnv {
                        client: "required".into(),
                        server: server.into(),
                    }),
                    downloads: vec![file.url.clone()],
                    file_size: file.size,
                });
            }
            None => bundled.push((rel, game_dir.join(entry.kind.folder()).join(&entry.file_name))),
        }
    }
    for item in &options.include {
        let rel = safe_relative(item)
            .ok_or_else(|| Error::Other(format!("invalid include path: {item}")))?;
        let path = game_dir.join(&rel);
        if path.exists() {
            bundled.push((item.replace('\\', "/").trim_end_matches('/').to_owned(), path));
        }
    }

    let mut dependencies = HashMap::from([("minecraft".to_owned(), instance.mc_version.clone())]);
    if let (Some(kind), Some(version)) = (instance.loader.kind(), instance.loader.version()) {
        dependencies.insert(loader_dep_key(kind).into(), version.to_owned());
    }
    let index = PackIndex {
        format_version: 1,
        game: "minecraft".into(),
        version_id: options.version.clone(),
        name: options.name.clone(),
        summary: options.summary.clone(),
        files,
        dependencies,
    };

    let referenced = index.files.len();
    let out = out.to_path_buf();
    let bundled_count = tokio::task::spawn_blocking(move || write_pack(&out, &index, &bundled)).await??;
    Ok((referenced, bundled_count))
}

fn write_pack(out: &Path, index: &PackIndex, bundled: &[(String, PathBuf)]) -> Result<usize> {
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    let mut zip = zip::ZipWriter::new(std::fs::File::create(out)?);
    zip.start_file(INDEX, options)?;
    zip.write_all(&serde_json::to_vec_pretty(index)?)?;

    let mut count = 0;
    for (rel, path) in bundled {
        let mut stack = vec![(rel.clone(), path.clone())];
        while let Some((rel, path)) = stack.pop() {
            if path.is_dir() {
                for child in std::fs::read_dir(&path)?.flatten() {
                    let name = child.file_name().to_string_lossy().into_owned();
                    stack.push((format!("{rel}/{name}"), child.path()));
                }
            } else {
                zip.start_file(format!("overrides/{rel}"), options)?;
                std::io::copy(&mut std::fs::File::open(&path)?, &mut zip)?;
                count += 1;
            }
        }
    }
    zip.finish()?;
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unsafe_paths() {
        assert!(safe_relative("mods/a.jar").is_some());
        assert!(safe_relative("../evil.jar").is_none());
        assert!(safe_relative("mods/../../evil.jar").is_none());
        assert!(safe_relative("C:/Windows/evil.dll").is_none());
        assert!(safe_relative("/etc/passwd").is_none());
        assert!(safe_relative("").is_none());
    }

    #[test]
    fn only_allows_listed_hosts() {
        assert!(allowed_url("https://cdn.modrinth.com/data/x/y.jar"));
        assert!(!allowed_url("http://cdn.modrinth.com/data/x/y.jar"));
        assert!(!allowed_url("https://cdn.modrinth.com.evil.net/y.jar"));
        assert!(!allowed_url("https://example.com/y.jar"));
    }
}
