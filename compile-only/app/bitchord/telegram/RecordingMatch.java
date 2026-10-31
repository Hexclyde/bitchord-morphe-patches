package app.bitchord.telegram;

import org.json.JSONObject;

/** Compile-only facade; the shared settings patch supplies the implementation. */
public final class RecordingMatch {
    private RecordingMatch() {}

    public static final class Reference {
        public final String name;
        public final double seconds;

        private Reference() {
            name = "";
            seconds = 0;
        }
    }

    public static Reference reference(float[] pcm, int length, int rate, String name) {
        throw new UnsupportedOperationException("Compile-only facade");
    }

    public static JSONObject compare(Reference reference, float[] pcm, int length, int rate)
            throws Exception {
        throw new UnsupportedOperationException("Compile-only facade");
    }
}
