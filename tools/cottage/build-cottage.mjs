// Builds a cottage schematic from the reference render, and renders an isometric preview so the
// result can be checked before it is ever loaded into the game.
//
// HONEST SCOPE. The reference is a painted/AI-generated illustration, not a real in-game screenshot:
// its block grid is inconsistent (plank widths, the cobble patch, the crops and the curved path are
// all painterly), so there is no exact underlying voxel layout to recover. One view also cannot say
// what the back wall or the interior look like. What this produces is therefore a faithful
// RECREATION of the silhouette, palette and details that are actually visible - hip-roofed plank
// cottage, cobble foundation, stone-brick chimney with a fire, shuttered windows, a flower-pot
// porch, a fenced crop plot and a dirt path. Everything facing away from the camera is invented.
//
// Usage:
//   node tools/cottage/build-cottage.mjs                 # build + verify + preview into tools/cottage/out
//   node tools/cottage/build-cottage.mjs --install       # also copy the .litematic into the instance
//
// Every block name is checked against tools/cottage/known-blocks.txt, which is the real vanilla
// blockstate list extracted from the 26.2 client jar - so a typo fails here rather than showing up
// as a missing block in game.

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

import { writeLitematic, readLitematic, parseState, indexOfBlock } from './litematic.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(HERE, 'out');

/* ------------------------------------------------------------------ block names */

const KNOWN = new Set(
  fs.readFileSync(path.join(HERE, 'known-blocks.txt'), 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean),
);

function checkBlockNames(states) {
  const bad = [];
  for (const state of new Set(states)) {
    const { Name } = parseState(state);
    const bare = Name.replace(/^minecraft:/, '');
    if (!Name.startsWith('minecraft:')) bad.push(`${state} (only minecraft: is supported)`);
    else if (!KNOWN.has(bare)) bad.push(`${state} (no such block in the 26.2 client jar)`);
  }
  return bad;
}

/* ------------------------------------------------------------------ grid builder */

class Build {
  constructor(size) {
    this.size = size;
    this.grid = new Map();
  }

  set(x, y, z, state) {
    const { x: sx, y: sy, z: sz } = this.size;
    if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
      throw new Error(`set(${x},${y},${z}) is outside the ${sx}x${sy}x${sz} canvas`);
    }
    const key = `${x},${y},${z}`;
    if (state === null || state === 'minecraft:air') this.grid.delete(key);
    else this.grid.set(key, state);
  }

  get(x, y, z) {
    return this.grid.get(`${x},${y},${z}`) || 'minecraft:air';
  }

  /** Fill an inclusive box. */
  box(x1, y1, z1, x2, y2, z2, state) {
    for (let x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
      for (let y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
        for (let z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) this.set(x, y, z, state);
      }
    }
  }

  /** A hollow rectangle of wall: the ring of a box, 1 block thick, at one Y. */
  ring(y, x1, z1, x2, z2, state) {
    for (let x = x1; x <= x2; x++) {
      this.set(x, y, z1, state);
      this.set(x, y, z2, state);
    }
    for (let z = z1; z <= z2; z++) {
      this.set(x1, y, z, state);
      this.set(x2, y, z, state);
    }
  }

  /** Box edges only (posts and rails), for fence perimeters. */
  fenceRing(y, x1, z1, x2, z2, state) {
    this.ring(y, x1, z1, x2, z2, state);
  }

  count() { return this.grid.size; }
}

/* ------------------------------------------------------------------ the design */

// Canvas. X is left-right, Z is back(0)-to-front, Y is up.
//
// The preview renders in 2:1 isometric looking from direction (1,1,1), which shows a block's +X and
// +Z faces. So the house's FRONT is the +Z wall (Z = HZ2) - otherwise the door, windows and porch all
// end up on the hidden side, which is exactly what the first attempt did. +X is on the right, +Z on
// the left, and larger (x+z) is nearer the camera.
const SIZE = { x: 20, y: 18, z: 18 };

const HX1 = 5, HX2 = 16;      // 12 wide
const HZ1 = 3, HZ2 = 12;      // 10 deep, front wall at Z = HZ2
const WALL_TOP = 6;           // walls occupy Y2..Y6

