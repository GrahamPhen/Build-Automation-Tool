package com.graham.startbuild;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The /startbuild state machine.
 *
 *   IDLE      - nothing running
 *   PRE_ROLL  - recording has started, waiting preRollSeconds so the first block is not frame one
 *   BUILDING  - Baritone is placing blocks; the stop countdown is held at zero
 *   COOLDOWN  - the build stopped; recording runs until stopDelaySeconds after the LAST BLOCK PLACED
 *
 * The countdown is anchored to a real placement, observed through Baritone's own block-change event
 * (see BaritoneEventBridge), not merely to the builder going idle. If the bridge could not be hooked
 * the anchor falls back to the moment the build stopped, which is the old behaviour.
 *
 * A materials pause is handled specially when autoResumeMaterials is on: the recording keeps rolling,
 * the missing blocks are reported, and the build resumes by itself once new block types appear in the
 * inventory.
 */
final class StartBuildSession {

    enum State { IDLE, PRE_ROLL, BUILDING, COOLDOWN }

    private static State state = State.IDLE;
    private static StartBuildConfig config = new StartBuildConfig();

    private static int preRollTicks;
    private static int graceTicks;
    private static long countdownAnchor;
    private static boolean sawBuildActive;
    private static boolean recordingStartedByUs;
    private static String finishReason = "";

    private static boolean episodeMode;
    private static int episodeNumber;
    private static int episodeStopLayer;
    /**
     * Lowest layer number Baritone has reported during this episode.
     *
     * The episode's end is measured as a DISTANCE from this, not against an absolute layer number, so it
     * is correct whichever numbering Baritone uses. An earlier version compared currentLayer() against
     * episodeNumber * episodeLayers, which is only right if currentLayer() is 1-based while building; if
     * that assumption is wrong every episode runs one layer long and overlaps the next, for ever.
     */
    private static int episodeLowestLayer = Integer.MAX_VALUE;

    private static boolean pauseHandled;
    private static int materialCheckCountdown;
    private static Set<String> inventorySnapshot = Set.of();
    private static String reportedMissing = "";

    /** Auto-stock: which autoStockFunctions entry is next, and which missing-set we last fed for. */
    private static int stockIndex;
    private static String stockFedFor = "";
    private static boolean stockExhaustedReported;
    private static int stockFeeds;
    /** Pending confirmation that a stock /function delivered; see verifyStockBatch. */
    private static long stockVerifyAt;
    private static List<String> stockVerifyTypes = List.of();
    private static String stockVerifyFunction = "";

    /** Unattended-run safety nets: how long we have been stuck, and the build's start time. */
    private static int pausedTicks;
    private static long buildStartTick;
    private static int diskCheckCountdown;

    /** Anchor for the "no progress" watchdog; moved forward whenever a stock batch is fed. */
    private static long noProgressAnchor;

    /** Set while a recording we started still needs saving; survives reset() so it can be retried. */
    private static boolean pendingFinish;
    private static int pendingFinishTicks;

    /** Watches for Flashback stopping recording behind our back. */
    private static int recordingCheckCountdown;
    private static boolean recordingLossReported;

    /** Set by the stuck-timer so the next pause tick resumes even without a new block type. */
    private static boolean forceResume;

    /** True when file mode turned Baritone's buildOnlySelection off and it must be put back. */
    private static boolean restoreBuildOnlySelection;

    /** One-run override from "/startbuild file <name>", so the command never rewrites the config. */
    private static String fileOverride;

    /**
     * Set when a watchdog ends the run, so the stop cannot be undone.
     *
     * This exists because of a real bug: tickCooldown() resumes the build whenever Baritone reports
     * itself active, and a builder that is active but placing nothing made the no-progress watchdog
     * fire, get "resumed", and fire again - twice per tick, for 13 minutes. It produced 7,908
     * "no blocks placed" and 7,907 "Build resumed" chat lines and made the game look frozen.
     */
    private static boolean stopIsFinal;

    /** Placements seen when the build began, so "nothing has been placed at all" is detectable. */
    private static long placementsAtBuildStart;
    private static boolean noPlacementWarned;

    /** Consecutive failed /function sends; the entry is not consumed on failure, so this bounds it. */
    private static int stockFailures;
    private static final int MAX_STOCK_FAILURES = 5;

    /** Tick the current cooldown began, so a flapping builder cannot flood chat with "resumed". */
    private static long cooldownSince;

    /** Stall recovery: how many automatic restarts have been tried, and whether we have given up. */
    private static int stallRecoveries;
    private static boolean stallGaveUp;
    /**
     * How much longer than stallRecoverSeconds a build may keep placing nothing while the player is still
     * moving, before the stall recovery takes it as wedged rather than walking.
     */
    private static final int MOVING_STALL_FACTOR = 4;

    /** True when this run turned Baritone's layer mode on and it must be put back. */
    private static boolean restoreBuildInLayers;
    private static boolean restoreBuildIgnoreDirection;

    /** The origin this run's build was requested at, reused when a stalled build is restarted. */
    private static BlockPos buildOrigin;
    /**
     * The build's footprint as known at build start, independent of Baritone.
     *
     * Needed because file mode has no Litematica placement to ask about, so {@code schematicBox} is null
     * for the whole run and anything that wanted the footprint silently did nothing.
     */
    private static BlockPos trackedMin;
    private static int trackedSizeX;
    private static int trackedSizeY;
    private static int trackedSizeZ;

    /**
     * The loaded schematic and where it was placed, kept for the finishing pass that places the blocks
     * Baritone cannot reach by clicking (cells with no solid neighbour). Read from the same Litematica
     * object used to create the placement, so there is no separate file parse to get wrong.
     */
    private static Object loadedSchematic;
    private static BlockPos loadedSchematicOrigin;
    private static Vec3i loadedSchematicSize;

    /** Types this run has supplied by /give, so only our own stock is ever cleared to make room. */
    private static final Set<String> suppliedTypes = new HashSet<>();
    private static final Map<String, Integer> giveAttempts = new HashMap<>();
    private static final int MAX_GIVE_ATTEMPTS = 3;

    /**
     * How many block types to supply per restock cycle.
     *
     * Deliberately small: every type needs its own free inventory slot, and clearing dozens of slots to
     * make room would throw away types Baritone is about to need. A handful per cycle, repeated every
     * restock interval, keeps up without gutting the inventory.
     */
    private static final int MAX_GIVES_PER_CYCLE = 4;

    /** The build's block types, most-used first, read from the stock datapack functions at start. */
    private static List<String> stockTypes = List.of();

    /** Gives waiting to be confirmed, type -> tick sent; and types reported present-but-missing. */
    private static final Map<String, Long> pendingGives = new HashMap<>();
    private static final Set<String> reportedPresent = new HashSet<>();
    private static final int GIVE_VERIFY_TICKS = 60;

    /** Countdown for the external stop-flag file check. */
    private static int stopFileCheckCountdown;

    /** So a desktop notification that cannot be sent is reported once, not silently swallowed. */
    private static boolean notificationFailedReported;

    /** Last tick the player actually moved - Baritone walking produces no block changes. */
    private static long lastMovementTick;
    private static BlockPos lastPlayerPos;

