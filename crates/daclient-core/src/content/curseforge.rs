//! CurseForge API v1 client (`api.curseforge.com`). Needs an API key from the
//! CurseForge console; see [`crate::config::Settings::curseforge_api_key`].
//!
//! Some authors disable third-party distribution: those files come back without
//! a `downloadUrl` and have to be downloaded by the user on the website.

use std::collections::HashMap;

use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::json;

use super::{ContentType, modrinth};
use crate::{Error, Result, http, meta::LoaderKind};

const API: &str = "https://api.curseforge.com/v1";
pub const GAME_MINECRAFT: u32 = 432;
pub const CLASS_MODPACK: u32 = 4471;

/// Relation types of [`FileDependency`].
pub const RELATION_REQUIRED: u32 = 3;
pub const RELATION_INCOMPATIBLE: u32 = 5;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Mod {
    pub id: u64,
    pub name: String,
    pub slug: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub download_count: f64,
    #[serde(default)]
    pub class_id: Option<u32>,
    #[serde(default)]
    pub logo: Option<Logo>,
    #[serde(default)]
    pub authors: Vec<Author>,
    #[serde(default)]
    pub categories: Vec<Category>,
    #[serde(default)]
    pub links: Option<Links>,
    /// `false` when the author blocks downloads outside the website.
    #[serde(default)]
    pub allow_mod_distribution: Option<bool>,
}

impl Mod {
    pub fn kind(&self) -> Option<ContentType> {
        self.class_id.and_then(ContentType::from_curseforge_class)
    }

    pub fn icon_url(&self) -> Option<String> {
        self.logo.as_ref().map(|l| l.thumbnail_url.clone()).filter(|u| !u.is_empty())
    }

