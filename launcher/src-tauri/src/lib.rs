//! Tauri layer: exposes `slopclient-core` to the UI as commands and events.
//!
//! Events:
//! - `task-progress` `{ taskId, event }` for long operations started with a task id
//! - `game-log` `{ instanceId, line, stderr }` for every line the game prints
//! - `game-state` `{ instanceId, running, exitCode }` when a game starts or stops
//! - `updates-changed` (no payload) when the known content updates of an instance changed

use std::{collections::HashMap, path::PathBuf, sync::Arc};

use slopclient_core::{
    Paths, Progress, ProgressFn,
    auth::{Account, AccountStore, DeviceCode, msa},
    config::Settings,
    cosmetics::{self, CapeInfo, CapeMeta, NewCape},
    cosmetics_online,
    crash::{self, CrashReport},
    discord,
    icon, mclogs,
    screenshots::{self, Screenshot},
    worlds::{self, Datapack, World},
    servers::{self, Server, ServerStatus},
    skins::{self, Skin, SkinEntry, Variant},
    foreign::{self, ForeignInstance},
    content::{
        BlockedFile, ContentEntry, ContentManager, ContentType, InstallReport, UpdateInfo,
        curseforge::CurseForge,
        update_cache::{self, UpdateCache, UpdateTarget},
        modrinth::{self, SearchQuery, SearchResult},
        mrpack::{self, ExportOptions, PackSource},
    },
    instance::{Instance, Loader, now},
    launch,
    shared_options::{self, Group, GroupStatus, Keep},
    store::{self, DedupeReport, GcReport, StoreStats},
    meta::{
        LoaderKind,
        loaders::{self, LoaderVersion},
        mojang::{self, ManifestEntry},
    },
};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    sync::Mutex,
};

type CmdResult<T> = Result<T, String>;

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

struct AppState {
    paths: Paths,
    /// Running games: instance id -> process id.
    games: Mutex<HashMap<String, u32>>,
    /// Games the user stopped; their exit is not a crash.
    stopping: Mutex<std::collections::HashSet<String>>,
    presence: discord::Presence,
    /// Instance a desktop shortcut asked to start (`--launch <id>`), until the UI takes it.
    launch_request: std::sync::Mutex<Option<String>>,
    /// What the running games do, for the Discord status.
    playing: std::sync::Mutex<Vec<Playing>>,
    started_at: u64,
    /// Results of update checks per instance (only in memory).
    updates: UpdateCache,
}

/// A running game as the Discord status shows it.
struct Playing {
    instance_id: String,
    name: String,
    mc_version: String,
    started_at: u64,
    /// From the client mod's "[SlopClient] presence:" log lines.
    place: Option<Place>,
}

enum Place {
    Menu,
    Singleplayer,
    Server(String),
}

impl AppState {
    /// Sends what the newest running game does to Discord, or "Im Launcher".
    fn refresh_presence(&self) {
        let hide_server = Settings::load(&self.paths).map(|s| s.discord_hide_server).unwrap_or(false);
        let playing = self.playing.lock().unwrap();
        let activity = match playing.last() {
            Some(p) => discord::Activity {
                details: format!("Spielt {}", p.name),
                state: Some(match &p.place {
                    Some(Place::Server(_)) if hide_server => "Auf einem Server".to_owned(),
                    Some(Place::Server(address)) => format!("Auf {address}"),
                    Some(Place::Singleplayer) => "Einzelspieler".to_owned(),
                    Some(Place::Menu) => "Im Hauptmenü".to_owned(),
                    None => format!("Minecraft {}", p.mc_version),
                }),
                started_at: Some(p.started_at),
            },
            None => discord::Activity { details: "Im Launcher".to_owned(), state: None, started_at: Some(self.started_at) },
        };
        self.presence.set(Some(activity));
    }

    /// Reads a "[SlopClient] presence: …" line of the client mod.
    fn presence_line(&self, instance_id: &str, line: &str) {
        let Some(rest) = line.split("[SlopClient] presence: ").nth(1) else { return };
        // The game logs XML (log4j): the message ends before "]]>".
        let rest = rest.split("]]>").next().unwrap_or(rest).trim();
        let place = match rest.split_once(' ') {
            Some(("server", address)) => Place::Server(address.trim().to_owned()),
            _ if rest == "singleplayer" => Place::Singleplayer,
            _ => Place::Menu,
        };
        if let Some(p) = self.playing.lock().unwrap().iter_mut().find(|p| p.instance_id == instance_id) {
            p.place = Some(place);
        }
        self.refresh_presence();
    }
}

/// The instance id after `--launch` in a command line.
fn launch_arg(args: &[String]) -> Option<String> {
    args.iter().position(|a| a == "--launch").and_then(|i| args.get(i + 1)).cloned()
}

/// The instance a desktop shortcut started the launcher for; asked once by the UI.
#[tauri::command]
fn take_launch_request(state: State<'_, AppState>) -> Option<String> {
    state.launch_request.lock().unwrap().take()
}

