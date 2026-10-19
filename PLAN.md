# DaClient – Projektplan

Stand: 19.10.2026 · Vorgänger dieses Plans: M0–M7 (siehe Abschnitt 1).

DaClient ist ein Minecraft-Launcher im Stil von Prism Launcher mit eigenem In-Game-Client im Stil von Lunar/Badlion. Grundsatz bleibt: **nur legitime Features** (HUD, Optik, Komfort, Kosmetik), keine Combat- oder Bewegungsvorteile.

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
| M6 | Client-Mod v1 | Fabric 26.3: Titelbildschirm, ClickGUI, HUD-Editor, 17 Module, Sora-Schrift, Freelook mit Server-Opt-out (`daclient:disable`), Config-Profile, Auto-Install durch den Launcher |
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
M9  Cape-Studio (lokal) ──┐
M10 Capes im Client-Mod ──┴──────► eigene Cape sofort im Spiel sichtbar (nur für dich)
M11 Cosmetics-Konto & Backend ───► andere DaClient-Spieler sehen deine Cape
M12 Backlog & Release-Vorbereitung
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

**Anzeige:** überall, wo heute die Kachel steht (Start-Strip, Instanzliste, Instanz-Kopfzeile, Browse-Ziel, Import-Dialog). Der Client-Mod zeigt das Icon im Titelbildschirm neben dem Instanznamen (Pfad per `-Ddaclient.icon`).

**Technik**
- Tauri-Commands `set_instance_icon(id, path, crop)` / `clear_instance_icon(id)`. Der Zuschnitt wird als Rechteck übergeben, Rust schneidet und skaliert.
- Bilder per Tauri-Asset-Protokoll laden, Scope nur auf `instances/*/icon.png`. Ein Zeitstempel im URL-Parameter verhindert veraltete Caches.
- CLI: `daclient instance icon <id> <bild> | --clear`.

**Fertig, wenn:** Icon setzen, zuschneiden, zurücksetzen und importieren in allen Ansichten funktioniert und Export/Import als `.mrpack` das Icon nicht verliert (es geht als `overrides/icon.png` mit).

---

### M9 · Cape-Studio im Launcher

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
- Grenzen: höchstens 64 Bilder, mindestens 40 ms pro Bild, Datei höchstens 2 MB, höchstens 512 × 256 pro Bild. Der Editor zeigt die Grenzen an, bevor man sie reißt.

**Ablage:** `%APPDATA%/DaClient/cosmetics/capes/<id>/` mit `cape.png`, `cape.json` und `source.*` (Original, nur lokal, damit man später weiter bearbeiten kann). Die aktive Cape steht in `settings.json`.

**Fertig, wenn:** ein beliebiges GIF in unter einer Minute zu einer sauber sitzenden, animierten Cape wird und die 3D-Vorschau dem entspricht, was das Spiel zeigt.

---

### M10 · Capes im Client-Mod

**Ziel:** Die aktive Cape im Spiel tragen, animiert, auch als Elytra.

- **Quelle (zunächst lokal):** Der Launcher kopiert vor dem Start die aktive Cape nach `minecraft/config/daclient/cosmetics/` (wie bei der Mod selbst). Ab M11 kommen Capes anderer Spieler vom Backend dazu.
- **Rendern:** Das Spritesheet wird als eine Textur geladen. Über Mixins in `CapeLayer` und `WingsLayer` (beide in 26.3 vorhanden) zeigt die Textur pro Tick das passende Bild (UV-Versatz oder Wechsel einer `DynamicTexture`). HD-Capes nutzen die volle Auflösung.
- **Reihenfolge:** DaClient-Cape vor Mojang-Cape (umschaltbar). Ohne DaClient-Cape bleibt alles wie in Vanilla.
- **ClickGUI-Bereich „Kosmetik“:**
  - eigene Capes wählen
  - Vorschau am eigenen Spielermodell
  - Schalter „Capes anderer anzeigen“ und „Animationen“
  - Elytra an/aus
- **Server-Opt-out:** Die Modul-ID `capes` lässt sich über `daclient:disable` abschalten (nur Optik, aber Server sollen es entscheiden können).
- **Selbsttest** um eine Aufnahme der Cape am Spielermodell erweitern.

**Fertig, wenn:** die im Launcher gebaute Cape im Spiel korrekt sitzt, flüssig animiert, als Elytra funktioniert und ohne Cape keine Leistung kostet.

---

### M11 · Cosmetics-Konto & Backend

**Ziel:** Andere DaClient-Spieler sehen deine Cape, und du siehst ihre.

**Anmeldung ohne eigenes Passwort**
- Der Besitz des Minecraft-Kontos wird wie beim Server-Join nachgewiesen:
  1. Das Backend schickt eine Zufalls-ID.
  2. Der Launcher ruft mit dem gespeicherten Minecraft-Token `sessionserver.mojang.com/session/minecraft/join` auf.
  3. Das Backend prüft das mit `hasJoined`.
- Danach stellt das Backend ein eigenes, kurzlebiges Token aus (im Launcher per DPAPI gespeichert).
- **Braucht die Mojang-Freigabe** (echte Tokens). Bis dahin: lokaler Testserver mit Test-Konten.

**Dienst**
- Kleiner Rust-Dienst (axum) im selben Workspace (`crates/daclient-cosmetics`), damit Formatprüfung und Typen mit dem Launcher geteilt werden.
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

