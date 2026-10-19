//! DaClient cosmetics backend: stores capes per Minecraft account and tells clients
//! which cape every player wears.
//!
//! Sign-in needs no password: like joining a server, the launcher calls Mojang's
//! `session/minecraft/join` with a challenge from us, and we confirm it with
//! `hasJoined`. That proves ownership of the account; we then hand out our own token.
//!
//! Uploads go through the same format check as the launcher (`daclient_core::cosmetics`),
//! are re-encoded and stored by content hash. Capes are visible right away; reports
//! and the admin endpoint hide them.

use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use axum::{
    Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{delete, get, post, put},
};
use base64::Engine;
use daclient_core::cosmetics::{self as format, CapeMeta};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const TOKEN_DAYS: u64 = 30;
const CHALLENGE_TTL: Duration = Duration::from_secs(120);
const UPLOADS_PER_DAY: i64 = 30;
const LOOKUP_MAX: usize = 100;
/// Base64 adds a third on top of the 8 MB image limit.
const BODY_LIMIT: usize = 12 * 1024 * 1024;

#[derive(Clone, Debug)]
pub struct Config {
    pub data_dir: PathBuf,
    /// Base URL clients use to fetch images, e.g. `https://cosmetics.example.com`.
    pub public_url: String,
    /// Mojang session server (overridable for tests).
    pub session_url: String,
    /// Enables `/v1/admin/*` when set.
    pub admin_token: Option<String>,
    /// DEV ONLY: trust the username in `/v1/auth/verify` without asking Mojang.
    pub dev_auth: bool,
}

impl Config {
    pub fn from_env() -> Self {
        let var = |k: &str| std::env::var(k).ok().filter(|v| !v.is_empty());
        Self {
            data_dir: var("DACLIENT_COSMETICS_DATA").map_or_else(|| PathBuf::from("data"), PathBuf::from),
            public_url: var("DACLIENT_COSMETICS_PUBLIC_URL").unwrap_or_else(|| "http://127.0.0.1:8787".into()),
            session_url: var("DACLIENT_COSMETICS_SESSION_URL").unwrap_or_else(|| "https://sessionserver.mojang.com".into()),
            admin_token: var("DACLIENT_COSMETICS_ADMIN_TOKEN"),
            dev_auth: var("DACLIENT_COSMETICS_DEV_AUTH").is_some_and(|v| v == "1"),
        }
    }
}

pub struct AppState {
    config: Config,
    db: Mutex<Connection>,
    challenges: Mutex<HashMap<String, Instant>>,
    http: reqwest::Client,
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

// ---------- setup ----------

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

pub fn open(config: Config) -> std::io::Result<Shared> {
    std::fs::create_dir_all(config.data_dir.join("files"))?;
    let db = Connection::open(config.data_dir.join("cosmetics.db")).map_err(std::io::Error::other)?;
    db.execute_batch(
        "PRAGMA journal_mode = WAL;
         CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, active_cape TEXT, created INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS tokens (token_hash TEXT PRIMARY KEY, uuid TEXT NOT NULL, expires INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS capes (id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, hash TEXT NOT NULL,
             meta TEXT NOT NULL, created INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0);
         CREATE INDEX IF NOT EXISTS capes_owner ON capes(owner);
         CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, cape_id TEXT NOT NULL, reporter TEXT NOT NULL,
             reason TEXT NOT NULL, created INTEGER NOT NULL, resolved INTEGER NOT NULL DEFAULT 0);",
    )
    .map_err(std::io::Error::other)?;
    if config.dev_auth {
        eprintln!("WARNING: DACLIENT_COSMETICS_DEV_AUTH is on, sign-in is NOT verified with Mojang. Never use this in production.");
    }
    Ok(Arc::new(AppState {
        config,
        db: Mutex::new(db),
        challenges: Mutex::new(HashMap::new()),
        http: reqwest::Client::builder().timeout(Duration::from_secs(10)).build().expect("http client"),
    }))
}

pub fn router(state: Shared) -> Router {
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/v1/auth/challenge", post(challenge))
        .route("/v1/auth/verify", post(verify))
        .route("/v1/cosmetics/{uuid}", get(cosmetics_of))
        .route("/v1/cosmetics/lookup", post(lookup))
        .route("/v1/capes", put(upload))
        .route("/v1/capes/mine", get(mine))
        .route("/v1/capes/active", put(set_active))
        .route("/v1/capes/{id}", delete(remove))
        .route("/v1/reports", post(report))
        .route("/v1/account", delete(delete_account))
        .route("/v1/admin/reports", get(admin_reports))
        .route("/v1/admin/capes/{id}/hide", post(admin_hide))
        .route("/files/{file}", get(file))
        .layer(DefaultBodyLimit::max(BODY_LIMIT))
        .with_state(state)
}

// ---------- auth ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Challenge {
    server_id: String,
}

