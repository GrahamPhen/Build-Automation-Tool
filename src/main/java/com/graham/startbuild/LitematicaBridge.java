package com.graham.startbuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reflection bridge into Litematica, so /startbuild can load a schematic and create a placement for
 * you instead of you walking the schematic menus.
 *
 * Litematica is not obfuscated, but none of this is a public API, so every call is reflective and
 * every failure is reported rather than thrown - the same approach used for Baritone and Flashback.
 *
 * The sequence mirrors Litematica's own "Create placement" exactly. It was read out of the bytecode of
 * GuiSchematicLoad$ButtonListener and WidgetSchematicEntry$ButtonListener against
 * litematica-fabric-26.2-0.28.8.jar rather than guessed:
 *
 *   LitematicaSchematic.createFromFile(Path dir, String fileName)   // fileName includes the extension;
 *                                                                   // the call site passes
 *                                                                   // DirectoryEntry.name()
 *   SchematicPlacement.createFor(schematic, origin, name, true, true)
 *   SchematicPlacementManager.addSchematicPlacement(placement, true)
 *   SchematicPlacementManager.setSelectedSchematicPlacement(placement)
 *
 * On createFor the two booleans receive the SAME value in Litematica's own code (it computes one local
 * from !isShiftDown() and passes it twice), so both are passed true here.
 *
 * Note that createFromFile is synchronous, so a very large schematic will briefly hitch the client
 * thread while it is read.
 */
final class LitematicaBridge {

    private static final String DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";
    private static final String SCHEMATIC = "fi.dy.masa.litematica.schematic.LitematicaSchematic";
    private static final String PLACEMENT = "fi.dy.masa.litematica.schematic.placement.SchematicPlacement";

    private LitematicaBridge() {
    }

    static boolean available() {
        return Reflect.find(DATA_MANAGER) != null
                && Reflect.find(SCHEMATIC) != null
                && Reflect.find(PLACEMENT) != null;
    }

    /**
     * Reads a schematic file and creates, adds and selects a placement at {@code origin}.
     *
     * @param clearFirst remove every existing placement first, so builds do not pile up ghost overlays
     * @return {1-based index of the new placement, number of old placements removed}, or {-1, 0}
     */
    static int[] loadAndPlace(Path schematicDir, String fileName, BlockPos origin, String placementName,
                              boolean clearFirst) {
        Object schematic = loadSchematic(schematicDir, fileName);
        if (schematic == null) {
            StartBuildMod.chat("\u00A7cLitematica could not read " + fileName + ".");
            return new int[]{-1, 0};
        }
        return place(schematic, origin, placementName, clearFirst);
    }

    /**
     * Reads a schematic file and returns an opaque handle, or null.
     *
     * Separate from {@link #place} so a caller can measure a schematic before committing to a placement -
     * which matters because reading is synchronous and creating a placement clears the existing ones.
     * Splitting the two means the file is read once and nothing is destroyed until we are sure.
     */
    static Object loadSchematic(Path schematicDir, String fileName) {
        try {
            Class<?> schematicClass = Reflect.find(SCHEMATIC);
            if (schematicClass == null) {
                StartBuildMod.LOGGER.warn("[StartBuild] Litematica schematic class not found");
                return null;
            }
            return Reflect.method(schematicClass, "createFromFile", Path.class, String.class)
                    .invoke(null, schematicDir, fileName);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not read {}: {}", fileName, Reflect.describe(t));
            return null;
        }
    }

    /** The schematic's size, straight from its metadata. No placement needed. Null on failure. */
    static Vec3i sizeOf(Object schematic) {
        if (schematic == null) {
            return null;
        }
        try {
            Object size = Reflect.method(schematic.getClass(), "getTotalSize").invoke(schematic);
            return (size instanceof Vec3i vec) ? vec : null;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not read schematic size: {}", Reflect.describe(t));
            return null;
        }
    }

