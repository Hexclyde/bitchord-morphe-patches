package android.net;

public final class Network {
    private final long handle;

    public Network(long handle) {
        this.handle = handle;
    }

    public long getNetworkHandle() {
        return handle;
    }
}
