//! Shared game settings: instances in a sync group share `options.txt`,
//! `optionsof.txt` and `optionsshaders.txt` through hard links to one file in
//! `shared-options/<group>/`.
//!
//! A hard link survives only when a program rewrites the file in place. A
//! program that saves by writing a new file and renaming it (many editors)
//! silently breaks the link, so [`reconcile`] checks every member at fixed
//! points (launcher start, before a game starts, after it exits, settings
//! page) and repairs it: the newest version wins, it is written in place into
//! the central file and every member is linked again. Where links are not
//! possible a member gets a copy that is only synced at those points.

use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
    sync::Mutex,
    time::SystemTime,
};

use serde::{Deserialize, Serialize};

use crate::{
    Error, Paths, Result,
    instance::{CopyPart, Instance, now, slugify},
    links::{self, Placed},
};

/// Backups kept per instance; older ones are removed.
const KEEP_BACKUPS: usize = 5;

/// All changes to groups and their files run one after another.
static LOCK: Mutex<()> = Mutex::new(());

fn files() -> &'static [&'static str] {
    CopyPart::Options.entries()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Group {
    pub id: String,
    pub name: String,
    pub instances: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Groups {
    groups: Vec<Group>,
}

/// Which settings apply when an instance joins a group.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Keep {
    /// The group's settings; the instance's files are backed up first.
    Group,
    /// The instance's settings, for every member (the group's files are backed up).
    Instance,
}

/// How a member's files are connected to the group, worst file first.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "state", content = "message", rename_all = "camelCase")]
pub enum MemberState {
    /// No settings file yet (instance never started); linked once the game writes one.
    Empty,
    Linked,
    /// A broken or missing link was set again.
    Repaired,
    /// The game is running; its files are synced when it exits.
    Waiting,
    /// Hard links are not possible here; synced at start and exit only.
    Copied,
    Error(String),
}

impl MemberState {
    fn rank(&self) -> u8 {
        match self {
            Self::Empty => 0,
            Self::Linked => 1,
            Self::Repaired => 2,
            Self::Waiting => 3,
            Self::Copied => 4,
            Self::Error(_) => 5,
        }
    }

    fn worst(self, other: Self) -> Self {
        if other.rank() > self.rank() { other } else { self }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MemberStatus {
    pub instance: String,
    #[serde(flatten)]
    pub state: MemberState,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GroupStatus {
    pub id: String,
    pub name: String,
    pub members: Vec<MemberStatus>,
}

fn group_dir(paths: &Paths, id: &str) -> PathBuf {
    paths.shared_options().join(id)
}

fn groups_file(paths: &Paths) -> PathBuf {
    paths.shared_options().join("groups.json")
}

/// The groups, without instances that no longer exist.
fn load(paths: &Paths) -> Result<Groups> {
    let mut groups: Groups = match fs::read_to_string(groups_file(paths)) {
        Ok(text) => serde_json::from_str(&text)?,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Groups::default()),
        Err(e) => return Err(e.into()),
    };
    let mut changed = false;
    for g in &mut groups.groups {
        let before = g.instances.len();
        g.instances.retain(|id| paths.instances().join(id).join("instance.json").is_file());
        changed |= g.instances.len() != before;
    }
    if changed {
        save(paths, &groups)?;
    }
    Ok(groups)
}

fn save(paths: &Paths, groups: &Groups) -> Result<()> {
    fs::create_dir_all(paths.shared_options())?;
    let file = groups_file(paths);
    let temp = file.with_extension("json.tmp");
    fs::write(&temp, serde_json::to_string_pretty(groups)?)?;
    fs::rename(temp, file)?;
    Ok(())
}

fn find<'a>(groups: &'a mut Groups, id: &str) -> Result<&'a mut Group> {
    groups
        .groups
        .iter_mut()
        .find(|g| g.id == id)
        .ok_or_else(|| Error::Other(format!("Gruppe {id} gibt es nicht")))
}

fn game_file(paths: &Paths, instance: &str, file: &str) -> PathBuf {
    paths.instances().join(instance).join("minecraft").join(file)
}

/// Copies `file` to `shared-options/<group>/backups/<owner>/<time>/`, keeping
/// the newest [`KEEP_BACKUPS`] backups of that owner.
fn backup(paths: &Paths, group: &str, owner: &str, file: &Path) -> Result<()> {
    let root = group_dir(paths, group).join("backups").join(owner);
    let stamp = now().to_string();
    let mut dir = root.join(&stamp);
    let mut n = 2;
    // Another file of the same backup run goes into the same folder.
    while dir.join(file.file_name().unwrap_or_default()).exists() {
        dir = root.join(format!("{stamp}-{n}"));
        n += 1;
    }
    fs::create_dir_all(&dir)?;
    fs::copy(file, dir.join(file.file_name().unwrap_or_default()))?;

    let mut runs: Vec<(SystemTime, PathBuf)> = fs::read_dir(&root)?
        .flatten()
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .collect();
    runs.sort_by(|a, b| b.0.cmp(&a.0));
    for (_, old) in runs.into_iter().skip(KEEP_BACKUPS) {
        let _ = fs::remove_dir_all(old);
    }
    Ok(())
}

fn modified(path: &Path) -> Option<SystemTime> {
    fs::metadata(path).ok()?.modified().ok()
}

/// Links `central` into an instance; a file with other content is backed up first.
fn join_file(paths: &Paths, group: &str, instance: &str, central: &Path, target: &Path) -> Result<Placed> {
    if target.exists() && !links::same_file(central, target)? && fs::read(target)? != fs::read(central)? {
        backup(paths, group, instance, target)?;
    }
    Ok(links::link_or_copy(central, target)?)
}

/// New group with the settings of `source`. The files are copied into the
/// group, the source instance links to them.
pub fn create(paths: &Paths, name: &str, source: &str) -> Result<Group> {
    let _lock = LOCK.lock().unwrap();
    Instance::load(paths, source)?;
    let mut groups = load(paths)?;
    if let Some(other) = groups.groups.iter().find(|g| g.instances.iter().any(|i| i == source)) {
        return Err(Error::Other(format!("Die Instanz ist schon in der Gruppe „{}“", other.name)));
    }
    let name = name.trim();
    if name.is_empty() {
        return Err(Error::Other("Die Gruppe braucht einen Namen".into()));
    }
    let base = slugify(name);
    let mut id = base.clone();
    let mut n = 2;
    while groups.groups.iter().any(|g| g.id == id) || group_dir(paths, &id).exists() {
        id = format!("{base}-{n}");
        n += 1;
    }
    let dir = group_dir(paths, &id);
    fs::create_dir_all(&dir)?;
    for file in files() {
        let own = game_file(paths, source, file);
        if own.is_file() {
            let central = dir.join(file);
            links::write_in_place(&central, &fs::read(&own)?)?;
            links::link_or_copy(&central, &own)?;
        }
    }
    let group = Group { id, name: name.to_owned(), instances: vec![source.to_owned()] };
    groups.groups.push(group.clone());
    save(paths, &groups)?;
    Ok(group)
}

pub fn rename(paths: &Paths, group: &str, name: &str) -> Result<()> {
    let _lock = LOCK.lock().unwrap();
    let name = name.trim();
    if name.is_empty() {
        return Err(Error::Other("Die Gruppe braucht einen Namen".into()));
    }
    let mut groups = load(paths)?;
    find(&mut groups, group)?.name = name.to_owned();
    save(paths, &groups)
}

/// Adds an instance to a group. It must not be in another group.
pub fn add(paths: &Paths, group: &str, instance: &str, keep: Keep) -> Result<()> {
    let _lock = LOCK.lock().unwrap();
    Instance::load(paths, instance)?;
    let mut groups = load(paths)?;
    if let Some(other) = groups.groups.iter().find(|g| g.instances.iter().any(|i| i == instance)) {
        if other.id == group {
            return Ok(());
        }
        return Err(Error::Other(format!("Die Instanz ist schon in der Gruppe „{}“", other.name)));
    }
    let dir = group_dir(paths, group);
    find(&mut groups, group)?;
    fs::create_dir_all(&dir)?;
    for file in files() {
        let central = dir.join(file);
        let own = game_file(paths, instance, file);
        let take_own = own.is_file() && (keep == Keep::Instance || !central.is_file());
        if take_own {
            let content = fs::read(&own)?;
            if central.is_file() && fs::read(&central)? != content {
                backup(paths, group, "_group", &central)?;
            }
            // In place, so the other members see the change through their links.
            links::write_in_place(&central, &content)?;
        }
        if central.is_file() {
            join_file(paths, group, instance, &central, &own)?;
        }
    }
    find(&mut groups, group)?.instances.push(instance.to_owned());
    save(paths, &groups)
}

/// Takes an instance out of its group. It keeps its current settings as its
/// own files; the other members are not touched.
pub fn remove(paths: &Paths, group: &str, instance: &str) -> Result<()> {
    let _lock = LOCK.lock().unwrap();
    let mut groups = load(paths)?;
    remove_member(paths, find(&mut groups, group)?, instance)?;
    save(paths, &groups)
}

fn remove_member(paths: &Paths, group: &mut Group, instance: &str) -> Result<()> {
    let dir = group_dir(paths, &group.id);
    for file in files() {
        let own = game_file(paths, instance, file);
        if own.is_file() && links::same_file(&dir.join(file), &own)? {
            links::unlink_keep_content(&own)?;
        }
    }
    group.instances.retain(|i| i != instance);
    Ok(())
}

/// Deletes a group: every member keeps its settings, then the group's folder
/// (with its backups) is removed.
pub fn delete(paths: &Paths, group: &str) -> Result<()> {
    let _lock = LOCK.lock().unwrap();
    let mut groups = load(paths)?;
    let g = find(&mut groups, group)?;
    for instance in g.instances.clone() {
        remove_member(paths, g, &instance)?;
    }
    groups.groups.retain(|g| g.id != group);
    save(paths, &groups)?;
    let dir = group_dir(paths, group);
    if dir.exists() {
        fs::remove_dir_all(dir)?;
    }
    Ok(())
}

/// Forgets a deleted instance. Its links went with its folder; the group's files stay.
pub fn forget_instance(paths: &Paths, instance: &str) -> Result<()> {
    let _lock = LOCK.lock().unwrap();
    // Loading drops instances whose folder is gone.
    let mut groups = load(paths)?;
    let mut changed = false;
    for g in &mut groups.groups {
        let before = g.instances.len();
        g.instances.retain(|i| i != instance);
        changed |= g.instances.len() != before;
    }
    if changed {
        save(paths, &groups)?;
    }
    Ok(())
}

/// All groups as stored, without checking their files.
pub fn groups(paths: &Paths) -> Result<Vec<Group>> {
    let _lock = LOCK.lock().unwrap();
    Ok(load(paths)?.groups)
}

/// The group of an instance, if it is in one.
pub fn group_of(paths: &Paths, instance: &str) -> Result<Option<Group>> {
    let _lock = LOCK.lock().unwrap();
    Ok(load(paths)?.groups.into_iter().find(|g| g.instances.iter().any(|i| i == instance)))
}

/// Checks and repairs every group. Files of `running` instances are not
/// replaced; that happens when they exit.
pub fn reconcile_all(paths: &Paths, running: &HashSet<String>) -> Result<Vec<GroupStatus>> {
    let _lock = LOCK.lock().unwrap();
    Ok(load(paths)?
        .groups
        .iter()
        .map(|g| reconcile_group(paths, g, running))
        .collect())
}

/// Checks and repairs the group of one instance (before it starts and after it exits).
pub fn reconcile_instance(paths: &Paths, instance: &str, running: &HashSet<String>) -> Result<Option<GroupStatus>> {
    let _lock = LOCK.lock().unwrap();
    Ok(load(paths)?
        .groups
        .iter()
        .find(|g| g.instances.iter().any(|i| i == instance))
        .map(|g| reconcile_group(paths, g, running)))
}

fn reconcile_group(paths: &Paths, group: &Group, running: &HashSet<String>) -> GroupStatus {
    let mut states: Vec<MemberState> = vec![MemberState::Empty; group.instances.len()];
    for file in files() {
        match reconcile_file(paths, group, file, running) {
            Ok(file_states) => {
                for (state, new) in states.iter_mut().zip(file_states) {
                    *state = std::mem::replace(state, MemberState::Empty).worst(new);
                }
            }
            Err(e) => {
                for state in &mut states {
                    *state = std::mem::replace(state, MemberState::Empty).worst(MemberState::Error(e.to_string()));
                }
            }
        }
    }
    GroupStatus {
        id: group.id.clone(),
        name: group.name.clone(),
        members: group
            .instances
            .iter()
            .zip(states)
            .map(|(instance, state)| MemberStatus { instance: instance.clone(), state })
            .collect(),
    }
}

/// One settings file of a group: the newest changed copy wins, then every
/// member that is not linked is linked again.
fn reconcile_file(paths: &Paths, group: &Group, file: &str, running: &HashSet<String>) -> Result<Vec<MemberState>> {
    let central = group_dir(paths, &group.id).join(file);
    let mut central_content = fs::read(&central).ok();

    // Members whose file is not the central one but has other content.
    let mut newest: Option<(SystemTime, Vec<u8>)> = None;
    for instance in &group.instances {
        let own = game_file(paths, instance, file);
        if !own.is_file() || links::same_file(&central, &own)? {
            continue;
        }
        let content = fs::read(&own)?;
        if central_content.as_ref() == Some(&content) {
            continue;
        }
        let time = modified(&own).unwrap_or(SystemTime::UNIX_EPOCH);
        if newest.as_ref().is_none_or(|(t, _)| time > *t) {
            newest = Some((time, content));
        }
    }
    if let Some((time, content)) = newest {
        let central_time = modified(&central);
        if central_time.is_none_or(|t| time > t) {
            fs::create_dir_all(central.parent().unwrap_or(&central))?;
            // In place: a rename would cut every other member off.
            links::write_in_place(&central, &content)?;
            central_content = Some(content);
        }
    }
    let Some(central_content) = central_content else {
        // Nobody has this file yet.
        return Ok(vec![MemberState::Empty; group.instances.len()]);
    };

    let mut states = Vec::with_capacity(group.instances.len());
    for instance in &group.instances {
        let own = game_file(paths, instance, file);
        let state = if own.is_file() && links::same_file(&central, &own)? {
            MemberState::Linked
        } else if running.contains(instance) {
            MemberState::Waiting
        } else {
            let result = (|| -> Result<Placed> {
                if own.is_file() && fs::read(&own)? != central_content {
                    backup(paths, &group.id, instance, &own)?;
                }
                Ok(links::link_or_copy(&central, &own)?)
            })();
            match result {
                Ok(Placed::Linked) => MemberState::Repaired,
                Ok(Placed::Copied) => MemberState::Copied,
                Err(e) => MemberState::Error(e.to_string()),
            }
        };
        states.push(state);
    }
    Ok(states)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::Loader;
    use std::time::Duration;

    fn setup(name: &str) -> (Paths, Vec<String>) {
        let root = std::env::temp_dir().join(format!("slopclient-shared-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let paths = Paths::new(&root);
        paths.ensure().unwrap();
        let ids = ["A", "B", "C"]
            .iter()
            .map(|n| Instance::create(&paths, n, "26.3", Loader::Vanilla).unwrap().id)
            .collect();
        (paths, ids)
    }

    fn options(paths: &Paths, id: &str) -> PathBuf {
        game_file(paths, id, "options.txt")
    }

    fn write(paths: &Paths, id: &str, text: &str) {
        fs::write(options(paths, id), text).unwrap();
    }

    fn read(paths: &Paths, id: &str) -> String {
        fs::read_to_string(options(paths, id)).unwrap()
    }

    fn states(status: &GroupStatus) -> Vec<MemberState> {
        status.members.iter().map(|m| m.state.clone()).collect()
    }

    /// Saves like an editor that writes a new file and renames it: breaks the link.
    fn save_by_rename(paths: &Paths, id: &str, text: &str) {
        let tmp = options(paths, id).with_extension("new");
        fs::write(&tmp, text).unwrap();
        fs::rename(tmp, options(paths, id)).unwrap();
    }

    #[test]
    fn group_settings_reach_new_members() {
        let (paths, ids) = setup("join");
        write(&paths, &ids[0], "fov:90");
        write(&paths, &ids[1], "fov:70");
        let g = create(&paths, "PvP", &ids[0]).unwrap();
        assert_eq!(g.id, "pvp");
        add(&paths, "pvp", &ids[1], Keep::Group).unwrap();
        assert_eq!(read(&paths, &ids[1]), "fov:90");
        assert!(links::same_file(&options(&paths, &ids[0]), &options(&paths, &ids[1])).unwrap());
        // B's own settings were backed up.
        let backups = group_dir(&paths, "pvp").join("backups").join(&ids[1]);
        let run = fs::read_dir(&backups).unwrap().next().unwrap().unwrap().path();
        assert_eq!(fs::read_to_string(run.join("options.txt")).unwrap(), "fov:70");
        // A change in A (written in place, like Minecraft) is in B at once.
        links::write_in_place(&options(&paths, &ids[0]), b"fov:100").unwrap();
        assert_eq!(read(&paths, &ids[1]), "fov:100");
        let status = reconcile_all(&paths, &HashSet::new()).unwrap();
        assert_eq!(states(&status[0]), vec![MemberState::Linked, MemberState::Linked]);
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn instance_settings_can_win() {
        let (paths, ids) = setup("keep");
        write(&paths, &ids[0], "fov:90");
        write(&paths, &ids[1], "fov:70");
        create(&paths, "PvP", &ids[0]).unwrap();
        add(&paths, "pvp", &ids[1], Keep::Instance).unwrap();
        assert_eq!(read(&paths, &ids[0]), "fov:70");
        assert_eq!(read(&paths, &ids[1]), "fov:70");
        assert!(group_dir(&paths, "pvp").join("backups/_group").is_dir());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn broken_link_is_repaired_and_newest_wins() {
        let (paths, ids) = setup("repair");
        write(&paths, &ids[0], "fov:90");
        create(&paths, "PvP", &ids[0]).unwrap();
        add(&paths, "pvp", &ids[1], Keep::Group).unwrap();
        add(&paths, "pvp", &ids[2], Keep::Group).unwrap();
        // Make sure the edit is newer than the central file.
        std::thread::sleep(Duration::from_millis(20));
        save_by_rename(&paths, &ids[1], "fov:110");
        assert_eq!(read(&paths, &ids[0]), "fov:90");
        let status = reconcile_instance(&paths, &ids[0], &HashSet::new()).unwrap().unwrap();
        assert_eq!(states(&status), vec![MemberState::Linked, MemberState::Repaired, MemberState::Linked]);
        for id in &ids {
            assert_eq!(read(&paths, id), "fov:110");
        }
        // C was never touched: written in place, its link survived.
        assert!(links::same_file(&group_dir(&paths, "pvp").join("options.txt"), &options(&paths, &ids[2])).unwrap());
        assert!(links::same_file(&options(&paths, &ids[0]), &options(&paths, &ids[1])).unwrap());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn older_broken_copy_loses_and_is_backed_up() {
        let (paths, ids) = setup("older");
        write(&paths, &ids[0], "fov:90");
        create(&paths, "PvP", &ids[0]).unwrap();
        add(&paths, "pvp", &ids[1], Keep::Group).unwrap();
        save_by_rename(&paths, &ids[1], "fov:50");
        std::thread::sleep(Duration::from_millis(20));
        links::write_in_place(&options(&paths, &ids[0]), b"fov:95").unwrap();
        reconcile_all(&paths, &HashSet::new()).unwrap();
        assert_eq!(read(&paths, &ids[1]), "fov:95");
        assert!(group_dir(&paths, "pvp").join("backups").join(&ids[1]).is_dir());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn running_instances_are_not_replaced() {
        let (paths, ids) = setup("running");
        write(&paths, &ids[0], "fov:90");
        create(&paths, "PvP", &ids[0]).unwrap();
        add(&paths, "pvp", &ids[1], Keep::Group).unwrap();
        fs::remove_file(options(&paths, &ids[1])).unwrap();
        let running: HashSet<String> = [ids[1].clone()].into();
        let status = reconcile_all(&paths, &running).unwrap();
        assert_eq!(states(&status[0]), vec![MemberState::Linked, MemberState::Waiting]);
        assert!(!options(&paths, &ids[1]).exists());
        let status = reconcile_all(&paths, &HashSet::new()).unwrap();
        assert_eq!(states(&status[0]), vec![MemberState::Linked, MemberState::Repaired]);
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn groups_are_independent() {
        let (paths, ids) = setup("two");
        write(&paths, &ids[0], "fov:90");
        write(&paths, &ids[1], "fov:70");
        create(&paths, "PvP", &ids[0]).unwrap();
        create(&paths, "Survival", &ids[1]).unwrap();
        links::write_in_place(&options(&paths, &ids[0]), b"fov:1").unwrap();
        reconcile_all(&paths, &HashSet::new()).unwrap();
        assert_eq!(read(&paths, &ids[1]), "fov:70");
        // One group per instance.
        assert!(add(&paths, "survival", &ids[0], Keep::Group).is_err());
        assert!(create(&paths, "Other", &ids[0]).is_err());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn removed_member_keeps_its_settings() {
        let (paths, ids) = setup("remove");
        write(&paths, &ids[0], "fov:90");
        create(&paths, "PvP", &ids[0]).unwrap();
        add(&paths, "pvp", &ids[1], Keep::Group).unwrap();
        remove(&paths, "pvp", &ids[1]).unwrap();
        assert_eq!(read(&paths, &ids[1]), "fov:90");
        links::write_in_place(&options(&paths, &ids[0]), b"fov:10").unwrap();
        assert_eq!(read(&paths, &ids[1]), "fov:90");
        links::write_in_place(&options(&paths, &ids[1]), b"fov:20").unwrap();
        assert_eq!(read(&paths, &ids[0]), "fov:10");
        assert!(group_of(&paths, &ids[1]).unwrap().is_none());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn deleting_group_or_instance_keeps_others() {
        let (paths, ids) = setup("delete");
        write(&paths, &ids[0], "fov:90");
        create(&paths, "PvP", &ids[0]).unwrap();
        add(&paths, "pvp", &ids[1], Keep::Group).unwrap();
        add(&paths, "pvp", &ids[2], Keep::Group).unwrap();
        // Deleting an instance only removes its link.
        Instance::delete(&paths, &ids[2]).unwrap();
        assert_eq!(group_of(&paths, &ids[0]).unwrap().unwrap().instances.len(), 2);
        assert_eq!(read(&paths, &ids[0]), "fov:90");
        delete(&paths, "pvp").unwrap();
        assert!(!group_dir(&paths, "pvp").exists());
        assert_eq!(read(&paths, &ids[0]), "fov:90");
        assert_eq!(read(&paths, &ids[1]), "fov:90");
        assert!(!links::same_file(&options(&paths, &ids[0]), &options(&paths, &ids[1])).unwrap());
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn duplicate_is_not_in_the_group() {
        let (paths, ids) = setup("dup");
        write(&paths, &ids[0], "fov:90");
        create(&paths, "PvP", &ids[0]).unwrap();
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let copy = rt.block_on(Instance::duplicate(&paths, &ids[0], "A copy")).unwrap();
        assert!(group_of(&paths, &copy.id).unwrap().is_none());
        assert!(!links::same_file(&options(&paths, &ids[0]), &options(&paths, &copy.id)).unwrap());
        assert_eq!(read(&paths, &copy.id), "fov:90");
        fs::remove_dir_all(&paths.root).unwrap();
    }

    #[test]
    fn status_json_for_the_launcher() {
        let status = GroupStatus {
            id: "pvp".into(),
            name: "PvP".into(),
            members: vec![
                MemberStatus { instance: "a".into(), state: MemberState::Linked },
                MemberStatus { instance: "b".into(), state: MemberState::Error("kaputt".into()) },
            ],
        };
        assert_eq!(
            serde_json::to_value(&status).unwrap(),
            serde_json::json!({
                "id": "pvp",
                "name": "PvP",
                "members": [
                    { "instance": "a", "state": "linked" },
                    { "instance": "b", "state": "error", "message": "kaputt" }
                ]
            })
        );
    }

    #[test]
    fn first_file_of_a_new_member_becomes_central() {
        let (paths, ids) = setup("first");
        // Group from an instance that was never started.
        create(&paths, "New", &ids[0]).unwrap();
        add(&paths, "new", &ids[1], Keep::Group).unwrap();
        let status = reconcile_all(&paths, &HashSet::new()).unwrap();
        assert_eq!(states(&status[0]), vec![MemberState::Empty, MemberState::Empty]);
        // The game writes options.txt for the first time in B.
        write(&paths, &ids[1], "fov:80");
        let status = reconcile_all(&paths, &HashSet::new()).unwrap();
        assert_eq!(states(&status[0]), vec![MemberState::Repaired, MemberState::Repaired]);
        assert_eq!(read(&paths, &ids[0]), "fov:80");
        fs::remove_dir_all(&paths.root).unwrap();
    }
}
