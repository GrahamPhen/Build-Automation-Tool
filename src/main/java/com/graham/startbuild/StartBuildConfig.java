package com.graham.startbuild;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Config for StartBuild, stored at .minecraft/config/startbuild.json.
 * Reloaded on every /startbuild, so edits apply without restarting the game.
 */
final class StartBuildConfig {

    /** How long the recording keeps running after the last block is placed. This is the "30 seconds". */
    public double stopDelaySeconds = 30.0;

    /** How long to record before the build actually begins, so the first block is not frame one. */
    public double preRollSeconds = 1.0;

    /** 1-based index of the Litematica placement to build (matches Baritone's "#litematica <#>"). */
    public int litematicaPlacement = 1;

    /** If set, build ".minecraft/schematics/<this file>" instead of the Litematica placement. */
    public String schematicFile = "";

    /** Start a Flashback recording when the build starts. */
    public boolean startRecording = true;

    /** Stop and save the recording when the build stops (plus stopDelaySeconds). */
    public boolean finishRecording = true;

    /** Treat a paused builder (usually missing materials) as "stopped". Ignored while autoResumeMaterials is on. */
    public boolean stopWhenPaused = true;

    /** If the build never actually begins, cancel the recording instead of saving junk. */
    public boolean cancelRecordingIfBuildNeverStarts = true;

    /** How long the build may take to actually begin before it is judged not to have started. */
    public double startupGraceSeconds = 3.0;

    /** Drop a marker in the Flashback timeline at the moment the build stops. */
    public boolean addCompletionMarker = true;

    /** Force Flashback's "quicksave" on so the replay saves with no naming dialog. */
    public boolean forceQuicksave = false;

    /** Refuse to start when the game directory has less than this much free space. */
    public double minFreeDiskGB = 5.0;

    /** Send a desktop notification when the recording is saved - useful for long unattended builds. */
    public boolean desktopNotification = true;

    /**
     * When Baritone pauses for materials, keep the recording rolling and resume automatically once
     * new block types appear in your inventory. This is what makes a long build survivable: run
     * /function sb:&lt;build&gt;_2 and it carries on by itself.
     */
    public boolean autoResumeMaterials = true;

    /** Layers per /startbuild next episode. 1 = one layer per recording. */
    public int episodeLayers = 1;

    /** How many episodes of this schematic have already been recorded (advanced by /startbuild next). */
    public int episodesDone = 0;

    /**
     * Automatic Baritone selection, so you never have to stand at two corners and type #sel pos1/pos2.
     * Only applies when buildOnlySelection is on (i.e. you asked for a bounded build) and there is no
     * selection yet - a manual #sel always wins unless autoSelectionReplace is true.
     *
     *   "none"   - never select automatically
     *   "layers" - the bottom selectionLayers layers of the whole build footprint  (default)
     *   "corner" - a selectionCornerSize cube at the build's minimum corner
     *   "full"   - the entire build footprint
     */
    public String autoSelection = "layers";

    /** Layers to select in "layers" mode. 1 = just the base layer. */
    public int selectionLayers = 1;

    /** Cube size in "corner" mode, in blocks on each axis. */
    public int selectionCornerSize = 16;

    /** Replace an existing selection rather than leaving a manual one alone. */
    public boolean autoSelectionReplace = false;

    /**
     * Automatic stock feeding. Each entry is a datapack function that /gives one batch of the build's
     * blocks, in the order they should be used - exactly the commands you would otherwise type by hand:
     *
     *   "autoStockFunctions": ["sb:haunted_80_1", "sb:haunted_80_2", "sb:haunted_80_3"]
     *
     * Whenever Baritone pauses for materials the next unused entry is run for you, and the existing
     * auto-resume then picks the build back up by itself. Empty (the default) disables the feature.
     */
    public List<String> autoStockFunctions = new ArrayList<>();

    /** Run the first autoStockFunctions entry at start, so even batch 1 never needs typing. */
    public boolean autoStockAtStart = true;

    /** Apply the video-ready Baritone settings (the same set as /buildprep) automatically on /startbuild. */
    public boolean autoVideoSettings = true;

    /**
     * Wrap around autoStockFunctions when the list runs out instead of stopping. A build using more
     * block types than fit in 36 slots needs its earlier batches again later, so stopping at the end
     * of the list would strand an unattended build.
     */
    public boolean autoStockCycle = true;

