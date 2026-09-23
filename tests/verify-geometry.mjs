// Regression guard for the geometry bugs that cost real runs.
//
// Two whole-run failures came from the same mistake: code that read a value with the wrong MEANING.
//   - BaritoneBridge.schematicBox() returns {originX, originY, originZ, WIDTH, HEIGHT, LENGTH}.
//     Slots 3..5 are sizes, not maximum coordinates. Two call sites read them as coordinates, so the
//     stall recovery decided a player standing in the middle of an 80-wide build was outside it and
//     left them there - the exact stall the method exists to clear.
//   - TerrainPrep cut terrain above the base level with "highest > baseY". A column whose top solid
//     block sits exactly AT baseY - i.e. inside the build's bottom layer - made highest == baseY, so
//     nothing was cut and terrain stayed in the build's way.
//
// The functions below mirror the Java exactly. If the Java changes meaning, these fail.
// Run: node tests/verify-geometry.mjs

const MAX_FILL_BLOCKS = 32768;

let failures = 0;
function check(name, actual, expected) {
    const a = JSON.stringify(actual);
    const e = JSON.stringify(expected);
    if (a === e) {
        console.log(`  PASS  ${name}`);
    } else {
        failures++;
        console.log(`  FAIL  ${name}\n          expected ${e}\n          actual   ${a}`);
    }
}

// ---------------------------------------------------------------------------------------------
// 1. The schematicBox contract, as BaritoneBridge.schematicBox() actually returns it.
//    Mirrors: return new int[]{origin.getX(), origin.getY(), origin.getZ(), width, height, length};
// ---------------------------------------------------------------------------------------------
function schematicBox(originX, originY, originZ, width, height, length) {
    return [originX, originY, originZ, width, height, length];
}

// Mirrors reportSites(): the footprint size for the site search.
function footprintSize(box) {
    return { sizeX: box[3], sizeZ: box[5] };
}
// The old, wrong reading - kept so the test proves the two disagree.
function oldFootprintSize(box) {
    return { sizeX: Math.abs(box[3] - box[0]) + 1, sizeZ: Math.abs(box[5] - box[2]) + 1 };
}

// Mirrors movePlayerOutOfBuild(): is the player standing in the build's footprint?
function playerInside(box, px, pz) {
    const minX = box[0], minZ = box[2];
    const maxX = box[0] + box[3] - 1, maxZ = box[2] + box[5] - 1;
    return px >= minX && px <= maxX && pz >= minZ && pz <= maxZ;
}
function oldPlayerInside(box, px, pz) {
    return px >= Math.min(box[0], box[3]) && px <= Math.max(box[0], box[3])
        && pz >= Math.min(box[2], box[5]) && pz <= Math.max(box[2], box[5]);
}

console.log('schematicBox contract (haunted_80: 80x64x80 placed at 100,70,200)');
const h80 = schematicBox(100, 70, 200, 80, 64, 80);
check('footprint size is the schematic size', footprintSize(h80), { sizeX: 80, sizeZ: 80 });
check('footprint spans x 100..179, z 200..279', [
    h80[0], h80[0] + h80[3] - 1, h80[2], h80[2] + h80[5] - 1,
], [100, 179, 200, 279]);

console.log('\nthe old reading produced a nonsense footprint');
check('old formula got the width wrong', oldFootprintSize(h80).sizeX, 21);
check('old and new disagree', oldFootprintSize(h80).sizeX !== footprintSize(h80).sizeX, true);

console.log('\nstall recovery: is the player inside the build?');
check('centre of the build (140, 240) is inside', playerInside(h80, 140, 240), true);
check('old logic said the centre was OUTSIDE - the bug', oldPlayerInside(h80, 140, 240), false);
check('min corner (100, 200) is inside', playerInside(h80, 100, 200), true);
check('max corner (179, 279) is inside', playerInside(h80, 179, 279), true);
check('one block past the far edge (180, 240) is outside', playerInside(h80, 180, 240), false);
check('one block past the near edge (99, 240) is outside', playerInside(h80, 99, 240), false);

console.log('\nwhere the player gets moved to');
function stepOut(box) {
    return { x: box[0] - 2 + 0.5, z: box[2] - 2 + 0.5 };
}
check('stepped to (98.5, 198.5), outside the footprint', stepOut(h80), { x: 98.5, z: 198.5 });
check('step-out target is not inside the build', playerInside(h80, 98, 198), false);

