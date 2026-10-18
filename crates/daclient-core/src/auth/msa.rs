//! Microsoft identity platform (consumer accounts): device code flow and token refresh.
//!
//! Requires an Azure app registration with "Allow public client flows" enabled
//! that Mojang has approved for the Minecraft services API.

use std::time::Duration;

use serde::Deserialize;

use crate::{Error, Result, http};

const DEVICE_CODE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const SCOPE: &str = "XboxLive.signin offline_access";

#[derive(Debug, Clone, Deserialize)]
pub struct DeviceCode {
    pub user_code: String,
    pub device_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
    pub message: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MsaToken {
    pub access_token: String,
    #[serde(default)]
    pub refresh_token: Option<String>,
    pub expires_in: u64,
}

#[derive(Deserialize)]
struct TokenError {
    error: String,
    #[serde(default)]
    error_description: String,
}

pub async fn start_device_code(client_id: &str) -> Result<DeviceCode> {
    let response = http::client()
        .post(DEVICE_CODE_URL)
        .form(&[("client_id", client_id), ("scope", SCOPE)])
        .send()
        .await?;
    if !response.status().is_success() {
        let err: TokenError = response.json().await?;
        return Err(Error::Auth(format!("{}: {}", err.error, err.error_description)));
    }
    Ok(response.json().await?)
}

/// Polls until the user has entered the code in the browser.
pub async fn poll_device_code(client_id: &str, code: &DeviceCode) -> Result<MsaToken> {
    let mut interval = code.interval.max(1);
    let deadline = std::time::Instant::now() + Duration::from_secs(code.expires_in);
    loop {
        tokio::time::sleep(Duration::from_secs(interval)).await;
        if std::time::Instant::now() > deadline {
            return Err(Error::Auth("the login code expired".into()));
        }
        let response = http::client()
            .post(TOKEN_URL)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", client_id),
                ("device_code", code.device_code.as_str()),
            ])
            .send()
            .await?;
        if response.status().is_success() {
            return Ok(response.json().await?);
        }
        let err: TokenError = response.json().await?;
        match err.error.as_str() {
            "authorization_pending" => {}
            "slow_down" => interval += 5,
            "authorization_declined" => return Err(Error::Auth("login was declined".into())),
            "expired_token" => return Err(Error::Auth("the login code expired".into())),
            _ => return Err(Error::Auth(format!("{}: {}", err.error, err.error_description))),
        }
    }
}

pub async fn refresh(client_id: &str, refresh_token: &str) -> Result<MsaToken> {
    let response = http::client()
        .post(TOKEN_URL)
        .form(&[
            ("grant_type", "refresh_token"),
            ("client_id", client_id),
            ("refresh_token", refresh_token),
            ("scope", SCOPE),
        ])
        .send()
        .await?;
    if !response.status().is_success() {
        let err: TokenError = response.json().await?;
        return Err(Error::Auth(format!(
            "session expired, please log in again ({})",
            err.error
        )));
    }
    Ok(response.json().await?)
}
