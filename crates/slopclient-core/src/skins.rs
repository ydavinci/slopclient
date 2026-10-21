//! Skin library (`skins/`) and uploading a skin to the Minecraft account.
//!
//! Skins are kept as the PNG the user picked plus an entry in `skins/index.json`.
//! Applying one uploads it through the Minecraft services API, the same call the
//! official launcher makes.

use std::path::PathBuf;

use base64::Engine;
use serde::{Deserialize, Serialize};

use crate::{
    Error, Paths, Result,
    auth::AccountStore,
    http,
    instance::now,
};

const SKINS_URL: &str = "https://api.minecraftservices.com/minecraft/profile/skins";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Variant {
    /// Steve arms (4 px).
    Classic,
    /// Alex arms (3 px).
    Slim,
}

impl Variant {
    fn api(self) -> &'static str {
        match self {
            Self::Classic => "classic",
            Self::Slim => "slim",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkinEntry {
    pub id: String,
    pub name: String,
    pub variant: Variant,
    pub added_at: u64,
}

/// A library entry with its texture, for the UI.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Skin {
    #[serde(flatten)]
    pub entry: SkinEntry,
    /// The PNG as a data URL.
    pub texture: String,
}

fn dir(paths: &Paths) -> PathBuf {
    paths.root.join("skins")
}

fn read_index(paths: &Paths) -> Result<Vec<SkinEntry>> {
    match std::fs::read_to_string(dir(paths).join("index.json")) {
        Ok(text) => Ok(serde_json::from_str(&text)?),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(e.into()),
    }
}

fn write_index(paths: &Paths, entries: &[SkinEntry]) -> Result<()> {
    std::fs::create_dir_all(dir(paths))?;
    std::fs::write(dir(paths).join("index.json"), serde_json::to_string_pretty(entries)?)?;
    Ok(())
}

fn file(paths: &Paths, id: &str) -> Result<PathBuf> {
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        return Err(Error::Other(format!("ungültige Skin-ID {id}")));
    }
    Ok(dir(paths).join(format!("{id}.png")))
}

/// Newest first.
pub fn list(paths: &Paths) -> Result<Vec<Skin>> {
    let mut out = Vec::new();
    for entry in read_index(paths)?.into_iter().rev() {
        let Ok(bytes) = std::fs::read(file(paths, &entry.id)?) else { continue };
        out.push(Skin {
            texture: format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes)),
            entry,
        });
    }
    Ok(out)
}

/// Checks that `bytes` is a Minecraft skin: a PNG of 64×64 or legacy 64×32.
fn validate(bytes: &[u8]) -> Result<image::RgbaImage> {
    let img = image::load_from_memory_with_format(bytes, image::ImageFormat::Png)
        .map_err(|_| Error::Other("Das ist kein PNG-Bild.".into()))?
        .to_rgba8();
    match img.dimensions() {
        (64, 64) | (64, 32) => Ok(img),
        (w, h) => Err(Error::Other(format!("Ein Skin ist 64×64 Pixel groß, dieses Bild hat {w}×{h}."))),
    }
}

/// Slim skins leave the outer arm column transparent.
pub fn guess_variant(bytes: &[u8]) -> Variant {
    match validate(bytes) {
        Ok(img) if img.height() == 64 && (20..32).all(|y| img.get_pixel(54, y)[3] == 0) => Variant::Slim,
        _ => Variant::Classic,
    }
}

pub fn add(paths: &Paths, name: &str, variant: Option<Variant>, bytes: &[u8]) -> Result<SkinEntry> {
    validate(bytes)?;
    let mut entries = read_index(paths)?;
    let id = format!("{:x}-{:04x}", now(), entries.len() as u16 ^ (bytes.len() as u16));
    let name = name.trim().trim_end_matches(".png").trim();
    let entry = SkinEntry {
        id: id.clone(),
        name: if name.is_empty() { "Skin".into() } else { name.chars().take(48).collect() },
        variant: variant.unwrap_or_else(|| guess_variant(bytes)),
        added_at: now(),
    };
    std::fs::create_dir_all(dir(paths))?;
    std::fs::write(file(paths, &id)?, bytes)?;
    entries.push(entry.clone());
    write_index(paths, &entries)?;
    Ok(entry)
}

