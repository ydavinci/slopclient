# Changelog

Alle nennenswerten Änderungen an DaClient (Launcher und Client-Mod). Die Versionen gelten für beide zusammen.

## 0.2.0

### Launcher
- **Instanzen kopieren** samt Mods, Welten und Einstellungen, **Gruppen** in der Instanzliste (einklappbar).
- **Lokale Dateien per Drag & Drop** oder Dateiauswahl: Mods, Resourcepacks und Shader werden am Inhalt erkannt und danach auf Modrinth bzw. CurseForge gesucht, damit sie Updates bekommen.
- **Welten-Tab**: Name, Modus, Version, Größe und Icon jeder Welt, Datapacks pro Welt (hinzufügen, an/aus, entfernen), Backup als ZIP, Löschen.
- **Screenshots-Tab** als Galerie mit Vorschaubildern.
- **Befehle vor dem Start und nach dem Beenden** mit Prisms `INST_*`-Variablen.
- **Log teilen** über mclo.gs aus dem Log-Tab und dem Crash-Dialog; Zugangsdaten und der Windows-Benutzername werden vorher entfernt.
- **Performance-Paket** (Sodium, Lithium, FerriteCore, Iris) beim Anlegen oder nachträglich mit einem Klick.
- **Cosmetics-Konto**: Anmeldung am DaClient-Cosmetics-Server, damit andere Spieler deine Cape sehen (Server selbst gehostet, siehe `deploy/cosmetics/`).
- Eigene **Instanz-Icons** mit Zuschnitt, übernommen auch aus Modpacks und Prism.
- **Cape-Studio**: aus Bildern und GIFs eigene, auch animierte Capes erstellen, mit 3D-Vorschau.
- Offline-Testmodus für Entwickler-Builds (`--features dev-offline`), nie in öffentlichen Releases.
- Die angezeigte Version kommt jetzt aus dem Build.

### Client-Mod
- **Karambit**: Das Netherite-Schwert wird zum 3D-Karambit aus CS:GO, im Rückwärtsgriff, mit Zieh- und Inspect-Animation (V). Funktioniert auch mit Resourcepacks, die das Schwert ändern; mit Punchy behält jedes andere Item Punchys Animationen.
- **Capes** aus dem Cape-Studio im Spiel, auch animiert und als Elytra, und die Capes anderer DaClient-Spieler.
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
