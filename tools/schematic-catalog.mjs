#!/usr/bin/env node
/**
 * schematic-catalog.mjs - inventory a folder of Minecraft schematics without running Minecraft.
 *
 * Reads .litematic / .schem / .schematic files directly (they are gzipped NBT), extracts the
 * metadata, counts the real non-air block count, finds duplicate and same-build files, and writes
 * a sortable catalog you can choose builds from. Optionally stages a shortlist into your
 * Litematica schematics folder.
 *
 * Why the format version matters for this workflow:
 *   Baritone's own  "#build <file>"  only parses:
 *     .litematic  format version 7 (Minecraft 1.21+)  - v4/v5/v6 are rejected as "too old"
 *     .schem      Sponge version 1 or 2               - v3 is not supported
 *     .schematic  MCEdit, any
 *   Everything else still works through the Litematica PLACEMENT path (/startbuild builds the
 *   placement and Litematica does its own version handling). This tool tells you which is which.
 *
 * Runtime: Node.js (no dependencies). Python is not installed on this machine.
 *
 * Usage:
 *   node schematic-catalog.mjs <folder> [options]
 *
 *   --out DIR            where to write the catalog   (default: <folder>/_catalog)
 *   --stage DIR          copy a shortlist into DIR    (e.g. your schematics folder)
 *   --top N              with --stage: only the N biggest by block count
 *   --only-buildable     with --stage: only files Baritone "#build" accepts FROM DISK. You do not
 *                        need this for the /startbuild workflow - that builds a Litematica
 *                        placement, so every format and version works
 *   --no-count-blocks    metadata only, fastest on huge libraries
 *   --count-all          count blocks even in very large schematics (slower)
 *   --jobs N             parallel workers             (default: CPU count)
 *   --no-extract-zips    do not look inside .zip archives
 *   --inspect FILE       parse one NBT file and print its structure, then exit
 *   --explain FILE       per-region breakdown of one .litematic (why counts differ from metadata)
 *   --stock FILE         write a datapack that stocks that schematic's materials (see below)
 *   --stock-out DIR      where to write the stock datapack   (default: <file folder>/_stock)
 *   --stock-mode MODE    creative (one stack of each type, default) or survival (exact counts)
 *   --stock-batch N      block types per function            (default: 36, one inventory)
 *
 * Catalog options:
 *   --min-blocks N       only show builds with at least N blocks
 *   --max-blocks N       only show builds with at most N blocks
 *   --author TEXT        only show builds whose author/name matches TEXT
 *   --name TEXT          only show builds whose filename/name matches TEXT
 *   --materials FILE     write the combined material list for the shown builds to FILE
 *   --previews           render top-down preview PNGs plus a browsable previews/index.html
 *   --preview-max N      how many previews to render          (default: 40)
 *   --preview-dim N      preview size in pixels               (default: 320)
 *   --no-cache           ignore the incremental cache in <out>/cache.json
 */

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import os from 'node:os';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';

const SCHEMATIC_EXTS = ['.litematic', '.schem', '.schematic'];
// air, void_air and cave_air (namespace optional). Deliberately not a loose /air$/ match, which
// would also catch things like a modded "chair" block.
const AIR_NAME = /^(?:[^:]+:)?(?:void_|cave_)?air$/i;
// Blocks with no obtainable item form: /give cannot supply them, so they are reported separately
// rather than producing commands that would silently fail.
const NON_ITEM_BLOCKS = new Set([
  'minecraft:air', 'minecraft:void_air', 'minecraft:cave_air',
  'minecraft:water', 'minecraft:lava', 'minecraft:flowing_water', 'minecraft:flowing_lava',
  'minecraft:fire', 'minecraft:soul_fire', 'minecraft:piston_head', 'minecraft:moving_piston',
  'minecraft:bubble_column', 'minecraft:nether_portal', 'minecraft:end_portal',
  'minecraft:end_gateway', 'minecraft:structure_void', 'minecraft:frosted_ice',
  'minecraft:tall_seagrass', 'minecraft:kelp_plant', 'minecraft:cave_vines_plant',
  'minecraft:twisting_vines_plant', 'minecraft:weeping_vines_plant', 'minecraft:chorus_plant',
  'minecraft:attached_melon_stem', 'minecraft:attached_pumpkin_stem', 'minecraft:big_dripleaf_stem',
  'minecraft:bamboo_sapling'
]);
const ZIP_EXTS = ['.zip'];
// Above this volume we skip exact block counting unless --count-all, because decoding tens of
// millions of palette indices is slow even in Node.
const DEFAULT_DECODE_LIMIT = 4_000_000;
const MASK64 = 0xFFFFFFFFFFFFFFFFn;

/* ------------------------------------------------------------------ NBT reader */

class NbtError extends Error {}

class Reader {
  constructor(buf) {
    this.b = buf;
    this.i = 0;
  }
  u1() {
    if (this.i + 1 > this.b.length) throw new NbtError('unexpected end of NBT data');
    return this.b[this.i++];
  }
  take(n) {
    if (n < 0 || this.i + n > this.b.length) throw new NbtError('unexpected end of NBT data');
    const v = this.b.subarray(this.i, this.i + n);
    this.i += n;
    return v;
  }
  i8() { return this.b.readInt8(this.i++); }
  i16() { const v = this.b.readInt16BE(this.i); this.i += 2; return v; }
  i32() { const v = this.b.readInt32BE(this.i); this.i += 4; return v; }
  i64() { const v = this.b.readBigInt64BE(this.i); this.i += 8; return v; }
  f32() { const v = this.b.readFloatBE(this.i); this.i += 4; return v; }
  f64() { const v = this.b.readDoubleBE(this.i); this.i += 8; return v; }
  str() { const n = this.i16(); return this.take(n).toString('utf8'); }
  payload(t) {
    switch (t) {
      case 0: return null;
      case 1: return this.i8();
      case 2: return this.i16();
      case 3: return this.i32();
      case 4: return this.i64();
      case 5: return this.f32();
      case 6: return this.f64();
      case 7: { const n = this.i32(); return this.take(Math.max(0, n)); }
      case 8: return this.str();
      case 9: {
        const itemType = this.u1();
        const n = this.i32();
        const out = [];
        for (let k = 0; k < Math.max(0, n); k++) out.push(this.payload(itemType));
        return out;
      }
      case 10: {
        const out = {};
        for (;;) {
          const tt = this.u1();
          if (tt === 0) return out;
          const name = this.str();
          out[name] = this.payload(tt);
        }
      }
      case 11: {
        const n = this.i32();
        const out = [];
        for (let k = 0; k < Math.max(0, n); k++) out.push(this.i32());
        return out;
      }
      case 12: {
        const n = this.i32();
        const out = [];
        for (let k = 0; k < Math.max(0, n); k++) out.push(this.i64());
        return out;
      }
      default: throw new NbtError('unknown NBT tag type ' + t);
    }
  }
}

export function parseNbtBuffer(raw) {
  let data = raw;
  if (raw.length > 1 && raw[0] === 0x1f && raw[1] === 0x8b) data = zlib.gunzipSync(raw);
  const r = new Reader(data);
  const t = r.u1();
  if (t !== 10) throw new NbtError('root tag is not a compound (tag ' + t + ')');
  r.str();
  return r.payload(10);
}

/* ------------------------------------------------------------------ helpers */

/** Tally palette indices packed little-endian at `bits` per entry, `count` of them. */
function tallyPackedIndices(longs, bits, count, tally, stats) {
  const modulus = Math.pow(2, bits);
  const perChunk = Math.max(1, Math.floor(53 / bits));
  let buf = 0n;
  let have = 0;
  let emitted = 0;
  for (let li = 0; li < longs.length && emitted < count; li++) {
    buf |= (longs[li] & MASK64) << BigInt(have);
    have += 64;
    while (have >= bits && emitted < count) {
      const take = Math.min(perChunk, Math.floor(have / bits), count - emitted);
      const chunkMask = (1n << BigInt(take * bits)) - 1n;
      let chunk = Number(buf & chunkMask);
      for (let k = 0; k < take; k++) {
        const idx = chunk % modulus;
        if (idx < tally.length) tally[idx]++;
        else if (stats) stats.outOfRange++;
        chunk = Math.floor(chunk / modulus);
      }
      buf >>= BigInt(take * bits);
      have -= take * bits;
      emitted += take;
    }
  }
  return emitted;
}

function* decodeVarints(buf) {
  let val = 0;
  let shift = 0;
  for (let i = 0; i < buf.length; i++) {
    const b = buf[i];
    val |= (b & 0x7f) << shift;
    if (b & 0x80) shift += 7;
    else { yield val >>> 0; val = 0; shift = 0; }
  }
}

/** Canonical key for a palette entry: block name plus its sorted block-state properties. */
function paletteKey(entry) {
  if (!entry || typeof entry !== 'object') return '(?)';
  const name = String(entry.Name || '(?)');
  const props = entry.Properties;
  if (!props || typeof props !== 'object') return name;
  const parts = Object.keys(props).sort().map((k) => `${k}=${props[k]}`);
  return parts.length ? `${name}[${parts.join(',')}]` : name;
}

