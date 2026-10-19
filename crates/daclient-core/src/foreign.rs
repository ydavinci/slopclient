//! Import instances from other launchers: Prism Launcher, PolyMC and MultiMC
//! (all share the MultiMC format: `instance.cfg` + `mmc-pack.json`).
//! The game folder is copied, the original stays untouched.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};

use crate::{
    Error, Paths, ProgressFn, Result,
    content::ContentManager,
    instance::{Instance, Loader},
    meta::LoaderKind,
    progress::stage,
};

/// Folders in the game directory that are not worth copying.
const SKIP: [&str; 3] = ["logs", "crash-reports", ".cache"];

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ForeignInstance {
    /// "Prism Launcher", "PolyMC" or "MultiMC".
    pub launcher: String,
    pub name: String,
    pub path: String,
    pub mc_version: String,
    pub loader: Loader,
    pub mods: usize,
}

#[derive(Deserialize)]
struct Pack {
    components: Vec<Component>,
}

#[derive(Deserialize)]
struct Component {
    uid: String,
    #[serde(default)]
    version: Option<String>,
}

/// Instance folders of launchers installed in the default locations.
pub fn default_roots() -> Vec<(String, PathBuf)> {
    let Some(appdata) = dirs::config_dir() else {
        return Vec::new();
    };
    [("Prism Launcher", "PrismLauncher"), ("PolyMC", "PolyMC")]
        .into_iter()
        .map(|(label, dir)| (label.to_owned(), appdata.join(dir).join("instances")))
        .filter(|(_, p)| p.is_dir())
        .collect()
}

/// Instances found in `roots` (each root is a launcher's `instances` folder).
/// A root may also be a single instance folder.
pub fn scan(roots: &[(String, PathBuf)]) -> Vec<ForeignInstance> {
    let mut out = Vec::new();
    for (launcher, root) in roots {
        if let Some(i) = read(root, launcher) {
            out.push(i);
            continue;
        }
        let Ok(dir) = std::fs::read_dir(root) else { continue };
        for entry in dir.flatten() {
            if let Some(i) = read(&entry.path(), launcher) {
                out.push(i);
            }
        }
    }
    out.sort_by_key(|i| i.name.to_lowercase());
    out
}

fn ini(path: &Path) -> HashMap<String, String> {
    std::fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .filter_map(|l| l.split_once('='))
        .map(|(k, v)| (k.trim().to_owned(), v.trim().trim_matches('"').to_owned()))
        .collect()
}

fn game_dir(instance: &Path) -> Option<PathBuf> {
    [".minecraft", "minecraft"].iter().map(|d| instance.join(d)).find(|p| p.is_dir())
}

fn loader_of(components: &[Component]) -> Loader {
    for c in components {
        let kind = match c.uid.as_str() {
            "net.fabricmc.fabric-loader" => LoaderKind::Fabric,
            "org.quiltmc.quilt-loader" => LoaderKind::Quilt,
            "net.neoforged" => LoaderKind::NeoForge,
            "net.minecraftforge" => LoaderKind::Forge,
            _ => continue,
        };
        if let Some(v) = &c.version {
            return Loader::new(kind, v.clone());
        }
    }
    Loader::Vanilla
}

/// Reads one instance folder; `None` if it isn't a MultiMC-style instance.
pub fn read(dir: &Path, launcher: &str) -> Option<ForeignInstance> {
    let cfg_path = dir.join("instance.cfg");
    let pack: Pack = serde_json::from_str(&std::fs::read_to_string(dir.join("mmc-pack.json")).ok()?).ok()?;
    if !cfg_path.is_file() {
        return None;
    }
    let cfg = ini(&cfg_path);
    let mc_version = pack
        .components
        .iter()
        .find(|c| c.uid == "net.minecraft")?
        .version
        .clone()?;
    let mods = game_dir(dir)
        .and_then(|g| std::fs::read_dir(g.join("mods")).ok())
        .map(|d| d.flatten().filter(|e| e.file_name().to_string_lossy().ends_with(".jar")).count())
        .unwrap_or(0);
    Some(ForeignInstance {
        launcher: launcher.to_owned(),
        name: cfg
            .get("name")
            .cloned()
            .filter(|n| !n.is_empty())
            .unwrap_or_else(|| dir.file_name().unwrap_or_default().to_string_lossy().into_owned()),
        path: dir.to_string_lossy().into_owned(),
        mc_version,
        loader: loader_of(&pack.components),
        mods,
    })
}

fn copy_dir(from: &Path, to: &Path, top: bool) -> std::io::Result<u64> {
    std::fs::create_dir_all(to)?;
    let mut count = 0;
    for entry in std::fs::read_dir(from)?.flatten() {
        let name = entry.file_name();
        if top && SKIP.iter().any(|s| name == *s) {
            continue;
        }
        let target = to.join(&name);
        let kind = entry.file_type()?;
        if kind.is_dir() {
            count += copy_dir(&entry.path(), &target, false)?;
        } else if kind.is_file() {
            std::fs::copy(entry.path(), &target)?;
            count += 1;
        }
    }
    Ok(count)
}

