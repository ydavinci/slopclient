//! Test CLI for the SlopClient backend.

use std::{
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, bail};
use clap::{Parser, Subcommand, ValueEnum};
use slopclient_core::{
    Paths, Progress, ProgressFn,
    auth::AccountStore,
    config::Settings,
    cosmetics, cosmetics_online, crash, foreign,
    content::{
        BlockedFile, ContentManager,
        curseforge::CurseForge,
        modrinth::{self, SearchQuery},
        mrpack::{self, ExportOptions, PackSource},
    },
    install::Installer,
    instance::{Instance, Loader, now},
    launch::{self, LaunchAuth},
    meta::{LoaderKind, loaders, mojang},
};
use indicatif::{ProgressBar, ProgressStyle};
use tokio::io::{AsyncBufReadExt, BufReader};

#[derive(Parser)]
#[command(name = "slopclient", version, about = "SlopClient launcher backend (CLI)")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

/// Content platform.
#[derive(Clone, Copy, PartialEq, Eq, ValueEnum)]
enum Platform {
    Modrinth,
    Curseforge,
}

#[derive(Subcommand)]
enum Command {
    /// List Minecraft versions
    Versions {
        /// Include snapshots, betas and alphas
        #[arg(long)]
        all: bool,
        #[arg(long, default_value_t = 20)]
        limit: usize,
    },
    /// Download a version (client, libraries, assets)
    Install {
        version: String,
        /// fabric, quilt, neoforge or forge
        #[arg(long)]
        loader: Option<LoaderKind>,
        /// Loader version (default: newest stable)
        #[arg(long, requires = "loader")]
        loader_version: Option<String>,
    },
    /// List loader versions for a Minecraft version
    Loaders {
        /// fabric, quilt, neoforge or forge
        loader: LoaderKind,
        mc_version: String,
        #[arg(long, default_value_t = 15)]
        limit: usize,
    },
    /// Manage instances
    #[command(subcommand)]
    Instance(InstanceCommand),
    /// Search Modrinth or CurseForge
    Search {
        query: String,
        #[arg(long, value_enum, default_value = "modrinth")]
        source: Platform,
        /// mod, resourcepack, shader, modpack
        #[arg(long = "type", default_value = "mod")]
        kind: String,
        /// Only show results compatible with this instance
        #[arg(long)]
        instance: Option<String>,
        /// relevance, downloads, follows, newest, updated
        #[arg(long, default_value = "relevance")]
        sort: String,
        #[arg(long, default_value_t = 10)]
        limit: u64,
    },
    /// Mods, resource packs and shaders of an instance
    #[command(subcommand)]
    Content(ContentCommand),
    /// Import instances from Prism Launcher, PolyMC or MultiMC
    #[command(subcommand)]
    Import(ImportCommand),
    /// Import modpacks (.mrpack, CurseForge zip) or export .mrpack
    #[command(subcommand)]
    Modpack(ModpackCommand),
    /// Log in with a Microsoft account (device code)
    Login,
    /// List accounts
    Accounts,
    /// Remove an account
    Logout { account: String },
    /// Launch an instance
    Launch {
        instance: String,
        /// Account name or UUID (default: active account)
        #[arg(long)]
        account: Option<String>,
        /// DEV ONLY: start without a Microsoft account (singleplayer/LAN only)
        #[arg(long, value_name = "NAME", conflicts_with = "account")]
        dev_offline: Option<String>,
        /// Prepare everything and print the Java command instead of starting it
        #[arg(long)]
        dry_run: bool,
        /// Join this server right after starting (Quick Play)
        #[arg(long, value_name = "ADDRESS")]
        server: Option<String>,
        /// Open this world right after starting (folder name in saves/)
        #[arg(long, value_name = "FOLDER", conflicts_with = "server")]
        world: Option<String>,
    },
    /// Ping a Minecraft server like the multiplayer screen does
    Ping { address: String },
    /// List the saved servers of an instance
    Servers { instance: String },
    /// Set the Azure client id used for Microsoft login
    SetClientId { client_id: String },
    /// Set the CurseForge API key
    SetCurseforgeKey { key: String },
    /// Explain a crash from a log or crash report file
    Diagnose { file: std::path::PathBuf },
    /// Capes of the Cape Studio
    #[command(subcommand)]
    Cape(CapeCommand),
}

