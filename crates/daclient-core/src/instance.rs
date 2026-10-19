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
    /// Put the DaClient client mod into the instance when it supports it.
    #[serde(default = "default_true")]
    pub client_mod: bool,
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

    pub fn delete(paths: &Paths, id: &str) -> Result<()> {
        let dir = paths.instances().join(id);
        if !dir.join("instance.json").exists() {
            return Err(Error::InstanceNotFound(id.to_owned()));
        }
        std::fs::remove_dir_all(dir)?;
        Ok(())
    }
}

fn slugify(name: &str) -> String {
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
