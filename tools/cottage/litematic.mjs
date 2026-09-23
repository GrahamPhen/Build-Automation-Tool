// Minimal .litematic reader/writer.
//
// Written against two real files in the BuildRecording instance rather than from memory:
//   haunted_80.litematic      80x80x64, 111-entry palette, 7 bits, 44800 longs
//   ArenaCharmander.litematic 221x228x252, 8-entry palette, 3 bits, 595209 longs
// Both agree on the schema and confirm the packing rule below:
//
//   root: MinecraftDataVersion(int) Version(int) Metadata(compound) Regions(compound)
//   region: Position{x,y,z} Size{x,y,z} BlockStatePalette(list<compound>)
//           BlockStates(long[]) PendingBlockTicks(list) PendingFluidTicks(list)
//           Entities(list) TileEntities(list)
//   bits = max(2, ceil(log2(palette.length)))
//   longs = ceil(volume * bits / 64)
//
// Indices form ONE continuous little-endian bitstream: value 0 occupies the lowest `bits` of long 0,
// value 1 the next `bits`, and so on across long boundaries. `selfTest()` proves that by repacking a
// real file and comparing the long arrays byte for byte.
//
// Index order inside a region is x fastest, then z, then y:  i = (y * sizeZ + z) * sizeX + x
// (Litematica's own order - the same one tools/schematic-catalog.mjs reads.)

import fs from 'node:fs';
import zlib from 'node:zlib';

export const TAG = {
  BYTE: 1, SHORT: 2, INT: 3, LONG: 4, FLOAT: 5, DOUBLE: 6, BYTE_ARRAY: 7,
  STRING: 8, LIST: 9, COMPOUND: 10, INT_ARRAY: 11, LONG_ARRAY: 12,
};

const MASK64 = (1n << 64n) - 1n;

/* ------------------------------------------------------------------ reading */

class Reader {
  constructor(buf) { this.b = buf; this.i = 0; }
  u1() { return this.b[this.i++]; }
  i16() { const v = this.b.readInt16BE(this.i); this.i += 2; return v; }
  i32() { const v = this.b.readInt32BE(this.i); this.i += 4; return v; }
  i64() { const v = this.b.readBigInt64BE(this.i); this.i += 8; return v; }
  f32() { const v = this.b.readFloatBE(this.i); this.i += 4; return v; }
  f64() { const v = this.b.readDoubleBE(this.i); this.i += 8; return v; }
  str() {
    const n = this.b.readUInt16BE(this.i);
    this.i += 2;
    const s = this.b.toString('utf8', this.i, this.i + n);
    this.i += n;
    return s;
  }
  payload(t) {
    switch (t) {
      case TAG.BYTE: return this.b[this.i++];
      case TAG.SHORT: return this.i16();
      case TAG.INT: return this.i32();
      case TAG.LONG: return this.i64();
      case TAG.FLOAT: return this.f32();
      case TAG.DOUBLE: return this.f64();
      case TAG.BYTE_ARRAY: {
        const n = this.i32();
        const a = this.b.subarray(this.i, this.i + n);
        this.i += n;
        return a;
      }
      case TAG.STRING: return this.str();
      case TAG.LIST: {
        const el = this.u1();
        const n = this.i32();
        const out = [];
        for (let k = 0; k < n; k++) out.push(this.payload(el));
        return out;
      }
      case TAG.COMPOUND: {
        const out = {};
        for (;;) {
          const t2 = this.u1();
          if (t2 === 0) return out;
          const name = this.str();
          out[name] = this.payload(t2);
        }
      }
      case TAG.INT_ARRAY: {
        const n = this.i32();
        const out = [];
        for (let k = 0; k < n; k++) out.push(this.i32());
        return out;
      }
      case TAG.LONG_ARRAY: {
        const n = this.i32();
        const out = [];
        for (let k = 0; k < n; k++) out.push(this.i64());
        return out;
      }
      default: throw new Error('unknown NBT tag type ' + t);
    }
  }
}

