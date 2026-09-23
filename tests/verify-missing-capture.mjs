// Verifies BaritoneEventBridge.observe() against the EXACT lines Baritone produced in the failing run.
//
// The capture logic is pure string handling, so it can be checked offline - which matters, because the
// last three fixes were all "should work" and none of them did. Run: node tests/verify-missing-capture.mjs

const HEADER = 'Missing materials for at least:';   // note the colon: omitting it was the bug
const BLOCK_ID = /[a-z_]+:[a-z_]+/;

// Mirrors observe() in BaritoneEventBridge.java
//
// Baritone logs the missing-materials list as ONE MESSAGE PER BLOCK TYPE, so the observer has to
// ACCUMULATE. The previous version captured only the single message after the header, which meant that
// with ten missing types the mod learned about one of them, gave that one, and left the build short of
// the other nine indefinitely.
function makeObserver() {
  let collecting = false;
  let buffer = '';
  let captured = '';
  const push = (line) => {
    buffer = buffer ? `${buffer}\n${line}` : line;
    captured = buffer;
  };
  return {
    feed(raw) {
      const text = String(raw).trim();
      const header = text.indexOf(HEADER);
      if (header >= 0) {
        const tail = text.substring(header + HEADER.length).trim();
        buffer = '';
        collecting = true;
        if (BLOCK_ID.test(tail)) push(tail);
        return;
      }
      if (collecting) {
        if (BLOCK_ID.test(text)) { push(text); return; }
        collecting = false;   // a line with no block id ends the list
      }
    },
    get captured() { return captured; },
  };
}

// The previous implementation: captures only the one message after the header.
function makeOneLineObserver() {
  let expectList = false;
  let captured = '';
  return {
    feed(raw) {
      const text = String(raw).trim();
      const header = text.indexOf(HEADER);
      if (header >= 0) {
        const tail = text.substring(header + HEADER.length).trim();
        if (BLOCK_ID.test(tail)) { expectList = false; captured = tail; } else { expectList = true; captured = ''; }
        return;
      }
      if (expectList) {
        expectList = false;
        if (BLOCK_ID.test(text)) captured = text;
      }
    },
    get captured() { return captured; },
  };
}

// The old implementation, kept to demonstrate what went wrong.
function makeOldObserver() {
  const OLD_HEADER = 'Missing materials for at least';   // no colon
  let expectList = false;
  let captured = '';
  return {
    feed(raw) {
      const text = String(raw).trim();
      if (expectList) { captured = text; expectList = false; return; }
      if (text.startsWith(OLD_HEADER)) { expectList = true; captured = ''; }
    },
    get captured() { return captured; },
  };
}

function parseBlockIds(missingText) {
  const found = new Set();
  for (const m of missingText.matchAll(/[a-z_]+:[a-z_]+/g)) found.add(m[0]);
  return [...found];
}

const REAL = [
  '[Baritone] Missing materials for at least:',
  '[Baritone] 1x Block{minecraft:red_concrete}',
  '[Baritone] Unable to do it. Pausing. resume to resume, cancel to cancel',
];

