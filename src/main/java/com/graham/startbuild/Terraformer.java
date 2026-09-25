package com.graham.startbuild;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans how the character reshapes the ground before building, so the build ends up IN the landscape: no
 * pad floating over a dip, no box sunk into a hillside with a cliff behind it.
 *
 * The plan is a list of target models that {@link NaturalBuilder} works through by hand, on camera:
 *   - one CLEAR part per tree in the way (worked top-down: a player felling it - leaves and trunk - one
 *     tree at a time), whole: trunk + exactly the leaves vanilla would let decay + vines/cocoa/nests;
 *   - CLEAR the ground (top-down, reads as digging): the hillside above the new ground, plants, and
 *     everything in the build's volume;
 *   - FILL (bottom-up): earth to raise dips to the pad, and a fresh top block (grass, sand, ...) on every
 *     column whose surface changed, so a cut shows grass, not bare dirt or stone.
 *
 * The new ground height ({@link #targetHeights}): flat only where the build actually stands (not its whole
 * bounding box); from there the ground eases back to the natural height along a smooth curve, with a little
 * noise so the banks wander. Each reshaped column keeps its own top block, and the banks are replanted by
 * hand with the same plants, as thickly as the untouched land around. The blend zone grows (up to {@link #MAX_RADIUS}) on steep
 * sites so there is no cliff where it ends. Water columns are left alone, and cuts never go below an
 * adjacent lake/river surface (that would let the water run in).
 */
final class Terraformer {

    /** One stage of the terraforming: a model the builder works through (clearing = break its AIR cells). */
    record Part(String label, SchematicModel model, BlockPos origin, boolean clearing) {
    }

    record Plan(List<Part> parts, int cut, int filled, int treeBlocks, int trees, int radius,
                BlockState surface, BlockState subsurface, int floodRisk, int planted, int wetFill) {
        String describe() {
            return String.format("%,d block(s) to clear (%d tree(s), %,d tree blocks), %,d to place (%d plant(s)), blend radius %d; "
                            + "ground %s over %s%s%s", cut, trees, treeBlocks, filled, planted, radius, name(surface), name(subsurface),
                    floodRisk > 0 ? "; WARNING: water above the base next to " + floodRisk + " footprint column(s)" : "",
                    wetFill > 0 ? "; " + wetFill + " fill block(s) in water" : "");
        }

        /** Hand work of the terraforming, as a multiple of the build itself. */
        double effortRatio(int buildBlocks) {
            return (cut + filled) / (double) Math.max(1, buildBlocks);
        }
    }

    static final int UNKNOWN = Integer.MIN_VALUE;
    /** The blend zone never reaches further than this from the footprint. */
    static final int MAX_RADIUS = 24;
    private static final int MAX_TREE_LOGS = 6000;

    private Terraformer() {
    }

    static Plan plan(ClientLevel level, BlockPos origin, SchematicModel m, int radius) {
        int pad = origin.getY() - 1;                        // the ground the build stands on
        int r = MAX_RADIUS;                                 // sample the widest zone; targetHeights picks the blend
        int fx1 = origin.getX(), fz1 = origin.getZ(), fx2 = fx1 + m.sizeX - 1, fz2 = fz1 + m.sizeZ - 1;
        int zx = fx1 - r, zz = fz1 - r, w = m.sizeX + 2 * r, d = m.sizeZ + 2 * r;

        // 1. The natural ground per column (trees and plants ignored), and what the ground is made of.
        int[] ground = new int[w * d];
        int[] top = new int[w * d];                        // first air above everything, leaves included
        boolean[] wet = new boolean[w * d];
        // Each column keeps its OWN ground when reshaped - grass stays grass, a sandy patch stays sand, snow
        // only where there was snow - so banks blend in (one site-wide block put beach sand into a taiga).
        BlockState[] colTop = new BlockState[w * d], colSub = new BlockState[w * d];
        boolean[] colSnow = new boolean[w * d];
        Map<BlockState, Integer> surfTally = new HashMap<>(), subTally = new HashMap<>();
        int snowCols = 0, dryCols = 0;
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int x = zx + dx, z = zz + dz, i = dz * w + dx;
                if (!PlacementFinder.loaded(level, x >> 4, z >> 4)) {
                    ground[i] = UNKNOWN;
                    continue;
                }
                int g = groundAt(level, x, z);
                ground[i] = g;
                top[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                BlockState gs = level.getBlockState(new BlockPos(x, g, z));
                wet[i] = !gs.getFluidState().isEmpty() || gs.is(Blocks.ICE);    // a frozen lake is still a lake
                if (!wet[i] && PlacementFinder.naturalGround(gs)) {
                    colTop[i] = gs.getBlock().defaultBlockState();
                    BlockState under = level.getBlockState(new BlockPos(x, g - 1, z));
                    if (under.isSolid() && PlacementFinder.naturalGround(under)) colSub[i] = under.getBlock().defaultBlockState();
                    colSnow[i] = level.getBlockState(new BlockPos(x, g + 1, z)).is(Blocks.SNOW);
                }
                if (wet[i] || distToFoot(x, z, fx1, fz1, fx2, fz2) > radius + 4) continue;   // tally near the site
                if (!PlacementFinder.naturalGround(gs)) continue;   // a structure's floor is not "the ground"
                dryCols++;
                surfTally.merge(gs.getBlock().defaultBlockState(), 1, Integer::sum);
                for (int k = 1; k <= 3; k++) {
                    BlockState below = level.getBlockState(new BlockPos(x, g - k, z));
                    if (below.isSolid()) subTally.merge(below.getBlock().defaultBlockState(), 1, Integer::sum);
                }
                if (level.getBlockState(new BlockPos(x, g + 1, z)).is(Blocks.SNOW)) snowCols++;
            }
        }
        BlockState surface = most(surfTally, Blocks.GRASS_BLOCK.defaultBlockState());
        BlockState subsurface = most(subTally, Blocks.DIRT.defaultBlockState());
        boolean snowy = dryCols > 0 && snowCols * 2 > dryCols;

        // 2. The new ground height.
        int[] noise = new int[w * d];
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                noise[dz * w + dx] = (int) Math.round(noise(zx + dx, zz + dz) * 90);   // 0..90 (hundredths)
            }
        }
        // Where the build actually stands (its bottom two layers), and how high the ground may come under
        // the rest of its bounding box (below its lowest block there): gardens, gaps and corners are then
        // shaped like the land around, not levelled into a rectangular plateau.
        boolean[] standing = new boolean[w * d];
        int[] cap = new int[w * d];
        java.util.Arrays.fill(cap, Integer.MAX_VALUE);
        boolean anyStanding = false;
        for (int sz = 0; sz < m.sizeZ; sz++) {
            for (int sx = 0; sx < m.sizeX; sx++) {
                int i = (sz + r) * w + sx + r;
                int lowest = -1;
                for (int y = 0; y < m.sizeY && lowest < 0; y++) if (!m.at(sx, y, sz).isAir()) lowest = y;
                if (lowest == 0 || lowest == 1) {
                    standing[i] = true;
                    anyStanding = true;
                }
                if (lowest >= 0) cap[i] = pad + lowest;     // ground top stays below the build's lowest block
                else cap[i] = pad + m.sizeY;                // open sky above: anything up to the build's top
            }
        }
        if (!anyStanding) {                                 // nothing in the bottom layers: level the whole box
            for (int sz = 0; sz < m.sizeZ; sz++) for (int sx = 0; sx < m.sizeX; sx++) standing[(sz + r) * w + sx + r] = true;
        }
        double[] dist = distanceField(standing, w, d);
        int[] blend = new int[1];
        int[] target = targetHeights(ground, wet, noise, dist, cap, w, d, pad, radius, blend);

        // What grows on the untouched land around (grass tufts, ferns, flowers, dry bushes - and how thickly),
        // so the reshaped ground can be replanted by hand to match and does not stand out as a bald ring.
        List<BlockState> plants = new ArrayList<>();
        int sampled = 0;
        for (int dz = 0; dz < d; dz += 2) {
            for (int dx = 0; dx < w; dx += 2) {
                int i = dz * w + dx;
                if (colTop[i] == null || target[i] != ground[i] || dist[i] == 0 || colSnow[i]) continue;
                sampled++;
                BlockState above = level.getBlockState(new BlockPos(zx + dx, ground[i] + 1, zz + dz));
                if (replantable(above)) plants.add(above.getBlock().defaultBlockState());
            }
        }
        double plantDensity = sampled == 0 ? 0 : Math.min(0.8, plants.size() / (double) sampled);

        // Water above the base right next to the footprint: digging the footprint would let it in.
        int floodRisk = 0;
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int i = dz * w + dx;
                if (!wet[i] || ground[i] == UNKNOWN || ground[i] <= pad) continue;
                if (distToFoot(zx + dx, zz + dz, fx1, fz1, fx2, fz2) <= 2) floodRisk++;
            }
        }

        // 3. The cells: clear above the new ground, fill up to it, re-surface every changed column.
        Map<BlockPos, BlockState> clear = new HashMap<>();
        Map<BlockPos, BlockState> fill = new HashMap<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        int buildTop = pad + m.sizeY + 1;
        int planted = 0;
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int x = zx + dx, z = zz + dz, i = dz * w + dx;
                int t = target[i], g = ground[i];
                if (t == UNKNOWN) continue;
                boolean foot = inFoot(x, z, fx1, fz1, fx2, fz2);
                if (!foot && t == g) continue;              // untouched ground: its plants and trees stay
                // Inside the footprint everything up to the build's top goes. Around it only the ground and
                // what grows on it: canopies of trees that stay are left alone (trees in the way are felled
                // whole below).
                int hi = foot ? Math.max(top[i], buildTop) + 1 : Math.max(g, t) + 3;
                for (int y = t + 1; y <= hi; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!foot && (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || isWood(s))) continue;
                    if (clearable(level, p, s)) clear.put(p, air);
                }
                BlockState mySurface = colTop[i] != null ? colTop[i] : surface;
                BlockState mySub = colSub[i] != null ? colSub[i] : subsurface;
                if (t < g) {
                    BlockPos p = new BlockPos(x, t, z);
                    if (!level.getBlockState(p).is(mySurface.getBlock())) fill.put(p, mySurface);
                } else if (t > g) {
                    for (int y = g + 1; y <= t; y++) fill.put(new BlockPos(x, y, z), y == t ? mySurface : mySub);
                }
                boolean snowHere = colTop[i] != null ? colSnow[i] : snowy;
                if (snowHere && !foot && t != g) {
                    fill.put(new BlockPos(x, t + 1, z), Blocks.SNOW.defaultBlockState());
                } else if (!foot && t != g && !plants.isEmpty() && hash(x * 3 + 7, z * 5 + 11) < plantDensity) {
                    // Replant by hand, as thickly and with the same mix as the untouched land around.
                    BlockState plant = plants.get((int) (hash(x * 13 + 1, z * 17 + 3) * plants.size()));
                    boolean dry = plant.is(Blocks.DEAD_BUSH) || plant.is(Blocks.SHORT_DRY_GRASS) || plant.is(Blocks.TALL_DRY_GRASS);
                    if (mySurface.is(dry ? BlockTags.SUPPORTS_DRY_VEGETATION : BlockTags.SUPPORTS_VEGETATION)) {
                        fill.put(new BlockPos(x, t + 1, z), plant);
                        planted++;
                    }
                }
            }
        }

        // 4. Whole trees, each its own part: a trunk standing where the ground changes (or anything woody in
        //    the build's way) goes with all its leaves - a half-felled tree or floating leaves would show.
        List<Map<BlockPos, BlockState>> trees = fellTrees(level, clear, ground, top, target, zx, zz, w, d,
                fx1, fz1, fx2, fz2, pad + m.sizeY + 6);
        int treeBlocks = 0;
        for (Map<BlockPos, BlockState> t : trees) {
            treeBlocks += t.size();
            for (BlockPos p : t.keySet()) clear.remove(p);  // a tree's cells belong to that tree's part only
        }

        List<Part> parts = new ArrayList<>();
        int n = 0;
        for (Map<BlockPos, BlockState> t : trees) {
            BlockPos[] o = new BlockPos[1];
            SchematicModel tm = toModel(t, o);
            if (tm != null) parts.add(new Part("terraforming: felling tree " + (++n) + "/" + trees.size(), tm, o[0], true));
        }
        BlockPos[] co = new BlockPos[1], fo = new BlockPos[1];
        SchematicModel cm = toModel(clear, co);
        if (cm != null) parts.add(new Part("terraforming: digging", cm, co[0], true));
        SchematicModel fm = toModel(fill, fo);
        if (fm != null) parts.add(new Part("terraforming: filling", fm, fo[0], false));
        // Fill that would go into standing water: filling a lake by hand is slow, unnatural to watch, and the
        // character works in the water (greenhouse_80's lake-side site).
        int wetFill = 0;
        for (BlockPos p : fill.keySet()) {
            if (!level.getBlockState(p).getFluidState().isEmpty()) wetFill++;
        }
        return new Plan(parts, clear.size() + treeBlocks, fill.size(), treeBlocks, trees.size(), blend[0],
                surface, subsurface, floodRisk, planted, wetFill);
    }

    /**
     * The new ground height per column - pure arithmetic, unit-tested.
     *
     * Flat only where the build actually stands (dist 0); from there the ground eases back to its natural
     * height along a smoothstep curve over the blend radius - gentle out of the pad, gentle into the land,
     * no crease at either end - with a little noise so the banks wander like real terrain.
     *
     * @param ground natural ground y per column (UNKNOWN = not loaded), row-major w x d
     * @param wet    column surface is water (left as it is)
     * @param noise  0..90 per column: hundredths of a block of jitter, so bank edges wander
     * @param dist   distance to the nearest column the build stands on (0 = it stands here)
     * @param cap    highest ground allowed per column (under a part of the build overhead), or MAX_VALUE
     * @param pad    the ground level under the build
     * @param minRadius the configured blend radius (the zone grows beyond it only when the site needs it)
     * @param blendOut receives the radius actually used
     * @return target height per column (UNKNOWN where ground is unknown)
     */
    static int[] targetHeights(int[] ground, boolean[] wet, int[] noise, double[] dist, int[] cap, int w, int d,
                               int pad, int minRadius, int[] blendOut) {
        // Blend radius: the smallest radius (>= minRadius, <= MAX_RADIUS) over which the height difference at
        // its edge averages at most 0.7 up per 1 across - the smoothstep's steepest point is then ~1:1.
        int r = Math.max(2, Math.min(minRadius, MAX_RADIUS));
        for (int cand = r; cand <= MAX_RADIUS; cand++) {
            double maxEdge = 0;
            for (int i = 0; i < w * d; i++) {
                if (ground[i] == UNKNOWN || wet[i]) continue;
                if (dist[i] >= cand - 1 && dist[i] <= cand) maxEdge = Math.max(maxEdge, Math.abs(ground[i] - pad));
            }
            r = cand;
            if (maxEdge / cand <= 0.7) break;
        }

        int[] target = new int[w * d];
        for (int i = 0; i < w * d; i++) {
            if (ground[i] == UNKNOWN) {
                target[i] = UNKNOWN;
                continue;
            }
            if (dist[i] == 0) {
                target[i] = pad;
                continue;
            }
            if (wet[i] || dist[i] > r) {
                target[i] = ground[i];                  // water, and land beyond the blend zone: untouched
            } else {
                double s = dist[i] / r;
                double ease = s * s * (3 - 2 * s);      // smoothstep: 0 at the pad, 1 at the zone's edge
                int diff = ground[i] - pad;
                int t = (int) Math.round(pad + diff * ease + (noise[i] - 45) / 100.0);
                target[i] = Math.max(Math.min(pad, ground[i]), Math.min(Math.max(pad, ground[i]), t));
            }
            if (target[i] > cap[i]) target[i] = cap[i];
        }
        // Never dig below the surface of water next to a cut: it would pour into the site.
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                if (target[i] == UNKNOWN || target[i] >= ground[i] || dist[i] == 0) continue;
                for (int oz = -2; oz <= 2; oz++) {
                    for (int ox = -2; ox <= 2; ox++) {
                        int nx = x + ox, nz = z + oz;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                        int j = nz * w + nx;
                        if (wet[j] && ground[j] != UNKNOWN) target[i] = Math.min(ground[i], Math.max(target[i], ground[j]));
                    }
                }
            }
        }
        if (blendOut != null) blendOut[0] = r;
        return target;
    }

    /** Distance (in columns, ~Euclidean: 1 straight, 1.414 diagonal) from every column to the nearest standing one. */
    static double[] distanceField(boolean[] standing, int w, int d) {
        double[] dist = new double[w * d];
        java.util.Arrays.fill(dist, Double.MAX_VALUE / 4);
        for (int i = 0; i < w * d; i++) if (standing[i]) dist[i] = 0;
        double diag = Math.sqrt(2);
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                if (x > 0) dist[i] = Math.min(dist[i], dist[i - 1] + 1);
                if (z > 0) dist[i] = Math.min(dist[i], dist[i - w] + 1);
                if (x > 0 && z > 0) dist[i] = Math.min(dist[i], dist[i - w - 1] + diag);
                if (x < w - 1 && z > 0) dist[i] = Math.min(dist[i], dist[i - w + 1] + diag);
            }
        }
        for (int z = d - 1; z >= 0; z--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = z * w + x;
                if (x < w - 1) dist[i] = Math.min(dist[i], dist[i + 1] + 1);
                if (z < d - 1) dist[i] = Math.min(dist[i], dist[i + w] + 1);
                if (x < w - 1 && z < d - 1) dist[i] = Math.min(dist[i], dist[i + w + 1] + diag);
                if (x > 0 && z < d - 1) dist[i] = Math.min(dist[i], dist[i + w - 1] + diag);
            }
        }
        return dist;
    }
    // ------------------------------------------------------------------ trees

    /** @return the trees to fell, each as its own set of cells (top-down worked by its own part). */
    private static List<Map<BlockPos, BlockState>> fellTrees(ClientLevel level, Map<BlockPos, BlockState> clear,
                                                             int[] ground, int[] top, int[] target, int zx, int zz,
                                                             int w, int d, int fx1, int fz1, int fx2, int fz2, int boxTop) {
        BlockState air = Blocks.AIR.defaultBlockState();
        Set<BlockPos> seeds = new HashSet<>();
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int x = zx + dx, z = zz + dz, i = dz * w + dx;
                if (target[i] == UNKNOWN) continue;
                boolean changed = inFoot(x, z, fx1, fz1, fx2, fz2) || target[i] != ground[i];
                boolean inBox = x >= fx1 - 2 && x <= fx2 + 2 && z >= fz1 - 2 && z <= fz2 + 2;
                if (!changed && !inBox) continue;
                for (int y = Math.min(target[i], ground[i]) + 1; y <= top[i]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    // Felled: a tree whose TRUNK stands where the ground changes, or anything woody inside
                    // the build's space. A neighbour's branch merely hanging over a reshaped bank is not
                    // reason enough (that used to fell whole neighbouring trees - slow, and bare-looking).
                    boolean trunkBase = isWood(s) && !isWood(level.getBlockState(p.below()));
                    if (isWood(s) && ((changed && trunkBase) || (inBox && y <= boxTop))) {
                        seeds.add(p);
                    } else if (inBox && y <= boxTop && s.is(BlockTags.LEAVES)) {
                        clear.put(p, air);                  // overhanging leaves inside the build's space
                    }
                }
            }
        }
        // Each tree: the wood connected to a seed (logs touching logs, diagonals included).
        List<Set<BlockPos>> trunks = new ArrayList<>();
        Set<BlockPos> allWood = new HashSet<>();
        for (BlockPos seed : seeds) {
            if (allWood.contains(seed)) continue;
            Set<BlockPos> wood = new HashSet<>();
            Deque<BlockPos> q = new ArrayDeque<>();
            wood.add(seed);
            q.add(seed);
            while (!q.isEmpty() && allWood.size() + wood.size() < MAX_TREE_LOGS) {
                BlockPos c = q.poll();
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            BlockPos n = c.offset(ox, oy, oz);
                            if (wood.contains(n) || allWood.contains(n) || !isWood(level.getBlockState(n))) continue;
                            if (n.getY() <= groundOf(n, ground, zx, zz, w, d)) continue;   // not into the ground
                            wood.add(n);
                            q.add(n);
                        }
                    }
                }
            }
            // A tree has leaves on it (or is a giant mushroom). Bare logs - a cabin, a fence of logs, an
            // earlier build - are not trees and are never felled as such.
            boolean tree = false;
            for (BlockPos p : wood) {
                if (!level.getBlockState(p).is(BlockTags.LOGS)) { tree = true; break; }
                for (Direction dir : Direction.values()) {
                    if (level.getBlockState(p.relative(dir)).is(BlockTags.LEAVES)) { tree = true; break; }
                }
                if (tree) break;
            }
            allWood.addAll(wood);
            if (tree) trunks.add(wood);
        }
        List<Map<BlockPos, BlockState>> trees = new ArrayList<>();
        if (trunks.isEmpty()) return trees;
        for (Set<BlockPos> t : trunks) {
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            for (BlockPos p : t) cells.put(p, air);
            trees.add(cells);
        }

        // Leaves: exactly the ones vanilla would let decay once that wood is gone - no remaining log within
        // 6 steps through leaves. Each goes with the felled trunk that reaches it first.
        int e = LeavesBlock.DECAY_DISTANCE;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : allWood) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        // Supported leaves: reachable from a log that stays, searched from logs up to 2*e away.
        Set<BlockPos> supported = new HashSet<>();
        Map<BlockPos, Integer> dist = new HashMap<>();
        Deque<BlockPos> bq = new ArrayDeque<>();
        for (int x = minX - 2 * e; x <= maxX + 2 * e; x++) {
            for (int y = minY - 2 * e; y <= maxY + 2 * e; y++) {
                for (int z = minZ - 2 * e; z <= maxZ + 2 * e; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!allWood.contains(p) && level.getBlockState(p).is(BlockTags.LOGS)) {
                        dist.put(p, 0);
                        bq.add(p);
                    }
                }
            }
        }
        while (!bq.isEmpty()) {
            BlockPos c = bq.poll();
            int dc = dist.get(c);
            if (dc >= e) continue;
            for (Direction dir : Direction.values()) {
                BlockPos n = c.relative(dir);
                if (dist.containsKey(n) || !level.getBlockState(n).is(BlockTags.LEAVES)) continue;
                dist.put(n, dc + 1);
                supported.add(n);
                bq.add(n);
            }
        }
        // Unsupported leaves, assigned to trees by a BFS out from each felled trunk (first to arrive wins).
        Map<BlockPos, Integer> owner = new HashMap<>();
        Deque<BlockPos> lq = new ArrayDeque<>();
        for (int k = 0; k < trunks.size(); k++) {
            for (BlockPos p : trunks.get(k)) {
                owner.put(p, k);
                lq.add(p);
            }
        }
        while (!lq.isEmpty()) {
            BlockPos c = lq.poll();
            int k = owner.get(c);
            for (Direction dir : Direction.values()) {
                BlockPos n = c.relative(dir);
                if (owner.containsKey(n) || supported.contains(n)) continue;
                if (n.getX() < minX - e || n.getX() > maxX + e || n.getY() < minY - e || n.getY() > maxY + e
                        || n.getZ() < minZ - e || n.getZ() > maxZ + e) continue;
                BlockState s = level.getBlockState(n);
                if (!s.is(BlockTags.LEAVES)) continue;
                if (s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT)) continue;
                owner.put(n, k);
                trees.get(k).put(n, air);
                lq.add(n);
            }
        }
        // Things hanging on or sitting in each felled tree (vines, cocoa, nests, snow on the leaves).
        for (Map<BlockPos, BlockState> tree : trees) {
            Deque<BlockPos> hang = new ArrayDeque<>(tree.keySet());
            int processed = 0;
            while (!hang.isEmpty() && processed++ < 50_000) {
                BlockPos c = hang.poll();
                for (Direction dir : Direction.values()) {
                    BlockPos n = c.relative(dir);
                    if (tree.containsKey(n)) continue;
                    BlockState s = level.getBlockState(n);
                    if (s.is(Blocks.VINE) || s.is(Blocks.COCOA) || s.is(Blocks.BEE_NEST) || s.is(Blocks.PALE_HANGING_MOSS)
                            || (s.is(Blocks.SNOW) && dir == Direction.UP)) {
                        tree.put(n, air);
                        hang.add(n);
                    }
                }
            }
        }
        return trees;
    }

    private static int groundOf(BlockPos p, int[] ground, int zx, int zz, int w, int d) {
        int dx = p.getX() - zx, dz = p.getZ() - zz;
        if (dx < 0 || dz < 0 || dx >= w || dz >= d) return Integer.MIN_VALUE;   // outside the zone: any height
        int g = ground[dz * w + dx];
        return g == UNKNOWN ? Integer.MIN_VALUE : g;
    }

    // ------------------------------------------------------------------ helpers

    /** Single-block ground plants the character can put back by hand (double-height ones as their lower half). */
    private static boolean replantable(BlockState s) {
        if (s.is(BlockTags.SMALL_FLOWERS)) return true;
        if (s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.BUSH) || s.is(Blocks.FIREFLY_BUSH)
                || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.SHORT_DRY_GRASS)) return true;
        // Double-height plants: only the lower half is sampled (placing it puts both halves down).
        return (s.is(Blocks.TALL_GRASS) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.TALL_DRY_GRASS))
                && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
    }

    /** Tree trunks and giant mushrooms: felled whole. */
    private static boolean isWood(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || s.is(Blocks.RED_MUSHROOM_BLOCK);
    }

    /** The natural ground: the top solid block, looking through trees, cacti, bamboo and giant mushrooms. */
    static int groundAt(ClientLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        while (y > level.getMinY()) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (isWood(s) || s.is(BlockTags.LEAVES) || s.is(Blocks.BEE_NEST) || s.is(Blocks.CACTUS)
                    || s.is(Blocks.BAMBOO) || s.isAir() || (!s.blocksMotion() && s.getFluidState().isEmpty())) {
                y--;
            } else {
                break;
            }
        }
        return y;
    }

    /** Anything a player can break by hand: not air, not water/lava, not bedrock-like. */
    private static boolean clearable(ClientLevel level, BlockPos p, BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && s.getDestroySpeed(level, p) >= 0;
    }

    static boolean inFoot(int x, int z, int fx1, int fz1, int fx2, int fz2) {
        return x >= fx1 && x <= fx2 && z >= fz1 && z <= fz2;
    }

    static double distToFoot(int x, int z, int fx1, int fz1, int fx2, int fz2) {
        int dx = Math.max(0, Math.max(fx1 - x, x - fx2));
        int dz = Math.max(0, Math.max(fz1 - z, z - fz2));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Smooth value noise in [0, 1) on a 5-block lattice, so bank edges wander a little. */
    static double noise(int x, int z) {
        int cx = Math.floorDiv(x, 5), cz = Math.floorDiv(z, 5);
        double fx = (x - cx * 5) / 5.0, fz = (z - cz * 5) / 5.0;
        double a = hash(cx, cz), b = hash(cx + 1, cz), c = hash(cx, cz + 1), e = hash(cx + 1, cz + 1);
        double top = a + (b - a) * fx, bot = c + (e - c) * fx;
        return top + (bot - top) * fz;
    }

    private static double hash(int x, int z) {
        long h = x * 73856093L ^ z * 19349663L;
        h = (h ^ (h >>> 13)) * 0x5bd1e995L;
        h ^= h >>> 15;
        return (h & 0xffff) / 65536.0;
    }

    private static BlockState most(Map<BlockState, Integer> tally, BlockState fallback) {
        BlockState best = fallback;
        int n = 0;
        for (Map.Entry<BlockState, Integer> e : tally.entrySet()) {
            BlockState s = e.getKey();
            // Only real ground blocks: never pick a log/leaf/ore that slipped into the sample.
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.getBlock().asItem() == Items.AIR) continue;
            if (e.getValue() > n) {
                n = e.getValue();
                best = s;
            }
        }
        return best;
    }

    private static SchematicModel toModel(Map<BlockPos, BlockState> cells, BlockPos[] originOut) {
        if (cells.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells.keySet()) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        SchematicModel m = SchematicModel.blank(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        for (Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
            BlockPos p = e.getKey();
            m.states[m.index(p.getX() - minX, p.getY() - minY, p.getZ() - minZ)] = e.getValue();
        }
        m.solidCount = cells.size();
        originOut[0] = new BlockPos(minX, minY, minZ);
        return m;
    }

    private static String name(BlockState s) {
        return s.getBlock().toString().replace("Block{minecraft:", "").replace("}", "");
    }
}
