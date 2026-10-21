//! Mods, resource packs and shader packs of an instance.
//!
//! Files live in the instance's game directory (`mods/`, `resourcepacks/`,
//! `shaderpacks/`). Disabled files get a `.disabled` suffix (Prism convention).
//! `content.lock.json` remembers where tracked files came from so they can be
//! updated; files without an entry are listed as local files.

pub mod curseforge;
pub mod cfpack;
mod manager;
pub mod modrinth;
pub mod mrpack;

use std::{fmt, str::FromStr};

use serde::{Deserialize, Serialize};

pub use manager::{BlockedFile, ContentManager, InstallReport, RemoteFile, RemoteVersion, UpdateInfo};

use crate::{Error, Result};

pub const DISABLED_SUFFIX: &str = ".disabled";

/// Performance preset (Modrinth slugs): rendering, game logic, memory, shaders.
/// Iris needs Sodium; together they replace OptiFine.
pub const PERFORMANCE_PRESET: [&str; 4] = ["sodium", "lithium", "ferrite-core", "iris"];

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

    pub fn curseforge_class(self) -> u32 {
        match self {
            Self::Mod => 6,
            Self::ResourcePack => 12,
            Self::Shader => 6552,
        }
    }

    pub fn from_curseforge_class(class_id: u32) -> Option<Self> {
        match class_id {
            6 => Some(Self::Mod),
            12 => Some(Self::ResourcePack),
            6552 => Some(Self::Shader),
            _ => None,
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
    #[serde(rename = "curseforge", rename_all = "camelCase")]
    CurseForge {
        project_id: u64,
        file_id: u64,
        #[serde(default)]
        slug: Option<String>,
    },
    Local,
}

impl Source {
    /// Project id as a string, for either platform.
    pub fn project_key(&self) -> Option<String> {
        match self {
            Source::Modrinth { project_id, .. } => Some(project_id.clone()),
            Source::CurseForge { project_id, .. } => Some(project_id.to_string()),
            Source::Local => None,
        }
    }

    pub fn platform(&self) -> &'static str {
        match self {
            Source::Modrinth { .. } => "modrinth",
            Source::CurseForge { .. } => "curseforge",
            Source::Local => "local",
        }
    }
}

/// Everything the launcher's project page shows, from either platform.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectDetails {
    pub source: &'static str,
    pub id: String,
    pub slug: String,
    pub title: String,
    pub summary: String,
    /// Long description: Markdown on Modrinth, HTML on CurseForge.
    pub body: String,
    pub body_format: &'static str,
    pub icon_url: Option<String>,
    pub author: Option<String>,
    pub downloads: u64,
    pub followers: Option<u64>,
    pub categories: Vec<String>,
    pub license: Option<String>,
    pub updated: Option<String>,
    pub page_url: String,
    /// (label, url) pairs: source, issues, wiki, Discord.
    pub links: Vec<(String, String)>,
    pub gallery: Vec<GalleryImage>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GalleryImage {
    pub url: String,
    pub title: Option<String>,
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
            Source::CurseForge { .. } | Source::Local => None,
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
                Source::CurseForge { project_id, slug, .. } => {
                    project_id.to_string() == q || slug.as_deref().is_some_and(|s| s == q)
                }
                Source::Local => false,
            }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ContentLock {
    pub entries: Vec<ContentEntry>,
}
