# Cataloging a downloaded schematic library

`schematic-catalog.mjs` in this repo's `tools/` folder inventories a folder of schematics without
running Minecraft. Built for the case where you have hundreds or thousands of `.litematic` /
`.schem` / `.schematic` files and need to know what's in there before you can use any of it.

Runtime is **Node.js** (v24 here). There is no working Python on this machine — the `python.exe` on
PATH is the Microsoft Store stub — so this is a Node script, not Python.

---

## Quick start

```powershell
# 1. fast first pass: metadata only, seconds even for thousands of files
node tools\schematic-catalog.mjs "D:\path\to\schematics" --no-count-blocks

# 2. full pass: real non-air block counts, parallel across all cores
node tools\schematic-catalog.mjs "D:\path\to\schematics"

# 3. load a shortlist straight into the recording instance
node tools\schematic-catalog.mjs "D:\path\to\schematics" `
  --stage "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft\schematics" `
  --only-buildable --top 30
```

Options: `--out DIR` (default `<folder>\_catalog`), `--count-all`, `--jobs N`,
`--no-extract-zips`, `--only-buildable`, `--top N`, `--inspect FILE`.

---

## The version trap this exists to catch

Baritone's own `#build <file>` only parses a subset of what creators publish:

| Format | Baritone `#build` accepts |
|---|---|
| `.litematic` | **format version 7 only** (Minecraft 1.21+). v4 (1.12), v5 (1.13–1.17) and v6 (1.18–1.20) are rejected as *"This litematic Version is too old"* |
| `.schem` | Sponge **version 1 or 2** only; v3 is not supported |
| `.schematic` | MCEdit, any |

A downloaded library will usually be a mix. Every row in the catalog therefore carries a
**Baritone #build** column: where it says *no*, load and place the schematic in Litematica and use
`/startbuild` instead — that path goes through Litematica's own version handling, so old files still
build fine.

---

## What it reads

It parses the files directly (they are gzipped NBT; `.schematic` and NBT-in-a-zip too), so nothing
here needs Minecraft, Litematica or Baritone installed.

Per file: name, author, description, dimensions, volume, **real non-air block count**, region count,
distinct block types, format and format version, byte size, SHA-256, whether Baritone can build it
from the file, and any read error.

It also looks inside `.zip` archives and pulls out any schematics it finds, writing them to
`_catalog\extracted\<zipname>\<zipname>__<file>`. Extraction is deterministic, so re-running
overwrites rather than piling up copies.

## Output

Written to `_catalog` next to the scanned folder (or `--out`):

