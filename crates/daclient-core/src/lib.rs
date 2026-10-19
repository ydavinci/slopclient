//! DaClient backend: everything the launcher does, independent of any UI.
//!
//! The Tauri app and the test CLI are thin layers on top of this crate.

pub mod auth;
pub mod client_mod;
pub mod config;
pub mod content;
pub mod cosmetics;
pub mod cosmetics_online;
pub mod crash;
pub mod download;
pub mod error;
pub mod foreign;
pub mod http;
pub mod icon;
pub mod install;
pub mod instance;
pub mod java;
pub mod launch;
pub mod mclogs;
pub mod meta;
pub mod paths;
pub mod progress;
pub mod rules;
pub mod screenshots;
pub mod worlds;

pub use error::{Error, Result};
pub use paths::Paths;
pub use progress::{Progress, ProgressFn};
