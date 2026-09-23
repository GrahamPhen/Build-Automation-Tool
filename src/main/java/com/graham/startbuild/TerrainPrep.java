package com.graham.startbuild;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Levels the ground for a build, so it sits in the landscape instead of on a plinth or half-buried in a
 * hillside.
 *
 * It does exactly one thing, over the footprint plus a margin: cuts everything standing above the build's
 * base level, and fills everything below it up to that level. The margin means there is no visible cliff
 * edge where the build's plot meets untouched terrain, which is the giveaway that it did not grow there.
 *
 * Deliberately /fill rather than Baritone: this runs BEFORE the recording starts, so it should be instant
 * and exact rather than filmed. Baritone-based terraforming, which would show on camera as real work, is a
 * separate idea and not what this does.
 *
 * /fill caps a single command at 32,768 blocks, so every box is split into slices that stay under it.
 *
 * The levelling heights are read live, column by column, rather than taken from SiteFinder's numbers: the
 * scorer samples every few blocks for speed, so it can miss a spike or a pit that would then poke through
 * the build's floor or leave a hole under it.
 */
final class TerrainPrep {

    /** Vanilla's own limit for one /fill. */
    private static final int MAX_FILL_BLOCKS = 32768;

    record Result(int commands, long cleared, long filled, boolean ok) {
        String describe() {
            return commands + " fill command(s), cutting " + String.format("%,d", cleared)
                    + " block(s) and filling " + String.format("%,d", filled);
        }
    }

    private TerrainPrep() {
    }

    /**
     * Levels the footprint plus {@code margin} to {@code baseY}.
     *
     * @param surfaceBlock    what the local ground is mostly made of; becomes the top of the fill
     * @param subsurfaceBlock what sits under it; becomes the body of the fill, so exposed sides match
     */
    static Result level(Level level, BlockPos origin, int sizeX, int sizeZ, int baseY,
                        String surfaceBlock, String subsurfaceBlock, int margin) {
        int x1 = origin.getX();
        int z1 = origin.getZ();
        int x2 = origin.getX() + sizeX - 1;
        int z2 = origin.getZ() + sizeZ - 1;
        int mx1 = x1 - margin;
        int mz1 = z1 - margin;
        int mx2 = x2 + margin;
        int mz2 = z2 + margin;

        // Two different areas, deliberately.
        //
        // CLEARING is confined to the footprint: everything above the base level inside it has to go,
        // because that is where the build's lower layers sit. Not touching the margin means the trees and
        // scenery around the site survive, which is what makes it look like a building in a landscape
        // rather than a quarry.
        //
        // FILLING spans the margin, because gaps in the ground right at the build's edge would show as a
        // step. Levelling the ground around it is enough to remove that.
        // "highest" starts BELOW every real height so that "was anything found at all" stays
        // distinguishable from "found something". Starting it at baseY (as it used to) meant a column
        // whose top solid block sits exactly AT baseY - i.e. a block occupying the build's bottom layer -
        // produced highest == baseY, and the guard below was ">", so nothing was cut and the terrain
        // stayed inside the build's floor. It must be ">=": a block at baseY is in the build's way.
        int highest = Integer.MIN_VALUE;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;                          // unloaded terrain reports 0, so skip it entirely
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (top > highest) {
                    highest = top;
                }
            }
        }

        int lowest = baseY;
        boolean sawAny = false;
        for (int x = mx1; x <= mx2; x++) {
            for (int z = mz1; z <= mz2; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (!sawAny || top < lowest) {
                    lowest = top;
                    sawAny = true;
                }
            }
        }
        if (!sawAny) {
            return new Result(0, 0, 0, false);
        }

        int commands = 0;
        long cleared = 0;
        long filled = 0;
        boolean ok = true;

        // 1. Cut everything above the base level inside the footprint, so nothing pokes through the
        //    build's bottom layer.
        if (highest >= baseY) {
            long[] stats = fill(x1, baseY, z1, x2, highest, z2, "minecraft:air");
            commands += (int) stats[0];
            cleared += stats[1];
            ok &= stats[2] == 1;
        }

        // 2. Fill the dips up to the base level across footprint and margin, otherwise the build floats
        //    over them and there is a step at its edge.
        if (lowest < baseY - 1) {
            long[] stats = fill(mx1, lowest, mz1, mx2, baseY - 2, mz2, subsurfaceBlock);
            commands += (int) stats[0];
            filled += stats[1];
            ok &= stats[2] == 1;
        }
        if (lowest < baseY) {
            // The top layer uses the ground block itself, so the surface reads as natural ground and the
            // exposed sides of the fill show soil rather than a green edge.
            long[] stats = fill(mx1, baseY - 1, mz1, mx2, baseY - 1, mz2, surfaceBlock);
            commands += (int) stats[0];
            filled += stats[1];
            ok &= stats[2] == 1;
        }

        return new Result(commands, cleared, filled, ok);
    }

    /**
     * Issues /fill for a box, split into slabs of at most MAX_FILL_BLOCKS.
     *
     * Slicing is done on X and then on Z. The X pass alone is not enough: when the box is shallow and
     * narrow but very tall, {@code height * depth} can exceed the limit on its own, perX collapses to 1,
     * and every command is then still over the limit - so vanilla rejects all of them and the ground is
     * left half-levelled while this reports success. Slicing Z as well bounds each command by
     * {@code MAX / height}, which always fits because no world is 32,768 blocks tall.
     *
     * @return {commands issued, blocks covered, 1 if all succeeded else 0}
     */
    private static long[] fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        int width = x2 - x1 + 1;
        int height = y2 - y1 + 1;
        int depth = z2 - z1 + 1;
        if (width <= 0 || height <= 0 || depth <= 0) {
            return new long[]{0, 0, 1};
        }

        int perX = Math.max(1, MAX_FILL_BLOCKS / Math.max(1, height * depth));
        long commands = 0;
        long blocks = 0;
        boolean allOk = true;

        for (int startX = x1; startX <= x2; startX += perX) {
            int endX = Math.min(x2, startX + perX - 1);
            int slabWidth = endX - startX + 1;
            int perZ = Math.max(1, MAX_FILL_BLOCKS / Math.max(1, height * slabWidth));
            for (int startZ = z1; startZ <= z2; startZ += perZ) {
                int endZ = Math.min(z2, startZ + perZ - 1);
                String command = String.format("fill %d %d %d %d %d %d %s",
                        startX, y1, startZ, endX, y2, endZ, block);
                if (!StartBuildMod.runServerCommand(command)) {
                    allOk = false;
                }
                commands++;
                blocks += (long) slabWidth * height * (endZ - startZ + 1);
            }
        }
        return new long[]{commands, blocks, allOk ? 1 : 0};
    }
}
