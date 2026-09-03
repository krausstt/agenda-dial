package de.agendadial.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Zeichnet den Agenda-Layer des Zifferblatts.
 *
 * Portierung von design/bench.html — identische Bahnen, identische Winkel-
 * mathematik, identische Konstanten (beide lesen [DialGeometry] bzw.
 * geometry.json). Was die Bench zeigt, kommt hier heraus.
 *
 * Der Renderer zeichnet bewusst KEINE Zeiger: die liegen nativ im WFF, damit
 * die Uhrzeit nicht am Complication-Update-Takt haengt. Die Organizer-App
 * setzt sie ueber [drawHands] selbst obendrauf.
 *
 * Winkelmodell: 0° = 12 Uhr, im Uhrzeigersinn. Skia rechnet ab 3 Uhr — daher
 * ueberall −90.
 */
class DialRenderer {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    private val lumeText = 0xFFF4F2EE.toInt()
    private val mutedText = 0xFF9AA0A8.toInt()

    /**
     * @param size Kantenlaenge in Pixeln — 450 (Watch Ultra) oder 396 (Watch 5).
     * @param nowMinuteOfDay Minuten seit Mitternacht.
     * @param ambient Always-on: gedaempft, keine Flaechen, duenne Konturen.
     */
    fun render(canvas: Canvas, size: Int, plan: DayPlan, nowMinuteOfDay: Int, ambient: Boolean) {
        val d = size.toFloat()
        val cx = d / 2f
        val cy = d / 2f
        val dim = if (ambient) 0.55f else 1f

        canvas.drawColor(Color.BLACK)

        drawTicks(canvas, cx, cy, d, dim)

        val markers = plan.markersFor(nowMinuteOfDay)
        drawHourMarkers(canvas, cx, cy, d, plan, markers, ambient)
        drawClashSeams(canvas, cx, cy, d, plan, dim)
        drawBadges(canvas, d, plan, badgeLayout(size, plan, nowMinuteOfDay), ambient)
        drawMinuteBand(canvas, cx, cy, d, plan, nowMinuteOfDay, ambient, dim)
        drawNowMark(canvas, cx, cy, d, nowMinuteOfDay, ambient)
        drawMeridiem(canvas, cx, cy, d, nowMinuteOfDay, dim)
    }

