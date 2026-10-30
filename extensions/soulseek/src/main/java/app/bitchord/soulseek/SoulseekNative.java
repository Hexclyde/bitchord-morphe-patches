package app.bitchord.soulseek;

/** JNI boundary. Call network operations on a worker, never the Android main thread. */
public final class SoulseekNative {
    private SoulseekNative() {}

    public static void load() {
        Loader.loaded();
    }

    private static final class Loader {
        static {
            System.loadLibrary("bitchord_soulseek");
        }

        static void loaded() {}
    }

    public static native long create(
            String home, String serverHost, int serverPort, int listenPort);

    public static native void connect(long handle, String username, String password);

    public static native String search(long handle, String query, int timeoutMs);

    public static native String searchAdaptive(
            long handle, String query, int timeoutMs, String preferredExtensions);

    public static native String download(long handle, String peer, String remotePath, long size);

    public static native String transfers(long handle);

    public static native void cancel(long handle, String transferId);

    public static native void close(long handle);
}
