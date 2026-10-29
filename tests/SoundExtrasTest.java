package app.bitchord.telegram;

/**
 * Playback extras: Sing leaves the song alone at no cut and turns a centred voice down, the
 * loudness level moves BitChord's gain and nothing else, and the sleep fade ends silent.
 */
public final class SoundExtrasTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    /** BitChord's audio block after R8, with the same field names and types. */
    static final class Block {
        Object children;
        int terminalBitCount;
        int symbol;

        Block(float[] samples, int channels) {
            children = samples;
            symbol = channels;
            terminalBitCount = samples.length / channels;
        }
    }

    static final int RATE = 48_000;

    /** A sung-like note: a 220 Hz tone with harmonics, the same in both ears. */
    static float[] voice(int frames, float side) {
        float[] out = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            float centre =
                    (float)
                            (0.3 * Math.sin(2 * Math.PI * 220 * t)
                                    + 0.15 * Math.sin(2 * Math.PI * 440 * t)
                                    + 0.08 * Math.sin(2 * Math.PI * 660 * t));
            float wide = (float) (side * Math.sin(2 * Math.PI * 1_000 * t));
            out[2 * i] = centre + wide;
            out[2 * i + 1] = centre - wide;
        }
        return out;
    }

    static double centreEnergy(float[] samples, int from) {
        double sum = 0;
        for (int i = from * 2; i + 1 < samples.length; i += 2) {
            double mid = (samples[i] + samples[i + 1]) * 0.5;
            sum += mid * mid;
        }
        return sum;
    }

    static double sideEnergy(float[] samples, int from) {
        double sum = 0;
        for (int i = from * 2; i + 1 < samples.length; i += 2) {
            double side = (samples[i] - samples[i + 1]) * 0.5;
            sum += side * side;
        }
        return sum;
    }

    /** Runs a signal through in 1,024-frame blocks, as BitChord's sink does. */
    static float[] run(VocalCut singer, float[] input, float cut) {
        float[] out = input.clone();
        int block = 1_024 * 2;
        for (int at = 0; at < out.length; at += block) {
            int size = Math.min(block, out.length - at);
            float[] part = new float[size];
            System.arraycopy(out, at, part, 0, size);
            singer.process(part, size, cut);
            System.arraycopy(part, 0, out, at, size);
        }
        return out;
    }

    public static void main(String[] args) {
        int frames = RATE * 2;
        int settled = RATE; // after a second

        // No cut: not a sample changes, with either method.
        float[] song = voice(frames, 0.1f);
        for (int method : new int[] {VocalCut.VOICE_GATE, VocalCut.CENTRE}) {
            VocalCut singer = new VocalCut(RATE);
            singer.method = method;
            check(
                    java.util.Arrays.equals(run(singer, song, 0f), song),
                    "no cut leaves the block exactly as it came, method " + method);
        }

        // The voice is turned down, the sides are kept.
        VocalCut gate = new VocalCut(RATE);
        float[] gated = run(gate, song, 1f);
        double before = centreEnergy(song, settled), after = centreEnergy(gated, settled);
        check(after < before * 0.25, "voice gate cuts a held, centred note: " + after / before);
        double sideBefore = sideEnergy(song, settled), sideAfter = sideEnergy(gated, settled);
        check(
                sideAfter > sideBefore * 0.7 && sideAfter < sideBefore * 2,
                "voice gate keeps the sides: " + sideAfter / sideBefore);
        boolean clean = true;
        for (float sample : gated) clean &= Math.abs(sample) <= 1f && !Float.isNaN(sample);
        check(clean, "no clipping or NaN");

        VocalCut centre = new VocalCut(RATE);
        centre.method = VocalCut.CENTRE;
        float[] mono = voice(RATE / 2, 0f);
        double left = centreEnergy(run(centre, mono, 1f), 0);
        check(left < 1e-9, "the whole centre is removed at full cut: " + left);
        float[] half = run(new VocalCut(RATE), song, 0.5f);
        double halfLeft = centreEnergy(half, settled);
        check(halfLeft > after && halfLeft < before, "half a cut is in between");

        // A reset after a seek starts cleanly, and a new rate is taken.
        gate.reset();
        gate.configure(44_100);
        check(gate.sampleRate == 44_100, "the sample rate follows the sink");
        gate.configure(1);
        check(gate.sampleRate == 8_000, "a nonsense rate isn't used");

        // Through the hook, on a block shaped like BitChord's.
        Object sink = new Object();
        float[] samples = voice(RATE, 0f);
        float[] untouched = samples.clone();
        SoundExtras.singLevel = 0;
        SoundExtras.process(sink, new Block(samples, 2), RATE);
        check(java.util.Arrays.equals(samples, untouched), "Sing off changes nothing");
        SoundExtras.singLevel = 100;
        SoundExtras.singMethod = VocalCut.CENTRE;
        SoundExtras.process(sink, new Block(samples, 2), RATE);
        check(centreEnergy(samples, 0) < 1e-9, "Sing on cuts the block in place");
        float[] surround = voice(RATE / 10, 0f);
        float[] surroundBefore = surround.clone();
        SoundExtras.process(sink, new Block(surround, 6), RATE);
        check(java.util.Arrays.equals(surround, surroundBefore), "only stereo blocks are cut");
        SoundExtras.flush(sink);
        SoundExtras.flush(new Object());
        SoundExtras.process(sink, null, RATE);
        SoundExtras.singLevel = 0;
        SoundExtras.singMethod = VocalCut.VOICE_GATE;

        // BitChord's usual mode: the equaliser's 16-bit block, changed in place.
        float[] tone = voice(RATE, 0f);
        java.nio.ByteBuffer pcm =
                java.nio.ByteBuffer.allocateDirect(tone.length * 2)
                        .order(java.nio.ByteOrder.nativeOrder());
        for (float sample : tone) pcm.putShort((short) Math.round(sample * 32767));
        pcm.flip();
        java.nio.ByteBuffer original = java.nio.ByteBuffer.allocate(pcm.remaining());
        original.put(pcm.duplicate()).flip();
        Object equaliser = new Object();
        SoundExtras.singLevel = 0;
        SoundExtras.processPcm16(equaliser, pcm, 2, RATE);
        check(pcm.equals(original), "Sing off leaves the 16-bit block alone");
        SoundExtras.singLevel = 100;
        SoundExtras.singMethod = VocalCut.CENTRE;
        SoundExtras.processPcm16(equaliser, pcm, 2, RATE);
        check(pcm.position() == 0 && pcm.limit() == original.limit(), "the block's bounds stay");
        long centreLeft = 0;
        for (int i = 0; i + 3 < pcm.limit(); i += 4)
            centreLeft += Math.abs(pcm.getShort(i) + pcm.getShort(i + 2));
        check(centreLeft < tone.length, "Sing cuts the 16-bit block: " + centreLeft);
        java.nio.ByteBuffer six = original.duplicate();
        SoundExtras.processPcm16(equaliser, six, 6, RATE);
        check(six.equals(original), "only stereo is cut");
        SoundExtras.processPcm16(equaliser, java.nio.ByteBuffer.allocate(0), 2, RATE);
        SoundExtras.singLevel = 0;
        SoundExtras.singMethod = VocalCut.VOICE_GATE;

        // Loudness level.
        check(SoundExtras.loudness(null) == null, "no gain stays no gain");
        SoundExtras.targetLufs = SoundExtras.HOST_TARGET_LUFS;
        check(SoundExtras.loudness(-620) == -620, "BitChord's level changes nothing");
        check(SoundExtras.loudness(300) == 300, "BitChord's limit at its own level");
        SoundExtras.targetLufs = -10;
        check(SoundExtras.loudness(-620) == -220, "4 dB louder");
        check(SoundExtras.loudness(300) == 700, "the upper limit moves with a louder level");
        check(SoundExtras.loudness(-1500) == -1100, "quiet songs come up too");
        SoundExtras.targetLufs = -18;
        check(SoundExtras.loudness(-620) == -1020, "4 dB quieter");
        check(SoundExtras.loudness(-1500) == -1900, "the lower limit moves with a quieter level");
        check(SoundExtras.loudness(300) == -100, "loud songs come down too");
        SoundExtras.setTargetLufs(-40);
        check(SoundExtras.targetLufs == SoundExtras.MIN_TARGET_LUFS, "the level has a floor");
        SoundExtras.setTargetLufs(0);
        check(SoundExtras.targetLufs == SoundExtras.MAX_TARGET_LUFS, "and a ceiling");
        SoundExtras.targetLufs = SoundExtras.HOST_TARGET_LUFS;

        // Pitch.
        check(SoundExtras.pitchFactor(0) == 1f, "no change");
        check(Math.abs(SoundExtras.pitchFactor(12) - 2f) < 1e-6, "an octave up doubles");
        check(Math.abs(SoundExtras.pitchFactor(-12) - 0.5f) < 1e-6, "an octave down halves");
        SoundExtras.setPitch(30);
        check(SoundExtras.pitchSemitones == SoundExtras.MAX_PITCH, "pitch is limited");
        SoundExtras.setPitch(0);

        // Sleep fade: from full to silent, never louder on the way.
        check(SoundExtras.fade(0, 100) == 1f, "starts at full volume");
        check(SoundExtras.fade(100, 100) == 0f, "ends silent");
        for (int step = 1; step <= 100; step++)
            check(
                    SoundExtras.fade(step, 100) <= SoundExtras.fade(step - 1, 100),
                    "the fade only goes down");

        check(!SoundExtras.losslessAnywhere(), "lossless addons wait for lossless output");
        SoundExtras.losslessOverBluetooth = true;
        check(SoundExtras.losslessAnywhere(), "unless asked");
        SoundExtras.losslessOverBluetooth = false;

        // Labels.
        check(SoundExtras.semitones(0).equals("Original key"), SoundExtras.semitones(0));
        check(SoundExtras.semitones(2).equals("+2 semitones"), SoundExtras.semitones(2));
        check(SoundExtras.semitones(-1).equals("−1 semitone"), SoundExtras.semitones(-1));
        check(SoundExtras.lufs(-14).equals("−14 LUFS (BitChord’s level)"), SoundExtras.lufs(-14));
        check(SoundExtras.lufs(-9).equals("−9 LUFS"), SoundExtras.lufs(-9));
        // The patch's pages follow BitChord's own language choice, saved by AndroidX.
        check(
                Strings.recorded(
                                "<?xml version='1.0' ?><locales application_locales=\"fr-CA,en\"/>")
                        .getLanguage()
                        .equals("fr"),
                "the first language picked in BitChord");
        check(Strings.recorded("<locales application_locales=\"\"/>") == null, "none picked");
        check(Strings.recorded("not that file") == null, "something else");
        System.out.println("PASS: " + checks + " playback extras checks.");
    }
}
