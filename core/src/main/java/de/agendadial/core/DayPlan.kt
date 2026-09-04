package de.agendadial.core

/**
 * Ein Termin, bereits auf Minuten seit Mitternacht normalisiert.
 *
 * [startMin] und [endMin] duerfen ueber 1440 hinausgehen, wenn ein Termin in den
 * Folgetag laeuft — der Renderer schneidet selbst zu.
 */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val startMin: Int,
    val endMin: Int,
    val colorArgb: Int,
    val glyph: String,
    val allDay: Boolean = false,
    /** Ort oder Konferenzlink, fuer die Detailansicht. */
    val location: String? = null,
    /**
     * Vom Organisator abgesagt oder von dir abgelehnt. Wird nicht ausgeblendet,
     * sondern grau gezeichnet — der Slot war schliesslich mal verplant, und ein
     * verschwundener Termin verwirrt mehr als ein durchgestrichener.
     */
    val isCancelled: Boolean = false,
) {
    val durationMin: Int get() = endMin - startMin
    fun overlaps(other: CalendarEvent) = startMin < other.endMin && endMin > other.startMin
    fun runsAt(minuteOfDay: Int) = startMin <= minuteOfDay && endMin > minuteOfDay
}

/** Zeitfenster, in dem mindestens zwei Termine gleichzeitig laufen. */
data class ClashSpan(val startMin: Int, val endMin: Int, val peakDepth: Int)

/**
 * Zeitlicher Zustand eines Termins — die wichtigste Information auf dem Blatt.
 *
 * Ein Arbeitskalender faerbt alle Termine gleich; die Kalenderfarbe sagt also
 * wenig. Was zaehlt: ist das vorbei, laeuft es, oder ist es das Naechste.
 * Deshalb traegt die Helligkeit den Zeitbezug, der Farbton nur noch die
 * Kalenderzugehoerigkeit.
 */
enum class EventState { PAST, RUNNING, NEXT, LATER, CANCELLED }

/**
 * Der Tag, aufbereitet fuers Zeichnen: Bahnzuweisung, Konfliktfenster,
 * Priorisierung wenn mehr Termine anfallen als Marker-Plaetze da sind.
 *
 * Reine Kotlin-Logik, keine Android-Abhaengigkeit — deshalb auf der JVM testbar.
 */
class DayPlan(events: List<CalendarEvent>) {

    /** Nach Startzeit sortiert; bei gleichem Start zuerst der kuerzere Termin. */
    val events: List<CalendarEvent> = events.sortedWith(
        compareBy({ it.startMin }, { it.durationMin })
    )

    /** Bahn je Termin. Ueberlappende Termine landen auf getrennten Bahnen. */
    val lanes: Map<Long, Int>

    /** Termine, die ueber [DialGeometry.maxHourLanes] hinausgehen und gefaltet wurden. */
    val overflow: Set<Long>

    /** Wie viele Bahnen der Tag ohne Faltung braeuchte. */
    val requiredLanes: Int

    init {
        val laneEnd = ArrayList<Int>()
        val assigned = HashMap<Long, Int>()
        val folded = HashSet<Long>()
        for (ev in this.events) {
            var lane = laneEnd.indexOfFirst { it <= ev.startMin }
            if (lane == -1) { lane = laneEnd.size; laneEnd.add(ev.endMin) } else laneEnd[lane] = ev.endMin
            if (lane >= DialGeometry.maxHourLanes) folded += ev.id
            assigned[ev.id] = minOf(lane, DialGeometry.maxHourLanes - 1)
        }
        lanes = assigned
        overflow = folded
        requiredLanes = laneEnd.size
    }

    /**
     * Fenster mit Doppelbuchung. Sweep-Line ueber Start- und Endpunkte:
     * ein Fenster oeffnet, sobald die Tiefe 2 erreicht, und schliesst, sobald
     * sie wieder darunter faellt.
     */
    val clashes: List<ClashSpan> by lazy {
        val pts = ArrayList<Pair<Int, Int>>(events.size * 2)
        for (ev in events) { pts += ev.startMin to 1; pts += ev.endMin to -1 }
        pts.sortWith(compareBy({ it.first }, { it.second }))

        val out = ArrayList<ClashSpan>()
        var depth = 0; var start = -1; var peak = 0
        for ((t, d) in pts) {
            val prev = depth; depth += d
            when {
                prev < 2 && depth >= 2 -> { start = t; peak = depth }
                depth >= 2             -> peak = maxOf(peak, depth)
            }
            if (prev >= 2 && depth < 2 && start >= 0) { out += ClashSpan(start, t, peak); start = -1; peak = 0 }
        }
        out
    }

