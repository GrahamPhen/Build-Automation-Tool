# Testing StartBuild

A staged procedure so a failure tells you *which* part broke. Everything here is current as of
StartBuild **1.2.0**.

Paths (Prism instance `BuildRecording`):

```
GAMEDIR = %APPDATA%\PrismLauncher\instances\BuildRecording\minecraft
config  = GAMEDIR\config\startbuild.json
replays = GAMEDIR\flashback\replays\
logs    = GAMEDIR\logs\latest.log
```

---

## Step 0 - create a world (required)

Datapacks live inside a world, and a folder without `level.dat` is not loadable. Create a world in
game: **creative**, **cheats ON** (needed for `/function` and `/give`), superflat is easiest because
Baritone has no terrain to fight.

Then install the stock datapacks into it:

```powershell
powershell -File tools\install-stock-datapack.ps1 -List
powershell -File tools\install-stock-datapack.ps1 -World "<your world>"
```

`-List` shows only folders that can actually take datapacks, and flags broken ones.

## Step 1 - did everything load? (10 seconds, zero risk)

In game:

```
/buildstatus
```

Look for `baritone=true flashback=true`, and the timing line - `timing=block-change events ...` means
the new last-block-placed hook took. If the startup self-test found problems, chat prints them by
itself as soon as you are in a world.

## Step 2 - test the stocking datapack (2 minutes)

In game, after `/reload`:

```
/function sb:haunted_80_1
```

Expect a batch message (`StartBuild: batch 1/4 - 36 block types`) and 36 stacks in your inventory.
That tests the whole "stock a build in one command" chain on its own. `/function sb:haunted_80_clear`
resets your inventory.

## Step 3 - place the schematic

1. `M` -> **Load Schematics** -> `haunted_80`. It lists as **"schematelizer"** - that is the tool stamp
   in its metadata, not a wrong file.
2. Click **Create placement** on that row. **This is the step that is easy to miss**: `/startbuild`
   builds *placements*, not merely loaded schematics.
3. Put it on flat ground; `Alt + scroll` with the stick nudges it, **Configure** sets exact coordinates.

Do **not** use Paste - that places the blocks for real and leaves `/startbuild` nothing to do.

## Step 4 - prepare Baritone (two commands, no corner-standing)

```
/buildprep
```

Applies the video-ready settings (allowInventory on, path/goal/selection rendering off) and reports
what changed.

```
/buildsel
```

**The selection is now automatic.** StartBuild reads the build's bounding box from the placement and
selects it for you - no `#sel pos1` / `#sel pos2`, no walking to corners. Defaults to
`autoSelection: layers` with `selectionLayers: 1`, i.e. **the base layer of the build**, which is a
good first test size.

* `/buildsel` with no arguments reports the build box, the current selection and the settings.
* `/buildsel layers 3`, `/buildsel corner 16`, `/buildsel full` change it (and remember the mode).
* `/buildsel off` stops automatic selection.
* To see the box in the world: `#set renderSelection true` (that is one thing `/buildprep` turns off,
  so turn it back on while you are testing, and off before a final take).
* A manual `#sel` is respected unless `autoSelectionReplace` is true.

## Step 5 - run it

Stand near the ghost build and:

```
/startbuild
```

Expected chat, in order:

| When | Message |
|---|---|
| now | `Armed. Target: Litematica placement 1. Recording is live.` |
| now | `Auto-selected 1 layer(s): 80x1x64 at (x, y, z) to (x, y, z)` |
| ~1s | `Build requested; watching for the last block.` |
| when done | `last block placed. Recording continues for another N.Ns.` |
| +delay | `Recording stopped and saved: build stopped (...), N.Ns after the last block placed` |

The delay is currently **10 s** for testing (config `stopDelaySeconds`). Confirm the replay landed:

```powershell
Get-ChildItem "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft\flashback\replays" |
  Sort-Object LastWriteTime -Descending | Select-Object -First 3 Name, Length, LastWriteTime
```

Then open it from the title screen (Flashback button) and check for the completion marker near the end.

If materials run short mid-build: with `autoResumeMaterials` on, the recording keeps rolling and chat
tells you what Baritone needs. With `autoStockFunctions` configured (1.3.0+) you do not even have to act
on that - StartBuild runs the next `/function sb:haunted_80_N` batch itself and the build resumes. The
manual command still works if you would rather do it by hand.

