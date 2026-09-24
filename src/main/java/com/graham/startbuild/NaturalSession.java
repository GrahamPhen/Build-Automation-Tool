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
        if (config.videoDaylight) {
            StartBuildMod.runServerCommand("time set noon");
            StartBuildMod.runServerCommand("weather clear");
        }

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
