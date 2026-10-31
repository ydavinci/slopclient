# Changelog

Alle nennenswerten Änderungen an SlopClient (Launcher und Client-Mod). Die Versionen gelten für beide zusammen.

## 0.5.6

### Launcher
- **Mod-Updates mit einem Klick**: Hat ein Mod, Resourcepack oder Shader ein Update, steht in seiner Zeile ein grüner „Update“-Knopf, der nur diesen einen Inhalt aktualisiert. Beta- und Alpha-Versionen sind gelb markiert. „Alle aktualisieren (N)“ gibt es jetzt auch in der linken Spalte.
- **Updates ohne Suchen**: Der Inhalte-Tab sucht beim Öffnen selbst nach Updates und merkt sich das Ergebnis 30 Minuten lang, auch beim Wechsel von Tab oder Instanz. Beim Start sucht der Launcher im Hintergrund für alle Instanzen nacheinander (abschaltbar unter Einstellungen → Downloads). Installiert wird nie von allein.
- **Updates in der Instanzliste**: Instanzen mit Updates zeigen „N Updates“ (ein Klick öffnet die Inhalte), auf der Startseite einen grünen Punkt am Icon.
- Solange eine Instanz läuft, sind ihre Update-Knöpfe gesperrt, weil sich geöffnete Dateien nicht ersetzen lassen.
- CLI: `slopclient content update <instanz> [namen…]` aktualisiert auf Wunsch nur die genannten Inhalte.
- **Geteilte Spieleinstellungen** (Einstellungen → Geteilte Spieleinstellungen): Instanzen in einer Gruppe teilen sich `options.txt`, `optionsof.txt` und `optionsshaders.txt`, also Grafik, Steuerung, Sound und Tastenbelegung. Eine Änderung in einer Instanz gilt sofort für alle in der Gruppe. Mehrere Gruppen sind möglich, eine Instanz ist in höchstens einer. Beim Hinzufügen wählst du, ob die Einstellungen der Gruppe oder die der Instanz gelten; die überschriebenen werden gesichert. Entfernte Instanzen behalten ihre aktuellen Einstellungen, Kopien einer Instanz sind in keiner Gruppe.
- Speichert ein Programm die Datei so, dass die Verbindung reißt, repariert der Launcher sie beim Start, vor und nach jedem Spiel und auf der Einstellungsseite (die neueste Änderung gewinnt). Wo Hardlinks nicht gehen (z. B. FAT32 oder Netzlaufwerke), wird nur zu diesen Zeitpunkten abgeglichen. Im Einstellungen-Tab einer Instanz steht, mit welcher Gruppe sie ihre Einstellungen teilt.
- **Mods nur einmal laden und speichern**: Mods, Resourcepacks und Shader liegen jetzt einmal in einem gemeinsamen Speicher, die Instanzen bekommen Hardlinks darauf. Was eine andere Instanz schon hat, wird nicht noch einmal heruntergeladen. Gilt für Installation, Updates, Modpacks, Import aus anderen Launchern, Drag & Drop und das Kopieren von Instanzen (dort werden nur Mods, Resourcepacks und Shader geteilt, Welten und Configs weiter kopiert). Updates, Deaktivieren und Entfernen in einer Instanz ändern nichts an den anderen. Abschaltbar unter Einstellungen → Downloads.
- **Doppelte Dateien zusammenführen**: Ein Knopf unter Einstellungen → Downloads macht aus Dateien, die schon vorher in mehreren Instanzen lagen, eine und zeigt, wie viel Platz frei wurde. Ungenutzte Dateien räumt der Launcher beim Start, nach Updates und nach dem Löschen einer Instanz selbst auf; „Aufräumen“ macht das sofort. Wo Hardlinks nicht gehen, bekommt die Instanz eine Kopie, der Download wird trotzdem gespart.

### Kosmetik
- **Sechs neue Begleiter**: Axolotl und Eule auf der Schulter, Phönix (ein leuchtender Feuervogel) und Qualle im Flug, Kitsune (weißer Fuchs mit drei Geisterflammen-Schweifen) und Mini-Golem (mit Mohnblume) zu Fuß.
- **Vier neue Hüte**: Zauberhut mit leuchtenden Sternen, Blütenkranz, Kopfhörer mit Chroma-Ringen und Fliegenpilz.
- **Flügel als Zubehör**: Engelsflügel und Drachenflügel.

