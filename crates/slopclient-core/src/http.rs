use std::{sync::OnceLock, time::Duration};

use serde::de::DeserializeOwned;

use crate::Result;

pub const USER_AGENT: &str = concat!("SlopClient/", env!("CARGO_PKG_VERSION"));

/// Shared HTTP client (connection pool is reused across all requests).
pub fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(60))
            .build()
            .expect("http client")
    })
}

pub async fn get_json<T: DeserializeOwned>(url: &str) -> Result<T> {
    Ok(client().get(url).send().await?.error_for_status()?.json().await?)
}

pub async fn get_text(url: &str) -> Result<String> {
    Ok(client().get(url).send().await?.error_for_status()?.text().await?)
}