* `catalog.md` — readable table, sorted by real block count, with duplicate and same-build groups
* `catalog.csv` — same data for spreadsheets
* `catalog.json` — same data plus the duplicate/same-build group lists, for scripting
* `extracted\` — schematics pulled out of any zips

## Reading the results

* **Blocks** is the non-air block count — a decent proxy for how much work a build is, and how long a
  Baritone recording will take. `(partial)` means the schematic was too large for the decode limit and
  only part of it was counted; `n/a` means block counting was off or the file wasn't readable.
* **Same build, more than one format** — the same build published as both `.litematic` and `.schem`.
  Byte-level de-duplication cannot see these as copies because the bytes genuinely differ; they are
  grouped by matching name + dimensions instead.
* **Duplicate files** — byte-identical, so genuinely wasted space and wasted clicks.

Staging (`--stage`) skips byte-identical duplicates, so you don't end up with the same build three
times, but keeps format twins since the `.schem` is a useful fallback.

Nothing is ever deleted. The tool only reads your library and writes the catalog plus whatever
`--stage` copies.

## Stocking a build in one command (`--stock`)

```powershell
node tools\schematic-catalog.mjs --stock "<schematic.litematic>"
```

Reads the schematic's palette, computes exact per-block counts, and writes a **datapack** plus a
`materials.txt` shopping list. Install the pack once, then stocking a build is one in-game command.

Output, for `haunted_80` (110 types, 12,100 blocks):

```
/function sb:haunted_80_1    <- 36 types, 11,375 blocks
/function sb:haunted_80_2    <- 36 types,    559 blocks
/function sb:haunted_80_3    <- 36 types,    164 blocks
/function sb:haunted_80_4    <-  2 types,      2 blocks
/function sb:haunted_80_clear
```

To use it: copy the generated folder into `<world>\datapacks\`, then `/reload`, then run the batch
functions. (In Prism that is
`instances/<instance>/minecraft/saves/<world>/datapacks/`.)

Design notes:

* **Batching is the point.** An inventory holds 36 slots, and a creator schematic can use 110+ block
  types, so one function per batch of 36 types - sorted by count, so batch 1 typically covers 90%+ of
  the build. Run the next batch when Baritone pauses for materials; StartBuild's auto-resume picks it
  up from there.
* **`--stock-mode creative`** (default) gives one stack of each type, which is enough because creative
  does not consume blocks when placing. **`--stock-mode survival`** gives exact counts, chunked into
  legal stack sizes (verified: 460 stacks of orange_concrete with a final 53, summing to 29,429).
* **Non-item blocks are excluded** and listed separately (water, lava, fire, piston heads, tall
  seagrass and friends), with a note to use `buildSubstitutes` or `buildIgnoreBlocks` - `/give` cannot
  supply them, so generating those commands would just fail silently.
* `--stock-batch N` changes types per function; `--stock-out DIR` changes where it is written.
* **Creative batches clear the build's own block types first.** Creative never consumes a placed block,
  so once batch 1 has filled the 36 slots they never free up and a later batch's `/give` would drop on
  the floor, where Baritone cannot see it - an unattended build would stall permanently. Each
  `<build>_N` function therefore starts with `clear @s <type>` for every giveable type in the whole
  build, then gives its own batch, so the batch always fits. It clears **only** the build's block types,
  never a blanket `clear @s`, so tools and armour survive. Survival packs are left alone, because there
  consumption frees slots naturally. (Regenerating a creative pack therefore changes the function files
  but not `materials.txt` or `stock-commands.txt` - verified by hash.)
* **`pack_format` is the real one for the target version** - currently `107`, which is what MC 26.2
  declares in its own client jar (`version.json` -> `pack_version.data_major`; read it with
  `26.2.jar` -> `version.json`). The generated `pack.mcmeta` also keeps a wide
  `supported_formats` range (`15..9999`) so a pack still loads on other versions. Earlier packs were
  written with `46`, which was wrong for 26.2 - it loaded only because of that range.
* **Run `--stock` against a modern MC and the pack appears immediately**: no restart needed, but a
  world that is already open needs `/reload` before `/function sb:<name>_1` resolves.

## Recommended flow for a large library

1. **`--no-count-blocks` first.** On thousands of files this is near-instant and immediately shows
   how many duplicates and same-build twins you have, which is usually a big fraction.
2. **Full pass** for real block counts, then sort by Blocks to pick builds that suit a Short.
3. **`--stage --only-buildable --top N`** to put a shortlist in the instance's `schematics` folder.
   Everything else stays in your library, to be loaded through Litematica when you want it.
4. `--inspect <file>` dumps the NBT structure of any file, which is the fastest way to see why a
   specific schematic was rejected.

## Verification status

Tested: 15 assertions over synthetic fixtures generated by `tests\make-fixtures.mjs` — block counts,
dimensions, v6-vs-v7 buildability, Sponge and MCEdit parsing, duplicate detection, same-build twins,
and unreadable-file handling all matched expected values. The NBT reader was additionally validated
against **real Minecraft NBT** (`servers.dat` and a gzipped `level.dat`). Zip reading was validated
against an archive produced by Windows `Compress-Archive`, not by this tool's own code. Runs are
idempotent, confirmed over three consecutive runs.

Then it was run against a real library of 31 published `.litematic` files (the GuardianBR set), which
is where the interesting bugs were:

| Finding | Resolution |
|---|---|
| Three bogus "same build" groups | The creator's tool stamps `Name: "schematelizer"` into every file, so grouping on metadata name + dimensions lumped unrelated builds together. Grouping now uses the **filename stem** and only groups files that span **different formats** |
| 19 files showed `n/a` for blocks | They exceed the decode cap, but each carries `Metadata.TotalBlocks`. The tool now falls back to that value, labelled *(reported)* rather than presented as measured |
| Two files disagreed with their own metadata | Not a decode bug: `minecraft:void_air` was being counted as a block. Litematica's `TotalBlocks` excludes all air variants, and Baritone won't place them either. With that fixed, **all 31 files' decoded counts match their metadata exactly** |

The agreement between this tool's independent decode and the `TotalBlocks` recorded by the tools that
*wrote* the files (Litematica and bloxelizer's Schematelizer) is a strong correctness signal, and it
is now a permanent feature: every run prints a self-check line, and `count_check` in `catalog.csv`
records the comparison per file. Structural checks on the two problem files also confirmed the decode
independently — the `BlockStates` long-array length matched the expected length exactly (595,209 and
2,646,947 longs), with zero out-of-range palette indices.

`--explain FILE` prints that per-region breakdown: palette size, bit width, entries read, bit-array
length versus expected, out-of-range indices, and the most common blocks. That is the tool to reach
for if a count ever looks wrong.

Still untested: `.schem` and `.schematic` files from a real creator (only synthetic fixtures so far),
and multi-region `.litematic` files (the real library was all single-region).
