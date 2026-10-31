//! Results of update checks, kept in memory per instance so they survive tab
//! and instance switches and the instance list can show them.

use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use serde::Deserialize;

use super::{ContentEntry, ContentType, UpdateInfo};

/// A check younger than this is reused instead of asking Modrinth/CurseForge again.
pub const MAX_AGE_SECS: u64 = 30 * 60;

/// One file to update, as the launcher sends it. The new version is always
/// chosen by the backend.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateTarget {
    pub kind: ContentType,
    pub file_name: String,
}

impl UpdateTarget {
    fn is(&self, entry: &ContentEntry) -> bool {
        self.kind == entry.kind && self.file_name == entry.file_name
    }
}

struct Cached {
    checked_at: u64,
    updates: Vec<UpdateInfo>,
}

#[derive(Default)]
pub struct UpdateCache {
    cached: Mutex<HashMap<String, Cached>>,
    /// Bumped by [`UpdateCache::invalidate`], so a check that started before a
    /// change does not store its outdated result.
    generations: Mutex<HashMap<String, u64>>,
    /// Updates of one instance run one after another (they share `content.lock.json`).
    busy: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
}

impl UpdateCache {
    /// The stored result if it is younger than [`MAX_AGE_SECS`].
    pub fn fresh(&self, id: &str, now: u64) -> Option<Vec<UpdateInfo>> {
        let cached = self.cached.lock().unwrap();
        let c = cached.get(id)?;
        (now.saturating_sub(c.checked_at) < MAX_AGE_SECS).then(|| c.updates.clone())
    }

    /// Call before a check; pass the token to [`UpdateCache::store`].
    pub fn begin(&self, id: &str) -> u64 {
        *self.generations.lock().unwrap().get(id).unwrap_or(&0)
    }

    /// Stores a check result unless the instance changed since [`UpdateCache::begin`].
    /// Returns whether it was stored.
    pub fn store(&self, id: &str, token: u64, updates: Vec<UpdateInfo>, now: u64) -> bool {
        if self.begin(id) != token {
            return false;
        }
        self.cached
            .lock()
            .unwrap()
            .insert(id.to_owned(), Cached { checked_at: now, updates });
        true
    }

    /// Forgets the result after the content, loader or Minecraft version of an
    /// instance changed, or the instance was deleted.
    pub fn invalidate(&self, id: &str) {
        *self.generations.lock().unwrap().entry(id.to_owned()).or_default() += 1;
        self.cached.lock().unwrap().remove(id);
    }

    /// Drops updates that were just installed, keeps the rest.
    pub fn remove_done(&self, id: &str, done: &[UpdateInfo]) {
        for d in done {
            self.forget(id, d.entry.kind, &d.entry.file_name);
        }
    }

    /// Drops the update of one file (installed or removed), keeps the rest. A
    /// check that started before is not stored, it would bring the update back.
    pub fn forget(&self, id: &str, kind: ContentType, file_name: &str) {
        *self.generations.lock().unwrap().entry(id.to_owned()).or_default() += 1;
        if let Some(c) = self.cached.lock().unwrap().get_mut(id) {
            c.updates.retain(|u| !(u.entry.kind == kind && u.entry.file_name == file_name));
        }
    }

    /// Number of known updates per instance, for the instance list. Instances
    /// without updates are left out.
    pub fn counts(&self) -> HashMap<String, usize> {
        self.cached
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, c)| !c.updates.is_empty())
            .map(|(id, c)| (id.clone(), c.updates.len()))
            .collect()
    }

    pub fn instance_lock(&self, id: &str) -> Arc<tokio::sync::Mutex<()>> {
        self.busy.lock().unwrap().entry(id.to_owned()).or_default().clone()
    }
}

/// Points updates at the current entries of the instance (a file may have been
/// toggled since the check) and drops updates whose file is gone or was replaced.
pub fn refresh_entries(updates: Vec<UpdateInfo>, current: &[ContentEntry]) -> Vec<UpdateInfo> {
    updates
        .into_iter()
        .filter_map(|mut u| {
            let now = current.iter().find(|e| {
                e.kind == u.entry.kind && e.file_name == u.entry.file_name && e.sha1 == u.entry.sha1
            })?;
            u.entry = now.clone();
            Some(u)
        })
        .collect()
}

