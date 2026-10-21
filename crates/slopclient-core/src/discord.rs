//! Discord Rich Presence over Discord's local IPC pipe (`\\.\pipe\discord-ipc-N`).
//!
//! A background task owns the connection: callers only say which activity should
//! be shown, the task connects when Discord is running, reconnects when it
//! restarts and drops the status when there is nothing to show.

use std::time::Duration;

use serde::Serialize;
use serde_json::json;
use tokio::sync::watch;

/// What the status shows. Mirrors Discord's activity object.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Activity {
    /// First line, e.g. "Spielt Survival".
    pub details: String,
    /// Second line, e.g. "Auf mc.hypixel.net".
    pub state: Option<String>,
    /// Unix seconds; Discord shows the elapsed time from here.
    pub started_at: Option<u64>,
}

/// Handle to the presence task. Cheap to clone.
#[derive(Clone)]
pub struct Presence {
    tx: watch::Sender<(Option<String>, Option<Activity>)>,
}

impl Presence {
    /// The handle and the task to spawn on the async runtime. Nothing is sent
    /// until an app id and an activity are set.
    pub fn new() -> (Self, impl std::future::Future<Output = ()> + Send + 'static) {
        let (tx, rx) = watch::channel((None, None));
        (Self { tx }, run(rx))
    }

    /// Sets the Discord application (none switches Rich Presence off).
    pub fn set_app(&self, app_id: Option<String>) {
        self.tx.send_if_modified(|(app, _)| {
            let changed = *app != app_id;
            *app = app_id;
            changed
        });
    }

    pub fn set(&self, activity: Option<Activity>) {
        self.tx.send_if_modified(|(_, current)| {
            let changed = *current != activity;
            *current = activity;
            changed
        });
    }
}

#[cfg(windows)]
async fn run(mut rx: watch::Receiver<(Option<String>, Option<Activity>)>) {
    use tokio::net::windows::named_pipe::{ClientOptions, NamedPipeClient};

    async fn connect(app_id: &str) -> Option<NamedPipeClient> {
        for i in 0..10 {
            let Ok(mut pipe) = ClientOptions::new().open(format!(r"\\.\pipe\discord-ipc-{i}")) else { continue };
            if send(&mut pipe, 0, &json!({ "v": 1, "client_id": app_id })).await.is_some() && receive(&mut pipe).await.is_some() {
                return Some(pipe);
            }
        }
        None
    }

    let mut pipe: Option<(String, NamedPipeClient)> = None;
    loop {
        let (app_id, activity) = rx.borrow_and_update().clone();
        if pipe.as_ref().is_some_and(|(id, _)| Some(id) != app_id.as_ref()) {
            pipe = None;
        }
        if let Some(app_id) = &app_id {
            if pipe.is_none() && activity.is_some() {
                pipe = connect(app_id).await.map(|p| (app_id.clone(), p));
            }
            if let Some((_, p)) = pipe.as_mut() {
                let payload = json!({
                    "cmd": "SET_ACTIVITY",
                    "nonce": format!("{:x}", crate::instance::now()),
                    "args": { "pid": std::process::id(), "activity": activity.as_ref().map(to_discord) },
                });
                if send(p, 1, &payload).await.is_none() || receive(p).await.is_none() {
                    // Discord was closed.
                    pipe = None;
                }
            }
        }
        if app_id.is_some() && activity.is_some() && pipe.is_none() {
            // Discord is not running: look again in a while or on the next change.
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_secs(15)) => {}
                r = rx.changed() => if r.is_err() { return },
            }
        } else if rx.changed().await.is_err() {
            return;
        }
    }
}

#[cfg(not(windows))]
async fn run(_rx: watch::Receiver<(Option<String>, Option<Activity>)>) {}

fn to_discord(a: &Activity) -> serde_json::Value {
    let mut v = json!({
        "details": a.details,
        "assets": { "large_image": "logo", "large_text": "SlopClient" },
    });
    if let Some(state) = &a.state {
        v["state"] = json!(state);
    }
    if let Some(start) = a.started_at {
        v["timestamps"] = json!({ "start": start });
    }
    v
}

#[cfg(windows)]
async fn send(pipe: &mut tokio::net::windows::named_pipe::NamedPipeClient, op: u32, payload: &serde_json::Value) -> Option<()> {
    use tokio::io::AsyncWriteExt;
    let body = serde_json::to_vec(payload).ok()?;
    let mut frame = Vec::with_capacity(8 + body.len());
    frame.extend(op.to_le_bytes());
    frame.extend((body.len() as u32).to_le_bytes());
    frame.extend(body);
    tokio::time::timeout(Duration::from_secs(5), pipe.write_all(&frame)).await.ok()?.ok()
}

/// Reads one frame; Discord answers every command and the handshake.
#[cfg(windows)]
async fn receive(pipe: &mut tokio::net::windows::named_pipe::NamedPipeClient) -> Option<serde_json::Value> {
    use tokio::io::AsyncReadExt;
    let read = async {
        let mut header = [0u8; 8];
        pipe.read_exact(&mut header).await.ok()?;
        let op = u32::from_le_bytes(header[..4].try_into().ok()?);
        let len = u32::from_le_bytes(header[4..].try_into().ok()?) as usize;
        if len > 1 << 20 {
            return None;
        }
        let mut body = vec![0; len];
        pipe.read_exact(&mut body).await.ok()?;
        // op 2 = close (e.g. invalid client id).
        if op == 2 {
            return None;
        }
        serde_json::from_slice(&body).ok()
    };
    tokio::time::timeout(Duration::from_secs(5), read).await.ok()?
}
