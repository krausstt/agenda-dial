#!/usr/bin/env node
/**
 * Erzeugt die Zeiger-PNGs für das Watch Face Format.
 *
 * WFF referenziert Zeiger über `resource="..."` auf ein Drawable. Statt Binär-
 * Assets ins Repo zu legen, rastern wir sie hier deterministisch aus
 * geometry.json — damit bleibt auch die Zeigerform an der Design-Bench hängen.
 *
 * Kein npm. Nur Node-Bordmittel (zlib).
 *
 *   node tools/genassets.mjs
 */
import { deflateSync } from "node:zlib";
import { writeFileSync, readFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const GEO = JSON.parse(readFileSync(join(ROOT, "design/geometry.json"), "utf8"));
const D = GEO.reference.diameterPx;
const OUT = join(ROOT, "watchface/src/main/res/drawable-nodpi");

// ── Minimaler PNG-Writer (RGBA, 8 bit, keine Filter) ──────────────────────
const crcTable = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
const crc32 = buf => {
  let c = -1;
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
};
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
};
function encodePng(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;                                  // Filter: None
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// ── Polygon-Rasterizer mit 4×4-Supersampling ──────────────────────────────
function rasterize(w, h, poly, [r, g, b]) {
  const buf = Buffer.alloc(w * h * 4);
  const SS = 4, inv = 1 / (SS * SS);
  const inside = (px, py) => {
    let hit = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const [xi, yi] = poly[i], [xj, yj] = poly[j];
      if ((yi > py) !== (yj > py) && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) hit = !hit;
    }
    return hit;
  };
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let cov = 0;
      for (let sy = 0; sy < SS; sy++)
        for (let sx = 0; sx < SS; sx++)
          if (inside(x + (sx + 0.5) / SS, y + (sy + 0.5) / SS)) cov++;
      if (!cov) continue;
      const o = (y * w + x) * 4;
      buf[o] = r; buf[o + 1] = g; buf[o + 2] = b;
      buf[o + 3] = Math.round(cov * inv * 255);
    }
  }
  return buf;
}

/**
 * Zeiger als sich verjüngendes Polygon, Spitze oben, Drehpunkt unten.
 * Das Bild ist so hoch wie Zeigerlänge + Überhang hinter dem Drehpunkt;
 * pivotY teilt WFF später mit, wo gedreht wird.
 */
function hand(lengthFrac, widthFrac, name) {
  const len = Math.round(lengthFrac * D);
  const wid = Math.max(4, Math.round(widthFrac * D));
  const tail = Math.round(0.035 * D);
  const w = wid, h = len + tail;
  const tip = Math.round(wid * 0.9);
  const poly = [
    [0, h], [0, tip], [w / 2, 0], [w, tip], [w, h],
  ];
  const px = rasterize(w, h, poly, [232, 228, 216]);
  writeFileSync(join(OUT, `${name}.png`), encodePng(w, h, px));
  const pivotY = (h - tail) / h;
  return { name, w, h, pivotY: +pivotY.toFixed(6), len, tail };
}

mkdirSync(OUT, { recursive: true });
const hands = [
  hand(GEO.handHourLength,   GEO.handHourWidth,   "hand_hour"),
  hand(GEO.handMinuteLength, GEO.handMinuteWidth, "hand_minute"),
];

// Die Pivot-Werte braucht der WFF-Generator — als JSON danebenlegen.
writeFileSync(join(ROOT, "tools/.hands.json"), JSON.stringify(hands, null, 2));
for (const x of hands) console.log(`  ${x.name}.png  ${x.w}×${x.h}  pivotY=${x.pivotY}`);
