//! Sign-in and sessions: the launcher's challenge (confirmed by Mojang's `hasJoined`),
//! session tokens, logout.

use std::time::{Duration, Instant};

use axum::{
    Json,
    extract::State,
    http::{HeaderMap, StatusCode, header},
};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use slopclient_core::cosmetics_online::bound_server_id;

use crate::{ApiError, ApiResult, AppState, Shared, lock, normalize_uuid, now, random_hex, sha256_hex};

/// Launcher sessions.
pub(crate) const TOKEN_DAYS: i64 = 30;
/// Web sessions are shorter: a code phished from someone is worth less.
pub(crate) const WEB_TOKEN_DAYS: i64 = 7;
const CHALLENGE_TTL: Duration = Duration::from_secs(120);
const MAX_CHALLENGES: usize = 50_000;
/// Sessions per account; signing in again drops the oldest.
const MAX_TOKENS: i64 = 20;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Challenge {
    server_id: String,
}

pub(crate) async fn challenge(State(s): State<Shared>) -> ApiResult<Json<Challenge>> {
    let id = random_hex(20);
    let mut map = lock(&s.challenges);
    if map.len() >= MAX_CHALLENGES {
        map.retain(|_, t| t.elapsed() < CHALLENGE_TTL);
        if map.len() >= MAX_CHALLENGES {
            return Err(ApiError::new(StatusCode::TOO_MANY_REQUESTS, "too many sign-ins, try again shortly"));
        }
    }
    map.insert(id.clone(), Instant::now());
    Ok(Json(Challenge { server_id: id }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VerifyRequest {
    username: String,
    server_id: String,
    /// Only used with dev auth.
    #[serde(default)]
    uuid: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Session {
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

pub(crate) async fn verify(State(s): State<Shared>, Json(req): Json<VerifyRequest>) -> ApiResult<Json<Session>> {
    let fresh = lock(&s.challenges).remove(&req.server_id).is_some_and(|t| t.elapsed() < CHALLENGE_TTL);
    if !fresh {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "challenge expired, start the sign-in again"));
    }
    if req.username.len() > 16 {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "invalid username"));
    }
    let (uuid, name) = if s.config.dev_auth {
        let uuid = req.uuid.as_deref().and_then(normalize_uuid).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "uuid required in dev auth"))?;
        (uuid, req.username.clone())
    } else {
        // The launcher joined the challenge bound to our address (see bound_server_id),
        // so a join made for another server's challenge does not count here.
        let joined_id = bound_server_id(&s.config.public_url, &req.server_id);
        let resp = s
            .http
            .get(format!("{}/session/minecraft/hasJoined", s.config.session_url))
            .query(&[("username", req.username.as_str()), ("serverId", joined_id.as_str())])
            .send()
            .await
            .map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "Mojang's session server is unreachable"))?;
        if resp.status() != StatusCode::OK {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "Mojang did not confirm the sign-in (update the launcher?)"));
        }
        let joined: HasJoined = resp.json().await.map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "unexpected answer from Mojang"))?;
        let uuid = normalize_uuid(&joined.id).ok_or_else(|| ApiError::new(StatusCode::BAD_GATEWAY, "unexpected answer from Mojang"))?;
        (uuid, joined.name)
    };
    create_session(&s, uuid, name, TOKEN_DAYS).map(Json)
}

/// Stores the account and hands out a new session token (shared with the web sign-in).
pub(crate) fn create_session(s: &AppState, uuid: String, name: String, days: i64) -> ApiResult<Session> {
    let token = random_hex(32);
    let expires = now() + days * 86_400;
    let db = s.db();
    db.execute(
        "INSERT INTO accounts (uuid, name, created) VALUES (?1, ?2, ?3) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name",
        params![uuid, name, now()],
    )?;
    db.execute("DELETE FROM tokens WHERE expires < ?1", params![now()])?;
    db.execute("INSERT INTO tokens (token_hash, uuid, expires) VALUES (?1, ?2, ?3)", params![sha256_hex(token.as_bytes()), uuid, expires])?;
    db.execute(
        "DELETE FROM tokens WHERE uuid = ?1 AND token_hash NOT IN
           (SELECT token_hash FROM tokens WHERE uuid = ?1 ORDER BY expires DESC LIMIT ?2)",
        params![uuid, MAX_TOKENS],
    )?;
    Ok(Session { token, uuid, name, expires_at: expires })
}

fn bearer(headers: &HeaderMap) -> Option<&str> {
    headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()).and_then(|v| v.strip_prefix("Bearer "))
}

/// UUID of the signed-in account (Bearer token).
pub(crate) fn account(s: &AppState, headers: &HeaderMap) -> ApiResult<String> {
    let token = bearer(headers).ok_or_else(|| ApiError::new(StatusCode::UNAUTHORIZED, "sign in first"))?;
    s.db()
        .query_row(
            "SELECT uuid FROM tokens WHERE token_hash = ?1 AND expires > ?2",
            params![sha256_hex(token.as_bytes()), now()],
            |r| r.get(0),
        )
        .optional()?
        .ok_or_else(|| ApiError::new(StatusCode::UNAUTHORIZED, "session expired, sign in again"))
}

#[derive(Deserialize, Default)]
pub(crate) struct Logout {
    /// Ends every session of the account (all launchers and browsers).
    #[serde(default)]
    everywhere: bool,
}

pub(crate) async fn logout(State(s): State<Shared>, headers: HeaderMap, body: Option<Json<Logout>>) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let everywhere = body.is_some_and(|Json(b)| b.everywhere);
    let db = s.db();
    if everywhere {
        db.execute("DELETE FROM tokens WHERE uuid = ?1", params![uuid])?;
    } else if let Some(token) = bearer(&headers) {
        db.execute("DELETE FROM tokens WHERE token_hash = ?1", params![sha256_hex(token.as_bytes())])?;
    }
    Ok(StatusCode::NO_CONTENT)
}
