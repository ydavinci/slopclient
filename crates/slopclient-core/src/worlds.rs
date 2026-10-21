//! Worlds of an instance (`saves/`) and their datapacks.
//!
//! The world list reads name, mode and last play time from `level.dat` (gzipped
//! NBT); only those few fields are parsed, everything else is skipped.

use std::{
    io::Read,
    path::{Path, PathBuf},
};

use serde::Serialize;

use crate::{Error, Paths, Result, content::DISABLED_SUFFIX, instance::Instance, nbt::Nbt};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct World {
    /// Folder name inside `saves/`.
    pub id: String,
    pub name: String,
    /// survival, creative, adventure, spectator
    pub game_mode: Option<String>,
    pub hardcore: bool,
    /// Minecraft version that last saved the world.
    pub version: Option<String>,
    /// Unix seconds.
    pub last_played: Option<u64>,
    pub size_bytes: u64,
    pub datapacks: usize,
    /// `icon.png` as a data URL.
    pub icon: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Datapack {
    /// File or folder name without the `.disabled` suffix.
    pub file_name: String,
    pub enabled: bool,
    pub folder: bool,
    pub description: Option<String>,
}

fn saves(paths: &Paths, instance: &Instance) -> PathBuf {
    instance.game_dir(paths).join("saves")
}

/// A world folder, refusing names that would leave `saves/`.
fn world_dir(paths: &Paths, instance: &Instance, id: &str) -> Result<PathBuf> {
    let safe = Path::new(id).file_name().is_some_and(|n| n == id) && !id.starts_with('.');
    let dir = saves(paths, instance).join(id);
    if !safe || !dir.join("level.dat").exists() {
        return Err(Error::Other(format!("Welt {id} gibt es nicht")));
    }
    Ok(dir)
}

pub fn list(paths: &Paths, instance: &Instance) -> Result<Vec<World>> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(saves(paths, instance)) else {
        return Ok(out);
    };
    for entry in entries.flatten() {
        let dir = entry.path();
        let Some(id) = entry.file_name().to_str().map(str::to_owned) else { continue };
        if !dir.join("level.dat").exists() {
            continue;
        }
        let level = read_level(&dir.join("level.dat")).unwrap_or_default();
        let icon = std::fs::read(dir.join("icon.png")).ok().map(|b| {
            use base64::Engine;
            format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(b))
        });
        let last_played = level.last_played.map(|ms| (ms / 1000) as u64).or_else(|| {
            let modified = std::fs::metadata(dir.join("level.dat")).ok()?.modified().ok()?;
            modified.duration_since(std::time::UNIX_EPOCH).ok().map(|d| d.as_secs())
        });
        out.push(World {
            name: level.name.filter(|n| !n.trim().is_empty()).unwrap_or_else(|| id.clone()),
            game_mode: level.game_type.map(|t| {
                match t {
                    1 => "creative",
                    2 => "adventure",
                    3 => "spectator",
                    _ => "survival",
                }
                .to_owned()
            }),
            hardcore: level.hardcore,
            version: level.version,
            last_played,
            size_bytes: dir_size(&dir),
            datapacks: datapacks_in(&dir.join("datapacks")).len(),
            icon,
            id,
        });
    }
    out.sort_by_key(|w| std::cmp::Reverse(w.last_played.unwrap_or(0)));
    Ok(out)
}

fn dir_size(dir: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
    entries
        .flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => dir_size(&e.path()),
            Ok(_) => e.metadata().map_or(0, |m| m.len()),
            Err(_) => 0,
        })
        .sum()
}

/// Deletes a world for good.
pub fn delete(paths: &Paths, instance: &Instance, id: &str) -> Result<()> {
    std::fs::remove_dir_all(world_dir(paths, instance, id)?)?;
    Ok(())
}

/// Zips a world into `instances/<id>/backups/` and returns the file.
pub fn backup(paths: &Paths, instance: &Instance, id: &str) -> Result<PathBuf> {
    let dir = world_dir(paths, instance, id)?;
    let backups = instance.dir(paths).join("backups");
    std::fs::create_dir_all(&backups)?;
    let stamp = crate::instance::now();
    let target = backups.join(format!("{id}-{stamp}.zip"));
    let file = std::fs::File::create(&target)?;
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    add_dir(&mut zip, &dir, id, options)?;
    zip.finish()?;
    Ok(target)
}

