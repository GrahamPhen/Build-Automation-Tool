# Prompt for ChatGPT (Codex) to take over

Paste everything below the line into ChatGPT / Codex, with the working folder set to
`C:\Users\Graham\Codex\Build-Automation-Tool`.

---

You are taking over an ongoing project on my Windows PC: **StartBuild**, a client-side Fabric mod for
Minecraft 26.2 (Java 25) that records a character hand-building Litematica schematics, plus the tooling that
turns those recordings into vertical YouTube Shorts / TikToks that advertise my Minecraft server, MineSurvive.

**Start by reading, in this order:**
1. `HANDOFF.md` (repo root). Section 0 is the current state, section 2 lists every folder/repo involved,
   section 3 is how to start builds, section 6 is how the Shorts are made (including how we cut 2–3 versions
   of each build and the MineSurvive outro).
2. `CLAUDE.md` (repo root). The short rules; they apply to you too.

**Repos and folders:**
- This repo: `C:\Users\Graham\Codex\Build-Automation-Tool`, branch `natural-builder-2.0`, remote
  https://github.com/GrahamPhen/Build-Automation-Tool. Commit and push after each working change, and bump
  `version=` in `gradle.properties` for every mod change.
- Staging copy that must stay identical: `C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922`
  (mirror every tracked file and copy the new jar). The launcher my desktop icon runs is
  `C:\Users\Graham\Codex\MineSurvive\tools\startbuild-launch.ps1`; keep it identical to `tools\startbuild-launch.ps1` here.
- Game instance: `%APPDATA%\PrismLauncher\instances\BuildRecording\minecraft`. Its `logs\latest.log` is the
  source of truth for what the builder did.
- Finished Shorts: `C:\Users\Graham\Desktop\Shorts\`. Schematic library: `C:\Users\Graham\Desktop\Schematics\_sorted\`.
- `C:\Users\Graham\Codex\MineSurvive` is my separate server project. Don't change it except the launcher copy
  and the staging mirror.

**Hard rules (don't break these):**
- The recording must look like a real player: every build block placed by the character with a real click.
  No `/setblock`, `/fill`, printer, Litematica ghost or chat text on camera. Site prep (trees, digging, filling)
  is done by the character on camera.
- Never save an empty or stalled take. Runs are unattended.
- Two Minecraft games may be open: the recording game's window title contains **"26.2"**. I also play a
  **26.3** multiplayer game. Only ever close or touch the 26.2 one, and don't click or focus windows while a
  take is recording (Minecraft pauses on focus loss).
- The mod jar only installs while the 26.2 game is closed. Use `tools\ops\restart-between-takes.ps1` to
  install a new version between takes.
- Never commit `tools/picture2schem/anthropic-key.txt`.
- Deleting recordings or videos: send them to the Recycle Bin, never hard-delete, and only when I ask.
- Keep your own effort and token use low. Read the log and files instead of taking screenshots. Watch takes
  with `tools\ops\watch-take.ps1` (it wakes only when a take ends). Only tell me about finished builds,
  problems, or things you need me to decide.

**Where we are right now:**
- The build queue is on hold (`config\startbuild-queue.hold`). It holds green_dragon, Sugar_Skull_60,
  Jigglypuff, sylveon, plus rebuilds of house_enchanted and Grim_Reaper, which test the newest fixes
  (2.17.12–2.17.14).
- 50 Shorts for 20 builds are in `Desktop\Shorts`. I'm reviewing them and will tell you which cuts and
  styles I like. **Don't render the 15 remaining takes (listed in HANDOFF §6.4) until I say so.**
- The animated MineSurvive outro (HANDOFF §6.5, `tools\shorts\add-outro.ps1`, badge text "CHECK OUT MY
  SERVER") is approved as a design but not applied yet. Apply it with `tools\shorts\add-outro-all.ps1` when
  I say go. It writes to a `with-outro` subfolder and leaves the originals alone.
- Preference: fewer Pokémon builds from now on unless I ask. Favour Halloween, houses, fantasy, dragons,
  vehicles and other statues.

**How I like to work with you:**
- If a take shows missing blocks or a stall, find the cause in `latest.log`. Use `take-report.mjs`,
  `verify-build.mjs` and `tools\ops\missing-visible.mjs` to measure the world. Fix it in the mod, test it,
  bump the version, commit/push, mirror to staging, and install it with the restart script between takes.
  You may stop and restart builds when needed.
- Verify Minecraft API signatures with `javap` against the 26.2 client jar before using them (Mojang
  mappings). Reach other mods (Litematica, Flashback) only by reflection.
- When you finish something, tell me briefly what changed and what's next.

Your first step: read `HANDOFF.md` and `CLAUDE.md`. Then confirm to me in a few lines that you understand
the current state and what you're waiting on me for (my feedback on the Shorts, the go-ahead for the outro,
and when to resume the build queue).
