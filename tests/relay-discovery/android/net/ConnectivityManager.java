package android.net;

public final class ConnectivityManager {
    public volatile Network network = new Network(71);
    public final NetworkCapabilities capabilities = new NetworkCapabilities();

    public Network getActiveNetwork() {
        return network;
    }

    public NetworkCapabilities getNetworkCapabilities(Network value) {
        return capabilities;
    }
}
