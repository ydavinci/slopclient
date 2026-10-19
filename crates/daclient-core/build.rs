//! Bundles the DaClient client mod (client-mod/, built with Gradle) when its jar exists.
//! Without it the launcher still builds; instances then just run without the mod.

use std::path::PathBuf;

fn main() {
    println!("cargo::rustc-check-cfg=cfg(client_mod)");
    let libs = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../client-mod/build/libs");
    println!("cargo::rerun-if-changed={}", libs.display());

    let jar = std::fs::read_dir(&libs).ok().and_then(|dir| {
        dir.flatten()
            .map(|e| e.path())
            .filter(|p| {
                let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
                name.starts_with("daclient-") && name.ends_with(".jar") && !name.contains("-sources")
            })
            .max_by_key(|p| p.metadata().and_then(|m| m.modified()).ok())
    });
    if let Some(jar) = jar {
        println!("cargo::rerun-if-changed={}", jar.display());
        println!("cargo::rustc-cfg=client_mod");
        println!("cargo::rustc-env=DACLIENT_MOD_JAR={}", jar.display());
        println!(
            "cargo::rustc-env=DACLIENT_MOD_FILE={}",
            jar.file_name().unwrap().to_string_lossy()
        );
    } else {
        println!("cargo::warning=client-mod jar not found; build it with `client-mod/gradlew build` to bundle the DaClient mod");
    }
}
