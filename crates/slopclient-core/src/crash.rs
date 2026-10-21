//! Explains why a game crashed: reads the newest crash report (or JVM `hs_err`
//! file), `logs/latest.log` and the tail of the game output, matches known
//! causes and names the responsible mod where possible.

use std::{
    path::{Path, PathBuf},
    time::{Duration, SystemTime},
};

use serde::Serialize;

use crate::{Paths, Result, content::ContentManager, instance::Instance};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CrashReport {
    /// Short cause, e.g. "Zu wenig Arbeitsspeicher".
    pub title: String,
    /// One or two sentences for the user.
    pub summary: String,
    /// Relevant lines from the logs (exception, mod list).
    pub details: Vec<String>,
    pub suggestions: Vec<String>,
    /// The mod that most likely caused it, when the logs name one.
    pub culprit: Option<Culprit>,
    /// Crash report or `hs_err` file to open.
    pub report_file: Option<String>,
    pub exit_code: Option<i32>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Culprit {
    pub mod_id: String,
    /// Installed file of that mod, so the UI can offer to disable it.
    pub file_name: Option<String>,
    pub name: Option<String>,
}

/// Inputs for [`diagnose`], separate from the file system so it can be tested.
#[derive(Debug, Default)]
pub struct CrashInput {
    pub exit_code: Option<i32>,
    pub crash_report: Option<String>,
    pub jvm_error: Option<String>,
    pub log: String,
    pub max_memory_mb: u32,
}

/// Analyzes a finished game. Returns `None` for a normal exit.
pub async fn analyze(
    paths: &Paths,
    instance: &Instance,
    started_at: SystemTime,
    exit_code: Option<i32>,
    output_tail: &[String],
) -> Result<Option<CrashReport>> {
    if exit_code == Some(0) {
        return Ok(None);
    }
    let game_dir = instance.game_dir(paths);
    // Files written during this session only (small slack for clock rounding).
    let since = started_at - Duration::from_secs(2);
    let report_path = newest(&game_dir.join("crash-reports"), since, |n| n.ends_with(".txt"));
    let jvm_path = newest(&game_dir, since, |n| n.starts_with("hs_err_pid") && n.ends_with(".log"));

    let mut log = output_tail.join("\n");
    if let Some(latest) = newest(&game_dir.join("logs"), since, |n| n == "latest.log") {
        let text = std::fs::read_to_string(latest).unwrap_or_default();
        // The end of the log is where the failure is.
        let tail: Vec<&str> = text.lines().rev().take(400).collect();
        log.push('\n');
        log.push_str(&tail.into_iter().rev().collect::<Vec<_>>().join("\n"));
    }

    let input = CrashInput {
        exit_code,
        crash_report: report_path.as_ref().and_then(|p| std::fs::read_to_string(p).ok()),
        jvm_error: jvm_path.as_ref().and_then(|p| std::fs::read_to_string(p).ok()),
        log,
        max_memory_mb: instance.java.max_memory_mb,
    };
    let Some(mut report) = diagnose(&input) else {
        return Ok(None);
    };
    report.report_file = report_path
        .or(jvm_path)
        .map(|p| p.to_string_lossy().into_owned());

    if let Some(culprit) = &mut report.culprit {
        let entries = ContentManager::new(paths, instance.clone(), 1).list().await.unwrap_or_default();
        let id = culprit.mod_id.to_lowercase().replace('_', "-");
        let hit = entries.iter().find(|e| {
            let file = e.file_name.to_lowercase().replace('_', "-");
            let name = e.name.to_lowercase().replace(' ', "-");
            file.starts_with(&id) || name == id
        });
        if let Some(e) = hit {
            culprit.file_name = Some(e.file_name.clone());
            culprit.name = Some(e.name.clone());
        }
    }
    Ok(Some(report))
}

fn newest(dir: &Path, since: SystemTime, matches: impl Fn(&str) -> bool) -> Option<PathBuf> {
    std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .filter(|e| matches(&e.file_name().to_string_lossy()))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .filter(|(modified, _)| *modified >= since)
        .max_by_key(|(modified, _)| *modified)
        .map(|(_, path)| path)
}

/// Text between `prefix` and `suffix` on the first line containing `prefix`.
fn between<'a>(text: &'a str, prefix: &str, suffix: char) -> Option<&'a str> {
    let start = text.find(prefix)? + prefix.len();
    let rest = &text[start..];
    Some(&rest[..rest.find(suffix)?])
}

