//! Available mod loader versions per Minecraft version.

use std::{cmp::Ordering, fmt, str::FromStr};

use serde::{Deserialize, Serialize};

use crate::{Error, Result, http};

pub const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
pub const QUILT_META: &str = "https://meta.quiltmc.org/v3";
pub const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases";
pub const FORGE_MAVEN: &str = "https://maven.minecraftforge.net";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LoaderKind {
    Fabric,
    Quilt,
    NeoForge,
    Forge,
}

impl FromStr for LoaderKind {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        match s.to_ascii_lowercase().as_str() {
            "fabric" => Ok(Self::Fabric),
            "quilt" => Ok(Self::Quilt),
            "neoforge" => Ok(Self::NeoForge),
            "forge" => Ok(Self::Forge),
            other => Err(Error::Other(format!("unknown loader: {other}"))),
        }
    }
}

impl fmt::Display for LoaderKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Fabric => "Fabric",
            Self::Quilt => "Quilt",
            Self::NeoForge => "NeoForge",
            Self::Forge => "Forge",
        })
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct LoaderVersion {
    pub version: String,
    pub stable: bool,
}

/// Loader versions for `mc`, newest first. Empty if the loader doesn't support it.
pub async fn list(kind: LoaderKind, mc: &str) -> Result<Vec<LoaderVersion>> {
    let mut versions = fetch(kind, mc).await?;
    versions.sort_by(|a, b| compare_versions(&b.version, &a.version));
    Ok(versions)
}

/// Compares versions like `0.19.5`, `0.20.0-beta.9` or `21.10.64` numerically;
/// a pre-release sorts before the release with the same base.
pub fn compare_versions(a: &str, b: &str) -> Ordering {
    fn segments(s: &str) -> Vec<&str> {
        s.split(['.', '+']).collect()
    }
    fn compare_segments(a: &str, b: &str) -> Ordering {
        for (x, y) in segments(a).into_iter().zip(segments(b)) {
            let ord = match (x.parse::<u64>(), y.parse::<u64>()) {
                (Ok(x), Ok(y)) => x.cmp(&y),
                _ => x.cmp(y),
            };
            if ord != Ordering::Equal {
                return ord;
            }
        }
        segments(a).len().cmp(&segments(b).len())
    }
    let (base_a, pre_a) = a.split_once('-').map_or((a, None), |(b, p)| (b, Some(p)));
    let (base_b, pre_b) = b.split_once('-').map_or((b, None), |(b, p)| (b, Some(p)));
    compare_segments(base_a, base_b).then_with(|| match (pre_a, pre_b) {
        (None, None) => Ordering::Equal,
        (None, Some(_)) => Ordering::Greater,
        (Some(_), None) => Ordering::Less,
        (Some(x), Some(y)) => compare_segments(x, y),
    })
}

async fn fetch(kind: LoaderKind, mc: &str) -> Result<Vec<LoaderVersion>> {
    match kind {
        LoaderKind::Fabric => list_fabric_like(&format!("{FABRIC_META}/versions/loader/{mc}"), true).await,
        LoaderKind::Quilt => list_fabric_like(&format!("{QUILT_META}/versions/loader/{mc}"), false).await,
        LoaderKind::NeoForge => list_neoforge(mc).await,
        LoaderKind::Forge => list_forge(mc).await,
    }
}

/// Newest stable version, or the newest at all if none is stable.
pub async fn recommended(kind: LoaderKind, mc: &str) -> Result<String> {
    let versions = list(kind, mc).await?;
    versions
        .iter()
        .find(|v| v.stable)
        .or(versions.first())
        .map(|v| v.version.clone())
        .ok_or_else(|| Error::Other(format!("{kind} is not available for Minecraft {mc}")))
}

#[derive(Deserialize)]
struct FabricEntry {
    loader: FabricLoader,
}

#[derive(Deserialize)]
struct FabricLoader {
    version: String,
    #[serde(default)]
    stable: Option<bool>,
}

