# HANDOFF — Build Automation Tool (StartBuild 2.9.x)

> Read this first. Last updated 2026-09-24, mod version **2.9.3**, branch `natural-builder-2.0`.
> The Baritone-era docs (1.x, why Baritone could not do this) are archived in `docs/`.

## 1. What it is

A client-side Fabric mod (MC 26.2, Java 25) that records a Minecraft character building a schematic
**by hand** for YouTube Shorts / TikTok / Reels timelapses. One command starts Flashback, the character
flies around and places every block with a real click (look, swing, block appears), and the recording
stops 30 s after the last block.

Owner's hard requirements: looks like a real build (no printer, no `/setblock` of build blocks, no mod
overlays on camera); no skipped blocks; runs unattended; site prep is done by the character on camera and the ground is terraformed to blend in (never `/fill`ed).

## 2. How a build runs (`NaturalSession` + `NaturalBuilder`)

1. `/startbuild place <name>` (corner = your feet, build extends +X/+Z), or `/previewbuild` / `/findsite`
   then `/startbuild confirm`.
2. **Before recording** (`NaturalSession.tickPreparing`): world made camera-ready (gamerules: no command
   feedback, no mobs/weather/daylight cycle/random ticks/fire spread, `block_drops false`; peaceful; noon);
   chunks awaited; Litematica placements removed so no ghost is recorded; **terraform plan** computed
   (`Terraformer.plan`, read-only — nothing is changed yet).
3. Flashback recording starts (`FlashbackBridge`).
4. **Terraforming, by hand, on camera** (2.6.0; tree by tree since 2.7.0), then the build — a list of
   `NaturalBuilder` stages (`Terraformer.Part`):
   - one FELL stage per tree in the way (top-down, `clearing=true`: AIR cells are broken with a fitting
     tool in hand): trunk + exactly the leaves vanilla would let decay + vines/cocoa/nests; giant mushrooms too;
   - DIG (top-down): the hillside above the new ground, plants, everything in the build volume;
   - FILL (bottom-up): earth up to the new ground, and a fresh top block (the site's most common surface —
     grass/sand/...) on every changed column, plus a snow layer in snowy sites;
   - BUILD.
   New ground height (2.9.0, `Terraformer.targetHeights`): flat only where the build STANDS (its bottom two
   layers), not the bounding box - gardens/gaps/corners are shaped like the land, kept under any part of the
   build overhead (`cap`); from there a smoothstep curve eases back to natural height over `terraformRadius`
   (12, grows to 24 so the average edge slope is <= 0.7), with value noise. Each changed column keeps its own
   top block / block below / snow; the banks are replanted by hand with the plants sampled on the untouched
   land around (same mix and density: grass, ferns, flowers, dry bushes). Water columns untouched; cuts
   never go below adjacent water.
