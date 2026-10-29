package app.bitchord.telegram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Lining up an upgrade: the stream that's playing is heard through its audio sink, in the sink's
 * own time, and the Telegram copy is decoded on the song's timeline; the offset between them is
 * found to within a few milliseconds, and two different songs aren't taken for one.
 */
public final class VersionAlignTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    /** Twenty seconds of drums and chords. */
    static float[] song(int rate, int seconds, long seed) {
        Random r = new Random(seed);
        float[] out = new float[rate * seconds];
        double beat = 60.0 / 112 / 2;
        for (int n = 0; n * beat < seconds; n++) {
            int start = (int) (n * beat * rate);
            double level = 0.2 + 0.8 * r.nextDouble();
            int length = (int) (0.12 * rate);
            for (int i = 0; i < length && start + i < out.length; i++)
                out[start + i] +=
                        (float) (r.nextGaussian() * level * Math.exp(-i / (0.03 * rate)) * 0.3);
            double f = 110 * Math.pow(2, r.nextInt(24) / 12.0);
            int note = (int) (beat * 2 * rate);
            for (int i = 0; i < note && start + i < out.length; i++)
                out[start + i] +=
                        (float)
                                (0.15
                                        * level
                                        * Math.sin(2 * Math.PI * f * i / rate)
                                        * Math.exp(-i / (0.4 * rate)));
        }
        return out;
    }

    /** The same song with {@code leadMs} more silence at the start, slightly different. */
    static float[] copy(float[] song, int rate, int leadMs, long seed) {
        Random r = new Random(seed);
        int lead = leadMs * rate / 1000;
        float[] out = new float[song.length];
        float smooth = 0;
        for (int i = 0; i < out.length; i++) {
            int from = i - lead;
            float v = from >= 0 && from < song.length ? song[from] : 0;
            smooth = 0.7f * smooth + 0.3f * v; // a duller master
            out[i] = 0.8f * smooth + 0.2f * v + (float) (r.nextGaussian() * 0.002);
        }
        return out;
    }

    static final long RENDER_OFFSET_US = 1_000_000_000_000L;

    /** Plays {@code mono} into a sink up to {@code untilMs}, as stereo 16-bit buffers. */
    static Object play(float[] mono, int rate, long untilMs) {
        Object sink = new Object();
        SoundCheck.format(sink, rate, 2, Downmix.PCM_16BIT);
        int block = 4096;
        for (int at = 0; (long) at * 1000 / rate < untilMs; at += block) {
            ByteBuffer b = ByteBuffer.allocate(block * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < block; i++) {
                short v =
                        (short) Math.max(-32768, Math.min(32767, Math.round(mono[at + i] * 32767)));
                b.putShort(v).putShort(v);
            }
            b.flip();
            long timeUs = RENDER_OFFSET_US + (long) at * 1_000_000L / rate;
            VersionAlign.tap(sink, b, timeUs);
            VersionAlign.tap(sink, b, timeUs); // handed again: counted once
        }
        return sink;
    }

    static double[] align(float[] stream, float[] telegram, int rate, long positionMs) {
        Object sink = play(stream, rate, positionMs + 400);
        VersionAlign.position(sink, RENDER_OFFSET_US + positionMs * 1000);
        VersionAlign.Envelope heard = VersionAlign.recent(positionMs, 1f);
        check(heard != null, "heard enough");
        check(
                Math.abs(heard.startMs - (positionMs - VersionAlign.HEARD_MS)) <= 5,
                "on the song's timeline: " + heard.startMs);
        long fromMs = (long) heard.startMs - VersionAlign.SEARCH_MS;
        int from = (int) (fromMs * rate / 1000), length = rate * 12;
        float[] part = new float[length];
        System.arraycopy(telegram, from, part, 0, length);
        return VersionAlign.offset(
                heard,
                VersionAlign.of(part, length, rate, (long) from * 1_000_000L / rate),
                VersionAlign.SEARCH_MS);
    }

    public static void main(String[] args) {
        SmoothUpgrade.enabled = true;
        int rate = 44100;
        float[] stream = song(rate, 22, 1);
        for (int lead : new int[] {237, -412, 0, 1800}) {
            float[] telegram = copy(stream, rate, lead, 2);
            double[] found = align(stream, telegram, rate, 15_000);
            check(found != null, "compared");
            check(Math.abs(found[0] - lead) <= 3, "offset " + lead + " ms found as " + found[0]);
            check(found[1] >= VersionAlign.MIN_MATCH, "a clear match: " + found[1]);
            check(found[2] >= VersionAlign.MIN_MARGIN, "and clearly the best: " + found[2]);
        }
        // Another song: no clear match, so nothing is shifted.
        double[] other = align(stream, song(rate, 22, 9), rate, 15_000);
        check(
                other == null
                        || other[1] < VersionAlign.MIN_MATCH
                        || other[2] < VersionAlign.MIN_MARGIN,
                "another song isn't lined up: " + java.util.Arrays.toString(other));
        // A seek starts the heard audio again.
        Object sink = play(stream, rate, 3000);
        ByteBuffer jump = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN);
        jump.limit(jump.capacity());
        VersionAlign.tap(sink, jump, RENDER_OFFSET_US + 60_000_000L);
        VersionAlign.position(sink, RENDER_OFFSET_US + 60_050_000L);
        check(VersionAlign.recent(60_050, 1f) == null, "too little heard since the seek");
        // Switched off: nothing is heard.
        SmoothUpgrade.enabled = false;
        Object quiet = play(stream, rate, 15_400);
        VersionAlign.position(quiet, RENDER_OFFSET_US + 15_000_000L);
        check(VersionAlign.recent(15_000, 1f) == null, "not listening when switched off");
        SmoothUpgrade.enabled = true;
        System.out.println("PASS: " + checks + " upgrade alignment checks.");
    }
}
