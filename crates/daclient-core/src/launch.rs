//! Builds the Java command line and starts the game.

use std::{collections::HashMap, path::PathBuf, process::Stdio};

use crate::{
    Paths, ProgressFn, Result,
    config::Settings,
    install::{self, Installer, ResolvedVersion},
    instance::{Instance, now},
    java,
    meta::mojang::Argument,
    progress::stage,
    rules::{self, Features},
};

/// Identity passed to the game.
#[derive(Debug, Clone)]
pub struct LaunchAuth {
    pub username: String,
    /// UUID without dashes.
    pub uuid: String,
    pub access_token: String,
    pub xuid: Option<String>,
    pub user_type: String,
}

impl LaunchAuth {
    /// Offline identity for local development only; online servers reject it.
    pub fn offline(username: &str) -> Self {
        let mut hash = md5::compute(format!("OfflinePlayer:{username}")).0;
        hash[6] = (hash[6] & 0x0f) | 0x30;
        hash[8] = (hash[8] & 0x3f) | 0x80;
        Self {
            username: username.to_owned(),
            uuid: hex::encode(hash),
            access_token: "0".into(),
            xuid: None,
            user_type: "legacy".into(),
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct LaunchOptions {
    pub java: PathBuf,
    pub game_dir: PathBuf,
    pub min_memory_mb: u32,
    pub max_memory_mb: u32,
    pub extra_jvm_args: Vec<String>,
    pub resolution: Option<(u32, u32)>,
}

/// Command line arguments after `java`.
pub fn build_args(version: &ResolvedVersion, auth: &LaunchAuth, opts: &LaunchOptions) -> Vec<String> {
    let info = &version.info;
    let mut features = Features::new();
    features.insert("has_custom_resolution".into(), opts.resolution.is_some());

    let mut classpath: Vec<String> = version
        .libraries
        .iter()
        .map(|p| p.to_string_lossy().into_owned())
        .collect();
    classpath.push(version.client_jar.to_string_lossy().into_owned());

    let (width, height) = opts.resolution.unwrap_or((854, 480));
    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", auth.username.clone()),
        ("auth_uuid", auth.uuid.clone()),
        ("auth_access_token", auth.access_token.clone()),
        ("auth_session", auth.access_token.clone()),
        ("auth_xuid", auth.xuid.clone().unwrap_or_else(|| "0".into())),
        ("clientid", "0".into()),
        ("user_type", auth.user_type.clone()),
        ("user_properties", "{}".into()),
        ("version_name", info.id.clone()),
        ("version_type", info.kind.clone().unwrap_or_else(|| "release".into())),
        ("game_directory", path_str(&opts.game_dir)),
        ("assets_root", path_str(&version.assets_root)),
        ("game_assets", path_str(&version.game_assets)),
        ("assets_index_name", version.asset_index_id.clone()),
        ("natives_directory", path_str(&version.natives_dir)),
        ("library_directory", path_str(&version.assets_root.with_file_name("libraries"))),
        ("classpath", classpath.join(";")),
        ("classpath_separator", ";".into()),
        ("launcher_name", "DaClient".into()),
        ("launcher_version", env!("CARGO_PKG_VERSION").into()),
        ("resolution_width", width.to_string()),
        ("resolution_height", height.to_string()),
    ]);

    let mut args = vec![
        format!("-Xms{}M", opts.min_memory_mb),
        format!("-Xmx{}M", opts.max_memory_mb),
    ];
    args.extend(opts.extra_jvm_args.iter().cloned());

    match &info.arguments {
        Some(a) if !a.jvm.is_empty() => args.extend(collect(&a.jvm, &features)),
        _ => args.extend(
            [
                "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
                "-Djava.library.path=${natives_directory}",
                "-cp",
                "${classpath}",
            ]
            .map(String::from),
        ),
    }
    args.extend(version.logging_arg.clone());
    args.push(info.main_class.clone().unwrap_or_else(|| "net.minecraft.client.main.Main".into()));

    match (&info.arguments, &info.minecraft_arguments) {
        (Some(a), _) if !a.game.is_empty() => args.extend(collect(&a.game, &features)),
        (_, Some(legacy)) => {
            args.extend(legacy.split_whitespace().map(String::from));
            if let Some((w, h)) = opts.resolution {
                args.extend(["--width".into(), w.to_string(), "--height".into(), h.to_string()]);
            }
        }
        _ => {}
    }

    args.into_iter().map(|a| substitute(&a, &vars)).collect()
}

fn collect(arguments: &[Argument], features: &Features) -> Vec<String> {
    let mut out = Vec::new();
    for arg in arguments {
        match arg {
            Argument::Plain(s) => out.push(s.clone()),
            Argument::Conditional { rules: r, value } => {
                if rules::allowed(r, features) {
                    out.extend(value.values());
                }
            }
        }
    }
    out
}

fn substitute(arg: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = String::with_capacity(arg.len());
    let mut rest = arg;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        match after.find('}') {
            Some(end) => {
                let key = &after[..end];
                match vars.get(key) {
                    Some(value) => out.push_str(value),
                    None => out.push_str(&rest[start..start + 3 + end]),
                }
                rest = &after[end + 1..];
            }
            None => {
                out.push_str(&rest[start..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

fn path_str(p: &std::path::Path) -> String {
    p.to_string_lossy().into_owned()
}

/// Windows limits a command line to 32 767 characters; above this, arguments go to an @argfile.
const MAX_COMMAND_LINE: usize = 30_000;

fn argfile_line(arg: &str) -> String {
    format!("\"{}\"", arg.replace('\\', "\\\\").replace('"', "\\\""))
}

/// Installs everything the instance needs and returns a ready-to-spawn command.
pub async fn prepare(
    paths: &Paths,
    instance: &Instance,
    auth: &LaunchAuth,
    progress: ProgressFn,
) -> Result<tokio::process::Command> {
    let settings = Settings::load(paths)?;
    let concurrency = settings.download_concurrency();
    let installer = Installer::new(paths, concurrency, progress.clone());
    let version_id = installer
        .install_loader(&instance.mc_version, &instance.loader)
        .await?;
    let version = installer.install(&version_id).await?;

    let java = match &instance.java.path {
        Some(path) => path.clone(),
        None => {
            let component = java::component_for(&version.info);
            java::ensure_runtime(paths, &component, concurrency, &progress).await?
        }
    };

    let game_dir = instance.game_dir(paths);
    std::fs::create_dir_all(&game_dir)?;
    if version.map_to_resources {
        let index = install::read_asset_index(paths, &version.asset_index_id)?;
        let objects = paths.assets().join("objects");
        let target = game_dir.join("resources");
        tokio::task::spawn_blocking(move || install::copy_assets(&index, &objects, &target)).await??;
    }

    stage(&progress, "Starting game");
    let opts = LaunchOptions {
        java: java.clone(),
        game_dir: game_dir.clone(),
        min_memory_mb: instance.java.min_memory_mb,
        max_memory_mb: instance.java.max_memory_mb,
        extra_jvm_args: instance.java.jvm_args.clone(),
        resolution: None,
    };
    let mut args = build_args(&version, auth, &opts);

    let length: usize = args.iter().map(|a| a.len() + 3).sum();
    let java_major = version.info.java_version.as_ref().map_or(8, |j| j.major_version);
    if length > MAX_COMMAND_LINE && java_major >= 9 {
        let file = instance.dir(paths).join("launch.args");
        let content: Vec<String> = args.iter().map(|a| argfile_line(a)).collect();
        std::fs::write(&file, content.join("\n"))?;
        args = vec![format!("@{}", file.display())];
    }

    let mut cmd = tokio::process::Command::new(java);
    cmd.args(args)
        .current_dir(&game_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(false);
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    Ok(cmd)
}

/// Records a finished session in the instance's play statistics.
pub fn record_session(paths: &Paths, instance_id: &str, started_at: u64) -> Result<()> {
    let mut instance = Instance::load(paths, instance_id)?;
    instance.last_played = Some(started_at);
    instance.play_time_secs += now().saturating_sub(started_at);
    instance.save(paths)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn substitutes_known_variables_only() {
        let vars = HashMap::from([("a", "1".to_string()), ("b", "two".to_string())]);
        assert_eq!(substitute("-D${a}=${b}", &vars), "-D1=two");
        assert_eq!(substitute("${unknown}x", &vars), "${unknown}x");
        assert_eq!(substitute("open ${a", &vars), "open ${a");
    }

    #[test]
    fn offline_uuid_matches_java() {
        // Java: UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(UTF_8))
        assert_eq!(LaunchAuth::offline("Steve").uuid, "5627dd98e6be3c21b8a8e92344183641");
    }
}
