#!/usr/bin/env node
/**
 * Schreibt core/src/main/java/de/agendadial/core/DialGeometry.kt aus
 * design/geometry.json. Damit teilen Design-Bench, WFF und Kotlin-Renderer
 * exakt dieselben Zahlen — Drift ist unmöglich, die CI prüft es per git diff.
 *
 *   node tools/genkotlin.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const GEO = JSON.parse(readFileSync(join(ROOT, "design/geometry.json"), "utf8"));

const SKIP = new Set(["$comment", "reference", "lanes", "palette"]);
const nums = Object.entries(GEO).filter(([k, v]) => !SKIP.has(k) && typeof v === "number");
const ints = new Set(["maxHourLanes", "maxRibbonLanes", "maxDayMarkers"]);

const consts = nums.map(([k, v]) =>
  ints.has(k)
    ? `    const val ${k}: Int = ${v}`
    : `    const val ${k}: Float = ${v.toFixed(4)}f`
).join("\n");

const colors = Object.entries(GEO.palette)
  .map(([k, v]) => `        "${k}" to 0xFF${v.slice(1).toUpperCase()}.toInt(),`).join("\n");

const kt = `// GENERIERT von tools/genkotlin.mjs aus design/geometry.json.
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
${consts}

    /** Kalenderfarben und Akzente, ARGB. */
    val palette: Map<String, Int> = mapOf(
${colors}
    )

    /** Stundenring-Winkel eines Zeitpunkts (Minuten seit Mitternacht). */
    fun hourAngle(minuteOfDay: Int, hours24: Boolean = false): Float =
        if (hours24) minuteOfDay / 60f * 15f else (minuteOfDay / 60f % 12f) * 30f

    /** Minutenring-Winkel innerhalb der laufenden Stunde. */
    fun minuteAngle(minuteOfDay: Int): Float = (minuteOfDay % 60) * 6f
}
`;

const out = join(ROOT, "core/src/main/java/de/agendadial/core/DialGeometry.kt");
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, kt, "utf8");
console.log(`  DialGeometry.kt  ${nums.length} Konstanten, ${Object.keys(GEO.palette).length} Farben`);
