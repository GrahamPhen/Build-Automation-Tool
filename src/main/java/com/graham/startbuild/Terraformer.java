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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plans how the character reshapes the ground before building, so the build ends up IN the landscape: no
 * pad floating over a dip, no box sunk into a hillside with a cliff behind it.
 *
 * The plan is two target models that {@link NaturalBuilder} works through by hand, on camera:
 *   - CLEAR (worked top-down, so it reads as digging/felling): everything standing above the new ground -
 *     the hillside inside and around the footprint, plants, and whole trees in the way (logs, the leaves
 *     that would decay without them, vines);
 *   - FILL (worked bottom-up): earth to raise dips to the pad, and a fresh top block (grass, sand, ...) on
 *     every column whose surface changed, so a cut shows grass, not bare dirt or stone.
 *
 * The new ground height: the footprint is flat at the build's base; around it the ground eases back to
 * the natural height within {@code radius} blocks, never steeper than the slope the site needs, with a
 * little noise so the banks are not a perfect cone. Water columns are left alone, and cuts never go below
 * an adjacent lake/river surface (that would let the water run in).
 */
final class Terraformer {

    record Plan(SchematicModel clear, BlockPos clearOrigin, SchematicModel fill, BlockPos fillOrigin,
                int cut, int filled, int treeBlocks, BlockState surface, BlockState subsurface) {
        String describe() {
            return String.format("%,d block(s) to clear (%,d of them trees), %,d to place; ground %s over %s",
                    cut, treeBlocks, filled, name(surface), name(subsurface));
        }

        boolean isEmpty() {
            return clear == null && fill == null;
        }
    }

    private static final int UNKNOWN = Integer.MIN_VALUE;
    private static final int MAX_TREE_LOGS = 6000;

    private Terraformer() {
    }

