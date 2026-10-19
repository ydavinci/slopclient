# DaClient

Minecraft-Launcher mit eigenem Client-Interface. Plan & Design: [PLAN.md](PLAN.md), [design/mockup.html](design/mockup.html).

## Stand

Backend (`crates/daclient-core`), Test-CLI (`crates/daclient-cli`), Launcher-UI (`launcher/`, Tauri 2 + React) und Client-Mod (`client-mod/`, Fabric 26.3).

Funktioniert:
- Versionsliste (Mojang-Manifest, offline aus Cache)
- Installation beliebiger Versionen: Client, Libraries, Natives (alt & neu), Assets, Log-Config
- Java-Runtimes automatisch von Mojang (Java 8 / 17 / 21 / 25 je nach Version)
- Mod-Loader: Fabric, Quilt, NeoForge, Forge (1.13+) mit Versionsauswahl
- Instanzen anlegen/listen/löschen, Spielzeit-Erfassung
- Modpacks: `.mrpack` und CurseForge-ZIP importieren (Datei oder Projekt-Slug), Instanzen als `.mrpack` exportieren
- Modrinth: Suche (gefiltert nach Instanz), Installation von Mods/Resourcepacks/Shadern mit Abhängigkeiten, Liste pro Instanz, (De)aktivieren, Entfernen, Updates, Erkennung manuell kopierter Dateien
- CurseForge (mit API-Schlüssel): Suche, Installation mit Abhängigkeiten, Updates, Erkennung per Fingerprint. Dateien, deren Autoren Downloads außerhalb der Website sperren, landen in `blocked.json` und werden aus dem Download-Ordner übernommen
- Start mit Live-Log und Crash-Analyse (erkennt u. a. zu wenig RAM, falsches Java, inkompatible/doppelte Mods, fehlerhafte Mixins, Grafiktreiber und nennt die verursachende Mod, die sich direkt deaktivieren lässt)
- Import von Instanzen aus Prism Launcher, PolyMC und MultiMC (Kopie inkl. Welten und Configs, Mods werden mit Modrinth/CurseForge verknüpft)
- DaClient-Mod (Fabric/Quilt, Minecraft 26.3): eigener Titelbildschirm, ClickGUI (Rechts-Shift) mit Suche, HUD-Editor mit Snapping, HUD-Module (Watermark, ArrayList, FPS, CPS, Keystrokes, Koordinaten, Rüstung, Trankeffekte, Ping, Uhrzeit), Zoom (C), Freelook (Alt), Helligkeit, Fadenkreuz, Toggle Sprint, Sora-Schrift, Config-Profile. Der Launcher legt die Mod beim Start in passende Instanzen und installiert Fabric API mit; abschaltbar pro Instanz
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
target/debug/daclient set-curseforge-key <api-key>
target/debug/daclient search jei --source curseforge --instance modded
target/debug/daclient content add modded jei --source curseforge
target/debug/daclient content blocked modded        # fehlende CurseForge-Dateien, holt Downloads ab
target/debug/daclient modpack import fabulously-optimized
target/debug/daclient modpack import all-the-mods-10 --platform curseforge
target/debug/daclient import list                  # Instanzen von Prism/PolyMC (--dir für MultiMC)
target/debug/daclient import run "%APPDATA%\PrismLauncher\instances\<name>"
target/debug/daclient diagnose crash-report.txt    # Absturzursache aus Log oder Crash-Report
target/debug/daclient modpack export modded modded.mrpack --include config --include options.txt
target/debug/daclient set-client-id <azure-client-id>
target/debug/daclient login
target/debug/daclient launch survival
```

`--dev-offline <name>` startet ohne Account (nur Entwicklung, Einzelspieler). `--dry-run` zeigt den Java-Befehl, statt zu starten.
Daten liegen in `%APPDATA%\DaClient` (überschreibbar mit `DACLIENT_HOME`).

## Installer bauen

```
cd launcher
npx tauri build      # Ergebnis: target/release/bundle/nsis/DaClient_<version>_x64-setup.exe
```

Der Installer richtet DaClient pro Benutzer ein (keine Admin-Rechte). Für eine Veröffentlichung fehlen noch Code-Signing (Zertifikat) und ein Update-Server für den Auto-Updater.

## Client-Mod bauen

```
cd client-mod
gradlew build        # braucht JDK 25; Ergebnis: build/libs/daclient-<version>.jar
```

`cargo build` bündelt das Jar danach automatisch in den Launcher (`crates/daclient-core/build.rs`). Ohne gebautes Jar startet der Launcher Instanzen einfach ohne Mod.

### Module serverseitig abschalten

Server können einzelne Module für die Sitzung abschalten (wie bei Lunar): ein Play-Payload auf dem Kanal `daclient:disable` mit einer Liste von Modul-IDs als UTF-8-Strings (Länge als VarInt, dann je String als VarInt-Länge + Bytes), z. B. `["freelook"]`. IDs sind die kleingeschriebenen Modulnamen mit Bindestrichen (`freelook`, `zoom`, `toggle-sprint`, `helligkeit`). Beim Verlassen des Servers gilt wieder die Auswahl des Spielers.

## Microsoft-Login einrichten

1. Im [Azure-Portal](https://portal.azure.com) eine App-Registrierung anlegen: Kontotyp „Nur persönliche Microsoft-Konten“, unter *Authentifizierung* „Öffentliche Clientflows zulassen“ aktivieren.
2. Die Client-ID bei Mojang für die Minecraft-API freischalten lassen (Antragsformular für Launcher-Entwickler). Bis zur Freigabe antwortet `login_with_xbox` mit 403.
3. `daclient set-client-id <id>`

## CurseForge einrichten

Die CurseForge-API braucht einen eigenen Schlüssel aus der [CurseForge-Konsole](https://console.curseforge.com/) (Antrag als Drittanbieter-Launcher). Hinterlegen mit `daclient set-curseforge-key <key>`, in den Einstellungen der App oder per `DACLIENT_CURSEFORGE_API_KEY`. Modrinth funktioniert ohne Schlüssel.
