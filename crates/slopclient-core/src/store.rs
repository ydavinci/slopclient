//! Content store: every mod, resource pack and shader pack file is kept once in
//! `store/<sha1[..2]>/<sha1>` and instances get hard links to it. A file
//! another instance already has is neither downloaded nor stored again.
//!
//! Files in the store never change: their name is the SHA-1 of the content.
//! A file is unused once only the store itself links to it (link count 1);
//! [`gc`] removes those. Where hard links are not possible the instance gets a
//! copy, the download is still saved.

use std::{
    collections::HashSet,
    fs, io,
    path::{Path, PathBuf},
    time::{Duration, SystemTime},
};

use serde::Serialize;
use sha1::{Digest, Sha1};

use crate::{
    Error, Paths, Progress, ProgressFn, Result,
    config::Settings,
    download::{DownloadTask, download_all, sha1_file},
    instance::Instance,
    links::{self, Placed},
    progress::stage,
};

/// Folders of the game directory whose files go through the store.
const CONTENT_FOLDERS: [&str; 3] = ["mods", "resourcepacks", "shaderpacks"];

/// Whether new content files go through the store (Einstellungen → Downloads).
pub fn enabled(paths: &Paths) -> bool {
    Settings::load(paths).map(|s| s.shared_content()).unwrap_or(true)
}

/// Where the file with this SHA-1 lives in the store.
pub fn path(paths: &Paths, sha1: &str) -> PathBuf {
    let sha1 = sha1.to_ascii_lowercase();
    paths.store().join(&sha1[..2.min(sha1.len())]).join(sha1)
}

/// Files directly in `<game dir>/mods`, `resourcepacks` or `shaderpacks`.
/// Only those are shared: configs and worlds get written to.
pub fn is_content_path(path: &Path) -> bool {
    let parent = path.parent();
    let folder = parent.and_then(|p| p.file_name()).and_then(|n| n.to_str());
    let game = parent.and_then(|p| p.parent()).and_then(|p| p.file_name());
    folder.is_some_and(|f| CONTENT_FOLDERS.contains(&f)) && game.is_some_and(|g| g == "minecraft")
}

/// Puts the stored file with this SHA-1 at `target`: a hard link, or a copy
/// where links are not possible. The file has to be in the store already.
pub fn place(paths: &Paths, sha1: &str, target: &Path) -> Result<Placed> {
    links::link_or_copy(&path(paths, sha1), target).map_err(|e| in_use(e, target))
}

/// A file a running game holds open cannot be replaced on Windows.
fn in_use(e: io::Error, target: &Path) -> Error {
    let name = target.file_name().unwrap_or_default().to_string_lossy();
    // 32 = ERROR_SHARING_VIOLATION, 5 = ERROR_ACCESS_DENIED
    if cfg!(windows) && matches!(e.raw_os_error(), Some(32) | Some(5)) {
        return Error::Other(format!(
            "{name} wird gerade von einem laufenden Spiel benutzt. Schließ das Spiel und versuch es noch mal."
        ));
    }
    Error::Other(format!("{name}: {e}"))
}

/// Copies `source` into the store unless the store has it already.
fn store_copy(paths: &Paths, sha1: &str, source: &Path) -> Result<bool> {
    let stored = path(paths, sha1);
    if stored.is_file() {
        return Ok(false);
    }
    fs::create_dir_all(stored.parent().unwrap_or(&stored))?;
    // Copy, never move: if anything goes wrong the instance still has its file.
    let part = stored.with_extension("part");
    fs::copy(source, &part)?;
    fs::rename(&part, &stored)?;
    Ok(true)
}

/// Takes `source` into the store and puts it at `target` (which may be the
/// same path: then the instance file becomes a link). Returns the SHA-1.
pub async fn import(paths: &Paths, source: &Path, target: &Path) -> Result<String> {
    let sha1 = sha1_file(source).await?;
    store_copy(paths, &sha1, source)?;
    place(paths, &sha1, target)?;
    Ok(sha1)
}

