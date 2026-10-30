package app.bitchord.soulseek;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class NostrIdentityStore {
    static volatile NostrIdentityStore last;
    volatile NostrNative.Gate nextLoad, nextClear;
    volatile int clears;
    private Settings settings;

    static final class Settings {
        final String secret;
        final List<String> relays, peers;

        Settings(String secret, List<String> relays, List<String> peers) {
            this.secret = secret;
            this.relays = Collections.unmodifiableList(new ArrayList<>(relays));
            this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
        }
    }

    NostrIdentityStore(Context context, File home) {
        last = this;
    }

    Settings loadOrCreate() throws Exception {
        final Settings value;
        final NostrNative.Gate gate;
        synchronized (this) {
            if (settings == null)
                settings =
                        new Settings(
                                NostrNative.generateSecret(),
                                Arrays.asList("wss://relay.damus.io", "wss://nos.lol"),
                                Collections.emptyList());
            value = settings;
            gate = nextLoad;
            nextLoad = null;
        }
        if (gate != null) gate.hold();
        return value;
    }

    synchronized void configure(List<String> relays, List<String> peers) throws Exception {
        Settings current = loadOrCreate();
        settings = new Settings(current.secret, relays, peers);
    }

    void clear() throws Exception {
        NostrNative.Gate gate = nextClear;
        nextClear = null;
        if (gate != null) gate.hold();
        synchronized (this) {
            settings = null;
            clears++;
        }
    }
}