#[derive(Subcommand)]
enum ContentCommand {
    /// List installed content
    List { instance: String },
    /// Install projects (slug or id) with their dependencies
    Add {
        instance: String,
        #[arg(required = true)]
        projects: Vec<String>,
        #[arg(long, value_enum, default_value = "modrinth")]
        source: Platform,
    },
    /// Install the performance preset (Sodium, Lithium, FerriteCore, Iris)
    Preset { instance: String },
    /// Remove a file (by name, file name or slug)
    Remove { instance: String, name: String },
    Enable { instance: String, name: String },
    Disable { instance: String, name: String },
    /// Show available updates
    Updates { instance: String },
    /// Install available updates: all, or only the named ones (name, file name or slug)
    Update { instance: String, names: Vec<String> },
    /// Recognize manually added files on Modrinth (and CurseForge with a key)
    Identify { instance: String },
    /// CurseForge files that need a manual download; picks up downloaded ones
    Blocked {
        instance: String,
        /// Folder to look in (default: Downloads)
        #[arg(long)]
        dir: Vec<std::path::PathBuf>,
    },
}

#[derive(Subcommand)]
enum ModpackCommand {
    /// Create an instance from a .mrpack/CurseForge zip or a modpack project (slug or id)
    Import {
        source: String,
        /// Where to look up a project name
        #[arg(long, value_enum, default_value = "modrinth")]
        platform: Platform,
        /// Instance name (default: the pack's name)
        #[arg(long)]
        name: Option<String>,
    },
    /// Export an instance as .mrpack
    Export {
        instance: String,
        output: std::path::PathBuf,
        /// Pack name (default: instance name)
        #[arg(long)]
        name: Option<String>,
        #[arg(long, default_value = "1.0.0")]
        version: String,
        /// Files or folders of the game directory to bundle
        #[arg(long, default_values_t = ["config".to_string()])]
        include: Vec<String>,
    },
}

#[derive(Subcommand)]
enum CapeCommand {
    /// List capes (the active one is marked)
    List,
    /// Add a finished spritesheet (frames stacked vertically, 64x32 per frame times scale)
    Import {
        png: std::path::PathBuf,
        #[arg(long, default_value = "Cape")]
        name: String,
        #[arg(long, default_value_t = 1)]
        frames: u32,
        #[arg(long, default_value_t = 1)]
        scale: u32,
        #[arg(long, default_value_t = 100)]
        frame_time: u32,
        /// Don't show it as elytra
        #[arg(long)]
        no_elytra: bool,
        /// Make it the active cape
        #[arg(long)]
        activate: bool,
    },
    /// Wear a cape (or none with --none)
    Use {
        #[arg(required_unless_present = "none")]
        id: Option<String>,
        #[arg(long)]
        none: bool,
    },
    Delete { id: String },
    /// The cosmetics server (other players see your cape)
    #[command(subcommand)]
    Online(OnlineCommand),
}

#[derive(Subcommand)]
enum OnlineCommand {
    Status,
    /// Sign in with the active Microsoft account
    SignIn {
        /// DEV ONLY: offline identity; needs a server with SLOPCLIENT_COSMETICS_DEV_AUTH=1
        #[arg(long, value_name = "NAME")]
        dev_offline: Option<String>,
    },
    /// Upload the active cape (or clear it online)
    Publish,
    SignOut,
    /// Delete your account and capes on the server
    DeleteAccount,
}

#[derive(Subcommand)]
enum ImportCommand {
    /// List instances of other launchers
    List {
        /// Extra instances folder, e.g. of a portable MultiMC
        #[arg(long)]
        dir: Option<std::path::PathBuf>,
    },
    /// Copy an instance folder into SlopClient
    Run {
        path: std::path::PathBuf,
        #[arg(long)]
        name: Option<String>,
    },
}

