//! `slopclient-cosmetics`: run the cosmetics backend.
//!
//! Configuration via environment variables (see `Config::from_env`):
//! `SLOPCLIENT_COSMETICS_BIND` (default 127.0.0.1:8787), `SLOPCLIENT_COSMETICS_DATA`,
//! `SLOPCLIENT_COSMETICS_PUBLIC_URL`, `SLOPCLIENT_COSMETICS_ADMIN_TOKEN`.

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let config = slopclient_cosmetics::Config::from_env();
    let bind = std::env::var("SLOPCLIENT_COSMETICS_BIND").or_else(|_| std::env::var("DACLIENT_COSMETICS_BIND")).unwrap_or_else(|_| "127.0.0.1:8787".into());
    println!("data in {}, images served from {}", config.data_dir.display(), config.public_url);
    let state = slopclient_cosmetics::open(config)?;
    let listener = tokio::net::TcpListener::bind(&bind).await?;
    println!("listening on {bind}");
    axum::serve(listener, slopclient_cosmetics::router(state)).await
}