let failures = 0;
function check(name, actual, expected) {
  const ok = actual === expected;
  if (!ok) failures++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}`);
  if (!ok) console.log(`        expected ${JSON.stringify(expected)}\n        actual   ${JSON.stringify(actual)}`);
}

console.log('=== the exact lines from the 11:13 run ===');
const oldObs = makeOldObserver();
REAL.forEach(line => oldObs.feed(line));
check('old logic captured nothing (this was the bug)', oldObs.captured, '');

const obs = makeObserver();
REAL.forEach(line => obs.feed(line));
check('new logic captures the list line',
  obs.captured, '[Baritone] 1x Block{minecraft:red_concrete}');
check('new logic parses the block id',
  parseBlockIds(obs.captured).join(','), 'minecraft:red_concrete');

console.log('\n=== other shapes Baritone might use ===');
{
  const o = makeObserver();
  o.feed('[Baritone] Missing materials for at least: 1x Block{minecraft:stone}');
  check('header and list on one line', parseBlockIds(o.captured).join(','), 'minecraft:stone');
}
{
  const o = makeObserver();
  o.feed('[Baritone] Missing materials for at least:\n1x Block{minecraft:oak_planks}\n1x Block{minecraft:sand}');
  check('header and multi-line list in one message',
    parseBlockIds(o.captured).join(','), 'minecraft:oak_planks,minecraft:sand');
}
{
  const o = makeObserver();
  o.feed('[Baritone] Missing materials for at least:');
  o.feed('[Baritone] Unable to do it. Pausing. resume to resume, cancel to cancel');
  check('a non-list line after the header is not captured', o.captured, '');
}
{
  const o = makeObserver();
  o.feed('[Baritone] Starting layer 0');
  o.feed('[Baritone] Done building');
  check('unrelated chatter is ignored', o.captured, '');
}
{
  const o = makeObserver();
  o.feed('[Baritone] Missing materials for at least:');
  o.feed('[Baritone] 1x Block{minecraft:red_concrete}');
  o.feed('[Baritone] Starting layer 1');
  check('capture survives later chatter', parseBlockIds(o.captured).join(','), 'minecraft:red_concrete');
}

// ---------------------------------------------------------------------------------------------
// The multi-type case, which is what a real late layer looks like. Baritone emits the header and then
// ONE MESSAGE PER MISSING TYPE, so a one-line capture can only ever learn about the first.
// ---------------------------------------------------------------------------------------------
console.log('\n=== a ten-type missing list, exactly as Baritone sends it ===');
const MULTI = [
  '[Baritone] Missing materials for at least:',
  '[Baritone] 3x Block{minecraft:acacia_log}',
  '[Baritone] 2x Block{minecraft:red_nether_bricks}',
  '[Baritone] 1x Block{minecraft:netherrack}',
  '[Baritone] 1x Block{minecraft:spruce_wood}',
  '[Baritone] 1x Block{minecraft:spruce_log}',
  '[Baritone] 1x Block{minecraft:red_terracotta}',
  '[Baritone] 1x Block{minecraft:bricks}',
  '[Baritone] 1x Block{minecraft:red_sand}',
  '[Baritone] 1x Block{minecraft:acacia_planks}',
  '[Baritone] 1x Block{minecraft:red_concrete}',
  '[Baritone] Unable to do it. Pausing. resume to resume, cancel to cancel',
];
{
  const multi = makeObserver();
  MULTI.forEach((line) => multi.feed(line));
  check('the accumulator captures all ten types', parseBlockIds(multi.captured).length, 10);
  check('and names them all',
    parseBlockIds(multi.captured).sort().join(','),
    ['minecraft:acacia_log', 'minecraft:acacia_planks', 'minecraft:bricks', 'minecraft:netherrack',
      'minecraft:red_concrete', 'minecraft:red_nether_bricks', 'minecraft:red_sand',
      'minecraft:red_terracotta', 'minecraft:spruce_log', 'minecraft:spruce_wood'].sort().join(','));

  const oneLine = makeOneLineObserver();
  MULTI.forEach((line) => oneLine.feed(line));
  check('the old one-line capture found only one (this was the bug)',
    parseBlockIds(oneLine.captured).length, 1);
}
{
  // A second, later list must REPLACE the first rather than pile onto it.
  const o = makeObserver();
  MULTI.forEach((line) => o.feed(line));
  o.feed('[Baritone] Missing materials for at least:');
  o.feed('[Baritone] 1x Block{minecraft:stone}');
  check('a new list replaces the previous one', parseBlockIds(o.captured).join(','), 'minecraft:stone');
}

// ---------------------------------------------------------------------------------------------
// Full materials chain: Baritone's line -> captured list -> parsed ids -> the exact command sent.
// Uses the REAL installed stock function to build the simulated inventory, so this runs against the
// same data the game will see.
// ---------------------------------------------------------------------------------------------
console.log('\n=== full materials chain, against the real datapack ===');

const fnPath = `${process.env.APPDATA}\\PrismLauncher\\instances\\BuildRecording\\minecraft\\saves\\New World (1)\\datapacks\\haunted_80\\data\\sb\\function\\haunted_80_1.mcfunction`;
let batch1 = [];
try {
  const fs = await import('node:fs');
  batch1 = [...new Set([...fs.readFileSync(fnPath, 'utf8').matchAll(/^give\s+@s\s+([a-z_]+:[a-z_]+)/gm)].map(m => m[1]))];
  console.log(`  read ${batch1.length} block types from haunted_80_1.mcfunction`);
} catch (err) {
  console.log(`  could not read the stock function (${err.message}) - skipping the chain check`);
}

if (batch1.length) {
  // Mirrors supplyMissingTypesDirectly(): parse ids, skip what we hold, free a slot if full, then give.
  function decide(missingText, inventory, giveCount, attempts = new Map()) {
    const missing = new Set(missingText.match(/[a-z_]+:[a-z_]+/g) ?? []);
    const commands = [];
    for (const type of missing) {
      if (inventory.has(type)) continue;                       // already held: a resume is what is needed
      if ((attempts.get(type) ?? 0) >= 3) continue;            // gave up on it after 3 failed tries
      if (inventory.size >= 36) {                              // full: clear the least-used type
        const candidates = [...inventory].filter(t => !missing.has(t));
        if (!candidates.length) continue;
        candidates.sort();                                     // stock order stands in for "least used"
        const victim = candidates[candidates.length - 1];
        commands.push(`clear @s ${victim}`);
        inventory.delete(victim);
      }
      commands.push(`give @s ${type} ${giveCount}`);
      inventory.add(type);
    }
    return commands;
  }

  const obs2 = makeObserver();
  REAL.forEach(line => obs2.feed(line));
  const inventory = new Set(batch1);
  const commands = decide(obs2.captured, inventory, 64);

  check('the chain produces exactly a clear + a give',
    commands.length === 2 ? 'clear+give' : commands.join(' | '), 'clear+give');
  check('the give is for red_concrete',
    commands.find(c => c.startsWith('give')) ?? '(none)', 'give @s minecraft:red_concrete 64');
  check('it clears something other than red_concrete',
    commands[0] !== 'clear @s minecraft:red_concrete', true);
  check('red_concrete ends up in the inventory', inventory.has('minecraft:red_concrete'), true);

  console.log('  commands that would be sent:');
  for (const c of commands) console.log(`    /${c}`);
}

console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);