/// Copies an instance into DaClient. `name` overrides the original name.
pub async fn import(
    paths: &Paths,
    dir: &Path,
    name: Option<&str>,
    curseforge_key: Option<String>,
    progress: &ProgressFn,
) -> Result<Instance> {
    let found = read(dir, "Prism Launcher")
        .ok_or_else(|| Error::Other(format!("{} is not a Prism/MultiMC instance", dir.display())))?;
    let source = game_dir(dir).ok_or_else(|| Error::Other("the instance has no game folder yet".into()))?;
    let cfg = ini(&dir.join("instance.cfg"));

    let mut instance = Instance::create(paths, name.unwrap_or(&found.name), &found.mc_version, found.loader.clone())?;
    if cfg.get("OverrideMemory").is_some_and(|v| v == "true") {
        let mb = |key: &str| cfg.get(key).and_then(|v| v.parse::<u32>().ok());
        if let Some(max) = mb("MaxMemAlloc") {
            instance.java.max_memory_mb = max;
        }
        if let Some(min) = mb("MinMemAlloc") {
            instance.java.min_memory_mb = min.min(instance.java.max_memory_mb);
        }
    }
    if cfg.get("OverrideJavaArgs").is_some_and(|v| v == "true")
        && let Some(args) = cfg.get("JvmArgs")
    {
        instance.java.jvm_args = args.split_whitespace().map(str::to_owned).collect();
    }
    instance.save(paths)?;

    stage(progress, "Copying instance files");
    let target = instance.game_dir(paths);
    let copied = tokio::task::spawn_blocking(move || copy_dir(&source, &target, true)).await?;
    if let Err(e) = copied {
        let _ = Instance::delete(paths, &instance.id);
        return Err(e.into());
    }

    // Prism keeps custom icons in <data>/icons/<iconKey>.*; built-in keys have no file.
    let icons = dir.parent().and_then(Path::parent).map(|data| data.join("icons"));
    let custom = cfg.get("iconKey").zip(icons).and_then(|(key, icons)| {
        ["png", "jpg", "jpeg", "gif", "webp"]
            .iter()
            .map(|ext| icons.join(format!("{key}.{ext}")))
            .find(|p| p.is_file())
    });
    match custom {
        Some(file) => {
            let _ = crate::icon::set_from_file(paths, &mut instance, &file, None);
        }
        None => crate::icon::adopt_from_game_dir(paths, &mut instance),
    }

    stage(progress, "Linking mods to Modrinth and CurseForge");
    // Recognising mods is a bonus; an offline import still succeeds.
    let _ = ContentManager::new(paths, instance.clone(), 8)
        .with_curseforge(curseforge_key)
        .identify()
        .await;
    Ok(instance)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(root: &Path, name: &str, pack: &str, cfg: &str) -> PathBuf {
        let dir = root.join(name);
        std::fs::create_dir_all(dir.join(".minecraft/mods")).unwrap();
        std::fs::write(dir.join("mmc-pack.json"), pack).unwrap();
        std::fs::write(dir.join("instance.cfg"), cfg).unwrap();
        std::fs::write(dir.join(".minecraft/mods/a.jar"), "x").unwrap();
        std::fs::write(dir.join(".minecraft/mods/notes.txt"), "x").unwrap();
        dir
    }

    #[test]
    fn reads_prism_instances() {
        let root = std::env::temp_dir().join(format!("daclient-foreign-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        fixture(
            &root,
            "neo",
            r#"{"components":[{"uid":"org.lwjgl3","version":"3.3.3"},{"uid":"net.minecraft","version":"1.21.1"},{"uid":"net.neoforged","version":"21.1.262"}],"formatVersion":1}"#,
            "[General]\nname=Mein Pack\nOverrideMemory=true\nMaxMemAlloc=6144\n",
        );
        fixture(&root, "vanilla", r#"{"components":[{"uid":"net.minecraft","version":"26.3"}]}"#, "name=\n");
        std::fs::create_dir_all(root.join("not-an-instance")).unwrap();

        let found = scan(&[("Prism Launcher".into(), root.clone())]);
        assert_eq!(found.len(), 2);
        let neo = found.iter().find(|i| i.name == "Mein Pack").unwrap();
        assert_eq!(neo.mc_version, "1.21.1");
        assert_eq!(neo.loader, Loader::new(LoaderKind::NeoForge, "21.1.262".into()));
        assert_eq!(neo.mods, 1);
        // An empty name falls back to the folder name.
        assert!(found.iter().any(|i| i.name == "vanilla" && i.loader == Loader::Vanilla));
        let _ = std::fs::remove_dir_all(&root);
    }
}
