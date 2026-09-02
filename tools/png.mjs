/**
 * Minimaler PNG-Writer plus ein analytischer Rasterizer fuer Kreisboegen.
 *
 * Warum nicht Chrome wie bei den Zifferblatt-Screenshots? Chrome erzwingt eine
 * Mindest-Fenstergroesse; 48-px-Icons kommen als Ausschnitt statt als Vollbild
 * heraus. Ein Bogen laesst sich exakt testen (Radius im Band UND Winkel im
 * Bereich, oder innerhalb einer der beiden runden Kappen), das ist praeziser
 * als jede Polygonnaeherung und braucht keinen Browser — laeuft also auch auf
 * den CI-Runnern.
 */
import { deflateSync } from "node:zlib";

// ── PNG (RGBA, 8 bit, Filter None) ────────────────────────────────────────
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

export function encodePng(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

export const hexToRgb = hex => {
  const n = parseInt(hex.replace("#", ""), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
};

// ── Analytischer Rasterizer ───────────────────────────────────────────────

/** Winkel eines Punktes im Zifferblattmodell: 0 Grad = 12 Uhr, im Uhrzeigersinn. */
const dialAngle = (dx, dy) => {
  const a = Math.atan2(dx, -dy) * 180 / Math.PI;
  return a < 0 ? a + 360 : a;
};

const inArc = (px, py, a) => {
  const dx = px - a.cx, dy = py - a.cy;
  const hw = a.stroke / 2;

  // Runde Kappen an beiden Enden
  for (const ang of [a.from, a.to]) {
    const r = (ang - 90) * Math.PI / 180;
    const ex = a.cx + a.radius * Math.cos(r), ey = a.cy + a.radius * Math.sin(r);
    if ((px - ex) ** 2 + (py - ey) ** 2 <= hw * hw) return true;
  }

  // Band: Radius passt UND Winkel liegt im Bereich
  const d = Math.hypot(dx, dy);
  if (d < a.radius - hw || d > a.radius + hw) return false;
  const sweep = ((a.to - a.from) % 360 + 360) % 360;
  const rel = ((dialAngle(dx, dy) - a.from) % 360 + 360) % 360;
  return rel <= sweep;
};

const inCircle = (px, py, c) => (px - c.cx) ** 2 + (py - c.cy) ** 2 <= c.r * c.r;

/**
 * Rendert Formen der Reihe nach in ein RGBA-Buffer. 4x4-Supersampling liefert
 * die Kantenglaettung; jede Form wird mit ihrer eigenen Deckung ueber das
 * bisherige Bild komponiert.
 *
 * @param {number} size Kantenlaenge in Pixeln
 * @param {Array<{kind:'circle'|'arc', color:string}>} shapes von hinten nach vorn
 */
export function rasterize(size, shapes) {
  const buf = Buffer.alloc(size * size * 4);
  const SS = 4, inv = 1 / (SS * SS);

  for (const s of shapes) {
    const [r, g, b] = hexToRgb(s.color);
    const test = s.kind === "circle" ? inCircle : inArc;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        let cov = 0;
        for (let sy = 0; sy < SS; sy++) {
          for (let sx = 0; sx < SS; sx++) {
            if (test(x + (sx + 0.5) / SS, y + (sy + 0.5) / SS, s)) cov++;
          }
        }
        if (!cov) continue;
        const a = cov * inv;
        const o = (y * size + x) * 4;
        const da = buf[o + 3] / 255;
        const oa = a + da * (1 - a);                       // Source-over
        buf[o]     = Math.round((r * a + buf[o]     * da * (1 - a)) / oa);
        buf[o + 1] = Math.round((g * a + buf[o + 1] * da * (1 - a)) / oa);
        buf[o + 2] = Math.round((b * a + buf[o + 2] * da * (1 - a)) / oa);
        buf[o + 3] = Math.round(oa * 255);
      }
    }
  }
  return buf;
}
