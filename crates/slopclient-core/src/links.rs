//! Hard link helpers: put a file in place as a link (or a copy where links are
//! not possible), tell whether two paths are the same file and count links.
//!
//! Hard links only work for files on the same volume (NTFS or a Unix file
//! system). FAT32/exFAT, network drives or an instance folder redirected to
//! another drive fall back to a copy.

use std::{
    fs,
    io::{self, Write},
    path::{Path, PathBuf},
};

/// How [`link_or_copy`] put the file in place.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Placed {
    Linked,
    /// Hard links are not possible there; the target is an independent copy.
    Copied,
}

/// Replaces `target` with a hard link to `source` (a copy if linking fails).
/// The target is swapped in by renaming, so it never goes missing.
pub fn link_or_copy(source: &Path, target: &Path) -> io::Result<Placed> {
    link_or_copy_with(source, target, |s, t| fs::hard_link(s, t))
}

pub(crate) fn link_or_copy_with(
    source: &Path,
    target: &Path,
    hard_link: impl Fn(&Path, &Path) -> io::Result<()>,
) -> io::Result<Placed> {
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = sibling(target, ".slink");
    let _ = fs::remove_file(&temp);
    let placed = match hard_link(source, &temp) {
        Ok(()) => Placed::Linked,
        Err(_) => {
            fs::copy(source, &temp)?;
            Placed::Copied
        }
    };
    if let Err(e) = fs::rename(&temp, target) {
        let _ = fs::remove_file(&temp);
        return Err(e);
    }
    Ok(placed)
}

/// Replaces `target` with an independent copy of its current content, so
/// that it no longer shares data with other links.
pub fn unlink_keep_content(target: &Path) -> io::Result<()> {
    let temp = sibling(target, ".tmp");
    fs::copy(target, &temp)?;
    if let Err(e) = fs::rename(&temp, target) {
        let _ = fs::remove_file(&temp);
        return Err(e);
    }
    Ok(())
}

/// Overwrites the content of `target` in place (creating it if needed). Unlike
/// writing a new file and renaming it, this keeps every hard link to it.
pub fn write_in_place(target: &Path, content: &[u8]) -> io::Result<()> {
    let mut file = fs::OpenOptions::new().write(true).create(true).truncate(true).open(target)?;
    file.write_all(content)?;
    file.sync_all()
}

/// Whether both paths point to the same file (one is a hard link of the other).
/// A missing path is never the same file.
pub fn same_file(a: &Path, b: &Path) -> io::Result<bool> {
    let (Some(a), Some(b)) = (identity(a)?, identity(b)?) else {
        return Ok(false);
    };
    Ok(a.volume == b.volume && a.index == b.index)
}

/// Number of names (hard links) the file has; 1 for an ordinary file.
pub fn link_count(path: &Path) -> io::Result<u64> {
    identity(path)?
        .map(|i| i.links)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, path.display().to_string()))
}

fn sibling(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    path.with_file_name(name)
}

struct Identity {
    volume: u64,
    index: u64,
    links: u64,
}

#[cfg(unix)]
fn identity(path: &Path) -> io::Result<Option<Identity>> {
    use std::os::unix::fs::MetadataExt;
    match fs::metadata(path) {
        Ok(m) => Ok(Some(Identity { volume: m.dev(), index: m.ino(), links: m.nlink() })),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e),
    }
}

#[cfg(windows)]
fn identity(path: &Path) -> io::Result<Option<Identity>> {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::Storage::FileSystem::{BY_HANDLE_FILE_INFORMATION, GetFileInformationByHandle};

    let file = match fs::File::open(path) {
        Ok(f) => f,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e),
    };
    // SAFETY: the handle belongs to `file`, which stays open for the call;
    // `info` is plain data that the call fills in.
    let info = unsafe {
        let mut info: BY_HANDLE_FILE_INFORMATION = std::mem::zeroed();
        if GetFileInformationByHandle(file.as_raw_handle() as _, &mut info) == 0 {
            return Err(io::Error::last_os_error());
        }
        info
    };
    Ok(Some(Identity {
        volume: info.dwVolumeSerialNumber as u64,
        index: ((info.nFileIndexHigh as u64) << 32) | info.nFileIndexLow as u64,
        links: info.nNumberOfLinks as u64,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("slopclient-links-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn link_shares_content_and_counts() {
        let dir = temp_dir("link");
        let (a, b) = (dir.join("a.txt"), dir.join("sub/b.txt"));
        fs::write(&a, "one").unwrap();
        assert_eq!(link_count(&a).unwrap(), 1);
        assert_eq!(link_or_copy(&a, &b).unwrap(), Placed::Linked);
        assert!(same_file(&a, &b).unwrap());
        assert_eq!(link_count(&a).unwrap(), 2);
        // Writing in place reaches both names.
        write_in_place(&b, b"two").unwrap();
        assert_eq!(fs::read_to_string(&a).unwrap(), "two");
        assert!(!dir.join("sub/b.txt.slink").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn link_replaces_existing_file() {
        let dir = temp_dir("replace");
        let (a, b) = (dir.join("a.txt"), dir.join("b.txt"));
        fs::write(&a, "central").unwrap();
        fs::write(&b, "old").unwrap();
        link_or_copy(&a, &b).unwrap();
        assert_eq!(fs::read_to_string(&b).unwrap(), "central");
        assert!(same_file(&a, &b).unwrap());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn falls_back_to_copy() {
        let dir = temp_dir("copy");
        let (a, b) = (dir.join("a.txt"), dir.join("b.txt"));
        fs::write(&a, "x").unwrap();
        let placed = link_or_copy_with(&a, &b, |_, _| Err(io::Error::other("no links here"))).unwrap();
        assert_eq!(placed, Placed::Copied);
        assert_eq!(fs::read_to_string(&b).unwrap(), "x");
        assert!(!same_file(&a, &b).unwrap());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn unlink_keeps_content_and_breaks_link() {
        let dir = temp_dir("unlink");
        let (a, b) = (dir.join("a.txt"), dir.join("b.txt"));
        fs::write(&a, "shared").unwrap();
        link_or_copy(&a, &b).unwrap();
        unlink_keep_content(&b).unwrap();
        assert!(!same_file(&a, &b).unwrap());
        write_in_place(&a, b"changed").unwrap();
        assert_eq!(fs::read_to_string(&b).unwrap(), "shared");
        assert_eq!(link_count(&a).unwrap(), 1);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn missing_files_are_not_the_same() {
        let dir = temp_dir("missing");
        let a = dir.join("a.txt");
        fs::write(&a, "x").unwrap();
        assert!(!same_file(&a, &dir.join("nope")).unwrap());
        assert!(!same_file(&dir.join("nope"), &dir.join("nope")).unwrap());
        assert!(link_count(&dir.join("nope")).is_err());
        fs::remove_dir_all(dir).unwrap();
    }
}
