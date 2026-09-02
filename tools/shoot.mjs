#!/usr/bin/env node
/**
 * Headless-Chrome-Screenshots der Design-Bench.
 *
 * Zwei Jobs in einem:
 *   1. preview.png fuer das Watchface-APK (Picker-Vorschau, exakt 450x450)
 *   2. Szenario-Screenshots als visuelle Regressionsbasis in der CI
 *
 *   node tools/shoot.mjs            alle Szenarien + Preview
 *   node tools/shoot.mjs preview    nur die Preview
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const BENCH = pathToFileURL(join(ROOT, "design/bench.html")).href;

const CANDIDATES = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].filter(Boolean);

const chrome = CANDIDATES.find(p => existsSync(p));
if (!chrome) {
  console.error("Kein Chrome/Chromium gefunden. CHROME_PATH setzen.");
  process.exit(1);
}

function shootUrl(out, url, w, h) {
  mkdirSync(dirname(out), { recursive: true });
  execFileSync(chrome, [
    "--headless=new", "--disable-gpu", "--hide-scrollbars",
    "--force-color-profile=srgb", "--default-background-color=00000000",
    `--window-size=${w},${h}`, "--virtual-time-budget=6000",
    `--screenshot=${out}`, url,
  ], { stdio: "ignore" });
  console.log(`  ${out.replace(ROOT + "/", "").replace(ROOT + "\\", "")}`);
}
const shoot = (out, query, w, h) => shootUrl(out, `${BENCH}${query}`, w, h);

// Picker-Vorschau des Watchfaces: buchstaeblich das Zifferblatt, damit im
// Picker nichts Fremdes auftaucht.
shoot(join(ROOT, "watchface/src/main/res/drawable-nodpi/preview.png"),
      "?only=dial&t=17:10&day=clean", 450, 450);

// Das App-Icon kommt NICHT von hier — Chrome erzwingt eine Mindest-Fenstergroesse
// und liefert bei 48 px nur einen Ausschnitt. tools/genicon.mjs rastert die
// Boegen analytisch, siehe tools/png.mjs.

if (process.argv[2] !== "preview") {
  const shots = join(ROOT, "build/shots");
  for (const [name, q] of [
    ["clean-1710",  "?only=dial&t=17:10&day=clean"],
    ["clash-1710",  "?only=dial&t=17:10&day=clash"],
    ["clash-1020",  "?only=dial&t=10:20&day=clash"],
    ["ambient",     "?only=dial&t=17:10&day=clean&ambient=1"],
    ["lanes",       "?only=dial&t=17:10&day=clash&guides=1"],
  ]) shoot(join(shots, `${name}.png`), q, 450, 450);
}
