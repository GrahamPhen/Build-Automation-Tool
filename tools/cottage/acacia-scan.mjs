// Scan the ENTIRE schematic for acacia_log cells and report their world state (full-state compare).
// Usage: node tools/cottage/acacia-scan.mjs <schematic.litematic> <regionDir> <ox> <oy> <oz>
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
  const data = bs && bs.data;
  if (!data || palette.length <= 1) { const o = palette[0] || { Name: 'minecraft:air' }; return () => o; }
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

const [schematicFile, regionDir, oxs, oys, ozs] = process.argv.slice(2);
const o = { x: Number(oxs), y: Number(oys), z: Number(ozs) };
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
  if (!secs) return null;
  const rd = secs.get(y >> 4);
  return rd ? rd(x & 15, y & 15, z & 15) : { Name: 'minecraft:air' };
};

let n = 0, correct = 0, wrongOrientation = 0, air = 0, other = 0;
for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
  const e = r.palette[r.indices[idx(x, y, z)]];
  if (!e || e.Name !== 'minecraft:acacia_log') continue;
  n++;
  const want = stateKey(e);
  const got = worldAt(o.x + x, o.y + y, o.z + z);
  const gotKey = got === null ? 'UNLOADED' : stateKey(got);
  if (gotKey === want) correct++;
  else if (gotKey === 'minecraft:acacia_log[axis=y]') wrongOrientation++;
  else if (gotKey === 'minecraft:air') air++;
  else other++;
  console.log(`  (${String(x).padStart(2)},${String(y).padStart(2)},${String(z).padStart(2)}) want=${want}  world=${gotKey}`);
}
console.log('');
console.log(`acacia_log cells: ${n}  exact=${correct}  axis=y(wrong)=${wrongOrientation}  air=${air}  other=${other}`);
