package app.bitchord.telegram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sing through a stand-in for BitChord's sink, which goes through the same steps as the real one's
 * bytecode with the hooks in place, down to a delegate that takes only part of a buffer at a time.
 */
public final class SingOutputTest {
    static int checks;
    static final int RATE = 44100;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    /** Field names as the host's audio block has them. */
    public static final class Block {
        public final Object children = new float[8192];
        public final int symbol = 2;
        public int terminalBitCount;
    }

    /** Takes what the audio track has room for, like the host's own delegate. */
    public static final class Delegate {
        final List<long[]> writes = new ArrayList<>();
        float[] played = new float[1 << 20];
        int frames, discontinuities, jumps, emptyWrites;
        ByteBuffer partial;
        androidx.media3.common.PlaybackParameters parameters =
                new androidx.media3.common.PlaybackParameters(1f, 1f);
        boolean parametersFail;

        public androidx.media3.common.PlaybackParameters getPlaybackParameters() {
            if (parametersFail) throw new IllegalStateException("Not available");
            return parameters;
        }

        int room = 2048;
        boolean ended;
        long startUs = Long.MIN_VALUE;

        public boolean handleBuffer(int units, long timeUs, ByteBuffer buffer) {
            if (ended) throw new AssertionError("Audio after the delegate was told it had ended");
            if (!buffer.hasRemaining()) {
                emptyWrites++;
                return true;
            }
            // As the real one does: a buffer it took part of must come back, and is checked once.
            if (partial != null && partial != buffer)
                throw new AssertionError("A new buffer before the last one was finished");
            if (partial == null) {
                if (startUs == Long.MIN_VALUE) startUs = timeUs;
                long expected = startUs + frames * 1_000_000L / RATE;
                // The real sink raises an error past 200 ms unless it was told about a jump.
                if (Math.abs(expected - timeUs) > 200_000 && discontinuities == 0)
                    throw new AssertionError("Unexpected time " + timeUs + ", wanted " + expected);
                if (Math.abs(expected - timeUs) > 200_000) {
                    discontinuities = 0;
                    jumps++;
                    startUs = timeUs - frames * 1_000_000L / RATE;
                }
                writes.add(new long[] {timeUs - startUs, frames});
            }
            int take = Math.min(room, buffer.remaining() / 8);
            for (int i = 0; i < take * 2; i++) {
                if (frames * 2 + i >= played.length)
                    played = Arrays.copyOf(played, played.length * 2);
                played[frames * 2 + i] = buffer.getFloat();
            }
            frames += take;
            partial = buffer.hasRemaining() ? buffer : null;
            return !buffer.hasRemaining();
        }

        public void handleDiscontinuity() {
            discontinuities++;
        }
    }

    /** Field and method names as the patched host sink has them. */
    public static final class Sink {
        public final Delegate delegate = new Delegate();
        public final Block audioBlock = new Block();
        public ByteBuffer outputByteBuffer =
                ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.nativeOrder());
        public long pendingPresentationTimeUs = Long.MIN_VALUE + 1, processedEndUs;
        public int pendingAccessUnitCount, configuredSampleRate = RATE;
        long timestamped = Long.MIN_VALUE + 1, emitted, totalIn;
        Object configured;

        Sink() {
            outputByteBuffer.flip();
        }

        static long dur(long frames) {
            return frames * 1_000_000L / RATE;
        }

        static boolean write(Delegate delegate, int units, long timeUs, ByteBuffer buffer) {
            long time = SingOutput.outputTimeUs(delegate, timeUs, buffer);
            if (SingOutput.skipsWrite(delegate, buffer)) return true;
            return delegate.handleBuffer(units, time, buffer);
        }

        void encode() {
            float[] samples = (float[]) audioBlock.children;
            outputByteBuffer.clear();
            for (int i = 0; i < audioBlock.terminalBitCount * 2; i++)
                outputByteBuffer.putFloat(samples[i]);
            outputByteBuffer.flip();
        }

        public boolean bitchordSingEmit(long timeUs) {
            encode();
            pendingPresentationTimeUs = timeUs;
            pendingAccessUnitCount = 0;
            return write(delegate, 0, timeUs, outputByteBuffer);
        }