/// Puts a shortcut on the desktop that starts the launcher straight into the instance.
#[tauri::command]
async fn create_shortcut(state: State<'_, AppState>, id: String) -> CmdResult<String> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    let exe = std::env::current_exe().map_err(err)?;
    let icon = icon::ico(&state.paths, &instance).map_err(err)?.unwrap_or_else(|| exe.clone());
    let desktop = dirs::desktop_dir().ok_or("Desktop-Ordner nicht gefunden")?;
    let safe: String = instance.name.chars().map(|c| if r#"<>:"/\|?*"#.contains(c) { '_' } else { c }).collect();
    let link = desktop.join(format!("{} – SlopClient.lnk", safe.trim()));
    // Values travel as environment variables, so names with quotes cannot break the script.
    let script = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($env:DA_LINK); \
        $s.TargetPath = $env:DA_EXE; $s.Arguments = $env:DA_ARGS; $s.IconLocation = $env:DA_ICON; \
        $s.WorkingDirectory = $env:DA_DIR; $s.Description = $env:DA_DESC; $s.Save()";
    let mut cmd = std::process::Command::new("powershell");
    cmd.args(["-NoProfile", "-NonInteractive", "-Command", script])
        .env("DA_LINK", &link)
        .env("DA_EXE", &exe)
        .env("DA_ARGS", format!("--launch {}", instance.id))
        .env("DA_ICON", &icon)
        .env("DA_DIR", exe.parent().unwrap_or(&exe))
        .env("DA_DESC", format!("{} mit SlopClient starten", instance.name));
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let out = cmd.output().map_err(err)?;
    if !out.status.success() {
        return Err(format!("Verknüpfung konnte nicht erstellt werden: {}", String::from_utf8_lossy(&out.stderr).trim()));
    }
    Ok(link.display().to_string())
}

/// Starts the Discord status task with the app id from the settings.
fn start_presence(paths: &Paths) -> discord::Presence {
    let (presence, task) = discord::Presence::new();
    tauri::async_runtime::spawn(task);
    presence.set_app(Settings::load(paths).ok().and_then(|s| s.discord_app_id()));
    presence
}

impl AppState {
    fn concurrency(&self) -> usize {
        Settings::load(&self.paths)
            .map(|s| s.download_concurrency())
            .unwrap_or(16)
    }

    fn curseforge_key(&self) -> Option<String> {
        Settings::load(&self.paths).ok()?.curseforge_api_key()
    }

    fn curseforge(&self) -> CmdResult<CurseForge> {
        self.curseforge_key()
            .map(CurseForge::new)
            .ok_or_else(|| "Kein CurseForge-API-Schlüssel hinterlegt (Einstellungen).".into())
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct TaskProgress {
    task_id: String,
    event: Progress,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GameLog {
    instance_id: String,
    line: String,
    stderr: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GameState {
    instance_id: String,
    running: bool,
    exit_code: Option<i32>,
}

/// Sent after a game ended abnormally, with the analysis of its logs.
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GameCrash {
    instance_id: String,
    report: CrashReport,
}

fn progress_for(app: &AppHandle, task_id: &str) -> ProgressFn {
    let app = app.clone();
    let task_id = task_id.to_owned();
    Arc::new(move |event| {
        let _ = app.emit(
            "task-progress",
            TaskProgress {
                task_id: task_id.clone(),
                event,
            },
        );
    })
}

// ---------- overview & settings ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Overview {
    instances: Vec<Instance>,
    accounts: Vec<Account>,
    active_account: Option<String>,
    has_client_id: bool,
    data_dir: String,
    running: Vec<String>,
    dev_build: bool,
    /// Launcher version, e.g. "0.2.0".
    version: &'static str,
    /// Data still lives in the old DaClient folder.
    legacy_data_dir: bool,
    /// The old DaClient launcher is still installed.
    legacy_install: bool,
}

/// Closes the old DaClient launcher, moves its data folder to SlopClient and restarts.
#[tauri::command]
async fn finish_migration(app: AppHandle, state: State<'_, AppState>) -> CmdResult<()> {
    if !state.games.lock().await.is_empty() {
        return Err("Beende zuerst das laufende Spiel.".into());
    }
    let mut kill = std::process::Command::new("taskkill");
    kill.args(["/F", "/IM", "daclient-launcher.exe"]);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        kill.creation_flags(0x0800_0000);
    }
    let _ = kill.output();
    // Give Windows a moment to release the files of the closed process.
    tokio::time::sleep(std::time::Duration::from_millis(800)).await;
    state.paths.finish_migration().map_err(err)?;
    app.restart();
}

/// Starts the uninstaller of the old DaClient launcher (its own window asks first).
#[tauri::command]
async fn uninstall_legacy() -> CmdResult<()> {
    let exe = Paths::legacy_uninstaller().ok_or("Der alte DaClient-Launcher ist nicht mehr installiert.")?;
    std::process::Command::new(exe).spawn().map_err(err)?;
    Ok(())
}

#[tauri::command]
async fn get_overview(state: State<'_, AppState>) -> CmdResult<Overview> {
    let paths = &state.paths;
    let store = AccountStore::new(paths);
    let settings = Settings::load(paths).map_err(err)?;
    Ok(Overview {
        instances: Instance::list(paths).map_err(err)?,
        accounts: store.list().map_err(err)?,
        active_account: store.active().map_err(err)?.map(|a| a.uuid),
        has_client_id: settings.msa_client_id().is_some(),
        data_dir: paths.root.display().to_string(),
        running: state.games.lock().await.keys().cloned().collect(),
        dev_build: cfg!(debug_assertions),
        version: env!("CARGO_PKG_VERSION"),
        legacy_data_dir: paths.is_legacy(),
        legacy_install: Paths::legacy_uninstaller().is_some(),
    })
}

#[tauri::command]
async fn get_settings(state: State<'_, AppState>) -> CmdResult<Settings> {
    Settings::load(&state.paths).map_err(err)
}

#[tauri::command]
async fn save_settings(state: State<'_, AppState>, settings: Settings) -> CmdResult<()> {
    settings.save(&state.paths).map_err(err)
}

/// Stores the accent hue on its own, so the colour wheel saves without the rest of
/// an unsaved settings form.
#[tauri::command]
async fn set_accent_hue(state: State<'_, AppState>, hue: Option<f32>) -> CmdResult<()> {
    let mut settings = Settings::load(&state.paths).map_err(err)?;
    settings.accent_hue = hue.map(|h| h.rem_euclid(360.0));
    settings.save(&state.paths).map_err(err)
}

#[tauri::command]
async fn set_home_layout(state: State<'_, AppState>, layout: Option<slopclient_core::config::HomeLayout>) -> CmdResult<()> {
    let mut settings = Settings::load(&state.paths).map_err(err)?;
    settings.home_layout = layout;
    settings.save(&state.paths).map_err(err)
}

#[tauri::command]
async fn set_discord(state: State<'_, AppState>, enabled: bool, hide_server: bool) -> CmdResult<()> {
    let mut settings = Settings::load(&state.paths).map_err(err)?;
    settings.discord_presence = Some(enabled);
    settings.discord_hide_server = hide_server;
    settings.save(&state.paths).map_err(err)?;
    state.presence.set_app(settings.discord_app_id());
    state.refresh_presence();
    Ok(())
}

#[tauri::command]
async fn set_centered_layout(state: State<'_, AppState>, centered: bool) -> CmdResult<()> {
    let mut settings = Settings::load(&state.paths).map_err(err)?;
    settings.centered_layout = centered;
    settings.save(&state.paths).map_err(err)
}

#[tauri::command]
async fn open_path(path: String) -> CmdResult<()> {
    tauri_plugin_opener::open_path(path, None::<&str>).map_err(err)
}

// ---------- versions & loaders ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VersionList {
    latest_release: String,
    versions: Vec<ManifestEntry>,
}

#[tauri::command]
async fn list_versions(state: State<'_, AppState>) -> CmdResult<VersionList> {
    let manifest = mojang::fetch_manifest(&state.paths).await.map_err(err)?;
    Ok(VersionList {
        latest_release: manifest.latest.release,
        versions: manifest.versions,
    })
}

#[tauri::command]
async fn list_loader_versions(loader: LoaderKind, mc_version: String) -> CmdResult<Vec<LoaderVersion>> {
    loaders::list(loader, &mc_version).await.map_err(err)
}

// ---------- instances ----------

#[tauri::command]
async fn create_instance(
    state: State<'_, AppState>,
    name: String,
    mc_version: String,
    loader: Option<LoaderKind>,
    loader_version: Option<String>,
) -> CmdResult<Instance> {
    let loader = match loader {
        None => Loader::Vanilla,
        Some(kind) => {
            let version = match loader_version {
                Some(v) => v,
                None => loaders::recommended(kind, &mc_version).await.map_err(err)?,
            };
            Loader::new(kind, version)
        }
    };
    Instance::create(&state.paths, &name, &mc_version, loader).map_err(err)
}

#[tauri::command]
async fn save_instance(app: AppHandle, state: State<'_, AppState>, instance: Instance) -> CmdResult<()> {
    // Never let the UI move an instance to another directory.
    let old = Instance::load(&state.paths, &instance.id).map_err(err)?;
    instance.save(&state.paths).map_err(err)?;
    // Other versions are compatible with another Minecraft or loader version.
    if old.mc_version != instance.mc_version || old.loader != instance.loader {
        invalidate_updates(&app, &state, &instance.id);
    }
    Ok(())
}

/// Takes over options, servers, configs or packs from another instance.
#[tauri::command]
async fn copy_instance_settings(state: State<'_, AppState>, id: String, from: String, parts: Vec<slopclient_core::instance::CopyPart>) -> CmdResult<usize> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    instance.copy_settings_from(&state.paths, &from, &parts).await.map_err(err)
}

#[tauri::command]
async fn duplicate_instance(state: State<'_, AppState>, id: String, name: String) -> CmdResult<Instance> {
    Instance::duplicate(&state.paths, &id, &name).await.map_err(err)
}

#[tauri::command]
async fn delete_instance(app: AppHandle, state: State<'_, AppState>, id: String) -> CmdResult<()> {
    if state.games.lock().await.contains_key(&id) {
        return Err("Die Instanz läuft gerade.".into());
    }
    Instance::delete(&state.paths, &id).map_err(err)?;
    invalidate_updates(&app, &state, &id);
    clean_store(&state.paths);
    Ok(())
}

/// Custom icon as a data URL, or none for the colour tile.
#[tauri::command]
async fn instance_icon(state: State<'_, AppState>, id: String) -> CmdResult<Option<String>> {
    use base64::Engine;
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    let Some(path) = icon::path(&state.paths, &instance) else {
        return Ok(None);
    };
    let bytes = std::fs::read(path).map_err(err)?;
    Ok(Some(format!(
        "data:image/png;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    )))
}

/// Sets the icon from image bytes (the crop dialog sends a square PNG).
#[tauri::command]
async fn set_instance_icon(state: State<'_, AppState>, id: String, bytes: Vec<u8>) -> CmdResult<Instance> {
    let mut instance = Instance::load(&state.paths, &id).map_err(err)?;
    icon::set(&state.paths, &mut instance, &bytes, None).map_err(err)?;
    Ok(instance)
}

#[tauri::command]
async fn clear_instance_icon(state: State<'_, AppState>, id: String) -> CmdResult<Instance> {
    let mut instance = Instance::load(&state.paths, &id).map_err(err)?;
    icon::clear(&state.paths, &mut instance).map_err(err)?;
    Ok(instance)
}

#[tauri::command]
async fn instance_dir(state: State<'_, AppState>, id: String) -> CmdResult<String> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    Ok(instance.game_dir(&state.paths).display().to_string())
}

// ---------- launching ----------

#[tauri::command]
async fn launch_instance(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    task_id: String,
    join: Option<launch::Join>,
) -> CmdResult<()> {
    if state.games.lock().await.contains_key(&id) {
        return Err("Die Instanz läuft bereits.".into());
    }
    let paths = state.paths.clone();
    let instance = Instance::load(&paths, &id).map_err(err)?;

    let store = AccountStore::new(&paths);
    let account = store
        .active()
        .map_err(err)?
        .ok_or("Kein Account angemeldet.")?;
    let client_id = Settings::load(&paths)
        .map_err(err)?
        .msa_client_id()
        .ok_or("Keine Microsoft-Client-ID hinterlegt (Einstellungen).")?;
    let auth = store.launch_auth(&client_id, &account.uuid).await.map_err(err)?;

    // Pick up settings another member of its sync group saved (or a broken link).
    let running = running_ids(&state).await;
    let _ = shared_options::reconcile_instance(&paths, &id, &running);

    let mut cmd = launch::prepare(&paths, &instance, &auth, join, progress_for(&app, &task_id))
        .await
        .map_err(err)?;
    let java = PathBuf::from(cmd.as_std().get_program());
    let mut child = cmd.spawn().map_err(|e| format!("Java konnte nicht gestartet werden: {e}"))?;
    let pid = child.id().unwrap_or(0);
    state.games.lock().await.insert(id.clone(), pid);
    state.playing.lock().unwrap().push(Playing {
        instance_id: id.clone(),
        name: instance.name.clone(),
        mc_version: instance.mc_version.clone(),
        started_at: now(),
        place: None,
    });
    state.refresh_presence();
    let _ = app.emit(
        "game-state",
        GameState {
            instance_id: id.clone(),
            running: true,
            exit_code: None,
        },
    );

    // The last lines feed the crash analysis.
    let tail = Arc::new(std::sync::Mutex::new(std::collections::VecDeque::<String>::with_capacity(400)));
    for (stream, stderr) in [
        (child.stdout.take().map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>), false),
        (child.stderr.take().map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>), true),
    ] {
        let Some(stream) = stream else { continue };
        let (app, id, tail) = (app.clone(), id.clone(), tail.clone());
        tokio::spawn(async move {
            let mut lines = BufReader::new(stream).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if line.contains("[SlopClient] presence: ") {
                    app.state::<AppState>().presence_line(&id, &line);
                }
                {
                    let mut t = tail.lock().unwrap();
                    if t.len() == 400 {
                        t.pop_front();
                    }
                    t.push_back(line.clone());
                }
                let _ = app.emit(
                    "game-log",
                    GameLog {
                        instance_id: id.clone(),
                        line,
                        stderr,
                    },
                );
            }
        });
    }

    let started = now();
    let started_at = std::time::SystemTime::now();
    let app2 = app.clone();
    tokio::spawn(async move {
        let status = child.wait().await.ok();
        let exit_code = status.and_then(|s| s.code());
        let _ = launch::record_session(&paths, &id, started);
        let state = app2.state::<AppState>();
        state.games.lock().await.remove(&id);
        // Share what the game saved, or repair links it broke while saving.
        let running = running_ids(&state).await;
        let _ = shared_options::reconcile_instance(&paths, &id, &running);
        state.playing.lock().unwrap().retain(|p| p.instance_id != id);
        state.refresh_presence();
        let stopped = state.stopping.lock().await.remove(&id);
        let _ = app2.emit(
            "game-state",
            GameState {
                instance_id: id.clone(),
                running: false,
                exit_code,
            },
        );
        if !instance.hooks.post_exit.trim().is_empty() {
            let line = match launch::run_hook(&paths, &instance, &instance.hooks.post_exit, &java).await {
                Ok(()) => "[SlopClient] Post-Exit-Befehl ausgeführt".to_owned(),
                Err(e) => format!("[SlopClient] {e}"),
            };
            let _ = app2.emit("game-log", GameLog { instance_id: id.clone(), line, stderr: false });
        }
        if !stopped {
            // Give the log pipes a moment to flush their last lines.
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            let lines: Vec<String> = tail.lock().unwrap().iter().cloned().collect();
            if let Ok(Some(report)) = crash::analyze(&paths, &instance, started_at, exit_code, &lines).await {
                let _ = app2.emit("game-crash", GameCrash { instance_id: id, report });
            }
        }
    });
    Ok(())
}

/// Ids of the instances whose game is running.
async fn running_ids(state: &AppState) -> std::collections::HashSet<String> {
    state.games.lock().await.keys().cloned().collect()
}

#[tauri::command]
async fn stop_instance(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    let Some(pid) = state.games.lock().await.get(&id).copied() else {
        return Ok(());
    };
    state.stopping.lock().await.insert(id.clone());
    std::process::Command::new("taskkill")
        .args(["/F", "/T", "/PID", &pid.to_string()])
        .output()
        .map_err(err)?;
    Ok(())
}

/// Shares log text on mclo.gs and returns the link.
#[tauri::command]
async fn upload_log(text: String) -> CmdResult<String> {
    mclogs::upload(&text).await.map_err(err)
}

/// The text of a crash report or log file the crash analysis pointed to.
#[tauri::command]
async fn read_log_file(path: String) -> CmdResult<String> {
    let bytes = std::fs::read(&path).map_err(err)?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

// ---------- raw uploads ----------

/// Files dropped into the UI arrive as a raw body plus headers, which is much
/// cheaper than a JSON number array for multi-megabyte mods.
fn raw_upload<'a>(request: &'a tauri::ipc::Request<'_>, keys: &[&str]) -> CmdResult<(Vec<String>, &'a [u8])> {
    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err("Erwartet wurde eine Datei".into());
    };
    let values = keys
        .iter()
        .map(|k| {
            let v = request
                .headers()
                .get(*k)
                .and_then(|v| v.to_str().ok())
                .ok_or_else(|| format!("Header {k} fehlt"))?;
            percent_decode(v)
        })
        .collect::<CmdResult<Vec<_>>>()?;
    Ok((values, bytes))
}

/// Header values are sent through encodeURIComponent.
fn percent_decode(s: &str) -> CmdResult<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 3 <= bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).map_err(err)?;
            out.push(u8::from_str_radix(hex, 16).map_err(err)?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(err)
}

