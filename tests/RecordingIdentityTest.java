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

        check(
                RecordingIdentity.matchesPrepared(a, same, 40, 40.25),
                "Preparation retains a verified constant leading offset");
        RecordingIdentity.Window[] spaced = {
            window(song, 0, 0), window(song, 13, 98.981), window(song, 26, 196.941)
        };
        RecordingIdentity.Window[] clock = {
            window(song, 0, 0), window(song, 13, 99.0665), window(song, 26, 197.101)
        };
        check(
                RecordingIdentity.match(spaced, clock, 213.941, 214.073791) == null,
                "The measured 160 ms drift still cannot authorize a constant-offset swap");
        check(
                RecordingIdentity.matchesPrepared(spaced, clock, 213.941, 214.073791),
                "Matching whole tracks tolerate duration-explained timing drift before playback");
        check(
                !RecordingIdentity.matchesPrepared(a, changed, 40, 40.25),
                "Prepared copies still require their middle to match");
        check(
                !RecordingIdentity.matchesPrepared(spaced, clock, 213.941, 215),
                "Preparation retains the duration bound");
        check(
                !RecordingIdentity.matchesPrepared(a, drifting, 40, 40),
                "A changed middle cannot be explained by equal total durations");
        RecordingIdentity.Window[] unexplained = {
            window(song, 0, 0), window(song, 13, 13.1), window(song, 26, 26.2)
        };
        check(
                !RecordingIdentity.matchesPrepared(a, unexplained, 40, 40),
                "An arbitrary fitted slope cannot replace measured durations");
        check(
                !RecordingIdentity.matchesPrepared(spaced, clock, 214.073791, 213.941),
                "The duration ratio must explain the observed direction of drift");
        check(
                !RecordingIdentity.matchesPrepared(a, same, Double.NaN, 40),
                "Unknown prepared durations do not verify");
        check(
                !RecordingIdentity.matchesPrepared(
                        new RecordingIdentity.Window[] {silence, silence, silence}, same, 40, 40),
                "Preparation does not accept silence as recording evidence");
        check(
                RecordingIdentity.match(spaced, clock, 213.941, 214.073791) == null,
                "Preparation never changes the strict swap's decision");
        RecordingIdentity.Window[] invalid = {window(song, 0, Double.NaN), spaced[1], spaced[2]};
        check(
                !RecordingIdentity.matchesPrepared(invalid, clock, 213.941, 214.073791),
                "Invalid decoder timestamps cannot certify a prepared recording");
        String measurements = RecordingIdentity.describe(spaced, clock, 213.941, 214.073791);
        check(
                measurements.contains("offset span 160 ms")
                        && measurements.contains("duration-normalized span 38 ms"),
                "Comparison logs distinguish raw drift from duration-explained drift");

        // Padding moves every window by the same amount and changes the total length.
        RecordingIdentity.Window[] padded = {
            window(song, 0, 2), window(song, 13, 15), window(song, 26, 28)
        };
        check(
                RecordingIdentity.match(a, padded, 40, 42) == null,
                "A swap still refuses a 2 s length difference");
        check(
                RecordingIdentity.matchesPrepared(a, padded, 40, 42),
                "Preparation accepts constant padding");
        check(
                RecordingIdentity.matchesPrepared(padded, a, 42, 40),
                "Padding is accepted in either direction");
        check(
                RecordingIdentity.matchesPrepared(a, padded, 40, 40.75 + 0.0001),
                "Just past the strict length limit uses the padding path");
        check(
                RecordingIdentity.matchesPrepared(a, padded, 40, 43)
                        && RecordingIdentity.matchesPrepared(a, padded, 43, 40),
                "Preparation accepts exactly 3 s of length difference");
        check(
                !RecordingIdentity.matchesPrepared(a, padded, 40, 43.001)
                        && !RecordingIdentity.matchesPrepared(a, padded, 43.001, 40),
                "Preparation refuses more than 3 s of length difference");
        RecordingIdentity.Window[] far = {
            window(song, 0, 3.001), window(song, 13, 16.001), window(song, 26, 29.001)
        };
        check(
                !RecordingIdentity.matchesPrepared(a, far, 40, 42)
                        && RecordingIdentity.matchesPrepared(
                                a,
                                new RecordingIdentity.Window[] {
                                    window(song, 0, 3), window(song, 13, 16), window(song, 26, 29)
                                },
                                40,
                                42),
                "The constant offset is bounded at 3 s");
        RecordingIdentity.Window[] paddedDrift = {padded[0], window(song, 13, 15.081), padded[2]};
        check(
                !RecordingIdentity.matchesPrepared(a, paddedDrift, 40, 42)
                        && RecordingIdentity.matchesPrepared(
                                a,
                                new RecordingIdentity.Window[] {
                                    padded[0], window(song, 13, 15.079), padded[2]
                                },
                                40,
                                42),
                "Padded windows still share one timeline within 80 ms");
        check(
                !RecordingIdentity.matchesPrepared(
                        a,
                        new RecordingIdentity.Window[] {
                            padded[0], window(other, 13, 15), padded[2]
                        },
                        40,
                        42),
                "Padding cannot hide a different middle");
        check(
                !RecordingIdentity.matchesPrepared(
                        a,
                        new RecordingIdentity.Window[] {
                            window(other, 0, 2), window(other, 13, 15), window(other, 26, 28)
                        },
                        40,
                        42),
                "Padding cannot turn another recording into this one");
        RecordingIdentity.Window[] slowed = {
            window(song, 0, 0), window(song, 13, 13.3), window(song, 26, 26.6)
        };
        check(
                !RecordingIdentity.matchesPrepared(a, slowed, 40, 42),
                "Beyond 0.75 s a rate difference is not explained away");
        check(
                RecordingIdentity.match(spaced, clock, 213.941, 214.073791) == null,
                "The padding path never changes the strict swap's decision");
        System.out.println("PASS: " + checks + " multi-window recording identity checks");
    }
}
