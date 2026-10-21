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
    /// `modrinth` or `curseforge`; Modrinth's API doesn't send it.
    #[serde(default = "modrinth_source")]
    pub source: String,
}

fn modrinth_source() -> String {
    "modrinth".into()
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

/// Changelog of one version (Markdown); empty when the author wrote none.
pub async fn changelog(version_id: &str) -> Result<String> {
    let v: serde_json::Value = http::client()
        .get(format!("{API}/version/{version_id}"))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(v["changelog"].as_str().unwrap_or("").to_owned())
}

/// The full project for the launcher's project page.
pub async fn details(id_or_slug: &str) -> Result<super::ProjectDetails> {
    let p: serde_json::Value = http::client()
        .get(format!("{API}/project/{id_or_slug}"))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let s = |k: &str| p[k].as_str().map(str::to_owned);
    let id = s("id").unwrap_or_default();
    // The owner is only listed with the team.
    let author = match http::client().get(format!("{API}/project/{id}/members")).send().await {
        Ok(r) => r.json::<serde_json::Value>().await.ok().and_then(|team| {
            let members = team.as_array()?;
            let owner = members.iter().find(|m| m["role"] == "Owner").or(members.first())?;
            owner["user"]["username"].as_str().map(str::to_owned)
        }),
        Err(_) => None,
    };
    let mut links = Vec::new();
    for (label, key) in [("Quellcode", "source_url"), ("Fehler melden", "issues_url"), ("Wiki", "wiki_url"), ("Discord", "discord_url")] {
        if let Some(url) = s(key).filter(|u| !u.is_empty()) {
            links.push((label.to_owned(), url));
        }
    }
    let kind = s("project_type").unwrap_or_else(|| "mod".into());
    let slug = s("slug").unwrap_or_default();
    Ok(super::ProjectDetails {
        source: "modrinth",
        page_url: format!("https://modrinth.com/{kind}/{slug}"),
        id,
        slug,
        title: s("title").unwrap_or_default(),
        summary: s("description").unwrap_or_default(),
        body: s("body").unwrap_or_default(),
        body_format: "markdown",
        icon_url: s("icon_url"),
        author,
        downloads: p["downloads"].as_u64().unwrap_or(0),
        followers: p["followers"].as_u64(),
        categories: p["categories"].as_array().map(|a| a.iter().filter_map(|c| c.as_str().map(str::to_owned)).collect()).unwrap_or_default(),
        license: [&p["license"]["name"], &p["license"]["id"]]
            .into_iter()
            .filter_map(|v| v.as_str())
            .find(|l| !l.is_empty())
            .map(str::to_owned),
        updated: s("updated"),
        links,
        gallery: p["gallery"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|g| {
                        Some(super::GalleryImage { url: g["url"].as_str()?.to_owned(), title: g["title"].as_str().map(str::to_owned) })
                    })
                    .collect()
            })
            .unwrap_or_default(),
    })
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
