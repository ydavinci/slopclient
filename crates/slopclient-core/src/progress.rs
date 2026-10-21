use std::sync::Arc;

use serde::Serialize;

/// Progress events emitted by long-running operations (install, download, login).
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Progress {
    /// A new phase started, e.g. "Downloading libraries".
    Stage { name: String },
    /// Aggregate download progress for the current phase.
    #[serde(rename_all = "camelCase")]
    Download {
        finished: usize,
        total: usize,
        bytes: u64,
        total_bytes: u64,
    },
}

pub type ProgressFn = Arc<dyn Fn(Progress) + Send + Sync>;

pub fn noop() -> ProgressFn {
    Arc::new(|_| {})
}

pub(crate) fn stage(progress: &ProgressFn, name: impl Into<String>) {
    progress(Progress::Stage { name: name.into() });
}
