#!/usr/bin/env node
/**
 * Generiert watchface/src/main/res/raw/watchface.xml aus design/geometry.json.
 *
 * Warum generiert und nicht handgeschrieben?
 * WFF ist deklaratives XML ohne Schleifen und ohne Variablen. Jede Geometrie-
 * zahl müsste sonst doppelt gepflegt werden — einmal in der Design-Bench,
 * einmal im Watchface. So bleibt geometry.json die einzige Quelle.
 *
 * Aufbau des erzeugten Zifferblatts:
 *
 *   Slot 0  full-screen PHOTO_IMAGE   Agenda-Layer: Indizes, Tages-Marker,
 *                                     Badges, Minutenband, Titel, Konflikt-
 *                                     nähte, Jetzt-Marke. Gerendert von
 *                                     AgendaComplicationService (Kotlin),
 *                                     Takt: einmal pro Minute.
 *   nativ   AnalogClock               Stunden- und Minutenzeiger. Bewusst
 *                                     NICHT im Bitmap: läuft das Complication-
 *                                     Update mal spät, zeigt die Uhr trotzdem
 *                                     die richtige Zeit.
 *
 * Struktur und Attribute sind an den offiziellen Samples validiert
 * (android/wear-os-samples, WatchFaceFormat/Complications + SimpleAnalog).
 *
 *   node tools/genwff.mjs
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const GEO = JSON.parse(readFileSync(join(ROOT, "design/geometry.json"), "utf8"));
const D = GEO.reference.diameterPx;
const C = D / 2;

const HANDS_FILE = join(ROOT, "tools/.hands.json");
if (!existsSync(HANDS_FILE)) {
  console.error("tools/.hands.json fehlt — zuerst `node tools/genassets.mjs` laufen lassen.");
  process.exit(1);
}
const HANDS = Object.fromEntries(JSON.parse(readFileSync(HANDS_FILE, "utf8")).map(h => [h.name, h]));

const PROVIDER = "de.agendadial.wear/de.agendadial.wear.AgendaComplicationService";
const n = v => (Math.round(v * 1000) / 1000).toString();

/** Platziert ein Zeigerbild so, dass sein Pivot exakt im Zifferblattmittelpunkt liegt. */
function handTag(tag, h) {
  const x = C - h.w / 2;
  const y = C - h.pivotY * h.h;
  return `      <${tag} resource="${h.name}" x="${n(x)}" y="${n(y)}" ` +
         `width="${h.w}" height="${h.h}" pivotX="0.5" pivotY="${h.pivotY}" />`;
}

const xml = `<?xml version="1.0" encoding="utf-8"?>
<!--
  AgendaDial — GENERIERT von tools/genwff.mjs aus design/geometry.json.
  Nicht von Hand bearbeiten. Geometrie ändern -> geometry.json -> neu generieren.
-->
<WatchFace width="${D}" height="${D}" clipShape="CIRCLE">
  <Metadata key="CLOCK_TYPE" value="ANALOG" />
  <Metadata key="PREVIEW_TIME" value="17:10:00" />

  <Scene backgroundColor="#000000">

    <!--
      Agenda-Layer. Ein bildschirmfüllender PHOTO_IMAGE-Slot ist zulässig — das
      offizielle Complications-Sample nutzt exakt dieses Muster für seinen
      Hintergrund. Der Slot ist zugleich das Tap-Ziel: ein Tipp öffnet die
      Organizer-App auf dem gerade laufenden Termin.
    -->
    <ComplicationSlot
        slotId="0"
        x="0" y="0" width="${D}" height="${D}"
        displayName="@string/slot_agenda"
        supportedTypes="PHOTO_IMAGE EMPTY"
        isCustomizable="false">
      <BoundingOval x="0" y="0" width="${D}" height="${D}" outlinePadding="2.0" />
      <DefaultProviderPolicy
          primaryProvider="${PROVIDER}"
          primaryProviderType="PHOTO_IMAGE"
          systemProvider="EMPTY"
          systemProviderType="EMPTY" />
      <Complication type="PHOTO_IMAGE">
        <PartImage x="0" y="0" width="${D}" height="${D}">
          <Image resource="[COMPLICATION.PHOTO_IMAGE]" />
          <Variant mode="AMBIENT" target="alpha" value="150" />
        </PartImage>
      </Complication>
    </ComplicationSlot>

    <!--
      Zeiger nativ. Der Agenda-Bitmap aktualisiert nur minütlich; die Zeit darf
      davon nicht abhängen. Kein Sekundenzeiger — spart Akku und passt zum
      Wireframe.
    -->
    <AnalogClock x="0" y="0" width="${D}" height="${D}">
${handTag("HourHand", HANDS.hand_hour)}
${handTag("MinuteHand", HANDS.hand_minute)}
    </AnalogClock>

    <!-- Zeigerkappe -->
    <PartDraw x="0" y="0" width="${D}" height="${D}">
      <Ellipse x="${n(C - GEO.handCap * D)}" y="${n(C - GEO.handCap * D)}"
               width="${n(GEO.handCap * D * 2)}" height="${n(GEO.handCap * D * 2)}">
        <Fill color="#0A0A0A" />
        <Stroke thickness="${n(0.005 * D)}" color="${GEO.palette.now}" />
      </Ellipse>
      <Variant mode="AMBIENT" target="alpha" value="120" />
    </PartDraw>

  </Scene>
</WatchFace>
`;

const out = join(ROOT, "watchface/src/main/res/raw/watchface.xml");
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, xml, "utf8");

// Wohlgeformtheit grob prüfen: Tag-Bilanz. Der echte Test ist der
// offizielle wff-validator in der CI.
const opens = [...xml.matchAll(/<([A-Za-z][\w.]*)(\s[^>]*?)?(\/?)>/g)]
  .filter(m => !m[0].startsWith("<?") && m[3] !== "/").map(m => m[1]);
const closes = [...xml.matchAll(/<\/([A-Za-z][\w.]*)>/g)].map(m => m[1]);
const stack = [];
let ok = true;
for (const m of xml.matchAll(/<\/?([A-Za-z][\w.]*)(\s[^>]*?)?(\/?)>/g)) {
  if (m[0].startsWith("</")) { if (stack.pop() !== m[1]) { ok = false; break; } }
  else if (m[3] !== "/") stack.push(m[1]);
}
if (!ok || stack.length) { console.error("XML-Tags unbalanciert:", stack); process.exit(1); }

console.log(`  watchface.xml  ${xml.length} B  ·  ${opens.length} Elemente, ${closes.length} Schließtags  ·  Tags balanciert`);
console.log(`  Provider: ${PROVIDER}`);
