use serde::{Deserialize, Serialize};

use crate::{Paths, Result};

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

    /// Client id from `DACLIENT_MSA_CLIENT_ID`, then settings, then the build-time default.
    pub fn msa_client_id(&self) -> Option<String> {
        std::env::var("DACLIENT_MSA_CLIENT_ID")
            .ok()
            .or_else(|| self.msa_client_id.clone())
            .or_else(|| option_env!("DACLIENT_MSA_CLIENT_ID").map(str::to_owned))
            .filter(|id| !id.trim().is_empty())
    }

    /// Key from `DACLIENT_CURSEFORGE_API_KEY`, then settings, then the build-time default.
    pub fn curseforge_api_key(&self) -> Option<String> {
        std::env::var("DACLIENT_CURSEFORGE_API_KEY")
            .ok()
            .or_else(|| self.curseforge_api_key.clone())
            .or_else(|| option_env!("DACLIENT_CURSEFORGE_API_KEY").map(str::to_owned))
            .filter(|key| !key.trim().is_empty())
    }

    pub fn download_concurrency(&self) -> usize {
        self.download_concurrency.unwrap_or(16).clamp(1, 64)
    }
}
