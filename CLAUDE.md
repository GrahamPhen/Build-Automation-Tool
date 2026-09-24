# CLAUDE.md — Build Automation Tool

Client-side Fabric mod (Minecraft 26.2, Java 25) that records a character hand-building a schematic for
Shorts/TikTok/Reels timelapses. **Read `HANDOFF.md` first** — it has the architecture, commands, file map
and what is/isn't verified. `docs/` holds the obsolete Baritone-era notes; don't follow them.

## Owner's non-negotiables
- The recording must look like a real player building: every build block placed by the character with a
  real click. No printer, no `/setblock`/`/fill` of build blocks, no Litematica ghost, chat text or
  digging on camera. Site prep (`/fill` clearing/levelling) is fine but ONLY before recording starts.
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
  The launcher looks in both this repo's `build\libs` and the old staging folder
  (`C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922`).
- Game log (the source of truth): `%APPDATA%\PrismLauncher\instances\BuildRecording\minecraft\logs\latest.log`.
  Builder progress lines: `[StartBuild] progress: N placed, M left, layer L, T min, last problem: ...`.
  Never trust the mod's own counters over the log/world.
- Verify a finished build against the schematic: `node tools/cottage/verify-build.mjs <schematic> <regionDir> <ox> <oy> <oz>`.
- Offline tests: `node tests/verify-geometry.mjs` etc. (cover the 1.x code).
- Never click/focus other windows while a take is recording — Minecraft pauses on focus loss.

## Git
- Work on branch `natural-builder-2.0` (pushed to `origin`). Commit and push after each working change.
- Don't commit `tools/picture2schem/anthropic-key.txt` (API key, git-ignored).

## Minecraft 26.2 API notes
- Mojang mappings; `ResourceLocation` is `net.minecraft.resources.Identifier`.
- Verify a signature before using it: `javap -cp <jar> <class>` against
  `%APPDATA%\PrismLauncher\libraries\com\mojang\minecraft\26.2\minecraft-26.2-client.jar`
  (Litematica/Baritone/Flashback jars are in the instance `mods` folder). Other mods are reached only by
  reflection (`Reflect`, `*Bridge`), never compile-time.
- Gamerules are snake_case in 26.2 (`send_command_feedback`, `advance_time`, ...).