const GROUND = 0;
const FLOOR = 1;              // cobble foundation slab

function buildCottage(b) {
  /* ---- ground -------------------------------------------------------- */
  b.box(0, GROUND, 0, SIZE.x - 1, GROUND, SIZE.z - 1, 'minecraft:grass_block');

  /* ---- house shell --------------------------------------------------- */
  b.box(HX1, FLOOR, HZ1, HX2, FLOOR, HZ2, 'minecraft:cobblestone');   // foundation slab
  b.box(HX1, FLOOR + 1, HZ1, HX2, FLOOR + 1, HZ2, 'minecraft:air');   // clear above the slab
  for (let y = FLOOR + 1; y <= WALL_TOP; y++) {
    b.ring(y, HX1, HZ1, HX2, HZ2, 'minecraft:oak_planks');
  }
  // Corner posts, plus the framed panelling either side of the front wall.
  for (const [x, z] of [[HX1, HZ1], [HX2, HZ1], [HX1, HZ2], [HX2, HZ2]]) {
    for (let y = FLOOR + 1; y <= WALL_TOP; y++) b.set(x, y, z, 'minecraft:oak_log[axis=y]');
  }
  // The stone patch high on the reference's front wall.
  b.box(6, WALL_TOP, HZ2, 9, WALL_TOP, HZ2, 'minecraft:cobblestone');
  b.set(6, WALL_TOP - 1, HZ2, 'minecraft:cobblestone');

  /* ---- door and windows on the front wall (Z = HZ2, facing south) ---- */
  const doorX = 12;
  b.set(doorX, FLOOR + 2, HZ2, 'minecraft:dark_oak_door[facing=south,half=lower,hinge=left,open=false]');
  b.set(doorX, FLOOR + 3, HZ2, 'minecraft:dark_oak_door[facing=south,half=upper,hinge=left,open=false]');

  const winY = FLOOR + 3;
  for (const x of [7, 8, 14, 15]) b.set(x, winY, HZ2, 'minecraft:glass_pane');
  for (const x of [6, 9, 13, 16]) {
    b.set(x, winY, HZ2, 'minecraft:oak_trapdoor[facing=south,half=top,open=false]');
  }
  // Upper window under the eaves, echoing the reference's high window.
  b.set(10, WALL_TOP, HZ2, 'minecraft:glass_pane');
  b.set(11, WALL_TOP, HZ2, 'minecraft:glass_pane');
  // A window on the right-hand wall, which the preview also shows.
  b.set(HX2, winY, 7, 'minecraft:glass_pane');
  b.set(HX2, winY, 8, 'minecraft:glass_pane');

  /* ---- stepped hip roof --------------------------------------------- */
  // Each layer rises one and insets one, which is what gives the reference its banded, terraced roof.
  const eaves = { x1: HX1 - 1, z1: HZ1 - 1, x2: HX2 + 1, z2: HZ2 + 1 };
  for (let i = 0; i < 12; i++) {
    const x1 = eaves.x1 + i, x2 = eaves.x2 - i;
    const z1 = eaves.z1 + i, z2 = eaves.z2 - i;
    if (x2 < x1 || z2 < z1) break;
    b.box(x1, WALL_TOP + 1 + i, z1, x2, WALL_TOP + 1 + i, z2, 'minecraft:spruce_planks');
  }

  /* ---- chimney ------------------------------------------------------- */
  // Drawn after the roof so it punches through it, as a real chimney does.
  const chX = 15;
  b.box(chX, FLOOR + 1, 5, chX, WALL_TOP + 7, 6, 'minecraft:stone_bricks');
  b.set(chX, WALL_TOP + 8, 5, 'minecraft:campfire[facing=north,lit=true]');

  /* ---- porch: fence rail, posts with flower pots, a lantern ---------- */
  const porchZ = HZ2 + 1;
  for (let x = 6; x <= 11; x++) b.set(x, FLOOR, porchZ, 'minecraft:oak_fence');
  for (const x of [17, 18]) b.set(x, FLOOR, 6, 'minecraft:oak_fence');
  b.set(6, FLOOR + 1, porchZ, 'minecraft:lantern[hanging=false]');
  b.set(7, FLOOR + 1, porchZ, 'minecraft:potted_red_tulip');
  b.set(10, FLOOR + 1, porchZ, 'minecraft:potted_pink_tulip');
  b.set(16, FLOOR + 1, HZ2 + 2, 'minecraft:potted_orange_tulip');
  b.set(17, FLOOR + 1, HZ2 + 2, 'minecraft:potted_pink_tulip');

  /* ---- clutter at the back, seen past the roof edge ------------------ */
  b.set(17, FLOOR, 4, 'minecraft:barrel[facing=up,open=false]');
  b.set(18, FLOOR, 5, 'minecraft:composter');

  /* ---- crop plot, front-left (low X, high Z = bottom-left on screen) - */
  const fx1 = 1, fz1 = 13, fx2 = 3, fz2 = 16;
  b.box(fx1, GROUND, fz1, fx2, GROUND, fz2, 'minecraft:farmland[moisture=7]');
  b.set(2, GROUND, 14, 'minecraft:water');                    // single source irrigates the whole plot
  const crops = ['minecraft:wheat[age=7]', 'minecraft:carrots[age=7]', 'minecraft:beetroots[age=3]'];
  let c = 0;
  for (let z = fz1; z <= fz2; z++) {
    for (let x = fx1; x <= fx2; x++) {
      if (x === 2 && z === 14) continue;
      b.set(x, GROUND + 1, z, crops[c++ % crops.length]);
    }
  }
  // Fence around the plot, with a gate facing the path.
  b.fenceRing(GROUND + 1, fx1 - 1, fz1 - 1, fx2 + 1, fz2 + 1, 'minecraft:oak_fence');
  b.set(fx2 + 1, GROUND + 1, 15, 'minecraft:oak_fence_gate[facing=east,in_wall=false,open=false]');

  /* ---- dirt path from the door to the front edge -------------------- */
  for (let z = HZ2 + 1; z <= SIZE.z - 1; z++) {
    b.set(12, GROUND, z, 'minecraft:dirt_path');
    b.set(13, GROUND, z, 'minecraft:dirt_path');
  }
  for (let x = 13; x <= 19; x++) b.set(x, GROUND, SIZE.z - 1, 'minecraft:dirt_path');
  for (let x = 5; x <= 19; x++) b.set(x, GROUND, SIZE.z - 2, 'minecraft:dirt_path');

  /* ---- grass tufts, avoiding anything already placed ----------------- */
  const spots = [
    [18, 9], [19, 11], [18, 14], [19, 16], [17, 2], [18, 1], [19, 8], [17, 11],
    [0, 6], [0, 9], [1, 6], [2, 9], [0, 3], [3, 4], [4, 8], [0, 11],
    [6, 16], [9, 17], [15, 16], [16, 17], [10, 16], [5, 17],
  ];
  for (const [x, z] of spots) {
    if (b.get(x, GROUND, z) === 'minecraft:grass_block' && b.get(x, GROUND + 1, z) === 'minecraft:air') {
      b.set(x, GROUND + 1, z, 'minecraft:short_grass');
    }
  }
}