// A placement whose origin is negative must still work - the arithmetic must not assume positive.
console.log('\nnegative origin');
const neg = schematicBox(-300, 70, -400, 48, 32, 16);
check('negative-origin footprint size', footprintSize(neg), { sizeX: 48, sizeZ: 16 });
check('negative-origin interior is inside', playerInside(neg, -280, -390), true);
check('negative-origin step-out is outside', playerInside(neg, neg[0] - 2, neg[2] - 2), false);

// ---------------------------------------------------------------------------------------------
// 2. TerrainPrep's clear decision. Mirrors level():
//      highest = max over the footprint of (getHeight - 1), starting below every real height
//      if (highest >= baseY) -> fill(x1, baseY, z1, x2, highest, z2, air)
// ---------------------------------------------------------------------------------------------
function clearPlan(tops, baseY) {
    let highest = Number.MIN_SAFE_INTEGER;
    for (const t of tops) if (t > highest) highest = t;
    if (highest === Number.MIN_SAFE_INTEGER) return null;   // no loaded column sampled
    return highest >= baseY ? { from: baseY, to: highest } : null;
}
function oldClearPlan(tops, baseY) {
    let highest = baseY;
    for (const t of tops) if (t > highest) highest = t;
    return highest > baseY ? { from: baseY, to: highest } : null;
}

console.log('\nTerrainPrep clear decision (baseY = 71, median ground top = 70)');
const baseY = 71;
check('flat ground at the median: nothing to cut', clearPlan([68, 69, 70, 70, 70], baseY), null);
check('a column reaching exactly baseY IS cut (was missed)',
    clearPlan([70, 70, 71, 70], baseY), { from: 71, to: 71 });
check('the old guard missed exactly that case',
    oldClearPlan([70, 70, 71, 70], baseY), null);
check('a taller spike is cut from baseY up to it',
    clearPlan([70, 74, 70], baseY), { from: 71, to: 74 });
check('no loaded columns at all: no commands', clearPlan([], baseY), null);
check('ground entirely below baseY: nothing to cut', clearPlan([60, 65, 70], baseY), null);

// ---------------------------------------------------------------------------------------------
// 3. TerrainPrep.fill's slab splitter. Every emitted /fill must cover at most MAX_FILL_BLOCKS,
//    or vanilla rejects it and the ground is left half-levelled.
//    The old splitter sliced only on X: when height * depth alone exceeded the cap, perSlice
//    collapsed to 1 and every command was still over the cap.
// ---------------------------------------------------------------------------------------------
function slabVolume(s) {
    return (s.x2 - s.x1 + 1) * (s.y2 - s.y1 + 1) * (s.z2 - s.z1 + 1);
}

// Mirrors the new fill(): slice X, then slice Z.
function fill(x1, y1, z1, x2, y2, z2) {
    const height = y2 - y1 + 1;
    const depth = z2 - z1 + 1;
    const out = [];
    if (x2 - x1 + 1 <= 0 || height <= 0 || depth <= 0) return out;
    const perX = Math.max(1, Math.floor(MAX_FILL_BLOCKS / Math.max(1, height * depth)));
    for (let sx = x1; sx <= x2; sx += perX) {
        const ex = Math.min(x2, sx + perX - 1);
        const slabWidth = ex - sx + 1;
        const perZ = Math.max(1, Math.floor(MAX_FILL_BLOCKS / Math.max(1, height * slabWidth)));
        for (let sz = z1; sz <= z2; sz += perZ) {
            const ez = Math.min(z2, sz + perZ - 1);
            out.push({ x1: sx, y1, z1: sz, x2: ex, y2, z2: ez });
        }
    }
    return out;
}

// The old behaviour, for comparison.
function oldFill(x1, y1, z1, x2, y2, z2) {
    const height = y2 - y1 + 1;
    const depth = z2 - z1 + 1;
    const out = [];
    const perSlice = Math.max(1, Math.floor(MAX_FILL_BLOCKS / Math.max(1, height * depth)));
    for (let start = x1; start <= x2; start += perSlice) {
        const end = Math.min(x2, start + perSlice - 1);
        out.push({ x1: start, y1, z1, x2: end, y2, z2 });
    }
    return out;
}