    /**
     * Creates, adds and selects a placement for an already-loaded schematic.
     *
     * @param clearFirst remove every existing placement first, so builds do not pile up ghost overlays
     * @return {1-based index of the new placement, number of old placements removed}, or {-1, 0}
     */
    static int[] place(Object schematic, BlockPos origin, String placementName, boolean clearFirst) {
        if (schematic == null) {
            return new int[]{-1, 0};
        }
        try {
            Class<?> dataManager = Reflect.find(DATA_MANAGER);
            Class<?> schematicClass = Reflect.find(SCHEMATIC);
            Class<?> placementClass = Reflect.find(PLACEMENT);
            if (dataManager == null || schematicClass == null || placementClass == null) {
                StartBuildMod.LOGGER.warn("[StartBuild] Litematica classes not found");
                return new int[]{-1, 0};
            }

            Object manager = Reflect.method(dataManager, "getSchematicPlacementManager").invoke(null);
            if (manager == null) {
                StartBuildMod.LOGGER.warn("[StartBuild] Litematica placement manager unavailable");
                return new int[]{-1, 0};
            }
            Class<?> managerClass = manager.getClass();

            // Clear only once the schematic is in hand: clearing first and then failing to place would
            // leave the world with no placements at all.
            int removed = 0;
            if (clearFirst) {
                removed = placementCount(manager, managerClass);
                if (removed > 0) {
                    Reflect.method(managerClass, "clear").invoke(manager);
                }
            }

            // createFor's first parameter is declared as LitematicaSchematic, and getMethod() needs an
            // EXACT type match - so the canonical class is used rather than getClass(), which would fail
            // if Litematica ever handed back a subclass.
            Object placement = Reflect.method(placementClass, "createFor", schematicClass, BlockPos.class,
                            String.class, boolean.class, boolean.class)
                    .invoke(null, schematic, origin, placementName, true, true);
            if (placement == null) {
                StartBuildMod.chat("\u00A7cLitematica refused to create a placement.");
                return new int[]{-1, removed};
            }

            Reflect.method(managerClass, "addSchematicPlacement", placementClass, boolean.class)
                    .invoke(manager, placement, true);
            Reflect.method(managerClass, "setSelectedSchematicPlacement", placementClass)
                    .invoke(manager, placement);

            // The index Baritone's "#litematica <n>" wants, found by identity rather than assumed order.
            List<?> all = placements(manager, managerClass);
            int index = (all == null) ? -1 : all.indexOf(placement);
            return new int[]{(index < 0) ? -1 : index + 1, removed};
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Litematica place failed: {}", Reflect.describe(t));
            StartBuildMod.chat("\u00A7cLitematica could not create that placement (see the log).");
            return new int[]{-1, 0};
        }
    }

    /**
     * Removes every Litematica placement, so no ghost overlay of an earlier build is drawn (and recorded).
     * @return how many were removed, or -1 on failure.
     */
    static int clearPlacements() {
        try {
            Class<?> dataManager = Reflect.find(DATA_MANAGER);
            if (dataManager == null) {
                return -1;
            }
            Object manager = Reflect.method(dataManager, "getSchematicPlacementManager").invoke(null);
            if (manager == null) {
                return -1;
            }
            int n = placementCount(manager, manager.getClass());
            if (n > 0) {
                Reflect.method(manager.getClass(), "clear").invoke(manager);
            }
            return n;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not clear Litematica placements: {}", Reflect.describe(t));
            return -1;
        }
    }

