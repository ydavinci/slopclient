//! End-to-end tests against a running server and a fake Mojang session server.

use std::{
    collections::HashSet,
    sync::{Arc, Mutex},
};

use axum::{Json, Router, extract::Query, http::StatusCode, response::IntoResponse, routing::get};
use base64::Engine;
use slopclient_core::cosmetics_online::bound_server_id;
use slopclient_cosmetics::{Config, open, router};
use serde_json::{Value, json};

type Joined = Arc<Mutex<HashSet<(String, String)>>>;

/// Fake `sessionserver.mojang.com`: knows which (username, serverId) pairs "joined".
async fn fake_mojang(joined: Joined) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    // Profile with a slim skin served by this fake server itself.
    let skin_url = format!("{base}/skin.png");
    let app = Router::new()
        .route(
            "/session/minecraft/profile/{uuid}",
            get(move || {
                let textures = json!({ "textures": { "SKIN": { "url": skin_url, "metadata": { "model": "slim" } } } });
                let value = base64::engine::general_purpose::STANDARD.encode(textures.to_string());
                async move { Json(json!({ "id": "x", "name": "Alice", "properties": [{ "name": "textures", "value": value }] })) }
            }),
        )
        .route("/skin.png", get(|| async { ([("content-type", "image/png")], base64::engine::general_purpose::STANDARD.decode(sheet(1, 2)).unwrap()) }))
        .route(
        "/session/minecraft/hasJoined",
        get(move |Query(q): Query<std::collections::HashMap<String, String>>| {
            let joined = joined.clone();
            async move {
                let user = q.get("username").cloned().unwrap_or_default();
                let server = q.get("serverId").cloned().unwrap_or_default();
                if joined.lock().unwrap().contains(&(user.clone(), server)) {
                    let id = if user == "Alice" { "11111111222233334444555555555555" } else { "aaaaaaaabbbbccccddddeeeeeeeeeeee" };
                    Json(json!({ "id": id, "name": user })).into_response()
                } else {
                    StatusCode::NO_CONTENT.into_response()
                }
            }
        }),
    );
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    base
}

struct Env {
    base: String,
    joined: Joined,
    http: reqwest::Client,
    _dir: TempDir,
}

struct TempDir(std::path::PathBuf);
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

async fn start() -> Env {
    let joined: Joined = Default::default();
    let session_url = fake_mojang(joined.clone()).await;
    let dir = std::env::temp_dir().join(format!("slopclient-cosmetics-{}-{}", std::process::id(), rand_id()));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let state = open(Config {
        data_dir: dir.clone(),
        public_url: base.clone(),
        session_url,
        admin_token: Some("admin-secret".into()),
        dev_auth: false,
        web_login_client_id: None,
        trust_proxy: false,
    })
    .unwrap();
    tokio::spawn(async move { axum::serve(listener, router(state)).await.unwrap() });
    Env { base, joined, http: reqwest::Client::new(), _dir: TempDir(dir) }
}

/// Unique per test in this process (tests run in parallel and must not share a database).
fn rand_id() -> u64 {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
}

impl Env {
    /// Full sign-in: challenge, the "client" joins at Mojang, then verify.
    async fn sign_in(&self, name: &str) -> (String, String) {
        let c: Value = self.http.post(format!("{}/v1/auth/challenge", self.base)).send().await.unwrap().json().await.unwrap();
        let server_id = c["serverId"].as_str().unwrap().to_owned();
        // The launcher joins the challenge bound to the server's address.
        self.joined.lock().unwrap().insert((name.into(), bound_server_id(&self.base, &server_id)));
        let s: Value = self
            .http
            .post(format!("{}/v1/auth/verify", self.base))
            .json(&json!({ "username": name, "serverId": server_id }))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        (s["token"].as_str().unwrap().to_owned(), s["uuid"].as_str().unwrap().to_owned())
    }
}

fn sheet(scale: u32, frames: u32) -> String {
    let img = image::RgbaImage::from_pixel(64 * scale, 32 * scale * frames, image::Rgba([139, 92, 246, 255]));
    let mut out = std::io::Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png).unwrap();
    base64::engine::general_purpose::STANDARD.encode(out.into_inner())
}

