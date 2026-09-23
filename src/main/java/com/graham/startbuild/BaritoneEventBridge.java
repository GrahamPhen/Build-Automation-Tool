package com.graham.startbuild;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Hooks Baritone's own event bus so StartBuild can measure the truth of "the last block placed",
 * instead of only inferring it from the builder going idle.
 *
 * Everything here is reflective, verified against baritone 1.19.0:
 *
 *   baritone.api.event.listener.AbstractGameEventListener   - an INTERFACE (all methods default), so
 *                                                             java.lang.reflect.Proxy can implement it
 *   IEventBus.registerEventListener(IGameEventListener)
 *   BlockChangeEvent.getBlocks() -> List&lt;Pair&lt;BlockPos, BlockState&gt;&gt;   with Pair.first()/second()
 *   Settings.logger is Setting&lt;Consumer&lt;Component&gt;&gt;  - so Baritone's chat output can be observed
 *
 * Being reflective, the mod still compiles and loads with no Baritone present at all.
 */
final class BaritoneEventBridge {

    /**
     * Fallback only: how close to the player a placement must be when NO build region is known.
     *
     * This used to be the only test, and that was the bug that made every large build look frozen. On an
     * 80x80 schematic Baritone places the overwhelming majority of blocks far more than 8 blocks from the
     * player, so those placements never touched {@link #lastPlacementTick}. The progress clock therefore
     * never advanced, the 60s warning fired, and at 180s the stall recovery CANCELLED a perfectly healthy
     * build and restarted it from layer 0 - which rebuilt the handful of blocks near the player and did it
     * all again, three times, then gave up. Every session from 1.7.0 to 1.19.1 placed only 77-254 blocks
     * and never got past layer 2 for exactly this reason.
     */
    private static final int PLACEMENT_RADIUS = 8;

    /** The volume being built. A block change inside it is progress; anything else is not our build. */
    private static volatile boolean regionSet;
    private static volatile int regionMinX, regionMinY, regionMinZ;
    private static volatile int regionMaxX, regionMaxY, regionMaxZ;

