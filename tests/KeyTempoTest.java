package app.bitchord.telegram;

import java.util.Random;

/**
 * Key and tempo detection on made-up music: a chord progression in a known key, beats at a known
 * tempo.
 */
public final class KeyTempoTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static final int RATE = KeyTempo.RATE;

    /** A note with a few harmonics, decaying over the beat. */
    static void note(float[] out, int from, int length, double hz, double level) {
        for (int i = 0; i < length && from + i < out.length; i++) {
            double t = (double) i / RATE;
            double env = Math.exp(-t * 2.5) * level;
            out[from + i] +=
                    (float)
                            (env
                                    * (Math.sin(2 * Math.PI * hz * t)
                                            + 0.5 * Math.sin(2 * Math.PI * 2 * hz * t)
                                            + 0.25 * Math.sin(2 * Math.PI * 3 * hz * t)));
        }
    }

    static double hz(int midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    /**
     * A song: chords from the key's scale on every beat at {@code bpm}, with a kick on each beat
     * and a hi-hat between, plus a little noise.
     */
    static float[] song(int seconds, int rootMidi, boolean minor, double bpm, long seed) {
        float[] out = new float[seconds * RATE];
        int beat = (int) Math.round(RATE * 60 / bpm);
        int[][] majorChords = {{0, 4, 7}, {5, 9, 12}, {7, 11, 14}, {9, 12, 16}}; // I IV V vi
        int[][] minorChords = {{0, 3, 7}, {5, 8, 12}, {7, 10, 14}, {8, 12, 15}}; // i iv v VI
        int[][] chords = minor ? minorChords : majorChords;
        Random random = new Random(seed);
        int b = 0;
        for (int at = 0; at + beat <= out.length; at += beat, b++) {
            int[] chord = chords[(b / 2) % chords.length];
            for (int interval : chord) note(out, at, beat, hz(rootMidi + interval), 0.12);
            note(out, at, beat, hz(rootMidi - 12 + chord[0]), 0.18); // bass
            // Kick: a short low thump; hi-hat: a shorter burst of noise halfway.
            for (int i = 0; i < RATE / 12 && at + i < out.length; i++)
                out[at + i] +=
                        (float)
                                (0.4
                                        * Math.exp(-i * 40.0 / RATE)
                                        * Math.sin(2 * Math.PI * 60 * i / RATE));
            int half = at + beat / 2;
            for (int i = 0; i < RATE / 40 && half + i < out.length; i++)
                out[half + i] +=
                        (float) (0.15 * Math.exp(-i * 120.0 / RATE) * random.nextGaussian());
        }
        for (int i = 0; i < out.length; i++) out[i] += (float) (0.004 * random.nextGaussian());
        return out;
    }

    public static void main(String[] args) {
        // Camelot wheel.
        check(KeyTempo.camelot(0, false).equals("8B"), "C major is 8B");
        check(KeyTempo.camelot(9, true).equals("8A"), "A minor is 8A");
        check(KeyTempo.camelot(7, false).equals("9B"), "G major is 9B");
        check(KeyTempo.camelot(11, false).equals("1B"), "B major is 1B");
        check(KeyTempo.camelot(8, true).equals("1A"), "A♭ minor is 1A");
        check(KeyTempo.camelot(6, true).equals("11A"), "F♯ minor is 11A");
        check(KeyTempo.camelotOf("C major").equals("8B"), "BitChord's key names");
        check(KeyTempo.camelotOf("A minor").equals("8A"), "minor");
        check(KeyTempo.camelotOf("F# min").equals("11A"), "sharp, short mode");
        check(KeyTempo.camelotOf("Bbm").equals("3A"), "flat, m");
        check(KeyTempo.camelotOf("Bb").equals("6B"), "bare major");
        check(KeyTempo.camelotOf("8a").equals("8A"), "a Camelot code");
        check(KeyTempo.camelotOf("H major") == null && KeyTempo.camelotOf("") == null, "not keys");
        check(KeyTempo.camelotOf("C♯ minor").equals("12A"), "with the sharp sign");
        check(KeyTempo.keyDistance("8A", "8A") == 0, "same key");
        check(KeyTempo.keyDistance("8A", "8B") == 1, "relative keys");
        check(KeyTempo.keyDistance("8A", "9A") == 1, "a fifth up");
        check(KeyTempo.keyDistance("12A", "1A") == 1, "round the wheel");
        check(KeyTempo.keyDistance("8A", "10A") == 2, "two steps");
        check(KeyTempo.keyDistance("8A", "9B") == 2.5, "step and mode");
        check(KeyTempo.keyDistance("8A", "2B") == 4, "far");
        check(KeyTempo.keyDistance(null, "2B") == 4, "unknown");
        check(KeyTempo.tempoDistance(120, 120) == 0, "same tempo");
        check(Math.abs(KeyTempo.tempoDistance(120, 60)) < 1e-9, "half time counts as the same");
        check(
                KeyTempo.tempoDistance(120, 128) > 0.08 && KeyTempo.tempoDistance(120, 128) < 0.1,
                "a little apart");

        // Detection.
        KeyTempo.Result c = KeyTempo.analyse(song(40, 60, false, 120, 1), 40 * RATE);
        check(
                c.pitchClass == 0 && !c.minor,
                "C major song: " + c.keyName() + " " + c.keyConfidence);
        check(Math.abs(c.bpm - 120) < 2, "120 BPM song: " + c.bpm);
        check(c.camelot().equals("8B"), "as 8B");

        KeyTempo.Result a = KeyTempo.analyse(song(40, 57, true, 96, 2), 40 * RATE);
        check(a.pitchClass == 9 && a.minor, "A minor song: " + a.keyName());
        check(Math.abs(a.bpm - 96) < 2, "96 BPM song: " + a.bpm);

        KeyTempo.Result fs = KeyTempo.analyse(song(30, 66, false, 150, 3), 30 * RATE);
        check(fs.pitchClass == 6 && !fs.minor, "F♯ major song: " + fs.keyName());
        check(Math.abs(fs.bpm - 150) < 2 || Math.abs(fs.bpm - 75) < 1, "150 BPM song: " + fs.bpm);

        // Too short or silent: nothing is claimed.
        KeyTempo.Result silent = KeyTempo.analyse(new float[20 * RATE], 20 * RATE);
        check(silent.pitchClass < 0 && silent.bpm == 0, "silence has no key or tempo");
        KeyTempo.Result tiny = KeyTempo.analyse(song(3, 60, false, 120, 4), 3 * RATE);
        check(tiny.pitchClass < 0 && tiny.bpm == 0, "three seconds aren't judged");
        System.out.println("PASS: " + checks + " key and tempo checks.");
    }
}
