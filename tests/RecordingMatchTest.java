package app.bitchord.telegram;

import org.json.JSONObject;

public final class RecordingMatchTest {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        float[] source = FingerprintTest.tune(91, 35, 1);
        RecordingMatch.Reference reference =
                RecordingMatch.reference(source, source.length, 44100, "Known local file");
        float[] excerpt = FingerprintTest.streamed(source, 7, 15, 81);
        JSONObject result = RecordingMatch.compare(reference, excerpt, excerpt.length, 48000);
        check(
                result.getString("verdict").equals("excerpt_match"),
                "Same recording survives level/resampling/noise changes");
        check(
                Math.abs(result.getDouble("reference_offset_seconds") - 7) < 0.15,
                "Evidence records the measured offset");
        check(result.getDouble("candidate_seconds") == 15, "Evidence records sample coverage");
        check(!result.getBoolean("same_master_verified"), "Excerpt cannot certify mastering");
        check(
                !result.getBoolean("complete_recording_verified"),
                "Excerpt cannot certify the whole file");
        check(
                !result.getBoolean("lossless_origin_verified"),
                "Recording match cannot certify quality");
        check(!result.has("confidence_percent"), "Landmark scores are not invented probabilities");
        float[] other = FingerprintTest.tune(391, 15, 1);
        check(
                !RecordingMatch.compare(reference, other, other.length, 44100)
                        .getString("verdict")
                        .equals("excerpt_match"),
                "Different recording is not accepted");
        float[] silence = new float[44100 * 12];
        check(
                RecordingMatch.compare(reference, silence, silence.length, 44100)
                        .getString("verdict")
                        .equals("insufficient_audio"),
                "Silence stays inconclusive");
        try {
            RecordingMatch.reference(new float[1], 2, 44100, "Bad");
            throw new AssertionError();
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        System.out.println("PASS: " + checks + " recording evidence checks");
    }
}
