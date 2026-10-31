//! Instances: isolated game directories with their own version, loader and settings.

use std::{
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

use serde::{Deserialize, Serialize};

use crate::{Error, Paths, Result, meta::LoaderKind};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Instance {
    pub id: String,
    pub name: String,
    pub mc_version: String,
    #[serde(default)]
    pub loader: Loader,
    #[serde(default)]
    pub java: JavaSettings,
    #[serde(default)]
    pub group: Option<String>,
    pub created_at: u64,
    #[serde(default)]
    pub last_played: Option<u64>,
    #[serde(default)]
    pub play_time_secs: u64,
    /// Set when the instance was created from a modpack.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modpack: Option<ModpackInfo>,
    /// Put the SlopClient client mod into the instance when it supports it.
    #[serde(default = "default_true")]
    pub client_mod: bool,
    /// Custom icon (instances/<id>/icon.png); none shows the colour tile.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<crate::icon::InstanceIcon>,
    /// Shell commands run around a game session.
    #[serde(default)]
    pub hooks: Hooks,
}

/// Commands run through `cmd /C` in the game folder, with the instance in
/// `INST_NAME`, `INST_ID`, `INST_DIR`, `INST_MC_DIR` and `INST_JAVA` (Prism's names).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Hooks {
    /// Runs before the game starts; a non-zero exit code cancels the start.
    pub pre_launch: String,
    /// Runs after the game exited.
    pub post_exit: String,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackInfo {
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub version_id: Option<String>,
    pub name: String,
    pub version: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Loader {
    #[default]
    Vanilla,
    Fabric { version: String },
    Quilt { version: String },
    NeoForge { version: String },
    Forge { version: String },
}

impl Loader {
    pub fn new(kind: LoaderKind, version: String) -> Self {
        match kind {
            LoaderKind::Fabric => Self::Fabric { version },
            LoaderKind::Quilt => Self::Quilt { version },
            LoaderKind::NeoForge => Self::NeoForge { version },
            LoaderKind::Forge => Self::Forge { version },
        }
    }

    pub fn kind(&self) -> Option<LoaderKind> {
        match self {
            Self::Vanilla => None,
            Self::Fabric { .. } => Some(LoaderKind::Fabric),
            Self::Quilt { .. } => Some(LoaderKind::Quilt),
            Self::NeoForge { .. } => Some(LoaderKind::NeoForge),
            Self::Forge { .. } => Some(LoaderKind::Forge),
        }
    }

    pub fn version(&self) -> Option<&str> {
        match self {
            Self::Vanilla => None,
            Self::Fabric { version }
            | Self::Quilt { version }
            | Self::NeoForge { version }
            | Self::Forge { version } => Some(version),
        }
    }
}

impl std::fmt::Display for Loader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match (self.kind(), self.version()) {
            (Some(kind), Some(version)) => write!(f, "{kind} {version}"),
            _ => f.write_str("Vanilla"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct JavaSettings {
    /// Custom `java(w).exe`; `None` = managed runtime matching the version.
    pub path: Option<PathBuf>,
    pub min_memory_mb: u32,
    pub max_memory_mb: u32,
    pub jvm_args: Vec<String>,
}

impl Default for JavaSettings {
    fn default() -> Self {
        Self {
            path: None,
            min_memory_mb: 512,
            max_memory_mb: 4096,
            jvm_args: Vec::new(),
        }
    }
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

impl Instance {
    pub fn dir(&self, paths: &Paths) -> PathBuf {
        paths.instances().join(&self.id)
    }

    /// The `.minecraft` of this instance (mods, saves, resourcepacks, …).
    pub fn game_dir(&self, paths: &Paths) -> PathBuf {
        self.dir(paths).join("minecraft")
    }

    pub fn create(paths: &Paths, name: &str, mc_version: &str, loader: Loader) -> Result<Self> {
        let base = slugify(name);
        let mut id = base.clone();
        let mut n = 2;
        while paths.instances().join(&id).exists() {
            id = format!("{base}-{n}");
            n += 1;
        }
        let instance = Self {
            id,
            name: name.trim().to_owned(),
            mc_version: mc_version.to_owned(),
            loader,
            java: JavaSettings::default(),
            group: None,
            created_at: now(),
            last_played: None,
            play_time_secs: 0,
            modpack: None,
            client_mod: true,
            icon: None,
            hooks: Hooks::default(),
        };
        std::fs::create_dir_all(instance.game_dir(paths))?;
        instance.save(paths)?;
        Ok(instance)
    }

    pub fn load(paths: &Paths, id: &str) -> Result<Self> {
        let file = paths.instances().join(id).join("instance.json");
        match std::fs::read_to_string(file) {
            Ok(text) => Ok(serde_json::from_str(&text)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                Err(Error::InstanceNotFound(id.to_owned()))
            }
            Err(e) => Err(e.into()),
        }
    }

    pub fn save(&self, paths: &Paths) -> Result<()> {
        let dir = self.dir(paths);
        std::fs::create_dir_all(&dir)?;
        std::fs::write(dir.join("instance.json"), serde_json::to_string_pretty(self)?)?;
        Ok(())
    }

    /// All instances, most recently played first.
    pub fn list(paths: &Paths) -> Result<Vec<Self>> {
        let mut out = Vec::new();
        let Ok(entries) = std::fs::read_dir(paths.instances()) else {
            return Ok(out);
        };
        for entry in entries.flatten() {
            if let Some(id) = entry.file_name().to_str()
                && let Ok(instance) = Self::load(paths, id) {
                    out.push(instance);
                }
        }
        out.sort_by_key(|i| std::cmp::Reverse(i.last_played.unwrap_or(i.created_at)));
        Ok(out)
    }

    /// Copies the instance with everything in it (worlds, mods, settings) under a new
    /// name. Play statistics start from zero.
    pub async fn duplicate(paths: &Paths, id: &str, name: &str) -> Result<Self> {
        let source = Self::load(paths, id)?;
        let mut copy = Self::create(paths, name, &source.mc_version, source.loader.clone())?;
        let (from, to) = (source.dir(paths), copy.dir(paths));
        let link = crate::store::enabled(paths);
        let copied = tokio::task::spawn_blocking(move || copy_tree(&from, &to, true, link)).await?;
        if let Err(e) = copied {
            let _ = Self::delete(paths, &copy.id);
            return Err(e.into());
        }
        copy.java = source.java;
        copy.group = source.group;
        copy.modpack = source.modpack;
        copy.client_mod = source.client_mod;
        copy.icon = source.icon;
        copy.hooks = source.hooks;
        copy.save(paths)?;
        Ok(copy)
    }

    pub fn delete(paths: &Paths, id: &str) -> Result<()> {
        let dir = paths.instances().join(id);
        if !dir.join("instance.json").exists() {
            return Err(Error::InstanceNotFound(id.to_owned()));
        }
        std::fs::remove_dir_all(dir)?;
        // Its settings links are gone with the folder; the group keeps its files.
        let _ = crate::shared_options::forget_instance(paths, id);
        Ok(())
    }
}

/// What a new instance can take over from an existing one.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum CopyPart {
    /// options.txt and friends: keybinds, FOV, video and sound settings.
    Options,
    /// servers.dat: the multiplayer server list.
    Servers,
    /// config/: settings of the installed mods (without SlopClient's).
    ModConfigs,
    /// config/slopclient: SlopClient modules, HUD layout, profiles.
    Slopclient,
    ResourcePacks,
    ShaderPacks,
}

impl CopyPart {
    /// Files and folders in the game directory this part covers.
    pub(crate) fn entries(self) -> &'static [&'static str] {
        match self {
            Self::Options => &["options.txt", "optionsof.txt", "optionsshaders.txt"],
            Self::Servers => &["servers.dat"],
            Self::ModConfigs => &["config"],
            Self::Slopclient => &["config/slopclient"],
            Self::ResourcePacks => &["resourcepacks"],
            Self::ShaderPacks => &["shaderpacks"],
        }
    }
}

impl Instance {
    /// Copies the chosen settings from another instance into this one. Existing
    /// files are overwritten; parts the source does not have are skipped.
    pub async fn copy_settings_from(&self, paths: &Paths, source_id: &str, parts: &[CopyPart]) -> Result<usize> {
        let source = Self::load(paths, source_id)?;
        let (from, to) = (source.game_dir(paths), self.game_dir(paths));
        let parts = parts.to_vec();
        let link = crate::store::enabled(paths);
        Ok(tokio::task::spawn_blocking(move || -> std::io::Result<usize> {
            let mut copied = 0;
            for part in &parts {
                for entry in part.entries() {
                    let src = from.join(entry);
                    let dst = to.join(entry);
                    if src.is_dir() {
                        // Mod configs without SlopClient's own folder, which is its own part.
                        let skip_slopclient = *part == CopyPart::ModConfigs;
                        copy_dir_filtered(&src, &dst, skip_slopclient.then_some("slopclient"), link)?;
                        copied += 1;
                    } else if src.is_file() {
                        if let Some(parent) = dst.parent() {
                            std::fs::create_dir_all(parent)?;
                        }
                        std::fs::copy(&src, &dst)?;
                        copied += 1;
                    }
                }
            }
            Ok(copied)
        })
        .await??)
    }
}

/// Copies a file; mods, resource packs and shaders become hard links instead when
/// `link` is set (they never change, so the instances can share them).
fn copy_or_link(from: &std::path::Path, to: &std::path::Path, link: bool) -> std::io::Result<()> {
    let name = to.file_name().unwrap_or_default().to_string_lossy();
    if link && crate::store::is_content_path(to) && !crate::client_mod::is_mod_file(&name) {
        crate::links::link_or_copy(from, to)?;
    } else {
        std::fs::copy(from, to)?;
    }
    Ok(())
}

fn copy_dir_filtered(from: &std::path::Path, to: &std::path::Path, skip_top: Option<&str>, link: bool) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)?.flatten() {
        if skip_top.is_some_and(|s| entry.file_name() == s) {
            continue;
        }
        let target = to.join(entry.file_name());
        let kind = entry.file_type()?;
        if kind.is_dir() {
            copy_dir_filtered(&entry.path(), &target, None, link)?;
        } else if kind.is_file() {
            copy_or_link(&entry.path(), &target, link)?;
        }
    }
    Ok(())
}

/// Recursive copy; at the top level the instance's own bookkeeping is left out.
fn copy_tree(from: &std::path::Path, to: &std::path::Path, top: bool, link: bool) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)?.flatten() {
        let name = entry.file_name();
        if top && (name == "instance.json" || name == "launch.args") {
            continue;
        }
        let target = to.join(&name);
        let kind = entry.file_type()?;
        if kind.is_dir() {
            copy_tree(&entry.path(), &target, false, link)?;
        } else if kind.is_file() {
            copy_or_link(&entry.path(), &target, link)?;
        }
    }
    Ok(())
}

pub(crate) fn slugify(name: &str) -> String {
    let mut slug = String::new();
    for c in name.trim().to_lowercase().chars() {
        if c.is_ascii_alphanumeric() {
            slug.push(c);
        } else if !slug.ends_with('-') {
            slug.push('-');
        }
    }
    let slug = slug.trim_matches('-').to_owned();
    if slug.is_empty() { "instance".into() } else { slug }
}

#[cfg(test)]
mod tests {
    use super::slugify;

    #[test]
    fn slugs() {
        assert_eq!(slugify("PvP Survival 1.21!"), "pvp-survival-1-21");
        assert_eq!(slugify("  ##  "), "instance");
    }
}
