package com.graham.startbuild;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the generated stock datapack functions to learn which block types a build uses, in the order the
 * datapack itself sorts them (most-used first).
 *
 * This is the text-free fallback for material restocking. Depending on a single text signal failed once
 * already: a startsWith() that never matched Baritone's "[Baritone] " chat prefix meant
 * lastMissingMaterials stayed empty for the whole life of the project, which silently disabled every
 * materials feature. The type list is also available from files the mod controls, so it is read too.
 *
 * Only used when Baritone's missing-materials text is unavailable.
 */
final class StockFunctions {

    /** "give @s minecraft:coal_block 64" - the generated functions give one type per line. */
    private static final Pattern GIVE =
            Pattern.compile("^give\\s+@s\\s+([a-z_]+:[a-z_]+)(?:\\s+(\\d+))?");

    private StockFunctions() {
    }

    /**
     * Block types for the given "namespace:function" names, most-used first and deduplicated.
     * Returns an empty list when the world folder or the functions cannot be found.
     */
    static List<String> typesInOrder(List<String> functions) {
        List<String> types = new ArrayList<>();
        if (functions == null || functions.isEmpty()) {
            return types;
        }
        Path datapacks = datapacksDir();
        if (datapacks == null) {
            StartBuildMod.LOGGER.warn("[StartBuild] could not locate the world's datapacks folder");
            return types;
        }
        for (String function : functions) {
            Path file = resolve(datapacks, function);
            if (file == null) {
                // Say what was actually searched. A silent miss here disables the whole text-free materials
                // fallback (and now the restock ranking too), and "not found" alone gave no way to tell a
                // missing datapack from a changed folder name from a wrong namespace.
                StartBuildMod.LOGGER.warn("[StartBuild] stock function not found: {} - looked for {}/data/{}/"
                        + "function/{}.mcfunction in every folder under {}, and found: {}",
                        function, "<pack>", namespaceOf(function), nameOf(function), datapacks,
                        describePacks(datapacks));
                continue;
            }
            try (Stream<String> lines = Files.lines(file)) {
                lines.forEach(line -> {
                    Matcher matcher = GIVE.matcher(line.trim());
                    if (matcher.find() && !types.contains(matcher.group(1))) {
                        types.add(matcher.group(1));
                    }
                });
            } catch (IOException e) {
                StartBuildMod.LOGGER.warn("[StartBuild] could not read {}: {}", file, e.toString());
            }
        }
        return types;
    }

    /** For diagnostics only. */
    private static String namespaceOf(String function) {
        int colon = function.indexOf(':');
        return (colon > 0) ? function.substring(0, colon) : "?";
    }

    /** For diagnostics only. */
    private static String nameOf(String function) {
        int colon = function.indexOf(':');
        return (colon > 0 && colon < function.length() - 1) ? function.substring(colon + 1) : "?";
    }

    /** The pack folders that were actually searched, so a missing datapack is obvious in the log. */
    private static String describePacks(Path datapacks) {
        try (Stream<Path> packs = Files.list(datapacks)) {
            List<String> names = packs.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
            return names.isEmpty() ? "(no pack folders)" : String.join(", ", names);
        } catch (Throwable t) {
            return "(could not list: " + t.getClass().getSimpleName() + ")";
        }
    }

    private static Path datapacksDir() {
        try {
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) {
                return null;                      // not singleplayer, so there is no world folder to read
            }
            return server.getWorldPath(LevelResource.ROOT).resolve("datapacks");
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] world path lookup failed: {}", Reflect.describe(t));
            return null;
        }
    }

    /** Finds "<datapacks>/<pack>/data/<namespace>/function/<name>.mcfunction" for "namespace:name". */
    private static Path resolve(Path datapacks, String function) {
        int colon = function.indexOf(':');
        if (colon <= 0 || colon == function.length() - 1) {
            return null;
        }
        String namespace = function.substring(0, colon);
        String name = function.substring(colon + 1);
        if (!Files.isDirectory(datapacks)) {
            return null;
        }
        try (Stream<Path> packs = Files.list(datapacks)) {
            return packs.filter(Files::isDirectory)
                    .map(pack -> pack.resolve("data")
                            .resolve(namespace)
                            .resolve("function")
                            .resolve(name + ".mcfunction"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
