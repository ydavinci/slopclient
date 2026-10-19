# DaClient – Projektplan

Ein eigener Minecraft-Launcher im Stil von Prism Launcher (Instanzen, Modrinth/CurseForge, Fabric/NeoForge, Microsoft-Login) – kombiniert mit einem **eigenen In-Game-Client-Interface** im Stil von Lunar/Badlion (Custom Title Screen, Mod-Menü/ClickGUI, HUD-Editor). Fokus: **nur legitime Features** (QoL, HUD, Performance, Kosmetik) – keine Combat-Vorteile, damit der Client auf Servern wie Hypixel nutzbar bleibt.

Das Design-Mockup liegt unter [design/mockup.html](design/mockup.html).

---

## 1. Produkt-Überblick

Das Projekt besteht aus **zwei Komponenten**:

| Komponente | Was | Technik |
|---|---|---|
| **Launcher** | Desktop-App: Login, Instanzen, Versionen/Loader, Mods/Packs/Shader, Java, Start | Tauri 2 (Rust-Core) + React + TypeScript + Tailwind |
| **Client-Mod** („DaClient Core“) | Wird automatisch in jede Instanz installiert: Title Screen, ClickGUI, HUD-Editor, Module | Fabric-Mod (Java 21, Fabric Loom), später NeoForge via Architectury |

### Warum Tauri statt Electron?
- ~10 MB Installer statt ~150 MB, deutlich weniger RAM im Hintergrund, während Minecraft läuft.
- Rust eignet sich gut für die schwere Arbeit: parallele Downloads mit SHA1-Prüfung, Entpacken, Prozessverwaltung.
- Frontend bleibt Web-Technik (React), damit ist das „Client-Look“-UI schnell gebaut.
- Rust (MSVC-Toolchain), Node 24 und Java 17 sind bereits installiert.
- Der Rust-Core ist eine **eigenständige Library** (`daclient-core`) ohne Tauri-Abhängigkeit. Dazu gibt es ein CLI (`daclient-cli`), mit dem das Backend komplett ohne UI getestet wird. Die Tauri-App wird später nur eine dünne Schicht darüber.

---

## 2. Feature-Umfang

### 2.1 Launcher (Prism-Parität)
- **Microsoft-Login** (mehrere Accounts, Wechsel per Klick, Skin-Kopf als Avatar)
- **Instanzen**: anlegen, kopieren, gruppieren, Icon/Name, Spielzeit, zuletzt gespielt, Ordner öffnen
- **Versionen**: alle Minecraft-Releases, optional Snapshots/Old Beta/Alpha
- **Mod-Loader**: Vanilla, Fabric, Quilt, NeoForge, Forge – jeweils mit Versionsauswahl
- **Content-Browser**: Modrinth + CurseForge, Filter (Version, Loader, Kategorie, Sortierung), Abhängigkeiten automatisch auflösen
- **Content-Typen**: Mods, Resourcepacks, Shaderpacks, Datapacks, Modpacks
- **Mod-Verwaltung pro Instanz**: aktivieren/deaktivieren, Updates prüfen, entfernen, lokale Dateien per Drag & Drop
- **Modpacks**: Import/Export `.mrpack` (Modrinth) und CurseForge-ZIP; Import aus Prism/MultiMC/offiziellem Launcher
- **Java-Manager**: passende Java-Version automatisch herunterladen (8 / 17 / 21 / 25 – je nach `javaVersion` aus der Version-JSON), manuelle Pfade möglich
- **Einstellungen pro Instanz**: RAM, JVM-Argumente, Fenstergröße, Pre-/Post-Launch-Befehle
- **Live-Log-Konsole** mit Filter, Crash-Erkennung und Crash-Report-Ansicht (inkl. Upload zu mclo.gs)
- **Download-Queue** mit Fortschritt
- **Auto-Updater** für den Launcher (Tauri Updater, signierte Releases)
- **i18n**: Deutsch + Englisch