/// Adds a dropped mod, resource pack or shader pack; returns the detected kind.
#[tauri::command]
async fn content_add_local(app: AppHandle, state: State<'_, AppState>, request: tauri::ipc::Request<'_>) -> CmdResult<ContentType> {
    let (v, bytes) = raw_upload(&request, &["x-instance", "x-file-name"])?;
    let kind = manager(&state, &v[0]).await?.add_local(&v[1], bytes).await.map_err(err)?;
    invalidate_updates(&app, &state, &v[0]);
    Ok(kind)
}

// ---------- worlds & screenshots ----------

// ---------- skins ----------

#[tauri::command]
async fn skins_list(state: State<'_, AppState>) -> CmdResult<Vec<Skin>> {
    skins::list(&state.paths).map_err(err)
}

/// Adds a skin PNG; the variant is guessed from the arms unless given.
#[tauri::command]
async fn skin_add(state: State<'_, AppState>, request: tauri::ipc::Request<'_>) -> CmdResult<SkinEntry> {
    let (v, bytes) = raw_upload(&request, &["x-file-name"])?;
    skins::add(&state.paths, &v[0], None, bytes).map_err(err)
}

#[tauri::command]
async fn skin_remove(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    skins::remove(&state.paths, &id).map_err(err)
}

#[tauri::command]
async fn skin_set_variant(state: State<'_, AppState>, id: String, variant: Variant) -> CmdResult<()> {
    skins::set_variant(&state.paths, &id, variant).map_err(err)
}

