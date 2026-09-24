# CLAUDE.md — Build Automation Tool

Client-side Fabric mod (Minecraft 26.2, Java 25) that records a character hand-building a schematic for
Shorts/TikTok/Reels timelapses. **Read `HANDOFF.md` first** — it has the architecture, commands, file map
and what is/isn't verified. `docs/` holds the obsolete Baritone-era notes; don't follow them.

## Owner's non-negotiables
- The recording must look like a real player building: every build block placed by the character with a
  real click. No printer, no `/setblock`/`/fill` of build blocks, no Litematica ghost or chat text on camera.
- Site prep is done BY THE CHARACTER, ON CAMERA (owner's call, 2026-09-24): it fells trees, digs the
  hillside down and builds up dips with real clicks, and the ground is terraformed so the pad blends into
  the land (no floating pad, no box cut into a mountain). Never `/fill` terrain.
- Runs unattended to completion; never saves an empty or stalled take.
- Keep token use low: no screenshots/desktop clicking when a file or log answers the question.

## Build / install / test (Windows, run from this repo)
```
.\gradlew.bat build                      # -> build\libs\startbuild-<version>.jar
```
- Bump `version=` in `gradle.properties` on every change (the launcher installs the highest version).
- Install + launch: desktop icon "Minecraft - Build Recording" (runs
  `C:\Users\Graham\Codex\MineSurvive\tools\startbuild-launch.ps1`). It copies the newest jar into
  `%APPDATA%\PrismLauncher\instances\BuildRecording\minecraft\mods` — **only while Minecraft is closed**.
  The launcher looks in both this repo's `build\libs` and the staging folder
  (`C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922`). Keep the staging copy and
  `MineSurvive\tools\startbuild-*.ps1` identical to this repo after every change.
- Game log (the source of truth): `%APPDATA%\PrismLauncher\instances\BuildRecording\minecraft\logs\latest.log`.
  Builder progress lines: `[StartBuild] progress: N placed, M left, layer L, T min, last problem: ...`.
  Never trust the mod's own counters over the log/world.
- Verify a finished build against the schematic: `node tools/cottage/verify-build.mjs <schematic> <regionDir> <ox> <oy> <oz>`.
- Unit tests (pure logic: terraform heights, flight A*): `.\gradlew.bat test` (also run by `build`).
- Never click/focus other windows while a take is recording — Minecraft pauses on focus loss.

## Git
- Work on branch `natural-builder-2.0` (pushed to `origin`). Commit and push after each working change.
- Don't commit `tools/picture2schem/anthropic-key.txt` (API key, git-ignored).

## Minecraft 26.2 API notes
- Mojang mappings; `ResourceLocation` is `net.minecraft.resources.Identifier`.
- Verify a signature before using it: `javap -cp <jar> <class>` against
  `%APPDATA%\PrismLauncher\libraries\com\mojang\minecraft\26.2\minecraft-26.2-client.jar`
  (Litematica/Flashback jars are in the instance `mods` folder). Other mods are reached only by
  reflection (`Reflect`, `*Bridge`), never compile-time.
- Gamerules are snake_case in 26.2 (`send_command_feedback`, `advance_time`, ...).
