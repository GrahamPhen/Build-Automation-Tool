# HANDOFF — Build Automation Tool (StartBuild 2.17.x)

> Read this first. Last updated **2026-09-29**, mod version **2.17.17**, branch `natural-builder-2.0`
> (pushed to `origin` = https://github.com/GrahamPhen/Build-Automation-Tool). The Baritone-era docs (1.x) are
> archived in `docs/`; don't follow them. `CLAUDE.md` holds the short rules; this file holds everything else.

---

## 0. Where things stand right now (2026-09-29 evening)

- **The build queue is held for an authorized repair, for ONLY the 50 new Sarox builds.** The owner corrected the batch on
  2026-09-29: skip the six existing builds and go straight to the 50 new ones. `Aether_Cliff_Outpost_tier_1_
  plains` started recording at 20:32:38 in the 26.2 `BuildRecording` instance at `-17367, 64, -26308`;
  it finished at 20:52:29 with 203 reported block mismatches after a repeated escape attempt at a roof slab.
  The recording `2026-09-29T20_52_28.zip` is preserved and must not be rendered as successful: world
  verification found 203 missing blocks, including 148 visible from outside. The reviewed **2.17.15** fix
  handles open/toggled blocks, chest pairing, empty bookshelves and blank wall signs, real placement
  offsets for attached blocks, barrel facing, and reachable escape breaks. `gradlew build` passed all
  22 tests; eight bounded restart-script mocks passed. Runtime placement is **not yet verified**.
  The first 2.17.15 launch installed the approved jar but crashed at 21:17:23 before entering the world:
  the mixin configuration used the main mod package, which prevented `StartBuildMod` from loading.
  The reviewed **2.17.16** correction isolates the mixin in `com.graham.startbuild.mixin` and passes
  `gradlew build` with all 22 tests. The corrected jar was installed with SHA256
  `0EC0919C3B4D18C77185ACDC58DD73C410865C75D8512AC3FCCCB5303E3B37A1`; Prism stayed open.
  The fresh retry started recording at **21:23:52**, origin **`-17044, 64, -28022`**, in 26.2 PID **25548**.
  It finished at **21:39:48** with **38 state mismatches**. Fresh saved-world verification found
  **3354/3388 correct block IDs, 34 missing, zero wrong IDs; 29 missing visible outside**. Missing blocks:
  25 spruce trapdoors, 4 spruce stairs, 3 white banners, 1 oak trapdoor and 1 bone block; four present
  chests have incorrect states. No watchdog or shut-in events occurred. Two temporary supports remain,
  neither visible outside by the report's test. Replay `2026-09-29T21_39_47.zip` is preserved and also
  excluded from successful takes. Evidence: `build\aether-fix-20260929\restart\retry-213947-*.txt`.
  The reviewed **2.17.17** fix uses vanilla item placement simulation, selects compatible temporary
  faces for stairs/trapdoors, retries single chest partners, and breaks/restores stage-owned blocks to
  reach enclosed cells. Actual aiming and hand placement are retained. `gradlew build` passed all
  **24 tests**, including actual Minecraft placement reproductions. The game is idle; installation
  and another fresh retry are next. Runtime correctness of 2.17.17 is **unverified**.
  The held 49-entry queue retains its original hash and order; no active queue exists.
  `config\startbuild-queue.hold` has **49 pending entries**, exactly the remaining Sarox imports in mapping
  order, all with `plains`; retry the first new build after the next reviewed fix. Hold the other
  49 during that retry, then restore their exact order only after the saved world and mod's final state
  mismatch count are verified. This is a maintenance validation hold, not a user pause.
  The old six (`green_dragon`, `Sugar_Skull_60`, `Jigglypuff`, `sylveon`, `house_enchanted`, `Grim_Reaper`)
  are skipped and must not be requeued without new authorization. The active `green_dragon` take was
  stopped through the documented stop file at 20:31:14; the mod logged 0 construction blocks placed
  and discarded the empty construction take automatically. Existing replay/video files were preserved.
- **SaroxBuilds import complete:** all 55 requested `.litematic` files are in `<inst>\schematics\` with
  command-safe build IDs; [filename/ID/hash/size mapping](docs/sarox-import-20260929.csv). All 55 fit the
  ≤ 55k-block / ≤ 144×144 footprint guidance. Target hashes and offline parsing verified; 50 now queued,
  excluding only standalone `Pine 1.litematic` through `Pine 5.litematic`; `Pinecrest Watchtower` is included.
  No Sarox build has passed runtime verification yet.
- Queue audit and monitoring state: `build\queue-start-20260929\` preserves the original held queue,
  reviewed 56-entry proposal and `runtime-state.json` (game/watcher PIDs, logs and lifecycle cursor).
  `switch-new50-20260929\` preserves the 55-entry queue before the correction and stop/start evidence.
  Both Aether watchers (PIDs 36296 and 40108) exited after their takes. No watcher is needed while idle;
  arm exactly one bounded watcher for the next retry. Watcher logs and failed-take evidence are in
  `build\aether-fix-20260929\restart\`. Heartbeat
  `watch-startbuild-queue` checks this chat every 15 minutes and handles completed takes or actual issues.
  Startup required a process-local WindowsPowerShell module path; no machine environment or mod changed.
- **Next:** install the reviewed 2.17.17 release between takes and retry Aether at a fresh site. At its end inspect
  `take-report`, the mod's final state mismatch count, `verify-build` and `missing-visible` against the
  fresh saved world. Confirm the remaining defects and temporary supports have resolved. Only
  then restore the exact held 49 entries to `startbuild-queue.txt`; expected held SHA256 is
  `EC1D251620769FA89ADD40B0083D3009D1C80ACD70D699A58073120BE4071733`. Otherwise keep the validation
  hold and diagnose; neither failed Aether recording is a successful take.
- **Shorts:** 20 builds were edited (50 videos) into `C:\Users\Graham\Desktop\Shorts\`. **15 finished takes
  still need editing** (list in §6.4). The owner said: **don't edit more yet** — they are reviewing the
  existing 50 first and will say which cut/style they like.
- **Outro:** an animated MineSurvive outro was built and approved in its 2nd design, with the badge text
  changed to "CHECK OUT MY SERVER" (§6.5). Applied to **all 50 existing Shorts across 20 builds** in each
  build's `with-outro\` folder; all outputs validated and all originals preserved. The 15 unedited takes
  remain pending for the owner's editing feedback; build recording has resumed separately.
- Owner preference going forward: **fewer Pokémon builds** unless they ask; favour Halloween, houses,
  fantasy, dragons, vehicles, other statues.

---

## 1. What it is

A client-side Fabric mod (Minecraft **26.2**, Java 25) that records a Minecraft character building a
schematic **by hand** for YouTube Shorts / TikTok / Reels timelapses. One command (or the desktop icon)
finds a natural site, the character fells trees / digs / fills the site on camera, then flies around and
places every block with a real click. Flashback records it; the recording stops 30 s after the last block.
Replays are then rendered into vertical Shorts (1080×1920, music) by the mod's `RenderDirector`.

Owner's non-negotiables (also in `CLAUDE.md`):
- Looks like a real player building: every build block placed by the character with a real click. No
  printer, no `/setblock`/`/fill` of build blocks, no Litematica ghost or chat text on camera.
- Site prep is done by the character on camera, and the ground is terraformed to blend in. Never `/fill` terrain.
- Runs unattended to completion; never saves an empty or stalled take.
- Keep token/effort low: read the log and files; no screenshots/desktop clicking when a file answers it.
- Never click/focus other windows while a take is recording (Minecraft pauses on focus loss).
- The owner also runs a **Minecraft 26.3 multiplayer game** on the same PC. Only ever touch the window
  whose title contains **"26.2"**. Never close the 26.3 one.

---

## 2. Folders and repos this uses

| What | Where |
|---|---|
| **This repo** (source of truth) | `C:\Users\Graham\Codex\Build-Automation-Tool` — branch `natural-builder-2.0`, pushed to `origin` |
| Staging copy (must stay identical) | `C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922` — mirror every `git ls-files` entry after each change + copy the new jar into its `build\libs` |
| Launcher actually run by the desktop icon | `C:\Users\Graham\Codex\MineSurvive\tools\startbuild-launch.ps1` (+ `startbuild-*.ps1`) — keep identical to this repo's `tools\startbuild-*.ps1` |
| MineSurvive repo (owner's server project, separate) | `C:\Users\Graham\Codex\MineSurvive` — only used here for the launcher copy, the staging copy and the logo (`.wt\s263\web\leaderboards\assets\minesurvive-logo.png`, copied into `tools\shorts\outro-assets\`) |
| Prism instance | `%APPDATA%\PrismLauncher\instances\BuildRecording\minecraft` (below: `<inst>`) |
| Mods (jar installed here only while the game is closed) | `<inst>\mods` (fabric-api, Flashback 0.43.4, Sodium, Iris, MaLiLib, Litematica, startbuild-x.y.z.jar) |
| Game log — **the source of truth** | `<inst>\logs\latest.log` (rotated at midnight / restart to `logs\YYYY-MM-DD-N.log.gz`) |
| Mod config | `<inst>\config\startbuild.json` |
| Take queue / held queue | `<inst>\config\startbuild-queue.txt` / `startbuild-queue.hold` |
| Past build centres (site spacing) | `<inst>\config\startbuild-sites.txt` |
| Stop file | `<inst>\config\startbuild-stop` (create it to stop + save the take) |
| Render job file | `<inst>\config\startbuild-render` (read at the title screen) |
| Music for Shorts | `<inst>\config\startbuild-music\` (aria_math, biome_fest, colossus, haunt_muskie, heat_waves_slowed, middle_of_the_night_slowed, royalty, runaway_slowed, taswell, trap_royalty) |
| Schematics the game can build | `<inst>\schematics\` (.litematic; the name without extension is the build name) |
| SaroxBuilds import mapping (55 files, 2026-09-29) | [docs/sarox-import-20260929.csv](docs/sarox-import-20260929.csv) — exact original names, command-safe build IDs, SHA256, non-air blocks, dimensions and one-take fit |
| Recordings (Flashback replays) | `<inst>\flashback\replays\YYYY-MM-DDTHH_MM_SS.zip` (named by when the take ended) |
| Flashback exports (raw renders) | `<inst>\flashback\exports\` (13 GB, mostly copies of the Shorts + old tests) |
| World | `<inst>\saves\Video Building` — regions in `dimensions\minecraft\overworld\region` (26.2 layout) |
| **Finished Shorts** | `C:\Users\Graham\Desktop\Shorts\<build>\<build>-B-cinematic-<music>.mp4` / `-C-orbit-<music>.mp4`, list in `Desktop\Shorts\README.txt` |
| Downloaded schematic library | `C:\Users\Graham\Desktop\Schematics\` sorted into `_sorted\<category>\<fits-one-take|too-big>\` (categories: building, halloween, statue-character-other, vehicle) |
| MC client jar (for `javap`) | `%APPDATA%\PrismLauncher\libraries\com\mojang\minecraft\26.2\minecraft-26.2-client.jar` |
| FFmpeg / ffprobe (installed 2026-09-29 via winget `Gyan.FFmpeg`) | `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_*\ffmpeg-*\bin\` (also on PATH in new shells) |

---

## 3. How to start a new build

### 3.1 Pick a schematic
1. Candidates live in `Desktop\Schematics\_sorted\<category>\fits-one-take\` (≤ 55k blocks, footprint ≤ 144).
   `node tools/cottage/catalog.mjs <folder> <out.csv>` gives size, block count, category and estimated hours.
2. The game can only build what is in `<inst>\schematics\`. Copy the `.litematic` there if it isn't
   (the organize step already copied every fitting build). The build name = file name without extension.
3. Choose a biome wish: `plains` is the safest (flat, few trees). `taiga`/forest/cherry sites can need
   hours of tree felling (Mimikyu_House: 336 trees, 7 h take). `savanna` sometimes finds nothing near.
   Snowy is fine. Wishes: optional terrain word (`near a lake`, `on a hill`, default flat) + optional biome.

### 3.2 One build, hands-free (the normal way)
- Game closed: run the desktop icon's launcher with a build armed:
  `powershell -File C:\Users\Graham\Codex\MineSurvive\tools\startbuild-launch.ps1 -Build <name> -Wish "<wish>"`
  It installs the newest `startbuild-x.y.z.jar` (from this repo's `build\libs` or staging), answers Prism's
  low-memory prompt, launches, loads the world and starts `/startbuild auto <name> <wish>` by itself.
  It only detects THIS instance's javaw (the 26.3 game may stay open).
- Game already open and idle: add the line to the queue (below) — it is popped within ~30 s.
- In game (if you are at the keyboard): `/startbuild auto <name> [wish]`, or `/findsite <name> [wish]` then
  `/startbuild confirm`, or `/startbuild place <name>` (corner at your feet).

### 3.3 Many builds back to back (the queue)
- `<inst>\config\startbuild-queue.txt`: one `name wish` per line (e.g. `ghost_cat_80 taiga`). A line is popped
  30 s after a take ends, only while the mod is idle. `#` lines are ignored.
- Hold the queue = rename it to `startbuild-queue.hold` (nothing starts). Resume = rename back.
  **Right now it is held for repair** (§0); consult the live queue/log and runtime state for current progress.
- Each take: site search (up to 16 areas, cheapest site first, skipping sites whose terraforming exceeds
  1.5× the build or 4000 blocks, deep water fill, flood risk, or within 450 blocks of a past build) →
  recording starts → trees → dig → fill → build → 30 s tail → `Done: ... Recording saved.`

### 3.4 Watching a take (cheap)
- `powershell -File tools\ops\watch-take.ps1 [-Minutes 240]` — sleeps, prints only when the take ends
  (`Done:`/`Stopped`/`Giving up`/error) plus how many "hops" it used. Survives log rotation.
- Progress lines every minute: `[StartBuild] progress: <stage> - N placed, B broken, M left, layer L, T min, last problem: ...`
- Report per take: `node tools/cottage/take-report.mjs [log] [n]`.
- Are missing blocks visible? `node tools/ops/missing-visible.mjs <schematic.litematic> <regionDir> <ox> <oy> <oz>`
  (origin from the log line `recording=true <name> at x, y, z`).
- Verify against the world: `node tools/cottage/verify-build.mjs <schematic> <regionDir> <ox> <oy> <oz>`.

### 3.5 Stopping / restarting
- Stop and save the current take: create `<inst>\config\startbuild-stop` (or `tools\startbuild-stop.ps1`,
  or `/stopbuild`). A take with nothing built is discarded automatically. Note: `start()` deletes the stop
  file, so a stop sent during a site search is lost — write it again once `recording=true` appears.
- **Install a new jar between takes:** `powershell -File tools\ops\restart-between-takes.ps1` (run it
  detached: `Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','<path>'`).
  It holds the queue, waits for the running take's `Done:` (or recognizes the latest completed idle take),
  verifies PID/start time/title, closes ONLY the 26.2 window (CloseMainWindow),
  runs the launcher with the first held line, and writes the rest back into the queue. Log:
  `%TEMP%\startbuild-overnight.log`.

### 3.6 Before long unattended runs
- `node tools/cottage/test-course.mjs` writes tc_garden / tc_overhang / tc_shutin / tc_details and prints
  queue lines — quick tests of farmland+water, overhangs, shut-in escape and block orientations.

---

## 4. How a build runs (`NaturalSession` + `NaturalBuilder`)

1. **Preparing** (`NaturalSession.tickPreparing`): world made camera-ready (gamerules snake_case in 26.2:
   no command feedback, `log_admin_commands false`, no mobs/weather/daylight cycle/random ticks/fire spread,
   `block_drops false`; peaceful; noon); chunks awaited; Litematica placements removed (no ghost);
   **terraform plan** (`Terraformer.plan`, read-only).
2. Flashback recording starts (`FlashbackBridge`). Site centre is appended to `startbuild-sites.txt`.
3. **Terraforming on camera**, stages (`Terraformer.Part`): one FELL per tree in the way (trunk + leaves that
   would decay), DIG (top-down), FILL (bottom-up, fresh top block, replanting sampled plants). New ground
   is flat only where the build stands and eases back to the land over 12–24 blocks (`targetHeights`).
   Terraform clicks every 2 ticks.
4. **Build** (`NaturalBuilder`, creative, flying), bottom-up, clicks every 3 ticks:
   - nearest cell that has something to click right now; simulates `getStateForPlacement` so the exact state
     (axis, stair facing, slab half) results; blocks that face the player's look wait one tick so the server
     has the look; facing is checked robustly (stand still works ±0.3 blocks off);
   - multi-step blocks: farmland (dirt + hoe), path (dirt + shovel), potted plants, crops (+ bone meal),
     water (bucket, placed last, only when contained), nether portal, **double slabs (2 clicks), leaf
     litter / petals / wildflowers (1 click per segment, 2.17.12), snow layers (1 click per layer, 2.17.14)**;
   - cells with nothing to click: temporary support chain → real block → supports removed. Chains are
     searched 5 deep, and **32 deep for floating parts (2.17.13)**;
   - flying: straight or A* through air; shut in → break the fewest blocks (rebuilt later);
     buried → teleport up (`surfaceIfBuried`);
   - **a stand the player could not fly to is remembered per target and not tried again; after two failed
     stands the player "hops" there with a short `/tp` (2.17.9)** — each hop is a small jump cut in the video;
     count them with `watch-take.ps1` / `grep "hop to"`;
   - every placed cell is re-checked after 20 ticks; stages end with `N cell(s) not done: {...}`;
     a final pass re-checks the whole build against the world (twice).
5. **Watchdog** (`NaturalSession.watchdog`): 3 min without progress, or 6 min busy without finishing a cell →
   replan; again → teleport to open air; again → give up the rest of the stage (logged, take still saved).
6. Stops 30 s after the last block, saves the replay. Empty takes are discarded.

Schematic loading (`SchematicModel`, 2.17.11): empty layers at the bottom of a schematic are dropped (many
downloaded statues have 6+ empty layers; the site finder found no footprint and every search failed).

Site finder (`PlacementFinder`, 2.17.10): up to 1% of the footprint may be pond columns (filled on camera,
at a cost); each `findsite` log line ends with `(ok N, rejected: unloaded / wet / biome / built / water above / wish)`.

Game speed: the client never runs faster than 20 TPS; `gameSpeed` must stay 1. Speed comes from click pacing.

---

## 5. Commands

| Command | Does |
|---|---|
| `/startbuild auto [name] [wish]` | fully hands-free: find site → terraform → build → save (= launcher `-Build`) |
| `/findsite <name> [wish]` / `/findsite next` | show a site (ghost) / next site |
| `/startbuild confirm` | build where findsite/preview put it |
| `/startbuild place <name>` | build with the corner at your feet |
| `/previewbuild <name>` / `off` | Litematica ghost |
| `/stopbuild` | stop and save (or cancel a site search) |
| `/buildstatus` | stage and progress |

---

## 6. Making the Shorts

### 6.1 How rendering works
`RenderDirector.java` renders a replay hands-free: at the **title screen** it reads `<inst>\config\startbuild-render`
(one job per line, `key=value`), opens the replay in Flashback, writes camera/timelapse keyframes, exports
1080×1920, then `MusicMixer` lays the music under the silent export. Rendering uses the same game as
recording, so **it can't run during a take** — hold the queue, let the take finish, then render.

Job keys: `replay=<zip in flashback\replays>` `style=cinematic|orbit|tripod` `output=<name>.mp4`
`centerX= centerZ= baseY=` (middle of the build, its ground) `width= depth= height=` (real size of the
non-air box) `terraformEnd= buildEnd=` (replay ticks, 20/s, since recording start, where site prep ended /
where the last block went) `frontYaw=` (camera yaw of the "front"; everything so far used 160 — some reveals
may show a side/back) `timeOfDay=` (optional) `music=<file name in startbuild-music>` `musicStart=` (optional).

### 6.2 Styles (owner-approved)
- **B "cinematic"** — still angles cut through the timelapse, close shots of the character, slow low reveal
  sweep, daylight. 38 s.
- **C "orbit"** — one continuous circling camera. 60 s.
- A "tripod" (top-down → fixed front, sunset) — the owner does NOT like it; don't render it.
Daylight over sunset, especially in snowy biomes.

### 6.3 How we cut 2–3 versions of the same build
For each take we render several jobs from the same replay and let the owner choose:
- **2 cuts**: one B cinematic + one C orbit, with different songs.
- **3 cuts** for the cleanest/biggest takes: B + C + a second B (or C) with a third song.
- Music by mood: spooky (haunt_muskie, middle_of_the_night_slowed, colossus) for Halloween/dark builds;
  upbeat (royalty, trap_royalty, heat_waves_slowed, runaway_slowed, biome_fest, aria_math, taswell) for
  Pokémon, houses, vehicles.
- File name: `<build>-<B|C>-<cinematic|orbit>-<music>[-t<timeOfDay>].mp4`.

Tools (`tools\shorts\`):
- `render-take.ps1 -Build <name> -Common "<replay/center/size/ticks keys> frontYaw=160" -Jobs "style=cinematic music=royalty","style=orbit music=heat_waves_slowed"`
  closes the 26.2 game, writes the job file, relaunches to the title screen, waits for `render: finished`
  per job, checks each video (25–65 s, 1080×1920, audio), copies it to `Desktop\Shorts\<build>\` and appends
  `README.txt`. Log: `%TEMP%\startbuild-render.log`. Leaves the game at the title screen/closed.
- `all-takes.ps1 [-Only a,b] [-Skip c]` — the table of the 19 rendered takes with their exact keys and jobs;
  re-render any with a different song/angle by editing its jobs and running `-Only <build>`.
- Deriving the keys for a new take:
  - `replay` = the zip whose name matches the `Done:` time (±2 s) of that take.
  - origin: the log line `recording=true <name> at x, y, z`. `node tools/shorts/extent.mjs <schematicDir>` gives
    each schematic's non-air box (x0..x1, y0..y1, z0..z1): `centerX = ox + (x0+x1)/2`, `centerZ = oz + (z0+z1)/2`,
    `baseY = oy`, `width/depth/height` = box size. Since 2.17.11 the mod drops empty bottom layers, so the
    origin's y already corresponds to the box's y0.
  - `powershell -File tools\shorts\take-lines.ps1` lists each take's lifecycle lines from all logs:
    `terraformEnd = (time of "building (N of N)" − time of recording=true) × 20`,
    `buildEnd = (time of "last block placed" − time of recording=true) × 20`.

### 6.4 Status of the Shorts
Rendered (in `Desktop\Shorts`, 2–3 cuts each, 50 videos, all verified 1080×1920 with audio via ffprobe):
charmander_80, pikachu+detective_80, gengar_black_80, halloween+witch_80, halloween+cauldron_80,
pumpkin+house_80, halloween+cottage_80, haunted+cottage_80, pumpkin_house_80, house_3_80, house_80_v2,
house_6_80, storybook_cottage, greenhouse_80, haunted_80, pumpkin_castle_80, haunted_halloween_80
(129 missing), halloween_80 (43 missing), treehouse_80 (956 wrong), whimsical_halloween_80 (8 older renders).

**Not rendered yet (15)** — replay files:
| Build | Replay | Take result |
|---|---|---|
| ninetales | 2026-09-27T20_44_20.zip | 20,127 placed, 0 wrong |
| pikachu_painter_80 | 2026-09-27T22_04_10.zip | 13,089, 0 |
| squirtle_falling | 2026-09-27T23_42_30.zip | 16,018, 0 (2 hops) |
| skull+mask_80 | 2026-09-28T01_56_42.zip | 17,800, 27 |
| coffin_80 | 2026-09-28T05_13_40.zip | 22,029, 2 |
| house_10_80 | 2026-09-28T08_21_21.zip | 30,348, 1 |
| pokemon_center_80 | 2026-09-28T11_10_55.zip | 38,449, 1 |
| bat_house_80 | 2026-09-28T14_35_10.zip | 20,359, 0 |
| ship_magma_80 | 2026-09-28T15_54_17.zip | 15,447, 1 |
| construction_vehicle_80 | 2026-09-28T20_02_32.zip | 48,530, 1 (1 hop) |
| Bulbasaur_house | 2026-09-28T20_21_26.zip | 3,639, 0 |
| ghost_cat_80 | 2026-09-28T21_59_08.zip | 13,321, 0 (1 hop) |
| Mimikyu_House | 2026-09-29T05_23_05.zip | 51,274, 168 (decor: cactus/leaf litter/chests); 437 min, ~4.5 h of it tree-felling — get terraformEnd right |
| vaporeon_80 | 2026-09-29T10_38_46.zip | 6,552, 0 |
| rayquaza1 | 2026-09-29T15_09_57.zip | 22,575, 1 |
Plan agreed with the owner: 3 cuts for ninetales, pokemon_center_80, construction_vehicle_80, coffin_80,
rayquaza1; 2 for the rest; spooky music for skull+mask_80, coffin_80, bat_house_80, ghost_cat_80, Mimikyu_House.
**Wait for the owner's feedback on the first 50 before rendering these** (they may change style/music).

### 6.5 The MineSurvive outro
- `tools\shorts\add-outro.ps1 -In <short.mp4> -Out <new.mp4>` appends a ~4.6 s animated end card: the last
  frame keeps playing as a slow push-in with a dark gradient; the MineSurvive logo drops in with a bounce;
  an orange pill **"CHECK OUT MY SERVER"** pops in; three cards slide in from alternating sides —
  **JAVA EDITION • PC: play.minesurvive.com**, **BEDROCK • CONSOLE & MOBILE: bedrock.minesurvive.com, Port 19132**,
  **DISCORD: discord.gg/minesurvive**; the music fades out. ~19 s per video.
- `tools\shorts\add-outro-all.ps1 [-Only a,b]` — every Short under `Desktop\Shorts` → `<build>\with-outro\<same name>.mp4`
  (originals untouched, already-done files skipped).
- Graphics: `tools\shorts\outro-assets\*.png`, made by `outro-assets.ps1` (System.Drawing, Segoe UI Black/Bold).
  Change wording/colours there and re-run it; timings/positions are in `add-outro.ps1`.
- The first design (static text on a blurred frame) was rejected as "horrible"; the animated one was accepted
  with the tag text change. Owner approved application; the existing batch created all 50 `with-outro`
  copies across 20 builds, with 0 failures and 0 skips.
- Verification: all 50 outputs have 1080×1920 video and audio, add 4.600–4.633 s, and total 2,823,507,221 bytes.
  All 50 original SHA256 hashes, sizes and modification timestamps are unchanged; three full sample decodes
  passed. Representative late-outro frames show the approved badge and connection cards without clipping.
  No additional takes were rendered during the outro batch; recording resumed afterward (§0).
- Server details come from the MineSurvive docs (`docs\INFRASTRUCTURE.md`, handoffs): Java `play.minesurvive.com`,
  Bedrock `bedrock.minesurvive.com:19132` (Geyser), Discord `discord.gg/minesurvive`.

---

## 7. Files

`src/main/java/com/graham/startbuild/`
| File | Role |
|---|---|
| `NaturalSession.java` | the take: commands, findsite/auto, queue, site checks, world settings, stages, watchdog, recording, safety nets |
| `NaturalBuilder.java` | the hand builder: planning, click solving, facing, flying + A* routing, dig-out, multi-step blocks, supports, hops |
| `FlightPath.java` | pure A* (unit-tested) |
| `Terraformer.java` | terraform plan: fell / dig / fill parts; `targetHeights` (unit-tested) |
| `PlacementFinder.java` | site scoring, biome words, rejection counts |
| `SchematicModel.java` | schematic → dense BlockState grid via Litematica (drops empty bottom layers) |
| `RenderDirector.java`, `MusicMixer.java` | hands-free Shorts rendering + music |
| `LitematicaBridge.java`, `FlashbackBridge.java`, `Reflect.java` | reflection bridges (other mods only by reflection) |
| `StartBuildMod.java`, `StartBuildConfig.java` | entry point, commands, `config/startbuild.json` |

Tools:
- `tools/startbuild-launch.ps1` / `-check.ps1` / `-stop.ps1` — launcher (desktop icon copy lives in MineSurvive\tools).
- `tools/ops/` — `watch-take.ps1`, `restart-between-takes.ps1`, `missing-visible.mjs`.
- `tools/shorts/` — `render-take.ps1`, `all-takes.ps1`, `take-lines.ps1`, `extent.mjs`, `add-outro.ps1`,
  `add-outro-all.ps1`, `outro-assets.ps1`, `outro-assets/`.
- `tools/cottage/` — `litematic.mjs` reader/writer, `verify-build.mjs`, `site-relief.mjs`, `take-report.mjs`,
  `test-course.mjs`, `catalog.mjs`, `find-builds.mjs`, `world.mjs` (region reader).
- `tools/picture2schem/` — picture → schematic via the Claude API (key in git-ignored `anthropic-key.txt`; never commit it).

Config (`<inst>\config\startbuild.json`, instance values): `maxBuildMinutes` 600, `autoSiteAttempts` 16,
`gameSpeed` 1.0, `minSiteSpacing` 450, `maxTerraformRatio` 1.5, `terraformAllowance` 4000, `maxWetFill` 100,
`stopDelaySeconds` 30, `forceQuicksave` true, `minFreeDiskGB` 5.

---

## 8. Build, install, test (Windows)

- `.\gradlew.bat build` → `build\libs\startbuild-<version>.jar` (runs unit tests too).
- **Bump `version=` in `gradle.properties` on every change** (launcher installs the highest version).
- Commit + push to `natural-builder-2.0` after each working change (end commit messages as the repo does).
- Mirror to staging (every `git ls-files` entry, hash-compare) + copy the jar; keep MineSurvive\tools launcher copies identical.
- The jar only installs while the 26.2 game is closed → use `tools\ops\restart-between-takes.ps1` between takes.
- Verify any MC API with `javap -cp <client jar> <class>` before using it (Mojang mappings; `ResourceLocation` is `Identifier`).

---

## 9. What changed recently (2.10 → 2.17.14)

- 2.10–2.14: hands-free Shorts rendering (3 styles, music, close shots), take queue, busy-loop watchdog.
- 2.15.x: leftover supports removed from any face; water placed last; direct movement in water; hoe/shovel with
  a plant on top; 64-stacks of tools rejected by the server → stacks of the item's max size.
- 2.16.x: site cost checks before recording, take report, test course, cherry grove reuse fix, stairs/slabs/doors facing.
- 2.17.1–2.17.4: faster clicks (3 ticks build / 2 ticks terraform), no wasted aim tick, `/tick` chat line hidden.
- 2.17.3: buried start on a slope → start stand raised, `surfaceIfBuried`.
- 2.17.5–2.17.6: facing blocks wait one tick for the server to get the look; robust stands.
- 2.17.7: `*_wood`/`*_hyphae` any axis (treehouse trunk).
- 2.17.8: queue never pops while a take is starting.
- 2.17.9: failed flight stands remembered; hop after two (haunted_halloween_80 had 129 misses → next takes 0–1).
- 2.17.10: up to 1% pond columns under big footprints; findsite rejection counts in the log.
- 2.17.11: empty bottom layers dropped (sylveon, Jigglypuff, Sugar_Skull_60 were skipped: "0 site(s)").
- 2.17.12: leaf litter / petals by segment.
- 2.17.13: 32-deep supports for floating parts (Grim_Reaper had 1,033 visible stone missing).
- 2.17.14: snow layers one per click (house_enchanted roof: 618 missing).
2.17.12–2.17.14 have NOT been proven in a take yet — the queued house_enchanted / Grim_Reaper rebuilds test them.

## 10. Open items / known limits

- Cactus touching other blocks can't exist in vanilla (Mimikyu_House: 68) — unfixable, ignore.
- A few chests/barrels end up facing wrong (Mimikyu_House: 8).
- skull+mask_80: 27 "could not place even with support" (logs/stone) — watch whether it recurs.
- `frontYaw` is always 160 in renders; reveals may show a side/back. Could be derived from the schematic
  (e.g. the side with the door) — owner hasn't asked yet.
- Forest/taiga sites make very long takes; consider a stricter tree limit if the owner wants shorter takes.
- The staging `build-now.bat` / `push-to-github.bat` are staging-only; leave them.

## 11. Working rules learned the hard way

- Measure the world and `latest.log`; never trust the mod's counters.
- Nothing may appear on camera: no chat, no ghost, no `/fill`. Terraforming IS filmed, done by hand.
- Don't click/focus other windows during a take; never touch the 26.3 game.
- Low-effort monitoring: one watcher that wakes only on Done/give-up/error (`tools\ops\watch-take.ps1`);
  report only finished builds and problems to the owner.
- Restart takes / the game when needed — the owner has approved that — but only between takes via the
  restart script, or with the stop file when a take is clearly broken.
- Deleting recordings: send to the Recycle Bin, never hard-delete.
- PowerShell 5.1: no `&&`; variable names are case-insensitive (`$card` = `$Card`!); `Set-Content` defaults
  to ANSI — write UTF-8 explicitly when a file has non-ASCII characters.