/// Uploads a library skin to the active account; returns the new skin URL.
#[tauri::command]
async fn skin_apply(state: State<'_, AppState>, id: String) -> CmdResult<String> {
    let account = AccountStore::new(&state.paths).active().map_err(err)?.ok_or("Kein Account angemeldet.")?;
    let client_id = Settings::load(&state.paths).map_err(err)?.msa_client_id().ok_or("Keine Microsoft-Client-ID")?;
    skins::apply(&state.paths, &client_id, &account.uuid, &id).await.map_err(err)
}

/// Saves the active account's current skin into the library.
#[tauri::command]
async fn skin_import_current(state: State<'_, AppState>) -> CmdResult<SkinEntry> {
    let account = AccountStore::new(&state.paths).active().map_err(err)?.ok_or("Kein Account angemeldet.")?;
    let url = account.skin_url.ok_or("Dein Account hat keinen eigenen Skin.")?;
    skins::import_current(&state.paths, &url, &format!("Skin von {}", account.username)).await.map_err(err)
}

#[tauri::command]
async fn servers_list(state: State<'_, AppState>, id: String) -> CmdResult<Vec<Server>> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    servers::list(&state.paths, &instance).map_err(err)
}

#[tauri::command]
async fn server_ping(address: String) -> CmdResult<ServerStatus> {
    servers::ping(&address).await.map_err(err)
}

#[tauri::command]
async fn worlds_list(state: State<'_, AppState>, id: String) -> CmdResult<Vec<World>> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    let paths = state.paths.clone();
    tokio::task::spawn_blocking(move || worlds::list(&paths, &instance)).await.map_err(err)?.map_err(err)
}

#[tauri::command]
async fn world_delete(state: State<'_, AppState>, id: String, world: String) -> CmdResult<()> {
    if state.games.lock().await.contains_key(&id) {
        return Err("Beende das Spiel, bevor du Welten löschst.".into());
    }
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    worlds::delete(&state.paths, &instance, &world).map_err(err)
}

/// Zips the world into the instance's backups folder; returns the zip path.
#[tauri::command]
async fn world_backup(state: State<'_, AppState>, id: String, world: String) -> CmdResult<String> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    let paths = state.paths.clone();
    let file = tokio::task::spawn_blocking(move || worlds::backup(&paths, &instance, &world))
        .await
        .map_err(err)?
        .map_err(err)?;
    Ok(file.display().to_string())
}

#[tauri::command]
async fn datapacks_list(state: State<'_, AppState>, id: String, world: String) -> CmdResult<Vec<Datapack>> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    worlds::datapacks(&state.paths, &instance, &world).map_err(err)
}

