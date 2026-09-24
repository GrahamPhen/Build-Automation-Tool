package com.graham.startbuild;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a take's Flashback replay into a finished vertical Short, with no clicking: opens the replay, writes
 * the edit as Flashback keyframes (orbiting camera shots, timelapse speed ramps, golden-hour light) and starts
 * Flashback's own exporter at 1080x1920.
 *
 * Started by a flag file, config/startbuild-render (key=value lines), checked at the title screen:
 *   replay=2026-09-24T12_47_34.zip        file in Flashback's replay folder
 *   centerX=-1241 centerZ=-245 baseY=62   the build's middle and ground level
 *   width=80 height=64                    the build's size (frames the shots)
 *   terraformEnd=45260 buildEnd=156780    replay ticks where the stages ended (from the take's log)
 *   seconds=120 output=build-short.mp4    length and file name (written to Flashback's replay folder/../exports)
 *
 * The edit: hook (untouched site, 4 s) -> terraforming timelapse (30 s) -> build timelapse (the bulk) ->
 * reveal (the last 30 s of the replay slowed to 16 s, a low sweeping orbit). Speed ramps are Flashback
 * timelapse keyframes: between two of them, (replay ticks crossed) / (output ticks) = playback speed.
 *
 * Everything in Flashback is reached by reflection (it is not a compile-time dependency).
 */
final class RenderDirector {

    private enum State { IDLE, OPENING, EXPORTING }

    private static State state = State.IDLE;
    private static Map<String, String> job = Map.of();
    private static int ticks;
    private static int waitTicks;
    private static Path output;

    private RenderDirector() {
    }

    static boolean isBusy() {
        return state != State.IDLE;
    }

    /** Every client tick, in a world or not. */
    static void tick() {
        ticks++;
        try {
            switch (state) {
                case IDLE -> checkFlag();
                case OPENING -> tickOpening();
                case EXPORTING -> tickExporting();
            }
        } catch (Throwable t) {
            StartBuildMod.LOGGER.error("[StartBuild] render: failed", t);
            state = State.IDLE;
        }
    }

    private static void checkFlag() throws Exception {
        if (ticks % 40 != 0 || ticks < 20 * 15) return;              // let the game finish starting up
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) return;                                  // from the menus only
        Path flag = FabricLoader.getInstance().getConfigDir().resolve("startbuild-render");
        if (!Files.exists(flag)) return;
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : Files.readAllLines(flag)) {
            for (String part : line.trim().split("\\s+")) {
                int eq = part.indexOf('=');
                if (eq > 0) kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        Files.deleteIfExists(flag);
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Path replay = ((Path) fb.getMethod("getReplayFolder").invoke(null)).resolve(kv.getOrDefault("replay", ""));
        if (!Files.isRegularFile(replay)) {
            StartBuildMod.LOGGER.warn("[StartBuild] render: no such replay {}", replay);
            return;
        }
        job = kv;
        StartBuildMod.LOGGER.info("[StartBuild] render: opening {} with {}", replay, kv);
        fb.getMethod("openReplayWorld", Path.class).invoke(null, replay);
        waitTicks = 0;
        state = State.OPENING;
    }

    private static void tickOpening() throws Exception {
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Minecraft mc = Minecraft.getInstance();
        boolean inReplay = (boolean) fb.getMethod("isInReplay").invoke(null);
        Object server = fb.getMethod("getReplayServer").invoke(null);
        if (!inReplay || server == null || mc.player == null || mc.level == null) {
            if (++waitTicks > 20 * 120) {
                StartBuildMod.LOGGER.warn("[StartBuild] render: the replay did not open");
                state = State.IDLE;
            }
            return;
        }
        if (++waitTicks < 20 * 8) return;                             // let the replay settle and load chunks
        int total = (int) server.getClass().getMethod("getTotalReplayTicks").invoke(server);
        startExport(total);
        state = State.EXPORTING;
        waitTicks = 0;
    }

    private static void tickExporting() throws Exception {
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Object running = fb.getField("EXPORT_JOB").get(null);
        if (running != null || ++waitTicks < 40) return;
        boolean ok = output != null && Files.exists(output);
        StartBuildMod.LOGGER.info("[StartBuild] render: finished - {} ({} MB)", output,
                ok ? Files.size(output) / (1024 * 1024) : 0);
        state = State.IDLE;
    }

    private static int num(String key, int def) {
        try {
            return (int) Math.round(Double.parseDouble(job.getOrDefault(key, String.valueOf(def))));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The edit, as keyframes on the current editor scene, then Flashback's exporter. */
    private static void startExport(int totalTicks) throws Exception {
        int end = Math.min(totalTicks, num("end", totalTicks));
        int terraEnd = Math.min(end, num("terraformEnd", end / 4));
        int buildEnd = Math.min(end, Math.max(terraEnd + 20, num("buildEnd", end - 600)));
        int seconds = num("seconds", 120);
        double cx = num("centerX", 0) + 0.5, cz = num("centerZ", 0) + 0.5;
        int baseY = num("baseY", 64), width = num("width", 60), height = num("height", 40);

        // Output ticks (20 per second of video) for each section.
        int out = seconds * 20;
        int hookOut = 80;                               // 4 s
        int revealOut = 320;                            // 16 s
        int terraOut = Math.max(200, (int) ((out - hookOut - revealOut) * 0.3));
        int buildOut = out - hookOut - revealOut - terraOut;
        int hookEnd = Math.min(terraEnd, 400);          // replay ticks shown in the hook (first 20 s)

        // Framing for a 9:16 frame: fit the build's width with room around it.
        double far = Math.max(60, width * 1.55 + 20), mid = Math.max(50, width * 1.35 + 15);
        double near = Math.max(40, width * 1.05 + 10);

        Class<?> esm = Class.forName("com.moulberry.flashback.state.EditorStateManager");
        Object es = esm.getMethod("getCurrent").invoke(null);
        Class<?> trackCls = Class.forName("com.moulberry.flashback.state.KeyframeTrack");
        Class<?> typeCls = Class.forName("com.moulberry.flashback.keyframe.KeyframeType");
        Object orbitType = Class.forName("com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType").getField("INSTANCE").get(null);
        Object lapseType = Class.forName("com.moulberry.flashback.keyframe.types.TimelapseKeyframeType").getField("INSTANCE").get(null);
        Object dayType = Class.forName("com.moulberry.flashback.keyframe.types.TimeOfDayKeyframeType").getField("INSTANCE").get(null);
        Constructor<?> newTrack = trackCls.getConstructor(typeCls);
        Constructor<?> orbitKf = Class.forName("com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe")
                .getConstructor(Vector3d.class, float.class, float.class, float.class);
        Constructor<?> lapseKf = Class.forName("com.moulberry.flashback.keyframe.impl.TimelapseKeyframe").getConstructor(int.class);
        Constructor<?> dayKf = Class.forName("com.moulberry.flashback.keyframe.impl.TimeOfDayKeyframe").getConstructor(int.class);
        Field byTick = trackCls.getField("keyframesByTick");

        Object orbit = newTrack.newInstance(orbitType);
        Object lapse = newTrack.newInstance(lapseType);
        Object day = newTrack.newInstance(dayType);
        @SuppressWarnings("unchecked") TreeMap<Integer, Object> o = (TreeMap<Integer, Object>) byTick.get(orbit);
        @SuppressWarnings("unchecked") TreeMap<Integer, Object> l = (TreeMap<Integer, Object>) byTick.get(lapse);
        @SuppressWarnings("unchecked") TreeMap<Integer, Object> d = (TreeMap<Integer, Object>) byTick.get(day);

        // Camera: one continuous orbit around the site, rising and tightening as the build grows.
        double lowY = baseY + 4, midY = baseY + height * 0.35, topY = baseY + height * 0.45;
        o.put(0, orbitKf.newInstance(new Vector3d(cx, lowY, cz), (float) (far * 1.15), 200f, 42f));        // establishing
        o.put(hookEnd, orbitKf.newInstance(new Vector3d(cx, lowY, cz), (float) far, 215f, 40f));            // push in
        o.put(terraEnd, orbitKf.newInstance(new Vector3d(cx, lowY + 2, cz), (float) far, 290f, 34f));       // over the earthworks
        o.put((terraEnd + buildEnd) / 2, orbitKf.newInstance(new Vector3d(cx, midY, cz), (float) mid, 340f, 24f));
        o.put(buildEnd, orbitKf.newInstance(new Vector3d(cx, topY, cz), (float) mid, 400f, 18f));           // finished
        o.put(end, orbitKf.newInstance(new Vector3d(cx, topY, cz), (float) near, 480f, 10f));               // low reveal sweep

        // Speed: replay ticks -> output ticks.
        l.put(0, lapseKf.newInstance(0));
        l.put(hookEnd, lapseKf.newInstance(hookOut));
        l.put(terraEnd, lapseKf.newInstance(hookOut + terraOut));
        l.put(buildEnd, lapseKf.newInstance(hookOut + terraOut + buildOut));
        l.put(end, lapseKf.newInstance(out));

        // Golden-hour light throughout (late afternoon, before sunset gets dark).
        int time = num("timeOfDay", 11000);
        d.put(0, dayKf.newInstance(time));
        d.put(end, dayKf.newInstance(time));

        long stamp = (long) es.getClass().getMethod("acquireWrite").invoke(es);
        try {
            Object scene = es.getClass().getMethod("getCurrentScene", long.class).invoke(es, stamp);
            @SuppressWarnings("unchecked") java.util.List<Object> tracks =
                    (java.util.List<Object>) scene.getClass().getField("keyframeTracks").get(scene);
            tracks.clear();
            tracks.add(orbit);
            tracks.add(lapse);
            tracks.add(day);
        } finally {
            es.getClass().getMethod("release", long.class).invoke(es, stamp);
        }
        es.getClass().getMethod("markDirty").invoke(es);

        // Export settings, exactly as Flashback's own Export button builds them.
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Path folder = ((Path) fb.getMethod("getReplayFolder").invoke(null)).resolveSibling("exports");
        Files.createDirectories(folder);
        output = folder.resolve(job.getOrDefault("output", "build-short.mp4"));
        Files.deleteIfExists(output);
        Object h264 = enumValue("com.moulberry.flashback.combo_options.VideoCodec", "H264");
        String[] encoders = (String[]) h264.getClass().getMethod("getEncoders").invoke(h264);
        String encoder = encoders.length > 0 ? encoders[0] : "libx264";
        double startDist = far * 1.15, yaw0 = Math.toRadians(200), pitch0 = Math.toRadians(42);
        Vec3 camPos = new Vec3(cx - Math.sin(yaw0) * Math.cos(pitch0) * startDist,
                lowY + Math.sin(pitch0) * startDist, cz + Math.cos(yaw0) * Math.cos(pitch0) * startDist);
        Class<?> settingsCls = Class.forName("com.moulberry.flashback.exporting.ExportSettings");
        Constructor<?> settingsCtor = null;
        for (Constructor<?> c : settingsCls.getConstructors()) if (c.getParameterCount() == 25) settingsCtor = c;
        Object settings = settingsCtor.newInstance(
                "build short", es, camPos, 200f, 42f,
                1080, 1920, 0, end,
                enumValue("com.moulberry.flashback.combo_options.ExportProjection", "PERSPECTIVE"), 1f,
                30.0, false, false,
                enumValue("com.moulberry.flashback.combo_options.VideoContainer", "MP4"), h264, encoder,
                24_000_000, false, false, true, false,
                null, output, "%04d");
        Class<?> utils = Class.forName("com.moulberry.flashback.Utils");
        Field seq = utils.getField("exportSequenceCount");
        seq.setInt(null, seq.getInt(null) + 1);
        Object exportJob = Class.forName("com.moulberry.flashback.exporting.ExportJob").getConstructor(settingsCls).newInstance(settings);
        fb.getField("EXPORT_JOB").set(null, exportJob);
        StartBuildMod.LOGGER.info("[StartBuild] render: exporting {} s of video from {} replay ticks to {} "
                        + "(hook {}->{}, terraforming ->{}, build ->{}, reveal ->{}; encoder {})",
                seconds, end, output, 0, hookEnd, terraEnd, buildEnd, end, encoder);
    }

    private static Object enumValue(String cls, String name) throws Exception {
        Class<?> c = Class.forName(cls);
        Method valueOf = c.getMethod("valueOf", String.class);
        return valueOf.invoke(null, name);
    }
}