/**
 * Order-independent content signature: the same block types with the same counts at the same size.
 * Catches a build re-exported under a different filename or format, which byte hashing cannot see.
 * It is a "looks like the same build" hint, not proof - two different builds with an identical
 * material tally and identical dimensions would also match.
 */
function contentSignature(pairs, sx, sy, sz) {
  if (!pairs.length) return '';
  return crypto.createHash('sha256')
    .update(pairs.slice().sort().join('|') + `|${sx}x${sy}x${sz}`)
    .digest('hex').slice(0, 16);
}

/** Just the block name, used for colouring previews. */
function paletteName(entry) {
  return (entry && typeof entry === 'object') ? String(entry.Name || '') : '';
}

const axis = (node, a) => {
  const v = node && typeof node === 'object' ? node[a] : 0;
  return Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : 0;
};
const text = (v) => (v == null ? '' : String(v).replace(/[\r\n\t]+/g, ' ').trim());
const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : d);

/* ------------------------------------------------------------------ per-format analysis */

function analyseLitematic(root, doCount, decodeLimit) {
  const version = root.Version == null ? null : num(root.Version);
  const meta = root.Metadata || {};
  const regions = root.Regions || {};
  const size = meta.EnclosingSize || {};

  let volumeFromRegions = 0;
  let nonAir = 0;
  let counted = doCount;
  let regionCount = 0;
  let paletteEntries = 0;
  const signatureParts = [];

  for (const region of Object.values(regions)) {
    if (!region || typeof region !== 'object') continue;
    regionCount++;
    const sz = region.Size || {};
    const vol = Math.abs(axis(sz, 'x') * axis(sz, 'y') * axis(sz, 'z'));
    volumeFromRegions += vol;

    const palette = Array.isArray(region.BlockStatePalette) ? region.BlockStatePalette : [];
    paletteEntries += palette.length;
    if (!palette.length) continue;

    let airIndices = [];
    for (let i = 0; i < palette.length; i++) {
      const e = palette[i];
      // Every air variant counts as air: Litematica's own TotalBlocks excludes void_air and
      // cave_air, and Baritone will not place them either (they are AirBlocks).
      if (e && typeof e === 'object' && AIR_NAME.test(String(e.Name))) airIndices.push(i);
    }

    if (!doCount) continue;
    if (vol > decodeLimit) { counted = false; continue; }

    const longs = Array.isArray(region.BlockStates) ? region.BlockStates : [];
    const bits = palette.length > 1 ? Math.max(2, Math.ceil(Math.log2(palette.length))) : 2;
    const tally = new Array(palette.length).fill(0);
    const got = tallyPackedIndices(longs, bits, vol, tally);
    if (got < vol) counted = false;
    let airCount = 0;
    for (const i of airIndices) airCount += tally[i];
    nonAir += got - airCount;

    for (let i = 0; i < palette.length; i++) {
      if (tally[i] && !airIndices.includes(i)) {
        signatureParts.push(`${paletteKey(palette[i])}=${tally[i]}`);
      }
    }
  }

  // Order-independent content signature (see contentSignature) - shared with the .schem path so a
  // .litematic and a .schem of the same build produce the same key.
  const contentKey = (counted && doCount)
    ? contentSignature(signatureParts, Math.abs(axis(size, 'x')), Math.abs(axis(size, 'y')), Math.abs(axis(size, 'z')))
    : '';

  let buildable;
  let note = '';
  if (version === 7) buildable = true;
  else if ([4, 5, 6].includes(version)) {
    buildable = false;
    note = `litematic v${version} - Baritone calls this "too old"; use the Litematica placement path`;
  } else {
    buildable = false;
    note = `litematic v${version} - Baritone does not support this version`;
  }

  // Free self-check: Litematica/tool-written files carry their own TotalBlocks. When we decoded the
  // blocks ourselves, that number must agree - if it does not, something is wrong with the decode.
  const metaBlocks = num(meta.TotalBlocks) || null;
  let countCheck = '';
  if (counted && doCount && metaBlocks !== null) {
    countCheck = nonAir === metaBlocks
      ? 'matches metadata'
      : `DIFFERS from metadata (${nonAir} vs ${metaBlocks})`;
  }

  return {
    format: 'litematic',
    format_version: version,
    name: text(meta.Name),
    author: text(meta.Author),
    description: text(meta.Description),
    size_x: Math.abs(axis(size, 'x')),
    size_y: Math.abs(axis(size, 'y')),
    size_z: Math.abs(axis(size, 'z')),
    volume: num(meta.TotalVolume) || volumeFromRegions,
    non_air_blocks: (counted && doCount) ? nonAir : (metaBlocks || null),
    block_count_exact: Boolean(counted && doCount),
    // Litematica/the tool that wrote the file records TotalBlocks itself. When a schematic is too
    // large to decode we fall back to that number, and it doubles as a check on the decoder.
    blocks_source: (counted && doCount) ? 'decoded' : (metaBlocks ? 'metadata' : ''),
    metadata_blocks: metaBlocks,
    count_check: countCheck,
    content_key: contentKey,
    region_count: num(meta.RegionCount) || regionCount,
    distinct_blocks: paletteEntries,
    buildable,
    build_note: note,
  };
}

function analyseSchem(root, doCount, decodeLimit) {
  const version = root.Version == null ? null : num(root.Version);
  const w = num(root.Width);
  const h = num(root.Height);
  const l = num(root.Length);
  const meta = root.Metadata || {};

  let palette = root.Palette;
  let data = null;
  if (palette && typeof palette === 'object' && !Buffer.isBuffer(palette)) {
    data = root.BlockData;
  } else if (root.Blocks && typeof root.Blocks === 'object') {
    palette = root.Blocks.Palette;
    data = root.Blocks.Data;
  }

  let airIndexes = [];
  if (palette && typeof palette === 'object') {
    for (const [key, value] of Object.entries(palette)) {
      if (AIR_NAME.test(key)) airIndexes.push(num(value));
    }
  }

  let nonAir = null;
  let counted = false;
  const signatureParts = [];
  const vol = w * h * l;
  if (doCount && Buffer.isBuffer(data) && (vol === 0 || vol <= decodeLimit)) {
    const tally = new Map();
    let total = 0;
    for (const idx of decodeVarints(data)) {
      tally.set(idx, (tally.get(idx) || 0) + 1);
      total++;
    }
    if (total > 0) {
      let air = 0;
      for (const i of airIndexes) air += tally.get(i) || 0;
      nonAir = total - air;
      counted = true;

      // name-based signature so this can be compared against a .litematic of the same build
      const idToName = new Map();
      if (palette && typeof palette === 'object') {
        for (const [key, value] of Object.entries(palette)) {
          idToName.set(num(value), key);
        }
      }
      for (const [id, count] of tally) {
        const name = idToName.get(id);
        if (count && name && !AIR_NAME.test(name)) {
          signatureParts.push(`${name}=${count}`);
        }
      }
    }
  }

  let buildable;
  let note = '';
  if (version === 1 || version === 2) buildable = true;
  else {
    buildable = false;
    note = `Sponge v${version} - Baritone only parses Sponge v1/v2`;
  }

  return {
    format: 'schem (Sponge)',
    format_version: version,
    name: text(meta.Name),
    author: text(meta.Author),
    description: text(meta.Description),
    size_x: w,
    size_y: h,
    size_z: l,
    volume: vol,
    non_air_blocks: counted ? nonAir : null,
    block_count_exact: counted,
    blocks_source: counted ? 'decoded' : '',
    metadata_blocks: null,
    content_key: counted ? contentSignature(signatureParts, w, h, l) : '',
    region_count: 1,
    distinct_blocks: palette && typeof palette === 'object' ? Object.keys(palette).length : 0,
    buildable,
    build_note: note,
  };
}

function analyseSchematic(root, doCount) {
  const w = num(root.Width);
  const h = num(root.Height);
  const l = num(root.Length);
  let nonAir = null;
  let counted = false;
  if (doCount && Buffer.isBuffer(root.Blocks)) {
    let n = 0;
    for (const b of root.Blocks) if (b !== 0) n++;
    nonAir = n;
    counted = true;
  }
  return {
    format: 'schematic (MCEdit)',
    format_version: 0,
    name: text(root.Name),
    author: text(root.Author),
    description: '',
    size_x: w,
    size_y: h,
    size_z: l,
    volume: w * h * l,
    non_air_blocks: counted ? nonAir : null,
    block_count_exact: counted,
    blocks_source: counted ? 'decoded' : '',
    metadata_blocks: null,
    region_count: 1,
    distinct_blocks: 0,
    buildable: true,
    build_note: '',
  };
}

function blankRow(file) {
  return {
    path: file,
    file: path.basename(file),
    // Filename stem is the reliable identity: creator tools often stamp their own name into
    // Metadata.Name (e.g. "schematelizer"), making the metadata name useless for a whole library.
    label: path.basename(file, path.extname(file)).replace(/\+/g, ' ').trim(),
    bytes: 0, sha256: '',
    format: '', format_version: null, name: '', author: '', description: '',
    size_x: 0, size_y: 0, size_z: 0, volume: 0,
    non_air_blocks: null, block_count_exact: false, blocks_source: '', metadata_blocks: null, count_check: '',
    content_key: '', content_match: '',
    region_count: 0, distinct_blocks: 0,
    buildable: null, build_note: '', error: '', duplicate_of: '', same_build_as: '',
  };
}