### Client-Mod
- **Glas**: Spieler als Figuren aus Glas, jedes Körperteil eine klare Scheibe mit hellem Rand und Glanzlicht, die eigene Figur mit weißem Leuchten. Für dich, für andere Spieler oder beide.
- **Amboss-Planer**: Gegenstand aus dem Inventar oder aus allen verzauberbaren Gegenständen wählen, die gewünschten Verzauberungen anklicken (nur passende, und nur solche, die zueinander passen) und die günstigste Reihenfolge sehen, in der die Bücher untereinander und mit dem Gegenstand im Amboss kombiniert werden: mit Leveln pro Schritt, Gesamt-Level und XP. Öffnet über den Knopf „Planer“ am Amboss oder eine eigene Taste.
- **Plan anheften**: Im Amboss-Planer lässt sich eine Reihenfolge anheften; sie steht dann als Karte links neben dem Amboss.
- **Trail-Stile**: Funken, Spirale, Komet, Blüten, Seelen, Noten und Herzen.
- **Glas-Varianten**: Klar, Frost, Rauch, Bernstein, Prisma und Abgrund (ein Sternenhimmel hinter violetten Kanten). Die eigenen Arme sind in der Ich-Ansicht jetzt auch aus Glas.
- **Bibliothekar** (HUD): Schaust du auf ein Lesepult, stehen dort die Bücher des Bibliothekars daneben samt Preis. Das Spiel verrät die Angebote erst, wenn sein Handel einmal geöffnet war; das Lesepult gehört dem nächsten Bibliothekar.
- **Auto-Werkzeug**: Wechselt beim Abbauen zum besten Werkzeug für den Block (schnellstes, das auch den Drop bringt, Effizienz mitgerechnet), auf Wunsch auch aus dem Inventar, und danach zurück.

### Entfernt
- **Karambit**: Das 3D-Karambit-Modell fürs Netherite-Schwert ist vorerst raus.

## 0.5.5

### Client-Mod
- **Trail**: Eine funkelnde Spur aus winzigen leuchtenden Sternen hinter dir, in Chroma, Gold oder einer festen Farbe, mit einstellbarer Dichte. Mehr Funken beim Laufen, wenige im Stehen. Nur du siehst sie, und sie funktioniert auf jedem Server.
- **Skybox**: Der fließende Shader des Launchers als Himmel, nahtlos rundum und in deiner Akzentfarbe. Der Nebel passt sich an, Wolken lassen sich ausblenden, auf Wunsch auch im End.

## 0.5.4

### Fixes
- **Elytra zu dünn, Stück fehlte**: Das Cape-Studio hat bisher eine falsche Flügelform fest in die Cape eingebrannt, gerade dort fehlte der Elytra die Fläche. Passt die Flügelfläche einer Cape nicht zur Vanilla-Form, erzeugen Mod, Launcher und Webinterface den Flügel jetzt neu aus der Vorderseite der Cape, auch bei schon hochgeladenen Capes. Das Studio macht die Flügel gleich so.

## 0.5.3

### Fixes
- **Capes als Elytra** (im Spiel, in der Kosmetik-Mod, im Launcher und im Webinterface): Die Flügel erschienen als hohle Kästen mit großem Loch, weil Vorder-, Rück- und Seitenflächen der Flügel bemalt blieben. Die Flügelform ist jetzt genau die der Vanilla-Elytra: nur eine Fläche pro Flügel, der Rest durchsichtig. Das gilt auch für schon hochgeladene Capes.

## 0.5.2

### Zubehör
- **Katana (schwarz-rot)** von dragonKnight127 (Cosmetica, mit Erlaubnis): ein schwarzes Katana mit rot-schwarzem Griff an der linken Hüfte.

