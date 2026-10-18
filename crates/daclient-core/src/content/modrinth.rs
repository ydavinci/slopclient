//! Modrinth API v2 client (no API key needed).

use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::{Error, Result, http};

const API: &str = "https://api.modrinth.com/v2";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResult {
    pub hits: Vec<SearchHit>,
    pub total_hits: u64,
    pub offset: u64,
    pub limit: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub project_type: String,
    pub downloads: u64,
    pub follows: u64,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub categories: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub project_type: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub loaders: Vec<String>,
    #[serde(default)]
    pub game_versions: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Version {
    pub id: String,
    pub project_id: String,
    pub name: String,
    pub version_number: String,
    pub version_type: String,
    #[serde(default)]
    pub game_versions: Vec<String>,
    #[serde(default)]
    pub loaders: Vec<String>,
    pub files: Vec<VersionFile>,
    #[serde(default)]
    pub dependencies: Vec<Dependency>,
    pub date_published: String,
}

impl Version {
    /// The file to install: the one marked primary, else the first.
    pub fn primary_file(&self) -> Option<&VersionFile> {
        self.files.iter().find(|f| f.primary).or(self.files.first())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionFile {
    pub hashes: Hashes,
    pub url: String,
    pub filename: String,
    #[serde(default)]
    pub primary: bool,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Hashes {
    pub sha1: String,
    #[serde(default)]
    pub sha512: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Dependency {
    #[serde(default)]
    pub version_id: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub file_name: Option<String>,
    /// `required`, `optional`, `incompatible` or `embedded`.
    pub dependency_type: String,
}

/// Search filters; empty fields are not applied.
#[derive(Debug, Clone, Default)]
pub struct SearchQuery {
    pub query: String,
    /// `mod`, `resourcepack`, `shader`, `modpack`, `datapack`.
    pub project_type: Option<String>,
    pub game_version: Option<String>,
    /// Any of these loaders (OR).
    pub loaders: Vec<String>,
    pub categories: Vec<String>,
    /// `relevance`, `downloads`, `follows`, `newest`, `updated`.
    pub sort: Option<String>,
    pub offset: u64,
    pub limit: u64,
}

pub async fn search(q: &SearchQuery) -> Result<SearchResult> {
    let mut facets: Vec<Vec<String>> = Vec::new();
    if let Some(t) = &q.project_type {
        facets.push(vec![format!("project_type:{t}")]);
    }
    if let Some(v) = &q.game_version {
        facets.push(vec![format!("versions:{v}")]);
    }
    if !q.loaders.is_empty() {
        facets.push(q.loaders.iter().map(|l| format!("categories:{l}")).collect());
    }
    for c in &q.categories {
        facets.push(vec![format!("categories:{c}")]);
    }
    let limit = if q.limit == 0 { 20 } else { q.limit.min(100) };
    let mut request = http::client().get(format!("{API}/search")).query(&[
        ("query", q.query.as_str()),
        ("index", q.sort.as_deref().unwrap_or("relevance")),
        ("offset", &q.offset.to_string()),
        ("limit", &limit.to_string()),
    ]);
    if !facets.is_empty() {
        request = request.query(&[("facets", serde_json::to_string(&facets)?)]);
    }
    Ok(request.send().await?.error_for_status()?.json().await?)
}

/// Project by id or slug.
pub async fn project(id_or_slug: &str) -> Result<Project> {
    let response = http::client()
        .get(format!("{API}/project/{id_or_slug}"))
        .send()
        .await?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(Error::Other(format!("Modrinth project not found: {id_or_slug}")));
    }
    Ok(response.error_for_status()?.json().await?)
}

pub async fn projects(ids: &[String]) -> Result<Vec<Project>> {
    if ids.is_empty() {
        return Ok(Vec::new());
    }
    Ok(http::client()
        .get(format!("{API}/projects"))
        .query(&[("ids", serde_json::to_string(ids)?)])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}

/// Versions of a project, newest first, optionally filtered.
pub async fn project_versions(
    project: &str,
    loaders: &[String],
    game_version: Option<&str>,
) -> Result<Vec<Version>> {
    let mut request = http::client()
        .get(format!("{API}/project/{project}/version"))
        .query(&[("include_changelog", "false")]);
    if !loaders.is_empty() {
        request = request.query(&[("loaders", serde_json::to_string(loaders)?)]);
    }
    if let Some(v) = game_version {
        request = request.query(&[("game_versions", serde_json::to_string(&[v])?)]);
    }
    Ok(request.send().await?.error_for_status()?.json().await?)
}

pub async fn version(id: &str) -> Result<Version> {
    http::get_json(&format!("{API}/version/{id}")).await
}

pub async fn versions(ids: &[String]) -> Result<Vec<Version>> {
    if ids.is_empty() {
        return Ok(Vec::new());
    }
    Ok(http::client()
        .get(format!("{API}/versions"))
        .query(&[("ids", serde_json::to_string(ids)?)])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}

/// Looks up versions by file SHA-1 (identifies files that were added manually).
pub async fn versions_by_hash(hashes: &[String]) -> Result<HashMap<String, Version>> {
    if hashes.is_empty() {
        return Ok(HashMap::new());
    }
    Ok(http::client()
        .post(format!("{API}/version_files"))
        .json(&json!({ "hashes": hashes, "algorithm": "sha1" }))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}

/// Newest compatible version for each file hash.
pub async fn latest_versions(
    hashes: &[String],
    loaders: &[String],
    game_version: &str,
) -> Result<HashMap<String, Version>> {
    if hashes.is_empty() {
        return Ok(HashMap::new());
    }
    let mut body = json!({ "hashes": hashes, "algorithm": "sha1", "game_versions": [game_version] });
    if !loaders.is_empty() {
        body["loaders"] = json!(loaders);
    }
    Ok(http::client()
        .post(format!("{API}/version_files/update"))
        .json(&body)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}

/// Prefers releases, then betas, then alphas; newest first within each.
pub fn pick_best(versions: Vec<Version>) -> Option<Version> {
    let rank = |v: &Version| match v.version_type.as_str() {
        "release" => 0,
        "beta" => 1,
        _ => 2,
    };
    let best_rank = versions.iter().map(rank).min()?;
    versions.into_iter().find(|v| rank(v) == best_rank)
}
