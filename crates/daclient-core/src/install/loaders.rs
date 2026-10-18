//! Mod loader installation. Each loader ends up as a version JSON in
//! `versions/<id>/` that inherits from the vanilla version.
//!
//! - Fabric / Quilt: the meta API serves a ready-made profile JSON.
//! - NeoForge / Forge: the official installer runs headless against our data
//!   directory (same layout as `.minecraft`) and performs its processors there.

use std::process::Stdio;

use super::Installer;
use crate::{
    Error, Result,
    download::{DownloadTask, download_all},
    http,
    instance::Loader,
    java,
    meta::{
        VersionInfo,
        loaders::{FABRIC_META, FORGE_MAVEN, NEOFORGE_MAVEN, QUILT_META},
    },
    progress::{noop, stage},
};

impl Installer<'_> {
    /// Installs `loader` for Minecraft `mc` and returns the version id to launch.
    pub async fn install_loader(&self, mc: &str, loader: &Loader) -> Result<String> {
        match loader {
            Loader::Vanilla => Ok(mc.to_owned()),
            Loader::Fabric { version } => {
                let url = format!("{FABRIC_META}/versions/loader/{mc}/{version}/profile/json");
                self.install_profile(&url, &format!("fabric-{mc}-{version}")).await
            }
            Loader::Quilt { version } => {
                let url = format!("{QUILT_META}/versions/loader/{mc}/{version}/profile/json");
                self.install_profile(&url, &format!("quilt-{mc}-{version}")).await
            }
            Loader::NeoForge { version } => {
                let url = format!(
                    "{NEOFORGE_MAVEN}/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar"
                );
                self.run_forge_installer(mc, "NeoForge", &url, &format!("neoforge-{version}-installer.jar"))
                    .await
            }
            Loader::Forge { version } => {
                let full = format!("{mc}-{version}");
                let url = format!(
                    "{FORGE_MAVEN}/net/minecraftforge/forge/{full}/forge-{full}-installer.jar"
                );
                self.run_forge_installer(mc, "Forge", &url, &format!("forge-{full}-installer.jar"))
                    .await
            }
        }
    }

    /// Stores a Fabric/Quilt profile JSON; uses the cached copy when offline.
    async fn install_profile(&self, url: &str, cache_name: &str) -> Result<String> {
        stage(&self.progress, "Fetching loader profile");
        let cache = self.paths.cache().join("profiles").join(format!("{cache_name}.json"));
        let text = match http::get_text(url).await {
            Ok(text) => {
                std::fs::create_dir_all(cache.parent().unwrap())?;
                std::fs::write(&cache, &text)?;
                text
            }
            Err(err) => std::fs::read_to_string(&cache).map_err(|_| err)?,
        };
        let profile: VersionInfo = serde_json::from_str(&text)?;
        let path = self.paths.version_json(&profile.id);
        std::fs::create_dir_all(path.parent().unwrap())?;
        std::fs::write(path, text)?;
        Ok(profile.id)
    }

    async fn run_forge_installer(
        &self,
        mc: &str,
        name: &str,
        url: &str,
        file_name: &str,
    ) -> Result<String> {
        stage(&self.progress, format!("Downloading {name} installer"));
        let dir = self.paths.cache().join("installers");
        let jar = dir.join(file_name);
        let sha1 = http::get_text(&format!("{url}.sha1"))
            .await
            .ok()
            .map(|s| s.trim().to_owned())
            .filter(|s| s.len() == 40);
        download_all(vec![DownloadTask::new(url, &jar).sha1(sha1)], 1, &noop()).await?;

        let id = {
            let jar = jar.clone();
            tokio::task::spawn_blocking(move || installer_version_id(&jar)).await??
        };
        let marker = self.paths.version_dir(&id).join(".installed");
        if marker.exists() && self.paths.version_json(&id).exists() {
            return Ok(id);
        }

        // The installer needs the vanilla jar and a Java matching the game.
        let vanilla = self.install(mc).await?;
        let component = java::component_for(&vanilla.info);
        let javaw = java::ensure_runtime(self.paths, &component, self.concurrency, &self.progress).await?;
        let java = javaw.with_file_name("java.exe");

        let profiles = self.paths.root.join("launcher_profiles.json");
        if !profiles.exists() {
            std::fs::write(&profiles, r#"{"profiles":{}}"#)?;
        }

        stage(&self.progress, format!("Running {name} installer (this can take a minute)"));
        let mut cmd = tokio::process::Command::new(&java);
        cmd.arg("-jar")
            .arg(&jar)
            .arg("--installClient")
            .arg(&self.paths.root)
            .current_dir(&dir)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        let output = cmd.output().await?;
        if !output.status.success() || !self.paths.version_json(&id).exists() {
            let log = String::from_utf8_lossy(&output.stdout);
            let tail: Vec<&str> = log.lines().rev().take(15).collect();
            return Err(Error::Other(format!(
                "{name} installer failed ({}):\n{}",
                output.status,
                tail.into_iter().rev().collect::<Vec<_>>().join("\n")
            )));
        }
        std::fs::write(marker, "")?;
        Ok(id)
    }
}

/// Reads the id of the version the installer will create from its embedded `version.json`.
fn installer_version_id(jar: &std::path::Path) -> Result<String> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(jar)?)?;
    let entry = zip.by_name("version.json")?;
    let info: VersionInfo = serde_json::from_reader(entry)?;
    Ok(info.id)
}