async fn challenge(State(s): State<Shared>) -> ApiResult<Json<Challenge>> {
    let id = random_hex(20);
    let mut map = s.challenges.lock().unwrap();
    map.retain(|_, t| t.elapsed() < CHALLENGE_TTL);
    if map.len() > 50_000 {
        return Err(ApiError::new(StatusCode::TOO_MANY_REQUESTS, "too many sign-ins, try again shortly"));
    }
    map.insert(id.clone(), Instant::now());
    Ok(Json(Challenge { server_id: id }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VerifyRequest {
    username: String,
    server_id: String,
    /// Only used with dev auth.
    #[serde(default)]
    uuid: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Session {
    token: String,
    uuid: String,
    name: String,
    expires_at: i64,
}

#[derive(Deserialize)]
struct HasJoined {
    id: String,
    name: String,
}

async fn verify(State(s): State<Shared>, Json(req): Json<VerifyRequest>) -> ApiResult<Json<Session>> {
    let fresh = s.challenges.lock().unwrap().remove(&req.server_id).is_some_and(|t| t.elapsed() < CHALLENGE_TTL);
    if !fresh {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "challenge expired, start the sign-in again"));
    }
    let (uuid, name) = if s.config.dev_auth {
        let uuid = req.uuid.as_deref().and_then(normalize_uuid).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "uuid required in dev auth"))?;
        (uuid, req.username.clone())
    } else {
        let resp = s
            .http
            .get(format!("{}/session/minecraft/hasJoined", s.config.session_url))
            .query(&[("username", req.username.as_str()), ("serverId", req.server_id.as_str())])
            .send()
            .await
            .map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "Mojang's session server is unreachable"))?;
        if resp.status() != StatusCode::OK {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "Mojang did not confirm the sign-in"));
        }
        let joined: HasJoined = resp.json().await.map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "unexpected answer from Mojang"))?;
        let uuid = normalize_uuid(&joined.id).ok_or_else(|| ApiError::new(StatusCode::BAD_GATEWAY, "unexpected answer from Mojang"))?;
        (uuid, joined.name)
    };

    let token = random_hex(32);
    let expires = now() + (TOKEN_DAYS * 86_400) as i64;
    let db = s.db.lock().unwrap();
    db.execute(
        "INSERT INTO accounts (uuid, name, created) VALUES (?1, ?2, ?3) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name",
        params![uuid, name, now()],
    )?;
    db.execute("DELETE FROM tokens WHERE expires < ?1", params![now()])?;
    db.execute("INSERT INTO tokens (token_hash, uuid, expires) VALUES (?1, ?2, ?3)", params![sha256_hex(token.as_bytes()), uuid, expires])?;
    Ok(Json(Session { token, uuid, name, expires_at: expires }))
}

/// UUID of the signed-in account (Bearer token).
fn account(s: &AppState, headers: &HeaderMap) -> ApiResult<String> {
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| ApiError::new(StatusCode::UNAUTHORIZED, "sign in first"))?;
    let db = s.db.lock().unwrap();
    db.query_row(
        "SELECT uuid FROM tokens WHERE token_hash = ?1 AND expires > ?2",
        params![sha256_hex(token.as_bytes()), now()],
        |r| r.get(0),
    )
    .optional()?
    .ok_or_else(|| ApiError::new(StatusCode::UNAUTHORIZED, "session expired, sign in again"))
}

