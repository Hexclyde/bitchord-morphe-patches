package app.bitchord.soulseek;

/** Cancellable encrypted relay checks. All methods belong on a worker. */
final class RelayProbeNative {
    private RelayProbeNative() {}

    static void load() {
        SoulseekNative.load();
    }

    static native long create(String secretHex);

    static native String probe(long handle, String relayUrl, int timeoutMs);

    static native void close(long handle);
}