/* ------------------------------------------------------------------ isometric preview */

const COLORS = {
  'minecraft:grass_block': [122, 168, 84],
  'minecraft:dirt_path': [148, 118, 74],
  'minecraft:farmland': [124, 84, 56],
  'minecraft:water': [58, 108, 200],
  'minecraft:oak_planks': [178, 143, 86],
  'minecraft:spruce_planks': [114, 84, 48],
  'minecraft:oak_log': [124, 100, 62],
  'minecraft:cobblestone': [128, 128, 128],
  'minecraft:stone_bricks': [122, 122, 122],
  'minecraft:oak_slab': [170, 137, 82],
  'minecraft:oak_stairs': [170, 137, 82],
  'minecraft:oak_fence': [150, 118, 68],
  'minecraft:oak_fence_gate': [150, 118, 68],
  'minecraft:dark_oak_door': [78, 55, 30],
  'minecraft:glass_pane': [196, 222, 232],
  'minecraft:oak_trapdoor': [150, 118, 68],
  'minecraft:potted_red_tulip': [190, 60, 60],
  'minecraft:potted_pink_tulip': [225, 150, 180],
  'minecraft:potted_orange_tulip': [225, 140, 60],
  'minecraft:lantern': [240, 205, 120],
  'minecraft:barrel': [140, 108, 66],
  'minecraft:composter': [122, 92, 56],
  'minecraft:wheat': [214, 194, 110],
  'minecraft:carrots': [86, 150, 62],
  'minecraft:beetroots': [96, 140, 72],
  'minecraft:short_grass': [108, 162, 74],
  'minecraft:campfire': [200, 150, 90],
  'minecraft:oak_leaves': [64, 132, 52],
};

