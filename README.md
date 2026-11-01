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

Ab Version 0.5.7 aktualisiert sich der Launcher selbst: Gibt es ein neues Release, erscheint oben ein Hinweis mit den
Änderungen und „Jetzt aktualisieren“ (Einstellungen → Updates). Der Client-Mod kommt dabei mit. Wer eine ältere Version hat,
installiert 0.5.7 einmal von Hand.

> Der Installer ist noch nicht signiert; Windows SmartScreen fragt deshalb beim ersten Start nach („Weitere Informationen“ → „Trotzdem ausführen“).
> Die Update-Signatur ist davon unabhängig: Der Launcher installiert nur Updates, die mit dem SlopClient-Schlüssel signiert sind.

## Funktionen

### Launcher

- **Instanzen** für jede Minecraft-Version mit Fabric, Quilt, NeoForge oder Forge (1.13+): Gruppen, Kopieren, eigene Icons, Spielzeit, Desktop-Verknüpfung, Befehle vor dem Start und nach dem Beenden
- **Mod-Loader aktualisieren**: Loader und Version bestehender Instanzen ändern, mit Hinweis, wenn es eine neuere stabile Version gibt
- **Java automatisch**: die passende Runtime (8 / 17 / 21 / 25) kommt direkt von Mojang
- **Mods, Resourcepacks und Shader** von Modrinth und CurseForge: Suche passend zur Instanz, Abhängigkeiten, Drag & Drop eigener Dateien (werden erkannt und bekommen Updates), Performance-Paket (Sodium, Lithium, FerriteCore, Iris) mit einem Klick
- **Updates mit einem Klick**: Der Launcher sucht im Hintergrund nach Updates. Instanzen zeigen „N Updates“, jeder Inhalt bekommt einen eigenen „Update“-Knopf, und vor „Alle aktualisieren“ gibt es die Changelogs.
- **Mods nur einmal speichern**: Mods, Resourcepacks und Shader liegen einmal in einem gemeinsamen Speicher, die Instanzen bekommen Hardlinks darauf. Was eine Instanz schon hat, lädt keine andere neu. Dazu „Doppelte Dateien zusammenführen“ für ältere Instanzen.
- **Geteilte Spieleinstellungen**: Instanzen einer Gruppe teilen sich Grafik, Steuerung, Sound und Tastenbelegung (`options.txt` und Co.).
- **Modpacks**: `.mrpack` und CurseForge-ZIP importieren, Instanzen als `.mrpack` exportieren
- **Umzug aus Prism Launcher, PolyMC und MultiMC** inklusive Welten und Configs
- **Welten, Screenshots und Server** pro Instanz: Datapacks pro Welt, Backup als ZIP, Server-Liste mit Ping, „Beitreten“ startet direkt auf dem Server
- **Crash-Analyse**: erkennt u. a. zu wenig RAM, falsches Java, kaputte oder doppelte Mods und nennt die Ursache; Logs mit einem Klick über mclo.gs teilen (ohne Zugangsdaten)
- **Selbst-Update**: neue Releases mit Änderungen, signiert geprüft und mit einem Klick installiert
- **Discord-Status** mit Instanz und Server (abschaltbar)
- **Microsoft-Login** per Device Code, mehrere Accounts; Tokens werden mit Windows DPAPI verschlüsselt gespeichert

### Client-Mod (Fabric/Quilt, Minecraft 26.3)

- eigener **Titelbildschirm** und **ClickGUI** (Rechts-Shift) mit Suche, **Config-Profile**
- **HUD-Editor** mit Snapping und vielen Elementen: FPS, CPS, Keystrokes, Koordinaten, Geschwindigkeit, Rüstung, Tränke, Ping, Server-IP, Uhrzeit, Kompass, Item-Zähler, Combo, Reichweite, Cooldowns, Bossleiste, Arbeitsspeicher, Biom, Tag, Stoppuhr, Resourcepacks, Modul-Liste
- **Bibliothekar**: Schaust du auf ein Lesepult, stehen die Bücher des Bibliothekars samt Preis daneben
- **Ansicht**: Zoom (C), Freelook (Alt), Helligkeit, eigenes Fadenkreuz, Block-Umriss, Hit-Color, Low Fire, eigener Nametag, Glow für sichtbare Spieler
- **Lichtlevel-Overlay**: zeigt auf dem Boden, wo Monster spawnen können (rot immer, gelb nachts, grün sicher)
- **Skybox**: der Shader des Launchers als Himmel
- **Amboss-Planer**: günstigste Reihenfolge, um Verzauberungsbücher im Amboss zu kombinieren, mit Leveln pro Schritt; der Plan lässt sich neben dem Amboss anheften
- **Komfort**: Toggle Sprint, Auto-Werkzeug beim Abbauen, Chat mit Uhrzeit und gebündelten Wiederholungen, Scoreboard anpassen, Screenshot-Vorschau nach F2
- **Eigene Schriften** für Menüs, HUD und auf Wunsch das ganze Spiel
- **Server-Regeln**: Server können einzelne Module abschalten

### Kosmetik

- **Cape-Studio**: eigene, auch animierte Capes aus Bildern und GIFs, mit 3D-Vorschau; das Motiv läuft über die Ränder, auf der Elytra in Flügelform
- **Hüte, Begleiter und Zubehör**: zehn Hüte, 22 Begleiter (auf der Schulter, fliegend oder laufend, darunter ein Mini-Me mit deinem Skin) und Zubehör wie Katanas und Flügel
- Nur im Client, für dich: **Trail** hinter dir in mehreren Stilen (Funken, Spirale, Komet, Blüten, Seelen, Noten, Herzen) und **Glas**, Spieler als Figuren aus Glas in sechs Varianten
- **Skins**: Bibliothek mit 3D-Vorschau, direkt auf den Minecraft-Account hochladen
- **Online sichtbar**: über den Cosmetics-Server sehen andere SlopClient-Spieler deine Kosmetik. Standard ist der SlopClient-Server; einen eigenen trägst du unter *Einstellungen → Kosmetik online* ein. Im Browser lässt sich alles auch ohne Launcher verwalten (Webinterface des Servers).

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

Version an vier Stellen anheben, `## Unveröffentlicht` im `CHANGELOG.md` umbenennen, Tag `v<version>` pushen. Der
Workflow baut alles, signiert den Launcher für das Selbst-Update und veröffentlicht das GitHub-Release und das
Docker-Image. Die Schritte im Einzelnen und die Signatur-Secrets: [RELEASING.md](RELEASING.md).

## Lizenz

[Eclipse Public License 2.0](LICENSE).

**Kein offizielles Minecraft-Produkt. Nicht von Mojang oder Microsoft genehmigt oder mit ihnen verbunden.**
