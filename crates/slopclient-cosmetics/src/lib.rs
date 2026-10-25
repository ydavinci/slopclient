//! SlopClient cosmetics backend: stores capes per Minecraft account and tells clients
//! which cape every player wears.
//!
//! Sign-in needs no password: like joining a server, the launcher calls Mojang's
//! `session/minecraft/join` with a challenge from us (bound to our address), and we
//! confirm it with `hasJoined`. That proves ownership of the account; we then hand out
//! our own token. The web interface signs in with Microsoft instead (see `web`).
//!
//! Uploads go through the same format check as the launcher (`slopclient_core::cosmetics`),
//! are re-encoded and stored by content hash. Capes are visible right away; reports
//! and the admin endpoint hide them.

use std::{
    collections::HashMap,
    net::IpAddr,
    path::PathBuf,
    sync::{Arc, Mutex, MutexGuard},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use axum::{
    Json, Router,
    extract::DefaultBodyLimit,
    http::{HeaderValue, StatusCode, header},
    middleware::from_fn_with_state,
    response::{IntoResponse, Response},
    routing::{delete, get, post, put},
};
use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};
use tower_http::{
    set_header::SetResponseHeaderLayer,
    timeout::{RequestBodyTimeoutLayer, TimeoutLayer},
};

mod auth;
mod capes;
mod catalogue;
mod limits;
mod moderation;
mod web;

use limits::{Limiter, Rule, limit};

/// Small JSON bodies everywhere except the cape upload.
const BODY_LIMIT: usize = 64 * 1024;
/// Base64 adds a third on top of the 8 MB image limit.
const UPLOAD_BODY_LIMIT: usize = 12 * 1024 * 1024;
/// Image checks at once (each can take a few hundred MB and seconds of CPU).
const UPLOAD_SLOTS: usize = 2;
/// Shortest admin token accepted.
const ADMIN_TOKEN_MIN: usize = 24;

#[derive(Clone, Debug)]
pub struct Config {
    pub data_dir: PathBuf,
    /// Base URL clients use, e.g. `https://cosmetics.example.com`. Sign-in challenges
    /// are bound to it, so it must be the address players enter in the launcher.
    pub public_url: String,
    /// Mojang session server (overridable for tests).
    pub session_url: String,
    /// Enables `/v1/admin/*` when set.
    pub admin_token: Option<String>,
    /// DEV ONLY: trust the username in `/v1/auth/verify` without asking Mojang.
    pub dev_auth: bool,
    /// Azure client id for the web interface's Microsoft sign-in (device code);
    /// none switches the web sign-in off.
    pub web_login_client_id: Option<String>,
    /// Take the client address for rate limits from `X-Forwarded-For` (behind Caddy).
    pub trust_proxy: bool,
}

impl Config {
    pub fn from_env() -> Self {
        let var = |k: &str| std::env::var(k).ok().filter(|v| !v.is_empty());
        // The DACLIENT_* names from before the rename still work, but only for harmless
        // settings: a forgotten old DEV_AUTH or SESSION_URL must not switch off the Mojang check.
        let legacy = |k: &str| var(k).or_else(|| var(&k.replacen("SLOPCLIENT_", "DACLIENT_", 1)));
        let admin_token = legacy("SLOPCLIENT_COSMETICS_ADMIN_TOKEN").filter(|t| {
            let ok = t.len() >= ADMIN_TOKEN_MIN;
            if !ok {
                eprintln!("WARNING: SLOPCLIENT_COSMETICS_ADMIN_TOKEN is shorter than {ADMIN_TOKEN_MIN} characters; the admin API stays off. Use `openssl rand -hex 32`.");
            }
            ok
        });
        Self {
            data_dir: legacy("SLOPCLIENT_COSMETICS_DATA").map_or_else(|| PathBuf::from("data"), PathBuf::from),
            public_url: legacy("SLOPCLIENT_COSMETICS_PUBLIC_URL").unwrap_or_else(|| "http://127.0.0.1:8787".into()),
            session_url: var("SLOPCLIENT_COSMETICS_SESSION_URL").unwrap_or_else(|| "https://sessionserver.mojang.com".into()),
            admin_token,
            dev_auth: var("SLOPCLIENT_COSMETICS_DEV_AUTH").is_some_and(|v| v == "1"),
            // SlopClient's own Azure app, the same the launcher signs in with.
            web_login_client_id: if var("SLOPCLIENT_COSMETICS_WEB_LOGIN").is_some_and(|v| v == "0") {
                None
            } else {
                Some(var("SLOPCLIENT_COSMETICS_MSA_CLIENT_ID").unwrap_or_else(|| slopclient_core::config::SLOPCLIENT_MSA_CLIENT_ID.into()))
            },
            trust_proxy: var("SLOPCLIENT_COSMETICS_TRUST_PROXY").is_some_and(|v| v == "1"),
        }
    }
}

