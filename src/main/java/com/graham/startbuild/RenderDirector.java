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

    private enum State { IDLE, OPENING, PROBING, EXPORTING }

    /** Where the builder was at the close-up moments (replay tick -> feet position), found by scrubbing. */
    private static final Map<Integer, Vec3> builderAt = new TreeMap<>();
    private static final Deque<Integer> probes = new ArrayDeque<>();
    private static Map<net.minecraft.sounds.SoundSource, Double> savedVolumes;
    /** Fractions of the build period where the edits cut to a close shot of the character. */
    private static final double[] CLOSEUPS = {0.2, 0.4, 0.55};
    /**
     * Replay ticks each close shot covers: 1.5 s of the take, shown at real speed - the builder flies up to a
     * block a tick between placements, so a longer window loses them from the frame.
     */
    private static final int CLOSEUP_TICKS = 30;

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
                case PROBING -> tickProbing();
                case EXPORTING -> tickExporting();
            }
        } catch (Throwable t) {
            StartBuildMod.LOGGER.error("[StartBuild] render: failed", t);
            jobs.clear();
            muteGameSounds(false);
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
        // Scrub to each close-up moment first, to see where the builder actually is then.
        job = jobs.peek();
        int T = (int) Math.min(totalTicks, num("terraformEnd", totalTicks / 4.0));
        int B = (int) Math.min(totalTicks, num("buildEnd", totalTicks - 600));
        builderAt.clear();
        closeupEye.clear();
        probes.clear();
        for (double f : CLOSEUPS) probes.add(T + (int) ((B - T) * f));
        waitTicks = 0;
        state = State.PROBING;
    }

    /** Seek to each probe tick, let the replay catch up, and note where the recorded builder stands. */
    private static void tickProbing() throws Exception {
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Object server = fb.getMethod("getReplayServer").invoke(null);
        if (probes.isEmpty()) {
            StartBuildMod.LOGGER.info("[StartBuild] render: builder positions {}", builderAt);
            muteGameSounds(true);
            job = jobs.poll();
            startExport();
            state = State.EXPORTING;
            waitTicks = 0;
            return;
        }
        int t = probes.peek();
        if (waitTicks == 0) server.getClass().getMethod("goToReplayTick", int.class).invoke(server, t);
        if (++waitTicks < 40) return;
        Minecraft mc = Minecraft.getInstance();
        for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
            if (p == mc.player) continue;                             // the replay viewer
            builderId = p.getUUID();
            Vec3 shot = clearShotOf(mc, p);
            if (shot != null) {
                builderAt.put(t, p.position());
                closeupEye.put(t, shot);
            }
            break;
        }
        probes.poll();
        waitTicks = 0;
    }

    /** The recorded builder (its name tag is hidden in the export). */
    private static java.util.UUID builderId;

    /** Camera spots for close shots, found while scrubbing: replay tick -> camera position. */
    private static final Map<Integer, Vec3> closeupEye = new TreeMap<>();

    /**
     * A camera spot a few blocks from the builder with a clear line of sight to their head - tried round
     * them (outward from the build first), at several distances and heights, in the replay world as it is at
     * that moment. Null if every spot is blocked (then that close shot is skipped).
     */
    private static Vec3 clearShotOf(Minecraft mc, net.minecraft.world.entity.player.Player p) {
        double cx = num("centerX", 0) + 0.5, cz = num("centerZ", 0) + 0.5;
        Vec3 head = p.getEyePosition();
        double out = Math.atan2(p.getZ() - cz, p.getX() - cx);        // away from the middle of the build
        for (double dist : new double[]{7, 9, 12}) {
            for (double up : new double[]{3, 5, 1.5}) {
                for (int k = 0; k < 8; k++) {
                    double ang = out + (k % 2 == 0 ? 1 : -1) * Math.PI / 4 * ((k + 1) / 2);
                    Vec3 eye = new Vec3(head.x + Math.cos(ang) * dist, head.y + up, head.z + Math.sin(ang) * dist);
                    if (!mc.level.getBlockState(net.minecraft.core.BlockPos.containing(eye)).isAir()) continue;
                    net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, head,
                            net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                    if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return eye;
                }
            }
        }
        return null;
    }

    /** Only the soundtrack in the video: game sounds (at 80x speed, a racket) are muted for the render. */
    private static void muteGameSounds(boolean mute) {
        net.minecraft.client.Options o = Minecraft.getInstance().options;
        if (mute) {
            savedVolumes = new java.util.EnumMap<>(net.minecraft.sounds.SoundSource.class);
            for (net.minecraft.sounds.SoundSource s : net.minecraft.sounds.SoundSource.values()) {
                if (s == net.minecraft.sounds.SoundSource.MASTER) continue;
                savedVolumes.put(s, o.getSoundSourceOptionInstance(s).get());
                o.getSoundSourceOptionInstance(s).set(0.0);
            }
        } else if (savedVolumes != null) {
            savedVolumes.forEach((s, v) -> o.getSoundSourceOptionInstance(s).set(v));
            savedVolumes = null;
        }
    }

    /**
     * A close still shot of the character at work: from just outside the build, a few blocks behind and
     * above the builder, looking at them. `a` is where the shot starts (a hard cut in and out).
     */
    private static boolean closeup(int a, double cx, double cz, double frontYaw) throws Exception {
        Vec3 p = null, spot = null;
        for (Map.Entry<Integer, Vec3> e : builderAt.entrySet()) {
            if (Math.abs(e.getKey() - a) < 40) {
                p = e.getValue();
                spot = closeupEye.get(e.getKey());
            }
        }
        if (p == null || spot == null) return false;                  // no clear view of the character then
        Vector3d eyePos = new Vector3d(spot.x, spot.y, spot.z);
        double tx = p.x - eyePos.x, ty = p.y + 1.2 - eyePos.y, tz = p.z - eyePos.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-tx, tz));
        float pitch = (float) -Math.toDegrees(Math.atan2(ty, Math.sqrt(tx * tx + tz * tz)));
        cam.put(a, camKf.newInstance(eyePos, yaw, pitch, 0f, hold));
        return true;
    }

    private static Path finalOutput, pendingMusic;
    private static String encoderUsed = "libx264";
    private static Thread mixing;

    private static void tickExporting() throws Exception {
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        if (fb.getField("EXPORT_JOB").get(null) != null || ++waitTicks < 40) return;
        if (pendingMusic != null && mixing == null) {
            // Lay the music under the silent render, off the game thread.
            Path video = output, music = pendingMusic, out = finalOutput;
            String enc = encoderUsed;
            StartBuildMod.LOGGER.info("[StartBuild] render: adding music {} to {}", music.getFileName(), out.getFileName());
            mixing = new Thread(() -> {
                long t0 = System.currentTimeMillis();
                String err = MusicMixer.mix(video, music, out, enc, 16_000_000);
                StartBuildMod.LOGGER.info("[StartBuild] render: music {} ({} s)", err == null ? "added" : "FAILED - " + err,
                        (System.currentTimeMillis() - t0) / 1000);
            }, "startbuild-music");
            mixing.setDaemon(true);
            mixing.start();
            return;
        }
        if (mixing != null) {
            if (mixing.isAlive()) return;
            mixing = null;
            pendingMusic = null;
            output = finalOutput;
        }
        StartBuildMod.LOGGER.info("[StartBuild] render: finished - {} ({} MB)", output,
                output != null && Files.exists(output) ? Files.size(output) / (1024 * 1024) : 0);
        if (!jobs.isEmpty()) {                                         // next video from the same replay
            job = jobs.poll();
            startExport();
            waitTicks = 0;
            return;
        }
        muteGameSounds(false);
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
                int p1 = T + (int) (D * 0.2), p2 = T + (int) (D * 0.55);
                fixed(0, low, front - 60, 22, frontDist * 1.25);
                speed(0, T, 5);
                fixed(T, mid, front + 70, 10, frontDist);
                if (closeup(p1, cx, cz, front)) {                        // cut in close to the character at work
                    speed(T, p1, 3.5);
                    speed(p1, p1 + CLOSEUP_TICKS, 1.5);
                    fixed(p1 + CLOSEUP_TICKS, mid, front + 70, 10, frontDist);
                    speed(p1 + CLOSEUP_TICKS, c1, 3.5);
                } else {
                    speed(T, c1, 7);
                }
                fixed(c1, mid, front, 24, frontDist * 1.05);
                if (closeup(p2, cx, cz, front)) {
                    speed(c1, p2, 3.5);
                    speed(p2, p2 + CLOSEUP_TICKS, 1.5);
                    fixed(p2 + CLOSEUP_TICKS, mid, front, 24, frontDist * 1.05);
                    speed(p2 + CLOSEUP_TICKS, c2, 3.5);
                } else {
                    speed(c1, c2, 7);
                }
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
                int p = T + (int) (D * 0.4);
                Vector3d between = new Vector3d(cx, (low.y + mid.y) / 2 + (mid.y - low.y) * -0.1, cz);
                if (closeup(p, cx, cz, front) && p > T + 40) {
                    // Circle up to 40% of the build, cut in to the character for a beat, then circle on.
                    move(T, p, low, between, front - 60, front + 12, 30, 24, frontDist * 1.2, frontDist * 1.12);
                    speed(T, p, 15);
                    closeup(p, cx, cz, front);
                    speed(p, p + CLOSEUP_TICKS, 1.5);
                    move(p + CLOSEUP_TICKS, B, between, mid, front + 12, front + 120, 24, 16, frontDist * 1.12, frontDist);
                    speed(p + CLOSEUP_TICKS, B, 21);
                } else {
                    move(T, B, low, mid, front - 60, front + 120, 30, 16, frontDist * 1.2, frontDist);
                    speed(T, B, 38);
                }
                move(B, end, mid, mid, front + 120, front + 200, 14, 6, frontDist, frontDist * 0.97);
                speed(B, end, 10);
            }
        }
        time = (int) num("timeOfDay", time);
        day.put(0, dayKf.newInstance(time));
        day.put(end, dayKf.newInstance(time));

        // Soundtrack: an audio file (config/startbuild-music/<music>.ogg), laid under the SILENT export by
        // MusicMixer at normal speed. (Flashback's own audio track plays at the replay's speed - under an
        // 80x timelapse that was a garbled blip.)
        Path music = null;
        if (job.containsKey("music")) {
            music = FabricLoader.getInstance().getConfigDir().resolve("startbuild-music").resolve(job.get("music") + ".ogg");
            if (!Files.isRegularFile(music)) {
                StartBuildMod.LOGGER.warn("[StartBuild] render: no music file {}", music);
                music = null;
            }
        }

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
        if (builderId != null) {                                      // no floating name tag over the character
            java.util.Set<java.util.UUID> hidden = cast(es.getClass().getField("hideNametags").get(es));
            hidden.add(builderId);
        }
        es.getClass().getMethod("markDirty").invoke(es);

        // Export settings, exactly as Flashback's own Export button builds them.
        Class<?> fb = Class.forName("com.moulberry.flashback.Flashback");
        Path folder = ((Path) fb.getMethod("getReplayFolder").invoke(null)).resolveSibling("exports");
        Files.createDirectories(folder);
        finalOutput = folder.resolve(job.getOrDefault("output", "build-short-" + style + ".mp4"));
        pendingMusic = music;
        output = music == null ? finalOutput
                : folder.resolve(finalOutput.getFileName().toString().replace(".mp4", "-silent.mp4"));
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
                encoderUsed = encoders.length > 0 ? encoders[0] : "libx264",
                24_000_000, false, false, true, false,
                null, output, "%04d");
        Class<?> utils = Class.forName("com.moulberry.flashback.Utils");
        Field seq = utils.getField("exportSequenceCount");
        seq.setInt(null, seq.getInt(null) + 1);
        fb.getField("EXPORT_JOB").set(null,
                Class.forName("com.moulberry.flashback.exporting.ExportJob").getConstructor(settingsCls).newInstance(settings));
        StartBuildMod.LOGGER.info("[StartBuild] render: exporting '{}' ({} s, {} camera keyframes, time {}, music {}) to {}",
                style, outTicks / 20, cam.size(), time, music == null ? "none" : music.getFileName(), output);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }

    private static Object enumValue(String cls, String name) throws Exception {
        return Class.forName(cls).getMethod("valueOf", String.class).invoke(null, name);
    }
}
