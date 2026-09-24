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
    public static void main(String[] args) {
        double start = args.length > 3 ? Double.parseDouble(args[3]) : 0;
        String enc = args.length > 4 ? args[4] : "h264_nvenc";
        String err = mix(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), enc, 16_000_000, start);
        System.out.println(err == null ? "ok " + args[2] : "FAILED " + err);
    }

    /** @return null when done, or why it failed. Blocking; run it off the game thread. */
    static String mix(Path video, Path music, Path out, String encoder, int bitrate) {
        return mix(video, music, out, encoder, bitrate, 0);
    }

    /** As above, starting the music `startSec` into the track (skip a slow intro to where it gets going). */
    static String mix(Path video, Path music, Path out, String encoder, int bitrate, double startSec) {
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
                    if (at > fadeFrom) fade(a, frameCls, Math.max(0, (videoLen - at) / 2_000_000.0));
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

    /** Scale a samples frame (S16) by `gain`, for the fade-out. */
    private static void fade(Object frame, Class<?> frameCls, double gain) throws Exception {
        Object[] samples = (Object[]) frameCls.getField("samples").get(frame);
        if (samples == null) return;
        for (Object b : samples) {
            if (!(b instanceof ShortBuffer sb)) continue;
            for (int i = sb.position(); i < sb.limit(); i++) sb.put(i, (short) (sb.get(i) * gain));
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
