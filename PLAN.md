# SlopClient – Projektplan

Stand: 19.10.2026 · Vorgänger dieses Plans: M0–M7 (siehe Abschnitt 1).

SlopClient ist ein Minecraft-Launcher im Stil von Prism Launcher mit eigenem In-Game-Client im Stil von Lunar/Badlion. Grundsatz bleibt: **nur legitime Features** (HUD, Optik, Komfort, Kosmetik), keine Combat- oder Bewegungsvorteile.

Design-System: [launcher/DESIGN.md](launcher/DESIGN.md) (Launcher und Client-Mod teilen Tokens, Sora, Chroma-Akzent und den Shader-Hintergrund). In-Game-Mockup: [design/ingame.html](design/ingame.html).

---

## 1. Stand

### 1.1 Abgeschlossen

| # | Meilenstein | Umgesetzt |
|---|---|---|
| M0 | Setup | Monorepo (Rust-Workspace, Tauri, Gradle), Azure-App angelegt, **Mojang-Freigabe beantragt**, **CurseForge-API-Key hinterlegt** |
| M1 | Vanilla-Start | Versionsliste, Installation (Client, Libraries, Natives, Assets), Java-Runtimes von Mojang, MS-Login (Device Code → XBL → XSTS → Minecraft, Tokens per DPAPI), Start mit Live-Log |
| M2 | Instanzen & Loader | Instanz-CRUD, Fabric, Quilt, NeoForge, Forge, Spielzeit, Einstellungen pro Instanz |
| M3 | Launcher-UI | Tauri 2 + React im Cheat-Client-Look: Shader-Hintergrund, Watermark-Titelleiste, ArrayList, Instanzen, Inhalte, Browse, Einstellungen, Modals, Benachrichtigungen |
| M4 | Modrinth | Suche, Installation mit Abhängigkeiten, (De)aktivieren, Updates, Erkennung per Hash, `.mrpack` Import/Export |
| M5 | CurseForge | Suche, Installation, Updates, Fingerprint-Erkennung, CF-Modpacks, gesperrte Downloads über den Download-Ordner (live getestet) |
| M6 | Client-Mod v1 | Fabric 26.3: Titelbildschirm, ClickGUI, HUD-Editor, 17 Module, Sora-Schrift, Freelook mit Server-Opt-out (`slopclient:disable`), Config-Profile, Auto-Install durch den Launcher |
| M7 | Polish | Crash-Analyse mit Ursachen und „Mod deaktivieren“, Import aus Prism/PolyMC/MultiMC, NSIS-Installer, Launcher-Look im Client-Mod (Shader als GUI-Pipeline) |

### 1.2 Geändert gegenüber dem alten Plan
- **Code-Signing gestrichen** (zurzeit nicht nötig).
- **Auto-Updater** und **Übersetzung (i18n)** kommen später (Abschnitt 4).
- **Kosmetik** rückt nach vorn: eigene Capes inklusive animierter GIFs mit Editor.
- Neu: **eigene Icons für Instanzen**.

### 1.3 Externe Abhängigkeiten
- **Mojang-Freigabe der Azure-App**: beantragt, steht aus. Bis dahin endet der echte Login mit 403. Alles, was ein echtes Minecraft-Konto braucht (Online-Spielen, das Cosmetics-Konto in M11), lässt sich vorher nur mit Testdaten entwickeln.
- **CurseForge-API-Key**: vorhanden.

---

## 2. Reihenfolge

```
M8  Instanz-Icons ────────────────► umgesetzt
M9  Cape-Studio (lokal) ──┐   (M9 umgesetzt)
M10 Capes im Client-Mod ──┴──────► umgesetzt: eigene Cape im Spiel sichtbar (nur für dich)
M11 Cosmetics-Konto & Backend ───► umgesetzt; Deployment auf dem VPS steht aus
M12 Backlog & Release-Vorbereitung ► umgesetzt
M13 Schnelle Gewinne ─────────────► umgesetzt: Discord-Status, Server direkt starten, Skins, HUD-Lücken
M14 Optik & Komfort im Spiel ────► Minimap, Wegpunkte, Animationen, Blur
M15 Launcher-Ausbau ──────────────► Auto-Updater, Konten, Mod-Updates, Config-Sync
M16 Social ───────────────────────► Freunde, Einladungen, Nachrichten, Emotes
```

