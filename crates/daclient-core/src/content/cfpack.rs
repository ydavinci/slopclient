//! CurseForge modpacks: a zip with `manifest.json` (project/file ids + loader)
//! and an `overrides/` folder. Files are resolved through the CurseForge API;
//! files with third-party downloads disabled are recorded in `blocked.json`.

use std::path::Path;

use serde::Deserialize;

use super::{
    BlockedFile, ContentEntry, ContentLock, ContentManager, ContentType, Source,
    curseforge::{self, CurseForge},
};
use crate::{
    Error, Paths, ProgressFn, Result,
    download::{DownloadTask, download_all},
    instance::{Instance, Loader, ModpackInfo},
    meta::LoaderKind,
    progress::stage,
};

pub const MANIFEST: &str = "manifest.json";

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub minecraft: ManifestMinecraft,
    #[serde(default)]
    pub manifest_type: String,
    pub name: String,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub files: Vec<ManifestFile>,
    #[serde(default = "default_overrides")]
    pub overrides: String,
}

fn default_overrides() -> String {
    "overrides".into()
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestMinecraft {
    pub version: String,
    #[serde(default)]
    pub mod_loaders: Vec<ManifestLoader>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ManifestLoader {
    /// e.g. `forge-47.2.0`, `neoforge-21.1.77`, `fabric-0.16.9`.
    pub id: String,
    #[serde(default)]
    pub primary: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestFile {
    #[serde(rename = "projectID")]
    pub project_id: u64,
    #[serde(rename = "fileID")]
    pub file_id: u64,
    #[serde(default = "yes")]
    pub required: bool,
}

fn yes() -> bool {
    true
}

/// `forge-47.2.0` → Forge 47.2.0. Old NeoForge packs write `neoforge-1.20.1-47.1.99`.
pub fn parse_loader(loaders: &[ManifestLoader]) -> Result<Loader> {
    let Some(loader) = loaders.iter().find(|l| l.primary).or(loaders.first()) else {
        return Ok(Loader::Vanilla);
    };
    let (name, version) = loader
        .id
        .split_once('-')
        .ok_or_else(|| Error::Other(format!("unknown mod loader in modpack: {}", loader.id)))?;
    let kind: LoaderKind = name.parse()?;
    let version = match kind {
        LoaderKind::NeoForge if version.starts_with("1.") => {
            version.split_once('-').map_or(version, |(_, v)| v)
        }
        _ => version,
    };
    Ok(Loader::new(kind, version.to_owned()))
}

pub fn read_manifest(pack: &Path) -> Result<Manifest> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(pack)?)?;
    let manifest: Manifest = serde_json::from_reader(zip.by_name(MANIFEST)?)?;
    if !manifest.manifest_type.is_empty() && manifest.manifest_type != "minecraftModpack" {
        return Err(Error::Other(format!("unsupported manifest type: {}", manifest.manifest_type)));
    }
    Ok(manifest)
}

/// Whether `pack` is a CurseForge pack (has `manifest.json`, no Modrinth index).
pub fn is_curseforge_pack(pack: &Path) -> Result<bool> {
    let zip = zip::ZipArchive::new(std::fs::File::open(pack)?)?;
    let names: Vec<&str> = zip.file_names().collect();
    Ok(names.contains(&MANIFEST) && !names.contains(&"modrinth.index.json"))
}

/// Downloads a CurseForge modpack project (id or slug), newest release.
pub async fn download_pack(
    paths: &Paths,
    cf: &CurseForge,
    query: &str,
    progress: &ProgressFn,
) -> Result<(std::path::PathBuf, ModpackInfo)> {
    stage(progress, "Resolving modpack");
    let project = cf.find_mod(query).await?;
    if project.class_id != Some(curseforge::CLASS_MODPACK) {
        return Err(Error::Other(format!("{} is not a modpack", project.name)));
    }
    let file = curseforge::pick_best(cf.mod_files(project.id, None, None).await?)
        .ok_or_else(|| Error::Other(format!("{} has no files", project.name)))?;
    let url = file.url().ok_or_else(|| {
        Error::Other(format!(
            "{} can only be downloaded on curseforge.com; import the zip file instead",
            project.name
        ))
    })?;
    let path = paths.cache().join("modpacks").join(&file.file_name);
    stage(progress, "Downloading modpack");
    download_all(
        vec![DownloadTask::new(url, &path).sha1(file.sha1()).size(Some(file.file_length))],
        1,
        progress,
    )
    .await?;
    let info = ModpackInfo {
        project_id: Some(project.id.to_string()),
        version_id: Some(file.id.to_string()),
        name: project.name,
        version: file.display_name,
    };
    Ok((path, info))
}

/// Creates a new instance from a CurseForge pack zip.
pub async fn import(
    paths: &Paths,
    cf: &CurseForge,
    pack: &Path,
    origin: Option<ModpackInfo>,
    name: Option<&str>,
    concurrency: usize,
    progress: &ProgressFn,
) -> Result<Instance> {
    let manifest = {
        let pack = pack.to_path_buf();
        tokio::task::spawn_blocking(move || read_manifest(&pack)).await??
    };
    let mut instance = Instance::create(
        paths,
        name.unwrap_or(&manifest.name),
        &manifest.minecraft.version,
        parse_loader(&manifest.minecraft.mod_loaders)?,
    )?;
    instance.modpack = Some(origin.unwrap_or_else(|| ModpackInfo {
        project_id: None,
        version_id: None,
        name: manifest.name.clone(),
        version: manifest.version.clone(),
    }));
    instance.save(paths)?;

    if let Err(e) = install_files(paths, cf, &instance, &manifest, pack, concurrency, progress).await {
        let _ = Instance::delete(paths, &instance.id);
        return Err(e);
    }
    Ok(instance)
}

async fn install_files(
    paths: &Paths,
    cf: &CurseForge,
    instance: &Instance,
    manifest: &Manifest,
    pack: &Path,
    concurrency: usize,
    progress: &ProgressFn,
) -> Result<()> {
    stage(progress, "Resolving modpack files");
    let wanted: Vec<&ManifestFile> = manifest.files.iter().filter(|f| f.required).collect();
    let file_ids: Vec<u64> = wanted.iter().map(|f| f.file_id).collect();
    let mod_ids: Vec<u64> = wanted.iter().map(|f| f.project_id).collect();
    let mut files = Vec::new();
    for chunk in file_ids.chunks(500) {
        files.extend(cf.files(chunk).await?);
    }
    let mut mods = std::collections::HashMap::new();
    for chunk in mod_ids.chunks(500) {
        mods.extend(cf.mods(chunk).await?.into_iter().map(|m| (m.id, m)));
    }

    let game_dir = instance.game_dir(paths);
    let mut tasks = Vec::new();
    let mut lock = ContentLock::default();
    let mut blocked = Vec::new();
    for file in &files {
        let project = mods.get(&file.mod_id);
        let kind = project.and_then(|m| m.kind()).unwrap_or(ContentType::Mod);
        if file.file_name.contains(['/', '\\']) || file.file_name.starts_with('.') {
            return Err(Error::Other(format!("unsafe file name in modpack: {}", file.file_name)));
        }
        let name = project.map_or_else(|| file.display_name.clone(), |m| m.name.clone());
        let slug = project.map(|m| m.slug.clone());
        match file.url() {
            Some(url) => {
                tasks.push(
                    DownloadTask::new(url, game_dir.join(kind.folder()).join(&file.file_name))
                        .sha1(file.sha1())
                        .size(Some(file.file_length)),
                );
                lock.entries.push(ContentEntry {
                    file_name: file.file_name.clone(),
                    kind,
                    name,
                    version: Some(file.display_name.clone()),
                    sha1: file.sha1().unwrap_or_default(),
                    source: Source::CurseForge {
                        project_id: file.mod_id,
                        file_id: file.id,
                        slug,
                    },
                    enabled: true,
                    dependency: false,
                    icon_url: project.and_then(|m| m.icon_url()),
                });
            }
            None => blocked.push(BlockedFile {
                kind,
                page_url: format!(
                    "{}/files/{}",
                    project.map_or_else(
                        || format!("https://www.curseforge.com/projects/{}", file.mod_id),
                        |m| m.website_url()
                    ),
                    file.id
                ),
                name,
                file_name: file.file_name.clone(),
                project_id: file.mod_id,
                file_id: file.id,
                slug,
                sha1: file.sha1(),
                size: file.file_length,
                dependency: false,
                icon_url: project.and_then(|m| m.icon_url()),
            }),
        }
    }
    if files.len() < wanted.len() {
        return Err(Error::Other(format!(
            "{} of {} modpack files are no longer available on CurseForge",
            wanted.len() - files.len(),
            wanted.len()
        )));
    }

    stage(progress, "Downloading modpack files");
    download_all(tasks, concurrency, progress).await?;

    stage(progress, "Applying overrides");
    let (pack, dir, prefix) = (pack.to_path_buf(), game_dir.clone(), format!("{}/", manifest.overrides));
    tokio::task::spawn_blocking(move || super::mrpack::extract_prefixes(&pack, &dir, &[&prefix])).await??;

    std::fs::write(
        instance.dir(paths).join("content.lock.json"),
        serde_json::to_string_pretty(&lock)?,
    )?;
    if !blocked.is_empty() {
        std::fs::write(instance.dir(paths).join("blocked.json"), serde_json::to_string_pretty(&blocked)?)?;
        // Files the user downloaded earlier are picked up right away.
        ContentManager::new(paths, instance.clone(), concurrency)
            .resolve_blocked(&[])
            .await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn loaders(id: &str) -> Vec<ManifestLoader> {
        vec![ManifestLoader { id: id.into(), primary: true }]
    }

    #[test]
    fn parses_loader_ids() {
        assert_eq!(parse_loader(&loaders("forge-47.2.0")).unwrap(), Loader::new(LoaderKind::Forge, "47.2.0".into()));
        assert_eq!(parse_loader(&loaders("fabric-0.16.9")).unwrap(), Loader::new(LoaderKind::Fabric, "0.16.9".into()));
        assert_eq!(parse_loader(&loaders("neoforge-21.1.77")).unwrap(), Loader::new(LoaderKind::NeoForge, "21.1.77".into()));
        assert_eq!(
            parse_loader(&loaders("neoforge-1.20.1-47.1.99")).unwrap(),
            Loader::new(LoaderKind::NeoForge, "47.1.99".into())
        );
        assert_eq!(parse_loader(&[]).unwrap(), Loader::Vanilla);
        assert!(parse_loader(&loaders("liteloader-1.12")).is_err());
    }

    #[test]
    fn parses_manifest() {
        let m: Manifest = serde_json::from_str(
            r#"{"minecraft":{"version":"1.21.1","modLoaders":[{"id":"neoforge-21.1.77","primary":true}]},
                "manifestType":"minecraftModpack","manifestVersion":1,"name":"Pack","version":"1.0","author":"x",
                "files":[{"projectID":238222,"fileID":5101366,"required":true}],"overrides":"overrides"}"#,
        )
        .unwrap();
        assert_eq!(m.files[0].project_id, 238222);
        assert_eq!(m.minecraft.version, "1.21.1");
    }
}
