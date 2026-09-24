# Goals

## 1. The goal of this tool

**Make a Minecraft character build a whole imported schematic from one command, while Flashback records
it, so the result can be cut into a YouTube Short.**

```
/startbuild place haunted_80
```

That single command should:

1. load the `.litematic` into Litematica and place it at the player's position,
2. start a Flashback recording,
3. have **Baritone** build the entire structure block by block — the character walking, aiming and
   swinging, i.e. real player mechanics,
4. keep the character supplied with every block type it needs (auto-restock),
5. survive stalls and material shortages unattended,
6. stop the recording a fixed delay after the **last block is placed**,
7. leave a build that matches the schematic exactly.

It is a client-side **Fabric** mod. Nothing extra is required in the world beyond a flat site.

### Why Baritone and not the Litematica printer

The printer places blocks instantly with the player standing still — no walking, no arm swing. On camera
that reads as magic rather than building. The explicit requirement was:

> "i dont want the overlay seen in the video i want it to look like a real build."

So the tool drives Baritone (genuine click-to-place) and switches off every in-world mod overlay
(`renderPath`, `renderGoal`, `renderSelection`) so nothing mod-related appears in frame. Shaders are
applied at **replay/export** time in Flashback, not while building.

---

## 2. Requirements that accumulated during development

These were all stated by the project owner at various points, and several of them conflict — see
`PROBLEM.md` for where they currently collide.

| # | Requirement | Consequence |
|---|---|---|
| 1 | **No printer** — it must look like the character is really building | Baritone only |
| 2 | **No in-world overlays on camera** | Baritone render settings forced off |
| 3 | **No skipped blocks.** "i do not want us to skip a single block - find a workaround!" | `skipFailedLayers` explicitly rejected |
| 4 | **No stalls** — it must run unattended to completion | stall detection + recovery, material restock |
| 5 | **The finished build must match the schematic** | verified by diffing the world against the schematic |
| 6 | **Nothing pre-placed.** "every individual block must be placed by my character … if they are pre-placed itll look fake on the video" | rules out `/setblock`, which removed the only workaround that was closing layers (this is the current blocker) |

---

## 3. 2D pictures → 3D schematics

> **NOT YET DOCUMENTED — I could not recover this discussion.**
>
> This section is a placeholder. A search of this repository, the full `HANDOFF.md`, the session handoff
> and the project notes turned up **no written record** of a conversation about turning 2D pictures into
> 3D schematics, and it is not in my context for this session, so I am not going to invent it.
>
> What I can state without guessing is only the obvious shape of the idea: taking a flat 2D image and
> producing a Minecraft build (and therefore a `.litematic` this tool could then build) from it — for
> example by mapping image pixels to a block palette and using brightness/height to give the result
> depth, in the same way the existing `tools/schematic-catalog.mjs` already shades a schematic preview
> "by height so the render reads as a heightmap".
>
> **To be filled in, please confirm:**
> - where the pictures come from (a photo of a real building? AI-generated art? pixel art? a floor plan?)
> - the intended output (a flat 1-block-thick wall relief, a hollow shell, a fully modelled building?)
> - whether it should be faithful to the image's colours (block-palette matching) or only its silhouette
> - what size/detail the result should be, and whether it must be buildable by Baritone afterwards
>   (which, per `PROBLEM.md`, constrains overhangs and orientations heavily)
> - whether this is a separate tool from the builder, or a stage that feeds it

---

## 4. Where things stand

The builder goal (section 1) is **not yet met**: the mod builds layer 0 perfectly but stalls on the first
layer containing floating cells or non-default block states. That blocker, its measured evidence, the
history of attempted fixes, and what a real solution requires are all written up in
**[PROBLEM.md](./PROBLEM.md)**.
