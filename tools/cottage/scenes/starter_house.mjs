// "Your Starter House Doesn't Need Rebuilding": the same 9x7 starter house twice - plain, then upgraded with
// a handful of changes (stone base, log corners, pitched roof with overhang, framed windows + shutters, flower
// boxes, a porch with lanterns, a chimney). Both share the footprint and origin, so the upgrade is built over
// the plain one on the same spot and the video can match-cut before/after. Front (door) faces south (+Z).
//   node tools/cottage/scenes/starter_house.mjs <outDir>
import path from 'node:path';
import { writeLitematic } from '../litematic.mjs';

const W = 13, D = 11;                         // box with room for overhang and porch; house x 2..10, z 1..7
const X0 = 2, X1 = 10, Z0 = 1, Z1 = 7;
const mk = () => { const g = new Map(); g.set = ((s) => (x, y, z, b) => s.call(g, `${x},${y},${z}`, b.includes(':') ? b : 'minecraft:' + b))(g.set); return g; };

// ---------- plain: oak plank box, flat roof, two windows, a door
function plain() {
  const g = mk();
  for (let x = X0; x <= X1; x++) for (let z = Z0; z <= Z1; z++) g.set(x, 0, z, 'oak_planks');            // floor
  for (let y = 1; y <= 3; y++) for (let x = X0; x <= X1; x++) for (let z = Z0; z <= Z1; z++) {
    if (x !== X0 && x !== X1 && z !== Z0 && z !== Z1) continue;
    g.set(x, y, z, 'oak_planks');
  }
  for (let x = X0; x <= X1; x++) for (let z = Z0; z <= Z1; z++) g.set(x, 4, z, 'oak_planks');            // flat roof
  for (const x of [4, 8]) g.set(x, 2, Z1, 'glass_pane[east=true,west=true]');
  g.set(6, 1, Z1, 'oak_door[facing=north,half=lower,hinge=left,open=false]');
  g.set(6, 2, Z1, 'oak_door[facing=north,half=upper,hinge=left,open=false]');
  g.set(6, 2, Z0, 'glass_pane[east=true,west=true]');
  g.set(4, 1, 3, 'crafting_table'); g.set(8, 1, 3, 'furnace[facing=south]'); g.set(5, 1, 2, 'red_bed[facing=east,part=foot]'); g.set(6, 1, 2, 'red_bed[facing=east,part=head]');
  return g;
}

// ---------- upgraded: same footprint and interior, the outside reworked
function upgraded() {
  const g = mk();
  for (let x = X0; x <= X1; x++) for (let z = Z0; z <= Z1; z++) g.set(x, 0, z, (x + z) % 2 ? 'spruce_planks' : 'oak_planks');
  for (let y = 1; y <= 3; y++) for (let x = X0; x <= X1; x++) for (let z = Z0; z <= Z1; z++) {
    if (x !== X0 && x !== X1 && z !== Z0 && z !== Z1) continue;
    const corner = (x === X0 || x === X1) && (z === Z0 || z === Z1);
    g.set(x, y, z, corner ? 'spruce_log[axis=y]' : y === 1 ? 'cobblestone' : 'oak_planks');            // stone base, log corners
  }
  // beam under the roof
  for (let x = X0; x <= X1; x++) for (const z of [Z0, Z1]) g.set(x, 4, z, 'stripped_spruce_log[axis=x]');
  for (let z = Z0 + 1; z < Z1; z++) for (const x of [X0, X1]) g.set(x, 4, z, 'stripped_spruce_log[axis=z]');
  // pitched roof along X with a one-block overhang (z 0 .. 8), ridge over z=4
  for (let x = X0 - 1; x <= X1 + 1; x++) {
    for (let k = 0; k <= 3; k++) {
      g.set(x, 5 + k, Z0 - 1 + k, 'spruce_stairs[facing=south,half=bottom]');
      g.set(x, 5 + k, Z1 + 1 - k, 'spruce_stairs[facing=north,half=bottom]');
    }
    g.set(x, 8, 4, 'spruce_slab[type=bottom]');
  }
  // gable ends
  for (const x of [X0, X1]) for (let k = 0; k <= 2; k++) for (let z = Z0 + 1 + k; z <= Z1 - 1 - k; z++) g.set(x, 5 + k, z, 'oak_planks');
  for (const x of [X0, X1]) g.set(x, 6, 4, 'glass_pane[north=true,south=true]');
  // bigger framed windows: two-high, with shutters and a flower box
  for (const x of [4, 8]) {
    for (let y = 2; y <= 3; y++) g.set(x, y, Z1, 'glass_pane[east=true,west=true]');
    for (let y = 2; y <= 3; y++) { g.set(x - 1, y, Z1 + 1, 'spruce_trapdoor[facing=south,half=bottom,open=true]'); g.set(x + 1, y, Z1 + 1, 'spruce_trapdoor[facing=south,half=bottom,open=true]'); }
    g.set(x, 1, Z1 + 1, 'spruce_trapdoor[facing=south,half=top,open=false]');
    g.set(x, 2, Z1 + 1, 'potted_red_tulip');
  }
  for (let y = 2; y <= 3; y++) g.set(6, y, Z0, 'glass_pane[east=true,west=true]');
  for (const z of [3, 5]) g.set(X0, 2, z, 'glass_pane[north=true,south=true]'), g.set(X1, 2, z, 'glass_pane[north=true,south=true]');
  // door with a small porch: steps, posts, lanterns
  g.set(6, 1, Z1, 'spruce_door[facing=north,half=lower,hinge=left,open=false]');
  g.set(6, 2, Z1, 'spruce_door[facing=north,half=upper,hinge=left,open=false]');
  for (const x of [5, 6, 7]) g.set(x, 0, Z1 + 1, 'spruce_planks'), g.set(x, 0, Z1 + 2, 'spruce_stairs[facing=north,half=bottom]');
  for (const x of [5, 7]) { g.set(x, 1, Z1 + 1, 'spruce_fence'); g.set(x, 2, Z1 + 1, 'spruce_fence'); g.set(x, 3, Z1 + 1, 'lantern[hanging=false]'); }
  // chimney
  for (let y = 1; y <= 9; y++) g.set(9, y, 2, y < 5 ? 'cobblestone' : y < 9 ? 'stone_bricks' : 'campfire[lit=true,facing=south,signal_fire=false,waterlogged=false]');
  // same interior + a carpet and a lantern
  g.set(4, 1, 3, 'crafting_table'); g.set(8, 1, 3, 'furnace[facing=south]'); g.set(5, 1, 2, 'red_bed[facing=east,part=foot]'); g.set(6, 1, 2, 'red_bed[facing=east,part=head]');
  g.set(6, 1, 5, 'red_carpet'); g.set(6, 3, 4, 'lantern[hanging=true]');
  return g;
}

const dir = process.argv[2] || '.';
for (const [name, grid, H] of [['starter_house_before', plain(), 5], ['starter_house_after', upgraded(), 10]]) {
  const file = path.join(dir, name + '.litematic');
  writeLitematic(file, { name, author: 'StartBuild', description: 'Starter house before/after - same footprint; front faces south.', size: { x: W, y: H, z: D }, grid });
  console.log(`wrote ${file}: ${grid.size} blocks`);
}
