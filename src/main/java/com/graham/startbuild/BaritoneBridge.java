package com.graham.startbuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Runtime bridge to Baritone.
 *
 * Verified against cabaletta/baritone (branches 1.21.4 / 26.2, and the released 1.19.0 jar):
 *
 *   baritone.api.BaritoneAPI.getProvider() -> IBaritoneProvider
 *   IBaritoneProvider.getPrimaryBaritone() -> IBaritone
 *   IBaritone.getBuilderProcess()          -> IBuilderProcess
 *   IBaritone.getGameEventHandler()        -> IEventBus
 *   BaritoneAPI.getSettings()              -> Settings (every Setting has a public "value" field)
 *   IBuilderProcess.buildOpenLitematic(int), build(String, File, Vec3i), isPaused(), resume(),
 *                   getMinLayer(), getMaxLayer()
 *   IBaritoneProcess.isActive(), onLostControl()
 *   IBaritone.getSelectionManager() -> ISelectionManager.getSelections() -> ISelection[]
 */
final class BaritoneBridge {

    private static final String API_CLASS = "baritone.api.BaritoneAPI";
    private static final String PROVIDER_CLASS = "baritone.api.IBaritoneProvider";
    private static final String BARITONE_CLASS = "baritone.api.IBaritone";
    private static final String BUILDER_CLASS = "baritone.api.process.IBuilderProcess";
    private static final String PROCESS_CLASS = "baritone.api.process.IBaritoneProcess";
    private static final String COMMAND_MANAGER_CLASS = "baritone.api.command.manager.ICommandManager";

    private static boolean initialised;
    private static Class<?> api;
    private static Class<?> providerIface;
    private static Class<?> baritoneIface;
    private static Class<?> builderIface;
    private static Class<?> processIface;
    private static Class<?> commandManagerIface;

    private BaritoneBridge() {
    }

    static boolean available() {
        if (!initialised) {
            api = Reflect.find(API_CLASS);
            providerIface = Reflect.find(PROVIDER_CLASS);
            baritoneIface = Reflect.find(BARITONE_CLASS);
            builderIface = Reflect.find(BUILDER_CLASS);
            processIface = Reflect.find(PROCESS_CLASS);
            commandManagerIface = Reflect.find(COMMAND_MANAGER_CLASS);
            // Only latch once everything is there. Latching on the first call meant that a class which
            // happened to be absent at that instant (mod load order) stayed "missing" for the whole
            // session with no way to recover.
            initialised = api != null && providerIface != null && baritoneIface != null
                    && builderIface != null && processIface != null && commandManagerIface != null;
        }
        boolean ok = api != null && providerIface != null && baritoneIface != null && builderIface != null
                && processIface != null && commandManagerIface != null;
        if (!ok) {
            StartBuildMod.LOGGER.warn("[StartBuild] Baritone API classes not found "
                    + "(api={} provider={} baritone={} builder={} process={} commands={})",
                    api, providerIface, baritoneIface, builderIface, processIface, commandManagerIface);
        }
        return ok;
    }

    static Object primaryBaritone() throws Exception {
        Object provider = Reflect.method(api, "getProvider").invoke(null);
        return Reflect.method(providerIface, "getPrimaryBaritone").invoke(provider);
    }

    private static Object builderProcess() throws Exception {
        return Reflect.method(baritoneIface, "getBuilderProcess").invoke(primaryBaritone());
    }

    /* ------------------------------------------------------------------ settings */

    private static Object settings() throws Exception {
        return Reflect.method(api, "getSettings").invoke(null);
    }

    /** @return the current value of a Baritone setting, or null if it could not be read. */
    static Object settingValue(String field) {
        if (!available()) {
            return null;
        }
        try {
            Object settings = settings();
            Object setting = Reflect.field(settings.getClass(), field).get(settings);
            return Reflect.field(setting.getClass(), "value").get(setting);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not read setting '{}': {}", field, Reflect.describe(t));
            return null;
        }
    }

