package app.bitchord.soulseek;

import java.io.IOException;

/** Retained-UDP STUN discovery and pinned-certificate QUIC, with bounded JNI buffers. */
final class DirectNative {
    static {
        System.loadLibrary("bitchord_direct");
    }

    private DirectNative() {}

    static native long start(String[] stunSources, int timeoutMs) throws IOException;

    static native String endpoint(long node) throws IOException;

    static native long connect(long node, String descriptor, int timeoutMs) throws IOException;

    static native long accept(long node, int timeoutMs) throws IOException;

    static native void punch(long node, String descriptor, int timeoutMs) throws IOException;

    static native int read(long stream, byte[] data, int offset, int length, int timeoutMs)
            throws IOException;

    static native void write(long stream, byte[] data, int offset, int length, int timeoutMs)
            throws IOException;

    static native void finish(long stream, int timeoutMs) throws IOException;

    static native void closeConnection(long stream);

    static native void closeNode(long node);
}
