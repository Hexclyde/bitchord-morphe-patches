package app.bitchord.telegram;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lines up a Telegram copy with the YouTube stream it replaces mid-song.
 *
 * <p>BitChord's two-player swap starts the new copy at the old one's position plus an offset it
 * measures between two versions of a song. A quality upgrade is the same song, so BitChord takes
 * the offset as zero, but two uploads rarely start at the same instant: a few hundred milliseconds
 * more silence at the start of one, and the switch repeats or skips that much.
 *
 * <p>So the loudness of what's playing is kept, five milliseconds at a time, for the last 15
 * seconds, from the buffers the player hands its audio sink. When an upgrade to a Telegram file
 * comes, the same stretch of that file is decoded, and the shift at which the two loudness curves
 * agree best is the offset BitChord is given. When they don't agree clearly, nothing changes.
 */
final class VersionAlign {
    private VersionAlign() {}

    /** One loudness value per this many microseconds. */
    static final long FRAME_US = 5000;

    static final int CAPACITY = 3000; // 15 seconds

    /** What's compared: the last 7 seconds heard, ending 0.2 seconds ago. */
    static final long HEARD_MS = 7000, HEARD_END_MS = 200;

    /** How far apart the two copies may be. */
    static final long SEARCH_MS = 2500;

    /** How clearly the best shift must win. */
    static final double MIN_MATCH = 0.5, MIN_MARGIN = 0.1;

    /** Measuring gives up after this long, and the swap goes ahead unaligned. */
    static final long BUDGET_MS = 8000;

    // ── Listening ───────────────────────────────────────────────────────────

    /** Loudness heard by one audio sink, in the sink's own (renderer) time. */
    static final class Heard {
        final float[] frames = new float[CAPACITY];
        int start, count, rate;
        long firstUs; // renderer time of the first frame since the last discontinuity
        long emitted, samples; // frames and samples since then
        double energy;
        int inFrame;
        long expectedUs = Long.MIN_VALUE;
        Object lastBuffer;
        long lastTimeUs = Long.MIN_VALUE;
        // The sink's playing position and when it was read.
        long positionUs = Long.MIN_VALUE, positionAtNanos;

        synchronized void add(
                ByteBuffer buffer, long timeUs, int rate, int channels, int encoding) {
            if (buffer == lastBuffer && timeUs == lastTimeUs) return; // handed again
            lastBuffer = buffer;
            lastTimeUs = timeUs;
            int width = Downmix.bytesPerSample(encoding);
            if (width == 0 || channels <= 0 || rate <= 0) return;
            // A seek, a new song or a new format: start again.
            if (rate != this.rate
                    || expectedUs == Long.MIN_VALUE
                    || Math.abs(timeUs - expectedUs) > 20_000) {
                this.rate = rate;
                start = count = inFrame = 0;
                emitted = samples = 0;
                energy = 0;
                firstUs = timeUs;
            }
            ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int frames = b.remaining() / (width * channels);
            double perFrame = rate * (FRAME_US / 1e6);
            for (int i = 0; i < frames; i++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) sum += Downmix.read(b, encoding);
                float mono = sum / channels;
                energy += mono * mono;
                inFrame++;
                if (++samples >= (long) Math.floor((emitted + 1) * perFrame)) push();
            }
            expectedUs = timeUs + frames * 1_000_000L / rate;
        }

        private void push() {
            float rms = (float) Math.sqrt(energy / Math.max(1, inFrame));
            energy = 0;
            inFrame = 0;
            emitted++;
            if (count < CAPACITY) frames[(start + count++) % CAPACITY] = rms;
            else {
                frames[start] = rms;
                start = (start + 1) % CAPACITY;
            }
        }

        synchronized void position(long us, long nanos) {
            positionUs = us;
            positionAtNanos = nanos;
        }

