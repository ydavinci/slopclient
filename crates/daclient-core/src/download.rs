use std::{
    collections::HashSet,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, AtomicUsize, Ordering},
    time::Duration,
};

use futures::{StreamExt, TryStreamExt, stream};
use sha1::{Digest, Sha1};
use tokio::io::AsyncWriteExt;

use crate::{Error, Progress, ProgressFn, Result, http};

const RETRIES: u64 = 3;

#[derive(Debug, Clone)]
pub struct DownloadTask {
    pub url: String,
    pub path: PathBuf,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

impl DownloadTask {
    pub fn new(url: impl Into<String>, path: impl Into<PathBuf>) -> Self {
        Self {
            url: url.into(),
            path: path.into(),
            sha1: None,
            size: None,
        }
    }

    pub fn sha1(mut self, sha1: Option<String>) -> Self {
        self.sha1 = sha1;
        self
    }

    pub fn size(mut self, size: Option<u64>) -> Self {
        self.size = size;
        self
    }
}

pub async fn sha1_file(path: &Path) -> Result<String> {
    let path = path.to_owned();
    tokio::task::spawn_blocking(move || -> Result<String> {
        let mut file = std::fs::File::open(&path)?;
        let mut hasher = Sha1::new();
        std::io::copy(&mut file, &mut hasher)?;
        Ok(hex::encode(hasher.finalize()))
    })
    .await?
}

/// Existing files are trusted when their size matches; the hash is only
/// computed when no size is known. Every fresh download is hash-checked.
async fn is_up_to_date(task: &DownloadTask) -> Result<bool> {
    let Ok(meta) = tokio::fs::metadata(&task.path).await else {
        return Ok(false);
    };
    if let Some(size) = task.size {
        return Ok(meta.len() == size);
    }
    if let Some(expected) = &task.sha1 {
        return Ok(sha1_file(&task.path).await?.eq_ignore_ascii_case(expected));
    }
    Ok(true)
}

/// Downloads every missing or outdated file, `concurrency` at a time.
pub async fn download_all(
    tasks: Vec<DownloadTask>,
    concurrency: usize,
    progress: &ProgressFn,
) -> Result<()> {
    let mut seen = HashSet::new();
    let mut pending = Vec::new();
    for task in tasks {
        if seen.insert(task.path.clone()) && !is_up_to_date(&task).await? {
            pending.push(task);
        }
    }
    if pending.is_empty() {
        return Ok(());
    }

    let total = pending.len();
    let total_bytes = pending.iter().filter_map(|t| t.size).sum();
    let finished = AtomicUsize::new(0);
    let bytes = AtomicU64::new(0);
    progress(Progress::Download {
        finished: 0,
        total,
        bytes: 0,
        total_bytes,
    });

    stream::iter(pending.into_iter().map(Ok::<_, Error>))
        .try_for_each_concurrent(concurrency.max(1), |task| {
            let (finished, bytes) = (&finished, &bytes);
            async move {
                download_with_retry(&task, bytes).await?;
                let done = finished.fetch_add(1, Ordering::Relaxed) + 1;
                progress(Progress::Download {
                    finished: done,
                    total,
                    bytes: bytes.load(Ordering::Relaxed),
                    total_bytes,
                });
                Ok(())
            }
        })
        .await
}

async fn download_with_retry(task: &DownloadTask, bytes: &AtomicU64) -> Result<()> {
    let mut attempt = 0;
    loop {
        match download_one(task, bytes).await {
            Ok(()) => return Ok(()),
            Err(e) if attempt + 1 >= RETRIES => return Err(e),
            Err(_) => {
                attempt += 1;
                tokio::time::sleep(Duration::from_millis(500 * attempt)).await;
            }
        }
    }
}

async fn download_one(task: &DownloadTask, bytes: &AtomicU64) -> Result<()> {
    if let Some(parent) = task.path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let part = part_path(&task.path);
    let response = http::client()
        .get(&task.url)
        .send()
        .await?
        .error_for_status()?;

    let mut file = tokio::fs::File::create(&part).await?;
    let mut hasher = Sha1::new();
    let mut body = response.bytes_stream();
    while let Some(chunk) = body.next().await {
        let chunk = chunk?;
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
        bytes.fetch_add(chunk.len() as u64, Ordering::Relaxed);
    }
    file.flush().await?;
    drop(file);

    let actual = hex::encode(hasher.finalize());
    if let Some(expected) = &task.sha1
        && !actual.eq_ignore_ascii_case(expected) {
            let _ = tokio::fs::remove_file(&part).await;
            return Err(Error::HashMismatch {
                path: task.path.display().to_string(),
                expected: expected.clone(),
                actual,
            });
        }
    tokio::fs::rename(&part, &task.path).await?;
    Ok(())
}

fn part_path(path: &Path) -> PathBuf {
    let mut s = path.as_os_str().to_owned();
    s.push(".part");
    PathBuf::from(s)
}
