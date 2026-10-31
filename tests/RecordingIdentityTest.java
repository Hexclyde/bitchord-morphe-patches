package app.bitchord.telegram;

import java.util.Arrays;

public final class RecordingIdentityTest {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static RecordingIdentity.Window window(float[] pcm, int start, double timeline) {
        return new RecordingIdentity.Window(
                Arrays.copyOfRange(pcm, start * 44100, (start + 12) * 44100), 44100, timeline);
    }

    public static void main(String[] args) {
        float[] song = FingerprintTest.tune(91, 40, 1), other = FingerprintTest.tune(817, 40, 1);
        RecordingIdentity.Window[] a = {
            window(song, 0, 0), window(song, 13, 13), window(song, 26, 26)
        };
        RecordingIdentity.Window[] same = {
            window(song, 0, .25), window(song, 13, 13.25), window(song, 26, 26.25)
        };
        Double offset = RecordingIdentity.match(a, same, 40, 40.25);
        check(
                offset != null && Math.abs(offset - .25) < .05,
                "Three windows preserve a constant leading-silence offset");
        RecordingIdentity.Window[] changed = {same[0], window(other, 13, 13.25), same[2]};
        check(
                RecordingIdentity.match(a, changed, 40, 40.25) == null,
                "A matching intro/outro cannot hide a different middle");
        check(
                RecordingIdentity.match(a, same, 40, 44) == null,
                "A different edit's duration is rejected");
        RecordingIdentity.Window[] drifting = {same[0], window(song, 13, 13.5), same[2]};
        check(
                RecordingIdentity.match(a, drifting, 40, 40.25) == null,
                "Different offsets cannot share one timeline");
        RecordingIdentity.Window silence =
                new RecordingIdentity.Window(new float[8000 * 12], 8000, 0);
        check(
                RecordingIdentity.match(
                                new RecordingIdentity.Window[] {silence, silence, silence},
                                same,
                                40,
                                40)
                        == null,
                "Silence is inconclusive");
        check(
                RecordingIdentity.match(
                                new RecordingIdentity.Window[] {a[0]},
                                new RecordingIdentity.Window[] {same[0]},
                                40,
                                40)
                        == null,
                "One excerpt is insufficient");
        check(
                RecordingIdentity.match(a, same, Double.NaN, 40) == null,
                "Unknown durations cannot certify a timeline");
        System.out.println("PASS: " + checks + " multi-window recording identity checks");
    }
}
