package com.graham.startbuild;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config for StartBuild, stored at .minecraft/config/startbuild.json.
 * Reloaded on every start, so edits apply without restarting the game. Keys this version no longer
 * knows (from the 1.x Baritone flow) are ignored and dropped the next time the file is rewritten.
 */
final class StartBuildConfig {

    // ------------------------------------------------------------------ recording

    /** How long the recording keeps running after the last block is placed. */
    public double stopDelaySeconds = 30.0;

    /** How long to record before the character starts, so the first block is not frame one. */
    public double preRollSeconds = 1.0;

    /** Start a Flashback recording when the take starts. */
    public boolean startRecording = true;

    /** Stop and save the recording when the take is done (plus stopDelaySeconds). */
    public boolean finishRecording = true;

    /** Drop a marker in the Flashback timeline at the moment the build is done. */
    public boolean addCompletionMarker = true;

    /**
     * Force Flashback's "quicksave" on so the replay saves with no naming screen. Must be on for an
     * unattended take: without it the save waits for someone to click.
     */
    public boolean forceQuicksave = true;

    /** Refuse to start, and stop and save a running take, when the game drive has less free space. */
    public double minFreeDiskGB = 5.0;

    /** Stop and save a take that runs longer than this many minutes. 0 = no limit. */
    public double maxBuildMinutes = 600.0;

    /** Send a desktop notification when the take is saved - useful for long unattended builds. */
    public boolean desktopNotification = true;

    /**
     * A file with this name in .minecraft/config/ stops the running take and SAVES it - the same as
     * /stopbuild - so a script (tools/startbuild-stop.ps1) can end a run without touching the game.
     */
    public String stopFileName = "startbuild-stop";

    // ------------------------------------------------------------------ the character

    /** Extra ticks between actions (on top of aim + check). 0/1 = about 4-5 blocks a second. */
    public int ticksPerBlock = 1;

    /**
     * Terraform the site ON CAMERA, by hand, before building: the character fells trees in the way, digs
     * the hillside down and builds up dips with real clicks, blending the pad into the land around it
     * (see Terraformer). Nothing is /fill'd.
     */
    public boolean prepTerrain = true;

    /** How far (blocks) around the footprint the ground is reshaped, at least; steep sites use more. */
    public int terraformRadius = 12;

    /**
     * Run the world this many times faster during a take (/tick rate, max 4). DOES NOT HELP (measured
     * 2026-09-25): the client - where the character acts - never ticks faster than 20/s, so only the
     * server world speeds up and the take takes as long. Keep 1.
     */
    public double gameSpeed = 1.0;

    /** Before recording: daylight and clear weather, so the replay is watchable. */
    public boolean videoDaylight = true;

    // ------------------------------------------------------------------ hands-free (desktop icon)

    /** Schematic used when a hands-free take names none. */
    public String autoRunSchematic = "haunted_80";

    /** Wish for the hands-free site search ("near a lake", "snowy", "on a hill"...); empty = flat ground. */
    public String autoRunWish = "";

    /** Seconds to wait after joining the world before a hands-free start. */
    public double autoRunDelaySeconds = 8.0;

    /** How many areas the hands-free site search tries before giving up. */
    public int autoSiteAttempts = 8;

    /**
     * A new site keeps at least this many blocks (horizontally) from every earlier build listed in
     * config/startbuild-sites.txt, so no previous build shows in the background of a take.
     */
    public int minSiteSpacing = 450;

    /**
     * Hands-free takes skip a site (before recording) whose terraforming - blocks cleared + placed - would be
     * more than this many times the build, or than terraformAllowance if that is larger. Measured: good
     * takes were 0.5-1.5x; a forest hill was 3x (9 h of digging), a 912-block cottage on a slope 10x.
     */
    public double maxTerraformRatio = 1.5;
    public int terraformAllowance = 4000;

    /** ...or that would have to fill more than this many blocks into standing water (a lake beside it). */
    public int maxWetFill = 100;

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
        // build of the mod added. Writing unconditionally on every read would revert hand-edits.
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

    String summary() {
        return String.format("stopDelay=%.0fs preRoll=%.1fs record=%s/%s quicksave=%s ticksPerBlock=%d "
                        + "terraform=%s radius=%d daylight=%s minFreeDisk=%.0fGB maxBuild=%.0fmin auto=%s '%s'",
                stopDelaySeconds, preRollSeconds, startRecording, finishRecording, forceQuicksave, ticksPerBlock,
                prepTerrain, terraformRadius, videoDaylight, minFreeDiskGB, maxBuildMinutes,
                autoRunSchematic, autoRunWish);
    }
}
