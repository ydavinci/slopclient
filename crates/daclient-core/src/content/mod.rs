//! Mods, resource packs and shader packs of an instance.
//!
//! Files live in the instance's game directory (`mods/`, `resourcepacks/`,
//! `shaderpacks/`). Disabled files get a `.disabled` suffix (Prism convention).
//! `content.lock.json` remembers where tracked files came from so they can be
//! updated; files without an entry are listed as local files.

mod manager;
pub mod modrinth;
pub mod mrpack;

use std::{fmt, str::FromStr};

use serde::{Deserialize, Serialize};

pub use manager::{ContentManager, InstallReport, UpdateInfo};

use crate::{Error, Result};

pub const DISABLED_SUFFIX: &str = ".disabled";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ContentType {
    Mod,
    ResourcePack,
    Shader,
}

impl ContentType {
    pub const ALL: [ContentType; 3] = [Self::Mod, Self::ResourcePack, Self::Shader];

    pub fn folder(self) -> &'static str {
        match self {
            Self::Mod => "mods",
            Self::ResourcePack => "resourcepacks",
            Self::Shader => "shaderpacks",
        }
    }

    pub fn modrinth_name(self) -> &'static str {
        match self {
            Self::Mod => "mod",
            Self::ResourcePack => "resourcepack",
            Self::Shader => "shader",
        }
    }

    pub fn from_modrinth(project_type: &str) -> Option<Self> {
        match project_type {
            "mod" => Some(Self::Mod),
            "resourcepack" => Some(Self::ResourcePack),
            "shader" => Some(Self::Shader),
            _ => None,
        }
    }
}

impl FromStr for ContentType {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        match s.to_ascii_lowercase().as_str() {
            "mod" | "mods" => Ok(Self::Mod),
            "resourcepack" | "resourcepacks" | "rp" => Ok(Self::ResourcePack),
            "shader" | "shaders" | "shaderpack" | "shaderpacks" => Ok(Self::Shader),
            other => Err(Error::Other(format!("unknown content type: {other}"))),
        }
    }
}

impl fmt::Display for ContentType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Mod => "Mod",
            Self::ResourcePack => "Resource Pack",
            Self::Shader => "Shader",
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Source {
    #[serde(rename_all = "camelCase")]
    Modrinth {
        project_id: String,
        version_id: String,
        #[serde(default)]
        slug: Option<String>,
    },
    Local,
}

/// One installed file.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentEntry {
    /// File name without the `.disabled` suffix.
    pub file_name: String,
    pub kind: ContentType,
    pub name: String,
    #[serde(default)]
    pub version: Option<String>,
    pub sha1: String,
    pub source: Source,
    pub enabled: bool,
    /// Installed automatically as a dependency of another project.
    #[serde(default)]
    pub dependency: bool,
    #[serde(default)]
    pub icon_url: Option<String>,
}

impl ContentEntry {
    pub fn project_id(&self) -> Option<&str> {
        match &self.source {
            Source::Modrinth { project_id, .. } => Some(project_id),
            Source::Local => None,
        }
    }

    /// Matches a user query against file name, display name, slug or project id.
    pub fn matches(&self, query: &str) -> bool {
        let q = query.to_lowercase();
        self.file_name.to_lowercase() == q
            || self.name.to_lowercase() == q
            || match &self.source {
                Source::Modrinth { project_id, slug, .. } => {
                    project_id.to_lowercase() == q || slug.as_deref().is_some_and(|s| s == q)
                }
                Source::Local => false,
            }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ContentLock {
    pub entries: Vec<ContentEntry>,
}
