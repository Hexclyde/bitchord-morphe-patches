package app.bitchord.soulseek;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

// Host-only adapters. No Android service, native library, relay, or user account is used.
final class NostrIdentityStore {
    static final class Settings {
        final String secret;
        final List<String> relays, peers;

        Settings(String secret, List<String> relays, List<String> peers) {
            this.secret = secret;
            this.relays = relays;
            this.peers = peers;
        }
    }
}

final class NostrCoordinator {
    static NostrCoordinator existing() {
        return null;
    }

    static NostrCoordinator get(Context ignored) {
        return new NostrCoordinator();
    }

    NostrIdentityStore.Settings discoverySettings() {
        return null;
    }

    boolean running() {
        return false;
    }

    String publicKey() {
        return "";
    }

    String peersText() {
        return "";
    }

    void sendDiscoveryMessage(String peer, String session, String type, JSONObject payload) {}
}

final class NostrNative {
    static void load() {}

    static String publicKey(String secret) {
        return secret;
    }
}

final class SoulseekNative {
    static void load() {}
}

final class DownloadSettings {
    static final String PEERS = "peers";

    static void requireEnabled(Context context, String feature) throws IOException {}

    static boolean enabled(Context context, String feature) {
        return true;
    }
}

final class NostrConfig {
    static List<String> peers(String text) {
        return Collections.emptyList();
    }
}
