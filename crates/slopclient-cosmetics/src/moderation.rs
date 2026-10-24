//! Reports, the admin endpoints that act on them, and deleting an account.

use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode, header},
};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};

use crate::{ApiError, ApiResult, AppState, Shared, auth::account, capes::drop_unused_file, normalize_uuid, now, sha256_hex};

/// Reports one player may file per day.
const REPORTS_PER_DAY: i64 = 20;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReportRequest {
    /// The reported player; their active cape is reported.
    uuid: String,
    reason: String,
}

pub(crate) async fn report(State(s): State<Shared>, headers: HeaderMap, Json(req): Json<ReportRequest>) -> ApiResult<StatusCode> {
    let reporter = account(&s, &headers)?;
    let target = normalize_uuid(&req.uuid).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "invalid uuid"))?;
    let db = s.db();
    let today: i64 = db.query_row(
        "SELECT COUNT(*) FROM reports WHERE reporter = ?1 AND created > ?2",
        params![reporter, now() - 86_400],
        |r| r.get(0),
    )?;
    if today >= REPORTS_PER_DAY {
        return Err(ApiError::new(StatusCode::TOO_MANY_REQUESTS, "report limit for today reached"));
    }
    let cape: Option<String> = db
        .query_row("SELECT active_cape FROM accounts WHERE uuid = ?1", params![target], |r| r.get(0))
        .optional()?
        .flatten();
    let Some(cape) = cape else {
        return Err(ApiError::new(StatusCode::NOT_FOUND, "that player wears no cape"));
    };
    let reason: String = req.reason.trim().chars().take(500).collect();
    // One report per player and cape; reporting again changes nothing.
    db.execute(
        "INSERT OR IGNORE INTO reports (cape_id, reporter, reason, created) VALUES (?1, ?2, ?3, ?4)",
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
pub(crate) struct ReportDto {
    cape_id: String,
    owner: String,
    url: String,
    /// How many players reported this cape.
    reports: i64,
    /// Newest reasons first.
    reasons: Vec<String>,
    last_reported_at: i64,
    hidden: bool,
}

/// Open reports grouped by cape, most reported first, so spam cannot bury a cape.
pub(crate) async fn admin_reports(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<Json<Vec<ReportDto>>> {
    admin(&s, &headers)?;
    let db = s.db();
    let mut stmt = db.prepare(
        "SELECT r.cape_id, c.owner, c.hash, COUNT(*), MAX(r.created), c.hidden,
                (SELECT json_group_array(reason) FROM (SELECT reason FROM reports r2
                   WHERE r2.cape_id = r.cape_id AND r2.resolved = 0 ORDER BY r2.created DESC LIMIT 10))
         FROM reports r JOIN capes c ON c.id = r.cape_id WHERE r.resolved = 0
         GROUP BY r.cape_id ORDER BY COUNT(*) DESC, MAX(r.created) DESC LIMIT 200",
    )?;
    let base = s.config.public_url.trim_end_matches('/').to_owned();
    let rows = stmt
        .query_map([], |r| {
            Ok(ReportDto {
                cape_id: r.get(0)?,
                owner: r.get(1)?,
                url: format!("{base}/files/{}.png", r.get::<_, String>(2)?),
                reports: r.get(3)?,
                last_reported_at: r.get(4)?,
                hidden: r.get::<_, i64>(5)? != 0,
                reasons: serde_json::from_str(&r.get::<_, String>(6)?).unwrap_or_default(),
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Json(rows))
}

/// Hides a cape for everyone and blocks its image, so it cannot simply be uploaded again.
pub(crate) async fn admin_hide(State(s): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> ApiResult<StatusCode> {
    admin(&s, &headers)?;
    let db = s.db();
    let hash: Option<String> = db.query_row("SELECT hash FROM capes WHERE id = ?1", params![id], |r| r.get(0)).optional()?;
    let Some(hash) = hash else {
        return Err(ApiError::new(StatusCode::NOT_FOUND, "no such cape"));
    };
    db.execute("UPDATE capes SET hidden = 1 WHERE hash = ?1", params![hash])?;
    db.execute("INSERT OR IGNORE INTO blocked_hashes (hash, created) VALUES (?1, ?2)", params![hash, now()])?;
    db.execute("UPDATE reports SET resolved = 1 WHERE cape_id IN (SELECT id FROM capes WHERE hash = ?1)", params![hash])?;
    Ok(StatusCode::NO_CONTENT)
}

pub(crate) async fn delete_account(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let db = s.db();
    let hashes: Vec<String> = db
        .prepare("SELECT hash FROM capes WHERE owner = ?1")?
        .query_map(params![uuid], |r| r.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    db.execute("DELETE FROM reports WHERE reporter = ?1 OR cape_id IN (SELECT id FROM capes WHERE owner = ?1)", params![uuid])?;
    db.execute("DELETE FROM capes WHERE owner = ?1", params![uuid])?;
    db.execute("DELETE FROM tokens WHERE uuid = ?1", params![uuid])?;
    db.execute("DELETE FROM accounts WHERE uuid = ?1", params![uuid])?;
    for hash in hashes {
        drop_unused_file(&s, &db, &hash)?;
    }
    Ok(StatusCode::NO_CONTENT)
}