pub struct AppState {
    config: Config,
    db: Mutex<Connection>,
    challenges: Mutex<HashMap<String, Instant>>,
    /// Web sign-ins waiting for the Microsoft device code (see `web`).
    web_logins: Mutex<HashMap<String, web::Login>>,
    /// Skins fetched for the web preview, per uuid (see `web`).
    skins: Mutex<HashMap<String, web::CachedSkin>>,
    limiter: Limiter,
    upload_slots: tokio::sync::Semaphore,
    http: reqwest::Client,
    /// For fetching skins: follows no redirects, so the host check cannot be bypassed.
    skin_http: reqwest::Client,
}

impl AppState {
    /// The database; a panic elsewhere while it was locked does not take the server down.
    fn db(&self) -> MutexGuard<'_, Connection> {
        self.db.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Locks a mutex, recovering it if a panic poisoned it.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

pub type Shared = Arc<AppState>;

// ---------- errors ----------

pub struct ApiError(StatusCode, String);

impl ApiError {
    fn new(status: StatusCode, msg: impl Into<String>) -> Self {
        Self(status, msg.into())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(serde_json::json!({ "error": self.1 }))).into_response()
    }
}

impl From<rusqlite::Error> for ApiError {
    fn from(e: rusqlite::Error) -> Self {
        eprintln!("database error: {e}");
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "database error")
    }
}

impl From<std::io::Error> for ApiError {
    fn from(e: std::io::Error) -> Self {
        eprintln!("io error: {e}");
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "storage error")
    }
}

type ApiResult<T> = Result<T, ApiError>;

// ---------- helpers ----------

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    getrandom::getrandom(&mut buf).expect("system random");
    hex::encode(buf)
}

fn sha256_hex(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

/// UUIDs without dashes, lowercase (Mojang's session API format).
fn normalize_uuid(uuid: &str) -> Option<String> {
    let u: String = uuid.chars().filter(|c| *c != '-').collect::<String>().to_lowercase();
    (u.len() == 32 && u.chars().all(|c| c.is_ascii_hexdigit())).then_some(u)
}

/// Checks `SLOPCLIENT_COSMETICS_PUBLIC_URL`: clients load images from it and sign-ins are
/// bound to it, so it must be a full http(s) address such as `https://cosmetics.example.com`.
pub fn check_public_url(url: &str) -> Result<(), String> {
    let parsed = reqwest::Url::parse(url).map_err(|e| format!("{url:?} is not a URL ({e})"))?;
    if !matches!(parsed.scheme(), "http" | "https") || parsed.host_str().is_none_or(str::is_empty) {
        return Err(format!("{url:?} needs http:// or https:// and a host name (is the domain variable empty?)"));
    }
    if parsed.path() != "/" || parsed.query().is_some() {
        return Err(format!("{url:?} must be just the address, without a path"));
    }
    Ok(())
}

/// Whether a bind address only accepts local connections.
pub fn is_loopback_bind(bind: &str) -> bool {
    let host = bind.rsplit_once(':').map_or(bind, |(h, _)| h).trim_matches(['[', ']']);
    host == "localhost" || host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback())
}

// ---------- setup ----------