    pub fn website_url(&self) -> String {
        self.links
            .as_ref()
            .and_then(|l| l.website_url.clone())
            .unwrap_or_else(|| format!("https://www.curseforge.com/minecraft/mc-mods/{}", self.slug))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Logo {
    #[serde(default)]
    pub thumbnail_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Author {
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Category {
    pub name: String,
    #[serde(default)]
    pub slug: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Links {
    #[serde(default)]
    pub website_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct File {
    pub id: u64,
    pub mod_id: u64,
    pub display_name: String,
    pub file_name: String,
    /// 1 release, 2 beta, 3 alpha.
    pub release_type: u32,
    pub file_date: String,
    pub file_length: u64,
    #[serde(default)]
    pub download_url: Option<String>,
    /// Minecraft versions mixed with loader and environment tags ("Fabric", "Client").
    #[serde(default)]
    pub game_versions: Vec<String>,
    #[serde(default)]
    pub dependencies: Vec<FileDependency>,
    #[serde(default)]
    pub hashes: Vec<FileHash>,
    #[serde(default)]
    pub file_fingerprint: u64,
}

impl File {
    pub fn sha1(&self) -> Option<String> {
        self.hashes
            .iter()
            .find(|h| h.algo == 1)
            .map(|h| h.value.to_lowercase())
    }

    /// Download address; `None` when the author disabled third-party downloads.
    pub fn url(&self) -> Option<String> {
        self.download_url.clone().filter(|u| !u.is_empty())
    }

    pub fn release_label(&self) -> &'static str {
        match self.release_type {
            1 => "release",
            2 => "beta",
            _ => "alpha",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileDependency {
    pub mod_id: u64,
    pub relation_type: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileHash {
    pub value: String,
    /// 1 SHA-1, 2 MD5.
    pub algo: u32,
}

#[derive(Debug, Deserialize)]
struct Data<T> {
    data: T,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Paged<T> {
    data: Vec<T>,
    pagination: Pagination,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pagination {
    index: u64,
    total_count: u64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FingerprintMatches {
    #[serde(default)]
    exact_matches: Vec<FingerprintMatch>,
}

#[derive(Debug, Deserialize)]
struct FingerprintMatch {
    file: File,
}

/// Loader filter id CurseForge uses (`modLoaderType`).
pub fn loader_type(kind: LoaderKind) -> u32 {
    match kind {
        LoaderKind::Forge => 1,
        LoaderKind::Fabric => 4,
        LoaderKind::Quilt => 5,
        LoaderKind::NeoForge => 6,
    }
}

/// API client holding the key.
#[derive(Clone)]
pub struct CurseForge {
    key: String,
}

impl CurseForge {
    pub fn new(key: impl Into<String>) -> Self {
        Self { key: key.into() }
    }

    pub fn key(&self) -> &str {
        &self.key
    }

    async fn send<T: DeserializeOwned>(&self, request: reqwest::RequestBuilder) -> Result<T> {
        let response = request
            .header("x-api-key", &self.key)
            .header("accept", "application/json")
            .send()
            .await?;
        match response.status() {
            reqwest::StatusCode::FORBIDDEN | reqwest::StatusCode::UNAUTHORIZED => {
                Err(Error::Other("CurseForge rejected the API key".into()))
            }
            reqwest::StatusCode::NOT_FOUND => Err(Error::Other("not found on CurseForge".into())),
            _ => Ok(response.error_for_status()?.json().await?),
        }
    }

    async fn get<T: DeserializeOwned>(&self, path: &str, query: &[(&str, String)]) -> Result<T> {
        self.send(http::client().get(format!("{API}{path}")).query(query))
            .await
    }

    async fn post<T: DeserializeOwned>(&self, path: &str, body: serde_json::Value) -> Result<T> {
        self.send(http::client().post(format!("{API}{path}")).json(&body))
            .await
    }

    /// Search in the same shape as Modrinth results (`source` = `curseforge`).
    pub async fn search(&self, q: &modrinth::SearchQuery) -> Result<modrinth::SearchResult> {
        let limit = if q.limit == 0 { 20 } else { q.limit.min(50) };
        // Popularity, total downloads, last updated, name.
        let sort = match q.sort.as_deref() {
            Some("downloads") => "6",
            Some("newest") | Some("updated") => "3",
            Some("follows") => "2",
            _ => "2",
        };
        let mut query = vec![
            ("gameId", GAME_MINECRAFT.to_string()),
            ("searchFilter", q.query.clone()),
            ("sortField", sort.into()),
            ("sortOrder", "desc".into()),
            ("index", q.offset.to_string()),
            ("pageSize", limit.to_string()),
        ];
        let class = match q.project_type.as_deref() {
            Some("modpack") => Some(CLASS_MODPACK),
            Some(t) => ContentType::from_modrinth(t).map(ContentType::curseforge_class),
            None => None,
        };
        if let Some(class) = class {
            query.push(("classId", class.to_string()));
        }
        if let Some(v) = &q.game_version {
            query.push(("gameVersion", v.clone()));
        }
        let loaders: Vec<u32> = q
            .loaders
            .iter()
            .filter_map(|l| l.parse::<LoaderKind>().ok())
            .map(loader_type)
            .collect();
        if let [one] = loaders.as_slice() {
            query.push(("modLoaderType", one.to_string()));
        }

        let page: Paged<Mod> = self.get("/mods/search", &query).await?;
        let hits = page
            .data
            .into_iter()
            .map(|m| modrinth::SearchHit {
                project_id: m.id.to_string(),
                slug: m.slug.clone(),
                title: m.name.clone(),
                description: m.summary.clone(),
                author: m.authors.first().map(|a| a.name.clone()).unwrap_or_default(),
                project_type: match m.class_id {
                    Some(CLASS_MODPACK) => "modpack".into(),
                    _ => m.kind().unwrap_or(ContentType::Mod).modrinth_name().into(),
                },
                downloads: m.download_count as u64,
                follows: 0,
                icon_url: m.icon_url(),
                categories: m.categories.iter().map(|c| c.slug.clone()).collect(),
                source: "curseforge".into(),
            })
            .collect();
        Ok(modrinth::SearchResult {
            hits,
            total_hits: page.pagination.total_count,
            offset: page.pagination.index,
            limit,
        })
    }

    pub async fn get_mod(&self, id: u64) -> Result<Mod> {
        Ok(self.get::<Data<Mod>>(&format!("/mods/{id}"), &[]).await?.data)
    }

    /// Project by numeric id or slug. Slugs are only unique per class, so mods win.
    pub async fn find_mod(&self, id_or_slug: &str) -> Result<Mod> {
        if let Ok(id) = id_or_slug.parse::<u64>() {
            return self.get_mod(id).await;
        }
        let page: Paged<Mod> = self
            .get(
                "/mods/search",
                &[
                    ("gameId", GAME_MINECRAFT.to_string()),
                    ("slug", id_or_slug.to_owned()),
                ],
            )
            .await?;
        let mut found: Vec<Mod> = page.data.into_iter().filter(|m| m.slug == id_or_slug).collect();
        found.sort_by_key(|m| match m.kind() {
            Some(ContentType::Mod) => 0,
            Some(_) => 1,
            None => 2,
        });
        found
            .into_iter()
            .next()
            .ok_or_else(|| Error::Other(format!("CurseForge project not found: {id_or_slug}")))
    }

    pub async fn mods(&self, ids: &[u64]) -> Result<Vec<Mod>> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        Ok(self
            .post::<Data<Vec<Mod>>>("/mods", json!({ "modIds": ids, "filterPcOnly": true }))
            .await?
            .data)
    }

    pub async fn files(&self, ids: &[u64]) -> Result<Vec<File>> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        Ok(self
            .post::<Data<Vec<File>>>("/mods/files", json!({ "fileIds": ids }))
            .await?
            .data)
    }

    /// Files of a project, newest first, filtered like the CurseForge website does.
    pub async fn mod_files(
        &self,
        mod_id: u64,
        game_version: Option<&str>,
        loader: Option<LoaderKind>,
    ) -> Result<Vec<File>> {
        let mut query = vec![("pageSize", "50".to_owned())];
        if let Some(v) = game_version {
            query.push(("gameVersion", v.to_owned()));
        }
        if let Some(l) = loader {
            query.push(("modLoaderType", loader_type(l).to_string()));
        }
        let page: Paged<File> = self.get(&format!("/mods/{mod_id}/files"), &query).await?;
        let mut files = page.data;
        files.sort_by(|a, b| b.file_date.cmp(&a.file_date));
        Ok(files)
    }

    /// Exact file matches for the given fingerprints, keyed by fingerprint.
    pub async fn files_by_fingerprint(&self, fingerprints: &[u32]) -> Result<HashMap<u32, File>> {
        if fingerprints.is_empty() {
            return Ok(HashMap::new());
        }
        let matches: Data<FingerprintMatches> = self
            .post(
                &format!("/fingerprints/{GAME_MINECRAFT}"),
                json!({ "fingerprints": fingerprints }),
            )
            .await?;
        Ok(matches
            .data
            .exact_matches
            .into_iter()
            .map(|m| (m.file.file_fingerprint as u32, m.file))
            .collect())
    }
}

/// Prefers releases, then betas, then alphas; newest first within each.
pub fn pick_best(files: Vec<File>) -> Option<File> {
    let best = files.iter().map(|f| f.release_type).min()?;
    files.into_iter().find(|f| f.release_type == best)
}

/// Whether a file lists `loader` (or no loader at all, e.g. resource packs).
pub fn supports_loader(file: &File, loaders: &[LoaderKind]) -> bool {
    let tags: Vec<LoaderKind> = file
        .game_versions
        .iter()
        .filter_map(|v| v.parse::<LoaderKind>().ok())
        .collect();
    tags.is_empty() || tags.iter().any(|t| loaders.contains(t))
}

/// CurseForge's file fingerprint: MurmurHash2 (seed 1) over the file with
/// tab, newline, carriage return and space bytes removed.
pub fn fingerprint(bytes: &[u8]) -> u32 {
    let data: Vec<u8> = bytes
        .iter()
        .copied()
        .filter(|b| !matches!(b, 9 | 10 | 13 | 32))
        .collect();
    murmur2(&data, 1)
}

fn murmur2(data: &[u8], seed: u32) -> u32 {
    const M: u32 = 0x5bd1_e995;
    let mut h = seed ^ data.len() as u32;
    let (chunks, tail) = data.as_chunks::<4>();
    for chunk in chunks {
        let mut k = u32::from_le_bytes(*chunk);
        k = k.wrapping_mul(M);
        k ^= k >> 24;
        k = k.wrapping_mul(M);
        h = h.wrapping_mul(M) ^ k;
    }
    if tail.len() >= 3 {
        h ^= (tail[2] as u32) << 16;
    }
    if tail.len() >= 2 {
        h ^= (tail[1] as u32) << 8;
    }
    if !tail.is_empty() {
        h ^= tail[0] as u32;
        h = h.wrapping_mul(M);
    }
    h ^= h >> 13;
    h = h.wrapping_mul(M);
    h ^= h >> 15;
    h
}

pub async fn fingerprint_file(path: &std::path::Path) -> Result<u32> {
    let bytes = tokio::fs::read(path).await?;
    Ok(tokio::task::spawn_blocking(move || fingerprint(&bytes)).await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn murmur2_reference_values() {
        // Reference: Austin Appleby's MurmurHash2, 32-bit.
        assert_eq!(murmur2(b"", 0), 0);
        assert_eq!(murmur2(b"", 1), 0x5bd1_5e36);
        assert_eq!(murmur2(b"abc", 0), 0x1357_7c9b);
        assert_eq!(murmur2(b"abc", 1), 0x60a4_fcc1);
        assert_eq!(murmur2(b"The quick brown fox jumps over the lazy dog", 1), 0x1e10_49e7);
    }

    /// Live check against a fingerprint from the API:
    /// `DACLIENT_FP_FILE=<jar> DACLIENT_FP_EXPECT=<n> cargo test -- --ignored fingerprint_matches`
    #[test]
    #[ignore]
    fn fingerprint_matches_curseforge() {
        let file = std::env::var("DACLIENT_FP_FILE").expect("DACLIENT_FP_FILE");
        let expected: u32 = std::env::var("DACLIENT_FP_EXPECT").expect("DACLIENT_FP_EXPECT").parse().unwrap();
        assert_eq!(fingerprint(&std::fs::read(file).unwrap()), expected);
    }

    /// Live lookup by fingerprint (needs `DACLIENT_CURSEFORGE_API_KEY` and `DACLIENT_FP_FILE`).
    #[tokio::test]
    #[ignore]
    async fn fingerprint_lookup_live() {
        let cf = CurseForge::new(std::env::var("DACLIENT_CURSEFORGE_API_KEY").unwrap());
        let file = std::env::var("DACLIENT_FP_FILE").expect("DACLIENT_FP_FILE");
        let print = fingerprint_file(std::path::Path::new(&file)).await.unwrap();
        let found = cf.files_by_fingerprint(&[print]).await.unwrap();
        let hit = found.get(&print).expect("CurseForge knows the file");
        assert!(file.ends_with(&hit.file_name), "{} vs {file}", hit.file_name);
        let mods = cf.mods(&[hit.mod_id]).await.unwrap();
        assert_eq!(mods.len(), 1);
    }

    #[test]
    fn fingerprint_ignores_whitespace() {
        assert_eq!(fingerprint(b"a b\tc\r\nd"), fingerprint(b"abcd"));
        assert_ne!(fingerprint(b"abcd"), fingerprint(b"abce"));
    }

    #[test]
    fn picks_release_over_newer_beta() {
        let file = |id, release_type| File {
            id,
            mod_id: 1,
            display_name: String::new(),
            file_name: String::new(),
            release_type,
            file_date: String::new(),
            file_length: 0,
            download_url: None,
            game_versions: vec![],
            dependencies: vec![],
            hashes: vec![],
            file_fingerprint: 0,
        };
        let best = pick_best(vec![file(3, 2), file(2, 1), file(1, 1)]).unwrap();
        assert_eq!(best.id, 2);
    }
}
