# Entwicklungsumgebung einrichten

Windows 11, Firmenlaptop, Alltagskonto ohne Admin-Rechte plus separates
Admin-Konto. Genau diese Konstellation hat eine Falle, die man einmal richtig
löst und danach nie wieder anfasst.

**Die Regel, an der alles hängt:**

> Das **Admin-Konto installiert nur Binaries**.
> Das **Alltagskonto macht jede Einrichtung** — SDK-Download, erster Start, Builds.

Warum: Android Studio lädt das SDK beim Erststart in das Profil des Kontos, das
es startet (`%LOCALAPPDATA%\Android\Sdk`). Startet das Admin-Konto den Assistenten,
liegt dein komplettes SDK in einem Profil, an das du nicht herankommst. Dasselbe
gilt für `winget`-Portable-Pakete: die landen im Profil des aufrufenden Kontos.

Geprüft auf deiner Maschine am 28.08.2026:
`azuread\tobiaskrauss`, **nicht** in der Administratorengruppe · 204 GB frei auf C:
· kein Proxy, keine SSL-Inspection-CA im Machine-Root-Store (spart dir die
gesamte Zertifikatsklasse an Problemen).

---

## Schritt 0 — Projekt aus OneDrive holen

**Vor allem anderen.** Dein `Documents` ist per Known Folder Move nach
`C:\Users\TobiasKrauss\OneDrive - Workaround GmbH\Documents` umgeleitet — das
Projekt liegt also in einem synchronisierten Ordner.

Gradle schreibt beim Bauen zehntausende Dateien in `build/`. In einem
OneDrive-Ordner heißt das: Sync-Sturm, Dateisperren mitten im Build
(`Unable to delete directory`), und Builds, die ein Vielfaches länger dauern.
Das ist kein Vielleicht, das passiert zuverlässig.

Kein Admin nötig:

```bash
mkdir C:\dev && move "C:\Users\TobiasKrauss\OneDrive - Workaround GmbH\Documents\Claude Code\AgendaDial" C:\dev\AgendaDial
```

Danach ist der Projektpfad `C:\dev\AgendaDial`. Das Git-Repo zieht komplett mit —
`.git` liegt im Ordner.

Dein `%LOCALAPPDATA%` und `%USERPROFILE%\.gradle` sind **nicht** umgeleitet, die
können bleiben, wo sie sind.

---

## Schritt 1 — Wer installiert was

| Paket | Konto | Warum |
|---|---|---|
| Android Studio | **Admin** | NSIS-Installer, schreibt nach `C:\Program Files` |
| Platform-Tools (`adb`) | **Admin**, mit `--scope machine` | sonst liegt `adb` im Admin-Profil |
| Temurin JDK 21 | **Admin** | MSI, maschinenweit — optional, siehe Schritt 4 |
| SDK, Emulator, erster Start | **Alltagskonto** | landet im aufrufenden Profil |
| Gradle-Wrapper, Builds, `adb`-Nutzung | **Alltagskonto** | – |

---

## Schritt 2 — Android Studio

**Im Admin-Konto** (PowerShell):

```bash
winget install --id Google.AndroidStudio --exact --source winget --accept-package-agreements --accept-source-agreements
```

Version 2026.1.3.7, ~1,5 GB. Installiert maschinenweit, danach **Admin-Konto
verlassen** — den Assistenten dort auf keinen Fall durchklicken.

**Im Alltagskonto** Android Studio starten. Im Setup-Assistenten:

1. *Standard* wählen, SDK-Pfad auf dem Vorschlag `%LOCALAPPDATA%\Android\Sdk` lassen
2. Nach dem Assistenten: **More Actions → SDK Manager**
3. Reiter *SDK Platforms*: **Android 16.0 (API 36)** ankreuzen — das ist Wear OS 6
4. Reiter *SDK Tools*: **Android SDK Build-Tools**, **Android SDK Platform-Tools**,
   **Android Emulator** ankreuzen
5. Apply

Für den Emulator zusätzlich: **Device Manager → Add → Wear OS** → ein rundes
Wear-OS-Gerät mit API 36. Das ersetzt keinen Test auf der echten Uhr — WFF
verhält sich im Emulator nicht immer identisch — aber es fängt die groben Fehler ab.

---

## Schritt 3 — adb für beide Konten sichtbar machen

Studio bringt `adb` mit, aber nur im SDK deines Profils und nicht im PATH. Für
die Kommandozeile brauchst du es separat.

**Im Admin-Konto:**

```bash
winget install --id Google.PlatformTools --exact --scope machine --source winget --accept-package-agreements
```

Das Paket ist ein **Portable-Zip**. Ohne `--scope machine` landet es in
`%LOCALAPPDATA%` des Admin-Kontos und dein Alltagskonto sieht es nie.

Falls winget bei `--scope machine` für Portables meckert — das kommt vor —, der
manuelle Weg ist deterministisch und dauert eine Minute. **Im Admin-Konto:**

```bash
Invoke-WebRequest -Uri https://dl.google.com/android/repository/platform-tools-latest-windows.zip -OutFile $env:TEMP\pt.zip; Expand-Archive $env:TEMP\pt.zip -DestinationPath C:\Android -Force
```

```bash
[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path','Machine') + ';C:\Android\platform-tools', 'Machine')
```

**Im Alltagskonto** neue PowerShell öffnen und prüfen:

```bash
adb version
```

---

## Schritt 4 — JDK (optional)

Android Studio bringt eine eigene JetBrains Runtime mit; für Builds **in Studio**
brauchst du kein separates JDK. Nur wenn du `gradlew` in einer normalen
PowerShell aufrufen willst:

