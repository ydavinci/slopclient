//! What players are listening to: the client's media helper reports the song while it plays
//! (only when the player switched sharing on), and other clients look it up to show it above
//! the nametag. Kept in memory only and forgotten after `TTL` without an update, so a
//! crashed client or a closed game does not leave a stale song behind.

use std::time::{Duration, Instant};

use axum::{
    Json,
    extract::State,
    http::{HeaderMap, StatusCode},
};
use serde::{Deserialize, Serialize};

use crate::{ApiError, ApiResult, Shared, auth::account, lock, normalize_uuid};

/// How long a song stays without an update; the helper repeats it every minute while it plays.
const TTL: Duration = Duration::from_secs(150);
const LOOKUP_MAX: usize = 100;
const TITLE_MAX: usize = 120;
const ARTIST_MAX: usize = 120;

pub(crate) struct Listening {
    title: String,
    artist: String,
    at: Instant,
}

#[derive(Deserialize)]
pub(crate) struct SetListening {
    title: String,
    #[serde(default)]
    artist: String,
}

/// Trims and drops control characters, so nothing odd reaches other players' screens.
fn clean(s: &str, max: usize) -> String {
    s.chars().filter(|c| !c.is_control()).take(max).collect::<String>().trim().to_owned()
}

pub(crate) async fn set(State(s): State<Shared>, headers: HeaderMap, Json(req): Json<SetListening>) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    let title = clean(&req.title, TITLE_MAX);
    if title.is_empty() {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "title missing"));
    }
    let artist = clean(&req.artist, ARTIST_MAX);
    let mut map = lock(&s.listening);
    let now = Instant::now();
    map.retain(|_, l| now.duration_since(l.at) < TTL);
    map.insert(uuid, Listening { title, artist, at: now });
    Ok(StatusCode::NO_CONTENT)
}

pub(crate) async fn clear(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<StatusCode> {
    let uuid = account(&s, &headers)?;
    lock(&s.listening).remove(&uuid);
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub(crate) struct Lookup {
    uuids: Vec<String>,
}

#[derive(Serialize)]
pub(crate) struct Entry {
    uuid: String,
    title: String,
    artist: String,
}

pub(crate) async fn lookup(State(s): State<Shared>, Json(req): Json<Lookup>) -> ApiResult<Json<Vec<Entry>>> {
    if req.uuids.len() > LOOKUP_MAX {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("at most {LOOKUP_MAX} players per lookup")));
    }
    let map = lock(&s.listening);
    let now = Instant::now();
    let out = req
        .uuids
        .iter()
        .filter_map(|u| normalize_uuid(u))
        .filter_map(|uuid| {
            let l = map.get(&uuid).filter(|l| now.duration_since(l.at) < TTL)?;
            Some(Entry { uuid, title: l.title.clone(), artist: l.artist.clone() })
        })
        .collect();
    Ok(Json(out))
}

/// Forgets an account's song (sign-out everywhere, account deletion).
pub(crate) fn forget(s: &crate::AppState, uuid: &str) {
    lock(&s.listening).remove(uuid);
}