    // ── Bahn 1: Indizes ──────────────────────────────────────────────────

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, d: Float, dim: Float) {
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 12) {
            val major = i % 3 == 0
            val a = i * 30f
            stroke.color = lume(if (major) 0.85f * dim else 0.34f * dim)
            stroke.strokeWidth = d * if (major) DialGeometry.tickMajorWidth else DialGeometry.tickMinorWidth
            val ro = d * if (major) DialGeometry.tickMajorOuter else DialGeometry.tickMinorOuter
            val ri = d * if (major) DialGeometry.tickMajorInner else DialGeometry.tickMinorInner
            canvas.drawLine(px(cx, ro, a), py(cy, ro, a), px(cx, ri, a), py(cy, ri, a), stroke)
        }
    }

    // ── Bahn 2/3: Tages-Marker ───────────────────────────────────────────

    private fun drawHourMarkers(
        canvas: Canvas, cx: Float, cy: Float, d: Float,
        plan: DayPlan, markers: List<CalendarEvent>, ambient: Boolean,
    ) {
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = d * DialGeometry.hourRingStroke
        for (ev in markers) {
            val lane = plan.lanes[ev.id] ?: 0
            val r = d * (DialGeometry.hourRingRadius - lane * DialGeometry.hourLaneStep)
            var a0 = DialGeometry.hourAngle(ev.startMin)
            var a1 = DialGeometry.hourAngle(ev.endMin)
            if (a1 <= a0) a1 += 360f
            if (a1 - a0 < DialGeometry.minMarkerSweepDeg) a1 = a0 + DialGeometry.minMarkerSweepDeg
            stroke.color = if (ambient) 0xFF3A3F47.toInt() else ev.colorArgb
            arc(canvas, cx, cy, r,
                a0 + DialGeometry.ribbonGapDeg / 2f,
                a1 - DialGeometry.ribbonGapDeg / 2f, stroke)
        }
    }

    /**
     * Konflikt-Naht: Haarlinie in der Rinne zwischen den beiden Marker-Bahnen,
     * begrenzt von zwei kurzen Radialstrichen. Liegt im Zwischenraum und
     * ueberzeichnet daher keinen Marker.
     */
    private fun drawClashSeams(canvas: Canvas, cx: Float, cy: Float, d: Float, plan: DayPlan, dim: Float) {
        if (plan.clashes.isEmpty()) return
        val rSeam = d * (DialGeometry.hourRingRadius - DialGeometry.hourLaneStep / 2f)
        val half = d * (DialGeometry.hourLaneStep / 2f + DialGeometry.hourRingStroke / 2f)
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = max(1f, d * 0.0030f)
        for (sp in plan.clashes) {
            var a0 = DialGeometry.hourAngle(sp.startMin)
            var a1 = DialGeometry.hourAngle(sp.endMin)
            if (a1 <= a0) a1 += 360f
            stroke.color = lume(0.40f * dim)
            arc(canvas, cx, cy, rSeam, a0, a1, stroke)
            stroke.color = lume(0.85f * dim)
            for (a in listOf(a0, a1)) {
                canvas.drawLine(px(cx, rSeam + half, a), py(cy, rSeam + half, a),
                                px(cx, rSeam - half, a), py(cy, rSeam - half, a), stroke)
            }
        }
    }

    // ── Bahn 4: Kuerzel-Badges ───────────────────────────────────────────

    /** Ein Kuerzel-Badge: Zeichenposition und Tap-Ziel in einem. */
    data class Badge(val event: CalendarEvent, val x: Float, val y: Float, val radius: Float)

    /**
     * Badge-Positionen inklusive Kollisionsaufloesung.
     *
     * Bewusst oeffentlich und von [render] mitbenutzt: die Organizer-App
     * bestimmt daraus ihre Trefferflaechen. Waeren es zwei Rechnungen, liefen
     * Darstellung und Antippbarkeit frueher oder spaeter auseinander.
     */
    fun badgeLayout(size: Int, plan: DayPlan, nowMinuteOfDay: Int): List<Badge> {
        val d = size.toFloat()
        val cx = d / 2f
        val cy = d / 2f
        val orbit = d * DialGeometry.hourBadgeOrbit
        val minSep = Math.toDegrees(
            2.0 * DialGeometry.hourBadgeRadius * DialGeometry.badgeSepFactor / DialGeometry.hourBadgeOrbit
        ).toFloat()

        // Mittelpunkte bestimmen, dann in einem Durchlauf auseinanderschieben.
        val placed = plan.markersFor(nowMinuteOfDay).map { ev ->
            var a0 = DialGeometry.hourAngle(ev.startMin)
            var a1 = DialGeometry.hourAngle(ev.endMin)
            if (a1 <= a0) a1 += 360f
            ev to (a0 + a1) / 2f
        }.sortedBy { it.second }.toMutableList()
        for (i in 1 until placed.size) {
            if (placed[i].second - placed[i - 1].second < minSep) {
                placed[i] = placed[i].first to (placed[i - 1].second + minSep)
            }
        }
        return placed.map { (ev, a) ->
            Badge(ev, px(cx, orbit, a), py(cy, orbit, a), d * DialGeometry.hourBadgeRadius)
        }
    }

    private fun drawBadges(
        canvas: Canvas, d: Float, plan: DayPlan, badges: List<Badge>, ambient: Boolean,
    ) {
        text.textSize = d * DialGeometry.badgeGlyphSize
        text.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val vCenter = -(text.fontMetrics.ascent + text.fontMetrics.descent) / 2f

        for (b in badges) {
            val ev = b.event
            fill.color = Color.BLACK
            canvas.drawCircle(b.x, b.y, b.radius, fill)

            stroke.pathEffect = null
            stroke.strokeWidth = max(1f, d * 0.0033f)
            stroke.color = if (ambient) 0xFF5C626B.toInt() else ev.colorArgb
            canvas.drawCircle(b.x, b.y, b.radius, stroke)

            // Doppelring = dieser Termin liegt in einer verdeckten Bahn
            if (ev.id in plan.overflow) {
                stroke.strokeWidth = max(1f, d * 0.0026f)
                stroke.color = alpha(if (ambient) 0xFF4A4F57.toInt() else ev.colorArgb, 0.5f)
                canvas.drawCircle(b.x, b.y, b.radius + d * 0.008f, stroke)
            }

            text.color = if (ambient) 0xFF8E9099.toInt() else ev.colorArgb
            canvas.drawText(ev.glyph, b.x, b.y + vCenter, text)
        }
    }

    // ── Bahn 5/6: Minutenband ────────────────────────────────────────────

    /** Radius, Bandhoehe und Schriftgrad je Ribbon-Bahn. */
    private data class Lane(val r: Float, val w: Float, val ts: Float)

    private fun lanes(d: Float) = listOf(
        Lane(d * DialGeometry.ribbonLane0Radius, d * DialGeometry.ribbonLane0Stroke, d * DialGeometry.titleSize),
        Lane(d * DialGeometry.ribbonLane1Radius, d * DialGeometry.ribbonLane1Stroke, d * DialGeometry.titleSizeSecondary),
    )

    private fun drawMinuteBand(
        canvas: Canvas, cx: Float, cy: Float, d: Float,
        plan: DayPlan, nowMinuteOfDay: Int, ambient: Boolean, dim: Float,
    ) {
        val hourStart = nowMinuteOfDay / 60 * 60
        val lane = lanes(d)

        // gestrichelte Grundlinie der Minutenskala, unter der inneren Bahn
        stroke.color = lume(0.16f * dim)
        stroke.strokeWidth = max(1f, d * 0.0026f)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.pathEffect = DashPathEffect(floatArrayOf(d * 0.006f, d * 0.012f), 0f)
        canvas.drawCircle(cx, cy, lane[1].r - lane[1].w / 2f - d * 0.012f, stroke)
        stroke.pathEffect = null

        val (bands, hidden) = plan.ribbonLanes(hourStart)
        if (bands.all { it.isEmpty() }) return

        fun clip(ev: CalendarEvent) = Pair(
            (max(ev.startMin, hourStart) - hourStart) * 6f,
            (min(ev.endMin, hourStart + 60) - hourStart) * 6f,
        )

        // Jede Bahn kann mehrere aufeinanderfolgende Termine tragen — jeder mit Titel.
        for ((li, band) in bands.withIndex()) {
            val (r, w, ts) = lane[li]
            for (ev in band) {
                val (a0, a1) = clip(ev)

                stroke.strokeCap = Paint.Cap.BUTT
                stroke.strokeWidth = w
                stroke.color = if (ambient) alpha(Color.WHITE, 0.06f) else alpha(ev.colorArgb, 0.22f)
                arc(canvas, cx, cy, r, a0, a1, stroke)
                stroke.color = if (ambient) 0xFF6D737C.toInt() else ev.colorArgb
                arc(canvas, cx, cy, r, a0, min(a0 + 1.4f, a1), stroke)

                stroke.strokeWidth = max(1f, d * 0.0026f)
                stroke.color = if (ambient) alpha(0xFFA0A6AF.toInt(), 0.45f) else alpha(ev.colorArgb, 0.55f)
                arc(canvas, cx, cy, r - w / 2f, a0, a1, stroke)
                arc(canvas, cx, cy, r + w / 2f, a0, a1, stroke)

                // Startzeit am Innenrand; ihr Winkel wird dem Titel abgezogen
                var leadDeg = 0f
                if (!ambient && a1 - a0 > 40f) {
                    mono.textSize = d * DialGeometry.edgeLabelSize
                    mono.color = alpha(ev.colorArgb, 0.95f)
                    val rIn = r - w / 2f + d * DialGeometry.edgeLabelSize * 0.85f
                    val label = hhmm(ev.startMin)
                    leadDeg = Math.toDegrees((mono.measureText(label) / rIn).toDouble()).toFloat() + 2.5f
                    curvedText(canvas, label, cx, cy, rIn, a0 + leadDeg / 2f, leadDeg, mono)
                }

                val tS = a0 + leadDeg + 3.5f
                val tE = a1 - 3.5f
                if (tE - tS > 7f) {
                    text.textSize = ts
                    text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    text.color = if (ambient) 0xFFC3C7CD.toInt() else 0xFFF4F2EE.toInt()
                    curvedText(canvas, ev.title, cx, cy, r + ts * 0.05f, (tS + tE) / 2f, tE - tS, text)
                }
            }
        }

        // Ueberlappungsfenster radial klammern — nur wo Bahn 0 und 1 sich decken
        for (a in bands[0]) for (b in bands.getOrElse(1) { emptyList() }) {
            val (p0, p1) = clip(a); val (q0, q1) = clip(b)
            val o0 = max(p0, q0); val o1 = min(p1, q1)
            if (o1 - o0 <= 0.5f) continue
            val rOut = lane[0].r + lane[0].w / 2f + d * 0.008f
            val rIn = lane[1].r - lane[1].w / 2f - d * 0.008f
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeWidth = max(1f, d * 0.0033f)
            stroke.color = lume(0.80f * dim)
            for (ang in listOf(o0, o1)) {
                canvas.drawLine(px(cx, rOut, ang), py(cy, rOut, ang), px(cx, rIn, ang), py(cy, rIn, ang), stroke)
            }
        }

        if (hidden > 0) {
            mono.textSize = d * DialGeometry.edgeLabelSize
            mono.color = if (ambient) 0xFF8E9099.toInt() else DialGeometry.palette.getValue("now")
            canvas.drawText("+$hidden", cx, cy + d * 0.115f, mono)
        }
    }

    private fun drawNowMark(canvas: Canvas, cx: Float, cy: Float, d: Float, nowMinuteOfDay: Int, ambient: Boolean) {
        val a = DialGeometry.minuteAngle(nowMinuteOfDay)
        val base = d * (DialGeometry.ribbonLane0Radius + DialGeometry.ribbonLane0Stroke / 2f)
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = max(1.5f, d * 0.006f)
        stroke.color = if (ambient) 0xFF9AA0A8.toInt() else DialGeometry.palette.getValue("now")
        canvas.drawLine(
            px(cx, base + d * 0.024f, a), py(cy, base + d * 0.024f, a),
            px(cx, base + d * 0.010f, a), py(cy, base + d * 0.010f, a), stroke)
    }

    private fun drawMeridiem(canvas: Canvas, cx: Float, cy: Float, d: Float, nowMinuteOfDay: Int, dim: Float) {
        mono.textSize = d * DialGeometry.meridiemSize
        mono.color = lume(0.55f * dim)
        canvas.drawText(if (nowMinuteOfDay >= 720) "PM" else "AM", cx, cy + d * 0.072f, mono)
    }

    // ── Zeiger. Nur die App braucht sie; im Watchface zeichnet WFF sie nativ. ──

    fun drawHands(canvas: Canvas, size: Int, nowMinuteOfDay: Int, ambient: Boolean) {
        val d = size.toFloat()
        val cx = d / 2f; val cy = d / 2f
        val color = if (ambient) 0xFF8E9099.toInt() else DialGeometry.palette.getValue("lume")
        hand(canvas, cx, cy, d, (nowMinuteOfDay / 60f % 12f) * 30f,
            d * DialGeometry.handHourLength, d * DialGeometry.handHourWidth, color, ambient)
        hand(canvas, cx, cy, d, (nowMinuteOfDay % 60) * 6f,
            d * DialGeometry.handMinuteLength, d * DialGeometry.handMinuteWidth, color, ambient)
        fill.color = 0xFF0A0A0A.toInt()
        canvas.drawCircle(cx, cy, d * DialGeometry.handCap, fill)
        stroke.pathEffect = null
        stroke.strokeWidth = max(1.5f, d * 0.005f)
        stroke.color = if (ambient) 0xFF8E9099.toInt() else DialGeometry.palette.getValue("now")
        canvas.drawCircle(cx, cy, d * DialGeometry.handCap, stroke)
    }

    private fun hand(
        canvas: Canvas, cx: Float, cy: Float, d: Float,
        deg: Float, len: Float, w: Float, color: Int, ambient: Boolean,
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(deg)
        val p = android.graphics.Path().apply {
            moveTo(-w / 2f, d * 0.035f)
            lineTo(-w / 2f, -len + w)
            lineTo(0f, -len)
            lineTo(w / 2f, -len + w)
            lineTo(w / 2f, d * 0.035f)
            close()
        }
        if (ambient) {
            stroke.pathEffect = null
            stroke.strokeWidth = max(1f, d * 0.0033f)
            stroke.color = color
            canvas.drawPath(p, stroke)
        } else {
            fill.color = color
            canvas.drawPath(p, fill)
        }
        canvas.restore()
    }

    // ── Detailansicht ─────────────────────────────────────────────────────

    /**
     * Blendet einen Termin gross und lesbar ueber das Zifferblatt.
     *
     * Ein Kuerzel auf dem Ring sagt, WANN etwas ist — nicht WAS. Das hier
     * beantwortet das Zweite, ohne die Uhr zu verlassen: abgedunkeltes Blatt,
     * Zeitspanne in der Kalenderfarbe, Titel umgebrochen, Ort darunter.
     */
    fun drawEventDetail(canvas: Canvas, size: Int, event: CalendarEvent) {
        val d = size.toFloat()
        val cx = d / 2f

        // Nicht ganz deckend: das Zifferblatt bleibt als Kontext erkennbar.
        canvas.drawColor(alpha(Color.BLACK, 0.90f))

        val maxWidth = d * 0.72f
        val timeSize = d * 0.042f
        val titleSize = d * 0.055f
        val placeSize = d * 0.032f

        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = titleSize
        val titleLines = wrapText(event.title, text, maxWidth, maxLines = 3)

        text.textSize = placeSize
        val placeLines = event.location
            ?.takeIf { it.isNotBlank() }
            ?.let { wrapText(it, text, maxWidth, maxLines = 2) }
            ?: emptyList()

        val timeLine =
            if (event.allDay) "ganztaegig" else "${hhmm(event.startMin)}–${hhmm(event.endMin)}"

        // Zeilen erst sammeln, dann als Block mittig setzen.
        val rows = ArrayList<Row>()
        rows += Row(timeLine, timeSize, event.colorArgb, monospaced = true, lead = timeSize * 1.85f)
        titleLines.forEach { rows += Row(it, titleSize, lumeText, false, titleSize * 1.24f) }
        if (placeLines.isNotEmpty()) {
            rows += Row("", 0f, 0, false, d * 0.022f)          // Abstandshalter
            placeLines.forEach { rows += Row(it, placeSize, mutedText, false, placeSize * 1.28f) }
        }

        var y = d / 2f - rows.sumOf { it.lead.toDouble() }.toFloat() / 2f
        for (r in rows) {
            if (r.s.isNotEmpty()) {
                val p = if (r.monospaced) mono else text
                p.textSize = r.size
                p.color = r.color
                if (!r.monospaced) p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                canvas.drawText(r.s, cx, y + r.size, p)
            }
            y += r.lead
        }

        // Farbmarke oben — bindet die Ansicht an ihren Marker auf dem Ring zurueck.
        fill.color = event.colorArgb
        val bw = d * 0.10f
        val by = d * 0.20f
        canvas.drawRoundRect(cx - bw / 2f, by, cx + bw / 2f, by + d * 0.011f, d * 0.006f, d * 0.006f, fill)
    }

    private data class Row(
        val s: String, val size: Float, val color: Int, val monospaced: Boolean, val lead: Float,
    )

    /** Wortumbruch; passt der Rest nicht mehr, bekommt die letzte Zeile eine Ellipse. */
    private fun wrapText(s: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val lines = ArrayList<String>()
        var cur = ""
        var truncated = false
        for ((i, w) in words.withIndex()) {
            val cand = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(cand) <= maxWidth) { cur = cand; continue }
            if (cur.isNotEmpty()) lines += cur
            if (lines.size >= maxLines) { truncated = i < words.size; cur = ""; break }
            cur = w
        }
        if (cur.isNotEmpty() && lines.size < maxLines) lines += cur
        else if (cur.isNotEmpty()) truncated = true

        if (truncated && lines.isNotEmpty()) {
            var last = lines.last()
            while (last.isNotEmpty() && paint.measureText("$last…") > maxWidth) last = last.dropLast(1)
            lines[lines.size - 1] = "$last…"
        }
        return lines
    }

    // ── Primitive ────────────────────────────────────────────────────────

    /** Bogen um [cx]/[cy]. Winkel im Zifferblattmodell (0° = 12 Uhr). */
    private fun arc(canvas: Canvas, cx: Float, cy: Float, r: Float, a0: Float, a1: Float, paint: Paint) {
        if (a1 - a0 <= 0.05f || r <= 0f) return
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, a0 - 90f, a1 - a0, false, paint)
    }

    /**
     * Text entlang eines Bogens, glyphenweise gedreht.
     *
     * Unterhalb der Waagerechten werden die Glyphen um 180° gekippt und gegen
     * den Uhrzeigersinn gesetzt — sonst stuende die Zeile fuer den Traeger auf
     * dem Kopf (Uhrmacher-Konvention). Zu lange Titel werden mit Ellipse gekuerzt.
     */
    private fun curvedText(
        canvas: Canvas, raw: String, cx: Float, cy: Float, r: Float,
        centerDeg: Float, maxSweepDeg: Float, paint: Paint,
    ) {
        if (r <= 0f || raw.isEmpty()) return
        fun sweepOf(s: String) = Math.toDegrees((paint.measureText(s) / r).toDouble()).toFloat()

        var s = raw
        if (sweepOf(s) > maxSweepDeg) {
            while (s.length > 1 && sweepOf("$s…") > maxSweepDeg) s = s.dropLast(1)
            s = "$s…"
        }
        val sweep = sweepOf(s)
        val norm = ((centerDeg % 360f) + 360f) % 360f
        val flip = norm > 90f && norm < 270f
        val vCenter = -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

        var a = if (flip) centerDeg + sweep / 2f else centerDeg - sweep / 2f
        for (ch in s) {
            val adv = Math.toDegrees((paint.measureText(ch.toString()) / r).toDouble()).toFloat()
            val mid = if (flip) a - adv / 2f else a + adv / 2f
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(mid)
            canvas.translate(0f, -r)
            if (flip) canvas.rotate(180f)
            canvas.drawText(ch.toString(), 0f, vCenter, paint)
            canvas.restore()
            a += if (flip) -adv else adv
        }
    }

    private fun px(cx: Float, r: Float, dialDeg: Float) = cx + r * cos((dialDeg - 90f) * PI / 180f).toFloat()
    private fun py(cy: Float, r: Float, dialDeg: Float) = cy + r * sin((dialDeg - 90f) * PI / 180f).toFloat()

    private fun lume(a: Float) = alpha(DialGeometry.palette.getValue("lume"), a)
    private fun alpha(argb: Int, a: Float) =
        (argb and 0x00FFFFFF) or ((abs(a).coerceAtMost(1f) * 255).toInt() shl 24)

    private fun hhmm(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
}
