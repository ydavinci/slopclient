//! Hats and pets. The models live in `cosmetics/extras.json` at the repository root;
//! the client mod renders them, the launcher previews them, and the cosmetics
//! server only stores which ones a player wears (checked against this list).

use serde::{Deserialize, Serialize};

use crate::{Error, Paths, Result, config::Settings};

/// The catalogue as shipped, for the launcher's preview.
pub const CATALOGUE_JSON: &str = include_str!("../../../cosmetics/extras.json");

#[derive(Debug, Deserialize)]
struct Entry {
    id: String,
}

#[derive(Debug, Deserialize)]
struct Catalogue {
    hats: Vec<Entry>,
    pets: Vec<Entry>,
}

fn catalogue() -> Catalogue {
    serde_json::from_str(CATALOGUE_JSON).expect("cosmetics/extras.json is valid")
}

pub fn is_hat(id: &str) -> bool {
    catalogue().hats.iter().any(|h| h.id == id)
}

pub fn is_pet(id: &str) -> bool {
    catalogue().pets.iter().any(|p| p.id == id)
}

/// What the player wears; none means nothing.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Choice {
    pub hat: Option<String>,
    pub pet: Option<String>,
}

impl Choice {
    /// Drops ids that are not in the catalogue (e.g. from a newer client).
    pub fn checked(self) -> Self {
        Self {
            hat: self.hat.filter(|h| is_hat(h)),
            pet: self.pet.filter(|p| is_pet(p)),
        }
    }
}

pub fn current(paths: &Paths) -> Result<Choice> {
    let s = Settings::load(paths)?;
    Ok(Choice { hat: s.hat, pet: s.pet })
}

pub fn set(paths: &Paths, choice: &Choice) -> Result<()> {
    if choice.hat.as_deref().is_some_and(|h| !is_hat(h)) || choice.pet.as_deref().is_some_and(|p| !is_pet(p)) {
        return Err(Error::Other("unbekannter Hut oder Begleiter".into()));
    }
    let mut s = Settings::load(paths)?;
    s.hat = choice.hat.clone();
    s.pet = choice.pet.clone();
    s.save(paths)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalogue_parses_and_has_unique_ids() {
        let c = catalogue();
        assert!(!c.hats.is_empty() && !c.pets.is_empty());
        let mut ids: Vec<&str> = c.hats.iter().chain(&c.pets).map(|e| e.id.as_str()).collect();
        let n = ids.len();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), n);
        assert!(is_hat("zylinder") && is_pet("slime") && !is_hat("slime"));
    }
}