#[tauri::command]
async fn datapack_add(state: State<'_, AppState>, request: tauri::ipc::Request<'_>) -> CmdResult<()> {
    let (v, bytes) = raw_upload(&request, &["x-instance", "x-world", "x-file-name"])?;
    let instance = Instance::load(&state.paths, &v[0]).map_err(err)?;
    worlds::add_datapack(&state.paths, &instance, &v[1], &v[2], bytes).map_err(err)
}

#[tauri::command]
async fn datapack_set_enabled(state: State<'_, AppState>, id: String, world: String, file_name: String, enabled: bool) -> CmdResult<()> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    worlds::set_datapack_enabled(&state.paths, &instance, &world, &file_name, enabled).map_err(err)
}

#[tauri::command]
async fn datapack_remove(state: State<'_, AppState>, id: String, world: String, file_name: String) -> CmdResult<()> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    worlds::remove_datapack(&state.paths, &instance, &world, &file_name).map_err(err)
}

#[tauri::command]
async fn screenshots_list(state: State<'_, AppState>, id: String) -> CmdResult<Vec<Screenshot>> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    screenshots::list(&state.paths, &instance).map_err(err)
}

/// Thumbnail as a JPEG data URL.
#[tauri::command]
async fn screenshot_thumb(state: State<'_, AppState>, id: String, name: String) -> CmdResult<String> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    let paths = state.paths.clone();
    let bytes = tokio::task::spawn_blocking(move || screenshots::thumbnail(&paths, &instance, &name))
        .await
        .map_err(err)?
        .map_err(err)?;
    Ok(format!("data:image/jpeg;base64,{}", b64(&bytes)))
}

#[tauri::command]
async fn screenshot_delete(state: State<'_, AppState>, id: String, name: String) -> CmdResult<()> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    screenshots::delete(&state.paths, &instance, &name).map_err(err)
}

// ---------- accounts ----------

#[tauri::command]
async fn login_start(state: State<'_, AppState>) -> CmdResult<DeviceCode> {
    let client_id = Settings::load(&state.paths)
        .map_err(err)?
        .msa_client_id()
        .ok_or("Keine Microsoft-Client-ID hinterlegt (Einstellungen).")?;
    msa::start_device_code(&client_id).await.map_err(err)
}

#[tauri::command]
async fn login_finish(state: State<'_, AppState>, code: DeviceCode) -> CmdResult<Account> {
    let client_id = Settings::load(&state.paths)
        .map_err(err)?
        .msa_client_id()
        .ok_or("Keine Microsoft-Client-ID hinterlegt.")?;
    AccountStore::new(&state.paths)
        .finish_device_code(&client_id, &code)
        .await
        .map_err(err)
}

#[tauri::command]
async fn set_active_account(state: State<'_, AppState>, uuid: String) -> CmdResult<()> {
    AccountStore::new(&state.paths).set_active(&uuid).map_err(err)
}

#[tauri::command]
async fn remove_account(state: State<'_, AppState>, uuid: String) -> CmdResult<()> {
    AccountStore::new(&state.paths).remove(&uuid).map_err(err)
}

// ---------- content ----------

async fn manager<'a>(state: &'a AppState, id: &str) -> CmdResult<ContentManager<'a>> {
    let instance = Instance::load(&state.paths, id).map_err(err)?;
    Ok(ContentManager::new(&state.paths, instance, state.concurrency()).with_curseforge(state.curseforge_key()))
}

#[tauri::command]
async fn content_list(state: State<'_, AppState>, id: String) -> CmdResult<Vec<ContentEntry>> {
    manager(&state, &id).await?.list().await.map_err(err)
}

#[tauri::command]
async fn content_install(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    project: String,
    source: Option<String>,
    task_id: String,
) -> CmdResult<InstallReport> {
    let manager = manager(&state, &id).await?;
    let progress = progress_for(&app, &task_id);
    let report = match source.as_deref() {
        Some("curseforge") => manager.install_curseforge(&project, &progress).await,
        _ => manager.install(&project, &progress).await,
    };
    invalidate_updates(&app, &state, &id);
    report.map_err(err)
}

/// Sodium, Lithium, FerriteCore and Iris in one go.
#[tauri::command]
async fn content_install_preset(app: AppHandle, state: State<'_, AppState>, id: String, task_id: String) -> CmdResult<InstallReport> {
    let manager = manager(&state, &id).await?;
    let report = manager
        .install_many(&slopclient_core::content::PERFORMANCE_PRESET, &progress_for(&app, &task_id))
        .await;
    invalidate_updates(&app, &state, &id);
    report.map_err(err)
}

#[tauri::command]
async fn content_blocked(state: State<'_, AppState>, id: String) -> CmdResult<Vec<BlockedFile>> {
    manager(&state, &id).await?.blocked().map_err(err)
}

/// Picks up manually downloaded CurseForge files from the Downloads folder.
#[tauri::command]
async fn content_resolve_blocked(app: AppHandle, state: State<'_, AppState>, id: String) -> CmdResult<Vec<BlockedFile>> {
    let (found, remaining) = manager(&state, &id)
        .await?
        .resolve_blocked(&[])
        .await
        .map_err(err)?;
    if found > 0 {
        invalidate_updates(&app, &state, &id);
    }
    Ok(remaining)
}

#[tauri::command]
async fn content_remove(app: AppHandle, state: State<'_, AppState>, id: String, file_name: String) -> CmdResult<()> {
    let removed = manager(&state, &id).await?.remove(&file_name).await.map_err(err)?;
    state.updates.forget(&id, removed.kind, &removed.file_name);
    let _ = app.emit("updates-changed", ());
    Ok(())
}

#[tauri::command]
async fn content_set_enabled(
    state: State<'_, AppState>,
    id: String,
    file_name: String,
    enabled: bool,
) -> CmdResult<()> {
    manager(&state, &id)
        .await?
        .set_enabled(&file_name, enabled)
        .await
        .map_err(err)?;
    Ok(())
}

/// Tells the instance list and content tab to reload the known updates.
fn invalidate_updates(app: &AppHandle, state: &AppState, id: &str) {
    state.updates.invalidate(id);
    let _ = app.emit("updates-changed", ());
}

/// Updates of an instance: from the cache when younger than 30 minutes (unless
/// `force`), otherwise asks Modrinth/CurseForge and remembers the result.
async fn known_updates(app: &AppHandle, state: &AppState, manager: &ContentManager<'_>, id: &str, force: bool) -> CmdResult<Vec<UpdateInfo>> {
    if !force && let Some(updates) = state.updates.fresh(id, now()) {
        return Ok(updates);
    }
    let token = state.updates.begin(id);
    let updates = manager.check_updates().await.map_err(err)?;
    if state.updates.store(id, token, updates.clone(), now()) {
        let _ = app.emit("updates-changed", ());
    }
    Ok(updates)
}

#[tauri::command]
async fn content_updates(app: AppHandle, state: State<'_, AppState>, id: String, force: Option<bool>) -> CmdResult<Vec<UpdateInfo>> {
    let manager = manager(&state, &id).await?;
    known_updates(&app, &state, &manager, &id, force.unwrap_or(true)).await
}

