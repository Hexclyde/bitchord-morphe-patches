package android.net;

public final class NetworkCapabilities {
    public static final int NET_CAPABILITY_INTERNET = 12;
    public volatile boolean internet = true;
    public volatile int transport = 1;

    public boolean hasCapability(int value) {
        return value == NET_CAPABILITY_INTERNET && internet;
    }

    public boolean hasTransport(int value) {
        return value == transport;
    }
}
