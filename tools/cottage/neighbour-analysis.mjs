// Does the schematic contain any cell that a click-based builder cannot reach, beyond the fully-isolated
// ones? Baritone's goal-based placement can click DOWN and horizontally, but the +/-5 scan can also click
// UP. The risky class is a cell whose ONLY solid neighbour is ABOVE it - goal pathing excludes UP.
//
// Usage: node tools/cottage/neighbour-analysis.mjs <schematic.litematic>

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

let total = 0;
let zeroNeighbour = 0;
let onlyAbove = 0;
let onlyDownOrHorizontal = 0;
const onlyAboveExamples = [];

for (let y = 0; y < sy; y++) {
  for (let z = 0; z < sz; z++) {
    for (let x = 0; x < sx; x++) {
      if (!solid(x, y, z)) continue;
      total++;
      const up = solid(x, y + 1, z);
      const down = solid(x, y - 1, z);
      const horiz = solid(x + 1, y, z) || solid(x - 1, y, z) || solid(x, y, z + 1) || solid(x, y, z - 1);
      const any = up || down || horiz;
      if (!any) zeroNeighbour++;
      else if (up && !down && !horiz) {
        onlyAbove++;
        if (onlyAboveExamples.length < 20) onlyAboveExamples.push(`(${x}, ${y}, ${z})`);
      }
      if (down || horiz) onlyDownOrHorizontal++;
    }
  }
}

console.log(`${file.split('\\').pop()}: ${sx}x${sy}x${sz}`);
console.log(`total non-air                : ${total}`);
console.log(`no neighbour at all          : ${zeroNeighbour}   (pre-pass handles these)`);
console.log(`ONLY-above neighbour         : ${onlyAbove}   (goal-pathing excludes UP)`);
console.log(`down or horizontal neighbour : ${onlyDownOrHorizontal}   (definitely click-placeable)`);
console.log('');
console.log('only-above examples:');
for (const e of onlyAboveExamples) console.log(`  ${e}`);