Begründung: M8 ist klein und sofort spürbar. M9 und M10 liefern den sichtbaren Kern (Cape erstellen, im Spiel tragen), ohne auf Server, Hosting oder die Mojang-Freigabe zu warten. M11 ist der teure Teil (Betrieb, Moderation, Recht) und kommt, wenn der Rest steht und die Freigabe da ist.

---

## 3. Neue Meilensteine

### M8 · Eigene Instanz-Icons · umgesetzt

**Ziel:** Jede Instanz kann ein eigenes Bild haben. Ohne eigenes Bild bleibt die bisherige Farbkachel mit Initialen.

**Datenmodell**
- `instance.json` bekommt `icon`: `null` (Farbkachel) oder `{ "file": "icon.png" }`.
- Das Bild liegt als `instances/<id>/icon.png`, 256 × 256 px, ohne Metadaten. Der Rust-Core dekodiert jedes Bild und schreibt es neu (Crate `image`). Er speichert nie die Originaldatei, das schützt auch vor kaputten oder präparierten Dateien.

**Quellen**
1. **Eigenes Bild** (PNG, JPG, WebP, GIF: erstes Bild) über Dateidialog oder Drag & Drop auf die Instanz.
2. **Zuschnitt-Dialog** im Launcher: quadratischer Ausschnitt, Zoom per Mausrad, Verschieben per Ziehen, runde und eckige Vorschau in den echten Größen (Strip, Liste, Kopfzeile).
3. **Automatisch beim Import**: Modrinth- und CurseForge-Modpacks übernehmen das Projekt-Icon, Prism/MultiMC-Instanzen ihr eigenes Icon (`iconKey` aus `instance.cfg`, Datei aus `icons/` bzw. `minecraft/icon.png`).
4. **Zurücksetzen** auf die Farbkachel.

**Anzeige:** überall, wo heute die Kachel steht (Start-Strip, Instanzliste, Instanz-Kopfzeile, Browse-Ziel, Import-Dialog). Der Client-Mod zeigt das Icon im Titelbildschirm neben dem Instanznamen (Pfad per `-Dslopclient.icon`).

**Technik**
- Tauri-Commands `set_instance_icon(id, path, crop)` / `clear_instance_icon(id)`. Der Zuschnitt wird als Rechteck übergeben, Rust schneidet und skaliert.
- Bilder per Tauri-Asset-Protokoll laden, Scope nur auf `instances/*/icon.png`. Ein Zeitstempel im URL-Parameter verhindert veraltete Caches.
- CLI: `slopclient instance icon <id> <bild> | --clear`.

**Fertig, wenn:** Icon setzen, zuschneiden, zurücksetzen und importieren in allen Ansichten funktioniert und Export/Import als `.mrpack` das Icon nicht verliert (es geht als `overrides/icon.png` mit).

---

### M9 · Cape-Studio im Launcher · umgesetzt

**Ziel:** Aus einem Bild oder GIF eine eigene (auch animierte) Cape machen, mit Editor und 3D-Vorschau.

**Ort:** neue Seite **„Kosmetik“** in der Navigationsleiste. Gebaut wird im Launcher, weil Upload, Zuschnitt und Vorschau dort deutlich besser bedienbar sind als im Spiel. Der Client-Mod zeigt das Ergebnis.

**Ablauf**
1. **Hochladen**: GIF, PNG, APNG, WebP, JPG per Dialog oder Drag & Drop. Animierte Dateien werden in Einzelbilder zerlegt (WebCodecs `ImageDecoder`, das WebView2 mitbringt; Fallback `gifuct-js`), in einem Web Worker, damit die UI flüssig bleibt.
2. **Editor** öffnet sich automatisch:
   - **Rahmen der Cape-Vorderseite** im Seitenverhältnis 10 : 16. Das Bild lässt sich darin verschieben, zoomen und drehen, mit den Modi „Füllen“ und „Einpassen“.
   - **Rückseite**: gespiegelt, eigenes Bild oder Farbe.
   - **Ränder und Seiten**: automatisch aus der Bildkante oder als wählbare Farbe.
   - **Elytra**: Mitnutzen (Standard) oder eigener Ausschnitt.
   - **Animation**: Start- und Endbild kürzen, Geschwindigkeit (0,25× bis 4×), Schleife, Anzahl der Bilder mit Live-Anzeige der Dateigröße.
   - **Auflösung**: Standard 64 × 32 oder HD 128/256/512 × 256 (Vorschau zeigt, wie scharf es im Spiel wird).
