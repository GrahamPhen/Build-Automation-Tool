// One report per take, from the game log and the saved world - instead of reading logs by hand:
//   - what ran: schematic, site, terraform plan, time per stage, result line;
//   - trouble: watchdog steps, shut-ins, skipped sites, cells left, temporary blocks left (and whether each
//     can be seen: a support with open sides and a clear line out shows on camera);
//   - the world: schematic vs world (verify-build.mjs) and the ground around the build (site-relief.mjs).
//
//   node tools/cottage/take-report.mjs [logFile] [takeNumber]
// Defaults: the BuildRecording instance's latest.log, the last take in it (1 = first). Close Minecraft (or
// wait for an autosave) first so the region files hold the finished build.

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { loadWorldReader } from './world.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const game = path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'),
  'PrismLauncher', 'instances', 'BuildRecording', 'minecraft');
const logFile = process.argv[2] || path.join(game, 'logs', 'latest.log');
const want = process.argv[3] ? Number(process.argv[3]) : 0;
const regionDir = path.join(game, 'saves', 'Video Building', 'dimensions', 'minecraft', 'overworld', 'region');

const raw = fs.readFileSync(logFile);
const lines = (logFile.endsWith('.gz') ? zlib.gunzipSync(raw) : raw).toString('utf8').split(/\r?\n/).filter((l) => l.includes('[StartBuild]') && !l.includes('[CHAT]'));
const starts = lines.map((l, i) => (/natural build of .* requested/.test(l) ? i : -1)).filter((i) => i >= 0);
if (!starts.length) { console.log('No take in ' + logFile); process.exit(1); }
const s = want > 0 ? starts[want - 1] : starts[starts.length - 1];
const next = starts.find((i) => i > s) ?? lines.length;
const take = lines.slice(s, next);
const msg = (l) => l.replace(/^\[[^\]]*\] \[[^\]]*\]: \[StartBuild\] /, '');
const time = (l) => (l.match(/^\[(\d\d:\d\d:\d\d)\]/) || [])[1] || '?';
const find = (re) => take.find((l) => re.test(l));
const all = (re) => take.filter((l) => re.test(l));

const head = take[0].match(/natural build of (\S+) \((\d+)x(\d+)x(\d+), (\d+) blocks\) at (-?\d+), (-?\d+), (-?\d+)/);
const [, schem, sx, sy, sz, blocks, ox, oy, oz] = head;
console.log(`== ${schem}  (${sx}x${sy}x${sz}, ${Number(blocks).toLocaleString()} blocks)  at ${ox} ${oy} ${oz}  started ${time(take[0])}`);
const plan = find(/terraform plan:/);
if (plan) console.log('   plan: ' + msg(plan).replace('terraform plan: ', ''));
for (const l of all(/hands-free: skipping site/)) console.log('   SKIPPED SITE ' + time(l) + ': ' + msg(l));
const done = find(/Done: /);
const stages = all(/(felling tree \d+\/\d+ done|digging done|filling done|last block placed)/)
  .filter((l, i, a) => !/felling/.test(l) || i === a.length - 1 || !/felling/.test(a[i + 1]));
for (const l of stages) console.log('   ' + time(l) + '  ' + msg(l));
console.log('   result: ' + (done ? time(done) + '  ' + msg(done) : 'NOT FINISHED (no Done line)'));

const watchdog = all(/watchdog:/), shut = all(/shut in:/), gaveUp = all(/giving up/);
console.log(`\n-- trouble: ${watchdog.length} watchdog step(s), ${shut.length} shut-in(s)${gaveUp.length ? ', GAVE UP on a stage' : ''}`);
for (const l of watchdog.slice(0, 8)) console.log('   ' + time(l) + '  ' + msg(l).slice(0, 170));

// Temporary blocks left: can each be seen?
const left = find(/temporary block\(s\) could not be removed/);
const supports = left ? [...msg(left).matchAll(/x=(-?\d+), y=(-?\d+), z=(-?\d+)/g)].map((m) => m.slice(1).map(Number)) : [];
let at = null;
try { at = loadWorldReader(regionDir); } catch { /* no world */ }
if (supports.length && at) {
  const dirs = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  const airy = (n) => n.endsWith(':air') || n.includes('glass');
  let visible = 0;
  const rows = supports.map(([x, y, z]) => {
    const open = dirs.filter(([dx, dy, dz]) => airy(at(x + dx, y + dy, z + dz))).length;
    const clear = dirs.filter(([dx, dy, dz]) => { for (let k = 1; k <= 12; k++) if (!at(x + dx * k, y + dy * k, z + dz * k).endsWith(':air')) return false; return true; }).length;
    if (clear > 0) visible++;
    return `   ${x} ${y} ${z}  ${at(x, y, z).replace('minecraft:', '').padEnd(24)} open sides ${open}, clear lines out ${clear}${clear ? '  <- SHOWS' : ''}`;
  });
  console.log(`\n-- temporary blocks left: ${supports.length} (${visible} can be seen from outside)`);
  rows.forEach((r) => console.log(r));
} else {
  console.log(`\n-- temporary blocks left: ${supports.length}`);
}

// The world: schematic vs world, and the ground around the build.
const schemFile = path.join(game, 'schematics', schem);
if (done && fs.existsSync(regionDir) && fs.existsSync(schemFile)) {
  const newest = Math.max(...fs.readdirSync(regionDir).map((f) => fs.statSync(path.join(regionDir, f)).mtimeMs));
  console.log(`\n-- world (region files last saved ${new Date(newest).toLocaleTimeString()}; close the game or wait for an autosave if that is before the result)`);
  try {
    const out = execFileSync('node', [path.join(here, 'verify-build.mjs'), schemFile, regionDir, ox, oy, oz], { encoding: 'utf8', maxBuffer: 64 << 20 });
    out.split(/\r?\n/).filter((l) => /match|missing|wrong|extra|total|%/i.test(l)).slice(0, 12).forEach((l) => console.log('   ' + l.trim()));
  } catch (e) { console.log('   verify-build failed: ' + (e.stderr || e.message).toString().split('\n')[0]); }
  try {
    const out = execFileSync('node', [path.join(here, 'site-relief.mjs'), regionDir, ox, oz, sx, sz, '12',
      path.join(os.tmpdir(), `take-${schem.replace(/\W+/g, '_')}-relief.png`)], { encoding: 'utf8', maxBuffer: 64 << 20 });
    out.split(/\r?\n/).filter((l) => l.trim()).slice(-8).forEach((l) => console.log('   ' + l.trim()));
  } catch (e) { console.log('   site-relief failed: ' + (e.stderr || e.message).toString().split('\n')[0]); }
}
