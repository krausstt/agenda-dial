# AgendaDial — Architekturentscheidung

Stand: 28. August 2026. Alle Plattformaussagen sind unten mit Quelle belegt.
Was ich **nicht** verifizieren konnte, ist als solches markiert.

---

## 1. Der Befund, der alles umdreht

Die naheliegende Lösung — ein Watchface in Kotlin, das mit `Canvas` frei zeichnet
und `CalendarContract` liest — ist auf deinen beiden Uhren **tot**.

| Fakt | Konsequenz |
|---|---|
| `androidx.wear.watchface` ist seit Version 1.3.0 (25. Feb. 2026) offiziell **deprecated**, zugunsten Watch Face Format | Kein zukunftsfähiger Kotlin-Watchface-Pfad |
| Ab **27. Jan. 2025**: keine neuen AndroidX/WSL-Watchfaces mehr im Play Store | Play-Distribution ausgeschlossen |
| Ab **14. Jan. 2026**: Legacy-Watchfaces lassen sich nicht mehr aus dem Play Store installieren, keine Updates mehr | — |
| Auf **Wear OS 6** blockiert die Plattform programmatische Watchfaces auch **beim Sideload**: Logcat meldet `Blocked watch face WatchFaceId[...]`, das Face erscheint gar nicht erst im Picker | Auch ADB-Sideload hilft nicht |
| Galaxy Watch Ultra **und** Galaxy Watch 5 laufen beide auf Wear OS 6 / One UI 8 Watch (Watch 5 seit Anfang Dez. 2025) | Beide Zieluhren betroffen |

Google formuliert die Regel selbst so: *„As of January 2026, the Watch Face Format
is required for installing watch faces on all Wear OS devices."*

**Damit ist Watch Face Format (WFF) nicht eine Option, sondern die einzige.**

### Was WFF ist — und wo die Wand steht

WFF ist deklaratives XML. **Kein ausführbarer Code im Watchface-APK.** Es gibt
keine Kalender-Datenquelle im Format. Ein Watchface kann von sich aus deinen
Kalender nicht lesen — nie.

Die Brücke sind **Complications**: deren API ist ausdrücklich *nicht* deprecated
und bleibt bestehen. Ein eigener `ComplicationDataSourceService` in Kotlin darf
alles — Kalender lesen, rechnen, Werte liefern. Das Watchface bindet diese Werte
an Geometrie.

Harte Grenze: **maximal 8 `ComplicationSlot` pro Watchface** (seit Wear OS 4).

---

## 2. Ist dein Wireframe damit zeichenbar? Ja.

Die zwei Elemente, an denen alles hängt, können beide dynamisch angesteuert werden:

| WFF-Element | Attribut | transformierbar? | wofür bei uns |
|---|---|---|---|
| `Arc` | `startAngle`, `endAngle`, `centerX/Y`, `width`, `height` | **ja** | Termin-Marker auf dem Stundenring, Ribbon auf dem Minutenring |
| `TextCircular` | `startAngle`, `endAngle`, `centerX/Y` | **ja** | Titel entlang des Ribbons |

Ausdrücke lesen Complication-Daten über `[COMPLICATION.*]`-Token, u. a.
`RANGED_VALUE_MIN`, `RANGED_VALUE_MAX`, `RANGED_VALUE_VALUE`, `TEXT`, `TITLE`,
`RANGED_VALUE_COLORS` (Leerzeichen-getrennte Hex-Liste). Verfügbar sind
Ternär-Operator, `clamp`, Winkelfunktionen, `icuText` u. a.

### Der Trick: eine Complication = ein Termin

Jeder Slot transportiert einen kompletten Termin in einem `RANGED_VALUE`:

```
RANGED_VALUE_MIN     → Startwinkel in Grad
RANGED_VALUE_MAX     → Endwinkel in Grad
TEXT                 → Titel
TITLE                → Kürzel fürs Badge
RANGED_VALUE_COLORS  → Kalenderfarbe
```

Slot-Budget (8 gesamt):

| Slots | Zweck |
|---|---|
| 1 | Primärtermin der laufenden Stunde — Ribbon + `TextCircular`-Titel |
| 2 | Zweittermin derselben Stunde — schmales Konfliktband |
| 3–8 | sechs Tages-Marker auf dem Stundenring |

