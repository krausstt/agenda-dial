// GENERIERT von tools/genkotlin.mjs aus design/geometry.json.
// Nicht von Hand bearbeiten — Änderungen gehen bei der nächsten Generierung verloren.
package de.agendadial.core

/**
 * Zifferblatt-Geometrie. Alle Radien sind Bruchteile des Displaydurchmessers D,
 * damit 450 px (Watch Ultra) und 396 px (Watch 5) ohne Sonderfall funktionieren.
 *
 * Winkelmodell: 0° = 12 Uhr, positiv im Uhrzeigersinn.
 * Skia rechnet ab 3 Uhr — im Renderer daher überall −90.
 */
object DialGeometry {
    const val tickMinorOuter: Float = 0.4780f
    const val tickMinorInner: Float = 0.4620f
    const val tickMinorWidth: Float = 0.0044f
    const val tickMajorOuter: Float = 0.4780f
    const val tickMajorInner: Float = 0.4550f
    const val tickMajorWidth: Float = 0.0089f
    const val hourRingRadius: Float = 0.4340f
    const val hourLaneStep: Float = 0.0260f
    const val hourRingStroke: Float = 0.0200f
    const val hourBadgeOrbit: Float = 0.3680f
    const val hourBadgeRadius: Float = 0.0230f
    const val ribbonLane0Radius: Float = 0.2990f
    const val ribbonLane0Stroke: Float = 0.0600f
    const val ribbonLane1Radius: Float = 0.2340f
    const val ribbonLane1Stroke: Float = 0.0540f
    const val handHourLength: Float = 0.1300f
    const val handHourWidth: Float = 0.0240f
    const val handMinuteLength: Float = 0.1900f
    const val handMinuteWidth: Float = 0.0150f
    const val handCap: Float = 0.0180f
    const val titleSize: Float = 0.0320f
    const val titleSizeSecondary: Float = 0.0270f
    const val edgeLabelSize: Float = 0.0230f
    const val badgeGlyphSize: Float = 0.0230f
    const val meridiemSize: Float = 0.0200f
    const val minMarkerSweepDeg: Float = 3.2000f
    const val ribbonGapDeg: Float = 1.6000f
    const val badgeSepFactor: Float = 1.2000f
    const val maxHourLanes: Int = 2
    const val maxRibbonLanes: Int = 2
    const val maxDayMarkers: Int = 6

    /** Kalenderfarben und Akzente, ARGB. */
    val palette: Map<String, Int> = mapOf(
        "lume" to 0xFFE8E4D8.toInt(),
        "now" to 0xFFFF7A2E.toInt(),
        "indigo" to 0xFF5B8DFF.toInt(),
        "teal" to 0xFF26C2A8.toInt(),
        "brass" to 0xFFF0B429.toInt(),
        "rose" to 0xFFEF6A88.toInt(),
        "violet" to 0xFFA78BFA.toInt(),
    )

    /** Stundenring-Winkel eines Zeitpunkts (Minuten seit Mitternacht). */
    fun hourAngle(minuteOfDay: Int, hours24: Boolean = false): Float =
        if (hours24) minuteOfDay / 60f * 15f else (minuteOfDay / 60f % 12f) * 30f

    /** Minutenring-Winkel innerhalb der laufenden Stunde. */
    fun minuteAngle(minuteOfDay: Int): Float = (minuteOfDay % 60) * 6f
}
