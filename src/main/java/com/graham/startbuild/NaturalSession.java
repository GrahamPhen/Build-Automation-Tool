package com.graham.startbuild;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One unattended take: pick/prepare the site, start Flashback, let {@link NaturalBuilder} build the whole
 * schematic by hand, then stop the recording a fixed delay after the last block.
 *
 *   /startbuild place <name>   build here (the player's position is the schematic's corner)
 *   /startbuild auto  [name]   build on a fresh patch of ground next to the last automatic build
 *   config/startbuild-autorun  (written by the desktop launcher) - same as "auto" as soon as a world loads
 *
 * Everything the builder says goes to the log, not to chat, so nothing appears on camera.
 */
final class NaturalSession {

    enum State { IDLE, PREPARING, PRE_ROLL, BUILDING, COOLDOWN }

    private static State state = State.IDLE;
    private static StartBuildConfig config;
    private static NaturalBuilder builder;
    private static SchematicModel model;
    private static BlockPos origin;
    private static String schematicName = "";
    private static boolean recordingByUs;
    private static int waitTicks;
    private static long buildStartMillis;
    private static int autorunCheck;
    private static int ticksInWorld;
    private static long lastPlaced;
    private static int idleTicks;
    private static int rechecks;

    private NaturalSession() {
    }

    static boolean isActive() {
        return state != State.IDLE;
    }

    // ================================================================== starting

