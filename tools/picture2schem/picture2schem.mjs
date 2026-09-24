#!/usr/bin/env node
// picture2schem - turn a picture of a Minecraft build into a buildable .litematic.
//
//   node picture2schem.mjs <picture.png|jpg> [--out <dir>] [--name <name>] [--model <id>]
//   node picture2schem.mjs --spec <plan.json> [--out <dir>]        (no AI: build from a saved plan)
//
// Step 1 (one API call): Claude looks at the picture and writes a short JSON build plan (the SCHEMA below).
// Step 2 (local, free):   this script turns the plan into exact blocks and writes <name>.litematic,
//                         plus <name>.plan.json so the plan can be tweaked and rebuilt without the AI.
//
// API key: env ANTHROPIC_API_KEY, or a file named anthropic-key.txt next to this script.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeLitematic } from '../cottage/litematic.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_MODEL = 'claude-opus-5-5';
const DEFAULT_OUT = path.join(process.env.APPDATA || '.', 'PrismLauncher', 'instances', 'BuildRecording', 'minecraft', 'schematics');

// ------------------------------------------------------------------ the plan format (also the prompt)
const SCHEMA = `
Return ONE JSON object, no prose. Coordinates: x = left->right as seen in the picture, z = back->front
(front faces the viewer, +z), y = up. Block ids are Minecraft Java 1.21+ ids without "minecraft:".
{
 "name": "short_snake_case",
 "plot": {"width": int, "depth": int, "ground": "grass_block"},          // whole area incl. yard
 "house": {
   "x": int, "z": int, "width": int, "depth": int,                          // footprint position/size on the plot
   "wallHeight": int,                                                       // blocks of wall above ground
   "floor": "block",
   "foundation": {"block": "block", "height": int},                         // bottom rows of the walls
   "walls": "block",
   "corners": "block or null",                                              // posts, e.g. "spruce_log"
   "bands": [{"y": int, "block": "block"}],                                 // full horizontal rows (y from 1)
   "roof": {"type": "gable", "ridge": "x"|"z", "stairs": "spruce_stairs", "ridgeBlock": "spruce_slab",
            "overhang": int, "gableFill": "block"},
   "door": {"side": "front|back|left|right", "offset": int, "block": "oak_door"},   // offset along that wall from its left/back corner
   "windows": [{"side": "...", "offset": int, "y": int, "width": int, "height": int, "block": "glass_pane"}],
   "chimney": {"x": int, "z": int, "block": "cobblestone", "cap": "block", "aboveRoof": int} or null
 },
 "boxes": [{"x": int, "z": int, "length": int, "axis": "x"|"z", "block": "block", "flowers": ["potted_red_tulip", ...]}],
 "path":  {"block": "dirt_path", "cells": [[x,z], ...]} or null,
 "garden": {"x": int, "z": int, "width": int, "depth": int, "crops": ["wheat","carrots"], "fence": "oak_fence", "water": true} or null,
 "extras": [{"x": int, "y": int, "z": int, "block": "block[optional_props]"}]      // anything else (bushes, lanterns, posts)
}
Rules: keep it buildable and to real scale (a normal door is 2 high; typical cottages are 7-15 wide).
Infer hidden sides by symmetry with what is visible. Prefer the exact blocks you can see (stone band vs
walls, log posts, stair roof material, flower pots). Leaves in extras need "[persistent=true]".`;

// ------------------------------------------------------------------ helpers
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const id = (b) => (b.includes(':') ? b : 'minecraft:' + b);

