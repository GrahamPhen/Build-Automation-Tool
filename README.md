# Build Automation Tool

Automates building a Minecraft schematic so it can be recorded as a YouTube Short — place the schematic,
run one command, and the character builds the whole thing (recorded by Flashback) with no further input.

```
/startbuild place haunted_80
```

A client-side **Fabric** mod that drives **Baritone** (block-by-block, no printer) through a **Litematica**
placement while **Flashback** records, auto-restocks materials, recovers from real stalls, and guarantees a
100%-complete, correctly-oriented build via a pre-pass and a verification pass.

## Quick start

```powershell
# build (requires JDK 25 + Gradle 9.7.1, included wrapper)
./gradlew build

# install + launch the Prism "BuildRecording" instance with the correct mod set
powershell -ExecutionPolicy Bypass -File tools/startbuild-launch.ps1
```

Then in-game:

```
/startbuild place <name.litematic>     # the one command
/buildstatus                          # state, footprint, materials, config
/stopbuild                            # clean stop (keeps the recording)
/buildsite [radius]                   # preview candidate sites (read-only)
/buildsel full|layers N|corner N|off  # auto Baritone selection
/buildprep                            # apply Baritone video settings
```

## Reading the code

Everything important is documented in **[HANDOFF.md](./HANDOFF.md)** — the file map, the architecture, the
build flow, the configuration schema, the `.litematic` binary format, the Baritone internals that matter,
and the full bug history with signatures and fixes so nothing has to be re-derived.

The offline diagnostic tools are under `tools/cottage/` (run with Node). The single most useful one:

```powershell
node tools/cottage/verify-build.mjs <schematic.litematic> <regionDir> <originX> <originY> <originZ>
```

This diffs the live world against the schematic and answers "how complete is the build, and what is
missing" with measured numbers instead of inference.

## Environment

Minecraft 26.2 · Fabric Loader 0.19.5 · Fabric API 0.161.0 · Java 25 · Gradle 9.7.1 · Loom 1.18.2 ·
Litematica 0.28.8 · Baritone 1.19.0 (api-fabric) · Flashback 0.43.4. Full pinned matrix in `HANDOFF.md` §1.