async fn list_fabric_like(url: &str, has_stable_flag: bool) -> Result<Vec<LoaderVersion>> {
    let response = http::client().get(url).send().await?;
    if response.status().is_client_error() {
        return Ok(Vec::new());
    }
    let entries: Vec<FabricEntry> = response.error_for_status()?.json().await?;
    Ok(entries
        .into_iter()
        .map(|e| LoaderVersion {
            stable: match (has_stable_flag, e.loader.stable) {
                (true, Some(stable)) => stable,
                _ => !e.loader.version.contains('-'),
            },
            version: e.loader.version,
        })
        .collect())
}

#[derive(Deserialize)]
struct MavenVersions {
    versions: Vec<String>,
}

async fn list_neoforge(mc: &str) -> Result<Vec<LoaderVersion>> {
    let list: MavenVersions = http::get_json(
        "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge",
    )
    .await?;
    let out: Vec<LoaderVersion> = list
        .versions
        .into_iter()
        .filter(|v| neoforge_mc_version(v).as_deref() == Some(mc))
        .map(|v| LoaderVersion {
            stable: !v.contains("beta") && !v.contains("alpha"),
            version: v,
        })
        .collect();
    Ok(out)
}

/// NeoForge encodes the Minecraft version in its own:
/// `21.4.150` → 1.21.4, `21.0.10` → 1.21, `26.3.0.92` → 26.3, `26.1.2.5` → 26.1.2.
pub fn neoforge_mc_version(neoforge: &str) -> Option<String> {
    let base = neoforge.split('-').next()?;
    let parts: Vec<u32> = base.split('.').map(|p| p.parse().ok()).collect::<Option<_>>()?;
    match parts.as_slice() {
        [major, minor, _] if (20..26).contains(major) => Some(if *minor == 0 {
            format!("1.{major}")
        } else {
            format!("1.{major}.{minor}")
        }),
        [major, minor, patch, _] if *major >= 26 => Some(if *patch == 0 {
            format!("{major}.{minor}")
        } else {
            format!("{major}.{minor}.{patch}")
        }),
        _ => None,
    }
}

async fn list_forge(mc: &str) -> Result<Vec<LoaderVersion>> {
    let xml = http::get_text(&format!("{FORGE_MAVEN}/net/minecraftforge/forge/maven-metadata.xml")).await?;
    let prefix = format!("{mc}-");
    Ok(xml
        .split("<version>")
        .skip(1)
        .filter_map(|chunk| chunk.split("</version>").next())
        .filter_map(|v| v.strip_prefix(&prefix))
        // Some old entries carry a branch suffix (`1.7.10-10.13.4.1614-1.7.10`).
        .map(|v| v.split('-').next().unwrap_or(v).to_owned())
        .map(|version| LoaderVersion { version, stable: true })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::{compare_versions, neoforge_mc_version};
    use std::cmp::Ordering;

    #[test]
    fn orders_versions() {
        assert_eq!(compare_versions("0.20.0-beta.9", "0.20.0-beta.10"), Ordering::Less);
        assert_eq!(compare_versions("0.20.0", "0.20.0-beta.9"), Ordering::Greater);
        assert_eq!(compare_versions("66.0.10", "66.0.3"), Ordering::Greater);
        assert_eq!(compare_versions("0.19.5", "0.20.0"), Ordering::Less);
    }

    #[test]
    fn maps_neoforge_versions() {
        assert_eq!(neoforge_mc_version("21.4.150").as_deref(), Some("1.21.4"));
        assert_eq!(neoforge_mc_version("21.0.10-beta").as_deref(), Some("1.21"));
        assert_eq!(neoforge_mc_version("26.3.0.92").as_deref(), Some("26.3"));
        assert_eq!(neoforge_mc_version("26.1.2.5").as_deref(), Some("26.1.2"));
        assert_eq!(neoforge_mc_version("0.25w14craftmine.3-beta"), None);
    }
}
