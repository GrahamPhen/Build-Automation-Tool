// Shrink a .litematic by an integer factor: each f*f*f cube becomes its most common block,
// kept only if at least `min` of its cells are filled (so thin 1-block walls survive, specks do not).
//   node shrink.mjs <in.litematic> <out.litematic> [factor=2] [min=3]
import { readLitematic, writeLitematic, stateKey } from '../cottage/litematic.mjs';
const [inp, out, fs_ = '2', ms = '3'] = process.argv.slice(2);
const f = +fs_, min = +ms;
const r = readLitematic(inp).regions[0], S = r.size;
const N = { x: Math.ceil(S.x / f), y: Math.ceil(S.y / f), z: Math.ceil(S.z / f) };
const grid = new Map();
const full = (k) => !/stairs|slab|fence|wall|pane|door|trapdoor|button|torch|carpet|sign|lantern|chain|bars/.test(k);
for (let Y = 0; Y < N.y; Y++) for (let Z = 0; Z < N.z; Z++) for (let X = 0; X < N.x; X++) {
  const c = new Map(); let n = 0;
  for (let y = Y * f; y < Math.min(S.y, Y * f + f); y++) for (let z = Z * f; z < Math.min(S.z, Z * f + f); z++) for (let x = X * f; x < Math.min(S.x, X * f + f); x++) {
    const p = r.palette[r.indices[(y * S.z + z) * S.x + x]];
    if (!p || p.Name === 'minecraft:air') continue;
    const k = stateKey(p); n++; c.set(k, (c.get(k) || 0) + (full(k) ? 1.01 : 1));
  }
  if (n < min) continue;
  let best = null, bv = -1; for (const [k, v] of c) if (v > bv) { bv = v; best = k; }
  grid.set(`${X},${Y},${Z}`, best);
}
const w = writeLitematic(out, { name: out.split(/[\\/]/).pop().replace(/\.litematic$/, ''), author: 'shrink', size: N, grid });
console.log(`${S.x}x${S.y}x${S.z} -> ${N.x}x${N.y}x${N.z}, ${w.totalBlocks} blocks`);
