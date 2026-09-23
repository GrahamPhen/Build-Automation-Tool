// Diffs a .litematic against what is ACTUALLY in the world, so "why is it stuck?" can be answered
// with a measurement instead of an inference.
//
// This exists because every previous diagnosis was built from indirect hints: the mod's own counters,
// Baritone's chat, and progress clocks. None of those can say "the build is 3% placed and the rest of
// the blocks are outside the selection", which is the kind of thing that actually explains a wedge.
//
// Reads vanilla region files directly. Format confirmed against this world:
//   region header   : 1024 x 4 bytes, 3-byte sector offset + 1-byte sector count
//   chunk           : 4-byte length, 1-byte compression (2 = zlib), then NBT
//   chunk NBT       : sections[] with { Y (SIGNED byte), block_states: { palette[], data[] } }
//   block_states    : bits = max(4, ceil(log2(palette))), valuesPerLong = floor(64/bits), NO cross-long
//                     packing, cell order inside a section = (y << 8) | (z << 4) | x
//
// Usage:
//   node tools/cottage/verify-build.mjs <schematic.litematic> <regionDir> <originX> <originY> <originZ>

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

import { readLitematic, parseState, stateKey, parseNbt } from './litematic.mjs';

/* ---------------------------------------------------------------- region reading */

function readRegionChunks(regionFile) {
  const buf = fs.readFileSync(regionFile);
  const chunks = new Map();
  for (let i = 0; i < 1024; i++) {
    const off = buf.readUIntBE(i * 4, 3);
    if (off === 0 || buf[i * 4 + 3] === 0) continue;
    const start = off * 4096;
    if (start + 5 > buf.length) continue;
    const length = buf.readUInt32BE(start);
    const compression = buf[start + 4];
    if (length <= 0 || start + 4 + length > buf.length) continue;
    const payload = buf.subarray(start + 5, start + 4 + length);
    let raw = null;
    try {
      if (compression === 1) raw = zlib.gunzipSync(payload);
      else if (compression === 2) raw = zlib.inflateSync(payload);
      else if (compression === 3) raw = payload;
    } catch { raw = null; }
    if (!raw) continue;
    const localX = i % 32, localZ = Math.floor(i / 32);
    chunks.set(`${localX},${localZ}`, raw);
  }
  return chunks;
}

/** Decode one section's block palette indices. Returns a function (x,y,z) -> name. */
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
  const longs = data;
  return (x, y, z) => {
    const index = (y << 8) | (z << 4) | x;
    const li = Math.floor(index / perLong);
    if (li >= longs.length) return 'minecraft:air';
    const shift = BigInt((index % perLong) * bits);
    const v = Number((longs[li] & (mask << shift)) >> shift);
    return names[v] || 'minecraft:air';
  };
}

function loadWorldReader(regionDir) {
  const regionCache = new Map();
  const sectionCache = new Map();

  const chunkAt = (chunkX, chunkZ) => {
    const rx = chunkX >> 5, rz = chunkZ >> 5;
    const key = `${rx},${rz}`;
    if (!regionCache.has(key)) {
      const file = path.join(regionDir, `r.${rx}.${rz}.mca`);
      regionCache.set(key, fs.existsSync(file) ? readRegionChunks(file) : new Map());
    }
    const local = `${chunkX & 31},${chunkZ & 31}`;
    const raw = regionCache.get(key).get(local);
    if (!raw) return null;
    const ck = `${key}|${local}`;
    if (!sectionCache.has(ck)) {
      const nbt = parseNbt(raw);
      const sections = new Map();
      for (const s of nbt.sections || []) {
        const sy = s.Y > 127 ? s.Y - 256 : s.Y;
        sections.set(sy, makeSectionReader(s));
      }
      sectionCache.set(ck, sections);
    }
    return sectionCache.get(ck);
  };

  return (x, y, z) => {
    const sections = chunkAt(x >> 4, z >> 4);
    if (!sections) return 'UNLOADED';
    const reader = sections.get(y >> 4);
    if (!reader) return 'minecraft:air';
    return reader(x & 15, y & 15, z & 15);
  };
}

/* ---------------------------------------------------------------- main */

const [schematicFile, regionDir, oxs, oys, ozs] = process.argv.slice(2);
if (!schematicFile || !regionDir) {
  console.error('usage: verify-build.mjs <schematic.litematic> <regionDir> <originX> <originY> <originZ>');
  process.exit(2);
}
const origin = { x: Number(oxs), y: Number(oys), z: Number(ozs) };

