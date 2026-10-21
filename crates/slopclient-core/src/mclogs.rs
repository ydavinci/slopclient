//! Shares a game log on mclo.gs, e.g. to ask for help with a crash.
//!
//! Before upload, access tokens and the Windows user name in paths are removed.
//! mclo.gs additionally hides IP addresses itself.

use serde::Deserialize;

use crate::{Error, Result};

const API: &str = "https://api.mclo.gs/1/log";
/// mclo.gs keeps at most 25 000 lines and 10 MiB.
const MAX_LINES: usize = 25_000;
const MAX_BYTES: usize = 10 * 1024 * 1024;

#[derive(Deserialize)]
struct Answer {
    success: bool,
    url: Option<String>,
    error: Option<String>,
}

/// Uploads the text and returns the public link.
pub async fn upload(text: &str) -> Result<String> {
    let mut content = redact(text);
    let lines: Vec<&str> = content.lines().collect();
    if lines.len() > MAX_LINES {
        content = lines[lines.len() - MAX_LINES..].join("\n");
    }
    if content.len() > MAX_BYTES {
        let mut cut = content.len() - MAX_BYTES;
        while !content.is_char_boundary(cut) {
            cut += 1;
        }
        content = content[cut..].to_owned();
    }
    if content.trim().is_empty() {
        return Err(Error::Other("Das Log ist leer".into()));
    }
    let answer: Answer = crate::http::client()
        .post(API)
        .form(&[("content", content.as_str())])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    match (answer.success, answer.url) {
        (true, Some(url)) => Ok(url),
        _ => Err(Error::Other(format!(
            "mclo.gs hat das Log abgelehnt: {}",
            answer.error.unwrap_or_else(|| "unbekannter Fehler".into())
        ))),
    }
}

/// Removes what identifies the player beyond their in-game name.
pub fn redact(text: &str) -> String {
    let user = std::env::var("USERNAME").ok().filter(|u| u.len() > 1);
    text.lines()
        .map(|line| {
            let mut line = redact_tokens(line);
            if let Some(user) = &user {
                for sep in ['\\', '/'] {
                    let needle = format!("Users{sep}{user}{sep}");
                    line = replace_ci(&line, &needle, &format!("Users{sep}<user>{sep}"));
                }
            }
            line
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn redact_tokens(line: &str) -> String {
    let mut words: Vec<String> = Vec::new();
    let mut after_flag = false;
    for word in line.split(' ') {
        let jwt = word.starts_with("eyJ") && word.matches('.').count() >= 2 && word.len() > 40;
        if after_flag || jwt {
            words.push("<token>".into());
        } else {
            words.push(word.to_owned());
        }
        after_flag = word == "--accessToken";
    }
    words.join(" ")
}

fn replace_ci(haystack: &str, needle: &str, with: &str) -> String {
    let lower = haystack.to_lowercase();
    let needle_lower = needle.to_lowercase();
    // Lowercasing can change byte lengths for non-ASCII text; only then fall back to an exact match.
    if lower.len() != haystack.len() {
        return haystack.replace(needle, with);
    }
    let mut out = String::with_capacity(haystack.len());
    let mut last = 0;
    for (i, _) in lower.match_indices(&needle_lower) {
        out.push_str(&haystack[last..i]);
        out.push_str(with);
        last = i + needle.len();
    }
    out.push_str(&haystack[last..]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_tokens() {
        let jwt = format!("eyJ{}.{}.{}", "a".repeat(30), "b".repeat(20), "c".repeat(20));
        let line = format!("args --username Steve --accessToken secret123 --other {jwt}");
        assert_eq!(redact_tokens(&line), "args --username Steve --accessToken <token> --other <token>");
    }

    #[test]
    fn hides_user_folder() {
        assert_eq!(replace_ci(r"C:\users\David\x", r"Users\david\", r"Users\<user>\"), r"C:\Users\<user>\x");
    }
}
