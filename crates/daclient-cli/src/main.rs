//! Test CLI for the DaClient backend.

use std::{
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, bail};
use clap::{Parser, Subcommand};
use daclient_core::{
    Paths, Progress, ProgressFn,
    auth::AccountStore,
    config::Settings,
    content::{ContentManager, Source, modrinth::{self, SearchQuery}},
    install::Installer,
    instance::{Instance, Loader, now},
    launch::{self, LaunchAuth},
    meta::{LoaderKind, loaders, mojang},
};
use indicatif::{ProgressBar, ProgressStyle};
use tokio::io::{AsyncBufReadExt, BufReader};

#[derive(Parser)]
#[command(name = "daclient", version, about = "DaClient launcher backend (CLI)")]
struct Cli {
    #[command(subcommand)]
    command: Command,
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
    /// Search Modrinth
    Search {
        query: String,
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
    },
    /// Set the Azure client id used for Microsoft login
    SetClientId { client_id: String },
}

#[derive(Subcommand)]
enum ContentCommand {
    /// List installed content
    List { instance: String },
    /// Install Modrinth projects (slug or id) with their dependencies
    Add {
        instance: String,
        #[arg(required = true)]
        projects: Vec<String>,
    },
    /// Remove a file (by name, file name or slug)
    Remove { instance: String, name: String },
    Enable { instance: String, name: String },
    Disable { instance: String, name: String },
    /// Show available updates
    Updates { instance: String },
    /// Install all available updates
    Update { instance: String },
    /// Recognize manually added files on Modrinth
    Identify { instance: String },
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
            let result = modrinth::search(&q).await?;
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
        Command::Launch {
            instance,
            account,
            dev_offline,
            dry_run,
        } => {
            let instance = Instance::load(&paths, &instance)?;
            let auth = match dev_offline {
                Some(name) => LaunchAuth::offline(&name),
                None => {
                    let store = AccountStore::new(&paths);
                    let account = match account {
                        Some(q) => store.find(&q)?,
                        None => store.active()?.context("no account, run `daclient login` first")?,
                    };
                    store.launch_auth(&client_id(&paths)?, &account.uuid).await?
                }
            };
            if dry_run {
                let (progress, bar) = progress_bar();
                let cmd = launch::prepare(&paths, &instance, &auth, progress).await?;
                bar.finish_and_clear();
                let std = cmd.as_std();
                println!("{}", std.get_program().to_string_lossy());
                for arg in std.get_args() {
                    let arg = arg.to_string_lossy();
                    if arg.contains("accessToken") { continue; }
                    println!("  {arg}");
                }
            } else {
                run_game(&paths, &instance, &auth).await?;
            }
        }
    }
    Ok(())
}

async fn content_command(paths: &Paths, cmd: ContentCommand) -> anyhow::Result<()> {
    let id = match &cmd {
        ContentCommand::List { instance }
        | ContentCommand::Add { instance, .. }
        | ContentCommand::Remove { instance, .. }
        | ContentCommand::Enable { instance, .. }
        | ContentCommand::Disable { instance, .. }
        | ContentCommand::Updates { instance }
        | ContentCommand::Update { instance }
        | ContentCommand::Identify { instance } => instance.clone(),
    };
    let instance = Instance::load(paths, &id)?;
    let manager = ContentManager::new(paths, instance, Settings::load(paths)?.download_concurrency());

    match cmd {
        ContentCommand::List { .. } => {
            let entries = manager.list().await?;
            if entries.is_empty() {
                println!("no content installed");
            }
            for e in entries {
                let source = match &e.source {
                    Source::Modrinth { .. } => "modrinth",
                    Source::Local => "local",
                };
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
        ContentCommand::Add { projects, .. } => {
            for project in projects {
                let (progress, bar) = progress_bar();
                let report = manager.install(&project, &progress).await;
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
        ContentCommand::Update { .. } => {
            let updates = manager.check_updates().await?;
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
            println!("recognized {n} files on Modrinth");
        }
    }
    Ok(())
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
        .context("no Microsoft client id: run `daclient set-client-id <azure-app-id>`")
}

async fn run_game(paths: &Paths, instance: &Instance, auth: &LaunchAuth) -> anyhow::Result<()> {
    let (progress, bar) = progress_bar();
    let mut cmd = launch::prepare(paths, instance, auth, progress).await?;
    bar.finish_and_clear();

    let started = now();
    let mut child = cmd.spawn().context("failed to start Java")?;
    println!("game started (pid {})", child.id().unwrap_or(0));

    let stdout = child.stdout.take().expect("piped stdout");
    let stderr = child.stderr.take().expect("piped stderr");
    let out = tokio::spawn(async move {
        let mut lines = BufReader::new(stdout).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            println!("{line}");
        }
    });
    let err = tokio::spawn(async move {
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            eprintln!("{line}");
        }
    });

    let status = child.wait().await?;
    let _ = tokio::join!(out, err);
    launch::record_session(paths, &instance.id, started)?;
    println!("game exited with {status}");
    Ok(())
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
