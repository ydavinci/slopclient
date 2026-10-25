//! Embeds the pet textures (cosmetics/pets/*.png at the repository root) so the server can
//! hand them to the mod, the launcher and the web page together with the catalogue.

use std::{fmt::Write, path::PathBuf};

fn main() {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../cosmetics/pets");
    println!("cargo::rerun-if-changed={}", dir.display());
    let mut files: Vec<PathBuf> = std::fs::read_dir(&dir)
        .map(|d| d.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|e| e == "png")).collect())
        .unwrap_or_default();
    files.sort();
    let mut out = String::from("pub static PET_TEXTURES: &[(&str, &[u8])] = &[\n");
    for path in &files {
        let name = path.file_name().unwrap().to_string_lossy();
        let path = dunce_path(path);
        writeln!(out, "    ({name:?}, include_bytes!({path:?})),").unwrap();
    }
    out.push_str("];\n");
    std::fs::write(PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("pet_textures.rs"), out).unwrap();
}

/// Absolute path without Windows' `\\?\` prefix, as include_bytes! wants it.
fn dunce_path(path: &std::path::Path) -> String {
    let p = path.canonicalize().unwrap_or_else(|_| path.to_owned()).to_string_lossy().into_owned();
    p.strip_prefix(r"\\?\").map(str::to_owned).unwrap_or(p)
}
