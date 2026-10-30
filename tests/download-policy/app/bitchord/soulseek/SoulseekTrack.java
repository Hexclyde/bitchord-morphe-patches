package app.bitchord.soulseek;

import org.json.JSONObject;

final class SoulseekTrack {
    String format, mime;

    JSONObject metadata() {
        throw new AssertionError("Playback requested download metadata");
    }
}
