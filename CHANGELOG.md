# Changelog

Alle nennenswerten Änderungen an SlopClient (Launcher und Client-Mod). Die Versionen gelten für beide zusammen.

## 0.4.1

### Client-Mod
- Capes auf Elytren sahen aus wie zwei Platten; jetzt haben sie die Flügelform der Elytra (auch bei vorhandenen Capes und denen anderer Spieler).

### Cosmetics-Server
- **GIFs als Cape im Webinterface**: Bilder und Animationen (GIF, WebP, APNG, PNG, JPG) werden wie im Cape-Studio des Launchers auf die Cape gelegt, mit Zoom, Ausschnitt, Rückseite, Auflösung und Tempo und live in der 3D-Vorschau. Fertige Cape-Texturen werden weiter unverändert übernommen.

## 0.4.0

### Launcher
- **Mod-Loader aktualisieren**: In den Instanz-Einstellungen lassen sich Loader und Version bestehender Instanzen ändern (z. B. Fabric 0.19.5 → 0.20.1); gibt es eine neuere stabile Version, zeigt die Instanz einen Hinweis mit „Aktualisieren“. Mods, Welten und Einstellungen bleiben.

### Cosmetics-Server
- **Sicherheit** nach einer Prüfung des Servers:
  - Anmeldungen sind an die Server-Adresse gebunden. Ein fremder Cosmetics-Server kann eine Anmeldung nicht mehr an den SlopClient-Server weiterreichen und so ein Konto übernehmen. **Launcher vor dieser Version können sich nicht mehr anmelden** (Capes anderer sehen geht weiter).
  - Präparierte PNGs mit riesiger angegebener Größe werden vor dem Entpacken abgelehnt; höchstens zwei Bildprüfungen gleichzeitig.
  - Rate-Limits pro Client und Konto, kleine Request-Größen außer beim Upload (dort erst nach der Anmeldung), Timeouts.
  - Echte Abmeldung (auch „überall abmelden“), Web-Anmeldungen gelten 7 Tage, höchstens 20 Anmeldungen pro Konto.
  - `DEV_AUTH` startet nur noch auf localhost und wird nicht mehr aus alten `DACLIENT_*`-Variablen übernommen; Admin-Token brauchen mindestens 24 Zeichen.
  - Moderation: eine Meldung pro Spieler und Cape, Meldungen nach Cape gruppiert; ausgeblendete Bilder werden nicht mehr ausgeliefert und lassen sich nicht erneut hochladen.
  - Skin-Abruf folgt keinen Weiterleitungen mehr, engere Content-Security-Policy, `no-store` für Kontodaten.
- **Webinterface** unter der Server-Adresse: Capes hochladen, tragen, ablegen und löschen, Hut und Begleiter wählen, Konto löschen, ganz ohne Launcher. Mit **3D-Vorschau** wie im Launcher: deine Figur mit deinem Skin zeigt jede angeklickte Cape, jeden Hut und Begleiter sofort, auch eine Cape vor dem Hochladen; gehend, stehend oder mit Elytra. Anmeldung mit Microsoft per Code wie im Launcher; der Server liest damit nur den Minecraft-Account und speichert keine Microsoft-Daten. Abschaltbar mit `SLOPCLIENT_COSMETICS_WEB_LOGIN=0`.

### Kosmetik-Mod
- **Capes, Hüte und Begleiter als eigene Mod** (`slopclient-cosmetics-mod`): zeigt die SlopClient-Kosmetik auch ohne den Client, in jedem Fabric-Launcher. Einstellungen in `config/slopclient-cosmetics.json`. Der Client enthält die Mod und verhält sich wie bisher.

## 0.3.0