#[derive(Subcommand)]
enum InstanceCommand {
    /// Create an instance
    Create {
        name: String,
        version: String,
        /// fabric, quilt, neoforge or forge
        #[arg(long)]
        loader: Option<LoaderKind>,
        /// Loader version (default: newest stable)
        #[arg(long, requires = "loader")]
        loader_version: Option<String>,
    },
    /// List instances
    List,
    /// Delete an instance and its game directory
    Delete { id: String },
    /// Set the icon from an image (PNG, JPG, GIF, WebP), or reset it
    Icon {
        id: String,
        #[arg(required_unless_present = "clear")]
        image: Option<std::path::PathBuf>,
        #[arg(long)]
        clear: bool,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    let paths = Paths::from_env()?;
    paths.ensure()?;

    match cli.command {
        Command::Versions { all, limit } => {
            let manifest = mojang::fetch_manifest(&paths).await?;
            println!("latest release: {}  snapshot: {}", manifest.latest.release, manifest.latest.snapshot);
            for v in manifest
                .versions
                .iter()
                .filter(|v| all || v.kind == "release")
                .take(limit)
            {
                println!("  {:<24} {:<10} {}", v.id, v.kind, &v.release_time[..10]);
            }
        }
        Command::Install {
            version,
            loader,
            loader_version,
        } => {
            let loader = resolve_loader(&version, loader, loader_version).await?;
            let settings = Settings::load(&paths)?;
            let (progress, bar) = progress_bar();
            let installer = Installer::new(&paths, settings.download_concurrency(), progress);
            let id = installer.install_loader(&version, &loader).await?;
            let resolved = installer.install(&id).await?;
            bar.finish_and_clear();
            println!(
                "installed {} ({} libraries, assets {})",
                resolved.info.id,
                resolved.libraries.len(),
                resolved.asset_index_id
            );
        }
        Command::Loaders {
            loader,
            mc_version,
            limit,
        } => {
            let versions = loaders::list(loader, &mc_version).await?;
            if versions.is_empty() {
                bail!("{loader} is not available for Minecraft {mc_version}");
            }
            for v in versions.iter().take(limit) {
                println!("  {:<28} {}", v.version, if v.stable { "stable" } else { "beta" });
            }
        }
        Command::Search {
            query,
            source,
            kind,
            instance,
            sort,
            limit,
        } => {
            let mut q = SearchQuery {
                query,
                project_type: Some(kind.clone()),
                sort: Some(sort),
                limit,
                ..Default::default()
            };
            if let Some(id) = instance {
                let instance = Instance::load(&paths, &id)?;
                q.game_version = Some(instance.mc_version.clone());
                if kind == "mod"
                    && let Some(loader) = instance.loader.kind() {
                        q.loaders = match loader {
                            LoaderKind::Quilt => vec!["quilt".into(), "fabric".into()],
                            other => vec![other.to_string().to_lowercase()],
                        };
                    }
            }
            let result = match source {
                Platform::Modrinth => modrinth::search(&q).await?,
                Platform::Curseforge => curseforge(&paths)?.search(&q).await?,
            };
            println!("{} results", result.total_hits);
            for hit in result.hits {
                println!(
                    "  {:<28} {:<32} {:>11} downloads  {}",
                    hit.slug,
                    truncate(&hit.title, 32),
                    hit.downloads,
                    truncate(&hit.description, 60)
                );
            }
        }
        Command::Content(cmd) => content_command(&paths, cmd).await?,
        Command::Import(cmd) => match cmd {
            ImportCommand::List { dir } => {
                let mut roots = foreign::default_roots();
                if let Some(dir) = dir {
                    roots.push(("MultiMC".into(), dir));
                }
                let found = foreign::scan(&roots);
                if found.is_empty() {
                    println!("no instances found");
                }
                for i in found {
                    println!(
                        "  {:<28} {:<10} {:<22} {:>3} mods  {}  ({})",
                        truncate(&i.name, 28),
                        i.mc_version,
                        i.loader.to_string(),
                        i.mods,
                        i.path,
                        i.launcher
                    );
                }
            }
            ImportCommand::Run { path, name } => {
                let key = Settings::load(&paths)?.curseforge_api_key();
                let (progress, bar) = progress_bar();
                let instance = foreign::import(&paths, &path, name.as_deref(), key, &progress).await;
                bar.finish_and_clear();
                let instance = instance?;
                let list = ContentManager::new(&paths, instance.clone(), 8).list().await?;
                let linked = list.iter().filter(|e| e.source.project_key().is_some()).count();
                println!(
                    "imported '{}' ({}) - Minecraft {}, {}, {} files ({linked} linked to Modrinth/CurseForge)",
                    instance.name,
                    instance.id,
                    instance.mc_version,
                    instance.loader,
                    list.len()
                );
            }
        },
        Command::Modpack(cmd) => {
            let concurrency = Settings::load(&paths)?.download_concurrency();
            match cmd {
                ModpackCommand::Import { source, platform, name } => {
                    let path = std::path::Path::new(&source);
                    let source = if path.is_file() {
                        PackSource::File(path)
                    } else if platform == Platform::Curseforge {
                        PackSource::CurseForge(&source)
                    } else {
                        PackSource::Modrinth(&source)
                    };
                    let cf = Settings::load(&paths)?.curseforge_api_key().map(CurseForge::new);
                    let (progress, bar) = progress_bar();
                    let instance =
                        mrpack::import(&paths, source, name.as_deref(), cf.as_ref(), concurrency, &progress).await;
                    bar.finish_and_clear();
                    let instance = instance?;
                    let manager = ContentManager::new(&paths, instance.clone(), concurrency);
                    let mods = manager.list().await?.len();
                    println!(
                        "created instance '{}' ({}) - Minecraft {}, {}, {} files",
                        instance.name, instance.id, instance.mc_version, instance.loader, mods
                    );
                    print_blocked(&manager.blocked()?);
                }
                ModpackCommand::Export {
                    instance,
                    output,
                    name,
                    version,
                    include,
                } => {
                    let instance = Instance::load(&paths, &instance)?;
                    let options = ExportOptions {
                        name: name.unwrap_or_else(|| instance.name.clone()),
                        version,
                        summary: None,
                        include,
                    };
                    let (referenced, bundled) =
                        mrpack::export(&paths, &instance, &options, &output, concurrency).await?;
                    println!(
                        "exported {} ({referenced} downloads, {bundled} bundled files)",
                        output.display()
                    );
                }
            }
        }
        Command::Instance(cmd) => match cmd {
            InstanceCommand::Create {
                name,
                version,
                loader,
                loader_version,
            } => {
                let manifest = mojang::fetch_manifest(&paths).await?;
                if manifest.find(&version).is_none() {
                    bail!("unknown Minecraft version {version}");
                }
                let loader = resolve_loader(&version, loader, loader_version).await?;
                let instance = Instance::create(&paths, &name, &version, loader)?;
                println!(
                    "created instance '{}' ({}) - Minecraft {}, {}",
                    instance.name, instance.id, instance.mc_version, instance.loader
                );
            }
            InstanceCommand::List => {
                for i in Instance::list(&paths)? {
                    println!(
                        "  {:<24} {:<20} {:<10} {:<24} {:>5} min played",
                        i.id,
                        i.name,
                        i.mc_version,
                        i.loader.to_string(),
                        i.play_time_secs / 60
                    );
                }
            }
            InstanceCommand::Delete { id } => {
                Instance::delete(&paths, &id)?;
                println!("deleted {id}");
            }
            InstanceCommand::Icon { id, image, clear } => {
                let mut instance = Instance::load(&paths, &id)?;
                match image {
                    Some(file) if !clear => {
                        slopclient_core::icon::set_from_file(&paths, &mut instance, &file, None)?;
                        println!("icon set ({} px)", slopclient_core::icon::SIZE);
                    }
                    _ => {
                        slopclient_core::icon::clear(&paths, &mut instance)?;
                        println!("icon reset");
                    }
                }
            }
        },
        Command::Login => {
            let client_id = client_id(&paths)?;
            let account = AccountStore::new(&paths)
                .login_device_code(&client_id, |code| {
                    println!("Open {} and enter the code: {}", code.verification_uri, code.user_code);
                })
                .await?;
            println!("logged in as {}", account.username);
        }
        Command::Accounts => {
            let store = AccountStore::new(&paths);
            let active = store.active()?.map(|a| a.uuid);
            for a in store.list()? {
                let mark = if Some(&a.uuid) == active.as_ref() { "*" } else { " " };
                println!("{mark} {:<16} {}", a.username, a.uuid);
            }
        }
        Command::Logout { account } => {
            let store = AccountStore::new(&paths);
            let a = store.find(&account)?;
            store.remove(&a.uuid)?;
            println!("removed {}", a.username);
        }
        Command::SetClientId { client_id } => {
            let mut settings = Settings::load(&paths)?;
            settings.msa_client_id = Some(client_id);
            settings.save(&paths)?;
            println!("saved");
        }
        Command::Cape(cmd) => match cmd {
            CapeCommand::List => {
                let active = cosmetics::active(&paths)?.map(|c| c.id);
                let capes = cosmetics::list(&paths)?;
                if capes.is_empty() {
                    println!("no capes yet");
                }
                for c in capes {
                    println!(
                        "  {} {:<20} {:<24} {:>2} frames  {}x{}",
                        if active.as_deref() == Some(c.id.as_str()) { "*" } else { " " },
                        c.id,
                        truncate(&c.name, 24),
                        c.meta.frames,
                        64 * c.meta.scale,
                        32 * c.meta.scale
                    );
                }
            }
            CapeCommand::Import { png, name, frames, scale, frame_time, no_elytra, activate } => {
                let bytes = std::fs::read(&png)?;
                let meta = cosmetics::CapeMeta {
                    version: 1,
                    frames,
                    frame_time_ms: if frames > 1 { frame_time } else { 0 },
                    scale,
                    elytra: !no_elytra,
                };
                let info = cosmetics::save(
                    &paths,
                    cosmetics::NewCape { id: None, name: &name, png: &bytes, meta, source: None, editor: Default::default() },
                )?;
                if activate {
                    cosmetics::set_active(&paths, Some(&info.id))?;
                }
                println!("added cape {} ({}){}", info.name, info.id, if activate { ", active" } else { "" });
            }
            CapeCommand::Use { id, none } => {
                let id = if none { None } else { id };
                cosmetics::set_active(&paths, id.as_deref())?;
                println!("{}", if id.is_some() { "cape active" } else { "no cape active" });
                if cosmetics_online::publish_active(&paths).await? {
                    println!("published to the cosmetics server");
                }
            }
            CapeCommand::Online(cmd) => match cmd {
                OnlineCommand::Status => {
                    let s = cosmetics_online::status(&paths);
                    println!("server: {}", s.server.as_deref().unwrap_or("not configured"));
                    match (s.name, s.uuid) {
                        (Some(name), Some(uuid)) => println!("signed in as {name} ({uuid})"),
                        _ => println!("not signed in"),
                    }
                }
                OnlineCommand::SignIn { dev_offline } => {
                    let auth = match dev_offline {
                        Some(name) => LaunchAuth::offline(&name),
                        None => {
                            let store = AccountStore::new(&paths);
                            let account = store.active()?.context("no account, run `slopclient login` first")?;
                            store.launch_auth(&client_id(&paths)?, &account.uuid).await?
                        }
                    };
                    let s = cosmetics_online::sign_in(&paths, &auth).await?;
                    println!("signed in as {} ({})", s.name, s.uuid);
                    if cosmetics_online::publish_active(&paths).await? {
                        println!("active cape published");
                    }
                }
                OnlineCommand::Publish => {
                    if cosmetics_online::publish_active(&paths).await? {
                        println!("published");
                    } else {
                        bail!("not signed in, run `slopclient cape online sign-in`");
                    }
                }
                OnlineCommand::SignOut => {
                    cosmetics_online::sign_out(&paths)?;
                    println!("signed out");
                }
                OnlineCommand::DeleteAccount => {
                    cosmetics_online::delete_account(&paths).await?;
                    println!("account and capes deleted on the server");
                }
            },
            CapeCommand::Delete { id } => {
                cosmetics::delete(&paths, &id)?;
                println!("deleted {id}");
            }
        },
        Command::Ping { address } => {
            let s = slopclient_core::servers::ping(&address).await?;
            println!("{} · {}/{} Spieler · {} ms{}", s.version, s.online, s.max, s.latency_ms, if s.icon.is_some() { " · Icon" } else { "" });
            println!("{}", s.motd);
        }
        Command::Servers { instance } => {
            let instance = Instance::load(&paths, &instance)?;
            for s in slopclient_core::servers::list(&paths, &instance)? {
                println!("{:<28} {}{}", s.name, s.address, if s.icon.is_some() { "  (Icon)" } else { "" });
            }
        }
        Command::Diagnose { file } => {
            let text = std::fs::read_to_string(&file)?;
            let input = crash::CrashInput {
                exit_code: Some(1),
                crash_report: text.contains("---- Minecraft Crash Report ----").then(|| text.clone()),
                log: text,
                max_memory_mb: 4096,
                ..Default::default()
            };
            match crash::diagnose(&input) {
                Some(report) => print_crash(&report),
                None => println!("no known crash cause found"),
            }
        }
        Command::SetCurseforgeKey { key } => {
            let mut settings = Settings::load(&paths)?;
            settings.curseforge_api_key = Some(key);
            settings.save(&paths)?;
            println!("saved");
        }
        Command::Launch {
            instance,
            account,
            dev_offline,
            dry_run,
            server,
            world,
        } => {
            let instance = Instance::load(&paths, &instance)?;
            let join = server.map(launch::Join::Server).or(world.map(launch::Join::World));
            let auth = match dev_offline {
                Some(name) => LaunchAuth::offline(&name),
                None => {
                    let store = AccountStore::new(&paths);
                    let account = match account {
                        Some(q) => store.find(&q)?,
                        None => store.active()?.context("no account, run `slopclient login` first")?,
                    };
                    store.launch_auth(&client_id(&paths)?, &account.uuid).await?
                }
            };
            if dry_run {
                let (progress, bar) = progress_bar();
                let cmd = launch::prepare(&paths, &instance, &auth, join, progress).await?;
                bar.finish_and_clear();
                let std = cmd.as_std();
                println!("{}", std.get_program().to_string_lossy());
                for arg in std.get_args() {
                    let arg = arg.to_string_lossy();
                    if arg.contains("accessToken") { continue; }
                    println!("  {arg}");
                }
            } else {
                run_game(&paths, &instance, &auth, join).await?;
            }
        }
    }
    Ok(())
}

async fn content_command(paths: &Paths, cmd: ContentCommand) -> anyhow::Result<()> {
    let id = match &cmd {
        ContentCommand::List { instance }
        | ContentCommand::Add { instance, .. }
        | ContentCommand::Preset { instance }
        | ContentCommand::Remove { instance, .. }
        | ContentCommand::Enable { instance, .. }
        | ContentCommand::Disable { instance, .. }
        | ContentCommand::Updates { instance }
        | ContentCommand::Update { instance, .. }
        | ContentCommand::Identify { instance }
        | ContentCommand::Blocked { instance, .. } => instance.clone(),
    };
    let instance = Instance::load(paths, &id)?;
    let settings = Settings::load(paths)?;
    let manager = ContentManager::new(paths, instance, settings.download_concurrency())
        .with_curseforge(settings.curseforge_api_key());

    match cmd {
        ContentCommand::List { .. } => {
            let entries = manager.list().await?;
            if entries.is_empty() {
                println!("no content installed");
            }
            for e in entries {
                let source = e.source.platform();
                println!(
                    "  [{}] {:<14} {:<30} {:<28} {:<9}{}",
                    if e.enabled { "x" } else { " " },
                    e.kind.to_string(),
                    truncate(&e.name, 30),
                    truncate(e.version.as_deref().unwrap_or("-"), 28),
                    source,
                    if e.dependency { "  (dependency)" } else { "" }
                );
            }
        }
        ContentCommand::Preset { .. } => {
            let (progress, bar) = progress_bar();
            let report = manager.install_many(&slopclient_core::content::PERFORMANCE_PRESET, &progress).await?;
            bar.finish_and_clear();
            for e in &report.installed {
                println!("installed {} {}", e.name, e.version.as_deref().unwrap_or(""));
            }
            for s in &report.skipped {
                println!("already installed: {s}");
            }
            for w in &report.warnings {
                println!("warning: {w}");
            }
        }
        ContentCommand::Add { projects, source, .. } => {
            for project in projects {
                let (progress, bar) = progress_bar();
                let report = match source {
                    Platform::Modrinth => manager.install(&project, &progress).await,
                    Platform::Curseforge => manager.install_curseforge(&project, &progress).await,
                };
                bar.finish_and_clear();
                let report = report?;
                for e in &report.installed {
                    println!(
                        "installed {} {}{}",
                        e.name,
                        e.version.as_deref().unwrap_or(""),
                        if e.dependency { " (dependency)" } else { "" }
                    );
                }
                for name in &report.skipped {
                    println!("{name} is already installed");
                }
                for w in &report.warnings {
                    println!("warning: {w}");
                }
                print_blocked(&report.blocked);
            }
        }
        ContentCommand::Remove { name, .. } => {
            let e = manager.remove(&name).await?;
            println!("removed {}", e.file_name);
        }
        ContentCommand::Enable { name, .. } => {
            let e = manager.set_enabled(&name, true).await?;
            println!("enabled {}", e.file_name);
        }
        ContentCommand::Disable { name, .. } => {
            let e = manager.set_enabled(&name, false).await?;
            println!("disabled {}", e.file_name);
        }
        ContentCommand::Updates { .. } => {
            let updates = manager.check_updates().await?;
            if updates.is_empty() {
                println!("everything is up to date");
            }
            for u in updates {
                println!(
                    "  {:<30} {} -> {}",
                    u.entry.name,
                    u.entry.version.as_deref().unwrap_or("?"),
                    u.new_version.version_number
                );
            }
        }
        ContentCommand::Update { names, .. } => {
            let mut updates = manager.check_updates().await?;
            if !names.is_empty() {
                updates.retain(|u| names.iter().any(|n| u.entry.matches(n)));
            }
            if updates.is_empty() {
                println!("everything is up to date");
                return Ok(());
            }
            let (progress, bar) = progress_bar();
            manager.apply_updates(&updates, &progress).await?;
            bar.finish_and_clear();
            println!("updated {} files", updates.len());
        }
        ContentCommand::Identify { .. } => {
            let n = manager.identify().await?;
            println!("recognized {n} files");
        }
        ContentCommand::Blocked { dir, .. } => {
            let (resolved, remaining) = manager.resolve_blocked(&dir).await?;
            if resolved > 0 {
                println!("added {resolved} downloaded files");
            }
            if remaining.is_empty() {
                println!("nothing is waiting for a manual download");
            }
            print_blocked(&remaining);
        }
    }
    Ok(())
}

fn curseforge(paths: &Paths) -> anyhow::Result<CurseForge> {
    let key = Settings::load(paths)?
        .curseforge_api_key()
        .context("no CurseForge API key, run `slopclient set-curseforge-key <key>` first")?;
    Ok(CurseForge::new(key))
}

fn print_blocked(blocked: &[BlockedFile]) {
    if blocked.is_empty() {
        return;
    }
    println!(
        "{} files can only be downloaded on curseforge.com. Download them, then run `slopclient content blocked <instance>`:",
        blocked.len()
    );
    for b in blocked {
        println!("  {:<30} {}", truncate(&b.name, 30), b.page_url);
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_owned()
    } else {
        let cut: String = s.chars().take(max - 1).collect();
        format!("{cut}\u{2026}")
    }
}

async fn resolve_loader(
    mc: &str,
    kind: Option<LoaderKind>,
    version: Option<String>,
) -> anyhow::Result<Loader> {
    let Some(kind) = kind else {
        return Ok(Loader::Vanilla);
    };
    let version = match version {
        Some(v) => v,
        None => loaders::recommended(kind, mc).await?,
    };
    Ok(Loader::new(kind, version))
}

fn client_id(paths: &Paths) -> anyhow::Result<String> {
    Settings::load(paths)?
        .msa_client_id()
        .context("no Microsoft client id: run `slopclient set-client-id <azure-app-id>`")
}

async fn run_game(paths: &Paths, instance: &Instance, auth: &LaunchAuth, join: Option<launch::Join>) -> anyhow::Result<()> {
    let (progress, bar) = progress_bar();
    let mut cmd = launch::prepare(paths, instance, auth, join, progress).await?;
    bar.finish_and_clear();

    let started = now();
    let started_at = std::time::SystemTime::now();
    let mut child = cmd.spawn().context("failed to start Java")?;
    println!("game started (pid {})", child.id().unwrap_or(0));

    // The last lines feed the crash analysis.
    let tail = Arc::new(Mutex::new(std::collections::VecDeque::<String>::new()));
    let keep = |tail: &Arc<Mutex<std::collections::VecDeque<String>>>, line: &str| {
        let mut t = tail.lock().unwrap();
        if t.len() == 400 {
            t.pop_front();
        }
        t.push_back(line.to_owned());
    };
    let stdout = child.stdout.take().expect("piped stdout");
    let stderr = child.stderr.take().expect("piped stderr");
    let out_tail = tail.clone();
    let out = tokio::spawn(async move {
        let mut lines = BufReader::new(stdout).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            keep(&out_tail, &line);
            println!("{line}");
        }
    });
    let err_tail = tail.clone();
    let err = tokio::spawn(async move {
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            keep(&err_tail, &line);
            eprintln!("{line}");
        }
    });

    let status = child.wait().await?;
    let _ = tokio::join!(out, err);
    launch::record_session(paths, &instance.id, started)?;
    println!("game exited with {status}");
    let tail: Vec<String> = tail.lock().unwrap().iter().cloned().collect();
    if let Some(report) = crash::analyze(paths, instance, started_at, status.code(), &tail).await? {
        print_crash(&report);
    }
    Ok(())
}

