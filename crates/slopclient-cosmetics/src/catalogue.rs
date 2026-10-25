//! The hat and pet catalogue (cosmetics/extras.json) and the pet textures. The server is
//! their source: the mod, the launcher and the web page fetch them from here (and keep a
//! copy), so new hats and pets only need a server update.

use std::sync::LazyLock;

use axum::{
    Router,
    extract::Path,
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::get,
};
use sha2::{Digest, Sha256};

use crate::{Shared, limited, limits};

include!(concat!(env!("OUT_DIR"), "/pet_textures.rs"));

/// Changes whenever the catalogue or any texture changes; clients ask with If-None-Match.
static ETAG: LazyLock<String> = LazyLock::new(|| {
    let mut h = Sha256::new();
    h.update(slopclient_core::extras::CATALOGUE_JSON.as_bytes());
    for (name, png) in PET_TEXTURES {
        h.update(name.as_bytes());
        h.update(png);
    }
    format!("\"{}\"", &hex::encode(h.finalize())[..20])
});

pub(crate) fn routes(s: &Shared) -> Router<Shared> {
    limited(
        s,
        &limits::READ,
        Router::new().route("/v1/extras/catalogue", get(catalogue)).route("/v1/extras/textures/{file}", get(texture)),
    )
}

/// The catalogue with the models; `304` when the client's copy is current.
async fn catalogue(headers: HeaderMap) -> Response {
    let etag = ETAG.as_str();
    // Clients keep a copy and revalidate each time, so an update shows at once.
    let cache = [(header::ETAG, etag), (header::CACHE_CONTROL, "no-cache")];
    if headers.get(header::IF_NONE_MATCH).and_then(|v| v.to_str().ok()) == Some(etag) {
        return (StatusCode::NOT_MODIFIED, cache).into_response();
    }
    (cache, [(header::CONTENT_TYPE, "application/json")], slopclient_core::extras::CATALOGUE_JSON).into_response()
}

/// A pet texture named in the catalogue (`textures/<file>.png`).
async fn texture(Path(file): Path<String>) -> Response {
    match PET_TEXTURES.iter().find(|(name, _)| *name == file) {
        Some((_, png)) => ([(header::CONTENT_TYPE, "image/png"), (header::CACHE_CONTROL, "public, max-age=3600")], *png).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}