        public boolean bitchordSingRetry() {
            if (!outputByteBuffer.hasRemaining()) return true;
            return write(
                    delegate, pendingAccessUnitCount, pendingPresentationTimeUs, outputByteBuffer);
        }

        public void configure(Object config) {
            if (SingOutput.configure(this, config)) return;
            configured = config;
        }

        boolean handleBuffer(long timeUs, ByteBuffer input) {
            if (SingOutput.holdsInput(this)) return false;
            if (outputByteBuffer.hasRemaining()) {
                boolean took =
                        write(
                                delegate,
                                pendingAccessUnitCount,
                                pendingPresentationTimeUs,
                                outputByteBuffer);
                if (!took || outputByteBuffer.hasRemaining()) return false;
            }
            if (timeUs != timestamped) {
                timestamped = timeUs;
                emitted = 0;
            }
            float[] samples = (float[]) audioBlock.children;
            while (input.remaining() >= 8) {
                int frames = Math.min(input.remaining() / 8, 4096);
                for (int i = 0; i < frames * 2; i++) samples[i] = input.getFloat();
                audioBlock.terminalBitCount = frames;
                SoundExtras.process(this, audioBlock);
                encode();
                long blockTime = timeUs + dur(emitted);
                processedEndUs = blockTime + dur(frames);
                pendingPresentationTimeUs = blockTime;
                pendingAccessUnitCount = 0;
                emitted += frames;
                boolean took = write(delegate, 0, blockTime, outputByteBuffer);
                if (!took || outputByteBuffer.hasRemaining()) return false;
            }
            return !input.hasRemaining() && !outputByteBuffer.hasRemaining();
        }

        void playToEnd() {
            if (SingOutput.playToEnd(this)) return;
            if (outputByteBuffer.hasRemaining())
                write(
                        delegate,
                        pendingAccessUnitCount,
                        pendingPresentationTimeUs,
                        outputByteBuffer);
            delegate.ended = true;
        }

        boolean isEnded() {
            if (outputByteBuffer.hasRemaining()) return false;
            // As the real delegate: one that was never given audio counts as finished.
            return delegate.startUs == Long.MIN_VALUE || delegate.ended;
        }

        boolean hasPendingData() {
            return outputByteBuffer.hasRemaining();
        }

        void flush() {
            SoundExtras.flush(this);
            outputByteBuffer.clear();
            outputByteBuffer.flip();
            timestamped = Long.MIN_VALUE + 1;
            emitted = 0;
            delegate.frames = 0;
            delegate.startUs = Long.MIN_VALUE;
            delegate.writes.clear();
            delegate.partial = null;
            delegate.ended = false;
        }

        /** The renderer: retries until the buffer is taken, ticking the track's room back. */
        void play(float[] pcm, long startUs) {
            totalIn = delegate.frames + (long) pcm.length / 2;
            int block = 8192;
            for (int at = 0; at < pcm.length / 2; at += block) {
                int n = Math.min(block, pcm.length / 2 - at);
                ByteBuffer input = ByteBuffer.allocate(n * 8).order(ByteOrder.nativeOrder());
                for (int i = 0; i < n * 2; i++) input.putFloat(pcm[at * 2 + i]);
                input.flip();
                long time = startUs + dur(at);
                int tries = 0;
                while (!handleBuffer(time, input))
                    if (++tries > 100_000) throw new AssertionError("The sink never took a buffer");
            }
        }

