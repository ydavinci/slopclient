//! Rate limits: token buckets per client address (or per account), kept in memory.
//!
//! Behind a reverse proxy every request comes from the proxy, so with
//! `SLOPCLIENT_COSMETICS_TRUST_PROXY=1` (or a proxy on the same machine) the address is
//! taken from the last `X-Forwarded-For` entry, the one the proxy added.

use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr},
    sync::Mutex,
    time::Instant,
};

use axum::{
    extract::{ConnectInfo, Request, State},
    http::StatusCode,
    middleware::Next,
    response::{IntoResponse, Response},
};

use crate::{ApiError, Shared, lock};

/// A bucket of `burst` requests that refills by `per_minute`.
pub(crate) struct Rule {
    name: &'static str,
    burst: f64,
    per_minute: f64,
}

/// Challenges and verifies (each verify asks Mojang).
pub(crate) const SIGN_IN: Rule = Rule { name: "sign-in", burst: 10.0, per_minute: 10.0 };
/// Starting a web sign-in (asks Microsoft, which then gets polled for minutes).
pub(crate) const WEB_LOGIN: Rule = Rule { name: "web-login", burst: 5.0, per_minute: 2.0 };
/// The page polls its sign-in every few seconds.
pub(crate) const POLL: Rule = Rule { name: "poll", burst: 30.0, per_minute: 60.0 };
/// Changes by signed-in players.
pub(crate) const WRITE: Rule = Rule { name: "write", burst: 30.0, per_minute: 60.0 };
/// Lookups and images, which game clients fetch for everyone in view.
pub(crate) const READ: Rule = Rule { name: "read", burst: 300.0, per_minute: 600.0 };
/// Upload attempts per account, failed ones included.
pub(crate) const UPLOAD_ATTEMPTS: Rule = Rule { name: "upload", burst: 10.0, per_minute: 1.0 };
/// Skin fetches per account (each asks Mojang twice when not cached).
pub(crate) const SKIN: Rule = Rule { name: "skin", burst: 10.0, per_minute: 10.0 };

/// Buckets kept before the full ones are dropped.
const MAX_BUCKETS: usize = 100_000;

#[derive(Default)]
pub(crate) struct Limiter {
    buckets: Mutex<HashMap<(&'static str, String), (f64, Instant)>>,
}

impl Limiter {
    /// Takes one request from `key`'s bucket for `rule`; false when it is empty.
    pub(crate) fn allow(&self, rule: &Rule, key: &str) -> bool {
        let now = Instant::now();
        let mut buckets = lock(&self.buckets);
        if buckets.len() >= MAX_BUCKETS {
            // Buckets that refilled completely carry no information.
            buckets.retain(|_, (_, at)| now.duration_since(*at).as_secs_f64() < 600.0);
            if buckets.len() >= MAX_BUCKETS {
                buckets.clear();
            }
        }
        let (tokens, at) = buckets.entry((rule.name, key.to_owned())).or_insert((rule.burst, now));
        *tokens = (*tokens + now.duration_since(*at).as_secs_f64() * rule.per_minute / 60.0).min(rule.burst);
        *at = now;
        if *tokens >= 1.0 {
            *tokens -= 1.0;
            true
        } else {
            false
        }
    }
}

/// The client's address: the connection's peer, or the proxy's `X-Forwarded-For` entry.
pub(crate) fn client(s: &Shared, req: &Request) -> String {
    let peer = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|c| c.0.ip());
    let behind_proxy = s.config.trust_proxy || peer.is_some_and(|ip| ip.is_loopback());
    let forwarded = behind_proxy
        .then(|| req.headers().get("x-forwarded-for")?.to_str().ok()?.rsplit(',').next()?.trim().parse::<IpAddr>().ok())
        .flatten();
    match forwarded.or(peer) {
        // IPv6 users usually own a whole /64: limit it as one client.
        Some(IpAddr::V6(v6)) => {
            let s = v6.segments();
            format!("{:x}:{:x}:{:x}:{:x}::/64", s[0], s[1], s[2], s[3])
        }
        Some(ip) => ip.to_string(),
        None => "unknown".into(),
    }
}

/// Middleware: rejects with 429 once the client's bucket for `rule` is empty.
pub(crate) async fn limit(State((s, rule)): State<(Shared, &'static Rule)>, req: Request, next: Next) -> Response {
    if !s.limiter.allow(rule, &client(&s, &req)) {
        return ApiError::new(StatusCode::TOO_MANY_REQUESTS, "too many requests, slow down").into_response();
    }
    next.run(req).await
}
