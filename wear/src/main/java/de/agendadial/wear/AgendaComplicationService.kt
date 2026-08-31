package de.agendadial.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import de.agendadial.core.CalendarEvent
import de.agendadial.core.DayPlan
import de.agendadial.core.DialGeometry
import de.agendadial.core.DialRenderer
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * Rendert den kompletten Agenda-Layer und liefert ihn als PHOTO_IMAGE.
 *
 * Warum ein Bild statt nativer WFF-Arcs? Watch Face Format kann Arcs und
 * gekruemmten Text zwar dynamisch transformieren, aber nur ueber maximal acht
 * Complication-Slots — und ohne die Freiheit fuer Konflikt-Klammern, zwei
 * betitelte Baender und Kollisionsaufloesung bei den Badges. Google nutzt das
 * bildschirmfuellende PHOTO_IMAGE-Muster im eigenen Complications-Sample; wir
 * bekommen damit exakt die Design-Bench aufs Handgelenk.
 *
 * Die Zeiger liegen bewusst NICHT hier drin, sondern nativ im WFF: das System
 * darf Complication-Updates drosseln, die Uhrzeit darf davon nicht abhaengen.
 */
class AgendaComplicationService : SuspendingComplicationDataSourceService() {

    private val renderer = DialRenderer()

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.PHOTO_IMAGE) return null
        return build(DayPlan(previewEvents()), 17 * 60 + 10)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.PHOTO_IMAGE) return null

        val repo = CalendarRepository(this)
        val events = repo.eventsForToday()
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

        return build(DayPlan(events), now)
    }

    private fun build(plan: DayPlan, nowMinuteOfDay: Int): ComplicationData {
        val size = resources.displayMetrics.widthPixels.coerceIn(320, 600)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        renderer.render(Canvas(bmp), size, plan, nowMinuteOfDay, ambient = false)

        // Ueber Binder gehen maximal ~1 MB. Ein schwarzes Zifferblatt komprimiert
        // als PNG auf wenige zehn KB — createWithData statt createWithBitmap.
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bmp.recycle()

        val running = plan.events.firstOrNull { it.runsAt(nowMinuteOfDay) }
        val next = plan.events.firstOrNull { it.startMin > nowMinuteOfDay }
        val description = running?.let { "Jetzt: ${it.title}" }
            ?: next?.let { "Als Naechstes: ${it.title}" }
            ?: "Keine Termine mehr heute"

        return PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithData(png, 0, png.size),
            contentDescription = PlainComplicationText.Builder(description).build(),
        )
            .setTapAction(openOrganizer())
            .build()
    }

    /** Tap aufs Zifferblatt oeffnet die Detailansicht. */
    private fun openOrganizer(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, OrganizerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Vorschau im Watchface-Picker — bevor die Kalenderfreigabe erteilt ist. */
    private fun previewEvents(): List<CalendarEvent> {
        fun c(k: String) = DialGeometry.palette.getValue(k)
        return listOf(
            CalendarEvent(1, "Daily Standup",      9 * 60,      9 * 60 + 30, c("teal"),   "S"),
            CalendarEvent(2, "Product Review",    10 * 60,     11 * 60,      c("indigo"), "P"),
            CalendarEvent(3, "Kundentermin",      14 * 60,     15 * 60 + 30, c("rose"),   "K"),
            CalendarEvent(4, "1:1 mit Lena",      17 * 60,     17 * 60 + 45, c("violet"), "L"),
            CalendarEvent(5, "Release Sync",      17 * 60 + 15, 18 * 60,     c("teal"),   "R"),
        )
    }
}