### Fixes
- **Elytra in der 3D-Vorschau** (Launcher und Webinterface): Capes, die nicht im Cape-Studio entstanden sind, zeigten die Flügel als zwei Platten. Die Vorschau formt sie jetzt wie die Mod im Spiel und zieht bei alten Capes das Motiv über die Ränder.

## 0.5.1

### Cosmetics-Server
- **Neues Webinterface im Stil des Launchers**: Dein Spieler steht groß auf einer Drehbühne vor dem Launcher-Shader, alles andere sitzt in einem Liquid-Glass-Dock mit den Reitern Capes, Studio, Hut, Begleiter, Zubehör und Konto. Studio und Konto klappen am Rechner als Blatt auf. Beim Wählen einer Cape dreht sich der Spieler, damit sie zu sehen ist. Die getragene Cape hat einen Chroma-Rand, Speichern erscheint als schwebende Pille. Sora und JetBrains Mono werden vom Server selbst ausgeliefert.
- **Docker baut die 3D-Vorschau mit**: Das Image übersetzt die Vorschau der Webseite aus dem Launcher-Viewer, statt eine mitgelieferte Kopie zu nehmen. `docker compose up -d --build` in `deploy/cosmetics` baut Server und Webseite aus dem eigenen Checkout, ohne auf ein Release zu warten.

## 0.5.0

### Zubehör
- **Neuer Platz „Zubehör“** neben Hut und Begleiter, im Launcher, im Webinterface und im Spiel (auch bei anderen Spielern). Zubehör sitzt am Körper oder Kopf und darf wie ein Java-Blockmodell aufgebaut sein (Flächen-UVs, gedrehte Teile).
- **Zenins Katana** von MazerOP (Cosmetica, mit Erlaubnis): ein rotes Katana an der linken Hüfte.

### Cosmetics-Server
- **Cape-Editor im Webinterface wie im Launcher**: Vorderseite mit der Maus verschieben und zoomen (auch per Tastatur), Drehen, Zoom bis 8×, Rückseite in einer Farbe, Ränder umlaufend, automatisch oder in eigener Farbe, Animation kürzen (erstes und letztes Bild), Tempo bis 4× und die Dateigröße live mit der 8-MB-Grenze.
- **Capes im Webinterface bearbeiten**: Capes aus Bildern lassen sich im selben Browser mit „Bearbeiten“ wieder öffnen und neu hochladen; die alte Fassung wird ersetzt und bleibt getragen, wenn sie es war. Das Originalbild bleibt dafür nur im Browser.

## 0.4.2

### Hüte & Begleiter vom Server
- **Neue Hüte und Begleiter brauchen nur noch ein Server-Update**: Mod, Launcher und Webinterface holen Katalog und Begleiter-Texturen vom Cosmetics-Server (mit ETag, unverändert nur ein kurzer Abgleich) und merken sie sich für den nächsten Start und offline. Ohne Server gilt der mitgelieferte Katalog. Kataloge vom Server werden auf Größen und Anzahlen geprüft und sonst verworfen.

### Launcher
- **Capes mit umlaufendem Bild**: Im Cape-Studio läuft das Motiv jetzt über die Seiten, die Ober- und die Unterkante der Cape (und die Ränder der Elytra), statt dass sie eine einzige, oft dunkle Farbe bekommen. Neuer Standard „Bild umlaufend“; die Randfarbe lässt sich weiter wählen. Vorhandene Capes ändern sich erst, wenn man sie damit neu speichert.

### Client-Mod
- Eine kaputte Bild-Adresse vom Cosmetics-Server (z. B. bei falsch gesetzter `PUBLIC_URL`) ließ das Spiel abstürzen. Die Mod lädt das Bild jetzt direkt vom Server oder überspringt die Cape; Fehler in der Kosmetik beenden das Spiel nie mehr.

### Cosmetics-Server
- Beim Hochladen von Bildern und GIFs im Webinterface gibt es dieselbe Wahl unter „Ränder“, ebenfalls mit „Bild umlaufend“ als Standard.
- Startet nicht mehr mit ungültiger `SLOPCLIENT_COSMETICS_PUBLIC_URL` (z. B. `https://` bei leerer Domain-Variable), sondern meldet den Fehler.

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
