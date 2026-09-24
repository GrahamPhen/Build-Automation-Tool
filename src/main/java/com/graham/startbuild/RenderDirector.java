package com.graham.startbuild;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a take's Flashback replay into finished vertical Shorts, with no clicking: opens the replay, writes
 * each edit as Flashback keyframes (camera shots, timelapse speed ramps, time of day) and runs Flashback's
 * own exporter at 1080x1920 - one video per line of the flag file, back to back.
 *
 * Flag file config/startbuild-render, checked at the title screen; one job per line, key=value:
 *   replay=<file in Flashback's replay folder>  style=tripod|cinematic|orbit  output=<name>.mp4
 *   centerX= centerZ= baseY=   the middle of what was built, and its ground level
 *   width= depth= height=      its real size (frames every shot)
 *   terraformEnd= buildEnd=    replay ticks where the stages ended (from the take's log)
 *   frontYaw=                  the direction the camera looks at the build's "front" (Minecraft yaw)
 *   timeOfDay=                 optional override of the style's light
 *
 * Shots: a FIXED shot is one camera keyframe with HOLD interpolation - perfectly still until the next shot,
 * which is a hard cut. A MOVE is a row of SMOOTH keyframes along an arc or push-in. Speed is set per
 * segment with timelapse keyframes: (replay ticks crossed) / (output ticks) = playback speed.
 */
final class RenderDirector {

    private enum State { IDLE, OPENING, EXPORTING }

    private static State state = State.IDLE;
    private static final Deque<Map<String, String>> jobs = new ArrayDeque<>();
    private static Map<String, String> job = Map.of();
    private static int ticks;
    private static int waitTicks;
    private static int totalTicks;
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
            jobs.clear();
            state = State.IDLE;
        }
    }

    private static void checkFlag() throws Exception {
        if (ticks % 40 != 0 || ticks < 20 * 15) return;              // let the game finish starting up
        if (Minecraft.getInstance().level != null) return;            // from the menus only
        Path flag = FabricLoader.getInstance().getConfigDir().resolve("startbuild-render");
        if (!Files.exists(flag)) return;
        for (String line : Files.readAllLines(flag)) {
            Map<String, String> kv = new LinkedHashMap<>();
            for (String part : line.trim().split("\\s+")) {
                int eq = part.indexOf('=');
                if (eq > 0) kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
            if (kv.containsKey("replay")) jobs.add(kv);
        }
        Files.deleteIfExists(flag);
        if (jobs.isEmpty()) return;
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Path replay = ((Path) fb.getMethod("getReplayFolder").invoke(null)).resolve(jobs.peek().get("replay"));
        if (!Files.isRegularFile(replay)) {
            StartBuildMod.LOGGER.warn("[StartBuild] render: no such replay {}", replay);
            jobs.clear();
            return;
        }
        StartBuildMod.LOGGER.info("[StartBuild] render: opening {} for {} video(s)", replay, jobs.size());
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
                jobs.clear();
                state = State.IDLE;
            }
            return;
        }
        if (++waitTicks < 20 * 8) return;                             // let the replay settle and load chunks
        totalTicks = (int) server.getClass().getMethod("getTotalReplayTicks").invoke(server);
        job = jobs.poll();
        startExport();
        state = State.EXPORTING;
        waitTicks = 0;
    }

    private static void tickExporting() throws Exception {
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        if (fb.getField("EXPORT_JOB").get(null) != null || ++waitTicks < 40) return;
        StartBuildMod.LOGGER.info("[StartBuild] render: finished - {} ({} MB)", output,
                output != null && Files.exists(output) ? Files.size(output) / (1024 * 1024) : 0);
        if (!jobs.isEmpty()) {                                         // next video from the same replay
            job = jobs.poll();
            startExport();
            waitTicks = 0;
            return;
        }
        state = State.IDLE;
    }

    // ================================================================== the edit

    private static double num(String key, double def) {
        try {
            return Double.parseDouble(job.getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // Keyframe collections for the edit being built.
    private static TreeMap<Integer, Object> cam, lapse, day;
    private static int outTicks;
    private static Constructor<?> camKf, lapseKf, dayKf;
    private static Object hold, smooth;

    /** Output seconds for replay ticks [a, b]: one timelapse segment. */
    private static void speed(int a, int b, double seconds) throws Exception {
        lapse.put(a, lapseKf.newInstance(outTicks));
        outTicks += (int) Math.round(seconds * 20);
        lapse.put(b, lapseKf.newInstance(outTicks));
    }

    /** Camera position looking at `target` with this yaw/pitch (Minecraft convention), from `dist` away. */
    private static Vector3d eye(Vector3d target, double yawDeg, double pitchDeg, double dist) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        return new Vector3d(target.x - fx * dist, target.y - fy * dist, target.z - fz * dist);
    }

    /** A still shot from replay tick `a` until the next shot (a hard cut). */
    private static void fixed(int a, Vector3d target, double yaw, double pitch, double dist) throws Exception {
        cam.put(a, camKf.newInstance(eye(target, yaw, pitch, dist), (float) yaw, (float) pitch, 0f, hold));
    }

    /** A smooth move over replay ticks [a, b): arc (yaw), tilt, push-in and rise, ending on a still frame. */
    private static void move(int a, int b, Vector3d t0, Vector3d t1, double yaw0, double yaw1, double p0, double p1,
                             double d0, double d1) throws Exception {
        int steps = 10;
        for (int k = 0; k <= steps; k++) {
            double s = k / (double) steps;
            int tick = a + (int) Math.round((b - 1 - a) * s);
            Vector3d t = new Vector3d(t0.x + (t1.x - t0.x) * s, t0.y + (t1.y - t0.y) * s, t0.z + (t1.z - t0.z) * s);
            double yaw = yaw0 + (yaw1 - yaw0) * s, pitch = p0 + (p1 - p0) * s, dist = d0 + (d1 - d0) * s;
            cam.put(tick, camKf.newInstance(eye(t, yaw, pitch, dist), (float) yaw, (float) pitch, 0f, k == steps ? hold : smooth));
        }
    }

    private static void startExport() throws Exception {
        String style = job.getOrDefault("style", "orbit");
        int end = totalTicks;
        int T = (int) Math.min(end, num("terraformEnd", end / 4.0));
        int B = (int) Math.min(end, Math.max(T + 20, num("buildEnd", end - 600)));
        int D = B - T, H = Math.min(T, 300);
        double cx = num("centerX", 0) + 0.5, cz = num("centerZ", 0) + 0.5, base = num("baseY", 64);
        double w = num("width", 60), dp = num("depth", 60), h = num("height", 40);
        double front = num("frontYaw", 160);

        // 9:16 frame, vertical FOV 70: half-width tan = tan(35) * 9/16.
        double tanV = Math.tan(Math.toRadians(35)), tanH = tanV * 9 / 16;
        double fitW = (Math.max(w, dp) / 2 + 6) / tanH, fitH = (h * 0.62) / tanV;
        double frontDist = Math.max(fitW * 0.85, fitH) * 1.08;        // a front view: fit the height, most width
        Vector3d mid = new Vector3d(cx, base + h * 0.42, cz), low = new Vector3d(cx, base + h * 0.3, cz);
        Vector3d ground = new Vector3d(cx, base, cz);
        double topHeight = (Math.max(w, dp) + 14) / (2 * tanH);       // straight down: the whole site across the frame

        Class<?> trackCls = Class.forName("com.moulberry.flashback.state.KeyframeTrack");
        Class<?> typeCls = Class.forName("com.moulberry.flashback.keyframe.KeyframeType");
        Class<?> interp = Class.forName("com.moulberry.flashback.keyframe.interpolation.InterpolationType");
        hold = interp.getMethod("valueOf", String.class).invoke(null, "HOLD");
        smooth = interp.getMethod("valueOf", String.class).invoke(null, "SMOOTH");
        camKf = Class.forName("com.moulberry.flashback.keyframe.impl.CameraKeyframe")
                .getConstructor(Vector3d.class, float.class, float.class, float.class, interp);
        lapseKf = Class.forName("com.moulberry.flashback.keyframe.impl.TimelapseKeyframe").getConstructor(int.class);
        dayKf = Class.forName("com.moulberry.flashback.keyframe.impl.TimeOfDayKeyframe").getConstructor(int.class);
        Constructor<?> newTrack = trackCls.getConstructor(typeCls);
        Field byTick = trackCls.getField("keyframesByTick");
        Object camTrack = newTrack.newInstance(Class.forName("com.moulberry.flashback.keyframe.types.CameraKeyframeType").getField("INSTANCE").get(null));
        Object lapseTrack = newTrack.newInstance(Class.forName("com.moulberry.flashback.keyframe.types.TimelapseKeyframeType").getField("INSTANCE").get(null));
        Object dayTrack = newTrack.newInstance(Class.forName("com.moulberry.flashback.keyframe.types.TimeOfDayKeyframeType").getField("INSTANCE").get(null));
        cam = cast(byTick.get(camTrack));
        lapse = cast(byTick.get(lapseTrack));
        day = cast(byTick.get(dayTrack));
        outTicks = 0;
        int time;

        switch (style) {
            case "tripod" -> {
                // Fixed cameras, hard cuts: straight down while the ground and floor plan go in, one front
                // three-quarter view as it grows, a flash back to the top view, then hold on the finished build.
                time = 9500;                                            // warm afternoon (sunset + snow fog = orange haze)
                int f1 = T + (int) (D * 0.15), f2 = T + (int) (D * 0.72), f3 = T + (int) (D * 0.78);
                Vector3d top = new Vector3d(cx, base + topHeight, cz);
                cam.put(0, camKf.newInstance(top, 180f, 90f, 0f, hold));
                speed(0, H, 2);
                speed(H, T, 7);
                speed(T, f1, 6);
                fixed(f1, mid, front - 35, 14, frontDist);
                speed(f1, f2, 13);
                cam.put(f2, camKf.newInstance(top, 180f, 90f, 0f, hold));
                speed(f2, f3, 2.5);
                fixed(f3, mid, front - 35, 14, frontDist);
                speed(f3, B, 5);
                move(B, end, mid, mid, front - 35, front - 30, 14, 10, frontDist, frontDist * 0.88);
                speed(B, end, 7);
            }
            case "cinematic" -> {
                // A few still angles cut together through the timelapse, then a slow low sweep past the finish.
                time = 6500;                                             // clear daylight
                int c1 = T + (int) (D * 0.35), c2 = T + (int) (D * 0.7);
                fixed(0, low, front - 60, 22, frontDist * 1.25);
                speed(0, T, 5);
                fixed(T, mid, front + 70, 10, frontDist);
                speed(T, c1, 7);
                fixed(c1, mid, front, 24, frontDist * 1.05);
                speed(c1, c2, 7);
                fixed(c2, mid, front - 40, 6, frontDist * 0.95);
                speed(c2, B, 6);
                move(B, end, new Vector3d(cx, base + h * 0.25, cz), new Vector3d(cx, base + h * 0.4, cz),
                        front - 55, front + 35, -4, 6, frontDist * 0.95, frontDist * 1.05);
                speed(B, end, 10);
            }
            default -> {
                // One continuous orbit, framed on the build, rising and tightening as it grows.
                time = 9000;                                             // bright morning
                move(0, T, ground, low, front - 140, front - 60, 38, 30, frontDist * 1.45, frontDist * 1.2);
                speed(0, H, 2);
                speed(H, T, 10);
                move(T, B, low, mid, front - 60, front + 120, 30, 16, frontDist * 1.2, frontDist);
                speed(T, B, 38);
                move(B, end, mid, mid, front + 120, front + 200, 14, 6, frontDist, frontDist * 0.97);
                speed(B, end, 10);
            }
        }
        time = (int) num("timeOfDay", time);
        day.put(0, dayKf.newInstance(time));
        day.put(end, dayKf.newInstance(time));

        Class<?> esm = Class.forName("com.moulberry.flashback.state.EditorStateManager");
        Object es = esm.getMethod("getCurrent").invoke(null);
        long stamp = (long) es.getClass().getMethod("acquireWrite").invoke(es);
        try {
            Object scene = es.getClass().getMethod("getCurrentScene", long.class).invoke(es, stamp);
            List<Object> tracks = cast(scene.getClass().getField("keyframeTracks").get(scene));
            tracks.clear();
            tracks.add(camTrack);
            tracks.add(lapseTrack);
            tracks.add(dayTrack);
        } finally {
            es.getClass().getMethod("release", long.class).invoke(es, stamp);
        }
        es.getClass().getMethod("markDirty").invoke(es);

        // Export settings, exactly as Flashback's own Export button builds them.
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Path folder = ((Path) fb.getMethod("getReplayFolder").invoke(null)).resolveSibling("exports");
        Files.createDirectories(folder);
        output = folder.resolve(job.getOrDefault("output", "build-short-" + style + ".mp4"));
        Files.deleteIfExists(output);
        Object h264 = enumValue("com.moulberry.flashback.combo_options.VideoCodec", "H264");
        String[] encoders = (String[]) h264.getClass().getMethod("getEncoders").invoke(h264);
        Map.Entry<Integer, Object> first = cam.firstEntry();
        Vector3d p0 = (Vector3d) first.getValue().getClass().getField("position").get(first.getValue());
        float yaw0 = first.getValue().getClass().getField("yaw").getFloat(first.getValue());
        float pitch0 = first.getValue().getClass().getField("pitch").getFloat(first.getValue());
        Class<?> settingsCls = Class.forName("com.moulberry.flashback.exporting.ExportSettings");
        Constructor<?> settingsCtor = null;
        for (Constructor<?> c : settingsCls.getConstructors()) if (c.getParameterCount() == 25) settingsCtor = c;
        Object settings = settingsCtor.newInstance(
                "build short " + style, es, new Vec3(p0.x, p0.y, p0.z), yaw0, pitch0,
                1080, 1920, 0, end,
                enumValue("com.moulberry.flashback.combo_options.ExportProjection", "PERSPECTIVE"), 1f,
                30.0, false, false,
                enumValue("com.moulberry.flashback.combo_options.VideoContainer", "MP4"), h264,
                encoders.length > 0 ? encoders[0] : "libx264",
                24_000_000, false, false, true, false,
                null, output, "%04d");
        Class<?> utils = Class.forName("com.moulberry.flashback.Utils");
        Field seq = utils.getField("exportSequenceCount");
        seq.setInt(null, seq.getInt(null) + 1);
        fb.getField("EXPORT_JOB").set(null,
                Class.forName("com.moulberry.flashback.exporting.ExportJob").getConstructor(settingsCls).newInstance(settings));
        StartBuildMod.LOGGER.info("[StartBuild] render: exporting '{}' ({} s, {} camera keyframes, time {}) to {}",
                style, outTicks / 20, cam.size(), time, output);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }

    private static Object enumValue(String cls, String name) throws Exception {
        return Class.forName(cls).getMethod("valueOf", String.class).invoke(null, name);
    }
}
