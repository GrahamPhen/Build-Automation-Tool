# Open problem: Baritone cannot build this schematic without help

**Status: UNRESOLVED.** Written 2026-09-23 against `startbuild 1.36.0`, Baritone `baritone-api-fabric-1.19.0`
(MC 26.2), Litematica `0.28.8`, schematic `haunted_80.litematic` (80×80×64, 12,100 blocks, 110 types).

---

## 1. Hard requirements (from the project owner)

1. **Nothing may be pre-placed.** Every individual block must be placed by the player character.
   Blocks placed with `/setblock` appear from nowhere and read as fake on camera.
2. **No stalls.** The build must run to completion unattended.
3. **No skipped blocks.** The finished build must match the schematic.
4. The run is recorded with Flashback to make a YouTube Short of the character building.

Requirement 1 rules out the `/setblock` pre-pass that versions 1.32.0–1.35.0 used (which placed up to
5,037 cells up front). It was removed in 1.36.0. That workaround is **no longer available.**

---

## 2. Measured current state

Run of 1.36.0, build origin `(-353, -60, 4)`:

| layer | required | placed | missing |
|---|---|---|---|
| 0 | 3317 | **3317** | 0 |
| 1 | 249 | 153 | 96 |
| 2–63 | 8861 | 0 | 8861 |

**Total 3470 / 12100 = 28.7 %. Wrong block present: 0.** Nothing was pre-placed.

Log sequence (`logs/latest.log`):

```
18:07:52  [Baritone] Starting layer 0
18:22:41  [Baritone] Missing materials for at least: 1x Block{minecraft:red_concrete}   -> restocked
18:22:47  [Baritone] Starting layer 1
18:24:16  Baritone is active but has placed nothing for 60s - it looks stuck.
18:26:16  Baritone has placed nothing for 180s. Restarting the build (attempt 1 of 3).
18:26:57  [Baritone] Missing materials: 1x acacia_log[axis=x] / 5x deepslate[axis=x]     -> restocked
18:28:03  Baritone is active but has placed nothing for 60s - it looks stuck.
```

**The build cannot get past layer 1.** Layer 0 is always 100 % complete (it has no floating cells and no
orientation problems) — that is the control which isolates both causes below.

---

## 3. Blocker A — floating cells have no clickable face, so the goal is unreachable

**4,733 of the 12,100 cells have no solid block directly below them** (`count-floating.mjs`). Every layer
has them (L1 95, L2 113, L10 659, L11 628, …).

Baritone's `BuilderProcess.placementGoal()` picks the position the character must stand at:

```java
for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
    if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(...)) {
        return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
    }
}
return new GoalPlace(pos);        // = GoalBlock(placeAt.above()) -> "stand ON TOP of this cell"
```

A floating cell has nothing below to click, and its horizontal neighbours are in the **same layer** and are
not placed yet. So no branch matches and Baritone returns `GoalPlace` — **"stand on top of this cell"**, a
position in mid-air that a walking character can never reach. The character walks at nothing indefinitely.
This is what the owner sees as **"stuck in an invisible spot."**

Because `recalc()` never finds zero incorrect positions, the layer never closes and the run stalls.

Measured on layer 1 at origin `(-353,-60,4)`: of the 96 missing cells, **44 have a solid block below
(placeable) and 52 have AIR below (no clickable face)** — `missing-geometry.mjs`.

---

## 4. Blocker B — Baritone's goal gate can only match an item's UPWARD-click state

`BuilderProcess.assemble()` decides which cells get a goal:

```java
} else if (containsBlockState(approxPlaceable, desired)) {
    placeable.add(pos);
} else {
    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
}
```

`approxPlaceable()` builds those candidate states by calling `getStateForPlacement` with **`Direction.UP`**:

```java
BlockState itemState = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(
        new BlockPlaceContext(new UseOnContext(..., new BlockHitResult(..., Direction.UP, ctx.playerFeet(), false)) {})
);
```

For any pillar/log/wood item an upward click yields **`axis=y`**. So an `axis=x` cell can never match, is
filed as "missing materials" **even while the player is holding the block**, and never gets a goal. When
only such cells remain, `assemble()` returns null twice and Baritone logs
**"Unable to do it. Pausing. resume to resume, cancel to cancel"** and sets `paused = true`.

`sameBlockstate()` is the switch that governs this match, and it ignores `ORIENTATION_PROPS`
(`RotatedPillarBlock.AXIS`, `PipeBlock.*`, `StairBlock.*`, `TrapDoorBlock.*`) when
`buildIgnoreDirection` is true.

### The catch: turning that switch on trades one failure for another

| `buildIgnoreDirection` | result |
|---|---|
| `false` (strict) | `axis=x` cells never get a goal → "Unable to do it. Pausing." → **stall** |
| `true` (1.36.0 forces this) | gate passes and the character **does** place them — but with the **wrong orientation**, because the same loosened comparison also makes the placement/correctness check accept any axis |

Measured with 1.36.0 (`buildIgnoreDirection=true`), layer 1's 15 oriented cells:

