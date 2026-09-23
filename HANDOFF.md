# HANDOFF — Build Automation Tool

> **Purpose of this document:** let a fresh AI agent (or human) pick up this repo with zero prior
> conversation and understand, in a few minutes: what this is, where every file is, what the code does,
> what has already been learned the hard way, what is verified vs not, and how to test a change.
>
> Last updated: 2026-09-23. Mod version: **1.29.0**.

---

## 1. What this is

A **client-side Fabric mod** (plus a set of offline Node tooling) that automates building a Minecraft
schematic so it can be recorded as a YouTube Short. One in-game command does the whole pipeline:

```
/startbuild place <name.litematic>
```

1. Loads a schematic into **Litematica** and creates a placement.
2. Pre-places any block that a click-based builder can never place (cells with no solid neighbour or whose
   only neighbour is above them).
3. Starts a **Flashback** replay recording.
4. Hands the build to **Baritone**, which walks the player and places blocks block-by-block (no Litematica
   printer — the recording must look like a real, hand-built structure).
5. Auto-restocks materials as Baritone pauses for them.
6. Watches progress, recovers from real stalls, and — when Baritone reports done — runs a verification
   pass that `/setblock`s anything still wrong so the build is 100% complete and correctly oriented.
7. Stops and saves the recording `stopDelaySeconds` after the last block.

**The core design constraint** that drives everything below: Baritone is a **click-based** builder. A block
can only be placed by clicking the face of an existing solid block. This single fact is the root of the two
hardest bugs in this project. Everything else — the state machine, the restock loop, the progress clock —
exists to make that work unattended.

### Environment (pinned, do not "upgrade" casually)

| Thing | Version |
|---|---|
| Minecraft | **26.2** (Java 25, `MinecraftDataVersion` 3955, data pack format **107**, resource major 88) |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.161.0+26.2 |
| Loom (Gradle) | 1.18.2 |
| Gradle | 9.7.1 (wrapper included; JDK 25 required) |
| Litematica | litematica-fabric-26.2-**0.28.8** |
| MaLiLib | malilib-fabric-26.2-**0.29.6** |
| Baritone | **baritone-api-fabric-1.19.0** (NOT the standalone jar — see §8) |
| Flashback | 0.43.4-for-MC26.2 |
| Sodium | 0.9.2+mc26.2 | Iris | 1.11.4+mc26.2 |

MC 26.2 renames `ResourceLocation` to `net.minecraft.resources.Identifier`. Any code referencing
`ResourceLocation` does not compile here.

---

## 2. Repository map

### The mod (repo root — a normal Fabric/Gradle project)

| File | What it is |
|---|---|
| `build.gradle`, `gradle.properties`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Standard Loom build. `gradle.properties` pins the versions in the table above and `version=`. Bump `version=` for each release. |
| `src/main/resources/fabric.mod.json` | Mod metadata + entrypoint (`StartBuildMod`). |
| `src/main/java/com/graham/startbuild/StartBuildMod.java` | Entrypoint. Registers all `/startbuild…`, `/stopbuild`, `/buildstatus`, `/buildprep`, `/buildsel`, `/buildsite` commands; the client tick handler; `runServerCommand`/`runClientCommand` (reflective, name-tolerant); the one-shot bridge self-test. |
| `StartBuildSession.java` | **The heart — ~2600 lines.** The IDLE/PRE_ROLL/BUILDING/COOLDOWN state machine, the build flow, the restock loop, stall detection/recovery, the pre-pass and verification pass. Start here. |
| `StartBuildConfig.java` | Gson config at `config/startbuild.json`, reloaded every run. Every field documented in §6. |
| `BaritoneBridge.java` | **All** Baritone access by reflection (no compile-time dependency). Settings get/set, build dispatch, cancel, selections, schematic box, notify. |
| `BaritoneEventBridge.java` | A `java.lang.reflect.Proxy` listener on Baritone's event bus (block changes) + a `Settings.logger` wrapper (chat capture). Also the missing-materials accumulator. |
| `LitematicaBridge.java` | Reflection into Litematica: `createFromFile`, `createFor`, `addSchematicPlacement`, `sizeOf`, `schematicBlockAt`, `solidGrid`. |
| `FlashbackBridge.java` | Reflection into Flashback: `RECORDER`, `startRecordingReplay`, `finishRecordingReplay`, `cancelRecordingReplay`, quicksave. |
| `SiteFinder.java` | Scores candidate footprints (flatness/clear/fill/water/distance) for `/buildsite` and `/startbuild site`. Read-only. |
| `TerrainPrep.java` | Levels the site with batched `/fill` (clear confined to footprint, fill spans margin). |
| `StockFunctions.java` | Reads the stock datapack `.mcfunction` files to learn the build's block types, most-used-first. |
| `Reflect.java` | Tiny reflection helpers (`find`, `method`, `field`, `describe`). |

