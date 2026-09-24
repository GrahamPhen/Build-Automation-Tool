// Spawn plaza from a picture: manor + tower (back), market stalls (left), lectern platform (centre),
// dragon shrine (centre-back), nether portal in a copper arch (right), planters, lantern posts, paths.
import { writeLitematic } from '../../cottage/litematic.mjs';
const g = new Map(), W = 52, D = 46;
const id = (b) => (b.includes(':') ? b : 'minecraft:' + b);
const set = (x, y, z, b) => g.set(`${x},${y},${z}`, id(b));
const box = (x0, y0, z0, x1, y1, z1, b) => { for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) set(x, y, z, b); };
const pave = ['cobblestone', 'stone_bricks', 'andesite', 'cobblestone', 'polished_andesite', 'stone'];
let seed = 7; const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);

// ground: grass behind, paved plaza in front
for (let x = 0; x < W; x++) for (let z = 0; z < D; z++) set(x, 0, z, z < 12 ? 'grass_block' : pave[Math.floor(rnd() * pave.length)]);
// wooden path strips
for (let x = 0; x < W; x++) { set(x, 0, 27, 'spruce_planks'); set(x, 0, 41, 'spruce_planks'); }
for (let z = 12; z < D; z++) { set(17, 0, z, 'spruce_planks'); set(31, 0, z, 'spruce_planks'); }

// ---- manor (back)
const mx0 = 1, mx1 = 36, mz0 = 1, mz1 = 9, mh = 11;
box(mx0, 1, mz0, mx1, mh, mz1, 'stone_bricks'); box(mx0 + 1, 1, mz0 + 1, mx1 - 1, mh, mz1 - 1, 'air');
for (const y of [1, 5, 9]) box(mx0, y, mz0, mx1, y, mz1, y === 1 ? 'cobblestone' : 'spruce_planks'); // floors/bands
for (let x = mx0 + 2; x < mx1 - 1; x += 3) for (const y of [2, 6]) { set(x, y, mz1, 'glass_pane'); set(x, y + 1, mz1, 'glass_pane'); }
for (let x = mx0 + 1; x < mx1; x++) set(x, 6, mz1 + 1, 'spruce_fence');           // balcony rail
box(mx0 + 1, 5, mz1 + 1, mx1 - 1, 5, mz1 + 1, 'spruce_slab[type=top]');
box(17, 1, mz1, 18, 3, mz1, 'air'); set(17, 1, mz1, 'spruce_door[facing=south,half=lower,hinge=left]'); set(17, 2, mz1, 'spruce_door[facing=south,half=upper,hinge=left]');
for (let k = 0; k <= 5; k++) for (let x = mx0 - 1; x <= mx1 + 1; x++) {           // roof, ridge along x
  set(x, mh + 1 + k, mz0 - 1 + k, 'spruce_stairs[facing=south]'); set(x, mh + 1 + k, mz1 + 1 - k, 'spruce_stairs[facing=north]');
}
for (let x = mx0 - 1; x <= mx1 + 1; x++) set(x, mh + 6, 5, 'spruce_slab');
for (const x of [mx0, mx1]) for (let k = 0; k < 5; k++) box(x, mh + 1 + k, mz0 + k, x, mh + 1 + k, mz1 - k, 'stone_bricks');
// tower
const tx0 = 24, tx1 = 30, tz0 = 2, tz1 = 8, th = 22;
box(tx0, 1, tz0, tx1, th, tz1, 'stone_bricks'); box(tx0 + 1, 2, tz0 + 1, tx1 - 1, th, tz1 - 1, 'air');
for (let y = 12; y < th; y += 4) { set(27, y, tz1, 'glass_pane'); set(27, y + 1, tz1, 'glass_pane'); set(tx0, y, 5, 'glass_pane'); set(tx1, y, 5, 'glass_pane'); }
for (let k = 0; k < 4; k++) for (let x = tx0 - 1 + k; x <= tx1 + 1 - k; x++) for (let z = tz0 - 1 + k; z <= tz1 + 1 - k; z++) {
  const edge = x === tx0 - 1 + k || x === tx1 + 1 - k || z === tz0 - 1 + k || z === tz1 + 1 - k;
  if (edge) set(x, th + 1 + k, z, 'stone_brick_slab');
}
box(27, th + 5, 5, 27, th + 8, 5, 'spruce_fence'); box(28, th + 7, 5, 29, th + 8, 5, 'yellow_wool'); set(28, th + 8, 5, 'black_wool');

// ---- market stalls (left)
const stripes = [['lime_wool', 'white_wool'], ['red_wool', 'white_wool'], ['yellow_wool', 'white_wool'], ['lime_wool', 'yellow_wool']];
[[2, 14], [2, 20], [9, 14], [9, 20]].forEach(([x0, z0], i) => {
  for (const [dx, dz] of [[0, 0], [4, 0], [0, 4], [4, 4]]) box(x0 + dx, 1, z0 + dz, x0 + dx, 3, z0 + dz, 'spruce_log');
  for (let dx = 0; dx <= 4; dx++) for (let dz = 0; dz <= 4; dz++) set(x0 + dx, 4, z0 + dz, stripes[i][dx % 2]);
  box(x0 + 1, 1, z0 + 4, x0 + 3, 1, z0 + 4, 'barrel[facing=up]'); set(x0 + 2, 2, z0 + 4, ['pumpkin', 'melon', 'hay_block', 'honeycomb_block'][i]);
  set(x0 + 1, 1, z0 + 1, 'crafting_table');
});