/** Analyse one file. Never throws: failures come back as an error row. */
export function analyseFile(file, opts) {
  const row = blankRow(file);
  try {
    const raw = fs.readFileSync(file);
    row.bytes = raw.length;
    row.sha256 = crypto.createHash('sha256').update(raw).digest('hex');
    const root = parseNbtBuffer(raw);
    const ext = path.extname(file).toLowerCase();
    if (ext === '.litematic') Object.assign(row, analyseLitematic(root, opts.doCount, opts.decodeLimit));
    else if (ext === '.schem') Object.assign(row, analyseSchem(root, opts.doCount, opts.decodeLimit));
    else if (ext === '.schematic') Object.assign(row, analyseSchematic(root, opts.doCount));
    else row.error = 'unsupported extension';
  } catch (err) {
    row.error = `${err.constructor.name}: ${err.message}`;
  }
  return row;
}

/* ------------------------------------------------------------------ minimal zip reading */

function readZipEntries(buf) {
  let eocd = -1;
  const floor = Math.max(0, buf.length - 22 - 65536);
  for (let i = buf.length - 22; i >= floor; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip archive (no end-of-central-directory record)');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let n = 0; n < count; n++) {
    if (off + 46 > buf.length || buf.readUInt32LE(off) !== 0x02014b50) break;
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    entries.push({ name, method, compSize, localOff });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function readZipEntry(buf, entry) {
  const lo = entry.localOff;
  if (buf.readUInt32LE(lo) !== 0x04034b50) throw new Error('bad zip local header');
  const nameLen = buf.readUInt16LE(lo + 26);
  const extraLen = buf.readUInt16LE(lo + 28);
  const compSize = entry.compSize || buf.readUInt32LE(lo + 18);
  const start = lo + 30 + nameLen + extraLen;
  const data = buf.subarray(start, start + compSize);
  if (entry.method === 0) return Buffer.from(data);
  if (entry.method === 8) return zlib.inflateRawSync(data);
  throw new Error('unsupported zip compression method ' + entry.method);
}

function extractZips(folder, outDir, warn) {
  const found = [];
  const destRoot = path.join(outDir, 'extracted');
  for (const file of walk(folder)) {
    if (!ZIP_EXTS.includes(path.extname(file).toLowerCase())) continue;
    try {
      const buf = fs.readFileSync(file);
      const base = path.basename(file, path.extname(file));
      for (const entry of readZipEntries(buf)) {
        if (!SCHEMATIC_EXTS.includes(path.extname(entry.name).toLowerCase())) continue;
        const leaf = path.basename(entry.name);
        if (!leaf) continue;
        const destDir = path.join(destRoot, base);
        fs.mkdirSync(destDir, { recursive: true });
        // Deterministic target name: repeated runs overwrite instead of piling up renamed copies,
        // and an extracted file can never be confused with the original next to the archive.
        const target = path.join(destDir, base + '__' + leaf);
        fs.writeFileSync(target, readZipEntry(buf, entry));
        found.push(target);
      }
    } catch (err) {
      warn(`  ! could not read ${path.basename(file)}: ${err.message}`);
    }
  }
  return found;
}

/* ------------------------------------------------------------------ discovery */

function* walk(dir) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) yield* walk(full);
    else if (e.isFile()) yield full;
  }
}

function findSchematics(folder) {
  const found = [];
  for (const file of walk(folder)) {
    const ext = path.extname(file).toLowerCase();
    if (SCHEMATIC_EXTS.includes(ext)) found.push(file);
  }
  return found;
}

/* ------------------------------------------------------------------ reporting */

const humanBytes = (n) => {
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${i === 0 ? n : n.toFixed(1)} ${units[i]}`;
};

const blocksLabel = (r) => {
  if (r.non_air_blocks == null) return 'n/a';
  const n = r.non_air_blocks.toLocaleString('en-US');
  if (r.block_count_exact) return n;
  return `${n} (${r.blocks_source === 'metadata' ? 'reported' : 'partial'})`;
};

function writeCatalog(rows, outDir) {
  fs.mkdirSync(outDir, { recursive: true });
  const good = rows.filter((r) => !r.error);
  const bad = rows.filter((r) => r.error);
  // Grouping fields are recomputed every run - never trust values that came back from the cache.
  for (const r of good) {
    r.duplicate_of = '';
    r.same_build_as = '';
    r.content_match = '';
  }
  const ranked = [...good].sort((a, b) =>
    ((b.non_air_blocks || 0) - (a.non_air_blocks || 0)) || (b.volume - a.volume) || a.file.localeCompare(b.file));

  // duplicates by content hash - pick the canonical member as the one that does not look like a
  // copy ("copy_of_x", "x (1)"), so duplicate_of points at something meaningful.
  const byHash = new Map();
  for (const r of good) {
    if (!byHash.has(r.sha256)) byHash.set(r.sha256, []);
    byHash.get(r.sha256).push(r);
  }
  // "copy_of_x", "x (1)" and extracted "zip__x" are all treated as the non-canonical member.
  const copyScore = (r) => (/copy|\(\d+\)|__/i.test(r.file) ? 1 : 0);
  const canonical = (group) => [...group].sort((a, b) =>
    (copyScore(a) - copyScore(b)) || a.file.localeCompare(b.file));
  const dupes = [...byHash.entries()].filter(([, g]) => g.length > 1)
    .map(([hash, group]) => [hash, canonical(group)]);
  for (const [, group] of dupes) {
    for (const r of group.slice(1)) r.duplicate_of = group[0].file;
  }

  // same build in different formats: same name + same dimensions, ignoring byte-identical copies
  // (those are already reported as duplicates, so they would just clutter this list).
  const normStem = (file) => path.basename(file, path.extname(file)).toLowerCase().replace(/[^a-z0-9]+/g, '');
  const byBuild = new Map();
  for (const r of good) {
    if (r.duplicate_of) continue;
    const stem = normStem(r.file);
    if (!stem) continue;
    const key = `${stem}|${r.size_x}x${r.size_y}x${r.size_z}`;
    if (!byBuild.has(key)) byBuild.set(key, []);
    byBuild.get(key).push(r);
  }
  const twins = [...byBuild.entries()]
    .map(([key, group]) => {
      const seen = new Set();
      const perFormat = group.filter((r) => (seen.has(r.format) ? false : (seen.add(r.format), true)));
      return [key, perFormat];
    })
    .filter(([, group]) => group.length > 1);
  for (const [, group] of twins) {
    for (const r of group) r.same_build_as = group.map((g) => g.file).filter((f) => f !== r.file).join(', ');
  }

  // Same build CONTENT under a different filename (order-independent block tally). Byte hashing
  // cannot see a renamed or re-exported copy; this can. It is a hint: identical material counts at
  // identical dimensions is strong evidence, but not proof, that two files are the same build.
  const byContent = new Map();
  for (const r of good) {
    if (!r.content_key) continue;
    if (!byContent.has(r.content_key)) byContent.set(r.content_key, []);
    byContent.get(r.content_key).push(r);
  }
  const contentGroups = [...byContent.entries()].filter(([, group]) => group.length > 1);
  for (const [, group] of contentGroups) {
    for (const r of group) r.content_match = group.map((g) => g.file).filter((f) => f !== r.file).join(', ');
  }

  const columns = ['file', 'label', 'path', 'format', 'format_version', 'name', 'author', 'size_x', 'size_y',
    'size_z', 'volume', 'non_air_blocks', 'block_count_exact', 'blocks_source', 'metadata_blocks', 'count_check',
    'content_key', 'content_match', 'region_count', 'distinct_blocks',
    'buildable', 'build_note', 'bytes', 'sha256', 'duplicate_of', 'same_build_as', 'error'];

  const csvEscape = (v) => {
    const s = v == null ? '' : String(v);
    return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  const csv = [columns.join(',')]
    .concat([...ranked, ...bad].map((r) => columns.map((c) => csvEscape(r[c])).join(',')))
    .join('\r\n');
  fs.writeFileSync(path.join(outDir, 'catalog.csv'), csv, 'utf8');

  fs.writeFileSync(path.join(outDir, 'catalog.json'), JSON.stringify({
    files: [...ranked, ...bad],
    duplicate_groups: dupes.map(([h, g]) => ({ sha256: h, files: g.map((r) => r.path) })),
    same_build_groups: twins.map(([k, g]) => ({ key: k, files: g.map((r) => r.path) })),
    same_content_groups: contentGroups.map(([k, g]) => ({ content_key: k, files: g.map((r) => r.path) })),
  }, null, 2), 'utf8');

  const md = [];
  md.push('# Schematic catalog', '');
  md.push(`${rows.length} file(s): ${good.length} readable, ${bad.length} unreadable, ` +
    `${dupes.length} duplicate group(s), ${twins.length} same-build group(s), ` +
    `${contentGroups.length} same-content group(s).`, '');
  md.push('"Blocks" is the real non-air block count - a decent proxy for how much work each build is.');
  md.push('"Baritone #build" = can be fed straight to Baritone from the file; where it says no, load and');
  md.push('place the schematic in Litematica and use `/startbuild` instead.', '');
  md.push('| # | Blocks | Size (x,y,z) | Name | Author | Format | Baritone #build | File |');
  md.push('|---|--------|--------------|------|--------|--------|-----------------|------|');
  ranked.forEach((r, i) => {
    md.push(`| ${i + 1} | ${blocksLabel(r)} | ${r.size_x}x${r.size_y}x${r.size_z} | ${r.label} | ` +
      `${r.author || '-'} | ${r.format} v${r.format_version} | ` +
      `${r.buildable ? 'yes' : 'no - ' + (r.build_note || 'unsupported')} | ${r.file} |`);
  });
  if (twins.length) {
    md.push('', '## Same build, more than one format', '');
    for (const [key, group] of twins) {
      md.push(`* \`${group[0].label}\` (${key.split('|')[1]})`);
      for (const g of group) md.push(`  * ${g.file} - ${g.format}, ${blocksLabel(g)} blocks`);
    }
  }
  if (dupes.length) {
    md.push('', '## Duplicate files (byte-identical)', '');
    for (const [h, group] of dupes) {
      md.push(`* \`${h.slice(0, 12)}\``);
      for (const g of group) md.push(`  * ${g.path}`);
    }
  }
  if (contentGroups.length) {
    md.push('', '## Same content under different filenames', '');
    md.push('Identical block tally at identical dimensions - almost certainly the same build saved twice.');
    md.push('(A hint, not proof: two different builds with identical materials and size would also match.)', '');
    for (const [key, group] of contentGroups) {
      md.push(`* content \`${key}\``);
      for (const g of group) md.push(`  * ${g.file} - ${blocksLabel(g)} blocks, ${g.file === group[0].file ? 'first seen' : 'duplicate content'}`);
    }
  }
  if (bad.length) {
    md.push('', '## Unreadable files', '');
    for (const r of bad) md.push(`* ${r.path} - ${r.error}`);
  }
  fs.writeFileSync(path.join(outDir, 'catalog.md'), md.join('\n') + '\n', 'utf8');

  return { ranked, bad, dupes, twins, contentGroups, good };
}

