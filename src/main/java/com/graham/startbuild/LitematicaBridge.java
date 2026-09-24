package com.graham.startbuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reflection bridge into Litematica: reads schematics (for {@link SchematicModel}), shows the preview
 * ghost for /previewbuild and /findsite, and removes every placement before a take so no ghost is
 * recorded.
 *
 * Litematica is not obfuscated, but none of this is a public API, so every call is reflective and
 * every failure is reported rather than thrown.
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
            // Always clear (a failed count must not leave a ghost on camera), then check it worked.
            int n = placementCount(manager, manager.getClass());
            Reflect.method(manager.getClass(), "clear").invoke(manager);
            List<?> left = placements(manager, manager.getClass());
            if (left == null || !left.isEmpty()) {
                StartBuildMod.LOGGER.warn("[StartBuild] Litematica placements still present after clear: {}", left);
                return -1;
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
            StartBuildMod.LOGGER.warn("[StartBuild] could not list Litematica placements: {}", Reflect.describe(t));
            return null;
        }
    }

    private static int placementCount(Object manager, Class<?> managerClass) {
        List<?> all = placements(manager, managerClass);
        return (all == null) ? 0 : all.size();
    }
}