/** Parse raw NBT bytes, decompressing gzip if needed. Returns the root compound. */
export function parseNbt(raw, { skipRootName = true } = {}) {
  const data = (raw.length > 1 && raw[0] === 0x1f && raw[1] === 0x8b) ? zlib.gunzipSync(raw) : raw;
  const r = new Reader(data);
  const t = r.u1();
  if (t !== TAG.COMPOUND) throw new Error('NBT root is tag ' + t + ', expected a compound');
  const rootName = r.str();
  const root = r.payload(TAG.COMPOUND);
  return skipRootName ? root : { rootName, root };
}

/* ------------------------------------------------------------------ writing */

class Writer {
  constructor() { this.parts = []; this.len = 0; }
  buf(b) { this.parts.push(b); this.len += b.length; }
  u1(v) { this.buf(Buffer.from([v & 0xff])); }
  i16(v) { const b = Buffer.alloc(2); b.writeInt16BE(v); this.buf(b); }
  i32(v) { const b = Buffer.alloc(4); b.writeInt32BE(v); this.buf(b); }
  i64(v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); this.buf(b); }
  str(s) {
    const b = Buffer.from(String(s), 'utf8');
    const len = Buffer.alloc(2);
    len.writeUInt16BE(b.length);
    this.buf(len);
    this.buf(b);
  }
  concat() { return Buffer.concat(this.parts, this.len); }
}

const named = (name, t) => ({ name, t });

function writeTag(w, t, v) {
  switch (t) {
    case TAG.BYTE: w.u1(v); break;
    case TAG.SHORT: w.i16(v); break;
    case TAG.INT: w.i32(v); break;
    case TAG.LONG: w.i64(v); break;
    case TAG.STRING: w.str(v); break;
    case TAG.BYTE_ARRAY: { w.i32(v.length); w.buf(Buffer.from(v)); break; }
    case TAG.INT_ARRAY: { w.i32(v.length); for (const x of v) w.i32(x); break; }
    case TAG.LONG_ARRAY: { w.i32(v.length); for (const x of v) w.i64(x); break; }
    case TAG.LIST: {
      // Empty lists are written as element type 0, which is what vanilla NBT does for "no elements".
      const el = v.length ? v[0].t : 0;
      w.u1(el);
      w.i32(v.length);
      for (const item of v) writeTag(w, item.t, item.v);
      break;
    }
    case TAG.COMPOUND: {
      for (const [name, field] of v) {
        w.u1(field.t);
        w.str(name);
        writeTag(w, field.t, field.v);
      }
      w.u1(0);
      break;
    }
    default: throw new Error('cannot write tag type ' + t);
  }
}

/* Tag constructors - explicit, because JS numbers cannot tell int from long and arrays cannot tell
   list from long array. */
export const nbt = {
  int: (v) => ({ t: TAG.INT, v: v | 0 }),
  long: (v) => ({ t: TAG.LONG, v: BigInt(v) }),
  string: (v) => ({ t: TAG.STRING, v: String(v) }),
  list: (items) => ({ t: TAG.LIST, v: items.map((x) => ({ t: TAG.COMPOUND, v: x })) }),
  emptyList: () => ({ t: TAG.LIST, v: [] }),
  compound: (fields) => ({ t: TAG.COMPOUND, v: fields }),
  longArray: (v) => ({ t: TAG.LONG_ARRAY, v }),
};

/* ------------------------------------------------------------------ packing */

export function bitsFor(paletteSize) {
  return paletteSize > 1 ? Math.max(2, Math.ceil(Math.log2(paletteSize))) : 2;
}

/**
 * Pack palette indices into 64-bit longs as one continuous little-endian bitstream.
 * Inverse of unpackIndices(); the two are checked against real files by selfTest().
 */