function stageShortlist(ranked, stageDir, top, onlyBuildable) {
  fs.mkdirSync(stageDir, { recursive: true });
  // Skip byte-identical duplicates (they carry duplicate_of) so staging does not copy the same
  // build three times. Format twins (.litematic plus .schem) are kept - they are different files.
  let candidates = ranked.filter((r) => r.non_air_blocks != null && !r.duplicate_of);
  if (onlyBuildable) candidates = candidates.filter((r) => r.buildable);
  if (top) candidates = candidates.slice(0, top);
  const copied = [];
  for (const r of candidates) {
    let target = path.join(stageDir, r.file);
    try {
      if (fs.existsSync(target)) {
        const st = fs.statSync(target);
        if (st.size === r.bytes) continue;
        const ext = path.extname(r.file);
        target = path.join(stageDir, path.basename(r.file, ext) + '_' + r.sha256.slice(0, 6) + ext);
      }
      fs.copyFileSync(r.path, target);
      copied.push(path.basename(target));
    } catch (err) {
      copied.push(`FAILED ${r.file}: ${err.message}`);
    }
  }
  return copied;
}

/* ------------------------------------------------------------------ workers */

function runWorkerPool(files, opts, jobs, onProgress) {
  if (files.length < 8 || jobs <= 1) {
    const rows = [];
    for (const f of files) { rows.push(analyseFile(f, opts)); onProgress(1); }
    return Promise.resolve(rows);
  }
  const chunkCount = Math.min(jobs, files.length);
  const chunks = Array.from({ length: chunkCount }, () => []);
  files.forEach((f, i) => chunks[i % chunkCount].push(f));
  return Promise.all(chunks.map((chunk) => new Promise((resolve) => {
    const worker = new Worker(new URL(import.meta.url), { workerData: { files: chunk, opts } });
    const rows = [];
    worker.on('message', (msg) => {
      if (msg.progress) { onProgress(msg.progress); return; }
      if (msg.rows) rows.push(...msg.rows);
      if (msg.done) { worker.terminate(); resolve(rows); }
    });
    worker.on('error', () => { worker.terminate(); resolve(rows); });
    worker.on('exit', () => resolve(rows));
  }))).then((all) => all.flat());
}

if (!isMainThread) {
  const rows = [];
  for (const f of workerData.files) {
    rows.push(analyseFile(f, workerData.opts));
    parentPort.postMessage({ progress: 1 });
  }
  parentPort.postMessage({ rows, done: true });
}

/* ------------------------------------------------------------------ explain mode */

/** Per-region breakdown, for when a file's decoded count disagrees with its own metadata. */
function explain(file) {
  const raw = fs.readFileSync(file);
  const root = parseNbtBuffer(raw);
  const meta = root.Metadata || {};
  const regions = root.Regions || {};
  const size = meta.EnclosingSize || {};

  console.log(`${file}`);
  console.log(`  ${humanBytes(raw.length)}, litematic v${num(root.Version)}, data version ${num(root.MinecraftDataVersion)}`);
  console.log(`  Metadata Name=${JSON.stringify(text(meta.Name))} Author=${JSON.stringify(text(meta.Author))}`);
  console.log(`  Metadata EnclosingSize=${axis(size, 'x')}x${axis(size, 'y')}x${axis(size, 'z')}` +
    `  RegionCount=${num(meta.RegionCount)}  TotalBlocks=${num(meta.TotalBlocks)}  TotalVolume=${num(meta.TotalVolume)}`);
  console.log(`  regions actually present in the file: ${Object.keys(regions).length}`);

  let sumNonAir = 0;
  let sumVolume = 0;
  const boxes = [];
  for (const [name, region] of Object.entries(regions)) {
    if (!region || typeof region !== 'object') continue;
    const pos = region.Position || {};
    const sz = region.Size || {};
    const px = axis(pos, 'x'), py = axis(pos, 'y'), pz = axis(pos, 'z');
    const sx = axis(sz, 'x'), sy = axis(sz, 'y'), szz = axis(sz, 'z');
    const vol = Math.abs(sx * sy * szz);
    const palette = Array.isArray(region.BlockStatePalette) ? region.BlockStatePalette : [];
    const airIndex = palette.findIndex((e) => e && typeof e === 'object' && e.Name === 'minecraft:air');
    const bits = palette.length > 1 ? Math.max(2, Math.ceil(Math.log2(palette.length))) : 2;
    const tally = new Array(palette.length).fill(0);
    const longs = Array.isArray(region.BlockStates) ? region.BlockStates : [];
    const stats = { outOfRange: 0 };
    const got = tallyPackedIndices(longs, bits, vol, tally, stats);
    const nonAir = airIndex === null ? got : got - tally[airIndex];
    sumNonAir += nonAir;
    sumVolume += vol;
    boxes.push({
      name,
      min: [Math.min(px, px + sx), Math.min(py, py + sy), Math.min(pz, pz + szz)],
      max: [Math.max(px, px + sx), Math.max(py, py + sy), Math.max(pz, pz + szz)],
    });
    const expectedLongs = Math.ceil((vol * bits) / 64);
    console.log(`  region '${name}': pos=(${px},${py},${pz}) size=(${sx},${sy},${szz}) volume=${vol.toLocaleString('en-US')}` +
      ` palette=${palette.length} bits=${bits}`);
    console.log(`     entries read=${got.toLocaleString('en-US')}/${vol.toLocaleString('en-US')}` +
      `   blockStates longs=${longs.length.toLocaleString('en-US')} expected=${expectedLongs.toLocaleString('en-US')}` +
      `   ${longs.length === expectedLongs ? 'CONSISTENT' : 'INCONSISTENT'}`);
    console.log(`     palette indices out of range (0 = bit width is right): ${stats.outOfRange.toLocaleString('en-US')}`);
    console.log(`     blocks(non-air)=${nonAir.toLocaleString('en-US')}   air=${(got - nonAir).toLocaleString('en-US')}`);
    const common = palette
      .map((e, i) => ({ name: (e && e.Name) || '(?)', n: tally[i] }))
      .sort((a, b) => b.n - a.n)
      .slice(0, 12);
    console.log(`     most common: ${common.map((p) => `${p.name}=${p.n.toLocaleString('en-US')}`).join('  ')}`);
  }

  const overlaps = [];
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i], b = boxes[j];
      if (a.min[0] <= b.max[0] && a.max[0] >= b.min[0] &&
        a.min[1] <= b.max[1] && a.max[1] >= b.min[1] &&
        a.min[2] <= b.max[2] && a.max[2] >= b.min[2]) {
        overlaps.push(`${a.name} <-> ${b.name}`);
      }
    }
  }

  console.log('');
  console.log(`  sum of per-region volumes : ${sumVolume.toLocaleString('en-US')}`);
  console.log(`  sum of per-region blocks  : ${sumNonAir.toLocaleString('en-US')}`);
  console.log(`  metadata TotalBlocks      : ${num(meta.TotalBlocks).toLocaleString('en-US')}`);
  console.log(`  difference                : ${(sumNonAir - num(meta.TotalBlocks)).toLocaleString('en-US')}`);
  console.log(`  overlapping region pairs  : ${overlaps.length ? overlaps.join(', ') : 'none'}`);
  if (overlaps.length) {
    console.log('  -> regions overlap, so summing per-region counts double-counts blocks that live in');
    console.log('     more than one region. The metadata TotalBlocks appears to be a union count.');
  }
}