Mehr als sechs Marker: der Provider priorisiert (laufend > als Nächstes > Rest)
und faltet den Überhang — genau die Overflow-Logik, die die Design-Bench zeigt.

---

## 3. Watchface oder App? Beides — aus einem Renderer

Deine Frage war richtig gestellt. Die Antwort ist nicht entweder/oder:

**Watchface (WFF)** — immer sichtbar, ambient-fähig, null Interaktionskosten.
Aber: 8 Slots, kein freies Zeichnen, keine Scroll-Liste, keine Details.

**Watch-App (Kotlin/Compose)** — keinerlei Plattformbeschränkung. Freies Canvas,
voller Kalenderzugriff, Tap-Targets, Listen. Aber: muss geöffnet werden.

Deshalb: **Watchface fürs Glanceable, App fürs Detail, Complication-Provider als
gemeinsame Datenquelle.** Der Nutzer tippt auf einen Complication-Slot im
Watchface → die App öffnet auf genau diesem Termin. Ein Datenmodell, eine
Geometrie-Konstantendatei, zwei Oberflächen.

### Hardware-Shortcut

Zur Quick-Button-Belegung auf der Watch Ultra: Samsungs eigene Support-Seite
spricht davon, *„an app or setting"* auszuwählen; publizierte Listen nennen
allerdings nur Samsung-Funktionen (Taschenlampe, Stoppuhr, Water Lock, Sirene).
**Ob dort eine Drittanbieter-App auswählbar ist, konnte ich nicht belegen** —
das sind 30 Sekunden Prüfung in *Galaxy Wearable → Watch-Einstellungen →
Tasten und Gesten*.

Garantierte Wege, die nicht davon abhängen:
1. **Tap auf den Complication-Slot** im Watchface → öffnet die App (Standardverhalten)
2. **Tile** — ein Wisch nach rechts vom Watchface
3. Home-Key-Doppeldruck, falls One UI Watch dort beliebige Apps zulässt (ebenfalls ungeprüft)

---

## 4. Die Datenquelle — auf Gerät geklärt

Das war das größte Restrisiko. Am 02.09.2026 auf einer Galaxy Watch Ultra
(SM-L705F, One UI 8.0 Watch, Wear OS 6 / API 36) durchgemessen:

| Provider | Ergebnis |
|---|---|
| `com.android.calendar` (AOSP `CalendarProvider2`) | **leer** — kein Kalender-Sync-Adapter für das Google-Konto auf der Uhr |
| `com.samsung.android.calendar.watch` | **gesperrt** — `SecurityException`, verlangt `com.samsung.android.calendar.permission.READ` (Signatur-Level) |
| `com.google.android.wearable.provider.calendar` | existiert, kennt aber weder `/calendars` noch `/instances` |
| **`com.samsung.android.watch.watchface.complication.calendar/events`** | **liefert alles** |

Der letzte ist genau der, den Samsung für Zifferblatt-Complications
bereitstellt — also für unseren Anwendungsfall gebaut. Er gibt den vom Handy
gespiegelten Kalender inklusive Exchange-/Outlook-Terminen heraus:

```
eventId, eventUid, begin, end, beginDay, endDay, title, location, description,
allDay, color, calendarId, timeZone, organizer, ownerAccount,
selfAttendeeStatus, hasAlarm, accessLevel, deleted, synced, timeStamp
```

Reicher als geplant: `color` und `allDay` sparen einen Join, `selfAttendeeStatus`
erlaubt das Ausblenden abgesagter Termine, `location` und `organizer` sind
Material für die Detailansicht.

### Drei Eigenheiten, die die Implementierung bestimmen

1. **Die Projektion wird ignoriert.** Es kommen immer alle Spalten zurück,
   `description` mit vollem HTML-Body inklusive.
2. **Eine Selection liefert null Zeilen** — nicht gefilterte, gar keine. Selbst
   `deleted=0` kippt das Ergebnis von 487 auf 0. `selection`, `selectionArgs`
   und `sortOrder` müssen `null` bleiben, gefiltert wird clientseitig.
3. **Der Zeitraum ist groß.** Im Test 487 Zeilen über rund fünf Monate.

