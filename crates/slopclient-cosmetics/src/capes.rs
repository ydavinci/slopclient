//! Capes, hats and pets: what players wear, their cape library, uploads and the images.

use std::path::Path as FsPath;

use axum::{
    Extension, Json,
    body::Body,
    extract::{Path, Request, State},
    http::{HeaderMap, StatusCode, header},
    middleware::Next,
    response::{IntoResponse, Response},
};
use base64::Engine;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use slopclient_core::cosmetics::{self as format, CapeMeta};

use crate::{
    ApiError, ApiResult, AppState, Shared, auth::account, limits, normalize_uuid, now, random_hex, sha256_hex,
};

const UPLOADS_PER_DAY: i64 = 30;
const MAX_CAPES: i64 = 50;
const LOOKUP_MAX: usize = 100;

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CapeDto {
    id: String,
    name: String,
    url: String,
    hash: String,
    meta: CapeMeta,
    created_at: i64,
    hidden: bool,
}

pub(crate) fn cape_dto(s: &AppState, id: String, name: String, hash: String, meta: String, created: i64, hidden: bool) -> CapeDto {
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
pub(crate) struct Cosmetics {
    uuid: String,
    cape: Option<CapeDto>,
    hat: Option<String>,
    pet: Option<String>,
}

fn extras_of(db: &Connection, uuid: &str) -> rusqlite::Result<(Option<String>, Option<String>)> {
    Ok(db
        .query_row("SELECT hat, pet FROM accounts WHERE uuid = ?1", params![uuid], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?
        .unwrap_or((None, None)))
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

fn cosmetics(s: &AppState, db: &Connection, uuid: String) -> rusqlite::Result<Cosmetics> {
    let cape = active_cape(s, db, &uuid)?;
    let (hat, pet) = extras_of(db, &uuid)?;
    Ok(Cosmetics { uuid, cape, hat, pet })
}

pub(crate) async fn cosmetics_of(State(s): State<Shared>, Path(uuid): Path<String>) -> ApiResult<Response> {
    let uuid = normalize_uuid(&uuid).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "invalid uuid"))?;
    let c = cosmetics(&s, &s.db(), uuid)?;
    // Short cache: changing a cape should show within a minute.
    Ok(([(header::CACHE_CONTROL, "public, max-age=60")], Json(c)).into_response())
}

#[derive(Deserialize)]
pub(crate) struct Lookup {
    uuids: Vec<String>,
}

pub(crate) async fn lookup(State(s): State<Shared>, Json(req): Json<Lookup>) -> ApiResult<Json<Vec<Cosmetics>>> {
    if req.uuids.len() > LOOKUP_MAX {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("at most {LOOKUP_MAX} players per lookup")));
    }
    let mut uuids: Vec<String> = req.uuids.iter().filter_map(|u| normalize_uuid(u)).collect();
    uuids.sort();
    uuids.dedup();
    let db = s.db();
    let out = uuids.into_iter().map(|u| cosmetics(&s, &db, u)).collect::<rusqlite::Result<_>>()?;
    Ok(Json(out))
}

// ---------- upload ----------

/// The account of an upload, checked before its body is read.
#[derive(Clone)]
pub(crate) struct Uploader(String);

/// Middleware for `PUT /v1/capes`: signed in, and within the per-account attempt limit
/// (failed uploads count too), before up to 12 MB are read.
pub(crate) async fn upload_gate(State(s): State<Shared>, mut req: Request, next: Next) -> Response {
    let uuid = match account(&s, req.headers()) {
        Ok(u) => u,
        Err(e) => return e.into_response(),
    };
    if !s.limiter.allow(&limits::UPLOAD_ATTEMPTS, &uuid) {
        return ApiError::new(StatusCode::TOO_MANY_REQUESTS, "too many uploads, try again later").into_response();
    }
    req.extensions_mut().insert(Uploader(uuid));
    next.run(req).await
}

#[derive(Deserialize)]
pub(crate) struct Upload {
    name: String,
    /// Base64 PNG spritesheet.
    png: String,
    meta: CapeMeta,
    /// Make it the active cape right away.
    #[serde(default)]
    activate: bool,
}

/// Daily and total cape limits of an account.
fn check_quota(db: &Connection, uuid: &str) -> ApiResult<()> {
    let (recent, total): (i64, i64) = db.query_row(
        "SELECT COUNT(*) FILTER (WHERE created > ?2), COUNT(*) FROM capes WHERE owner = ?1",
        params![uuid, now() - 86_400],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    if recent >= UPLOADS_PER_DAY {
        return Err(ApiError::new(StatusCode::TOO_MANY_REQUESTS, "upload limit for today reached"));
    }
    if total >= MAX_CAPES {
        return Err(ApiError::new(StatusCode::CONFLICT, format!("at most {MAX_CAPES} capes per account, delete some first")));
    }
    Ok(())
}

/// Writes to a temporary file and renames it, so a crash never leaves a torn image.
fn write_atomic(path: &FsPath, data: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension(format!("{}.tmp", random_hex(6)));
    std::fs::write(&tmp, data)?;
    std::fs::rename(&tmp, path).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })
}

fn is_blocked(db: &Connection, hash: &str) -> rusqlite::Result<bool> {
    db.prepare_cached("SELECT 1 FROM blocked_hashes WHERE hash = ?1")?.exists(params![hash])
}