### Neuer Name: SlopClient
- DaClient heißt jetzt **SlopClient**: Launcher, Client-Mod, Installer und Cosmetics-Server.
- Beim ersten Start zieht der Launcher deine Daten von `%APPDATA%\DaClient` nach `%APPDATA%\SlopClient` um. Instanzen bekommen beim nächsten Start den neuen Mod; der alte `daclient`-Jar fliegt raus, Mod-Einstellungen und Tastenbelegungen werden übernommen.
- Umgebungsvariablen heißen `SLOPCLIENT_*`, die alten `DACLIENT_*` gelten weiter.
- Der Installer installiert SlopClient neben dem alten DaClient; den alten Eintrag kannst du danach in Windows deinstallieren.

### Launcher
- **Hüte & Begleiter** unter Kosmetik: sechs Hüte und 16 Begleiter mit 3D-Vorschau, in drei Arten: auf der Schulter (Mini-Me mit deinem eigenen Skin, Igel, Katze, Frosch, Pinguin, Küken, Mini-Slime), fliegend (Mini-Drache, Geist, Biene, Fledermaus, Chroma-Würfel) und laufend (Fuchs, Shiba, Panda, Schildkröte). Mit Cosmetics-Konto sehen andere Spieler sie auch.
- **Server-Tab** pro Instanz: die Mehrspieler-Liste mit Icon, MOTD, Spielerzahl und Ping (auch über SRV-Einträge). „Beitreten“ startet das Spiel direkt auf dem Server (Quick Play), Welten lassen sich mit „Direkt spielen“ ebenso öffnen.
- **Discord-Status**: welche Instanz du spielst, auf welchem Server oder im Einzelspieler und seit wann. Abschaltbar, die Serveradresse lässt sich ausblenden.
- **Skins** unter Kosmetik: Bibliothek mit 3D-Vorschau, breite oder schmale Arme (wird erkannt), mit einem Klick auf den Minecraft-Account hochladen, aktuellen Skin sichern.
- **Changelogs vor dem Update**: „Änderungen ansehen“ zeigt bei „Alle aktualisieren“ für jeden Mod, was sich ändert.
- **Desktop-Verknüpfung** pro Instanz mit ihrem Icon; ein Doppelklick startet die Instanz direkt, auch wenn der Launcher schon offen ist.
- **Eigener Cosmetics-Server** wählbar unter Einstellungen → Kosmetik online. Leer gelassen bleibt es beim SlopClient-Server.
- Seiten und Instanz-Tabs blieben im installierten Launcher manchmal unsichtbar, bis man das Fenster vergrößerte. Behoben.

### Client-Mod
- **Hüte und Begleiter** im Spiel, auch bei anderen Spielern: texturierte Modelle mit Animationen (Flügel, Schwanz, Beine). Laufende Begleiter folgen dir rechts hinter dir, stehen auf dem Boden und laufen mit; das Mini-Me sitzt mit deinem Skin auf der Schulter. Der Hut sitzt auf Wunsch über dem Helm.
- **Glow**: Spieler, die du gerade siehst, leuchten mit einem weichen Umriss (Chroma, Akzent, Teamfarbe …). Hinter Wänden leuchtet niemand. Server können das Modul abschalten.
- **Schrift überall**: Die gewählte Schrift gilt jetzt für das ganze Spiel (Chat, Menüs, Tooltips, Schilder, Nametags), abschaltbar mit „Überall im Spiel“.
- Item-Zähler im HUD-Editor des Titelbildschirms ließ das Spiel abstürzen. Behoben.
- Neue HUD-Elemente: **Kompass**, **Item-Zähler** (Pfeile, Totems, Perlen, Goldäpfel und mehr), **Combo**, **Reichweite** des letzten eigenen Treffers, **Cooldowns** mit Restzeit, **Bossleiste** (verschieben, skalieren, ausblenden), **Arbeitsspeicher**, **Biom**, **Tag**, **Stoppuhr** (eigene Taste) und **Resourcepacks** mit Icon.
- Meldet dem Launcher für den Discord-Status, ob du im Menü, im Einzelspieler oder auf einem Server bist (nur über das lokale Spiel-Log).

### Cosmetics-Server
- **Docker-Image** `ghcr.io/ydavinci/slopclient-cosmetics` mit `compose.yaml`, Anleitung in `deploy/cosmetics/`.