        void finish() {
            int tries = 0;
            do {
                playToEnd();
                if (delegate.frames < totalIn) {
                    check(!isEnded(), "not ended while the delay holds audio");
                    check(hasPendingData(), "and that audio counts as pending");
                }
                if (++tries > 100_000) throw new AssertionError("The stream never ended");
            } while (!isEnded());
        }
    }

    static float[] tone(int frames, double hz) {
        float[] out = new float[frames * 2];
        for (int i = 0; i < frames; i++)
            out[2 * i] = out[2 * i + 1] = (float) (.4 * Math.sin(2 * Math.PI * hz * i / RATE));
        return out;
    }

    static double power(float[] pcm, int from, int to, double hz) {
        double re = 0, im = 0;
        for (int i = from; i < to; i++) {
            re += pcm[2 * i] * Math.cos(2 * Math.PI * hz * i / RATE);
            im += pcm[2 * i] * Math.sin(2 * Math.PI * hz * i / RATE);
        }
        return 2 * Math.hypot(re, im) / Math.max(1, to - from);
    }

    static void on() {
        SoundExtras.singEnabled = true;
        SoundExtras.singLevel = 100;
        SoundExtras.singMethod = SeparationModel.METHOD;
        SeparationModel.forTest(input -> new float[input.length], true);
    }

    static void checkTimes(Delegate delegate, long startUs, String name) {
        for (long[] write : delegate.writes) {
            long expected = (startUs - delegate.startUs) + write[1] * 1_000_000L / RATE;
            // Each block's duration is rounded to a microsecond; the real check allows 200 ms.
            check(
                    Math.abs(write[0] - expected) <= 150,
                    name + ": time " + write[0] + " not " + expected);
        }
    }

    static void wholeStream(int frames, int room, long startUs) {
        on();
        Sink sink = new Sink();
        sink.delegate.room = room;
        String name = frames + " frames, room " + room;
        float[] pcm = tone(frames, 440);
        pcm[0] = pcm[1] = .5f;
        sink.play(pcm, startUs);
        sink.finish();
        check(sink.delegate.frames == frames, name + ": frames out " + sink.delegate.frames);
        check(sink.delegate.emptyWrites == 0, name + ": the delegate never saw an empty buffer");
        check(
                Math.abs(sink.delegate.startUs - startUs) <= 150,
                name + ": first audio at track time, not later: " + sink.delegate.startUs);
        check(sink.delegate.played[0] != 0, name + ": no silence before the first frame");
        checkTimes(sink.delegate, startUs, name);
        check(sink.isEnded(), name + ": ended");
        check(sink.processedEndUs <= startUs + frames * 1_000_000L / RATE, name + ": lead is real");
    }

    static void seek() {
        on();
        Sink sink = new Sink();
        sink.play(tone(RATE * 3, 440), 0);
        sink.flush();
        check(!SingOutput.holds(sink), "nothing is owed after a seek");
        long seekUs = 50_000_000;
        sink.play(tone(RATE * 3, 1000), seekUs);
        sink.finish();
        check(sink.delegate.frames == RATE * 3, "after a seek, just the new frames");
        check(sink.delegate.startUs == seekUs, "starting at the seek position");
        checkTimes(sink.delegate, seekUs, "seek");
        check(power(sink.delegate.played, 0, sink.delegate.frames, 440) < .01, "no old audio");
    }

    static void gapless() {
        on();
        Sink sink = new Sink();
        sink.play(tone(RATE * 3, 440), 0);
        int written = sink.delegate.frames;
        check(written > 0, "playing");
        SingOutput.streamBoundary(sink);
        check(sink.delegate.frames == written, "the song change doesn't touch what's held");
        sink.play(tone(RATE * 3, 1000), 3_000_000);
        sink.finish();
        check(
                sink.delegate.frames == RATE * 6,
                "every frame of both songs: " + sink.delegate.frames);
        checkTimes(sink.delegate, 0, "gapless");
        check(sink.delegate.jumps == 0, "no jump anywhere");
    }

    static void levelZeroThenBack() {
        on();
        Sink sink = new Sink();
        sink.play(tone(RATE * 3, 440), 0);
        check(sink.delegate.jumps == 0, "no jump yet");
        SoundExtras.singLevel = 0;
        sink.play(tone(RATE, 700), 3_000_000);
        check(!SingOutput.holds(sink), "nothing owed at zero");
        int atZero = sink.delegate.frames;
        check(
                Math.abs(power(sink.delegate.played, atZero - RATE, atZero, 700) - .4) < .01,
                "untouched at zero, straight away");
        check(power(sink.delegate.played, atZero - RATE, atZero, 440) < .01, "no old song in it");
        SoundExtras.singLevel = 100;
        sink.play(tone(RATE * 3, 1000), 4_000_000);
        sink.finish();
        int total = sink.delegate.frames;
        check(power(sink.delegate.played, atZero, total, 440) < .01, "A, 0, B: no A");
        check(power(sink.delegate.played, atZero, total, 700) < .01, "and none of the bypass");
        check(total == atZero + RATE * 3, "B whole: " + (total - atZero));
        check(
                sink.delegate.jumps == 1,
                "the one jump is the bypass, announced: " + sink.delegate.jumps);
    }

    static void twoSinks() {
        on();
        Sink first = new Sink(), second = new Sink();
        first.play(tone(RATE * 2, 440), 0);
        second.play(tone(RATE * 2, 880), 0);
        first.flush();
        second.play(tone(RATE, 880), 2_000_000);
        second.finish();
        check(second.delegate.frames == RATE * 3, "another stream's flush costs this one nothing");
        check(second.delegate.jumps == 0, "and no jump");
        checkTimes(second.delegate, 0, "other stream");
    }

    static void formatChange() {
        on();
        Sink sink = new Sink();
        sink.delegate.room = 512;
        sink.play(tone(RATE * 3, 440), 0);
        int before = sink.delegate.frames;
        check(before < RATE * 3, "the delay holds audio");
        check(SingOutput.holdFormatForTest(sink, "next format"), "the change is held back");
        check(sink.configured == null, "not applied yet");
        ByteBuffer next = ByteBuffer.allocate(8192 * 8).order(ByteOrder.nativeOrder());
        next.limit(8192 * 8);
        int refused = 0;
        while (sink.configured == null) {
            check(!sink.handleBuffer(3_000_000, next) || sink.configured != null, "input waits");
            if (sink.configured == null)
                check(next.position() == 0, "not a frame of the next song is taken meanwhile");
            if (++refused > 100_000) throw new AssertionError("Never applied");
        }
        check(sink.delegate.frames >= RATE * 3, "the old format's audio all went first");
        check(sink.delegate.frames == RATE * 3, "and only it: " + sink.delegate.frames);
        check(sink.configured.equals("next format"), "then the change is applied");
        check(!SingOutput.holds(sink), "with nothing owed");
    }

    /** The host's configuration, whose format can't otherwise be made off a device. */
    public static final class Config {
        public final androidx.media3.common.Format format;

        Config(int rate, int channels) throws Exception {
            java.lang.reflect.Constructor<androidx.media3.common.Format> make =
                    androidx.media3.common.Format.class.getDeclaredConstructor();
            make.setAccessible(true);
            format = make.newInstance();
            for (String[] field :
                    new String[][] {
                        {"sampleRate", String.valueOf(rate)},
                        {"channelCount", String.valueOf(channels)}
                    }) {
                java.lang.reflect.Field member =
                        androidx.media3.common.Format.class.getDeclaredField(field[0]);
                member.setAccessible(true);
                member.setInt(format, Integer.parseInt(field[1]));
            }
        }
    }

    static void realConfigure() throws Exception {
        on();
        Sink sink = new Sink();
        sink.play(tone(RATE * 3, 440), 0);
        Config same = new Config(RATE, 2);
        sink.configure(same);
        check(sink.configured == same, "the same format changes nothing, even a new song's");
        check(!SingOutput.holds(sink), "and nothing is drained for it");
        sink.play(tone(RATE, 440), 3_000_000);
        check(
                sink.delegate.frames == RATE * 4 - SeparationModel.engine(sink).latencySamples(),
                "on");
        Config faster = new Config(48000, 2);
        sink.configure(faster);
        check(sink.configured == same, "another sample rate waits for the old audio");
        ByteBuffer none = ByteBuffer.allocate(0);
        int tries = 0;
        while (sink.configured != faster) {
            sink.handleBuffer(4_000_000, none);
            if (++tries > 100_000) throw new AssertionError("Never applied");
        }
        check(sink.delegate.frames == RATE * 4, "every frame first: " + sink.delegate.frames);
        Config surround = new Config(RATE, 6);
        Sink other = new Sink();
        other.play(tone(RATE * 3, 440), 0);
        other.configure(surround);
        check(other.configured == null, "so does a change of channels");
    }

    static void formatChangeFlushed() {
        on();
        Sink sink = new Sink();
        sink.play(tone(RATE * 3, 440), 0);
        SingOutput.holdFormatForTest(sink, "next format");
        sink.flush();
        check("next format".equals(sink.configured), "a seek drops the audio, not the new format");
        Sink dropped = new Sink();
        dropped.play(tone(RATE * 3, 440), 0);
        SingOutput.holdFormatForTest(dropped, "gone");
        SoundExtras.sinkReset(dropped);
        check(dropped.configured == null, "a reset drops both");
        check(!SingOutput.holdsInput(dropped), "and nothing waits for it");
    }

    static void filterModesUntouched() {
        SoundExtras.singEnabled = true;
        SoundExtras.singLevel = 100;
        SoundExtras.singMethod = VocalCut.VOICE_GATE;
        SeparationModel.forTest(null, false);
        Sink sink = new Sink();
        long startUs = 7_000_000;
        sink.play(tone(RATE, 440), startUs);
        check(sink.delegate.frames == RATE, "a filter adds no delay");
        check(sink.delegate.startUs == startUs, "and no shift");
        check(!SingOutput.playToEnd(sink), "and has no tail");
        check(!SingOutput.holds(sink), "nor holds anything");
        check(SingOutput.outputTimeUs(sink.delegate, 5, sink.outputByteBuffer) == 5, "time as is");
        check(!SingOutput.skipsWrite(sink.delegate, sink.outputByteBuffer), "no writes skipped");
    }

    static void params(Sink sink, float speed, float pitch) {
        sink.delegate.parameters = new androidx.media3.common.PlaybackParameters(speed, pitch);
    }

    /** One sink's speed and pitch decide for that sink alone. */
    static void streamsDecideForThemselves() {
        for (float[] other : new float[][] {{1.2f, 1f}, {1f, 1.189f}, {.8f, 1f}, {1f, .84f}}) {
            on();
            Sink changed = new Sink(), normal = new Sink();
            int latency = new SeparatedSing(RATE, null, r -> {}, null).latencySamples();
            changed.play(tone(RATE * 3, 440), 0);
            normal.play(tone(RATE * 3, 880), 0);
            SeparatedSing engine = SeparationModel.engine(normal);
            long before = engine.inputFrames();
            check(before == RATE * 3, "the other stream is in its delay");
            params(changed, other[0], other[1]);
            changed.play(tone(RATE, 700), 3_000_000);
            check(!SingOutput.holds(changed), "the changed stream owes nothing");
            int atSpeed = changed.delegate.frames;
            check(
                    atSpeed == RATE * 3 - latency + RATE,
                    "the changed stream plays undelayed from there: " + atSpeed);
            check(changed.delegate.jumps == 1, "with its jump announced");
            double left = power(changed.delegate.played, atSpeed - RATE / 2, atSpeed, 700);
            check(left < .2, "under the filter: " + left);
            check(SeparationModel.status().startsWith("Filter fallback"), "and the page says why");
            normal.play(tone(RATE, 880), 3_000_000);
            check(engine == SeparationModel.engine(normal), "the other engine is the same one");
            check(engine.inputFrames() == before + RATE, "still running, not reset");
            check(normal.delegate.jumps == 0, "the other stream doesn't jump");
            check(
                    normal.delegate.frames == RATE * 4 - latency,
                    "or lose anything: " + normal.delegate.frames);
            check(
                    !SeparationModel.status().startsWith("Filter fallback"),
                    "the page follows the stream that's playing");

            // Back to normal: the delay starts again, with no audio from before the change.
            params(changed, 1f, 1f);
            changed.play(tone(RATE * 3, 1000), 4_000_000);
            changed.finish();
            int all = changed.delegate.frames;
            check(all == atSpeed + RATE * 3, "all of it, after a fresh delay: " + (all - atSpeed));
            check(
                    power(changed.delegate.played, atSpeed, all, 440) < .01,
                    "nothing of the song before");
            check(
                    power(changed.delegate.played, atSpeed, all, 700) < .01,
                    "nothing of what played at the other speed");
            normal.finish();
            check(normal.delegate.frames == RATE * 4, "the other stream finishes whole");
        }
    }

    /** A stream that starts at another speed never holds audio back. */
    static void startsAtAnotherSpeed() {
        on();
        Sink sink = new Sink();
        params(sink, 1.2f, 1f);
        long startUs = 9_000_000;
        sink.play(tone(RATE, 440), startUs);
        check(sink.delegate.frames == RATE, "undelayed from its first frame");
        check(sink.delegate.startUs == startUs, "at its own time");
        check(SeparationModel.engine(sink) == null, "with no engine made for it");
        // It's already been heard, so going back to normal isn't a stream's start: nothing that
        // follows may be taken as time before the stream began.
        params(sink, 1f, 1f);
        sink.play(tone(RATE, 440), startUs + 1_000_000);
        check(sink.delegate.frames > RATE, "the next block still plays: " + sink.delegate.frames);
        sink.finish();
        check(sink.delegate.frames == RATE * 2, "and all of it: " + sink.delegate.frames);
        check(sink.delegate.startUs == startUs, "with its time unmoved");
    }

    /** If the sink can't say, this stream gets the filter, and nothing fails or fills the log. */
    static void unknownMeansTheFilter() {
        on();
        Sink failing = new Sink(), missing = new Sink(), normal = new Sink();
        failing.delegate.parametersFail = true;
        missing.delegate.parameters = null;
        normal.play(tone(RATE * 3, 880), 0);
        long held = SeparationModel.engine(normal).inputFrames();
        failing.play(tone(RATE, 440), 0);
        missing.play(tone(RATE, 440), 0);
        check(failing.delegate.frames == RATE, "a sink that throws: undelayed");
        check(missing.delegate.frames == RATE, "a sink with no answer: undelayed");
        // The tone's .4 in both channels, so the filter's what brings it down.
        for (Sink filtered : new Sink[] {failing, missing}) {
            double left = power(filtered.delegate.played, RATE / 2, RATE, 440);
            check(left < .2, "and filtered, not left as it was: " + left);
        }
        check(SeparationModel.engine(failing) == null, "no engine for it");
        check(SeparationModel.engine(normal).inputFrames() == held, "the other stream untouched");
        failing.delegate.parametersFail = false;
        check(SingOutput.playsNormally(failing), "and it recovers when the sink can say again");
    }

    /** The same shape as the sink, over a delegate that has no such getter. */
    static final class BareSink {
        final Object delegate = new Object();
        final Block audioBlock = new Block();
        ByteBuffer outputByteBuffer;
        long pendingPresentationTimeUs, processedEndUs;

        public void bitchordSingEmit(long timeUs) {}

        public boolean bitchordSingRetry() {
            return true;
        }
    }

    static void missingGetterMeansTheFilter() throws Exception {
        java.lang.reflect.Field port = SingOutput.class.getDeclaredField("port");
        port.setAccessible(true);
        port.set(null, null);
        try {
            check(!SingOutput.playsNormally(new BareSink()), "no getter: not normal");
            check(port.get(null) != null, "and the rest of the sink's still known");
        } finally {
            port.set(null, null);
        }
    }

    public static void main(String[] args) throws Exception {
        long start = 1_000_000_000_000L;
        for (int room : new int[] {100_000, 2048, 300}) {
            wholeStream(RATE * 4, room, start);
            wholeStream(1000, room, start);
            wholeStream(1, room, 0);
        }
        seek();
        gapless();
        levelZeroThenBack();
        twoSinks();
        streamsDecideForThemselves();
        startsAtAnotherSpeed();
        unknownMeansTheFilter();
        missingGetterMeansTheFilter();
        formatChange();
        realConfigure();
        formatChangeFlushed();
        filterModesUntouched();
        SoundExtras.singEnabled = false;
        SoundExtras.singMethod = VocalCut.VOICE_GATE;
        SeparationModel.forTest(null, false);
        System.out.println("PASS: " + checks + " Sing sink output checks.");
    }
}
