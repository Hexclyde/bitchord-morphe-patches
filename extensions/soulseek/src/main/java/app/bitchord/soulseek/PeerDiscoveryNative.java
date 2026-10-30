package app.bitchord.soulseek;

/** Bounded public provider advertisements; never admits an identity to paired transfers. */
final class PeerDiscoveryNative {
    private PeerDiscoveryNative() {}

    static void load() {
        SoulseekNative.load();
    }

    static native long open(String secret, String relaysJson, boolean advertise);

    static native String poll(long handle, int timeoutMs);

    static native String status(long handle);

    static native String verify(String eventsJson);

    static native void close(long handle);
}