Alle drei sind in [`CalendarRepository`](../wear/src/main/java/de/agendadial/wear/CalendarRepository.kt)
kommentiert. Punkt 2 ist die gefährlichste: eine gut gemeinte Optimierung, die
eine `where`-Klausel einführt, macht die App still leer.

### Plan B steht weiter bereit

Der Provider ist Samsung-spezifisch und undokumentiert. Fällt er weg — anderes
Fabrikat, Samsung ändert etwas —, greift automatisch der Standardweg über
`CalendarContract`. Bleibt auch der leer, ist der Rückfallplan unverändert:
eine Companion-App am Handy liest dort `CalendarContract` und schiebt die
Tagesagenda über die **Wearable Data Layer API** auf die Uhr.

---

## 5. Fallback-Leiter

Falls Stufe A an einer Stelle bricht, ist der Weg nach unten vorgezeichnet:

**A — WFF mit nativen `Arc` + `TextCircular`** *(Primärpfad)*
Sauber, ambient-fähig, ruckelfrei. Grenze: 8 Slots.

**B — WFF mit einem bildschirmfüllenden `PHOTO_IMAGE`-Slot**
Der Provider rendert das komplette Zifferblatt in Kotlin auf ein `Bitmap` —
pixelgenau die Design-Bench, beliebig viele Termine, gekrümmter Text ohne
Einschränkung. Zeiger und Indizes bleiben nativ in WFF (die brauchen 60 fps).
Offene Punkte: Update-Takt für Complications, Ambient-Verhalten, Binder-Limit
(mit `Icon.createWithData(pngBytes)` unkritisch — ein schwarzes Zifferblatt
komprimiert auf wenige zehn KB). Dass ein Slot bildschirmfüllend sein *darf*,
ist in der Doku **nicht explizit bestätigt** — erste CI-Validierung klärt das.

**C — nur die Watch-App**
Garantiert lauffähig, volle Wireframe-Treue, aber nicht always-on.
Wear OS erlaubt Always-on-Aktivitäten via `AmbientLifecycleObserver` — wie lange
das System die Ansicht hält, bevor es zum Watchface zurückspringt, habe ich
nicht verifiziert.

Weil A, B und C denselben Datenlayer und dieselbe `geometry.json` nutzen, kostet
ein Wechsel zwischen ihnen kein Redesign.

---

## Quellen

- [Upcoming changes to Wear OS watch faces — Android Developers Blog, 12.06.2025](https://android-developers.googleblog.com/2025/06/upcoming-changes-to-wear-os-watch-faces.html)
- [Wear Watchface Release Notes (Deprecation, 1.3.0)](https://developer.android.com/jetpack/androidx/releases/wear-watchface)
- [WearOS 6+ watchface support — NightscoutFoundation/xDrip Discussion #4438](https://github.com/NightscoutFoundation/xDrip/discussions/4438)
- [WFF `Arc` Reference](https://developer.android.com/reference/wear-os/wff/group/part/draw/shape/arc)
- [WFF `TextCircular` Reference](https://developer.android.com/reference/wear-os/wff/group/part/text/text-circular)
- [WFF `ComplicationSlot` Reference](https://developer.android.com/reference/wear-os/wff/complication/complication-slot)
- [WFF `Complication` — Expression-Token je Typ](https://developer.android.com/training/wearables/wff/complication/complication)
- [WFF `ArithmeticExpression` — Operatoren und Funktionen](https://developer.android.com/reference/wear-os/wff/common/attributes/arithmetic-expression)
- [google/watchface — offizieller WFF-Validator](https://github.com/google/watchface)
- [One UI 8 Watch Rollout abgeschlossen — SamMobile](https://www.sammobile.com/news/one-ui-8-watch-rollout-is-now-complete-have-you-received-the-update/)
- [Galaxy Watch 5 One UI 8 Beta — 9to5Google, 10.11.2025](https://9to5google.com/2025/11/10/galaxy-watch-5-one-ui-8-beta-rollout/)
- [Quick Button anpassen — Samsung Support](https://www.samsung.com/au/support/mobile-devices/customise-quick-button/)
- [Use Google Calendar on your Wear OS watch — Google Support](https://support.google.com/wearos/answer/14143488)

*Generated by AI — Quellen wie oben verlinkt.*
