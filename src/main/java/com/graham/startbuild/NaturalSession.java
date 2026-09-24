package com.graham.startbuild;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import com.mojang.datafixers.util.Pair;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private static String siteWishText = "";
    private static SchematicModel siteModel;
    /** Every site already shown for siteName - never offered again, so each search really moves. */
    private static final List<BlockPos> shownSites = new ArrayList<>();
    private static int explorations;
    // A search that first has to travel: find the biome (server thread), fly there, wait for chunks.
    private static CompletableFuture<Pair<BlockPos, Holder<Biome>>> biomeLookup;
    private static BlockPos exploreTarget;
    private static int exploreTicks;

    /**
     * "/findsite <name> [wish]" - shows the most natural spot for the build as a ghost and flies the player
     * to a viewpoint; /startbuild confirm builds it.
     *   - the first search for a schematic looks at the loaded world around the player;
     *   - running it again for the same schematic (or /findsite next past the last choice) flies 600-900
     *     blocks away in a new direction and searches there - a different area every time;
     *   - a biome in the wish ("snowy", "desert", "cherry grove", ...) flies to the nearest such biome that has
     *     not been used yet, and only offers sites inside it.
     * Sites already shown are never offered again.
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
        boolean repeat = name.equals(siteName);
        if (!repeat) {
            shownSites.clear();
            explorations = 0;
        }
        siteName = name;
        siteModel = m;
        siteWishText = wishText == null ? "" : wishText;
        siteChoices = List.of();
        if (!PlacementFinder.biomeWords(siteWishText).isEmpty()) return exploreBiome(mc);
        if (repeat) return exploreElsewhere(mc);
        return searchAround(mc, mc.player.blockPosition());
    }

    static int nextSite() {
        if (siteChoices.isEmpty()) {
            StartBuildMod.chat("\u00A7eRun /findsite <name> <wish> first.");
            return 0;
        }
        if (siteIndex + 1 >= siteChoices.size()) {
            // Seen every choice here: go somewhere new rather than cycling back to the first one.
            Minecraft mc = Minecraft.getInstance();
            if (!PlacementFinder.biomeWords(siteWishText).isEmpty()) return exploreBiome(mc);
            return exploreElsewhere(mc);
        }
        siteIndex++;
        return showSite();
    }

    /** Next exploration point: 600-900 blocks away, each time in a new direction (golden-angle spiral). */
    private static BlockPos nextExplorePoint(BlockPos from) {
        explorations++;
        double angle = Math.toRadians(explorations * 137.508);
        int dist = 600 + 150 * (explorations % 3);
        return new BlockPos(from.getX() + (int) (Math.cos(angle) * dist), 64, from.getZ() + (int) (Math.sin(angle) * dist));
    }

    private static int exploreElsewhere(Minecraft mc) {
        flyTo(mc, nextExplorePoint(mc.player.blockPosition()));
        StartBuildMod.chat("Looking somewhere new, ~" + (int) Math.sqrt(mc.player.blockPosition().distSqr(exploreTarget))
                + " blocks away - loading the area...");
        return 1;
    }

    private static int exploreBiome(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            StartBuildMod.chat("\u00A7cBiome search needs a singleplayer world.");
            return 0;
        }
        List<String> words = PlacementFinder.biomeWords(siteWishText);
        // Start from the player, or - once this biome has been used - from a point far away, so the nearest
        // match is a different patch of it.
        BlockPos from = shownSites.isEmpty() ? mc.player.blockPosition() : nextExplorePoint(mc.player.blockPosition());
        ServerLevel level = server.getLevel(mc.level.dimension());
        biomeLookup = server.submit(() -> level.findClosestBiome3d(h -> PlacementFinder.biomeMatches(h, words),
                from, 3200, 32, 64));
        exploreTarget = null;
        StartBuildMod.chat("Looking for " + String.join("/", words) + "...");
        return 1;
    }

    private static void flyTo(Minecraft mc, BlockPos target) {
        exploreTarget = target;
        exploreTicks = 0;
        if (mc.player.getAbilities().mayfly) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
        StartBuildMod.runServerCommand("tp @s " + target.getX() + " 200 " + target.getZ());
    }

    /** Called every idle tick: finishes a biome lookup, then waits for the new area's chunks and searches. */
    private static void tickExplore(Minecraft mc) {
        if (biomeLookup != null) {
            if (!biomeLookup.isDone()) return;
            Pair<BlockPos, Holder<Biome>> hit = null;
            try {
                hit = biomeLookup.join();
            } catch (RuntimeException e) {
                StartBuildMod.LOGGER.warn("[StartBuild] biome search failed", e);
            }
            biomeLookup = null;
            if (hit == null) {
                StartBuildMod.chat("\u00A7eNo " + String.join("/", PlacementFinder.biomeWords(siteWishText))
                        + " biome within 3200 blocks.");
                return;
            }
            StartBuildMod.LOGGER.info("[StartBuild] findsite biome {} at {}",
                    hit.getSecond().unwrapKey().map(k -> k.identifier().toString()).orElse("?"), hit.getFirst());
            flyTo(mc, hit.getFirst());
            return;
        }
        if (exploreTarget == null) return;
        exploreTicks++;
        if (exploreTicks % 10 != 0) return;
        // Wait until the chunks the search needs have arrived (they are generated on the way), max 40 s.
        int reach = searchRadius(mc, siteModel) + Math.max(siteModel.sizeX, siteModel.sizeZ);
        int missing = 0, total = 0;
        for (int cz = (exploreTarget.getZ() - reach) >> 4; cz <= (exploreTarget.getZ() + reach) >> 4; cz++) {
            for (int cx = (exploreTarget.getX() - reach) >> 4; cx <= (exploreTarget.getX() + reach) >> 4; cx++) {
                total++;
                if (!PlacementFinder.loaded(mc.level, cx, cz)) missing++;
            }
        }
        // At least 2 s after the teleport (the old area's chunks are still there right after it), then until
        // 95% of the new area has arrived.
        if ((exploreTicks < 40 || missing > total / 20) && exploreTicks < 800) return;
        if (missing > total / 2) {
            exploreTarget = null;
            StartBuildMod.chat("§eThe area did not load in time. Run /findsite again.");
            return;
        }
        BlockPos centre = exploreTarget;
        exploreTarget = null;
        StartBuildMod.LOGGER.info("[StartBuild] findsite area {} loaded ({} of {} chunks) after {} ticks",
                centre, total - missing, total, exploreTicks);
        searchAround(mc, centre);
    }

    private static int searchRadius(Minecraft mc, SchematicModel m) {
        return Math.max(48, mc.options.getEffectiveRenderDistance() * 16 - Math.max(m.sizeX, m.sizeZ) - 16);
    }

    private static int searchAround(Minecraft mc, BlockPos centre) {
        SchematicModel m = siteModel;
        PlacementFinder.Wish wish = PlacementFinder.parseWish(siteWishText);
        List<String> biomes = PlacementFinder.biomeWords(siteWishText);
        int radius = searchRadius(mc, m);
        long t0 = System.currentTimeMillis();
        List<PlacementFinder.Result> found = new ArrayList<>();
        int apart = Math.max(m.sizeX, m.sizeZ) + 24;
        for (PlacementFinder.Result r : PlacementFinder.find(mc.level, centre, radius, m, wish, 20)) {
            BlockPos o = r.origin();
            if (shownSites.stream().anyMatch(s -> Math.abs(s.getX() - o.getX()) < apart && Math.abs(s.getZ() - o.getZ()) < apart)) {
                continue;
            }
            if (!biomes.isEmpty() && !PlacementFinder.biomeMatches(
                    mc.level.getBiome(o.offset(m.sizeX / 2, 0, m.sizeZ / 2)), biomes)) {
                continue;
            }
            found.add(r);
            if (found.size() >= 5) break;
        }
        StartBuildMod.LOGGER.info("[StartBuild] findsite {} wish={} biome={} centre={} radius={} -> {} site(s) in {} ms",
                siteName, wish, biomes, centre, radius, found.size(), System.currentTimeMillis() - t0);
        if (found.isEmpty()) {
            StartBuildMod.chat("\u00A7eNo good new spot here"
                    + (wish == PlacementFinder.Wish.WATER ? " next to water" : "") + ". Run /findsite again to look further away.");
            return 0;
        }
        siteChoices = found;
        siteIndex = 0;
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
        shownSites.add(r.origin());
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
        // Build exactly where the preview was put. (Reading Litematica's live placement back proved unreliable:
        // it returned the viewpoint the player was teleported to, 18 blocks up, so the build floated.)
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
            case IDLE -> {
                tickExplore(mc);
                checkAutorun(mc);
            }
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
            int bx1 = origin.getX(), bz1 = origin.getZ(), bx2 = bx1 + model.sizeX - 1, bz2 = bz1 + model.sizeZ - 1;
            long n = 0;
            n += fillSliced(bx1 - m, origin.getY(), bz1 - m, bx2 + m, top, bz2 + m, "air replace #minecraft:leaves");
            n += fillSliced(bx1 - m, origin.getY(), bz1 - m, bx2 + m, top, bz2 + m, "air replace #minecraft:logs");
            n += fillSliced(bx1, origin.getY(), bz1, bx2, origin.getY() + model.sizeY, bz2, "air");
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

        if (builder.isFinished() && builder.placed == 0) {
            abort("nothing could be placed at " + origin.toShortString()
                    + " - the build has no ground to start from there. Try another spot.");
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