3. **3D-Vorschau** mit `skinview3d` (MIT, three.js): dein Skin mit der Cape, drehbar, Lauf- und Fluganimation, Umschalter Cape/Elytra, Animation läuft live.
4. **Speichern** in die lokale Cape-Bibliothek (mehrere Capes, eine davon aktiv).

**Export-Format** (gemeinsam für Mod und Backend)
- `cape.png`: Spritesheet, alle Bilder untereinander im Minecraft-Cape-Layout (64 × 32 je Bild, bei HD entsprechend größer).
- `cape.json`: `{ "version": 1, "frames": 12, "frameTimeMs": 80, "scale": 4, "elytra": true }`.
- Grenzen (Schutz für Server und Grafikkarte, keine Stil-Grenzen): höchstens 128 Bilder, mindestens 40 ms pro Bild, Datei höchstens 8 MB, bis 1024 × 512 pro Bild, Spritesheet höchstens 32 768 px hoch (bei 1024 × 512 also 64 Bilder; mehr Bilder werden gleichmäßig ausgedünnt).

**Ablage:** `%APPDATA%/SlopClient/cosmetics/capes/<id>/` mit `cape.png`, `cape.json` und `source.*` (Original, nur lokal, damit man später weiter bearbeiten kann). Die aktive Cape steht in `settings.json`.

**Fertig, wenn:** ein beliebiges GIF in unter einer Minute zu einer sauber sitzenden, animierten Cape wird und die 3D-Vorschau dem entspricht, was das Spiel zeigt.

---

### M10 · Capes im Client-Mod · umgesetzt (für den eigenen Spieler; Capes anderer folgen mit M11)

**Ziel:** Die aktive Cape im Spiel tragen, animiert, auch als Elytra.

- **Quelle (zunächst lokal):** Der Launcher kopiert vor dem Start die aktive Cape nach `minecraft/config/slopclient/cosmetics/` (wie bei der Mod selbst). Ab M11 kommen Capes anderer Spieler vom Backend dazu.
- **Rendern:** Das Spritesheet wird als eine Textur geladen. Über Mixins in `CapeLayer` und `WingsLayer` (beide in 26.3 vorhanden) zeigt die Textur pro Tick das passende Bild (UV-Versatz oder Wechsel einer `DynamicTexture`). HD-Capes nutzen die volle Auflösung.
- **Reihenfolge:** SlopClient-Cape vor Mojang-Cape (umschaltbar). Ohne SlopClient-Cape bleibt alles wie in Vanilla.
- **ClickGUI-Bereich „Kosmetik“:**
  - eigene Capes wählen
  - Vorschau am eigenen Spielermodell
  - Schalter „Capes anderer anzeigen“ und „Animationen“
  - Elytra an/aus
- **Server-Opt-out:** Die Modul-ID `capes` lässt sich über `slopclient:disable` abschalten (nur Optik, aber Server sollen es entscheiden können).
- **Selbsttest** um eine Aufnahme der Cape am Spielermodell erweitern.

**Fertig, wenn:** die im Launcher gebaute Cape im Spiel korrekt sitzt, flüssig animiert, als Elytra funktioniert und ohne Cape keine Leistung kostet.

---

### M11 · Cosmetics-Konto & Backend · umgesetzt (Betrieb auf dem VPS steht aus)

**Ziel:** Andere SlopClient-Spieler sehen deine Cape, und du siehst ihre.