    /**
     * Records the last tick the player moved.
     *
     * Baritone spends a lot of time walking between blocks and pillaring up, and neither produces a block
     * change until the block is actually placed. Without this, a builder crossing a wide schematic for
     * three minutes looks identical to a builder that is wedged - and the stall recovery would CANCEL a
     * perfectly working build. That is a failure mode this mod would otherwise have created itself.
     */
    private static void trackPlayerMovement() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BlockPos now = minecraft.player.blockPosition();
        if (lastPlayerPos == null || !now.equals(lastPlayerPos)) {
            lastPlayerPos = now;
            lastMovementTick = BaritoneEventBridge.currentTick();
        }
    }

    /**
     * Ends the run when a flag file appears in the config folder, exactly as /stopbuild would.
     *
     * This is the interface for anything outside the game - a scheduled task, or an agent reading the
     * log. Terminating the process instead would lose the in-flight Flashback recording, so this is the
     * safe way to intervene in an unattended run.
     */
    private static void checkStopFile() {
        if (config == null || !config.stopFileEnabled) {
            return;
        }
        try {
            String name = (config.stopFileName == null || config.stopFileName.isBlank())
                    ? "startbuild-stop" : config.stopFileName;
            Path flag = FabricLoader.getInstance().getConfigDir().resolve(name);
            if (!Files.exists(flag)) {
                return;
            }
            Files.deleteIfExists(flag);
            StartBuildMod.chat("\u00A7eStop requested by '" + name + "' - stopping and saving the recording.");
            StartBuildMod.LOGGER.info("[StartBuild] stop file {} found", flag);
            if (stopNow() == 0 && pendingFinish) {
                tickPendingFinish();
            }
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] stop-file check failed: {}", Reflect.describe(t));
        }
    }

    private StartBuildSession() {
    }

    static State state() {
        return state;
    }

    static boolean isBusy() {
        return state != State.IDLE;
    }

    /** @param requestedPlacement 1-based placement, or <= 0 to use the config value. */
    static int start(int requestedPlacement) {
        return start(requestedPlacement, false);
    }

    /**
     * "/startbuild site &lt;name&gt;": pick a site, level the ground, place the schematic there and build it.
     *
     * The whole point is that this is the only command needed in a normal world. The steps are ordered so
     * everything destructive happens BEFORE the recording starts, so the video shows the build itself
     * sitting in ground prepared for it rather than the preparation.
     *
     * A provisional placement is created first only to learn the schematic's footprint, then removed and
     * recreated at the chosen site. That reuses placement code already proven to work instead of adding a
     * second way to read a schematic's size.
     */
    static int startAtSite(String fileName, int radius) {
        if (state != State.IDLE) {
            StartBuildMod.chat("\u00A7eAlready running (" + state + "). Use /stopbuild first.");
            return 0;
        }
        String name = normaliseSchematicName(fileName);
        if (name.isEmpty()) {
            StartBuildMod.chat("\u00A7cUsage: /startbuild site <name.litematic> [radius]");
            return 0;
        }
        if (!LitematicaBridge.available()) {
            StartBuildMod.chat("\u00A7cLitematica is not installed, so no placement can be made.");
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            StartBuildMod.chat("\u00A7cJoin a world first.");
            return 0;
        }
        File file = new File(new File(minecraft.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("\u00A7cNo such schematic: " + file.getAbsolutePath());
            return 0;
        }

        StartBuildConfig loaded = StartBuildConfig.load();
        BlockPos here = minecraft.player.blockPosition();
        String placementName = name.replaceFirst("(?i)\\.(litematic|schem|schematic)$", "");

        // 1. Read the file ONCE and measure it. No placement is created yet, so nothing of the player's
        //    is destroyed before we know the size - and the file (read synchronously) is read once.
        Object schematic = LitematicaBridge.loadSchematic(file.toPath().getParent(), name);
        if (schematic == null) {
            StartBuildMod.chat("\u00A7cLitematica could not read " + name + ".");
            return 0;
        }
        Vec3i size = LitematicaBridge.sizeOf(schematic);
        if (size == null || size.getX() <= 0 || size.getZ() <= 0) {
            StartBuildMod.chat("\u00A7cCould not read the schematic's size.");
            return 0;
        }
        int sizeX = size.getX();
        int sizeZ = size.getZ();

        // 2. Find the best site for a footprint that size.
        BlockPos origin = here;
        int baseY = here.getY();
        String surfaceBlock = "minecraft:dirt";
        String subsurfaceBlock = "minecraft:dirt";
        if (loaded.autoSite) {
            List<SiteFinder.Site> sites = SiteFinder.find(minecraft.level, here, radius, sizeX, sizeZ, 1);
            if (sites.isEmpty()) {
                StartBuildMod.chat("\u00A7eNo suitable site found within " + radius + " blocks; using where "
                        + "you are standing. Move somewhere flatter, or raise siteRadius.");
            } else {
                SiteFinder.Site site = sites.get(0);
                origin = site.origin();
                baseY = site.origin().getY();
                surfaceBlock = site.surfaceBlock();
                subsurfaceBlock = site.subsurfaceBlock();
                StartBuildMod.chat("\u00A7aSite chosen: " + site.describe(1) + "  cost=" + site.cost());
                StartBuildMod.chat("The ground there is mostly " + surfaceBlock.replace("minecraft:", "") + ".");
            }
        }

        // Refuse a buried base BEFORE anything is placed or recorded. Reached when no site could be
        // found and the fallback is "where you are standing", which is exactly how the build ended up
        // with its base at y=-60.
        String buried = buriedProblem(origin, sizeX, sizeZ);
        if (buried != null) {
            StartBuildMod.chat("\u00A7cNot starting: " + buried);
            return 0;
        }

        // 3. Level the ground, before any recording starts.
        if (loaded.terraform) {
            TerrainPrep.Result prep = TerrainPrep.level(minecraft.level, origin, sizeX, sizeZ, baseY,
                    surfaceBlock, subsurfaceBlock, loaded.terrainMargin);
            StartBuildMod.chat((prep.ok() ? "\u00A7aLevelled the site: " : "\u00A7ePartly levelled the site: ")
                    + prep.describe());
            if (!prep.ok()) {
                StartBuildMod.chat("\u00A7eSome fill commands were not accepted - check the log.");
            }
        }

        // 4. Stand just OUTSIDE the footprint, not on its corner: the build's bottom layer occupies the
        //    corner block, and a player standing in it is one of the things Baritone cannot place around.
        //    Feet go at baseY, which is the level of the levelled ground's surface plus one.
        trackedMin = new BlockPos(origin.getX(), baseY, origin.getZ());
        trackedSizeX = sizeX;
        trackedSizeY = size.getY();
        trackedSizeZ = sizeZ;
        loadedSchematic = schematic;
        loadedSchematicOrigin = new BlockPos(origin.getX(), baseY, origin.getZ());
        loadedSchematicSize = size;
        StartBuildMod.runServerCommand("tp @s " + (origin.getX() - 2 + 0.5) + " " + baseY + " "
                + (origin.getZ() - 2 + 0.5) + " 0 0");

        int[] placed = LitematicaBridge.place(schematic, origin, placementName, loaded.clearPlacementsOnPlace);
        if (placed[0] <= 0) {
            StartBuildMod.chat("\u00A7cCould not create the placement at the chosen site.");
            return 0;
        }
        if (placed[1] > 0) {
            StartBuildMod.chat("Removed " + placed[1] + " previous placement(s).");
        }

        fileOverride = "";                     // force the Litematica placement path for this run
        StartBuildMod.chat("\u00A7aPlaced at (" + origin.getX() + ", " + baseY + ", " + origin.getZ()
                + "). Starting the build and the recording.");
        prePlaceUnbuildableBlocks();
        return start(placed[0]);
    }

    /**
     * "/buildsite [radius]": report the best places around the player for the loaded schematic.
     *
     * Read-only on purpose. Choosing a site is the part that needs a human eye, so the numbers are shown
     * before anything is allowed to move earth - it is far cheaper to tune a scoring function against
     * real terrain than to discover it picks bad spots by watching a build half-buried in a hillside.
     */
    static int reportSites(int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            StartBuildMod.chat("\u00A7cJoin a world first.");
            return 0;
        }
        StartBuildConfig loaded = StartBuildConfig.load();

        int sizeX = 32;
        int sizeZ = 32;
        String source = "default 32x32, no Litematica placement loaded";
        int[] box = BaritoneBridge.schematicBox(loaded.litematicaPlacement);
        if (box != null && box.length >= 6) {
            // schematicBox returns {originX, originY, originZ, WIDTH, HEIGHT, LENGTH} - slots 3..5 are
            // SIZES, not maximum coordinates. Reading them as coordinates gave a nonsense footprint (an
            // 80-wide build at x=100 reported 21), so this searched for the wrong shape.
            sizeX = box[3];
            sizeZ = box[5];
            source = "the loaded Litematica placement";
        }

        StartBuildMod.chat("Looking for a " + sizeX + "x" + sizeZ + " site within " + radius
                + " blocks (" + source + ")...");
        List<SiteFinder.Site> sites = SiteFinder.find(minecraft.level, minecraft.player.blockPosition(),
                radius, sizeX, sizeZ, 5);

        if (sites.isEmpty()) {
            StartBuildMod.chat("\u00A7eNo suitable site found. Try a larger radius, or move somewhere "
                    + "less steep or less wet.");
            return 0;
        }
        StartBuildMod.chat("\u00A7aBest sites, lowest cost first:");
        for (int i = 0; i < sites.size(); i++) {
            StartBuildMod.chat("  " + sites.get(i).describe(i + 1) + "  cost=" + sites.get(i).cost());
        }
        StartBuildMod.chat("\u00A77clear = terrain to cut above the base, fill = gap to build up beneath.");
        return 1;
    }

    /**
     * "/startbuild file &lt;name&gt;": build a schematic straight out of the instance's schematics folder.
     *
     * Baritone loads the file itself, so there is no Litematica placement to create - stand where you
     * want the schematic's corner and run this. That is what removes the whole "load the schematic, then
     * create a placement" step for v7 schematics. Baritone rejects v6 as "too old" (21 of the 32 files
     * in Graham's library are v6), and those still need the Litematica placement path.
     *
     * The name is not written to the config, so this stays a one-off command.
     */
    static int startFromFile(String fileName) {
        String name = normaliseSchematicName(fileName);
        if (name.isEmpty()) {
            StartBuildMod.chat("\u00A7cUsage: /startbuild file <name.litematic>");
            return 0;
        }

        File file = new File(new File(Minecraft.getInstance().gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("\u00A7cNo such schematic: " + file.getAbsolutePath());
            StartBuildMod.chat("\u00A7eSchematics live in the instance's schematics folder. For anything "
                    + "Litematica can open but Baritone cannot (v6 and older), load it in Litematica and "
                    + "use /startbuild instead.");
            return 0;
        }

        fileOverride = name;
        StartBuildMod.chat("\u00A7aFile mode: building '" + name + "' from the schematics folder - no "
                + "Litematica placement needed.");
        return start(-1);
    }

    /**
     * "/startbuild place &lt;name&gt;": load a schematic into Litematica, create a placement at the player's
     * position, and build it - everything the "Load Schematics -&gt; Create a placement" menu walk does,
     * in one command. Works for any format Litematica can read, including the v6 files that Baritone's
     * file reader rejects.
     */
    static int startFromLitematica(String fileName) {
        // Refuse before touching anything: this clears placements, and doing that in the middle of a
        // running build would be destructive.
        if (state != State.IDLE) {
            StartBuildMod.chat("\u00A7eAlready running (" + state + "). Use /stopbuild first.");
            return 0;
        }
        String name = normaliseSchematicName(fileName);
        if (name.isEmpty()) {
            StartBuildMod.chat("\u00A7cUsage: /startbuild place <name.litematic>");
            return 0;
        }
        if (!LitematicaBridge.available()) {
            StartBuildMod.chat("\u00A7cLitematica is not installed, so there is nothing to create a placement with. "
                    + "Use /startbuild file <name> instead if the schematic is v7.");
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            StartBuildMod.chat("\u00A7cJoin a world first.");
            return 0;
        }
        File file = new File(new File(minecraft.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("\u00A7cNo such schematic: " + file.getAbsolutePath());
            return 0;
        }

        StartBuildConfig loaded = StartBuildConfig.load();
        BlockPos origin = minecraft.player.blockPosition();
        String placementName = name.replaceFirst("(?i)\\.(litematic|schem|schematic)$", "");

        // Read the file once so the footprint is known BEFORE anything is placed: placing first and then
        // discovering the base is buried would already have cleared the existing placements.
        Object schematic = LitematicaBridge.loadSchematic(file.toPath().getParent(), name);
        if (schematic == null) {
            StartBuildMod.chat("\u00A7cLitematica could not read " + name + ".");
            return 0;
        }
        Vec3i size = LitematicaBridge.sizeOf(schematic);
        if (size != null) {
            String buried = buriedProblem(origin, size.getX(), size.getZ());
            if (buried != null) {
                StartBuildMod.chat("\u00A7cNot starting: " + buried);
                return 0;
            }
        }

        // Keep the schematic and its world origin so the finishing pass can fill the cells Baritone cannot.
        loadedSchematic = schematic;
        loadedSchematicOrigin = origin;
        loadedSchematicSize = size;

        int[] result = LitematicaBridge.place(schematic, origin, placementName, loaded.clearPlacementsOnPlace);
        if (result[0] <= 0) {
            StartBuildMod.chat("\u00A7cCould not create a Litematica placement for " + name + ".");
            return 0;
        }
        if (result[1] > 0) {
            StartBuildMod.chat("Removed " + result[1] + " previous placement(s) so the overlays do not overlap.");
        }

        // Step OUT of the footprint before building.
        //
        // The placement's corner is exactly where the player was standing, so the build's bottom layers
        // occupy the space the player is in - and Baritone cannot place a block where the player is. That
        // shows up as a builder that is active, not paused, asking for no materials, and placing nothing,
        // which is precisely the stall a 14-minute run ended in. Moving the player does NOT move the
        // build: the placement origin is already fixed.
        //
        // If the footprint cannot be read, fall back to the origin we just placed at rather than skipping
        // the move silently - skipping it leaves the player in the corner cell, which IS the stall, and the
        // old code then went on to say "Stepping clear of it" either way.
        int[] box = BaritoneBridge.schematicBox(result[0]);
        String stepClear;
        if (box != null && box.length >= 6) {
            trackedMin = new BlockPos(box[0], box[1], box[2]);
            trackedSizeX = box[3];
            trackedSizeY = box[4];
            trackedSizeZ = box[5];
            StartBuildMod.runServerCommand("tp @s " + (box[0] - 2 + 0.5) + " " + box[1] + " "
                    + (box[2] - 2 + 0.5) + " 0 0");
            stepClear = "Stepping clear of it";
        } else {
            // The origin is the player's own block position, so two blocks out on X and Z is outside the
            // placement whichever way it extends.
            StartBuildMod.runServerCommand("tp @s " + (origin.getX() - 2 + 0.5) + " " + origin.getY() + " "
                    + (origin.getZ() - 2 + 0.5) + " 0 0");
            stepClear = "Stepped clear of the corner (footprint unreadable)";
            StartBuildMod.LOGGER.warn("[StartBuild] could not read the placement footprint for step-clear; "
                    + "used the placement origin instead");
        }

        // Force the Litematica placement path for this run, whatever the config's schematicFile says.
        fileOverride = "";
        StartBuildMod.chat("\u00A7aLoaded '" + name + "' and created placement " + result[0]
                + " with its corner here. " + stepClear + ", then building.");
        prePlaceUnbuildableBlocks();
        return start(result[0]);
    }

    /** Adds .litematic when no extension was given, and trims. Empty means "no name supplied". */
    private static String normaliseSchematicName(String fileName) {
        String name = (fileName == null) ? "" : fileName.trim();
        if (name.isEmpty()) {
            return "";
        }
        String lower = name.toLowerCase();
        if (!lower.endsWith(".litematic") && !lower.endsWith(".schem") && !lower.endsWith(".schematic")) {
            name = name + ".litematic";
        }
        return name;
    }

    /** @param nextEpisode true for "/startbuild next": record only the next episodeLayers layers. */
    static int start(int requestedPlacement, boolean nextEpisode) {
        config = StartBuildConfig.load();
        if (fileOverride != null) {
            config.schematicFile = fileOverride;
            // Consume it. It used to be cleared only by reset()/startFromLitematica, so a start that
            // bailed out early (already running, low disk, no Baritone, a failed pre-flight) left it set
            // and the NEXT plain /startbuild silently built this stale file instead of the configured
            // Litematica placement. Nobody would connect the two.
            fileOverride = null;
        }

        if (state != State.IDLE) {
            StartBuildMod.chat("\u00A7eAlready running (" + state + "). Use /stopbuild first.");
            return 0;
        }
        if (!BaritoneBridge.available()) {
            StartBuildMod.chat("\u00A7cBaritone is not installed or its API is unreachable, so there is nothing to build with.");
            return 0;
        }
        if (BaritoneBridge.isBuildActive()) {
            StartBuildMod.chat("\u00A7cBaritone is already running a build. Use /stopbuild (or Baritone's #cancel) first.");
            return 0;
        }

        int placement = (requestedPlacement > 0) ? requestedPlacement : config.litematicaPlacement;
        if (requestedPlacement > 0) {
            config.litematicaPlacement = placement;
        }

        // Pre-flight 1: enough disk for a long recording?
        String diskProblem = diskSpaceProblem();
        if (diskProblem != null) {
            StartBuildMod.chat("\u00A7c" + diskProblem);
            return 0;
        }

        // Pre-flight 0: make the take camera-clean without needing a separate /buildprep.
        if (config.autoVideoSettings) {
            List<String> changed = BaritoneBridge.applyVideoSettings();
            if (!changed.isEmpty()) {
                StartBuildMod.chat("\u00A7aBaritone video settings applied: " + String.join(", ", changed));
            }
            // allowInventory is load-bearing for everything downstream: without it Baritone uses only
            // the 9 hotbar slots, so every stock batch overflows and the build pauses over and over
            // with the recording running. Refuse rather than start a run that cannot work.
            Object allowInventory = BaritoneBridge.settingValue("allowInventory");
            if (Boolean.FALSE.equals(allowInventory)) {
                StartBuildMod.chat("\u00A7cBaritone's allowInventory is OFF and could not be turned on, so it "
                        + "would use only the 9 hotbar slots and run out of blocks constantly. Run "
                        + "#set allowInventory true, then /startbuild again.");
                return 0;
            }
            if (allowInventory == null) {
                StartBuildMod.chat("\u00A7eCould not read Baritone's allowInventory setting - if the build keeps "
                        + "pausing for blocks, set it with #set allowInventory true.");
            }
        }

        // Auto-select the build box, so nobody has to stand at two corners and type #sel pos1/pos2.
        // Read the setting once: two separate reads can disagree if one of them fails.
        Boolean buildOnlySelection = BaritoneBridge.isBuildOnlySelection();

        // Layer-by-layer building. This is the single biggest reliability lever for a big schematic:
        // without it Baritone picks the nearest remaining block across the whole volume and can thrash
        // instead of progressing (one run managed 160 blocks in ten minutes and then stalled). It also
        // makes progress observable - /buildstatus can only report "layer N of M" in layer mode.
        if (config.buildInLayers && !Boolean.TRUE.equals(BaritoneBridge.isBuildInLayers())) {
            if (BaritoneBridge.setBuildInLayers(true)) {
                restoreBuildInLayers = true;
                StartBuildMod.chat("\u00A7aLayer mode on for this build (restored afterwards).");
            }
        }

        // -----------------------------------------------------------------------------------------
        // THE fix for the failure that made every run stop at layer 1.
        //
        // Baritone's builder derives the block state an item can place from a synthetic upward-facing
        // click, so a pillar item (deepslate, basalt, logs, wood) is only ever seen as placeable in
        // `axis=y`. The schematic asks for `axis=x`. Baritone compares states exactly, so it files every
        // `axis=x` cell as a MISSING MATERIAL, never generates a goal for it, and - in layer mode - can
        // never close the layer. Measured: 0 of 1081 property-bearing cells were ever placed, across 8
        // runs; "Starting layer 2" appears 0 times in all 14 logs.
        //
        // `buildIgnoreDirection=true` makes the axis property ignored in that comparison, so `axis=x`
        // cells become buildable and the layers close. The other unbuildable class - cells with no solid
        // neighbour at all - is handled by prePlaceUnbuildableBlocks(), which /setblocks them before
        // Baritone starts. Together they mean nothing ever has to be skipped. Restored afterwards, like
        // buildInLayers.
        if (!Boolean.TRUE.equals(BaritoneBridge.settingValue("buildIgnoreDirection"))) {
            if (BaritoneBridge.setSetting("buildIgnoreDirection", Boolean.TRUE)) {
                restoreBuildIgnoreDirection = true;
                StartBuildMod.chat("Ignoring block orientation while building (restored afterwards).");
            }
        }

        // File mode builds a .litematic straight through Baritone, with no Litematica placement
        // involved at all - so there is no placed schematic to derive a selection from, and a
        // selection-based build would just refuse. Turn it off for this run and put it back afterwards.
        // This is what lets a v7 schematic be built with no placement step whatsoever.
        if (!config.schematicFile.isBlank() && Boolean.TRUE.equals(buildOnlySelection)) {
            if (BaritoneBridge.setSetting("buildOnlySelection", Boolean.FALSE)) {
                restoreBuildOnlySelection = true;
                buildOnlySelection = Boolean.FALSE;
                StartBuildMod.chat("\u00A7eFile mode: buildOnlySelection turned off for this run (there is no "
                        + "Litematica placement to take a selection from). It is restored afterwards.");
            } else {
                StartBuildMod.chat("\u00A7cCould not turn off buildOnlySelection, so file mode would build "
                        + "nothing. Run #set buildOnlySelection false and try again.");
                return 0;
            }
        }

        if (Boolean.TRUE.equals(buildOnlySelection)) {
            String mode = (config.autoSelection == null) ? "none" : config.autoSelection.trim().toLowerCase();
            if (!mode.isEmpty() && !"none".equals(mode)) {
                int steps = "corner".equals(mode) ? config.selectionCornerSize : config.selectionLayers;
                String summary = autoSelectBox(mode, steps, config.autoSelectionReplace, placement);
                if (summary != null) {
                    StartBuildMod.chat("\u00A7aAuto-selected " + summary);
                    StartBuildMod.chat("\u00A7aAdjust with /buildsel full | layers N | corner N, or /buildsel off.");
                }
            }
        }

        // Pre-flight 2: buildOnlySelection with no selection builds exactly nothing.
        if (Boolean.TRUE.equals(buildOnlySelection) && BaritoneBridge.selectionCount() == 0) {
            StartBuildMod.chat("\u00A7cBaritone's buildOnlySelection is ON but no selection is set, so it would "
                    + "build nothing. Set one first with #sel pos1 then #sel pos2, or run "
                    + "#set buildOnlySelection false to build the whole schematic.");
            return 0;
        }

        // Pre-flight 3: episode mode needs Baritone's layer mode.
        episodeMode = false;
        if (nextEpisode) {
            if (!Boolean.TRUE.equals(BaritoneBridge.isBuildInLayers())) {
                BaritoneBridge.setBuildInLayers(true);
                StartBuildMod.chat("Turned on Baritone's buildInLayers for episode mode.");
            }
            // Read it back rather than assuming: a null/failed read previously left episode mode armed
            // with layer mode off, so the whole schematic was recorded as "episode 1".
            if (!Boolean.TRUE.equals(BaritoneBridge.isBuildInLayers())) {
                StartBuildMod.chat("\u00A7cCould not enable Baritone's buildInLayers, so episode mode cannot "
                        + "work. Run #set buildInLayers true and try again, or use /startbuild normally.");
                return 0;
            }
            int layers = Math.max(1, config.episodeLayers);
            episodeNumber = config.episodesDone + 1;
            int startLayer = (episodeNumber - 1) * layers;
            episodeStopLayer = startLayer + layers - 1;
            if (!BaritoneBridge.setStartAtLayer(startLayer)) {
                StartBuildMod.chat("\u00A7cCould not set Baritone's startAtLayer, so episode mode cannot work. "
                        + "Run #set buildInLayers true manually and use /startbuild normally.");
                return 0;
            }
            // The end of the episode is decided from OBSERVED layer numbers, not from the number we set:
            // Baritone's getMinLayer() numbering is not part of any documentation we could verify, and an
            // off-by-one here would make every episode overlap the next by a layer, permanently. See
            // episodeLowestLayer. episodeStopLayer is for the message only.
            episodeLowestLayer = Integer.MAX_VALUE;
            episodeMode = true;
            StartBuildMod.chat(String.format("Episode %d: layers %d-%d.", episodeNumber,
                    startLayer + 1, startLayer + layers));
        } else {
            // A previous "/startbuild next" leaves Baritone's persistent startAtLayer set, and a plain
            // /startbuild would then silently build only from that layer upwards while reporting a
            // normal full build.
            Integer leaked = BaritoneBridge.startAtLayer();
            if (leaked != null && leaked > 0) {
                if (BaritoneBridge.setStartAtLayer(0)) {
                    StartBuildMod.chat("\u00A7eReset Baritone's startAtLayer from " + leaked
                            + " to 0 (left over from an episode run).");
                } else {
                    // Reporting nothing here was the whole problem: this run WILL build only from
                    // `leaked` upwards while still claiming to be a normal full build.
                    StartBuildMod.chat("\u00A7cCould not reset Baritone's startAtLayer (still " + leaked
                            + "), so this build would start at that layer and skip everything below it. "
                            + "Run #set startAtLayer 0 and start again.");
                    return 0;
                }
            }
        }

        recordingStartedByUs = false;
        if (config.startRecording) {
            if (!FlashbackBridge.available()) {
                StartBuildMod.chat("\u00A7eFlashback is not installed - building without recording.");
            } else if (FlashbackBridge.isRecording()) {
                StartBuildMod.chat("\u00A7eFlashback is already recording; leaving that recording alone.");
            } else {
                if (Boolean.FALSE.equals(FlashbackBridge.isQuicksaveEnabled())) {
                    if (config.forceQuicksave) {
                        if (FlashbackBridge.setQuicksave(true)) {
                            StartBuildMod.chat("Forced Flashback quicksave on for this session (replay saves with no dialog).");
                        }
                    } else {
                        StartBuildMod.chat("\u00A7eFlashback quicksave is OFF, so the replay will be saved through the naming screen. "
                                + "Enable Recording > Quicksave in Flashback's config, or set forceQuicksave=true in startbuild.json.");
                    }
                }
                if (FlashbackBridge.startRecording()) {
                    recordingStartedByUs = true;
                } else {
                    StartBuildMod.chat("\u00A7cCould not start the Flashback recording; aborting so the build is not wasted.");
                    return 0;
                }
            }
        }

        preRollTicks = config.preRollTicks();
        graceTicks = config.startupGraceTicks();
        sawBuildActive = false;
        pauseHandled = false;
        reportedMissing = "";
        inventorySnapshot = Set.of();
        finishReason = "";
        stockIndex = 0;
        stockFedFor = "";
        stockExhaustedReported = false;
        stockFeeds = 0;
        pausedTicks = 0;
        buildStartTick = 0;
        noProgressAnchor = 0;
        diskCheckCountdown = 600;
        recordingCheckCountdown = 100;
        recordingLossReported = false;
        stopIsFinal = false;
        noPlacementWarned = false;
        stockFailures = 0;
        stockVerifyAt = 0;
        stockVerifyTypes = List.of();
        stockVerifyFunction = "";
        stockFailedReported = false;
        lastWorldProgressTick = 0;
        lastFilledSample = -1;
        progressSampleCountdown = 0;
        lastSeenLayer = -1;
        cooldownSince = 0;
        stallRecoveries = 0;
        stallGaveUp = false;
        buildOrigin = null;
        trackedMin = null;
        trackedSizeX = 0;
        trackedSizeY = 0;
        trackedSizeZ = 0;
        loadedSchematic = null;
        loadedSchematicOrigin = null;
        loadedSchematicSize = null;
        episodeLowestLayer = Integer.MAX_VALUE;
        notificationFailedReported = false;
        suppliedTypes.clear();
        giveAttempts.clear();
        pendingGives.clear();
        reportedPresent.clear();
        stopFileCheckCountdown = 0;
        lastMovementTick = 0;
        lastPlayerPos = null;
        stockTypes = List.of();
        forceResume = false;
        BaritoneEventBridge.clearMissingMaterials();
        BaritoneEventBridge.resetMessageLog();
        BaritoneEventBridge.clearBuildRegion();
        state = State.PRE_ROLL;

        // Stock batch 1 up front, so the build does not immediately pause on an empty inventory.
        if (config.autoStockAtStart) {
            feedNextStockBatch("stocking the build");
        }

        // Learn the build's block types from the stock functions: both as the fallback source of needed
        // types when Baritone's text cannot be read (gated separately by topTypeFallbackCount in
        // ensureTopTypes), and as the RANKING that freeOneSlot uses to clear the rarest type first.
        // Loading it only when topTypeFallbackCount > 0 left the ranking empty for anyone who turned the
        // fallback off ("0 = off"), and every candidate then tied at "unknown" so the clear order was
        // whatever HashMap iteration happened to produce - which can clear the type Baritone needs next.
        if (config.directRestock) {
            stockTypes = StockFunctions.typesInOrder(config.autoStockFunctions);
            if (!stockTypes.isEmpty()) {
                StartBuildMod.LOGGER.info("[StartBuild] stock list: {} block types (most-used first)",
                        stockTypes.size());
            }
        }

        StartBuildMod.chat("\u00A7aArmed. Target: " + (config.schematicFile.isBlank()
                ? "Litematica placement " + placement
                : "schematic file " + config.schematicFile)
                + (recordingStartedByUs ? ". Recording is live." : "."));
        return 1;
    }

    /**
     * Work out the Baritone selection automatically from the loaded Litematica placement.
     *
     * Modes: "full" = the whole build footprint, "layers" = the bottom N layers,
     * "corner" = an N-block cube at the build's minimum corner.
     *
     * @return a description of the box that was set, or null when nothing changed.
     */
    private static String autoSelectBox(String mode, int amount, boolean replaceExisting, int placement) {
        int existing = BaritoneBridge.selectionCount();
        if (existing > 0 && !replaceExisting) {
            StartBuildMod.chat("\u00A7eUsing your existing Baritone selection (" + existing
                    + "). autoSelectionReplace=false, so it was left alone.");
            return null;
        }
        int[] box = BaritoneBridge.schematicBox(placement);
        if (box == null) {
            return null;
        }
        final int x0 = box[0];
        final int y0 = box[1];
        final int z0 = box[2];
        final int width = box[3];
        final int height = box[4];
        final int length = box[5];
        final int steps = Math.max(1, amount);

        int minX = x0;
        int minY = y0;
        int minZ = z0;
        int maxX = x0 + width - 1;
        int maxY = y0 + height - 1;
        int maxZ = z0 + length - 1;
        String label;
        switch (mode) {
            case "layers" -> {
                maxY = y0 + Math.min(steps, height) - 1;
                label = steps + " layer(s)";
            }
            case "corner" -> {
                maxX = x0 + Math.min(steps, width) - 1;
                maxY = y0 + Math.min(steps, height) - 1;
                maxZ = z0 + Math.min(steps, length) - 1;
                label = steps + "-block corner";
            }
            case "full" -> label = "the whole build footprint";
            default -> {
                return null;
            }
        }
        if (!BaritoneBridge.setSelection(minX, minY, minZ, maxX, maxY, maxZ)) {
            return null;
        }
        return String.format("%s: %dx%dx%d at (%d, %d, %d) to (%d, %d, %d)",
                label, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** /buildsel - report the build box, the current selection, and the auto-selection settings. */
    static int selectionStatus() {
        int[] current = BaritoneBridge.currentSelectionBox();
        if (current == null) {
            StartBuildMod.chat("No Baritone selection right now.");
        } else {
            StartBuildMod.chat(String.format("Selection: %dx%dx%d at (%d, %d, %d) to (%d, %d, %d), %d selection(s)",
                    current[3] - current[0] + 1, current[4] - current[1] + 1, current[5] - current[2] + 1,
                    current[0], current[1], current[2], current[3], current[4], current[5], current[6]));
        }
        StartBuildConfig cfg = StartBuildConfig.load();
        int[] box = BaritoneBridge.schematicBox(cfg.litematicaPlacement);
        if (box == null) {
            StartBuildMod.chat("Build box: unavailable - is a Litematica placement loaded?");
        } else {
            StartBuildMod.chat(String.format("Build box: placement %d is %dx%dx%d at (%d, %d, %d)",
                    cfg.litematicaPlacement, box[3], box[4], box[5], box[0], box[1], box[2]));
        }
        StartBuildMod.chat("autoSelection=" + cfg.autoSelection + "  layers=" + cfg.selectionLayers
                + "  corner=" + cfg.selectionCornerSize + "  replace=" + cfg.autoSelectionReplace);
        StartBuildMod.chat("Commands: /buildsel full | layers N | corner N | off");
        return 1;
    }

    /** /buildsel &lt;mode&gt; [n] - apply a selection now and remember the mode. */
    static int applyAutoSelection(String mode, int amount) {
        StartBuildConfig cfg = StartBuildConfig.load();
        if (mode != null && !mode.isBlank()) {
            cfg.autoSelection = mode;
            if (amount > 0) {
                if ("corner".equals(mode)) {
                    cfg.selectionCornerSize = amount;
                } else {
                    cfg.selectionLayers = amount;
                }
            }
            cfg.save();
            cfg = StartBuildConfig.load();
        }
        String effective = (cfg.autoSelection == null) ? "none" : cfg.autoSelection.trim().toLowerCase();
        if ("none".equals(effective)) {
            StartBuildMod.chat("Automatic selection is off. Use /buildsel full | layers N | corner N to turn it on.");
            return 1;
        }
        int steps = "corner".equals(effective) ? cfg.selectionCornerSize : cfg.selectionLayers;
        String summary = autoSelectBox(effective, steps, true, cfg.litematicaPlacement);
        if (summary == null) {
            StartBuildMod.chat("\u00A7cCould not work out the build box - is a Litematica placement loaded?");
            return 0;
        }
        StartBuildMod.chat("\u00A7aAuto-selected " + summary);
        return 1;
    }

    /** /buildsel off - stop selecting automatically (does not touch existing selections). */
    static int disableAutoSelection() {
        StartBuildConfig cfg = StartBuildConfig.load();
        cfg.autoSelection = "none";
        cfg.save();
        StartBuildMod.chat("Automatic selection off. Existing Baritone selections are untouched "
                + "(use Baritone's #sel clear to remove them).");
        return 1;
    }

    /** How far below the local ground a build's base may sit before it is treated as buried. */
    private static final int BURIED_TOLERANCE = 4;

    /**
     * Is this build's base buried under the local ground?
     *
     * A buried placement is not merely ugly, it is one of the main ways a run "freezes": Baritone has to
     * mine the schematic's entire volume out before it can place anything, so it reports active and
     * unpaused while making almost no progress - "active but has placed nothing" - and every stall
     * restart lands in the same hole. The run this was written for had an 80-block-tall schematic whose
     * base was at y=-60 because the player was standing deep underground when the command ran: 233 blocks
     * in fifteen minutes, then a permanent stall, twice.
     *
     * Compares the base against the MEDIAN surface height over the footprint rather than one sample, so a
     * single dip or a tree does not trigger it.
     *
     * @return null when the base looks placeable, else a message naming both heights
     */
    private static String buriedProblem(BlockPos origin, int sizeX, int sizeZ) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || sizeX <= 0 || sizeZ <= 0) {
                return null;
            }
            int step = Math.max(1, Math.min(sizeX, sizeZ) / 16);
            List<Integer> tops = new ArrayList<>();
            for (int dx = 0; dx < sizeX; dx += step) {
                for (int dz = 0; dz < sizeZ; dz += step) {
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    if (!minecraft.level.hasChunk(x >> 4, z >> 4)) {
                        continue;                    // unloaded chunks report height 0, so ignore them
                    }
                    tops.add(minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
                }
            }
            if (tops.isEmpty()) {
                return null;                         // nothing loaded to judge by; do not block the run
            }
            tops.sort(Integer::compareTo);
            int median = tops.get(tops.size() / 2);
            int buriedBy = median - origin.getY();
            if (buriedBy < BURIED_TOLERANCE) {
                return null;
            }
            return "the build's base is at y=" + origin.getY() + " but the ground under it is at about y="
                    + median + ", so " + buriedBy + " block(s) of it would start inside the terrain. "
                    + "Baritone would have to mine the whole volume out first, which is what makes a run "
                    + "look frozen while reporting that it is active. Stand at the surface, or use "
                    + "/startbuild site <name> to pick and level a site automatically.";
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] buried check failed: {}", Reflect.describe(t));
            return null;
        }
    }

    private static String diskSpaceProblem() {        try {
            File dir = Minecraft.getInstance().gameDirectory;
            long free = dir.getUsableSpace();
            double freeGB = free / 1024.0 / 1024.0 / 1024.0;
            if (freeGB < config.minFreeDiskGB) {
                return String.format("Only %.1f GB free on the drive holding the game (minFreeDiskGB=%.1f). "
                        + "Long Flashback recordings are large - free some space or lower the setting.",
                        freeGB, config.minFreeDiskGB);
            }
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] disk space check failed: {}", Reflect.describe(t));
        }
        return null;
    }

    static void tick() {
        // Runs regardless of state: a recording we started that could not be saved must not be
        // abandoned, because then nothing in the mod will ever stop it.
        if (pendingFinish) {
            tickPendingFinish();
        }
        // Cheap when nothing is pending, and it is the only thing that checks a /give actually landed.
        if (!pendingGives.isEmpty()) {
            verifyPendingGives();
        }
        if (stockVerifyAt > 0 && BaritoneEventBridge.currentTick() >= stockVerifyAt) {
            verifyStockBatch();
        }
        trackPlayerMovement();

        // Lets something outside the game end a run cleanly, without a keyboard.
        if (--stopFileCheckCountdown <= 0) {
            stopFileCheckCountdown = 20;
            checkStopFile();
        }
        switch (state) {
            case IDLE -> {
            }
            case PRE_ROLL -> {
                if (preRollTicks-- > 0) {
                    return;
                }
                beginBuild();
            }
            case BUILDING -> tickBuilding();
            case COOLDOWN -> tickCooldown();
        }
    }

    private static void beginBuild() {
        Minecraft minecraft = Minecraft.getInstance();
        noProgressAnchor = BaritoneEventBridge.currentTick();
        placementsAtBuildStart = BaritoneEventBridge.placementCount();
        noPlacementWarned = false;
        boolean started;

        if (config.schematicFile.isBlank()) {
            started = BaritoneBridge.requestLitematicaBuild(config.litematicaPlacement);
        } else {
            File file = new File(new File(minecraft.gameDirectory, "schematics"), config.schematicFile);
            if (!file.exists()) {
                abort("schematic file not found: " + file.getAbsolutePath());
                return;
            }
            Vec3i origin = (minecraft.player != null) ? minecraft.player.blockPosition() : Vec3i.ZERO;
            // Remembered so a stall restart cannot relocate the build to wherever the player is by then.
            buildOrigin = (minecraft.player != null) ? minecraft.player.blockPosition() : null;

            // File mode takes its origin from the player, so by construction the player starts INSIDE the
            // build's bottom layer - the documented usage ("stand where you want the corner") guarantees the
            // one stall Baritone cannot work around. Read the size through Litematica (read-only, and it
            // reads the v6 files Baritone rejects too) and step the player clear BEFORE dispatching.
            Vec3i size = null;
            if (LitematicaBridge.available()) {
                Object schematic = LitematicaBridge.loadSchematic(file.toPath().getParent(), file.getName());
                size = LitematicaBridge.sizeOf(schematic);
            }
            if (size != null && size.getX() > 0 && size.getZ() > 0) {
                trackedMin = new BlockPos(origin.getX(), origin.getY(), origin.getZ());
                trackedSizeX = size.getX();
                trackedSizeY = size.getY();
                trackedSizeZ = size.getZ();
                StartBuildMod.runServerCommand("tp @s " + (origin.getX() - 2 + 0.5) + " " + origin.getY() + " "
                        + (origin.getZ() - 2 + 0.5) + " 0 0");
                StartBuildMod.chat("Stepped clear of the " + size.getX() + "x" + size.getZ()
                        + " footprint so Baritone can place its bottom layer.");
            } else {
                trackedMin = null;
                trackedSizeX = 0;
                trackedSizeY = 0;
                trackedSizeZ = 0;
                StartBuildMod.chat("\u00A7eCould not read that schematic's size, so you are still standing on "
                        + "its corner block - step two blocks back on X and Z or Baritone may refuse to place "
                        + "the bottom layer.");
            }
            started = BaritoneBridge.buildFromFile(file, origin);
        }

        if (!started) {
            abort("Baritone would not accept the build request");
            return;
        }

        state = State.BUILDING;
        buildStartTick = BaritoneEventBridge.currentTick();
        diskCheckCountdown = 600;
        pausedTicks = 0;
        // The progress clock must watch the BUILD, not a small sphere around the player. See
        // BaritoneEventBridge.setBuildRegion - scoping it to 8 blocks is what made every large build
        // look stalled and get cancelled after 180 seconds.
        publishBuildRegion();
        // No mobs in the build volume: a creature standing on the next block stalls placement for ever.
        clearNearbyMobs();
        StartBuildMod.chat("Build requested; watching for the last block.");
    }

    /** Kills every non-player entity near the build. Mobs block placement; item drops do not. */
    private static void clearNearbyMobs() {
        // "execute if entity ..." so a no-match kill does not spam "No entity was found" every 20 seconds.
        StartBuildMod.runServerCommand("execute if entity @e[type=!minecraft:player,distance=.."
                + (int) MOB_CLEAR_RADIUS + "] run kill @e[type=!minecraft:player,distance=.."
                + (int) MOB_CLEAR_RADIUS + "]");
    }

    /**
     * Tells the event bridge which volume counts as progress, preferring Baritone's own view of the
     * placement and falling back to the footprint captured when the build started (the only source there
     * is in file mode, where no Litematica placement exists).
     */
    private static void publishBuildRegion() {
        int[] box = BaritoneBridge.schematicBox(config.litematicaPlacement);
        if (box != null && box.length >= 6) {
            BaritoneEventBridge.setBuildRegion(box[0], box[1], box[2],
                    box[0] + box[3] - 1, box[1] + box[4] - 1, box[2] + box[5] - 1);
            return;
        }
        if (trackedMin != null && trackedSizeX > 0 && trackedSizeY > 0 && trackedSizeZ > 0) {
            BaritoneEventBridge.setBuildRegion(
                    trackedMin.getX(), trackedMin.getY(), trackedMin.getZ(),
                    trackedMin.getX() + trackedSizeX - 1,
                    trackedMin.getY() + trackedSizeY - 1,
                    trackedMin.getZ() + trackedSizeZ - 1);
            return;
        }
        BaritoneEventBridge.clearBuildRegion();
        StartBuildMod.chat("\u00A7eCould not work out the build's volume, so progress is judged by blocks "
                + "placed near you - which under-counts on a large schematic and can trigger a false stall.");
    }

    private static void tickBuilding() {
        if (episodeMode) {
            int layer = BaritoneBridge.currentLayer();
            if (layer >= 0) {
                // Remember the first layer we ever see in this episode, then stop once Baritone has moved
                // `episodeLayers` layers past it. Subtracting two readings of the same method cancels out
                // whatever base Baritone counts from, so this needs no assumption about its numbering.
                if (layer < episodeLowestLayer) {
                    episodeLowestLayer = layer;
                }
                int layers = Math.max(1, config.episodeLayers);
                if (layer - episodeLowestLayer >= layers) {
                    BaritoneBridge.cancelBuild();
                    beginCooldown("episode " + episodeNumber + " complete (through layer "
                            + (episodeStopLayer + 1) + ")");
                    return;
                }
            }
        }

        // Unattended safety net 1: a build that is never going to finish must not record for ever.
        if (config.maxBuildMinutes > 0 && buildStartTick > 0) {
            long elapsed = BaritoneEventBridge.currentTick() - buildStartTick;
            if (elapsed > (long) (config.maxBuildMinutes * 60.0 * 20.0)) {
                endRunForGood("max build time reached (" + (int) config.maxBuildMinutes + " min)");
                return;
            }
        }

        // Stall detection. "Active but placing nothing" is the signature that cost a whole run once:
        // Baritone reports isBuildActive()=true and isBuildPaused()=false while making no progress, so
        // no existing watchdog noticed. Measured from the last placement, or from the build start when
        // nothing has been placed at all - which is the same measurement, so one clock covers both.
        //
        // The world sample is taken first and unconditionally: it is the only progress signal that does not
        // depend on Baritone telling us the truth about its own work. See sampleWorldProgress().
        sampleWorldProgress();
        // Mobs spawn during a long run; clear them on a cadence so one cannot sit on a block and stall it.
        if (--mobClearCountdown <= 0) {
            mobClearCountdown = MOB_CLEAR_INTERVAL_TICKS;
            clearNearbyMobs();
        }
        if ((config.warnIfNoPlacementSeconds > 0 || config.stallRecoverSeconds > 0) && buildStartTick > 0) {
            long now = BaritoneEventBridge.currentTick();
            // Two clocks, because they answer different questions.
            //
            // lastPlacement is the honest progress clock. lastActivity additionally counts the player
            // moving, because walking and pillaring change no blocks - so a builder crossing a wide
            // schematic can look idle to a placement-only clock for minutes at a time.
            //
            // The RESTART cannot use lastActivity alone: Baritone's builder walks almost constantly, so a
            // builder that is wedged against something it keeps re-pathing to would be "active" for ever
            // and the recovery would never fire. Hence: restart when nothing has moved at all for
            // stallRecoverSeconds, or when nothing has been PLACED for MOVING_STALL_FACTOR times that even
            // though the player is moving. The second threshold is long enough that legitimately walking to
            // the far side of a big build does not trip it.
            long lastPlacement = Math.max(
                    Math.max(BaritoneEventBridge.lastPlacementTick(), lastWorldProgressTick),
                    Math.max(buildStartTick, noProgressAnchor));
            long lastActivity = Math.max(lastPlacement, lastMovementTick);
            long quietTicks = now - lastActivity;
            long placementQuietTicks = now - lastPlacement;
            long stallTicks = (long) (config.stallRecoverSeconds * 20.0);

            // A placement means progress, so arm the warning again for any later stall.
            if (placementQuietTicks < 20) {
                noPlacementWarned = false;
            }

            if (!noPlacementWarned && config.warnIfNoPlacementSeconds > 0
                    && quietTicks > (long) (config.warnIfNoPlacementSeconds * 20.0)) {
                noPlacementWarned = true;
                StartBuildMod.chat("\u00A7eBaritone is active but has placed nothing for "
                        + (int) config.warnIfNoPlacementSeconds + "s"
                        + (BaritoneEventBridge.placementCount() == placementsAtBuildStart
                            ? " - it may not have accepted the schematic (it only reads litematic v7; "
                              + "use /startbuild place <name> for older files)."
                            : " - it looks stuck."));
            }

            if (config.stallRecoverSeconds > 0) {
                boolean frozen = quietTicks > stallTicks;                  // nothing placed AND nothing moved
                boolean movingButNotBuilding = placementQuietTicks > stallTicks * MOVING_STALL_FACTOR;
                if (frozen || movingButNotBuilding) {
                    attemptStallRecovery();
                    return;
                }
            }
        }

        // Unattended safety net 2: stop and save before a multi-hour recording fills the drive.
        if (config.diskWatchdog && --diskCheckCountdown <= 0) {
            diskCheckCountdown = 600;
            String diskProblem = diskSpaceProblem();
            if (diskProblem != null) {
                StartBuildMod.chat("\u00A7c" + diskProblem);
                endRunForGood("low disk space");
                return;
            }
        }

        // Unattended safety net 3: notice if Flashback stopped recording behind our back. Otherwise a
        // multi-hour run builds nothing anyone can watch, and only finds out at the end.
        if (recordingStartedByUs && --recordingCheckCountdown <= 0) {
            recordingCheckCountdown = 100;
            if (!FlashbackBridge.isRecording()) {
                if (!recordingLossReported) {
                    recordingLossReported = true;
                    StartBuildMod.chat("\u00A7cFlashback is no longer recording, so this build is not being "
                            + "captured. Stopping here rather than building the rest for nothing.");
                }
                endRunForGood("the recording stopped unexpectedly");
                return;
            }
        }

        // Unattended safety net 4: no block placed for a long time means we are stuck, whatever the
        // cause. Measured from the last placement OR the last batch fed, so legitimate stocking pauses
        // do not trip it, and clamped forward for the same stale-static reason as the countdown anchor.
        if (config.noProgressMinutes > 0) {
            long last = Math.max(BaritoneEventBridge.lastPlacementTick(), noProgressAnchor);
            if (BaritoneEventBridge.currentTick() - last > (long) (config.noProgressMinutes * 60.0 * 20.0)) {
                endRunForGood("no blocks placed for " + (int) config.noProgressMinutes + " min");
                return;
            }
        }

        if (BaritoneBridge.isBuildActive()) {
            sawBuildActive = true;
            if (BaritoneBridge.isBuildPaused()) {
                handlePause();
            } else {
                pauseHandled = false;
                pausedTicks = 0;
            }
            return;
        }

        if (sawBuildActive) {
            // Baritone is done. Before the recording stops, fill in any cell it could not place - the ones
            // with no solid neighbour to click against, plus any it skipped for a wrong orientation. This is
            // what makes the build 100% complete instead of ~0.5% short.
            completeMissingBlocks();
            beginCooldown("last block placed");
            return;
        }

        if (--graceTicks <= 0) {
            abort(config.schematicFile.isBlank()
                    ? "the build never started - is a schematic actually loaded/placed in Litematica?"
                    : "the build never started - Baritone may have rejected '" + config.schematicFile
                      + "'. It only reads litematic v7 (1.21+); older files need the Litematica path.");
        }
    }

    /**
     * Baritone paused. Usually it is missing materials. With autoResumeMaterials on we keep the
     * recording rolling, tell the user exactly what is missing, and resume once the inventory gains
     * new block types.
     */
    private static void handlePause() {
        if (!pauseHandled) {
            pauseHandled = true;
            materialCheckCountdown = 20;
            inventorySnapshot = inventoryTypes();
        }

        if (!config.autoResumeMaterials) {
            if (config.stopWhenPaused) {
                beginCooldown("build paused (usually: out of materials)");
            }
            return;
        }

        reportMissingMaterialsOnce();
        maybeFeedStock();

        // If we are still stuck long after the last feed, try the next batch anyway. Without this a
        // repeat pause with an identical missing list would wait for ever, which is the difference
        // between a build that finishes overnight and one that records until the disk fills.
        int restockTicks = (int) Math.round(Math.max(0.0, config.restockAfterSeconds) * 20.0);
        if (restockTicks > 0 && ++pausedTicks >= restockTicks) {
            pausedTicks = 0;
            // Also force a resume attempt on this tick: waiting for a *new block type* is not a
            // sufficient condition (see below).
            forceResume = true;
            restockNow("still waiting after " + (int) Math.round(config.restockAfterSeconds) + "s");
        }

        if (--materialCheckCountdown > 0) {
            return;
        }
        materialCheckCountdown = 20;

        Set<String> now = inventoryTypes();
        boolean gained = now.stream().anyMatch(type -> !inventorySnapshot.contains(type));

        // Resume on a new block type, OR on a forced attempt. Watching only for new types is not
        // enough: in survival the missing material is often a type already in the inventory but in too
        // small a quantity, so feeding more of it changes nothing the type-set test can see and the
        // build would wait for ever. Resuming is cheap - if the blocks really are absent Baritone
        // simply pauses again.
        boolean resumeAnyway = forceResume;
        forceResume = false;
        if (!gained && !resumeAnyway) {
            return;
        }
        inventorySnapshot = now;
        pausedTicks = 0;
        if (BaritoneBridge.resumeBuild()) {
            reportedMissing = "";
            BaritoneEventBridge.clearMissingMaterials();
            StartBuildMod.chat(gained
                    ? "\u00A7aNew blocks in your inventory - resuming the build."
                    : "\u00A7eRetrying the build (forced resume after a stall).");
        }
    }

    private static void reportMissingMaterialsOnce() {
        String missing = BaritoneEventBridge.lastMissingMaterials();
        if (missing == null || missing.isBlank() || missing.equals(reportedMissing)) {
            return;
        }
        reportedMissing = missing;
        String[] lines = missing.split("\n");
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            if (i > 0) {
                summary.append(", ");
            }
            summary.append(lines[i].trim());
        }
        if (lines.length > 3) {
            summary.append(", and ").append(lines.length - 3).append(" more");
        }
        StartBuildMod.chat("\u00A7eBaritone needs: " + summary);
        StartBuildMod.chat("\u00A7eAdd those blocks (e.g. /function sb:<build>_2) and the build resumes by itself.");
    }

    /**
     * Automatic stock feeding. Baritone pauses for a *new* set of missing blocks, so run the next
     * configured /function for you; the inventory watch in handlePause then resumes the build on its
     * own. Together with autoStockAtStart this is what makes a long build need no input at all.
     */
    private static void maybeFeedStock() {
        String missing = BaritoneEventBridge.lastMissingMaterials();
        if (missing == null || missing.isBlank() || missing.equals(stockFedFor)) {
            return;
        }
        stockFedFor = missing;
        supplyOrCycle(missing, "materials needed");
    }

    /**
     * Restock attempt used by the stall timer, which fires every restockAfterSeconds even when the
     * missing list has not changed.
     */
    private static void restockNow(String reason) {
        String missing = BaritoneEventBridge.lastMissingMaterials();
        if (missing == null || missing.isBlank()) {
            // No text to work from. Rather than give up - or batch-feed, which clears the inventory -
            // fall back to making sure the build's most-used types are present, using the type list read
            // straight from the stock datapack functions. Covers the bulk of a build with no text at all,
            // which is far better than stalling because one signal could not be read.
            if (config.directRestock) {
                ensureTopTypes(reason);
            }
            return;
        }
        supplyOrCycle(missing, reason);
    }

    /**
     * Text-free fallback: give the most-used block types for this build, in the datapack's own order,
     * until the inventory is full. Only ever reached when Baritone's missing-materials text is
     * unavailable, because that text is a far better signal (it says exactly what is wanted now).
     */
    private static void ensureTopTypes(String reason) {
        if (stockTypes.isEmpty() || config.topTypeFallbackCount <= 0) {
            return;
        }
        Set<String> present = inventoryTypes();
        int limit = Math.min(config.topTypeFallbackCount, stockTypes.size());
        List<String> wanted = new ArrayList<>(stockTypes.subList(0, limit));

        int given = 0;
        for (String type : wanted) {
            if (present.contains(type)) {
                continue;
            }
            if (freeInventorySlots() <= 0 && !freeOneSlotOutside(wanted)) {
                break;
            }
            if (StartBuildMod.runServerCommand("give @s " + type + " "
                    + Math.max(1, config.restockGiveCount))) {
                present.add(type);
                suppliedTypes.add(type);
                given++;
            }
        }
        if (given > 0) {
            StartBuildMod.chat("\u00A7eNo missing-materials text available (" + reason + "), so topped up "
                    + given + " of the build's most-used block type(s) instead.");
        }
    }

    /**
     * Clears a block type that is not in the wanted set, to make room. BlockItems only.
     *
     * Same ordering rule as {@link #freeOneSlot}: stock this mod gave goes first, then the rarest types.
     * Iterating the inventory set directly meant the cleared type was whichever the set happened to yield
     * first, which made every restock clear a different (and often needed-soonest) type at random.
     */
    private static boolean freeOneSlotOutside(List<String> wanted) {
        List<String> ours = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String type : inventoryTypes()) {
            if (wanted.contains(type)) {
                continue;
            }
            if (suppliedTypes.contains(type)) {
                ours.add(type);
            } else {
                others.add(type);
            }
        }
        Comparator<String> rarestFirst = (a, b) -> Integer.compare(indexInStock(b), indexInStock(a));
        ours.sort(rarestFirst);
        others.sort(rarestFirst);

        for (List<String> group : List.of(ours, others)) {
            for (String type : group) {
                if (StartBuildMod.runServerCommand("clear @s " + type)) {
                    suppliedTypes.remove(type);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Directly supply the missing types, or fall back to cycling the stock batches.
     *
     * With directRestock on there is deliberately no batch fallback mid-build: batch functions clear
     * every build type before giving their own, so a batch feed destroys whatever Baritone currently
     * holds. Batch feeding is still used to bulk-stock at the start of a run, which is safe because
     * there is nothing to lose yet.
     */
    private static void supplyOrCycle(String missing, String reason) {
        if (config.directRestock) {
            supplyMissingTypesDirectly(missing);
            return;
        }
        feedNextStockBatch(reason);
    }

    /**
     * Frees inventory slots until {@code count} are available, or nothing more can be freed.
     *
     * Necessary because {@code /give} into a full inventory does not fail - it drops the items on the
     * ground. That looked like "the give never arrived" and left Baritone waiting for blocks that were
     * lying at the player's feet.
     *
     * @return how many slots are free afterwards
     */
    private static int makeRoomFor(int count, Set<String> needed) {
        int guard = 0;
        while (freeInventorySlots() < count && guard++ <= count + 4) {
            if (!freeOneSlot(needed)) {
                break;
            }
        }
        return freeInventorySlots();
    }

    /** How many items of one block type the 36 builder slots hold in total. */
    private static int countOf(String type) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString().equals(type)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Empty slots in the 36 the builder can use. */
    private static int freeInventorySlots() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return 0;
        }
        int free = 0;
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            if (stack == null || stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Block ids mentioned in Baritone's "Missing materials" text, e.g. "1x Block{minecraft:coal_block}". */
    private static Set<String> parseMissingTypes(String missingText) {
        Set<String> types = new HashSet<>();
        Matcher matcher = MISSING_TYPE.matcher(missingText);
        while (matcher.find()) {
            types.add(matcher.group());
        }
        return types;
    }

    private static final Pattern MISSING_TYPE = Pattern.compile("[a-z_]+:[a-z_]+");

    /**
     * Stocks Baritone's missing block types by overwriting inventory slots atomically.
     *
     * This was originally a "/clear a slot then /give" dance. That raced the CLIENT's inventory view: both
     * commands are async server-side, but freeInventorySlots() reads the client copy, which does not update
     * within the same tick. So the mod "freed" a slot that was still full, every /give landed on a full
     * inventory, and vanilla /give silently DROPPED the items on the ground - the server logged
     * "Gave 64 [X]" while the mod saw "0 free inventory slots" and "never arrived" for ever. That is the
     * whole full-inventory deadlock.
     *
     * /item replace has no such race: it overwrites one NAMED slot server-side, regardless of what is
     * there, so the block is guaranteed to land. Slots 9..35 (the 27 non-hotbar slots) are used, rotating,
     * leaving Baritone's 9 hotbar slots alone; Baritone's allowInventory logic pulls from there as needed.
     *
     * @return true when something was supplied or found to be unsupplyable, so batch cycling is skipped
     */
    private static boolean supplyMissingTypesDirectly(String missingText) {
        Set<String> missing = parseMissingTypes(missingText);
        if (missing.isEmpty()) {
            return false;
        }
        int given = 0;
        int unsupplyable = 0;
        Set<String> notItems = new HashSet<>();

        for (String type : missing) {
            if (given >= MAX_GIVES_PER_CYCLE) {
                break;                                      // the rest wait for the next cycle
            }
            if (!isGiveableBlockItem(type)) {
                if (reportedPresent.add("nonitem:" + type)) {
                    StartBuildMod.LOGGER.info("[StartBuild] Baritone wants {}, which has no matching item", type);
                }
                notItems.add(type);
                continue;
            }
            // Rotate over the 27 non-hotbar slots so no single slot is thrashed every cycle. The slot index
            // is deliberately independent of the client's (stale) inventory view.
            int slot = 9 + (given % 27);
            String command = "item replace entity @s container." + slot + " with " + type + " "
                    + Math.max(1, config.restockGiveCount);
            if (StartBuildMod.runServerCommand(command)) {
                suppliedTypes.add(type);
                given++;
                StartBuildMod.LOGGER.info("[StartBuild] replaced container.{} with {} x{}", slot, type,
                        Math.max(1, config.restockGiveCount));
            } else {
                unsupplyable++;
            }
        }

        if (given > 0) {
            StartBuildMod.chat("\u00A7aStocked " + given + " missing block type(s) into the inventory.");
        }
        if (!notItems.isEmpty()) {
            StartBuildMod.chat("\u00A7eBaritone is also waiting on " + notItems.size() + " block state(s) that "
                    + "have no matching item, so no give can supply them: " + String.join(", ", notItems)
                    + ". If the build stops here, add them to Baritone's buildSubstitutes or "
                    + "buildIgnoreBlocks.");
        }
        if (unsupplyable > 0) {
            StartBuildMod.chat("\u00A7e" + unsupplyable + " /item replace command(s) could not be sent - check "
                    + "the log.");
        }
        // Anything parsed counts as handled, so batch cycling (which would CLEAR the whole inventory and
        // destroy types Baritone still holds) stays only as the fallback for when nothing could be parsed.
        return true;
    }

    /**
     * Clears one block type the inventory holds that Baritone is not currently asking for, preferring the
     * LEAST-used one.
     *
     * "Least used" is the position in the build's count order, read from the stock functions, so the type
     * that has to be given back soonest is cleared last - which cuts the clear/give churn that is
     * unavoidable when 36 slots must cover 110+ block types.
     *
     * Our OWN stock goes first: those are types this mod gave, so clearing them costs nothing. Only if
     * none of those can be cleared does it fall back to the wider inventory - which is needed because
     * after the first batch feed all 36 slots can hold types that were never given by this path. Only
     * BlockItems are ever considered, so tools and armour are untouched either way.
     */
    private static boolean freeOneSlot(Set<String> needed) {
        List<String> ours = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String type : inventoryTypes()) {
            if (needed.contains(type)) {
                continue;
            }
            if (suppliedTypes.contains(type)) {
                ours.add(type);
            } else {
                others.add(type);
            }
        }
        // Descending by stock index: the rarest type first. Types not in the stock list at all (so not
        // part of this build) sort highest and are cleared first.
        Comparator<String> rarestFirst = (a, b) -> Integer.compare(indexInStock(b), indexInStock(a));
        ours.sort(rarestFirst);
        others.sort(rarestFirst);

        for (String type : ours) {
            if (StartBuildMod.runServerCommand("clear @s " + type)) {
                suppliedTypes.remove(type);
                return true;
            }
        }
        for (String type : others) {
            if (StartBuildMod.runServerCommand("clear @s " + type)) {
                suppliedTypes.remove(type);
                return true;
            }
        }
        return false;
    }

    /** Position in the build's count order, most-used first. Unknown types sort last. */
    private static int indexInStock(String type) {
        int index = stockTypes.indexOf(type);
        return (index < 0) ? Integer.MAX_VALUE : index;
    }

    /**
     * Confirms that a /give really landed.
     *
     * runServerCommand only proves the command was SENT, so without this a give that failed - a full
     * inventory, an item id that does not exist, no permission - would be reported as a success and
     * retried blindly. This closes the last step in the materials path that was assumed rather than
     * checked, and it is what makes "this block cannot be obtained with /give" an observation instead of
     * a guess.
     */
    /** So a stock batch that silently delivered nothing is reported instead of assumed to have worked. */
    private static boolean stockFailedReported;

    /**
     * Progress measured from the WORLD rather than from Baritone.
     *
     * This is the fix for the failure that survived every other fix. Baritone's BlockChangeEvent does not
     * report most of what Baritone places: one measured run had 123 events for 3424 blocks actually in the
     * world. So `lastPlacementTick` never advanced, the mod announced "has placed nothing for 60s", and at
     * 180s it CANCELLED a build that was working and restarted it from layer 0 - over and over. Proof,
     * sampled 100 seconds apart while the mod was reporting a stall:
     *
     *   layer 1: 79/249  ->  107/249      (28 blocks placed while "placed nothing for 60s")
     *
     * Sampling the client level directly cannot be wrong in that way: it is the same world the player is
     * looking at, and it depends on neither Baritone's internals nor the mod's own counters - both of which
     * have now caused a wrong diagnosis more than once.
     */
    private static long lastWorldProgressTick;
    private static int lastFilledSample = -1;
    private static int progressSampleCountdown;
    /** Highest layer counter seen this run - Baritone advances it only when a layer closes. */
    private static int lastSeenLayer = -1;

    /**
     * Mobs stand in the build and block placement - a single creature sitting on the next block stalls the
     * whole run until someone removes it. A build-recording world wants no mobs at all, so they are killed
     * once at the start and then every 20 seconds, within a radius big enough to cover the whole build
     * wherever the player happens to be.
     */
    private static int mobClearCountdown;
    private static final int MOB_CLEAR_INTERVAL_TICKS = 400;    // 20 seconds
    private static final double MOB_CLEAR_RADIUS = 160.0;       // covers an 80x80x64 build from any corner

    /**
     * Every Nth block on each axis. 2 keeps an 80x80x80 region to ~64k reads (~tens of ms every 5 s) and,
     * unlike 3, still sees a ~0.1 block/s trickle: each placement has a 1/8 chance to land on the grid, so
     * a few blocks a minute is enough to move the count. 3 was too coarse and could miss slow-but-real
     * progress, which is exactly the false-stall it exists to prevent.
     */
    private static final int PROGRESS_STRIDE = 2;

    /**
     * Counts non-air blocks in the build volume on a fixed stride.
     *
     * The absolute number does not matter - only whether it GREW - so any pre-existing terrain inside the
     * box is a constant offset and cannot produce a false "no progress".
     *
     * @return the sample count, or -1 when the volume is unknown or no world is loaded
     */
    private static int sampleBuildProgress() {
        try {
            if (trackedMin == null || trackedSizeX <= 0 || trackedSizeY <= 0 || trackedSizeZ <= 0) {
                return -1;
            }
            net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return -1;
            }
            int filled = 0;
            // Widen the stride for a big schematic so the sample stays a few thousand reads rather than
            // hundreds of thousands. The signal is "did the count grow", which survives a coarser grid.
            int stride = PROGRESS_STRIDE;
            while ((long) (trackedSizeX / stride + 1) * (trackedSizeZ / stride + 1)
                    * (trackedSizeY / stride + 1) > 64000) {
                stride++;
            }
            for (int dx = 0; dx < trackedSizeX; dx += stride) {
                for (int dz = 0; dz < trackedSizeZ; dz += stride) {
                    for (int dy = 0; dy < trackedSizeY; dy += stride) {
                        int x = trackedMin.getX() + dx;
                        int y = trackedMin.getY() + dy;
                        int z = trackedMin.getZ() + dz;
                        if (!level.hasChunk(x >> 4, z >> 4)) {
                            continue;                    // unloaded columns would read as air; skip them
                        }
                        if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                            filled++;
                        }
                    }
                }
            }
            return filled;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] progress sample failed: {}", Reflect.describe(t));
            return -1;
        }
    }

    /** Records progress when the world sample shows the build gained blocks since the last sample. */
    private static void sampleWorldProgress() {
        if (--progressSampleCountdown > 0) {
            return;
        }
        progressSampleCountdown = 100;                     // every 5 seconds
        int filled = sampleBuildProgress();
        if (filled >= 0 && filled > lastFilledSample) {
            lastFilledSample = filled;
            lastWorldProgressTick = BaritoneEventBridge.currentTick();
        }
        // The layer counter is the one progress signal that cannot lie: Baritone only increments it when
        // a whole layer has been closed (every incorrect position in the window is gone). It is coarse -
        // it moves once per layer - but authoritative, so it backstops the probabilistic world sample.
        int layer = BaritoneBridge.currentLayer();
        if (layer > lastSeenLayer) {
            lastSeenLayer = layer;
            lastWorldProgressTick = BaritoneEventBridge.currentTick();
            StartBuildMod.LOGGER.info("[StartBuild] progress: layer counter advanced to {}", layer);
        }
    }

    /**
     * Confirms that a stock /function actually put blocks in the inventory.
     *
     * The mod used to report "Auto-stocked: /function X" purely because the packet was sent. But vanilla
     * SUPPRESSES the per-command feedback inside a function, so a batch whose give commands are rejected
     * prints nothing at all - no error, no "Gave" line, nothing in the log. A datapack that silently does
     * nothing would leave Baritone waiting for ever with the mod cheerfully reporting that it had just
     * been stocked.
     *
     * The function's own tellraw IS still delivered, which is why "batch 1/4" appears even when the gives
     * do not - that is exactly the false signal this check is here to catch.
     */
    private static void verifyStockBatch() {
        stockVerifyAt = 0;
        if (stockVerifyTypes.isEmpty()) {
            return;
        }
        Set<String> now = inventoryTypes();
        boolean anyArrived = stockVerifyTypes.stream().anyMatch(now::contains);
        if (anyArrived) {
            StartBuildMod.LOGGER.info("[StartBuild] stock batch {} delivered ({} of its {} type(s) are in "
                    + "the inventory)", stockVerifyFunction, now.size(), stockVerifyTypes.size());
            return;
        }
        if (!stockFailedReported) {
            stockFailedReported = true;
            StartBuildMod.LOGGER.warn("[StartBuild] stock batch {} delivered NONE of its {} type(s)",
                    stockVerifyFunction, stockVerifyTypes.size());
            StartBuildMod.chat("\u00A7c/function " + stockVerifyFunction + " ran but gave none of its "
                    + stockVerifyTypes.size() + " block types - check the function and the pack metadata. "
                    + "The build cannot proceed without them.");
        }
    }

    private static void verifyPendingGives() {
        if (pendingGives.isEmpty()) {
            return;
        }
        // No player means no inventory to read, which would look like every pending give failing and
        // produce a misleading "giving X is not working" message. Wait until there is someone to check.
        if (Minecraft.getInstance().player == null) {
            return;
        }
        long now = BaritoneEventBridge.currentTick();
        Set<String> present = inventoryTypes();
        Iterator<Map.Entry<String, Long>> entries = pendingGives.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Long> entry = entries.next();
            String type = entry.getKey();
            if (present.contains(type)) {
                entries.remove();
                continue;
            }
            if (now - entry.getValue() <= GIVE_VERIFY_TICKS) {
                continue;
            }
            entries.remove();
            suppliedTypes.remove(type);
            int attempts = giveAttempts.merge(type, 1, Integer::sum);
            // Record WHY it did not arrive. "/give into a full inventory" and "/give was rejected" look
            // identical from here, and they need opposite fixes - so the log has to tell them apart
            // instead of leaving it to be guessed at later.
            int freeNow = freeInventorySlots();
            StartBuildMod.LOGGER.warn("[StartBuild] /give {} never arrived (failed attempt {}): {} free "
                            + "inventory slot(s) now, {} of that item held. {}",
                    type, attempts, freeNow, countOf(type),
                    freeNow <= 0
                            ? "No room - /give drops the items on the ground instead of failing, which is why "
                              + "the inventory never gained them."
                            : "There was room, so the give itself was refused (check the server log).");
            if (attempts >= MAX_GIVE_ATTEMPTS) {
                StartBuildMod.chat("\u00A7eGiving " + type + " is not working - it never reaches the "
                        + "inventory, so Baritone cannot use it. That block needs buildSubstitutes or "
                        + "buildIgnoreBlocks, or it will hold the build up.");
            }
        }
    }

    private static void feedNextStockBatch(String reason) {
        if (config == null || config.autoStockFunctions == null || config.autoStockFunctions.isEmpty()) {
            return;
        }
        List<String> functions = config.autoStockFunctions;

        if (stockFeeds >= Math.max(1, config.maxStockFeeds)) {
            reportStockGivingUp("fed " + stockFeeds + " batches, which is the maxStockFeeds limit");
            return;
        }
        if (stockIndex >= functions.size()) {
            if (!config.autoStockCycle) {
                reportStockGivingUp("the list of " + functions.size() + " batches is exhausted");
                return;
            }
            // A build with more block types than the 36 inventory slots needs its earlier batches
            // again later, so cycle rather than stopping.
            stockIndex = 0;
        }

        String function = functions.get(stockIndex);
        if (StartBuildMod.runServerCommand("function " + function)) {
            stockIndex++;
            stockFeeds++;
            stockFailures = 0;
            pausedTicks = 0;
            noProgressAnchor = BaritoneEventBridge.currentTick();
            StartBuildMod.chat("\u00A7aAuto-stocked (" + reason + "): /function " + function);
            // Sending is not succeeding: check a moment later that the inventory actually gained
            // something. See verifyStockBatch.
            stockVerifyFunction = function;
            stockVerifyTypes = StockFunctions.typesInOrder(List.of(function));
            stockVerifyAt = BaritoneEventBridge.currentTick() + 60;
        } else {
            // Do not consume the entry, so it can still be typed by hand without skipping a batch.
            // Failures ARE counted though: the entry is not consumed, so without this a /function that
            // can never run would be retried and re-reported every restock interval for ever.
            stockFailures++;
            if (stockFailures >= MAX_STOCK_FAILURES) {
                reportStockGivingUp("the last " + stockFailures + " attempts to run /function " + function
                        + " all failed");
                return;
            }
            StartBuildMod.chat("\u00A7cCould not run /function " + function + " - run it yourself to continue.");
        }
    }

    private static void reportStockGivingUp(String why) {
        if (stockExhaustedReported) {
            return;
        }
        stockExhaustedReported = true;
        StartBuildMod.chat("\u00A7eAuto-stock stopped: " + why + ". Add the missing blocks yourself "
                + "(or extend autoStockFunctions) and the build resumes by itself.");
    }

    /**
     * Whether a type Baritone named can actually be handed over with /give.
     *
     * Baritone's missing map is keyed by BlockState, so its text can name things that are not items at
     * all - fluids, fire, a redstone wire id rather than redstone dust. {@code /give} fails for those, and
     * because {@link #inventoryTypes()} can only see BlockItems they can never be confirmed as arrived
     * either, so the old code gave them three times, failed three times, and then blamed buildSubstitutes
     * with a message about water and fire. Recognising them up front avoids both the wasted attempts and
     * the wrong diagnosis.
     *
     * Fails OPEN: anything it cannot classify is treated as giveable, because refusing to supply a block
     * the build genuinely needs is far worse than one pointless /give.
     */
    private static boolean isGiveableBlockItem(String type) {
        try {
            Identifier id = Identifier.tryParse(type);
            if (id == null) {
                return false;                        // not even a well-formed id: /give would reject it
            }
            // Both registries must know it. `minecraft:water` is a block with no item; `minecraft:redstone`
            // is an item whose block is `redstone_wire`, so neither passes - correctly, since Baritone wants
            // the placeable form and the block registry is what it matches against.
            return BuiltInRegistries.ITEM.containsKey(id);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Block types currently in the player's inventory. */
    private static Set<String> inventoryTypes() {
        Set<String> types = new HashSet<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return types;
        }
        try {
            // getNonEquipmentItems() is the public accessor for the 36 main slots - the same slots
            // Baritone's builder scans. (Inventory.items itself is private in 26.2.)
            for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (stack.getItem() instanceof BlockItem blockItem) {
                    types.add(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
                }
            }
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not read inventory: {}", Reflect.describe(t));
        }
        return types;
    }

    /**
     * Keeps trying to save a recording that Flashback refused to finish. For an unattended run the
     * alternative is worse than a failed save: the replay keeps growing all night and nothing in the
     * mod will ever stop it, because the session has already gone back to IDLE.
     */
    private static void tickPendingFinish() {
        if (--pendingFinishTicks > 0) {
            return;
        }
        pendingFinishTicks = 100;
        if (!FlashbackBridge.isRecording()) {
            pendingFinish = false;
            StartBuildMod.chat("\u00A7eThe recording that could not be saved has ended on its own.");
            return;
        }
        if (FlashbackBridge.finishRecording()) {
            pendingFinish = false;
            recordingStartedByUs = false;
            StartBuildMod.chat("\u00A7aRecording stopped and saved on retry.");
            if (config.desktopNotification) {
                notifyDesktop("StartBuild: recording saved");
            }
        }
    }

    /**
     * Ends the run for good. The ordinary "builder went idle" path may be cancelled if the build
     * genuinely resumes, but a watchdog decision must stick - otherwise a builder that is active while
     * placing nothing flips COOLDOWN -> BUILDING -> COOLDOWN for ever.
     */
    private static void endRunForGood(String reason) {
        stopIsFinal = true;
        beginCooldown(reason);
    }

    /**
     * Baritone is active, unpaused, and placing nothing. Cancel the build and ask again - that is what
     * unsticks it in practice. Bounded, because a build that is stuck for a reason restarting cannot
     * fix (an unreachable placement, a bad selection) must still end in a saved replay rather than
     * recording nothing for hours.
     *
     * The restart reuses the ORIGINAL origin, never the player's current position: file mode takes its
     * origin from the player, so using "here" would silently move the build.
     */
    private static void attemptStallRecovery() {
        int allowed = Math.max(1, config.maxStallRecoveries);
        if (stallRecoveries >= allowed) {
            if (!stallGaveUp) {
                stallGaveUp = true;
                StartBuildMod.chat("\u00A7cBaritone stayed stuck through " + stallRecoveries
                        + " restarts, so this build is not going to finish. Stopping and saving rather "
                        + "than recording hours of nothing. Try layer mode, or move the player clear of "
                        + "the schematic and start again.");
            }
            endRunForGood("the build stalled repeatedly");
            return;
        }

        stallRecoveries++;
        StartBuildMod.chat("\u00A7eBaritone has placed nothing for " + (int) config.stallRecoverSeconds
                + "s. Restarting the build (attempt " + stallRecoveries + " of " + allowed + ").");

        // Cancel first, and check it. A cancel that silently failed leaves Baritone's old build running,
        // and IBuilderProcess.build() resets its layer counter to startAtLayer - so in layer mode the
        // "restart" re-runs the layer scan from the top, which looks even more stuck than before while
        // this reported a successful recovery.
        if (!BaritoneBridge.cancelBuild()) {
            StartBuildMod.chat("\u00A7cCould not stop Baritone's current build, so a restart would not help. "
                    + "Stopping and saving instead.");
            endRunForGood("Baritone could not be cancelled");
            return;
        }

        // Before asking again, get the player out of the build's way. A builder that is active, not
        // paused, asking for no materials and placing nothing is very often blocked by the player
        // occupying the space a block has to go in - and a restart alone would not fix that.
        movePlayerOutOfBuild();

        // The restart must not walk back into the same wall. Before re-dispatching, fix the actual cause:
        // setblock every cell that is wrong or missing (an unplaceable axis=x cell the pre-pass missed, a
        // legacy wall state, anything). Baritone then finds those cells correct and moves on instead of
        // oscillating on one block for ever - the "player glitches back and forth in the same block" the
        // user saw is exactly Baritone retrying one unplaceable cell across every restart.
        completeMissingBlocks();

        boolean dispatched;
        if (config.schematicFile.isBlank()) {
            dispatched = BaritoneBridge.requestLitematicaBuild(config.litematicaPlacement);
        } else {
            File file = new File(new File(Minecraft.getInstance().gameDirectory, "schematics"), config.schematicFile);
            Vec3i origin = (buildOrigin != null) ? buildOrigin : Vec3i.ZERO;
            dispatched = BaritoneBridge.buildFromFile(file, origin);
        }
        if (!dispatched) {
            StartBuildMod.chat("\u00A7cBaritone would not accept the restart request.");
            endRunForGood("the build could not be restarted");
            return;
        }

        // Do NOT re-anchor the watchdogs as if this worked. Baritone cannot tell us whether it accepted
        // the request (buildOpenLitematic is void), so the restart is only confirmed once Baritone reports
        // the build active again. Until then the stall clock keeps running from where it was, which means a
        // restart that silently does nothing ends the run instead of buying another three minutes.
        noPlacementWarned = false;
    }

    /**
     * Puts the player just outside the build's footprint if they are standing inside it.
     *
     * Baritone places blocks through the player's position only when the player is not there. A builder
     * that is active, unpaused, wanting no materials and placing nothing is very often simply blocked by
     * the player occupying the cell a block has to go into - and moving the player does not move the
     * build, because the placement's origin is already fixed.
     */
    private static void movePlayerOutOfBuild() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            // Prefer Baritone's own view of the placement. Fall back to the footprint captured at build
            // start, which is the ONLY source in file mode - there is no placement to ask about there, so
            // this method used to give up silently and every restart re-requested the build with the player
            // still standing in the corner cell it was blocking.
            int minX;
            int minY;
            int minZ;
            int sizeX;
            int sizeZ;
            int[] box = BaritoneBridge.schematicBox(config.litematicaPlacement);
            if (box != null && box.length >= 6) {
                minX = box[0];
                minY = box[1];
                minZ = box[2];
                sizeX = box[3];
                sizeZ = box[5];
            } else if (trackedMin != null && trackedSizeX > 0 && trackedSizeZ > 0) {
                minX = trackedMin.getX();
                minY = trackedMin.getY();
                minZ = trackedMin.getZ();
                sizeX = trackedSizeX;
                sizeZ = trackedSizeZ;
                StartBuildMod.LOGGER.info("[StartBuild] no Baritone footprint; using the one captured at "
                        + "build start ({}, {}, {} {}x{})", minX, minY, minZ, sizeX, sizeZ);
            } else {
                // Say so rather than returning silently: the caller is a stall recovery, and without the
                // footprint it cannot move the player - which is very likely why the build is stuck.
                StartBuildMod.LOGGER.warn("[StartBuild] cannot step clear of the build: no footprint is "
                        + "available for placement {}", config.litematicaPlacement);
                StartBuildMod.chat("\u00A7eCould not work out where the build's footprint is, so you may still "
                        + "be standing inside it - which stops Baritone placing there. Step a few blocks away "
                        + "and it should continue.");
                return;
            }

            // box from schematicBox is {originX, originY, originZ, WIDTH, HEIGHT, LENGTH}: the far edge is
            // origin + size - 1. Reading box[3]/box[5] as maximum coordinates made the containment test
            // nonsense - for an 80-wide build at x=100 the "inside" band was x in [80,100], so a player in
            // the middle of the build was judged outside and left where they were.
            int maxX = minX + sizeX - 1;
            int maxZ = minZ + sizeZ - 1;
            BlockPos player = minecraft.player.blockPosition();
            boolean inside = player.getX() >= minX && player.getX() <= maxX
                    && player.getZ() >= minZ && player.getZ() <= maxZ;
            if (!inside) {
                return;
            }
            int outX = minX - 2;
            int outZ = minZ - 2;
            StartBuildMod.chat("\u00A7eYou are standing inside the build, which blocks placement - "
                    + "stepping outside it.");
            StartBuildMod.runServerCommand("tp @s " + (outX + 0.5) + " " + minY + " " + (outZ + 0.5) + " 0 0");
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not move the player clear: {}", Reflect.describe(t));
        }
    }

    /**
     * Desktop notification, with the failure reported.
     *
     * For a run you walk away from, this is the only way you find out it finished, so a notification that
     * could not be sent used to mean finding out much later - from the chat log, or not at all.
     */
    private static void notifyDesktop(String message) {
        if (BaritoneBridge.notifyDesktop(message)) {
            return;
        }
        if (notificationFailedReported) {
            return;
        }
        notificationFailedReported = true;
        StartBuildMod.chat("\u00A7eCould not send a desktop notification (Baritone's NotificationHelper is "
                + "unavailable or refused). If you are away from the PC, check for the replay file in "
                + "Flashback's replays folder instead.");
    }

    /**
     * Pre-place every cell that no click-based builder can reach, BEFORE the recording and Baritone start.
     *
     * A block can only be placed by clicking against a solid face, so a cell with no solid neighbour in
     * any of the six directions is unreachable to Baritone by construction. Rather than skip it - or leave
     * it for a later pass - it is placed up front with /setblock (exact state, needs no neighbour). That is
     * the workaround for "never skip a single block": nothing is skipped, because the unbuildable cells are
     * already in place before Baritone ever looks at them, and every layer closes naturally.
     */
    private static void prePlaceUnbuildableBlocks() {
        if (loadedSchematic == null || loadedSchematicOrigin == null || loadedSchematicSize == null) {
            return;
        }
        int sx = loadedSchematicSize.getX();
        int sy = loadedSchematicSize.getY();
        int sz = loadedSchematicSize.getZ();
        boolean[] solid = LitematicaBridge.solidGrid(loadedSchematic, sx, sy, sz);
        if (solid == null) {
            StartBuildMod.chat("\u00A7eCould not pre-scan the schematic, so floating blocks will instead be "
                    + "filled by the finishing pass at the end.");
            return;
        }
        int placed = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = (y * sz + z) * sx + x;
                    if (!solid[i]) {
                        continue;
                    }
                    BlockState want = LitematicaBridge.schematicBlockAt(loadedSchematic, x, y, z);
                    if (want == null || want.isAir()) {
                        continue;
                    }
                    // Two classes of cell Baritone can NEVER place, so both are pre-placed with /setblock:
                    //
                    // 1. No DOWN/HORIZONTAL solid neighbour - there is no face to click against. (Its goal
                    //    placement uses HORIZONTALS + DOWN and deliberately excludes UP.)
                    // 2. A NON-DEFAULT block state. Baritone's approxPlaceable() derives each item's
                    //    placeable state from a synthetic upward-facing click, which for a pillar always
                    //    yields axis=y - so an axis=x (or any non-default) cell never matches and never gets
                    //    a goal. buildIgnoreDirection was tried and does NOT change this (0 of 1081 such
                    //    cells were ever placed, even with it set).
                    boolean supported = (x > 0 && solid[i - 1])
                            || (x < sx - 1 && solid[i + 1])
                            || (y > 0 && solid[i - sz * sx])
                            || (z > 0 && solid[i - sx])
                            || (z < sz - 1 && solid[i + sx]);
                    boolean nonDefault = !want.equals(want.getBlock().defaultBlockState());
                    if (supported && !nonDefault) {
                        continue;
                    }
                    BlockPos wp = new BlockPos(loadedSchematicOrigin.getX() + x,
                            loadedSchematicOrigin.getY() + y, loadedSchematicOrigin.getZ() + z);
                    if (StartBuildMod.runServerCommand("setblock " + wp.getX() + " " + wp.getY() + " "
                            + wp.getZ() + " " + BlockStateParser.serialize(want))) {
                        placed++;
                    }
                }
            }
        }
        if (placed > 0) {
            StartBuildMod.chat("\u00A7aPre-placed " + placed + " block(s) that Baritone could never build "
                    + "(no clickable face, or a non-default block state), so nothing will be skipped.");
        }
    }

    /**
     * Verification pass after Baritone reports done: fill anything it still left wrong or empty.
     *
     * The pre-pass already places both the unclickable cells AND the non-default-state cells (axis=x and
     * the like, which Baritone's up-facing approxPlaceable can never match), so this should normally find
     * zero. It exists as the guarantee - if any cell is still wrong (a neighbour Baritone never reached,
     * anything), it is /setblocked to the exact state so the finished build is complete and correct no
     * matter what Baritone did. Runs before the recording stops, so the finished take shows a whole build.
     */
    private static void completeMissingBlocks() {
        if (loadedSchematic == null || loadedSchematicOrigin == null || loadedSchematicSize == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        int sizeX = loadedSchematicSize.getX();
        int sizeY = loadedSchematicSize.getY();
        int sizeZ = loadedSchematicSize.getZ();
        int fixed = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockState want = LitematicaBridge.schematicBlockAt(loadedSchematic, x, y, z);
                    if (want == null || want.isAir()) {
                        continue;
                    }
                    BlockPos wp = new BlockPos(loadedSchematicOrigin.getX() + x,
                            loadedSchematicOrigin.getY() + y, loadedSchematicOrigin.getZ() + z);
                    if (!minecraft.level.hasChunk(wp.getX() >> 4, wp.getZ() >> 4)) {
                        continue;                        // not loaded: Baritone never reached it either
                    }
                    if (minecraft.level.getBlockState(wp).equals(want)) {
                        continue;
                    }
                    if (StartBuildMod.runServerCommand("setblock " + wp.getX() + " " + wp.getY() + " "
                            + wp.getZ() + " " + BlockStateParser.serialize(want))) {
                        fixed++;
                    }
                }
            }
        }
        if (fixed > 0) {
            StartBuildMod.chat("\u00A7aCompleted " + fixed + " block(s) Baritone could not place, so the build "
                    + "is whole.");
        }
        // NOTE: the schematic reference is deliberately kept (cleared only in reset()) so this pass can
        // also run mid-build from attemptStallRecovery() to unstick Baritone.
    }

    private static void beginCooldown(String reason) {        // Never re-announce: this used to be reachable every other tick, which is what flooded chat.
        if (state == State.COOLDOWN) {
            return;
        }
        cooldownSince = BaritoneEventBridge.currentTick();
        finishReason = reason;
        long now = BaritoneEventBridge.currentTick();
        long placement = BaritoneEventBridge.lastPlacementTick();
        // Anchor to the last real placement when we have one: that is literally what "30 seconds after
        // the last block is placed" means. If the build stopped long after its last block, the remaining
        // wait is correspondingly shorter.
        //
        // lastPlacementTick() is static and survives between runs, so it is clamped to this run's build
        // start. Without that, a second /startbuild in the same session whose build places no blocks
        // before stopping would anchor the countdown in the *previous* run, see a huge elapsed time, and
        // save the replay immediately instead of after stopDelaySeconds.
        long anchor = Math.max(placement, buildStartTick);
        countdownAnchor = (anchor > 0) ? anchor : now;
        state = State.COOLDOWN;
        long alreadyWaited = Math.max(0, now - countdownAnchor);
        double remaining = Math.max(0.0, config.stopDelaySeconds - alreadyWaited / 20.0);
        StartBuildMod.chat(String.format("%s. Recording continues for another %.1fs.", reason, remaining));
    }

    private static void tickCooldown() {
        // A watchdog stop is final. Resuming here is only correct for the ordinary "the builder went
        // idle, then a placement arrived" case - see stopIsFinal.
        if (!stopIsFinal && BaritoneBridge.isBuildActive() && !BaritoneBridge.isBuildPaused()) {
            long now = BaritoneEventBridge.currentTick();
            state = State.BUILDING;
            pauseHandled = false;
            // Only announce a real resume. A builder that flaps active/inactive would otherwise emit
            // this every other tick - the same flood pattern that made the game look frozen.
            if (now - cooldownSince >= 20) {
                StartBuildMod.chat("Build resumed; stop countdown cancelled.");
            }
            return;
        }

        // A placement during the countdown pushes the stop time out.
        long placement = BaritoneEventBridge.lastPlacementTick();
        if (placement > countdownAnchor) {
            countdownAnchor = placement;
        }

        long now = BaritoneEventBridge.currentTick();
        if (now - countdownAnchor < config.stopDelayTicks()) {
            return;
        }
        double sinceLastBlock = (now - countdownAnchor) / 20.0;
        finish(String.format("build stopped (%s), %.1fs after the last block placed", finishReason, sinceLastBlock));
    }

    private static void finish(String reason) {
        if (episodeMode) {
            config.episodesDone = Math.max(config.episodesDone, episodeNumber);
            config.save();
            int layers = Math.max(1, config.episodeLayers);
            StartBuildMod.chat(String.format("Episode %d recorded (layers %d-%d). Next: /startbuild next.",
                    episodeNumber, config.episodesDone * layers - layers + 1, config.episodesDone * layers));
        }

        if (config.addCompletionMarker && recordingStartedByUs && FlashbackBridge.isRecording()) {
            StartBuildMod.runClientCommand("flashback mark");
        }

        if (config.finishRecording && recordingStartedByUs) {
            if (FlashbackBridge.isRecording()) {
                if (FlashbackBridge.finishRecording()) {
                    StartBuildMod.chat("\u00A7aRecording stopped and saved: " + reason);
                    if (config.desktopNotification) {
                        notifyDesktop("StartBuild: recording saved - " + reason);
                    }
                } else {
                    pendingFinish = true;
                    pendingFinishTicks = 100;
                    StartBuildMod.chat("\u00A7cFlashback refused to finish the recording. StartBuild will keep "
                            + "retrying every 5s, so the replay is not left recording for ever - you can also "
                            + "run /flashback finish yourself.");
                }
            } else {
                StartBuildMod.chat("\u00A7eThe recording had already been stopped, so there was nothing to save.");
            }
        } else if (!recordingStartedByUs && FlashbackBridge.isRecording()) {
            StartBuildMod.chat("Flashback was already recording before this build, so it is still running. Use /flashback finish when you are ready.");
        }

        reset();
    }

    private static void abort(String reason) {
        StartBuildMod.chat("\u00A7cAborted: " + reason);

        if (recordingStartedByUs && FlashbackBridge.isRecording()) {
            if (config.cancelRecordingIfBuildNeverStarts) {
                FlashbackBridge.cancelRecording();
                StartBuildMod.chat("Discarded that recording so you do not end up with an empty replay.");
            } else {
                FlashbackBridge.finishRecording();
                StartBuildMod.chat("Saved the recording anyway (cancelRecordingIfBuildNeverStarts=false).");
            }
        }

        reset();
    }

    static int stopNow() {
        if (state == State.IDLE) {
            // A recording we started that failed to save is still ours to stop, even though the session
            // itself has finished.
            if (pendingFinish && FlashbackBridge.isRecording()) {
                if (FlashbackBridge.finishRecording()) {
                    pendingFinish = false;
                    StartBuildMod.chat("\u00A7aRecording stopped and saved.");
                    if (config.desktopNotification) {
                        notifyDesktop("StartBuild: recording saved (stopped manually)");
                    }
                    return 1;
                }
                StartBuildMod.chat("\u00A7cFlashback still refuses to finish the recording. Try /flashback finish.");
                return 0;
            }
            StartBuildMod.chat(FlashbackBridge.isRecording()
                    ? "No StartBuild session is running. Flashback is recording, but not from StartBuild - use /flashback finish."
                    : "Nothing to stop.");
            return 0;
        }

        boolean cancelled = BaritoneBridge.cancelBuild();
        StartBuildMod.chat(cancelled
                ? "Build cancelled."
                : "\u00A7eCould not cancel the build automatically; run Baritone's #cancel.");

        if (config.addCompletionMarker && recordingStartedByUs && FlashbackBridge.isRecording()) {
            StartBuildMod.runClientCommand("flashback mark");
        }
        if (recordingStartedByUs && FlashbackBridge.isRecording() && FlashbackBridge.finishRecording()) {
            StartBuildMod.chat("\u00A7aRecording stopped and saved.");
            if (config.desktopNotification) {
                notifyDesktop("StartBuild: recording saved (stopped manually)");
            }
        }

        reset();
        return 1;
    }

    static int status() {
        StartBuildConfig shown = StartBuildConfig.load();
        StartBuildMod.chat("state=" + state
                + " baritone=" + BaritoneBridge.available()
                + " flashback=" + FlashbackBridge.available()
                + (FlashbackBridge.available() ? " recording=" + FlashbackBridge.isRecording() : ""));
        StartBuildMod.chat("timing=" + (BaritoneEventBridge.isRegistered()
                        ? "block-change events (last placed tick " + BaritoneEventBridge.lastPlacementTick()
                          + ", count " + BaritoneEventBridge.placementCount() + ")"
                        : "idle-detection fallback (event hook unavailable)")
                + " logger=" + BaritoneEventBridge.isLoggerHooked());

        if (state == State.BUILDING || state == State.COOLDOWN) {
            int layer = BaritoneBridge.currentLayer();
            int max = BaritoneBridge.maxLayer();
            StartBuildMod.chat("buildActive=" + BaritoneBridge.isBuildActive()
                    + " paused=" + BaritoneBridge.isBuildPaused()
                    + (layer >= 0 ? " layer=" + layer + "/" + max : " (not in layer mode)"));
        }

        String missing = BaritoneEventBridge.lastMissingMaterials();
        if (missing != null && !missing.isBlank()) {
            StartBuildMod.chat("Baritone last reported missing: " + missing.replace("\n", " | "));
        } else {
            // lastMissingMaterials is cleared as soon as a build resumes, so fall back to the last list
            // seen this session. Without this, /buildstatus says "no missing materials yet" moments after
            // a successful restock, which is confusing exactly when someone is debugging.
            String ever = BaritoneEventBridge.lastMissingReportedEver();
            if (ever != null && !ever.isBlank()) {
                StartBuildMod.chat("No materials outstanding (last request was: " + ever.replace("\n", " | ") + ").");
            } else {
                StartBuildMod.chat("Baritone has reported no missing materials yet"
                        + (BaritoneBridge.isBuildPaused() ? " (but it is paused - check the log)" : "") + ".");
            }
        }

        // Where the build actually is, INCLUDING its base height, and how that compares to the ground.
        //
        // This line is here because its absence cost a lot of time: a run froze with the base at y=-60
        // under an 80-block-tall schematic, and nothing in /buildstatus showed a Y coordinate at all - the
        // only clue was the one-off "Auto-selected ... at (1453, -60, 74)" line printed at start. A buried
        // base is the single most common cause of "active, unpaused, placing nothing", so it is worth one
        // line every time.
        int[] box = BaritoneBridge.schematicBox(shown.litematicaPlacement);
        if (box != null && box.length >= 6) {
            int minX = box[0], minY = box[1], minZ = box[2];
            int maxX = box[0] + box[3] - 1, maxZ = box[2] + box[5] - 1;
            StartBuildMod.chat("footprint: " + box[3] + "x" + box[4] + "x" + box[5]
                    + " at (" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", "
                    + (minY + box[4] - 1) + ", " + maxZ + ")");
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.level.hasChunk(minX >> 4, minZ >> 4)) {
                int ground = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, minX, minZ) - 1;
                String buried = buriedProblem(new BlockPos(minX, minY, minZ), box[3], box[5]);
                StartBuildMod.chat("base y=" + minY + " vs ground y=" + ground
                        + (buried == null ? " - looks placeable."
                          : " \u00A7cBURIED by " + (ground - minY) + " - this build cannot progress. "
                            + "Use /startbuild site <name>."));
            }
        } else {
            StartBuildMod.chat("footprint: unreadable (no Litematica placement " + shown.litematicaPlacement
                    + "?) - if the build is stuck, this is why nothing can move the player clear of it.");
        }

        // The materials subsystem in one line, so nothing here needs log archaeology next time.
        StartBuildMod.chat("materials: directRestock=" + shown.directRestock
                + " stockTypes=" + stockTypes.size()
                + " given=" + suppliedTypes.size()
                + " awaitingConfirm=" + pendingGives.size()
                + " failedAttempts=" + giveAttempts.values().stream().mapToInt(Integer::intValue).sum());

        StartBuildMod.chat("config: " + shown.summary());
        StartBuildMod.chat("config file: " + FabricLoader.getInstance().getConfigDir().resolve("startbuild.json"));
        return 1;
    }

    private static void reset() {
        // Put back anything this run changed in Baritone's own settings.
        if (restoreBuildInLayers) {
            restoreBuildInLayers = false;
            if (BaritoneBridge.setBuildInLayers(false)) {
                StartBuildMod.chat("Restored Baritone's buildInLayers to false.");
            }
        }
        if (restoreBuildOnlySelection) {
            restoreBuildOnlySelection = false;
            if (BaritoneBridge.setSetting("buildOnlySelection", Boolean.TRUE)) {
                StartBuildMod.chat("Restored Baritone's buildOnlySelection to true.");
            }
        }
        if (restoreBuildIgnoreDirection) {
            restoreBuildIgnoreDirection = false;
            if (BaritoneBridge.setSetting("buildIgnoreDirection", Boolean.FALSE)) {
                StartBuildMod.chat("Restored Baritone's buildIgnoreDirection to false.");
            }
        }
        state = State.IDLE;
        recordingStartedByUs = false;
        fileOverride = null;
        stopIsFinal = false;
        sawBuildActive = false;
        preRollTicks = 0;
        graceTicks = 0;
        finishReason = "";
        episodeMode = false;
        episodeNumber = 0;
        episodeStopLayer = 0;
        pauseHandled = false;
        materialCheckCountdown = 0;
        inventorySnapshot = Set.of();
        reportedMissing = "";
        // Movement tracking is per run; a stale anchor here would make the next run look active when
        // nothing has moved since the last one.
        lastMovementTick = 0;
        lastPlayerPos = null;
    }
}