async function planFromPicture(file, model) {
  const key = process.env.ANTHROPIC_API_KEY
    || (fs.existsSync(path.join(HERE, 'anthropic-key.txt')) ? fs.readFileSync(path.join(HERE, 'anthropic-key.txt'), 'utf8').trim() : '');
  if (!key) throw new Error('No API key: set ANTHROPIC_API_KEY or create anthropic-key.txt next to this script.');
  const ext = path.extname(file).toLowerCase();
  const media = ext === '.png' ? 'image/png' : ext === '.webp' ? 'image/webp' : 'image/jpeg';
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
    body: JSON.stringify({
      model, max_tokens: 4000,
      system: 'You convert pictures of Minecraft builds into precise, buildable block plans.' + SCHEMA,
      messages: [{ role: 'user', content: [
        { type: 'image', source: { type: 'base64', media_type: media, data: fs.readFileSync(file).toString('base64') } },
        { type: 'text', text: 'Write the build plan for this picture. Count blocks carefully (wall widths, roof steps, window sizes).' },
      ] }],
    }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(`API ${res.status}: ${JSON.stringify(body.error || body)}`);
  const text = body.content.map((c) => c.text || '').join('');
  const json = text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1);
  console.log(`plan from ${model}: ${body.usage?.input_tokens} in / ${body.usage?.output_tokens} out tokens`);
  return JSON.parse(json);
}