**Anmeldung ohne eigenes Passwort**
- Der Besitz des Minecraft-Kontos wird wie beim Server-Join nachgewiesen:
  1. Das Backend schickt eine Zufalls-ID.
  2. Der Launcher ruft mit dem gespeicherten Minecraft-Token `sessionserver.mojang.com/session/minecraft/join` auf.
  3. Das Backend prüft das mit `hasJoined`.
- Danach stellt das Backend ein eigenes, kurzlebiges Token aus (im Launcher per DPAPI gespeichert).
- **Braucht die Mojang-Freigabe** (echte Tokens). Bis dahin: lokaler Testserver mit Test-Konten.

**Dienst**
- Kleiner Rust-Dienst (axum) im selben Workspace (`crates/slopclient-cosmetics`), damit Formatprüfung und Typen mit dem Launcher geteilt werden.
- Datenbank (SQLite für den Start, später Postgres).
- Bilder in einem S3-kompatiblen Speicher hinter einem CDN.

**API (v1)**
- `POST /v1/auth/challenge`, `POST /v1/auth/verify`
- `GET /v1/cosmetics/{uuid}`, `POST /v1/cosmetics/lookup`: bis zu 100 UUIDs auf einmal für alle Spieler in Sichtweite; Antworten werden lange gecacht.
- `PUT /v1/capes` (Spritesheet + `cape.json`), `GET /v1/capes/mine`, `DELETE /v1/capes/{id}`, `PUT /v1/capes/active`
- `POST /v1/reports` (Cape melden)
- `DELETE /v1/account` (alle Daten löschen)

**Prüfung beim Hochladen**
- Das Backend dekodiert jede Datei und schreibt sie neu.
- Es prüft die Grenzen aus M9 und Hash-Sperrlisten.
- Es setzt eine Ratenbegrenzung.

**Moderation**
- Melden im Launcher und im Spiel.
- Eine Prüfliste für Admins; gemeldete Capes sind bis zur Prüfung für andere ausgeblendet.
- Der Upload ist sofort für dich selbst sichtbar. Für andere wird er sichtbar nach automatischer Prüfung (Variante A) oder erst nach Freigabe (Variante B) → offene Frage 2.

**Datenschutz**
- Gespeichert werden nur UUID, Name und Capes.
- Datenschutzerklärung, Löschfunktion, keine IP-Speicherung über das Nötige hinaus.

**Client-Mod**
- Lookup gebündelt für Spieler in Sichtweite, mit Cache (Ablaufzeit) und Texturgrenze.
- Capes anderer werden wieder entladen, wenn sie außer Sicht sind.

**Fertig, wenn:** zwei Rechner mit SlopClient sich gegenseitig mit ihren Capes sehen, Melden funktioniert und das Konto löschbar ist.

---

### M12 · Backlog & Release-Vorbereitung · umgesetzt (Website-Download steht aus)

Offene Punkte aus dem alten Feature-Umfang, nach Nutzen sortiert:

**Launcher** · umgesetzt
- Instanzen kopieren und gruppieren
- lokale Mods, Resourcepacks und Shader per Drag & Drop oder Dateiauswahl
- Datapacks pro Welt
- Tabs für Welten (Backup als ZIP, Löschen) und Screenshots
- Pre-/Post-Launch-Befehle
- Log- und Crash-Upload zu mclo.gs (ohne Token und Benutzernamen)

**Client-Mod: Module** · umgesetzt
- Block-Umriss (Farbe, Chroma, Deckkraft, Linienstärke), Hit-Color, Low Fire, eigener Nametag
- HUD: Server-IP und Geschwindigkeit
- Chat (Uhrzeit, gebündelte Wiederholungen, längerer Verlauf), Scoreboard (Größe, Zahlen aus), Screenshot-Vorschau nach F2 (die Galerie ist der Screenshots-Tab im Launcher)
- Zusätzlich: Karambit statt Netherite-Schwert (3D-Modell, Rückwärtsgriff, Zieh- und Inspect-Animation, Punchy- und Resourcepack-fest)

**Performance-Paket** · umgesetzt
- Sodium, Lithium, FerriteCore und Iris beim Anlegen einer Instanz oder nachträglich im Inhalte-Tab (CLI: `slopclient content preset`)

