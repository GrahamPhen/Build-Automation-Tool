// "I Built a House Without Moving the Stream": a timber house standing on two stone piers, one on each bank,
// spanning an 11-block stream. The stream flows along Z under x 7..17; the front (door, steps, the big
// window over the water) faces south (+Z).
//   node tools/cottage/scenes/stream_house.mjs <out.litematic>
import { writeLitematic } from '../litematic.mjs';

const grid = new Map();
const set = (x, y, z, s) => grid.set(`${x},${y},${z}`, s.includes(':') ? s : 'minecraft:' + s);
const hash = (x, y, z) => ((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) >>> 0;

const X = 25, Y = 16, Z = 14;
const PIERS = [[1, 6], [18, 23]];             // x ranges of the two piers (inclusive)
const GAP = [7, 17];                          // over the water
const D0 = 1, D1 = 9;                         // depth of the house (z)
const FLOOR = 3, WALL0 = 4, WALL1 = 8;

// Piers: cobble footing, stone bricks with some moss, three high, the deck on top.
for (const [a, b] of PIERS) for (let x = a; x <= b; x++) for (let z = D0; z <= D1; z++) for (let y = 0; y < FLOOR; y++) {
  const h = hash(x, y, z) % 10;
  set(x, y, z, y === 0 ? (h < 4 ? 'mossy_cobblestone' : 'cobblestone') : h < 3 ? 'mossy_stone_bricks' : h < 4 ? 'cracked_stone_bricks' : 'stone_bricks');
}
// Arch under the deck over the water: corbels and top slabs stepping in from each pier.
for (let z = D0; z <= D1; z++) {
  set(GAP[0], FLOOR - 1, z, 'stone_brick_stairs[facing=east,half=top]');
  set(GAP[1], FLOOR - 1, z, 'stone_brick_stairs[facing=west,half=top]');
  set(GAP[0] + 1, FLOOR - 1, z, 'stone_brick_slab[type=top]');
  set(GAP[1] - 1, FLOOR - 1, z, 'stone_brick_slab[type=top]');
}
// Deck / floor across the whole span.
for (let x = 1; x <= 23; x++) for (let z = D0; z <= D1; z++) set(x, FLOOR, z, x === 1 || x === 23 || z === D0 || z === D1 ? 'stripped_dark_oak_log[axis=x]' : 'spruce_planks');
// Walls: dark oak posts, white calcite infill, a spruce sill row at the bottom.
const posts = new Set([1, 6, 12, 18, 23]);
const zPosts = new Set([D0, 5, D1]);
for (let y = WALL0; y <= WALL1; y++) {
  for (let x = 1; x <= 23; x++) for (const z of [D0, D1]) set(x, y, z, posts.has(x) ? 'dark_oak_log[axis=y]' : y === WALL0 ? 'spruce_planks' : y === WALL1 ? 'stripped_dark_oak_log[axis=x]' : 'calcite');
  for (let z = D0 + 1; z < D1; z++) for (const x of [1, 23]) set(x, y, z, zPosts.has(z) ? 'dark_oak_log[axis=y]' : y === WALL0 ? 'spruce_planks' : y === WALL1 ? 'stripped_dark_oak_log[axis=z]' : 'calcite');
}
// Windows: two-high panes in the side bays; the bedroom window over the stream runs the whole middle of
// the south (front) wall, x 8..16.
const pane = (x, y, z, ax) => set(x, y, z, `glass_pane[${ax === 'x' ? 'east=true,west=true' : 'north=true,south=true'}]`);
for (const z of [D0, D1]) for (const [a, b] of [[3, 4], [20, 21]]) for (let x = a; x <= b; x++) for (let y = 5; y <= 7; y++) pane(x, y, z, 'x');
for (let x = 8; x <= 16; x++) if (x !== 12) for (let y = 5; y <= 7; y++) pane(x, y, D1, 'x');
for (let x = 8; x <= 10; x++) for (let y = 5; y <= 7; y++) pane(x, y, D0, 'x');
for (let x = 14; x <= 16; x++) for (let y = 5; y <= 7; y++) pane(x, y, D0, 'x');
for (const x of [1, 23]) for (const z of [3, 7]) for (let y = 5; y <= 7; y++) pane(x, y, z, 'z');
// Shutters beside the front side windows.
for (const [x, f] of [[2, 'west'], [5, 'east'], [19, 'west'], [22, 'east']]) for (let y = 5; y <= 7; y++)
  set(x, y, D1 + 1, `spruce_trapdoor[facing=south,half=bottom,open=true]`);
// Flower boxes under the big window.
for (let x = 8; x <= 16; x++) if (x !== 12) {
  set(x, WALL0, D1 + 1, 'spruce_trapdoor[facing=south,half=top,open=false]');
}
// Front door (south wall, west pier) with a landing and steps down to the bank.
set(3, WALL0, D1, 'spruce_door[facing=north,half=lower,hinge=right,open=false]');
set(3, WALL0 + 1, D1, 'spruce_door[facing=north,half=upper,hinge=right,open=false]');
for (const x of [2, 3, 4]) {
  set(x, FLOOR, D1 + 1, 'spruce_planks');
  set(x, FLOOR - 1, D1 + 2, 'stone_brick_stairs[facing=north,half=bottom]');
  set(x, FLOOR - 2, D1 + 3, 'stone_brick_stairs[facing=north,half=bottom]');
  set(x, FLOOR - 3, D1 + 4, 'stone_brick_stairs[facing=north,half=bottom]');
  set(x, FLOOR - 2, D1 + 2, 'stone_bricks'); set(x, FLOOR - 3, D1 + 2, 'stone_bricks'); set(x, FLOOR - 3, D1 + 3, 'stone_bricks');
}
set(2, WALL0 + 1, D1 + 1, 'lantern[hanging=false]');   // lanterns either side of the door, on the landing fence posts
set(4, WALL0 + 1, D1 + 1, 'lantern[hanging=false]');
set(2, WALL0, D1 + 1, 'spruce_fence'); set(4, WALL0, D1 + 1, 'spruce_fence');
// Roof: dark oak stairs gable along X, one block of overhang all round, ridge at z=5.
for (let x = 0; x <= 24; x++) {
  for (let k = 0; k <= 4; k++) {
    set(x, 9 + k, D0 - 1 + k, 'dark_oak_stairs[facing=south,half=bottom]');
    set(x, 9 + k, D1 + 1 - k, 'dark_oak_stairs[facing=north,half=bottom]');
  }
  set(x, 13, 5, 'dark_oak_planks');
  set(x, 14, 5, 'dark_oak_slab[type=bottom]');
}
// Gable ends under the roof: calcite with a little round window.
for (const x of [1, 23]) for (let k = 0; k <= 3; k++) for (let z = D0 + 1 + k; z <= D1 - 1 - k; z++) set(x, 9 + k, z, 'calcite');
for (const x of [1, 23]) set(x, 10, 5, 'glass_pane[north=true,south=true]');
// Chimney on the east pier side, through the roof.
for (let y = WALL0; y <= 15; y++) set(21, y, 3, y > 13 ? 'bricks' : 'stone_bricks');

// Inside: a bed under the stream window, a table and a bookshelf.
set(13, WALL0, 7, 'red_bed[facing=south,part=head]'); set(13, WALL0, 6, 'red_bed[facing=south,part=foot]');
set(10, WALL0, 4, 'spruce_fence'); set(10, WALL0 + 1, 4, 'spruce_pressure_plate');
for (let z = 2; z <= 4; z++) set(22, WALL0, z, 'bookshelf');
// (no lantern hanging inside: nothing above it to hang from)
// Hanging lanterns under the deck, over the water.
for (const z of [3, 7]) set(12, FLOOR - 1, z, 'lantern[hanging=true]');
// Moss and a few plants on the piers' outer faces.
for (const [x, z] of [[1, D0], [6, D1], [18, D1], [23, D0]]) set(x, 0, z, 'mossy_cobblestone');

const out = process.argv[2] || 'stream_house.litematic';
writeLitematic(out, { name: 'Stream House', author: 'StartBuild', description: 'House spanning a stream on two stone piers; front faces south.', size: { x: X, y: Y, z: Z }, grid });
console.log(`wrote ${out}: ${grid.size} blocks, ${X}x${Y}x${Z}`);
