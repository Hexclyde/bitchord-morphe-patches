package app.bitchord.soulseek;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The discovery screen uses only this small service boundary; no relay is contacted. */
final class PeerDiscovery {
    static PeerDiscovery fixture;
    boolean enabled, advertise, running, fail;
    int starts, stops, clears, advertised;
    final List<Candidate> candidates = new ArrayList<>();

    static PeerDiscovery get(Context context) throws IOException {
        return fixture;
    }

    static final class Candidate {
        final String key;
        final long created, expires;
        final List<String> transports;
        final JSONObject signed = new JSONObject();

        Candidate(int number) {
            key = String.format(java.util.Locale.ROOT, "%064x", number + 1);
            created = System.currentTimeMillis() / 1000;
            expires = created + 300;
            transports =
                    Collections.unmodifiableList(Arrays.asList("direct", "tor", "i2p", "ipfs"));
        }
    }

    static final class Snapshot {
        final boolean enabled, advertise, running;
        final String status;
        final List<Candidate> candidates;
        final int connectedRelays, received;

        Snapshot(PeerDiscovery service) {
            enabled = service.enabled;
            advertise = service.advertise;
            running = service.running;
            status = enabled ? "Peer discovery stopped." : "Peer discovery is off.";
            candidates = Collections.unmodifiableList(new ArrayList<>(service.candidates));
            connectedRelays = running ? 2 : 0;
            received = candidates.size();
        }
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(this);
    }

    synchronized void setEnabled(boolean on) throws IOException {
        if (fail) throw new IOException("fixture failure must stay out of the UI");
        enabled = on;
        if (!on) stop();
    }

    synchronized void setAdvertise(boolean on) throws IOException {
        if (fail) throw new IOException("fixture failure");
        advertise = on;
        advertised++;
        stop();
    }

    synchronized void startScan() {
        starts++;
        running = true;
    }

    synchronized void stop() {
        stops++;
        running = false;
    }

    synchronized void clearCache() {
        stop();
        clears++;
        candidates.clear();
    }
}