**Release**
- Version 0.2.0 für Launcher und Mod, `CHANGELOG.md` · umgesetzt
- Download-Link auf der Website · offen (Website-Dateien pflegst du selbst)

---

---

## 3b. Feature-Liste aus dem Vergleich mit anderen Clients (Stand 21.10.2026)

Verglichen mit Lunar, Badlion, Feather (inzwischen „Dawn“), LabyMod und Essential auf der Client-Seite sowie mit Prism Launcher und der Modrinth App auf der Launcher-Seite. Aufgenommen ist nur, was SlopClient noch fehlt und was zum Grundsatz passt: **keine Combat- oder Bewegungsvorteile**. Bei allem, was Informationen über das Spiel hinaus zeigt (Minimap, Reach-Anzeige), gilt wie bei Freelook der Server-Opt-out `slopclient:disable`.

Schon vorhanden und daher nicht aufgeführt: FPS, CPS, Ping, Koordinaten, Uhrzeit, Rüstung, Trankeffekte, Keystrokes, Server-IP, Geschwindigkeit, Zoom, Freelook, Toggle Sprint, Helligkeit, Fadenkreuz, Block-Umriss, Hit-Color, Low Fire, Nametags, Chat, Scoreboard, Capes, Karambit, Schriften, Screenshot-Vorschau.

### M13 · Schnelle Gewinne · umgesetzt (Skin-Upload und Spieltest mit echtem Account offen)
Kleiner Aufwand, aber jeder große Client hat es.

**Launcher**
- **Discord Rich Presence**: „Spielt Instanz X · Server Y“ aus dem Launcher, den Server meldet der Mod per Log-Zeile oder IPC. Ein- und ausschaltbar.
- **Server direkt starten**: Serverliste der Instanz mit Icon, MOTD und Ping im Launcher, „Beitreten“ über Quick Play (`--quickPlayMultiplayer`). Dasselbe für Einzelspielerwelten (`--quickPlaySingleplayer`) im Welten-Tab.
- **Skin-Verwaltung**: Skin hochladen (Mojang-API, Classic/Slim), eigene Skin-Bibliothek, 3D-Vorschau mit dem vorhandenen skinview3d.
- **Alle Mods aktualisieren** mit einem Klick, mit Changelog-Vorschau pro Mod.
- **Desktop-Verknüpfung** für eine Instanz (startet den Launcher direkt in die Instanz).

**Client-Mod: HUD**
- Kompass bzw. Richtungsanzeige (Lunar „Direction HUD“)
- Item-Zähler: Pfeile, Totems, Enderperlen, Goldäpfel, Wurftränke, Feuerwerk, Spinnweben, Erfahrungsfläschchen (frei wählbare Items folgen)
- Combo-Zähler und Reach-Anzeige (zeigt nur den Abstand des letzten eigenen Treffers, verändert nichts)
- Cooldowns: Enderperle, Chorusfrucht, Schild, Goldapfel als Leiste mit Restzeit
- Bossleiste: verschieben, skalieren, ausblenden
- Speicher-/RAM-Anzeige, Biom, Tag-Zähler, Stoppuhr
- Pack-Anzeige: aktive Resourcepacks mit Icon

### M14 · Optik & Komfort im Spiel
**Darstellung**
- **Minimap mit Wegpunkten** (Lunar, Badlion, LabyMod): Höhlenmodus aus, Todespunkt als automatischer Wegpunkt, Wegpunkte pro Server und Welt, Beam in der Welt. Server-Opt-out.
- **Alte Animationen** (1.7-Optik): Schwertblock-Pose, Bogen- und Angel-Haltung, Swing beim Abbauen. Nur Optik.
- **Menü-Blur** und **Motion Blur** (Shader-Pipeline aus M7 wiederverwenden)
- **Partikel**: Menge pro Art, Krit-Partikel immer zeigen, Explosionspartikel reduzieren
- **Hitboxen** mit eigenen Farben und Sichtlinie (wie F3+B, nur schöner), **Chunk-Grenzen**
- **Item-Physik** bzw. 2D-Items für gedroppte Items
- **3D-Skin-Layer** (zweite Skin-Ebene als Voxel, wie „3D Skins“)
- **Verzauberungsglanz**: Farbe und Stärke wählbar
- **Wetter und Uhrzeit** nur clientseitig festlegen
- **Kamera**: Hurt-Cam aus oder abgeschwächt, Bildschirmwackeln aus, eigene FOV-Effekte

