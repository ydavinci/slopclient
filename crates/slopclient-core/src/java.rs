//! Java runtimes from Mojang's runtime manifest (the same builds the official launcher uses).

use std::{collections::HashMap, path::PathBuf};

use serde::Deserialize;

use crate::{
    Error, Paths, ProgressFn, Result,
    download::{DownloadTask, download_all},
    http,
    meta::VersionInfo,
    progress::stage,
};

const RUNTIMES_URL: &str = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Component used by versions without `javaVersion` (Java 8).
pub const LEGACY_COMPONENT: &str = "jre-legacy";

#[derive(Deserialize)]
struct RuntimeEntry {
    manifest: RemoteFile,
    version: RuntimeVersion,
}

#[derive(Deserialize)]
struct RuntimeVersion {
    name: String,
}

#[derive(Deserialize)]
struct RemoteFile {
    sha1: String,
    size: u64,
    url: String,
}

#[derive(Deserialize)]
struct RuntimeManifest {
    files: HashMap<String, RuntimeFile>,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
enum RuntimeFile {
    File { downloads: RuntimeDownloads },
    Directory,
    Link {},
}

#[derive(Deserialize)]
struct RuntimeDownloads {
    raw: RemoteFile,
}

fn platform() -> &'static str {
    match std::env::consts::ARCH {
        "aarch64" => "windows-arm64",
        "x86" => "windows-x86",
        _ => "windows-x64",
    }
}

pub fn component_for(info: &VersionInfo) -> String {
    info.java_version
        .as_ref()
        .map(|j| j.component.clone())
        .unwrap_or_else(|| LEGACY_COMPONENT.to_owned())
}

/// Path of `javaw.exe` for an installed component.
pub fn executable(paths: &Paths, component: &str) -> PathBuf {
    paths.java().join(component).join("bin").join("javaw.exe")
}

/// Makes sure the runtime `component` is installed and complete; returns `javaw.exe`.
pub async fn ensure_runtime(
    paths: &Paths,
    component: &str,
    concurrency: usize,
    progress: &ProgressFn,
) -> Result<PathBuf> {
    stage(progress, format!("Preparing Java ({component})"));
    let exe = executable(paths, component);
    let all: HashMap<String, HashMap<String, Vec<RuntimeEntry>>> =
        match http::get_json(RUNTIMES_URL).await {
            Ok(all) => all,
            // Offline: an existing runtime is good enough.
            Err(_) if exe.exists() => return Ok(exe),
            Err(e) => return Err(e),
        };
    let entry = all
        .get(platform())
        .and_then(|components| components.get(component))
        .and_then(|entries| entries.first())
        .ok_or_else(|| Error::Other(format!("Java runtime {component} not available for {}", platform())))?;

    let dir = paths.java().join(component);
    let marker = dir.join(".version");
    let installed = std::fs::read_to_string(&marker).unwrap_or_default();
    if installed.trim() == entry.version.name && exe.exists() {
        return Ok(exe);
    }

    let manifest_path = paths.cache().join(format!("java-{component}.json"));
    download_all(
        vec![
            DownloadTask::new(&entry.manifest.url, &manifest_path)
                .sha1(Some(entry.manifest.sha1.clone()))
                .size(Some(entry.manifest.size)),
        ],
        1,
        &crate::progress::noop(),
    )
    .await?;
    let manifest: RuntimeManifest =
        serde_json::from_str(&tokio::fs::read_to_string(&manifest_path).await?)?;

    let mut tasks = Vec::new();
    for (name, file) in manifest.files {
        let path = crate::paths::join_rel(&dir, &name);
        match file {
            RuntimeFile::Directory => std::fs::create_dir_all(&path)?,
            RuntimeFile::File { downloads } => tasks.push(
                DownloadTask::new(downloads.raw.url, path)
                    .sha1(Some(downloads.raw.sha1))
                    .size(Some(downloads.raw.size)),
            ),
            // Symlinks only occur in the macOS/Linux runtimes.
            RuntimeFile::Link {} => {}
        }
    }
    stage(progress, format!("Downloading Java ({component})"));
    download_all(tasks, concurrency, progress).await?;
    std::fs::write(marker, &entry.version.name)?;
    Ok(exe)
}
