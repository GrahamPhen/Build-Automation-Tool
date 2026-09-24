// Count the cells the pre-pass SHOULD place (unsupported OR non-default), and list every distinct
// property-bearing state, so we can compare against the "Pre-placed 1181" the mod reported.
// Usage: node tools/cottage/count-unbuildable.mjs <schematic.litematic>
import { readLitematic, stateKey } from './litematic.mjs';

const [file] = process.argv.slice(2);
const r = readLitematic(file).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;
const isAir = (i) => !r.palette[i] || r.palette[i].Name === 'minecraft:air';
const solidAt = (x, y, z) => x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz && !isAir(r.indices[idx(x, y, z)]);

let solidTotal = 0, nonDefault = 0, unsupported = 0, both = 0;
const propStates = new Map();
for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
  const i = idx(x, y, z);
  if (isAir(r.indices[i])) continue;
  solidTotal++;
  const e = r.palette[r.indices[i]];
  const key = stateKey(e);
  const hasProps = /\[/.test(key);
  const isNonDefault = hasProps && !/axis=y/.test(key);  // pillars/logs: default is axis=y
  const supported = solidAt(x - 1, y, z) || solidAt(x + 1, y, z) || solidAt(x, y - 1, z)
    || solidAt(x, y, z - 1) || solidAt(x, y, z + 1);
  if (hasProps) propStates.set(key, (propStates.get(key) || 0) + 1);
  if (isNonDefault) nonDefault++;
  if (!supported) unsupported++;
  if (isNonDefault && !supported) both++;
}

console.log(`solid cells       : ${solidTotal}`);
console.log(`non-default (axis!=y or other props): ${nonDefault}`);
console.log(`unsupported (no -x/+x/-y/-z/+z solid neighbour): ${unsupported}`);
console.log(`both (intersection): ${both}`);
console.log(`union (pre-pass target) = nonDefault + unsupported - both = ${nonDefault + unsupported - both}`);
console.log('');
console.log('distinct property-bearing states:');
for (const [k, c] of [...propStates.entries()].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${String(c).padStart(5)}  ${k}`);
}
