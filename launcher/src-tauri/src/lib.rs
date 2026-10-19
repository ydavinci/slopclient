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
    crash::{self, CrashReport},
    icon,
    foreign::{self, ForeignInstance},
    content::{
        BlockedFile, ContentEntry, ContentManager, InstallReport, UpdateInfo,
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
}

#[tauri::command]
async fn get_overview(state: State<'_, AppState>) -> CmdResult<Overview> {
    let paths = &state.paths;
    let store = AccountStore::new(paths);
    Ok(Overview {
        instances: Instance::list(paths).map_err(err)?,
        accounts: store.list().map_err(err)?,
        active_account: store.active().map_err(err)?.map(|a| a.uuid),
        has_client_id: Settings::load(paths).map_err(err)?.msa_client_id().is_some(),
        data_dir: paths.root.display().to_string(),
        running: state.games.lock().await.keys().cloned().collect(),
        dev_build: cfg!(debug_assertions),
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

    let auth = match offline_name {
        Some(name) if cfg!(debug_assertions) => LaunchAuth::offline(&name),
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

#[tauri::command]
async fn cape_set_active(state: State<'_, AppState>, id: Option<String>) -> CmdResult<()> {
    cosmetics::set_active(&state.paths, id.as_deref()).map_err(err)
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
            instance_dir,
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
        ])
        .run(tauri::generate_context!())
        .expect("error while running DaClient");
}
