//! Client for the DaClient cosmetics backend (`crates/daclient-cosmetics`):
//! sign-in through Mojang's session server, publishing the active cape.
//!
//! The backend token is stored DPAPI-encrypted in `cosmetics/session.dat`.

use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::{
    Error, Paths, Result, auth::secrets, config::Settings, cosmetics, http, instance::now, launch::LaunchAuth,
};

const MOJANG_JOIN: &str = "https://sessionserver.mojang.com/session/minecraft/join";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OnlineSession {
    pub server: String,
    pub token: String,
    pub uuid: String,
    pub name: String,
    pub expires_at: i64,
}

/// What the launcher shows: which server, and who is signed in there.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OnlineStatus {
    pub server: Option<String>,
    pub uuid: Option<String>,
    pub name: Option<String>,
}

fn session_file(paths: &Paths) -> std::path::PathBuf {
    paths.root.join("cosmetics").join("session.dat")
}

pub fn server(paths: &Paths) -> Option<String> {
    Settings::load(paths).ok()?.cosmetics_url()
}

/// The stored session if it belongs to the configured server and hasn't expired.
pub fn session(paths: &Paths) -> Option<OnlineSession> {
    let server = server(paths)?;
    let data = secrets::unprotect(&std::fs::read(session_file(paths)).ok()?).ok()?;
    let s: OnlineSession = serde_json::from_slice(&data).ok()?;
    (s.server == server && s.expires_at > now() as i64 + 60).then_some(s)
}

pub fn status(paths: &Paths) -> OnlineStatus {
    let s = session(paths);
    OnlineStatus { server: server(paths), uuid: s.as_ref().map(|s| s.uuid.clone()), name: s.map(|s| s.name) }
}

fn save_session(paths: &Paths, s: &OnlineSession) -> Result<()> {
    std::fs::create_dir_all(paths.root.join("cosmetics"))?;
    std::fs::write(session_file(paths), secrets::protect(&serde_json::to_vec(s)?)?)?;
    Ok(())
}

pub fn sign_out(paths: &Paths) -> Result<()> {
    match std::fs::remove_file(session_file(paths)) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.into()),
        _ => Ok(()),
    }
}

#[derive(Deserialize)]
struct ApiErrorBody {
    error: String,
}

/// Turns a non-2xx answer into a readable error.
async fn check(resp: reqwest::Response) -> Result<reqwest::Response> {
    if resp.status().is_success() {
        return Ok(resp);
    }
    let status = resp.status();
    let msg = resp.json::<ApiErrorBody>().await.map(|b| b.error).unwrap_or_default();
    Err(Error::Other(format!("cosmetics server: {} {}", status.as_u16(), msg).trim().to_owned()))
}

fn need_server(paths: &Paths) -> Result<String> {
    server(paths).ok_or_else(|| Error::Other("no cosmetics server configured (cosmeticsUrl in settings.json)".into()))
}

fn need_session(paths: &Paths) -> Result<OnlineSession> {
    session(paths).ok_or_else(|| Error::Other("not signed in to the cosmetics server".into()))
}

/// Signs in with a Minecraft account. Offline (dev) identities skip Mojang; only
/// a server started with DACLIENT_COSMETICS_DEV_AUTH=1 accepts them.
pub async fn sign_in(paths: &Paths, auth: &LaunchAuth) -> Result<OnlineSession> {
    let server = need_server(paths)?;
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Challenge {
        server_id: String,
    }
    let c: Challenge = check(http::client().post(format!("{server}/v1/auth/challenge")).send().await?)
        .await?
        .json()
        .await?;
    if auth.user_type == "msa" {
        let join = http::client()
            .post(MOJANG_JOIN)
            .json(&json!({ "accessToken": auth.access_token, "selectedProfile": auth.uuid, "serverId": c.server_id }))
            .send()
            .await?;
        if !join.status().is_success() {
            return Err(Error::Auth(format!("Mojang refused the session join ({})", join.status())));
        }
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Verified {
        token: String,
        uuid: String,
        name: String,
        expires_at: i64,
    }
    let v: Verified = check(
        http::client()
            .post(format!("{server}/v1/auth/verify"))
            .json(&json!({ "username": auth.username, "serverId": c.server_id, "uuid": auth.uuid }))
            .send()
            .await?,
    )
    .await?
    .json()
    .await?;
    let s = OnlineSession { server, token: v.token, uuid: v.uuid, name: v.name, expires_at: v.expires_at };
    save_session(paths, &s)?;
    Ok(s)
}

/// Makes the server match the local active cape: uploads it once (the remote id is
/// remembered per cape), then marks it active; no active cape clears it online.
/// Returns false when not signed in (nothing to do).
pub async fn publish_active(paths: &Paths) -> Result<bool> {
    let Some(s) = session(paths) else {
        return Ok(false);
    };
    let Some(cape) = cosmetics::active(paths)? else {
        put_active(&s, None).await?;
        return Ok(true);
    };
    if let Some(remote) = cosmetics::remote_id(paths, &cape.id, &s.server)? {
        match put_active(&s, Some(&remote)).await {
            Ok(()) => return Ok(true),
            // Deleted online (or hidden by moderation): upload again below.
            Err(Error::Other(m)) if m.contains(" 404") => {}
            Err(e) => return Err(e),
        }
    }
    use base64::Engine as _;
    let png = cosmetics::cape_png(paths, &cape.id)?;
    #[derive(Deserialize)]
    struct Uploaded {
        id: String,
    }
    let up: Uploaded = check(
        http::client()
            .put(format!("{}/v1/capes", s.server))
            .bearer_auth(&s.token)
            .json(&json!({
                "name": cape.name,
                "png": base64::engine::general_purpose::STANDARD.encode(png),
                "meta": cape.meta,
                "activate": true,
            }))
            .send()
            .await?,
    )
    .await?
    .json()
    .await?;
    cosmetics::set_remote_id(paths, &cape.id, &s.server, &up.id)?;
    Ok(true)
}

async fn put_active(s: &OnlineSession, id: Option<&str>) -> Result<()> {
    check(
        http::client()
            .put(format!("{}/v1/capes/active", s.server))
            .bearer_auth(&s.token)
            .json(&json!({ "id": id }))
            .send()
            .await?,
    )
    .await?;
    Ok(())
}

/// Deletes the account and all capes on the server, then signs out.
pub async fn delete_account(paths: &Paths) -> Result<()> {
    let s = need_session(paths)?;
    check(http::client().delete(format!("{}/v1/account", s.server)).bearer_auth(&s.token).send().await?).await?;
    sign_out(paths)
}