**Komfort**
- **Pack-Organizer**: Resourcepacks im Spiel mit Suche, Ordnern und Favoriten
- **Text-Hotkeys** (z. B. „/l“, „gg“) pro Server, mit Hinweis, dass manche Server Makros verbieten
- **Auto-Reconnect** und **Schnellwechsel** zwischen Servern aus dem Pausemenü
- **Bessere Sounds**: Lautstärke pro Kategorie im Pausemenü, eigener Treffer-Sound
- **Screenshot-Galerie im Spiel** mit Teilen (Upload als Link über das Cosmetics-Backend)

**Kosmetik** (baut auf M9–M11 auf)
- Cape-Physik im eigenen Cape-Renderer (wehend wie WaveyCapes, ohne Zusatzmod)
- Flügel, Hüte, Begleiter (Pets), Bandanas: im Cape-Studio gestaltet, über das Backend für andere sichtbar
- Weitere Messer-Skins für den Karambit (Fade, Doppler usw.) und andere Waffen-Modelle

### M15 · Launcher-Ausbau
- **Auto-Updater** (aus Abschnitt 4 vorgezogen): Tauri Updater mit signierten Paketen, Update-Hinweis im Launcher, der Client-Mod aktualisiert sich mit.
- **Mehrere Konten** mit Schnellwechsel in der Titelleiste und Konto pro Instanz
- **Modpacks durchsuchen** (Modrinth und CurseForge) mit Installation als neue Instanz und Updates, die Configs behalten
- **Config-Sync über das SlopClient-Konto**: Module, HUD-Layout, Keybinds und Profile auf jedem PC gleich
- **Mod-Konflikte und fehlende Abhängigkeiten** vor dem Start melden (nicht erst im Crash-Report)
- **JVM-Voreinstellungen**: RAM-Empfehlung aus dem Arbeitsspeicher, Flag-Profile (z. B. Aikar, ZGC), gemeinsame Java-Versionen
- **Neuigkeiten** auf der Startseite: SlopClient-Changelog und Minecraft-Snapshots
- **Instanz-Export** als `.mrpack` mit gewählten Configs (Dialog aus „Einstellungen übernehmen“ wiederverwenden)
- **Weitere Quellen** beim Import: ATLauncher, FTB-App, CurseForge-App, Modrinth App

### M16 · Social (Essential, LabyMod)
- **Freundesliste** mit Online-Status und aktuellem Server, im Launcher und im Spiel
- **Einladungen**: Freund auf den eigenen Server holen (Quick Play über den Launcher des Freundes)
- **Direktnachrichten** und Gruppen-Chats, im Spiel als Overlay
- **Emotes** (Animationen am Spielermodell, nur für SlopClient-Nutzer sichtbar)
- **Welt mit Freunden teilen** (wie Essential per Peer-to-Peer): teuer (Relay-Server, NAT), daher zuletzt und nur, wenn der Rest steht

Rechtliches für M16: Chat und Bilder brauchen Meldefunktion, Blockieren und Moderation (siehe Abschnitt 6), außerdem Datenschutzhinweise für Online-Status und Nachrichten.

## 4. Später

- ~~Auto-Updater~~ → M15
- **Übersetzung** Deutsch/Englisch für Launcher und Mod
- ~~Weitere Kosmetik~~ → M14, Emotes → M16
- ~~Freundesliste~~ → M16
- **Weitere Minecraft-Versionen** des Client-Mods (1.21.x), NeoForge-Port

---

## 5. Architektur (aktuell)

