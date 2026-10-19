//! Custom instance icons: any PNG, JPG, GIF (first frame) or WebP, cropped to a
//! square and stored as `instances/<id>/icon.png` (256 px). Every image is decoded
//! and re-encoded, so the original file (and whatever else it carries) is never kept.

use std::path::{Path, PathBuf};

use image::{DynamicImage, ImageFormat, imageops::FilterType};
use serde::{Deserialize, Serialize};

use crate::{
    Error, Paths, Result, http,
    instance::{Instance, now},
};

pub const SIZE: u32 = 256;
const FILE: &str = "icon.png";
/// Larger downloads or files are refused before decoding.
const MAX_BYTES: usize = 16 * 1024 * 1024;

/// Square crop in source pixels; `None` crops the centre.
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct Crop {
    pub x: u32,
    pub y: u32,
    pub size: u32,
}

/// Stored on the instance; `updated` lets the UI bust its image cache.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InstanceIcon {
    pub updated: u64,
}

pub fn path(paths: &Paths, instance: &Instance) -> Option<PathBuf> {
    instance.icon.as_ref()?;
    let file = instance.dir(paths).join(FILE);
    file.is_file().then_some(file)
}

/// Decodes, crops, scales and encodes an icon; returns the PNG bytes.
pub fn process(bytes: &[u8], crop: Option<Crop>) -> Result<Vec<u8>> {
    if bytes.len() > MAX_BYTES {
        return Err(Error::Other("the image is larger than 16 MB".into()));
    }
    let img = image::load_from_memory(bytes).map_err(|e| Error::Other(format!("not a supported image: {e}")))?;
    let (w, h) = (img.width(), img.height());
    if w == 0 || h == 0 {
        return Err(Error::Other("the image is empty".into()));
    }
    let side = w.min(h);
    let crop = crop.unwrap_or(Crop { x: (w - side) / 2, y: (h - side) / 2, size: side });
    let size = crop.size.clamp(1, side);
    let x = crop.x.min(w - size);
    let y = crop.y.min(h - size);
    let square: DynamicImage = img.crop_imm(x, y, size, size);
    // Pixel art stays crisp; photos get a smooth downscale.
    let filter = if size <= 64 { FilterType::Nearest } else { FilterType::Lanczos3 };
    let scaled = square.resize_exact(SIZE, SIZE, filter).to_rgba8();
    let mut out = std::io::Cursor::new(Vec::new());
    scaled
        .write_to(&mut out, ImageFormat::Png)
        .map_err(|e| Error::Other(format!("could not encode the icon: {e}")))?;
    Ok(out.into_inner())
}

/// Sets the icon from image bytes and saves the instance.
pub fn set(paths: &Paths, instance: &mut Instance, bytes: &[u8], crop: Option<Crop>) -> Result<()> {
    let png = process(bytes, crop)?;
    std::fs::create_dir_all(instance.dir(paths))?;
    std::fs::write(instance.dir(paths).join(FILE), png)?;
    instance.icon = Some(InstanceIcon { updated: now() });
    instance.save(paths)
}

pub fn set_from_file(paths: &Paths, instance: &mut Instance, file: &Path, crop: Option<Crop>) -> Result<()> {
    if std::fs::metadata(file)?.len() as usize > MAX_BYTES {
        return Err(Error::Other("the image is larger than 16 MB".into()));
    }
    set(paths, instance, &std::fs::read(file)?, crop)
}

/// Downloads an icon (e.g. a modpack's project icon).
pub async fn set_from_url(paths: &Paths, instance: &mut Instance, url: &str) -> Result<()> {
    let bytes = http::client().get(url).send().await?.error_for_status()?.bytes().await?;
    set(paths, instance, &bytes, None)
}

pub fn clear(paths: &Paths, instance: &mut Instance) -> Result<()> {
    match std::fs::remove_file(instance.dir(paths).join(FILE)) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
        _ => {}
    }
    instance.icon = None;
    instance.save(paths)
}

/// Icon for a freshly imported instance: the project icon when there is one,
/// otherwise `minecraft/icon.png` from the pack. Failures only mean no icon.
pub async fn after_import(paths: &Paths, instance: &mut Instance, project_icon: Option<&str>) {
    if let Some(url) = project_icon
        && set_from_url(paths, instance, url).await.is_ok()
    {
        return;
    }
    adopt_from_game_dir(paths, instance);
}

/// Uses `minecraft/icon.png` as the icon when there is none yet. Prism, MultiMC and
/// exported `.mrpack`s keep the instance icon there.
pub fn adopt_from_game_dir(paths: &Paths, instance: &mut Instance) {
    if instance.icon.is_some() {
        return;
    }
    let file = instance.game_dir(paths).join(FILE);
    if file.is_file() {
        let _ = set_from_file(paths, instance, &file, None);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{Rgba, RgbaImage};

    fn png(w: u32, h: u32) -> Vec<u8> {
        let img = RgbaImage::from_fn(w, h, |x, _| if x < w / 2 { Rgba([255, 0, 0, 255]) } else { Rgba([0, 0, 255, 255]) });
        let mut out = std::io::Cursor::new(Vec::new());
        img.write_to(&mut out, ImageFormat::Png).unwrap();
        out.into_inner()
    }

    #[test]
    fn centre_crops_and_scales_to_square() {
        let icon = image::load_from_memory(&process(&png(400, 200), None).unwrap()).unwrap();
        assert_eq!((icon.width(), icon.height()), (SIZE, SIZE));
        // Centre crop of a red|blue image is half red, half blue.
        let rgba = icon.to_rgba8();
        assert_eq!(rgba.get_pixel(10, 128)[0], 255);
        assert_eq!(rgba.get_pixel(245, 128)[2], 255);
    }

    #[test]
    fn crop_is_clamped_to_the_image() {
        let icon = process(&png(100, 100), Some(Crop { x: 90, y: 90, size: 500 })).unwrap();
        assert_eq!(image::load_from_memory(&icon).unwrap().width(), SIZE);
    }

    #[test]
    fn rejects_non_images() {
        assert!(process(b"not an image", None).is_err());
    }
}
