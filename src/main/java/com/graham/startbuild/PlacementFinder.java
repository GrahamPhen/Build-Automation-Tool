package com.graham.startbuild;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
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

    /** Wish words -> biome id fragments ("snowy" -> snowy_plains, snowy_taiga, ...). Empty = any biome. */
    private static final String[][] BIOME_WORDS = {
            {"snow|snowy|winter", "snowy"}, {"ice|icy|frozen", "frozen"}, {"ice spikes", "ice_spikes"},
            {"desert|sand dune", "desert"}, {"badlands|mesa|canyon", "badlands"}, {"jungle|bamboo", "jungle"},
            {"savanna|acacia", "savanna"}, {"taiga|pine|spruce", "taiga"}, {"birch", "birch"},
            {"dark forest|roofed", "dark_forest"}, {"cherry|sakura|pink", "cherry"}, {"flower", "flower"},
            {"forest|woods|woodland", "forest"}, {"plains|grassland|field", "plains"}, {"meadow", "meadow"},
            {"swamp|marsh|bog|mangrove", "swamp"}, {"mushroom", "mushroom"}, {"grove", "grove"},
    };

    static List<String> biomeWords(String text) {
        String t = (text == null) ? "" : text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String[] w : BIOME_WORDS) {
            if (t.matches(".*\\b(" + w[0] + ")\\b.*") && !out.contains(w[1])) out.add(w[1]);
        }
        // "dark forest" / "ice spikes" are more specific than the generic word they contain.
        if (out.contains("dark_forest")) out.remove("forest");
        if (out.contains("ice_spikes")) out.remove("frozen");
        return out;
    }

    static boolean biomeMatches(Holder<Biome> biome, List<String> words) {
        String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        // A build cannot stand in an ocean or a river ("frozen" used to find frozen_ocean).
        if (path.contains("ocean") || path.contains("river")) return false;
        // "plains" must not land in snowy_plains: cold variants only when the wish asked for cold.
        boolean cold = path.contains("snowy") || path.contains("frozen") || path.contains("ice");
        if (cold && !words.contains("snowy") && !words.contains("frozen") && !words.contains("ice_spikes")) return false;
        for (String w : words) {
            if (path.contains(w)) return true;
        }
        return false;
    }

    /**
     * True once the chunk's real data has reached the client. ClientLevel.hasChunk is not enough: the client
     * hands out an empty placeholder chunk for anything not received yet, whose ground reads as the world
     * bottom (y -64) - that made sites land in caves.
     */
    static boolean loaded(ClientLevel level, int cx, int cz) {
        LevelChunk c = level.getChunkSource().getChunk(cx, cz, false);
        return c != null && !(c instanceof EmptyLevelChunk);
    }

    /**
     * Water whose surface is at or above the base within 3 blocks of the footprint: digging the site out
     * would let it pour in on camera, so such a site is not offered.
     */
    private static boolean waterAboveBase(int[] surf, boolean[] water, int size, int cx, int cz, int sx, int sz, int ground) {
        for (int z = cz - 3; z < cz + sz + 3; z += 2) {
            for (int x = cx - 3; x < cx + sx + 3; x += 2) {
                if (x >= cx && x < cx + sx && z >= cz && z < cz + sz) continue;
                if (x < 0 || z < 0 || x >= size || z >= size) continue;
                int i = z * size + x;
                if (water[i] && surf[i] >= ground) return true;
            }
        }
        return false;
    }

    /**
     * Natural terrain a site may stand on: soil, sand, stone, gravel, clay, snow, ice, terracotta... Anything
     * else at ground level (bricks, planks, a path, a build's floor) means a structure is there.
     */
    static boolean naturalGround(BlockState s) {
        return s.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
                || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.ICE)
                || s.is(BlockTags.SNOW) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.MUD)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.MOSS_BLOCK);
    }

    /** Count of "built" columns in [x1..x2] x [z1..z2] (clipped to the grid), from the prefix sums. */
    private static int builtIn(int[] sum, int size, int x1, int z1, int x2, int z2) {
        x1 = Math.max(0, x1); z1 = Math.max(0, z1); x2 = Math.min(size - 1, x2); z2 = Math.min(size - 1, z2);
        if (x1 > x2 || z1 > z2) return 0;
        int w = size + 1;
        return sum[(z2 + 1) * w + x2 + 1] - sum[z1 * w + x2 + 1] - sum[(z2 + 1) * w + x1] + sum[z1 * w + x1];
    }

    /** @return up to `wanted` sites, best first. */
    static List<Result> find(ClientLevel level, BlockPos centre, int radius, SchematicModel m, Wish wish, int wanted) {
        // 1. One pass over the area: surface height and whether the surface is water, per column.
        int size = radius * 2 + Math.max(m.sizeX, m.sizeZ) + 16;
        int x0 = centre.getX() - radius - 8, z0 = centre.getZ() - radius - 8;
        int[] surf = new int[size * size];          // y of the natural ground (through trees); MIN = unloaded
        boolean[] water = new boolean[size * size];
        boolean[] built = new boolean[size * size]; // the top block is not natural terrain: a structure
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int wx = x0 + dx, wz = z0 + dz, i = dz * size + dx;
                if (!loaded(level, wx >> 4, wz >> 4)) {
                    surf[i] = Integer.MIN_VALUE;
                    continue;
                }
                int h = Terraformer.groundAt(level, wx, wz);
                if (h < level.getMinY()) {               // no ground at all (void / not really there)
                    surf[i] = Integer.MIN_VALUE;
                    continue;
                }
                surf[i] = h;
                BlockState s = level.getBlockState(new BlockPos(wx, h, wz));
                water[i] = !s.getFluidState().isEmpty() && s.getFluidState().getType().isSame(Fluids.WATER);
                built[i] = s.getFluidState().isEmpty() && !naturalGround(s);
            }
        }
        // Prefix sums over "built", so "any structure near this footprint?" is four lookups.
        int[] builtSum = new int[(size + 1) * (size + 1)];
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                builtSum[(dz + 1) * (size + 1) + dx + 1] = (built[dz * size + dx] ? 1 : 0)
                        + builtSum[dz * (size + 1) + dx + 1] + builtSum[(dz + 1) * (size + 1) + dx] - builtSum[dz * (size + 1) + dx];
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
                // Never on or next to anything man-made (an earlier build, a village): the character would
                // tear it down as "terrain".
                if (builtIn(builtSum, size, cx - 4, cz - 4, cx + m.sizeX + 3, cz + m.sizeZ + 3) > 0) continue;
                int[] sorted = java.util.Arrays.copyOf(hs, n);
                java.util.Arrays.sort(sorted);
                int ground = sorted[n / 2];                      // base sits on the median surface
                if (waterAboveBase(surf, water, size, cx, cz, m.sizeX, m.sizeZ, ground)) continue;
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
                                int col = cx + d;
                                if (zz < 0 || zz >= size || col < 0 || col >= size) continue;   // no row wrap-around
                                int i = zz * size + col;
                                if (surf[i] != Integer.MIN_VALUE) {
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
