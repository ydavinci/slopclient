//! Evaluation of Mojang `rules` blocks (OS, architecture and launcher features).

use std::collections::HashMap;

use crate::meta::mojang::{Rule, RuleAction};

/// Launcher features referenced by argument rules (`has_custom_resolution`, …).
pub type Features = HashMap<String, bool>;

pub fn os_name() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

/// Architecture as Mojang spells it (`x86` means 32-bit).
pub fn arch_name() -> &'static str {
    match std::env::consts::ARCH {
        "x86_64" => "x86_64",
        "aarch64" => "arm64",
        "x86" => "x86",
        other => other,
    }
}

/// Value for `${arch}` in legacy native classifiers.
pub fn arch_bits() -> &'static str {
    if cfg!(target_pointer_width = "64") { "64" } else { "32" }
}

/// The modern LWJGL natives classifier for this machine.
pub fn native_classifier() -> &'static str {
    match (os_name(), arch_name()) {
        ("windows", "arm64") => "natives-windows-arm64",
        ("windows", "x86") => "natives-windows-x86",
        ("windows", _) => "natives-windows",
        ("osx", "arm64") => "natives-macos-arm64",
        ("osx", _) => "natives-macos",
        ("linux", "arm64") => "natives-linux-arm64",
        _ => "natives-linux",
    }
}

/// True for classifiers like `natives-linux` or `natives-windows-arm64`
/// that belong to a different platform than this one.
pub fn is_foreign_native(classifier: &str) -> bool {
    classifier.starts_with("natives-") && classifier != native_classifier()
}

/// No rules means allowed; otherwise the last matching rule decides.
pub fn allowed(rules: &[Rule], features: &Features) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        if matches(rule, features) {
            allowed = rule.action == RuleAction::Allow;
        }
    }
    allowed
}

fn matches(rule: &Rule, features: &Features) -> bool {
    if let Some(os) = &rule.os {
        if os.name.as_deref().is_some_and(|n| n != os_name()) {
            return false;
        }
        if os.arch.as_deref().is_some_and(|a| a != arch_name()) {
            return false;
        }
    }
    if let Some(required) = &rule.features {
        for (name, value) in required {
            if features.get(name).copied().unwrap_or(false) != *value {
                return false;
            }
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::meta::mojang::OsRule;

    fn rule(action: RuleAction, os: Option<&str>) -> Rule {
        Rule {
            action,
            os: os.map(|n| OsRule {
                name: Some(n.into()),
                version: None,
                arch: None,
            }),
            features: None,
        }
    }

    #[test]
    fn last_matching_rule_wins() {
        let f = Features::new();
        assert!(allowed(&[], &f));
        assert!(allowed(&[rule(RuleAction::Allow, None)], &f));
        let rules = [rule(RuleAction::Allow, None), rule(RuleAction::Disallow, Some(os_name()))];
        assert!(!allowed(&rules, &f));
        assert!(!allowed(&[rule(RuleAction::Allow, Some("nonexistent-os"))], &f));
    }
}
