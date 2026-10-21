use std::path::PathBuf;

use crate::{Error, Result};

/// Root of all launcher data (`%APPDATA%\SlopClient` by default).
///
/// Libraries, assets, version files and Java runtimes are shared between
/// instances; each instance only owns its own game directory.
#[derive(Debug, Clone)]
pub struct Paths {
    pub root: PathBuf,
}

/// Data folder of the launcher before it was renamed to SlopClient.
const LEGACY_DIR: &str = "DaClient";

impl Paths {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    /// `SLOPCLIENT_HOME` (or the older `DACLIENT_HOME`) if set, otherwise the
    /// roaming app data directory. Data from before the rename to SlopClient
    /// (`%APPDATA%\DaClient`) is moved over on the first start.
    ///
    /// The root is resolved to its real location: junctions, symlinks and
    /// packaged-app (MSIX) redirection would otherwise make Java report
    /// different paths than the classpath we pass, which breaks Fabric/Quilt.
    pub fn from_env() -> Result<Self> {
        let root = match std::env::var_os("SLOPCLIENT_HOME").or_else(|| std::env::var_os("DACLIENT_HOME")) {
            Some(home) => PathBuf::from(home),
            None => {
                let data = dirs::data_dir().ok_or_else(|| Error::Other("no app data directory".into()))?;
                let root = data.join("SlopClient");
                let legacy = data.join(LEGACY_DIR);
                if !root.exists() && legacy.is_dir() {
                    // Still in use (the old launcher is open): keep using the old folder.
                    if std::fs::rename(&legacy, &root).is_err() {
                        return Ok(Self::new(dunce::canonicalize(&legacy).unwrap_or(legacy)));
                    }
                }
                root
            }
        };
        std::fs::create_dir_all(&root)?;
        Ok(Self::new(dunce::canonicalize(&root).unwrap_or(root)))
    }

    pub fn ensure(&self) -> Result<()> {
        for dir in [
            self.libraries(),
            self.assets(),
            self.versions(),
            self.java(),
            self.instances(),
            self.cache(),
        ] {
            std::fs::create_dir_all(dir)?;
        }
        Ok(())
    }

    pub fn libraries(&self) -> PathBuf {
        self.root.join("libraries")
    }
    pub fn assets(&self) -> PathBuf {
        self.root.join("assets")
    }
    pub fn versions(&self) -> PathBuf {
        self.root.join("versions")
    }
    pub fn java(&self) -> PathBuf {
        self.root.join("java")
    }
    pub fn instances(&self) -> PathBuf {
        self.root.join("instances")
    }
    pub fn cache(&self) -> PathBuf {
        self.root.join("cache")
    }
    pub fn settings_file(&self) -> PathBuf {
        self.root.join("settings.json")
    }
    pub fn accounts_file(&self) -> PathBuf {
        self.root.join("accounts.json")
    }
    pub fn secrets_file(&self) -> PathBuf {
        self.root.join("secrets.dat")
    }

    pub fn version_dir(&self, id: &str) -> PathBuf {
        self.versions().join(id)
    }
    pub fn version_json(&self, id: &str) -> PathBuf {
        self.version_dir(id).join(format!("{id}.json"))
    }
    pub fn version_jar(&self, id: &str) -> PathBuf {
        self.version_dir(id).join(format!("{id}.jar"))
    }
}

/// Joins a `/`-separated relative path (as used in Maven paths and manifests)
/// using native separators. Fabric compares classpath entries literally, so
/// mixed `\` and `/` would break its library detection.
pub fn join_rel(base: &std::path::Path, rel: &str) -> PathBuf {
    rel.split('/').filter(|c| !c.is_empty()).fold(base.to_path_buf(), |p, c| p.join(c))
}