**Im Admin-Konto:**

```bash
winget install --id EclipseAdoptium.Temurin.21.JDK --exact --scope machine --source winget --accept-package-agreements
```

MSI, installiert nach `C:\Program Files\Eclipse Adoptium\`. Falls `java -version`
im Alltagskonto danach nichts findet, fehlt der PATH-Eintrag — **im Admin-Konto**:

```bash
[Environment]::SetEnvironmentVariable('JAVA_HOME', (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object Name -like 'jdk-21*' | Select-Object -First 1).FullName, 'Machine')
```

---

## Schritt 5 — Gradle-Wrapper erzeugen

Das Repo enthält `gradle/wrapper/gradle-wrapper.properties` (pinnt Gradle 9.5.1),
aber bewusst **keine** `gradle-wrapper.jar`. Ein Binary, das ich nicht gegen eine
offizielle Prüfsumme verifizieren kann, gehört nicht ins Repo — die soll deine
eigene Toolchain erzeugen.

Studio kommt ohne die Jar zurecht: es liest die Properties und spricht Gradle
über die Tooling-API an. Der Sync funktioniert also sofort.

Nach dem ersten erfolgreichen Sync in Studio:

1. Rechts das **Gradle**-Tool-Window öffnen
2. `AgendaDial → Tasks → build setup → wrapper` doppelklicken

Das erzeugt `gradlew`, `gradlew.bat` und `gradle/wrapper/gradle-wrapper.jar`.
Danach committen — ab dann funktioniert `.\gradlew` auch in der Kommandozeile.

Die CI braucht den Wrapper nicht, sie zieht Gradle über
`gradle/actions/setup-gradle` mit fester Version.

---

## Schritt 6 — Uhr verbinden

Auf der Uhr:

1. *Einstellungen → Info → Softwareinformationen* → **siebenmal** auf
   *Softwareversion* tippen
2. *Einstellungen → Entwickleroptionen* → **ADB-Debugging** an
3. Ebenda → **Debugging über WLAN** an. Uhr und Laptop müssen im selben Netz sein
   (Firmen-WLAN mit Client-Isolation blockt das — dann Hotspot vom Handy nehmen)
4. Die Uhr zeigt IP und Port. Für das Pairing zusätzlich
   *Neues Gerät koppeln* antippen — das gibt einen **zweiten** Port und einen Code

Im Alltagskonto, einmalig pro Uhr:

```bash
adb pair <UHR-IP>:<PAIRING-PORT>
```

Danach bei jeder Session:

```bash
adb connect <UHR-IP>:<DEBUG-PORT>
```

```bash
adb devices -l
```

---

## Schritt 7 — Der erste echte Test

Noch bevor irgendetwas gebaut ist: die Frage, an der das ganze Projekt hängt.
Liefert der Kalender-Provider **der Uhr** überhaupt Daten?

```bash
adb shell content query --uri content://com.android.calendar/calendars --projection _id:account_name:calendar_displayName
```

Kommen Zeilen mit deinen Kalendern zurück, ist der Hauptpfad frei. Kommt nichts
oder nur ein leerer lokaler Kalender, brauchen wir Plan B — eine Companion-App
am Handy, die die Agenda über die Wearable Data Layer API auf die Uhr schiebt.
Siehe [ARCHITECTURE.md, Abschnitt 4](ARCHITECTURE.md).

Dann bauen und installieren:

```bash
.\gradlew :watchface:assembleRelease :wear:assembleRelease
```

```bash
adb install -r -d watchface\build\outputs\apk\release\watchface-release.apk
```

```bash
adb install -r -d wear\build\outputs\apk\release\wear-release.apk
```

Auf der Uhr lange aufs Zifferblatt drücken → **AgendaDial** auswählen.

---

## Wenn es klemmt

**Build ist quälend langsam.** Defender scannt jede Datei, die Gradle schreibt.
**Im Admin-Konto**, einmalig:

```bash
Add-MpPreference -ExclusionPath 'C:\dev', "$env:USERPROFILE\.gradle", "$env:LOCALAPPDATA\Android\Sdk"
```

Achtung: `$env:USERPROFILE` löst im Admin-Konto auf dessen Profil auf. Trage den
Pfad `C:\Users\TobiasKrauss\.gradle` dort ausgeschrieben ein.

**`adb devices` zeigt die Uhr als `unauthorized`.** Auf der Uhr den
Bestätigungsdialog quittieren. Kommt keiner: *Entwickleroptionen → USB-Debugging-
Autorisierungen widerrufen*, dann neu koppeln.

**`adb connect` läuft in einen Timeout.** Fast immer Client-Isolation im
Firmen-WLAN. Handy-Hotspot, beide Geräte hinein, erneut versuchen.

**Studio findet das JDK nicht.** *Settings → Build, Execution, Deployment →
Build Tools → Gradle → Gradle JDK* → die gebündelte JetBrains Runtime wählen.

**`PKIX path building failed` beim Gradle-Sync.** Wäre SSL-Inspection — auf
deiner Maschine aktuell nicht der Fall. Falls die IT das nachrüstet: Corp-Root-CA
in den `cacerts` der von Studio genutzten JVM importieren.

**Das Watchface taucht nach der Installation nicht im Picker auf.** Erst prüfen,
ob es das Kotlin-APK und nicht das WFF-APK ist — nur letzteres erscheint dort.
Dann `adb logcat | Select-String WFInfoResolver`: eine Zeile `Blocked watch face`
hieße, dass die Plattform es ablehnt. Bei WFF sollte das nicht passieren; genau
das war der Grund, den AndroidX-Pfad zu verwerfen.
