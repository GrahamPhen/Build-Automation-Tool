// How many schematic cells have NO solid neighbour in any of the 6 directions?
// A block can only be click-placed against an existing solid neighbour, so a cell with no schematic
// neighbour is one Baritone can never place by hand (no scaffold) - these are the completeness gap that
// skipFailedLayers would leave behind.
//
// Usage: node tools/cottage/isolated-cells.mjs <schematic.litematic>

import { readLitematic } from './litematic.mjs';

const file = process.argv[2];
const r = readLitematic(file).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;
const solid = (x, y, z) => {
  if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return false;
  const e = r.palette[r.indices[idx(x, y, z)]];
  return e && e.Name !== 'minecraft:air';
};
const nameOf = (x, y, z) => {
  const e = r.palette[r.indices[idx(x, y, z)]];
  return e ? e.Name : 'minecraft:air';
};

const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

let total = 0;
let isolated = 0;
const perLayer = new Map();
const examples = [];
for (let y = 0; y < sy; y++) {
  let layerIso = 0;
  for (let z = 0; z < sz; z++) {
    for (let x = 0; x < sx; x++) {
      if (!solid(x, y, z)) continue;
      total++;
      let neighbours = 0;
      for (const [dx, dy, dz] of DIRS) if (solid(x + dx, y + dy, z + dz)) neighbours++;
      if (neighbours === 0) {
        isolated++;
        layerIso++;
        if (examples.length < 25) examples.push({ x, y, z, name: nameOf(x, y, z) });
      }
    }
  }
  if (layerIso > 0) perLayer.set(y, layerIso);
}

console.log(`${file.split('\\').pop()}: ${sx}x${sy}x${sz}`);
console.log(`total non-air cells : ${total}`);
console.log(`isolated cells      : ${isolated}  (${(100 * isolated / total).toFixed(2)}% of the build)`);
console.log('');
console.log('isolated cells per layer:');
for (const [y, n] of [...perLayer.entries()].sort((a, b) => a[0] - b[0])) {
  console.log(`  layer ${String(y).padStart(3)}: ${n}`);
}
console.log('');
console.log('first 25 isolated cells:');
for (const e of examples) console.log(`  (${e.x}, ${e.y}, ${e.z})  ${e.name}`);