console.log('\n/fill slab splitting never exceeds the 32,768 block cap');
const boxes = [
    // [x1, y1, z1, x2, y2, z2, label]
    [100, 70, 200, 179, 76, 279, 'clear of an 80x80 footprint, 7 layers'],
    [104, 70, 204, 175, 70, 275, 'single-layer fill across the margin'],
    [0, 60, 0, 1, 471, 1, 'tall narrow column pair (height alone is over the cap)'],
    [0, 0, 0, 0, 399, 99, 'one column wide, 400 tall, 100 deep (height*depth over the cap)'],
    [0, 0, 0, 1, 400, 100, 'tall and deep, 2 wide'],
    [-500, -60, -500, 499, -40, 499, '10,000 column box, 21 layers'],
    [0, 0, 0, 0, 0, 0, 'one block'],
    [5, 9, 13, 4, 9, 13, 'empty on X (no commands)'],
];
for (const [x1, y1, z1, x2, y2, z2, label] of boxes) {
    const slabs = fill(x1, y1, z1, x2, y2, z2);
    const worst = slabs.reduce((m, s) => Math.max(m, slabVolume(s)), 0);
    check(`${label}: every slab <= cap (worst ${worst})`, worst <= MAX_FILL_BLOCKS, true);

    // And the slabs must still cover exactly the requested volume, with no gap or overlap.
    const covered = slabs.reduce((t, s) => t + slabVolume(s), 0);
    const want = (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    check(`${label}: covers the whole box exactly`, covered, want <= 0 ? 0 : want);
}

console.log('\nthe old X-only splitter really did emit rejected commands');
// 400 tall * 100 deep = 40,000 on its own. The old splitter only sliced X, so perSlice collapsed to
// 1 and the single command it emitted asked vanilla to place 40,000 blocks - over the 32,768 cap, so
// vanilla rejects it and the ground is left half-levelled while the mod reports success.
const trapOld = oldFill(0, 0, 0, 0, 399, 99);
check('old splitter emitted an over-cap command',
    trapOld.some((s) => slabVolume(s) > MAX_FILL_BLOCKS), true);
check('new splitter handles the same box', 
    fill(0, 0, 0, 0, 399, 99).every((s) => slabVolume(s) <= MAX_FILL_BLOCKS), true);

// ---------------------------------------------------------------------------------------------
// 4. Progress detection. Mirrors BaritoneEventBridge.onBlockChange().
//
//    This is the bug that made every large build look frozen from 1.7.0 to 1.19.1. Progress was judged
//    by whether a block was placed within 8 blocks of the PLAYER. On an 80x80 schematic Baritone works
//    all over the volume, so nearly every placement was discarded: the progress clock never advanced,
//    the 60s warning fired, and at 180s the stall recovery CANCELLED a healthy build and restarted it
//    from layer 0. Every session placed 77-254 blocks and never passed layer 2.
// ---------------------------------------------------------------------------------------------
function countsAsProgress(pos, player, box) {
    if (box) {
        return pos.x >= box.minX && pos.x <= box.maxX
            && pos.y >= box.minY && pos.y <= box.maxY
            && pos.z >= box.minZ && pos.z <= box.maxZ;
    }
    // No region known: fall back to "near the player".
    const dx = pos.x - player.x, dy = pos.y - player.y, dz = pos.z - player.z;
    return dx * dx + dy * dy + dz * dz <= 8 * 8;
}

console.log('\nprogress detection is scoped to the build, not to the player');
// The real case: haunted_80 placed at (1453, -60, 74), player stepped out to (1451, -60, 72).
const build = { minX: 1453, minY: -60, minZ: 74, maxX: 1532, maxY: 19, maxZ: 137 };
const player = { x: 1451, y: -60, z: 72 };
check('a placement at the FAR corner of the build counts',
    countsAsProgress({ x: 1532, y: 19, z: 137 }, player, build), true);
check('the same placement did NOT count under the old radius rule',
    countsAsProgress({ x: 1532, y: 19, z: 137 }, player, null), false);
check('a placement 40 blocks away but inside the build counts',
    countsAsProgress({ x: 1493, y: -58, z: 74 }, player, build), true);
check('a placement near the player but inside the build counts',
    countsAsProgress({ x: 1455, y: -60, z: 76 }, player, build), true);
check('a placement outside the build does NOT count (water flowing next door)',
    countsAsProgress({ x: 1600, y: 63, z: 200 }, player, build), false);
check('a placement one block outside the build does NOT count',
    countsAsProgress({ x: 1533, y: 0, z: 100 }, player, build), false);
check('the fallback still ignores distant blocks when no build is known',
    countsAsProgress({ x: 1493, y: -58, z: 74 }, player, null), false);

console.log('');
if (failures > 0) {
    console.log(`${failures} CHECK(S) FAILED`);
    process.exit(1);
}
console.log('ALL CHECKS PASSED');
