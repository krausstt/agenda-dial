package de.agendadial.wear

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import de.agendadial.core.CalendarEvent
import de.agendadial.core.DayPlan
import de.agendadial.core.DialRenderer
import java.util.Calendar
import kotlin.math.hypot

/**
 * Detailansicht. Zeigt dasselbe Zifferblatt wie das Watchface — gleicher
 * Renderer, gleiche Geometrie — plus die Zeiger, die im Watchface nativ von
 * WFF kommen.
 *
 * Bewusst eine schlichte View statt Compose: weniger Abhaengigkeiten, weniger
 * das brechen kann.
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

    /** Zurueck schliesst erst die Detailansicht, dann die App. */
    override fun onBackPressed() {
        if (dial.dismissDetail()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onResume() { super.onResume(); dial.start() }
    override fun onPause() { dial.stop(); super.onPause() }

    private companion object { const val REQ_CALENDAR = 1 }
}

/**
 * Zeichnet Agenda-Layer und Zeiger, minuetlich aktualisiert.
 *
 * Antippen eines Kuerzels oeffnet den Termin im Klartext. Die Trefferflaechen
 * kommen aus [DialRenderer.badgeLayout] — derselben Rechnung, die die Badges
 * auch zeichnet, damit beides nicht auseinanderlaeuft.
 */
private class DialView(context: Context) : View(context) {

    private val renderer = DialRenderer()
    private val repo = CalendarRepository(context)
    private val handler = Handler(Looper.getMainLooper())

    private var plan = DayPlan(emptyList())
    private var selected: CalendarEvent? = null

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // Auf die naechste volle Minute synchronisieren statt blind 60 s warten.
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init {
        isClickable = true
        reload()
    }

    fun reload() {
        plan = DayPlan(repo.eventsForToday())
        selected = null
        invalidate()
    }

    fun start() { reload(); handler.post(tick) }
    fun stop() { handler.removeCallbacks(tick) }

    /** Schliesst die Detailansicht. Gibt true zurueck, wenn eine offen war. */
    fun dismissDetail(): Boolean {
        if (selected == null) return false
        selected = null
        invalidate()
        return true
    }

    private fun nowMinuteOfDay(): Int =
        Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height)
        if (size <= 0) return
        val now = nowMinuteOfDay()
        renderer.render(canvas, size, plan, now, ambient = false)
        renderer.drawHands(canvas, size, now, ambient = false)
        selected?.let { renderer.drawEventDetail(canvas, size, it) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        performClick()

        // Offene Detailansicht: jeder Tipp schliesst sie wieder.
        if (dismissDetail()) return true

        val size = minOf(width, height)
        if (size <= 0) return true
        val d = size.toFloat()
        val now = nowMinuteOfDay()

        // Badges sind klein; die Trefferflaeche ist bewusst grosszuegiger als
        // der gezeichnete Kreis, sonst trifft man sie am Handgelenk nie.
        val badges = renderer.badgeLayout(size, plan, now)
        val tolerance = maxOf(d * 0.055f, badges.firstOrNull()?.radius?.times(2.2f) ?: 0f)
        val hit = badges
            .map { it to hypot(event.x - it.x, event.y - it.y) }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }
            ?.first

        selected = when {
            hit != null -> hit.event
            // Mitte antippen: was laeuft gerade, sonst was kommt als Naechstes.
            hypot(event.x - d / 2f, event.y - d / 2f) < d * 0.22f ->
                plan.events.firstOrNull { it.runsAt(now) }
                    ?: plan.events.firstOrNull { it.startMin > now }
            else -> null
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
