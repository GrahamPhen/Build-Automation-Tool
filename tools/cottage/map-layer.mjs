// Print a per-layer map of world-vs-schematic: which cells are done and which are missing.
// Answers "is the stall a shape, a boundary, or a cluster?" instead of guessing.
//
// Usage: node tools/cottage/map-layer.mjs <schematic.litematic> <regionDir> <ox> <oy> <oz> [fromLayer] [toLayer] [step]

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { readLitematic, parseNbt } from './litematic.mjs';

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
  if (!data || names.length <= 1) {
    const only = names[0] || 'minecraft:air';
    return () => only;
  }
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

const [schematicFile, regionDir, oxs, oys, ozs, fromArg, toArg, stepArg] = process.argv.slice(2);
const o = { x: Number(oxs), y: Number(oys), z: Number(ozs) };
const fromLayer = Number(fromArg ?? 0);
const toLayer = Number(toArg ?? 3);
const step = Number(stepArg ?? 1);

const r = readLitematic(schematicFile).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const sIdx = (x, y, z) => (y * sz + z) * sx + x;
const want = (x, y, z) => { const e = r.palette[r.indices[sIdx(x, y, z)]]; return e ? e.Name : 'minecraft:air'; };

const regionCache = new Map();
const chunkCache = new Map();
function sectionAt(cx, cz) {
  const rx = cx >> 5, rz = cz >> 5;
  const rk = `${rx},${rz}`;
  if (!regionCache.has(rk)) {
    const f = path.join(regionDir, `r.${rx}.${rz}.mca`);
    regionCache.set(rk, fs.existsSync(f) ? readRegionChunks(f) : new Map());
  }
  const raw = regionCache.get(rk).get(`${cx & 31},${cz & 31}`);
  if (!raw) return null;
  const ck = `${rk}|${cx & 31},${cz & 31}`;
  if (!chunkCache.has(ck)) {
    const nbt = parseNbt(raw);
    const map = new Map();
    for (const s of nbt.sections || []) {
      const syv = s.Y > 127 ? s.Y - 256 : s.Y;
      map.set(syv, makeSectionReader(s));
    }
    chunkCache.set(ck, map);
  }
  return chunkCache.get(ck);
}
const worldAt = (x, y, z) => {
  const secs = sectionAt(x >> 4, z >> 4);
  if (!secs) return 'UNLOADED';
  const rd = secs.get(y >> 4);
  return rd ? rd(x & 15, y & 15, z & 15) : 'minecraft:air';
};

for (let y = fromLayer; y <= toLayer; y++) {
  let req = 0, ok = 0, miss = 0;
  const missXs = [], missZs = [];
  const rows = [];
  for (let z = 0; z < sz; z += step) {
    let line = '';
    for (let x = 0; x < sx; x += step) {
      const w = want(x, y, z);
      if (w === 'minecraft:air') { line += '.'; continue; }
      req++;
      const got = worldAt(o.x + x, o.y + y, o.z + z);
      if (got === w) { line += '#'; ok++; } else if (got === 'minecraft:air' || got === 'UNLOADED') { line += 'o'; miss++; missXs.push(x); missZs.push(z); } else { line += 'x'; }
    }
    rows.push(line);
  }
  console.log('');
  console.log(`=== layer ${y} (Y=${o.y + y}): ${ok}/${req} placed, ${miss} missing   '#'=done 'o'=air 'x'=wrong '.'=not in schematic ===`);
  for (const line of rows) console.log('  ' + line);
  if (missXs.length) {
    const ax = missXs.reduce((a, b) => a + b, 0) / missXs.length;
    const az = missZs.reduce((a, b) => a + b, 0) / missZs.length;
    console.log(`  missing cells: x ${Math.min(...missXs)}..${Math.max(...missXs)} (avg ${ax.toFixed(1)}), z ${Math.min(...missZs)}..${Math.max(...missZs)} (avg ${az.toFixed(1)})`);
  }
}
