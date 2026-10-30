package android.content.res;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class AssetManager {
    public volatile String candidates = "";

    public InputStream open(String path) {
        if (!path.equals("soulseek-native/nostr-relays.txt"))
            throw new AssertionError("Unexpected asset");
        return new ByteArrayInputStream(candidates.getBytes(StandardCharsets.UTF_8));
    }
}
