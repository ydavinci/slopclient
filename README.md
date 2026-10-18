# DaClient

Minecraft-Launcher mit eigenem Client-Interface. Plan & Design: [PLAN.md](PLAN.md), [design/mockup.html](design/mockup.html).

## Stand

Backend (`crates/daclient-core`) + Test-CLI (`crates/daclient-cli`). Die UI (Tauri) folgt später.

Funktioniert:
- Versionsliste (Mojang-Manifest, offline aus Cache)
- Installation beliebiger Versionen: Client, Libraries, Natives (alt & neu), Assets, Log-Config
- Java-Runtimes automatisch von Mojang (Java 8 / 17 / 21 / 25 je nach Version)
- Mod-Loader: Fabric, Quilt, NeoForge, Forge (1.13+) mit Versionsauswahl
- Instanzen anlegen/listen/löschen, Spielzeit-Erfassung
- Modrinth: Suche (gefiltert nach Instanz), Installation von Mods/Resourcepacks/Shadern mit Abhängigkeiten, Liste pro Instanz, (De)aktivieren, Entfernen, Updates, Erkennung manuell kopierter Dateien
- Start mit Live-Log
- Microsoft-Login (Device Code → Xbox → XSTS → Minecraft), Tokens per DPAPI verschlüsselt

## CLI

```
cargo build
target/debug/daclient versions
target/debug/daclient instance create "Survival" 26.3
target/debug/daclient loaders neoforge 26.3
target/debug/daclient instance create "Modded" 26.3 --loader fabric   # neueste stabile Version
target/debug/daclient search sodium --instance modded
target/debug/daclient content add modded sodium iris modmenu
target/debug/daclient content list modded
target/debug/daclient content updates modded
target/debug/daclient set-client-id <azure-client-id>
target/debug/daclient login
target/debug/daclient launch survival
```

`--dev-offline <name>` startet ohne Account (nur Entwicklung, Einzelspieler). `--dry-run` zeigt den Java-Befehl, statt zu starten.
Daten liegen in `%APPDATA%\DaClient` (überschreibbar mit `DACLIENT_HOME`).

## Microsoft-Login einrichten

1. Im [Azure-Portal](https://portal.azure.com) eine App-Registrierung anlegen: Kontotyp „Nur persönliche Microsoft-Konten“, unter *Authentifizierung* „Öffentliche Clientflows zulassen“ aktivieren.
2. Die Client-ID bei Mojang für die Minecraft-API freischalten lassen (Antragsformular für Launcher-Entwickler). Bis zur Freigabe antwortet `login_with_xbox` mit 403.
3. `daclient set-client-id <id>`