/// Installs the known updates of an instance, or only those of `only`. The new
/// versions are always the ones the backend found.
#[tauri::command]
async fn content_apply_updates(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    task_id: String,
    only: Option<Vec<UpdateTarget>>,
) -> CmdResult<usize> {
    if state.games.lock().await.contains_key(&id) {
        return Err("Schließ zuerst das Spiel. Solange es läuft, lassen sich seine Dateien nicht ersetzen.".into());
    }
    // One update run per instance at a time: they all rewrite content.lock.json.
    let busy = state.updates.instance_lock(&id);
    let _guard = busy.lock().await;
    let manager = manager(&state, &id).await?;
    let updates = known_updates(&app, &state, &manager, &id, false).await?;
    let current = manager.list().await.map_err(err)?;
    let refreshed = update_cache::refresh_entries(updates.clone(), &current);
    // Files changed since the check (e.g. updated elsewhere) have no update any more.
    let stale: Vec<UpdateInfo> = updates
        .into_iter()
        .filter(|u| !refreshed.iter().any(|r| r.entry.kind == u.entry.kind && r.entry.file_name == u.entry.file_name))
        .collect();
    state.updates.remove_done(&id, &stale);
    let chosen = update_cache::select(refreshed, only.as_deref());
    if let Err(e) = manager.apply_updates(&chosen, &progress_for(&app, &task_id)).await {
        // Some files may already be replaced: check again next time.
        invalidate_updates(&app, &state, &id);
        return Err(err(e));
    }
    state.updates.remove_done(&id, &chosen);
    let _ = app.emit("updates-changed", ());
    // Old versions nobody uses any more.
    clean_store(&state.paths);
    Ok(chosen.len())
}

/// Number of known updates per instance, for the instance list.
#[tauri::command]
async fn updates_overview(state: State<'_, AppState>) -> CmdResult<HashMap<String, usize>> {
    Ok(state.updates.counts())
}

/// Looks for updates of every instance one after another, quietly (offline is fine).
async fn check_all_updates(app: AppHandle) {
    let state = app.state::<AppState>();
    if !Settings::load(&state.paths).map(|s| s.check_updates_on_start()).unwrap_or(true) {
        return;
    }
    let Ok(instances) = Instance::list(&state.paths) else { return };
    for instance in instances {
        let id = instance.id.clone();
        let manager = ContentManager::new(&state.paths, instance, state.concurrency()).with_curseforge(state.curseforge_key());
        let _ = known_updates(&app, &state, &manager, &id, true).await;
    }
}

/// Description, gallery and links of a project for the launcher's project page.
#[tauri::command]
async fn project_details(state: State<'_, AppState>, source: String, id: String) -> CmdResult<slopclient_core::content::ProjectDetails> {
    match source.as_str() {
        "curseforge" => {
            let cf = state.curseforge()?;
            let numeric = match id.parse::<u64>() {
                Ok(n) => n,
                Err(_) => cf.find_mod(&id).await.map_err(err)?.id,
            };
            cf.details(numeric).await
        }
        _ => modrinth::details(&id).await,
    }
    .map_err(err)
}