### Tests (offline, run with `node`)

| File | Verifies |
|---|---|
| `tests/verify-missing-capture.mjs` | The missing-materials chat capture + full give-command chain against the real datapack. |
| `tests/verify-geometry.mjs` | Footprint math, terrain clear/fill decisions, `/fill` slab caps, **progress-scoping regression** (the 8-block-radius bug). |
| `tests/verify-episode-rule.mjs` | Episode layer boundary + stall-restart gating. |
| `tests/fixtures/`, `tests/staged/`, `tests/make-fixtures.mjs` | Fixtures for `schematic-catalog.mjs` tests. |

Run all three before trusting a change: `node tests/verify-*.mjs`.

### Tools (offline scripts; Node 24+ for `.mjs`, PowerShell 5.1 for `.ps1`)

| Path | What it is |
|---|---|
| `tools/startbuild-launch.ps1` | One double-click: installs the right mod set (hash-verified), refreshes datapacks and the shader pack, then launches the Prism instance. Installs the highest `startbuild-x.y.z.jar` from the repo build dir. |
| `tools/startbuild-check.ps1` | Diagnostics; verdict from StartBuild counters. |
| `tools/startbuild-stop.ps1` | Writes the stop-flag file (clean stop without killing the recording). |
| `tools/install-stock-datapack.ps1` | Installs the stock `give` datapacks into a world. |
| `tools/schematic-catalog.mjs` | Generates the stock datapacks (`pack.mcmeta` + `give`/`clear` batches) from a schematic's block palette; also the schematic library triage tool. |
| `tools/make-shortcut-icon.mjs` | Makes the `.ico`. |
| `tools/cottage/litematic.mjs` | **Self-contained `.litematic` reader/writer** + `parseNbt`. The single source of truth for the `.litematic` binary format (§7). |
| `tools/cottage/build-cottage.mjs` | Generates an example cottage `.litematic` + isometric preview (the `examples/` content). |
| `tools/cottage/verify-build.mjs` | **THE diagnostic.** Diffs the live world against a schematic: per-layer required/correct/missing + top missing types + bounding box. |
| `tools/cottage/map-layer.mjs` | ASCII per-layer map: `#` done, `o` air, `x` wrong, `.` not in schematic. |
| `tools/cottage/layer-types.mjs` | Per-layer block-type counts; which layers a stock batch cannot cover. |
| `tools/cottage/isolated-cells.mjs` | Counts cells with no solid neighbour (the unclickable class). |
| `tools/cottage/neighbour-analysis.mjs` | Counts isolated + only-above cells vs down/horizontal-supported. |
| `tools/cottage/why-stuck.mjs` | Per-layer placed/missing grouped by block state (to test "oriented states" hypotheses). |
| `tools/cottage/analyse-schematic.mjs` | Per-layer block counts + base-layer shape. |
| `tools/cottage/known-blocks.txt` | The 1198 vanilla block-state names, extracted from the 26.2 client jar (validates `build-cottage.mjs`). |

### Examples

`examples/cottage.litematic` (+ materials list) is a small generated build (1208 blocks, 25 types) with
no datapack, so it fits the 36 inventory slots — the clean "does the pipeline work at all" test.

---

## 3. The build flow, step by step (`/startbuild place <name>`)

`StartBuildSession.startFromLitematica`:

1. Validate state/args/Litematica/player/file.
2. `LitematicaBridge.loadSchematic(dir, name)` → the `LitematicaSchematic` object (read once).
3. `sizeOf` → total size; **burial guard** (`buriedProblem`) refuses a base far below the local surface.
4. Store `loadedSchematic` / `loadedSchematicOrigin` / `loadedSchematicSize` (used by the two passes).
5. `place(schematic, origin, name, clearFirst)` → creates + selects the placement; step the player 2
   blocks outside the footprint.
6. **`prePlaceUnbuildableBlocks()`** — see §4.
7. `start(placementIndex)` → config load, pre-flights, `buildInLayers`, **`buildIgnoreDirection=true`**,
   start recording, PRE_ROLL → `beginBuild()` (publishes the build region, dispatches to Baritone).
8. BUILDING: `tickBuilding` runs the watchdogs and progress clock (§5).
9. Baritone "Done building" → `sawBuildActive` transition → **`completeMissingBlocks()`** (§4) → cooldown.
10. Cooldown → finish → recording saved → `reset()` restores Baritone settings.