/// A file that already lies in an instance becomes a link into the store.
pub async fn adopt(paths: &Paths, file: &Path) -> Result<String> {
    import(paths, file, file).await
}

/// Stores `bytes` and puts them at `target`.
pub fn write(paths: &Paths, bytes: &[u8], target: &Path) -> Result<String> {
    let sha1 = hex::encode(Sha1::digest(bytes));
    let stored = path(paths, &sha1);
    if !stored.is_file() {
        fs::create_dir_all(stored.parent().unwrap_or(&stored))?;
        let part = stored.with_extension("part");
        fs::write(&part, bytes)?;
        fs::rename(&part, &stored)?;
    }
    place(paths, &sha1, target)?;
    Ok(sha1)
}

/// Downloads `tasks` into an instance. Content files with a known SHA-1 go
/// through the store when sharing is on: what the store has is not downloaded
/// again. Everything else is downloaded directly, content files are taken into
/// the store afterwards.
pub async fn download(paths: &Paths, tasks: Vec<DownloadTask>, concurrency: usize, progress: &ProgressFn) -> Result<()> {
    if !enabled(paths) {
        return download_all(tasks, concurrency, progress).await;
    }
    let mut downloads = Vec::new();
    let mut placements = Vec::new();
    let mut adoptions = Vec::new();
    for task in tasks {
        let content = is_content_path(&task.path);
        match &task.sha1 {
            Some(sha1) if content => {
                let sha1 = sha1.to_ascii_lowercase();
                let stored = path(paths, &sha1);
                // The instance may have the file already (e.g. installed before sharing).
                if !stored.is_file()
                    && task.path.is_file()
                    && task.size.is_none_or(|s| fs::metadata(&task.path).map(|m| m.len()) .ok() == Some(s))
                    && sha1_file(&task.path).await?.eq_ignore_ascii_case(&sha1)
                {
                    store_copy(paths, &sha1, &task.path)?;
                }
                placements.push((sha1.clone(), task.path.clone()));
                downloads.push(DownloadTask { path: stored, ..task }.verify_hash());
            }
            _ => {
                if content {
                    adoptions.push(task.path.clone());
                }
                downloads.push(task);
            }
        }
    }
    download_all(downloads, concurrency, progress).await?;
    for (sha1, target) in placements {
        place(paths, &sha1, &target)?;
    }
    for file in adoptions {
        adopt(paths, &file).await?;
    }
    Ok(())
}

/// Content files of an instance: everything in its mods, resourcepacks and
/// shaderpacks folders except folders and the SlopClient client mod.
fn content_files(paths: &Paths, instance: &Instance) -> Vec<PathBuf> {
    let game = instance.game_dir(paths);
    let mut files = Vec::new();
    for folder in CONTENT_FOLDERS {
        let Ok(entries) = fs::read_dir(game.join(folder)) else { continue };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let is_file = entry.file_type().is_ok_and(|t| t.is_file());
            if is_file && !crate::client_mod::is_mod_file(&name) && !name.ends_with(".part") && !name.ends_with(".slink") {
                files.push(entry.path());
            }
        }
    }
    files
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DedupeReport {
    /// Files that were their own copies and are links into the store now.
    pub merged: usize,
    /// Disk space that is free now.
    pub freed_bytes: u64,
    /// Running instances that were left out.
    pub skipped: Vec<String>,
}