### 2.2 Client-Mod „DaClient Core“ (Lunar/Badlion-Stil)
- **Custom Title Screen**: animierter Hintergrund, Logo, eigene Buttons, Account-Anzeige, Server-Schnellzugriff
- **Mod-Menü / ClickGUI** (Standard-Taste: `Rechts-Shift`): Kategorien, Modul-Karten mit Toggle, Einstellungen (Slider, Farbe, Keybind, Modus), Suche
- **HUD-Editor**: Drag & Drop, Snapping an Kanten/Mitte, Skalierung, Hintergrund/Farben pro Element
- **Eigener Font-Renderer** (Sora) für den modernen Look
- **Profile/Configs**: mehrere Config-Profile, Export/Import
- **Module v1** (alle legit):
  - HUD: FPS, CPS, Keystrokes, Koordinaten, Richtung, Rüstungsstatus, Trankeffekte, Ping, Uhrzeit, Server-IP, Speed
  - Render: Zoom, Fullbright, Crosshair-Editor, Block-Outline, Hit-Color, Motion Blur, Nametags, Low Fire
  - QoL: Toggle Sprint/Sneak, Freelook (respektiert Server-Verbote), Chat-Verbesserungen, Scoreboard-Anpassung, Screenshot-Manager, Auto-GG (optional)
  - Performance: Preset, das Sodium, Lithium, FerriteCore, (Iris für Shader) über Modrinth mitinstalliert
- **Server-Opt-out**: Plugin-Channel, über den Server Module wie Freelook deaktivieren können (wie bei Lunar)

### 2.3 Später (nicht v1)
- Kosmetik (Capes, Wings) mit eigenem Backend
- Freundesliste / Online-Status / Einladungen
- Multi-Version des Client-Mods (z. B. 1.8.9 via Legacy Fabric, 1.20.x, 1.21.x) über Stonecutter
- NeoForge-Port des Client-Mods

---

## 3. Architektur

```
daclient/
├─ crates/
│  ├─ daclient-core/          Backend-Library (auth, meta, install, launch, content, instances)
│  └─ daclient-cli/           Test-CLI: login, versions, install, launch
├─ launcher/
│  ├─ src/                    React + TS (UI)
│  │  ├─ app/                 Routing, Layout, Titlebar
│  │  ├─ features/            home, instances, browse, accounts, settings, downloads, logs
│  │  ├─ components/ui/       Design-System (Button, Card, Chip, Tabs, Modal, Toast …)
│  │  └─ lib/ipc.ts           typisierte Tauri-Commands + Events
│  └─ src-tauri/              Rust-Core
│     └─ src/
│        ├─ auth/             MSA → Xbox Live → XSTS → Minecraft
│        ├─ meta/             Mojang-Manifest, Fabric/Quilt-Meta, NeoForge/Forge-Maven
│        ├─ install/          Libraries, Assets, Natives, Loader-Installer, Java-Runtimes
│        ├─ launch/           Argumente bauen, Prozess starten, Log-Streaming, Crash-Erkennung
│        ├─ content/          Modrinth-, CurseForge-Client, Dependency-Resolver, Update-Check
│        ├─ instances/        Instanz-Modell, Import/Export (mrpack, CF-ZIP, Prism)
│        ├─ download/         parallele Download-Queue, Retry, SHA1/SHA512-Prüfung
│        └─ storage/          Pfade, Settings, Keyring
├─ client-mod/                Fabric-Mod (Gradle, Loom, Mixin)
│  └─ src/main/java/…/daclient/
│     ├─ gui/                 TitleScreen, ClickGui, HudEditor, Widgets
│     ├─ module/              Module + Settings-System
│     ├─ hud/                 HUD-Elemente
│     ├─ render/              Font-Renderer, Shapes, Blur
│     └─ mixin/
└─ docs/
```

### 3.1 Datenablage (`%APPDATA%/DaClient/`)
```
accounts.json        nur Profil-Infos (Name, UUID, Skin)
secrets.dat          Tokens, per Windows DPAPI an den Windows-Benutzer gebunden verschlüsselt
settings.json
instances/<id>/
   instance.json     Name, MC-Version, Loader+Version, Java, RAM, Icon, Gruppe, Spielzeit
   content.lock.json Quelle (modrinth/curseforge/lokal), Projekt-ID, Version-ID, Hash, Dateiname, aktiv
   minecraft/        mods/, resourcepacks/, shaderpacks/, config/, saves/, screenshots/, logs/
libraries/           geteilt zwischen allen Instanzen (wie Prism)
assets/              indexes/, objects/
versions/            Version-JSONs + Client-JARs
java/                heruntergeladene Runtimes
cache/               API-Antworten, Icons
```