// ---------- capes ----------

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct CapeDto {
    id: String,
    name: String,
    url: String,
    hash: String,
    meta: CapeMeta,
    created_at: i64,
    hidden: bool,
}

fn cape_dto(s: &AppState, id: String, name: String, hash: String, meta: String, created: i64, hidden: bool) -> CapeDto {
    CapeDto {
        url: format!("{}/files/{hash}.png", s.config.public_url.trim_end_matches('/')),
        meta: serde_json::from_str(&meta).unwrap_or(CapeMeta { version: 1, frames: 1, frame_time_ms: 0, scale: 1, elytra: false }),
        id,
        name,
        hash,
        created_at: created,
        hidden,
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Cosmetics {
    uuid: String,
    cape: Option<CapeDto>,
}

fn active_cape(s: &AppState, db: &Connection, uuid: &str) -> rusqlite::Result<Option<CapeDto>> {
    db.query_row(
        "SELECT c.id, c.name, c.hash, c.meta, c.created FROM accounts a JOIN capes c ON c.id = a.active_cape
         WHERE a.uuid = ?1 AND c.hidden = 0",
        params![uuid],
        |r| Ok(cape_dto(s, r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, false)),
    )
    .optional()
}

async fn cosmetics_of(State(s): State<Shared>, Path(uuid): Path<String>) -> ApiResult<Response> {
    let uuid = normalize_uuid(&uuid).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "invalid uuid"))?;
    let cape = active_cape(&s, &s.db.lock().unwrap(), &uuid)?;
    // Short cache: changing a cape should show within a minute.
    Ok(([(header::CACHE_CONTROL, "public, max-age=60")], Json(Cosmetics { uuid, cape })).into_response())
}

#[derive(Deserialize)]
struct Lookup {
    uuids: Vec<String>,
}

async fn lookup(State(s): State<Shared>, Json(req): Json<Lookup>) -> ApiResult<Json<Vec<Cosmetics>>> {
    if req.uuids.len() > LOOKUP_MAX {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("at most {LOOKUP_MAX} players per lookup")));
    }
    let db = s.db.lock().unwrap();
    let mut out = Vec::new();
    for uuid in req.uuids.iter().filter_map(|u| normalize_uuid(u)) {
        let cape = active_cape(&s, &db, &uuid)?;
        out.push(Cosmetics { uuid, cape });
    }
    Ok(Json(out))
}

#[derive(Deserialize)]
struct Upload {
    name: String,
    /// Base64 PNG spritesheet.
    png: String,
    meta: CapeMeta,
    /// Make it the active cape right away.
    #[serde(default)]
    activate: bool,
}

async fn upload(State(s): State<Shared>, headers: HeaderMap, Json(req): Json<Upload>) -> ApiResult<Json<CapeDto>> {
    let uuid = account(&s, &headers)?;
    {
        let db = s.db.lock().unwrap();
        let recent: i64 = db.query_row(
            "SELECT COUNT(*) FROM capes WHERE owner = ?1 AND created > ?2",
            params![uuid, now() - 86_400],
            |r| r.get(0),
        )?;
        if recent >= UPLOADS_PER_DAY {
            return Err(ApiError::new(StatusCode::TOO_MANY_REQUESTS, "upload limit for today reached"));
        }
    }
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(req.png.split_once(',').map_or(req.png.as_str(), |(_, b)| b))
        .map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, "png must be base64"))?;
    let meta = req.meta;
    // Decoding is CPU work; keep it off the async workers.
    let png = tokio::task::spawn_blocking(move || format::validate(&bytes, &meta))
        .await
        .map_err(|_| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "validation failed"))?
        .map_err(|e| ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, e.to_string()))?;

    let hash = sha256_hex(&png);
    let file = s.config.data_dir.join("files").join(format!("{hash}.png"));
    if !file.exists() {
        std::fs::write(&file, &png)?;
    }
    let id = random_hex(8);
    let name: String = req.name.trim().chars().take(40).collect();
    let name = if name.is_empty() { "Cape".to_owned() } else { name };
    let created = now();
    let db = s.db.lock().unwrap();
    db.execute(
        "INSERT INTO capes (id, owner, name, hash, meta, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![id, uuid, name, hash, serde_json::to_string(&meta).unwrap(), created],
    )?;
    if req.activate {
        db.execute("UPDATE accounts SET active_cape = ?1 WHERE uuid = ?2", params![id, uuid])?;
    }
    Ok(Json(cape_dto(&s, id, name, hash, serde_json::to_string(&meta).unwrap(), created, false)))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Mine {
    uuid: String,
    active_id: Option<String>,
    capes: Vec<CapeDto>,
}

