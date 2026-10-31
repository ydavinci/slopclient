# Releases

## Ablauf

1. Version an allen vier Stellen gleich setzen: `Cargo.toml` (`[workspace.package]`), `client-mod/gradle.properties`,
   `launcher/src-tauri/tauri.conf.json`, `launcher/package.json`. Der Job `version` im Workflow prüft das.
2. In `CHANGELOG.md` den Abschnitt `## Unveröffentlicht` in `## <version>` umbenennen. Daraus werden die Release-Notes
   und der Text im Update-Hinweis des Launchers.
3. Tag pushen: `git tag v<version> && git push origin v<version>`. Tags mit `-` (z. B. `v0.6.0-beta.1`) werden Pre-Releases.

Ein manueller Lauf (`Actions → Release → Run workflow`) baut alles und lädt nur die Artefakte hoch (Probelauf).

## Updater-Signatur

Der Launcher aktualisiert sich über `tauri-plugin-updater`. Er lädt
`https://github.com/ydavinci/slopclient/releases/latest/download/latest.json` und installiert nur Installer, deren
Signatur zum öffentlichen Schlüssel in `launcher/src-tauri/tauri.conf.json` (`plugins.updater.pubkey`) passt.
`releases/latest` überspringt Pre-Releases, Betas bekommen also nur Leute, die sie selbst installieren.

Pro Release erzeugt der Workflow zusätzlich:
- `SlopClient_<version>_x64-setup.exe.sig` (vom `tauri build`, mit dem privaten Schlüssel)
- `latest.json` (Version, Notes aus `CHANGELOG.md`, Datum, URL und Signatur des Installers)

### Repository-Secrets (Settings → Secrets and variables → Actions)

| Secret | Inhalt |
|---|---|
| `TAURI_SIGNING_PRIVATE_KEY` | Inhalt der privaten Schlüsseldatei (eine Zeile Base64) |
| `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | Passwort des Schlüssels |

Fehlt `TAURI_SIGNING_PRIVATE_KEY`, bricht ein Release (Tag) mit einer klaren Meldung ab, statt unsigniert zu
veröffentlichen. Ein Probelauf baut dann ohne Updater-Dateien.

### Den Schlüssel nicht verlieren

- Der private Schlüssel und sein Passwort gehören in einen Passwort-Manager **und** ein Backup, nie ins Repo.
- **Ist der Schlüssel weg, können installierte Launcher nie wieder automatisch aktualisiert werden.** Dann braucht es
  ein neues Schlüsselpaar, einen neuen `pubkey` in `tauri.conf.json`, und alle müssen diese Version von Hand installieren.
- Ist er in falsche Hände geraten: genauso neu erzeugen und den alten `pubkey` ersetzen, sonst kann jemand Updates
  signieren, die alle Launcher annehmen.

Neues Schlüsselpaar:

```sh
cd launcher
npx tauri signer generate -w ~/.tauri/slopclient-updater.key
```

Der Inhalt von `~/.tauri/slopclient-updater.key.pub` kommt nach `plugins.updater.pubkey`.