export function packIndices(indices, bits) {
  const longs = [];
  let buf = 0n;
  let have = 0;
  for (const value of indices) {
    buf |= BigInt(value & ((1 << bits) - 1)) << BigInt(have);
    have += bits;
    while (have >= 64) {
      longs.push(BigInt.asIntN(64, buf & MASK64));
      buf >>= 64n;
      have -= 64;
    }
  }
  if (have > 0) longs.push(BigInt.asIntN(64, buf & MASK64));
  return longs;
}

export function unpackIndices(longs, bits, count) {
  const out = new Array(count);
  const modulus = 1 << bits;
  let buf = 0n;
  let have = 0;
  let emitted = 0;
  for (let li = 0; li < longs.length && emitted < count; li++) {
    buf |= (longs[li] & MASK64) << BigInt(have);
    have += 64;
    while (have >= bits && emitted < count) {
      out[emitted++] = Number(buf & BigInt(modulus - 1));
      buf >>= BigInt(bits);
      have -= bits;
    }
  }
  return out;
}

/* ------------------------------------------------------------------ litematic */

export const DATA_VERSION_26_2 = 3955;
export const SCHEMATIC_VERSION = 7;

/** Canonical palette key: name plus sorted properties, so equal states collapse to one entry. */
export function stateKey(entry) {
  if (typeof entry === 'string') return entry;
  const props = entry.Properties;
  if (!props) return entry.Name;
  const parts = Object.keys(props).sort().map((k) => `${k}=${props[k]}`);
  return parts.length ? `${entry.Name}[${parts.join(',')}]` : entry.Name;
}

/** "minecraft:oak_log[axis=y]" -> { Name, Properties } */
export function parseState(text) {
  const m = /^([a-z0-9_.-]+:[a-z0-9_/.-]+)(?:\[(.*)\])?$/.exec(text);
  if (!m) throw new Error('not a block state: ' + text);
  if (!m[2]) return { Name: m[1] };
  const Properties = {};
  for (const pair of m[2].split(',')) {
    const eq = pair.indexOf('=');
    if (eq < 0) throw new Error('bad property in ' + text);
    Properties[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim();
  }
  return { Name: m[1], Properties };
}

export const indexOfBlock = (x, y, z, size) => (y * size.z + z) * size.x + x;

/**
 * Write a single-region .litematic.
 *
 * `grid` maps "x,y,z" -> block-state string. Anything absent is air.
 * `size` is {x,y,z} with positive components; region Position is the origin corner.
 */
export function writeLitematic(file, { name, author, description = '', size, grid, position = { x: 0, y: 0, z: 0 } }) {
  const volume = size.x * size.y * size.z;

  // Build the palette: air first (matching the real files), then the rest sorted so output is stable.
  const present = new Map();
  for (const [key, state] of grid) {
    if (state === 'minecraft:air') continue;
    const k = stateKey(parseState(state));
    if (!present.has(k)) present.set(k, parseState(state));
    void key;
  }
  const palette = [{ Name: 'minecraft:air' }, ...[...present.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)).map(([, v]) => v)];
  const paletteIndex = new Map(palette.map((e, i) => [stateKey(e), i]));

  const indices = new Array(volume).fill(0);
  let totalBlocks = 0;
  for (const [key, state] of grid) {
    if (state === 'minecraft:air') continue;
    const [xs, ys, zs] = key.split(',');
    const x = Number(xs), y = Number(ys), z = Number(zs);
    if (x < 0 || y < 0 || z < 0 || x >= size.x || y >= size.y || z >= size.z) {
      throw new Error(`block ${key} (${state}) is outside the ${size.x}x${size.y}x${size.z} region`);
    }
    indices[indexOfBlock(x, y, z, size)] = paletteIndex.get(stateKey(parseState(state)));
    totalBlocks++;
  }

  const bits = bitsFor(palette.length);
  const longs = packIndices(indices, bits);
  const expectedLongs = Math.ceil(volume * bits / 64);
  if (longs.length !== expectedLongs) {
    throw new Error(`packed ${longs.length} longs, expected ${expectedLongs}`);
  }

  const now = BigInt(Date.now());
  const root = [
    ['MinecraftDataVersion', nbt.int(DATA_VERSION_26_2)],
    ['Version', nbt.int(SCHEMATIC_VERSION)],
    ['Metadata', nbt.compound([
      ['Name', nbt.string(name)],
      ['Author', nbt.string(author)],
      ['Description', nbt.string(description)],
      ['RegionCount', nbt.int(1)],
      ['TotalBlocks', nbt.int(totalBlocks)],
      ['TotalVolume', nbt.int(volume)],
      ['EnclosingSize', nbt.compound([
        ['x', nbt.int(size.x)], ['y', nbt.int(size.y)], ['z', nbt.int(size.z)],
      ])],
      ['TimeCreated', nbt.long(now)],
      ['TimeModified', nbt.long(now)],
    ])],
    ['Regions', nbt.compound([
      [name, nbt.compound([
        ['Position', nbt.compound([
          ['x', nbt.int(position.x)], ['y', nbt.int(position.y)], ['z', nbt.int(position.z)],
        ])],
        ['Size', nbt.compound([
          ['x', nbt.int(size.x)], ['y', nbt.int(size.y)], ['z', nbt.int(size.z)],
        ])],
        ['BlockStatePalette', nbt.list(palette.map((e) => (e.Properties
          ? [['Name', nbt.string(e.Name)], ['Properties', nbt.compound(Object.entries(e.Properties).map(([k, v]) => [k, nbt.string(v)]))]]
          : [['Name', nbt.string(e.Name)]])))],
        ['BlockStates', nbt.longArray(longs)],
        ['PendingBlockTicks', nbt.emptyList()],
        ['PendingFluidTicks', nbt.emptyList()],
        ['Entities', nbt.emptyList()],
        ['TileEntities', nbt.emptyList()],
      ])],
    ])],
  ];

  const w = new Writer();
  w.u1(TAG.COMPOUND);
  w.str('Litematic');
  writeTag(w, TAG.COMPOUND, root);
  fs.writeFileSync(file, zlib.gzipSync(w.concat(), { level: 9 }));

  return { file, palette: palette.length, bits, longs: longs.length, totalBlocks, volume };
}