/// Changelog of an update: Markdown from Modrinth, HTML from CurseForge.
#[tauri::command]
async fn version_changelog(state: State<'_, AppState>, source: String, project_id: String, version_id: String) -> CmdResult<String> {
    match source.as_str() {
        "curseforge" => {
            let (m, f) = (project_id.parse::<u64>().map_err(err)?, version_id.parse::<u64>().map_err(err)?);
            state.curseforge()?.changelog(m, f).await
        }
        _ => modrinth::changelog(&version_id).await,
    }
    .map_err(err)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SearchArgs {
    #[serde(default)]
    source: Option<String>,
    query: String,
    project_type: String,
    game_version: Option<String>,
    loaders: Vec<String>,
    sort: Option<String>,
    offset: u64,
    limit: u64,
}

#[tauri::command]
async fn search(state: State<'_, AppState>, args: SearchArgs) -> CmdResult<SearchResult> {
    let query = SearchQuery {
        query: args.query,
        project_type: Some(args.project_type),
        game_version: args.game_version,
        loaders: args.loaders,
        categories: Vec::new(),
        sort: args.sort,
        offset: args.offset,
        limit: args.limit,
    };
    match args.source.as_deref() {
        Some("curseforge") => state.curseforge()?.search(&query).await,
        _ => modrinth::search(&query).await,
    }
    .map_err(err)
}

// ---------- cosmetics (Cape Studio) ----------

fn b64(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

fn unb64(data: &str) -> CmdResult<Vec<u8>> {
    use base64::Engine;
    // Accepts plain base64 and data URLs.
    let raw = data.split_once(',').map_or(data, |(_, b)| b);
    base64::engine::general_purpose::STANDARD.decode(raw).map_err(err)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CapeLibrary {
    capes: Vec<CapeInfo>,
    active_id: Option<String>,
}

#[tauri::command]
async fn capes_list(state: State<'_, AppState>) -> CmdResult<CapeLibrary> {
    Ok(CapeLibrary {
        capes: cosmetics::list(&state.paths).map_err(err)?,
        active_id: cosmetics::active(&state.paths).map_err(err)?.map(|c| c.id),
    })
}

/// The spritesheet as a data URL (previews animate it by shifting frames).
#[tauri::command]
async fn cape_image(state: State<'_, AppState>, id: String) -> CmdResult<String> {
    Ok(format!("data:image/png;base64,{}", b64(&cosmetics::cape_png(&state.paths, &id).map_err(err)?)))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CapeSave {
    id: Option<String>,
    name: String,
    /// Base64 PNG spritesheet.
    png: String,
    meta: CapeMeta,
    source_ext: Option<String>,
    /// Base64 original upload.
    source: Option<String>,
    #[serde(default)]
    editor: serde_json::Value,
}

#[tauri::command]
async fn cape_save(state: State<'_, AppState>, cape: CapeSave) -> CmdResult<CapeInfo> {
    let png = unb64(&cape.png)?;
    let source = match &cape.source {
        Some(s) => Some(unb64(s)?),
        None => None,
    };
    let ext = cape.source_ext.as_deref().unwrap_or("png");
    cosmetics::save(
        &state.paths,
        NewCape {
            id: cape.id.as_deref(),
            name: &cape.name,
            png: &png,
            meta: cape.meta,
            source: source.as_deref().map(|b| (ext, b)),
            editor: cape.editor,
        },
    )
    .map_err(err)
}

#[tauri::command]
async fn cape_delete(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    cosmetics::delete(&state.paths, &id).map_err(err)
}

/// Wears a cape locally and, when signed in, on the cosmetics server. Returns an
/// error text when only the online part failed (the local change stays).
#[tauri::command]
async fn cape_set_active(state: State<'_, AppState>, id: Option<String>) -> CmdResult<Option<String>> {
    cosmetics::set_active(&state.paths, id.as_deref()).map_err(err)?;
    Ok(cosmetics_online::publish_active(&state.paths).await.err().map(|e| e.to_string()))
}

/// The hat and pet catalogue (models included) for the launcher's preview: first brought
/// up to date from the cosmetics server (a quick 304 when unchanged), the copy or the
/// shipped one when the server is slow or unreachable.
#[tauri::command]
async fn extras_catalogue(state: State<'_, AppState>) -> CmdResult<serde_json::Value> {
    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), slopclient_core::extras::refresh(&state.paths)).await;
    Ok(serde_json::from_str(&slopclient_core::extras::catalogue_json(&state.paths)).unwrap_or_default())
}

/// A pet texture from the server's copy of the catalogue as data URL; none falls back to
/// the textures shipped with the launcher.
#[tauri::command]
async fn extras_texture(state: State<'_, AppState>, name: String) -> CmdResult<Option<String>> {
    use base64::Engine;
    Ok(slopclient_core::extras::texture(&state.paths, &name)
        .map(|png| format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(png))))
}

#[tauri::command]
async fn extras_current(state: State<'_, AppState>) -> CmdResult<slopclient_core::extras::Choice> {
    slopclient_core::extras::current(&state.paths).map_err(err)
}

/// Wears a hat, pet and accessory (none clears) locally and, when signed in, online.
/// Returns an error text when only the online part failed.
#[tauri::command]
async fn extras_set(state: State<'_, AppState>, hat: Option<String>, pet: Option<String>, accessory: Option<String>) -> CmdResult<Option<String>> {
    slopclient_core::extras::set(&state.paths, &slopclient_core::extras::Choice { hat, pet, accessory }).map_err(err)?;
    Ok(cosmetics_online::publish_extras(&state.paths).await.err().map(|e| e.to_string()))
}

#[tauri::command]
async fn cosmetics_status(state: State<'_, AppState>) -> CmdResult<cosmetics_online::OnlineStatus> {
    Ok(cosmetics_online::status(&state.paths))
}

/// Signs in to the cosmetics server with the active account.
#[tauri::command]
async fn cosmetics_sign_in(state: State<'_, AppState>) -> CmdResult<cosmetics_online::OnlineStatus> {
    let paths = &state.paths;
    let store = AccountStore::new(paths);
    let account = store.active().map_err(err)?.ok_or("Kein Account angemeldet.")?;
    let client_id = Settings::load(paths)
        .map_err(err)?
        .msa_client_id()
        .ok_or("Keine Microsoft-Client-ID hinterlegt (Einstellungen).")?;
    let auth = store.launch_auth(&client_id, &account.uuid).await.map_err(err)?;
    cosmetics_online::sign_in(paths, &auth).await.map_err(err)?;
    cosmetics_online::publish_active(paths).await.map_err(err)?;
    // An older server without hats and pets must not fail the sign-in.
    let _ = cosmetics_online::publish_extras(paths).await;
    Ok(cosmetics_online::status(paths))
}

#[tauri::command]
async fn cosmetics_sign_out(state: State<'_, AppState>) -> CmdResult<()> {
    cosmetics_online::sign_out(&state.paths).map_err(err)
}

#[tauri::command]
async fn cosmetics_delete_account(state: State<'_, AppState>) -> CmdResult<()> {
    cosmetics_online::delete_account(&state.paths).await.map_err(err)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CapeSource {
    ext: String,
    data_url: String,
    editor: serde_json::Value,
}

/// Original upload plus editor state, to open a cape in the editor again.
#[tauri::command]
async fn cape_source(state: State<'_, AppState>, id: String) -> CmdResult<Option<CapeSource>> {
    let Some((ext, bytes, editor)) = cosmetics::source(&state.paths, &id).map_err(err)? else {
        return Ok(None);
    };
    let mime = match ext.as_str() {
        "gif" => "image/gif",
        "webp" => "image/webp",
        "jpg" | "jpeg" => "image/jpeg",
        _ => "image/png",
    };
    Ok(Some(CapeSource { data_url: format!("data:{mime};base64,{}", b64(&bytes)), ext, editor }))
}

// ---------- other launchers ----------

/// Instances of Prism/PolyMC (default folders) plus an optional extra folder.
#[tauri::command]
async fn foreign_scan(extra_dir: Option<String>) -> CmdResult<Vec<ForeignInstance>> {
    let mut roots = foreign::default_roots();
    if let Some(dir) = extra_dir {
        roots.push(("MultiMC".into(), PathBuf::from(dir)));
    }
    Ok(tokio::task::spawn_blocking(move || foreign::scan(&roots)).await.map_err(err)?)
}

#[tauri::command]
async fn foreign_import(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
    name: Option<String>,
    task_id: String,
) -> CmdResult<Instance> {
    foreign::import(
        &state.paths,
        &PathBuf::from(path),
        name.as_deref(),
        state.curseforge_key(),
        &progress_for(&app, &task_id),
    )
    .await
    .map_err(err)
}

// ---------- shared game settings ----------

/// Sync groups with the state of every member; checks and repairs the links first.
#[tauri::command]
async fn shared_options_groups(state: State<'_, AppState>) -> CmdResult<Vec<GroupStatus>> {
    let running = running_ids(&state).await;
    shared_options::reconcile_all(&state.paths, &running).map_err(err)
}

/// The "Jetzt prüfen" button: same as loading the groups.
#[tauri::command]
async fn shared_options_reconcile(state: State<'_, AppState>) -> CmdResult<Vec<GroupStatus>> {
    shared_options_groups(state).await
}

async fn refuse_running(state: &AppState, ids: &[String]) -> CmdResult<()> {
    let games = state.games.lock().await;
    if ids.iter().any(|id| games.contains_key(id)) {
        return Err("Schließ zuerst das Spiel. Solange es läuft, schreibt es seine Einstellungen selbst.".into());
    }
    Ok(())
}

#[tauri::command]
async fn shared_options_create(state: State<'_, AppState>, name: String, source_instance: String) -> CmdResult<Group> {
    refuse_running(&state, std::slice::from_ref(&source_instance)).await?;
    shared_options::create(&state.paths, &name, &source_instance).map_err(err)
}

#[tauri::command]
async fn shared_options_rename(state: State<'_, AppState>, group: String, name: String) -> CmdResult<()> {
    shared_options::rename(&state.paths, &group, &name).map_err(err)
}

#[tauri::command]
async fn shared_options_delete(state: State<'_, AppState>, group: String) -> CmdResult<()> {
    let members = shared_options::groups(&state.paths)
        .map_err(err)?
        .into_iter()
        .find(|g| g.id == group)
        .map(|g| g.instances)
        .unwrap_or_default();
    refuse_running(&state, &members).await?;
    shared_options::delete(&state.paths, &group).map_err(err)
}

#[tauri::command]
async fn shared_options_add(state: State<'_, AppState>, group: String, instance: String, keep: Keep) -> CmdResult<()> {
    refuse_running(&state, std::slice::from_ref(&instance)).await?;
    shared_options::add(&state.paths, &group, &instance, keep).map_err(err)
}

#[tauri::command]
async fn shared_options_remove(state: State<'_, AppState>, group: String, instance: String) -> CmdResult<()> {
    refuse_running(&state, std::slice::from_ref(&instance)).await?;
    shared_options::remove(&state.paths, &group, &instance).map_err(err)
}

/// The sync group of an instance, for the hint in its settings tab.
#[tauri::command]
async fn shared_options_group_of(state: State<'_, AppState>, instance: String) -> CmdResult<Option<Group>> {
    shared_options::group_of(&state.paths, &instance).map_err(err)
}

// ---------- content store ----------

/// Removes unused store files in the background.
fn clean_store(paths: &Paths) {
    let paths = paths.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let _ = store::gc(&paths);
    });
}