```
davinci client/
├─ crates/
│  ├─ slopclient-core/        auth, install, launch, content (Modrinth, CurseForge, mrpack, cfpack),
│  │                        client_mod, crash, foreign (Prism/MultiMC), instance, java, meta
│  ├─ slopclient-cli/         Test-CLI für alles im Core
│  └─ slopclient-cosmetics/   (M11) Backend-Dienst
├─ launcher/                Tauri 2 + React 19 + Tailwind v4 + motion
│  ├─ src/pages/            Home, Instances, InstanceDetail, Browse, Settings, (M9) Cosmetics
│  ├─ src/ui/               kit, Shell, Shader, Modals, (M8) IconCropper, (M9) CapeEditor
│  └─ src-tauri/            Commands + Events über slopclient-core
├─ client-mod/              Fabric 26.3 (Loom 1.18, Java 25)
│  └─ src/client/java/dev/slopclient/
│     ├─ gui/               Titelbildschirm, ClickGUI, HUD-Editor, Chrome
│     ├─ module/ hud/ setting/
│     ├─ render/            Draw, Theme, MenuShader, (M10) CapeTextures
│     └─ mixin/
├─ design/                  In-Game-Mockup
└─ web/                     Website
```

Datenablage `%APPDATA%/SlopClient/` (für Tests aus Claude heraus: `SLOPCLIENT_HOME` außerhalb von AppData):

```
settings.json, accounts.json, secrets.dat (DPAPI)
instances/<id>/instance.json, icon.png (M8), content.lock.json, blocked.json, minecraft/
cosmetics/capes/<id>/cape.png, cape.json, source.* (M9)
libraries/, assets/, versions/, java/, cache/
```

Format-Vertrag Launcher ↔ Mod ↔ Backend: `cape.png` + `cape.json` (M9). Er wird einmal als Rust-Typ mit Prüfung definiert und vom Launcher, der CLI und dem Backend gemeinsam genutzt. Der Mod liest dasselbe JSON.

---

## 6. Risiken & rechtliche Punkte

- **Nutzerinhalte** (M11): hochgeladene GIFs können anstößig oder urheberrechtlich geschützt sein. Nötig sind Nutzungsbedingungen, Melden, Entfernen auf Hinweis und eine Kontaktadresse. Ohne Moderation nicht für andere sichtbar schalten.
- **Mojang-Richtlinien**: Capes sind rein kosmetisch und nur für SlopClient-Nutzer sichtbar. Offizielle Mojang- oder Minecon-Capes werden nicht nachgebildet, und Capes werden nicht verkauft, solange das nicht geprüft ist.
- **Betrieb & Kosten** (M11): Server, Speicher, CDN, Backups. Der Start ist klein und günstig möglich, braucht aber jemanden, der den Dienst betreibt.
- **Datenschutz (DSGVO)**: Datenschutzerklärung, Impressum für die Website/den Dienst, Löschrecht.
- **Mojang-Freigabe** blockiert weiterhin echtes Online-Spielen und das Cosmetics-Konto.
- **Leistung**: viele animierte HD-Capes auf vollen Servern → Texturgrenzen, Entladen außer Sicht, Animationen abschaltbar.
- **Server-Regeln**: nur Optik und Komfort, Opt-out über `slopclient:disable` bleibt für alle Module.

---

## 7. Offene Fragen

1. ~~Hosting für M11~~ → entschieden: VPS (Anleitung in deploy/cosmetics).
2. ~~Freigabe~~ → entschieden: automatisch; Melden und Ausblenden über die Admin-Schnittstelle.
3. ~~HD-Grenze~~ → entschieden: keine Stil-Grenze, bis 1024 × 512; nur technische Schutzgrenzen (8 MB, 128 Bilder, 32 768 px Höhe).
4. **Elytra**: soll die Cape standardmäßig auch als Elytra erscheinen?

---

## 8. Entscheidungen

1. **Ziel-Versionen des Client-Mods**: moderne Versionen (26.x, später 1.21.x). Der Launcher startet weiterhin alle Versionen.
2. **Name**: SlopClient, dunkle Farben, lila Akzent, Sora.
3. **Plattform**: vorerst nur Windows.
4. **Code-Signing**: nicht nötig, gestrichen.
5. **Auto-Updater, Übersetzung**: später.
6. **Kosmetik**: als Nächstes, beginnend mit selbst erstellten Capes (auch aus GIFs), erst lokal, dann mit Backend.