**Fertig, wenn:** zwei Rechner mit DaClient sich gegenseitig mit ihren Capes sehen, Melden funktioniert und das Konto löschbar ist.

---

### M12 · Backlog & Release-Vorbereitung

Offene Punkte aus dem alten Feature-Umfang, nach Nutzen sortiert:

**Launcher**
- Instanzen kopieren und gruppieren
- lokale Mods per Drag & Drop
- Datapacks
- Tabs für Welten und Screenshots
- Pre-/Post-Launch-Befehle
- Crash-Log-Upload zu mclo.gs

**Client-Mod: Module**
- Block-Umriss, Hit-Color, Low Fire, Nametags
- Server-IP- und Geschwindigkeits-Anzeige
- Chat-Verbesserungen, Scoreboard-Anpassung, Screenshot-Manager

**Client-Mod: Performance-Preset**
- Sodium, Lithium, FerriteCore und Iris auf Wunsch über Modrinth mitinstallieren

**Release**
- Versionsnummern, Changelog, Website-Download

---

## 4. Später

- **Auto-Updater** (Tauri Updater mit signierten Update-Paketen; Signaturschlüssel ≠ Code-Signing-Zertifikat)
- **Übersetzung** Deutsch/Englisch für Launcher und Mod
- **Weitere Kosmetik**: Flügel, Hüte, Emotes (baut auf M9–M11 auf)
- **Freundesliste**, Online-Status, Einladungen (nutzt das Konto aus M11)
- **Weitere Minecraft-Versionen** des Client-Mods (1.21.x), NeoForge-Port

---

## 5. Architektur (aktuell)

```
davinci client/
├─ crates/
│  ├─ daclient-core/        auth, install, launch, content (Modrinth, CurseForge, mrpack, cfpack),
│  │                        client_mod, crash, foreign (Prism/MultiMC), instance, java, meta
│  ├─ daclient-cli/         Test-CLI für alles im Core
│  └─ daclient-cosmetics/   (M11) Backend-Dienst
├─ launcher/                Tauri 2 + React 19 + Tailwind v4 + motion
│  ├─ src/pages/            Home, Instances, InstanceDetail, Browse, Settings, (M9) Cosmetics
│  ├─ src/ui/               kit, Shell, Shader, Modals, (M8) IconCropper, (M9) CapeEditor
│  └─ src-tauri/            Commands + Events über daclient-core
├─ client-mod/              Fabric 26.3 (Loom 1.18, Java 25)
│  └─ src/client/java/dev/daclient/
│     ├─ gui/               Titelbildschirm, ClickGUI, HUD-Editor, Chrome
│     ├─ module/ hud/ setting/
│     ├─ render/            Draw, Theme, MenuShader, (M10) CapeTextures
│     └─ mixin/
├─ design/                  In-Game-Mockup
└─ web/                     Website
```

Datenablage `%APPDATA%/DaClient/` (für Tests aus Claude heraus: `DACLIENT_HOME` außerhalb von AppData):

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
- **Mojang-Richtlinien**: Capes sind rein kosmetisch und nur für DaClient-Nutzer sichtbar. Offizielle Mojang- oder Minecon-Capes werden nicht nachgebildet, und Capes werden nicht verkauft, solange das nicht geprüft ist.
- **Betrieb & Kosten** (M11): Server, Speicher, CDN, Backups. Der Start ist klein und günstig möglich, braucht aber jemanden, der den Dienst betreibt.
- **Datenschutz (DSGVO)**: Datenschutzerklärung, Impressum für die Website/den Dienst, Löschrecht.
- **Mojang-Freigabe** blockiert weiterhin echtes Online-Spielen und das Cosmetics-Konto.
- **Leistung**: viele animierte HD-Capes auf vollen Servern → Texturgrenzen, Entladen außer Sicht, Animationen abschaltbar.
- **Server-Regeln**: nur Optik und Komfort, Opt-out über `daclient:disable` bleibt für alle Module.

---

## 7. Offene Fragen

1. **Hosting für M11**: eigener kleiner Server (VPS) mit dem Rust-Dienst, oder ein Serverless-Anbieter? Empfehlung: VPS + S3-kompatibler Speicher, weil Code und Formatprüfung mit dem Launcher geteilt werden.
2. **Freigabe hochgeladener Capes**: sofort für alle sichtbar nach automatischer Prüfung, oder erst nach manueller Freigabe? Empfehlung zum Start: manuelle Freigabe, solange es wenige Nutzer sind.
3. **HD-Grenze**: reicht 512 × 256 pro Bild, oder bewusst kleiner (256 × 128), um Speicher und Leistung zu schonen?
4. **Elytra**: soll die Cape standardmäßig auch als Elytra erscheinen?

---

## 8. Entscheidungen

1. **Ziel-Versionen des Client-Mods**: moderne Versionen (26.x, später 1.21.x). Der Launcher startet weiterhin alle Versionen.
2. **Name**: DaClient, dunkle Farben, lila Akzent, Sora.
3. **Plattform**: vorerst nur Windows.
4. **Code-Signing**: nicht nötig, gestrichen.
5. **Auto-Updater, Übersetzung**: später.
6. **Kosmetik**: als Nächstes, beginnend mit selbst erstellten Capes (auch aus GIFs), erst lokal, dann mit Backend.
