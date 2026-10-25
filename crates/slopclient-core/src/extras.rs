//! Hats and pets. The models live in `cosmetics/extras.json` at the repository root;
//! the client mod renders them, the launcher previews them, and the cosmetics
//! server stores which ones a player wears (checked against its list).
//!
//! The cosmetics server is the source of the catalogue: the launcher fetches it and the
//! pet textures from there (`refresh`) and keeps a copy in `cosmetics/catalogue/`, so new
//! hats and pets need no launcher update. The shipped catalogue is the fallback.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::{Error, Paths, Result, config::Settings, http};

/// The catalogue as shipped (and what the server hands out).
pub const CATALOGUE_JSON: &str = include_str!("../../../cosmetics/extras.json");

/// Limits for a catalogue from a server; the mod checks the same (Catalogue.java).
pub const MAX_ITEMS: usize = 256;
pub const MAX_BOXES: usize = 512;
pub const MAX_PARTS: usize = 64;
/// Largest coordinate, size or pivot of a box, in pixels.
pub const MAX_COORD: f64 = 64.0;
pub const MAX_TEXTURE_BYTES: usize = 512 * 1024;
pub const MAX_TEXTURE_SIZE: u32 = 512;
const MAX_CATALOGUE_BYTES: usize = 4 * 1024 * 1024;

#[derive(Debug, Deserialize)]
struct Entry {
    id: String,
}

#[derive(Debug, Deserialize)]
struct Catalogue {
    hats: Vec<Entry>,
    pets: Vec<Entry>,
}

fn parse(json: &str) -> Catalogue {
    serde_json::from_str(json).unwrap_or(Catalogue { hats: Vec::new(), pets: Vec::new() })
}

fn shipped() -> Catalogue {
    parse(CATALOGUE_JSON)
}

/// In the shipped catalogue (what the server accepts).
pub fn is_hat(id: &str) -> bool {
    shipped().hats.iter().any(|h| h.id == id)
}

pub fn is_pet(id: &str) -> bool {
    shipped().pets.iter().any(|p| p.id == id)
}

/// What the player wears; none means nothing.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Choice {
    pub hat: Option<String>,
    pub pet: Option<String>,
}

impl Choice {
    /// Drops ids that are not in the catalogue (e.g. from a newer client).
    pub fn checked(self) -> Self {
        Self {
            hat: self.hat.filter(|h| is_hat(h)),
            pet: self.pet.filter(|p| is_pet(p)),
        }
    }
}

pub fn current(paths: &Paths) -> Result<Choice> {
    let s = Settings::load(paths)?;
    Ok(Choice { hat: s.hat, pet: s.pet })
}

pub fn set(paths: &Paths, choice: &Choice) -> Result<()> {
    let c = parse(&catalogue_json(paths));
    let known_hat = |h: &str| c.hats.iter().any(|e| e.id == h);
    let known_pet = |p: &str| c.pets.iter().any(|e| e.id == p);
    if choice.hat.as_deref().is_some_and(|h| !known_hat(h)) || choice.pet.as_deref().is_some_and(|p| !known_pet(p)) {
        return Err(Error::Other("unbekannter Hut oder Begleiter".into()));
    }
    let mut s = Settings::load(paths)?;
    s.hat = choice.hat.clone();
    s.pet = choice.pet.clone();
    s.save(paths)
}

// ---------- checking a catalogue ----------