#[tauri::command]
async fn store_stats(state: State<'_, AppState>) -> CmdResult<StoreStats> {
    let paths = state.paths.clone();
    tauri::async_runtime::spawn_blocking(move || store::stats(&paths)).await.map_err(err)?.map_err(err)
}

/// "Doppelte Dateien zusammenführen": running instances are left out.
#[tauri::command]
async fn store_dedupe(app: AppHandle, state: State<'_, AppState>, task_id: String) -> CmdResult<DedupeReport> {
    let running = running_ids(&state).await;
    store::dedupe(&state.paths, &running, &progress_for(&app, &task_id)).await.map_err(err)
}

#[tauri::command]
async fn store_gc(state: State<'_, AppState>) -> CmdResult<GcReport> {
    let paths = state.paths.clone();
    tauri::async_runtime::spawn_blocking(move || store::gc(&paths)).await.map_err(err)?.map_err(err)
}

// ---------- modpacks ----------

#[tauri::command]
async fn modpack_import(
    app: AppHandle,
    state: State<'_, AppState>,
    source: String,
    is_file: bool,
    platform: Option<String>,
    name: Option<String>,
    task_id: String,
) -> CmdResult<Instance> {
    let path = PathBuf::from(&source);
    let source = if is_file {
        PackSource::File(&path)
    } else if platform.as_deref() == Some("curseforge") {
        PackSource::CurseForge(&source)
    } else {
        PackSource::Modrinth(&source)
    };
    let cf = state.curseforge_key().map(CurseForge::new);
    mrpack::import(
        &state.paths,
        source,
        name.as_deref(),
        cf.as_ref(),
        state.concurrency(),
        &progress_for(&app, &task_id),
    )
    .await
    .map_err(err)
}

#[tauri::command]
async fn modpack_export(
    state: State<'_, AppState>,
    id: String,
    output: String,
    name: String,
    version: String,
    include: Vec<String>,
) -> CmdResult<(usize, usize)> {
    let instance = Instance::load(&state.paths, &id).map_err(err)?;
    let options = ExportOptions {
        name,
        version,
        summary: None,
        include,
    };
    mrpack::export(
        &state.paths,
        &instance,
        &options,
        &PathBuf::from(output),
        state.concurrency(),
    )
    .await
    .map_err(err)
}

pub fn run() {
    let paths = Paths::from_env().expect("data directory");
    paths.ensure().expect("create data directory");

    let mut builder = tauri::Builder::default();
    // A second start (e.g. from a desktop shortcut) hands its instance to the running
    // launcher. Test runs with their own SLOPCLIENT_HOME stay independent.
    if std::env::var_os("SLOPCLIENT_HOME").is_none() && std::env::var_os("DACLIENT_HOME").is_none() {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if let Some(id) = launch_arg(&args) {
                let _ = app.emit("launch-request", id);
            }
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }));
    }
    builder
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let state = app.state::<AppState>();
            state.refresh_presence();
            // Repair settings links that broke while the launcher was closed.
            let paths = state.paths.clone();
            tauri::async_runtime::spawn_blocking(move || {
                let _ = shared_options::reconcile_all(&paths, &Default::default());
                let _ = store::gc(&paths);
            });
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                // Let the window come up first.
                tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                check_all_updates(handle).await;
            });
            Ok(())
        })
        .manage(AppState {
            presence: start_presence(&paths),
            launch_request: std::sync::Mutex::new(launch_arg(&std::env::args().collect::<Vec<_>>())),
            paths,
            games: Mutex::new(HashMap::new()),
            stopping: Mutex::new(Default::default()),
            playing: std::sync::Mutex::new(Vec::new()),
            started_at: now(),
            updates: UpdateCache::default(),
        })
        .invoke_handler(tauri::generate_handler![
            get_overview,
            get_settings,
            save_settings,
            set_accent_hue,
            set_centered_layout,
            set_discord,
            take_launch_request,
            finish_migration,
            uninstall_legacy,
            create_shortcut,
            set_home_layout,
            open_path,
            list_versions,
            list_loader_versions,
            create_instance,
            save_instance,
            delete_instance,
            duplicate_instance,
            copy_instance_settings,
            instance_dir,
            upload_log,
            read_log_file,
            content_add_local,
            worlds_list,
            servers_list,
            server_ping,
            skins_list,
            skin_add,
            skin_remove,
            skin_set_variant,
            skin_apply,
            skin_import_current,
            world_delete,
            world_backup,
            datapacks_list,
            datapack_add,
            datapack_set_enabled,
            datapack_remove,
            screenshots_list,
            screenshot_thumb,
            screenshot_delete,
            instance_icon,
            set_instance_icon,
            clear_instance_icon,
            launch_instance,
            stop_instance,
            login_start,
            login_finish,
            set_active_account,
            remove_account,
            content_list,
            content_install,
            content_install_preset,
            content_blocked,
            content_resolve_blocked,
            content_remove,
            content_set_enabled,
            content_updates,
            content_apply_updates,
            updates_overview,
            shared_options_groups,
            shared_options_reconcile,
            shared_options_create,
            shared_options_rename,
            shared_options_delete,
            shared_options_add,
            shared_options_remove,
            shared_options_group_of,
            store_stats,
            store_dedupe,
            store_gc,
            search,
            project_details,
            version_changelog,
            modpack_import,
            modpack_export,
            foreign_scan,
            foreign_import,
            capes_list,
            cape_image,
            cape_save,
            cape_delete,
            cape_set_active,
            cape_source,
            cosmetics_status,
            extras_catalogue,
            extras_texture,
            extras_current,
            extras_set,
            cosmetics_sign_in,
            cosmetics_sign_out,
            cosmetics_delete_account,
        ])
        .run(tauri::generate_context!())
        .expect("error while running SlopClient");
}
