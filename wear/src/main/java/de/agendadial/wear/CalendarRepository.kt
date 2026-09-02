package de.agendadial.wear

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import de.agendadial.core.CalendarEvent
import de.agendadial.core.DialGeometry
import java.util.Calendar
import java.util.TimeZone

/**
 * Liest die Tagesagenda auf der Uhr.
 *
 * ## Welcher Provider — auf Geraet durchprobiert
 *
 * Auf der Galaxy Watch Ultra (One UI 8 Watch / Wear OS 6) gibt es vier
 * Kandidaten. Drei davon fuehren ins Leere, der vierte ist der richtige:
 *
 * | Provider | Ergebnis |
 * |---|---|
 * | `com.android.calendar` (AOSP) | leer — auf der Uhr laeuft kein Kalender-Sync-Adapter |
 * | `com.samsung.android.calendar.watch` | `SecurityException`, Signatur-Permission |
 * | `…watchface.complication.calendar` | Daten vorhanden, aber Paket-Allowlist sperrt uns aus |
 * | **`com.google.android.wearable.provider.calendar`** | **liefert den gespiegelten Handy-Kalender** |
 *
 * Der letzte ist Wear OS' eigener Kalenderspiegel — historisch als
 * `WearableCalendarContract` bekannt. Wear OS synchronisiert den Kalender des
 * Handys selbst auf die Uhr, inklusive Exchange- und Outlook-Konten. Genau
 * dafuer ist er da, und er ist fuer Drittanbieter-Apps offen.
 *
 * Der Samsung-Provider sieht auf den ersten Blick verlockender aus — er ist
 * `exported="true"` ohne jede Permission, und aus der adb-Shell liest er sich
 * problemlos. Er prueft aber intern den aufrufenden Paketnamen:
 *
 *     W ComplicationHelper: [AppValidator] package[de.agendadial.wear] is not allowed to access!!
 *
 * Nicht erneut versuchen. Die Sperre ist Absicht und gilt fuer alle Apps
 * ausserhalb von Samsungs eigener Zifferblatt-Familie.
 *
 * ## Zwei Eigenheiten des Wear-Providers
 *
 * 1. **`sortOrder` muss `null` sein.** Ein `ORDER BY` liefert nicht sortierte,
 *    sondern **null** Zeilen. Sortiert wird in [de.agendadial.core.DayPlan].
 * 2. Eine Projektion wird dagegen respektiert — wichtig, weil `description`
 *    ganze HTML-Mails enthaelt und den CursorWindow unnoetig fuellt.
 */
class CalendarRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Termine des Tages, in Minuten seit Mitternacht lokaler Zeit. */
    fun eventsForToday(nowMillis: Long = System.currentTimeMillis()): List<CalendarEvent> {
        if (!hasPermission()) {
            Log.w(TAG, "READ_CALENDAR fehlt")
            return emptyList()
        }

        val tz = TimeZone.getDefault()
        val dayStart = Calendar.getInstance(tz).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + DAY_MS

        readInstances(wearUri(dayStart, dayEnd), "Wear-Provider", dayStart)?.let { return it }
        return readInstances(aospUri(dayStart, dayEnd), "CalendarContract", dayStart) ?: emptyList()
    }

    // ── URIs ──────────────────────────────────────────────────────────────

    /**
     * Wear OS' Kalenderspiegel. Der Pfad ist `instances/when/<begin>/<end>` —
     * dieselbe Form wie [CalendarContract.Instances], nur unter eigener
     * Authority. Frueher als `WearableCalendarContract.Instances.CONTENT_URI`
     * in der Wearable Support Library; die ist Geschichte, der Provider auf
     * dem Geraet nicht.
     */
    private fun wearUri(from: Long, to: Long): Uri =
        WEAR_INSTANCES.buildUpon().let {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
            it.build()
        }

    private fun aospUri(from: Long, to: Long): Uri =
        CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
            it.build()
        }

    // ── Lesen ─────────────────────────────────────────────────────────────

    /** Gibt `null` zurueck, wenn der Provider nicht erreichbar ist — dann greift der naechste. */
    private fun readInstances(uri: Uri, label: String, dayStart: Long): List<CalendarEvent>? {
        val cursor: Cursor? = try {
            // sortOrder MUSS null bleiben: der Wear-Provider liefert sonst null Zeilen.
            context.contentResolver.query(uri, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            Log.i(TAG, "$label: nicht zugaenglich", e); return null
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "$label: unbekannte URI", e); return null
        }

        if (cursor == null) {
            // Kein Fehler, nur Stille — so sieht ein Provider aus, der uns
            // aussperrt oder wegen Package Visibility unsichtbar ist.
            Log.w(TAG, "$label: query lieferte null")
            return null
        }

        val out = ArrayList<CalendarEvent>()
        cursor.use { c ->
            val iId       = c.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val iBegin    = c.getColumnIndex(CalendarContract.Instances.BEGIN)
            val iEnd      = c.getColumnIndex(CalendarContract.Instances.END)
            val iTitle    = c.getColumnIndex(CalendarContract.Instances.TITLE)
            val iAllDay   = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val iEvColor  = c.getColumnIndex(CalendarContract.Instances.EVENT_COLOR)
            val iCalColor = c.getColumnIndex(CalendarContract.Instances.CALENDAR_COLOR)
            val iStatus   = c.getColumnIndex(CalendarContract.Instances.SELF_ATTENDEE_STATUS)

            if (iBegin < 0 || iEnd < 0 || iTitle < 0) {
                Log.w(TAG, "$label: unerwartetes Schema"); return null
            }

            while (c.moveToNext()) {
                if (iStatus >= 0 && c.getInt(iStatus) == STATUS_DECLINED) continue

                val begin = c.getLong(iBegin)
                val end = c.getLong(iEnd)
                val title = (if (iTitle >= 0) c.getString(iTitle) else null)
                    ?.trim().orEmpty().ifEmpty { "(ohne Titel)" }
                val id = if (iId >= 0) c.getLong(iId) else begin

                // eventColor sticht die Kalenderfarbe, 0 heisst "nicht gesetzt".
                val ev = if (iEvColor >= 0) c.getInt(iEvColor) else 0
                val cal = if (iCalColor >= 0) c.getInt(iCalColor) else 0
                val color = when {
                    ev != 0 -> ev or ALPHA_OPAQUE
                    cal != 0 -> cal or ALPHA_OPAQUE
                    else -> fallbackColor(id)
                }

                val startMin = toMinuteOfDay(begin, dayStart)
                out += CalendarEvent(
                    id = id,
                    title = title,
                    startMin = startMin,
                    endMin = toMinuteOfDay(end, dayStart).coerceAtLeast(startMin + 1),
                    colorArgb = color,
                    glyph = glyphFor(title),
                    allDay = iAllDay >= 0 && c.getInt(iAllDay) == 1,
                )
            }
        }
        Log.i(TAG, "$label: ${out.size} Termine heute" +
            out.joinToString(prefix = " [", postfix = "]") { "${it.startMin / 60}:%02d ${it.title}".format(it.startMin % 60) })
        return out
    }

    // ── Abbildung ─────────────────────────────────────────────────────────

    /** Minuten seit Tagesbeginn, auf [0, 1440] geklemmt — Termine ragen ueber den Tag hinaus. */
    private fun toMinuteOfDay(millis: Long, dayStart: Long): Int =
        (((millis - dayStart) / 60_000L).coerceIn(0L, 1440L)).toInt()

    /** Erster Buchstabe des Titels als Kuerzel fuers Badge. */
    private fun glyphFor(title: String): String =
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"

    /** Kalender ohne eigene Farbe bekommen eine stabile aus der Palette. */
    private fun fallbackColor(id: Long): Int {
        val keys = listOf("indigo", "teal", "brass", "rose", "violet")
        val i = (((id % keys.size) + keys.size) % keys.size).toInt()
        return DialGeometry.palette.getValue(keys[i])
    }

    private companion object {
        const val TAG = "AgendaDial"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val STATUS_DECLINED = CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        const val ALPHA_OPAQUE = 0xFF000000.toInt()

        val WEAR_INSTANCES: Uri =
            Uri.parse("content://com.google.android.wearable.provider.calendar/instances/when")

        val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_COLOR,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
    }
}
