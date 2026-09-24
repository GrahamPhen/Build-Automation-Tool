package com.graham.startbuild;

import com.mojang.brigadier.CommandDispatcher;
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

/**
 * StartBuild: records a character hand-building a schematic (see {@link NaturalSession}).
 *
 * Client-side only: every command is registered on the client dispatcher (the same mechanism Flashback
 * uses for /flashback), so nothing extra is sent to the server.
 *
 *   /findsite <name> [wish]   /findsite next      pick a natural spot (ghost + viewpoint)
 *   /previewbuild <name>      /previewbuild off   ghost at your feet + what is in the way
 *   /startbuild confirm                           build where the preview/findsite put it
 *   /startbuild place <name>                      build with the corner at your feet
 *   /startbuild auto [name] [wish]                fully hands-free: find a site, terraform, build, save
 *   /stopbuild   /buildstatus
 */
public class StartBuildMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("startbuild");

    private static CommandDispatcher<FabricClientCommandSource> clientDispatcher;
    private static FabricClientCommandSource lastCommandSource;
    private static boolean selfTestDone;

    @Override
    public void onInitializeClient() {
        StartBuildConfig config = StartBuildConfig.load();
        LOGGER.info("[StartBuild] Config: {}", config.summary());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            clientDispatcher = dispatcher;

            dispatcher.register(ClientCommands.literal("startbuild")
                    .then(ClientCommands.literal("place")
                            .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        return NaturalSession.startHere(StringArgumentType.getString(context, "name"));
                                    })))
                    .then(ClientCommands.literal("confirm")
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return NaturalSession.confirmPreview();
                            }))
                    .then(ClientCommands.literal("auto")
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                return NaturalSession.startAuto("");
                            })
                            .then(ClientCommands.argument("nameAndWish", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        return NaturalSession.startAuto(StringArgumentType.getString(context, "nameAndWish"));
                                    }))));

            // One argument, checked in code: a literal "next" beside a string argument is ambiguous to
            // brigadier (it logged a warning, and a schematic called "next" could never be found).
            dispatcher.register(ClientCommands.literal("findsite")
                    .then(ClientCommands.argument("name", StringArgumentType.word())
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                String name = StringArgumentType.getString(context, "name");
                                return name.equalsIgnoreCase("next") ? NaturalSession.nextSite()
                                        : NaturalSession.findSite(name, "", false);
                            })
                            .then(ClientCommands.argument("wish", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        lastCommandSource = context.getSource();
                                        return NaturalSession.findSite(StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "wish"), false);
                                    }))));

            dispatcher.register(ClientCommands.literal("previewbuild")
                    .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes(context -> {
                                lastCommandSource = context.getSource();
                                String name = StringArgumentType.getString(context, "name").trim();
                                return name.equalsIgnoreCase("off") ? NaturalSession.clearPreview()
                                        : NaturalSession.preview(name);
                            })));

            dispatcher.register(ClientCommands.literal("stopbuild")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        return NaturalSession.stop();
                    }));

            dispatcher.register(ClientCommands.literal("buildstatus")
                    .executes(context -> {
                        lastCommandSource = context.getSource();
                        NaturalSession.status();
                        return 1;
                    }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            selfTestOnce(client);
            RenderDirector.tick();
            if (!RenderDirector.isBusy()) NaturalSession.tick();
        });
    }

    /** Once a player exists: check the mods we drive by reflection, and say so if one is missing. */
    private static void selfTestOnce(Minecraft client) {
        if (selfTestDone || client == null || client.player == null) {
            return;
        }
        selfTestDone = true;
        boolean litematica = LitematicaBridge.available();
        boolean flashback = FlashbackBridge.available();
        LOGGER.info("[StartBuild] self-test: Litematica {}, Flashback {} (quicksave {})",
                litematica ? "ok" : "MISSING", flashback ? "ok" : "MISSING", FlashbackBridge.isQuicksaveEnabled());
        if (!litematica) chat("§cLitematica is not installed - schematics cannot be read.");
        if (!flashback) chat("§eFlashback is not installed - builds will not be recorded.");
    }

    static void chat(String message) {
        LOGGER.info("[StartBuild] {}", message.replaceAll("§.", ""));
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gui != null && minecraft.gui.hud != null) {
                minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal("§b[StartBuild] §r" + message));
            }
        } catch (Throwable t) {
            LOGGER.warn("[StartBuild] Could not write to chat: {}", Reflect.describe(t));
        }
    }

    /**
     * Runs one of Flashback's own client commands (/flashback mark at the end of a take).
     *
     * The command source is rebuilt at execution time. lastCommandSource is captured when the player
     * typed a command and may be hours stale by the time the marker is due, and Fabric's source gets
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
     * Sends a server-side command exactly as if it had been typed (gamerules, /tp, /gamemode). Needs
     * cheats/OP.
     *
     * The method name has moved across versions (sendCommand / sendChatCommand), so the known names are
     * tried in turn instead of binding to one at compile time.
     *
     * Deliberately NOT sendUnattendedCommand: despite the name, that is the one that runs the command
     * through verifyCommand and can pop a modal "click to run" confirmation screen - verified against
     * 26.2's own bytecode. A confirmation screen on an unattended run would silently stall everything.
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
}