#[tokio::test]
async fn sign_in_upload_lookup_and_moderation() {
    let env = start().await;
    let (alice, alice_uuid) = env.sign_in("Alice").await;
    let (bob, bob_uuid) = env.sign_in("Bob").await;
    assert_eq!(alice_uuid, "11111111222233334444555555555555");

    // Upload and activate.
    let r = env
        .http
        .put(format!("{}/v1/capes", env.base))
        .bearer_auth(&alice)
        .json(&json!({ "name": "Galaxie", "png": sheet(2, 4), "meta": { "version": 1, "frames": 4, "frameTimeMs": 80, "scale": 2, "elytra": true }, "activate": true }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 200);
    let cape: Value = r.json().await.unwrap();
    let cape_id = cape["id"].as_str().unwrap().to_owned();

    // Lookup for players in sight, then the image itself.
    let found: Value = env
        .http
        .post(format!("{}/v1/cosmetics/lookup", env.base))
        .json(&json!({ "uuids": [alice_uuid, bob_uuid, "not-a-uuid"] }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(found.as_array().unwrap().len(), 2);
    assert_eq!(found[0]["cape"]["name"], "Galaxie");
    assert_eq!(found[0]["cape"]["meta"]["frames"], 4);
    assert!(found[1]["cape"].is_null());
    let img = env.http.get(found[0]["cape"]["url"].as_str().unwrap()).send().await.unwrap();
    assert_eq!(img.status(), 200);
    assert!(img.headers()["cache-control"].to_str().unwrap().contains("immutable"));

    // Bad uploads are rejected with a reason.
    let bad = env
        .http
        .put(format!("{}/v1/capes", env.base))
        .bearer_auth(&alice)
        .json(&json!({ "name": "x", "png": sheet(2, 3), "meta": { "version": 1, "frames": 4, "frameTimeMs": 80, "scale": 2, "elytra": true } }))
        .send()
        .await
        .unwrap();
    assert_eq!(bad.status(), 422);

    // Bob can't wear or delete Alice's cape.
    let steal = env.http.put(format!("{}/v1/capes/active", env.base)).bearer_auth(&bob).json(&json!({ "id": cape_id })).send().await.unwrap();
    assert_eq!(steal.status(), 404);
    let del = env.http.delete(format!("{}/v1/capes/{cape_id}", env.base)).bearer_auth(&bob).send().await.unwrap();
    assert_eq!(del.status(), 404);

    // Report → admin sees it → hide → no longer served in lookups.
    let rep = env.http.post(format!("{}/v1/reports", env.base)).bearer_auth(&bob).json(&json!({ "uuid": alice_uuid, "reason": "test" })).send().await.unwrap();
    assert_eq!(rep.status(), 202);
    let reports: Value = env.http.get(format!("{}/v1/admin/reports", env.base)).bearer_auth("admin-secret").send().await.unwrap().json().await.unwrap();
    assert_eq!(reports[0]["capeId"], cape_id);
    let no_admin = env.http.get(format!("{}/v1/admin/reports", env.base)).bearer_auth(&bob).send().await.unwrap();
    assert_eq!(no_admin.status(), 401);
    let hide = env.http.post(format!("{}/v1/admin/capes/{cape_id}/hide", env.base)).bearer_auth("admin-secret").send().await.unwrap();
    assert_eq!(hide.status(), 204);
    let after: Value = env.http.get(format!("{}/v1/cosmetics/{alice_uuid}", env.base)).send().await.unwrap().json().await.unwrap();
    assert!(after["cape"].is_null());

    // Account deletion removes everything, including the token.
    let gone = env.http.delete(format!("{}/v1/account", env.base)).bearer_auth(&alice).send().await.unwrap();
    assert_eq!(gone.status(), 204);
    let mine = env.http.get(format!("{}/v1/capes/mine", env.base)).bearer_auth(&alice).send().await.unwrap();
    assert_eq!(mine.status(), 401);
}

#[tokio::test]
async fn sign_in_needs_mojang_confirmation() {
    let env = start().await;
    let c: Value = env.http.post(format!("{}/v1/auth/challenge", env.base)).send().await.unwrap().json().await.unwrap();
    // Never joined at Mojang.
    let r = env
        .http
        .post(format!("{}/v1/auth/verify", env.base))
        .json(&json!({ "username": "Mallory", "serverId": c["serverId"] }))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 401);
    // A challenge works only once.
    let again = env
        .http
        .post(format!("{}/v1/auth/verify", env.base))
        .json(&json!({ "username": "Mallory", "serverId": c["serverId"] }))
        .send()
        .await
        .unwrap();
    assert_eq!(again.status(), 401);
    let upload = env.http.put(format!("{}/v1/capes", env.base)).json(&json!({})).send().await.unwrap();
    assert!(upload.status() == 401 || upload.status() == 422);
}

#[tokio::test]
async fn web_page_and_catalogue() {
    let env = start().await;
    let page = env.http.get(format!("{}/", env.base)).send().await.unwrap();
    assert_eq!(page.status(), 200);
    assert!(page.headers()["content-security-policy"].to_str().unwrap().contains("script-src 'self'"));
    assert!(page.text().await.unwrap().contains("app.js"));
    let js = env.http.get(format!("{}/app.js", env.base)).send().await.unwrap();
    assert_eq!(js.headers()["content-type"], "text/javascript; charset=utf-8");

    let c: Value = env.http.get(format!("{}/v1/extras/catalogue", env.base)).send().await.unwrap().json().await.unwrap();
    // The full catalogue, with the models for the 3D preview.
    assert!(c["hats"][0]["boxes"].is_array());
    assert!(c["hats"].as_array().unwrap().iter().any(|h| h["id"] == "krone"));
    assert!(c["pets"].as_array().unwrap().iter().any(|p| p["id"] == "fuchs" && p["tier"] == "walking"));

    // Web sign-in switched off (no client id) and unknown sign-ins.
    let r = env.http.post(format!("{}/v1/web/login", env.base)).send().await.unwrap();
    assert_eq!(r.status(), 404);
    let r = env.http.get(format!("{}/v1/web/login/nope", env.base)).send().await.unwrap();
    assert_eq!(r.status(), 404);
}

#[tokio::test]
async fn own_skin_for_the_preview() {
    let env = start().await;
    let r = env.http.get(format!("{}/v1/skin", env.base)).send().await.unwrap();
    assert_eq!(r.status(), 401);
    let (token, _) = env.sign_in("Alice").await;
    let r = env.http.get(format!("{}/v1/skin", env.base)).bearer_auth(&token).send().await.unwrap();
    assert_eq!(r.status(), 200);
    assert_eq!(r.headers()["x-skin-model"], "slim");
    assert_eq!(r.headers()["content-type"], "image/png");
    assert!(r.bytes().await.unwrap().starts_with(b"\x89PNG"));
}

// ---------- security ----------

fn upload_body(png: &str, scale: u32, frames: u32) -> Value {
    json!({ "name": "x", "png": png, "meta": { "version": 1, "frames": frames, "frameTimeMs": 80, "scale": scale, "elytra": true } })
}

#[tokio::test]
async fn relayed_challenge_does_not_sign_in() {
    let env = start().await;
    // A malicious cosmetics server fetches our challenge and hands it to the victim's
    // launcher, which joins it bound to the malicious server's address.
    let c: Value = env.http.post(format!("{}/v1/auth/challenge", env.base)).send().await.unwrap().json().await.unwrap();
    let challenge = c["serverId"].as_str().unwrap();
    env.joined.lock().unwrap().insert(("Alice".into(), bound_server_id("https://evil.example", challenge)));
    // Old launchers joined the raw challenge; that is not accepted either.
    env.joined.lock().unwrap().insert(("Alice".into(), challenge.to_owned()));
    let r = env.http.post(format!("{}/v1/auth/verify", env.base)).json(&json!({ "username": "Alice", "serverId": challenge })).send().await.unwrap();
    assert_eq!(r.status(), 401);
}

#[tokio::test]
async fn logout_ends_sessions() {
    let env = start().await;
    let (one, _) = env.sign_in("Alice").await;
    let (two, _) = env.sign_in("Alice").await;
    let mine = |t: String| env.http.get(format!("{}/v1/capes/mine", env.base)).bearer_auth(t).send();
    let r = env.http.post(format!("{}/v1/auth/logout", env.base)).bearer_auth(&one).send().await.unwrap();
    assert_eq!(r.status(), 204);
    assert_eq!(mine(one.clone()).await.unwrap().status(), 401);
    assert_eq!(mine(two.clone()).await.unwrap().status(), 200);
    let (three, _) = env.sign_in("Alice").await;
    let r = env.http.post(format!("{}/v1/auth/logout", env.base)).bearer_auth(&three).json(&json!({ "everywhere": true })).send().await.unwrap();
    assert_eq!(r.status(), 204);
    assert_eq!(mine(two).await.unwrap().status(), 401);
}

fn crc32(data: &[u8]) -> u32 {
    let mut crc = !0u32;
    for &b in data {
        crc ^= u32::from(b);
        for _ in 0..8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ 0xEDB8_8320 } else { crc >> 1 };
        }
    }
    !crc
}

