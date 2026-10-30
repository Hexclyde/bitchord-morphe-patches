package android.net;

public final class ConnectivityManager {
    public volatile Network active = new Network(1);
    public volatile NetworkCallback callback;
    public int unregisters;

    public Network getActiveNetwork() {
        return active;
    }

    public void registerDefaultNetworkCallback(NetworkCallback callback) {
        this.callback = callback;
        callback.onAvailable(active);
    }

    public void unregisterNetworkCallback(NetworkCallback callback) {
        if (this.callback == callback) {
            this.callback = null;
            unregisters++;
        }
    }

    public void switchTo(Network network) {
        active = network;
        NetworkCallback next = callback;
        if (next != null) next.onAvailable(network);
    }

    public static class NetworkCallback {
        public void onAvailable(Network network) {}

        public void onLost(Network network) {}
    }
}