### 3.2 Kommunikation UI ↔ Rust
- Tauri **Commands** für Aktionen (`login`, `create_instance`, `install_content`, `launch` …)
- Tauri **Events** für Streams (Download-Fortschritt, Log-Zeilen, Spielstatus)
- Typen werden mit `specta`/`tauri-specta` aus Rust nach TypeScript generiert → keine Typ-Drift

### 3.3 Launcher ↔ Client-Mod
- Launcher legt das Mod-JAR passend zur MC-Version in `mods/` und hält es aktuell.
- Übergabe von Infos (Launcher-Version, Instanz-Name) über JVM-Properties (`-Ddaclient.instance=…`).
- Client-Mod-Configs liegen in `minecraft/config/daclient/` → der Launcher kann sie später auch anzeigen/synchronisieren.

---

## 4. Kern-Abläufe im Detail

### 4.1 Microsoft-Login
1. **OAuth 2.0 Authorization Code + PKCE** im System-Browser mit Loopback-Redirect (`http://localhost:<port>`); Fallback: Device-Code-Flow.
2. MS-Token → `user.auth.xboxlive.com/user/authenticate` → **XBL-Token** + User-Hash
3. XBL → `xsts.auth.xboxlive.com/xsts/authorize` (RelyingParty `rp://api.minecraftservices.com/`) → **XSTS-Token**
   - Fehlercodes behandeln: kein Xbox-Profil, Kinderkonto, Land nicht unterstützt
4. `api.minecraftservices.com/authentication/login_with_xbox` → **Minecraft-Access-Token**
5. `/entitlements/mcstore` → Besitz prüfen; `/minecraft/profile` → UUID, Name, Skin
6. Refresh-Token sicher speichern, beim Start still erneuern.

> ⚠️ **Blocker vorab klären:** Man braucht eine eigene **Azure-App-Registrierung** (Client-ID). Neue App-IDs müssen von Mojang/Microsoft für die Minecraft-API **freigeschaltet** werden (Antragsformular), sonst liefert `login_with_xbox` 403. Das dauert ggf. Tage–Wochen → als Erstes beantragen.

### 4.2 Version installieren & starten
1. `piston-meta.mojang.com/mc/game/version_manifest_v2.json` laden → Version-JSON
2. Loader-Profil mergen (`inheritsFrom`): Fabric/Quilt via Meta-API, NeoForge/Forge via offiziellem Installer
3. **Rules** auswerten (OS, Architektur, Features) → Libraries-Liste
4. Libraries + Client-JAR parallel laden, SHA1 prüfen; Natives entpacken (ältere Versionen)
5. Asset-Index + Objekte laden (`resources.download.minecraft.net`), Legacy/virtual für alte Versionen
6. Passende Java-Runtime sicherstellen (`javaVersion.majorVersion`)
7. Argumente bauen: JVM-Args + Game-Args mit Platzhaltern (`${auth_player_name}`, `${auth_access_token}`, `${game_directory}` …), Log4j-Config
8. Prozess starten, stdout/stderr live ins UI streamen, Exit-Code + Crash-Report auswerten, Spielzeit erfassen

### 4.3 Mod-Loader
| Loader | Quelle | Installation |
|---|---|---|
| Fabric | `meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json` | Profil-JSON mergen – fertig |
| Quilt | `meta.quiltmc.org/v3/…` | wie Fabric |
| NeoForge | `maven.neoforged.net` (Versionsliste aus maven-metadata.xml) | Installer-JAR headless ausführen (`--install-client`) bzw. Processors selbst ausführen |
| Forge | `maven.minecraftforge.net` | wie NeoForge |

