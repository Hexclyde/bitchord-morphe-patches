package app.bitchord.telegram;

import java.util.Arrays;
import java.util.List;

/**
 * DJ mode: the next song is the one that mixes best, songs you queued stay put, BitChord's analyses
 * are read.
 */
public final class DjModeTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    /** The shape of BitChord's TrackAnalysis, as far as DJ mode reads it. */
    static final class Analysis {
        String status, trackId, key;
        double bpm;

        Analysis(String status, String key, double bpm) {
            this.status = status;
            this.key = key;
            this.bpm = bpm;
            this.trackId = "x";
        }
    }

    static DjMode.Info info(String camelot, double bpm) {
        return new DjMode.Info(camelot, bpm, "test");
    }

    public static void main(String[] args) {
        DjMode.Info now = info("8A", 124);
        check(DjMode.cost(now, info("8A", 124)) == 0, "the same key and tempo cost nothing");
        check(
                DjMode.cost(now, info("8A", 124)) < DjMode.cost(now, info("9A", 124)),
                "a step round the wheel costs");
        check(
                DjMode.cost(now, info("9A", 124)) < DjMode.cost(now, info("2B", 124)),
                "a far key costs more");
        check(
                DjMode.cost(now, info("8A", 126)) < DjMode.cost(now, info("8A", 140)),
                "a tempo further off costs more");
        check(DjMode.cost(now, info("8A", 62)) < 0.5, "half time is the same tempo");
        check(
                DjMode.cost(now, info(null, 124)) > DjMode.cost(now, info("2B", 124)),
                "no key is worse than a far key");
        check(
                DjMode.cost(now, null) == 100 && DjMode.cost(null, now) == 100,
                "unknown songs cost most");

        // Picking.
        List<DjMode.Info> queue =
                Arrays.asList(info("2B", 90), info("9A", 125), info("8A", 124), null);
        List<Boolean> nobody = Arrays.asList(false, false, false, false);
        check(DjMode.pick(now, queue, nobody) == 2, "the matching song comes forward");
        check(
                DjMode.pick(
                                now,
                                Arrays.asList(info("8A", 124), info("8A", 124)),
                                Arrays.asList(false, false))
                        == -1,
                "the next already fits");
        check(
                DjMode.pick(now, queue, Arrays.asList(true, false, false, false)) == -1,
                "a song you queued next stays next");
        check(
                DjMode.pick(now, queue, Arrays.asList(false, true, false, false)) == -1,
                "nothing jumps over your queue");
        check(
                DjMode.pick(
                                now,
                                Arrays.asList(info("2B", 90), info("1B", 91)),
                                Arrays.asList(false, false))
                        == -1,
                "no change for a tiny gain");
        check(
                DjMode.pick(null, queue, nobody) == -1
                        && DjMode.pick(now, Arrays.asList(), Arrays.asList()) == -1,
                "nothing to do");
        check(
                DjMode.pick(now, Arrays.asList(null, info("8B", 124)), Arrays.asList(false, false))
                        == 1,
                "a known song beats an unknown one");

        // Descriptions.
        check(
                info("8A", 124).describe().equals("A minor · 8A · 124 BPM"),
                info("8A", 124).describe());
        check(info("8B", 0).describe().equals("C major · 8B"), info("8B", 0).describe());
        check(info(null, 100).describe().equals("100 BPM"), info(null, 100).describe());
        check(info(null, 0).describe().equals("Nothing known yet"), info(null, 0).describe());
        check(
                DjMode.keyName("1A").equals("A♭ minor") && DjMode.keyName("12B").equals("E major"),
                "names round the wheel");
        check(DjMode.keyName("x") == null && DjMode.keyName(null) == null, "no name for nonsense");

        // The saved form.
        DjMode.Info kept = DjMode.Info.fromJson(info("11B", 98.5).toJson());
        check(
                kept != null
                        && kept.camelot.equals("11B")
                        && kept.bpm == 98.5
                        && kept.source.equals("test"),
                "saved and read back");
        DjMode.Info keyless = DjMode.Info.fromJson(info(null, 98.5).toJson());
        check(keyless != null && keyless.camelot == null, "no key stays no key");
        check(
                DjMode.Info.fromJson("bad") == null && DjMode.Info.fromJson(null) == null,
                "bad saved forms");

        // BitChord's analyses.
        DjMode.Info host = DjMode.fromHost(new Analysis("ready", "A minor", 124.2));
        check(
                host != null
                        && host.camelot.equals("8A")
                        && host.bpm == 124.2
                        && host.source.equals("BitChord"),
                "a ready analysis");
        check(DjMode.fromHost(new Analysis("", "A minor", 124)) == null, "not finished");
        check(
                DjMode.fromHost(new Analysis("ready", "A minor", 0)) == null,
                "a failed decode has no tempo");
        DjMode.Info nokey = DjMode.fromHost(new Analysis("ready", "", 100));
        check(nokey != null && nokey.camelot == null, "a tempo without a key");
        System.out.println("PASS: " + checks + " DJ mode checks.");
    }
}
