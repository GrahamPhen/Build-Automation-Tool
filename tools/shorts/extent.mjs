// Non-air bounding box of each schematic (local coords from the region's min corner).
import fs from 'node:fs';
import path from 'node:path';
import { readLitematic } from '../cottage/litematic.mjs';
const dir = process.argv[2];
for (const f of fs.readdirSync(dir).filter(n => n.endsWith('.litematic'))) {
  const { regions } = readLitematic(path.join(dir, f));
  const r = regions[0];
  const air = new Set(r.palette.map((p, i) => /air$/.test(p.Name) ? i : -1).filter(i => i >= 0));
  let x0 = 1e9, x1 = -1, y0 = 1e9, y1 = -1, z0 = 1e9, z1 = -1;
  const { x: sx, y: sy, z: sz } = r.size;
  for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    if (air.has(r.indices[(y * sz + z) * sx + x])) continue;
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; if (z < z0) z0 = z; if (z > z1) z1 = z;
  }
  const p = r.position, s = regions[0];
  console.log([f.replace('.litematic', ''), regions.length, `pos ${p.x},${p.y},${p.z} size ${sx}x${sy}x${sz} rawSize ${s.size.x}`,
    `x ${x0}-${x1} y ${y0}-${y1} z ${z0}-${z1}`, `w ${x1 - x0 + 1} h ${y1 - y0 + 1} d ${z1 - z0 + 1}`,
    `cx ${((x0 + x1) / 2).toFixed(1)} cz ${((z0 + z1) / 2).toFixed(1)}`].join(' | '));
}
