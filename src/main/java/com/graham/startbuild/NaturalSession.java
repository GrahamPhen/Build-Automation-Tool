package com.graham.startbuild;

import com.mojang.datafixers.util.Pair;
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
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One unattended take: prepare the world, start Flashback, let the character terraform the site and then
 * build the whole schematic by hand ({@link NaturalBuilder}), and stop the recording a fixed delay after
 * the last block.
 *
 *   /findsite <name> [wish] (+ /findsite next) then /startbuild confirm   - you pick the spot
 *   /startbuild place <name>                                             - corner at your feet
 *   /startbuild auto [name] [wish], or config/startbuild-autorun         - fully hands-free: find a site,
 *                                                                          confirm it, terraform, build, save
 *
 * Rules this class enforces: nothing but the character on camera (no chat, no ghost, no /fill); the take
 * always runs to an end (watchdog, error guard, pause-safe); an empty or failed take is discarded, never
 * saved. Everything the builder says goes to the log, not to chat.
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
    private static int rechecks;
    private static int tickErrors;
    private static Boolean savedPauseOnLostFocus;

    /** Terraforming stages (one per tree, then the ground: clear, fill), then the build itself. */
    private static List<Terraformer.Part> parts = List.of();
    private static int partIndex;
    private static long terrainBroken;
    private static long terrainPlaced;

    private NaturalSession() {
    }

    static boolean isActive() {
        return state != State.IDLE;
    }

    private static boolean building() {
        return partIndex >= parts.size();
    }

    private static String stageLabel() {
        return building() ? "building" : parts.get(partIndex).label();
    }

    // ================================================================== starting

    static int startHere(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            StartBuildMod.chat("§cJoin a world first.");
            return 0;
        }
        SchematicModel m = readModel(mc, name);
        BlockPos corner = mc.player.blockPosition();
        return start(name, m == null ? corner : onGround(mc.level, corner, m));
    }

    private static SchematicModel readModel(Minecraft mc, String rawName) {
        String name = schematicFileName(rawName);
        File file = new File(new File(mc.gameDirectory, "schematics"), name);
        return file.isFile() ? SchematicModel.read(LitematicaBridge.loadSchematic(file.toPath().getParent(), name)) : null;
    }

    /**
     * The corner at the height the build must stand at: one above the MEDIAN natural ground under the
     * columns it actually stands on - so a build started while flying (or standing on a tree or a rock)
     * sits on the land instead of floating or sinking. The terraforming then evens out the rest.
     */
    static BlockPos onGround(ClientLevel level, BlockPos corner, SchematicModel m) {
        List<Integer> hs = new ArrayList<>();
        for (int z = 0; z < m.sizeZ; z += 2) {
            for (int x = 0; x < m.sizeX; x += 2) {
                boolean stands = !m.at(x, 0, z).isAir() || (m.sizeY > 1 && !m.at(x, 1, z).isAir());
                if (!stands || !PlacementFinder.loaded(level, (corner.getX() + x) >> 4, (corner.getZ() + z) >> 4)) continue;
                hs.add(Terraformer.groundAt(level, corner.getX() + x, corner.getZ() + z));
            }
        }
        if (hs.isEmpty()) return corner;
        java.util.Collections.sort(hs);
        return new BlockPos(corner.getX(), hs.get(hs.size() / 2) + 1, corner.getZ());
    }

    /**
     * Fully hands-free: "[name] [wish words]" (both optional; the config supplies defaults). Searches for a
     * natural site (exploring further away if nothing good is near), confirms the best one by itself, and
     * the take then runs to the end.
     */
    static int startAuto(String nameAndWish) {
        StartBuildConfig cfg = StartBuildConfig.load();
        String text = nameAndWish == null ? "" : nameAndWish.trim();
        String name = text.isEmpty() ? cfg.autoRunSchematic : text.split("\\s+", 2)[0];
        String wish = text.contains(" ") ? text.split("\\s+", 2)[1] : cfg.autoRunWish;
        StartBuildMod.LOGGER.info("[StartBuild] hands-free take: {} wish='{}'", name, wish);
        return findSite(name, wish, true);
    }

    private static int start(String rawName, BlockPos corner) {
        if (state != State.IDLE) {
            StartBuildMod.chat("§eA build is already running. /stopbuild first.");
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
        double freeGb = new File(mc.gameDirectory.getAbsolutePath()).getUsableSpace() / 1e9;
        if (freeGb < config.minFreeDiskGB) {
            StartBuildMod.chat(String.format("§cOnly %.1f GB free on the game drive (minimum %.0f GB). Not starting.",
                    freeGb, config.minFreeDiskGB));
            return 0;
        }
        String name = schematicFileName(rawName);
        File file = new File(new File(mc.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("§cNo such schematic: " + file.getAbsolutePath());
            return 0;
        }
        model = SchematicModel.read(LitematicaBridge.loadSchematic(file.toPath().getParent(), name));
        if (model == null || model.solidCount == 0) {
            StartBuildMod.chat("§cCould not read " + name + ".");
            return 0;
        }
        schematicName = name;
        resetSiteSearch();

        // No ghost may be recorded: if it cannot be removed, do not start at all.
        if (LitematicaBridge.clearPlacements() < 0) {
            StartBuildMod.chat("§cCould not remove the Litematica preview (it would be recorded). Not starting.");
            return 0;
        }
        if (mc.gameMode.getPlayerMode() != GameType.CREATIVE) {
            StartBuildMod.runServerCommand("gamemode creative");
        }
        applyWorldSettings();
        // A focus change must not pause the game mid-take (the watchdog would see it as a stall).
        savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        deleteStopFile();

        origin = corner;
        // Step off the corner cell, facing into the build.
        StartBuildMod.runServerCommand("tp @s " + (corner.getX() - 3 + 0.5) + " " + (corner.getY() + 2)
                + " " + (corner.getZ() - 3 + 0.5) + " -45 30");

        builder = null;
        rechecks = 0;
        waitTicks = 0;
        parts = List.of();
        partIndex = 0;
        terrainBroken = 0;
        terrainPlaced = 0;
        stalls = 0;
        stallMark = -1;
        tickErrors = 0;
        state = State.PREPARING;
        StartBuildMod.LOGGER.info("[StartBuild] natural build of {} ({}x{}x{}, {} blocks) at {} requested",
                name, model.sizeX, model.sizeY, model.sizeZ, model.solidCount, corner.toShortString());
        StartBuildMod.chat("Preparing " + name + " (" + model.solidCount + " blocks)...");
        return 1;
    }

    private static String schematicFileName(String rawName) {
        String name = rawName.trim();
        return name.toLowerCase().matches(".*\\.(litematic|schem|schematic)$") ? name : name + ".litematic";
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
        // Terraforming breaks blocks on camera: plants knocked off their ground must not litter items.
        StartBuildMod.runServerCommand("gamerule block_drops false");
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
            StartBuildMod.chat("§cJoin a world first.");
            return 0;
        }
        if (state != State.IDLE) {
            StartBuildMod.chat("§eA build is running - the preview would show on camera. /stopbuild first.");
            return 0;
        }
        String name = schematicFileName(rawName);
        File file = new File(new File(mc.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("§cNo such schematic: " + name);
            return 0;
        }
        Object schematic = LitematicaBridge.loadSchematic(file.toPath().getParent(), name);
        SchematicModel m = SchematicModel.read(schematic);
        if (m == null) {
            StartBuildMod.chat("§cCould not read " + name + ".");
            return 0;
        }
        BlockPos corner = onGround(mc.level, mc.player.blockPosition(), m);
        int[] placed = LitematicaBridge.place(schematic, corner, "preview " + name, true);
        if (placed[0] <= 0) {
            StartBuildMod.chat("§cLitematica could not show the preview.");
            return 0;
        }
        previewName = name;
        previewOrigin = corner;

        int inTheWay = 0, below = 0;
        for (int y = 0; y < m.sizeY; y++) {
            for (int z = 0; z < m.sizeZ; z++) {
                for (int x = 0; x < m.sizeX; x++) {
                    BlockState have = mc.level.getBlockState(corner.offset(x, y, z));
                    if (have.isAir() || have.canBeReplaced()) continue;
                    if (!NaturalBuilder.matches(have, m.at(x, y, z))) inTheWay++;
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
        StartBuildMod.chat((inTheWay == 0 ? "§aNothing in the way." : "§e" + inTheWay
                + " existing block(s) inside the footprint - the character will clear them by hand.")
                + (below > 0 ? " §e" + below + " cell(s) of the base hang over air - the character will fill them." : ""));
        StartBuildMod.chat("/startbuild confirm to build it here, or /previewbuild off.");
        return 1;
    }

    // ================================================================== site search

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
    /** Hands-free: confirm the site shown by itself, and search further away when nothing is found. */
    private static boolean autoMode;
    private static int autoAttempts;
    private static int autoConfirmTicks;

    private static void resetSiteSearch() {
        if (biomeLookup != null) biomeLookup.cancel(false);
        biomeLookup = null;
        exploreTarget = null;
        autoConfirmTicks = 0;
        autoMode = false;
    }

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
    static int findSite(String rawName, String wishText, boolean auto) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            StartBuildMod.chat("§cJoin a world first.");
            return 0;
        }
        if (state != State.IDLE) {
            StartBuildMod.chat("§eA build is running. /stopbuild first.");
            return 0;
        }
        String name = schematicFileName(rawName);
        File file = new File(new File(mc.gameDirectory, "schematics"), name);
        if (!file.isFile()) {
            StartBuildMod.chat("§cNo such schematic: " + name);
            return 0;
        }
        SchematicModel m = SchematicModel.read(LitematicaBridge.loadSchematic(file.toPath().getParent(), name));
        if (m == null) {
            StartBuildMod.chat("§cCould not read " + name + ".");
            return 0;
        }
        resetSiteSearch();
        boolean repeat = name.equals(siteName) && !auto;
        if (!repeat) {
            shownSites.clear();
            explorations = 0;
        }
        autoMode = auto;
        autoAttempts = 0;
        siteName = name;
        siteModel = m;
        siteWishText = wishText == null ? "" : wishText;
        siteChoices = List.of();
        // Flying far needs creative (a survival player teleported to y=200 would fall).
        if (mc.gameMode.getPlayerMode() != GameType.CREATIVE) {
            StartBuildMod.runServerCommand("gamemode creative");
        }
        if (!PlacementFinder.biomeWords(siteWishText).isEmpty()) return exploreBiome(mc);
        if (repeat) return exploreElsewhere(mc);
        return searchAround(mc, mc.player.blockPosition());
    }

    static int nextSite() {
        if (state != State.IDLE) {
            StartBuildMod.chat("§eA build is running. /stopbuild first.");
            return 0;
        }
        if (siteChoices.isEmpty()) {
            StartBuildMod.chat("§eRun /findsite <name> <wish> first.");
            return 0;
        }
        autoMode = false;
        if (siteIndex + 1 >= siteChoices.size()) {
            // Seen every choice here: go somewhere new rather than cycling back to the first one.
            return exploreFurther(Minecraft.getInstance());
        }
        siteIndex++;
        return showSite();
    }

    private static int exploreFurther(Minecraft mc) {
        return PlacementFinder.biomeWords(siteWishText).isEmpty() ? exploreElsewhere(mc) : exploreBiome(mc);
    }

    /** Next exploration point: 600-900 blocks away, each time in a new direction (golden-angle spiral). */
    private static BlockPos nextExplorePoint(BlockPos from) {
        explorations++;
        double angle = Math.toRadians(explorations * 137.508);
        int dist = 600 + 150 * (explorations % 3);
        return new BlockPos(from.getX() + (int) (Math.cos(angle) * dist), 64, from.getZ() + (int) (Math.sin(angle) * dist));
    }

    private static int exploreElsewhere(Minecraft mc) {
        BlockPos target = nextExplorePoint(mc.player.blockPosition());
        StartBuildMod.chat("Looking somewhere new, ~" + (int) Math.sqrt(mc.player.blockPosition().distSqr(target))
                + " blocks away - loading the area...");
        flyTo(mc, target);
        return 1;
    }

    private static int exploreBiome(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            StartBuildMod.chat("§cBiome search needs a singleplayer world.");
            autoMode = false;
            return 0;
        }
        List<String> words = PlacementFinder.biomeWords(siteWishText);
        // Start from the player, or - once this biome has been used - from a point far away, so the nearest
        // match is a different patch of it.
        BlockPos from = shownSites.isEmpty() && autoAttempts == 0 ? mc.player.blockPosition()
                : nextExplorePoint(mc.player.blockPosition());
        ServerLevel level = server.getLevel(mc.level.dimension());
        if (level == null) {
            StartBuildMod.chat("§cBiome search: this dimension is not available.");
            autoMode = false;
            return 0;
        }
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

    /** Idle ticks: finish a biome lookup, wait for a new area's chunks and search, auto-confirm. */
    private static void tickSiteSearch(Minecraft mc) {
        if (autoConfirmTicks > 0 && --autoConfirmTicks == 0) {
            StartBuildMod.LOGGER.info("[StartBuild] hands-free: confirming site {}", previewOrigin);
            autoMode = false;
            if (confirmPreview() == 0) {
                StartBuildMod.chat("§cHands-free take could not start (see the log).");
            }
            return;
        }
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
                StartBuildMod.chat("§eNo " + String.join("/", PlacementFinder.biomeWords(siteWishText))
                        + " biome within 3200 blocks.");
                autoRetry(mc);
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
        // The corners of the square lie beyond render distance and never load, hence 85%, not 100%.
        int reach = searchRadius(mc, siteModel) + Math.max(siteModel.sizeX, siteModel.sizeZ);
        int missing = 0, total = 0;
        for (int cz = (exploreTarget.getZ() - reach) >> 4; cz <= (exploreTarget.getZ() + reach) >> 4; cz++) {
            for (int cx = (exploreTarget.getX() - reach) >> 4; cx <= (exploreTarget.getX() + reach) >> 4; cx++) {
                total++;
                if (!PlacementFinder.loaded(mc.level, cx, cz)) missing++;
            }
        }
        if ((exploreTicks < 40 || missing > total * 15 / 100) && exploreTicks < 800) return;
        BlockPos centre = exploreTarget;
        exploreTarget = null;
        if (missing > total / 2) {
            StartBuildMod.chat("§eThe area did not load in time.");
            autoRetry(mc);
            return;
        }
        StartBuildMod.LOGGER.info("[StartBuild] findsite area {} loaded ({} of {} chunks) after {} ticks",
                centre, total - missing, total, exploreTicks);
        searchAround(mc, centre);
    }

    /** Hands-free: nothing usable here - try another area, up to config.autoSiteAttempts. */
    private static void autoRetry(Minecraft mc) {
        if (!autoMode) {
            StartBuildMod.chat("Run /findsite again to look further away.");
            return;
        }
        int max = StartBuildConfig.load().autoSiteAttempts;
        if (++autoAttempts >= max) {
            StartBuildMod.chat("§cHands-free take: no usable site found in " + max + " areas. Giving up.");
            autoMode = false;
            return;
        }
        StartBuildMod.LOGGER.info("[StartBuild] hands-free: no site here, trying another area ({}/{})", autoAttempts, max);
        exploreFurther(mc);
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
        for (PlacementFinder.Result r : PlacementFinder.find(mc.level, centre, radius, m, wish, 40, biomes)) {
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
            StartBuildMod.chat("§eNo good new spot here" + (wish == PlacementFinder.Wish.WATER ? " next to water" : "") + ".");
            autoRetry(mc);
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
            StartBuildMod.chat("§cLitematica could not show the site.");
            autoMode = false;
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
        if (autoMode) {
            StartBuildMod.chat("Hands-free: starting here in 3 s.");
            autoConfirmTicks = 60;
        } else {
            StartBuildMod.chat("/startbuild confirm to build here, /findsite next for another.");
        }
        return 1;
    }

    static int clearPreview() {
        if (state != State.IDLE) return 0;
        int n = LitematicaBridge.clearPlacements();
        previewName = null;
        previewOrigin = null;
        StartBuildMod.chat(n > 0 ? "Preview hidden." : "No preview to hide.");
        return 1;
    }

    /** Builds the last preview exactly where it was shown, wherever the player is now. */
    static int confirmPreview() {
        if (previewName == null || previewOrigin == null) {
            StartBuildMod.chat("§eNo preview yet. /findsite or /previewbuild first.");
            return 0;
        }
        String name = previewName;
        BlockPos at = previewOrigin;
        // Build exactly where the preview was put. (Reading Litematica's live placement back proved unreliable:
        // it returned the viewpoint the player was teleported to, 18 blocks up, so the build floated.)
        previewName = null;
        previewOrigin = null;
        return start(name, at);     // start() clears the ghost so it never appears on camera
    }

    // ================================================================== ticking

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            ticksInWorld = 0;
            resetSiteSearch();
            if (state != State.IDLE) {
                // Left the world mid-take: Flashback ends its own recording with the world.
                StartBuildMod.LOGGER.warn("[StartBuild] left the world during a take ({})", stageLabel());
                state = State.IDLE;
                restoreOptions(mc);
            }
            return;
        }
        // Paused (menu open): nothing in the world moves, so nothing may be counted - the watchdog would
        // otherwise take a long pause for a stall.
        if (state != State.IDLE && mc.isPaused()) return;
        try {
            tickInner(mc);
            tickErrors = 0;
        } catch (Throwable t) {
            // One bad tick must not crash the game and lose the recording; a persistent error ends the take.
            tickErrors++;
            StartBuildMod.LOGGER.error("[StartBuild] error during {} (tick error {} in a row)", stageLabel(), tickErrors, t);
            if (tickErrors >= 40 && state != State.IDLE) {
                tickErrors = 0;
                if (state == State.BUILDING && building() && builder != null && builder.placed > 0) {
                    finish(mc);
                } else {
                    abort("repeated errors (see the log)");
                }
            }
        }
    }

    private static void tickInner(Minecraft mc) {
        ticksInWorld++;
        if (state != State.IDLE && ticksInWorld % 20 == 0 && stopFileExists()) {
            StartBuildMod.LOGGER.info("[StartBuild] stop file found - stopping");
            deleteStopFile();
            stop();
            return;
        }
        switch (state) {
            case IDLE -> {
                tickSiteSearch(mc);
                checkAutorun(mc);
            }
            case PREPARING -> tickPreparing(mc);
            case PRE_ROLL -> {
                if (--waitTicks <= 0) {
                    if (recordingByUs && !FlashbackBridge.isRecording()) {
                        abort("Flashback did not start recording");
                        return;
                    }
                    buildStartMillis = System.currentTimeMillis();
                    state = State.BUILDING;
                    startStage();
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
        // Wait for the chunks under the footprint (required) and the terraforming zone around it (wanted).
        // ClientLevel.hasChunk is always true in 26.2, so real chunk data is checked.
        int zone = Math.max(config.terraformRadius, Terraformer.MAX_RADIUS) + 2;
        boolean footprint = true, all = true;
        for (int cx = (origin.getX() - zone) >> 4; cx <= (origin.getX() + model.sizeX + zone) >> 4; cx++) {
            for (int cz = (origin.getZ() - zone) >> 4; cz <= (origin.getZ() + model.sizeZ + zone) >> 4; cz++) {
                if (PlacementFinder.loaded(level, cx, cz)) continue;
                all = false;
                int x0 = cx << 4, z0 = cz << 4;
                if (x0 + 15 >= origin.getX() && x0 <= origin.getX() + model.sizeX
                        && z0 + 15 >= origin.getZ() && z0 <= origin.getZ() + model.sizeZ) footprint = false;
            }
        }
        if (!footprint) {
            if (waitTicks > 600) abort("the build area did not load (render distance too small?)");
            return;
        }
        if ((!all && waitTicks < 400) || waitTicks < 40) {
            return;
        }
        // Plan the terraforming now (read-only). The character does all of it by hand ON CAMERA once the
        // recording runs: fell trees, dig the hillside down, build up dips, re-grass the banks, then build.
        parts = List.of();
        if (config.prepTerrain) {
            long t0 = System.currentTimeMillis();
            Terraformer.Plan plan = Terraformer.plan(level, origin, model, config.terraformRadius);
            parts = plan.parts();
            StartBuildMod.LOGGER.info("[StartBuild] terraform plan: {} ({} ms)", plan.describe(),
                    System.currentTimeMillis() - t0);
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
        applyGameSpeed(true);
        waitTicks = Math.max(20, config.preRollTicks());
        state = State.PRE_ROLL;
        StartBuildMod.LOGGER.info("[StartBuild] recording={} {} at {}", recordingByUs, schematicName, origin.toShortString());
    }

    private static void startStage() {
        if (building()) {
            builder = new NaturalBuilder(model, origin, config.ticksPerBlock);
        } else {
            Terraformer.Part p = parts.get(partIndex);
            builder = new NaturalBuilder(p.model(), p.origin(), config.ticksPerBlock, p.clearing());
        }
        stalls = 0;
        stallMark = -1;
        StartBuildMod.LOGGER.info("[StartBuild] {} ({} of {}) {}", stageLabel(), Math.min(partIndex, parts.size()) + 1,
                parts.size() + 1, schematicName);
    }

    private static void tickBuilding(Minecraft mc) {
        builder.tick();
        watchdog(mc);

        // Mobs block placement and wander into shot; clear them quietly.
        if (builder.ticks % 400 == 1) {
            StartBuildMod.runServerCommand("execute if entity @e[type=!minecraft:player,type=!minecraft:item,"
                    + "distance=..128] run kill @e[type=!minecraft:player,type=!minecraft:item,distance=..128]");
        }

        if (builder.ticks % 1200 == 0) {
            // Progress to the log once a minute (never to chat - it would be on camera) + the safety limits.
            long minutes = (System.currentTimeMillis() - buildStartMillis) / 60000;
            StartBuildMod.LOGGER.info("[StartBuild] progress: {} - {} placed, {} broken, {} left, layer {}, {} min{}",
                    stageLabel(), builder.placed, builder.broken, builder.remaining(), builder.layer(), minutes,
                    builder.lastProblem().isEmpty() ? "" : ", last problem: " + builder.lastProblem());
            double freeGb = new File(mc.gameDirectory.getAbsolutePath()).getUsableSpace() / 1e9;
            if (freeGb < config.minFreeDiskGB) {
                StartBuildMod.LOGGER.warn("[StartBuild] only {} GB free - stopping to protect the recording", freeGb);
                finish(mc);
                return;
            }
            if (config.maxBuildMinutes > 0 && minutes >= config.maxBuildMinutes) {
                StartBuildMod.LOGGER.warn("[StartBuild] {} min limit reached - stopping", config.maxBuildMinutes);
                finish(mc);
                return;
            }
        }

        // Stop safely if the recording was ended behind our back.
        if (recordingByUs && builder.ticks % 100 == 0 && !FlashbackBridge.isRecording()) {
            StartBuildMod.LOGGER.warn("[StartBuild] Flashback stopped recording; stopping the build");
            recordingByUs = false;
            finish(mc);
            return;
        }

        if (!building()) {
            // Terraforming stages hand over to the next one when done: trees, clear, fill -> build.
            if (builder.isFinished()) {
                StartBuildMod.LOGGER.info("[StartBuild] {} done: {} broken, {} placed, {} left over, {} min",
                        stageLabel(), builder.broken, builder.placed, builder.remaining(),
                        (System.currentTimeMillis() - buildStartMillis) / 60000);
                terrainBroken += builder.broken;
                terrainPlaced += builder.placed;
                partIndex++;
                startStage();
            }
            return;
        }

        if (builder.isFinished() && builder.placed == 0) {
            abort("nothing could be placed at " + origin.toShortString() + ". Try another spot.");
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
            StartBuildMod.LOGGER.info("[StartBuild] last block placed; recording continues {}s", config.stopDelaySeconds);
        }
    }

    /** No progress for this long -> the watchdog steps in (and again each time after that). */
    private static final int STALL_TICKS = 20 * 60 * 3;
    private static int stalls;
    private static long stallMark = -1;

    /**
     * Unattended runs must never hang. If the current stage makes no progress for 3 minutes:
     *   1st time - forget all plans and retry everything left from scratch;
     *   2nd time - also move the player to open air above the work (it may be shut in) and retry;
     *   3rd time - give up on what is left of this stage and carry on (the build stage then finishes and
     *              the take is saved), logging exactly what was left.
     * Progress in between resets the count. Paused ticks are not counted (tick() returns early).
     */
    private static void watchdog(Minecraft mc) {
        if (builder.lastProgressTick > stallMark) {
            stalls = 0;
        }
        if (builder.isFinished() || builder.ticks - builder.lastProgressTick < STALL_TICKS) return;
        stalls++;
        String where = stageLabel() + ", " + builder.remaining() + " left, last problem: " + builder.lastProblem();
        if (stalls == 1) {
            StartBuildMod.LOGGER.warn("[StartBuild] watchdog: no progress for 3 min ({}) - replanning", where);
            builder.recover();
        } else if (stalls == 2) {
            BlockPos p = builder.escapePoint();
            for (int k = 0; k < 64 && !(isFree(mc, p) && isFree(mc, p.above())); k++) p = p.above();
            StartBuildMod.LOGGER.warn("[StartBuild] watchdog: still stuck ({}) - moving the player to {}", where, p);
            StartBuildMod.runServerCommand("tp @s " + (p.getX() + 0.5) + " " + p.getY() + " " + (p.getZ() + 0.5));
            builder.recover();
        } else {
            StartBuildMod.LOGGER.warn("[StartBuild] watchdog: giving up on the rest of this stage ({})", where);
            builder.giveUp();
            if (building()) rechecks = 2;     // no final re-check loop on cells that cannot be done
        }
        stallMark = builder.lastProgressTick;
    }

    private static boolean isFree(Minecraft mc, BlockPos p) {
        return mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
    }

    // ================================================================== ending

    /**
     * Ends the take. A take in which the character placed nothing of the build (stopped during
     * terraforming, or before anything happened) is DISCARDED - an empty take is never saved.
     */
    private static void finish(Minecraft mc) {
        boolean built = building() && builder != null;
        long placed = built ? builder.placed : 0;
        int wrong = (built && mc.level != null) ? builder.countWrong(mc.level) : -1;
        long minutes = buildStartMillis == 0 ? 0 : (System.currentTimeMillis() - buildStartMillis) / 60000;
        boolean keep = placed > 0;
        boolean saved = false;
        if (recordingByUs && FlashbackBridge.isRecording()) {
            if (!keep) {
                FlashbackBridge.cancelRecording();
                StartBuildMod.LOGGER.info("[StartBuild] nothing of the build was placed - recording discarded");
            } else {
                if (config.addCompletionMarker) {
                    StartBuildMod.runClientCommand("flashback mark");
                }
                if (config.finishRecording) {
                    saved = FlashbackBridge.finishRecording();
                }
            }
        }
        state = State.IDLE;
        buildStartMillis = 0;
        restoreOptions(mc);
        String summary = String.format("Done: %s - %d placed by the character, %d not matching, %d min"
                        + " (terraforming: %d broken, %d placed).",
                schematicName, placed, wrong, minutes, terrainBroken + (built ? 0 : builder == null ? 0 : builder.broken),
                terrainPlaced);
        StartBuildMod.LOGGER.info("[StartBuild] {}{}", summary, saved ? " Recording saved." : "");
        StartBuildMod.chat((wrong == 0 ? "§a" : "§e") + summary
                + (saved ? " Recording saved." : keep ? "" : " Recording discarded (nothing built)."));
        if (config.desktopNotification && saved) {
            notifyDesktop("StartBuild: " + summary);
        }
        recordingByUs = false;
    }

    /** Ends a take that failed: the recording is discarded, never saved. */
    private static void abort(String why) {
        StartBuildMod.chat("§cStopped: " + why);
        if (recordingByUs && FlashbackBridge.isRecording()) {
            FlashbackBridge.cancelRecording();
            StartBuildMod.LOGGER.info("[StartBuild] recording discarded");
        }
        recordingByUs = false;
        state = State.IDLE;
        buildStartMillis = 0;
        restoreOptions(Minecraft.getInstance());
    }

    private static boolean speedApplied;

    /**
     * Optional: run the world faster than real time during a take (/tick rate). Everything - the character
     * included - happens in game ticks and Flashback records game ticks, so the replay plays at normal
     * speed while the take finishes sooner. Off (gameSpeed 1) until verified on this setup.
     */
    private static void applyGameSpeed(boolean on) {
        double speed = config == null ? 1.0 : config.gameSpeed;
        if (on && speed > 1.0) {
            int rate = (int) Math.round(20 * Math.min(4.0, speed));
            StartBuildMod.runServerCommand("tick rate " + rate);
            speedApplied = true;
            StartBuildMod.LOGGER.info("[StartBuild] game speed x{} (tick rate {})", speed, rate);
        } else if (!on && speedApplied) {
            StartBuildMod.runServerCommand("tick rate 20");
            speedApplied = false;
        }
    }

    private static void restoreOptions(Minecraft mc) {
        applyGameSpeed(false);
        if (savedPauseOnLostFocus != null && mc != null && mc.options != null) {
            mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
        }
        savedPauseOnLostFocus = null;
    }

    /** /stopbuild (and the stop file): ends the take and saves it - or cancels a hands-free site search. */
    static int stop() {
        if (state == State.IDLE) {
            if (autoMode || biomeLookup != null || exploreTarget != null || autoConfirmTicks > 0) {
                resetSiteSearch();
                StartBuildMod.chat("Site search stopped.");
                return 1;
            }
            return 0;
        }
        StartBuildMod.chat("Stopping.");
        finish(Minecraft.getInstance());
        return 1;
    }

    static void status() {
        if (state == State.IDLE) {
            StartBuildMod.chat("natural builder: idle" + (autoMode ? " (hands-free site search running)" : ""));
            return;
        }
        if (builder == null) {
            StartBuildMod.chat("natural builder: " + state + " (" + schematicName + ")");
            return;
        }
        StartBuildMod.chat(String.format("natural builder: %s (%s) %s - %d placed, %d broken, %d left, layer %d%s",
                state, stageLabel(), schematicName, builder.placed, builder.broken, builder.remaining(), builder.layer(),
                builder.lastProblem().isEmpty() ? "" : " | last problem: " + builder.lastProblem()));
    }

    // ================================================================== outside control

    private static Path stopFile() {
        String name = (config == null ? StartBuildConfig.load() : config).stopFileName;
        return FabricLoader.getInstance().getConfigDir().resolve(name == null || name.isBlank() ? "startbuild-stop" : name);
    }

    private static boolean stopFileExists() {
        try {
            return Files.exists(stopFile());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteStopFile() {
        try {
            Files.deleteIfExists(stopFile());
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not delete the stop file: {}", Reflect.describe(t));
        }
    }

    /**
     * The desktop launcher writes config/startbuild-autorun ("name [wish]"). As soon as a world is loaded
     * and has settled, the hands-free take starts by itself - no typing.
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
            String text = Files.readString(flag).trim();
            Files.deleteIfExists(flag);
            StartBuildMod.LOGGER.info("[StartBuild] autorun flag found: '{}'", text);
            startAuto(text);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] autorun check failed: {}", Reflect.describe(t));
        }
    }

    /** A Windows/desktop tray notification, off the game thread; silently skipped where unsupported. */
    /**
     * A Windows notification balloon, shown by a separate hidden PowerShell process: Minecraft runs Java
     * headless, so java.awt's tray cannot be used from inside the game. Silently skipped off Windows.
     */
    private static void notifyDesktop(String text) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        String safe = text.replace("'", "''").replace("\r", " ").replace("\n", " ");
        String script = "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                + "$n = New-Object System.Windows.Forms.NotifyIcon; $n.Icon = [System.Drawing.SystemIcons]::Information; "
                + "$n.Visible = $true; $n.ShowBalloonTip(15000, 'StartBuild', '" + safe + "', 'Info'); "
                + "Start-Sleep -Seconds 16; $n.Dispose()";
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Throwable e) {
            StartBuildMod.LOGGER.info("[StartBuild] desktop notification failed: {}", Reflect.describe(e));
        }
    }
}