`/startbuild site <name>` adds SiteFinder + TerrainPrep before placing (picks + levels a spot).
`/startbuild file <name>` builds a v7 `.litematic` straight through Baritone (no placement) and reads the
size through Litematica to step the player clear.

---

## 4. Completeness: how "every block gets placed" is achieved

Baritone cannot place a cell that has no face to click. Two unclickable classes exist in any schematic:

- **Fully isolated** — no solid neighbour in any of the six directions.
- **Only-above** — the sole solid neighbour is *above* the cell (Baritone's goal placement uses
  HORIZONTALS + DOWN, explicitly excluding UP).

For `haunted_80` (the test build: 80×80×64, 12,100 blocks, 110 types): 55 isolated + 55 only-above = 110
unclickable cells; the other 11,990 have a down/horizontal neighbour.

Three mechanisms guarantee a whole build:

1. **`buildIgnoreDirection = true`** (set at start, restored at `reset()`). Baritone derives a placeable
   item's state from an upward-facing click, so a pillar item only ever yields `axis=y`; the schematic asks
   for `axis=x`. Baritone's `assemble()` files every `axis=x` cell as a *missing material* (never a goal)
   unless direction is ignored. Without this, **not one of the 1081 axis cells is ever placed and layer 1
   can never close** — the primary historical bug. See §8.
2. **`prePlaceUnbuildableBlocks()`** — before the recording and Baritone start, reads the whole schematic
   into a solidity grid (`LitematicaBridge.solidGrid`) and `/setblock`s every cell with no down/horizontal
   solid neighbour. This is what lets every layer close naturally, so **nothing is ever skipped**.
3. **`completeMissingBlocks()`** — after "Done building" and before the recording stops, walks all cells,
   compares world vs schematic by exact state, and `/setblock`s anything wrong (wrong orientation, an
   un-reached neighbour, anything). Normally finds zero; it is the guarantee.

`skipFailedLayers` is **deliberately not used** (Graham's hard requirement: no block may be skipped).

---

## 5. The progress clock and stall recovery

The project's single most repeated mistake was trusting Baritone's self-reporting. Concretely:

- Baritone's `BlockChangeEvent` under-reports by ~15× (one measured run: 123 events for 3424 blocks
  actually placed). A progress clock built on it fired a false "stalled" every 180 s and **cancelled a
  working build**, resetting Baritone's layer counter to 0 each time.
- The fix: progress is sampled from the **world** (`sampleBuildProgress`, stride 2, adaptive, every 5 s —
  counts non-air blocks in the build volume; growth = progress) **plus** Baritone's **layer counter**
  (`currentLayer()`), which only advances when a layer truly closes and therefore cannot lie.

Stall detection uses two clocks: restart when nothing has moved at all for `stallRecoverSeconds`, or
nothing placed for `stallRecoverSeconds × 4` while moving. Restart first confirms `cancelBuild()` and
moves the player out of the footprint (`movePlayerOutOfBuild`), and does not re-anchor the watchdog on an
unconfirmed restart.

---

## 6. Configuration (`config/startbuild.json`, written at runtime)

Load-bearing fields (all in `StartBuildConfig`):

| Field | Meaning / default |
|---|---|
| `stopDelaySeconds` (30) | Recording continues this long after the last block. |
| `startRecording` / `finishRecording` | Whether the mod starts/stops Flashback. |
| `litematicaPlacement` (1) / `schematicFile` | Which target to build (placement index, or a file name). |
| `autoStockFunctions` | List of `namespace:function` batch functions, most-used types first. |
| `autoStockAtStart` (true) | Feed batch 1 before building. |
| `directRestock` (true) | Give missing types on demand instead of feeding batches mid-build. |
| `restockGiveCount` (64) | Stack size per give. |
| `buildInLayers` (true) | Layer-by-layer (the big reliability lever). |
| `autoSelection` ("full") | Auto Baritone selection: full / layers / corner / none. |
| `autoSite` (true) / `terraform` (true) / `siteRadius` (128) | `/startbuild site` only. |
| `stallRecoverSeconds` (180) / `maxStallRecoveries` (3) | Stall restart cadence. |
| `noProgressMinutes` (30) / `maxBuildMinutes` (360) | Hard stop backstops. |
| `warnIfNoPlacementSeconds` (60) | "looks stuck" chat warning. |
| `diskWatchdog` / `minFreeDiskGB` (20) | Stop before the drive fills. |
| `stopFileEnabled` / `stopFileName` | Flag file to end a run cleanly. |