### 4.4 Content (Modrinth & CurseForge)
- **Modrinth API v2** (`api.modrinth.com/v2`): Suche mit Facets (`project_type`, `versions`, `categories` = Loader), Versionen pro Projekt, Abhängigkeiten, Hashes; eigener `User-Agent` Pflicht. Kein API-Key nötig.
- **CurseForge API** (`api.curseforge.com/v1`): **API-Key nötig** (über die CurseForge-Konsole beantragen). gameId `432`; classIds: Mods `6`, Resourcepacks `12`, Shaders `6552`, Modpacks `4471`; Loader-Typen: Forge 1, Fabric 4, Quilt 5, NeoForge 6.
  - Manche Autoren verbieten Drittanbieter-Downloads (`downloadUrl = null`) → Dialog „manuell herunterladen“ mit Link, dann Datei automatisch aus dem Downloads-Ordner übernehmen.
- **Dependency-Resolver**: Pflicht-Abhängigkeiten automatisch mitinstallieren, Inkompatibilitäten warnen.
- **Update-Check**: per Hash (Modrinth `version_files/update`, CurseForge Fingerprints) → „3 Updates verfügbar“.
- **Shaderpacks**: Hinweis/Auto-Install von Iris (+ Sodium), wenn die Instanz keinen Shader-Loader hat.

---

## 5. Design

**Leitidee:** dunkles, cleanes „Client“-UI wie Lunar/Badlion – Glas-Panels über einem unscharfen Hintergrundbild der aktiven Instanz, ein klarer Akzent, großer PLAY-Button. Launcher und In-Game-UI teilen dasselbe Design-System, damit sich alles wie *ein* Produkt anfühlt.

### 5.1 Design-Tokens
| Token | Wert | Verwendung |
|---|---|---|
| `bg` | `#09080D` | App-Hintergrund |
| `surface` | `#121019` | Panels, Karten |
| `surface-2` | `#1A1724` | Hover, Inputs |
| `border` | `rgba(255,255,255,.07)` | Trennlinien |
| `text` | `#E8EAF0` | Primärtext |
| `muted` | `#8B91A1` | Sekundärtext |
| `accent` | `#8B5CF6` → `#C084FC` (Lila-Verlauf) | PLAY, aktive Toggles, Fokus |
| `success` / `warn` / `danger` | `#3DDC97` / `#FFB547` / `#FF5C6C` | Status |
| Radius | 8 / 12 / 16 px | Chips / Karten / Panels |
| Schrift | Sora (UI), JetBrains Mono (Logs) | |
| Motion | 150–220 ms, ease-out; Panels sliden + faden | |

### 5.2 Launcher-Screens
1. **Titlebar** (rahmenlos, eigene Fenster-Buttons) + **Icon-Rail links**: Home, Instanzen, Browse, Downloads, Einstellungen; unten Account-Avatar.
2. **Home**: Hero mit Hintergrund der zuletzt gespielten Instanz, Name, Chips (MC-Version, Loader, Modanzahl), großer **PLAY**-Button mit Dropdown (Instanz wechseln), darunter „Zuletzt gespielt“-Leiste und News.
3. **Instanzen**: Grid mit Karten (Icon, Name, Version, Loader, Spielzeit), Gruppen, Suche, „+ Neue Instanz“.
4. **Neue Instanz** (Modal, 3 Schritte): Version wählen (mit Filter Release/Snapshot) → Loader + Loader-Version → Name/Icon/RAM. Alternativ Tab „Modpack importieren“.
5. **Instanz-Detail**: Tabs *Übersicht · Mods · Resourcepacks · Shader · Welten · Screenshots · Logs · Einstellungen*. Mod-Liste mit Icon, Name, Version, Quelle-Badge, Toggle, Update-Pfeil.
6. **Browse**: Quelle-Umschalter Modrinth/CurseForge, Typ-Tabs (Mods/Packs/Shader/Modpacks), Filter-Sidebar, Ergebnis-Karten mit „Installieren in ▸ Instanz“.
7. **Downloads-Drawer** (unten ausfahrbar): Queue mit Fortschrittsbalken.
8. **Account-Switcher**: Popover mit allen Accounts + „Account hinzufügen“.
9. **Laufendes Spiel**: PLAY wird zu „Läuft · 00:42“ + Stop; Log-Konsole mit Level-Filter.