/* ------------------------------------------------------------------ stock generation */

/** Exact per-block-type counts for a schematic (air variants excluded). */
function paletteCounts(file) {
  const root = parseNbtBuffer(fs.readFileSync(file));
  const regions = root.Regions || {};
  const counts = new Map();
  for (const region of Object.values(regions)) {
    if (!region || typeof region !== 'object') continue;
    const palette = Array.isArray(region.BlockStatePalette) ? region.BlockStatePalette : [];
    if (!palette.length) continue;
    const sz = region.Size || {};
    const vol = Math.abs(axis(sz, 'x') * axis(sz, 'y') * axis(sz, 'z'));
    const bits = palette.length > 1 ? Math.max(2, Math.ceil(Math.log2(palette.length))) : 2;
    const tally = new Array(palette.length).fill(0);
    tallyPackedIndices(Array.isArray(region.BlockStates) ? region.BlockStates : [], bits, vol, tally);
    for (let i = 0; i < palette.length; i++) {
      const entry = palette[i];
      const name = entry && typeof entry === 'object' ? String(entry.Name || '') : '';
      if (!name || AIR_NAME.test(name)) continue;
      counts.set(name, (counts.get(name) || 0) + tally[i]);
    }
  }
  return counts;
}

/**
 * Write a datapack that stocks a schematic's materials.
 *
 * Batched because an inventory holds 36 slots: a build needing 111 block types cannot be stocked in
 * one go, so this emits one function per batch and you run them as Baritone consumes each one.
 */
function writeStock(file, outRoot, mode, batchSize) {
  const label = path.basename(file, path.extname(file))
    .replace(/\+/g, '_').replace(/[^A-Za-z0-9_.-]/g, '_').toLowerCase();
  const ns = 'sb';
  const counts = paletteCounts(file);
  const entries = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  const giveable = entries.filter(([n]) => !NON_ITEM_BLOCKS.has(n));
  const skipped = entries.filter(([n]) => NON_ITEM_BLOCKS.has(n));
  const totalBlocks = entries.reduce((sum, [, c]) => sum + c, 0);

  const packDir = path.join(outRoot, label);
  const fnDir = path.join(packDir, 'data', ns, 'function');
  // Regenerate cleanly: this directory is entirely tool-generated, so stale functions must not linger.
  fs.rmSync(packDir, { recursive: true, force: true });
  fs.mkdirSync(fnDir, { recursive: true });

  fs.writeFileSync(path.join(packDir, 'pack.mcmeta'), JSON.stringify({
    pack: {
      // Data pack format for MC 26.2, read from the client jar's own version.json
      // (pack_version.data_major = 107). This is the exact right number, so no supported_formats range
      // is needed.
      //
      // The range that used to be here was { min_inclusive: 15, max_inclusive: 9999 }, which claimed
      // support for versions far newer than the game. From this schema onwards that is a parse ERROR,
      // not a hint: Minecraft throws
      //   "Pack declares support for version newer than N, but is missing mandatory fields min_format
      //    and max_format"
      // and then falls back to GUESSING the pack type from the folder layout. Functions still loaded,
      // but a datapack guessed to be a resource pack would silently kill every /function.
      pack_format: 107,
      description: `StartBuild stock - ${label}`
    }
  }, null, 2) + '\n');

  const batches = [];
  for (let i = 0; i < giveable.length; i += batchSize) batches.push(giveable.slice(i, i + batchSize));

  const manifest = [];
  batches.forEach((batch, index) => {
    const commands = [];
    commands.push(`tellraw @s {"text":"StartBuild: batch ${index + 1}/${batches.length} - ${batch.length} block types","color":"aqua"}`);
    if (mode !== 'survival') {
      // Creative never consumes a placed block, so the 36 slots stay full forever. Without this the
      // next batch's /give would have nowhere to go - it would drop on the floor, Baritone would never
      // see the items, and an unattended build would stall permanently. Only the build's own block
      // types are cleared, so tools, armour and everything else are left alone.
      for (const [name] of giveable) commands.push(`clear @s ${name}`);
    }
    for (const [name, count] of batch) {
      if (mode === 'survival') {
        let left = count;
        while (left > 0) {
          const take = Math.min(64, left);
          commands.push(`give @s ${name} ${take}`);
          left -= take;
        }
      } else {
        // Creative does not consume blocks when placing, so one stack per type is enough.
        commands.push(`give @s ${name} 64`);
      }
    }
    const fnName = `${label}_${index + 1}`;
    fs.writeFileSync(path.join(fnDir, `${fnName}.mcfunction`), commands.join('\n') + '\n');
    manifest.push({
      function: `${ns}:${fnName}`,
      types: batch.length,
      blocks: batch.reduce((sum, [, c]) => sum + c, 0),
      commands: commands.length
    });
  });

  fs.writeFileSync(path.join(fnDir, `${label}_clear.mcfunction`),
    `tellraw @s {"text":"StartBuild: inventory cleared","color":"yellow"}\nclear @s\n`);

  const materialLines = [
    `${label}: ${entries.length} block types, ${totalBlocks.toLocaleString('en-US')} blocks total`,
    mode === 'survival' ? 'mode: survival (exact counts)' : 'mode: creative (one stack of each type)',
    `batches: ${batches.length} x up to ${batchSize} types`,
    '',
    ...entries.map(([name, count]) => `${String(count).padStart(9)}  ${name}`)
  ];
  if (skipped.length) {
    materialLines.push('', 'not obtainable as an item (Baritone will pause on these; consider buildSubstitutes or buildIgnoreBlocks):',
      ...skipped.map(([name, count]) => `${String(count).padStart(9)}  ${name}`));
  }
  fs.writeFileSync(path.join(packDir, 'materials.txt'), materialLines.join('\n') + '\n');

  const allCommands = batches.flatMap((batch) => batch.flatMap(([name, count]) => {
    if (mode === 'survival') {
      const out = [];
      let left = count;
      while (left > 0) { const take = Math.min(64, left); out.push(`/give @s ${name} ${take}`); left -= take; }
      return out;
    }
    return [`/give @s ${name} 64`];
  }));
  fs.writeFileSync(path.join(packDir, 'stock-commands.txt'), allCommands.join('\n') + '\n');

  return { label, packDir, ns, entries, skipped, totalBlocks, batches: manifest, mode, batchSize };
}

/* ------------------------------------------------------------------ preview images */

// 16 dye colours, so every coloured block family can be derived from its name.
const DYE_COLOURS = {
  white: [249, 254, 254], orange: [249, 128, 29], magenta: [199, 78, 189], light_blue: [58, 179, 218],
  yellow: [254, 216, 61], lime: [128, 199, 31], pink: [243, 139, 170], gray: [66, 67, 68],
  light_gray: [157, 157, 151], cyan: [22, 156, 156], purple: [137, 50, 184], blue: [60, 68, 170],
  brown: [114, 71, 40], green: [84, 109, 27], red: [161, 39, 34], black: [29, 29, 33]
};

const NATURAL_COLOURS = {
  'minecraft:stone': [125, 125, 125], 'minecraft:cobblestone': [110, 110, 110],
  'minecraft:mossy_cobblestone': [95, 110, 80], 'minecraft:deepslate': [80, 80, 84],
  'minecraft:cobbled_deepslate': [77, 77, 80], 'minecraft:polished_deepslate': [72, 72, 76],
  'minecraft:dirt': [134, 96, 67], 'minecraft:coarse_dirt': [119, 85, 59],
  'minecraft:grass_block': [95, 159, 53], 'minecraft:sand': [219, 207, 163],
  'minecraft:red_sand': [190, 102, 33], 'minecraft:sandstone': [216, 203, 155],
  'minecraft:gravel': [136, 126, 126], 'minecraft:clay': [160, 166, 179],
  'minecraft:terracotta': [152, 94, 67], 'minecraft:glass': [200, 220, 220],
  'minecraft:obsidian': [21, 18, 30], 'minecraft:netherrack': [97, 38, 38],
  'minecraft:blackstone': [42, 35, 40], 'minecraft:basalt': [72, 72, 78],
  'minecraft:polished_basalt': [88, 88, 94], 'minecraft:tuff': [108, 109, 102],
  'minecraft:andesite': [136, 136, 137], 'minecraft:granite': [149, 103, 85],
  'minecraft:diorite': [188, 188, 190], 'minecraft:coal_block': [16, 15, 15],
  'minecraft:iron_block': [220, 220, 220], 'minecraft:gold_block': [246, 208, 61],
  'minecraft:diamond_block': [97, 219, 213], 'minecraft:emerald_block': [42, 203, 86],
  'minecraft:redstone_block': [175, 24, 5], 'minecraft:lapis_block': [30, 67, 140],
  'minecraft:netherite_block': [67, 61, 60], 'minecraft:quartz_block': [235, 229, 222],
  'minecraft:prismarine': [99, 171, 158], 'minecraft:dark_prismarine': [51, 91, 75],
  'minecraft:water': [63, 118, 228], 'minecraft:lava': [207, 92, 15], 'minecraft:ice': [145, 183, 242],
  'minecraft:snow_block': [249, 254, 254], 'minecraft:bookshelf': [117, 90, 58],
  'minecraft:moss_block': [89, 109, 45], 'minecraft:mud': [60, 57, 60],
  'minecraft:mud_bricks': [137, 103, 79], 'minecraft:packed_mud': [142, 106, 79],
  'minecraft:bone_block': [229, 225, 207], 'minecraft:hay_block': [166, 136, 26],
  'minecraft:sculk': [13, 27, 29], 'minecraft:amethyst_block': [133, 97, 191],
  'minecraft:copper_block': [192, 107, 79], 'minecraft:oxidized_copper': [82, 162, 132],
  'minecraft:bricks': [150, 97, 83], 'minecraft:nether_bricks': [44, 22, 26],
  'minecraft:end_stone': [219, 222, 158], 'minecraft:purpur_block': [169, 125, 169],
  'minecraft:glowstone': [171, 131, 84], 'minecraft:sea_lantern': [172, 199, 190],
  'minecraft:magma_block': [142, 64, 26], 'minecraft:soul_sand': [81, 62, 50],
  'minecraft:crimson_nylium': [131, 31, 45], 'minecraft:warped_nylium': [43, 111, 101]
};

