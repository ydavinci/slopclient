//! Web interface: a page to manage your cosmetics in the browser (`/`), signed in with
//! Microsoft by device code like the launcher. The Microsoft and Minecraft tokens are only
//! used to read the Minecraft profile (which proves the account) and dropped right after;
//! the browser gets a session token like the launcher's, with a shorter lifetime.

use std::time::{Duration, Instant};

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use base64::Engine;
use serde::Serialize;
use slopclient_core::auth::{msa, xbox};

use crate::{
    ApiError, ApiResult, Shared,
    auth::{Session, WEB_TOKEN_DAYS, account, create_session},
    limited, limits, lock, normalize_uuid, random_hex,
};

/// Pending web sign-ins at once; more are refused until some finish.
const MAX_PENDING: usize = 500;
/// Finished or failed sign-ins the page did not pick up are dropped after this.
const KEEP: Duration = Duration::from_secs(20 * 60);
/// Skins are fetched from Mojang at most this often per player.
const SKIN_TTL: Duration = Duration::from_secs(300);
const SKIN_CACHE_MAX: usize = 2_000;

const INDEX_HTML: &str = include_str!("web/index.html");
const COMMON_JS: &str = include_str!("web/common.js");
const APP_JS: &str = include_str!("web/app.js");
/// 3D preview (three.js, skinview3d, hat and pet models), built from launcher/src/webviewer.
const VIEWER_JS: &str = include_str!("web/viewer.js");
/// Largest skin the preview fetches from Mojang.
const SKIN_MAX_BYTES: usize = 256 * 1024;
const MOJANG_SESSION: &str = "https://sessionserver.mojang.com";

pub(crate) struct Login {
    started: Instant,
    state: LoginState,
}

enum LoginState {
    Waiting,
    Done(Session),
    Failed(String),
}

pub(crate) struct CachedSkin {
    at: Instant,
    png: Vec<u8>,
    model: &'static str,
}

pub(crate) fn routes(s: &Shared) -> Router<Shared> {
    Router::new()
        .route("/", get(index))
        .route("/common.js", get(|| async { script(COMMON_JS) }))
        .route("/app.js", get(|| async { script(APP_JS) }))
        .route("/viewer.js", get(|| async { script(VIEWER_JS) }))
        .route("/v1/extras/catalogue", get(catalogue))
        .merge(limited(s, &limits::READ, Router::new().route("/v1/skin", get(own_skin))))
        .merge(limited(s, &limits::WEB_LOGIN, Router::new().route("/v1/web/login", post(start_login))))
        .merge(limited(s, &limits::POLL, Router::new().route("/v1/web/login/{id}", get(login_status))))
}

/// The page only loads its own scripts and same-origin, data and blob images.
const CSP: &str = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

async fn index() -> Response {
    (
        [
            (header::CONTENT_TYPE, "text/html; charset=utf-8"),
            (header::CONTENT_SECURITY_POLICY, CSP),
            (header::X_FRAME_OPTIONS, "DENY"),
            (header::CACHE_CONTROL, "no-cache"),
        ],
        INDEX_HTML,
    )
        .into_response()
}

fn script(body: &'static str) -> Response {
    ([(header::CONTENT_TYPE, "text/javascript; charset=utf-8"), (header::CACHE_CONTROL, "no-cache")], body).into_response()
}

/// The hat and pet catalogue (cosmetics/extras.json) with the models, for the page's 3D preview.
async fn catalogue() -> Response {
    ([(header::CONTENT_TYPE, "application/json"), (header::CACHE_CONTROL, "public, max-age=3600")], slopclient_core::extras::CATALOGUE_JSON).into_response()
}

// ---------- skin for the preview ----------

/// The signed-in player's Minecraft skin, fetched from Mojang for the 3D preview (browsers
/// may not put Mojang's skin images into WebGL). The model goes in `x-skin-model`.
async fn own_skin(State(s): State<Shared>, headers: HeaderMap) -> ApiResult<Response> {
    let uuid = account(&s, &headers)?;
    let cached = lock(&s.skins).get(&uuid).filter(|c| c.at.elapsed() < SKIN_TTL).map(|c| (c.png.clone(), c.model));
    let (png, model) = match cached {
        Some(hit) => hit,
        None => {
            if !s.limiter.allow(&limits::SKIN, &uuid) {
                return Err(ApiError::new(StatusCode::TOO_MANY_REQUESTS, "too many requests, slow down"));
            }
            let fetched = fetch_skin(&s, &uuid).await.ok_or_else(|| ApiError::new(StatusCode::NOT_FOUND, "no skin available"))?;
            let mut skins = lock(&s.skins);
            if skins.len() >= SKIN_CACHE_MAX {
                skins.retain(|_, c| c.at.elapsed() < SKIN_TTL);
                if skins.len() >= SKIN_CACHE_MAX {
                    skins.clear();
                }
            }
            skins.insert(uuid, CachedSkin { at: Instant::now(), png: fetched.0.clone(), model: fetched.1 });
            fetched
        }
    };
    Ok((
        [
            (header::CONTENT_TYPE, "image/png"),
            (header::CACHE_CONTROL, "private, no-store"),
            (header::HeaderName::from_static("x-skin-model"), model),
        ],
        png,
    )
        .into_response())
}

