// Which side of a build is its front, and the camera yaw that looks at it (RenderDirector's frontYaw).
//   node tools/shorts/front.mjs <schematic.litematic | folder> [--json]
// Builds are placed unrotated, so schematic axes are world axes (+X east, +Z south).
// 1. Exterior doors on the ground floor: the side they open onto is the front (most doors wins; doors
//    near the middle of that side count more).
// 2. No such doors (statues, towers): the side whose visible surface has the most detail - distinct block
//    types seen from that side, with extra weight for "feature" blocks (glass panes, signs, lanterns,
//    banners, buttons, black/white wool or concrete = eyes) - is the front.
// Camera yaw (Minecraft: 0 looks +Z/south, 90 looks -X/west, 180 looks -Z/north, 270 looks +X/east):
// a front facing south is seen by a camera south of it looking north -> 180; an east front by a camera east of
// it looking west -> 90. (East/west were swapped until 2.17.35, so those Shorts ended on the back.)
import fs from 'node:fs';
import path from 'node:path';
import { readLitematic } from '../cottage/litematic.mjs';

const SIDES = {
  south: { normal: [0, 1], yaw: 180 }, north: { normal: [0, -1], yaw: 0 },
  east: { normal: [1, 0], yaw: 90 }, west: { normal: [-1, 0], yaw: 270 },
};
const FEATURE = /glass_pane|sign|lantern|banner|button|torch|door|trapdoor|flower_pot|item_frame|bell|_wool$|concrete$|terracotta$/;
const EYES = /(black|white)_(wool|concrete|terracotta)$/;
const PASSABLE = /(^|:)(air|cave_air)$|stairs|slab|fence|wall$|_wall_|gate|carpet|pane|bars|chain|lantern|torch|sign|banner|flower|grass|fern|bush|petals|leaf_litter|button|pressure|rail|path|snow$|trapdoor|_door$|ladder|vine|leaves|pot|candle|_bed$|chest|barrel|campfire|lever|hay|scaffold|dripleaf|sapling|mushroom|azalea|moss_carpet|water|lava/;
const WALL = n => !PASSABLE.test(n);

function analyse(file) {
  const { regions } = readLitematic(file);
  const r = regions[0];
  const { x: sx, y: sy, z: sz } = r.size;
  const name = (x, y, z) => {
    if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 'minecraft:air';
    const e = r.palette[r.indices[(y * sz + z) * sx + x]];
    return e ? e.Name : 'minecraft:air';
  };
  const props = (x, y, z) => r.palette[r.indices[(y * sz + z) * sx + x]]?.Properties || {};
  const air = n => /(^|:)(air|cave_air|void_air)$/.test(n);
  // non-air box
  let x0 = sx, x1 = -1, y0 = sy, y1 = -1, z0 = sz, z1 = -1;
  for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    if (air(name(x, y, z))) continue;
    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; if (z < z0) z0 = z; if (z > z1) z1 = z;
  }
  const score = { south: 0, north: 0, east: 0, west: 0 };
  const doors = { south: 0, north: 0, east: 0, west: 0 };

  // 1. exterior ground-floor doors: march out of the door along X and Z; the direction that reaches the box
  //    edge through air only is outside.
  const groundTop = y0 + Math.max(3, Math.round((y1 - y0) * 0.3));
  for (let y = y0; y <= groundTop; y++) for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) {
    const n = name(x, y, z);
    if (!/_door$/.test(n) || props(x, y, z).half === 'upper') continue;
    for (const [side, s] of Object.entries(SIDES)) {
      const [dx, dz] = s.normal;
      // Outside = the ray reaches the box edge (or runs 8+ blocks) without meeting a wall. Porch steps,
      // fences, plants, lamps and the like do not count as walls (they made every house "doorless").
      let open = true, k = 1;
      for (; ; k++) {
        const cx = x + dx * k, cz = z + dz * k;
        if (cx < x0 || cx > x1 || cz < z0 || cz > z1 || k > 8) break;
        if (WALL(name(cx, y, cz)) && WALL(name(cx, y + 1, cz))) { open = false; break; }
      }
      if (!open) continue;
      // doors near the middle of the side weigh more than corner/back doors
      const along = dx === 0 ? (x - x0) / Math.max(1, x1 - x0) : (z - z0) / Math.max(1, z1 - z0);
      doors[side] += 1 + (1 - Math.abs(along - 0.5) * 2);
    }
  }

  // 2. visible detail from each side
  const visible = side => {
    const [dx, dz] = SIDES[side].normal;
    const seen = new Map();
    const W = dx === 0 ? [x0, x1] : [z0, z1];
    for (let y = y0; y <= y1; y++) for (let u = W[0]; u <= W[1]; u++) {
      // march from outside inward
      const span = dx === 0 ? z1 - z0 : x1 - x0;
      for (let k = 0; k <= span; k++) {
        const x = dx === 0 ? u : (dx > 0 ? x1 - k : x0 + k);
        const z = dx === 0 ? (dz > 0 ? z1 - k : z0 + k) : u;
        const n = name(x, y, z);
        if (air(n)) continue;
        seen.set(n, (seen.get(n) || 0) + 1);
        break;
      }
    }
    let s = seen.size;
    for (const [n, c] of seen) {
      if (FEATURE.test(n)) s += Math.min(c, 40) / 10;
      if (EYES.test(n)) s += Math.min(c, 60) / 6;
    }
    return s;
  };
  for (const side of Object.keys(SIDES)) score[side] = visible(side);

  const doorSide = Object.entries(doors).sort((a, b) => b[1] - a[1])[0];
  const detailSide = Object.entries(score).sort((a, b) => b[1] - a[1])[0];
  const pick = doorSide[1] >= 1 ? doorSide[0] : detailSide[0];
  return {
    name: path.basename(file, '.litematic'), front: pick, frontYaw: SIDES[pick].yaw,
    by: doorSide[1] >= 1 ? 'doors' : 'detail',
    doors: Object.fromEntries(Object.entries(doors).map(([k, v]) => [k, +v.toFixed(1)])),
    detail: Object.fromEntries(Object.entries(score).map(([k, v]) => [k, +v.toFixed(1)])),
  };
}

const arg = process.argv[2];
const files = fs.statSync(arg).isDirectory()
  ? fs.readdirSync(arg).filter(n => n.endsWith('.litematic')).map(n => path.join(arg, n)) : [arg];
const out = files.map(f => { try { return analyse(f); } catch (e) { return { name: path.basename(f), error: e.message }; } });
if (process.argv.includes('--json')) console.log(JSON.stringify(out, null, 1));
else for (const o of out) console.log(o.error ? `${o.name}: ERROR ${o.error}` :
  `${o.name.padEnd(34)} front ${o.front.padEnd(5)} yaw ${String(o.frontYaw).padStart(3)} (${o.by})  doors ${JSON.stringify(o.doors)} detail ${JSON.stringify(o.detail)}`);
