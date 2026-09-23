package com.graham.startbuild;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * StartBuild - one command that starts a Baritone build, starts a Flashback recording, and stops
 * and saves that recording a configurable delay after the last block is placed.
 *
 * Client-side only: /startbuild, /stopbuild and /buildstatus are registered on the client command
 * dispatcher (the same mechanism Flashback uses for /flashback), so nothing extra is sent to the
 * server and it works on any server you can already play on.
 */
public class StartBuildMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("startbuild");

    private static CommandDispatcher<FabricClientCommandSource> clientDispatcher;
    private static FabricClientCommandSource lastCommandSource;

    private static boolean bridgesInitialised;
    private static boolean selfTestReported;
    private static List<String> selfTestProblems = List.of();

    @Override
    public void onInitializeClient() {
        StartBuildConfig config = StartBuildConfig.load();
        LOGGER.info("[StartBuild] Config: {}", config.summary());
        LOGGER.info("[StartBuild] Baritone reachable: {}, Flashback reachable: {}",
                BaritoneBridge.available(), FlashbackBridge.available());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            clientDispatcher = dispatcher;

            dispatcher.register(ClientCommands.literal("startbuild")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        return StartBuildSession.start(-1);
                    })
                    // Episode mode: record only the next config.episodeLayers layers, then stop.
                    .then(ClientCommands.literal("next")
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return StartBuildSession.start(-1, true);
                            })
                            .then(ClientCommands.argument("layers", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        StartBuildConfig cfg = StartBuildConfig.load();
                                        cfg.episodeLayers = IntegerArgumentType.getInteger(context, "layers");
                                        cfg.save();
                                        return StartBuildSession.start(-1, true);
                                    })))
                    .then(ClientCommands.argument("placement", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return StartBuildSession.start(IntegerArgumentType.getInteger(context, "placement"));
                            }))
                    // Everything in one command: load this .litematic and build it here. Baritone reads
                    // the file itself, so no Litematica placement is involved at all - stand where you
                    // want the corner and run it.
                    .then(ClientCommands.literal("file")
                            .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        return StartBuildSession.startFromFile(StringArgumentType.getString(context, "name"));
                                    })))
                    // Everything in one command, for any format Litematica can read (v7 and older):
                    // load the schematic, create the placement here, and build it.
                    .then(ClientCommands.literal("place")
                            .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        return StartBuildSession.startFromLitematica(StringArgumentType.getString(context, "name"));
                                    })))
                    // The normal-world flow: pick a site nearby, level the ground, place it there, build.
                    // The name is a plain string(), NOT greedyString(): a greedy argument swallows the
                    // rest of the line, which would make the optional radius after it unparseable and
                    // turn "/startbuild site haunted_80 256" into a lookup for "haunted_80 256".
                    .then(ClientCommands.literal("site")
                            .then(ClientCommands.argument("name", StringArgumentType.string())
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        StartBuildConfig cfg = StartBuildConfig.load();
                                        return StartBuildSession.startAtSite(
                                                StringArgumentType.getString(context, "name"), cfg.siteRadius);
                                    })
                                    .then(ClientCommands.argument("radius", IntegerArgumentType.integer(16, 512))
                                            .executes(context -> {
                                                lastCommandSource = context.getSource();
                                                return StartBuildSession.startAtSite(
                                                        StringArgumentType.getString(context, "name"),
                                                        IntegerArgumentType.getInteger(context, "radius"));
                                            })))));

            dispatcher.register(ClientCommands.literal("stopbuild")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        return StartBuildSession.stopNow();
                    }));

            // Where in a normal world would this schematic sit best? Read-only, for tuning the scoring
            // and for choosing a site before anything is allowed to move earth.
            dispatcher.register(ClientCommands.literal("buildsite")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        return StartBuildSession.reportSites(128);
                    })
                    .then(ClientCommands.argument("radius", IntegerArgumentType.integer(16, 512))
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return StartBuildSession.reportSites(
                                        IntegerArgumentType.getInteger(context, "radius"));
                            })));

            // Selection helpers - so nobody has to stand at two corners and type #sel pos1/pos2.
            dispatcher.register(ClientCommands.literal("buildsel")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        return StartBuildSession.selectionStatus();
                    })
                    .then(ClientCommands.literal("off")
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return StartBuildSession.disableAutoSelection();
                            }))
                    .then(ClientCommands.literal("full")
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return StartBuildSession.applyAutoSelection("full", 0);
                            }))
                    .then(ClientCommands.literal("layers")
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return StartBuildSession.applyAutoSelection("layers", 0);
                            })
                            .then(ClientCommands.argument("count", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        return StartBuildSession.applyAutoSelection("layers",
                                                IntegerArgumentType.getInteger(context, "count"));
                                    })))
                    .then(ClientCommands.literal("corner")
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return StartBuildSession.applyAutoSelection("corner", 0);
                            })
                            .then(ClientCommands.argument("size", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        return StartBuildSession.applyAutoSelection("corner",
                                                IntegerArgumentType.getInteger(context, "size"));
                                    }))));

            dispatcher.register(ClientCommands.literal("buildstatus")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        return StartBuildSession.status();
                    }));

            // One-off setup: the Baritone settings the recording workflow wants, applied and reported.
            dispatcher.register(ClientCommands.literal("buildprep")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        if (!BaritoneBridge.available()) {
                            chat("\u00A7cBaritone is not installed, so there is nothing to configure.");
                            return 0;
                        }
                        java.util.List<String> changed = BaritoneBridge.applyVideoSettings();
                        if (changed.isEmpty()) {
                            chat("\u00A7aBaritone is already set up for recording "
                                    + "(allowInventory on, path/goal/selection rendering off).");
                        } else {
                            chat("\u00A7aApplied video-ready Baritone settings:");
                            for (String line : changed) {
                                chat("\u00A7a  " + line);
                            }
                        }
                        chat("Litematica's ghost-block overlay is separate - toggle it with M + G before recording.");
                        return 1;
                    }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BaritoneEventBridge.onClientTick();
            initialiseBridgesOnce(client);
            maybeReportSelfTest();
            StartBuildSession.tick();
        });
    }

    /**
     * Baritone and Flashback are only touched once a player exists, so nothing happens during client
     * startup and a missing/renamed API cannot break loading.
     */
    private static void initialiseBridgesOnce(Minecraft client) {
        if (bridgesInitialised || client == null || client.player == null) {
            return;
        }
        bridgesInitialised = true;

        BaritoneEventBridge.register();
        BaritoneEventBridge.hookLogger();

        selfTestProblems = selfTest();
        if (selfTestProblems.isEmpty()) {
            LOGGER.info("[StartBuild] self-test passed (event timing: {}, logger hook: {})",
                    BaritoneEventBridge.isRegistered(), BaritoneEventBridge.isLoggerHooked());
        } else {
            for (String problem : selfTestProblems) {
                LOGGER.warn("[StartBuild] self-test: {}", problem);
            }
        }
    }

    /** Report binding problems once, in chat, as soon as we are actually in a world. */
    private static void reportSelfTestOnce() {
        if (selfTestReported || selfTestProblems.isEmpty()) {
            return;
        }
        selfTestReported = true;
        chat("\u00A7eStartBuild self-test found " + selfTestProblems.size() + " problem(s):");
        for (String problem : selfTestProblems) {
            chat("\u00A7e  - " + problem);
        }
    }

    /** Probe every bridge the mod depends on. @return an empty list when everything resolved. */
    static List<String> selfTest() {
        List<String> problems = new ArrayList<>();

        if (!BaritoneBridge.available()) {
            problems.add("Baritone API classes not found - is the baritone-api-fabric jar installed "
                    + "(not the obfuscated 'standalone' build)?");
        } else {
            for (String probe : new String[]{"settings", "layers", "selections", "builder", "notify", "events"}) {
                String problem = BaritoneBridge.probe(probe);
                if (problem != null) {
                    problems.add("Baritone " + probe + ": " + problem);
                }
            }
            if (!BaritoneEventBridge.isRegistered()) {
                problems.add("Baritone block-change events not hooked - the stop delay will fall back to "
                        + "idle detection instead of measuring from the last block placed");
            }
            if (!BaritoneEventBridge.isLoggerHooked()) {
                problems.add("Baritone chat output not hooked - missing-material reports will be unavailable");
            }
        }

        if (!FlashbackBridge.available()) {
            problems.add("Flashback not found - /startbuild will build without recording");
        } else if (FlashbackBridge.isQuicksaveEnabled() == null) {
            problems.add("Flashback quicksave setting could not be read");
        }

        return problems;
    }

    static List<String> problems() {
        return selfTestProblems;
    }

    static void chat(String message) {
        LOGGER.info("[StartBuild] {}", message.replaceAll("\u00A7.", ""));
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gui != null && minecraft.gui.hud != null) {
                minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal("\u00A7b[StartBuild] \u00A7r" + message));
            }
        } catch (Throwable t) {
            LOGGER.warn("[StartBuild] Could not write to chat: {}", Reflect.describe(t));
        }
    }

    /**
     * Runs one of Flashback's own client commands. Used to drop a timeline marker at the moment the
     * build stops (/flashback mark), which gives you a jump-to point when editing the replay.
     *
     * The command source is rebuilt at execution time. lastCommandSource is captured when the player
     * typed /startbuild and may be hours stale by the time the marker is due, and Fabric's source gets
     * the player and level out of its suggestion provider. lastCommandSource is kept only as a fallback.
     * (ClientPacketListener.getSuggestionsProvider() verified present in 26.2.)
     */
    static void runClientCommand(String command) {
        if (clientDispatcher == null) {
            return;
        }
        Object provider = null;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.player.connection != null) {
                provider = minecraft.player.connection.getSuggestionsProvider();
            }
        } catch (Throwable t) {
            LOGGER.warn("[StartBuild] could not build a fresh command source: {}", Reflect.describe(t));
        }
        FabricClientCommandSource source =
                (provider instanceof FabricClientCommandSource fresh) ? fresh : lastCommandSource;
        if (source == null) {
            return;
        }
        try {
            clientDispatcher.execute(command, source);
        } catch (Throwable t) {
            LOGGER.warn("[StartBuild] Could not run client command '{}': {}", command, Reflect.describe(t));
        }
    }

    /**
     * Sends a server-side command exactly as if it had been typed, e.g. a datapack "/function".
     * These are not client commands, so they cannot go through the client dispatcher above - they
     * have to be sent to the server. Needs cheats/OP, which the stock datapack needs anyway.
     *
     * The method name has moved across versions (sendCommand / sendChatCommand), so the known names are
     * tried in turn instead of binding to one at compile time.
     *
     * Deliberately NOT sendUnattendedCommand: despite the name, that is the one that runs the command
     * through verifyCommand and can pop a modal "click to run" confirmation screen - verified against
     * 26.2's own bytecode, where sendUnattendedCommand calls verifyCommand/openCommandSendConfirmationWindow
     * while sendCommand just parses and sends. A confirmation screen on an unattended run would silently
     * stall everything.
     */
    static boolean runServerCommand(String command) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.player.connection == null) {
                return false;
            }
            Object connection = minecraft.player.connection;
            for (String name : new String[]{"sendCommand", "sendChatCommand"}) {
                java.lang.reflect.Method method;
                try {
                    method = connection.getClass().getMethod(name, String.class);
                } catch (NoSuchMethodException notThisOne) {
                    continue;
                }
                try {
                    method.invoke(connection, command);
                    return true;
                } catch (Throwable failed) {
                    // Try the next name rather than giving up on the whole chain.
                    LOGGER.warn("[StartBuild] '{}' failed: {}", name, Reflect.describe(failed));
                }
            }
            LOGGER.warn("[StartBuild] No known command-sending method on {}", connection.getClass().getName());
            return false;
        } catch (Throwable t) {
            LOGGER.warn("[StartBuild] Could not run server command '{}': {}", command, Reflect.describe(t));
            return false;
        }
    }

    /** Called from the tick handler so bridge problems surface without the user asking. */
    static void maybeReportSelfTest() {
        reportSelfTestOnce();
    }
}