    static int startHere(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            StartBuildMod.chat("§cJoin a world first.");
            return 0;
        }
        return start(name, mc.player.blockPosition(), false);
    }

    static int startAuto(String name) {
        return start((name == null || name.isBlank()) ? StartBuildConfig.load().autoRunSchematic : name, null, true);
    }

    private static int start(String rawName, BlockPos corner, boolean freshSite) {
        if (state != State.IDLE) {
            StartBuildMod.chat("§eA build is already running. /stopbuild first.");
            return 0;
        }
        if (StartBuildSession.isBusy()) {
            StartBuildMod.chat("§eThe old Baritone build session is running. /stopbuild first.");
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            StartBuildMod.chat("§cJoin a world first.");
            return 0;
        }
        if (!LitematicaBridge.available()) {
            StartBuildMod.chat("§cLitematica is needed to read the schematic.");
            return 0;
        }
        config = StartBuildConfig.load();
        String name = rawName.trim();
        if (!name.toLowerCase().matches(".*\\.(litematic|schem|schematic)$")) {
            name = name + ".litematic";
        }
        File file = new File(new File(mc.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("§cNo such schematic: " + file.getAbsolutePath());
            return 0;
        }
        Object schematic = LitematicaBridge.loadSchematic(file.toPath().getParent(), name);
        model = SchematicModel.read(schematic);
        if (model == null || model.solidCount == 0) {
            StartBuildMod.chat("§cCould not read " + name + ".");
            return 0;
        }
        schematicName = name;

        // Anything else that could fight us for the player or draw on camera goes first.
        if (BaritoneBridge.available() && BaritoneBridge.isBuildActive()) {
            BaritoneBridge.cancelBuild();
        }
        BaritoneBridge.applyVideoSettings();
        LitematicaBridge.clearPlacements();

        if (mc.gameMode.getPlayerMode() != GameType.CREATIVE) {
            StartBuildMod.runServerCommand("gamemode creative");
        }
        applyWorldSettings();

        if (freshSite) {
            int x, z;
            if (config.lastAutoOriginX != null && config.lastAutoOriginZ != null) {
                x = config.lastAutoOriginX + model.sizeX + Math.max(16, config.autoSiteSpacing);
                z = config.lastAutoOriginZ;
            } else {
                x = player.blockPosition().getX() + 16;
                z = player.blockPosition().getZ() + 16;
            }
            config.lastAutoOriginX = x;
            config.lastAutoOriginZ = z;
            config.save();
            // Y is found once the chunks there have loaded (PREPARING).
            origin = new BlockPos(x, Integer.MIN_VALUE, z);
            StartBuildMod.runServerCommand("tp @s " + (x - 4) + " " + (player.blockPosition().getY() + 20)
                    + " " + (z - 4) + " -45 30");
        } else {
            origin = corner;
            // Step off the corner cell, facing into the build.
            StartBuildMod.runServerCommand("tp @s " + (corner.getX() - 3 + 0.5) + " " + (corner.getY() + 2)
                    + " " + (corner.getZ() - 3 + 0.5) + " -45 30");
        }

        builder = null;
        rechecks = 0;
        waitTicks = 0;
        state = State.PREPARING;
        StartBuildMod.LOGGER.info("[StartBuild] natural build of {} ({}x{}x{}, {} blocks) requested",
                name, model.sizeX, model.sizeY, model.sizeZ, model.solidCount);
        StartBuildMod.chat("Preparing " + name + " (" + model.solidCount + " blocks)...");
        return 1;
    }

    /**
     * Makes whatever world this runs in camera-ready, every time: no chat echo from our own commands, no
     * mobs, fixed noon and clear sky, nothing growing/spreading/burning on its own, and peaceful.
     */
    private static void applyWorldSettings() {
        // First, so none of the following commands prints anything on camera.
        StartBuildMod.runServerCommand("gamerule send_command_feedback false");
        String[] off = {"command_block_output", "advance_time", "advance_weather", "spawn_monsters",
                "spawn_phantoms", "spawn_patrols", "spawn_wandering_traders", "mob_griefing", "spread_vines"};
        for (String rule : off) {
            StartBuildMod.runServerCommand("gamerule " + rule + " false");
        }
        StartBuildMod.runServerCommand("gamerule fire_spread_radius_around_player 0");
        StartBuildMod.runServerCommand("gamerule random_tick_speed 0");
        StartBuildMod.runServerCommand("difficulty peaceful");
        if (config.videoDaylight) {
            StartBuildMod.runServerCommand("time set noon");
            StartBuildMod.runServerCommand("weather clear");
        }
    }

    // ================================================================== preview

    private static String previewName;
    private static BlockPos previewOrigin;

    /**
     * Shows the schematic as Litematica's see-through ghost exactly where /startbuild place would build it
     * (corner at your feet, extending east and south), and reports what is in the way. Nothing is placed.
     */
    static int preview(String rawName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            StartBuildMod.chat("\u00A7cJoin a world first.");
            return 0;
        }
        if (state != State.IDLE) {
            StartBuildMod.chat("\u00A7eA build is running - the preview would show on camera. /stopbuild first.");
            return 0;
        }
        String name = rawName.trim();
        if (!name.toLowerCase().matches(".*\\.(litematic|schem|schematic)$")) {
            name = name + ".litematic";
        }
        File file = new File(new File(mc.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("\u00A7cNo such schematic: " + name);
            return 0;
        }
        Object schematic = LitematicaBridge.loadSchematic(file.toPath().getParent(), name);
        SchematicModel m = SchematicModel.read(schematic);
        if (m == null) {
            StartBuildMod.chat("\u00A7cCould not read " + name + ".");
            return 0;
        }
        BlockPos corner = mc.player.blockPosition();
        int[] placed = LitematicaBridge.place(schematic, corner, "preview " + name, true);
        if (placed[0] <= 0) {
            StartBuildMod.chat("\u00A7cLitematica could not show the preview.");
            return 0;
        }
        previewName = name;
        previewOrigin = corner;

        // What is in the way: existing blocks inside the footprint that are not what the build wants there
        // (trees, hills). The builder does not clear them, so they would end up in the video.
        int inTheWay = 0, below = 0;
        for (int y = 0; y < m.sizeY; y++) {
            for (int z = 0; z < m.sizeZ; z++) {
                for (int x = 0; x < m.sizeX; x++) {
                    BlockPos p = corner.offset(x, y, z);
                    net.minecraft.world.level.block.state.BlockState have = mc.level.getBlockState(p);
                    if (have.isAir() || have.canBeReplaced()) continue;
                    net.minecraft.world.level.block.state.BlockState want = m.at(x, y, z);
                    if (!NaturalBuilder.matches(have, want)) inTheWay++;
                }
            }
        }
        for (int z = 0; z < m.sizeZ; z++) {
            for (int x = 0; x < m.sizeX; x++) {
                if (mc.level.getBlockState(corner.offset(x, -1, z)).isAir()) below++;
            }
        }
        StartBuildMod.chat("Preview: " + name + " " + m.sizeX + "x" + m.sizeY + "x" + m.sizeZ + " at "
                + corner.toShortString() + " (east/south of you).");
        StartBuildMod.chat((inTheWay == 0 ? "\u00A7aNothing in the way." : "\u00A7e" + inTheWay
                + " existing block(s) inside the footprint would stay in the shot (trees/terrain).")
                + (below > 0 ? " \u00A7e" + below + " cell(s) of the base hang over air." : ""));
        StartBuildMod.chat("Move it: Litematica's nudge (hold the stick, Alt + scroll) or M > Placements > Configure. "
                + "Then /startbuild confirm to build it where the ghost is, or /previewbuild off.");
        return 1;
    }

    private static List<PlacementFinder.Result> siteChoices = List.of();
    private static int siteIndex;
    private static String siteName;

    /**
     * "/findsite <name> near a river" - searches the loaded world around the player for the most natural
     * spot, shows the build there as a ghost, and flies the player to a viewpoint. /findsite next shows
     * the runner-up; /startbuild confirm builds it.
     */
    static int findSite(String rawName, String wishText) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            StartBuildMod.chat("\u00A7cJoin a world first.");
            return 0;
        }
        if (state != State.IDLE) {
            StartBuildMod.chat("\u00A7eA build is running. /stopbuild first.");
            return 0;
        }
        String name = rawName.trim();
        if (!name.toLowerCase().matches(".*\\.(litematic|schem|schematic)$")) name = name + ".litematic";
        File file = new File(new File(mc.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("\u00A7cNo such schematic: " + name);
            return 0;
        }
        SchematicModel m = SchematicModel.read(LitematicaBridge.loadSchematic(file.toPath().getParent(), name));
        if (m == null) {
            StartBuildMod.chat("\u00A7cCould not read " + name + ".");
            return 0;
        }
        PlacementFinder.Wish wish = PlacementFinder.parseWish(wishText);
        int radius = Math.max(48, mc.options.getEffectiveRenderDistance() * 16 - Math.max(m.sizeX, m.sizeZ) - 16);
        long t0 = System.currentTimeMillis();
        List<PlacementFinder.Result> found = PlacementFinder.find(mc.level, mc.player.blockPosition(), radius, m, wish, 5);
        StartBuildMod.LOGGER.info("[StartBuild] findsite {} wish={} radius={} -> {} site(s) in {} ms",
                name, wish, radius, found.size(), System.currentTimeMillis() - t0);
        if (found.isEmpty()) {
            StartBuildMod.chat("\u00A7eNo good spot within " + radius + " blocks"
                    + (wish == PlacementFinder.Wish.WATER ? " next to water" : "") + ". Fly somewhere else and try again.");
            return 0;
        }
        siteChoices = found;
        siteIndex = 0;
        siteName = name;
        return showSite();
    }

    static int nextSite() {
        if (siteChoices.isEmpty()) {
            StartBuildMod.chat("\u00A7eRun /findsite <name> <wish> first.");
            return 0;
        }
        siteIndex = (siteIndex + 1) % siteChoices.size();
        return showSite();
    }

    private static int showSite() {
        Minecraft mc = Minecraft.getInstance();
        PlacementFinder.Result r = siteChoices.get(siteIndex);
        File file = new File(new File(mc.gameDirectory, "schematics"), siteName);
        Object schematic = LitematicaBridge.loadSchematic(file.toPath().getParent(), siteName);
        if (LitematicaBridge.place(schematic, r.origin(), "preview " + siteName, true)[0] <= 0) {
            StartBuildMod.chat("\u00A7cLitematica could not show the site.");
            return 0;
        }
        previewName = siteName;
        previewOrigin = r.origin();
        // A viewpoint: outside the near corner, above, looking in.
        BlockPos o = r.origin();
        StartBuildMod.runServerCommand("tp @s " + (o.getX() - 12) + " " + (o.getY() + 18) + " " + (o.getZ() - 12) + " -45 35");
        StartBuildMod.chat(String.format("Site %d/%d at %s: %s%s%s", siteIndex + 1, siteChoices.size(),
                o.toShortString(),
                r.overhang() == 0 && r.buried() == 0 ? "sits flush on the ground" : "ground unevenness " + (r.overhang() + r.buried()),
                r.waterDist() < 40 ? ", water " + r.waterDist() + " blocks away" : "",
                r.obstacles() > 0 ? ", ~" + r.obstacles() + " trees/rocks inside" : ""));
        StartBuildMod.chat("/startbuild confirm to build here, /findsite next for another, or nudge it with Litematica first.");
        return 1;
    }

    static int clearPreview() {
        int n = LitematicaBridge.clearPlacements();
        previewName = null;
        previewOrigin = null;
        StartBuildMod.chat(n > 0 ? "Preview hidden." : "No preview to hide.");
        return 1;
    }

    /** Builds the last preview exactly where it was shown, wherever the player is now. */
    static int confirmPreview() {
        if (previewName == null || previewOrigin == null) {
            StartBuildMod.chat("\u00A7eNo preview yet. /previewbuild <name> first.");
            return 0;
        }
        String name = previewName;
        BlockPos at = previewOrigin;
        // Build wherever the ghost is NOW - it may have been moved with Litematica's own controls.
        Object[] sel = LitematicaBridge.selectedPlacement();
        if (sel != null && sel[0] instanceof BlockPos moved) {
            if (!"NONE".equals(sel[1]) || !"NONE".equals(sel[2])) {
                StartBuildMod.chat("\u00A7eThe preview is rotated or mirrored - that is not supported yet. "
                        + "Reset rotation/mirror in Litematica (M > Placements > Configure) and confirm again.");
                return 0;
            }
            at = moved;
        }
        previewName = null;
        previewOrigin = null;
        return start(name, at, false);     // start() clears the ghost so it never appears on camera
    }

    /** /fill in horizontal slabs that each stay under vanilla's 32,768-block limit. @return commands sent. */
    private static long fillSliced(int x1, int y1, int z1, int x2, int y2, int z2, String what) {
        int layer = (x2 - x1 + 1) * (z2 - z1 + 1);
        int per = Math.max(1, 32768 / Math.max(1, layer));
        long cmds = 0;
        if (per >= 1 && layer <= 32768) {
            for (int y = y1; y <= y2; y += per) {
                StartBuildMod.runServerCommand("fill " + x1 + " " + y + " " + z1 + " " + x2 + " "
                        + Math.min(y2, y + per - 1) + " " + z2 + " " + what);
                cmds++;
            }
            return cmds;
        }
        int mid = (x1 + x2) / 2;          // footprint wider than one layer allows: split it
        return fillSliced(x1, y1, z1, mid, y2, z2, what) + fillSliced(mid + 1, y1, z1, x2, y2, z2, what);
    }

    // ================================================================== ticking

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            ticksInWorld = 0;
            if (state != State.IDLE) {
                // Left the world mid-build: nothing to save any more.
                state = State.IDLE;
            }
            return;
        }
        ticksInWorld++;
        switch (state) {
            case IDLE -> checkAutorun(mc);
            case PREPARING -> tickPreparing(mc);
            case PRE_ROLL -> {
                if (--waitTicks <= 0) {
                    builder = new NaturalBuilder(model, origin, config.ticksPerBlock);
                    buildStartMillis = System.currentTimeMillis();
                    lastPlaced = 0;
                    idleTicks = 0;
                    state = State.BUILDING;
                    StartBuildMod.LOGGER.info("[StartBuild] building {} at {}", schematicName, origin);
                }
            }
            case BUILDING -> tickBuilding(mc);
            case COOLDOWN -> {
                if (--waitTicks <= 0) {
                    finish(mc);
                }
            }
        }
    }

    private static void tickPreparing(Minecraft mc) {
        waitTicks++;
        ClientLevel level = mc.level;
        if (origin.getY() == Integer.MIN_VALUE) {
            // Fresh site: wait for its chunks, then sit the schematic on the ground surface.
            if (!level.hasChunk(origin.getX() >> 4, origin.getZ() >> 4)) {
                if (waitTicks > 400) {
                    abort("the build site never loaded");
                }
                return;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
            origin = new BlockPos(origin.getX(), ground, origin.getZ());
            StartBuildMod.runServerCommand("tp @s " + (origin.getX() - 3 + 0.5) + " " + (origin.getY() + 2)
                    + " " + (origin.getZ() - 3 + 0.5) + " -45 30");
            waitTicks = 0;
            return;
        }
        // Wait for every chunk under the footprint, and a moment for the teleport to settle.
        int x0 = origin.getX() >> 4, z0 = origin.getZ() >> 4;
        int x1 = (origin.getX() + model.sizeX - 1) >> 4, z1 = (origin.getZ() + model.sizeZ - 1) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    if (waitTicks > 600) {
                        abort("the build area did not load (render distance too small?)");
                    }
                    return;
                }
            }
        }
        if (waitTicks < 40) {
            return;
        }
        // Site prep BEFORE the camera rolls: cut away terrain inside the build's volume (a hillside, trees)
        // and fill dips just around it, so the character only ever builds - never digs - on camera.
        if (config.prepTerrain) {
            TerrainPrep.Result prep = TerrainPrep.level(level, origin, model.sizeX, model.sizeZ, origin.getY(),
                    "minecraft:grass_block", "minecraft:dirt", 2);
            StartBuildMod.LOGGER.info("[StartBuild] site prep: {}", prep.describe());
            // Whole trees near the build go (not just the leaves inside it), then the build volume is emptied.
            int m = 6, top = origin.getY() + model.sizeY + 12;
            int x1 = origin.getX(), z1 = origin.getZ(), x2 = x1 + model.sizeX - 1, z2 = z1 + model.sizeZ - 1;
            long n = 0;
            n += fillSliced(x1 - m, origin.getY(), z1 - m, x2 + m, top, z2 + m, "air replace #minecraft:leaves");
            n += fillSliced(x1 - m, origin.getY(), z1 - m, x2 + m, top, z2 + m, "air replace #minecraft:logs");
            n += fillSliced(x1, origin.getY(), z1, x2, origin.getY() + model.sizeY, z2, "air");
            StartBuildMod.LOGGER.info("[StartBuild] site cleared: {} fill command(s)", n);
            waitTicks = 0;
            config.prepTerrain = false;          // once per run (config is reloaded at the next start)
            return;                              // let the world settle a moment before recording
        }
        recordingByUs = false;
        if (config.startRecording && FlashbackBridge.available()) {
            if (FlashbackBridge.isRecording()) {
                StartBuildMod.LOGGER.info("[StartBuild] Flashback already recording; leaving it alone");
            } else {
                if (config.forceQuicksave) {
                    FlashbackBridge.setQuicksave(true);
                }
                recordingByUs = FlashbackBridge.startRecording();
                if (!recordingByUs) {
                    abort("Flashback would not start recording");
                    return;
                }
            }
        }
        waitTicks = Math.max(20, config.preRollTicks());
        state = State.PRE_ROLL;
        StartBuildMod.LOGGER.info("[StartBuild] recording={} building {} at {}", recordingByUs, schematicName, origin.toShortString());
    }

    private static void tickBuilding(Minecraft mc) {
        builder.tick();

        // Mobs (superflat slimes especially) block placement and wander into shot; clear them quietly.
        if (builder.ticks % 400 == 1) {
            StartBuildMod.runServerCommand("execute if entity @e[type=!minecraft:player,type=!minecraft:item,"
                    + "distance=..128] run kill @e[type=!minecraft:player,type=!minecraft:item,distance=..128]");
        }

        // Progress report to the log once a minute (never to chat - it would be on camera).
        if (builder.ticks % 1200 == 0) {
            StartBuildMod.LOGGER.info("[StartBuild] progress: {} placed, {} left, layer {}, {} min{}",
                    builder.placed, builder.remaining(), builder.layer(),
                    (System.currentTimeMillis() - buildStartMillis) / 60000,
                    builder.lastProblem().isEmpty() ? "" : ", last problem: " + builder.lastProblem());
        }

        // Stop safely if the recording was ended behind our back.
        if (recordingByUs && builder.ticks % 100 == 0 && !FlashbackBridge.isRecording()) {
            StartBuildMod.LOGGER.warn("[StartBuild] Flashback stopped recording; stopping the build");
            recordingByUs = false;
            finish(mc);
            return;
        }

        if (builder.isFinished()) {
            // Final check against the world: anything wrong is reopened and built again (twice at most).
            int reopened = (rechecks < 2) ? builder.recheckAll(mc.level) : 0;
            rechecks++;
            if (reopened > 0) {
                StartBuildMod.LOGGER.info("[StartBuild] final check reopened {} cell(s)", reopened);
                return;
            }
            waitTicks = config.stopDelayTicks();
            state = State.COOLDOWN;
            StartBuildMod.LOGGER.info("[StartBuild] last block placed; recording continues {}s",
                    config.stopDelaySeconds);
        }
    }

    private static void finish(Minecraft mc) {
        int wrong = (builder != null && mc.level != null) ? builder.countWrong(mc.level) : -1;
        long minutes = (System.currentTimeMillis() - buildStartMillis) / 60000;
        if (recordingByUs && FlashbackBridge.isRecording()) {
            if (config.addCompletionMarker) {
                StartBuildMod.runClientCommand("flashback mark");
            }
            if (config.finishRecording) {
                FlashbackBridge.finishRecording();
            }
        }
        state = State.IDLE;
        String summary = String.format("Done: %s - %d placed by the character, %d not matching, %d min.",
                schematicName, builder == null ? 0 : builder.placed, wrong, minutes);
        StartBuildMod.chat((wrong == 0 ? "§a" : "§e") + summary
                + (recordingByUs ? " Recording saved." : ""));
        if (config.desktopNotification) {
            BaritoneBridge.notifyDesktop("StartBuild: " + summary);
        }
        recordingByUs = false;
    }

    private static void abort(String why) {
        StartBuildMod.chat("§cStopped: " + why);
        if (recordingByUs && FlashbackBridge.isRecording()) {
            FlashbackBridge.finishRecording();
        }
        recordingByUs = false;
        state = State.IDLE;
    }

    static int stop() {
        if (state == State.IDLE) {
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        StartBuildMod.chat("Stopping.");
        finish(mc);
        return 1;
    }

    static void status() {
        if (state == State.IDLE) {
            StartBuildMod.chat("natural builder: idle");
            return;
        }
        if (builder == null) {
            StartBuildMod.chat("natural builder: " + state + " (" + schematicName + ")");
            return;
        }
        StartBuildMod.chat(String.format("natural builder: %s %s - %d placed, %d left, layer %d/%d%s",
                state, schematicName, builder.placed, builder.remaining(), builder.layer(), model.sizeY,
                builder.lastProblem().isEmpty() ? "" : " | last problem: " + builder.lastProblem()));
    }

    // ================================================================== autorun (desktop icon)

    /**
     * The desktop launcher writes config/startbuild-autorun (containing the schematic name). As soon as a
     * world is loaded and has settled, the build starts by itself - no typing.
     */
    private static void checkAutorun(Minecraft mc) {
        if (++autorunCheck < 20) {
            return;
        }
        autorunCheck = 0;
        try {
            Path flag = FabricLoader.getInstance().getConfigDir().resolve("startbuild-autorun");
            if (!Files.exists(flag)) {
                return;
            }
            StartBuildConfig cfg = StartBuildConfig.load();
            if (ticksInWorld < (int) (cfg.autoRunDelaySeconds * 20)) {
                return;
            }
            String name = Files.readString(flag).trim();
            Files.deleteIfExists(flag);
            StartBuildMod.LOGGER.info("[StartBuild] autorun flag found: '{}'", name);
            startAuto(name.isEmpty() ? cfg.autoRunSchematic : name);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] autorun check failed: {}", Reflect.describe(t));
        }
    }
}