fn print_crash(report: &crash::CrashReport) {
    println!("\ncrash: {}\n  {}", report.title, report.summary);
    for d in &report.details {
        println!("    {d}");
    }
    if let Some(c) = &report.culprit {
        println!("  likely cause: {}", c.name.as_deref().unwrap_or(&c.mod_id));
    }
    for s in &report.suggestions {
        println!("  - {s}");
    }
    if let Some(f) = &report.report_file {
        println!("  report: {f}");
    }
}

fn progress_bar() -> (ProgressFn, ProgressBar) {
    let bar = ProgressBar::new_spinner();
    bar.enable_steady_tick(Duration::from_millis(120));
    let style = ProgressStyle::with_template("{spinner} {msg} [{bar:30}] {pos}/{len}")
        .expect("template")
        .progress_chars("=> ");
    let stage = Arc::new(Mutex::new(String::new()));
    let b = bar.clone();
    let progress: ProgressFn = Arc::new(move |event| match event {
        Progress::Stage { name } => {
            *stage.lock().unwrap() = name.clone();
            b.set_style(ProgressStyle::default_spinner());
            b.set_message(name);
        }
        Progress::Download {
            finished,
            total,
            bytes,
            total_bytes,
        } => {
            b.set_style(style.clone());
            b.set_length(total as u64);
            b.set_position(finished as u64);
            b.set_message(format!(
                "{} {:.1}/{:.1} MB",
                stage.lock().unwrap(),
                bytes as f64 / 1e6,
                total_bytes as f64 / 1e6
            ));
        }
    });
    (progress, bar)
}
