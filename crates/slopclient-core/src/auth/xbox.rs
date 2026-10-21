//! Xbox Live, XSTS and Minecraft services authentication.

use serde::Deserialize;
use serde_json::json;

use crate::{Error, Result, http};

const XBL_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XboxToken {
    token: String,
    display_claims: DisplayClaims,
}

#[derive(Deserialize)]
struct DisplayClaims {
    xui: Vec<XuiClaim>,
}

#[derive(Deserialize)]
struct XuiClaim {
    uhs: String,
    #[serde(default)]
    xid: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XstsError {
    #[serde(default)]
    x_err: u64,
}

#[derive(Deserialize)]
struct McLogin {
    access_token: String,
    expires_in: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Profile {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub skins: Vec<Skin>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Skin {
    pub state: String,
    pub url: String,
}

impl Profile {
    pub fn active_skin(&self) -> Option<&Skin> {
        self.skins.iter().find(|s| s.state == "ACTIVE")
    }
}

/// Result of a complete Xbox → Minecraft login.
#[derive(Debug, Clone)]
pub struct MinecraftSession {
    pub access_token: String,
    pub expires_in: u64,
    pub xuid: Option<String>,
    pub profile: Profile,
}

pub async fn login(msa_access_token: &str) -> Result<MinecraftSession> {
    let xbl: XboxToken = http::client()
        .post(XBL_URL)
        .json(&json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={msa_access_token}"),
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        }))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let response = http::client()
        .post(XSTS_URL)
        .json(&json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl.token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        }))
        .send()
        .await?;
    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        let err: XstsError = response.json().await.unwrap_or(XstsError { x_err: 0 });
        return Err(Error::Auth(xsts_message(err.x_err)));
    }
    let xsts: XboxToken = response.error_for_status()?.json().await?;
    let claim = xsts
        .display_claims
        .xui
        .into_iter()
        .next()
        .ok_or_else(|| Error::Auth("XSTS response without user claims".into()))?;

    let response = http::client()
        .post(MC_LOGIN_URL)
        .json(&json!({ "identityToken": format!("XBL3.0 x={};{}", claim.uhs, xsts.token) }))
        .send()
        .await?;
    if response.status() == reqwest::StatusCode::FORBIDDEN {
        return Err(Error::Auth(
            "Minecraft services rejected the app (403). The Azure client id has not been approved by Mojang yet.".into(),
        ));
    }
    let mc: McLogin = response.error_for_status()?.json().await?;

    let response = http::client()
        .get(MC_PROFILE_URL)
        .bearer_auth(&mc.access_token)
        .send()
        .await?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(Error::Auth(
            "this Microsoft account does not own Minecraft: Java Edition (or has no profile yet)".into(),
        ));
    }
    let profile: Profile = response.error_for_status()?.json().await?;

    Ok(MinecraftSession {
        access_token: mc.access_token,
        expires_in: mc.expires_in,
        xuid: claim.xid,
        profile,
    })
}

fn xsts_message(code: u64) -> String {
    match code {
        2148916233 => "this Microsoft account has no Xbox profile; sign in once at xbox.com".into(),
        2148916235 => "Xbox Live is not available in your country".into(),
        2148916236 | 2148916237 => "this account needs adult verification on xbox.com".into(),
        2148916238 => "child account: it must be added to a Microsoft family by an adult".into(),
        0 => "Xbox authorization failed".into(),
        other => format!("Xbox authorization failed (XErr {other})"),
    }
}