pub fn open(config: Config) -> std::io::Result<Shared> {
    std::fs::create_dir_all(config.data_dir.join("files"))?;
    let db = Connection::open(config.data_dir.join("cosmetics.db")).map_err(std::io::Error::other)?;
    db.execute_batch(
        "PRAGMA journal_mode = WAL;
         PRAGMA synchronous = NORMAL;
         PRAGMA busy_timeout = 5000;
         CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, active_cape TEXT, created INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS tokens (token_hash TEXT PRIMARY KEY, uuid TEXT NOT NULL, expires INTEGER NOT NULL);
         CREATE INDEX IF NOT EXISTS tokens_uuid ON tokens(uuid);
         CREATE INDEX IF NOT EXISTS tokens_expires ON tokens(expires);
         CREATE TABLE IF NOT EXISTS capes (id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, hash TEXT NOT NULL,
             meta TEXT NOT NULL, created INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0);
         CREATE INDEX IF NOT EXISTS capes_owner ON capes(owner);
         CREATE INDEX IF NOT EXISTS capes_hash ON capes(hash);
         CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, cape_id TEXT NOT NULL, reporter TEXT NOT NULL,
             reason TEXT NOT NULL, created INTEGER NOT NULL, resolved INTEGER NOT NULL DEFAULT 0);
         CREATE TABLE IF NOT EXISTS blocked_hashes (hash TEXT PRIMARY KEY, created INTEGER NOT NULL);
         -- One report per player and cape (older databases may hold duplicates).
         DELETE FROM reports WHERE id NOT IN (SELECT MIN(id) FROM reports GROUP BY reporter, cape_id);
         CREATE UNIQUE INDEX IF NOT EXISTS reports_once ON reports(reporter, cape_id);",
    )
    .map_err(std::io::Error::other)?;
    // Hats and pets came later: add their columns to existing databases.
    for column in ["hat", "pet"] {
        let has: bool = db
            .prepare("SELECT 1 FROM pragma_table_info('accounts') WHERE name = ?1")
            .and_then(|mut q| q.exists(params![column]))
            .map_err(std::io::Error::other)?;
        if !has {
            db.execute_batch(&format!("ALTER TABLE accounts ADD COLUMN {column} TEXT")).map_err(std::io::Error::other)?;
        }
    }
    if config.dev_auth {
        eprintln!("WARNING: SLOPCLIENT_COSMETICS_DEV_AUTH is on, sign-in is NOT verified with Mojang. Never use this in production.");
    }
    let http = || reqwest::Client::builder().timeout(Duration::from_secs(10));
    Ok(Arc::new(AppState {
        config,
        db: Mutex::new(db),
        challenges: Mutex::new(HashMap::new()),
        web_logins: Mutex::new(HashMap::new()),
        skins: Mutex::new(HashMap::new()),
        limiter: Limiter::default(),
        upload_slots: tokio::sync::Semaphore::new(UPLOAD_SLOTS),
        http: http().build().expect("http client"),
        skin_http: http().redirect(reqwest::redirect::Policy::none()).build().expect("http client"),
    }))
}

/// Routes behind one rate limit rule.
fn limited(state: &Shared, rule: &'static Rule, routes: Router<Shared>) -> Router<Shared> {
    routes.route_layer(from_fn_with_state((state.clone(), rule), limit))
}

pub fn router(state: Shared) -> Router {
    let s = &state;
    let header = |name, value| SetResponseHeaderLayer::if_not_present(name, HeaderValue::from_static(value));
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .merge(limited(
            s,
            &limits::SIGN_IN,
            Router::new().route("/v1/auth/challenge", post(auth::challenge)).route("/v1/auth/verify", post(auth::verify)),
        ))
        .merge(limited(
            s,
            &limits::READ,
            Router::new()
                .route("/v1/cosmetics/{uuid}", get(capes::cosmetics_of))
                .route("/v1/cosmetics/lookup", post(capes::lookup))
                .route("/v1/capes/mine", get(capes::mine))
                .route("/files/{file}", get(capes::file)),
        ))
        .merge(limited(
            s,
            &limits::WRITE,
            Router::new()
                .route("/v1/auth/logout", post(auth::logout))
                .route("/v1/capes/active", put(capes::set_active))
                .route("/v1/extras", put(capes::set_extras))
                .route("/v1/capes/{id}", delete(capes::remove))
                .route("/v1/reports", post(moderation::report))
                .route("/v1/account", delete(moderation::delete_account))
                .route("/v1/admin/reports", get(moderation::admin_reports))
                .route("/v1/admin/capes/{id}/hide", post(moderation::admin_hide)),
        ))
        // The upload checks the account before reading its large body.
        .merge(
            limited(
                s,
                &limits::WRITE,
                Router::new()
                    .route("/v1/capes", put(capes::upload))
                    .route_layer(from_fn_with_state(state.clone(), capes::upload_gate))
                    .layer(DefaultBodyLimit::max(UPLOAD_BODY_LIMIT)),
            ),
        )
        .merge(catalogue::routes(s))
        .merge(web::routes(s))
        .layer(DefaultBodyLimit::max(BODY_LIMIT))
        .layer(RequestBodyTimeoutLayer::new(Duration::from_secs(30)))
        .layer(TimeoutLayer::with_status_code(StatusCode::REQUEST_TIMEOUT, Duration::from_secs(60)))
        .layer(header(header::X_CONTENT_TYPE_OPTIONS, "nosniff"))
        .layer(header(header::REFERRER_POLICY, "no-referrer"))
        // API answers carry tokens and account data: never store them in a shared cache.
        .layer(header(header::CACHE_CONTROL, "no-store"))
        .with_state(state)
}
