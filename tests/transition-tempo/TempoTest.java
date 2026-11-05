package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

public final class TempoTest {
    private static int checks;

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    private static JSONObject stored() throws Exception {
        return new JSONObject(new TransitionEdit().encode())
                .put("manualBpm", new JSONArray(new double[] {120, 150}))
                .put("overlapRate", new JSONArray(new double[] {1.25, .75}));
    }

    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1e-9, message);
    }

    private static void rejects(Runnable operation, String message) {
        boolean rejected = false;
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, message);
    }

    public static void main(String[] args) throws Exception {
        JSONObject value = stored().put("trim", 4).put("cue", 3);
        TransitionEdit edit = TransitionEdit.decode(value.toString());
        check(
                Math.abs(edit.start(100) - 86) < 1e-9,
                "Outgoing source span must account for the chosen overlap tempo");
        JSONObject copied = new JSONObject(edit.copy().encode());
        check(
                copied.has("overlapRate") && copied.has("manualBpm"),
                "Directed-pair tempo settings must survive persistence");
        check(
                Math.abs(copied.getJSONArray("overlapRate").getDouble(1) - .75) < 1e-9,
                "Incoming rate must survive copying without changing the outgoing deck");
        JSONObject legacy = new JSONObject(new TransitionEdit().encode());
        for (String name :
                new String[] {"manualBpm", "overlapRate", "automaticTiming", "filterDepth"})
            legacy.remove(name);
        TransitionEdit old = TransitionEdit.decode(legacy.toString());
        near(1, old.overlapRate[0], "Old outgoing rate remains original");
        near(1, old.overlapRate[1], "Old incoming rate remains original");
        check(!old.hasTempoOverrides(), "Opening an old edit must not create tempo overrides");
        check(!old.automaticTiming, "Old custom edits retain their timing");
        near(0, old.manualBpm[0], "Unknown tempo remains unknown");
        check(Double.isNaN(old.baseBpm(0, 0)), "No tempo is invented for an unmeasured track");
        check(!old.canMatchTempo(1, 120, 0), "An unknown target tempo cannot match");
        rejects(
                () -> old.matchTempo(1, 120, 0),
                "Matching unknown tempo must fail without mutation");
        near(1, old.overlapRate[1], "Failed matching preserves the old rate");
        old.manualBpm[0] = 120;
        old.manualBpm[1] = 150;
        old.matchTempo(1, 0, 0);
        near(.8, old.overlapRate[1], "Manual tempos can match without analysis");
        near(120, old.overlapBpm(1, 0), "The matched target follows the other effective tempo");
        old.setOverlapBpm(0, 150, 0);
        near(1.25, old.overlapRate[0], "An explicit overlap tempo derives the correct rate");
        old.matchTempo(1, 0, 0);
        near(
                1,
                old.overlapRate[1],
                "Matching uses the other deck's effective rather than base tempo");
        old.manualBpm[1] = 30;
        check(
                !old.canMatchTempo(1, 0, 0),
                "Out-of-range matching is disabled without octave guessing");
        rejects(
                () -> old.matchTempo(1, 0, 0),
                "Out-of-range matching must reject instead of clamp");
        old.resetOverlapRates();
        near(120, old.manualBpm[0], "Original tempo reset preserves manual metadata");
        near(1, old.overlapRate[0], "Original resets outgoing rate");
        near(1, old.overlapRate[1], "Original resets incoming rate");
        near(10, edit.sourceSpan(0), "Outgoing source clock advances at its own rate");
        near(6, edit.sourceSpan(1), "Incoming source clock advances at its own rate");
        near(6, edit.incomingAt(.5), "Handoff uses the incoming source clock");
        near(3, edit.incomingAt(-1), "Incoming clock clamps before the transition");
        near(9, edit.incomingAt(2), "Incoming clock clamps after the transition");
        for (int preset = TransitionEdit.FADE; preset <= TransitionEdit.SLAM; preset++) {
            edit.preset(preset);
            near(1.25, edit.overlapRate[0], "Changing preset preserves outgoing tempo");
            near(.75, edit.overlapRate[1], "Changing preset preserves incoming tempo");
        }
        TransitionEdit invalid = new TransitionEdit();
        invalid.overlapRate[0] = Double.NaN;
        rejects(invalid::validate, "Non-finite rates are rejected");
        invalid.overlapRate[0] = .49;
        rejects(invalid::validate, "Rates below the supported range are rejected");
        invalid.overlapRate[0] = 2.01;
        rejects(invalid::validate, "Rates above the supported range are rejected");
        invalid.overlapRate[0] = 1;
        invalid.manualBpm[0] = 29;
        rejects(invalid::validate, "Invalid manual tempos are rejected");
        edit.automaticTiming = true;
        check(edit.copy().automaticTiming, "Automatic timing overlay survives persistence");
        edit.filter[0] = 1;
        edit.filterDepth = 0;
        near(20000, edit.cutoffs(0, 1)[0], "Zero depth bypasses the low-pass sweep");
        edit.filterDepth = 1;
        check(edit.cutoffs(0, 1)[0] < 20000, "Full depth restores the selected filter sweep");
        System.out.println("PASS: " + checks + " transition tempo checks");
    }
}