    static boolean setSetting(String field, Object value) {
        if (!available()) {
            return false;
        }
        try {
            Object settings = settings();
            Object setting = Reflect.field(settings.getClass(), field).get(settings);
            Reflect.field(setting.getClass(), "value").set(setting, value);
            return true;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not set setting '{}': {}", field, Reflect.describe(t));
            return false;
        }
    }

    /**
     * Baritone's buildOnlySelection. When it is on the builder is wrapped in a SelectionSchematic, so
     * with no selection set it builds nothing at all - announced only by the easily-missed
     * "Poor little kitten forgot to set a selection" line.
     */
    static Boolean isBuildOnlySelection() {
        Object value = settingValue("buildOnlySelection");
        return (value instanceof Boolean b) ? b : null;
    }

    static Boolean isBuildInLayers() {
        Object value = settingValue("buildInLayers");
        return (value instanceof Boolean b) ? b : null;
    }

    static Integer startAtLayer() {
        Object value = settingValue("startAtLayer");
        return (value instanceof Integer i) ? i : null;
    }

    static boolean setStartAtLayer(int layer) {
        return setSetting("startAtLayer", layer);
    }

    static boolean setBuildInLayers(boolean enabled) {
        return setSetting("buildInLayers", enabled);
    }

    /* ------------------------------------------------------------------ builder state */

    static boolean isBuildActive() {
        if (!available()) {
            return false;
        }
        try {
            Class<?> owner = (processIface != null) ? processIface : builderIface;
            Method isActive = Reflect.method(owner, "isActive");
            return Boolean.TRUE.equals(isActive.invoke(builderProcess()));
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Baritone isActive() failed: {}", Reflect.describe(t));
            return false;
        }
    }

    static boolean isBuildPaused() {
        if (!available()) {
            return false;
        }
        try {
            Method isPaused = Reflect.method(builderIface, "isPaused");
            return Boolean.TRUE.equals(isPaused.invoke(builderProcess()));
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Baritone isPaused() failed: {}", Reflect.describe(t));
            return false;
        }
    }

    static boolean resumeBuild() {
        if (!available()) {
            return false;
        }
        try {
            Reflect.method(builderIface, "resume").invoke(builderProcess());
            return true;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Baritone resume() failed: {}", Reflect.describe(t));
            return false;
        }
    }

    /** @return the layer Baritone is currently building, or -1 when it is not in layer mode. */
    static int currentLayer() {
        return optionalLayer("getMinLayer");
    }

    /** @return the last layer Baritone intends to build, or -1 when it is not in layer mode. */
    static int maxLayer() {
        return optionalLayer("getMaxLayer");
    }

