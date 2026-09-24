// Count every cell Baritone genuinely CANNOT click-place: a cell is only reliably placeable when it
// has a solid block DIRECTLY BELOW it (Baritone's placement goal is HORIZONTALS + DOWN; in layer mode
// the below block is always placed first). A floating cell (no block below) can only be clicked from a
// horizontal face, and its same-layer horizontal neighbour may not be placed yet -> deadlock.
//
// This reports the union of:
//   1. non-default states (axis=x etc.) - Baritone's click only makes the default state
//   2. floating cells (no solid block directly below) - no clickable face
// That union is the set the pre-pass MUST /setblock before Baritone starts.
//
// Usage: node tools/cottage/count-floating.mjs <schematic.litematic>
import { readLitematic, stateKey } from './litematic.mjs';

const [file] = process.argv.slice(2);
const r = readLitematic(file).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;
const isAir = (i) => !r.palette[i] || r.palette[i].Name === 'minecraft:air';
const solidAt = (x, y, z) => x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz && !isAir(r.indices[idx(x, y, z)]);

let solidTotal = 0, nonDefault = 0, floating = 0, both = 0;
const perLayerFloat = [];
for (let y = 0; y < sy; y++) {
  let f = 0;
  for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    const i = idx(x, y, z);
    if (isAir(r.indices[i])) continue;
    solidTotal++;
    const key = stateKey(r.palette[r.indices[i]]);
    const isNonDefault = /\[/.test(key) && !/axis=y/.test(key);
    // y=0 sits on the superflat ground (world y=-61 is solid), so layer 0 always has below support.
    const hasBelow = y === 0 ? true : solidAt(x, y - 1, z);
    if (isNonDefault) nonDefault++;
    if (!hasBelow) { floating++; f++; }
    if (isNonDefault && !hasBelow) both++;
  }
  perLayerFloat.push(f);
}

console.log(`solid cells       : ${solidTotal}`);
console.log(`non-default (axis!=y etc): ${nonDefault}`);
console.log(`floating (no solid below): ${floating}`);
console.log(`both (intersection): ${both}`);
console.log(`UNION (must pre-place) = ${nonDefault + floating - both}`);
console.log('');
console.log('floating cells per layer (top 20 with any):');
perLayerFloat.forEach((n, y) => { if (n > 0) console.log(`  layer ${String(y).padStart(2)}: ${n}`); });
