// The test course: small schematics, each built around one situation that has broken a real take, so a
// new StartBuild version can be checked in minutes instead of discovering it 3 hours into a long take.
//
//   tc_garden   farmland + a water source in its middle + a dirt path (petals/grass on the dirt blocked the
//               hoe; water poured early flooded the empty farmland - storybook_cottage)
//   tc_overhang a tower whose roof overhangs 2 blocks on every side, sideways logs in the eaves (the
//               place/break loop at haunted_80's roof; supports left in the eaves)
//   tc_shutin   a closed box with a roof and no door (the character walls itself in: dig-out + rebuild)
//   tc_details  slabs top/bottom, stairs every way, logs on all three axes, a hanging lantern, a door
//               (orientation / two-step blocks)
//
//   node tools/cottage/test-course.mjs [outDir]   (default: the BuildRecording instance's schematics folder)
// Then put the queue lines it prints into config/startbuild-queue.txt and read each take with
// take-report.mjs.

import path from 'node:path';
import os from 'node:os';
import { writeLitematic } from './litematic.mjs';

const out = process.argv[2] || path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'),
  'PrismLauncher', 'instances', 'BuildRecording', 'minecraft', 'schematics');

function course(name, size, fill) {
  const grid = new Map();
  const set = (x, y, z, s) => grid.set(`${x},${y},${z}`, s.includes(':') ? s : 'minecraft:' + s);
  fill(set);
  const file = path.join(out, name + '.litematic');
  const r = writeLitematic(file, { name, author: 'StartBuild test course', size, grid });
  console.log(`${name.padEnd(12)} ${size.x}x${size.y}x${size.z}  ${r.totalBlocks} blocks  -> ${file}`);
}

// 11 x 3 x 11: stone-brick rim, dirt path ring, farmland with wheat, water source in the middle.
course('tc_garden', { x: 11, y: 3, z: 11 }, (set) => {
  for (let x = 0; x < 11; x++) for (let z = 0; z < 11; z++) {
    const edge = x === 0 || z === 0 || x === 10 || z === 10;
    const ring = x === 1 || z === 1 || x === 9 || z === 9;
    if (edge) { set(x, 0, z, 'stone_bricks'); set(x, 1, z, 'oak_fence'); continue; }
    if (ring) { set(x, 0, z, 'dirt_path'); continue; }
    if (x === 5 && z === 5) { set(x, 0, z, 'water'); continue; }
    set(x, 0, z, 'farmland[moisture=7]');
    set(x, 1, z, 'wheat[age=7]');
  }
});

// 9 x 10 x 9: 5x5 stone tower, roof slab 9x9 at the top (overhang 2), acacia_wood eaves along x and z.
course('tc_overhang', { x: 9, y: 10, z: 9 }, (set) => {
  for (let y = 0; y < 8; y++) for (let x = 2; x <= 6; x++) for (let z = 2; z <= 6; z++) {
    const wall = x === 2 || x === 6 || z === 2 || z === 6;
    if (y === 0 || wall) set(x, y, z, y > 0 && y % 3 === 0 && (x === 4 || z === 4) ? 'glass' : 'cobblestone');
  }
  for (let x = 0; x < 9; x++) for (let z = 0; z < 9; z++) {
    const eaveX = z === 0 || z === 8, eaveZ = x === 0 || x === 8;
    set(x, 8, z, eaveX ? 'acacia_log[axis=x]' : eaveZ ? 'acacia_log[axis=z]' : 'dark_oak_planks');
    if (x >= 1 && x <= 7 && z >= 1 && z <= 7) set(x, 9, z, 'dark_oak_slab[type=bottom]');
  }
});

// 7 x 5 x 7: closed box, no door - the character can only get out by breaking (and rebuilding) a block.
course('tc_shutin', { x: 7, y: 5, z: 7 }, (set) => {
  for (let y = 0; y < 5; y++) for (let x = 0; x < 7; x++) for (let z = 0; z < 7; z++) {
    const shell = y === 0 || y === 4 || x === 0 || z === 0 || x === 6 || z === 6;
    if (shell) set(x, y, z, y === 4 ? 'spruce_planks' : 'bricks');
  }
});

// 9 x 5 x 9: floor, then orientation-sensitive blocks on posts.
course('tc_details', { x: 9, y: 5, z: 9 }, (set) => {
  for (let x = 0; x < 9; x++) for (let z = 0; z < 9; z++) set(x, 0, z, 'polished_andesite');
  const dirs = ['north', 'east', 'south', 'west'];
  dirs.forEach((f, i) => { set(1 + i * 2, 1, 1, `oak_stairs[facing=${f},half=bottom]`); set(1 + i * 2, 2, 1, `oak_stairs[facing=${f},half=top]`); });
  set(1, 1, 3, 'spruce_slab[type=bottom]'); set(3, 1, 3, 'spruce_slab[type=top]'); set(5, 1, 3, 'spruce_slab[type=double]');
  set(1, 1, 5, 'oak_log[axis=x]'); set(3, 1, 5, 'oak_log[axis=y]'); set(5, 1, 5, 'oak_log[axis=z]');
  for (let y = 1; y <= 3; y++) set(7, y, 7, 'stone_brick_wall');
  set(6, 3, 7, 'stone_bricks'); set(6, 2, 7, 'lantern[hanging=true]');
  set(7, 1, 3, 'oak_door[facing=west,half=lower,hinge=left,open=false]');
  set(7, 2, 3, 'oak_door[facing=west,half=upper,hinge=left,open=false]');
  set(7, 1, 2, 'cobblestone'); set(7, 2, 2, 'cobblestone'); set(7, 1, 4, 'cobblestone'); set(7, 2, 4, 'cobblestone');
  set(7, 3, 2, 'cobblestone'); set(7, 3, 3, 'cobblestone'); set(7, 3, 4, 'cobblestone');
});

console.log('\nqueue lines (config/startbuild-queue.txt):');
console.log('tc_garden cherry\ntc_overhang forest\ntc_shutin plains\ntc_details near a lake');
