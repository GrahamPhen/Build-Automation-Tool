// Missing cells of a finished build: how many can be seen from outside (a straight air line of 16 in any of 6 directions).
import { readLitematic } from '../cottage/litematic.mjs';
import { loadWorldReader } from '../cottage/world.mjs';
const [file, regionDir, ox, oy, oz] = process.argv.slice(2);
const r = readLitematic(file).regions[0];
const { x: sx, y: sy, z: sz } = r.size;
const at = loadWorldReader(regionDir);
const name = (x, y, z) => { const e = r.palette[r.indices[(y * sz + z) * sx + x]]; return e ? e.Name : 'minecraft:air'; };
// schematic may carry empty bottom layers the mod drops: find the first non-empty layer
let y0 = 0;
outer: for (; y0 < sy; y0++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) if (name(x, y0, z) !== 'minecraft:air') break outer;
const dirs = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];
let miss = 0, visible = 0, airSide = 0, withNb = 0; const samples = [], byY = {};
let minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
for (let y = y0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
  const want = name(x, y, z);
  if (want === 'minecraft:air') continue;
  const wx = +ox + x, wy = +oy + y - y0, wz = +oz + z;
  if (at(wx, wy, wz) !== 'minecraft:air') continue;
  miss++;
  let vis = false, side = false;
  for (const [dx, dy, dz] of dirs) {
    let open = true;
    for (let k = 1; k <= 16; k++) { if (at(wx + dx * k, wy + dy * k, wz + dz * k) !== 'minecraft:air') { open = false; if (k === 1) {} break; } if (k === 1) side = true; }
    if (open) vis = true;
  }
  if (vis) { visible++; if (samples.length < 5) samples.push(`${wx},${wy},${wz}`); }
  if (side) airSide++;
  const solidNb = dirs.some(([dx, dy, dz]) => at(wx + dx, wy + dy, wz + dz) !== 'minecraft:air');
  if (solidNb) withNb++;
  const k = `${Math.floor((wy) / 10) * 10}`; byY[k] = (byY[k] || 0) + 1;
  minX = Math.min(minX, wx); maxX = Math.max(maxX, wx); minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
}
console.log(`trimmed ${y0} empty layers; missing ${miss}, next to air ${airSide}, visible from outside ${visible}`, samples.join(' '));
console.log(`with a solid face neighbour ${withNb}; x ${minX}..${maxX} z ${minZ}..${maxZ}; by y`, JSON.stringify(byY));
console.log(`schematic ${sx}x${sy}x${sz}`);
