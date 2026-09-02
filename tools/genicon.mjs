#!/usr/bin/env node
/**
 * Erzeugt das App-Icon aus der icon-Spec in design/geometry.json.
 *
 * Das Motiv ist die kleinstmoegliche ehrliche Reduktion des Zifferblatts:
 * zwei konzentrische Halbkreise, die sich ueber 60 Grad ueberlappen — die
 * beiden Ribbon-Bahnen. Ein Screenshot des vollen Zifferblatts waere bei 48 dp
 * nur Farbbrei; zwei dicke Boegen bleiben erkennbar.
 *
 * Ausgabe, Struktur wie in Googles ComposeStarter-Sample:
 *   drawable/ic_launcher_foreground.xml   Vector, 108 dp, Motiv in der Safe Zone
 *   drawable/ic_launcher_background.xml   Vector, einfarbige Flaeche
 *   mipmap-anydpi-v26/ic_launcher.xml     Adaptive Icon
 *   mipmap-anydpi-v26/ic_launcher_round.xml
 *   design/icon.svg                       Quelle fuer die Raster-Fallbacks,
 *                                         rasterisiert von tools/shoot.mjs
 *
 *   node tools/genicon.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { encodePng, rasterize } from "./png.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const GEO = JSON.parse(readFileSync(join(ROOT, "design/geometry.json"), "utf8"));
const ICON = GEO.icon;

const n = v => (Math.round(v * 1000) / 1000).toString();
const rad = d => (d - 90) * Math.PI / 180;

/**
 * Bogen als SVG/VectorDrawable-Pfad. Winkel im Zifferblattmodell
 * (0 Grad = 12 Uhr, im Uhrzeigersinn); SVG rechnet ab 3 Uhr, daher -90.
 * sweepFlag ist 1, weil die y-Achse nach unten zeigt.
 */
function arcPath(cx, cy, r, from, to) {
  const p = a => [cx + r * Math.cos(rad(a)), cy + r * Math.sin(rad(a))];
  const [x0, y0] = p(from);
  const [x1, y1] = p(to);
  const large = (to - from) % 360 > 180 ? 1 : 0;
  return `M${n(x0)},${n(y0)} A${n(r)},${n(r)} 0 ${large} 1 ${n(x1)},${n(y1)}`;
}

/** Motiv in einem quadratischen Feld der Kantenlaenge `box`, Motivgroesse `scale`. */
function marks(box, scale) {
  const c = box / 2;
  const k = (box * scale) / 100;   // Spec ist auf 100x100 normiert
  return ICON.arcs.map(a => ({
    d: arcPath(c, c, a.radius * k, a.from, a.to),
    width: a.stroke * k,
    color: GEO.palette[a.color] ?? a.color,
  }));
}

// ── Adaptive Icon: 108 dp Canvas, Motiv nur in der Safe Zone (72 von 108) ──
const fgPaths = marks(108, ICON.safeZone).map(m =>
  `    <path
        android:pathData="${m.d}"
        android:strokeColor="${m.color}"
        android:strokeWidth="${n(m.width)}"
        android:strokeLineCap="round" />`).join("\n");

const foreground = `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERIERT von tools/genicon.mjs aus design/geometry.json. Nicht editieren. -->
<!--
  Vordergrund des Adaptive Icon. Der Launcher darf alles ausserhalb der
  zentralen 72 von 108 dp wegmaskieren, deshalb sitzt das Motiv komplett
  innerhalb dieser Safe Zone.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${fgPaths}
</vector>
`;

const background = `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERIERT von tools/genicon.mjs aus design/geometry.json. Nicht editieren. -->
<!--
  Hintergrund des Adaptive Icon. Bewusst nicht reines Schwarz: der Wear-Launcher
  steht selbst auf Schwarz, ein schwarzes Icon haette dort keine Kontur.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M0,0h108v108h-108z"
        android:fillColor="${ICON.background}" />
</vector>
`;

const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERIERT von tools/genicon.mjs. Nicht editieren. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
`;

// ── SVG fuer die Raster-Fallbacks. Rund beschnitten, damit dieselbe Datei ──
// fuer ic_launcher und ic_launcher_round taugt. Motiv hier groesser: ein
// Legacy-Icon hat keine Safe Zone, es wird nur rund maskiert.
const R = 256;
const svgPaths = marks(R * 2, 0.84).map(m =>
  `  <path d="${m.d}" stroke="${m.color}" stroke-width="${n(m.width)}" stroke-linecap="round" fill="none"/>`
).join("\n");

// width/height auf 100 %, damit Chrome beim Rasterisieren auf die Fenstergroesse
// skaliert statt das SVG in Naturgroesse zu rendern und nur den Ausschnitt zu
// erwischen. Die Geometrie haelt die viewBox.
const svg = `<svg xmlns="http://www.w3.org/2000/svg"
     width="100%" height="100%" viewBox="0 0 ${R * 2} ${R * 2}"
     preserveAspectRatio="xMidYMid meet">
  <!-- GENERIERT von tools/genicon.mjs aus design/geometry.json. Nicht editieren. -->
  <circle cx="${R}" cy="${R}" r="${R}" fill="${ICON.background}"/>
${svgPaths}
</svg>
`;

const write = (rel, content) => {
  const p = join(ROOT, rel);
  mkdirSync(dirname(p), { recursive: true });
  // Buffer roh schreiben, Strings als UTF-8 — sonst zerlegt es die PNGs.
  writeFileSync(p, content, Buffer.isBuffer(content) ? undefined : "utf8");
  console.log(`  ${rel}`);
};

write("wear/src/main/res/drawable/ic_launcher_foreground.xml", foreground);
write("wear/src/main/res/drawable/ic_launcher_background.xml", background);
write("wear/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", adaptive);
write("wear/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", adaptive);
write("design/icon.svg", svg);

// ── Raster-Fallbacks ──────────────────────────────────────────────────────
// Bei minSdk 34 gewinnt zur Laufzeit immer das Adaptive Icon aus
// mipmap-anydpi-v26. Diese PNGs existieren fuer Tooling und Store-Oberflaechen,
// die die Bitmap direkt lesen. Rund beschnitten, damit dieselbe Datei fuer
// ic_launcher und ic_launcher_round taugt.
for (const [density, px] of Object.entries(ICON.rasterDensities)) {
  const c = px / 2;
  const k = px * 0.84 / 100;   // Legacy-Icon hat keine Safe Zone, nur runde Maske
  const shapes = [
    { kind: "circle", cx: c, cy: c, r: c, color: ICON.background },
    ...ICON.arcs.map(a => ({
      kind: "arc", cx: c, cy: c,
      radius: a.radius * k, stroke: a.stroke * k,
      from: a.from, to: a.to,
      color: GEO.palette[a.color] ?? a.color,
    })),
  ];
  const png = encodePng(px, px, rasterize(px, shapes));
  for (const name of ["ic_launcher", "ic_launcher_round"]) {
    write(`wear/src/main/res/mipmap-${density}/${name}.png`, png);
  }
}