/// Turns the content files of every instance into links into the store (for
/// files from before sharing), then removes what nobody uses any more.
pub async fn dedupe(paths: &Paths, running: &HashSet<String>, progress: &ProgressFn) -> Result<DedupeReport> {
    let mut report = DedupeReport::default();
    let mut files = Vec::new();
    for instance in Instance::list(paths)? {
        if running.contains(&instance.id) {
            report.skipped.push(instance.name.clone());
        } else {
            files.extend(content_files(paths, &instance));
        }
    }
    stage(progress, "Merging duplicate files");
    let total = files.len();
    let mut saved: i64 = 0;
    for (i, file) in files.iter().enumerate() {
        progress(Progress::Download { finished: i, total, bytes: 0, total_bytes: 0 });
        let size = fs::metadata(file)?.len();
        let sha1 = sha1_file(file).await?;
        let stored = path(paths, &sha1);
        if links::same_file(&stored, file)? {
            continue;
        }
        if store_copy(paths, &sha1, file)? {
            // The first copy of a file only moves into the store.
            saved -= size as i64;
        }
        if place(paths, &sha1, file)? == Placed::Linked {
            saved += size as i64;
            report.merged += 1;
        }
    }
    progress(Progress::Download { finished: total, total, bytes: 0, total_bytes: 0 });
    let gc = gc(paths)?;
    report.freed_bytes = (saved.max(0) as u64) + gc.freed_bytes;
    Ok(report)
}

