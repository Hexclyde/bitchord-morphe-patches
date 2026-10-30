package org.torproject.jni;

/**
 * Minimal Java 8 JNI binding for Guardian tor-android 0.4.9.14. The class, native method and field
 * names are part of its binary ABI. This is not an Android Service: the host owns a private process
 * lifecycle.
 */
public final class TorService {
    static {
        System.loadLibrary("tor");
    }

    @SuppressWarnings("unused")
    private long torConfiguration = -1;

    @SuppressWarnings("unused")
    private int torControlFd = -1;

    private native boolean createTorConfiguration();

    private native void mainConfigurationFree();

    private native boolean mainConfigurationSetCommandLine(String[] arguments);

    private native int runMain();

    /** Called once, on the owning process's Tor worker thread. */
    public int run(String[] arguments) {
        if (!createTorConfiguration())
            throw new IllegalStateException("Tor configuration allocation failed");
        try {
            if (!mainConfigurationSetCommandLine(arguments))
                throw new IllegalArgumentException("Tor configuration rejected");
            return runMain();
        } finally {
            mainConfigurationFree();
        }
    }
}