/// Profile → skin URL → PNG. Only Mojang's texture server over https (or, when testing
/// against another session server, that server), no redirects, at most SKIN_MAX_BYTES.
async fn fetch_skin(s: &Shared, uuid: &str) -> Option<(Vec<u8>, &'static str)> {
    let session = reqwest::Url::parse(&s.config.session_url).ok()?;
    let profile: serde_json::Value = s
        .skin_http
        .get(format!("{}/session/minecraft/profile/{uuid}", s.config.session_url))
        .send()
        .await
        .ok()?
        .error_for_status()
        .ok()?
        .json()
        .await
        .ok()?;
    let textures = profile["properties"]
        .as_array()?
        .iter()
        .find(|p| p["name"] == "textures")
        .and_then(|p| p["value"].as_str())
        .and_then(|v| base64::engine::general_purpose::STANDARD.decode(v).ok())
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())?;
    let skin = &textures["textures"]["SKIN"];
    let mut url = reqwest::Url::parse(skin["url"].as_str()?).ok()?;
    if url.host_str() == Some("textures.minecraft.net") {
        // Mojang hands out http:// links.
        url.set_scheme("https").ok()?;
        url.set_port(None).ok()?;
    } else {
        let same_origin = url.scheme() == session.scheme() && url.host_str() == session.host_str() && url.port_or_known_default() == session.port_or_known_default();
        if s.config.session_url.trim_end_matches('/') == MOJANG_SESSION || !same_origin {
            return None;
        }
    }
    let mut resp = s.skin_http.get(url).send().await.ok()?.error_for_status().ok()?;
    if resp.content_length().is_some_and(|n| n > SKIN_MAX_BYTES as u64) {
        return None;
    }
    let mut png = Vec::new();
    while let Some(chunk) = resp.chunk().await.ok()? {
        png.extend_from_slice(&chunk);
        if png.len() > SKIN_MAX_BYTES {
            return None;
        }
    }
    if !png.starts_with(b"\x89PNG") {
        return None;
    }
    Some((png, if skin["metadata"]["model"] == "slim" { "slim" } else { "default" }))
}

// ---------- Microsoft sign-in ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Started {
    login_id: String,
    user_code: String,
    verification_uri: String,
    expires_in: u64,
}

/// Starts a Microsoft device code sign-in; the page shows the code and polls the status.
async fn start_login(State(s): State<Shared>) -> ApiResult<Json<Started>> {
    let client_id = s
        .config
        .web_login_client_id
        .clone()
        .ok_or_else(|| ApiError::new(StatusCode::NOT_FOUND, "web sign-in is switched off on this server"))?;
    // Reserve the slot before asking Microsoft, so parallel requests cannot pass the cap together.
    let id = random_hex(24);
    {
        let mut logins = lock(&s.web_logins);
        logins.retain(|_, l| l.started.elapsed() < KEEP);
        if logins.len() >= MAX_PENDING {
            return Err(ApiError::new(StatusCode::TOO_MANY_REQUESTS, "too many sign-ins, try again shortly"));
        }
        logins.insert(id.clone(), Login { started: Instant::now(), state: LoginState::Waiting });
    }
    let code = match msa::start_device_code(&client_id).await {
        Ok(code) => code,
        Err(e) => {
            lock(&s.web_logins).remove(&id);
            eprintln!("web sign-in: device code failed: {e}");
            return Err(ApiError::new(StatusCode::BAD_GATEWAY, "Microsoft sign-in is unavailable right now"));
        }
    };
    let started = Started {
        login_id: id.clone(),
        user_code: code.user_code.clone(),
        verification_uri: code.verification_uri.clone(),
        expires_in: code.expires_in,
    };
    let state = s.clone();
    tokio::spawn(async move {
        let result = finish(&state, &client_id, &code).await;
        if let Some(login) = lock(&state.web_logins).get_mut(&id) {
            login.state = match result {
                Ok(session) => LoginState::Done(session),
                Err(msg) => LoginState::Failed(msg),
            };
        }
    });
    Ok(Json(started))
}

/// What the page may show about a failed sign-in: the explanations from the login chain
/// (no Xbox profile, child account, no Minecraft …), anything else only in the log.
fn sign_in_error(e: slopclient_core::Error) -> String {
    match e {
        slopclient_core::Error::Auth(msg) => msg,
        e => {
            eprintln!("web sign-in failed: {e}");
            "sign-in failed, try again".into()
        }
    }
}

/// Waits for the code, proves the account through Xbox and Minecraft, then forgets those tokens.
async fn finish(s: &Shared, client_id: &str, code: &msa::DeviceCode) -> Result<Session, String> {
    let token = msa::poll_device_code(client_id, code).await.map_err(sign_in_error)?;
    let mc = xbox::login(&token.access_token).await.map_err(sign_in_error)?;
    drop(token);
    let uuid = normalize_uuid(&mc.profile.id).ok_or("unexpected Minecraft profile")?;
    create_session(s, uuid, mc.profile.name, WEB_TOKEN_DAYS).map_err(|e| e.1)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase", tag = "status")]
enum Status {
    Waiting,
    Done(Session),
    Failed { error: String },
}

/// `waiting`, then once `done` with the session (handed out a single time) or `failed`.
async fn login_status(State(s): State<Shared>, Path(id): Path<String>) -> ApiResult<Json<Status>> {
    let mut logins = lock(&s.web_logins);
    match logins.get(&id).map(|l| matches!(l.state, LoginState::Waiting)) {
        None => Err(ApiError::new(StatusCode::NOT_FOUND, "unknown or expired sign-in")),
        Some(true) => Ok(Json(Status::Waiting)),
        Some(false) => Ok(Json(match logins.remove(&id).map(|l| l.state) {
            Some(LoginState::Done(session)) => Status::Done(session),
            Some(LoginState::Failed(error)) => Status::Failed { error },
            _ => Status::Waiting,
        })),
    }
}
