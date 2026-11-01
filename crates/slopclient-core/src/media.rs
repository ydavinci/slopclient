//! The music helper the client mod starts (`<launcher or CLI> --media-helper`): what a music
//! app is playing, read from the Windows media session (the one behind the volume flyout),
//! with play/pause and skip. Only music players count; browsers and video players are left
//! out, so a YouTube video never shows up as your song.
//!
//! Protocol, one line each way:
//! - out: JSON `{"app","title","artist","album","playing","positionMs","durationMs","cover"?}`
//!   whenever something changes and once a second while playing (`cover` is a base64 PNG,
//!   sent only when the track changes), `{"none":true}` when no music app plays, or
//!   `{"error":"..."}` when the media session is unavailable.
//! - in: `toggle`, `next`, `previous`, `share on`, `share off`. End of input ends the helper.
//!
//! With `share on`, the helper itself tells the cosmetics server what plays (through the
//! launcher's signed-in session, which never reaches the game), and clears it on pause,
//! `share off` and exit.

use std::time::{Duration, Instant};

use serde::Serialize;

use crate::paths::Paths;

/// Music apps whose session counts, matched in their app id (case-insensitive). Browsers,
/// video players and everything else are ignored.
pub const MUSIC_APPS: &[(&str, &str)] = &[
    ("spotify", "Spotify"),
    ("cider", "Cider"),
    ("applemusic", "Apple Music"),
    ("itunes", "iTunes"),
    ("tidal", "TIDAL"),
    ("deezer", "Deezer"),
    ("amazonmusic", "Amazon Music"),
    ("foobar2000", "foobar2000"),
    ("musicbee", "MusicBee"),
    ("aimp", "AIMP"),
    ("winamp", "Winamp"),
];

/// The display name of a music app from its media session id, or None for anything else.
pub fn music_app(app_id: &str) -> Option<&'static str> {
    let id = app_id.to_ascii_lowercase();
    MUSIC_APPS.iter().find(|(key, _)| id.contains(key)).map(|(_, name)| *name)
}

#[derive(Debug, Clone, Default, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NowPlaying {
    pub app: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub position_ms: u64,
    pub duration_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cover: Option<String>,
}

impl NowPlaying {
    fn track(&self) -> (&str, &str, &str) {
        (&self.app, &self.title, &self.artist)
    }
}

enum Event {
    Media(Option<NowPlaying>),
    Error(String),
    Command(String),
    Eof,
}

/// Runs the helper until its input ends.
pub fn run(paths: &Paths) -> crate::Result<()> {
    let (tx, rx) = std::sync::mpsc::channel::<Event>();
    let (control_tx, control_rx) = std::sync::mpsc::channel::<String>();
    {
        let tx = tx.clone();
        std::thread::spawn(move || {
            let stdin = std::io::stdin();
            let mut line = String::new();
            loop {
                line.clear();
                match stdin.read_line(&mut line) {
                    Ok(0) | Err(_) => {
                        let _ = tx.send(Event::Eof);
                        return;
                    }
                    Ok(_) => {
                        let _ = tx.send(Event::Command(line.trim().to_owned()));
                    }
                }
            }
        });
    }
    {
        let tx = tx.clone();
        std::thread::spawn(move || watch(tx, control_rx));
    }

    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let mut sharing = false;
    let mut published: Option<(String, String)> = None;
    let mut last_put = Instant::now();
    let mut current: Option<NowPlaying> = None;
    loop {
        let event = rx.recv_timeout(Duration::from_secs(15)).ok();
        match event {
            Some(Event::Eof) => break,
            Some(Event::Command(cmd)) => match cmd.as_str() {
                "share on" => sharing = true,
                "share off" => sharing = false,
                "toggle" | "next" | "previous" => {
                    let _ = control_tx.send(cmd);
                }
                _ => {}
            },
            Some(Event::Media(now)) => {
                emit(&match &now {
                    Some(n) => serde_json::to_value(n).unwrap_or_default(),
                    None => serde_json::json!({ "none": true }),
                });
                current = now;
            }
            Some(Event::Error(e)) => emit(&serde_json::json!({ "error": e })),
            None => {}
        }
        // What other players should see: the playing track while sharing, otherwise nothing.
        let wanted = current.as_ref().filter(|n| sharing && n.playing).map(|n| (n.title.clone(), n.artist.clone()));
        let stale = last_put.elapsed() > Duration::from_secs(60);
        if wanted != published || (wanted.is_some() && stale) {
            runtime.block_on(publish(paths, wanted.as_ref()));
            published = wanted;
            last_put = Instant::now();
        }
    }
    if published.is_some() {
        runtime.block_on(publish(paths, None));
    }
    Ok(())
}