### 5.3 In-Game-Screens
1. **Title Screen**: Panorama-/Video-Hintergrund mit Blur, Logo mittig, vertikale Button-Liste (Einzelspieler, Mehrspieler, DaClient-Menü, Optionen, Beenden), unten links Account + Version, unten rechts Server-Favoriten.
2. **ClickGUI / Mod-Menü**: zentriertes Glas-Panel, links Kategorien (Alle, HUD, Render, QoL, Performance, Profile), oben Suche, rechts Grid mit Modul-Karten (Icon, Name, Toggle, ⚙). Klick auf ⚙ öffnet Einstellungs-Ansicht im selben Panel.
3. **HUD-Editor**: Spielwelt abgedunkelt, alle HUD-Elemente mit gestricheltem Rahmen, Hilfslinien beim Snapping, Rechtsklick = Element-Einstellungen.

---

## 6. Meilensteine

| # | Meilenstein | Ergebnis |
|---|---|---|
| **M0** | Setup & Vorarbeiten | Rust installiert, Repo + Monorepo-Struktur, Azure-App + Mojang-Freigabe beantragt, CurseForge-API-Key beantragt, CI (GitHub Actions) |
| **M1** | Vanilla-Start | MS-Login funktioniert, Vanilla-Version herunterladen + starten, Log-Konsole |
| **M2** | Instanzen & Loader | Instanz-CRUD, Fabric/Quilt, NeoForge/Forge, Java-Manager, Einstellungen pro Instanz |
| **M3** | Launcher-UI v1 | Design-System, alle Screens aus 5.2, Downloads-Drawer, Account-Switcher |
| **M4** | Modrinth | Suche, Installation, Abhängigkeiten, Mod-Liste, Updates, `.mrpack` Import/Export |
| **M5** | CurseForge | Suche/Installation, manueller Download-Fallback, CF-Modpack-Import (Backend, CLI, UI umgesetzt; Live-Test braucht API-Schlüssel) |
| **M6** | Client-Mod v1 | Title Screen, ClickGUI, HUD-Editor, Font-Renderer, ~15 Module, Auto-Install durch Launcher (Stand: Title Screen, ClickGUI, HUD-Editor, 15 Module und Auto-Install umgesetzt; offen: Sora-Font, Freelook mit Server-Opt-out, Profile) |
| **M7** | Polish & Release | Auto-Updater, Crash-Analyse, Import aus Prism/MultiMC, i18n, Installer (MSI/NSIS), Code-Signing |
| **M8** | Erweiterungen | Multi-Version-Client-Mod, NeoForge-Port, Kosmetik-Backend, Freunde |

Reihenfolge: **Backend zuerst** – M0 → M1 → M2 → M4 → M5 (alles über das CLI testbar), danach M3 (UI) → M6 → M7. Der Client-Mod kann ab M1 parallel entwickelt werden (er braucht den Launcher nicht – lässt sich mit Fabric Loom direkt testen).

---

## 7. Risiken & rechtliche Punkte
- **Minecraft-API-Freigabe** (siehe 4.1) – größter externer Blocker.
- **Mojang EULA / Markenrichtlinien**: Minecraft-Dateien nie selbst hosten (immer von Mojang laden), „Minecraft“ nicht im Produktnamen, kein Offline-/Cracked-Login (höchstens als Dev-Option).
- **CurseForge-Bedingungen**: API-Key nicht im Klartext ausliefern ist kaum möglich → Key über eigenen kleinen Proxy oder akzeptieren, dass er im Binary steckt (wie bei Prism); Distributionsverbote respektieren.
- **Server-Regeln**: nur Features, die Lunar/Badlion auch haben; Freelook & Co. per Server-Opt-out abschaltbar. Keine Combat-Module.
- **NeoForge/Forge-Installer** ändern sich gelegentlich → Installation über den offiziellen Installer ist robuster als eigene Processor-Logik.
- **Code-Signing** unter Windows (sonst SmartScreen-Warnung) → Zertifikat einplanen.

---

## 8. Entscheidungen
1. **Ziel-Versionen des Client-Mods**: moderne Versionen: **1.21.x und die neuen 26.x-Releases** (kein 1.8.9-PvP-Fokus). Der Launcher selbst startet trotzdem alle Versionen.
2. **Name**: **DaClient**. Dunkle Farben, **lila Akzent**.
3. **Reihenfolge**: zuerst das Backend (Rust-Core + CLI), die UI danach.
4. **Plattform**: vorerst **nur Windows**.
5. Kosmetik-Backend: später (nicht v1).