    fun isInClash(ev: CalendarEvent) = clashes.any { ev.startMin < it.endMin && ev.endMin > it.startMin }

    // ── Zeitlicher Zustand ────────────────────────────────────────────────

    /** Der naechste anstehende Termin — abgesagte und ganztaegige zaehlen nicht. */
    fun nextUp(nowMinuteOfDay: Int): CalendarEvent? =
        events.firstOrNull { !it.isCancelled && !it.allDay && it.startMin > nowMinuteOfDay }

    /**
     * [next] kann durchgereicht werden, damit der Renderer es nicht je Termin
     * neu sucht.
     */
    fun stateOf(
        ev: CalendarEvent,
        nowMinuteOfDay: Int,
        next: CalendarEvent? = nextUp(nowMinuteOfDay),
    ): EventState = when {
        ev.isCancelled -> EventState.CANCELLED
        ev.endMin <= nowMinuteOfDay -> EventState.PAST
        ev.runsAt(nowMinuteOfDay) -> EventState.RUNNING
        ev.id == next?.id -> EventState.NEXT
        else -> EventState.LATER
    }

    /**
     * Termine, deren Farbton gedreht wird, weil der zeitliche Nachbar fast
     * dieselbe Kalenderfarbe hat.
     *
     * Bewusst der Farbton und nicht die Helligkeit: Helligkeit ist schon fuer
     * "vorbei" vergeben, eine zweite Bedeutung darauf waere nicht mehr lesbar.
     * Bei einer Kette gleichfarbiger Termine alterniert es, sodass nie zwei
     * benachbarte gleich aussehen.
     */
    val hueShifted: Set<Long> by lazy {
        val out = HashSet<Long>()
        var shift = false
        var prev: CalendarEvent? = null
        for (ev in events) {
            val p = prev
            shift = p != null && colorsClose(p.colorArgb, ev.colorArgb) && !shift
            if (shift) out += ev.id
            prev = ev
        }
        out
    }

    private fun colorsClose(a: Int, b: Int): Boolean {
        val d = kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
            kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
            kotlin.math.abs((a and 255) - (b and 255))
        return d < 90
    }

    /** Termine, die in die Stunde ab [hourStartMin] hineinragen — Primaertermin zuerst. */
    fun inHour(hourStartMin: Int): List<CalendarEvent> =
        events.filter { it.endMin > hourStartMin && it.startMin < hourStartMin + 60 && !it.allDay }

    /** Bahnbelegung des Minutenbands plus die Zahl der nicht darstellbaren Termine. */
    data class RibbonLanes(val lanes: List<List<CalendarEvent>>, val hidden: Int)

    /**
     * Verteilt die Termine der Stunde auf die Baender des Minutenrings.
     *
     * Entscheidend: aufeinanderfolgende Termine — Standup bis :30, Review ab :30 —
     * ueberlappen nicht und teilen sich deshalb Bahn 0 an verschiedenen Winkeln.
     * Beide bekommen ihren vollen Titel. Nur echte Ueberschneidungen wandern auf
     * Bahn 1, die ebenfalls Text traegt. Was darueber hinausgeht, zaehlt [hidden].
     */
    fun ribbonLanes(hourStartMin: Int): RibbonLanes {
        val laneEnd = ArrayList<Int>()
        val out = List(DialGeometry.maxRibbonLanes) { ArrayList<CalendarEvent>() }
        var hidden = 0
        for (ev in inHour(hourStartMin)) {
            var lane = laneEnd.indexOfFirst { it <= ev.startMin }
            if (lane == -1) { lane = laneEnd.size; laneEnd.add(ev.endMin) } else laneEnd[lane] = ev.endMin
            if (lane >= DialGeometry.maxRibbonLanes) hidden++ else out[lane] += ev
        }
        return RibbonLanes(out, hidden)
    }

    /**
     * Welche Termine bekommen einen Marker auf dem Stundenring, wenn der Tag
     * mehr hergibt als [DialGeometry.maxDayMarkers]? Laufende zuerst, dann die
     * naechsten, dann der Rest — vergangene fallen zuerst weg.
     */
    fun markersFor(nowMinuteOfDay: Int): List<CalendarEvent> {
        val timed = events.filter { !it.allDay }
        if (timed.size <= DialGeometry.maxDayMarkers) return timed
        return timed.sortedBy { ev ->
            when {
                ev.isCancelled -> 3                       // faellt als Erstes weg
                ev.runsAt(nowMinuteOfDay) -> 0
                ev.startMin > nowMinuteOfDay -> 1
                else -> 2
            } * 100_000 + kotlin.math.abs(ev.startMin - nowMinuteOfDay)
        }.take(DialGeometry.maxDayMarkers).sortedBy { it.startMin }
    }
}
