package de.agendadial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Bahnlogik ist der Teil, an dem das Zifferblatt kippt, wenn er falsch ist:
 * aufeinanderfolgende Termine duerfen sich eine Bahn teilen, echte
 * Ueberschneidungen nicht. Reines Kotlin, laeuft auf der JVM ohne Emulator.
 */
class DayPlanTest {

    private fun ev(id: Long, from: String, to: String, title: String = "T$id") =
        CalendarEvent(id, title, min(from), min(to), 0xFF5B8DFF.toInt(), title.take(1))

    private fun min(hhmm: String) = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }

    @Test
    fun `aufeinanderfolgende Termine teilen sich eine Bahn`() {
        val plan = DayPlan(listOf(ev(1, "09:00", "09:30"), ev(2, "09:30", "10:30")))
        assertEquals(0, plan.lanes[1L])
        assertEquals(0, plan.lanes[2L])
        assertEquals(1, plan.requiredLanes)
        assertTrue(plan.clashes.isEmpty())
    }

    @Test
    fun `ueberlappende Termine bekommen getrennte Bahnen`() {
        val plan = DayPlan(listOf(ev(1, "10:00", "11:00"), ev(2, "10:30", "11:30")))
        assertEquals(0, plan.lanes[1L])
        assertEquals(1, plan.lanes[2L])
        assertEquals(2, plan.requiredLanes)
        assertEquals(1, plan.clashes.size)
        assertEquals(min("10:30"), plan.clashes[0].startMin)
        assertEquals(min("11:00"), plan.clashes[0].endMin)
    }

    @Test
    fun `dreifache Ueberschneidung wird gefaltet und gemeldet`() {
        val plan = DayPlan(listOf(
            ev(1, "10:00", "11:00"), ev(2, "10:15", "10:45"), ev(3, "10:20", "10:40"),
        ))
        assertEquals(3, plan.requiredLanes)
        assertTrue(3L in plan.overflow)
        assertEquals(3, plan.clashes.single().peakDepth)
    }

    @Test
    fun `Minutenband gibt beiden Folgeterminen derselben Stunde eine Bahn`() {
        val plan = DayPlan(listOf(ev(1, "09:00", "09:30", "Standup"), ev(2, "09:30", "10:30", "Review")))
        val (lanes, hidden) = plan.ribbonLanes(9 * 60)
        assertEquals(2, lanes[0].size)   // beide auf Bahn 0 -> beide mit Titel
        assertTrue(lanes[1].isEmpty())
        assertEquals(0, hidden)
    }

    @Test
    fun `Minutenband stapelt echte Ueberschneidungen auf zwei Bahnen`() {
        val plan = DayPlan(listOf(ev(1, "17:00", "17:45", "1zu1"), ev(2, "17:15", "18:00", "Release")))
        val (lanes, hidden) = plan.ribbonLanes(17 * 60)
        assertEquals(1, lanes[0].size)
        assertEquals(1, lanes[1].size)
        assertEquals(0, hidden)          // beide sichtbar, beide mit Titel
    }

    @Test
    fun `Marker-Priorisierung behaelt laufende und kommende Termine`() {
        val many = (0 until 10).map { ev(it.toLong(), "%02d:00".format(8 + it), "%02d:30".format(8 + it)) }
        val markers = DayPlan(many).markersFor(min("14:10"))
        assertEquals(DialGeometry.maxDayMarkers, markers.size)
        assertTrue(markers.any { it.runsAt(min("14:10")) })
    }

    @Test
    fun `Winkelmodell entspricht der Design-Bench`() {
        assertEquals(157.5f, DialGeometry.hourAngle(min("17:15")), 0.01f)
        assertEquals(90f,    DialGeometry.minuteAngle(min("17:15")), 0.01f)
        assertEquals(270f,   DialGeometry.minuteAngle(min("17:45")), 0.01f)
    }
}
