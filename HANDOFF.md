# HANDOFF — Build Automation Tool (StartBuild 2.4.x)

> Read this first. Last updated 2026-09-24, mod version **2.5.1**, branch `natural-builder-2.0`.
> The Baritone-era docs (1.x, why Baritone could not do this) are archived in `docs/`.

## 1. What it is

A client-side Fabric mod (MC 26.2, Java 25) that records a Minecraft character building a schematic
**by hand** for YouTube Shorts / TikTok / Reels timelapses. One command starts Flashback, the character
flies around and places every block with a real click (look, swing, block appears), and the recording
stops 30 s after the last block.

Owner's hard requirements: looks like a real build (no printer, no `/setblock` of build blocks, no mod
overlays on camera); no skipped blocks; runs unattended; nothing digging/half-cleared on camera.

## 2. How a build runs (`NaturalSession` + `NaturalBuilder`)

1. `/startbuild place <name>` (corner = your feet, build extends +X/+Z), or `/previewbuild` / `/findsite`
   then `/startbuild confirm`.
2. **Before recording** (`NaturalSession.tickPreparing`): world made camera-ready (gamerules: no command
   feedback, no mobs/weather/daylight cycle/random ticks/fire spread; peaceful; noon); chunks awaited;
   **site prep** — `TerrainPrep.level` flattens (cut above base inside footprint, fill dips at edge), then
   whole trees (logs+leaves) within 6 blocks and the entire build volume are `/fill`ed to air. Litematica
   placements are removed so no ghost is recorded.
3. Flashback recording starts (`FlashbackBridge`).
4. `NaturalBuilder` (creative, flying) places blocks bottom-up:
   - picks the nearest cell that has something to click against right now;
   - solves the click that yields the EXACT state (`getStateForPlacement` simulation — gets `axis=x` logs,
     stairs, slabs right), then verifies and retries;
   - cells with nothing to click: temporary support block(s) → real block → break support;
   - two-step blocks: farmland (dirt + hoe), dirt path (dirt + shovel), potted plants (pot + plant),
     crops (seed + bone meal), water (bucket), nether portal (obsidian frame + flint & steel);
   - blocks come from the creative inventory (pick-block style); never waits on anything — a stuck cell
     is parked and retried later; final pass re-checks the whole build against the world.
5. Stops `stopDelaySeconds` after the last block, adds a Flashback marker, saves the replay.
   If nothing could be placed at all, it aborts instead of saving an empty take.

Progress goes to `logs/latest.log` only (never chat — chat is on camera):
`[StartBuild] progress: N placed, M left, layer L, T min, last problem: ...`

## 3. Commands

| Command | Does |
|---|---|
| `/startbuild place <name>` | build with the corner at your feet |
| `/previewbuild <name>` / `off` | Litematica ghost at your feet + report of what is in the way / hide |
| `/findsite <name> [wish]` | most natural spot (wish: "near a lake/river", "on a hill", default flat; plus an optional biome: snowy, desert, cherry, jungle, taiga, badlands, swamp, ...), show ghost there, teleport to a viewpoint. First run searches around you; running it again for the same schematic flies 600-900 blocks away in a new direction and searches there. A biome wish flies to the nearest unused patch of that biome (server `findClosestBiome3d`). Sites already shown are never offered again |
| `/findsite next` | next of up to 5 distinct spots; after the last one it explores a new area |
| `/startbuild confirm` | build exactly where the preview/findsite put it |
| `/stopbuild` | stop and save |
| `/buildstatus` | progress |
| `/startbuild baritone <name>` | the old Baritone 1.x flow, kept for comparison only |

