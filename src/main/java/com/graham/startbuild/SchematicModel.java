package com.graham.startbuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * A dense, read-once copy of a Litematica schematic: one BlockState per cell.
 *
 * Read through Litematica (so every format/version Litematica understands works) but WITHOUT creating
 * a placement - a placement renders Litematica's ghost overlay, which would end up on camera.
 *
 * Index order is (y * sizeZ + z) * sizeX + x, the same as the .litematic bit stream and the Node tools.
 */
final class SchematicModel {

    final int sizeX;
    final int sizeY;
    final int sizeZ;
    final BlockState[] states;
    int solidCount;

    private SchematicModel(int sx, int sy, int sz) {
        this.sizeX = sx;
        this.sizeY = sy;
        this.sizeZ = sz;
        this.states = new BlockState[sx * sy * sz];
    }

    int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    /** @return the wanted state (never null; air outside the schematic). */
    BlockState at(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState s = states[index(x, y, z)];
        return (s == null) ? Blocks.AIR.defaultBlockState() : s;
    }

    /**
     * Reads every cell of a LitematicaSchematic.
     *
     * Litematica stores each region with an origin and a size that may be NEGATIVE on any axis (the size
     * is "from the origin corner towards the other corner"), so each region's minimum corner is computed
     * rather than assumed to be its origin.
     */
    static SchematicModel read(Object schematic) {
        if (schematic == null) {
            return null;
        }
        try {
            Vec3i total = LitematicaBridge.sizeOf(schematic);
            if (total == null) {
                return null;
            }
            int sx = Math.abs(total.getX());
            int sy = Math.abs(total.getY());
            int sz = Math.abs(total.getZ());
            if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > 64L * 1024 * 1024) {
                return null;
            }
            SchematicModel model = new SchematicModel(sx, sy, sz);
            Class<?> cls = schematic.getClass();
            Object areasObj = Reflect.method(cls, "getAreaPositions").invoke(schematic);
            if (!(areasObj instanceof Map<?, ?> areas) || areas.isEmpty()) {
                return null;
            }
            Method getSize = Reflect.method(cls, "getAreaSize", String.class);
            Method getContainer = Reflect.method(cls, "getSubRegionContainer", String.class);

            // The enclosing box's minimum corner, relative to which cells are indexed.
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (Map.Entry<?, ?> e : areas.entrySet()) {
                if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof BlockPos pos)) continue;
                Object so = getSize.invoke(schematic, name);
                if (!(so instanceof BlockPos size)) continue;
                minX = Math.min(minX, regionMin(pos.getX(), size.getX()));
                minY = Math.min(minY, regionMin(pos.getY(), size.getY()));
                minZ = Math.min(minZ, regionMin(pos.getZ(), size.getZ()));
            }

            for (Map.Entry<?, ?> e : areas.entrySet()) {
                if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof BlockPos pos)) continue;
                Object so = getSize.invoke(schematic, name);
                if (!(so instanceof BlockPos size)) continue;
                Object container = getContainer.invoke(schematic, name);
                if (container == null) continue;
                Method get = Reflect.method(container.getClass(), "get", int.class, int.class, int.class);
                int ax = Math.abs(size.getX()), ay = Math.abs(size.getY()), az = Math.abs(size.getZ());
                int ox = regionMin(pos.getX(), size.getX()) - minX;
                int oy = regionMin(pos.getY(), size.getY()) - minY;
                int oz = regionMin(pos.getZ(), size.getZ()) - minZ;
                for (int ly = 0; ly < ay; ly++) {
                    for (int lz = 0; lz < az; lz++) {
                        for (int lx = 0; lx < ax; lx++) {
                            Object st = get.invoke(container, lx, ly, lz);
                            if (!(st instanceof BlockState bs) || bs.isAir()) continue;
                            int x = ox + lx, y = oy + ly, z = oz + lz;
                            if (!model.inside(x, y, z)) continue;
                            model.states[model.index(x, y, z)] = bs;
                            model.solidCount++;
                        }
                    }
                }
            }
            return model;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] schematic read failed: {}", Reflect.describe(t));
            return null;
        }
    }

    private static int regionMin(int origin, int size) {
        return (size >= 0) ? origin : origin + size + 1;
    }
}