fn add_dir<W: std::io::Write + std::io::Seek>(
    zip: &mut zip::ZipWriter<W>,
    dir: &Path,
    prefix: &str,
    options: zip::write::SimpleFileOptions,
) -> Result<()> {
    for entry in std::fs::read_dir(dir)?.flatten() {
        let name = format!("{prefix}/{}", entry.file_name().to_string_lossy());
        let kind = entry.file_type()?;
        if kind.is_dir() {
            zip.add_directory(&name, options)?;
            add_dir(zip, &entry.path(), &name, options)?;
        } else if kind.is_file() {
            // The game holds session.lock open while the world is loaded.
            if entry.file_name() == "session.lock" {
                continue;
            }
            zip.start_file(&name, options)?;
            std::io::copy(&mut std::fs::File::open(entry.path())?, zip)?;
        }
    }
    Ok(())
}

// ---------- datapacks ----------

fn datapacks_in(dir: &Path) -> Vec<Datapack> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut out: Vec<Datapack> = entries
        .flatten()
        .filter_map(|e| {
            let raw = e.file_name().to_string_lossy().into_owned();
            let (file_name, enabled) = match raw.strip_suffix(DISABLED_SUFFIX) {
                Some(base) => (base.to_owned(), false),
                None => (raw, true),
            };
            let folder = e.file_type().is_ok_and(|t| t.is_dir());
            if !folder && !file_name.to_ascii_lowercase().ends_with(".zip") {
                return None;
            }
            let description = pack_description(&e.path(), folder);
            Some(Datapack { file_name, enabled, folder, description })
        })
        .collect();
    out.sort_by_key(|d| d.file_name.to_lowercase());
    out
}

/// `pack.description` from `pack.mcmeta`, when it is plain text.
fn pack_description(path: &Path, folder: bool) -> Option<String> {
    let text = if folder {
        std::fs::read_to_string(path.join("pack.mcmeta")).ok()?
    } else {
        let mut archive = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
        let mut s = String::new();
        archive.by_name("pack.mcmeta").ok()?.read_to_string(&mut s).ok()?;
        s
    };
    let json: serde_json::Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).ok()?;
    let desc = &json["pack"]["description"];
    desc.as_str()
        .map(str::to_owned)
        .or_else(|| desc["text"].as_str().map(str::to_owned))
        .map(|s| strip_formatting(&s))
        .filter(|s| !s.is_empty())
}

/// Removes `§x` colour codes.
pub(crate) fn strip_formatting(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
        } else {
            out.push(c);
        }
    }
    out.trim().to_owned()
}

pub fn datapacks(paths: &Paths, instance: &Instance, world: &str) -> Result<Vec<Datapack>> {
    Ok(datapacks_in(&world_dir(paths, instance, world)?.join("datapacks")))
}

/// Adds a datapack zip. Only archives with `pack.mcmeta` and a `data/` folder count.
pub fn add_datapack(paths: &Paths, instance: &Instance, world: &str, file_name: &str, bytes: &[u8]) -> Result<()> {
    let name = Path::new(file_name)
        .file_name()
        .and_then(|n| n.to_str())
        .filter(|n| n.to_ascii_lowercase().ends_with(".zip") && !n.starts_with('.'))
        .ok_or_else(|| Error::Other(format!("{file_name}: Datapacks sind .zip-Dateien")))?;
    let archive = zip::ZipArchive::new(std::io::Cursor::new(bytes))?;
    let names: Vec<&str> = archive.file_names().collect();
    if !names.contains(&"pack.mcmeta") || !names.iter().any(|n| n.starts_with("data/")) {
        return Err(Error::Other(format!("{name} ist kein Datapack (pack.mcmeta und data/ fehlen)")));
    }
    let dir = world_dir(paths, instance, world)?.join("datapacks");
    if dir.join(name).exists() || dir.join(format!("{name}{DISABLED_SUFFIX}")).exists() {
        return Err(Error::Other(format!("{name} ist schon in dieser Welt")));
    }
    std::fs::create_dir_all(&dir)?;
    std::fs::write(dir.join(name), bytes)?;
    Ok(())
}

fn datapack_path(paths: &Paths, instance: &Instance, world: &str, file_name: &str) -> Result<(PathBuf, bool)> {
    if Path::new(file_name).file_name().is_none_or(|n| n != file_name) {
        return Err(Error::Other(format!("ungültiger Name: {file_name}")));
    }
    let dir = world_dir(paths, instance, world)?.join("datapacks");
    let on = dir.join(file_name);
    if on.exists() {
        return Ok((on, true));
    }
    let off = dir.join(format!("{file_name}{DISABLED_SUFFIX}"));
    if off.exists() {
        return Ok((off, false));
    }
    Err(Error::Other(format!("Datapack {file_name} gibt es nicht")))
}