Known limits: preview cannot be rotated/mirrored; nudging the Litematica ghost does **not** move the build
(reading the live placement back returned the player's viewpoint — removed in 2.4.1).

## 4. Files

`src/main/java/com/graham/startbuild/`
| File | Role |
|---|---|
| `NaturalSession.java` | 2.x session: commands, preview/findsite/confirm, site prep, world settings, recording |
| `NaturalBuilder.java` | the hand-placing builder (planning, click solving, flying, two-step blocks, scaffolds) |
| `PlacementFinder.java` | `/findsite` scoring (real footprint vs ground, overhang/buried, water distance, trees) |
| `SchematicModel.java` | reads a schematic through Litematica into a dense BlockState grid (no placement) |
| `TerrainPrep.java` | `/fill` levelling used by site prep |
| `LitematicaBridge.java`, `FlashbackBridge.java`, `Reflect.java` | reflection bridges |
| `StartBuildMod.java` | command registration + client tick |
| `StartBuildConfig.java` | `config/startbuild.json` (reloaded each run) |
| `StartBuildSession.java`, `Baritone*.java`, `StockFunctions.java`, `SiteFinder.java` | 1.x Baritone flow (legacy) |

Config fields that matter now: `ticksPerBlock` (0 = fastest, set to 0 on the owner's PC),
`prepTerrain` (true), `stopDelaySeconds` (30), `videoDaylight`, `startRecording`/`finishRecording`.

`tools/picture2schem/`
- `picture2schem.mjs` — picture → JSON build plan (Claude API, model `claude-opus-5-5`, key in
  `anthropic-key.txt` next to it, git-ignored) → `.litematic`. `--spec plan.json` rebuilds without the AI.
  Good at houses/cottages (walls, gable roof, chimney, windows, boxes, garden, path, extras).
- `picture-to-schematic.bat` — drag a picture onto it.
- `scenes/spawn_plaza.mjs` — hand-scripted scene for a complex picture (template for others).
- `shrink.mjs` — scale a schematic down by an integer factor (`whimsical_halloween_40` came from this).
`tools/cottage/` — `.litematic` reader/writer (`litematic.mjs`) and world-vs-schematic diff tools.

## 5. Build, install, test (Windows)

- Mod project actually built: `C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922`
  (same sources as this repo — keep them in sync).
- `build-now.bat` there: `gradlew build` → `build-log.txt`, then `push-to-github.bat` (commits + pushes
  this repo's branch; its `git add` list is explicit — add new paths to it).
- Desktop icon "Minecraft - Build Recording" → `MineSurvive\tools\startbuild-launch.ps1`: installs the
  highest `startbuild-x.y.z.jar` into the Prism instance `BuildRecording` and launches. It does NOT open a
  world or auto-start (owner's choice); `-Build <name>` restores the hands-free autorun.
- The jar only updates while Minecraft is closed. Bump `version=` in `gradle.properties` every change.
- Test world: `saves\Video Building` (normal terrain, creative, cheats on).
- Schematics: `...\instances\BuildRecording\minecraft\schematics\` (haunted_80, whimsical_halloween_80/_40,
  storybook_cottage, spawn_plaza, cottage).

## 6. State and open items (2026-09-24)

Proven live: 2.0 built haunted_80 past layer 1 (where every Baritone version stalled) at ~150 blocks/min,
0 wrong orientations logged; the Halloween 80 build ran at ~110 blocks/min.

`/findsite` exploring/biome search: 2.5.0 ran live and travelled correctly, but searched before the new
area's chunks had arrived — `ClientLevel.hasChunk` is true for the client's empty placeholder chunk, whose
ground reads y -64, so sites landed in caves. 2.5.1 checks for real chunk data (`PlacementFinder.loaded`)
and waits for 95% of the area; not yet re-run. (2.4.2 only ever re-found the same spot 2-3 blocks over,
because each search centred on the viewpoint it had just teleported to.)

Not yet verified in game (compiled, never run): 2.4.x site prep (`/fill` of trees/volume — watch for red
chat errors), gamerule names (26.2 snake_case), `/findsite` 2.4.2 spread/ranking, portal lighting,
water bucket placement.

Ideas the owner raised: rotate builds to face water in `/findsite`; search beyond loaded chunks;
more roof types (hip, round towers) in picture2schem.

## 7. Working rules learned the hard way

- Measure the world and `latest.log`, never trust counters.
- Nothing may appear on camera: no chat, no ghost, no digging — do all prep before `startRecording()`.
- Anything clicked/focused on the PC during a take can pause Minecraft (focus loss) — don't.
- After editing, grep the files on disk to confirm the change landed before building.
