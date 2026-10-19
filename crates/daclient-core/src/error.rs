#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("network error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("invalid json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid archive: {0}")]
    Zip(#[from] zip::result::ZipError),
    #[error("hash mismatch for {path}: expected {expected}, got {actual}")]
    HashMismatch {
        path: String,
        expected: String,
        actual: String,
    },
    #[error("version not found: {0}")]
    VersionNotFound(String),
    #[error("instance not found: {0}")]
    InstanceNotFound(String),
    #[error("account not found: {0}")]
    AccountNotFound(String),
    #[error("authentication failed: {0}")]
    Auth(String),
    #[error("no Microsoft client id configured (set DACLIENT_MSA_CLIENT_ID or msaClientId in settings.json)")]
    MissingClientId,
    #[error("no CurseForge API key configured (set DACLIENT_CURSEFORGE_API_KEY or curseforgeApiKey in settings.json)")]
    MissingCurseForgeKey,
    #[error("{0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, Error>;

impl From<tokio::task::JoinError> for Error {
    fn from(e: tokio::task::JoinError) -> Self {
        Error::Other(format!("background task failed: {e}"))
    }
}
