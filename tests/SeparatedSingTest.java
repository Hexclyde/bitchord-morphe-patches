package app.bitchord.telegram;

import java.lang.reflect.Field;
import java.util.*;

/** Transform, timing and lifecycle checks without a runtime or model dependency. */
public final class SeparatedSingTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static float[] signal(int count) {
        float[] out = new float[count * 2];
        for (int i = 0; i < count; i++) {
            out[2 * i] = (float) (.3 * Math.sin(i * .037) + .1 * Math.cos(i * .013));
            out[2 * i + 1] = (float) (.2 * Math.sin(i * .029));
        }
        return out;
    }

    private static float[] tone(int frames, double hz, int rate) {
        float[] out = new float[frames * 2];
        for (int i = 0; i < frames; i++)
            out[2 * i] = out[2 * i + 1] = (float) (.4 * Math.sin(2 * Math.PI * hz * i / rate));
        return out;
    }

    private static double power(float[] pcm, int from, int to, double hz, int rate) {
        double re = 0, im = 0;
        for (int i = from; i < to; i++) {
            re += pcm[2 * i] * Math.cos(2 * Math.PI * hz * i / rate);
            im += pcm[2 * i] * Math.sin(2 * Math.PI * hz * i / rate);
        }
        return 2 * Math.hypot(re, im) / Math.max(1, to - from);
    }

    /** What a sink does: only frames that aren't suppressed go on, then whatever's left. */
    private static final class Output {
        float[] data = new float[1 << 16];
        int frames;

        void add(float[] from, int offset, int count) {
            while ((frames + count) * 2 > data.length) data = Arrays.copyOf(data, data.length * 2);
            System.arraycopy(from, offset * 2, data, frames * 2, count * 2);
            frames += count;
        }

        void feed(SeparatedSing engine, float[] input, int blockFrames, float level) {
            float[] block = new float[blockFrames * 2];
            for (int at = 0; at < input.length / 2; at += blockFrames) {
                int n = Math.min(blockFrames, input.length / 2 - at);
                System.arraycopy(input, at * 2, block, 0, n * 2);
                int skipped = engine.process(block, n * 2, level);
                add(block, skipped, n - skipped);
            }
        }

        void finish(SeparatedSing engine, int chunkFrames, float level) {
            engine.end();
            float[] block = new float[chunkFrames * 2];
            while (engine.hasTail()) add(block, 0, engine.drain(block, chunkFrames, level));
        }
    }

    private static SeparatedSing dry(int rate) {
        return new SeparatedSing(rate, input -> new float[input.length], Runnable::run, null);
    }

    private static void identity(int rate, int frames, int block, int chunk) {
        SeparatedSing engine = dry(rate);
        float[] original = signal(frames);
        Output out = new Output();
        out.feed(engine, original, block, 1);
        out.finish(engine, chunk, 1);
        String name = rate + " Hz, " + frames + " frames in blocks of " + block;
        check(out.frames == frames, name + ": every frame out, got " + out.frames);
        double error = 0;
        for (int i = 0; i < original.length; i++)
            error = Math.max(error, Math.abs(original[i] - out.data[i]));
        check(error < 1e-4, name + ": reconstruction " + error);
        check(!engine.hasTail(), name + ": nothing left");
        engine.close();
    }

    private static void silentPrefix() {
        SeparatedSing engine = dry(44100);
        float[] original = tone(44100 * 3, 440, 44100);
        original[0] = original[1] = .5f;
        Output out = new Output();
        out.feed(engine, original, 4096, 1);
        check(Math.abs(out.data[0] - .5f) < 1e-3, "the first frame played is the first frame in");
        float[] block = new float[engine.latencySamples() * 2];
        engine.reset();
        check(
                engine.process(block, block.length, 1) == engine.latencySamples(),
                "exactly the delay is held back, however much of a window is ready");
    }

    private static void levelZero() {
        float[] untouched = {-0f, Float.intBitsToFloat(0x7fc00001), .25f, -.5f};
        float[] before = untouched.clone();
        SeparatedSing absent =
                new SeparatedSing(
                        44100,
                        null,
                        Runnable::run,
                        (pcm, n, level) -> {
                            for (int i = 0; i < n; i++) pcm[i] *= .5f;
                        });
        check(absent.process(untouched, untouched.length, 0) == 0, "nothing suppressed at zero");
        for (int i = 0; i < before.length; i++)
            check(
                    Float.floatToRawIntBits(before[i]) == Float.floatToRawIntBits(untouched[i]),
                    "level zero is bit-identical");
        float[] fallback = {1, -1};
        check(absent.process(fallback, 2, 1) == 0, "no delay without a model");
        check(fallback[0] == .5f && fallback[1] == -.5f, "missing model uses filter");
    }

    /** A → slider at zero, song moves on → B: nothing of A may come back. */
    private static void noStaleAudio() {
        int rate = 44100;
        SeparatedSing engine = dry(rate);
        new Output().feed(engine, tone(rate * 3, 440, rate), 4096, 1);
        float[] moved = tone(4096, 700, rate), kept = moved.clone();
        engine.process(moved, moved.length, 0);
        check(Arrays.equals(moved, kept), "at zero the block passes untouched");
        check(!engine.hasTail(), "and nothing is owed");
        Output after = new Output();
        after.feed(engine, tone(rate * 3, 1000, rate), 4096, 1);
        check(
                after.frames == rate * 3 - engine.latencySamples(),
                "the delay starts again, with no zeros played: " + after.frames);
        check(power(after.data, 0, after.frames, 440, rate) < .002, "no trace of the old song");
        check(power(after.data, 0, after.frames, 1000, rate) > .3, "the new song plays");
    }

    private static void gaplessChange() {
        int rate = 44100;
        SeparatedSing engine = dry(rate);
        Output out = new Output();
        float[] first = signal(rate * 3), second = tone(rate * 3, 1000, rate);
        out.feed(engine, first, 4096, 1);
        engine.newTrack();
        out.feed(engine, second, 4096, 1);
        out.finish(engine, 1024, 1);
        check(out.frames == rate * 6, "a song change loses no frame: " + out.frames);
        double error = 0;
        for (int i = 0; i < first.length; i++)
            error = Math.max(error, Math.abs(first[i] - out.data[i]));
        for (int i = 0; i < second.length; i++)
            error = Math.max(error, Math.abs(second[i] - out.data[first.length + i]));
        check(error < 1e-4, "a song change is seamless: " + error);
    }

    /** Vocals everywhere: under a mask this cancels, so what's left shows which mask applied. */
    private static void outgoingSongKeepsItsMask() {
        int rate = 44100;
        List<float[]> seen = new ArrayList<>();
        SeparatedSing engine =
                new SeparatedSing(
                        rate,
                        magnitude -> {
                            seen.add(magnitude.clone());
                            return magnitude.clone();
                        },
                        Runnable::run,
                        null);
        Output out = new Output();
        float[] song = tone(rate * 6, 440, rate);
        out.feed(engine, song, 4096, 1);
        check(!seen.isEmpty(), "the model was asked");
        engine.newTrack();
        check(!engine.ready(), "the new song starts without an estimate made for the last one");
        int asked = seen.size();
        out.feed(engine, new float[rate * 4], 4096, 1);
        int boundary = rate * 6;
        // What was still in the delay when the song changed comes out under the old estimates.
        check(
                power(out.data, boundary - rate, boundary - 4096, 440, rate) < .02,
                "the end of a song stays separated though the next has begun");
        check(seen.size() > asked, "the new song gets its own estimates");
        float[] newest = seen.get(seen.size() - 1);
        double old = 0;
        int column = SeparatedSing.FRAMES - rate * 3 / SeparatedSing.HOP;
        for (int bin = 0; bin < SeparatedSing.BINS; bin++)
            for (int frame = 0; frame < column; frame++)
                old += newest[bin * SeparatedSing.FRAMES + frame];
        check(old == 0, "the old song isn't in what the model is shown for the new one: " + old);
    }

    private static void seekClearsEverything() {
        int rate = 44100;
        SeparatedSing engine = dry(rate);
        new Output().feed(engine, tone(rate * 3, 440, rate), 4096, 1);
        engine.reset();
        Output out = new Output();
        out.feed(engine, tone(rate * 3, 1000, rate), 4096, 1);
        out.finish(engine, 1024, 1);
        check(out.frames == rate * 3, "after a seek the new stream's frames, no others");
        check(power(out.data, 0, out.frames, 440, rate) < .002, "seek reset never emits old audio");
        check(engine.inputFrames() > 0, "counted from the seek");
    }

    private static void endThenMoreInput() {
        int rate = 44100;
        SeparatedSing engine = dry(rate);
        Output first = new Output();
        first.feed(engine, signal(rate), 4096, 1);
        first.finish(engine, 512, 1);
        check(first.frames == rate, "first stream complete");
        Output second = new Output();
        second.feed(engine, signal(rate), 4096, 1);
        second.finish(engine, 512, 1);
        check(second.frames == rate, "a stream after an end starts clean: " + second.frames);
    }

    private static void slowEstimatesDoNotQueue() {
        List<Runnable> pending = new ArrayList<>();
        SeparatedSing late =
                new SeparatedSing(44100, input -> new float[input.length], pending::add, null);
        float[] audio = signal(44100);
        late.process(audio, audio.length, 1);
        late.process(audio, audio.length, 1);
        check(pending.size() == 1, "late inference cannot grow the queue");
        late.close();
        pending.get(0).run();
        check(!late.ready(), "closed engine rejects stale results");
    }

    private static void outOfMemory() {
        int[] failed = {0}, filtered = {0};
        SeparatedSing engine =
                new SeparatedSing(
                        44100,
                        new SeparatedSing.Model() {
                            @Override
                            public float[] infer(float[] magnitude) {
                                throw new OutOfMemoryError("test");
                            }

                            @Override
                            public void failed() {
                                failed[0]++;
                            }
                        },
                        Runnable::run,
                        (pcm, n, level) -> filtered[0]++);
        float[] audio = signal(44100);
        engine.process(audio, audio.length, 1);
        check(failed[0] == 1, "memory running out is reported, not thrown on the worker");
        float[] more = {.5f, .5f};
        check(engine.process(more, 2, 1) == 0, "a closed engine holds nothing back");
        check(filtered[0] > 0 && !engine.ranLastBlock(), "and it goes straight to the filter");
    }

    private static void unavailableStopsAsking() {
        boolean[] available = {true};
        int[] asked = {0};
        SeparatedSing engine =
                new SeparatedSing(
                        44100,
                        new SeparatedSing.Model() {
                            @Override
                            public float[] infer(float[] magnitude) {
                                asked[0]++;
                                return new float[magnitude.length];
                            }

                            @Override
                            public boolean available() {
                                return available[0];
                            }
                        },
                        Runnable::run,
                        null);
        Output out = new Output();
        out.feed(engine, signal(44100), 4096, 1);
        int before = asked[0];
        check(before > 0, "asked while it can answer");
        available[0] = false;
        out.feed(engine, signal(44100), 4096, 1);
        check(asked[0] == before, "not asked while it can't");
        out.finish(engine, 1024, 1);
        check(out.frames == 88200, "and the delay carries on, losing nothing: " + out.frames);
    }

    // ── The model's own rules, with a stand-in for the runtime ──────────────

    private static void waitFor(java.util.function.BooleanSupplier done, String what)
            throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("Timed out: " + what);
            Thread.sleep(5);
        }
    }

    private static void flushIsolation() {
        int rate = 44100;
        SeparationModel.forTest(input -> new float[input.length], true);
        Object first = new Object(), second = new Object();
        VocalCut filterA = new VocalCut(rate), filterB = new VocalCut(rate);
        float[] a = tone(rate * 2, 440, rate), b = tone(rate * 2, 880, rate);
        int latency = dry(rate).latencySamples();
        check(
                SeparationModel.process(first, a, a.length, rate, 1, filterA, true) == latency,
                "a stream starts in the delay at once, not under the filter");
        check(
                SeparationModel.process(second, b, b.length, rate, 1, filterB, true) == latency,
                "so does the other");
        SeparatedSing other = SeparationModel.engine(second);
        long held = other.inputFrames();
        SeparationModel.flush(first);
        float[] c = new float[4096 * 2];
        SeparationModel.process(second, c, c.length, rate, 1, filterB, true);
        check(other.inputFrames() == held + 4096, "flushing one stream leaves the other running");
        float[] d = new float[4096 * 2];
        check(
                SeparationModel.process(first, d, d.length, rate, 1, filterA, true) == 4096,
                "the flushed one starts its delay again");
        check(SeparationModel.engine(first).inputFrames() == 4096, "from nothing");
        SeparationModel.forTest(null, false);
    }

    private static void bypassThroughTheModel() {
        int rate = 44100;
        SeparationModel.forTest(input -> new float[input.length], true);
        Object key = new Object();
        VocalCut filter = new VocalCut(rate);
        float[] a = tone(rate * 3, 440, rate);
        SeparationModel.process(key, a, a.length, rate, 1, filter, true);
        float[] passed = tone(4096, 700, rate), same = passed.clone();
        check(
                SeparationModel.process(key, passed, passed.length, rate, 0, filter, true) == 0,
                "level 0");
        check(Arrays.equals(passed, same), "level zero through the model is bit-identical");
        float[] b = tone(rate * 3, 1000, rate);
        int skipped = SeparationModel.process(key, b, b.length, rate, 1, filter, true);
        check(skipped == SeparationModel.engine(key).latencySamples(), "the delay starts again");
        check(power(b, skipped, b.length / 2, 440, rate) < .002, "A, then 0, then B: no A");
        SeparationModel.forTest(null, false);
    }

    private static void missingModelUsesTheFilter() {
        int rate = 44100;
        SeparationModel.forTest(null, true);
        check(SeparationModel.status().equals("Filter"), "nothing claims to be ready");
        Object key = new Object();
        float[] audio = signal(rate), original = audio.clone();
        check(
                SeparationModel.process(key, audio, audio.length, rate, 1, new VocalCut(rate), true)
                        == 0,
                "no delay without a model");
        check(SeparationModel.engine(key) == null, "and no engine");
        check(!Arrays.equals(audio, original), "the filter ran");
        SeparationModel.forTest(null, false);
    }

    private static void tooSlowBacksOff() throws Exception {
        SeparationModel.forTest(input -> new float[input.length], true);
        check(SeparationModel.MODEL.available(), "ready to begin");
        SeparationModel.noteInference(SeparationModel.BUDGET_MS + 1);
        SeparationModel.noteInference(SeparationModel.BUDGET_MS + 1);
        SeparationModel.noteInference(1);
        SeparationModel.noteInference(SeparationModel.BUDGET_MS + 1);
        SeparationModel.noteInference(SeparationModel.BUDGET_MS + 1);
        check(!SeparationModel.tooSlow(), "an estimate in time starts the count again");
        SeparationModel.noteInference(SeparationModel.BUDGET_MS + 1);
        check(SeparationModel.tooSlow(), "three late in a row is too slow");
        check(SeparationModel.status().equals(SeparationModel.TOO_SLOW), "and the page says so");
        check(!SeparationModel.MODEL.available(), "no more estimates are asked for");
        int rate = 44100;
        Object key = new Object();
        float[] audio = signal(rate);
        check(
                SeparationModel.process(key, audio, audio.length, rate, 1, new VocalCut(rate), true)
                        == 0,
                "a new stream goes straight to the filter while too slow");
        check(SeparationModel.engine(key) == null, "with no engine");
        SeparationModel.flush(key);
        check(SeparationModel.tooSlow(), "a seek before the wait is over changes nothing");
        Field retry = SeparationModel.class.getDeclaredField("retryAtMs");
        retry.setAccessible(true);
        retry.setLong(null, 0);
        SeparationModel.flush(key);
        check(!SeparationModel.tooSlow(), "a seek after the wait tries again");
        check(SeparationModel.status().equals("Model ready"), "and says so");
        SeparationModel.forTest(null, false);
    }

    private static void memoryFailureReleasesTheSession() throws Exception {
        int rate = 44100;
        boolean[] thrown = {false};
        SeparationModel.forTest(
                input -> {
                    thrown[0] = true;
                    throw new OutOfMemoryError("test");
                },
                true);
        Object key = new Object();
        VocalCut filter = new VocalCut(rate);
        float[] audio = signal(rate * 2);
        SeparationModel.process(key, audio, audio.length, rate, 1, filter, true);
        waitFor(() -> thrown[0], "the model to be asked");
        waitFor(() -> SeparationModel.engine(key) == null, "the engines to be released");
        waitFor(
                () -> SeparationModel.status().equals(SeparationModel.UNAVAILABLE),
                "the page to be told");
        float[] more = signal(4096), kept = more.clone();
        check(
                SeparationModel.process(key, more, more.length, rate, 1, filter, true) == 0,
                "after that, no delay");
        check(!Arrays.equals(more, kept), "just the filter");
        // The worker survived: it still does what it's asked.
        boolean[] worked = {false};
        SeparationModel.forTest(
                input -> {
                    worked[0] = true;
                    return new float[input.length];
                },
                true);
        float[] again = signal(rate * 2);
        SeparationModel.process(new Object(), again, again.length, rate, 1, filter, true);
        waitFor(() -> worked[0], "the worker to carry on");
        SeparationModel.forTest(null, false);
    }

    public static void main(String[] args) throws Exception {
        levelZero();
        check(SeparatedSing.mask(1, 0) == 0, "no vocal magnitude");
        check(Math.abs(SeparatedSing.mask(1, .5f) - .5f) < 1e-6, "balanced soft mask");
        check(SeparatedSing.mask(1, 2) <= 1, "mask bounded");
        check(SeparatedSing.mask(1, Float.NaN) == 0, "invalid model estimate");
        check(
                Math.abs(SeparatedSing.modelBin(1000, 48000) - 1000d * 48000 / 44100) < 1e-6,
                "frequency mapping to model grid");
        for (int rate : new int[] {44100, 48000}) {
            identity(rate, rate, 4096, 1024);
            identity(rate, rate * 3, 1000, 777);
            identity(rate, 1000, 4096, 1024);
            identity(rate, 1, 4096, 1024);
            identity(rate, dry(rate).latencySamples(), 512, 4096);
            identity(rate, dry(rate).latencySamples() + 1, 512, 1);
        }
        identity(96000, 96000, 4096, 1024);
        silentPrefix();
        noStaleAudio();
        gaplessChange();
        outgoingSongKeepsItsMask();
        seekClearsEverything();
        endThenMoreInput();
        slowEstimatesDoNotQueue();
        outOfMemory();
        unavailableStopsAsking();
        flushIsolation();
        bypassThroughTheModel();
        missingModelUsesTheFilter();
        tooSlowBacksOff();
        memoryFailureReleasesTheSession();
        System.out.println(
                "PASS: " + checks + " separation transform, timing and fallback checks.");
    }
}
