//! The SlopClient client mod (title screen, ClickGUI, HUD) that the launcher puts
//! into supported Fabric/Quilt instances before each start.

use crate::{
    Paths, ProgressFn, Result,
    content::ContentManager,
    instance::Instance,
    meta::LoaderKind,
    progress::stage,
};

/// Minecraft versions the bundled mod is built for (`fabric.mod.json` `~26.3`).
pub const SUPPORTED_MC: &[&str] = &["26.3"];

const PREFIX: &str = "slopclient-";
/// Jar name of the mod before the rename to SlopClient.
const LEGACY_PREFIX: &str = "daclient-";

#[cfg(client_mod)]
fn bundled() -> Option<(&'static str, &'static [u8])> {
    Some((env!("SLOPCLIENT_MOD_FILE"), include_bytes!(env!("SLOPCLIENT_MOD_JAR"))))
}

#[cfg(not(client_mod))]
fn bundled() -> Option<(&'static str, &'static [u8])> {
    None
}

/// Whether this launcher build carries the mod at all.
pub fn available() -> bool {
    bundled().is_some()
}

/// Fabric or Quilt on a Minecraft version the mod is built for (patch releases included).
pub fn supports(instance: &Instance) -> bool {
    matches!(instance.loader.kind(), Some(LoaderKind::Fabric | LoaderKind::Quilt))
        && SUPPORTED_MC
            .iter()
            .any(|v| instance.mc_version == *v || instance.mc_version.starts_with(&format!("{v}.")))
}

/// Files of the mod, which the content list hides (the launcher manages them).
pub fn is_mod_file(file_name: &str) -> bool {
    let base = file_name.trim_end_matches(crate::content::DISABLED_SUFFIX);
    (base.starts_with(PREFIX) || base.starts_with(LEGACY_PREFIX)) && base.ends_with(".jar")
}

/// Puts the current mod jar into the instance (or removes it when turned off or
/// unsupported) and makes sure Fabric API, which the mod needs, is installed.
pub async fn sync(paths: &Paths, instance: &Instance, concurrency: usize, progress: &ProgressFn) -> Result<()> {
    migrate_legacy(&instance.game_dir(paths));
    let mods = instance.game_dir(paths).join("mods");
    let wanted = match bundled() {
        Some(jar) if instance.client_mod && supports(instance) => Some(jar),
        _ => None,
    };

    // Old versions (and the current one when it's turned off) go away.
    if let Ok(dir) = std::fs::read_dir(&mods) {
        for entry in dir.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if is_mod_file(&name) && wanted.is_none_or(|(file, _)| name != file) {
                std::fs::remove_file(entry.path())?;
            }
        }
    }
    let Some((file, bytes)) = wanted else {
        return Ok(());
    };

    stage(progress, "Installing SlopClient mod");
    std::fs::create_dir_all(&mods)?;
    let target = mods.join(file);
    if std::fs::read(&target).ok().as_deref() != Some(bytes) {
        std::fs::write(&target, bytes)?;
    }

    let manager = ContentManager::new(paths, instance.clone(), concurrency);
    let has_fabric_api = manager.list().await?.iter().any(|e| {
        e.file_name.starts_with("fabric-api")
            || matches!(&e.source, crate::content::Source::Modrinth { slug: Some(s), .. } if s == "fabric-api")
            || matches!(&e.source, crate::content::Source::CurseForge { slug: Some(s), .. } if s == "fabric-api")
    });
    if !has_fabric_api {
        stage(progress, "Installing Fabric API");
        manager.install("fabric-api", progress).await?;
    }
    Ok(())
}

/// Carries an instance over from DaClient: the mod's config folder and its keybindings.
fn migrate_legacy(game_dir: &std::path::Path) {
    let (old, new) = (game_dir.join("config").join("daclient"), game_dir.join("config").join("slopclient"));
    if old.is_dir() && !new.exists() {
        let _ = std::fs::rename(&old, &new);
    }
    let options = game_dir.join("options.txt");
    if let Ok(text) = std::fs::read_to_string(&options) {
        if text.contains("key_key.daclient.") {
            let _ = std::fs::write(&options, text.replace("key_key.daclient.", "key_key.slopclient."));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::Loader;

    fn instance(mc: &str, loader: Loader) -> Instance {
        let mut i: Instance = serde_json::from_str(&format!(
            r#"{{"id":"x","name":"x","mcVersion":"{mc}","createdAt":0}}"#
        ))
        .unwrap();
        i.loader = loader;
        i
    }

    #[test]
    fn supports_matching_fabric_versions_only() {
        let fabric = Loader::new(LoaderKind::Fabric, "0.19.5".into());
        assert!(supports(&instance("26.3", fabric.clone())));
        assert!(supports(&instance("26.3.1", fabric.clone())));
        assert!(!supports(&instance("26.30", fabric.clone())));
        assert!(!supports(&instance("1.21.10", fabric)));
        assert!(!supports(&instance("26.3", Loader::Vanilla)));
        assert!(!supports(&instance("26.3", Loader::new(LoaderKind::NeoForge, "26.3.0.92".into()))));
    }

    #[test]
    fn old_instances_default_to_the_mod_on() {
        assert!(instance("26.3", Loader::Vanilla).client_mod);
    }

    #[test]
    fn recognises_mod_files() {
        assert!(is_mod_file("slopclient-0.1.0.jar"));
        assert!(is_mod_file("slopclient-0.1.0.jar.disabled"));
        assert!(!is_mod_file("slopclient-config.json"));
        assert!(!is_mod_file("sodium-fabric.jar"));
    }
}
