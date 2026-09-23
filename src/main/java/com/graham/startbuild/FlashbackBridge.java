package com.graham.startbuild;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runtime bridge to Flashback.
 *
 * Verified against Flashback's published source (Moulberry/Flashback, master, read 2026-09-22):
 *
 *   public static volatile Recorder RECORDER = null;
 *   public static void startRecordingReplay();
 *   public static void finishRecordingReplay();
 *   public static void cancelRecordingReplay();
 *   public static void pauseRecordingReplay(boolean pause);
 *   public static FlashbackConfigV1 getConfig();           // public static
 *   ...getConfig().recordingControls.quicksave             // public field, read by finishRecordingReplay()
 *
 * Flashback also ships its own client commands (/flashback start|finish|pause|mark), so this is a
 * supported surface rather than a private hack.
 */
final class FlashbackBridge {

    private static final String CLASS_NAME = "com.moulberry.flashback.Flashback";

    private static boolean initialised;
    private static Class<?> flashback;

    private FlashbackBridge() {
    }

    static boolean available() {
        if (!initialised) {
            flashback = Reflect.find(CLASS_NAME);
            initialised = true;
        }
        return flashback != null;
    }

    static boolean isRecording() {
        if (!available()) {
            return false;
        }
        try {
            Field recorder = Reflect.field(flashback, "RECORDER");
            return recorder.get(null) != null;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Could not read Flashback.RECORDER: {}", Reflect.describe(t));
            return false;
        }
    }

    static boolean startRecording() {
        return invokeStatic("startRecordingReplay");
    }

    static boolean finishRecording() {
        return invokeStatic("finishRecordingReplay");
    }

    static boolean cancelRecording() {
        return invokeStatic("cancelRecordingReplay");
    }

    private static boolean invokeStatic(String name) {
        if (!available()) {
            StartBuildMod.LOGGER.warn("[StartBuild] Flashback is not installed, so {} is unavailable", name);
            return false;
        }
        try {
            Method method = Reflect.method(flashback, name);
            method.invoke(null);
            return true;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.error("[StartBuild] Flashback.{} failed: {}", name, Reflect.describe(t));
            return false;
        }
    }

    /** @return Flashback's own quicksave setting, or null when it could not be read. */
    static Boolean isQuicksaveEnabled() {
        if (!available()) {
            return null;
        }
        try {
            Object config = Reflect.method(flashback, "getConfig").invoke(null);
            if (config == null) {
                return null;
            }
            Object controls = Reflect.field(config.getClass(), "recordingControls").get(config);
            if (controls == null) {
                return null;
            }
            return (Boolean) Reflect.field(controls.getClass(), "quicksave").get(controls);
        } catch (Throwable t) {
            StartBuildMod.LOGGER.warn("[StartBuild] Could not read Flashback quicksave: {}", Reflect.describe(t));
            return null;
        }
    }

    /**
     * Flips Flashback's quicksave on for this session, so finishRecordingReplay() writes the replay
     * straight to the replay folder instead of opening the naming screen. Not persisted to disk.
     */
    static boolean setQuicksave(boolean value) {
        if (!available()) {
            return false;
        }
        try {
            Object config = Reflect.method(flashback, "getConfig").invoke(null);
            Object controls = Reflect.field(config.getClass(), "recordingControls").get(config);
            Reflect.field(controls.getClass(), "quicksave").set(controls, value);
            return true;
        } catch (Throwable t) {
            StartBuildMod.LOGGER.error("[StartBuild] Could not set Flashback quicksave: {}", Reflect.describe(t));
            return false;
        }
    }
}
