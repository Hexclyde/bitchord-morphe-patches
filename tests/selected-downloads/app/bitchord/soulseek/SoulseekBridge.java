package app.bitchord.soulseek;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SoulseekBridge {
    static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;
    static SoulseekBridge instance = new SoulseekBridge();
    final Map<String, SoulseekTrack> tracks = new ConcurrentHashMap<>();
    volatile String sessionTag = "old-session";
    volatile boolean changeSessionOnLookup;

    static SoulseekBridge get() {
        return instance;
    }

    SoulseekTrack track(String id) throws IOException {
        SoulseekTrack track = tracks.get(id);
        if (track == null) throw new IOException("Selected result expired.");
        if (changeSessionOnLookup) {
            sessionTag = "new-session";
            changeSessionOnLookup = false;
        }
        return track;
    }
}