/// Takes the content files of one instance into the store (after importing it
/// from another launcher).
pub async fn adopt_instance(paths: &Paths, instance: &Instance) -> Result<usize> {
    if !enabled(paths) {
        return Ok(0);
    }
    let mut n = 0;
    for file in content_files(paths, instance) {
        adopt(paths, &file).await?;
        n += 1;
    }
    Ok(n)
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GcReport {
    pub removed_files: usize,
    pub freed_bytes: u64,
}

/// Store files younger than this are kept even without links: an install may
/// have just put them there and not linked them yet.
const GC_GRACE: Duration = Duration::from_secs(10 * 60);

/// Removes store files no instance links to any more, unfinished `.part`
/// files and empty folders.
pub fn gc(paths: &Paths) -> Result<GcReport> {
    gc_older_than(paths, GC_GRACE)
}

fn age(meta: &fs::Metadata) -> Duration {
    // Copies on Windows keep the modification time of the original, the creation time is new.
    let newest = [meta.created().ok(), meta.modified().ok()].into_iter().flatten().max();
    newest.and_then(|t| SystemTime::now().duration_since(t).ok()).unwrap_or(Duration::MAX)
}

fn gc_older_than(paths: &Paths, grace: Duration) -> Result<GcReport> {
    let mut report = GcReport::default();
    let Ok(dirs) = fs::read_dir(paths.store()) else { return Ok(report) };
    for dir in dirs.flatten() {
        if !dir.file_type().is_ok_and(|t| t.is_dir()) {
            continue;
        }
        for entry in fs::read_dir(dir.path())?.flatten() {
            let file = entry.path();
            let Ok(meta) = entry.metadata() else { continue };
            if age(&meta) < grace {
                continue;
            }
            let unused = file.extension().is_some_and(|e| e == "part") || links::link_count(&file)? <= 1;
            if unused {
                let size = meta.len();
                if fs::remove_file(&file).is_ok() {
                    report.removed_files += 1;
                    report.freed_bytes += size;
                }
            }
        }
        // Only succeeds when empty.
        let _ = fs::remove_dir(dir.path());
    }
    Ok(report)
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StoreStats {
    pub files: usize,
    /// Size of the store.
    pub bytes: u64,
    /// What the instances would need on top without sharing.
    pub saved_bytes: u64,
}

pub fn stats(paths: &Paths) -> Result<StoreStats> {
    let mut stats = StoreStats::default();
    let Ok(dirs) = fs::read_dir(paths.store()) else { return Ok(stats) };
    for dir in dirs.flatten() {
        let Ok(entries) = fs::read_dir(dir.path()) else { continue };
        for entry in entries.flatten() {
            let file = entry.path();
            if file.extension().is_some_and(|e| e == "part") {
                continue;
            }
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            // One link is the store itself, one instance would need the file anyway.
            let links = links::link_count(&file).unwrap_or(1);
            stats.files += 1;
            stats.bytes += size;
            stats.saved_bytes += size * links.saturating_sub(2);
        }
    }
    Ok(stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::Loader;

    fn setup(name: &str) -> (Paths, Instance, Instance) {
        let root = std::env::temp_dir().join(format!("slopclient-store-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let paths = Paths::new(&root);
        paths.ensure().unwrap();
        let a = Instance::create(&paths, "A", "26.3", Loader::Vanilla).unwrap();
        let b = Instance::create(&paths, "B", "26.3", Loader::Vanilla).unwrap();
        (paths, a, b)
    }

    fn mods(paths: &Paths, i: &Instance) -> PathBuf {
        let dir = i.game_dir(paths).join("mods");
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn sha(bytes: &[u8]) -> String {
        hex::encode(Sha1::digest(bytes))
    }

    #[test]
    fn content_paths() {
        assert!(is_content_path(Path::new("/x/instances/a/minecraft/mods/sodium.jar")));
        assert!(is_content_path(Path::new("/x/instances/a/minecraft/resourcepacks/p.zip")));
        assert!(!is_content_path(Path::new("/x/instances/a/minecraft/config/sodium.json")));
        assert!(!is_content_path(Path::new("/x/instances/a/minecraft/config/mods/x.jar")));
    }

    #[test]
    fn write_place_and_gc() {
        let (paths, a, b) = setup("place");
        let (ma, mb) = (mods(&paths, &a).join("sodium.jar"), mods(&paths, &b).join("sodium.jar"));
        let sha1 = write(&paths, b"jar", &ma).unwrap();
        assert_eq!(sha1, sha(b"jar"));
        assert_eq!(place(&paths, &sha1, &mb).unwrap(), Placed::Linked);
        assert!(links::same_file(&ma, &mb).unwrap());
        assert_eq!(links::link_count(&path(&paths, &sha1)).unwrap(), 3);
        let s = stats(&paths).unwrap();
        assert_eq!((s.files, s.bytes, s.saved_bytes), (1, 3, 3));

        // Removing it from A leaves B alone; gc keeps the file while B uses it.
        fs::remove_file(&ma).unwrap();
        assert_eq!(gc_older_than(&paths, Duration::ZERO).unwrap().removed_files, 0);
        assert_eq!(fs::read(&mb).unwrap(), b"jar");
        fs::remove_file(&mb).unwrap();
        // A fresh file may still be waiting for its link.
        assert_eq!(gc(&paths).unwrap().removed_files, 0);
        let r = gc_older_than(&paths, Duration::ZERO).unwrap();
        assert_eq!((r.removed_files, r.freed_bytes), (1, 3));
        assert!(!path(&paths, &sha1).exists());
        assert!(fs::read_dir(paths.store()).unwrap().next().is_none());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[tokio::test]
    async fn duplicate_links_mods_and_copies_the_rest() {
        let (paths, a, _) = setup("dup");
        let jar = mods(&paths, &a).join("sodium.jar");
        write(&paths, b"jar", &jar).unwrap();
        fs::write(mods(&paths, &a).join("slopclient-1.0.jar"), b"client").unwrap();
        let config = a.game_dir(&paths).join("config");
        fs::create_dir_all(&config).unwrap();
        fs::write(config.join("sodium.json"), b"{}").unwrap();
        let copy = Instance::duplicate(&paths, &a.id, "A2").await.unwrap();
        let game = copy.game_dir(&paths);
        assert!(links::same_file(&jar, &game.join("mods/sodium.jar")).unwrap());
        assert!(!links::same_file(&config.join("sodium.json"), &game.join("config/sodium.json")).unwrap());
        assert_eq!(links::link_count(&game.join("mods/slopclient-1.0.jar")).unwrap(), 1);
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[tokio::test]
    async fn adopt_links_an_existing_file() {
        let (paths, a, _) = setup("adopt");
        let file = mods(&paths, &a).join("iris.jar");
        fs::write(&file, b"iris").unwrap();
        let sha1 = adopt(&paths, &file).await.unwrap();
        assert!(links::same_file(&path(&paths, &sha1), &file).unwrap());
        assert_eq!(fs::read(&file).unwrap(), b"iris");
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[tokio::test]
    async fn dedupe_merges_copies_and_reports_saving() {
        let (paths, a, b) = setup("dedupe");
        let content = vec![7u8; 1000];
        fs::write(mods(&paths, &a).join("big.jar"), &content).unwrap();
        fs::write(mods(&paths, &b).join("big.jar"), &content).unwrap();
        fs::write(mods(&paths, &b).join("own.jar"), b"only b").unwrap();
        // The client mod is managed elsewhere and stays out.
        fs::write(mods(&paths, &b).join("slopclient-1.0.jar"), b"client").unwrap();
        let progress = crate::progress::noop();
        let report = dedupe(&paths, &HashSet::new(), &progress).await.unwrap();
        assert_eq!(report.merged, 3);
        // Two copies of big.jar became one; own.jar only moved into the store.
        assert_eq!(report.freed_bytes, 1000);
        assert!(links::same_file(&mods(&paths, &a).join("big.jar"), &mods(&paths, &b).join("big.jar")).unwrap());
        assert_eq!(links::link_count(&mods(&paths, &b).join("slopclient-1.0.jar")).unwrap(), 1);
        // Running instances are skipped.
        let running: HashSet<String> = [a.id.clone()].into();
        let again = dedupe(&paths, &running, &progress).await.unwrap();
        assert_eq!((again.merged, again.skipped.clone()), (0, vec!["A".to_owned()]));
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[tokio::test]
    async fn download_reuses_what_an_instance_already_has() {
        let (paths, a, b) = setup("download");
        let jar = mods(&paths, &a).join("lithium.jar");
        fs::write(&jar, b"lithium").unwrap();
        let sha1 = sha(b"lithium");
        // The URL is never fetched: A's file is taken into the store and B links to it.
        let task = |target: PathBuf| DownloadTask::new("http://127.0.0.1:9/never", target).sha1(Some(sha1.clone())).size(Some(7));
        let progress = crate::progress::noop();
        download(&paths, vec![task(jar.clone())], 1, &progress).await.unwrap();
        let target = mods(&paths, &b).join("lithium.jar");
        download(&paths, vec![task(target.clone())], 1, &progress).await.unwrap();
        assert!(links::same_file(&jar, &target).unwrap());
        assert!(links::same_file(&path(&paths, &sha1), &target).unwrap());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[tokio::test]
    async fn changed_store_file_is_downloaded_again() {
        let (paths, a, _) = setup("verify");
        let sha1 = sha(b"good");
        let stored = path(&paths, &sha1);
        fs::create_dir_all(stored.parent().unwrap()).unwrap();
        // Same size, other content: a size check alone would trust it.
        fs::write(&stored, b"evil").unwrap();
        let target = mods(&paths, &a).join("x.jar");
        let task = DownloadTask::new("http://127.0.0.1:9/never", target).sha1(Some(sha1)).size(Some(4));
        let progress = crate::progress::noop();
        // The download is attempted (and fails here, offline) instead of linking the changed file.
        assert!(download(&paths, vec![task], 1, &progress).await.is_err());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[tokio::test]
    async fn sharing_off_downloads_directly() {
        let (paths, a, _) = setup("off");
        Settings { shared_content: Some(false), ..Default::default() }.save(&paths).unwrap();
        assert!(!enabled(&paths));
        let jar = mods(&paths, &a).join("l.jar");
        fs::write(&jar, b"lithium").unwrap();
        let task = DownloadTask::new("http://127.0.0.1:9/never", jar.clone()).sha1(Some(sha(b"lithium"))).size(Some(7));
        let progress = crate::progress::noop();
        download(&paths, vec![task], 1, &progress).await.unwrap();
        assert!(!paths.store().join(&sha(b"lithium")[..2]).exists());
        assert_eq!(links::link_count(&jar).unwrap(), 1);
        fs::remove_dir_all(&paths.root).unwrap();
    }
}