```
want                                  world
minecraft:deepslate[axis=x]           minecraft:deepslate[axis=z]
minecraft:polished_basalt[axis=x]     minecraft:polished_basalt[axis=y]
minecraft:acacia_log[axis=x]          minecraft:acacia_log[axis=y]
minecraft:basalt[axis=x]              minecraft:basalt[axis=y]
...  0 exact matches, 11 wrong state, 4 still air
```

So the switch **does** unlock those cells (layer 1 went from 118 to 153 placed between 1.34.0 and 1.36.0),
but 1,081 oriented cells across the schematic would end up with the wrong grain — and because `valid()`
uses the same loosened comparison, Baritone marks the wrong orientation **correct** and never revisits it.

**Exact orientation and "placed by the character" are mutually exclusive with stock Baritone's gate.**

---

## 5. What has been tried, and why each failed

| version | change | outcome |
|---|---|---|
| 1.30.0 | `/item replace` restock (full-inventory deadlock) | real fix, kept |
| 1.31.0 | world-sampled progress (false-stall fix) | real fix, kept |
| 1.32.0 | pre-place unclickable + non-default cells (1,181) | reached layer 2 first time, then stalled |
| 1.33.0 | stall recovery re-runs the finishing pass | no effect on the real cause |
| 1.34.0 | repair pass; force `buildIgnoreDirection` **off** | wrong direction — measured 0 improvement |
| 1.35.0 | pre-place floating cells too (5,037) | would have worked, but **rejected: looks fake** |
| 1.36.0 | remove all setblock; force `buildIgnoreDirection` **on** | oriented cells now place (wrong axis); layer 1 still cannot close (52 floating cells, Blocker A) |

Earlier diagnoses that blamed the `acacia_log[axis=x]` cell alone were **wrong** — that cell was a symptom.
With it verified exactly correct, the build still stalled.

---

## 6. What a fix requires

Blocker A and Blocker B both live in `BuilderProcess`, and neither can be solved from outside with
settings alone. A real fix needs a **patched/forked Baritone** (or a different builder) that:

1. **Gives floating cells a reachable, neighbour-anchored goal.** Either defer a cell until a horizontal
   neighbour exists (instead of returning `GoalPlace`, which is mid-air), or build the
   gravity-supported cells of a layer first so the floating ones acquire an anchor.
2. **Separates the goal gate from the placement check.** The gate should accept a desired state that
   differs *only* in orientation (so Baritone walks the character there and plans the placement), while
   `possibleToPlace()`/`hasAnyItemThatWouldPlace()` keep requiring an **exact** match — that path already
   computes the real `wouldBePlaced` state per click direction, so it can place the correct `axis=x`
   when a face that yields it is reachable.
3. **Keeps `valid()` strict for correctness**, so a wrongly-oriented block still counts as incorrect and
   is retried rather than silently accepted (Baritone already has an opportunistic per-tick placement
   scan, `searchForPlacables()`, which honours the exact match — it just needs the character to be
   within 5 blocks, which requirement 1 makes reachable only if the goal gate lets Baritone walk there).

Any alternative approach must satisfy all four requirements in section 1 — in particular, no `/setblock`.

---

## 7. Reproducing the measurement

```powershell
# 1. read the build origin from the log
#    [StartBuild] build region: (x, y, z) to (...)      and  footprint: 80x80x64 at (x, y, z) to (...)
$region = "C:\Users\Graham\AppData\Roaming\PrismLauncher\instances\BuildRecording\minecraft\saves\New World (1)\dimensions\minecraft\overworld\region"
$schem  = "C:\Users\Graham\AppData\Roaming\PrismLauncher\instances\BuildRecording\minecraft\schematics\haunted_80.litematic"

# overall fidelity + per-layer progress (world vs schematic)
node tools/cottage/verify-build.mjs   $schem $region <ox> <oy> <oz>
# full-state comparison of oriented cells in one layer (verify-build compares names only)
node tools/cottage/axis-cells.mjs     $schem $region <ox> <oy> <oz> <layer>
# what the missing cells look like: is there a block below them, and are neighbours placed?
node tools/cottage/missing-geometry.mjs $schem $region <ox> <oy> <oz> <layer>
# how many cells the schematic has with no block below / a non-default orientation
node tools/cottage/count-floating.mjs $schem
```

> Measure the **world and the server log**, not the mod's own counters or Baritone's self-reports.
> Both have repeatedly reported success while the world was unchanged.

---

## 8. Where the code lives

| what | path |
|---|---|
| mod source | `src/main/java/com/graham/startbuild/` |
| the two files that matter for this problem | `StartBuildSession.java` (build flow), `BaritoneBridge.java` (settings/bridge) |
| diagnostic tools | `tools/cottage/*.mjs` |
| Baritone source used as evidence | [`baritone/process/BuilderProcess.java`](https://raw.githubusercontent.com/cabaletta/baritone/1.21.4/src/main/java/baritone/process/BuilderProcess.java) |
