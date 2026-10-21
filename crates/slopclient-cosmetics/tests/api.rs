//! End-to-end tests against a running server and a fake Mojang session server.

use std::{
    collections::HashSet,
    sync::{Arc, Mutex},
};

use axum::{Json, Router, extract::Query, http::StatusCode, response::IntoResponse, routing::get};
use base64::Engine;
use slopclient_cosmetics::{Config, open, router};
use serde_json::{Value, json};

type Joined = Arc<Mutex<HashSet<(String, String)>>>;

/// Fake `sessionserver.mojang.com`: knows which (username, serverId) pairs "joined".
async fn fake_mojang(joined: Joined) -> String {
    let app = Router::new().route(
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
    serve(app).await
}

async fn serve(app: Router) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}")
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
    })
    .unwrap();
    tokio::spawn(async move { axum::serve(listener, router(state)).await.unwrap() });
    Env { base, joined, http: reqwest::Client::new(), _dir: TempDir(dir) }
}

fn rand_id() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().subsec_nanos() as u64
}

impl Env {
    /// Full sign-in: challenge, the "client" joins at Mojang, then verify.
    async fn sign_in(&self, name: &str) -> (String, String) {
        let c: Value = self.http.post(format!("{}/v1/auth/challenge", self.base)).send().await.unwrap().json().await.unwrap();
        let server_id = c["serverId"].as_str().unwrap().to_owned();
        self.joined.lock().unwrap().insert((name.into(), server_id.clone()));
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