    /** Hard cap on how many batches get fed, so a pathological build cannot loop for ever. */
    public int maxStockFeeds = 100;

    /**
     * While Baritone is paused and making no progress, feed another batch after this many seconds.
     * 0 = only feed when the set of missing blocks changes. Without this a repeat pause with an
     * identical missing list would wait for ever.
     */
    public double restockAfterSeconds = 30.0;

    /**
     * Stop and save if the build runs longer than this many minutes. 0 = no limit. The disk watchdog
     * is what really protects an unattended run; this is the "it is never going to finish" net.
     */
    public double maxBuildMinutes = 0.0;

    /** Re-check free disk space during the build and stop before a long recording fills the drive. */
    public boolean diskWatchdog = true;

    /**
     * Stop and save if no block has been placed for this many minutes. This is the better "we are stuck"
     * signal for an unattended run than a blanket time limit, because it catches every cause: materials
     * that never arrive, a blocked path, an unreachable block. 0 = off.
     */
    public double noProgressMinutes = 30.0;

    /**
     * "/startbuild place" removes every existing Litematica placement before creating the new one, so
     * old builds do not pile up ghost overlays over each other and the new placement is always index 1.
     * Set false to keep them.
     */
    public boolean clearPlacementsOnPlace = true;

    /**
     * Warn when Baritone is active but has placed nothing for this long. This is the signature of a
     * real stall: active, not paused, no blocks going down. Without it a run can look fine for half an
     * hour - one did exactly that for 39 minutes. The warning repeats for each new stall, not once
     * ever. 0 = off.
     */
    public double warnIfNoPlacementSeconds = 60.0;

    /**
     * Cancel and re-issue the build when it has been stalled this long. Baritone often recovers when
     * the build request is restarted; this is the difference between a dead run and one that finishes
     * unattended. 0 = off.
     */
    public double stallRecoverSeconds = 180.0;

    /** How many automatic restarts before giving up and saving what there is. */
    public int maxStallRecoveries = 3;

    /**
     * Supply the exact block types Baritone reports missing, straight from /give, instead of only
     * cycling through the stock batches.
     *
     * This is needed because batch feeding alone cannot work: the inventory holds 36 types and a build
     * can use 110+, so every batch swap loses types the previous batch supplied. One run got wedged
     * needing a single coal_block that batch 1 had given and a later batch had cleared, and the blind
     * cycle took minutes to come back round to batch 1.
     */
    public boolean directRestock = true;

    /** How many of each missing block to give when restocking directly. */
    public int restockGiveCount = 64;

    /**
     * Text-free fallback: how many of the build's most-used block types to keep in the inventory when
     * Baritone's missing-materials text cannot be read at all. Read from the stock datapack functions,
     * most-used first. 0 = off.
     */
    public int topTypeFallbackCount = 30;

    /**
     * Watch for a flag file in the config folder and, when it appears, stop the build and SAVE the
     * recording - the same as typing /stopbuild. This is the only way anything outside the game can end
     * a run without a keyboard: a scheduled task, or an agent watching the log. Killing the process
     * instead would discard the in-flight recording, which is the outcome every watchdog here exists to
     * prevent.
     */
    public boolean stopFileEnabled = true;

    /** Name of that flag file, in .minecraft/config/. */
    public String stopFileName = "startbuild-stop";

    /**
     * "/startbuild site": let the tool choose the spot instead of using exactly where the player stands.
     * The site is scored on flatness, how much would have to be cut or filled, water, and distance.
     */
    public boolean autoSite = true;

    /** How far from the player to look for a site, in blocks. */
    public int siteRadius = 128;

    /** Level the ground at the chosen site before building, so the build sits naturally in the world. */
    public boolean terraform = true;

    /** How far the levelling extends beyond the build's footprint, so there is no step at the edge. */
    public int terrainMargin = 4;

    /**
     * Build layer by layer. Baritone is far more reliable this way on a large schematic: without it,
     * it picks the nearest remaining block across the whole volume (80x80x80 here) and can thrash
     * instead of making progress - the run that placed 160 blocks in ten minutes and then stalled.
     * It also makes progress visible, because only layer mode lets /buildstatus report "layer N of M".
     * Restored afterwards if it was off before.
     */
    public boolean buildInLayers = true;

