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
) {
    val durationMin: Int get() = endMin - startMin
    fun overlaps(other: CalendarEvent) = startMin < other.endMin && endMin > other.startMin
    fun runsAt(minuteOfDay: Int) = startMin <= minuteOfDay && endMin > minuteOfDay
}

/** Zeitfenster, in dem mindestens zwei Termine gleichzeitig laufen. */
data class ClashSpan(val startMin: Int, val endMin: Int, val peakDepth: Int)

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
                ev.runsAt(nowMinuteOfDay) -> 0
                ev.startMin > nowMinuteOfDay -> 1
                else -> 2
            } * 100_000 + kotlin.math.abs(ev.startMin - nowMinuteOfDay)
        }.take(DialGeometry.maxDayMarkers).sortedBy { it.startMin }
    }
}