5. `NaturalBuilder` (creative, flying) places blocks bottom-up:
   - picks the nearest cell that has something to click against right now;
   - solves the click that yields the EXACT state (`getStateForPlacement` simulation — gets `axis=x` logs,
     stairs, slabs right), then verifies and retries;
   - cells with nothing to click: temporary support block(s) → real block → break support;
   - two-step blocks: farmland (dirt + hoe), dirt path (dirt + shovel), potted plants (pot + plant),
     crops (seed + bone meal), water (bucket), nether portal (obsidian frame + flint & steel);
   - blocks come from the creative inventory (pick-block style); never waits on anything — a stuck cell
     is parked and retried later; final pass re-checks the whole build against the world;
   - flying (2.6.1): straight if clear, else A* through the air (round walls, out through doors); if shut
     in with no way round, the path goes through the fewest blocks and the first is broken ("shut in:
     breaking ..." in the log) — a build block broken that way is parked and rebuilt later. Unreachable
     temporary blocks are retried after a minute instead of every tick (that stalled a 2.5.1 take at
     10,041/15,248 under the roof for 27+ min);
   - watchdog (`NaturalSession.watchdog`): 3 min without progress → replan everything; again → teleport to
     open air above the work and replan; again → give up the rest of that stage and carry on (logged).
6. Stops `stopDelaySeconds` after the last block, adds a Flashback marker, saves the replay. A take in
   which nothing of the build was placed (aborted, or /stopbuild during terraforming) is DISCARDED
   (`FlashbackBridge.cancelRecording`), never saved.

Speed (2.8.0): phases that finish at once run on in the same tick and clicks are paced at most one per 4
ticks (5/s, vanilla's right-click rate) instead of fixed waits; long flights at sprint-fly speed (1.0/tick);
only trees whose TRUNK stands where the ground changes are felled; `/findsite` ranks sites by the
earthworks around them (`ringEarthworks`) and weights trees/rocks inside x8 (shortlist 40). Optional
`gameSpeed` (1 = off, max 4) runs `/tick rate 20*speed` during a take - off until it is verified that
Flashback still plays the replay at normal speed.

Safety nets (2.7.0): pause-safe (a paused game is not counted; `pauseOnLostFocus` is switched off during a
take and restored after); every tick is guarded (an exception is logged, 40 in a row end the take); a take
refuses to start / stops and saves under `minFreeDiskGB`, and stops after `maxBuildMinutes` (600); the
file `config/startbuild-stop` stops and saves (`tools/startbuild-stop.ps1`); the Litematica ghost must be
removed or the take does not start; Flashback must really be recording after the pre-roll.

Progress goes to `logs/latest.log` only (never chat — chat is on camera):
`[StartBuild] progress: <stage> - N placed, B broken, M left, layer L, T min, last problem: ...`
(`tools/startbuild-check.ps1` summarises a run from the log.)

## 3. Commands

| Command | Does |
|---|---|
| `/findsite <name> [wish]` | most natural spot (wish: "near a lake/river", "on a hill", default flat; plus an optional biome: snowy, desert, cherry, jungle, taiga, badlands, swamp, ... — never ocean/river biomes), show ghost there, teleport to a viewpoint. First run searches around you; running it again for the same schematic flies 600-900 blocks away in a new direction. A biome wish flies to the nearest unused patch of that biome (server `findClosestBiome3d`). Sites already shown are never offered again; sites with water above the base right beside them are rejected |
| `/findsite next` | next of up to 5 distinct spots; after the last one it explores a new area |
| `/startbuild confirm` | build exactly where the preview/findsite put it |
| `/startbuild place <name>` | build with the corner at your feet |
| `/startbuild auto [name] [wish]` | fully hands-free: findsite → auto-confirm the best spot (tries up to `autoSiteAttempts` areas) → terraform → build → save. Same as the desktop launcher's `-Build <name> [-Wish ...]` (autorun file `config/startbuild-autorun` = "name wish") |
| `/previewbuild <name>` / `/previewbuild off` | Litematica ghost at your feet + report / hide |
| `/stopbuild` | stop and save (or cancel a hands-free site search) |
| `/buildstatus` | stage and progress |

Known limits: preview cannot be rotated/mirrored; nudging the Litematica ghost does **not** move the build.

## 4. Files

`src/main/java/com/graham/startbuild/`
| File | Role |
|---|---|
| `NaturalSession.java` | the take: commands, preview/findsite/auto, world settings, stages, watchdog, recording, safety nets |
| `NaturalBuilder.java` | the hand builder (planning, click solving, line of sight, flying + A* routing, dig-out, two-step blocks, scaffolds); `clearing` mode breaks the model's AIR cells top-down |
| `FlightPath.java` | pure A* over feet cells (no MC types; unit-tested) |
| `Terraformer.java` | plans the on-camera terraforming: one part per tree (felled whole), dig, fill; `targetHeights` is the pure (unit-tested) new-ground maths |
| `PlacementFinder.java` | `/findsite` scoring, biome words, `loaded()` (real chunk data — `ClientLevel.hasChunk` is always true in 26.2) |
| `SchematicModel.java` | reads a schematic through Litematica into a dense BlockState grid (no placement) |
| `LitematicaBridge.java`, `FlashbackBridge.java`, `Reflect.java` | reflection bridges |
| `StartBuildMod.java` | command registration, client tick, self-test (Litematica/Flashback) |
| `StartBuildConfig.java` | `config/startbuild.json` (reloaded each run; old 1.x keys are ignored) |

The 1.x Baritone flow (StartBuildSession, Baritone*, SiteFinder, TerrainPrep, StockFunctions — ~4,000 lines)
was deleted in 2.7.0; it is in git history. Baritone is not needed any more.

Config fields: `ticksPerBlock` (0 = fastest, set to 0 on the owner's PC), `prepTerrain`, `terraformRadius`
(12, grows to 24 on steep sites), `gameSpeed` (1 = off), `stopDelaySeconds` (30), `videoDaylight`, `startRecording`/`finishRecording`,
`forceQuicksave` (true — must be on unattended), `minFreeDiskGB` (5), `maxBuildMinutes` (600),
`autoRunSchematic`/`autoRunWish`/`autoSiteAttempts` (8), `minSiteSpacing` (450), `stopFileName`, `desktopNotification`.

`tools/picture2schem/`
- `picture2schem.mjs` — picture → JSON build plan (Claude API, model `claude-opus-5-5`, key in
  `anthropic-key.txt` next to it, git-ignored) → `.litematic`. `--spec plan.json` rebuilds without the AI.
- `picture-to-schematic.bat` — drag a picture onto it. `scenes/spawn_plaza.mjs` — hand-scripted scene.
- `shrink.mjs` — scale a schematic down by an integer factor.
`tools/cottage/` — `.litematic` reader/writer (`litematic.mjs`), `verify-build.mjs` and a few diff tools.
`tools/startbuild-launch.ps1` / `-check.ps1` / `-stop.ps1` — launcher (desktop icon), log summary, stop.

## 5. Build, install, test (Windows)

- Build from this repo: `.\gradlew.bat build` → `build\libs\startbuild-x.y.z.jar` (also runs the unit
  tests; `.\gradlew.bat test` alone). Bump `version=` in `gradle.properties` every change.
- The staging copy `C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922` must stay
  identical (tracked files mirrored, deleted files deleted); its `build-now.bat` also builds there.
- Desktop icon "Minecraft - Build Recording" → `MineSurvive\tools\startbuild-launch.ps1` (identical copy of
  `tools\startbuild-launch.ps1`): installs the highest `startbuild-x.y.z.jar` from both build folders into
  the Prism instance `BuildRecording` and launches. `-Build <name> [-Wish "..."]` makes it hands-free.
- The jar only updates while Minecraft is closed.
- Test world: `saves\Video Building` (normal terrain, creative, cheats on).
- Schematics: `...\instances\BuildRecording\minecraft\schematics\` (haunted_80, whimsical_halloween_80/_40,
  storybook_cottage, spawn_plaza, cottage).

## 6. State and open items (2026-09-24)

Proven live: 2.0 built haunted_80 past layer 1 (where every Baritone version stalled) at ~150 blocks/min;
the Halloween 80 build ran at ~110 blocks/min; a 2.5.1 `/findsite` site near (-656, 85, -212) was built on
to 10,041/15,248 blocks, then stalled shut in under its roof (fixed in 2.6.1: A* + dig-out + watchdog).

**First complete take (2026-09-24, 2.9.0):** whimsical_halloween_80 at (-1281, 63, -285), snowy taiga lake
shore: 20 trees felled whole, 6,100 dug, 1,385 filled/replanted (37 min), then all 15,249 blocks placed by
hand (94 min, ~240/min low, ~145/min high), 131 min total, recording saved; no stalls/watchdog/errors,
1 dig-out (2 blocks, rebuilt). Measured from the save (`site-relief.mjs` + a ring check): ground 2-3 blocks
out from the build is within 1 block of its base for 98-99% of columns, easing up to the hill further out.
Loose ends found and fixed since: 20 temporary blocks left (14 walled in - now skipped, 6 visible - more
cleanup passes), 1 sideways jungle_log not placed, 5 leaves inside a kept canopy, several 20 s
"could not fly" waits (2.9.1), bounding box over a frozen lake (2.9.2: plain ice = water), desktop
notification never shown (2.9.3: PowerShell balloon), /startbuild place while flying floated (2.9.3: base
snapped to the median ground).

Not yet run in game (compiled + unit-tested only): 2.9.1-2.9.3 fixes; earlier items still unobserved live: — on-camera terraforming
(tree by tree), A* routing / dig-out, watchdog, hands-free auto, discard of empty takes, stop file,
line-of-sight placement. Watch the log for `terraform plan:`, `felling tree`, `digging`/`filling done`,
`shut in:`, `watchdog:`, `hands-free:`. Also unverified: portal lighting, water bucket placement.

**Overnight run (2026-09-24/25, 2.14.4 → 2.15.3), unattended:** haunted_80 complete (12,100, 134 min,
24 visible supports left → fixed 2.15.1); storybook_cottage 78% (garden: petals blocked hoeing → 2.15.2,
water poured early flooded the garden → 2.15.3); pumpkin_castle_80 complete (29,833, 283 min, 7 supports
left, all tucked in 1-open-side nooks); halloween_80 re-queued from a 218-tree forest hill (~9 h of
terraforming) to plains. Proven live: queue chaining, two scripted restarts, site spacing, empty-take
discard, the busy-loop watchdog.

Unattended nights:
- `config/startbuild-queue.txt` — one `name [wish]` per line; popped 30 s after a take ends (while idle).
- `config/startbuild-sites.txt` — every build's centre (appended at start; seed with
  `tools/cottage/find-builds.mjs <regionDir>`); new sites keep `minSiteSpacing` (450) blocks away.
- To install a new jar between takes: hold the queue (rename to `.hold`), wait for `Done:`, close ONLY the
  26.2 window (`CloseMainWindow`, never another Minecraft), run the launcher with `-NoLaunch -Build ...`
  (it refuses to launch while any javaw runs), then `prismlauncher -l BuildRecording -w "Video Building"`.
- Prefer `plains` wishes for 96-128 wide builds: forest/cherry/flower sites can need more terraforming than
  the build itself (the plan line `terraform plan:` says how much; stop early if it dwarfs the build).

Checking a version / a take (2.16.0):
- Hands-free takes skip a site before recording when its terraforming is > `maxTerraformRatio` (1.5) x the
  build (at least `terraformAllowance` 4000), it would fill > `maxWetFill` (100) blocks into water, or has
  water above the base beside it (`hands-free: skipping site` in the log); the next choice/area is tried.
- `node tools/cottage/take-report.mjs [log] [n]` - one report per take: plan, stage times, watchdog/shut-ins,
  leftover supports and whether each can be seen, schematic-vs-world, ground steps.
- `node tools/cottage/test-course.mjs` writes tc_garden / tc_overhang / tc_shutin / tc_details (farmland +
  water, deep overhang, closed box, orientations) and prints their queue lines: run them before long takes.

Open: leftover supports still happen when nooks close around them (halloween_80: 6, none visible); storybook_cottage needs a redo;
schematics with water/farmland are untested since 2.15.3. More schematics wait in `Desktop\litematic`
(catalog in `_catalog`).

Ideas the owner raised: rotate builds to face water in `/findsite`; search beyond loaded chunks;
more roof types (hip, round towers) in picture2schem.
## 7. Working rules learned the hard way

- Measure the world and `latest.log`, never trust counters.
- Nothing may appear on camera: no chat, no ghost, no `/fill`. Terraforming IS filmed, done by hand.
- Anything clicked/focused on the PC during a take can pause Minecraft (focus loss) — don't.
- After editing, grep the files on disk to confirm the change landed before building.