    /**
     * Tell the listener which volume counts as "the build".
     *
     * Scoping progress to the schematic's own bounding box is both correct and much sharper than a radius
     * around the player: it accepts a placement anywhere in the build, however far away Baritone is
     * working, and still ignores water flowing or a neighbour's build.
     */
    static void setBuildRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        regionMinX = Math.min(minX, maxX);
        regionMinY = Math.min(minY, maxY);
        regionMinZ = Math.min(minZ, maxZ);
        regionMaxX = Math.max(minX, maxX);
        regionMaxY = Math.max(minY, maxY);
        regionMaxZ = Math.max(minZ, maxZ);
        regionSet = true;
        StartBuildMod.LOGGER.info("[StartBuild] build region: ({}, {}, {}) to ({}, {}, {})",
                regionMinX, regionMinY, regionMinZ, regionMaxX, regionMaxY, regionMaxZ);
    }

    static void clearBuildRegion() {
        regionSet = false;
    }

    static boolean hasBuildRegion() {
        return regionSet;
    }

    private static volatile boolean registered;
    private static volatile boolean loggerHooked;
    private static volatile long tickCounter;
    private static volatile long lastPlacementTick = -1;
    private static volatile long placementCount;

    private static final List<String> recentMessages = Collections.synchronizedList(new ArrayList<>());
    /** True while Baritone's multi-message missing-materials list is still arriving. */
    private static volatile boolean collectingMissing;
    /** Accumulates that list, because it arrives one block type per message. */
    private static final StringBuilder missingBuffer = new StringBuilder();
    private static volatile String lastMissingMaterials = "";

    /**
     * The most recent missing list, never cleared on resume. lastMissingMaterials is deliberately
     * cleared once a build resumes, which left /buildstatus reporting that Baritone had never asked
     * for anything - most confusing exactly when someone is trying to debug.
     */

    private static volatile String lastMissingEver = "";

    private static Method methodGetBlocks;
    private static Method methodPairFirst;
    private static Method methodPairSecond;

    private BaritoneEventBridge() {
    }

    static void onClientTick() {
        tickCounter++;
    }

    static long currentTick() {
        return tickCounter;
    }

    /** Tick of the most recent block placement, or -1 when none has been seen. */
    static long lastPlacementTick() {
        return lastPlacementTick;
    }

    static long placementCount() {
        return placementCount;
    }

    static boolean isRegistered() {
        return registered;
    }

    static boolean isLoggerHooked() {
        return loggerHooked;
    }

    /** The block list Baritone last said it was missing, or "" if it has not said. */
    static String lastMissingMaterials() {
        return lastMissingMaterials;
    }

    /** The last missing list seen this session, whether or not it has since been satisfied. */
    static String lastMissingReportedEver() {
        return lastMissingEver;
    }

    static void clearMissingMaterials() {
        lastMissingMaterials = "";
        collectingMissing = false;
        missingBuffer.setLength(0);
    }

    /**
     * Re-arm the verbatim Baritone logging for a new run.
     *
     * {@code loggedMessages} is a session-lifetime counter, so without this the raw text was only ever
     * logged for the first 40 Baritone messages after the game started. From the second /startbuild of a
     * session onwards the log showed nothing - and this instrumentation is exactly what identified the
     * "[Baritone] " prefix bug, so losing it is losing the diagnostic that matters most.
     */
    static void resetMessageLog() {
        loggedMessages = 0;
        recentMessages.clear();
    }

    /** Register the block-change listener with Baritone. Safe to call repeatedly. */
    static void register() {
        if (registered || !BaritoneBridge.available()) {
            return;
        }
        try {
            Class<?> listenerIface = Reflect.find("baritone.api.event.listener.AbstractGameEventListener");
            Class<?> gameListenerIface = Reflect.find("baritone.api.event.listener.IGameEventListener");
            Class<?> busIface = Reflect.find("baritone.api.event.listener.IEventBus");
            Class<?> blockChange = Reflect.find("baritone.api.event.events.BlockChangeEvent");
            Class<?> pairClass = Reflect.find("baritone.api.utils.Pair");
            if (listenerIface == null || gameListenerIface == null || busIface == null
                    || blockChange == null || pairClass == null) {
                StartBuildMod.LOGGER.warn("[StartBuild] Baritone event classes not found; "
                        + "falling back to idle-detection timing");
                return;
            }

            methodGetBlocks = Reflect.method(blockChange, "getBlocks");
            methodPairFirst = Reflect.method(pairClass, "first");
            methodPairSecond = Reflect.method(pairClass, "second");

            Object proxy = Proxy.newProxyInstance(
                    BaritoneEventBridge.class.getClassLoader(),
                    new Class<?>[]{listenerIface},
                    (self, method, args) -> {
                        switch (method.getName()) {
                            case "onBlockChange":
                                if (args != null && args.length == 1) {
                                    onBlockChange(args[0]);
                                }
                                return null;
                            case "hashCode":
                                return System.identityHashCode(self);
                            case "equals":
                                return args != null && args.length == 1 && self == args[0];
                            case "toString":
                                return "StartBuild-BaritoneListener";
                            default:
                                return null; // every other listener method is a no-op default
                        }
                    });

            Object bus = Reflect.method(Reflect.find("baritone.api.IBaritone"), "getGameEventHandler")
                    .invoke(BaritoneBridge.primaryBaritone());
            Reflect.method(busIface, "registerEventListener", gameListenerIface).invoke(bus, proxy);

            registered = true;
            StartBuildMod.LOGGER.info("[StartBuild] hooked Baritone's block-change event");
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not hook Baritone events: {}", Reflect.describe(t));
        }
    }

    /** Wrap Baritone's chat logger so "Missing materials for at least:" can be captured. */
    static void hookLogger() {
        if (loggerHooked || !BaritoneBridge.available()) {
            return;
        }
        try {
            Object current = BaritoneBridge.settingValue("logger");
            if (!(current instanceof Consumer)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Consumer<Object> original = (Consumer<Object>) current;
            Consumer<Object> wrapper = component -> {
                try {
                    String text = (component instanceof net.minecraft.network.chat.Component c)
                            ? c.getString()
                            : String.valueOf(component);
                    observe(text);
                } catch (Throwable ignored) {
                    // never break Baritone's logging
                }
                original.accept(component);
            };
            if (BaritoneBridge.setSetting("logger", wrapper)) {
                loggerHooked = true;
                StartBuildMod.LOGGER.info("[StartBuild] hooked Baritone's chat output");
            }
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not hook Baritone logging: {}", Reflect.describe(t));
        }
    }

    /**
     * The header Baritone prints before its missing-materials list. The trailing colon is part of it.
     *
     * Matched with indexOf, NOT startsWith. This is load-bearing: Baritone prefixes its chat (the
     * rendered line is "[Baritone] Missing materials for at least:"), so an earlier startsWith() check
     * silently never matched and lastMissingMaterials stayed empty for the entire life of the project.
     *
     * The colon matters just as much: with the constant stopping before it, the "remainder of the line"
     * is the colon itself, which is not empty - so the code treated a bare colon as the missing list and
     * never captured the real one below it. That produced exactly one live failure.
     */
    private static final String MISSING_HEADER = "Missing materials for at least:";

    /** A namespaced block id, which is what a real missing-materials line contains. */
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z_]+:[a-z_]+");

    /** Baritone messages logged verbatim so far, so the raw text is in the log when this breaks again. */
    private static int loggedMessages;

    private static void observe(String raw) {
        if (raw == null) {
            return;
        }
        String text = raw.trim();

        // Verbatim instrumentation for the first messages of a run. If restocking ever stops working,
        // this is the evidence that says why - it caught the colon bug above within seconds.
        if (loggedMessages < 40) {
            loggedMessages++;
            StartBuildMod.LOGGER.info("[StartBuild] baritone says: {}", text.replace('\n', '|'));
        }

        recentMessages.add(text);
        if (recentMessages.size() > 60) {
            recentMessages.remove(0);
        }

        int header = text.indexOf(MISSING_HEADER);
        if (header >= 0) {
            // The header and the list arrive either as one message or as separate ones. Only a remainder
            // that actually names a block counts as list content; anything else (a bare colon, or some
            // other trailing punctuation) means the list is still coming.
            String tail = text.substring(header + MISSING_HEADER.length()).trim();
            missingBuffer.setLength(0);
            collectingMissing = true;
            if (BLOCK_ID.matcher(tail).find()) {
                appendMissing(tail);
            }
            return;
        }

        if (collectingMissing) {
            // Baritone logs ONE MESSAGE PER MISSING TYPE, so accumulate instead of replacing. Capturing
            // only the single line after the header was a real bug: with ten missing types the mod learned
            // about one of them, gave that one, and left the build short of the other nine for ever.
            if (BLOCK_ID.matcher(text).find()) {
                appendMissing(text);
                return;
            }
            // A message with no block id ends the list - Baritone follows it with "Unable to do it.
            // Pausing. resume to resume, cancel to cancel".
            collectingMissing = false;
        }
    }

    /** Appends one line of Baritone's missing list and republishes the whole list so far. */
    private static void appendMissing(String line) {
        if (missingBuffer.length() > 0) {
            missingBuffer.append('\n');
        }
        missingBuffer.append(line);
        lastMissingMaterials = missingBuffer.toString();
        lastMissingEver = lastMissingMaterials;
        StartBuildMod.LOGGER.info("[StartBuild] missing materials so far ({} line(s)): {}",
                missingBuffer.toString().split("\n").length, lastMissingMaterials.replace('\n', '|'));
    }

    private static void onBlockChange(Object event) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || methodGetBlocks == null) {
                return;
            }
            BlockPos centre = minecraft.player.blockPosition();
            Object blocks = methodGetBlocks.invoke(event);
            if (!(blocks instanceof Iterable)) {
                return;
            }
            long radiusSq = (long) PLACEMENT_RADIUS * PLACEMENT_RADIUS;
            for (Object pair : (Iterable<?>) blocks) {
                if (pair == null) {
                    continue;
                }
                Object posObj = methodPairFirst.invoke(pair);
                Object stateObj = methodPairSecond.invoke(pair);
                if (!(posObj instanceof BlockPos pos) || !(stateObj instanceof BlockState state)) {
                    continue;
                }
                if (state.isAir()) {
                    continue; // a break, or air being written - not a placement
                }
                if (regionSet) {
                    // Anywhere inside the build counts, however far from the player Baritone is working.
                    int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
                    if (px < regionMinX || px > regionMaxX
                            || py < regionMinY || py > regionMaxY
                            || pz < regionMinZ || pz > regionMaxZ) {
                        continue; // outside the schematic: not this build's progress
                    }
                } else if (pos.distSqr(centre) > radiusSq) {
                    continue; // no region known yet, so fall back to "near the player"
                }
                lastPlacementTick = tickCounter;
                placementCount++;
            }
        } catch (Throwable ignored) {
            // a throwing listener would propagate into Baritone's tick; never allow that
        }
    }
}