    // ------------------------------------------------------------------ natural builder (2.0)

    /** Extra ticks between placements (on top of aim + check). 1 = about 4-5 blocks a second. */
    public int ticksPerBlock = 1;

    /** Schematic used when the build is started automatically (desktop icon / autorun file). */
    public String autoRunSchematic = "haunted_80";

    /** Gap between successive automatic build sites, so every run starts on empty ground. */
    public int autoSiteSpacing = 40;

    /** Where the last automatic build was put (null before the first one). */
    public Integer lastAutoOriginX = null;
    public Integer lastAutoOriginZ = null;

    /**
     * Terraform the site ON CAMERA, by hand, before building: the character fells trees in the way, digs
     * the hillside down and builds up dips with real clicks, blending the pad into the land around it
     * (see Terraformer). Nothing is /fill'd.
     */
    public boolean prepTerrain = true;

    /** How far (blocks) around the footprint the ground is reshaped to blend the pad into the landscape. */
    public int terraformRadius = 12;

    /** Before recording: daylight and clear weather, so the replay is watchable. */
    public boolean videoDaylight = true;

    /** Seconds to wait after joining the world before an automatic start. */
    public double autoRunDelaySeconds = 8.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("startbuild.json");
    }

    static StartBuildConfig load() {
        StartBuildConfig config = new StartBuildConfig();
        Path file = path();
        String existing = null;
        if (Files.exists(file)) {
            try {
                existing = Files.readString(file);
                StartBuildConfig read = GSON.fromJson(existing, StartBuildConfig.class);
                if (read != null) {
                    config = read;
                }
            } catch (Throwable t) {
                StartBuildMod.LOGGER.warn("[StartBuild] Could not read {}: {}", file, Reflect.describe(t));
            }
        }
        // Write back only when there is actually something new to record - a missing file, or a field this
        // build of the mod added. Writing unconditionally on every read rewrote the file for things as
        // harmless as /buildstatus, and any later explicit save() writes this in-memory object, so a
        // hand-edit could be silently reverted. The file is meant to be a control surface.
        String serialized = GSON.toJson(config);
        if (existing == null || !existing.trim().equals(serialized.trim())) {
            config.writeSerialized(serialized);
        }
        return config;
    }

    void save() {
        writeSerialized(GSON.toJson(this));
    }

    private void writeSerialized(String json) {
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Could not write config: {}", Reflect.describe(t));
        }
    }

    int stopDelayTicks() {
        return Math.max(1, (int) Math.round(this.stopDelaySeconds * 20.0));
    }

    int preRollTicks() {
        return Math.max(0, (int) Math.round(this.preRollSeconds * 20.0));
    }

    int startupGraceTicks() {
        return Math.max(20, (int) Math.round(this.startupGraceSeconds * 20.0));
    }

    String summary() {
        return String.format(
                "stopDelay=%.1fs preRoll=%.1fs placement=%d file=%s startRecording=%s finishRecording=%s "
                        + "stopWhenPaused=%s autoResumeMaterials=%s episodeLayers=%d episodesDone=%d autoSelection=%s "
                        + "autoStock=%d autoStockAtStart=%s autoVideoSettings=%s cycle=%s restock=%.0fs "
                        + "maxBuild=%s diskWatchdog=%s noProgress=%.0fmin warnNoPlacement=%.0fs",
                this.stopDelaySeconds, this.preRollSeconds, this.litematicaPlacement,
                this.schematicFile.isBlank() ? "(litematica)" : this.schematicFile,
                this.startRecording, this.finishRecording, this.stopWhenPaused,
                this.autoResumeMaterials, this.episodeLayers, this.episodesDone, this.autoSelection,
                (this.autoStockFunctions == null ? 0 : this.autoStockFunctions.size()),
                this.autoStockAtStart, this.autoVideoSettings, this.autoStockCycle,
                this.restockAfterSeconds,
                (this.maxBuildMinutes > 0 ? String.format("%.0fmin", this.maxBuildMinutes) : "off"),
                this.diskWatchdog, this.noProgressMinutes, this.warnIfNoPlacementSeconds);
    }
}
