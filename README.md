# StartBuild — hand-built Minecraft timelapses

A client-side Fabric mod (Minecraft 26.2, Java 25) that records a character building a schematic **by
hand** for YouTube Shorts / TikTok / Reels timelapses. It finds a natural-looking site, terraforms it on
camera, then flies around placing every block with a real click while Flashback records. The recording
stops shortly after the last block and the take is saved.

Full architecture, file map and verification status: **[HANDOFF.md](HANDOFF.md)**. The `docs/` folder
holds obsolete Baritone-era (1.x) notes.

## Requirements
- Prism Launcher, instance `BuildRecording` (Minecraft 26.2, Fabric Loader 0.19.5)
- Mods: Fabric API, Litematica + MaLiLib, Flashback; optional Sodium + Iris and a shader pack
  (Baritone is no longer needed)
- The schematic (`.litematic` / `.schem` / `.schematic`) in the instance's `schematics` folder

## Build and install
```
.\gradlew.bat build          # -> build\libs\startbuild-<version>.jar
```
Then double-click the desktop icon **"Minecraft - Build Recording"** (`tools/startbuild-launch.ps1`). It
installs/verifies the mod set and the newest StartBuild jar (only while Minecraft is closed), then starts
the instance. Bump `version=` in `gradle.properties` on every change; the launcher installs the highest.

## In-game commands
| Command | What it does |
|---|---|
| `/findsite <name> [wish]` | find the most natural spot (e.g. `near a lake`, `on a hill`, a biome), show it |
| `/findsite next` | next candidate spot; after the last one it explores a new area |
| `/previewbuild <name>` / `off` | preview the build at your feet / hide it |
| `/startbuild confirm` | terraform and build where the preview/findsite put it, recorded |
| `/startbuild place <name>` | build with the corner at your feet |
| `/stopbuild` | stop and save the take |
| `/buildstatus` | progress |

## Hands-free runs
```
powershell -ExecutionPolicy Bypass -File tools\startbuild-launch.ps1 -Build haunted_80 -Wish "near a lake"
```
The launcher writes `config\startbuild-autorun` (`<name> [wish]`) and opens the world `-World` (default
`Video Building`). The mod then finds a site, terraforms, builds and saves with no typing.
- `tools\startbuild-check.ps1` — what the current run is doing, from the game log
- `tools\startbuild-stop.ps1` — ask a running build to stop and save (never kill the game mid-take)
- `node tools/cottage/verify-build.mjs <schematic> <regionDir> <ox> <oy> <oz>` — check a finished build

Game log (source of truth):
`%APPDATA%\PrismLauncher\instances\BuildRecording\minecraft\logs\latest.log`.