async fn mine(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<Json<Mine>> {
    let uuid = account(&s, &headers)?;
    let db = s.db.lock().unwrap();
    let active_id: Option<String> = db
        .query_row("SELECT active_cape FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0))
        .optional()?
        .flatten();
    let mut stmt = db.prepare("SELECT id, name, hash, meta, created, hidden FROM capes WHERE owner = ?1 ORDER BY created DESC")?;
    let capes = stmt
        .query_map(params![uuid], |r| Ok(cape_dto(&s, r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get::<_, i64>(5)? != 0)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Json(Mine { uuid, active_id, capes }))
}

#[derive(Deserialize)]
struct SetActive {
    id: Option<String>,
}

async fn set_active(State(s): State<Shared>, headers: HeaderMap, Json(req): Json<SetActive>) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let db = s.db.lock().unwrap();
    if let Some(id) = &req.id {
        let owned: Option<String> = db
            .query_row("SELECT id FROM capes WHERE id = ?1 AND owner = ?2", params![id, uuid], |r| r.get(0))
            .optional()?;
        if owned.is_none() {
            return Err(ApiError::new(StatusCode::NOT_FOUND, "no such cape"));
        }
    }
    db.execute("UPDATE accounts SET active_cape = ?1 WHERE uuid = ?2", params![req.id, uuid])?;
    Ok(StatusCode::NO_CONTENT)
}

/// Deletes the image file when no cape uses it any more.
fn drop_unused_file(s: &AppState, db: &Connection, hash: &str) -> ApiResult<()> {
    let users: i64 = db.query_row("SELECT COUNT(*) FROM capes WHERE hash = ?1", params![hash], |r| r.get(0))?;
    if users == 0 {
        let _ = std::fs::remove_file(s.config.data_dir.join("files").join(format!("{hash}.png")));
    }
    Ok(())
}

async fn remove(State(s): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let db = s.db.lock().unwrap();
    let hash: Option<String> = db
        .query_row("SELECT hash FROM capes WHERE id = ?1 AND owner = ?2", params![id, uuid], |r| r.get(0))
        .optional()?;
    let Some(hash) = hash else {
        return Err(ApiError::new(StatusCode::NOT_FOUND, "no such cape"));
    };
    db.execute("DELETE FROM capes WHERE id = ?1", params![id])?;
    db.execute("UPDATE accounts SET active_cape = NULL WHERE uuid = ?1 AND active_cape = ?2", params![uuid, id])?;
    drop_unused_file(&s, &db, &hash)?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- reports & admin ----------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReportRequest {
    /// The reported player; their active cape is reported.
    uuid: String,
    reason: String,
}

async fn report(State(s): State<Shared>, headers: HeaderMap, Json(req): Json<ReportRequest>) -> ApiResult<StatusCode> {
    let reporter = account(&s, &headers)?;
    let target = normalize_uuid(&req.uuid).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "invalid uuid"))?;
    let db = s.db.lock().unwrap();
    let cape: Option<String> = db
        .query_row("SELECT active_cape FROM accounts WHERE uuid = ?1", params![target], |r| r.get(0))
        .optional()?
        .flatten();
    let Some(cape) = cape else {
        return Err(ApiError::new(StatusCode::NOT_FOUND, "that player wears no cape"));
    };
    let reason: String = req.reason.trim().chars().take(500).collect();
    db.execute(
        "INSERT INTO reports (cape_id, reporter, reason, created) VALUES (?1, ?2, ?3, ?4)",
        params![cape, reporter, reason, now()],
    )?;
    Ok(StatusCode::ACCEPTED)
}

fn admin(s: &AppState, headers: &HeaderMap) -> ApiResult<()> {
    let expected = s.config.admin_token.as_deref().ok_or_else(|| ApiError::new(StatusCode::NOT_FOUND, "not found"))?;
    let given = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()).and_then(|v| v.strip_prefix("Bearer "));
    // Compare hashes so the comparison time doesn't depend on the token.
    if given.map(|g| sha256_hex(g.as_bytes())) != Some(sha256_hex(expected.as_bytes())) {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "admin token required"));
    }
    Ok(())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ReportDto {
    id: i64,
    cape_id: String,
    owner: String,
    url: String,
    reporter: String,
    reason: String,
    created_at: i64,
    hidden: bool,
}

