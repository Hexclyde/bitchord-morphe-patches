package app.bitchord.soulseek;

/** Encrypted coordination transport; blocking operations belong on a worker. */
final class NostrNative {
    private NostrNative() {}

    static void load() {
        SoulseekNative.load();
    }

    static native String generateSecret();

    static native String publicKey(String secretHex);

    static native long open(String secretHex, String relaysJson, String peersJson);

    static native String status(long handle);

    static native String send(
            long handle, String recipientHex, String sessionId, String type, String payloadJson);

    static native String poll(long handle, int timeoutMs);

    static native void close(long handle);
}
