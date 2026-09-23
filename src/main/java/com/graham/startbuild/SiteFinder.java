package com.graham.startbuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks a place in a normal world for a schematic to be built, so builds sit in the landscape instead of
 * on a flat test plane.
 *
 * Read-only: this only samples terrain and scores it. Nothing is changed here.
 *
 * For every candidate footprint the terrain surface is sampled on a grid, the median surface height
 * becomes the build's base level, and then:
 *
 *   spread  - max minus min surface height. The main signal: a small spread means the build sits
 *             naturally without a big cut or a big plinth.
 *   clear   - blocks of terrain standing above the base, which have to be cut away.
 *   fill    - depth of gaps below the base, which have to be built up. Weighted above clearing, because
 *             a visible plinth reads as artificial.
 *   wet     - columns whose surface is fluid; a site that is a fifth water is rejected outright.
 *   distance- distance from the player, so it does not pick something across the map.
 *
 * Sampling uses MOTION_BLOCKING_NO_LEAVES so tree canopy does not read as terrain - otherwise a forest
 * looks like a mountain range and every wooded site scores terribly.
 *
 * IMPORTANT: chunks that are not loaded must be rejected. The client answers getHeight for an unloaded
 * chunk from an empty chunk, which reports 0 - so an unloaded footprint samples as perfectly flat, scores
 * the best possible cost, and would be chosen and then levelled at y=0. Every sample is therefore checked
 * against hasChunk() and a candidate touching unloaded terrain is discarded.
 */
final class SiteFinder {

    /** Roughly how many samples to take across a footprint. More is slower; this is plenty to rank. */
    private static final int SAMPLES_PER_AXIS = 16;

    private static final double MAX_WATER_FRACTION = 0.2;
    private static final int MAX_SPREAD = 24;

    /**
     * @param origin          minimum corner of the footprint, at the level the build's bottom layer sits on
     * @param spread          max minus min sampled surface height
     * @param clear           blocks of terrain above the base level inside the footprint
     * @param fill            blocks of gap below the base level inside the footprint
     * @param wet             sampled columns whose surface is fluid
     * @param surfaceBlock    what the local ground is mostly made of, for the top of a fill
     * @param subsurfaceBlock what sits just under the ground, for the body of a fill
     * @param lowestSurface   lowest sampled surface height, i.e. how far down gaps can go
     */
    record Site(BlockPos origin, int sizeX, int sizeZ, int spread, int clear, int fill, int wet, int distance,
                String surfaceBlock, String subsurfaceBlock, int lowestSurface) {

        int cost() {
            return spread * 8 + clear + fill * 2 + wet * 10 + distance / 4;
        }

        String describe(int index) {
            return String.format("#%d  (%d, %d, %d)  %dx%d  flat-spread=%d  clear=%d  fill=%d  water=%d  dist=%d",
                    index, origin.getX(), origin.getY(), origin.getZ(), sizeX, sizeZ,
                    spread, clear, fill, wet, distance);
        }
    }

    private SiteFinder() {
    }

    /** Best candidate sites within {@code radius} of the player, cheapest first. */
    static List<Site> find(Level level, BlockPos centre, int radius, int sizeX, int sizeZ, int wanted) {
        List<Site> sites = new ArrayList<>();
        if (level == null) {
            return sites;
        }
        int stride = Math.max(8, Math.min(64, radius / 6));
        for (int offsetX = -radius; offsetX <= radius; offsetX += stride) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ += stride) {
                Site site = evaluate(level, centre.getX() + offsetX, centre.getZ() + offsetZ,
                        sizeX, sizeZ, centre);
                if (site != null) {
                    sites.add(site);
                }
            }
        }
        sites.sort(Comparator.comparingInt(Site::cost));
        return sites.subList(0, Math.min(Math.max(0, wanted), sites.size()));
    }

    private static Site evaluate(Level level, int originX, int originZ, int sizeX, int sizeZ,
                                 BlockPos centre) {
        // A step that spreads a fixed number of samples across the whole footprint, edges included.
        int step = Math.max(1, Math.min(sizeX, sizeZ) / SAMPLES_PER_AXIS);

        List<Integer> tops = new ArrayList<>();
        Map<String, Integer> surfaceCounts = new HashMap<>();
        Map<String, Integer> subsurfaceCounts = new HashMap<>();
        int wet = 0;

        for (int dx = 0; dx < sizeX; dx += step) {
            for (int dz = 0; dz < sizeZ; dz += step) {
                int x = originX + dx;
                int z = originZ + dz;

                // Unloaded terrain reports height 0, which would read as perfectly flat. Never trust it.
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    return null;
                }

                // getHeight returns the first air block, so the top solid block is one below it.
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                tops.add(top);

                BlockState state = level.getBlockState(new BlockPos(x, top, z));
                if (state.getFluidState().isEmpty()) {
                    // Only solid ground is counted as a fill material. Checking the fluid state is exact,
                    // where matching ids containing "water" would not be.
                    surfaceCounts.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);

                    BlockState below = level.getBlockState(new BlockPos(x, top - 1, z));
                    if (below.getFluidState().isEmpty()) {
                        subsurfaceCounts.merge(
                                BuiltInRegistries.BLOCK.getKey(below.getBlock()).toString(), 1, Integer::sum);
                    }
                } else {
                    wet++;
                }
            }
        }

        int sampleCount = tops.size();
        if (sampleCount == 0) {
            return null;
        }

        int[] sorted = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            sorted[i] = tops.get(i);
        }
        Arrays.sort(sorted);

        int median = sorted[sampleCount / 2];
        int base = median + 1;                       // the build's bottom layer sits on the median surface
        int spread = sorted[sampleCount - 1] - sorted[0];

        if (wet > sampleCount * MAX_WATER_FRACTION || spread > MAX_SPREAD) {
            return null;
        }

        int clear = 0;
        int fill = 0;
        for (int top : sorted) {
            if (top > median) {
                clear += top - median;
            } else if (top < median) {
                fill += median - top;
            }
        }

        int dx = originX - centre.getX();
        int dz = originZ - centre.getZ();
        int distance = (int) Math.sqrt((double) dx * dx + (double) dz * dz);

        return new Site(new BlockPos(originX, base, originZ), sizeX, sizeZ, spread, clear, fill, wet, distance,
                mostCommon(surfaceCounts, "minecraft:dirt"),
                mostCommon(subsurfaceCounts, "minecraft:dirt"),
                sorted[0]);
    }

    /**
     * The most common block id, with fluids and air rejected.
     *
     * A fill made of water would be a disaster, and air would leave the hole it was meant to fill, so
     * anything that is not solid ground falls back to dirt.
     */
    private static String mostCommon(Map<String, Integer> counts, String fallback) {
        return counts.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("minecraft:air")
                        && !entry.getKey().equals("minecraft:cave_air")
                        && !entry.getKey().equals("minecraft:void_air")
                        && !entry.getKey().contains("water")
                        && !entry.getKey().contains("lava"))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(fallback);
    }
}