    private static List<?> placements(Object manager, Class<?> managerClass) {
        try {
            Method all = Reflect.method(managerClass, "getAllSchematicsPlacements");
            Object value = all.invoke(manager);
            return (value instanceof List<?> list) ? list : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int placementCount(Object manager, Class<?> managerClass) {
        List<?> all = placements(manager, managerClass);
        return (all == null) ? 0 : all.size();
    }

    /**
     * Read one block state from a loaded {@code LitematicaSchematic} at a schematic-relative position.
     *
     * Used by the finishing pass that places the blocks Baritone physically cannot (a cell with no solid
     * neighbour has no face to click against, so no click-based builder can ever place it). The accessor
     * chain was verified against litematica-fabric-26.2-0.28.8.jar:
     *
     *   LitematicaSchematic.getAreaPositions() -> Map<String, BlockPos>   (region name -> area origin)
     *   LitematicaSchematic.getAreaSize(String)   -> BlockPos             (that area's size)
     *   LitematicaSchematic.getSubRegionContainer(String) -> LitematicaBlockStateContainer
     *   LitematicaBlockStateContainer.get(int,int,int)  -> BlockState
     *
     * @param x/y/z relative to the schematic's own (0,0,0) corner
     * @return the desired state, or null when the coordinate is outside every area or any reflection fails
     */
    /**
     * Read the whole schematic into a dense solidity grid, indexed {@code (y*sizeZ + z)*sizeX + x}.
     *
     * One reflection call per area per cell, so ~12k for haunted_80 - a few tens of ms, run once before
     * the build. Used to find the cells that no click-based builder can ever place (no solid neighbour),
     * so they can be pre-placed by /setblock and nothing is left for Baritone to skip.
     *
     * @return the solidity grid, or null on any reflection failure
     */
    static boolean[] solidGrid(Object schematic, int sizeX, int sizeY, int sizeZ) {
        if (schematic == null || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            return null;
        }
        try {
            Class<?> cls = schematic.getClass();
            Object areasObj = Reflect.method(cls, "getAreaPositions").invoke(schematic);
            if (!(areasObj instanceof Map<?, ?> areas) || areas.isEmpty()) {
                return null;
            }
            boolean[] grid = new boolean[sizeX * sizeY * sizeZ];
            for (Map.Entry<?, ?> entry : areas.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof BlockPos areaOrigin)) {
                    continue;
                }
                Object sizeObj = Reflect.method(cls, "getAreaSize", String.class).invoke(schematic, name);
                if (!(sizeObj instanceof BlockPos areaSize)) {
                    continue;
                }
                Object container = Reflect.method(cls, "getSubRegionContainer", String.class).invoke(schematic, name);
                if (container == null) {
                    continue;
                }
                Method get = Reflect.method(container.getClass(), "get", int.class, int.class, int.class);
                for (int lx = 0; lx < areaSize.getX(); lx++) {
                    for (int ly = 0; ly < areaSize.getY(); ly++) {
                        for (int lz = 0; lz < areaSize.getZ(); lz++) {
                            int x = areaOrigin.getX() + lx;
                            int y = areaOrigin.getY() + ly;
                            int z = areaOrigin.getZ() + lz;
                            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                                continue;
                            }
                            Object state = get.invoke(container, lx, ly, lz);
                            if (state instanceof BlockState bs && !bs.isAir()) {
                                grid[(y * sizeZ + z) * sizeX + x] = true;
                            }
                        }
                    }
                }
            }
            return grid;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] solidity grid read failed: {}", Reflect.describe(t));
            return null;
        }
    }

    /**
     * Read one block state from a loaded {@code LitematicaSchematic} at a schematic-relative position.
     *
     * Same accessor chain as {@link #solidGrid}, but for a single cell, and it returns the exact
     * {@code BlockState} so it can be serialized for /setblock. Used for the handful of isolated cells the
     * pre-pass needs to place (so a full dense read is unnecessary there).
     *
     * @param x/y/z relative to the schematic's own (0,0,0) corner
     * @return the desired state, or null when the coordinate is outside every area or any reflection fails
     */
    static BlockState schematicBlockAt(Object schematic, int x, int y, int z) {
        if (schematic == null) {
            return null;
        }
        try {
            Class<?> cls = schematic.getClass();
            Object areasObj = Reflect.method(cls, "getAreaPositions").invoke(schematic);
            if (!(areasObj instanceof Map<?, ?> areas) || areas.isEmpty()) {
                return null;
            }
            for (Map.Entry<?, ?> entry : areas.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof BlockPos areaOrigin)) {
                    continue;
                }
                Object sizeObj = Reflect.method(cls, "getAreaSize", String.class).invoke(schematic, name);
                if (!(sizeObj instanceof BlockPos areaSize)) {
                    continue;
                }
                int lx = x - areaOrigin.getX();
                int ly = y - areaOrigin.getY();
                int lz = z - areaOrigin.getZ();
                if (lx < 0 || ly < 0 || lz < 0
                        || lx >= areaSize.getX() || ly >= areaSize.getY() || lz >= areaSize.getZ()) {
                    continue;
                }
                Object container = Reflect.method(cls, "getSubRegionContainer", String.class).invoke(schematic, name);
                if (container == null) {
                    continue;
                }
                Object state = Reflect.method(container.getClass(), "get", int.class, int.class, int.class)
                        .invoke(container, lx, ly, lz);
                return (state instanceof BlockState bs) ? bs : null;
            }
            return null;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] schematic block read failed at ({},{},{}): {}", x, y, z,
                    Reflect.describe(t));
            return null;
        }
    }
}
