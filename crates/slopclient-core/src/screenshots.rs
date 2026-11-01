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

/// A screenshot with the instance it belongs to, for the gallery across all instances.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceScreenshot {
    pub instance_id: String,
    pub instance_name: String,
    #[serde(flatten)]
    pub shot: Screenshot,
}

/// Screenshots of every instance, newest first. Instances that cannot be read are left out.
pub fn list_all(paths: &Paths) -> Result<Vec<InstanceScreenshot>> {
    let mut out = Vec::new();
    for instance in Instance::list(paths)? {
        let Ok(shots) = list(paths, &instance) else { continue };
        out.extend(shots.into_iter().map(|shot| InstanceScreenshot {
            instance_id: instance.id.clone(),
            instance_name: instance.name.clone(),
            shot,
        }));
    }
    out.sort_by_key(|s| std::cmp::Reverse(s.shot.taken_at));
    Ok(out)
}

/// The PNG bytes of a screenshot, for the launcher's viewer.
pub fn read(paths: &Paths, instance: &Instance, name: &str) -> Result<Vec<u8>> {
    Ok(std::fs::read(file(paths, instance, name)?)?)
}

/// Undoes `encodeURIComponent` for one path part of a viewer URL (`%20`, `%C3%A4`, ...).
pub fn decode_url_part(s: &str) -> String {
    let hex = |b: u8| (b as char).to_digit(16).map(|d| d as u8);
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && let (Some(hi), Some(lo)) = (bytes.get(i + 1).and_then(|&b| hex(b)), bytes.get(i + 2).and_then(|&b| hex(b)))
        {
            out.push(hi << 4 | lo);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::Loader;

    fn temp_paths(tag: &str) -> Paths {
        let root = std::env::temp_dir().join(format!("slopclient-shots-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let paths = Paths::new(root);
        paths.ensure().unwrap();
        paths
    }

    fn shot(paths: &Paths, instance: &Instance, name: &str, age_secs: u64) {
        let dir = folder(paths, instance);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        image::RgbImage::new(4, 3).save(&path).unwrap();
        let when = std::time::SystemTime::now() - std::time::Duration::from_secs(age_secs);
        std::fs::File::options().write(true).open(&path).unwrap().set_modified(when).unwrap();
    }

    #[test]
    fn lists_all_instances_newest_first() {
        let paths = temp_paths("all");
        let a = Instance::create(&paths, "Alpha", "26.3", Loader::Vanilla).unwrap();
        let b = Instance::create(&paths, "Beta", "26.3", Loader::Vanilla).unwrap();
        shot(&paths, &a, "old.png", 300);
        shot(&paths, &b, "new.png", 10);
        shot(&paths, &a, "middle.png", 100);
        std::fs::write(folder(&paths, &a).join("notes.txt"), "no picture").unwrap();

        let all = list_all(&paths).unwrap();
        let names: Vec<_> = all.iter().map(|s| (s.instance_name.as_str(), s.shot.file_name.as_str())).collect();
        assert_eq!(names, [("Beta", "new.png"), ("Alpha", "middle.png"), ("Alpha", "old.png")]);
        assert_eq!(all[0].instance_id, b.id);

        let json = serde_json::to_value(&all[0]).unwrap();
        assert_eq!(json["fileName"], "new.png");
        assert_eq!(json["instanceName"], "Beta");
        let _ = std::fs::remove_dir_all(&paths.root);
    }

    #[test]
    fn decodes_url_parts() {
        assert_eq!(decode_url_part("2026-10-31_20.14.03.png"), "2026-10-31_20.14.03.png");
        assert_eq!(decode_url_part("Neue%20Welt%20%C3%A4"), "Neue Welt ä");
        assert_eq!(decode_url_part("100%"), "100%");
        assert_eq!(decode_url_part("%zz%4"), "%zz%4");
        assert_eq!(decode_url_part("..%2Fsecret.png"), "../secret.png");
    }

    #[test]
    fn read_refuses_paths_outside_the_folder() {
        let paths = temp_paths("read");
        let a = Instance::create(&paths, "Alpha", "26.3", Loader::Vanilla).unwrap();
        shot(&paths, &a, "pic.png", 0);
        assert!(read(&paths, &a, "pic.png").unwrap().starts_with(b"\x89PNG"));
        std::fs::write(a.dir(&paths).join("secret.png"), "x").unwrap();
        for bad in ["../secret.png", "..\\secret.png", "missing.png", "notes.txt", ""] {
            assert!(read(&paths, &a, bad).is_err(), "{bad} must be refused");
        }
        let _ = std::fs::remove_dir_all(&paths.root);
    }
}
