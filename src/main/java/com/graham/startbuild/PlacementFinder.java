package com.graham.startbuild;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds where a schematic sits most naturally in the loaded world, optionally matching a wish such as
 * "near a river or lake", "on a hill" or "flat open ground".
 *
 * It scores the build's REAL footprint (the columns its bottom layers occupy), not its bounding box:
 *   - every footprint column should have ground exactly under the base: no hanging over air, water or a
 *     bank edge (overhang), and no terrain poking up through the floor (buried);
 *   - no water under the footprint at all;
 *   - few trees/rocks inside the build volume (the builder does not clear them, so they would show);
 *   - the wish: water 1-6 blocks from the footprint (a bank, not a cliff 20 blocks above it), or higher than
 *     the surroundings (hill), or as flat as possible.
 * Read-only: nothing in the world is changed.
 */
final class PlacementFinder {

    enum Wish { WATER, HILL, FLAT }

    record Result(BlockPos origin, double cost, int overhang, int buried, int obstacles, int waterDist) {
    }

    static Wish parseWish(String text) {
        String t = (text == null) ? "" : text.toLowerCase(Locale.ROOT);
        if (t.matches(".*(river|lake|water|pond|shore|beach|bank|sea|ocean|coast).*")) return Wish.WATER;
        if (t.matches(".*(hill|mountain|cliff|view|high|peak|ridge).*")) return Wish.HILL;
        return Wish.FLAT;
    }

