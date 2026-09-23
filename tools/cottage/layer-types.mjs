// Which block types does each layer need, and could the inventory possibly have them?
//
// The mod's restocking is reactive: it waits for Baritone to say "Missing materials for at least: ...".
// If Baritone ever skips blocks silently instead of pausing, the build deadlocks with no message at all.
// This prints the per-layer type requirements next to the types a stock batch actually gives, so that
// either shows up as data.
//
// Usage: node tools/cottage/layer-types.mjs <schematic.litematic> [mcfunctionDir]

import fs from 'node:fs';
import path from 'node:path';
import { readLitematic } from './litematic.mjs';

const file = process.argv[2];
const fnDir = process.argv[3];
const { regions, metadata } = readLitematic(file);
const r = regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const idx = (x, y, z) => (y * sz + z) * sx + x;
const nameAt = (x, y, z) => {
  const e = r.palette[r.indices[idx(x, y, z)]];
  return e ? e.Name : 'minecraft:air';
};

console.log(`${path.basename(file)} (${metadata.Name}) ${sx}x${sy}x${sz}, ${metadata.TotalBlocks} blocks`);

// per-layer type sets
const layerTypes = [];
const layerCount = [];
for (let y = 0; y < sy; y++) {
  const types = new Map();
  let n = 0;
  for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    const nm = nameAt(x, y, z);
    if (nm === 'minecraft:air') continue;
    types.set(nm, (types.get(nm) || 0) + 1);
    n++;
  }
  layerTypes.push(types);
  layerCount.push(n);
}

// cumulative distinct types up to and including each layer
let cum = new Set();
console.log('');
console.log('layer  blocks  types  cumulative-distinct-types');
for (let y = 0; y < Math.min(sy, 20); y++) {
  for (const t of layerTypes[y].keys()) cum.add(t);
  console.log(`  ${String(y).padStart(3)}  ${String(layerCount[y]).padStart(6)}  ${String(layerTypes[y].size).padStart(5)}  ${String(cum.size).padStart(5)}`);
}
const allTypes = new Set();
for (const m of layerTypes) for (const t of m.keys()) allTypes.add(t);
console.log(`  ... total distinct types in the whole schematic: ${allTypes.size}`);

// what a stock batch gives
if (fnDir && fs.existsSync(fnDir)) {
  console.log('');
  for (const fn of fs.readdirSync(fnDir).filter((f) => f.endsWith('.mcfunction')).sort()) {
    const text = fs.readFileSync(path.join(fnDir, fn), 'utf8');
    const given = [...text.matchAll(/give\s+@s\s+([a-z0-9_:/.-]+)/g)].map((m) => m[1]);
    const cleared = [...text.matchAll(/clear\s+@s\s+([a-z0-9_:/.-]+)/g)].length;
    console.log(`${fn}: gives ${new Set(given).size} distinct types, ${given.length} give lines, ${cleared} clear lines`);
  }
}

// The decisive question: how many of the types needed by layers 1..N would a 36-slot inventory hold?
console.log('');
console.log('=== how deep could a build get with only the FIRST batch loaded? ===');
if (fnDir && fs.existsSync(fnDir)) {
  const files = fs.readdirSync(fnDir).filter((f) => f.endsWith('.mcfunction')).sort();
  const batch1 = new Set(
    [...fs.readFileSync(path.join(fnDir, files[0]), 'utf8').matchAll(/give\s+@s\s+([a-z0-9_:/.-]+)/g)].map((m) => m[1]),
  );
  console.log(`batch 1 (${files[0]}) holds ${batch1.size} types`);
  let blockedAt = null;
  const have = new Set(batch1);
  for (let y = 0; y < sy; y++) {
    const need = [...layerTypes[y].keys()].filter((t) => t !== 'minecraft:air');
    const absent = need.filter((t) => !have.has(t));
    if (absent.length && blockedAt === null) blockedAt = y;
    // a real run would restock, so also report the first layer needing a type not in ANY batch
    for (const t of need) have.add(t);
  }
  console.log(`first layer needing a type absent from batch 1: ${blockedAt}`);
  if (blockedAt !== null) {
    const need = [...layerTypes[blockedAt].keys()].filter((t) => t !== 'minecraft:air' && !batch1.has(t));
    console.log(`  layer ${blockedAt} needs ${need.length} type(s) batch 1 does not give: ${need.slice(0, 10).join(', ')}`);
  }
}