pub fn remove(paths: &Paths, id: &str) -> Result<()> {
    let mut entries = read_index(paths)?;
    entries.retain(|e| e.id != id);
    write_index(paths, &entries)?;
    let _ = std::fs::remove_file(file(paths, id)?);
    Ok(())
}

pub fn set_variant(paths: &Paths, id: &str, variant: Variant) -> Result<()> {
    let mut entries = read_index(paths)?;
    let entry = entries.iter_mut().find(|e| e.id == id).ok_or_else(|| Error::Other("Skin nicht gefunden".into()))?;
    entry.variant = variant;
    write_index(paths, &entries)
}

#[derive(Deserialize)]
struct ProfileSkins {
    #[serde(default)]
    skins: Vec<ProfileSkin>,
}

#[derive(Deserialize)]
struct ProfileSkin {
    state: String,
    url: String,
}

/// Uploads a library skin to the account and returns the new skin URL.
pub async fn apply(paths: &Paths, client_id: &str, account_uuid: &str, id: &str) -> Result<String> {
    let entry = read_index(paths)?
        .into_iter()
        .find(|e| e.id == id)
        .ok_or_else(|| Error::Other("Skin nicht gefunden".into()))?;
    let bytes = std::fs::read(file(paths, id)?)?;
    let store = AccountStore::new(paths);
    let auth = store.launch_auth(client_id, account_uuid).await?;

    let form = reqwest::multipart::Form::new().text("variant", entry.variant.api()).part(
        "file",
        reqwest::multipart::Part::bytes(bytes).file_name("skin.png").mime_str("image/png")?,
    );
    let response = http::client().post(SKINS_URL).bearer_auth(&auth.access_token).multipart(form).send().await?;
    if response.status() == reqwest::StatusCode::TOO_MANY_REQUESTS {
        return Err(Error::Other("Zu viele Skin-Wechsel in kurzer Zeit. Versuch es in einer Minute noch mal.".into()));
    }
    if !response.status().is_success() {
        let status = response.status();
        let text = response.text().await.unwrap_or_default();
        return Err(Error::Other(format!("Minecraft hat den Skin abgelehnt ({status}): {}", text.chars().take(200).collect::<String>())));
    }
    let profile: ProfileSkins = response.json().await?;
    let url = profile
        .skins
        .into_iter()
        .find(|s| s.state == "ACTIVE")
        .map(|s| s.url)
        .ok_or_else(|| Error::Other("Antwort ohne aktiven Skin".into()))?;
    store.set_skin_url(account_uuid, &url)?;
    Ok(url)
}

/// Saves the account's current skin into the library.
pub async fn import_current(paths: &Paths, skin_url: &str, name: &str) -> Result<SkinEntry> {
    // Mojang serves skins over http; the texture host also answers on https.
    let url = skin_url.replacen("http://", "https://", 1);
    let bytes = http::client().get(url).send().await?.error_for_status()?.bytes().await?;
    add(paths, name, None, &bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(img: &image::RgbaImage) -> Vec<u8> {
        let mut out = std::io::Cursor::new(Vec::new());
        img.write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    #[test]
    fn guesses_slim_arms() {
        let mut classic = image::RgbaImage::from_pixel(64, 64, image::Rgba([200, 150, 120, 255]));
        assert_eq!(guess_variant(&png(&classic)), Variant::Classic);
        for y in 16..32 {
            for x in 54..56 {
                classic.put_pixel(x, y, image::Rgba([0, 0, 0, 0]));
            }
        }
        assert_eq!(guess_variant(&png(&classic)), Variant::Slim);
    }

    #[test]
    fn rejects_wrong_sizes() {
        let img = image::RgbaImage::new(32, 32);
        assert!(validate(&png(&img)).is_err());
    }
}