/// A valid 64 × 32 PNG whose header claims `w` × `h`.
fn lying_png(w: u32, h: u32) -> String {
    let mut png = base64::engine::general_purpose::STANDARD.decode(sheet(1, 1)).unwrap();
    // IHDR data starts after the signature (8), length (4) and type (4).
    png[16..20].copy_from_slice(&w.to_be_bytes());
    png[20..24].copy_from_slice(&h.to_be_bytes());
    let crc = crc32(&png[12..29]);
    png[29..33].copy_from_slice(&crc.to_be_bytes());
    base64::engine::general_purpose::STANDARD.encode(png)
}

#[tokio::test]
async fn huge_png_is_rejected_before_decoding() {
    let env = start().await;
    let (alice, _) = env.sign_in("Alice").await;
    let started = std::time::Instant::now();
    let r = env.http.put(format!("{}/v1/capes", env.base)).bearer_auth(&alice).json(&upload_body(&lying_png(30_000, 30_000), 1, 1)).send().await.unwrap();
    assert_eq!(r.status(), 422);
    assert!(started.elapsed() < std::time::Duration::from_secs(5));
}

#[tokio::test]
async fn bodies_need_a_sign_in_and_stay_small() {
    let env = start().await;
    // 8 MB without a token: refused before the body is read.
    let big = "A".repeat(8 * 1024 * 1024);
    // The server may answer and close before the client finished sending: both are a refusal.
    let refused = |r: reqwest::Result<reqwest::Response>, status: u16| match r {
        Ok(r) => assert_eq!(r.status(), status),
        Err(e) => assert!(e.is_request(), "{e}"),
    };
    refused(env.http.put(format!("{}/v1/capes", env.base)).json(&upload_body(&big, 1, 1)).send().await, 401);
    let (alice, _) = env.sign_in("Alice").await;
    refused(env.http.post(format!("{}/v1/reports", env.base)).bearer_auth(&alice).json(&json!({ "uuid": "x", "reason": big })).send().await, 413);
}

