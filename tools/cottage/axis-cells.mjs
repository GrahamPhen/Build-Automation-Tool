// For a given schematic layer, list every non-default-state cell (e.g. axis=x) and show what is
// ACTUALLY in the world at that spot, comparing FULL states (name + properties) — unlike
// verify-build.mjs / why-stuck.mjs, whose world readers compare names only.
//
// Usage: node tools/cottage/axis-cells.mjs <schematic.litematic> <regionDir> <ox> <oy> <oz> <layer>
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { readLitematic, parseNbt, stateKey } from './litematic.mjs';

function readRegionChunks(regionFile) {
  const buf = fs.readFileSync(regionFile);
  const chunks = new Map();
  for (let i = 0; i < 1024; i++) {
    const off = buf.readUIntBE(i * 4, 3);
    if (off === 0 || buf[i * 4 + 3] === 0) continue;
    const start = off * 4096;
    if (start + 5 > buf.length) continue;
    const length = buf.readUInt32BE(start);
    const comp = buf[start + 4];
    if (length <= 0 || start + 4 + length > buf.length) continue;
    const payload = buf.subarray(start + 5, start + 4 + length);
    let raw = null;
    try { raw = comp === 1 ? zlib.gunzipSync(payload) : comp === 2 ? zlib.inflateSync(payload) : comp === 3 ? payload : null; } catch { raw = null; }
    if (raw) chunks.set(`${i % 32},${Math.floor(i / 32)}`, raw);
  }
  return chunks;
}

// Return FULL palette entry (Name + Properties) for each cell, so stateKey can be applied.
function makeSectionReader(section) {
  const bs = section.block_states;
  const palette = (bs && bs.palette) || [];
  const data = bs && bs.data;
  if (!data || palette.length <= 1) {
    const only = palette[0] || { Name: 'minecraft:air' };
    return () => only;
  }
  const bits = Math.max(4, Math.ceil(Math.log2(palette.length)));
  const perLong = Math.floor(64 / bits);
  const mask = (1n << BigInt(bits)) - 1n;
  return (x, y, z) => {
    const index = (y << 8) | (z << 4) | x;
    const li = Math.floor(index / perLong);
    if (li >= data.length) return { Name: 'minecraft:air' };
    const shift = BigInt((index % perLong) * bits);
    const v = Number((data[li] & (mask << shift)) >> shift);
    return palette[v] || { Name: 'minecraft:air' };
  };
}

const [schematicFile, regionDir, oxs, oys, ozs, layerArg] = process.argv.slice(2);
const o = { x: Number(oxs), y: Number(oys), z: Number(ozs) };
const layer = Number(layerArg ?? 1);

const r = readLitematic(schematicFile).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;

const regionCache = new Map(); const chunkCache = new Map();
function sectionAt(cx, cz) {
  const rx = cx >> 5, rz = cz >> 5, rk = `${rx},${rz}`;
  if (!regionCache.has(rk)) {
    const f = path.join(regionDir, `r.${rx}.${rz}.mca`);
    regionCache.set(rk, fs.existsSync(f) ? readRegionChunks(f) : new Map());
  }
  const raw = regionCache.get(rk).get(`${cx & 31},${cz & 31}`);
  if (!raw) return null;
  const ck = `${rk}|${cx & 31},${cz & 31}`;
  if (!chunkCache.has(ck)) {
    const nbt = parseNbt(raw); const m = new Map();
    for (const s of nbt.sections || []) m.set(s.Y > 127 ? s.Y - 256 : s.Y, makeSectionReader(s));
    chunkCache.set(ck, m);
  }
  return chunkCache.get(ck);
}
const worldAt = (x, y, z) => {
  const secs = sectionAt(x >> 4, z >> 4);
  if (!secs) return null;               // chunk not on disk / not generated
  const rd = secs.get(y >> 4);
  return rd ? rd(x & 15, y & 15, z & 15) : { Name: 'minecraft:air' };
};

// Collect every non-default-state cell in the target layer.
const cells = [];
for (let z = 0; z < sz; z++) {
  for (let x = 0; x < sx; x++) {
    const e = r.palette[r.indices[idx(x, layer, z)]];
    if (!e || e.Name === 'minecraft:air') continue;
    const props = e.Properties || {};
    const key = stateKey(e);
    // non-default: any property present that isn't the block's natural default. For pillars/logs that
    // means axis != y. We flag any cell whose stateKey contains '[' AND is not a "default" orientation.
    cells.push({ x, z, want: key, props });
  }
}

// default axis is y for logs/pillars; anything with a '[' and not axis=y is the interesting class.
const interesting = cells.filter((c) => /\[/.test(c.want) && !/axis=y/.test(c.want));
console.log(`layer ${layer} (Y=${o.y + layer}): ${cells.length} solid cells, ${interesting.length} oriented (non-axis=y)`);
console.log('');
console.log('  x    z    want                                  world                                  match');
const counts = { match: 0, air: 0, wrong: 0, unloaded: 0 };
for (const c of interesting) {
  const wx = o.x + c.x, wz = o.z + c.z, wy = o.y + layer;
  const got = worldAt(wx, wy, wz);
  let gotKey;
  if (got === null) gotKey = 'UNLOADED';
  else gotKey = stateKey(got);
  const match = gotKey === c.want;
  if (match) counts.match++;
  else if (gotKey === 'UNLOADED') counts.unloaded++;
  else if (gotKey === 'minecraft:air') counts.air++;
  else counts.wrong++;
  console.log(`  ${String(c.x).padStart(3)}  ${String(c.z).padStart(3)}  ${c.want.padEnd(38)}  ${gotKey.padEnd(38)}  ${match ? 'YES' : 'no'}`);
}
console.log('');
console.log(`=== SUMMARY (oriented cells in layer ${layer}) ===`);
console.log(`  exact state match : ${counts.match}`);
console.log(`  world is AIR      : ${counts.air}`);
console.log(`  wrong state       : ${counts.wrong}`);
console.log(`  chunk unloaded    : ${counts.unloaded}`);
