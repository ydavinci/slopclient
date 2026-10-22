# SlopClient

Minecraft-Launcher mit eigenem Client-Interface. Plan & Design: [PLAN.md](PLAN.md), [design/mockup.html](design/mockup.html).

## Stand

Backend (`crates/slopclient-core`), Test-CLI (`crates/slopclient-cli`), Launcher-UI (`launcher/`, Tauri 2 + React) und Client-Mod (`client-mod/`, Fabric 26.3).

Funktioniert:
- Versionsliste (Mojang-Manifest, offline aus Cache)
- Installation beliebiger Versionen: Client, Libraries, Natives (alt & neu), Assets, Log-Config
- Java-Runtimes automatisch von Mojang (Java 8 / 17 / 21 / 25 je nach Version)
- Mod-Loader: Fabric, Quilt, NeoForge, Forge (1.13+) mit Versionsauswahl
- Instanzen anlegen/listen/löschen, Spielzeit-Erfassung, eigene Icons (Zuschnitt-Dialog; Modpacks und Prism-Instanzen bringen ihr Icon mit)
- Modpacks: `.mrpack` und CurseForge-ZIP importieren (Datei oder Projekt-Slug), Instanzen als `.mrpack` exportieren
- Modrinth: Suche (gefiltert nach Instanz), Installation von Mods/Resourcepacks/Shadern mit Abhängigkeiten, Liste pro Instanz, (De)aktivieren, Entfernen, Updates, Erkennung manuell kopierter Dateien
- CurseForge (mit API-Schlüssel): Suche, Installation mit Abhängigkeiten, Updates, Erkennung per Fingerprint. Dateien, deren Autoren Downloads außerhalb der Website sperren, landen in `blocked.json` und werden aus dem Download-Ordner übernommen
- Start mit Live-Log und Crash-Analyse (erkennt u. a. zu wenig RAM, falsches Java, inkompatible/doppelte Mods, fehlerhafte Mixins, Grafiktreiber und nennt die verursachende Mod, die sich direkt deaktivieren lässt)
- Cape-Studio (Seite „Kosmetik“): eigene Capes aus Bildern und GIFs mit Editor (Ausschnitt, Rückseite, Ränder, Elytra, Bildauswahl, Tempo, Auflösung bis 512×256) und 3D-Vorschau (skinview3d); Bibliothek mit aktiver Cape. Die aktive Cape trägt dein Spieler im Spiel (animiert, auch als Elytra; Modul „Capes“, serverseitig abschaltbar über `capes`). Mit Cosmetics-Server (`crates/slopclient-cosmetics`, Anleitung in [deploy/cosmetics](deploy/cosmetics/README.md)) sehen andere SlopClient-Spieler deine Cape und du ihre
- Import von Instanzen aus Prism Launcher, PolyMC und MultiMC (Kopie inkl. Welten und Configs, Mods werden mit Modrinth/CurseForge verknüpft)
- SlopClient-Mod (Fabric/Quilt, Minecraft 26.3): eigener Titelbildschirm, ClickGUI (Rechts-Shift) mit Suche, HUD-Editor mit Snapping, HUD-Module (Watermark, ArrayList, FPS, CPS, Keystrokes, Koordinaten, Rüstung, Trankeffekte, Ping, Uhrzeit), Zoom (C), Freelook (Alt), Helligkeit, Fadenkreuz, Toggle Sprint, Sora-Schrift, Config-Profile. Der Launcher legt die Mod beim Start in passende Instanzen und installiert Fabric API mit; abschaltbar pro Instanz
- Microsoft-Login (Device Code → Xbox → XSTS → Minecraft), Tokens per DPAPI verschlüsselt