## Step 6 - episodes (optional)

```
/startbuild next          # build the next episodeLayers layers, then stop and save
/startbuild next 3        # three layers this episode
```

It remembers how far it got (`episodesDone`), so successive calls walk up the build.

## Step 7 - restore the real settings

* `stopDelaySeconds`: **10 -> 30** in `startbuild.json`.
* `/buildprep` again (puts selection rendering back off for camera-clean recordings).
* Shaders: off while building, on in the replay for camera work and export.

---

## Troubleshooting

| What you see | Meaning | Fix |
|---|---|---|
| No `[StartBuild]` lines in `latest.log` | mod not loaded | Prism -> Mods tab; re-run the launcher |
| `Baritone is not installed or its API is unreachable` | wrong Baritone jar | must be `baritone-api-fabric-1.19.0.jar`; the launcher enforces this |
| `buildOnlySelection is ON but no selection is set` | auto-selection off and no manual `#sel` | `/buildsel layers 1`, or `#set buildOnlySelection false` |
| `Auto-selected ...` never appears | no placement loaded | Create placement (step 3) |
| `the build never started` | the selection missed the build, or nothing was placed | `/buildsel` to see the box vs the build; `/buildsel full` |
| `Baritone needs: ...` then a pause | missing materials | automatic with `autoStockFunctions`; otherwise run the next `/function sb:<build>_N` batch |
| `Only N GB free` | disk pre-flight | free space or lower `minFreeDiskGB` |
| Recording stopped but a naming screen appeared | Flashback quicksave off | `forceQuicksave: true` is already set; check `/flashback config` |
| `/function sb:...` unknown | datapack not installed in *this* world | re-run the installer (step 0) |

Useful: `/buildstatus` (state, timing mode, layer progress, missing materials), `/buildsel` (box),
`/stopbuild` (cancel + save now), `#cancel` (Baritone's own cancel), `/flashback finish`.

---

## Offline checks (no game needed)

Run both before trusting a build. They cover the two failure classes that cost whole runs:
string handling of Baritone's chat, and geometry.

```
node tests/verify-missing-capture.mjs    # Baritone's missing-materials line -> the exact give command
node tests/verify-geometry.mjs           # footprint maths, terrain cut/fill decisions, /fill slab caps
node tests/verify-episode-rule.mjs       # episode layer boundary, stall-restart gating
```

`verify-geometry.mjs` exists because two run-killing bugs were both "read a value with the wrong
meaning": `BaritoneBridge.schematicBox()` returns sizes in slots 3..5, not maximum coordinates, and
two call sites read them as coordinates; and `TerrainPrep` cut terrain above the base level with
`>` where a block sitting exactly *at* the base level also had to go. Both are mirrored as pure
functions there, so if the Java changes meaning the test fails.

`verify-episode-rule.mjs` covers logic that otherwise needs a multi-hour run to reach. It pins the
episode boundary to a rule that is correct whichever numbering Baritone's `getMinLayer()` uses, and
the two-clock stall rule that keeps a builder walking across a wide schematic from being mistaken
for a wedged one.

---

## What is verified, and what is not

**Verified offline:** the mod compiles; every reflection call was checked against the real
Baritone 1.19.0 and Flashback 0.43.4 jars with `javap`; the launcher created the instance, installed
nine hash-verified jars and repaired itself after a jar lock; the stock generator's arithmetic is
exact; the catalog tool passes its fixture suite and ran clean over a 32-file library;
`--previews` output was inspected as an image; and both test scripts above pass.

**Not verified: any of this in a running game.** StartBuild 1.1.0/1.2.0 behaviour - the event-based
timing, auto-selection, episode mode, auto-resume and the shader split - has never run. Steps 1-5 are
that test. If something misbehaves, send `/buildstatus` output, the chat log, and
`GAMEDIR\logs\latest.log`.

**Specifically unverified:** the site finder and terrain prep. Their reported numbers were wrong
until 1.18.0 (`/buildsite` measured the footprint from sizes read as coordinates), so any earlier
judgement of site quality was made against a nonsense footprint and does not count as evidence.
