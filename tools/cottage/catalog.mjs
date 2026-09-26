// Catalogs a folder of schematics (.litematic, .schem v2/v3, .schematic) for choosing takes: size, block
// count, whether it fits one take, and the same build in several formats (paired by name).
//
//   node tools/cottage/catalog.mjs <folder> [out.csv]
// Fits a take: <= 55,000 blocks and a footprint of <= 144 x 144 (hours ~ blocks / 9,000 incl. terraforming).

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { parseNbt } from './litematic.mjs';

const [root, out] = process.argv.slice(2);
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (/\.(litematic|schem|schematic)$/i.test(e.name)) files.push(p);
  }
})(root);

const nbtOf = (p) => {
  const b = fs.readFileSync(p);
  return parseNbt(b[0] === 0x1f ? zlib.gunzipSync(b) : b);
};
const isAir = (n) => /(^|:)(air|cave_air|void_air|structure_void)(\[|$)/.test(n);

function info(p) {
  const ext = path.extname(p).toLowerCase();
  const t = nbtOf(p);
  if (ext === '.litematic') {
    const m = t.Metadata || {};
    const s = m.EnclosingSize || {};
    return { x: s.x, y: s.y, z: s.z, blocks: m.TotalBlocks, regions: Object.keys(t.Regions || {}).length };
  }
  if (ext === '.schem') {
    const s = t.Schematic || t;                    // v3 wraps everything in "Schematic"
    const blk = s.Blocks || s;                     // v3: Blocks{Palette,Data}; v2: Palette + BlockData
    const pal = blk.Palette || s.Palette || {};
    const data = blk.Data || s.BlockData;
    const air = new Set(Object.entries(pal).filter(([k]) => isAir(k)).map(([, v]) => v));
    let blocks = 0;
    if (data) {
      const bytes = data instanceof Uint8Array || Buffer.isBuffer(data) ? data : Uint8Array.from(data, (v) => v & 0xff);
      for (let i = 0; i < bytes.length;) {
        let v = 0, sh = 0, c;
        do { c = bytes[i++]; v |= (c & 0x7f) << sh; sh += 7; } while (c & 0x80);
        if (!air.has(v)) blocks++;
      }
    }
    return { x: s.Width, y: s.Height, z: s.Length, blocks, regions: 1 };
  }
  // MCEdit .schematic
  const bl = t.Blocks || [];
  let blocks = 0;
  for (const v of bl) if (v !== 0) blocks++;
  return { x: t.Width, y: t.Height, z: t.Length, blocks, regions: 1 };
}

const rows = [];
for (const p of files) {
  let r;
  try { r = info(p); } catch (e) { r = { error: e.message.slice(0, 60) }; }
  const name = path.basename(p).replace(/\.(litematic|schem|schematic)$/i, '');
  rows.push({ name, ext: path.extname(p).slice(1).toLowerCase(), file: path.relative(root, p), ...r });
}
// Pair formats by name: keep one row per build, preferring .litematic (the format proven in takes).
const byName = new Map();
for (const r of rows) {
  const k = r.name.toLowerCase();
  if (!byName.has(k)) byName.set(k, { ...r, formats: [r.ext] });
  else {
    const b = byName.get(k);
    b.formats.push(r.ext);
    if (r.ext === 'litematic' && !r.error) Object.assign(b, { ...r, formats: b.formats });
  }
}
const builds = [...byName.values()].filter((b) => !b.error && b.blocks > 0);
for (const b of builds) {
  b.fits = b.blocks <= 55000 && b.x <= 144 && b.z <= 144;
  b.hours = +(b.blocks / 9000).toFixed(1);
}
builds.sort((a, b) => a.blocks - b.blocks);
const errors = [...byName.values()].filter((b) => b.error);

const bucket = (lo, hi) => builds.filter((b) => b.blocks > lo && b.blocks <= hi).length;
console.log(`${files.length} files -> ${byName.size} distinct builds (${errors.length} unreadable)`);
console.log(`  <= 5k: ${bucket(0, 5000)}   5-15k: ${bucket(5000, 15000)}   15-30k: ${bucket(15000, 30000)}   30-55k: ${bucket(30000, 55000)}   > 55k: ${bucket(55000, 1e9)}`);
console.log(`  fit one take (<= 55k blocks, footprint <= 144): ${builds.filter((b) => b.fits).length}`);
if (out) {
  const esc = (s) => `"${String(s).replace(/"/g, '""')}"`;
  const lines = ['name,formats,x,y,z,blocks,fits,hours,file'];
  for (const b of builds) lines.push([esc(b.name), b.formats.join('+'), b.x, b.y, b.z, b.blocks, b.fits, b.hours, esc(b.file)].join(','));
  for (const e of errors) lines.push([esc(e.name), e.ext, '', '', '', '', '', '', esc(`ERROR ${e.error}`)].join(','));
  fs.writeFileSync(out, lines.join('\n') + '\n');
  console.log('wrote ' + out);
}