    static Plan plan(ClientLevel level, BlockPos origin, SchematicModel m, int radius) {
        int pad = origin.getY() - 1;                        // the ground the build stands on
        int r = Math.max(2, radius);
        int fx1 = origin.getX(), fz1 = origin.getZ(), fx2 = fx1 + m.sizeX - 1, fz2 = fz1 + m.sizeZ - 1;
        int zx = fx1 - r, zz = fz1 - r, w = m.sizeX + 2 * r, d = m.sizeZ + 2 * r;

        // 1. The natural ground per column (trees and plants ignored), and what the ground is made of.
        int[] ground = new int[w * d];
        int[] top = new int[w * d];                        // first air above everything, leaves included
        boolean[] wet = new boolean[w * d];
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
                wet[i] = !gs.getFluidState().isEmpty();
                if (wet[i]) continue;
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

        // 2. The new ground height. The slope is as gentle as the site allows: 1 up per 2 across, steeper
        //    only when the height difference at the edge of the zone would not fit otherwise.
        double maxEdge = 0;
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int i = dz * w + dx;
                if (ground[i] == UNKNOWN || wet[i]) continue;
                if (distToFoot(zx + dx, zz + dz, fx1, fz1, fx2, fz2) >= r - 1) {
                    maxEdge = Math.max(maxEdge, Math.abs(ground[i] - pad));
                }
            }
        }
        double slope = Math.min(1.5, Math.max(0.5, maxEdge / r));
        int[] target = new int[w * d];
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int x = zx + dx, z = zz + dz, i = dz * w + dx;
                if (ground[i] == UNKNOWN) {
                    target[i] = UNKNOWN;
                } else if (inFoot(x, z, fx1, fz1, fx2, fz2)) {
                    target[i] = pad;
                } else if (wet[i]) {
                    target[i] = ground[i];
                } else {
                    double dist = distToFoot(x, z, fx1, fz1, fx2, fz2);
                    int allowed = (int) Math.floor(dist * slope + noise(x, z) * 0.9);
                    int diff = ground[i] - pad;
                    target[i] = pad + Math.max(-allowed, Math.min(allowed, diff));
                }
            }
        }
        // Never dig below the surface of water next to a cut: it would pour into the site.
        for (int dz = 0; dz < d; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int i = dz * w + dx;
                if (target[i] == UNKNOWN || target[i] >= ground[i]
                        || inFoot(zx + dx, zz + dz, fx1, fz1, fx2, fz2)) continue;
                for (int oz = -2; oz <= 2; oz++) {
                    for (int ox = -2; ox <= 2; ox++) {
                        int nx = dx + ox, nz = dz + oz;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                        int j = nz * w + nx;
                        if (wet[j] && ground[j] != UNKNOWN) target[i] = Math.min(ground[i], Math.max(target[i], ground[j]));
                    }
                }
            }
        }

        // 3. The cells: clear above the new ground, fill up to it, re-surface every changed column.
        Map<BlockPos, BlockState> clear = new HashMap<>();
        Map<BlockPos, BlockState> fill = new HashMap<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        int buildTop = pad + m.sizeY + 1;
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
                    if (!foot && (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES))) continue;   // trees: whole, below
                    if (clearable(level, p, s)) clear.put(p, air);
                }
                if (t < g) {
                    BlockPos p = new BlockPos(x, t, z);
                    if (!level.getBlockState(p).is(surface.getBlock())) fill.put(p, surface);
                } else if (t > g) {
                    for (int y = g + 1; y <= t; y++) fill.put(new BlockPos(x, y, z), y == t ? surface : subsurface);
                }
                if (snowy && !foot && t != g) fill.put(new BlockPos(x, t + 1, z), Blocks.SNOW.defaultBlockState());
            }
        }

        // 4. Whole trees: a trunk standing where the ground changes (or anything woody in the build's way)
        //    goes with all its leaves - a half-felled tree or floating leaves would give the game away.
        int before = clear.size();
        fellTrees(level, clear, ground, top, target, zx, zz, w, d, fx1, fz1, fx2, fz2, pad + m.sizeY + 6);
        int treeBlocks = clear.size() - before;
        BlockPos[] co = new BlockPos[1], fo = new BlockPos[1];
        SchematicModel cm = toModel(clear, co);
        SchematicModel fm = toModel(fill, fo);
        return new Plan(cm, co[0], fm, fo[0], clear.size(), fill.size(), treeBlocks, surface, subsurface);
    }

    // ------------------------------------------------------------------ trees

    private static void fellTrees(ClientLevel level, Map<BlockPos, BlockState> clear, int[] ground, int[] top,
                                  int[] target, int zx, int zz, int w, int d,
                                  int fx1, int fz1, int fx2, int fz2, int boxTop) {
        BlockState air = Blocks.AIR.defaultBlockState();
        Set<BlockPos> logs = new HashSet<>();
        Deque<BlockPos> q = new ArrayDeque<>();
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
                    if (s.is(BlockTags.LOGS) && (changed || y <= boxTop)) {
                        if (logs.add(p)) q.add(p);
                    } else if (inBox && y <= boxTop && s.is(BlockTags.LEAVES)) {
                        clear.put(p, air);                  // overhanging leaves inside the build's space
                    }
                }
            }
        }
        // The whole trunk and its branches (logs touching logs, diagonals included).
        while (!q.isEmpty() && logs.size() < MAX_TREE_LOGS) {
            BlockPos c = q.poll();
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        BlockPos n = c.offset(ox, oy, oz);
                        if (!logs.contains(n) && level.getBlockState(n).is(BlockTags.LOGS)
                                && n.getY() > groundOf(n, ground, zx, zz, w, d)) {
                            logs.add(n);
                            q.add(n);
                        }
                    }
                }
            }
        }
        if (logs.isEmpty()) return;
        for (BlockPos p : logs) clear.put(p, air);

        // Leaves: exactly the ones vanilla would let decay once those logs are gone - no log left within
        // 6 steps through leaves. Everything else (neighbouring trees) stays.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : logs) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int e = LeavesBlock.DECAY_DISTANCE;
        minX -= e; minY -= e; minZ -= e; maxX += e; maxY += e; maxZ += e;
        // Supported = reachable from a remaining log within 6 steps, searched from logs up to 13 away.
        Map<BlockPos, Integer> dist = new HashMap<>();
        Deque<BlockPos> bq = new ArrayDeque<>();
        for (int x = minX - e; x <= maxX + e; x++) {
            for (int y = minY - e; y <= maxY + e; y++) {
                for (int z = minZ - e; z <= maxZ + e; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!logs.contains(p) && level.getBlockState(p).is(BlockTags.LOGS)) {
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
                bq.add(n);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!s.is(BlockTags.LEAVES) || dist.containsKey(p)) continue;
                    if (s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT)) continue;
                    clear.put(p, air);
                }
            }
        }
        // Things hanging on or sitting in the felled tree (vines, cocoa, nests, snow on the leaves).
        Deque<BlockPos> hang = new ArrayDeque<>(clear.keySet());
        Set<BlockPos> seen = new HashSet<>(clear.keySet());
        while (!hang.isEmpty() && seen.size() < 200_000) {
            BlockPos c = hang.poll();
            for (Direction dir : Direction.values()) {
                BlockPos n = c.relative(dir);
                if (!seen.add(n)) continue;
                BlockState s = level.getBlockState(n);
                if (s.is(Blocks.VINE) || s.is(Blocks.COCOA) || s.is(Blocks.BEE_NEST) || s.is(Blocks.PALE_HANGING_MOSS)
                        || (s.is(Blocks.SNOW) && dir == Direction.UP)) {
                    clear.put(n, air);
                    hang.add(n);
                }
            }
        }
    }

    private static int groundOf(BlockPos p, int[] ground, int zx, int zz, int w, int d) {
        int dx = p.getX() - zx, dz = p.getZ() - zz;
        if (dx < 0 || dz < 0 || dx >= w || dz >= d) return Integer.MIN_VALUE;   // outside the zone: any height
        int g = ground[dz * w + dx];
        return g == UNKNOWN ? Integer.MIN_VALUE : g;
    }

    // ------------------------------------------------------------------ helpers

    /** The natural ground: the top solid block, looking through trees, cacti, bamboo and giant mushrooms. */
    static int groundAt(ClientLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        while (y > level.getMinY()) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.BEE_NEST) || s.is(Blocks.CACTUS)
                    || s.is(Blocks.BAMBOO) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
                    || s.is(Blocks.RED_MUSHROOM_BLOCK) || (s.isAir()) || (!s.blocksMotion() && s.getFluidState().isEmpty())) {
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

    private static boolean inFoot(int x, int z, int fx1, int fz1, int fx2, int fz2) {
        return x >= fx1 && x <= fx2 && z >= fz1 && z <= fz2;
    }

    private static double distToFoot(int x, int z, int fx1, int fz1, int fx2, int fz2) {
        int dx = Math.max(0, Math.max(fx1 - x, x - fx2));
        int dz = Math.max(0, Math.max(fz1 - z, z - fz2));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Smooth value noise in [0, 1) on a 5-block lattice, so bank edges wander a little. */
    private static double noise(int x, int z) {
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