**Never hand-edit Baritone's own settings file** — use `/buildprep` (and the mod sets/restores what it
needs itself).

---

## 7. The `.litematic` binary format (verified against 3 real files)

`tools/cottage/litematic.mjs` is the authoritative reader/writer. Schema, read from real files:

- Gzip-compressed NBT. Root name `"Litematic"`.
- `MinecraftDataVersion` (int, 3955), `Version` (int, 7), `Metadata` (compound with `Name`, `Author`,
  `TotalBlocks`, `TotalVolume`, `EnclosingSize{x,y,z}`, `TimeCreated/Modified`), `Regions` (compound).
- Region: `Position{x,y,z}`, `Size{x,y,z}` (may be negative), `BlockStatePalette` (list of
  `{Name, Properties?}`), `BlockStates` (long array), and empty `TileEntities`/`Entities`/`PendingBlockTicks`/
  `PendingFluidTicks`.
- `bits = max(2, ceil(log2(palette.length)))`; `longs = ceil(volume × bits / 64)`.
- Indices form ONE continuous little-endian bitstream (crosses long boundaries). Iteration order inside a
  region is **x fastest, then z, then y**: `index = (y*sizeZ + z)*sizeX + x`.
- `selfTest()` repacks real files byte-for-byte (proves the packing matches Litematica).

`verify-build.mjs` / `map-layer.mjs` read the vanilla world (region `.mca` → chunk NBT → sections with
`block_states{palette, data}`; signed byte `Y`; bits `max(4, ceil(log2(palette)))`, no cross-long packing,
cell order `(y<<8)|(z<<4)|x`) — so "is the build complete" can always be measured against ground truth.

---

## 8. Baritone internals that matter (learned the hard way)

- **The jar is ProGuard-obfuscated.** Only `baritone.api.*` and a few `baritone.utils.*` helper names are
  readable. `IBuilderProcess` is the API surface; `BuilderProcess` is obfuscated. Use the API, never guess
  at internal class names.
- **`baritone-api-fabric`**, not the standalone jar. The standalone is obfuscated and does not expose
  `baritone.api.*` by name, which would break all of this reflection.
- **`IBuilderProcess.buildOpenLitematic(int)` returns `void`** (verified with `javap`) — it cannot report
  refusal. `build(String, File, Vec3i)` returns `boolean`.
- **`approxPlaceable()`** derives each item's placeable state from an upward-facing click → `axis=y`. This
  is the root of the axis deadlock (§4). `buildIgnoreDirection=true` is the fix; the verification pass
  corrects any resulting wrong orientation.
- **Layer advancement** (`onTick`): `layer++` only when `recalc()` finds zero incorrect positions in the
  window. `getMinLayer()` is that counter. So `layer=2/80` means "layers 0 and 1 are considered finished",
  not "building layer 2".
- **Missing materials are only printed when nothing at all is placeable** (`assemble()` returns a goal as
  soon as any placeable block exists). That is why the axis cells were silent, not reported.
- **Settings exist** (verified `javap`): `buildIgnoreDirection`, `buildIgnoreProperties`, `skipFailedLayers`,
  `distanceTrim`, `builderTickScanRadius`, `buildInLayers`, `layerOrder`, `layerHeight`, `startAtLayer`,
  `buildOnlySelection`, `buildIgnoreExisting`, `buildIgnoreBlocks`, `buildValidSubstitutes`, `allowInventory`.
  The mod only touches `buildInLayers`, `buildIgnoreDirection`, `startAtLayer`, `buildOnlySelection`,
  `allowInventory`, `logger`, plus the render settings.

---

## 9. Bug history — the signatures and their fixes (do not re-derive)

Each is a class of failure, with the tell-tale log line and the fix.

