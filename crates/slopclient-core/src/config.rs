use serde::{Deserialize, Serialize};

use crate::{Paths, Result};

/// `SLOPCLIENT_<name>`, or `DACLIENT_<name>` from before the rename.
pub fn env(name: &str) -> Option<String> {
    std::env::var(format!("SLOPCLIENT_{name}"))
        .or_else(|_| std::env::var(format!("DACLIENT_{name}")))
        .ok()
        .filter(|v| !v.is_empty())
}

/// Azure application (client) id of SlopClient. Not a secret: it ships in every build.
pub const SLOPCLIENT_MSA_CLIENT_ID: &str = "3741ed40-b503-47b7-aa63-d5e9258db81e";

/// SlopClient's own cosmetics server (deploy/cosmetics).
pub const SLOPCLIENT_COSMETICS_URL: &str = "https://cosmetics.ydavinci.me";

/// Discord application of SlopClient (name and logo of the Rich Presence).
/// Its "logo" art asset is the large image of the status.
pub const SLOPCLIENT_DISCORD_APP_ID: &str = "1562576081704325221";

/// Positions of the start page blocks as fractions of the free space (x, y).
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct HomeLayout {
    pub hero: [f32; 2],
    pub actions: [f32; 2],
    pub strip: [f32; 2],
}

/// Global launcher settings stored in `settings.json`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    /// Azure application (client) id used for Microsoft login.
    pub msa_client_id: Option<String>,
    /// CurseForge API key (console.curseforge.com).
    pub curseforge_api_key: Option<String>,
    /// Parallel downloads.
    pub download_concurrency: Option<usize>,
    /// Cape from the Cape Studio that the client mod shows.
    pub active_cape: Option<String>,
    /// Accent hue (OKLCH degrees) for launcher and client mod; none keeps the violet.
    pub accent_hue: Option<f32>,
    /// Start page and in-game title screen centred instead of left-aligned.
    pub centered_layout: bool,
    /// Custom start page layout; none uses the left or centred preset.
    pub home_layout: Option<HomeLayout>,
    /// SlopClient cosmetics server, e.g. `https://cosmetics.example.com`.
    pub cosmetics_url: Option<String>,
    /// Show what you play in Discord; none means on.
    pub discord_presence: Option<bool>,
    /// Leave the server address out of the Discord status.
    pub discord_hide_server: bool,
    /// Look for content updates of all instances when the launcher starts; none means on.
    pub check_updates_on_start: Option<bool>,
    /// Keep mods, resource packs and shaders once in the content store and hard link
    /// them into instances; none means on.
    pub shared_content: Option<bool>,
    /// Look for a new launcher version at start and every 6 hours; none means on.
    pub launcher_update_check: Option<bool>,
    /// Install a launcher update found at start without asking.
    pub launcher_auto_install: bool,
    /// Hat from `cosmetics/extras.json`.
    pub hat: Option<String>,
    /// Pet from `cosmetics/extras.json`.
    pub pet: Option<String>,
    /// Accessory from `cosmetics/extras.json` (worn on the body, e.g. a katana).
    pub accessory: Option<String>,
}

impl Settings {
    pub fn load(paths: &Paths) -> Result<Self> {
        match std::fs::read_to_string(paths.settings_file()) {
            Ok(text) => Ok(serde_json::from_str(&text)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Self::default()),
            Err(e) => Err(e.into()),
        }
    }

    pub fn save(&self, paths: &Paths) -> Result<()> {
        std::fs::create_dir_all(&paths.root)?;
        std::fs::write(paths.settings_file(), serde_json::to_string_pretty(self)?)?;
        Ok(())
    }

    /// Client id from `SLOPCLIENT_MSA_CLIENT_ID`, then settings, then the build-time
    /// value, then SlopClient's own Azure app (approved by Mojang for the Minecraft API).
    pub fn msa_client_id(&self) -> Option<String> {
        env("MSA_CLIENT_ID")
            .or_else(|| self.msa_client_id.clone())
            .or_else(|| option_env!("SLOPCLIENT_MSA_CLIENT_ID").map(str::to_owned))
            .filter(|id| !id.trim().is_empty())
            .or_else(|| Some(SLOPCLIENT_MSA_CLIENT_ID.to_owned()))
    }

    /// Key from `SLOPCLIENT_CURSEFORGE_API_KEY`, then settings, then the build-time default.
    pub fn curseforge_api_key(&self) -> Option<String> {
        env("CURSEFORGE_API_KEY")
            .or_else(|| self.curseforge_api_key.clone())
            .or_else(|| option_env!("SLOPCLIENT_CURSEFORGE_API_KEY").or(option_env!("DACLIENT_CURSEFORGE_API_KEY")).map(str::to_owned))
            .filter(|key| !key.trim().is_empty())
    }

    /// Server from `SLOPCLIENT_COSMETICS_URL`, then settings, then the build-time default.
    pub fn cosmetics_url(&self) -> Option<String> {
        env("COSMETICS_URL")
            .or_else(|| self.cosmetics_url.clone())
            .or_else(|| option_env!("SLOPCLIENT_COSMETICS_URL").map(str::to_owned))
            .map(|u| u.trim().trim_end_matches('/').to_owned())
            .filter(|u| !u.is_empty())
            .or_else(|| Some(SLOPCLIENT_COSMETICS_URL.to_owned()))
    }

    /// Discord application id from `SLOPCLIENT_DISCORD_APP_ID`, then the build-time
    /// value, then SlopClient's own app; none while Rich Presence is switched off.
    pub fn shared_content(&self) -> bool {
        self.shared_content.unwrap_or(true)
    }

    pub fn check_updates_on_start(&self) -> bool {
        self.check_updates_on_start.unwrap_or(true)
    }

    pub fn launcher_update_check(&self) -> bool {
        self.launcher_update_check.unwrap_or(true)
    }

    pub fn discord_app_id(&self) -> Option<String> {
        if !self.discord_presence.unwrap_or(true) {
            return None;
        }
        env("DISCORD_APP_ID")
            .or_else(|| option_env!("SLOPCLIENT_DISCORD_APP_ID").map(str::to_owned))
            .or_else(|| Some(SLOPCLIENT_DISCORD_APP_ID.to_owned()))
            .filter(|id| !id.trim().is_empty())
    }

    pub fn download_concurrency(&self) -> usize {
        self.download_concurrency.unwrap_or(16).clamp(1, 64)
    }
}
