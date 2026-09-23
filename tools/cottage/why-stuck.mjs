// Why does Baritone place some blocks in a layer and not others?
//
// For the partial layer, group the required block STATES (name + properties) by whether the world has
// them. The hypothesis under test: Baritone can place plain/default states but not specific ORIENTED
// states (like axis=x), because an item's default state is what its placeability check sees - which
// would make those blocks permanently unplaceable while Baritone still reports itself active.
//
// Usage: node tools/cottage/why-stuck.mjs <schematic.litematic> <regionDir> <ox> <oy> <oz> <layer>

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

function makeSectionReader(section) {
  const bs = section.block_states;
  const palette = (bs && bs.palette) || [];
  const names = palette.map((e) => (e && e.Name) || 'minecraft:air');
  const data = bs && bs.data;
  if (!data || names.length <= 1) { const o = names[0] || 'minecraft:air'; return () => o; }
  const bits = Math.max(4, Math.ceil(Math.log2(names.length)));
  const perLong = Math.floor(64 / bits);
  const mask = (1n << BigInt(bits)) - 1n;
  return (x, y, z) => {
    const index = (y << 8) | (z << 4) | x;
    const li = Math.floor(index / perLong);
    if (li >= data.length) return 'minecraft:air';
    const shift = BigInt((index % perLong) * bits);
    return names[Number((data[li] & (mask << shift)) >> shift)] || 'minecraft:air';
  };
}

const [schematicFile, regionDir, oxs, oys, ozs, layerArg] = process.argv.slice(2);
const o = { x: Number(oxs), y: Number(oys), z: Number(ozs) };
const layer = Number(layerArg ?? 1);

const r = readLitematic(schematicFile).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;
const wantKey = (x, y, z) => { const e = r.palette[r.indices[idx(x, y, z)]]; return e ? stateKey(e) : 'minecraft:air'; };

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
  if (!secs) return 'UNLOADED';
  const rd = secs.get(y >> 4);
  return rd ? rd(x & 15, y & 15, z & 15) : 'minecraft:air';
};

// group the required states of this layer by placed / missing
const stats = new Map();
for (let z = 0; z < sz; z++) {
  for (let x = 0; x < sx; x++) {
    const want = wantKey(x, layer, z);
    if (want === 'minecraft:air') continue;
    const got = worldAt(o.x + x, o.y + layer, o.z + z);
    if (!stats.has(want)) stats.set(want, { placed: 0, missing: 0 });
    if (got === want) stats.get(want).placed++;
    else stats.get(want).missing++;
  }
}

const rows = [...stats.entries()].map(([state, s]) => ({
  state, ...s, total: s.placed + s.missing, pct: (s.placed / (s.placed + s.missing)) * 100,
  oriented: /\[/.test(state),
}));
rows.sort((a, b) => b.total - a.total);

console.log(`layer ${layer} (Y=${o.y + layer}) — ${rows.reduce((t, r2) => t + r2.total, 0)} required blocks in ${rows.length} distinct states`);
console.log('');
console.log('  placed  missing  total   %placed  oriented  state');
for (const r2 of rows) {
  console.log(`  ${String(r2.placed).padStart(6)}  ${String(r2.missing).padStart(7)}  ${String(r2.total).padStart(5)}   ${r2.pct.toFixed(0).padStart(6)}%  ${r2.oriented ? '   YES  ' : '    no  '}  ${r2.state}`);
}

const plain = rows.filter((r2) => !r2.oriented);
const oriented = rows.filter((r2) => r2.oriented);
const sum = (a, k) => a.reduce((t, r2) => t + r2[k], 0);
console.log('');
console.log('=== SUMMARY ===');
console.log(`  plain states     : ${sum(plain, 'placed')} placed / ${sum(plain, 'total')} required  (${(sum(plain, 'placed') / Math.max(1, sum(plain, 'total')) * 100).toFixed(1)}%)`);
console.log(`  oriented states  : ${sum(oriented, 'placed')} placed / ${sum(oriented, 'total')} required  (${(sum(oriented, 'placed') / Math.max(1, sum(oriented, 'total')) * 100).toFixed(1)}%)`);
