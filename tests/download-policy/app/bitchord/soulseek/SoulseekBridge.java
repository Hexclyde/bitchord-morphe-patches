package app.bitchord.soulseek;

import java.util.List;

final class SoulseekBridge {
    final Object context = new Object();
    static int accesses;
    String sessionTag;

    static SoulseekBridge get() {
        accesses++;
        throw new AssertionError("Playback reached the download bridge");
    }

    List<SoulseekTrack> search(String query) {
        throw new AssertionError("Playback initiated a search");
    }

    SoulseekTrack track(String id) {
        throw new AssertionError("Playback resolved a download");
    }

    String url() {
        throw new AssertionError("Playback requested a download endpoint");
    }
}