## 0.2.0

### Launcher
- **Instanzen kopieren** samt Mods, Welten und Einstellungen, **Gruppen** in der Instanzliste (einklappbar).
- **Lokale Dateien per Drag & Drop** oder Dateiauswahl: Mods, Resourcepacks und Shader werden am Inhalt erkannt und danach auf Modrinth bzw. CurseForge gesucht, damit sie Updates bekommen.
- **Welten-Tab**: Name, Modus, Version, Größe und Icon jeder Welt, Datapacks pro Welt (hinzufügen, an/aus, entfernen), Backup als ZIP, Löschen.
- **Screenshots-Tab** als Galerie mit Vorschaubildern.
- **Befehle vor dem Start und nach dem Beenden** mit Prisms `INST_*`-Variablen.
- **Log teilen** über mclo.gs aus dem Log-Tab und dem Crash-Dialog; Zugangsdaten und der Windows-Benutzername werden vorher entfernt.
- **Performance-Paket** (Sodium, Lithium, FerriteCore, Iris) beim Anlegen oder nachträglich mit einem Klick.
- **Cosmetics-Konto**: Anmeldung am SlopClient-Cosmetics-Server, damit andere Spieler deine Cape sehen (Server selbst gehostet, siehe `deploy/cosmetics/`).
- Eigene **Instanz-Icons** mit Zuschnitt, übernommen auch aus Modpacks und Prism.
- **Cape-Studio**: aus Bildern und GIFs eigene, auch animierte Capes erstellen, mit 3D-Vorschau.
- Offline-Testmodus für Entwickler-Builds (`--features dev-offline`), nie in öffentlichen Releases.
- Die angezeigte Version kommt jetzt aus dem Build.

### Client-Mod
- **Karambit**: Das Netherite-Schwert wird zum 3D-Karambit aus CS:GO, im Rückwärtsgriff, mit Zieh- und Inspect-Animation (V). Funktioniert auch mit Resourcepacks, die das Schwert ändern; mit Punchy behält jedes andere Item Punchys Animationen.
- **Capes** aus dem Cape-Studio im Spiel, auch animiert und als Elytra, und die Capes anderer SlopClient-Spieler.
- **Block-Umriss**: Farbe (auch Chroma), Deckkraft und Linienstärke.
- **Hit-Color**: eigene Farbe für getroffene Gegner.
- **Low Fire**: Feuer am Bildschirmrand tiefer.
- **Nametags**: eigener Name in der Außenansicht.
- **Chat**: Uhrzeit vor jeder Nachricht, Wiederholungen gebündelt („(x3)“), Verlauf bis 2000 Zeilen.
- **Scoreboard**: Größe, rote Zahlen ausblenden, ganz ausblenden.
- **Screenshots**: Vorschau nach F2, Taste zeigt den letzten Screenshot im Explorer.
- Neue HUD-Elemente **Server-IP** und **Geschwindigkeit**.
- Titelbildschirm ohne Statusleiste oben links; Serverkarten zeigen das echte Server-Icon.

## 0.1.0

### Launcher
- Instanzen mit eigener Minecraft-Version, Fabric, Quilt, NeoForge oder Forge und eigener Java-Laufzeit.
- Microsoft-Anmeldung, Mehrfach-Accounts.
- Mods, Resourcepacks und Shader von Modrinth und CurseForge mit Abhängigkeiten und Updates.
- Modpacks importieren und exportieren (`.mrpack`, CurseForge-ZIP), Import aus Prism, PolyMC und MultiMC.
- Crash-Analyse mit Ursache und direkter Lösung, Live-Log.
- Windows-Installer (NSIS).

### Client-Mod
- Eigener Titelbildschirm, ClickGUI (Rechts-Shift) und frei verschiebbares HUD.
- Module: Zoom, Helligkeit, Fadenkreuz, Toggle Sprint, Freelook (Server können es abschalten), Sora-Schrift.
- Konfigurationsprofile.
