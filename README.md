<p align="center">
  <img src="design/logo/glyph-512.png" width="96" alt="SlopClient-Logo">
</p>

<h1 align="center">SlopClient</h1>

<p align="center">
  Minecraft-Launcher und In-Game-Client in einem: Instanzen, Mods und Modpacks verwalten,<br>
  dazu ein eigenes Client-Interface mit HUD, ClickGUI und Kosmetik. Fair Play, keine Cheats.
</p>

<p align="center">
  <a href="https://github.com/ydavinci/slopclient/releases/latest"><img src="https://img.shields.io/github/v/release/ydavinci/slopclient?label=Download&color=8B5CF6" alt="Neueste Version"></a>
  <a href="https://github.com/ydavinci/slopclient/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/ydavinci/slopclient/release.yml?label=Build" alt="Build-Status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ydavinci/slopclient?color=555" alt="Lizenz"></a>
</p>

---

## Download

| Was | Datei | Für |
|---|---|---|
| **Launcher** | [`SlopClient_<version>_x64-setup.exe`](https://github.com/ydavinci/slopclient/releases/latest) | Windows 10/11, enthält den Client-Mod |
| Client-Mod einzeln | `slopclient-<version>.jar` | Fabric/Quilt, Minecraft 26.3, für andere Launcher |
| Nur Kosmetik | `slopclient-cosmetics-mod-<version>.jar` | Capes, Hüte und Begleiter ohne den Client, in jedem Fabric-Launcher ([mehr](#nur-die-kosmetik-als-mod)) |
| Cosmetics-Server | `ghcr.io/ydavinci/slopclient-cosmetics` | eigener Server für Capes, Hüte und Begleiter ([Anleitung](deploy/cosmetics/README.md)) |

Installer starten, mit dem Microsoft-Konto anmelden, Instanz anlegen, spielen. Der Launcher legt den Client-Mod
automatisch in passende Instanzen (pro Instanz abschaltbar).

Ab Version 0.5.6 aktualisiert sich der Launcher selbst: Gibt es ein neues Release, erscheint oben ein Hinweis mit den
Änderungen und „Jetzt aktualisieren“ (Einstellungen → Updates). Der Client-Mod kommt dabei mit. Wer eine ältere Version hat,
installiert 0.5.6 einmal von Hand.

> Der Installer ist noch nicht signiert; Windows SmartScreen fragt deshalb beim ersten Start nach („Weitere Informationen“ → „Trotzdem ausführen“).
> Die Update-Signatur ist davon unabhängig: Der Launcher installiert nur Updates, die mit dem SlopClient-Schlüssel signiert sind.

## Funktionen

### Launcher

- **Instanzen** für jede Minecraft-Version mit Fabric, Quilt, NeoForge oder Forge (1.13+), Gruppen, Kopieren, eigene Icons, Spielzeit, Desktop-Verknüpfung
- **Java automatisch**: die passende Runtime (8 / 17 / 21 / 25) kommt direkt von Mojang
- **Mods, Resourcepacks und Shader** von Modrinth und CurseForge: Suche passend zur Instanz, Abhängigkeiten, Updates mit Changelog, Drag & Drop eigener Dateien
- **Modpacks**: `.mrpack` und CurseForge-ZIP importieren, Instanzen als `.mrpack` exportieren
- **Umzug aus Prism Launcher, PolyMC und MultiMC** inklusive Welten und Configs
- **Welten, Screenshots und Server** pro Instanz; „Beitreten“ startet direkt auf dem Server
- **Crash-Analyse**: erkennt u. a. zu wenig RAM, falsches Java, kaputte oder doppelte Mods und nennt die Ursache; Logs mit einem Klick über mclo.gs teilen (ohne Zugangsdaten)
- **Discord-Status** mit Instanz und Server (abschaltbar)
- **Microsoft-Login** per Device Code; Tokens werden mit Windows DPAPI verschlüsselt gespeichert

### Client-Mod (Fabric/Quilt, Minecraft 26.3)

- eigener **Titelbildschirm** und **ClickGUI** (Rechts-Shift) mit Suche
- **HUD-Editor** mit Snapping und vielen Modulen: FPS, CPS, Keystrokes, Koordinaten, Rüstung, Tränke, Ping, Kompass, Item-Zähler, Combo, Reichweite, Cooldowns, Bossleiste …
- **Zoom** (C), **Freelook** (Alt), Helligkeit, Fadenkreuz, Toggle Sprint, Glow, eigene Schriften, Config-Profile
- **Server-Regeln**: Server können einzelne Module abschalten

### Kosmetik

- **Cape-Studio**: eigene, auch animierte Capes aus Bildern und GIFs, mit 3D-Vorschau
- **Hüte und Begleiter**: sechs Hüte, 16 Begleiter (auf der Schulter, fliegend oder laufend)
- **Skins**: Bibliothek mit 3D-Vorschau, direkt auf den Minecraft-Account hochladen
- **Online sichtbar**: über den Cosmetics-Server sehen andere SlopClient-Spieler deine Kosmetik. Standard ist der SlopClient-Server; einen eigenen trägst du unter *Einstellungen → Kosmetik online* ein.

**Fair Play:** SlopClient bietet HUD, Komfort, Performance und Kosmetik, aber keine Vorteile im Kampf.

## Nur die Kosmetik als Mod

`slopclient-cosmetics-mod-<version>.jar` zeigt Capes, Hüte und Begleiter von SlopClient-Spielern auch ohne den
SlopClient-Client, in jedem Launcher: zusammen mit [Fabric API](https://modrinth.com/mod/fabric-api) in den
`mods`-Ordner einer Fabric-Instanz für Minecraft 26.3 legen. Du siehst dann die Kosmetik aller Spieler, die auf dem
Cosmetics-Server angemeldet sind, auch deine eigene.

Einstellungen stehen in `config/slopclient-cosmetics.json` (wird beim ersten Start angelegt):

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `server` | `https://cosmetics.ydavinci.me` | Cosmetics-Server; leer schaltet die Online-Kosmetik ab |
| `capes`, `capesOfOthers` | `true` | Capes überhaupt / auch bei anderen |
| `capesOverMojang` | `true` | SlopClient-Cape statt Mojang-Cape zeigen |
| `animateCapes`, `capesAsElytra` | `true` | animierte Capes, Cape auch auf der Elytra |
| `hatsAndPets`, `hatsAndPetsOfOthers` | `true` | Hüte und Begleiter / auch bei anderen |
| `hideHatWithHelmet` | `false` | Hut ausblenden, wenn ein Helm getragen wird |

Die eigene Kosmetik suchst du im Launcher aus oder im Browser auf der Seite des Cosmetics-Servers
(z. B. `https://cosmetics.ydavinci.me/`, Anmeldung mit Microsoft).
Der SlopClient-Client enthält diese Mod bereits; beide zusammen zu installieren schadet nicht.

## Eigener Cosmetics-Server

Der Server (`crates/slopclient-cosmetics`) speichert Capes, Hüte und Begleiter pro Minecraft-Account.
Anmeldung ohne Passwort über Mojangs Session-Server, Daten in SQLite. Schnellstart mit Docker:

```sh
docker run -d -p 127.0.0.1:8787:8787 -v slopclient-cosmetics:/data \
  -e SLOPCLIENT_COSMETICS_PUBLIC_URL=https://cosmetics.example.com \
  ghcr.io/ydavinci/slopclient-cosmetics:latest
```

Unter der Server-Adresse liegt ein Webinterface, in dem Spieler ihre Kosmetik ohne Launcher verwalten.
HTTPS, Compose, Konfiguration, Webinterface, Moderation und Backups: [deploy/cosmetics/README.md](deploy/cosmetics/README.md).

## Selbst bauen

Voraussetzungen: [Rust](https://rustup.rs) (stable), Node.js 22, JDK 25.

```sh
# 1. Client-Mod (wird beim nächsten Schritt in den Launcher eingebettet)
cd client-mod && ./gradlew build && cd ..

# 2. Launcher: Entwicklungsmodus bzw. Installer
cd launcher && npm ci
npx tauri dev
npx tauri build          # → target/release/bundle/nsis/

# CLI und Cosmetics-Server
cargo build --release -p slopclient-cli
cargo build --release -p slopclient-cosmetics
```

Tests: `cargo test --workspace`.

Die 3D-Vorschau im Webinterface des Cosmetics-Servers ist der Viewer des Launchers
(`launcher/src/webviewer/`), gebaut nach `crates/slopclient-cosmetics/src/web/viewer.js`. Nach Änderungen an
`launcher/src/lib/extras.ts` oder `launcher/src/lib/cape.ts` in `launcher/` neu bauen: `npm run build:viewer`.

**Neue Hüte und Begleiter** kommen nach `cosmetics/extras.json` (Begleiter-Texturen nach `cosmetics/pets/`) und
brauchen nur ein neues Server-Release: Mod, Launcher und Webinterface holen den Katalog vom Cosmetics-Server.
**Zubehör** aus einem Java-Blockmodell (z. B. ein Blockbench-Export) übernimmt `tools/accessories/import_java_model.py`
in den Katalog, mit Ersteller (`--credit`) und Platzierung am Körper. Nur Modelle mit Erlaubnis des Erstellers.

### Projektstruktur

| Ordner | Inhalt |
|---|---|
| `crates/slopclient-core` | Kern in Rust: Versionen, Installation, Mod-Loader, Inhalte, Login, Start |
| `crates/slopclient-cli` | Test-CLI auf dem Kern |
| `crates/slopclient-cosmetics` | Cosmetics-Server (axum + SQLite) |
| `launcher/` | Launcher-Oberfläche (Tauri 2 + React) |
| `client-mod/` | In-Game-Client (Fabric) |
| `client-mod/cosmetics/` | Kosmetik-Mod (Capes, Hüte, Begleiter), im Client enthalten und einzeln nutzbar |
| `cosmetics/` | Katalog der Hüte und Begleiter, geteilt von Launcher, Mod und Server |
| `deploy/cosmetics/` | Docker, Compose, systemd und Caddy für den Server |
| `web/` | Projekt-Website (Astro) |

Plan und Design: [PLAN.md](PLAN.md), [DESIGN.md](DESIGN.md). Änderungen: [CHANGELOG.md](CHANGELOG.md).

### Release

Versionsnummer in `Cargo.toml`, `client-mod/gradle.properties`, `launcher/package.json` und
`launcher/src-tauri/tauri.conf.json` anheben (dazu `Cargo.lock` und `launcher/package-lock.json`), Abschnitt im `CHANGELOG.md` ergänzen, dann einen Tag `v<version>` pushen.
Der Workflow baut alles, veröffentlicht das GitHub-Release und das Docker-Image.

## Lizenz

[Eclipse Public License 2.0](LICENSE).

**Kein offizielles Minecraft-Produkt. Nicht von Mojang oder Microsoft genehmigt oder mit ihnen verbunden.**