fn emit(value: &serde_json::Value) {
    use std::io::Write;
    let mut out = std::io::stdout().lock();
    let _ = writeln!(out, "{value}");
    let _ = out.flush();
}

/// Sets (or with None clears) the shared song on the cosmetics server. Failures are only
/// skipped: without a sign-in or a server, the song simply isn't shared.
async fn publish(paths: &Paths, song: Option<&(String, String)>) {
    let Some(session) = crate::cosmetics_online::session(paths) else { return };
    let url = format!("{}/v1/listening", session.server);
    let client = crate::http::client();
    let request = match song {
        Some((title, artist)) => client.put(url).json(&serde_json::json!({ "title": title, "artist": artist })),
        None => client.delete(url),
    };
    let _ = request.bearer_auth(&session.token).timeout(Duration::from_secs(10)).send().await;
}

#[cfg(not(windows))]
fn watch(tx: std::sync::mpsc::Sender<Event>, _control: std::sync::mpsc::Receiver<String>) {
    let _ = tx.send(Event::Error("Nur unter Windows verfügbar".into()));
}

/// Polls the media session twice a second and runs control commands, on one thread.
#[cfg(windows)]
fn watch(tx: std::sync::mpsc::Sender<Event>, control: std::sync::mpsc::Receiver<String>) {
    use windows::Media::Control::GlobalSystemMediaTransportControlsSessionManager as Manager;

    unsafe {
        let _ = windows::Win32::System::WinRT::RoInitialize(windows::Win32::System::WinRT::RO_INIT_MULTITHREADED);
    }
    let mut manager: Option<Manager> = None;
    let mut last: Option<NowPlaying> = None;
    let mut cover: Option<(String, Option<String>)> = None; // track key, base64 PNG
    let mut last_emit = Instant::now();
    let mut reported_error = false;
    loop {
        if manager.is_none() {
            match Manager::RequestAsync().and_then(|op| op.join()) {
                Ok(m) => manager = Some(m),
                Err(e) => {
                    if !reported_error {
                        let _ = tx.send(Event::Error(format!("Windows-Mediensteuerung nicht verfügbar: {e}")));
                        reported_error = true;
                    }
                    std::thread::sleep(Duration::from_secs(5));
                    continue;
                }
            }
        }
        let m = manager.as_ref().expect("set above");
        let session = pick(m);
        while let Ok(cmd) = control.try_recv() {
            if let Some((s, _)) = &session {
                let _ = match cmd.as_str() {
                    "toggle" => s.TryTogglePlayPauseAsync().and_then(|op| op.join()).map(|_| ()),
                    "next" => s.TrySkipNextAsync().and_then(|op| op.join()).map(|_| ()),
                    _ => s.TrySkipPreviousAsync().and_then(|op| op.join()).map(|_| ()),
                };
            }
        }
        let now = session.as_ref().and_then(|(s, app)| read(s, app).ok().map(|n| (s, n)));
        let now = now.map(|(s, mut n)| {
            let key = format!("{}\u{1}{}\u{1}{}", n.app, n.title, n.artist);
            match &cover {
                Some((k, _)) if *k == key => {}
                _ => {
                    // A new track: fetch its cover once (the session may need a moment).
                    let art = cover_png(s);
                    n.cover = art.clone();
                    if art.is_some() {
                        cover = Some((key, art));
                    } else {
                        cover = None;
                    }
                }
            }
            n
        });
        let changed = now.as_ref().map(NowPlaying::track) != last.as_ref().map(NowPlaying::track)
            || now.as_ref().map(|n| n.playing) != last.as_ref().map(|n| n.playing)
            || now.as_ref().is_some_and(|n| n.cover.is_some());
        let tick = now.as_ref().is_some_and(|n| n.playing) && last_emit.elapsed() >= Duration::from_secs(1);
        if changed || tick {
            if tx.send(Event::Media(now.clone())).is_err() {
                return;
            }
            last_emit = Instant::now();
        }
        last = now.map(|mut n| {
            n.cover = None;
            n
        });
        std::thread::sleep(Duration::from_millis(500));
    }

    /// The music app's session to show: one that is playing if any, else the first paused one.
    fn pick(m: &Manager) -> Option<(windows::Media::Control::GlobalSystemMediaTransportControlsSession, &'static str)> {
        use windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status;
        let sessions = m.GetSessions().ok()?;
        let mut paused = None;
        for s in sessions {
            let Some(app) = s.SourceAppUserModelId().ok().and_then(|id| music_app(&id.to_string())) else { continue };
            let playing = s.GetPlaybackInfo().and_then(|p| p.PlaybackStatus()).is_ok_and(|st| st == Status::Playing);
            if playing {
                return Some((s, app));
            }
            paused.get_or_insert((s, app));
        }
        paused
    }

    fn read(s: &windows::Media::Control::GlobalSystemMediaTransportControlsSession, app: &str) -> windows::core::Result<NowPlaying> {
        use windows::Media::Control::GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status;
        let props = s.TryGetMediaPropertiesAsync()?.join()?;
        let playing = s.GetPlaybackInfo()?.PlaybackStatus()? == Status::Playing;
        let timeline = s.GetTimelineProperties()?;
        // TimeSpan and DateTime count 100 ns ticks; the position is as of LastUpdatedTime.
        let mut position = timeline.Position()?.Duration.max(0) as u64 / 10_000;
        let duration = (timeline.EndTime()?.Duration - timeline.StartTime()?.Duration).max(0) as u64 / 10_000;
        if playing {
            let updated = timeline.LastUpdatedTime()?.UniversalTime;
            let now = windows_now();
            if updated > 0 && now > updated {
                position += ((now - updated) / 10_000) as u64;
            }
        }
        if duration > 0 {
            position = position.min(duration);
        }
        Ok(NowPlaying {
            app: app.to_owned(),
            title: props.Title()?.to_string(),
            artist: props.Artist()?.to_string(),
            album: props.AlbumTitle()?.to_string(),
            playing,
            position_ms: position,
            duration_ms: duration,
            cover: None,
        })
    }

    /// Now as a Windows DateTime (100 ns ticks since 1601).
    fn windows_now() -> i64 {
        let unix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
        (unix.as_nanos() / 100) as i64 + 116_444_736_000_000_000
    }

    /// The session's cover as a 64 x 64 PNG in base64.
    fn cover_png(session: &windows::Media::Control::GlobalSystemMediaTransportControlsSession) -> Option<String> {
        let props = session.TryGetMediaPropertiesAsync().ok()?.join().ok()?;
        let stream = props.Thumbnail().ok()?.OpenReadAsync().ok()?.join().ok()?;
        let size = stream.Size().ok()? as u32;
        if size == 0 || size > 8 * 1024 * 1024 {
            return None;
        }
        let reader = windows::Storage::Streams::DataReader::CreateDataReader(&stream).ok()?;
        reader.LoadAsync(size).ok()?.join().ok()?;
        let mut bytes = vec![0u8; size as usize];
        reader.ReadBytes(&mut bytes).ok()?;
        let img = image::load_from_memory(&bytes).ok()?;
        // Square it (Spotify's covers are, some apps add bars), then shrink.
        let side = img.width().min(img.height());
        let img = img.crop_imm((img.width() - side) / 2, (img.height() - side) / 2, side, side).resize_exact(64, 64, image::imageops::FilterType::Triangle);
        let mut png = std::io::Cursor::new(Vec::new());
        img.write_to(&mut png, image::ImageFormat::Png).ok()?;
        use base64::Engine;
        Some(base64::engine::general_purpose::STANDARD.encode(png.into_inner()))
    }
}

#[cfg(test)]
mod tests {
    use super::music_app;

    #[test]
    fn only_music_apps_count() {
        assert_eq!(music_app("Spotify.exe"), Some("Spotify"));
        assert_eq!(music_app("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"), Some("Spotify"));
        assert_eq!(music_app("AppleInc.AppleMusicWin_nzyj5cx40ttak!App"), Some("Apple Music"));
        assert_eq!(music_app("Cider.exe"), Some("Cider"));
        assert_eq!(music_app("chrome.exe"), None);
        assert_eq!(music_app("MSEdge"), None);
        assert_eq!(music_app("308046B0AF4A39CB"), None); // Firefox
        assert_eq!(music_app("vlc.exe"), None);
    }
}
