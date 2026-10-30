package app.bitchord.soulseek;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class StunServersTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    interface Task {
        void run() throws Exception;
    }

    private static void denied(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void immutable(List<String> values) {
        try {
            values.add("stun:unexpected.example:3478");
            throw new AssertionError("Result must be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> imported =
                StunServers.parse(
                        "# Candidate endpoints\r\n"
                                + "23.21.150.121:3478\r\n"
                                + "stun.l.google.com:19302\r\n"
                                + "STUN:Stun.L.Google.Com:19302\n"
                                + "stun.cloudflare.com.:3478\n\n");
        check(imported.size() == 3, "Import gist-style lines and remove duplicate URIs");
        check(imported.get(0).equals("stun:23.21.150.121:3478"), "Normalize IPv4 endpoint");
        check(
                imported.get(2).equals("stun:stun.cloudflare.com:3478"),
                "Normalize fully qualified hostname");
        immutable(imported);
        immutable(StunServers.defaults());

        String[] invalid = {
            "https://stun.example:3478",
            "stun://stun.example:3478",
            "turn:stun.example:3478",
            "stuns:stun.example:5349",
            "user:pass@stun.example:3478",
            "stun.example:3478/path",
            "stun.example:3478?transport=udp",
            "stun.example:3478#fragment",
            "stun.example:0",
            "stun.example:-1",
            "stun.example:65536",
            "stun.example:2147483648",
            "stun.example:+3478",
            "stun.example",
            "stun.example:",
            "stun.example:03478",
            " stun.example:3478",
            "stun.example:3478 ",
            "stun.example\t:3478",
            "stun.example\0:3478",
            "stun.example\r:3478",
            "stun.exämple:3478",
            "bad_host.example:3478",
            "-bad.example:3478",
            "bad-.example:3478",
            "bad..example:3478",
            "localhost:3478",
            "256.1.1.1:3478",
            "127.1:3478",
            "010.1.1.1:3478",
            "[2001:db8:::1]:3478",
            "[fe80::1%25wlan0]:3478",
            "2001:4860:4860::8888:3478",
            "[::ffff:192.0.2.1]:3478"
        };
        for (String value : invalid) {
            check(StunServers.parse(value).isEmpty(), "Ignore malformed endpoint: " + value);
        }
        check(
                StunServers.parse("invalid\nstun.example:3478\ninvalid").size() == 1,
                "Retain valid entries alongside malformed lines");
        List<String> ipv6 =
                StunServers.parse(
                        "[2001:4860:4860::8888]:3478\n"
                                + "stun:[2001:4860:4860:0:0:0:0:8888]:3478");
        check(ipv6.size() == 1, "Equivalent IPv6 literals deduplicate without DNS lookup");
        check(ipv6.get(0).startsWith("stun:[2001:4860:4860:"), "Preserve IPv6 brackets");

        denied(() -> StunServers.parse(null), "Reject null input");
        denied(
                () -> StunServers.parse(String.join("", Collections.nCopies(65537, "a"))),
                "Bound raw text size");
        denied(
                () -> StunServers.parse(String.join("", Collections.nCopies(1025, "\n"))),
                "Bound line count");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 513; i++) many.append("stun").append(i).append(".example:3478\n");
        denied(() -> StunServers.parse(many.toString()), "Bound unique candidate count");
        check(
                StunServers.parse(String.join("", Collections.nCopies(301, "a"))).isEmpty(),
                "Ignore oversized individual line");

        List<String> pool =
                Arrays.asList(
                        "stun:stun.l.google.com:19302",
                        "stun:stun1.l.google.com:19302",
                        "stun:stun2.l.google.com:19302",
                        "stun:stun.cloudflare.com:3478",
                        "stun:stun.cloudflare.com:19302",
                        "stun:first.example:3478",
                        "stun:second.example:3478");
        List<String> before = new ArrayList<>(pool);
        for (int i = 0; i < 20; i++) {
            List<String> selected = StunServers.select(pool, new Random(i));
            check(selected.size() == 3, "Cap each connection at three candidates");
            int google = 0;
            int cloudflare = 0;
            for (String candidate : selected) {
                if (candidate.contains(".google.com:")) google++;
                if (candidate.contains(".cloudflare.com:")) cloudflare++;
            }
            check(google <= 1 && cloudflare <= 1, "Group known operators and duplicate hosts");
            immutable(selected);
        }
        check(pool.equals(before), "Selection never mutates the configured pool");
        check(
                StunServers.select(Collections.emptyList(), new Random(1)).isEmpty(),
                "Empty configuration does not silently add endpoints");
        check(
                StunServers.select(
                                        Arrays.asList(
                                                "stun:first.example:3478", "first.example:3478"),
                                        new Random(1))
                                .size()
                        == 1,
                "Select a hostname once regardless of URI spelling");
        check(
                StunServers.select(pool, new Random(8))
                        .equals(StunServers.select(pool, new Random(8))),
                "Seeded selection is deterministic for tests");
        denied(() -> StunServers.select(null, new Random(1)), "Reject null candidate pool");
        denied(() -> StunServers.select(pool, null), "Reject missing randomness source");
        denied(
                () ->
                        StunServers.select(
                                Collections.singletonList("https://bad.example"), new Random(1)),
                "Revalidate externally supplied selections");
        denied(
                () ->
                        StunServers.select(
                                Collections.nCopies(513, "stun:first.example:3478"), new Random(1)),
                "Bound selection input");
        System.out.println("PASS: " + checks + " STUN configuration checks.");
    }
}
