package app.bitchord.telegram;

import org.json.JSONObject;

/** Source-independent evidence API. Scores are landmark counts, never probability percentages. */
public final class RecordingMatch {
  public static final String ALGORITHM = "bitchord-landmarks-1";

  private RecordingMatch() {}

  public static final class Reference {
    private final Fingerprint fingerprint;
    public final String name;
    public final double seconds;

    Reference(Fingerprint fingerprint, String name, double seconds) {
      this.fingerprint = fingerprint;
      this.name = name;
      this.seconds = seconds;
    }
  }

  public static Reference reference(float[] pcm, int length, int rate, String name) {
    validate(pcm, length, rate);
    return new Reference(Fingerprint.of(pcm, length, rate), name, length / (double) rate);
  }

  public static JSONObject compare(Reference reference, float[] pcm, int length, int rate)
      throws Exception {
    validate(pcm, length, rate);
    Fingerprint candidate = Fingerprint.of(pcm, length, rate);
    Fingerprint.Match match = Fingerprint.match(reference.fingerprint, candidate);
    boolean enough =
        length >= (long) rate * 10
            && candidate.size() >= 100
            && reference.fingerprint.size() >= 100;
    String verdict =
        !enough ? "insufficient_audio" : match.same() ? "excerpt_match" : "no_confident_match";
    return new JSONObject()
        .put("algorithm", ALGORITHM)
        .put("verdict", verdict)
        .put("reference", reference.name)
        .put("reference_seconds", reference.seconds)
        .put("candidate_start_seconds", 0)
        .put("candidate_seconds", length / (double) rate)
        .put("aligned_landmarks", match.score)
        .put("candidate_landmarks", match.size)
        .put("reference_offset_seconds", match.offset)
        .put("same_master_verified", false)
        .put("lossless_origin_verified", false)
        .put("complete_recording_verified", false);
  }

  private static void validate(float[] pcm, int length, int rate) {
    if (pcm == null
        || length < 0
        || length > pcm.length
        || rate < 8000
        || rate > 192000
        || length > (long) rate * 60)
      throw new IllegalArgumentException("Invalid bounded audio sample");
  }
}