const WOOD_COLOURS = {
  oak: [162, 130, 78], spruce: [114, 84, 48], birch: [196, 179, 123], jungle: [154, 110, 76],
  acacia: [168, 90, 50], dark_oak: [66, 43, 20], mangrove: [117, 54, 48], cherry: [225, 180, 180],
  bamboo: [193, 175, 79], crimson: [101, 47, 68], warped: [43, 104, 99], pale_oak: [225, 220, 205]
};

const DYE_FAMILY = /^(white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_(wool|concrete|concrete_powder|terracotta|glazed_terracotta|stained_glass|stained_glass_pane|shulker_box|candle|bed|banner|carpet)$/;
const WOOD_FAMILY = /^(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|bamboo|crimson|warped|pale_oak)_/;

/** Approximate block colour. Unknown blocks get a stable grey from a hash, never a crash. */
function blockColour(name) {
  if (NATURAL_COLOURS[name]) return NATURAL_COLOURS[name];
  const bare = String(name).replace(/^minecraft:/, '');
  const dye = bare.match(DYE_FAMILY);
  if (dye && DYE_COLOURS[dye[1]]) return DYE_COLOURS[dye[1]];
  const wood = bare.match(WOOD_FAMILY);
  if (wood && WOOD_COLOURS[wood[1]]) {
    const base = WOOD_COLOURS[wood[1]];
    return /leaves$/.test(bare) ? [Math.round(base[0] * 0.75), Math.round(base[1] * 0.95), Math.round(base[2] * 0.6)] : base;
  }
  if (/leaves$/.test(bare)) return [70, 120, 45];
  if (/(glass|pane)$/.test(bare)) return [190, 210, 210];
  let hash = 0;
  for (let i = 0; i < bare.length; i++) hash = (hash * 31 + bare.charCodeAt(i)) >>> 0;
  const v = 90 + (hash % 110);
  return [v, v, v];
}

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])));
  return Buffer.concat([length, typeBuf, data, crc]);
}

/** Minimal 8-bit RGB PNG encoder - no dependencies. */
function encodePng(width, height, rgb) {
  const stride = width * 3;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgb.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 2;  // colour type: truecolour
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0))
  ]);
}

/** Yield every packed palette index in order (used for position-aware rendering). */
function* iterPackedIndices(longs, bits, count) {
  const modulus = Math.pow(2, bits);
  const perChunk = Math.max(1, Math.floor(53 / bits));
  let buf = 0n;
  let have = 0;
  let emitted = 0;
  for (let li = 0; li < longs.length && emitted < count; li++) {
    buf |= (longs[li] & MASK64) << BigInt(have);
    have += 64;
    while (have >= bits && emitted < count) {
      const take = Math.min(perChunk, Math.floor(have / bits), count - emitted);
      const chunkMask = (1n << BigInt(take * bits)) - 1n;
      let chunk = Number(buf & chunkMask);
      for (let k = 0; k < take; k++) {
        yield chunk % modulus;
        chunk = Math.floor(chunk / modulus);
      }
      buf >>= BigInt(take * bits);
      have -= take * bits;
      emitted += take;
    }
  }
}

/**
 * Highest non-air block per (x,z) column. Iteration order inside a region is x fastest, then z,
 * then y - the order Litematica writes - so index arithmetic gives us real positions.
 */
function topDownGrid(file, volumeLimit) {
  const root = parseNbtBuffer(fs.readFileSync(file));
  const regions = root.Regions || {};
  const size = (root.Metadata && root.Metadata.EnclosingSize) || {};
  const sx = Math.abs(axis(size, 'x'));
  const sz = Math.abs(axis(size, 'z'));
  if (!sx || !sz || sx * sz > 4_000_000) return null;

  const items = [];
  let minX = Infinity;
  let minZ = Infinity;
  for (const region of Object.values(regions)) {
    if (!region || typeof region !== 'object') continue;
    const pos = region.Position || {};
    const sz3 = region.Size || {};
    const wx = Math.abs(axis(sz3, 'x'));
    const wy = Math.abs(axis(sz3, 'y'));
    const wz = Math.abs(axis(sz3, 'z'));
    if (!wx || !wz) continue;
    const vol = wx * wy * wz;
    if (vol > volumeLimit) return null; // too big to render at interactive speed
    const rx = Math.min(axis(pos, 'x'), axis(pos, 'x') + axis(sz3, 'x'));
    const ry = Math.min(axis(pos, 'y'), axis(pos, 'y') + axis(sz3, 'y'));
    const rz = Math.min(axis(pos, 'z'), axis(pos, 'z') + axis(sz3, 'z'));
    minX = Math.min(minX, rx);
    minZ = Math.min(minZ, rz);
    items.push({ region, wx, wy, wz, vol, rx, ry, rz });
  }
  if (!items.length) return null;

  const names = new Array(sx * sz).fill('');
  const heights = new Int32Array(sx * sz).fill(-32768);
  let placed = 0;

  for (const item of items) {
    const palette = Array.isArray(item.region.BlockStatePalette) ? item.region.BlockStatePalette : [];
    if (!palette.length) continue;
    const bits = palette.length > 1 ? Math.max(2, Math.ceil(Math.log2(palette.length))) : 2;
    const longs = Array.isArray(item.region.BlockStates) ? item.region.BlockStates : [];
    const plane = item.wz * item.wx;
    let index = 0;
    for (const value of iterPackedIndices(longs, bits, item.vol)) {
      const name = paletteName(palette[value]);
      if (name && !AIR_NAME.test(name)) {
        const y = Math.floor(index / plane);
        const rem = index - y * plane;
        const z = Math.floor(rem / item.wx);
        const x = rem - z * item.wx;
        const gx = item.rx + x - minX;
        const gz = item.rz + z - minZ;
        if (gx >= 0 && gx < sx && gz >= 0 && gz < sz) {
          const gi = gz * sx + gx;
          const gy = item.ry + y;
          if (gy >= heights[gi]) {
            heights[gi] = gy;
            names[gi] = name;
            placed++;
          }
        }
      }
      index++;
    }
  }

  return { sx, sz, names, heights, placed };
}