pub(crate) async fn upload(State(s): State<Shared>, Extension(Uploader(uuid)): Extension<Uploader>, Json(req): Json<Upload>) -> ApiResult<Json<CapeDto>> {
    check_quota(&s.db(), &uuid)?;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(req.png.split_once(',').map_or(req.png.as_str(), |(_, b)| b))
        .map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, "png must be base64"))?;
    drop(req.png);
    let meta = req.meta;
    // Decoding and re-encoding take memory and CPU: only a few at once, off the async workers.
    let permit = s.upload_slots.acquire().await.map_err(|_| ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "shutting down"))?;
    let png = tokio::task::spawn_blocking(move || format::validate(&bytes, &meta))
        .await
        .map_err(|_| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "validation failed"))?
        .map_err(|e| ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, e.to_string()))?;
    drop(permit);

    let hash = sha256_hex(&png);
    let id = random_hex(8);
    let name: String = req.name.trim().chars().filter(|c| !c.is_control()).take(40).collect();
    let name = if name.is_empty() { "Cape".to_owned() } else { name };
    let created = now();
    let meta_json = serde_json::to_string(&meta).map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, "invalid meta"))?;
    // Quota, file and row under one lock: parallel uploads cannot pass the quota together,
    // and removing a cape cannot delete the file between writing it and inserting the row.
    let db = s.db();
    check_quota(&db, &uuid)?;
    if is_blocked(&db, &hash)? {
        return Err(ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, "this image was removed by the moderation"));
    }
    let file = s.config.data_dir.join("files").join(format!("{hash}.png"));
    if !file.exists() {
        write_atomic(&file, &png)?;
    }
    db.execute(
        "INSERT INTO capes (id, owner, name, hash, meta, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![id, uuid, name, hash, meta_json, created],
    )?;
    if req.activate {
        db.execute("UPDATE accounts SET active_cape = ?1 WHERE uuid = ?2", params![id, uuid])?;
    }
    Ok(Json(cape_dto(&s, id, name, hash, meta_json, created, false)))
}

// ---------- your capes ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Mine {
    uuid: String,
    active_id: Option<String>,
    capes: Vec<CapeDto>,
}

pub(crate) async fn mine(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<Json<Mine>> {
    let uuid = account(&s, &headers)?;
    let db = s.db();
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
pub(crate) struct SetActive {
    id: Option<String>,
}

/// Which hat and pet the signed-in player wears (ids from the shared catalogue).
pub(crate) async fn set_extras(State(s): State<Shared>, headers: HeaderMap, Json(req): Json<slopclient_core::extras::Choice>) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let checked = req.clone().checked();
    if checked != req {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "unknown hat or pet"));
    }
    s.db().execute("UPDATE accounts SET hat = ?1, pet = ?2 WHERE uuid = ?3", params![checked.hat, checked.pet, uuid])?;
    Ok(StatusCode::NO_CONTENT)
}

pub(crate) async fn set_active(State(s): State<Shared>, headers: HeaderMap, Json(req): Json<SetActive>) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let db = s.db();
    if let Some(id) = &req.id {
        let owned = db.prepare_cached("SELECT 1 FROM capes WHERE id = ?1 AND owner = ?2")?.exists(params![id, uuid])?;
        if !owned {
            return Err(ApiError::new(StatusCode::NOT_FOUND, "no such cape"));
        }
    }
    db.execute("UPDATE accounts SET active_cape = ?1 WHERE uuid = ?2", params![req.id, uuid])?;
    Ok(StatusCode::NO_CONTENT)
}

/// Deletes the image file when no cape uses it any more (call with the database locked).
pub(crate) fn drop_unused_file(s: &AppState, db: &Connection, hash: &str) -> ApiResult<()> {
    let users: i64 = db.query_row("SELECT COUNT(*) FROM capes WHERE hash = ?1", params![hash], |r| r.get(0))?;
    if users == 0 {
        let _ = std::fs::remove_file(s.config.data_dir.join("files").join(format!("{hash}.png")));
    }
    Ok(())
}

pub(crate) async fn remove(State(s): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let db = s.db();
    let hash: Option<String> = db
        .query_row("SELECT hash FROM capes WHERE id = ?1 AND owner = ?2", params![id, uuid], |r| r.get(0))
        .optional()?;
    let Some(hash) = hash else {
        return Err(ApiError::new(StatusCode::NOT_FOUND, "no such cape"));
    };
    db.execute("DELETE FROM capes WHERE id = ?1", params![id])?;
    db.execute("DELETE FROM reports WHERE cape_id = ?1", params![id])?;
    db.execute("UPDATE accounts SET active_cape = NULL WHERE uuid = ?1 AND active_cape = ?2", params![uuid, id])?;
    drop_unused_file(&s, &db, &hash)?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- images ----------

pub(crate) async fn file(State(s): State<Shared>, Path(file): Path<String>) -> ApiResult<Response> {
    let not_found = || ApiError::new(StatusCode::NOT_FOUND, "not found");
    let hash = file.strip_suffix(".png").unwrap_or("");
    if hash.len() != 64 || !hash.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(not_found());
    }
    // Images removed by the moderation are not served, even while the file still exists.
    if is_blocked(&s.db(), hash)? {
        return Err(not_found());
    }
    let bytes = tokio::fs::read(s.config.data_dir.join("files").join(format!("{hash}.png"))).await.map_err(|_| not_found())?;
    // Content-addressed, so it never changes.
    Ok((
        [(header::CONTENT_TYPE, "image/png"), (header::CACHE_CONTROL, "public, max-age=31536000, immutable")],
        Body::from(bytes),
    )
        .into_response())
}
