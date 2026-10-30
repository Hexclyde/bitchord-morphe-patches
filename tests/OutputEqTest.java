package app.bitchord.telegram;

/**
 * Equaliser per output: a setting survives its code and its saved form, and bad input is refused.
 */
public final class OutputEqTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        OutputEq.Snapshot s = new OutputEq.Snapshot();
        s.enabled = true;
        s.mode = "MANUAL";
        s.toneX = -3;
        s.toneY = 5;
        s.focused = true;
        s.balance = -0.25f;
        s.bands = new float[] {3f, 1.5f, 0f, -2f, -4.5f, 6f, 12f};

        String code = s.code();
        check(code.startsWith(OutputEq.CODE_PREFIX), code);
        check(
                code.length() < 30
                        && !code.contains("=")
                        && !code.contains("+")
                        && !code.contains("/"),
                "a short, safe code: " + code);
        OutputEq.Snapshot back = OutputEq.Snapshot.fromCode(code);
        check(back != null && back.sameAs(s), "the code gives the setting back");
        check(back.enabled, "a pasted code is on");
        check(OutputEq.Snapshot.fromCode("  " + code + "\n") != null, "surrounding space is fine");
        check(OutputEq.Snapshot.fromCode("BCEQ1.abc") == null, "wrong length");
        check(
                OutputEq.Snapshot.fromCode("hello") == null
                        && OutputEq.Snapshot.fromCode(null) == null,
                "not a code");
        check(OutputEq.Snapshot.fromCode(OutputEq.CODE_PREFIX + "!!!!") == null, "not base64");

        String json = s.toJson();
        OutputEq.Snapshot stored = OutputEq.Snapshot.fromJson(json);
        check(stored != null && stored.sameAs(s), "the saved form gives the setting back: " + json);
        check(
                OutputEq.Snapshot.fromJson("a|b") == null
                        && OutputEq.Snapshot.fromJson(null) == null,
                "bad saved forms");

        OutputEq.Snapshot off = new OutputEq.Snapshot();
        check(off.describe().equals("Equaliser off"), off.describe());
        check(s.describe().startsWith("Sliders"), s.describe());
        OutputEq.Snapshot pad = new OutputEq.Snapshot();
        pad.enabled = true;
        pad.toneX = 2;
        check(
                pad.describe().contains("2") && pad.describe().startsWith("Tone pad"),
                pad.describe());

        float[] bands = OutputEq.parseBands("1,2.5,x,-20,,3,4,5,6");
        check(
                bands.length == 7
                        && bands[0] == 1f
                        && bands[1] == 2.5f
                        && bands[2] == 0f
                        && bands[3] == -12f,
                "bands are read leniently");
        check(OutputEq.parseBands(null).length == 7, "no bands is flat");

        // Gains beyond ±12 dB are clipped by the code, not wrapped.
        OutputEq.Snapshot loud = new OutputEq.Snapshot();
        loud.bands[0] = 20f;
        check(OutputEq.Snapshot.fromCode(loud.code()).bands[0] == 12f, "clipped");
        System.out.println("PASS: " + checks + " equaliser per output checks.");
    }
}
