package app.bitchord.soulseek;

import java.io.IOException;

/**
 * Embedded i2pd; load and call only from the private :bitchord_i2p process.
 *
 * <p>All blocking calls belong on workers. A stopped router cannot restart in the same process. The
 * service must terminate its own process after native stop. Home must be an app-private no-backup
 * directory with bundled certificates/ installed before start. No SAM, SOCKS, HTTP or control
 * listener is opened.
 */
public final class I2pNative {
    public static final int MAX_IO = 64 * 1024;
    public static final int MAX_TIMEOUT_MS = 120000;

    static {
        System.loadLibrary("bitchord_i2p");
    }

    private I2pNative() {}

    public static native long start(String privateHome) throws IOException;

    /** "starting", "ready" or "stopped". Ready means usable local tunnels. */
    public static native String status(long router) throws IOException;

    /** Stable 52-character base32 destination followed by .b32.i2p. */
    public static native String localDestination(long router) throws IOException;

    /** Returns 0 on deadline; incoming queue and stream registry are bounded. */
    public static native long accept(long router, int timeoutMs) throws IOException;

    /** Only canonical base32 destinations are accepted; no DNS/outproxy use. */
    public static native long connect(long router, String destination, int timeoutMs)
            throws IOException;

    /** -1 for EOF, 0 for timeout, positive for bytes read. Timeout closes stream. */
    public static native int read(long stream, byte[] buffer, int offset, int length, int timeoutMs)
            throws IOException;

    /** Waits for bounded stream send completion; throws and closes on deadline. */
    public static native int write(
            long stream, byte[] buffer, int offset, int length, int timeoutMs) throws IOException;

    public static native void closeStream(long stream);

    /** Cancels streams and joins destination/router workers. Idempotent. */
    public static native void stop(long router);
}
