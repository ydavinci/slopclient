//! `slopclient-cosmetics`: run the cosmetics backend.
//!
//! Configuration via environment variables (see `Config::from_env` and deploy/cosmetics/README.md):
//! `SLOPCLIENT_COSMETICS_BIND` (default 127.0.0.1:8787), `SLOPCLIENT_COSMETICS_DATA`,
//! `SLOPCLIENT_COSMETICS_PUBLIC_URL`, `SLOPCLIENT_COSMETICS_ADMIN_TOKEN`, `SLOPCLIENT_COSMETICS_TRUST_PROXY`.

use std::net::SocketAddr;

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let config = slopclient_cosmetics::Config::from_env();
    let bind = std::env::var("SLOPCLIENT_COSMETICS_BIND").or_else(|_| std::env::var("DACLIENT_COSMETICS_BIND")).unwrap_or_else(|_| "127.0.0.1:8787".into());
    if let Err(e) = slopclient_cosmetics::check_public_url(&config.public_url) {
        eprintln!("SLOPCLIENT_COSMETICS_PUBLIC_URL: {e}");
        std::process::exit(2);
    }
    // Without the Mojang check anyone can sign in as anyone: only for a local test server.
    if config.dev_auth && !slopclient_cosmetics::is_loopback_bind(&bind) {
        eprintln!("SLOPCLIENT_COSMETICS_DEV_AUTH=1 only works with a loopback SLOPCLIENT_COSMETICS_BIND (127.0.0.1), not {bind}");
        std::process::exit(2);
    }
    println!("data in {}, images served from {}", config.data_dir.display(), config.public_url);
    let state = slopclient_cosmetics::open(config)?;
    let listener = tokio::net::TcpListener::bind(&bind).await?;
    println!("listening on {bind}");
    // The peer address feeds the per-client rate limits.
    axum::serve(listener, slopclient_cosmetics::router(state).into_make_service_with_connect_info::<SocketAddr>()).await
}