    private static int optionalLayer(String method) {
        if (!available()) {
            return -1;
        }
        try {
            @SuppressWarnings("unchecked")
            Optional<Integer> result = (Optional<Integer>) Reflect.method(builderIface, method).invoke(builderProcess());
            return result.map(Integer::intValue).orElse(-1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Asks Baritone to build a Litematica placement.
     *
     * Baritone's own {@code IBuilderProcess.buildOpenLitematic(int)} is declared {@code void} - verified
     * with javap against baritone-api-fabric-1.19.0, where the interface shows
     * {@code public abstract void buildOpenLitematic(int)} - so there is NO return value saying whether it
     * accepted the request. A build Baritone silently ignores (typically "No schematic currently loaded"
     * when the placement index is stale, or the placement was removed in Litematica's own UI) is
     * indistinguishable here from a successful call. This method therefore reports only that the request
     * was DISPATCHED; the caller has to confirm the build actually started via {@link #isBuildActive()}.
     *
     * @param placementIndex1Based the same number you would pass to Baritone's "#litematica <#>".
     * @return true if the request was dispatched; false only if reflection itself failed.
     */
    static boolean requestLitematicaBuild(int placementIndex1Based) {
        if (!available()) {
            return false;
        }
        try {
            Method build = Reflect.method(builderIface, "buildOpenLitematic", int.class);
            build.invoke(builderProcess(), placementIndex1Based - 1);
            return true;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.error("[StartBuild] Baritone buildOpenLitematic failed: {}", Reflect.describe(t));
            return false;
        }
    }

    static boolean buildFromFile(File schematic, Vec3i origin) {
        if (!available()) {
            return false;
        }
        try {
            Method build = Reflect.method(builderIface, "build", String.class, File.class, Vec3i.class);
            Object result = build.invoke(builderProcess(), schematic.getName(), schematic, origin);
            return !Boolean.FALSE.equals(result);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.error("[StartBuild] Baritone build(file) failed: {}", Reflect.describe(t));
            return false;
        }
    }

    /** Best-effort cancel: Baritone's own "cancel" command first, then onLostControl() as a fallback. */
    static boolean cancelBuild() {
        if (!available()) {
            return false;
        }
        try {
            Object baritone = primaryBaritone();
            Object commandManager = Reflect.method(baritoneIface, "getCommandManager").invoke(baritone);
            Reflect.method(commandManagerIface, "execute", String.class).invoke(commandManager, "cancel");
            return true;
        } catch (Throwable first) {
            StartBuildMod.LOGGER.warn("[StartBuild] cancel via command manager failed ({}), trying onLostControl()",
                    Reflect.describe(first));
            try {
                Class<?> owner = (processIface != null) ? processIface : builderIface;
                Reflect.method(owner, "onLostControl").invoke(builderProcess());
                return true;
            } catch (Throwable second) {
                StartBuildMod.LOGGER.error("[StartBuild] Could not cancel the build: {}", Reflect.describe(second));
                return false;
            }
        }
    }

    /** @return how many Baritone selections exist, or -1 when it could not be read. */
    static int selectionCount() {
        if (!available()) {
            return -1;
        }
        try {
            Class<?> selectionManager = Reflect.find("baritone.api.selection.ISelectionManager");
            if (selectionManager == null) {
                return -1;
            }
            Object baritone = primaryBaritone();
            Object manager = Reflect.method(baritoneIface, "getSelectionManager").invoke(baritone);
            Object selections = Reflect.method(selectionManager, "getSelections").invoke(manager);
            return (selections == null) ? 0 : java.lang.reflect.Array.getLength(selections);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Could not read Baritone selections: {}", Reflect.describe(t));
            return -1;
        }
    }

    /* ------------------------------------------------------------------ selections */

    /**
     * The build's bounding box, worked out from the loaded Litematica placement.
     *
     * Baritone's LitematicaHelper lives in an obfuscated package (its methods are renamed by
     * ProGuard - in 1.19.0 they are literally called "a"), so the accessor is found by SIGNATURE
     * rather than by name: a static method taking an int and returning Pair. That survives Baritone
     * renaming things.
     *
     * @return {minX, minY, minZ, width, height, length} or null when it cannot be determined.
     */
    static int[] schematicBox(int placementIndex1Based) {
        if (!available()) {
            return null;
        }
        try {
            Class<?> helper = Reflect.find("baritone.utils.schematic.litematica.LitematicaHelper");
            Class<?> pairClass = Reflect.find("baritone.api.utils.Pair");
            Class<?> schematicIface = Reflect.find("baritone.api.schematic.IStaticSchematic");
            if (helper == null || pairClass == null || schematicIface == null) {
                return null;
            }

            // Bind by shape, because the helper's name is not part of Baritone's API surface - but do NOT
            // just take the first structural match. getDeclaredMethods() has no defined order, so if a
            // Baritone release adds a second static (int) -> Pair helper, "first match" would silently
            // bind to the wrong one and every footprint-derived feature (the placement teleport,
            // movePlayerOutOfBuild, /buildsite, /buildsel) would quietly misbehave. Instead, try every
            // candidate and accept the first that yields a sane placement, preferring a plausible name.
            java.util.List<Method> candidates = new java.util.ArrayList<>();
            for (Method candidate : helper.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                        && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == int.class
                        && candidate.getReturnType() == pairClass
                        && !candidate.isSynthetic()) {
                    candidates.add(candidate);
                }
            }
            candidates.sort(java.util.Comparator.comparingInt(c -> nameScore(c.getName())));
            if (candidates.isEmpty()) {
                return null;
            }

            for (Method accessor : candidates) {
                int[] box = readBox(accessor, pairClass, schematicIface, placementIndex1Based);
                if (box != null) {
                    return box;
                }
            }
            StartBuildMod.LOGGER.warn("[StartBuild] no Baritone Litematica accessor produced a usable "
                    + "placement for index {} (tried {} candidate(s))", placementIndex1Based, candidates.size());
            return null;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not work out the schematic box: {}", Reflect.describe(t));
            return null;
        }
    }

    /** Lower sorts first. Only a tie-break between equally valid candidates; never a correctness gate. */
    private static int nameScore(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("placement") || lower.contains("origin") || lower.contains("schematic")) {
            return 0;
        }
        return lower.startsWith("get") ? 1 : 2;
    }

    /** @return {originX, originY, originZ, width, height, length}, or null if unusable. */
    private static int[] readBox(Method accessor, Class<?> pairClass, Class<?> schematicIface, int index) {
        try {
            Object pair = accessor.invoke(null, index - 1);
            if (pair == null) {
                return null;
            }
            Object schematic = Reflect.method(pairClass, "first").invoke(pair);
            Object originObj = Reflect.method(pairClass, "second").invoke(pair);
            if (!(originObj instanceof Vec3i origin) || schematic == null) {
                return null;
            }
            int width = ((Number) Reflect.method(schematicIface, "widthX").invoke(schematic)).intValue();
            int height = ((Number) Reflect.method(schematicIface, "heightY").invoke(schematic)).intValue();
            int length = ((Number) Reflect.method(schematicIface, "lengthZ").invoke(schematic)).intValue();
            // Reject nonsense: a wrong helper is very unlikely to return a plausible box by accident, and a
            // bad footprint is worse than none because it silently teleports the player to the wrong place.
            if (width <= 0 || height <= 0 || length <= 0
                    || width > 4096 || height > 4096 || length > 4096
                    || origin.getY() < -512 || origin.getY() > 1024) {
                return null;
            }
            return new int[]{origin.getX(), origin.getY(), origin.getZ(), width, height, length};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Replace Baritone's selections with one box. */
    static boolean setSelection(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (!available()) {
            return false;
        }
        try {
            Class<?> selectionManager = Reflect.find("baritone.api.selection.ISelectionManager");
            Class<?> betterBlockPos = Reflect.find("baritone.api.utils.BetterBlockPos");
            if (selectionManager == null || betterBlockPos == null) {
                return false;
            }
            Object manager = Reflect.method(baritoneIface, "getSelectionManager").invoke(primaryBaritone());
            Reflect.method(selectionManager, "removeAllSelections").invoke(manager);
            Object pos1 = Reflect.constructor(betterBlockPos, BlockPos.class)
                    .newInstance(new BlockPos(minX, minY, minZ));
            Object pos2 = Reflect.constructor(betterBlockPos, BlockPos.class)
                    .newInstance(new BlockPos(maxX, maxY, maxZ));
            Reflect.method(selectionManager, "addSelection", betterBlockPos, betterBlockPos)
                    .invoke(manager, pos1, pos2);
            return true;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.error("[StartBuild] could not set the Baritone selection: {}", Reflect.describe(t));
            return false;
        }
    }

    /** @return {minX, minY, minZ, maxX, maxY, maxZ, count} for the first selection, or null. */
    static int[] currentSelectionBox() {
        if (!available()) {
            return null;
        }
        try {
            Class<?> selectionManager = Reflect.find("baritone.api.selection.ISelectionManager");
            Class<?> selectionIface = Reflect.find("baritone.api.selection.ISelection");
            if (selectionManager == null || selectionIface == null) {
                return null;
            }
            Object manager = Reflect.method(baritoneIface, "getSelectionManager").invoke(primaryBaritone());
            Object selections = Reflect.method(selectionManager, "getSelections").invoke(manager);
            int count = (selections == null) ? 0 : java.lang.reflect.Array.getLength(selections);
            if (count == 0) {
                return null;
            }
            Object first = java.lang.reflect.Array.get(selections, 0);
            Vec3i min = (Vec3i) Reflect.method(selectionIface, "min").invoke(first);
            Vec3i max = (Vec3i) Reflect.method(selectionIface, "max").invoke(first);
            return new int[]{min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(), count};
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not read the current selection: {}", Reflect.describe(t));
            return null;
        }
    }

    /* ------------------------------------------------------------------ misc */

    /**
     * Apply the Baritone settings the recording workflow wants: items moved to the hotbar for you,
     * and Baritone's in-world overlays (path, goal box, selection box) switched off so they cannot
     * appear on camera.
     *
     * Done here rather than by writing Baritone's settings file, because that file's format is
     * Baritone's business - this way it is explicit, reported, and cannot corrupt anything.
     *
     * @return lines describing what was changed.
     */
    static java.util.List<String> applyVideoSettings() {
        java.util.List<String> changed = new java.util.ArrayList<>();
        Object[][] wanted = {
                {"allowInventory", Boolean.TRUE},
                {"renderPath", Boolean.FALSE},
                {"renderGoal", Boolean.FALSE},
                {"renderSelection", Boolean.FALSE},
        };
        for (Object[] pair : wanted) {
            String name = (String) pair[0];
            Object value = pair[1];
            Object current = settingValue(name);
            if (current == null) {
                changed.add(name + ": could not be read, left alone");
                continue;
            }
            if (value.equals(current)) {
                continue;
            }
            if (setSetting(name, value)) {
                changed.add(name + " -> " + value);
            } else {
                changed.add(name + ": could not be set");
            }
        }
        return changed;
    }

    /** Desktop notification, for builds you walk away from. */
    static boolean notifyDesktop(String message) {        Class<?> helper = Reflect.find("baritone.api.utils.NotificationHelper");
        if (helper == null) {
            return false;
        }
        try {
            Reflect.method(helper, "notify", String.class, boolean.class).invoke(null, message, true);
            return true;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] desktop notification failed: {}", Reflect.describe(t));
            return false;
        }
    }

    /** Probes used by the startup self-test. @return null when fine, else the problem. */
    static String probe(String name) {
        if (!available()) {
            return "Baritone API not found";
        }
        try {
            switch (name) {
                case "settings" -> {
                    if (settingValue("buildOnlySelection") == null) return "cannot read buildOnlySelection";
                }
                case "layers" -> {
                    if (settingValue("startAtLayer") == null) return "cannot read startAtLayer";
                }
                case "selections" -> {
                    if (selectionCount() < 0) return "cannot read selections";
                }
                case "builder" -> {
                    isBuildActive();
                    if (builderIface == null) return "IBuilderProcess missing";
                }
                case "notify" -> {
                    if (Reflect.find("baritone.api.utils.NotificationHelper") == null) return "NotificationHelper missing";
                }
                case "events" -> {
                    if (Reflect.find("baritone.api.event.listener.AbstractGameEventListener") == null) {
                        return "listener interface missing";
                    }
                }
                default -> {
                    return "unknown probe";
                }
            }
        } catch (Throwable t) {
            return Reflect.describe(t);
        }
        return null;
    }
}
