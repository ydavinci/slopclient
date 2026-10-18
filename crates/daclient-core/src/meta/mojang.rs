//! Mojang launcher metadata: version manifest and per-version JSON.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::{Paths, Result, http};

pub const VERSION_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
pub const LIBRARIES_URL: &str = "https://libraries.minecraft.net/";
pub const RESOURCES_URL: &str = "https://resources.download.minecraft.net";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<ManifestEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    pub release_time: String,
    pub sha1: String,
}

impl VersionManifest {
    pub fn find(&self, id: &str) -> Option<&ManifestEntry> {
        self.versions.iter().find(|v| v.id == id)
    }
}

/// Fetches the version manifest, falling back to the cached copy when offline.
pub async fn fetch_manifest(paths: &Paths) -> Result<VersionManifest> {
    let cache = paths.cache().join("version_manifest_v2.json");
    match http::get_text(VERSION_MANIFEST_URL).await {
        Ok(text) => {
            let manifest = serde_json::from_str(&text)?;
            std::fs::create_dir_all(paths.cache())?;
            std::fs::write(&cache, text)?;
            Ok(manifest)
        }
        Err(err) => match std::fs::read_to_string(&cache) {
            Ok(text) => Ok(serde_json::from_str(&text)?),
            Err(_) => Err(err),
        },
    }
}

/// A version JSON (`versions/<id>/<id>.json`). Loader profiles (Fabric, NeoForge …)
/// use the same format with `inheritsFrom` pointing at the vanilla version.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionInfo {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub inherits_from: Option<String>,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub main_class: Option<String>,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    /// Pre-1.13 game arguments as a single string.
    #[serde(default)]
    pub minecraft_arguments: Option<String>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(default)]
    pub asset_index: Option<AssetIndexRef>,
    #[serde(default)]
    pub assets: Option<String>,
    #[serde(default)]
    pub downloads: Option<VersionDownloads>,
    #[serde(default)]
    pub java_version: Option<JavaVersion>,
    #[serde(default)]
    pub logging: Option<Logging>,
    /// Version whose client jar is used (set when a profile inherits the jar).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub jar: Option<String>,
}

impl VersionInfo {
    /// Combines a loader profile with the version it inherits from.
    /// The child's libraries come first so they win de-duplication.
    pub fn merge(parent: VersionInfo, child: VersionInfo) -> VersionInfo {
        let jar = child
            .jar
            .or_else(|| child.downloads.is_none().then(|| parent.jar.clone().unwrap_or(parent.id.clone())));
        let mut libraries = child.libraries;
        libraries.extend(parent.libraries);
        let arguments = match (parent.arguments, child.arguments) {
            (Some(mut p), Some(c)) => {
                p.game.extend(c.game);
                p.jvm.extend(c.jvm);
                Some(p)
            }
            (p, c) => c.or(p),
        };
        VersionInfo {
            id: child.id,
            inherits_from: None,
            kind: child.kind.or(parent.kind),
            main_class: child.main_class.or(parent.main_class),
            arguments,
            minecraft_arguments: child.minecraft_arguments.or(parent.minecraft_arguments),
            libraries,
            asset_index: child.asset_index.or(parent.asset_index),
            assets: child.assets.or(parent.assets),
            downloads: child.downloads.or(parent.downloads),
            java_version: child.java_version.or(parent.java_version),
            logging: child.logging.or(parent.logging),
            jar,
        }
    }

    pub fn jar_id(&self) -> &str {
        self.jar.as_deref().unwrap_or(&self.id)
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional { rules: Vec<Rule>, value: ArgValue },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ArgValue {
    One(String),
    Many(Vec<String>),
}

impl ArgValue {
    pub fn values(&self) -> Vec<String> {
        match self {
            ArgValue::One(s) => vec![s.clone()],
            ArgValue::Many(v) => v.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub action: RuleAction,
    #[serde(default)]
    pub os: Option<OsRule>,
    #[serde(default)]
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RuleAction {
    Allow,
    Disallow,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OsRule {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub arch: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibraryDownloads>,
    /// Maven repository base URL (used by Fabric/Quilt profiles).
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub rules: Option<Vec<Rule>>,
    /// Legacy natives: OS name -> classifier (may contain `${arch}`).
    #[serde(default)]
    pub natives: Option<HashMap<String, String>>,
    #[serde(default)]
    pub extract: Option<Extract>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LibraryDownloads {
    #[serde(default)]
    pub artifact: Option<Artifact>,
    #[serde(default)]
    pub classifiers: Option<HashMap<String, Artifact>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Artifact {
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub url: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Extract {
    #[serde(default)]
    pub exclude: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    #[serde(default)]
    pub total_size: Option<u64>,
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionDownloads {
    #[serde(default)]
    pub client: Option<Artifact>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    pub component: String,
    pub major_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Logging {
    #[serde(default)]
    pub client: Option<LoggingEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingEntry {
    pub argument: String,
    pub file: LoggingFile,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingFile {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

/// Contents of `assets/indexes/<id>.json`.
#[derive(Debug, Clone, Deserialize)]
pub struct AssetIndex {
    pub objects: HashMap<String, AssetObject>,
    #[serde(default, rename = "virtual")]
    pub is_virtual: bool,
    #[serde(default)]
    pub map_to_resources: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}
