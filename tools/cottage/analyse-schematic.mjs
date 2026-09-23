// Analyse haunted_80.litematic: how many blocks live in each layer, and what the bottom layers
// actually look like. Answers "why would a builder wedge on layer 2?" with data instead of guesses.
import fs from 'node:fs';
import { readLitematic } from './litematic.mjs';

const file = process.argv[2];
const { regions, metadata } = readLitematic(file);
const r = regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;

const isAir = (i) => !r.palette[i] || r.palette[i].Name === 'minecraft:air';

console.log(`file        : ${file.split('\\').pop()}`);
console.log(`metadata    : ${metadata.Name} by ${metadata.Author}, TotalBlocks=${metadata.TotalBlocks}`);
console.log(`region      : ${r.name} ${sx}x${sy}x${sz}  palette=${r.palette.length} bits=${r.bits}`);

// per-layer counts
const perLayer = [];
for (let y = 0; y < sy; y++) {
  let n = 0;
  for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) if (!isAir(r.indices[idx(x, y, z)])) n++;
  perLayer.push(n);
}
const total = perLayer.reduce((a, b) => a + b, 0);
console.log(`total blocks: ${total}`);
console.log('');
console.log('layer  blocks  cumulative   (first layer with blocks = the base)');
let cum = 0;
for (let y = 0; y < Math.min(sy, 24); y++) {
  cum += perLayer[y];
  console.log(`  ${String(y).padStart(3)}  ${String(perLayer[y]).padStart(6)}  ${String(cum).padStart(10)}`);
}
console.log(`  ... (${sy - 24} more layers)`);
console.log('');
let firstNonEmpty = perLayer.findIndex((n) => n > 0);
let lastNonEmpty = sy - 1; while (lastNonEmpty > 0 && perLayer[lastNonEmpty] === 0) lastNonEmpty--;
console.log(`first non-empty layer: ${firstNonEmpty}   last non-empty layer: ${lastNonEmpty}`);
console.log(`blocks in layers 0-1: ${perLayer[0] + perLayer[1]}`);
console.log(`blocks in layers 0-2: ${perLayer[0] + perLayer[1] + perLayer[2]}`);

// horizontal extent of the first few non-empty layers: is the base a dense slab or sparse?
console.log('');
for (const y of [firstNonEmpty, firstNonEmpty + 1, firstNonEmpty + 2, firstNonEmpty + 3]) {
  if (y < 0 || y >= sy) continue;
  let minX = 1e9, maxX = -1, minZ = 1e9, maxZ = -1;
  for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    if (!isAir(r.indices[idx(x, y, z)])) {
      minX = Math.min(minX, x); maxX = Math.max(maxX, x);
      minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    }
  }
  console.log(`layer ${y}: ${perLayer[y]} blocks, x ${minX}..${maxX}  z ${minZ}..${maxZ}`);
}

// ASCII map of the base layer, to see the shape
function map(y, step) {
  console.log('');
  console.log(`--- layer ${y} (${perLayer[y]} blocks), x across, z down (step ${step}) ---`);
  for (let z = 0; z < sz; z += step) {
    let line = '';
    for (let x = 0; x < sx; x += step) line += isAir(r.indices[idx(x, y, z)]) ? '.' : '#';
    console.log('  ' + line);
  }
}
map(firstNonEmpty, 2);
map(firstNonEmpty + 1, 2);
map(firstNonEmpty + 2, 2);