// ---- dragon shrine (centre back)
const sx0 = 20, sx1 = 30, sz0 = 13, sz1 = 21;
box(sx0, 0, sz0, sx1, 0, sz1, 'polished_andesite');
for (const x of [sx0, sx1]) box(x, 1, sz0, x, 5, sz1, 'stone_bricks');
box(sx0, 1, sz0, sx1, 5, sz0, 'stone_bricks'); box(sx0 + 1, 1, sz0 + 1, sx1 - 1, 1, sz0 + 1, 'moss_block');
for (const x of [sx0, sx0 + 3, sx1 - 3, sx1]) box(x, 1, sz1, x, 5, sz1, 'deepslate_bricks');
for (let k = 0; k <= 5; k++) for (let z = sz0 - 1; z <= sz1 + 1; z++) {
  set(sx0 - 1 + k, 6 + k, z, 'stone_brick_stairs[facing=east]'); set(sx1 + 1 - k, 6 + k, z, 'stone_brick_stairs[facing=west]');
}
for (let k = 0; k < 5; k++) box(sx0 + k, 6 + k, sz1, sx1 - k, 6 + k, sz1, 'spruce_planks');
set(25, 3, sz0 + 1, 'dragon_head[rotation=0]'); set(25, 1, sz0 + 2, 'chest[facing=south]');
box(sx0 + 1, 0, sz1 + 1, sx1 - 1, 0, sz1 + 1, 'stone_brick_slab');

// ---- nether portal in a copper arch (right)
const px = 40, pz = 17;
box(px, 1, pz, px + 3, 5, pz, 'obsidian'); box(px + 1, 2, pz, px + 2, 4, pz, 'nether_portal[axis=x]');
for (const x of [px - 2, px + 5]) box(x, 1, pz, x, 7, pz, 'waxed_cut_copper');
box(px - 2, 7, pz, px + 5, 7, pz, 'waxed_cut_copper'); box(px - 1, 8, pz, px + 4, 8, pz, 'waxed_cut_copper');
set(px - 1, 6, pz, 'waxed_cut_copper'); set(px + 4, 6, pz, 'waxed_cut_copper');
box(px - 2, 0, pz + 1, px + 5, 0, pz + 2, 'waxed_cut_copper'); set(px - 3, 1, pz, 'oxidized_copper'); set(px + 6, 1, pz, 'oxidized_copper');

// ---- central lectern platform
const cx = 24, cz = 34;
for (let dx = -4; dx <= 4; dx++) for (let dz = -4; dz <= 4; dz++) {
  const r = Math.max(Math.abs(dx), Math.abs(dz));
  if (r === 4) set(cx + dx, 1, cz + dz, 'spruce_slab'); else set(cx + dx, 1, cz + dz, 'spruce_planks');
  if (r <= 2) set(cx + dx, 2, cz + dz, r === 2 ? 'spruce_slab' : 'stone_bricks');
}
set(cx, 3, cz, 'lectern[facing=south]');
for (const [dx, dz] of [[-3, -3], [3, -3], [-3, 3], [3, 3]]) { box(cx + dx, 2, cz + dz, cx + dx, 3, cz + dz, 'polished_andesite'); set(cx + dx, 4, cz + dz, 'lantern'); }

// ---- planters, lantern posts, fences along the paths
const planter = (x, z, tree) => { set(x, 1, z, 'moss_block'); set(x, 2, z, tree ? 'flowering_azalea' : 'azalea'); };
const post = (x, z) => { set(x, 1, z, 'polished_andesite'); set(x, 2, z, 'lantern'); };
for (let x = 2; x < W - 2; x += 1) {
  if (x >= 16 && x <= 32) continue;
  const m = x % 6;
  for (const z of [26, 40]) { if (m === 0) post(x, z); else if (m === 3) planter(x, z, x % 12 === 3); else set(x, 1, z, 'spruce_fence'); }
}
for (let z = 24; z < D - 1; z += 1) for (const x of [16, 32]) {
  const m = z % 5; if (m === 0) post(x, z); else if (m === 2) planter(x, z, z % 10 === 2); else if (z !== 27 && z !== 41) set(x, 1, z, 'spruce_fence');
}
for (let x = sx0; x <= sx1; x += 2) set(x, 1, 23, x % 4 === 0 ? 'lantern' : 'azalea'); box(sx0, 0, 23, sx1, 0, 23, 'moss_block');

// trees behind
for (const [x, z] of [[40, 4], [46, 8], [44, 2], [49, 5]]) {
  box(x, 1, z, x, 7, z, 'spruce_log[axis=y]');
  for (let y = 4; y <= 9; y++) { const r = y < 7 ? 2 : 1; for (let dx = -r; dx <= r; dx++) for (let dz = -r; dz <= r; dz++) if (dx || dz || y > 7) set(x + dx, y, z + dz, 'spruce_leaves[persistent=true]'); }
}

const out = process.argv[2] || '/tmp/p2s/spawn_plaza.litematic';
let maxY = 0; for (const k of g.keys()) maxY = Math.max(maxY, +k.split(',')[1]);
for (const [k, v] of g) if (v === 'minecraft:air') g.delete(k);
const r = writeLitematic(out, { name: 'spawn_plaza', author: 'picture2schem', size: { x: W, y: maxY + 1, z: D }, grid: g });
console.log(`wrote ${out}: ${W}x${maxY + 1}x${D}, ${r.totalBlocks} blocks`);
