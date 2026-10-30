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

    private static net.minecraft.client.CloudStatus savedClouds;

    /**
     * Only the soundtrack in the video: game sounds (at 80x speed, a racket) are muted for the render. Clouds are
     * off too - a wide opening shot of a big build sits up at cloud height, and clouds drifting through at
     * timelapse speed hid the build.
     */
    private static void muteGameSounds(boolean mute) {
        net.minecraft.client.Options o = Minecraft.getInstance().options;
        if (mute && savedClouds == null) {
            savedClouds = o.cloudStatus().get();
            o.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        } else if (!mute && savedClouds != null) {
            o.cloudStatus().set(savedClouds);
            savedClouds = null;
        }
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
    private static double musicStart;

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
                String err = MusicMixer.mix(video, music, out, enc, 32_000_000, musicStart);
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

    /**
     * Output seconds for replay ticks [a, b] spread by how much is BUILT in each stretch, not by clock time: the
     * "progress" key ("tick:placed,..." from the take's progress log lines) gives the cumulative blocks placed.
     * Quiet stretches (flying about, supports) go by fast, stretches where the shape grows get the time, and
     * the last 12% of blocks are slowed down into the finish. Rates are smoothed so the speed never jumps.
     * Without progress data: an even speed, as before.
     */
    private static void speedProgress(int a, int b, double seconds) throws Exception {
        TreeMap<Integer, Double> prog = new TreeMap<>();
        for (String s : job.getOrDefault("progress", "").split(",")) {
            String[] kv = s.split(":");
            if (kv.length == 2) try { prog.put(Integer.parseInt(kv[0].trim()) - tickShift, Double.parseDouble(kv[1].trim())); } catch (NumberFormatException ignored) { }
        }
        // At most one segment per half second of output: a progress line every minute gave the cinematic cut's
        // 3.5 s stretches ~30 segments, some rounded to 0 output ticks - and Flashback's timelapse froze there.
        List<Integer> inner = new java.util.ArrayList<>();
        for (int t : prog.keySet()) if (t > a + 40 && t < b - 40) inner.add(t);
        int maxSegs = Math.max(1, (int) Math.floor(seconds / 0.5));
        int every = Math.max(1, (int) Math.ceil((inner.size() + 1) / (double) maxSegs));
        List<Integer> cuts = new java.util.ArrayList<>();
        cuts.add(a);
        for (int i = every - 1; i < inner.size(); i += every) cuts.add(inner.get(i));
        if (cuts.get(cuts.size() - 1) >= b - 40) cuts.remove(cuts.size() - 1);
        cuts.add(b);
        if (prog.size() < 3 || cuts.size() < 3) { speed(a, b, seconds); return; }
        java.util.function.IntToDoubleFunction placed = t -> {
            Map.Entry<Integer, Double> lo = prog.floorEntry(t), hi = prog.ceilingEntry(t);
            if (lo == null) return hi.getValue();
            if (hi == null || hi.getKey().equals(lo.getKey())) return lo.getValue();
            return lo.getValue() + (hi.getValue() - lo.getValue()) * (t - lo.getKey()) / (double) (hi.getKey() - lo.getKey());
        };
        double total = placed.applyAsDouble(prog.lastKey()), start = placed.applyAsDouble(a);
        int n = cuts.size() - 1;
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double d = Math.max(0, placed.applyAsDouble(cuts.get(i + 1)) - placed.applyAsDouble(cuts.get(i)));
            w[i] = d;
            sum += d;
        }
        double floor = Math.max(1, sum / n * 0.2);                    // a quiet stretch still gets a little time
        for (int i = 0; i < n; i++) {
            w[i] = Math.max(w[i], floor);
            if (total > start && placed.applyAsDouble(cuts.get(i + 1)) >= start + (total - start) * 0.88) w[i] *= 1.6;
        }
        // seconds per replay tick, smoothed over neighbours, then scaled back to the segment's total
        double[] rate = new double[n];
        double wsum = 0;
        for (double x : w) wsum += x;
        for (int i = 0; i < n; i++) rate[i] = seconds * w[i] / wsum / Math.max(1, cuts.get(i + 1) - cuts.get(i));
        double[] sm = new double[n];
        for (int i = 0; i < n; i++) {
            double r = rate[i] * 2, c = 2;
            if (i > 0) { r += rate[i - 1]; c++; }
            if (i < n - 1) { r += rate[i + 1]; c++; }
            sm[i] = r / c;
        }
        double out = 0;
        for (int i = 0; i < n; i++) out += sm[i] * (cuts.get(i + 1) - cuts.get(i));
        for (int i = 0; i < n; i++) speed(cuts.get(i), cuts.get(i + 1), sm[i] * (cuts.get(i + 1) - cuts.get(i)) * seconds / out);
    }

    private static int duskFrom = -1;
    private static int tickShift;                                     // log-clock ticks minus replay ticks (see startExport)

    // ---- Clear view of the build, for EVERY camera position (still shots and every step of every move) ----
    // A hill, tree or cliff between the camera and the build must never hide it: the Grand Ocean Liner's ending
    // (and so the Short's opening frame) showed a hillside. Each camera position is tested against nine sight
    // lines to the build - the corners of its box at the bottom and half way up, and its middle - plus the
    // camera itself must not sit inside a block. If more than one line is blocked the camera is raised (5
    // degrees a try) and brought in (6% a try) until it is clear.
    private static double gCx, gCz, gW, gDp, gBase, gH;
    private static boolean guardOn;
    private static int guardFixes;

    private static int blockedLines(Minecraft mc, Vector3d e) {
        BlockPosHolder.set(e);
        if (!mc.level.getBlockState(BlockPosHolder.pos).getCollisionShape(mc.level, BlockPosHolder.pos).isEmpty()
                || !mc.level.getFluidState(BlockPosHolder.pos).isEmpty()) return 99;   // inside a block, or under water
        // 25 sight lines: 20 round the build's bottom edge (where it meets the ground - nine points let a hill
        // crest hide the liner's hull between the corners), the four corners half way up, and the middle.
        int blocked = 0;
        for (int i = 0; i < 5; i++) {
            double f = -1 + i * 0.5;
            for (int s = -1; s <= 1; s += 2) {
                if (terrainBetween(mc, e, new double[]{gCx + f * (gW / 2 - 0.5), gBase + 1.5, gCz + s * (gDp / 2 - 0.5)}, gCx, gCz, gW, gDp, gBase, gH)) blocked++;
                if (terrainBetween(mc, e, new double[]{gCx + s * (gW / 2 - 0.5), gBase + 1.5, gCz + f * (gDp / 2 - 0.5)}, gCx, gCz, gW, gDp, gBase, gH)) blocked++;
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
            if (terrainBetween(mc, e, new double[]{gCx + sx * Math.max(0, gW / 2 - 1), gBase + gH * 0.5, gCz + sz * Math.max(0, gDp / 2 - 1)},
                    gCx, gCz, gW, gDp, gBase, gH)) blocked++;
        }
        if (terrainBetween(mc, e, new double[]{gCx, gBase + gH * 0.42, gCz}, gCx, gCz, gW, gDp, gBase, gH)) blocked++;
        return blocked;
    }

    private static final class BlockPosHolder {
        static net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.ZERO;
        static void set(Vector3d e) { pos = net.minecraft.core.BlockPos.containing(e.x, e.y, e.z); }
    }

    private static final int MAX_LIFT = 14;                           // pitch +70 (capped at 80), distance x0.42
    private static int guardBlocked;

    /** How many 5-degree / 6% steps this camera position needs before it sees the build (0 = already clear). */
    private static int liftSteps(Vector3d target, double yaw, double pitch, double dist) {
        Minecraft mc = Minecraft.getInstance();
        if (!guardOn || mc.level == null) return 0;
        for (int k = 0; k <= MAX_LIFT; k++) {
            double p = Math.min(80, pitch + 5 * k), d = dist * Math.pow(0.94, k);
            // clear = at most one line blocked; once a lift was needed, one step more so the view never grazes a crest
            if (blockedLines(mc, eye(target, yaw, p, d)) <= 1) return k == 0 ? 0 : Math.min(MAX_LIFT, k + 1);
        }
        // Still blocked from almost straight above: say so loudly (it used to give up silently at 8 steps).
        guardBlocked++;
        StartBuildMod.LOGGER.warn("[StartBuild] render: WARN view still blocked at yaw {} after {} lifts", Math.round(yaw), MAX_LIFT);
        return MAX_LIFT;
    }

    /** True if a block outside the build's own box (grown by 2) sits on the line from the camera to `to`. */
    private static boolean terrainBetween(Minecraft mc, Vector3d e, double[] to, double cx, double cz, double w, double dp,
                                          double base, double h) {
        Vec3 from = new Vec3(e.x, e.y, e.z), end = new Vec3(to[0], to[1], to[2]);
        net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, end,
                net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return false;
        var p = hit.getBlockPos();
        boolean inBuild = Math.abs(p.getX() + 0.5 - cx) <= w / 2 + 3 && Math.abs(p.getZ() + 0.5 - cz) <= dp / 2 + 3
                && p.getY() >= base - 1 && p.getY() <= base + h + 2;
        return !inBuild;
    }

    /** Output seconds for replay ticks [a, b]: one timelapse segment. */
    private static void speed(int a, int b, double seconds) throws Exception {
        if (b <= a) return;                                           // an empty stretch adds no keyframes
        lapse.put(a, lapseKf.newInstance(outTicks));
        outTicks += Math.max(1, (int) Math.round(seconds * 20));      // never a zero-length segment (froze the timelapse)
        lapse.put(b, lapseKf.newInstance(outTicks));
    }

    /** Camera position looking at `target` with this yaw/pitch (Minecraft convention), from `dist` away. */
    private static Vector3d eye(Vector3d target, double yawDeg, double pitchDeg, double dist) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        return new Vector3d(target.x - fx * dist, target.y - fy * dist, target.z - fz * dist);
    }

    /** A still shot from replay tick `a` until the next shot (a hard cut) - lifted clear of anything in the way. */
    private static void fixed(int a, Vector3d target, double yaw, double pitch, double dist) throws Exception {
        lastMove = null;                                              // a hard cut: nothing to join to
        int k = liftSteps(target, yaw, pitch, dist);
        if (k > 0) { guardFixes++; StartBuildMod.LOGGER.info("[StartBuild] render: still shot at {} lifted {} step(s) for a clear view", a, k); }
        double p = Math.min(80, pitch + 5 * k), d = dist * Math.pow(0.94, k);
        cam.put(a, camKf.newInstance(eye(target, yaw, p, d), (float) yaw, (float) p, 0f, hold));
    }

    /**
     * A smooth move over replay ticks [a, b): arc (yaw), tilt, push-in and rise, ending on a still frame. Every
     * keyframe - and the half-way point to the next, where the camera also passes - is checked for a clear view;
     * a lift spreads to the neighbouring keyframes so the camera eases up and down instead of jumping.
     */
    private static void move(int a, int b, Vector3d t0, Vector3d t1, double yaw0, double yaw1, double p0, double p1,
                             double d0, double d1) throws Exception {
        int steps = 20;
        int[] need = new int[steps + 1];
        for (int k = 0; k <= steps; k++) {
            for (double half : new double[]{0, 0.5}) {
                double s = Math.min(1, (k + half) / steps);
                Vector3d t = new Vector3d(t0.x + (t1.x - t0.x) * s, t0.y + (t1.y - t0.y) * s, t0.z + (t1.z - t0.z) * s);
                int l = liftSteps(t, yaw0 + (yaw1 - yaw0) * s, p0 + (p1 - p0) * s, d0 + (d1 - d0) * s);
                need[k] = Math.max(need[k], l);
                if (half > 0 && k < steps) need[k + 1] = Math.max(need[k + 1], l);
            }
        }
        int[] lift = new int[steps + 1];
        int fixedSteps = 0;
        for (int k = 0; k <= steps; k++) {
            int m = need[k];
            for (int j = Math.max(0, k - 2); j <= Math.min(steps, k + 2); j++) m = Math.max(m, need[j] - Math.abs(j - k));   // ramp in/out
            lift[k] = m;
            if (m > 0) fixedSteps++;
        }
        if (fixedSteps > 0) { guardFixes++; StartBuildMod.LOGGER.info("[StartBuild] render: move {}-{}: {} of {} keyframes lifted for a clear view", a, b, fixedSteps, steps + 1); }
        Move m = new Move(a, b, t0, t1, yaw0, yaw1, p0, p1, d0, d1, lift);
        // Where this move carries straight on from the last one, both sides of the join get the same lift (each
        // move used to work its lifts out alone, so a join at a hill could pop by several steps).
        if (lastMove != null && lastMove.b == a) {
            int join = Math.max(lastMove.lift[steps], lift[0]);
            for (int j = 0; j <= steps; j++) {
                lastMove.lift[steps - j] = Math.max(lastMove.lift[steps - j], join - j);
                lift[j] = Math.max(lift[j], join - j);
            }
            putMove(lastMove);
        }
        putMove(m);
        lastMove = m;
    }

    private record Move(int a, int b, Vector3d t0, Vector3d t1, double yaw0, double yaw1, double p0, double p1,
                        double d0, double d1, int[] lift) { }

    private static Move lastMove;
    // output seconds where the build runs and where the shots cut - written beside the video for finish-short
    // (building sounds under the music, a whoosh on each cut)
    private static double buildOut0 = -1, buildOut1 = -1;
    private static final List<Double> cutTimes = new java.util.ArrayList<>();

    private static void putMove(Move m) throws Exception {
        int steps = m.lift.length - 1;
        for (int k = 0; k <= steps; k++) {
            double s = k / (double) steps;
            int tick = m.a + (int) Math.round((m.b - 1 - m.a) * s);
            Vector3d t = new Vector3d(m.t0.x + (m.t1.x - m.t0.x) * s, m.t0.y + (m.t1.y - m.t0.y) * s, m.t0.z + (m.t1.z - m.t0.z) * s);
            double yaw = m.yaw0 + (m.yaw1 - m.yaw0) * s;
            double pitch = Math.min(80, m.p0 + (m.p1 - m.p0) * s + 5 * m.lift[k]), dist = (m.d0 + (m.d1 - m.d0) * s) * Math.pow(0.94, m.lift[k]);
            cam.put(tick, camKf.newInstance(eye(t, yaw, pitch, dist), (float) yaw, (float) pitch, 0f, k == steps ? hold : smooth));
        }
    }

    private static void startExport() throws Exception {
        String style = job.getOrDefault("style", "orbit");
        int end = totalTicks;
        // The cut points come from log CLOCK times x 20; if the client dropped ticks the replay is shorter than
        // that, the last block "lands" past the end and the reveal vanished. The take always records ~600 ticks
        // (the 30 s stop delay) after the last block, so if that gap is far off, re-anchor on the end.
        tickShift = 0;
        double bLog = num("buildEnd", end - 600);
        if (Math.abs((end - bLog) - 600) > 200 && bLog > end - 200) {
            tickShift = (int) Math.round(bLog - (end - 600));
            StartBuildMod.LOGGER.warn("[StartBuild] render: WARN buildEnd {} vs replay end {} - shifting cut points by {} ticks", (int) bLog, end, -tickShift);
        }
        int T = (int) Math.max(0, Math.min(end, num("terraformEnd", end / 4.0) - tickShift));
        int B = (int) Math.min(end, Math.max(T + 20, bLog - tickShift));
        lastMove = null;
        guardBlocked = 0;
        buildOut0 = -1; buildOut1 = -1; cutTimes.clear();
        int D = B - T, H = Math.min(T, 300);
        double cx = num("centerX", 0) + 0.5, cz = num("centerZ", 0) + 0.5, base = num("baseY", 64);
        double w = num("width", 60), dp = num("depth", 60), h = num("height", 40);
        double front = num("frontYaw", 160);
        gCx = cx; gCz = cz; gW = w; gDp = dp; gBase = base; gH = h;   // the clear-view guard checks against this box
        guardOn = num("viewGuard", 1) > 0;
        guardFixes = 0;

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
                boolean roomForCloseups = D > 400;                        // a short build: p1 + closeup ran past c1
                if (roomForCloseups && closeup(p1, cx, cz, front)) {                        // cut in close to the character at work
                    speedProgress(T, p1, 3.5);
                    speed(p1, p1 + CLOSEUP_TICKS, 1.5);
                    fixed(p1 + CLOSEUP_TICKS, mid, front + 70, 10, frontDist);
                    speedProgress(p1 + CLOSEUP_TICKS, c1, 3.5);
                } else {
                    speedProgress(T, c1, 7);
                }
                fixed(c1, mid, front, 24, frontDist * 1.05);
                if (roomForCloseups && closeup(p2, cx, cz, front)) {
                    speedProgress(c1, p2, 3.5);
                    speed(p2, p2 + CLOSEUP_TICKS, 1.5);
                    fixed(p2 + CLOSEUP_TICKS, mid, front, 24, frontDist * 1.05);
                    speedProgress(p2 + CLOSEUP_TICKS, c2, 3.5);
                } else {
                    speedProgress(c1, c2, 7);
                }
                fixed(c2, mid, front - 40, 6, frontDist * 0.95);
                speedProgress(c2, B, 6);
                // the closing sweep settles almost square on the front (it ended at front+35, a side-ish view)
                move(B, end, new Vector3d(cx, base + h * 0.25, cz), new Vector3d(cx, base + h * 0.4, cz),
                        front - 50, front + 12, -4, 6, frontDist * 0.95, frontDist * 1.05);
                speed(B, end, 10);
            }
            case "flyby" -> {
                // NOT USED (owner, 2026-10-30: "way too much camera movement", "there should be no jumpyness" - the
                // approved styles are orbit and cuts). Kept only for reference.
                // Drone fly-bys (after the Winnel Castle Short the owner liked): close and low, each shot slides
                // sideways past the build (the hills move behind it) and rises with the part going up now, a cut to
                // the next side every ~4 s, then one long pull back and up to the whole build, ending on the front.
                time = 9000;
                double sc = Math.max(0.4, Math.min(1.5, num("seconds", 34) / 60));
                // a calm opening: a slow 12-degree drift over the site prep (it swung 50 degrees in under 5 s - the
                // owner: "way too much camera movement ... at least in the first 5 seconds")
                move(0, T, ground, low, front - 120, front - 108, 22, 20, frontDist * 1.15, frontDist * 1.05);
                speed(0, H, 1.5 * sc);
                speed(H, T, 7 * sc);
                buildOut0 = outTicks / 20.0;
                double[] side = {-60, 50, -140, 120, -20, 85, -95, 20};
                double buildSec = 41.5 * sc;
                int n = Math.max(2, Math.min(side.length, (int) Math.round(buildSec / 4)));
                for (int i = 0; i < n; i++) {
                    int a = T + (int) ((long) D * i / n), b = T + (int) ((long) D * (i + 1) / n);
                    if (i > 0) cutTimes.add(outTicks / 20.0);
                    // aim at the height being built in this stretch (layers go up from the bottom)
                    double f0 = (double) i / n, f1 = (double) (i + 1) / n;
                    Vector3d t0 = new Vector3d(cx, base + h * Math.min(0.8, 0.1 + f0 * 0.8), cz);
                    Vector3d t1 = new Vector3d(cx, base + h * Math.min(0.85, 0.1 + f1 * 0.8), cz);
                    double dir = i % 2 == 0 ? 1 : -1;                          // alternate the slide direction
                    lastMove = null;
                    move(a, b, t0, t1, front + side[i], front + side[i] + 40 * dir, 10, 14, frontDist * 0.68, frontDist * 0.6);
                    speedProgress(a, b, buildSec / n);
                }
                buildOut1 = outTicks / 20.0;
                cutTimes.add(outTicks / 20.0);
                lastMove = null;
                move(B, end, new Vector3d(cx, base + h * 0.35, cz), mid, front - 45, front + 10, 4, 12, frontDist * 0.6, frontDist * 1.05);
                speed(B, end, 10 * Math.max(0.75, sc));
                boolean intoSun = Math.cos(Math.toRadians(front + 10 - 90)) > 0.4;
                if (intoSun) time = 3000;
                if (num("dusk", 1) > 0 && !intoSun) duskFrom = B;
            }
            case "cuts" -> {
                // Snap cuts (test against the orbit): a new angle about every 4 s while it builds, each with a
                // slight push-in, then the same reveal as the orbit's last 80 degrees, ending on the front.
                time = 9000;
                double sc = Math.max(0.4, Math.min(1.5, num("seconds", 34) / 60));
                fixed(0, ground, front - 50, 38, frontDist * 1.35);
                speed(0, H, 1.5 * sc);
                speed(H, T, 7 * sc);
                buildOut0 = outTicks / 20.0;
                double[][] shots = {                                      // yaw offset, pitch, distance factor
                        {-55, 30, 1.1}, {45, 18, 0.9}, {-150, 34, 1.0}, {110, 14, 0.85}, {-20, 24, 1.05}, {60, 12, 0.95},
                        {-100, 28, 0.9}, {15, 18, 1.0}};
                double buildSec = 41.5 * sc;
                int n = Math.max(2, Math.min(shots.length, (int) Math.round(buildSec / 4)));
                for (int i = 0; i < n; i++) {
                    int a = T + (int) ((long) D * i / n), b = T + (int) ((long) D * (i + 1) / n);
                    if (i > 0) cutTimes.add(outTicks / 20.0);
                    double[] s = shots[i];
                    lastMove = null;                                          // a hard cut, not a join
                    Vector3d tgt = i % 2 == 0 ? low : mid;
                    move(a, b, tgt, mid, front + s[0], front + s[0] + 10, s[1], s[1] - 3, frontDist * s[2], frontDist * s[2] * 0.92);
                    speedProgress(a, b, buildSec / n);
                }
                buildOut1 = outTicks / 20.0;
                cutTimes.add(outTicks / 20.0);
                lastMove = null;
                move(B, end, mid, mid, front - 70, front + 10, 16, 6, frontDist, frontDist * 0.97);
                speed(B, end, 10 * Math.max(0.75, sc));
                boolean intoSun = Math.cos(Math.toRadians(front + 10 - 90)) > 0.4;
                if (intoSun) time = 3000;
                if (num("dusk", 1) > 0 && !intoSun) duskFrom = B;
            }
            default -> {
                // One continuous orbit, framed on the build, rising and tightening as it grows - and ENDING ON
                // THE FRONT. (The old angles ran front-140 -> front+200, so every orbit finished on the back.)
                time = 9000;                                             // bright morning
                front -= 190;
                // ~36 s (was 60): viewers left at ~22 s of the 43-65 s Shorts (channel analytics, Oct 2026)
                double sc = Math.max(0.4, Math.min(1.5, num("seconds", 34) / 60));
                // Site prep 8.5 s (was 12: ten seconds of distant digging dragged); the build gets the time.
                move(0, T, ground, low, front - 140, front - 60, 38, 30, frontDist * 1.4, frontDist * 1.05);
                speed(0, H, 1.5 * sc);
                speed(H, T, 7 * sc);
                buildOut0 = outTicks / 20.0;
                // No close-up of the character in the orbit any more: it played 1.5 s at real speed and the
                // builder was usually standing between clicks - it read as a freeze (castle v2 at 29 s). One
                // unbroken orbit, starting ~15% closer than before so the walls read while they go up.
                move(T, B, low, mid, front - 60, front + 120, 30, 16, frontDist * 1.05, frontDist);
                speedProgress(T, B, 41.5 * sc);
                buildOut1 = outTicks / 20.0;
                // (every camera position, this reveal included, is lifted clear of terrain by move()/fixed())
                move(B, end, mid, mid, front + 120, front + 200, 16, 6, frontDist, frontDist * 0.97);   // starts where the build move ends (14 popped 2 degrees)
                speed(B, end, 10 * Math.max(0.75, sc));
                // Golden hour for the reveal: the light warms and lanterns / windows start to glow - a second
                // payoff after the last block (off for snowy sites: sunset + shader snow fog looked orange).
                // Not when the last shot looks west (an east front): it stares into the setting sun and the shader
                // haze washed the whole frame orange (liner v4).
                boolean intoSun = Math.cos(Math.toRadians(front + 200 - 90)) > 0.4;
                if (intoSun) {                                           // and morning light, so the sun is behind the camera
                    time = 3000;
                    StartBuildMod.LOGGER.info("[StartBuild] render: morning, no golden hour - the last shot looks west, into the afternoon sun");
                }
                if (num("dusk", 1) > 0 && !intoSun) duskFrom = B;
            }
        }
        time = (int) num("timeOfDay", time);
        day.put(0, dayKf.newInstance(time));
        if (duskFrom >= 0 && duskFrom < end) {
            day.put(duskFrom, dayKf.newInstance(time));
            day.put(end, dayKf.newInstance((int) num("duskTime", 12300)));
        } else {
            day.put(end, dayKf.newInstance(time));
        }
        duskFrom = -1;

        // Soundtrack: an audio file (config/startbuild-music/<music>.ogg), laid under the SILENT export by
        // MusicMixer at normal speed. (Flashback's own audio track plays at the replay's speed - under an
        // 80x timelapse that was a garbled blip.)
        Path music = null;
        if (job.containsKey("music")) {
            // Any audio file named like that in config/startbuild-music (mp3, ogg, m4a, wav...).
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("startbuild-music");
            music = dir.resolve(job.get("music") + ".ogg");
            try (var files = Files.list(dir)) {
                String want = job.get("music").toLowerCase();
                music = files.filter(f -> f.getFileName().toString().toLowerCase().replaceAll("\\.[a-z0-9]+$", "").equals(want))
                        .findFirst().orElse(music);
            } catch (Exception ignored) {
            }
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
        try {
            StringBuilder tm = new StringBuilder();
            if (buildOut0 >= 0) tm.append(String.format(java.util.Locale.ROOT, "build=%.2f,%.2f%n", buildOut0, buildOut1));
            if (!cutTimes.isEmpty()) tm.append("cuts=").append(cutTimes.stream().map(c -> String.format(java.util.Locale.ROOT, "%.2f", c))
                    .collect(java.util.stream.Collectors.joining(","))).append(System.lineSeparator());
            Files.writeString(folder.resolve(finalOutput.getFileName().toString().replace(".mp4", ".timing.txt")), tm.toString());
        } catch (Exception e) {
            StartBuildMod.LOGGER.warn("[StartBuild] render: could not write the timing file: {}", e.toString());
        }
        pendingMusic = music;
        musicStart = num("musicStart", -1);                           // -1 = where the song gets going
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
                // 60 fps at a high bitrate (playbook, Oct 2026: 30 fps / ~9 Mbps finals looked soft next to 1080p60 Shorts)
                num("fps", 60), false, false,
                enumValue("com.moulberry.flashback.combo_options.VideoContainer", "MP4"), h264,
                encoderUsed = encoders.length > 0 ? encoders[0] : "libx264",
                40_000_000, false, false, true, false,
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