#[tokio::test]
async fn hidden_images_stay_gone() {
    let env = start().await;
    let (alice, alice_uuid) = env.sign_in("Alice").await;
    let (bob, _) = env.sign_in("Bob").await;
    let up = |t: &str| env.http.put(format!("{}/v1/capes", env.base)).bearer_auth(t.to_owned()).json(&json!({ "name": "x", "png": sheet(1, 1), "meta": { "version": 1, "frames": 1, "frameTimeMs": 0, "scale": 1, "elytra": true }, "activate": true })).send();
    let cape: Value = up(&alice).await.unwrap().json().await.unwrap();
    // Reporting twice counts once.
    for _ in 0..2 {
        env.http.post(format!("{}/v1/reports", env.base)).bearer_auth(&bob).json(&json!({ "uuid": alice_uuid, "reason": "spam" })).send().await.unwrap();
    }
    let reports: Value = env.http.get(format!("{}/v1/admin/reports", env.base)).bearer_auth("admin-secret").send().await.unwrap().json().await.unwrap();
    assert_eq!(reports[0]["reports"], 1);
    let id = cape["id"].as_str().unwrap();
    env.http.post(format!("{}/v1/admin/capes/{id}/hide", env.base)).bearer_auth("admin-secret").send().await.unwrap();
    // The image is no longer served, and uploading it again is refused.
    let img = env.http.get(format!("{}/files/{}.png", env.base, cape["hash"].as_str().unwrap())).send().await.unwrap();
    assert_eq!(img.status(), 404);
    assert_eq!(up(&alice).await.unwrap().status(), 422);
}

#[tokio::test]
async fn sign_in_is_rate_limited() {
    let env = start().await;
    let mut limited = false;
    for _ in 0..15 {
        let r = env.http.post(format!("{}/v1/auth/challenge", env.base)).send().await.unwrap();
        limited |= r.status() == 429;
    }
    assert!(limited);
}

#[test]
fn dev_auth_only_on_loopback() {
    assert!(slopclient_cosmetics::is_loopback_bind("127.0.0.1:8787"));
    assert!(slopclient_cosmetics::is_loopback_bind("[::1]:8787"));
    assert!(slopclient_cosmetics::is_loopback_bind("localhost:8787"));
    assert!(!slopclient_cosmetics::is_loopback_bind("0.0.0.0:8787"));
    assert!(!slopclient_cosmetics::is_loopback_bind("192.168.1.5:8787"));
}