const colourOf = (state) => COLORS[parseState(state).Name] || [200, 40, 200]; // magenta = unmapped block

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
  return Buffer.concat([len, t, data, crc]);
}

function encodePng(width, height, rgb) {
  const stride = width * 3;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    rgb.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

/** 2:1 isometric voxel render. Painter order: ascending x+y+z, which is farthest-first for this view. */
function renderIso(grid, size, { tile = 24, background = [232, 240, 248] } = {}) {
  const hw = tile / 2;          // half block width
  const hh = tile / 4;          // half of the top diamond's height
  const cubeH = tile / 2;       // vertical screen height of one block

  const blocks = [];
  for (const [key, state] of grid) {
    const [x, y, z] = key.split(',').map(Number);
    blocks.push({ x, y, z, state, depth: x + y + z });
  }
  blocks.sort((a, b) => a.depth - b.depth);

  const sxOf = (x, z) => (x - z) * hw;
  const syOf = (x, y, z) => (x + z) * hh - (y + 1) * cubeH + cubeH;

  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const bl of blocks) {
    const px = sxOf(bl.x, bl.z);
    const py = syOf(bl.x, bl.y, bl.z);
    minX = Math.min(minX, px - hw);
    maxX = Math.max(maxX, px + hw);
    minY = Math.min(minY, py - hh);
    maxY = Math.max(maxY, py + hh + cubeH);
  }
  const margin = 6;
  const width = Math.ceil(maxX - minX) + margin * 2;
  const height = Math.ceil(maxY - minY) + margin * 2;
  const ox = -minX + margin;
  const oy = -minY + margin;

  const rgb = Buffer.alloc(width * height * 3);
  for (let i = 0; i < width * height; i++) {
    rgb[i * 3] = background[0];
    rgb[i * 3 + 1] = background[1];
    rgb[i * 3 + 2] = background[2];
  }

  const put = (px, py, c, shade) => {
    if (px < 0 || py < 0 || px >= width || py >= height) return;
    const i = (py * width + px) * 3;
    rgb[i] = Math.min(255, Math.round(c[0] * shade));
    rgb[i + 1] = Math.min(255, Math.round(c[1] * shade));
    rgb[i + 2] = Math.min(255, Math.round(c[2] * shade));
  };

  // Convex polygon fill by scanline, in screen space.
  const fillPoly = (pts, c, shade) => {
    let y0 = Infinity, y1 = -Infinity;
    for (const [, y] of pts) { y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
    for (let y = Math.floor(y0); y <= Math.ceil(y1); y++) {
      const xs = [];
      for (let i = 0; i < pts.length; i++) {
        const [ax, ay] = pts[i];
        const [bx, by] = pts[(i + 1) % pts.length];
        if ((ay <= y && by > y) || (by <= y && ay > y)) xs.push(ax + ((y - ay) / (by - ay)) * (bx - ax));
      }
      xs.sort((a, b) => a - b);
      for (let k = 0; k + 1 < xs.length; k += 2) {
        for (let x = Math.ceil(xs[k]); x <= Math.floor(xs[k + 1]); x++) put(x, y, c, shade);
      }
    }
  };

  for (const bl of blocks) {
    const c = colourOf(bl.state);
    const px = sxOf(bl.x, bl.z) + ox;
    const py = syOf(bl.x, bl.y, bl.z) + oy;
    // Top face.
    fillPoly([[px, py - hh], [px + hw, py], [px, py + hh], [px - hw, py]], c, 1.0);
    // Left face (+Z side) and right face (+X side).
    fillPoly([[px - hw, py], [px, py + hh], [px, py + hh + cubeH], [px - hw, py + cubeH]], c, 0.60);
    fillPoly([[px + hw, py], [px, py + hh], [px, py + hh + cubeH], [px + hw, py + cubeH]], c, 0.78);
  }

  return { png: encodePng(width, height, rgb), width, height };
}

/* ------------------------------------------------------------------ main */

function main() {
  const install = process.argv.includes('--install');

  const b = new Build(SIZE);
  buildCottage(b);

  const bad = checkBlockNames([...b.grid.values()]);
  if (bad.length) {
    console.error('Unknown block names - refusing to write a broken schematic:');
    for (const x of bad) console.error('  ' + x);
    return 1;
  }

  fs.mkdirSync(OUT, { recursive: true });
  const target = path.join(OUT, 'cottage.litematic');
  const info = writeLitematic(target, {
    name: 'cottage',
    author: 'StartBuild',
    description: 'Recreation of the reference cottage render: hip roof, cobble base, brick chimney, fenced crop plot.',
    size: SIZE,
    grid: b.grid,
  });

  console.log('Wrote ' + target);
  console.log(`  palette ${info.palette} (${info.bits} bits), ${info.longs} longs, ` +
    `${info.totalBlocks} blocks in ${info.volume} cells`);
  console.log(`  bounding: ${SIZE.x} x ${SIZE.y} x ${SIZE.z}`);

  // --- verify by reading the file back and comparing every cell ---------
  const back = readLitematic(target);
  const region = back.regions[0];
  const paletteState = region.palette.map((e) => {
    if (!e.Properties) return e.Name;
    const props = Object.keys(e.Properties).sort().map((k) => `${k}=${e.Properties[k]}`);
    return `${e.Name}[${props.join(',')}]`;
  });
  const rebuilt = new Map();
  for (let i = 0; i < region.volume; i++) {
    const state = paletteState[region.indices[i]];
    if (!state || state === 'minecraft:air') continue;
    const y = Math.floor(i / (SIZE.x * SIZE.z));
    const rem = i % (SIZE.x * SIZE.z);
    const z = Math.floor(rem / SIZE.x);
    const x = rem % SIZE.x;
    rebuilt.set(`${x},${y},${z}`, state);
  }
  let mismatches = 0;
  for (const [k, v] of b.grid) if (rebuilt.get(k) !== v) mismatches++;
  const expectedCount = [...rebuilt.values()].filter((s) => s !== 'minecraft:air').length;
  if (mismatches || expectedCount !== b.grid.size) {
    console.error(`ROUND TRIP FAILED: ${mismatches} mismatched cell(s), ${expectedCount} vs ${b.grid.size} blocks`);
    return 1;
  }
  console.log(`  round trip: all ${b.grid.size} blocks match`);

  // --- preview ---------------------------------------------------------
  const preview = renderIso(b.grid, SIZE);
  const previewPath = path.join(OUT, 'cottage-preview.png');
  fs.writeFileSync(previewPath, preview.png);
  console.log(`  preview: ${previewPath} (${preview.width}x${preview.height})`);

  // --- material list, because the build has to be stocked before Baritone can place it --------
  const counts = new Map();
  for (const state of b.grid.values()) {
    const { Name } = parseState(state);
    counts.set(Name, (counts.get(Name) || 0) + 1);
  }
  const materials = [...counts.entries()].sort((a, z) => z[1] - a[1]);
  const materialsPath = path.join(OUT, 'cottage-materials.txt');
  fs.writeFileSync(materialsPath, materials.map(([n, c]) => `${String(c).padStart(6)}  ${n}`).join('\n') + '\n');
  console.log(`  materials: ${counts.size} block types, ${b.grid.size} blocks -> ${materialsPath}`);
  for (const [n, c] of materials.slice(0, 8)) console.log(`      ${String(c).padStart(5)}  ${n}`);

  if (install) {
    const dest = path.join(
      process.env.APPDATA,
      'PrismLauncher/instances/BuildRecording/minecraft/schematics/cottage.litematic',
    );
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(target, dest);
    console.log('  installed: ' + dest);
  }
  return 0;
}

process.exit(main());