        /** Frames whose start lies in [fromUs, toUs) of renderer time, and the first one's time. */
        synchronized float[] range(long fromUs, long toUs, long[] firstUs) {
            long kept = this.firstUs + (emitted - count) * FRAME_US;
            int from = (int) Math.max(0, Math.ceil((fromUs - kept) / (double) FRAME_US));
            int to = (int) Math.min(count, Math.ceil((toUs - kept) / (double) FRAME_US));
            if (to <= from) return new float[0];
            float[] out = new float[to - from];
            for (int i = from; i < to; i++) out[i - from] = frames[(start + i) % CAPACITY];
            firstUs[0] = kept + from * FRAME_US;
            return out;
        }
    }

    private static final Map<Object, Heard> sinks = new WeakHashMap<>();

    private static Heard heard(Object sink) {
        synchronized (sinks) {
            Heard h = sinks.get(sink);
            if (h == null) sinks.put(sink, h = new Heard());
            return h;
        }
    }

    /** Every buffer a player hands its audio sink. */
    static void tap(Object sink, ByteBuffer buffer, long timeUs) {
        if (!SmoothUpgrade.enabled) return;
        try {
            int[] format = SoundCheck.formatOf(sink);
            if (format == null) return;
            heard(sink).add(buffer, timeUs, format[0], format[1], format[2]);
        } catch (Throwable ignored) {
            // Listening must never get in the way of playback.
        }
    }

    /** The sink's playing position, each time the player asks for it. */
    public static void position(Object sink, long positionUs) {
        if (!SmoothUpgrade.enabled || positionUs == Long.MIN_VALUE) return;
        try {
            heard(sink).position(positionUs, System.nanoTime());
        } catch (Throwable ignored) {
            // As above.
        }
    }

    /**
     * The loudness heard by the sink that's playing now, put on the player's own timeline: what was
     * heard from {@code HEARD_MS} before {@code positionMs} up to shortly before it. Null when
     * there isn't enough.
     */
    static Envelope recent(long positionMs, float speed) {
        Heard playing = null;
        long now = System.nanoTime();
        synchronized (sinks) {
            for (Heard h : sinks.values())
                if (h.positionUs != Long.MIN_VALUE
                        && now - h.positionAtNanos < 200_000_000L
                        && (playing == null || h.positionAtNanos > playing.positionAtNanos))
                    playing = h;
        }
        if (playing == null) return null;
        long renderNowUs;
        synchronized (playing) {
            renderNowUs =
                    playing.positionUs + (long) ((now - playing.positionAtNanos) / 1000.0 * speed);
        }
        // Renderer time minus the player's own position: the same moment on both clocks.
        long shiftUs = renderNowUs - positionMs * 1000;
        long[] first = new long[1];
        float[] frames =
                playing.range(
                        shiftUs + (positionMs - HEARD_MS) * 1000,
                        shiftUs + (positionMs - HEARD_END_MS) * 1000,
                        first);
        if (frames.length < (HEARD_MS - HEARD_END_MS) * 1000 / FRAME_US * 3 / 4) return null;
        return new Envelope(frames, (first[0] - shiftUs) / 1000.0);
    }

    // ── Comparing ───────────────────────────────────────────────────────────

    /** Loudness every five milliseconds, from {@code startMs} on the song's timeline. */
    static final class Envelope {
        final float[] frames;
        final double startMs;

        Envelope(float[] frames, double startMs) {
            this.frames = frames;
            this.startMs = startMs;
        }
    }

    /** The loudness of decoded mono samples that start at {@code startUs}. */
    static Envelope of(float[] samples, int length, int rate, long startUs) {
        double perFrame = rate * (FRAME_US / 1e6);
        int count = (int) Math.floor(length / perFrame);
        float[] frames = new float[count];
        int from = 0;
        for (int f = 0; f < count; f++) {
            int to = (int) Math.floor((f + 1) * perFrame);
            double energy = 0;
            for (int i = from; i < to; i++) energy += samples[i] * samples[i];
            frames[f] = (float) Math.sqrt(energy / Math.max(1, to - from));
            from = to;
        }
        return new Envelope(frames, startUs / 1000.0);
    }

    /** Log loudness with its slow changes taken out: what's left is where the music moves. */
    static double[] shape(float[] frames) {
        int n = frames.length, half = 20;
        double[] log = new double[n], out = new double[n];
        for (int i = 0; i < n; i++) log[i] = Math.log10(1e-7 + frames[i]);
        double[] sum = new double[n + 1];
        for (int i = 0; i < n; i++) sum[i + 1] = sum[i] + log[i];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - half), b = Math.min(n, i + half + 1);
            out[i] = log[i] - (sum[b] - sum[a]) / (b - a);
        }
        return out;
    }

    /**
     * Where {@code later} has the content {@code heard} has: {offsetMs, match, margin}, offset
     * meaning a moment at t in {@code heard} is at t + offset in {@code later}. Null when they
     * don't overlap enough.
     */
    static double[] offset(Envelope heard, Envelope later, long searchMs) {
        double[] a = shape(heard.frames), b = shape(later.frames);
        double frameMs = FRAME_US / 1000.0;
        // later[j] is heard[i] when (later.start + j*frame) - (heard.start + i*frame) = offset.
        double base = later.startMs - heard.startMs;
        int range = (int) Math.ceil(searchMs / frameMs);
        int center = (int) Math.round(-base / frameMs);
        int minOverlap = a.length / 2;
        double[] scores = new double[2 * range + 1];
        double best = -2;
        int bestK = Integer.MIN_VALUE;
        for (int s = 0; s <= 2 * range; s++) {
            int k = center - range + s; // j = i + k
            int from = Math.max(0, -k), to = Math.min(a.length, b.length - k);
            scores[s] = Double.NaN;
            if (to - from < minOverlap) continue;
            double ma = 0, mb = 0;
            for (int i = from; i < to; i++) {
                ma += a[i];
                mb += b[i + k];
            }
            ma /= to - from;
            mb /= to - from;
            double ab = 0, aa = 0, bb = 0;
            for (int i = from; i < to; i++) {
                double x = a[i] - ma, y = b[i + k] - mb;
                ab += x * y;
                aa += x * x;
                bb += y * y;
            }
            double score = aa > 0 && bb > 0 ? ab / Math.sqrt(aa * bb) : 0;
            scores[s] = score;
            if (score > best) {
                best = score;
                bestK = s;
            }
        }
        if (bestK == Integer.MIN_VALUE) return null;
        // The best score away from the peak: how clearly the peak wins.
        double runnerUp = -1;
        for (int s = 0; s < scores.length; s++)
            if (Math.abs(s - bestK) > 20 && !Double.isNaN(scores[s]))
                runnerUp = Math.max(runnerUp, scores[s]);
        // Between frames: the top of the parabola through the peak and its neighbours.
        double fraction = 0;
        if (bestK > 0 && bestK < scores.length - 1) {
            double l = scores[bestK - 1], c = scores[bestK], r = scores[bestK + 1];
            if (!Double.isNaN(l) && !Double.isNaN(r) && l - 2 * c + r < 0)
                fraction = 0.5 * (l - r) / (l - 2 * c + r);
        }
        double k = center - range + bestK + fraction;
        return new double[] {base + k * frameMs, best, best - runnerUp};
    }

    // ── At an upgrade ───────────────────────────────────────────────────────

    private static final ExecutorService work =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "Telegram version align");
                        thread.setDaemon(true);
                        return thread;
                    });

    /** Decodes part of a Telegram file: {samples, length, rate, start}. */
    interface Decoder {
        SoundCheck.Pcm decode(String telegramId, long fromMs, int seconds) throws Exception;
    }

    static volatile Decoder decoder;

    /**
     * Measures how far the Telegram copy {@code item} is from what {@code player} is playing, then
     * calls {@code then} on the player's thread with the offset in milliseconds, or null. False
     * when it can't be measured at all (not a Telegram copy, too little heard yet), and nothing is
     * called.
     */
    static boolean measure(Player player, MediaItem item, java.util.function.Consumer<Long> then) {
        Decoder decode = decoder;
        TelegramBridge bridge = TelegramBridge.instance;
        if (decode == null || bridge == null || item == null || item.localConfiguration == null)
            return false;
        android.os.Looper looper = player.getApplicationLooper();
        if (looper == null) return false;
        String id;
        try {
            id = InternalAddon.audioId(bridge, item.localConfiguration.uri.toString());
        } catch (Exception e) {
            return false;
        }
        long positionMs = player.getCurrentPosition();
        float speed = player.getPlaybackParameters().speed;
        Envelope heard = recent(positionMs, speed);
        if (heard == null) return false;
        android.os.Handler handler = new android.os.Handler(looper);
        long started = System.currentTimeMillis();
        work.execute(
                () -> {
                    Long offset = null;
                    try {
                        long fromMs = Math.max(0, (long) heard.startMs - SEARCH_MS);
                        long toMs =
                                (long) (heard.startMs + heard.frames.length * FRAME_US / 1000.0)
                                        + SEARCH_MS;
                        SoundCheck.Pcm pcm =
                                decode.decode(
                                        id, fromMs, (int) Math.ceil((toMs - fromMs) / 1000.0));
                        if (System.currentTimeMillis() - started < BUDGET_MS) {
                            double[] found =
                                    offset(
                                            heard,
                                            of(pcm.samples, pcm.length, pcm.rate, pcm.startUs),
                                            SEARCH_MS);
                            if (found != null && found[1] >= MIN_MATCH && found[2] >= MIN_MARGIN)
                                offset = Math.round(found[0]);
                            HostLog.d(
                                    found == null
                                            ? "Unofficial Telegram couldn't line up the upgrade:"
                                                    + " too little to compare"
                                            : String.format(
                                                    java.util.Locale.US,
                                                    "Unofficial Telegram lined up the upgrade: the"
                                                            + " Telegram copy is %+.0f ms from the"
                                                            + " stream (match %.2f, margin %.2f)%s",
                                                    found[0],
                                                    found[1],
                                                    found[2],
                                                    offset == null ? "; not used" : ""));
                        }
                    } catch (Exception e) {
                        HostLog.d(
                                "Unofficial Telegram couldn't line up the upgrade: "
                                        + e.getMessage());
                    }
                    Long result = offset;
                    handler.post(() -> then.accept(result));
                });
        return true;
    }
}