// ------------------------------------------------------------------ plan -> blocks
function build(plan) {
  const g = new Map();
  const set = (x, y, z, b) => { if (b) g.set(`${x},${y},${z}`, id(b)); };
  const get = (x, y, z) => g.get(`${x},${y},${z}`);
  const P = plan.plot, H = plan.house, R = H.roof;
  const ov = R?.overhang ?? 1;
  // Shift everything so the roof overhang never goes below 0.
  const pad = Math.max(0, ov - H.x, ov - H.z);
  const W = P.width + 2 * pad, D = P.depth + 2 * pad;
  const hx = H.x + pad, hz = H.z + pad;

  for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) set(x, 0, z, P.ground || 'grass_block');

  // house shell
  const top = H.wallHeight;
  for (let x = 0; x < H.width; x++) for (let z = 0; z < H.depth; z++) {
    const edge = x === 0 || z === 0 || x === H.width - 1 || z === H.depth - 1;
    set(hx + x, 0, hz + z, H.floor || H.walls);
    if (!edge) continue;
    const corner = (x === 0 || x === H.width - 1) && (z === 0 || z === H.depth - 1);
    for (let y = 1; y <= top; y++) {
      let b = H.walls;
      if (y <= (H.foundation?.height ?? 0)) b = H.foundation.block;
      for (const band of H.bands || []) if (band.y === y) b = band.block;
      if (corner && H.corners) b = H.corners.includes('log') || H.corners.includes('wood') ? H.corners + '[axis=y]' : H.corners;
      set(hx + x, y, hz + z, b);
    }
  }
  // wall-local -> plot coords. offset runs left->right seen from outside (front), back->front on sides.
  const wallCell = (side, off) => ({
    front: [hx + off, hz + H.depth - 1], back: [hx + off, hz],
    left: [hx, hz + off], right: [hx + H.width - 1, hz + off],
  }[side]);
  const facing = { front: 'south', back: 'north', left: 'west', right: 'east' };
  for (const w of H.windows || []) {
    for (let i = 0; i < (w.width || 1); i++) for (let j = 0; j < (w.height || 1); j++) {
      const [x, z] = wallCell(w.side, w.offset + i);
      set(x, w.y + j, z, w.block || 'glass_pane');
    }
  }
  if (H.door) {
    const [x, z] = wallCell(H.door.side, H.door.offset);
    const f = facing[H.door.side];
    set(x, 1, z, `${H.door.block}[facing=${f},half=lower,hinge=left,open=false]`);
    set(x, 2, z, `${H.door.block}[facing=${f},half=upper,hinge=left,open=false]`);
  }

  // gable roof
  let roofTop = top;
  if (R) {
    const alongX = R.ridge !== 'z';
    const span = alongX ? H.depth : H.width;       // across the slope
    const len = alongX ? H.width : H.depth;        // along the ridge
    const lo = -ov, hi = span - 1 + ov;
    // Low side (a) rises towards the ridge in +; stairs face the direction they rise to.
    const up = alongX ? 'south' : 'east', down = alongX ? 'north' : 'west';
    for (let k = 0; ; k++) {
      const a = lo + k, b = hi - k, y = top + 1 + k;
      if (a > b) break;
      roofTop = y;
      for (let t = -ov; t < len + ov; t++) {
        const put = (s, st) => set(alongX ? hx + t : hx + s, y, alongX ? hz + s : hz + t, st);
        if (a === b) { put(a, R.ridgeBlock || R.stairs.replace('stairs', 'slab')); continue; }
        put(a, `${R.stairs}[facing=${up},half=bottom,shape=straight]`);
        put(b, `${R.stairs}[facing=${down},half=bottom,shape=straight]`);
      }
      // gable ends: fill the triangle under this layer
      for (let s = Math.max(0, a + 1); s <= Math.min(span - 1, b - 1); s++) {
        for (const t of [0, len - 1]) {
          const px = alongX ? hx + t : hx + s, pz = alongX ? hz + s : hz + t;
          if (!get(px, y, pz)) set(px, y, pz, R.gableFill || H.walls);
        }
      }
    }
  }

  // chimney
  if (H.chimney) {
    const c = H.chimney, cx = c.x + pad, cz = c.z + pad;
    const h = roofTop + (c.aboveRoof ?? 2);
    for (let y = 1; y <= h; y++) set(cx, y, cz, c.block);
    set(cx, h + 1, cz, c.cap || c.block);
  }
  // flower boxes
  for (const bx of plan.boxes || []) {
    for (let i = 0; i < bx.length; i++) {
      const x = bx.x + pad + (bx.axis === 'z' ? 0 : i), z = bx.z + pad + (bx.axis === 'z' ? i : 0);
      set(x, 1, z, bx.block);
      const fl = bx.flowers?.[i % Math.max(1, bx.flowers.length)];
      if (fl) set(x, 2, z, fl);
    }
  }
  // path
  for (const [x, z] of plan.path?.cells || []) set(x + pad, 0, z + pad, plan.path.block || 'dirt_path');
  // garden
  const G = plan.garden;
  if (G) {
    const crops = G.crops?.length ? G.crops : ['wheat'];
    const ages = { wheat: 7, carrots: 7, potatoes: 7, beetroots: 3 };
    for (let x = 0; x < G.width; x++) for (let z = 0; z < G.depth; z++) {
      const px = G.x + pad + x, pz = G.z + pad + z;
      const edge = x === 0 || z === 0 || x === G.width - 1 || z === G.depth - 1;
      if (edge && G.fence) { set(px, 1, pz, G.fence); continue; }
      const mid = x === Math.floor(G.width / 2) && z === Math.floor(G.depth / 2);
      if (G.water && mid) { set(px, 0, pz, 'water'); continue; }
      const c = crops[Math.floor((x - 1) / Math.max(1, Math.floor((G.width - 2) / crops.length))) % crops.length];
      set(px, 0, pz, 'farmland[moisture=7]');
      set(px, 1, pz, `${c}[age=${ages[c] ?? 7}]`);
    }
  }
  for (const e of plan.extras || []) set(e.x + pad, e.y, e.z + pad, e.block);

  let maxY = 0;
  for (const k of g.keys()) maxY = Math.max(maxY, Number(k.split(',')[1]));
  return { grid: g, size: { x: W, y: maxY + 1, z: D } };
}

// ------------------------------------------------------------------ main
const specFile = opt('--spec');
const picture = args.find((a, i) => !a.startsWith('--') && (i === 0 || !args[i - 1].startsWith('--')));
const outDir = opt('--out', DEFAULT_OUT);
let plan;
if (specFile) plan = JSON.parse(fs.readFileSync(specFile, 'utf8'));
else if (picture) plan = await planFromPicture(picture, opt('--model', DEFAULT_MODEL));
else { console.log('usage: node picture2schem.mjs <picture> [--out dir] [--name n] [--model id] | --spec plan.json'); process.exit(1); }

const name = (opt('--name') || plan.name || path.parse(picture || specFile).name).replace(/[^\w-]/g, '_');
fs.mkdirSync(outDir, { recursive: true });
if (!specFile) fs.writeFileSync(path.join(outDir, name + '.plan.json'), JSON.stringify(plan, null, 1));
const { grid, size } = build(plan);
const r = writeLitematic(path.join(outDir, name + '.litematic'), { name, author: 'picture2schem', size, grid });
console.log(`wrote ${path.join(outDir, name + '.litematic')}: ${size.x}x${size.y}x${size.z}, ${r.totalBlocks} blocks`);
console.log(`in game: /startbuild place ${name}`);
