package app.bitchord.soulseek;

import java.io.IOException;

final class SoulseekBridge {
    static final SoulseekBridge INSTANCE = new SoulseekBridge();
    static int gets;
    static boolean expired, changeSession;
    volatile String sessionTag = "session-a";
    String selected;

    static SoulseekBridge get() {
        gets++;
        return INSTANCE;
    }

    SoulseekTrack track(String id) throws IOException {
        selected = id;
        if (expired) throw new IOException("Result expired");
        if (changeSession) sessionTag = "session-b";
        return new SoulseekTrack();
    }
}