const { regions, metadata } = readLitematic(schematicFile);
const r = regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const sIdx = (x, y, z) => (y * sz + z) * sx + x;
const schematicName = (x, y, z) => {
  const e = r.palette[r.indices[sIdx(x, y, z)]];
  return e ? e.Name : 'minecraft:air';
};
const schematicKey = (x, y, z) => {
  const e = r.palette[r.indices[sIdx(x, y, z)]];
  return e ? stateKey(e) : 'minecraft:air';
};

console.log(`schematic : ${path.basename(schematicFile)} (${metadata.Name}), ${sx}x${sy}x${sz}`);
console.log(`origin    : (${origin.x}, ${origin.y}, ${origin.z})`);
console.log(`world     : ${regionDir}`);
console.log('');

const worldAt = loadWorldReader(regionDir);

let required = 0, correct = 0, wrongBlock = 0, missing = 0, worldEmptyCells = 0;
const missingTypes = new Map();
const wrongTypes = new Map();
let missMinX = 1e9, missMaxX = -1, missMinY = 1e9, missMaxY = -1, missMinZ = 1e9, missMaxZ = -1;
const perLayerRequired = new Array(sy).fill(0);
const perLayerCorrect = new Array(sy).fill(0);
const perLayerMissing = new Array(sy).fill(0);

for (let y = 0; y < sy; y++) {
  for (let z = 0; z < sz; z++) {
    for (let x = 0; x < sx; x++) {
      const want = schematicName(x, y, z);
      if (want === 'minecraft:air') continue;
      required++;
      perLayerRequired[y]++;
      const wx = origin.x + x, wy = origin.y + y, wz = origin.z + z;
      const got = worldAt(wx, wy, wz);
      if (got === want) {
        correct++;
        perLayerCorrect[y]++;
      } else if (got === 'UNLOADED') {
        missing++;
        perLayerMissing[y]++;
        missingTypes.set('(chunk not generated)', (missingTypes.get('(chunk not generated)') || 0) + 1);
      } else if (got === 'minecraft:air') {
        missing++;
        perLayerMissing[y]++;
        missingTypes.set(want, (missingTypes.get(want) || 0) + 1);
        missMinX = Math.min(missMinX, wx); missMaxX = Math.max(missMaxX, wx);
        missMinY = Math.min(missMinY, wy); missMaxY = Math.max(missMaxY, wy);
        missMinZ = Math.min(missMinZ, wz); missMaxZ = Math.max(missMaxZ, wz);
      } else {
        wrongBlock++;
        wrongTypes.set(`${want} <- ${got}`, (wrongTypes.get(`${want} <- ${got}`) || 0) + 1);
      }
    }
  }
}

const pct = (n) => ((n / Math.max(1, required)) * 100).toFixed(1);
console.log('=== RESULT ===');
console.log(`required (non-air cells in the schematic): ${required}`);
console.log(`correct in world                         : ${correct}  (${pct(correct)}%)`);
console.log(`missing (world has air)                  : ${missing}  (${pct(missing)}%)`);
console.log(`wrong block present                      : ${wrongBlock}  (${pct(wrongBlock)}%)`);
console.log('');

console.log('=== per layer (first 12 with any content) ===');
console.log('  layer  required  correct  missing');
for (let y = 0; y < sy; y++) {
  if (perLayerRequired[y] === 0) continue;
  console.log(`  ${String(y).padStart(5)}  ${String(perLayerRequired[y]).padStart(8)}  ${String(perLayerCorrect[y]).padStart(7)}  ${String(perLayerMissing[y]).padStart(7)}`);
  if (y > 12 && perLayerCorrect[y] === 0 && y > 20) break;
}
console.log('');

const top = (m) => [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12);
if (missingTypes.size) {
  console.log('=== top MISSING block types ===');
  for (const [t, c] of top(missingTypes)) console.log(`  ${String(c).padStart(6)}  ${t}`);
  console.log('');
  console.log(`missing blocks bounding box: (${missMinX}, ${missMinY}, ${missMinZ}) to (${missMaxX}, ${missMaxY}, ${missMaxZ})`);
}
if (wrongTypes.size) {
  console.log('');
  console.log('=== top WRONG blocks (schematic <- world) ===');
  for (const [t, c] of top(wrongTypes)) console.log(`  ${String(c).padStart(6)}  ${t}`);
}
