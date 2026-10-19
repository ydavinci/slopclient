//! Screenshots of an instance (`screenshots/`), with cached thumbnails.

use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::{Error, Paths, Result, instance::Instance};

/// Longest side of a thumbnail.
const THUMB: u32 = 360;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Screenshot {
    pub file_name: String,
    pub path: String,
    /// Unix seconds.
    pub taken_at: u64,
    pub size_bytes: u64,
}

fn folder(paths: &Paths, instance: &Instance) -> PathBuf {
    instance.game_dir(paths).join("screenshots")
}

fn file(paths: &Paths, instance: &Instance, name: &str) -> Result<PathBuf> {
    let safe = Path::new(name).file_name().is_some_and(|n| n == name) && name.to_ascii_lowercase().ends_with(".png");
    let path = folder(paths, instance).join(name);
    if !safe || !path.is_file() {
        return Err(Error::Other(format!("Screenshot {name} gibt es nicht")));
    }
    Ok(path)
}

/// Newest first.
pub fn list(paths: &Paths, instance: &Instance) -> Result<Vec<Screenshot>> {
    let Ok(entries) = std::fs::read_dir(folder(paths, instance)) else {
        return Ok(Vec::new());
    };
    let mut out: Vec<Screenshot> = entries
        .flatten()
        .filter_map(|e| {
            let file_name = e.file_name().to_str()?.to_owned();
            if !file_name.to_ascii_lowercase().ends_with(".png") {
                return None;
            }
            let meta = e.metadata().ok()?;
            let taken_at = meta
                .modified()
                .ok()?
                .duration_since(std::time::UNIX_EPOCH)
                .ok()?
                .as_secs();
            Some(Screenshot {
                path: e.path().display().to_string(),
                file_name,
                taken_at,
                size_bytes: meta.len(),
            })
        })
        .collect();
    out.sort_by_key(|s| std::cmp::Reverse(s.taken_at));
    Ok(out)
}

/// A small JPEG of the screenshot, made once and kept in `instances/<id>/.thumbs/`.
pub fn thumbnail(paths: &Paths, instance: &Instance, name: &str) -> Result<Vec<u8>> {
    let source = file(paths, instance, name)?;
    let cache = instance.dir(paths).join(".thumbs").join(format!("{name}.jpg"));
    let fresh = |c: &Path| -> Option<bool> {
        Some(std::fs::metadata(c).ok()?.modified().ok()? >= std::fs::metadata(&source).ok()?.modified().ok()?)
    };
    if fresh(&cache) == Some(true)
        && let Ok(bytes) = std::fs::read(&cache)
    {
        return Ok(bytes);
    }
    let img = image::open(&source).map_err(|e| Error::Other(format!("{name}: {e}")))?;
    let thumb = img.thumbnail(THUMB, THUMB).to_rgb8();
    let mut bytes = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, 82)
        .encode_image(&thumb)
        .map_err(|e| Error::Other(e.to_string()))?;
    if let Some(dir) = cache.parent() {
        std::fs::create_dir_all(dir)?;
        let _ = std::fs::write(&cache, &bytes);
    }
    Ok(bytes)
}

pub fn delete(paths: &Paths, instance: &Instance, name: &str) -> Result<()> {
    std::fs::remove_file(file(paths, instance, name)?)?;
    let _ = std::fs::remove_file(instance.dir(paths).join(".thumbs").join(format!("{name}.jpg")));
    Ok(())
}

/// Full path, for opening in the image viewer.
pub fn path(paths: &Paths, instance: &Instance, name: &str) -> Result<PathBuf> {
    file(paths, instance, name)
}