    /** @return up to `wanted` sites, best first. */
    static List<Result> find(ClientLevel level, BlockPos centre, int radius, SchematicModel m, Wish wish, int wanted) {
        // 1. One pass over the area: surface height and whether the surface is water, per column.
        int size = radius * 2 + Math.max(m.sizeX, m.sizeZ) + 16;
        int x0 = centre.getX() - radius - 8, z0 = centre.getZ() - radius - 8;
        int[] surf = new int[size * size];          // y of the topmost solid-or-fluid block; MIN = unloaded
        boolean[] water = new boolean[size * size];
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int wx = x0 + dx, wz = z0 + dz, i = dz * size + dx;
                if (!level.hasChunk(wx >> 4, wz >> 4)) {
                    surf[i] = Integer.MIN_VALUE;
                    continue;
                }
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                surf[i] = h;
                BlockState s = level.getBlockState(new BlockPos(wx, h, wz));
                water[i] = !s.getFluidState().isEmpty() && s.getFluidState().getType().isSame(Fluids.WATER);
            }
        }
        // Distance (in columns) from each column to the nearest water, capped - a cheap two-pass chamfer.
        int cap = 40;
        int[] wd = new int[size * size];
        for (int i = 0; i < wd.length; i++) wd[i] = water[i] ? 0 : cap;
        for (int pass = 0; pass < 2; pass++) {
            for (int dz = 0; dz < size; dz++) {
                for (int dx = 0; dx < size; dx++) {
                    int zz = pass == 0 ? dz : size - 1 - dz, xx = pass == 0 ? dx : size - 1 - dx;
                    int i = zz * size + xx, s = pass == 0 ? -1 : 1;
                    int nx = xx + s, nz = zz + s;
                    if (nx >= 0 && nx < size) wd[i] = Math.min(wd[i], wd[zz * size + nx] + 1);
                    if (nz >= 0 && nz < size) wd[i] = Math.min(wd[i], wd[nz * size + xx] + 1);
                }
            }
        }

        // 2. The build's real footprint: columns its bottom two layers occupy.
        List<int[]> foot = new ArrayList<>();
        for (int z = 0; z < m.sizeZ; z++) {
            for (int x = 0; x < m.sizeX; x++) {
                boolean solid = !m.at(x, 0, z).isAir() || (m.sizeY > 1 && !m.at(x, 1, z).isAir());
                if (solid) foot.add(new int[]{x, z});
            }
        }
        if (foot.isEmpty()) return List.of();
        int stride = foot.size() > 1500 ? 2 : 1;

        // 3. Score candidate corners on a 3-block grid.
        List<Result> all = new ArrayList<>();
        int[] hs = new int[foot.size()];
        for (int cz = 8; cz + m.sizeZ < size - 8; cz += 3) {
            for (int cx = 8; cx + m.sizeX < size - 8; cx += 3) {
                int n = 0;
                boolean bad = false;
                int wet = 0, near = cap;
                for (int k = 0; k < foot.size(); k += stride) {
                    int[] f = foot.get(k);
                    int i = (cz + f[1]) * size + cx + f[0];
                    if (surf[i] == Integer.MIN_VALUE) { bad = true; break; }
                    if (water[i]) wet++;
                    hs[n++] = surf[i];
                    near = Math.min(near, wd[i]);
                }
                if (bad || wet > 0) continue;
                int[] sorted = java.util.Arrays.copyOf(hs, n);
                java.util.Arrays.sort(sorted);
                int ground = sorted[n / 2];                      // base sits on the median surface
                int overhang = 0, buried = 0;
                for (int k = 0; k < n; k++) {
                    int d = hs[k] - ground;
                    if (d < 0) overhang += d * d;
                    else if (d > 0) buried += d * d;
                }
                int ring = near;                                 // columns from the footprint to water
                double cost = overhang * 3.0 + buried * 2.0;
                switch (wish) {
                    case WATER -> {
                        if (ring > 8) continue;                  // not by the water at all
                        // The bank should be low: water not far below the base.
                        cost += ring * 4.0;
                    }
                    case HILL -> {
                        double around = 0;
                        int cnt = 0;
                        for (int d = -12; d <= m.sizeX + 12; d += 6) {
                            for (int zz : new int[]{cz - 12, cz + m.sizeZ + 12}) {
                                int i = zz * size + cx + d;
                                if (i >= 0 && i < surf.length && surf[i] != Integer.MIN_VALUE) {
                                    around += surf[i];
                                    cnt++;
                                }
                            }
                        }
                        if (cnt > 0) cost -= Math.min(20, ground - around / cnt) * 40.0;
                    }
                    case FLAT -> cost += 0;
                }
                double dist = Math.hypot(x0 + cx - centre.getX(), z0 + cz - centre.getZ());
                cost += dist * 0.004;              // distance only breaks ties
                all.add(new Result(new BlockPos(x0 + cx, ground + 1, z0 + cz), cost, overhang, buried, 0, ring));
            }
        }
        all.sort((a, b) -> Double.compare(a.cost(), b.cost()));

        // 4. Only for the leaders: count trees/rocks inside the build volume (the costly check), re-rank,
        //    and keep sites that do not overlap each other.
        List<Result> top = new ArrayList<>();
        // Spread the shortlist out first: the best candidate of each non-overlapping area, up to 20 areas.
        List<Result> spread = new ArrayList<>();
        for (Result r : all) {
            boolean near = spread.stream().anyMatch(o -> Math.abs(o.origin().getX() - r.origin().getX()) < m.sizeX
                    && Math.abs(o.origin().getZ() - r.origin().getZ()) < m.sizeZ);
            if (!near) spread.add(r);
            if (spread.size() >= 20) break;
        }
        for (Result r : spread) {
            int obstacles = 0;
            for (int y = 0; y < Math.min(m.sizeY, 30); y += 2) {
                for (int z = 0; z < m.sizeZ; z += 2) {
                    for (int x = 0; x < m.sizeX; x += 2) {
                        BlockState s = level.getBlockState(r.origin().offset(x, y, z));
                        if (!s.isAir() && !s.canBeReplaced()) obstacles++;
                    }
                }
            }
            top.add(new Result(r.origin(), r.cost() + obstacles * 4.0, r.overhang(), r.buried(), obstacles * 8, r.waterDist()));
        }
        top.sort((a, b) -> Double.compare(a.cost(), b.cost()));
        List<Result> out = new ArrayList<>();
        for (Result r : top) {
            boolean overlaps = out.stream().anyMatch(o -> Math.abs(o.origin().getX() - r.origin().getX()) < m.sizeX
                    && Math.abs(o.origin().getZ() - r.origin().getZ()) < m.sizeZ);
            if (!overlaps) out.add(r);
            if (out.size() >= wanted) break;
        }
        return out;
    }
}
