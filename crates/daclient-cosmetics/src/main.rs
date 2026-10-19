//! `daclient-cosmetics`: run the cosmetics backend.
//!
//! Configuration via environment variables (see `Config::from_env`):
//! `DACLIENT_COSMETICS_BIND` (default 127.0.0.1:8787), `DACLIENT_COSMETICS_DATA`,
//! `DACLIENT_COSMETICS_PUBLIC_URL`, `DACLIENT_COSMETICS_ADMIN_TOKEN`.

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let config = daclient_cosmetics::Config::from_env();
    let bind = std::env::var("DACLIENT_COSMETICS_BIND").unwrap_or_else(|_| "127.0.0.1:8787".into());
    println!("data in {}, images served from {}", config.data_dir.display(), config.public_url);
    let state = daclient_cosmetics::open(config)?;
    let listener = tokio::net::TcpListener::bind(&bind).await?;
    println!("listening on {bind}");
    axum::serve(listener, daclient_cosmetics::router(state)).await
}