/// The updates to install: all of them, or only the given files.
pub fn select(updates: Vec<UpdateInfo>, only: Option<&[UpdateTarget]>) -> Vec<UpdateInfo> {
    match only {
        None => updates,
        Some(targets) => updates
            .into_iter()
            .filter(|u| targets.iter().any(|t| t.is(&u.entry)))
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::content::{RemoteFile, RemoteVersion, Source};

    fn entry(kind: ContentType, file: &str, sha1: &str, enabled: bool) -> ContentEntry {
        ContentEntry {
            file_name: file.to_owned(),
            kind,
            name: file.to_owned(),
            version: Some("1".to_owned()),
            sha1: sha1.to_owned(),
            source: Source::Modrinth {
                project_id: file.to_owned(),
                version_id: "v1".to_owned(),
                slug: None,
            },
            enabled,
            dependency: false,
            icon_url: None,
        }
    }

    fn update(kind: ContentType, file: &str) -> UpdateInfo {
        UpdateInfo {
            entry: entry(kind, file, "old", true),
            new_version: RemoteVersion {
                id: "v2".to_owned(),
                project_id: file.to_owned(),
                version_number: "2".to_owned(),
                version_type: "release".to_owned(),
                file: RemoteFile {
                    url: Some(format!("https://example.com/{file}")),
                    filename: format!("new-{file}"),
                    sha1: None,
                    size: 1,
                },
            },
        }
    }

    fn target(kind: ContentType, file: &str) -> UpdateTarget {
        UpdateTarget { kind, file_name: file.to_owned() }
    }

    #[test]
    fn fresh_expires_after_max_age() {
        let cache = UpdateCache::default();
        let t = cache.begin("a");
        assert!(cache.store("a", t, vec![update(ContentType::Mod, "sodium.jar")], 1000));
        assert_eq!(cache.fresh("a", 1000 + MAX_AGE_SECS - 1).map(|u| u.len()), Some(1));
        assert!(cache.fresh("a", 1000 + MAX_AGE_SECS).is_none());
        assert!(cache.fresh("b", 1000).is_none());
    }

    #[test]
    fn invalidate_drops_result_and_rejects_older_checks() {
        let cache = UpdateCache::default();
        let t = cache.begin("a");
        assert!(cache.store("a", t, vec![update(ContentType::Mod, "sodium.jar")], 0));
        // A check starts, then a mod gets installed while it runs.
        let running = cache.begin("a");
        cache.invalidate("a");
        assert!(cache.fresh("a", 0).is_none());
        assert!(!cache.store("a", running, vec![update(ContentType::Mod, "sodium.jar")], 0));
        assert!(cache.fresh("a", 0).is_none());
        // A check started after the change is stored again.
        let next = cache.begin("a");
        assert!(cache.store("a", next, vec![], 0));
        assert_eq!(cache.fresh("a", 0).map(|u| u.len()), Some(0));
    }

    #[test]
    fn remove_done_keeps_other_updates() {
        let cache = UpdateCache::default();
        let all = vec![
            update(ContentType::Mod, "sodium.jar"),
            update(ContentType::Mod, "iris.jar"),
            update(ContentType::Shader, "sodium.jar"),
        ];
        cache.store("a", cache.begin("a"), all.clone(), 0);
        cache.remove_done("a", &all[..1]);
        let left = cache.fresh("a", 0).unwrap();
        assert_eq!(left.len(), 2);
        assert!(left.iter().any(|u| u.entry.file_name == "iris.jar"));
        // Same file name, other content type: still there.
        assert!(left.iter().any(|u| u.entry.kind == ContentType::Shader));
        cache.forget("a", ContentType::Shader, "sodium.jar");
        assert_eq!(cache.fresh("a", 0).unwrap().len(), 1);
    }

    #[test]
    fn check_started_before_an_update_is_not_stored() {
        let cache = UpdateCache::default();
        let all = vec![update(ContentType::Mod, "sodium.jar")];
        cache.store("a", cache.begin("a"), all.clone(), 0);
        let running = cache.begin("a");
        cache.remove_done("a", &all);
        assert!(!cache.store("a", running, all, 0));
        assert_eq!(cache.fresh("a", 0).unwrap().len(), 0);
    }

    #[test]
    fn counts_skip_instances_without_updates() {
        let cache = UpdateCache::default();
        cache.store("a", cache.begin("a"), vec![update(ContentType::Mod, "x.jar"), update(ContentType::Mod, "y.jar")], 0);
        cache.store("b", cache.begin("b"), vec![], 0);
        let counts = cache.counts();
        assert_eq!(counts.get("a"), Some(&2));
        assert!(!counts.contains_key("b"));
    }

    #[test]
    fn select_filters_by_kind_and_file() {
        let all = vec![
            update(ContentType::Mod, "sodium.jar"),
            update(ContentType::Mod, "iris.jar"),
            update(ContentType::ResourcePack, "pack.zip"),
        ];
        assert_eq!(select(all.clone(), None).len(), 3);
        let one = select(all.clone(), Some(&[target(ContentType::Mod, "iris.jar")]));
        assert_eq!(one.len(), 1);
        assert_eq!(one[0].entry.file_name, "iris.jar");
        // Right file name, wrong type: nothing.
        assert!(select(all.clone(), Some(&[target(ContentType::Shader, "iris.jar")])).is_empty());
        assert!(select(all, Some(&[])).is_empty());
    }

    #[test]
    fn refresh_entries_follows_toggles_and_drops_replaced_files() {
        let updates = vec![
            update(ContentType::Mod, "sodium.jar"),
            update(ContentType::Mod, "iris.jar"),
            update(ContentType::Mod, "gone.jar"),
        ];
        let current = vec![
            // Disabled since the check: the update must use the new state.
            entry(ContentType::Mod, "sodium.jar", "old", false),
            // Replaced by another file with the same name.
            entry(ContentType::Mod, "iris.jar", "different", true),
        ];
        let fresh = refresh_entries(updates, &current);
        assert_eq!(fresh.len(), 1);
        assert_eq!(fresh[0].entry.file_name, "sodium.jar");
        assert!(!fresh[0].entry.enabled);
    }

    #[tokio::test]
    async fn instance_lock_is_shared_per_instance() {
        let cache = UpdateCache::default();
        let a1 = cache.instance_lock("a");
        let a2 = cache.instance_lock("a");
        let b = cache.instance_lock("b");
        let _held = a1.lock().await;
        assert!(a2.try_lock().is_err());
        assert!(b.try_lock().is_ok());
    }
}
