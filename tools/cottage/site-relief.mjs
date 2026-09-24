// Draws a relief map of a build site straight from the world save, to judge the terraforming without
// screenshots: hill-shaded ground height, tinted by what the ground is made of, the build's footprint
// outlined, and every step of 3+ blocks between neighbouring columns marked red (a cliff / seam that would
// look fake on camera). Also prints the numbers.
//
//   node tools/cottage/site-relief.mjs <regionDir> <originX> <originZ> <sizeX> <sizeZ> [margin=32] [out.png]
//
// Run it after the take (the game writes chunks to disk when it saves / quits).

import fs from 'node:fs';
import zlib from 'node:zlib';

import { loadWorldReader } from './world.mjs';

const [regionDir, oxs, ozs, sxs, szs, ms, outArg] = process.argv.slice(2);
if (!regionDir || szs === undefined) {
  console.error('usage: site-relief.mjs <regionDir> <originX> <originZ> <sizeX> <sizeZ> [margin=32] [out.png]');
  process.exit(1);
}
const ox = +oxs, oz = +ozs, sx = +sxs, sz = +szs, margin = ms ? +ms : 32;
const out = outArg || 'site-relief.png';
const at = loadWorldReader(regionDir);

const x0 = ox - margin, z0 = oz - margin, W = sx + 2 * margin, D = sz + 2 * margin;
const bare = (n) => n.replace('minecraft:', '');

// Natural ground (what terraforming shapes) vs everything else (trees, plants, snow layers, the build).
const GROUND = /^(grass_block|dirt|coarse_dirt|rooted_dirt|podzol|mycelium|dirt_path|farmland|sand|red_sand|gravel|clay|mud|moss_block|snow_block|powder_snow|ice|packed_ice|blue_ice|stone|granite|diorite|andesite|deepslate|tuff|calcite|sandstone|red_sandstone|terracotta|.*_terracotta|water)$/;
const PLANT = /^(short_grass|tall_grass|fern|large_fern|dead_bush|bush|firefly_bush|short_dry_grass|tall_dry_grass|.*tulip|dandelion|poppy|blue_orchid|allium|azure_bluet|oxeye_daisy|cornflower|lily_of_the_valley|torchflower|sweet_berry_bush|pink_petals|wildflowers|leaf_litter)$/;

const ground = new Int32Array(W * D), mat = new Array(W * D), built = new Uint8Array(W * D), plant = new Uint8Array(W * D);
for (let dz = 0; dz < D; dz++) {
  for (let dx = 0; dx < W; dx++) {
    const x = x0 + dx, z = z0 + dz, i = dz * W + dx;
    ground[i] = -9999;
    mat[i] = 'unloaded';
    for (let y = 200; y > -64; y--) {
      const n = at(x, y, z);
      if (n === 'UNLOADED') break;
      const b = bare(n);
      if (b === 'air' || b === 'cave_air' || b === 'snow' || b.endsWith('_leaves') || b.endsWith('_log') || b.endsWith('_wood')) continue;
      if (PLANT.test(b)) { plant[i] = 1; continue; }
      if (GROUND.test(b)) { ground[i] = y; mat[i] = b; break; }
      built[i] = 1;                          // anything else above the ground: the build (or a structure)
    }
  }
}

// ---- numbers
const inFoot = (dx, dz) => dx >= margin && dx < margin + sx && dz >= margin && dz < margin + sz;
let steps = [0, 0, 0, 0], ring = 0, ringPlants = 0, outside = 0, outsidePlants = 0;
const mats = new Map();
for (let dz = 0; dz < D; dz++) {
  for (let dx = 0; dx < W; dx++) {
    const i = dz * W + dx;
    if (ground[i] === -9999) continue;
    if (!inFoot(dx, dz)) {
      mats.set(mat[i], (mats.get(mat[i]) || 0) + 1);
      const near = dx >= margin - 14 && dx < margin + sx + 14 && dz >= margin - 14 && dz < margin + sz + 14;
      if (near) { ring++; ringPlants += plant[i]; } else { outside++; outsidePlants += plant[i]; }
    }
    for (const [ax, az] of [[1, 0], [0, 1]]) {
      const j = (dz + az) * W + dx + ax;
      if (dx + ax >= W || dz + az >= D || ground[j] === -9999) continue;
      const step = Math.abs(ground[i] - ground[j]);
      steps[Math.min(3, step)]++;
    }
  }
}
console.log(`site ${ox},${oz} ${sx}x${sz}, margin ${margin}: ${regionDir}`);
console.log(`neighbour steps: 0=${steps[0]} 1=${steps[1]} 2=${steps[2]} 3+=${steps[3]}  (3+ are cliffs/seams, marked red)`);
console.log(`plants per column: blend ring (<=14 from the footprint) ${(ringPlants / Math.max(1, ring)).toFixed(3)}, `
  + `further out ${(outsidePlants / Math.max(1, outside)).toFixed(3)}  (similar = the replanting matches)`);
