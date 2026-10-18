//! Microsoft account login: MSA → Xbox Live → XSTS → Minecraft services.

pub mod accounts;
pub mod msa;
mod secrets;
pub mod xbox;

pub use accounts::{Account, AccountStore};
pub use msa::DeviceCode;