/** Read a .litematic and expose each region's decoded indices. */
export function readLitematic(file) {
  const raw = fs.readFileSync(file);
  const root = parseNbt(raw);
  const regions = [];
  for (const [regionName, region] of Object.entries(root.Regions || {})) {
    const size = {
      x: Math.abs(region.Size.x), y: Math.abs(region.Size.y), z: Math.abs(region.Size.z),
    };
    const palette = region.BlockStatePalette || [];
    const bits = bitsFor(palette.length);
    const volume = size.x * size.y * size.z;
    regions.push({
      name: regionName,
      position: region.Position,
      size,
      palette,
      bits,
      volume,
      longs: region.BlockStates || [],
      indices: unpackIndices(region.BlockStates || [], bits, volume),
    });
  }
  return { root, regions, metadata: root.Metadata || {} };
}

/** "#rrggbb" -> flat block state, for the flat pixel-art mode. */
export function stateNameOnly(state) {
  return parseState(state).Name;
}

/**
 * Repack every region of a real .litematic and compare the long arrays byte for byte.
 * This is the check that the packing convention is Litematica's and not merely self-consistent.
 */
export function selfTest(files) {
  const results = [];
  for (const file of files) {
    if (!fs.existsSync(file)) {
      results.push({ file, ok: false, reason: 'not found' });
      continue;
    }
    const { regions } = readLitematic(file);
    let ok = true;
    const detail = [];
    for (const region of regions) {
      const repacked = packIndices(region.indices, region.bits);
      const same = repacked.length === region.longs.length
        && repacked.every((v, i) => v === region.longs[i]);
      ok = ok && same;
      detail.push({
        region: region.name,
        palette: region.palette.length,
        bits: region.bits,
        longs: region.longs.length,
        repackMatches: same,
      });
    }
    results.push({ file, ok, regions: detail });
  }
  return results;
}