console.log('ground outside the footprint: ' + [...mats.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8)
  .map(([k, v]) => `${k} ${v}`).join(', '));

// ---- image: 4 px per column
const P = 4, IW = W * P, IH = D * P;
const px = Buffer.alloc(IW * IH * 4);
const TINT = { grass_block: [96, 150, 70], podzol: [120, 90, 50], snow_block: [235, 240, 245], powder_snow: [235, 240, 245],
  sand: [220, 205, 150], red_sand: [200, 120, 60], gravel: [140, 135, 130], stone: [128, 128, 128], dirt: [130, 95, 60],
  coarse_dirt: [120, 90, 60], water: [60, 100, 200], ice: [170, 200, 240], packed_ice: [150, 180, 230], clay: [160, 165, 180],
  mud: [70, 60, 55], moss_block: [90, 120, 50], unloaded: [0, 0, 0] };
let lo = Infinity, hi = -Infinity;
for (const g of ground) if (g !== -9999) { lo = Math.min(lo, g); hi = Math.max(hi, g); }
for (let dz = 0; dz < D; dz++) {
  for (let dx = 0; dx < W; dx++) {
    const i = dz * W + dx, g = ground[i];
    let c = TINT[mat[i]] || [150, 150, 110];
    if (g !== -9999) {
      // hill shade from the north-west, plus a little height brightness
      const gw = dx > 0 && ground[i - 1] !== -9999 ? ground[i - 1] : g;
      const gn = dz > 0 && ground[i - W] !== -9999 ? ground[i - W] : g;
      const shade = Math.max(0.45, Math.min(1.35, 1 + (gw - g) * 0.12 + (gn - g) * 0.12));
      const lift = 0.85 + 0.3 * (hi > lo ? (g - lo) / (hi - lo) : 0.5);
      c = c.map((v) => Math.max(0, Math.min(255, Math.round(v * shade * lift))));
      if (built[i]) c = [Math.round(c[0] * 0.5 + 110), Math.round(c[1] * 0.5 + 40), Math.round(c[2] * 0.5 + 130)];
      if (plant[i]) c = [Math.round(c[0] * 0.7), Math.min(255, Math.round(c[1] * 0.7 + 60)), Math.round(c[2] * 0.7)];
    }
    for (let yy = 0; yy < P; yy++) {
      for (let xx = 0; xx < P; xx++) {
        const o = ((dz * P + yy) * IW + dx * P + xx) * 4;
        px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; px[o + 3] = 255;
      }
    }
  }
}
const mark = (x, y, c) => { if (x < 0 || y < 0 || x >= IW || y >= IH) return; const o = (y * IW + x) * 4; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; };
// cliffs / seams (3+ steps) in red, on the edge between the two columns
for (let dz = 0; dz < D; dz++) {
  for (let dx = 0; dx < W; dx++) {
    const i = dz * W + dx;
    if (ground[i] === -9999) continue;
    if (dx + 1 < W && ground[i + 1] !== -9999 && Math.abs(ground[i] - ground[i + 1]) >= 3)
      for (let k = 0; k < P; k++) { mark(dx * P + P - 1, dz * P + k, [230, 30, 30]); mark(dx * P + P, dz * P + k, [230, 30, 30]); }
    if (dz + 1 < D && ground[i + W] !== -9999 && Math.abs(ground[i] - ground[i + W]) >= 3)
      for (let k = 0; k < P; k++) { mark(dx * P + k, dz * P + P - 1, [230, 30, 30]); mark(dx * P + k, dz * P + P, [230, 30, 30]); }
  }
}
// footprint outline (the schematic's bounding box) in yellow
for (let k = 0; k <= sx * P; k++) { mark(margin * P + k, margin * P, [250, 220, 0]); mark(margin * P + k, (margin + sz) * P, [250, 220, 0]); }
for (let k = 0; k <= sz * P; k++) { mark(margin * P, margin * P + k, [250, 220, 0]); mark((margin + sx) * P, margin * P + k, [250, 220, 0]); }

// ---- minimal PNG writer
const crcTable = new Int32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc32 = (buf) => { let c = -1; for (const b of buf) c = crcTable[(c ^ b) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; };
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
};
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(IW, 0); ihdr.writeUInt32BE(IH, 4); ihdr[8] = 8; ihdr[9] = 6;
const raw = Buffer.alloc(IH * (IW * 4 + 1));
for (let y = 0; y < IH; y++) px.copy(raw, y * (IW * 4 + 1) + 1, y * IW * 4, (y + 1) * IW * 4);
fs.writeFileSync(out, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
console.log(`wrote ${out} (${IW}x${IH}; yellow = the build's bounding box, purple = build, green specks = plants, red = 3+ block steps)`);