/// Lines of `text` that contain `needle`, trimmed, without duplicates.
fn lines_with(text: &str, needle: &str, max: usize) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for line in text.lines().filter(|l| l.contains(needle)) {
        let line = clean(line);
        if !line.is_empty() && !out.contains(&line) {
            out.push(line);
        }
        if out.len() == max {
            break;
        }
    }
    out
}

/// Strips log4j XML wrapping and timestamps from a log line.
fn clean(line: &str) -> String {
    let line = line.trim();
    // <log4j:Message><![CDATA[text]]></log4j:Message> and <log4j:Throwable> alike.
    let line = match (line.starts_with("<log4j:"), line.find("<![CDATA[")) {
        (true, Some(i)) => &line[i + 9..],
        _ => line,
    };
    let line = line.split("]]>").next().unwrap_or(line);
    // "[12:34:56] [Render thread/ERROR]: text" → "text"
    let line = match line.strip_prefix('[') {
        Some(_) if line.contains("]: ") => line.split_once("]: ").map_or(line, |(_, r)| r),
        _ => line,
    };
    line.trim().chars().take(240).collect()
}

/// First "Exception"/"Error" line, the usual headline of a stack trace.
fn first_exception(text: &str) -> Option<String> {
    text.lines()
        .map(clean)
        .find(|l| {
            !l.starts_with("at ")
                && (l.contains("Exception") || l.contains("Error:"))
                && !l.contains("Exception in thread \"main\" ")
        })
}

fn report(title: &str, summary: impl Into<String>, details: Vec<String>, suggestions: &[&str], exit_code: Option<i32>) -> CrashReport {
    CrashReport {
        title: title.into(),
        summary: summary.into(),
        details,
        suggestions: suggestions.iter().map(|s| s.to_string()).collect(),
        culprit: None,
        report_file: None,
        exit_code,
    }
}