pub fn set_datapack_enabled(paths: &Paths, instance: &Instance, world: &str, file_name: &str, enabled: bool) -> Result<()> {
    let (path, is_on) = datapack_path(paths, instance, world, file_name)?;
    if is_on != enabled {
        let target = path.with_file_name(if enabled { file_name.to_owned() } else { format!("{file_name}{DISABLED_SUFFIX}") });
        std::fs::rename(path, target)?;
    }
    Ok(())
}

pub fn remove_datapack(paths: &Paths, instance: &Instance, world: &str, file_name: &str) -> Result<()> {
    let (path, _) = datapack_path(paths, instance, world, file_name)?;
    if path.is_dir() {
        std::fs::remove_dir_all(path)?;
    } else {
        std::fs::remove_file(path)?;
    }
    Ok(())
}

// ---------- level.dat ----------

#[derive(Debug, Default)]
struct Level {
    name: Option<String>,
    last_played: Option<i64>,
    game_type: Option<i32>,
    hardcore: bool,
    version: Option<String>,
}

fn read_level(path: &Path) -> Option<Level> {
    let mut data = Vec::new();
    flate2::read::GzDecoder::new(std::fs::File::open(path).ok()?)
        .take(64 * 1024 * 1024)
        .read_to_end(&mut data)
        .ok()?;
    let mut r = Nbt { data: &data, pos: 0 };
    // Root: a named compound, inside it the "Data" compound.
    if r.u8()? != 10 {
        return None;
    }
    r.string()?;
    let mut level = Level::default();
    r.compound(&mut |r, tag, name, path| {
        match (path, name, tag) {
            ("Data", "LevelName", 8) => level.name = Some(r.string()?),
            ("Data", "LastPlayed", 4) => level.last_played = Some(r.i64()?),
            ("Data", "GameType", 3) => level.game_type = Some(r.i32()?),
            ("Data", "hardcore", 1) => level.hardcore = r.u8()? != 0,
            ("Data/Version", "Name", 8) => level.version = Some(r.string()?),
            _ => return Some(false),
        }
        Some(true)
    }, "")?;
    Some(level)
}


#[cfg(test)]
mod tests {
    use super::*;

    fn s(out: &mut Vec<u8>, v: &str) {
        out.extend((v.len() as u16).to_be_bytes());
        out.extend(v.as_bytes());
    }

    #[test]
    fn reads_level_fields() {
        let mut nbt = vec![10];
        s(&mut nbt, "");
        nbt.push(10);
        s(&mut nbt, "Data");
        // A list and an int array the reader has to skip.
        nbt.push(9);
        s(&mut nbt, "Players");
        nbt.extend([3, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 2]);
        nbt.push(11);
        s(&mut nbt, "Ids");
        nbt.extend([0, 0, 0, 1, 0, 0, 0, 7]);
        nbt.push(8);
        s(&mut nbt, "LevelName");
        s(&mut nbt, "Meine Welt");
        nbt.push(3);
        s(&mut nbt, "GameType");
        nbt.extend(1i32.to_be_bytes());
        nbt.push(4);
        s(&mut nbt, "LastPlayed");
        nbt.extend(1_700_000_000_000i64.to_be_bytes());
        nbt.push(10);
        s(&mut nbt, "Version");
        nbt.push(8);
        s(&mut nbt, "Name");
        s(&mut nbt, "26.3");
        nbt.push(0);
        nbt.extend([0, 0]);

        let dir = std::env::temp_dir().join(format!("slopclient-nbt-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("level.dat");
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        std::io::Write::write_all(&mut gz, &nbt).unwrap();
        std::fs::write(&file, gz.finish().unwrap()).unwrap();

        let level = read_level(&file).unwrap();
        assert_eq!(level.name.as_deref(), Some("Meine Welt"));
        assert_eq!(level.game_type, Some(1));
        assert_eq!(level.last_played, Some(1_700_000_000_000));
        assert_eq!(level.version.as_deref(), Some("26.3"));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn strips_colour_codes() {
        assert_eq!(strip_formatting("§aGrün §lfett"), "Grün fett");
    }
}