async fn admin_reports(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<Json<Vec<ReportDto>>> {
    admin(&s, &headers)?;
    let db = s.db.lock().unwrap();
    let mut stmt = db.prepare(
        "SELECT r.id, r.cape_id, c.owner, c.hash, r.reporter, r.reason, r.created, c.hidden
         FROM reports r JOIN capes c ON c.id = r.cape_id WHERE r.resolved = 0 ORDER BY r.created DESC LIMIT 200",
    )?;
    let base = s.config.public_url.trim_end_matches('/').to_owned();
    let rows = stmt
        .query_map([], |r| {
            Ok(ReportDto {
                id: r.get(0)?,
                cape_id: r.get(1)?,
                owner: r.get(2)?,
                url: format!("{base}/files/{}.png", r.get::<_, String>(3)?),
                reporter: r.get(4)?,
                reason: r.get(5)?,
                created_at: r.get(6)?,
                hidden: r.get::<_, i64>(7)? != 0,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Json(rows))
}

async fn admin_hide(State(s): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> ApiResult<StatusCode> {
    admin(&s, &headers)?;
    let db = s.db.lock().unwrap();
    if db.execute("UPDATE capes SET hidden = 1 WHERE id = ?1", params![id])? == 0 {
        return Err(ApiError::new(StatusCode::NOT_FOUND, "no such cape"));
    }
    db.execute("UPDATE reports SET resolved = 1 WHERE cape_id = ?1", params![id])?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- account & files ----------

async fn delete_account(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let db = s.db.lock().unwrap();
    let hashes: Vec<String> = db
        .prepare("SELECT hash FROM capes WHERE owner = ?1")?
        .query_map(params![uuid], |r| r.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    db.execute("DELETE FROM capes WHERE owner = ?1", params![uuid])?;
    db.execute("DELETE FROM reports WHERE reporter = ?1", params![uuid])?;
    db.execute("DELETE FROM tokens WHERE uuid = ?1", params![uuid])?;
    db.execute("DELETE FROM accounts WHERE uuid = ?1", params![uuid])?;
    for hash in hashes {
        drop_unused_file(&s, &db, &hash)?;
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn file(State(s): State<Shared>, Path(file): Path<String>) -> ApiResult<Response> {
    let hash = file.strip_suffix(".png").unwrap_or("");
    if hash.len() != 64 || !hash.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(ApiError::new(StatusCode::NOT_FOUND, "not found"));
    }
    let bytes = match std::fs::read(s.config.data_dir.join("files").join(format!("{hash}.png"))) {
        Ok(b) => b,
        Err(_) => return Err(ApiError::new(StatusCode::NOT_FOUND, "not found")),
    };
    // Content-addressed, so it never changes.
    Ok(Response::builder()
        .header(header::CONTENT_TYPE, "image/png")
        .header(header::CACHE_CONTROL, "public, max-age=31536000, immutable")
        .body(Body::from(bytes))
        .unwrap())
}
