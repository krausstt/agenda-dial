package de.agendadial.wear

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import de.agendadial.core.CalendarEvent
import de.agendadial.core.DialGeometry
import java.util.Calendar
import java.util.TimeZone

/**
 * Liest die Tagesagenda aus dem Kalender-Provider DER UHR.
 *
 * Wichtig: das ist nicht automatisch der Kalender deines Handys. Wear OS hat
 * einen eigenen Provider, gefuellt von den auf der Uhr angemeldeten Konten.
 * Ob dein Arbeitskalender dort landet, ist der groesste offene Punkt des
 * Projekts — siehe docs/ARCHITECTURE.md, Abschnitt 4, inklusive Pruefkommando.
 */
class CalendarRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Termine des Tages, in Minuten seit Mitternacht lokaler Zeit. */
    fun eventsForToday(nowMillis: Long = System.currentTimeMillis()): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()

        val tz = TimeZone.getDefault()
        val dayStart = Calendar.getInstance(tz).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24L * 60 * 60 * 1000

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
        context.contentResolver.query(
            uri, projection,
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ?",
            arrayOf(CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString()),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val title = c.getString(1)?.trim().orEmpty().ifEmpty { "(ohne Titel)" }
                val begin = c.getLong(2)
                val end = c.getLong(3)
                val allDay = c.getInt(4) == 1
                val color = c.getInt(5).let { if (it == 0) fallbackColor(id) else it or 0xFF000000.toInt() }

                out += CalendarEvent(
                    id = id,
                    title = title,
                    startMin = toMinuteOfDay(begin, dayStart),
                    endMin = toMinuteOfDay(end, dayStart).coerceAtLeast(toMinuteOfDay(begin, dayStart) + 1),
                    colorArgb = color,
                    glyph = glyphFor(title),
                    allDay = allDay,
                )
            }
        }
        return out
    }

    /** Minuten seit Tagesbeginn, auf [0, 1440] geklemmt. */
    private fun toMinuteOfDay(millis: Long, dayStart: Long): Int =
        (((millis - dayStart) / 60_000L).coerceIn(0L, 1440L)).toInt()

    /** Erster Buchstabe des Titels als Kuerzel fuers Badge. */
    private fun glyphFor(title: String): String =
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"

    /** Kalender ohne eigene Farbe bekommen eine stabile aus der Palette. */
    private fun fallbackColor(id: Long): Int {
        val keys = listOf("indigo", "teal", "brass", "rose", "violet")
        return DialGeometry.palette.getValue(keys[(id % keys.size).toInt()])
    }
}
