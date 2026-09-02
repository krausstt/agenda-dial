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
 * Liest die Tagesagenda auf der Uhr — aus zwei Quellen, in dieser Reihenfolge.
 *
 * ## Warum zwei Quellen
 *
 * Auf der Galaxy Watch Ultra (One UI 8 Watch / Wear OS 6) ist der AOSP-Provider
 * `content://com.android.calendar` **leer**. Auf der Uhr laeuft kein
 * Kalender-Sync-Adapter fuer das Google-Konto, und Samsungs eigener
 * `com.samsung.android.calendar.watch` ist mit einer Signatur-Permission
 * geschuetzt (`com.samsung.android.calendar.permission.READ`), an die eine
 * Drittanbieter-App nicht herankommt.
 *
 * Lesbar ist dagegen der Provider, den Samsung ausdruecklich fuer Zifferblatt-
 * Complications bereitstellt:
 *
 *     content://com.samsung.android.watch.watchface.complication.calendar/events
 *
 * Der liefert den echten, vom Handy gespiegelten Kalender inklusive Exchange-
 * bzw. Outlook-Terminen. Auf einer Galaxy Watch am 02.09.2026 verifiziert.
 *
 * ## Eigenheiten dieses Providers — teuer erkauft, bitte nicht wegoptimieren
 *
 * 1. **Die Projektion wird ignoriert.** Egal was man anfordert, es kommen immer
 *    alle Spalten zurueck, `description` mit vollem HTML-Body inklusive.
 * 2. **Eine Selection liefert null Zeilen.** Nicht etwa gefilterte — gar keine.
 *    Selbst `deleted=0` kippt das Ergebnis von 487 auf 0. Also: `selection`,
 *    `selectionArgs` und `sortOrder` MUESSEN `null` sein, gefiltert wird hier.
 * 3. **Der Zeitraum ist gross.** Im Test 487 Zeilen ueber rund fuenf Monate.
 *
 * Faellt der Provider aus — anderes Uhrenfabrikat, Samsung aendert etwas —,
 * greift automatisch der Standardweg ueber [CalendarContract].
 */
class CalendarRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Termine des Tages, in Minuten seit Mitternacht lokaler Zeit. */
    fun eventsForToday(nowMillis: Long = System.currentTimeMillis()): List<CalendarEvent> {
        val tz = TimeZone.getDefault()
        val dayStart = Calendar.getInstance(tz).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + DAY_MS

        readSamsungComplicationProvider(dayStart, dayEnd)?.let { return it }
        if (!hasPermission()) return emptyList()
        return readCalendarContract(dayStart, dayEnd)
    }

    // ── Quelle 1: Samsungs Complication-Provider ──────────────────────────

    private fun readSamsungComplicationProvider(dayStart: Long, dayEnd: Long): List<CalendarEvent>? {
        val cursor: Cursor? = try {
            // Alles null. Siehe Eigenheit 1 und 2 im Klassenkommentar.
            context.contentResolver.query(SAMSUNG_EVENTS, null, null, null, null)
        } catch (e: SecurityException) {
            Log.i(TAG, "Samsung-Provider nicht zugaenglich, weiche auf CalendarContract aus", e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "Samsung-Provider nicht vorhanden, weiche auf CalendarContract aus", e)
            return null
        }

        cursor ?: return null
        val out = ArrayList<CalendarEvent>()
        cursor.use { c ->
            val iBegin  = c.getColumnIndex("begin")
            val iEnd    = c.getColumnIndex("end")
            val iTitle  = c.getColumnIndex("title")
            if (iBegin < 0 || iEnd < 0 || iTitle < 0) {
                Log.w(TAG, "Samsung-Provider hat unerwartetes Schema, weiche aus")
                return null
            }
            val iId      = c.getColumnIndex("eventId")
            val iUid     = c.getColumnIndex("eventUid")
            val iAllDay  = c.getColumnIndex("allDay")
            val iColor   = c.getColumnIndex("color")
            val iDeleted = c.getColumnIndex("deleted")
            val iStatus  = c.getColumnIndex("selfAttendeeStatus")

            while (c.moveToNext()) {
                if (iDeleted >= 0 && c.getInt(iDeleted) == 1) continue
                if (iStatus >= 0 && c.getInt(iStatus) == STATUS_DECLINED) continue

                val begin = c.getLong(iBegin)
                val end = c.getLong(iEnd)
                if (begin >= dayEnd || end <= dayStart) continue      // Tagesfenster

                val id = when {
                    iId >= 0 -> c.getLong(iId)
                    iUid >= 0 -> c.getString(iUid)?.hashCode()?.toLong() ?: begin
                    else -> begin
                }
                out += toEvent(
                    id = id,
                    rawTitle = if (iTitle >= 0) c.getString(iTitle) else null,
                    begin = begin, end = end, dayStart = dayStart,
                    rawColor = if (iColor >= 0) c.getInt(iColor) else 0,
                    allDay = iAllDay >= 0 && c.getInt(iAllDay) == 1,
                )
            }
        }
        Log.i(TAG, "Samsung-Provider: ${out.size} Termine heute")
        return out
    }

    // ── Quelle 2: Standardweg ─────────────────────────────────────────────

    private fun readCalendarContract(dayStart: Long, dayEnd: Long): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, dayStart)
            ContentUris.appendId(it, dayEnd)
            it.build()
        }
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )

        val out = ArrayList<CalendarEvent>()
        try {
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(6) == STATUS_DECLINED) continue
                    out += toEvent(
                        id = c.getLong(0),
                        rawTitle = c.getString(1),
                        begin = c.getLong(2), end = c.getLong(3), dayStart = dayStart,
                        rawColor = c.getInt(5),
                        allDay = c.getInt(4) == 1,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "CalendarContract nicht lesbar", e)
        }
        Log.i(TAG, "CalendarContract: ${out.size} Termine heute")
        return out
    }

    // ── Gemeinsame Abbildung ──────────────────────────────────────────────

    private fun toEvent(
        id: Long, rawTitle: String?, begin: Long, end: Long, dayStart: Long,
        rawColor: Int, allDay: Boolean,
    ): CalendarEvent {
        val title = rawTitle?.trim().orEmpty().ifEmpty { "(ohne Titel)" }
        val startMin = toMinuteOfDay(begin, dayStart)
        return CalendarEvent(
            id = id,
            title = title,
            startMin = startMin,
            endMin = toMinuteOfDay(end, dayStart).coerceAtLeast(startMin + 1),
            colorArgb = if (rawColor == 0) fallbackColor(id) else rawColor or ALPHA_OPAQUE,
            glyph = glyphFor(title),
            allDay = allDay,
        )
    }

    /** Minuten seit Tagesbeginn, auf [0, 1440] geklemmt — Termine ragen ueber den Tag hinaus. */
    private fun toMinuteOfDay(millis: Long, dayStart: Long): Int =
        (((millis - dayStart) / 60_000L).coerceIn(0L, 1440L)).toInt()

    /** Erster Buchstabe des Titels als Kuerzel fuers Badge. */
    private fun glyphFor(title: String): String =
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"

    /** Kalender ohne eigene Farbe bekommen eine stabile aus der Palette. */
    private fun fallbackColor(id: Long): Int {
        val keys = listOf("indigo", "teal", "brass", "rose", "violet")
        val i = ((id % keys.size) + keys.size) % keys.size
        return DialGeometry.palette.getValue(keys[i.toInt()])
    }

    private companion object {
        const val TAG = "AgendaDial"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val STATUS_DECLINED = 2          // CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        const val ALPHA_OPAQUE = 0xFF000000.toInt()
        val SAMSUNG_EVENTS: Uri =
            Uri.parse("content://com.samsung.android.watch.watchface.complication.calendar/events")
    }
}
