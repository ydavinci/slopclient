//! Tauri layer: exposes `daclient-core` to the UI as commands and events.
//!
//! Events:
//! - `task-progress` `{ taskId, event }` for long operations started with a task id
//! - `game-log` `{ instanceId, line, stderr }` for every line the game prints
//! - `game-state` `{ instanceId, running, exitCode }` when a game starts or stops

use std::{collections::HashMap, path::PathBuf, sync::Arc};

use daclient_core::{
    Paths, Progress, ProgressFn,
    auth::{Account, AccountStore, DeviceCode, msa},
    config::Settings,
    cosmetics::{self, CapeInfo, CapeMeta, NewCape},
    cosmetics_online,
    crash::{self, CrashReport},
    icon, mclogs,
    screenshots::{self, Screenshot},
    worlds::{self, Datapack, World},
    foreign::{self, ForeignInstance},
    content::{
        BlockedFile, ContentEntry, ContentManager, ContentType, InstallReport, UpdateInfo,
        curseforge::CurseForge,
        modrinth::{self, SearchQuery, SearchResult},
        mrpack::{self, ExportOptions, PackSource},
    },
    instance::{Instance, Loader, now},
    launch::{self, LaunchAuth},
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

/// Debug builds, or release builds made with `--features dev-offline` for local
/// testing. Only these may start the game without a Microsoft account.
const DEV_BUILD: bool = cfg!(debug_assertions) || cfg!(feature = "dev-offline");

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
    dev_offline: bool,
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
        dev_build: DEV_BUILD,
        version: env!("CARGO_PKG_VERSION"),
        dev_offline: DEV_BUILD && settings.dev_offline,
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
async fn save_instance(state: State<'_, AppState>, instance: Instance) -> CmdResult<()> {
    // Never let the UI move an instance to another directory.
    Instance::load(&state.paths, &instance.id).map_err(err)?;
    instance.save(&state.paths).map_err(err)
}

#[tauri::command]
async fn duplicate_instance(state: State<'_, AppState>, id: String, name: String) -> CmdResult<Instance> {
    Instance::duplicate(&state.paths, &id, &name).await.map_err(err)
}

#[tauri::command]
async fn delete_instance(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    if state.games.lock().await.contains_key(&id) {
        return Err("Die Instanz läuft gerade.".into());
    }
    Instance::delete(&state.paths, &id).map_err(err)
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
    offline_name: Option<String>,
) -> CmdResult<()> {
    if state.games.lock().await.contains_key(&id) {
        return Err("Die Instanz läuft bereits.".into());
    }
    let paths = state.paths.clone();
    let instance = Instance::load(&paths, &id).map_err(err)?;

    let settings = Settings::load(&paths).map_err(err)?;
    let offline_name = offline_name.or_else(|| {
        (DEV_BUILD && settings.dev_offline).then(|| "DevPlayer".to_owned())
    });
    let auth = match offline_name {
        Some(name) if DEV_BUILD => LaunchAuth::offline(&name),
        Some(_) => return Err("Offline-Start ist nur in Entwickler-Builds verfügbar.".into()),
        None => {
            let store = AccountStore::new(&paths);
            let account = store
                .active()
                .map_err(err)?
                .ok_or("Kein Account angemeldet.")?;
            let client_id = Settings::load(&paths)
                .map_err(err)?
                .msa_client_id()
                .ok_or("Keine Microsoft-Client-ID hinterlegt (Einstellungen).")?;
            store.launch_auth(&client_id, &account.uuid).await.map_err(err)?
        }
    };

    let mut cmd = launch::prepare(&paths, &instance, &auth, progress_for(&app, &task_id))
        .await
        .map_err(err)?;
    let java = PathBuf::from(cmd.as_std().get_program());
    let mut child = cmd.spawn().map_err(|e| format!("Java konnte nicht gestartet werden: {e}"))?;
    let pid = child.id().unwrap_or(0);
    state.games.lock().await.insert(id.clone(), pid);
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
                Ok(()) => "[DaClient] Post-Exit-Befehl ausgeführt".to_owned(),
                Err(e) => format!("[DaClient] {e}"),
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
async fn content_add_local(state: State<'_, AppState>, request: tauri::ipc::Request<'_>) -> CmdResult<ContentType> {
    let (v, bytes) = raw_upload(&request, &["x-instance", "x-file-name"])?;
    manager(&state, &v[0]).await?.add_local(&v[1], bytes).await.map_err(err)
}

// ---------- worlds & screenshots ----------

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
    match source.as_deref() {
        Some("curseforge") => manager.install_curseforge(&project, &progress).await,
        _ => manager.install(&project, &progress).await,
    }
    .map_err(err)
}

/// Sodium, Lithium, FerriteCore and Iris in one go.
#[tauri::command]
async fn content_install_preset(app: AppHandle, state: State<'_, AppState>, id: String, task_id: String) -> CmdResult<InstallReport> {
    let manager = manager(&state, &id).await?;
    manager
        .install_many(&daclient_core::content::PERFORMANCE_PRESET, &progress_for(&app, &task_id))
        .await
        .map_err(err)
}

#[tauri::command]
async fn content_blocked(state: State<'_, AppState>, id: String) -> CmdResult<Vec<BlockedFile>> {
    manager(&state, &id).await?.blocked().map_err(err)
}

/// Picks up manually downloaded CurseForge files from the Downloads folder.
#[tauri::command]
async fn content_resolve_blocked(state: State<'_, AppState>, id: String) -> CmdResult<Vec<BlockedFile>> {
    let (_, remaining) = manager(&state, &id)
        .await?
        .resolve_blocked(&[])
        .await
        .map_err(err)?;
    Ok(remaining)
}

#[tauri::command]
async fn content_remove(state: State<'_, AppState>, id: String, file_name: String) -> CmdResult<()> {
    manager(&state, &id).await?.remove(&file_name).await.map_err(err)?;
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

#[tauri::command]
async fn content_updates(state: State<'_, AppState>, id: String) -> CmdResult<Vec<UpdateInfo>> {
    manager(&state, &id).await?.check_updates().await.map_err(err)
}

#[tauri::command]
async fn content_apply_updates(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    task_id: String,
) -> CmdResult<usize> {
    let manager = manager(&state, &id).await?;
    let updates = manager.check_updates().await.map_err(err)?;
    manager
        .apply_updates(&updates, &progress_for(&app, &task_id))
        .await
        .map_err(err)?;
    Ok(updates.len())
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

#[tauri::command]
async fn cosmetics_status(state: State<'_, AppState>) -> CmdResult<cosmetics_online::OnlineStatus> {
    Ok(cosmetics_online::status(&state.paths))
}

/// Signs in to the cosmetics server with the active account (offline only in dev builds).
#[tauri::command]
async fn cosmetics_sign_in(state: State<'_, AppState>, offline_name: Option<String>) -> CmdResult<cosmetics_online::OnlineStatus> {
    let paths = &state.paths;
    let auth = match offline_name {
        Some(name) if DEV_BUILD => LaunchAuth::offline(&name),
        Some(_) => return Err("Offline-Anmeldung gibt es nur in Entwickler-Builds.".into()),
        None => {
            let store = AccountStore::new(paths);
            let account = store.active().map_err(err)?.ok_or("Kein Account angemeldet.")?;
            let client_id = Settings::load(paths)
                .map_err(err)?
                .msa_client_id()
                .ok_or("Keine Microsoft-Client-ID hinterlegt (Einstellungen).")?;
            store.launch_auth(&client_id, &account.uuid).await.map_err(err)?
        }
    };
    cosmetics_online::sign_in(paths, &auth).await.map_err(err)?;
    cosmetics_online::publish_active(paths).await.map_err(err)?;
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

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState {
            paths,
            games: Mutex::new(HashMap::new()),
            stopping: Mutex::new(Default::default()),
        })
        .invoke_handler(tauri::generate_handler![
            get_overview,
            get_settings,
            save_settings,
            open_path,
            list_versions,
            list_loader_versions,
            create_instance,
            save_instance,
            delete_instance,
            duplicate_instance,
            instance_dir,
            upload_log,
            read_log_file,
            content_add_local,
            worlds_list,
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
            search,
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
            cosmetics_sign_in,
            cosmetics_sign_out,
            cosmetics_delete_account,
        ])
        .run(tauri::generate_context!())
        .expect("error while running DaClient");
}