/// Matches known crash causes; the most specific rule wins.
pub fn diagnose(input: &CrashInput) -> Option<CrashReport> {
    let report_text = input.crash_report.as_deref().unwrap_or("");
    let all = format!("{report_text}\n{}\n{}", input.log, input.jvm_error.as_deref().unwrap_or(""));
    let code = input.exit_code;

    if all.contains("java.lang.OutOfMemoryError") {
        return Some(report(
            "Zu wenig Arbeitsspeicher",
            format!(
                "Minecraft hatte nicht genug RAM (eingestellt: {} MB).",
                input.max_memory_mb
            ),
            lines_with(&all, "OutOfMemoryError", 2),
            &[
                "Erhöhe in den Instanz-Einstellungen den maximalen Arbeitsspeicher, für Modpacks auf 6 bis 8 GB.",
                "Schließe andere speicherhungrige Programme.",
            ],
            code,
        ));
    }

    if all.contains("UnsupportedClassVersionError") || all.contains("compiled by a more recent version of the Java Runtime") {
        return Some(report(
            "Falsche Java-Version",
            "Eine Mod oder Minecraft braucht eine neuere Java-Version als die verwendete.",
            lines_with(&all, "UnsupportedClassVersionError", 2),
            &[
                "Lass den Java-Pfad in den Instanz-Einstellungen leer, dann wählt SlopClient die passende Version.",
                "Prüfe, ob alle Mods für diese Minecraft-Version gedacht sind.",
            ],
            code,
        ));
    }

    if all.contains("loader 'knot'") && all.contains("loader 'app'") && all.contains("cannot be cast to") {
        return Some(report(
            "Klassen doppelt geladen",
            "Fabric hat seine eigenen Klassen zweimal geladen. Das passiert, wenn Windows den Datenordner umleitet, etwa beim Start aus einer App mit eigener Umgebung.",
            lines_with(&all, "cannot be cast to", 2),
            &[
                "Starte SlopClient direkt über das Startmenü oder die Verknüpfung.",
                "Wenn das Problem bleibt, lege den Datenordner außerhalb von AppData an (Umgebungsvariable SLOPCLIENT_HOME).",
            ],
            code,
        ));
    }

    if all.contains("Incompatible mods found!")
        || all.contains("incompatible mod set")
        || all.contains("Missing or unsupported mandatory dependencies")
    {
        let mut details: Vec<String> = all
            .lines()
            .map(clean)
            .filter(|l| {
                (l.starts_with("- Mod ") || l.starts_with("- Install ") || l.starts_with("- Replace ") || l.starts_with("- Remove ")
                    || l.starts_with("Mod ID:") || l.contains("requires"))
                    && l.len() > 8
            })
            .collect();
        details.dedup();
        details.truncate(8);
        let mut r = report(
            "Inkompatible Mods",
            "Der Mod-Loader hat den Start abgebrochen, weil Mods fehlen oder nicht zusammenpassen.",
            details,
            &[
                "Installiere fehlende Abhängigkeiten oder aktualisiere die genannten Mods (Inhalte → Updates prüfen).",
                "Entferne Mods, die nicht für diese Minecraft- oder Loader-Version sind.",
            ],
            code,
        );
        r.culprit = between(&all, "- Mod '", '\'')
            .and_then(|name| between(&all, &format!("- Mod '{name}' ("), ')'))
            .map(|id| Culprit { mod_id: id.into(), file_name: None, name: None });
        return Some(r);
    }

    if all.contains("Found duplicate mods") || all.contains("Duplicate mod") || all.contains("DuplicateModsFoundException") {
        return Some(report(
            "Doppelte Mods",
            "Eine Mod liegt in mehreren Versionen im Mod-Ordner.",
            lines_with(&all, "uplicate", 4),
            &["Entferne die ältere Version der genannten Mod."],
            code,
        ));
    }

    let entrypoint = between(&all, "provided by '", '\'');
    let mixin = between(&all, "Mixin apply for mod ", ' ')
        .or_else(|| between(&all, "mixin config ", '.').filter(|_| all.contains("MixinApplyError") || all.contains("InvalidInjectionException")));
    if let Some(mod_id) = entrypoint.or(mixin) {
        let mut r = report(
            "Mod-Fehler beim Start",
            format!("Die Mod „{mod_id}“ ist beim Laden abgestürzt."),
            first_exception(&all).into_iter().collect(),
            &[
                "Deaktiviere die Mod und starte erneut.",
                "Suche nach einem Update der Mod oder prüfe, ob sie zu dieser Minecraft-Version passt.",
            ],
            code,
        );
        r.culprit = Some(Culprit { mod_id: mod_id.into(), file_name: None, name: None });
        return Some(r);
    }

    if all.contains("GLFW error 65542")
        || all.contains("does not appear to support OpenGL")
        || all.contains("Pixel format not accelerated")
        || all.contains("Couldn't set pixel format")
    {
        return Some(report(
            "Grafiktreiber",
            "OpenGL ließ sich nicht starten. Meist fehlt ein aktueller Grafiktreiber.",
            lines_with(&all, "GL", 2),
            &[
                "Installiere den neuesten Treiber von NVIDIA, AMD oder Intel.",
                "Bei Laptops: Weise Java (javaw.exe) in den Windows-Grafikeinstellungen die dedizierte Grafikkarte zu.",
            ],
            code,
        ));
    }

    if let Some(jvm) = &input.jvm_error {
        let problem = jvm
            .lines()
            .find(|l| l.contains("EXCEPTION_") || l.contains("SIGSEGV"))
            .map(|l| l.trim_start_matches('#').trim().to_string());
        let frame = jvm
            .lines()
            .find(|l| l.starts_with("# C  [") || l.starts_with("# J ") || l.starts_with("# j "))
            .map(|l| l.trim_start_matches('#').trim().to_string());
        return Some(report(
            "Java ist abgestürzt",
            "Die Java-Laufzeit selbst ist abgestürzt, oft wegen Grafiktreibern oder Shader-/Performance-Mods.",
            problem.into_iter().chain(frame).collect(),
            &[
                "Aktualisiere den Grafiktreiber.",
                "Deaktiviere testweise Shader und Performance-Mods (Sodium, Iris).",
            ],
            code,
        ));
    }

    if !report_text.is_empty() {
        let description = between(report_text, "Description: ", '\n').map(str::trim).unwrap_or("Unbekannter Fehler");
        return Some(report(
            "Minecraft ist abgestürzt",
            format!("Absturz: {description}."),
            first_exception(report_text).into_iter().collect(),
            &[
                "Öffne den Crash-Report und suche nach Mod-Namen in den ersten Zeilen.",
                "Wenn du zuletzt Mods hinzugefügt hast, deaktiviere sie testweise.",
            ],
            code,
        ));
    }

    match code {
        Some(0) | None => None,
        Some(c) => Some(report(
            "Unerwartet beendet",
            format!("Das Spiel wurde mit Code {c} beendet."),
            first_exception(&all).into_iter().collect(),
            &["Sieh dir das Log an, die letzten Zeilen zeigen meist die Ursache."],
            code,
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(log: &str) -> CrashInput {
        CrashInput { exit_code: Some(1), log: log.into(), max_memory_mb: 4096, ..Default::default() }
    }

    #[test]
    fn normal_exit_is_no_crash() {
        assert!(diagnose(&CrashInput { exit_code: Some(0), ..Default::default() }).is_none());
    }

    #[test]
    fn out_of_memory() {
        let r = diagnose(&input("Exception in thread \"Render thread\" java.lang.OutOfMemoryError: Java heap space")).unwrap();
        assert_eq!(r.title, "Zu wenig Arbeitsspeicher");
        assert!(r.summary.contains("4096"));
    }

    #[test]
    fn fabric_entrypoint_names_the_mod() {
        let log = "Could not execute entrypoint stage 'preLaunch' due to errors, provided by 'modernfix' at 'org.embeddedt.modernfix.ModernFixPreLaunchFabric'!\n\
                   Caused by: net.fabricmc.loader.api.LanguageAdapterException: Class X cannot be cast to Y";
        let r = diagnose(&input(log)).unwrap();
        assert_eq!(r.culprit.unwrap().mod_id, "modernfix");
    }

    #[test]
    fn classloader_conflict_beats_entrypoint() {
        let log = "provided by 'modernfix'\nclass A cannot be cast to class B (A is in unnamed module of loader 'knot' @1; B is in unnamed module of loader 'app')";
        assert_eq!(diagnose(&input(log)).unwrap().title, "Klassen doppelt geladen");
    }

    #[test]
    fn incompatible_mod_set() {
        let log = "[main/ERROR]: Incompatible mods found!\n\
                   <log4j:Message><![CDATA[ - Mod 'Sodium Extra' (sodium-extra) 0.6.0 requires version 0.6 of 'Sodium' (sodium), but only the wrong version is present: 0.5!]]></log4j:Message>";
        let r = diagnose(&input(log)).unwrap();
        assert_eq!(r.title, "Inkompatible Mods");
        assert_eq!(r.culprit.unwrap().mod_id, "sodium-extra");
        assert!(r.details[0].starts_with("- Mod 'Sodium Extra'"));
    }

    #[test]
    fn crash_report_description() {
        let r = diagnose(&CrashInput {
            exit_code: Some(-1),
            crash_report: Some("---- Minecraft Crash Report ----\nDescription: Rendering overlay\n\njava.lang.NullPointerException: boom\n\tat a.b(c)".into()),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(r.summary, "Absturz: Rendering overlay.");
        assert_eq!(r.details, vec!["java.lang.NullPointerException: boom".to_string()]);
    }

    #[test]
    fn jvm_crash() {
        let r = diagnose(&CrashInput {
            exit_code: Some(1),
            jvm_error: Some("#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x0\n# C  [atio6axx.dll+0x1234]\n".into()),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(r.title, "Java ist abgestürzt");
        assert!(r.details[1].contains("atio6axx.dll"));
    }
}
