// Finds where previous builds stand in a world, from the region files alone (no game needed): chunks whose
// block palettes hold blocks that do not generate naturally (concrete, stained glass, ...), grouped into
// sites. Prints "x y z blocks" per site - the format of config/startbuild-sites.txt (StartBuild keeps new
// sites far from every line in it).
//
//   node tools/cottage/find-builds.mjs <regionDir> [out.txt]

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { parseNbt } from './litematic.mjs';

const MARKERS = [/_concrete$/, /_concrete_powder$/, /stained_glass/, /^minecraft:glass_pane$/, /^minecraft:polished_basalt$/,
  /^minecraft:jack_o_lantern$/, /_glazed_terracotta$/, /^minecraft:lantern$/, /^minecraft:soul_lantern$/];

const [regionDir, out] = process.argv.slice(2);
const hits = [];                                   // [chunkX, chunkZ, markerCount, topY]
for (const f of fs.readdirSync(regionDir)) {
  const m = f.match(/^r\.(-?\d+)\.(-?\d+)\.mca$/);
  if (!m) continue;
  const rx = +m[1], rz = +m[2];
  const buf = fs.readFileSync(path.join(regionDir, f));
  if (buf.length < 8192) continue;
  for (let i = 0; i < 1024; i++) {
    const off = buf.readUIntBE(i * 4, 3);
    if (off === 0) continue;
    const start = off * 4096;
    if (start + 5 > buf.length) continue;
    const len = buf.readUInt32BE(start);
    if (len <= 0 || start + 4 + len > buf.length) continue;
    let raw;
    try {
      const p = buf.subarray(start + 5, start + 4 + len);
      raw = buf[start + 4] === 1 ? zlib.gunzipSync(p) : buf[start + 4] === 2 ? zlib.inflateSync(p) : p;
    } catch { continue; }
    let nbt;
    try { nbt = parseNbt(raw); } catch { continue; }
    let n = 0, topY = -999;
    for (const s of nbt.sections || []) {
      const sy = s.Y > 127 ? s.Y - 256 : s.Y;
      for (const e of (s.block_states && s.block_states.palette) || []) {
        if (e && MARKERS.some((r) => r.test(e.Name))) { n++; topY = Math.max(topY, sy * 16); }
      }
    }
    if (n >= 2) hits.push([rx * 32 + (i % 32), rz * 32 + Math.floor(i / 32), n, topY]);
  }
}
// Group chunks within 8 chunks of each other into one site.
const sites = [];
for (const h of hits) {
  const s = sites.find((q) => Math.abs(q.cx - h[0]) <= 8 && Math.abs(q.cz - h[1]) <= 8);
  if (s) { s.list.push(h); s.cx = s.list.reduce((a, q) => a + q[0], 0) / s.list.length; s.cz = s.list.reduce((a, q) => a + q[1], 0) / s.list.length; }
  else sites.push({ cx: h[0], cz: h[1], list: [h] });
}
const lines = sites.filter((s) => s.list.length >= 2)
  .map((s) => `${Math.round(s.cx * 16 + 8)} ${Math.max(...s.list.map((q) => q[3]))} ${Math.round(s.cz * 16 + 8)} ${s.list.length}-chunks`);
console.log(lines.join('\n') || '(no builds found)');
if (out) fs.writeFileSync(out, lines.join('\n') + '\n');