| # | Symptom (log/world) | Root cause | Fix |
|---|---|---|---|
| 1 | "Starting layer 0/1" then forever; **0 of 1081 `axis=x` cells ever placed; "Starting layer 2" never appears** | Baritone files `axis=x` as missing material; layer 1 can never close | `buildIgnoreDirection=true` |
| 2 | Build stops mid-layer; ~55 cells missing at the end | Cells with no solid neighbour are unclickable | `prePlaceUnbuildableBlocks` (/setblock) |
| 3 | `only-above` cells also missing | goal placement excludes UP | widened pre-pass criterion to "no down/horizontal neighbour" |
| 4 | "active but placed nothing for 60s" → cancel → restart, on a build that was working | progress clock on `BlockChangeEvent` (123 events vs 3424 blocks) | world sampling + layer counter |
| 5 | "Gave 64 [X]" logged, then "never arrived", `given=1` | `/give` into a full inventory drops items; only one slot freed per batch | `makeRoomFor` before the batch; top-up present-but-requested types |
| 6 | Missing list captured as ONE type | Baritone logs one message per missing type | accumulator in `observe()` |
| 7 | `pack.mcmeta` "newer than 81 … missing min_format/max_format" every launch | `supported_formats.max_inclusive: 9999` claims a future version | removed the range; `pack_format: 107` is exact |
| 8 | Build placed at y=-60, seemingly "buried" | **Not a bug.** The world is superflat (ground y=-61). The burial guard compares against the real surface, so it correctly does NOT fire. |
| 9 |   free inventory slots + Gave 64 [X] + 
ever arrived while Baritone re-pauses | clear+give raced the CLIENT's inventory view (both async, same tick), so every give landed on a full inventory and dropped | /item replace entity @s container.N with ... (atomic, server-side, no free-slot logic) |

**The recurring meta-lesson:** every wrong diagnosis came from trusting Baritone's or the mod's own
self-reporting. The world files and the server-side log are the only two signals that have never lied.
`verify-build.mjs` exists precisely so "why is it stuck" is measured, not argued.

---

## 10. What is proven vs not (state as of 1.29.0)

**Proven (measurement + source):**
- The axis deadlock and its fix (Baritone 1.19.0 source + 0/1081 axis cells measured + "Starting layer 2"
  absent from 14 logs).
- 110 unclickable cells (55 isolated + 55 only-above) and that the rest have down/horizontal support.
- The reflection accessors exist (`javap` against the real jars).
- The `.litematic` and world formats (byte-exact round-trips).

**Not yet proven (needs one real run, cannot be produced statically):**
- That Baritone actually reaches "Done building" (so the verification pass runs) now that the axis cells
  are buildable and the 110 are pre-placed.
- Wall-clock build rate (~0.15 blocks/s on layer 1 was never fully explained).

**The one residual risk:** if some cell class the pre-pass does not classify still blocks a layer, Baritone
logs **`Unable to do it. Pausing.`** and the run ends via stall-recovery give-up *without* the verification
pass, leaving the build short. If that line appears, the fix is to widen the pre-pass (or re-add
`skipFailedLayers` purely as deadlock-safety with the verification pass still guaranteeing no gap).

---

## 11. How to build, install, and run

Build:
```
./gradlew build          # output: build/libs/startbuild-<version>.jar
node tests/verify-missing-capture.mjs && node tests/verify-geometry.mjs && node tests/verify-episode-rule.mjs
```

Install + run (the supported path):
```
powershell -ExecutionPolicy Bypass -File tools/startbuild-launch.ps1          # sets up + launches Prism
# in game:  /startbuild place <name.litematic>
#           /buildstatus   /stopbuild
```

**Standing gotchas:**
- The launcher only updates the installed jar while **Minecraft is closed** (a running game holds the old
  jar; several "same failure again" reports were the old jar still loaded).
- Stop with `/stopbuild` or the stop-flag file — never kill `javaw` (discards the recording).
- Schematics live in the instance's `schematics/` folder. Stock datapacks are generated by
  `tools/schematic-catalog.mjs` and copied by the launcher from `Desktop\litematic\_stock\`.
- Do not use the Litematica printer (the recording must look hand-built), and do not hand-edit Baritone's
  settings file.

---

## 12. Where to start if you have to change something

- **"The build is stuck"** → first run `node tools/cottage/verify-build.mjs <schematic> <regionDir> <ox> <oy> <oz>`
  (region dir: `…\saves\<world>\dimensions\minecraft\overworld\region`). Measure, then read
  `logs/latest.log` for `Starting layer N`, `Unable to do it. Pausing.`, `Completed N block(s)`,
  `Pre-placed N`, `never arrived`, `delivered NONE`.
- **Materials** → `StartBuildSession.supplyMissingTypesDirectly` / `makeRoomFor` / `verifyPendingGives`;
  capture in `BaritoneEventBridge.observe`.
- **Progress/stall** → `StartBuildSession.sampleWorldProgress` / `tickBuilding`; region in
  `BaritoneEventBridge.setBuildRegion`.
- **Schematic reading** → `LitematicaBridge.schematicBlockAt` / `solidGrid` (the accessor chain is in the
  javadoc there).
- **A new reflection call** → verify the exact signature with
  `javap -p -cp <jar> <fully.qualified.Class>` against the real jar in the instance `mods/` before using it.
