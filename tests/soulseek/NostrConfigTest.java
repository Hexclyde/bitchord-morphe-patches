package app.bitchord.soulseek;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class NostrConfigTest {
    private static int checks;
    private static final String KEY = repeat("ab", 32);

    interface Task {
        void run() throws Exception;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void denied(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            check(!expected.getMessage().contains(KEY), "Errors do not echo peer keys");
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        List<String> relays =
                NostrConfig.relays(
                        " WSS://Relay.Example.org:443/ \r\n\nwss://relay.example.org\n"
                                + "wss://other.example.net/nostr%20relay ");
        check(
                relays.equals(
                        Arrays.asList(
                                "wss://relay.example.org",
                                "wss://other.example.net/nostr%20relay")),
                "Canonical WSS URLs preserve paths and deduplicate");
        check(
                NostrConfig.lines(relays)
                        .equals("wss://relay.example.org\nwss://other.example.net/nostr%20relay"),
                "Line export round trips");
        check(
                NostrConfig.relays(NostrConfig.lines(relays)).equals(relays),
                "Saved relay configuration round trips");
        try {
            relays.add("wss://third.example.org");
            throw new AssertionError("Relay list must be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
        String[] badRelays = {
            "",
            "\n ",
            "http://relay.example.org",
            "ws://relay.example.org",
            "wss://relay.example.org:80",
            "wss://relay.example.org:0443",
            "wss://relay.example.org:",
            "wss://user:password@relay.example.org",
            "wss://relay.example.org?token=secret",
            "wss://relay.example.org#fragment",
            "wss://localhost",
            "wss://relay.localhost",
            "wss://device.local",
            "wss://relay.internal",
            "wss://relay.invalid",
            "wss://relay.test",
            "wss://127.0.0.1",
            "wss://127.1",
            "wss://2130706433",
            "wss://[::1]",
            "wss://[2001:4860:4860::8888]",
            "wss://relay..example.org",
            "wss://-relay.example.org",
            "wss://relay.example.org.",
            "wss://relay.example.org/a%0D%0Ab",
            "wss://relay.example.org/%00",
            "wss://relay.example.org/%7f",
            "wss://relay.example.org/\u0000",
            "wss://relay.example.org/\t",
            "wss://relay.example.org/\u200B",
            "wss://relay.example.org\rwss://other.example.org",
            "wss://one.example.org\n"
                    + "wss://two.example.org\n"
                    + "wss://three.example.org\n"
                    + "wss://four.example.org"
        };
        for (String bad : badRelays)
            denied(() -> NostrConfig.relays(bad), "Invalid relay accepted");
        denied(() -> NostrConfig.relays(null), "Null relay config accepted");
        denied(() -> NostrConfig.relays(repeat(" ", 2049)), "Oversized relay config accepted");
        List<String> peers =
                NostrConfig.peers(
                        " "
                                + KEY.toUpperCase(java.util.Locale.ROOT)
                                + "\r\n"
                                + KEY
                                + "\n"
                                + repeat("01", 32));
        check(
                peers.equals(Arrays.asList(KEY, repeat("01", 32))),
                "Keys canonicalize and deduplicate");
        try {
            peers.clear();
            throw new AssertionError("Peer list must be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
        for (String bad :
                new String[] {
                    "",
                    "nsec1secret",
                    "npub1public",
                    repeat("ab", 31),
                    repeat("ab", 33),
                    repeat("gg", 32),
                    KEY + " x",
                    KEY + "\t",
                    KEY + "\u200B"
                }) denied(() -> NostrConfig.peers(bad), "Invalid peer config accepted");
        denied(() -> NostrConfig.peers(null), "Null peer config accepted");
        denied(() -> NostrConfig.peers(repeat(" ", 2049)), "Oversized peer config accepted");
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 17; i++) tooMany.append(String.format("%064x", i)).append('\n');
        denied(() -> NostrConfig.peers(tooMany.toString()), "Too many peers accepted");
        check(
                NostrConfig.peers(tooMany.substring(0, 16 * 65)).size() == 16,
                "Sixteen peers accepted");
        check(
                NostrConfig.relays(
                                        "wss://one.example.org\n"
                                                + "wss://two.example.org\n"
                                                + "wss://three.example.org")
                                .size()
                        == 3,
                "Three relays accepted");
        System.out.println("PASS: " + checks + " Nostr configuration checks.");
    }
}
