// Why does Baritone stall on layer 1 with ~131 default-state cells left? For every missing cell in
// the target layer, report its support geometry: what is BELOW it (schematic vs actual world) and how
// many solid neighbours it has. The hypothesis under test: Baritone can't place a cell that floats
// over air (no world block below) unless it can click a horizontal face, and mutually-supporting
// floating cells are a deadlock neither can be clicked into place.
//
// Usage: node tools/cottage/missing-geometry.mjs <schematic> <regionDir> <ox> <oy> <oz> <layer>
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

const [schematicFile, regionDir, oxs, oys, ozs, layerArg] = process.argv.slice(2);
const o = { x: Number(oxs), y: Number(oys), z: Number(ozs) };
const layer = Number(layerArg ?? 1);

const r = readLitematic(schematicFile).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;
const schemName = (x, y, z) => {
  if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 'minecraft:air';
  const e = r.palette[r.indices[idx(x, y, z)]];
  return e ? e.Name : 'minecraft:air';
};

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
const worldName = (x, y, z) => {
  const secs = sectionAt(x >> 4, z >> 4);
  if (!secs) return 'UNLOADED';
  const rd = secs.get(y >> 4);
  return rd ? rd(x & 15, y & 15, z & 15).Name : 'minecraft:air';
};

// collect missing cells in this layer
const missing = [];
for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
  const want = schemName(x, layer, z);
  if (want === 'minecraft:air') continue;
  const got = worldName(o.x + x, o.y + layer, o.z + z);
  if (got === want) continue;
  missing.push({ x, z, want, got });
}

console.log(`layer ${layer} (Y=${o.y + layer}): ${missing.length} missing cells`);
console.log('');
console.log('  x    z   want (name)                    worldBelow  schemBelow  horizSchem  horizWorld');
const buckets = { belowSolid: 0, belowAir: 0, belowUnloaded: 0 };
for (const m of missing) {
  const wx = o.x + m.x, wy = o.y + layer, wz = o.z + m.z;
  const schemBelow = schemName(m.x, layer - 1, m.z);
  const worldBelow = worldName(wx, wy - 1, wz);
  const horizSchem = [schemName(m.x - 1, layer, m.z), schemName(m.x + 1, layer, m.z),
    schemName(m.x, layer, m.z - 1), schemName(m.x, layer, m.z + 1)]
    .filter((n) => n !== 'minecraft:air').length;
  const horizWorld = [worldName(wx - 1, wy, wz), worldName(wx + 1, wy, wz),
    worldName(wx, wy, wz - 1), worldName(wx, wy, wz + 1)]
    .filter((n) => n !== 'minecraft:air').length;
  if (worldBelow === 'minecraft:air') buckets.belowAir++;
  else if (worldBelow === 'UNLOADED') buckets.belowUnloaded++;
  else buckets.belowSolid++;
  console.log(`  ${String(m.x).padStart(3)}  ${String(m.z).padStart(3)}  ${m.want.padEnd(30)}  ${String(worldBelow).padStart(9)}  ${String(schemBelow).padStart(10)}  ${String(horizSchem).padStart(9)}  ${String(horizWorld).padStart(9)}`);
}
console.log('');
console.log('=== worldBelow buckets (of the missing cells) ===');
console.log(`  below solid   : ${buckets.belowSolid}`);
console.log(`  below AIR     : ${buckets.belowAir}`);
console.log(`  below UNLOADED: ${buckets.belowUnloaded}`);
