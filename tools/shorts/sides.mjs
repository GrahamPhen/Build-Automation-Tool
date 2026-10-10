// Flat pictures of a build's four sides (south, east, north, west as seen from outside), coloured with each
// block's average texture colour, darker with depth - to see which side is the front without the game.
//   node tools/shorts/sides.mjs <out.bmp> <schematic.litematic>...   (one row per schematic)
import fs from 'node:fs';
import path from 'node:path';
import { readLitematic } from '../cottage/litematic.mjs';

const colors = JSON.parse(fs.readFileSync(new URL('./block-colors.json', import.meta.url)));
const cache = new Map();
function color(full) {
  if (cache.has(full)) return cache.get(full);
  let n = full.replace(/^minecraft:/, '');
  const tries = [n, n + '_side', n + '_front', n + '_top'];
  const base = n.replace(/_(stairs|slab|wall|fence_gate|fence|button|pressure_plate|wall_sign|sign|hanging_sign|wall_hanging_sign|carpet|pane)$/, '');
  tries.push(base, base + 's', base + '_planks', base + '_block', base.replace(/_wood$/, '_log'), base.replace(/brick$/, 'bricks'),
    base.replace(/tile$/, 'tiles'), base + '_glass', base.replace(/^waxed_/, ''), base.replace(/_glass$/, '_stained_glass'));
  if (/glass_pane$/.test(n)) tries.push(n.replace(/_pane$/, ''));
  let c = null;
  for (const t of tries) if (colors[t]) { c = colors[t]; break; }
  if (!c) c = /leaves/.test(n) ? [60, 110, 40] : /water/.test(n) ? [50, 80, 200] : [140, 140, 140];
  cache.set(full, c);
  return c;
}

function views(file) {
  const r = readLitematic(file).regions[0];
  const { x: sx, y: sy, z: sz } = r.size;
  const at = (x, y, z) => { const e = r.palette[r.indices[(y * sz + z) * sx + x]]; return e && !/(^|:)(air|cave_air)$/.test(e.Name) ? e.Name : null; };
  const sides = [
    ['south', sx, sz, (u, k) => [u, sz - 1 - k]],          // seen from +Z looking north: u = x
    ['east', sz, sx, (u, k) => [sx - 1 - k, sz - 1 - u]],   // from +X looking west: u runs north->south reversed
    ['north', sx, sz, (u, k) => [sx - 1 - u, k]],
    ['west', sz, sx, (u, k) => [k, u]],
  ];
  return sides.map(([label, w, depth, cell]) => {
    const img = new Array(w * sy).fill(null);
    for (let y = 0; y < sy; y++) for (let u = 0; u < w; u++) {
      for (let k = 0; k < depth; k++) {
        const [x, z] = cell(u, k);
        const n = at(x, y, z);
        if (!n) continue;
        const c = color(n), f = 1 - 0.55 * k / depth;
        img[(sy - 1 - y) * w + u] = c.map(v => Math.round(v * f));
        break;
      }
    }
    return { label, w, h: sy, img };
  });
}

const [out, ...files] = process.argv.slice(2);
const rows = files.map(f => ({ name: path.basename(f, '.litematic'), v: views(f) }));
// pixels per block: about 170 px tall per row, whatever the build's height (small builds were unreadable)
const S = Math.max(1, Math.min(8, Math.round(170 / Math.max(...rows.map(r => Math.max(...r.v.map(v => v.h))))))), GAP = 8;
const H = Math.max(...rows.map(r => Math.max(...r.v.map(v => v.h)))) * S;
const W = Math.max(...rows.map(r => r.v.reduce((a, v) => a + v.w * S + GAP, 0)));
const width = W, height = rows.length * (H + GAP);
const px = Buffer.alloc(width * height * 3, 230);
rows.forEach((row, ri) => {
  let ox = 0;
  for (const v of row.v) {
    for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
      const c = v.img[y * v.w + x] || [205, 225, 245];
      for (let dy = 0; dy < S; dy++) for (let dx = 0; dx < S; dx++) {
        const X = ox + x * S + dx, Y = ri * (H + GAP) + (H - v.h * S) + y * S + dy;
        const i = (Y * width + X) * 3; px[i] = c[2]; px[i + 1] = c[1]; px[i + 2] = c[0];
      }
    }
    ox += v.w * S + GAP;
  }
});
// 24-bit BMP, bottom-up rows padded to 4 bytes
const rowLen = Math.ceil(width * 3 / 4) * 4;
const bmp = Buffer.alloc(54 + rowLen * height);
bmp.write('BM'); bmp.writeUInt32LE(bmp.length, 2); bmp.writeUInt32LE(54, 10); bmp.writeUInt32LE(40, 14);
bmp.writeInt32LE(width, 18); bmp.writeInt32LE(height, 22); bmp.writeUInt16LE(1, 26); bmp.writeUInt16LE(24, 28);
bmp.writeUInt32LE(rowLen * height, 34);
for (let y = 0; y < height; y++) px.copy(bmp, 54 + (height - 1 - y) * rowLen, y * width * 3, (y + 1) * width * 3);
fs.writeFileSync(out, bmp);
console.log(rows.map(r => r.name).join(' | '), '-> rows of: south | east | north | west');
