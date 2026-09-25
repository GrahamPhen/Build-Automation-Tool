package com.graham.startbuild;

import java.lang.reflect.Method;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lays a music file under a rendered (silent) video at NORMAL speed and writes the finished MP4.
 *
 * Why not Flashback's own audio track: it plays audio through the game's sound engine at the replay's
 * current speed, so under an 80x timelapse the music came out as a garbled blip. Here the video frames are
 * copied (re-encoded with the same encoder Flashback used) and the music is decoded, cut to the video's
 * length with a 2 s fade-out, and encoded as AAC, interleaved by timestamp.
 *
 * Uses the javacv FFmpeg classes bundled inside Flashback (org.bytedeco.javacv), by reflection.
 */
final class MusicMixer {

    private MusicMixer() {
    }

    /**
     * Standalone use (no game needed - Flashback's jar carries the FFmpeg natives):
     *   java -cp "Flashback.jar;<mod classes>" com.graham.startbuild.MusicMixer video music out [startSec] [encoder]
     */
    public static void main(String[] args) throws Exception {
        if (args[0].equals("start")) {                                   // loudness per 0.5 s over the first 8 s
            for (int i = 1; i < args.length; i++) {
                double[] r = loudness(Path.of(args[i]));
                StringBuilder sb = new StringBuilder(Path.of(args[i]).getFileName() + ":");
                for (int k = 0; k < Math.min(16, r.length); k++) sb.append(" ").append((int) r[k]);
                System.out.println(sb);
            }
            return;
        }
        if (args[0].equals("probe")) {                                   // how loud is the audio, per 5 s?
            for (int i = 1; i < args.length; i++) System.out.println(probe(Path.of(args[i])));
            return;
        }
        double start = args.length > 3 ? Double.parseDouble(args[3]) : -1;   // -1 = where the song gets going
        String enc = args.length > 4 ? args[4] : "h264_nvenc";
        String err = mix(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), enc, 16_000_000, start);
        System.out.println(err == null ? "ok " + args[2] : "FAILED " + err);
    }

    /** @return null when done, or why it failed. Blocking; run it off the game thread. */
    static String mix(Path video, Path music, Path out, String encoder, int bitrate) {
        return mix(video, music, out, encoder, bitrate, 0);
    }

    /** Target loudness (RMS of 16-bit samples) every song is brought to: a strong, even level. */
    private static final double TARGET_RMS = 9000;

    /**
     * Loudness of a song per half second (RMS), for choosing where to start it and how much to boost it.
     * @return {rms per 0.5 s...}
     */
    static double[] loudness(Path music) throws Exception {
        Class<?> grabberCls = Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber");
        Class<?> frameCls = Class.forName("org.bytedeco.javacv.Frame");
        Object g = grabberCls.getConstructor(String.class).newInstance(music.toString());
        call(g, "setSampleFormat", int.class, 1);
        call(g, "start");
        java.util.List<Double> out = new java.util.ArrayList<>();
        double sum = 0;
        long n = 0, window = 0;
        Object f;
        try {
            while ((f = grabberCls.getMethod("grabSamples").invoke(g)) != null) {
                long w = (long) call(g, "getTimestamp") / 500_000;
                if (w > window) {
                    out.add(n == 0 ? 0 : Math.sqrt(sum / n));
                    sum = 0;
                    n = 0;
                    window = w;
                }
                for (Object b : (Object[]) frameCls.getField("samples").get(f)) {
                    if (!(b instanceof ShortBuffer s)) continue;
                    for (int i = s.position(); i < s.limit(); i++) {
                        sum += (double) s.get(i) * s.get(i);
                        n++;
                    }
                }
            }
        } finally {
            quietly(g, "release");
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Where a song gets going: the first half-second that reaches half the song's typical (median) loudness.
     * 0 if it is loud from the start. (A 0.15 s fade-in keeps a mid-note start from clicking.)
     */
    static double autoStart(double[] rms) {
        if (rms.length == 0) return 0;
        double[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        double typical = sorted[sorted.length / 2];
        for (int i = 0; i < rms.length; i++) {
            if (rms[i] >= typical * 0.5) return i * 0.5;      // right on it: a Short cannot wait for a build-up
        }
        return 0;
    }

    /** Gain that brings the song's typical loudness to TARGET_RMS (never cut, at most x4). */
    static double gainFor(double[] rms) {
        if (rms.length == 0) return 1;
        double[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        double typical = sorted[sorted.length / 2];
        return typical <= 1 ? 1 : Math.max(1, Math.min(4, TARGET_RMS / typical));
    }

    /**
     * As above, starting the music `startSec` into the track - or, if negative, where the song gets going
     * (automatic) - and bringing it to an even loudness.
     */
    static String mix(Path video, Path music, Path out, String encoder, int bitrate, double startSec) {
        double gain = 1;
        try {
            double[] rms = loudness(music);
            if (startSec < 0) startSec = autoStart(rms);
            gain = gainFor(rms);
            System.out.printf("music %s: start %.1f s, gain x%.2f%n", music.getFileName(), startSec, gain);
        } catch (Throwable t) {
            if (startSec < 0) startSec = 0;
        }
        return mixAt(video, music, out, encoder, bitrate, startSec, gain);
    }

    private static String mixAt(Path video, Path music, Path out, String encoder, int bitrate, double startSec, double gain) {
        Object vg = null, ag = null, rec = null;
        try {
            Class<?> grabberCls = Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber");
            Class<?> recorderCls = Class.forName("org.bytedeco.javacv.FFmpegFrameRecorder");
            Class<?> frameCls = Class.forName("org.bytedeco.javacv.Frame");
            vg = grabberCls.getConstructor(String.class).newInstance(video.toString());
            call(vg, "start");
            ag = grabberCls.getConstructor(String.class).newInstance(music.toString());
            call(ag, "setSampleFormat", int.class, 1);                     // AV_SAMPLE_FMT_S16, interleaved shorts
            call(ag, "start");
            long offset = (long) (startSec * 1_000_000);
            if (offset > 0) call(ag, "setTimestamp", long.class, offset);
            int w = (int) call(vg, "getImageWidth"), h = (int) call(vg, "getImageHeight");
            double fps = (double) call(vg, "getFrameRate");
            int rate = (int) call(ag, "getSampleRate"), channels = Math.max(1, (int) call(ag, "getAudioChannels"));
            long videoLen = (long) call(vg, "getLengthInTime");            // microseconds

            Files.deleteIfExists(out);
            rec = recorderCls.getConstructor(String.class, int.class, int.class, int.class).newInstance(out.toString(), w, h, channels);
            call(rec, "setFormat", String.class, "mp4");
            call(rec, "setFrameRate", double.class, fps);
            call(rec, "setVideoCodecName", String.class, encoder);
            call(rec, "setVideoBitrate", int.class, bitrate);
            call(rec, "setPixelFormat", int.class, 0);                     // AV_PIX_FMT_YUV420P
            call(rec, "setAudioCodec", int.class, 86018);                  // AV_CODEC_ID_AAC
            call(rec, "setSampleRate", int.class, rate);
            call(rec, "setAudioChannels", int.class, channels);
            call(rec, "setAudioBitrate", int.class, 192_000);
            call(rec, "start");

            Method grabImage = grabberCls.getMethod("grabImage"), grabSamples = grabberCls.getMethod("grabSamples");
            Method record = recorderCls.getMethod("record", frameCls);
            Method ts = grabberCls.getMethod("getTimestamp");
            long fadeFrom = videoLen - 2_000_000;
            boolean audioDone = false;
            Object frame;
            while ((frame = grabImage.invoke(vg)) != null) {
                long vt = (long) ts.invoke(vg);
                record.invoke(rec, frame);
                // Keep the music up to the video's clock, so the file is interleaved.
                while (!audioDone && (long) ts.invoke(ag) - offset <= vt) {
                    Object a = grabSamples.invoke(ag);
                    long at = (long) ts.invoke(ag) - offset;
                    if (a == null || at > videoLen) {
                        audioDone = true;
                        break;
                    }
                    double g = gain * (at > fadeFrom ? Math.max(0, (videoLen - at) / 2_000_000.0) : 1)
                            * Math.min(1, Math.max(0, at) / 150_000.0);                      // 0.15 s fade-in
                    if (g != 1) fade(a, frameCls, g);
                    record.invoke(rec, a);
                }
            }
            call(rec, "stop");
            return null;
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
            return c.getClass().getSimpleName() + ": " + c.getMessage();
        } finally {
            quietly(rec, "release");
            quietly(vg, "release");
            quietly(ag, "release");
        }
    }

    /** Audio stream facts and RMS loudness (0-32767) per 5 s window - to check a mix without listening. */
    static String probe(Path file) throws Exception {
        Class<?> grabberCls = Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber");
        Class<?> frameCls = Class.forName("org.bytedeco.javacv.Frame");
        Object g = grabberCls.getConstructor(String.class).newInstance(file.toString());
        call(g, "setSampleFormat", int.class, 1);
        call(g, "start");
        StringBuilder sb = new StringBuilder(file.getFileName() + ": audio streams=" + call(g, "hasAudio")
                + " rate=" + call(g, "getSampleRate") + " ch=" + call(g, "getAudioChannels") + " | rms/5s:");
        double sum = 0;
        long n = 0, window = 0;
        Object f;
        while ((f = grabberCls.getMethod("grabSamples").invoke(g)) != null) {
            long t = (long) call(g, "getTimestamp");
            if (t / 5_000_000 > window) {
                sb.append(' ').append(n == 0 ? 0 : (int) Math.sqrt(sum / n));
                sum = 0;
                n = 0;
                window = t / 5_000_000;
            }
            for (Object b : (Object[]) frameCls.getField("samples").get(f)) {
                if (!(b instanceof ShortBuffer s)) continue;
                for (int i = s.position(); i < s.limit(); i++) {
                    sum += (double) s.get(i) * s.get(i);
                    n++;
                }
            }
        }
        sb.append(' ').append(n == 0 ? 0 : (int) Math.sqrt(sum / n));
        quietly(g, "release");
        return sb.toString();
    }

    /** Scale a samples frame (S16) by `gain` (boost and fade-out), clipped to the 16-bit range. */
    private static void fade(Object frame, Class<?> frameCls, double gain) throws Exception {
        Object[] samples = (Object[]) frameCls.getField("samples").get(frame);
        if (samples == null) return;
        for (Object b : samples) {
            if (!(b instanceof ShortBuffer sb)) continue;
            for (int i = sb.position(); i < sb.limit(); i++) {
                sb.put(i, (short) Math.max(-32767, Math.min(32767, Math.round(sb.get(i) * gain))));
            }
        }
    }

    private static Object call(Object o, String name, Object... args) throws Exception {
        if (args.length == 0) return o.getClass().getMethod(name).invoke(o);
        return o.getClass().getMethod(name, (Class<?>) args[0]).invoke(o, args[1]);
    }

    private static void quietly(Object o, String name) {
        if (o == null) return;
        try {
            o.getClass().getMethod(name).invoke(o);
        } catch (Throwable ignored) {
        }
    }
}
