package app.bitchord.soulseek;

import java.util.List;

final class NostrCoordinator {
    volatile String identity = repeat('a');
    volatile String relays = "wss://relay.damus.io\nwss://nos.lol";
    volatile boolean running;
    volatile int creates, closes, configurations;
    private long probe;

    boolean running() {
        return running;
    }

    String publicKey() {
        return identity;
    }

    String relaysText() {
        return relays;
    }

    synchronized long createRelayProbe() {
        creates++;
        probe = RelayProbeNative.create(identity);
        return probe;
    }

    synchronized void closeRelayProbe(long value) {
        if (probe != value) return;
        try {
            RelayProbeNative.close(value);
        } catch (Exception error) {
            throw new AssertionError("Probe cleanup failed", error);
        }
        probe = 0;
        closes++;
    }

    void configureRelays(String value) throws Exception {
        List<String> validated = NostrConfig.relays(value);
        configurations++;
        relays = NostrConfig.lines(validated);
    }

    static String repeat(char value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 64; i++) result.append(value);
        return result.toString();
    }
}