/** Render one top-down preview PNG. @return info, or null when it could not be drawn. */
function renderPreview(file, pngPath, maxDim, volumeLimit) {
  const grid = topDownGrid(file, volumeLimit);
  if (!grid || !grid.placed) return null;

  const step = Math.max(1, Math.ceil(Math.max(grid.sx, grid.sz) / maxDim));
  const width = Math.max(1, Math.ceil(grid.sx / step));
  const height = Math.max(1, Math.ceil(grid.sz / step));
  const rgb = Buffer.alloc(width * height * 3);
  // Mid-grey background: creator builds are often very dark (coal block, blackstone, black wool),
  // and on a dark background they disappear into the canvas.
  for (let i = 0; i < width * height; i++) {
    rgb[i * 3] = 74; rgb[i * 3 + 1] = 79; rgb[i * 3 + 2] = 88;
  }

  let minY = Number.MAX_SAFE_INTEGER;
  let maxY = -Number.MAX_SAFE_INTEGER;
  for (let i = 0; i < grid.sx * grid.sz; i++) {
    if (grid.names[i]) {
      const y = grid.heights[i];
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  const span = Math.max(1, maxY - minY);

  for (let oy = 0; oy < height; oy++) {
    for (let ox = 0; ox < width; ox++) {
      let bestName = '';
      let bestY = -32768;
      for (let dz = 0; dz < step; dz++) {
        const z = oy * step + dz;
        if (z >= grid.sz) break;
        for (let dx = 0; dx < step; dx++) {
          const x = ox * step + dx;
          if (x >= grid.sx) break;
          const gi = z * grid.sx + x;
          if (grid.names[gi] && grid.heights[gi] > bestY) {
            bestY = grid.heights[gi];
            bestName = grid.names[gi];
          }
        }
      }
      if (!bestName) continue;
      const [r, g, b] = blockColour(bestName);
      // shade by height so the render reads as a heightmap rather than flat colour
      const shade = 0.5 + 0.5 * ((bestY - minY) / span);
      const o = (oy * width + ox) * 3;
      rgb[o] = Math.min(255, Math.round(r * shade));
      rgb[o + 1] = Math.min(255, Math.round(g * shade));
      rgb[o + 2] = Math.min(255, Math.round(b * shade));
    }
  }

  fs.mkdirSync(path.dirname(pngPath), { recursive: true });
  fs.writeFileSync(pngPath, encodePng(width, height, rgb));
  return { width, height, placed: grid.placed, minY, maxY };
}

/** Render previews for a set of rows plus a browsable index.html. */
function generatePreviews(rows, outDir, maxCount, maxDim, volumeLimit, warn) {
  const dir = path.join(outDir, 'previews');
  fs.mkdirSync(dir, { recursive: true });
  const made = [];
  for (const row of rows.slice(0, maxCount)) {
    try {
      const png = path.join(dir, row.file.replace(/\.(litematic|schem|schematic)$/i, '') + '.png');
      const info = renderPreview(row.path, png, maxDim, volumeLimit);
      if (info) {
        made.push({ row, png: path.basename(png), ...info });
      } else {
        warn(`  no preview for ${row.file} (too large, or nothing to draw)`);
      }
    } catch (err) {
      warn(`  preview failed for ${row.file}: ${err.message}`);
    }
  }

  const html = [
    '<!doctype html><meta charset="utf-8"><title>Schematic previews</title>',
    '<style>body{background:#15161a;color:#ddd;font:14px system-ui,sans-serif;margin:24px}',
    'figure{display:inline-block;vertical-align:top;margin:0 18px 22px 0;text-align:center}',
    'img{image-rendering:pixelated;background:#000;border:1px solid #333;max-width:260px;max-height:260px}',
    'figcaption{max-width:260px;margin-top:6px}</style>',
    `<h1>Schematic previews (${made.length})</h1>`,
    '<p>Top-down: each pixel is the highest non-air block in that column, shaded by height. ',
    'Colours are approximate - derived from block names, not textures.</p>'
  ];
  for (const m of made) {
    html.push('<figure>',
      `<img src="${encodeURIComponent(m.png)}" alt="${m.row.label}">`,
      `<figcaption><b>${m.row.label}</b><br>${blocksLabel(m.row)} blocks<br>`,
      `${m.row.size_x}x${m.row.size_y}x${m.row.size_z}</figcaption></figure>`);
  }
  fs.writeFileSync(path.join(dir, 'index.html'), html.join('\n') + '\n', 'utf8');
  return { dir, made };
}

/* ------------------------------------------------------------------ inspect mode */

function inspect(file) {
  const raw = fs.readFileSync(file);
  const root = parseNbtBuffer(raw);
  const describe = (v, depth = 0) => {
    if (Buffer.isBuffer(v)) return `<buffer ${v.length} bytes>`;
    if (Array.isArray(v)) {
      if (!v.length) return '[]';
      const inner = depth < 2 ? describe(v[0], depth + 1) : '...';
      return `[${v.length} x ${inner}]`;
    }
    if (v && typeof v === 'object') {
      const keys = Object.keys(v);
      if (depth >= 2) return `{${keys.length} keys}`;
      return '{\n' + keys.map((k) => `${'  '.repeat(depth + 1)}${k}: ${describe(v[k], depth + 1)}`).join('\n') +
        '\n' + '  '.repeat(depth) + '}';
    }
    if (typeof v === 'bigint') return v.toString() + 'n';
    return JSON.stringify(v);
  };
  console.log(`${file}  (${humanBytes(raw.length)}${raw[0] === 0x1f ? ', gzipped' : ', uncompressed'})`);
  console.log(describe(root));
}

/* ------------------------------------------------------------------ main */

async function main(argv) {
  const args = { folder: null, out: null, stage: null, top: null, onlyBuildable: false, doCount: true, countAll: false, jobs: null, extractZips: true, inspect: null };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--out') args.out = argv[++i];
    else if (a === '--stage') args.stage = argv[++i];
    else if (a === '--top') args.top = parseInt(argv[++i], 10);
    else if (a === '--jobs') args.jobs = parseInt(argv[++i], 10);
    else if (a === '--only-buildable') args.onlyBuildable = true;
    else if (a === '--no-count-blocks') args.doCount = false;
    else if (a === '--count-all') args.countAll = true;
    else if (a === '--no-extract-zips') args.extractZips = false;
    else if (a === '--inspect') args.inspect = argv[++i];
    else if (a === '--explain') args.explain = argv[++i];
    else if (a === '--stock') args.stock = argv[++i];
    else if (a === '--stock-out') args.stockOut = argv[++i];
    else if (a === '--stock-mode') args.stockMode = argv[++i];
    else if (a === '--stock-batch') args.stockBatch = parseInt(argv[++i], 10);
    else if (a === '--min-blocks') args.minBlocks = parseInt(argv[++i], 10);
    else if (a === '--max-blocks') args.maxBlocks = parseInt(argv[++i], 10);
    else if (a === '--author') args.author = argv[++i];
    else if (a === '--name') args.nameFilter = argv[++i];
    else if (a === '--materials') args.materials = argv[++i];
    else if (a === '--previews') args.previews = true;
    else if (a === '--preview-max') args.previewMax = parseInt(argv[++i], 10);
    else if (a === '--preview-dim') args.previewDim = parseInt(argv[++i], 10);
    else if (a === '--no-cache') args.noCache = true;
    else if (a === '-h' || a === '--help') { args.help = true; }
    else rest.push(a);
  }
  args.folder = rest[0];

  if (args.help || (!args.folder && !args.inspect && !args.explain && !args.stock)) {
    console.log(fs.readFileSync(new URL(import.meta.url), 'utf8').split('*/')[0].replace(/^#!.*\n/, ''));
    return args.help ? 0 : 2;
  }
  if (args.inspect) { inspect(args.inspect); return 0; }
  if (args.explain) { explain(args.explain); return 0; }
  if (args.stock) {
    const stockFile = path.resolve(args.stock);
    if (!fs.existsSync(stockFile)) { console.error('not a file: ' + stockFile); return 2; }
    const mode = (args.stockMode || 'creative').toLowerCase();
    if (!['creative', 'survival'].includes(mode)) {
      console.error("--stock-mode must be 'creative' or 'survival'");
      return 2;
    }
    const batchSize = args.stockBatch || 36;
    const outRoot = args.stockOut
      ? path.resolve(args.stockOut)
      : path.join(path.dirname(stockFile), '_stock');
    const result = writeStock(stockFile, outRoot, mode, batchSize);

    console.log('');
    console.log('='.repeat(70));
    console.log(`Stock pack: ${result.label}`);
    console.log(`  ${result.entries.length} block types, ${result.totalBlocks.toLocaleString('en-US')} blocks total`);
    console.log(`  mode: ${result.mode}${result.mode === 'creative' ? ' (one stack of each type - creative does not consume blocks)' : ' (exact counts)'}`);
    console.log(`  ${result.batches.length} batch(es) of up to ${result.batchSize} types (36 = one inventory)`);
    if (result.skipped.length) {
      console.log(`  ${result.skipped.length} type(s) with no item form, excluded: ` +
        result.skipped.map(([n]) => n.replace('minecraft:', '')).join(', '));
    }
    console.log('='.repeat(70));
    console.log('');
    console.log('Most needed:');
    for (const [name, count] of result.entries.slice(0, 10)) {
      console.log(`  ${String(count).padStart(9)}  ${name}`);
    }
    console.log('');
    console.log('Datapack written to:');
    console.log(`  ${result.packDir}`);
    console.log('');
    console.log('To use it:');
    console.log(`  1. copy that whole folder into   <world>\\datapacks\\${result.label}\\`);
    console.log('     (in Prism: instances/<instance>/minecraft/saves/<world>/datapacks/)');
    console.log('  2. in game (cheats on):   /reload');
    for (const batch of result.batches) {
      console.log(`     /function ${batch.function}    <- ${batch.types} types, ${batch.blocks.toLocaleString('en-US')} blocks`);
    }
    console.log(`     /function ${result.ns}:${result.label}_clear    <- resets your inventory`);
    console.log('');
    console.log('Run batch 1, and the next batch whenever Baritone pauses for materials.');
    console.log(`Material list: ${path.join(result.packDir, 'materials.txt')}`);
    return 0;
  }

  const folder = path.resolve(args.folder);
  if (!fs.existsSync(folder) || !fs.statSync(folder).isDirectory()) {
    console.error('not a folder: ' + folder);
    return 2;
  }

  const outDir = args.out ? path.resolve(args.out) : path.join(folder, '_catalog');
  const opts = { doCount: args.doCount, decodeLimit: args.countAll ? Number.MAX_SAFE_INTEGER : DEFAULT_DECODE_LIMIT };
  const warn = (m) => console.error(m);

  console.log(`Scanning ${folder}`);
  let files = findSchematics(folder).filter((f) => !f.startsWith(outDir + path.sep));

  if (args.extractZips) {
    const zips = [...walk(folder)].filter((f) => ZIP_EXTS.includes(path.extname(f).toLowerCase()));
    if (zips.length) {
      console.log(`Found ${zips.length} zip archive(s); looking inside...`);
      files = files.concat(extractZips(folder, outDir, warn));
    }
  }

  files = [...new Set(files)].sort();
  if (!files.length) {
    console.log(`No ${SCHEMATIC_EXTS.join(' / ')} files found under ${folder}`);
    return 1;
  }

  const jobs = args.jobs || Math.max(1, os.availableParallelism ? os.availableParallelism() : os.cpus().length);

  // Incremental cache: re-analysis is skipped for files whose size and mtime are unchanged. The key
  // includes the analysis mode, so switching --count-all / --no-count-blocks invalidates it.
  const CACHE_VERSION = 'schema3';
  const cachePath = path.join(outDir, 'cache.json');
  const cacheKey = `${CACHE_VERSION}|${opts.doCount ? 'counted' : 'meta'}|${opts.decodeLimit}`;
  let cacheEntries = {};
  if (!args.noCache && fs.existsSync(cachePath)) {
    try {
      const cached = JSON.parse(fs.readFileSync(cachePath, 'utf8'));
      if (cached && cached.key === cacheKey && cached.entries) {
        cacheEntries = cached.entries;
      }
    } catch (err) {
      warn(`  ignoring unreadable cache: ${err.message}`);
    }
  }

  const reused = [];
  const pending = [];
  for (const file of files) {
    try {
      const st = fs.statSync(file);
      const hit = cacheEntries[file];
      if (hit && hit.size === st.size && hit.mtimeMs === Math.round(st.mtimeMs)) {
        reused.push(hit.row);
        continue;
      }
    } catch (err) {
      // fall through and analyse it
    }
    pending.push(file);
  }

  console.log(`Analysing ${pending.length} file(s)${args.doCount ? ' with block counting' : ' (metadata only)'}` +
    `${reused.length ? `, reusing ${reused.length} from cache` : ''} using ${jobs} workers...`);
  let done = 0;
  const fresh = await runWorkerPool(pending, opts, jobs, (n) => {
    done += n;
    if (done % 100 === 0) process.stdout.write(`  ${done}/${pending.length}\r`);
  });
  const rows = [...reused, ...fresh];

  if (!args.noCache) {
    const nextEntries = {};
    for (const row of rows) {
      try {
        const st = fs.statSync(row.path);
        nextEntries[row.path] = { size: st.size, mtimeMs: Math.round(st.mtimeMs), row };
      } catch (err) {
        // file vanished mid-run; leave it out of the cache
      }
    }
    try {
      fs.writeFileSync(cachePath, JSON.stringify({ key: cacheKey, entries: nextEntries }));
    } catch (err) {
      warn(`  could not write cache: ${err.message}`);
    }
  }

  // Optional filters. Unreadable rows are always kept so failures stay visible.
  const filters = [];
  if (Number.isFinite(args.minBlocks)) filters.push((r) => (r.non_air_blocks || 0) >= args.minBlocks);
  if (Number.isFinite(args.maxBlocks)) filters.push((r) => r.non_air_blocks != null && r.non_air_blocks <= args.maxBlocks);
  if (args.author) {
    const needle = args.author.toLowerCase();
    filters.push((r) => `${r.author} ${r.name} ${r.label}`.toLowerCase().includes(needle));
  }
  if (args.nameFilter) {
    const needle = args.nameFilter.toLowerCase();
    filters.push((r) => `${r.file} ${r.name} ${r.label}`.toLowerCase().includes(needle));
  }
  const readable = rows.filter((r) => !r.error).length;
  const shown = filters.length
    ? rows.filter((r) => r.error || filters.every((f) => f(r)))
    : rows;
  if (filters.length) {
    console.log(`Filters match ${shown.filter((r) => !r.error).length} of ${readable} readable file(s)`);
  }

  const { ranked, bad, dupes, twins, contentGroups } = writeCatalog(shown, outDir);
  const totalBytes = rows.reduce((s, r) => s + r.bytes, 0);
  const counted = ranked.filter((r) => r.non_air_blocks != null);
  const exact = ranked.filter((r) => r.block_count_exact);
  const reported = counted.filter((r) => r.blocks_source === 'metadata');
  const buildable = ranked.filter((r) => r.buildable);
  const wasted = dupes.reduce((s, [, g]) => s + g.slice(1).reduce((t, r) => t + r.bytes, 0), 0);

  console.log('');
  console.log('='.repeat(70));
  console.log(`${rows.length} file(s), ${humanBytes(totalBytes)} total`);
  console.log(`${ranked.length} readable, ${bad.length} unreadable`);
  console.log(`${exact.length} block counts decoded exactly, ${reported.length} taken from file metadata, ${buildable.length} ready for Baritone #build`);
  const checked = ranked.filter((r) => r.count_check);
  const disagree = checked.filter((r) => r.count_check !== 'matches metadata');
  if (checked.length) {
    console.log(`self-check: decoded block count agrees with the file's own TotalBlocks in ` +
      `${checked.length - disagree.length}/${checked.length} file(s)${disagree.length ? ' - see count_check in catalog.csv' : ''}`);
  }
  if (twins.length) console.log(`${twins.length} same-build group(s) (same build, multiple formats)`);
  if (contentGroups.length) {
    console.log(`${contentGroups.length} same-content group(s) (identical block tally and size - probably the same build renamed)`);
  }
  if (dupes.length) console.log(`${dupes.length} byte-identical duplicate group(s), wasting ${humanBytes(wasted)}`);
  console.log(`catalog: ${path.join(outDir, 'catalog.md')}`);
  console.log('='.repeat(70));

  if (ranked.length) {
    console.log('\nBiggest builds by real block count:');
    console.log(`  ${'blocks'.padEnd(14)}${'size (x,y,z)'.padEnd(16)}${'name'.padEnd(28)}file`);
    for (const r of ranked.slice(0, 15)) {
      const size = `${r.size_x}x${r.size_y}x${r.size_z}`;
      console.log(`  ${blocksLabel(r).slice(0, 13).padEnd(14)}${size.padEnd(16)}${r.label.slice(0, 27).padEnd(28)}${r.file.slice(0, 40)}`);
    }
  }

  if (args.stage) {
    const copied = stageShortlist(ranked, path.resolve(args.stage), args.top, args.onlyBuildable);
    console.log(`\nStaged ${copied.length} file(s) into ${path.resolve(args.stage)}`);
    for (const name of copied.slice(0, 20)) console.log('   ' + name);
    if (copied.length > 20) console.log(`   ... and ${copied.length - 20} more`);
  }

  // Combined shopping list for whatever the filters selected (or the top N).
  if (args.materials) {
    const selected = ranked.slice(0, Number.isFinite(args.top) ? args.top : ranked.length);
    const totals = new Map();
    for (const row of selected) {
      try {
        for (const [name, count] of paletteCounts(row.path)) {
          totals.set(name, (totals.get(name) || 0) + count);
        }
      } catch (err) {
        warn(`  materials: could not read ${row.file}: ${err.message}`);
      }
    }
    const ordered = [...totals.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    const totalBlocks = ordered.reduce((sum, [, c]) => sum + c, 0);
    const lines = [
      `# combined materials for ${selected.length} build(s)`,
      `# ${ordered.length} block types, ${totalBlocks.toLocaleString('en-US')} blocks`,
      '',
      ...ordered.map(([name, count]) => `${String(count).padStart(10)}  ${name}`)
    ];
    fs.writeFileSync(path.resolve(args.materials), lines.join('\n') + '\n', 'utf8');
    console.log(`\nCombined materials for ${selected.length} build(s) ` +
      `(${ordered.length} types, ${totalBlocks.toLocaleString('en-US')} blocks):`);
    console.log(`  ${path.resolve(args.materials)}`);
  }

  // Top-down preview renders, plus a browsable index page.
  if (args.previews) {
    const maxCount = Number.isFinite(args.previewMax) ? args.previewMax : 40;
    const maxDim = Number.isFinite(args.previewDim) ? args.previewDim : 320;
    const volumeLimit = args.countAll ? 80_000_000 : 30_000_000;
    console.log(`\nRendering up to ${maxCount} preview(s) at ${maxDim}px...`);
    const result = generatePreviews(ranked, outDir, maxCount, maxDim, volumeLimit, warn);
    console.log(`Wrote ${result.made.length} preview(s). Browse them at:`);
    console.log(`  ${path.join(result.dir, 'index.html')}`);
  }

  return 0;
}

if (isMainThread) {
  main(process.argv.slice(2)).then((code) => process.exit(code)).catch((err) => {
    console.error('error: ' + (err && err.stack ? err.stack : err));
    process.exit(1);
  });
}
