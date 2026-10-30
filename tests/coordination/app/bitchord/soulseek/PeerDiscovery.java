package app.bitchord.soulseek;

import org.json.JSONObject;

// Discovery has its own injected host tests; existing paired coordination stays isolated.
final class PeerDiscovery {
    static PeerDiscovery existing() {
        return null;
    }

    void onPairedStarted() {}

    void onPairedMessage(String peer, String session, String type, JSONObject payload) {}

    void stop() {}

    void clearCache() {}

    boolean shutdownFailed() {
        return false;
    }
}

final class PeerExchange {
    static final String PROTOCOL = "app.bitchord.pex/1";
}