/// Ids, names and texture files of the shape the mod can read.
fn id_ok(id: &str) -> bool {
    !id.is_empty() && id.len() <= 40 && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

pub fn texture_name_ok(name: &str) -> bool {
    name.strip_suffix(".png").is_some_and(id_ok)
}

fn coord_ok(v: &Value) -> bool {
    v.as_f64().is_some_and(|f| f.is_finite() && f.abs() <= MAX_COORD)
}

fn coords_ok(v: &Value, n: usize) -> bool {
    v.as_array().is_some_and(|a| a.len() == n && a.iter().all(coord_ok))
}

/// Checks a catalogue from a server against the limits and returns the texture files it
/// names. A catalogue that fails is not used at all.
pub fn validate(json: &str) -> Result<Vec<String>> {
    let bad = |m: &str| Error::Other(format!("cosmetics catalogue: {m}"));
    let root: Value = serde_json::from_str(json).map_err(|_| bad("not JSON"))?;
    let list = |key: &str| root[key].as_array().cloned().ok_or_else(|| bad(&format!("{key} missing")));
    let (hats, pets) = (list("hats")?, list("pets")?);
    if hats.len() > MAX_ITEMS || pets.len() > MAX_ITEMS {
        return Err(bad("too many hats or pets"));
    }
    let mut ids = std::collections::HashSet::new();
    let mut textures = Vec::new();
    for item in hats.iter().chain(&pets) {
        let id = item["id"].as_str().filter(|i| id_ok(i)).ok_or_else(|| bad("invalid id"))?;
        if !ids.insert(id.to_owned()) {
            return Err(bad(&format!("{id} twice")));
        }
        if !item["name"].as_str().is_some_and(|n| !n.is_empty() && n.chars().count() <= 40) {
            return Err(bad(&format!("{id}: invalid name")));
        }
    }
    for hat in &hats {
        let boxes = hat["boxes"].as_array().ok_or_else(|| bad("hat without boxes"))?;
        let ok = boxes.len() <= MAX_BOXES
            && boxes.iter().all(|b| b.as_array().is_some_and(|a| (7..=8).contains(&a.len()) && a[..6].iter().all(coord_ok) && a[6].is_string()));
        if !ok {
            return Err(bad(&format!("{}: invalid boxes", hat["id"])));
        }
    }
    for pet in &pets {
        let id = &pet["id"];
        if !matches!(pet["tier"].as_str(), None | Some("shoulder" | "flying" | "walking")) {
            return Err(bad(&format!("{id}: unknown tier")));
        }
        if let Some(t) = pet.get("texture") {
            let t = t.as_str().filter(|t| texture_name_ok(t)).ok_or_else(|| bad(&format!("{id}: invalid texture name")))?;
            if !textures.iter().any(|x| x == t) {
                textures.push(t.to_owned());
            }
        }
        if let Some(size) = pet.get("textureSize") {
            let ok = size.as_array().is_some_and(|a| a.len() == 2 && a.iter().all(|v| v.as_u64().is_some_and(|n| (1..=u64::from(MAX_TEXTURE_SIZE)).contains(&n))));
            if !ok {
                return Err(bad(&format!("{id}: invalid texture size")));
            }
        }
        if pet.get("scale").is_some_and(|s| !s.as_f64().is_some_and(|s| s > 0.0 && s <= 4.0)) {
            return Err(bad(&format!("{id}: invalid scale")));
        }
        let parts = pet["parts"].as_array().map(Vec::as_slice).unwrap_or_default();
        let total: usize = parts.iter().map(|p| p["boxes"].as_array().map_or(0, Vec::len)).sum();
        let parts_ok = parts.len() <= MAX_PARTS
            && total <= MAX_BOXES
            && parts.iter().all(|p| {
                coords_ok(&p["pivot"], 3)
                    && p["boxes"].as_array().is_some_and(|bs| bs.iter().all(|b| coords_ok(&b["from"], 3) && coords_ok(&b["size"], 3)))
            });
        if !parts_ok {
            return Err(bad(&format!("{id}: invalid parts")));
        }
    }
    Ok(textures)
}

// ---------- the copy from the server ----------

fn cache_dir(paths: &Paths) -> PathBuf {
    paths.root.join("cosmetics").join("catalogue")
}

/// The catalogue to show: the server's copy when there is a valid one, else the shipped one.
pub fn catalogue_json(paths: &Paths) -> String {
    std::fs::read_to_string(cache_dir(paths).join("extras.json"))
        .ok()
        .filter(|json| validate(json).is_ok())
        .unwrap_or_else(|| CATALOGUE_JSON.to_owned())
}

/// A pet texture from the server's copy (PNG bytes), if there is one.
pub fn texture(paths: &Paths, name: &str) -> Option<Vec<u8>> {
    if !texture_name_ok(name) {
        return None;
    }
    std::fs::read(cache_dir(paths).join("pets").join(name)).ok()
}

/// Reads a response, refusing bodies larger than `max` bytes.
async fn body_limited(mut resp: reqwest::Response, max: usize) -> Result<Vec<u8>> {
    if resp.content_length().is_some_and(|n| n > max as u64) {
        return Err(Error::Other("cosmetics catalogue: file too large".into()));
    }
    let mut out = Vec::new();
    while let Some(chunk) = resp.chunk().await? {
        out.extend_from_slice(&chunk);
        if out.len() > max {
            return Err(Error::Other("cosmetics catalogue: file too large".into()));
        }
    }
    Ok(out)
}

fn texture_ok(png: &[u8]) -> bool {
    image::ImageReader::with_format(std::io::Cursor::new(png), image::ImageFormat::Png)
        .into_dimensions()
        .is_ok_and(|(w, h)| w <= MAX_TEXTURE_SIZE && h <= MAX_TEXTURE_SIZE)
}

/// Fetches the catalogue and pet textures from the cosmetics server when they changed
/// (ETag), checks them and replaces the local copy in one step. True when it changed.
pub async fn refresh(paths: &Paths) -> Result<bool> {
    let Some(server) = crate::cosmetics_online::server(paths) else {
        return Ok(false);
    };
    let dir = cache_dir(paths);
    let mut req = http::client().get(format!("{server}/v1/extras/catalogue")).timeout(std::time::Duration::from_secs(10));
    if let Ok(etag) = std::fs::read_to_string(dir.join("etag"))
        && dir.join("extras.json").is_file()
    {
        req = req.header(reqwest::header::IF_NONE_MATCH, etag.trim());
    }
    let resp = req.send().await?;
    if resp.status() == reqwest::StatusCode::NOT_MODIFIED {
        return Ok(false);
    }
    let resp = resp.error_for_status()?;
    let etag = resp.headers().get(reqwest::header::ETAG).and_then(|v| v.to_str().ok()).unwrap_or_default().to_owned();
    let json = String::from_utf8(body_limited(resp, MAX_CATALOGUE_BYTES).await?).map_err(|_| Error::Other("cosmetics catalogue: not UTF-8".into()))?;
    let textures = validate(&json)?;

    let fresh = dir.with_file_name("catalogue.new");
    let _ = std::fs::remove_dir_all(&fresh);
    std::fs::create_dir_all(fresh.join("pets"))?;
    for name in &textures {
        let resp = http::client()
            .get(format!("{server}/v1/extras/textures/{name}"))
            .timeout(std::time::Duration::from_secs(20))
            .send()
            .await?
            .error_for_status()?;
        let png = body_limited(resp, MAX_TEXTURE_BYTES).await?;
        if !texture_ok(&png) {
            return Err(Error::Other(format!("cosmetics catalogue: invalid texture {name}")));
        }
        std::fs::write(fresh.join("pets").join(name), png)?;
    }
    std::fs::write(fresh.join("extras.json"), &json)?;
    std::fs::write(fresh.join("etag"), &etag)?;
    replace_dir(&fresh, &dir)?;
    Ok(true)
}

fn replace_dir(fresh: &Path, dir: &Path) -> std::io::Result<()> {
    let old = dir.with_file_name("catalogue.old");
    let _ = std::fs::remove_dir_all(&old);
    if dir.exists() {
        std::fs::rename(dir, &old)?;
    }
    std::fs::rename(fresh, dir)?;
    let _ = std::fs::remove_dir_all(&old);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalogue_parses_and_has_unique_ids() {
        let c = shipped();
        assert!(!c.hats.is_empty() && !c.pets.is_empty());
        let mut ids: Vec<&str> = c.hats.iter().chain(&c.pets).map(|e| e.id.as_str()).collect();
        let n = ids.len();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), n);
        assert!(is_hat("zylinder") && is_pet("slime") && !is_hat("slime"));
    }

    #[test]
    fn shipped_catalogue_is_within_the_limits() {
        let textures = validate(CATALOGUE_JSON).unwrap();
        assert!(textures.iter().any(|t| t == "fuchs.png"));
    }

    #[test]
    fn bad_catalogues_are_refused() {
        assert!(validate("nope").is_err());
        assert!(validate(r#"{"hats":[],"pets":[{"id":"x","name":"X","texture":"../evil.png","parts":[]}]}"#).is_err());
        assert!(validate(r#"{"hats":[{"id":"Big","name":"X","boxes":[]}],"pets":[]}"#).is_err());
        assert!(validate(r##"{"hats":[{"id":"h","name":"X","boxes":[[0,0,0,1e9,1,1,"#fff"]]}],"pets":[]}"##).is_err());
        assert!(validate(r#"{"hats":[{"id":"h","name":"X","boxes":[]},{"id":"h","name":"Y","boxes":[]}],"pets":[]}"#).is_err());
        assert!(validate(r#"{"hats":[{"id":"h","name":"Hut","boxes":[[0,0,0,1,1,1,"accent"]]}],"pets":[]}"#).is_ok());
    }
}
