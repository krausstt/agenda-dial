package de.agendadial.wear

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import de.agendadial.core.DayPlan
import de.agendadial.core.DialRenderer
import java.util.Calendar

/**
 * Detailansicht. Zeigt dasselbe Zifferblatt wie das Watchface — gleicher
 * Renderer, gleiche Geometrie — plus die Zeiger, die im Watchface nativ
 * von WFF kommen.
 *
 * Bewusst eine schlichte View statt Compose: weniger Abhaengigkeiten, weniger
 * das in der ersten CI-Runde brechen kann. Listenansicht und Tap-Ziele je
 * Termin kommen im naechsten Schritt.
 */
class OrganizerActivity : Activity() {

    private lateinit var dial: DialView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dial = DialView(this)
        setContentView(dial)

        if (!CalendarRepository(this).hasPermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_CALENDAR) dial.reload()
    }

    override fun onResume() { super.onResume(); dial.start() }
    override fun onPause()  { dial.stop(); super.onPause() }

    private companion object { const val REQ_CALENDAR = 1 }
}

/** Zeichnet Agenda-Layer und Zeiger, minuetlich aktualisiert. */
private class DialView(context: Context) : View(context) {

    private val renderer = DialRenderer()
    private val repo = CalendarRepository(context)
    private val handler = Handler(Looper.getMainLooper())
    private var plan = DayPlan(emptyList())

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // Auf die naechste volle Minute synchronisieren statt blind 60 s warten.
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init { reload() }

    fun reload() { plan = DayPlan(repo.eventsForToday()); invalidate() }

    fun start() { reload(); handler.post(tick) }
    fun stop()  { handler.removeCallbacks(tick) }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height)
        if (size <= 0) return
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        renderer.render(canvas, size, plan, now, ambient = false)
        renderer.drawHands(canvas, size, now, ambient = false)
    }
}
