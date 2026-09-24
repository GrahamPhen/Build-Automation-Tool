// Reads blocks straight from a Minecraft world's region files (no game needed). Format confirmed against
// the 26.2 test world:
//   region header : 1024 x 4 bytes, 3-byte sector offset + 1-byte sector count
//   chunk         : 4-byte length, 1-byte compression (2 = zlib), then NBT
//   chunk NBT     : sections[] with { Y (SIGNED byte), block_states: { palette[], data[] } }
//
//   import { loadWorldReader } from './world.mjs';
//   const at = loadWorldReader('<save>/region');  at(x, y, z) -> 'minecraft:stone' | 'minecraft:air' | 'UNLOADED'

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

import { parseNbt } from './litematic.mjs';
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

export function loadWorldReader(regionDir) {
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

