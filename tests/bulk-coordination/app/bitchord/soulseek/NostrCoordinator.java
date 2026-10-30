package app.bitchord.soulseek;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class NostrCoordinator {
    interface MessageHandler {
        void onMessage(
                long generation, String peer, String session, String type, JSONObject payload);

        void onStopped();
    }

    static NostrCoordinator instance = new NostrCoordinator();

    static final class Sent {
        final String peer, session, type;
        final JSONObject payload;

        Sent(String peer, String session, String type, JSONObject payload) {
            this.peer = peer;
            this.session = session;
            this.type = type;
            this.payload = payload;
        }
    }

    final List<Sent> sent = new ArrayList<>();
    MessageHandler listener;
    boolean running;
    long generation = 1;
    String peers = "";

    static NostrCoordinator get(Context context) {
        return instance;
    }

    void setMessageHandler(MessageHandler handler) {
        listener = handler;
    }

    void start() {
        running = true;
    }

    boolean running() {
        return running;
    }

    void stop() {
        running = false;
        generation++;
        if (listener != null) listener.onStopped();
    }

    boolean isActive(long expected) {
        return running && generation == expected;
    }

    String peersText() {
        return peers;
    }

    synchronized void sendToPeer(String peer, String session, String type, JSONObject payload) {
        sent.add(new Sent(peer, session, type, payload));
    }

    synchronized Sent last() {
        return sent.get(sent.size() - 1);
    }
}
