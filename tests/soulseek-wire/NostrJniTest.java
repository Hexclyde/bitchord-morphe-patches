package app.bitchord.soulseek;

/** Checks the shipped signaling JNI methods without opening a relay connection. */
public final class NostrJniTest {
    private static final String SECRET =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String PUBLIC =
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final String PEER =
            "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";
    private static final String SESSION = "0123456789abcdef0123456789abcdef";
    private static final String MARKER = "private-fixture-value";
    private static final String RELAYS = "[\"wss://relay.example\"]";
    private static final String PEERS = "[\"" + PEER + "\"]";
    private static int checks;

    private interface Action {
        void run() throws Exception;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void fails(Class<? extends Exception> type, Action action, String message)
            throws Exception {
        try {
            action.run();
        } catch (Exception expected) {
            check(type.isInstance(expected), message + " uses the expected exception type");
            String text = expected.getMessage();
            check(text != null && !text.isEmpty(), message + " has an actionable message");
            check(
                    !text.contains(SECRET)
                            && !text.contains(PEER)
                            && !text.contains(MARKER)
                            && !text.contains(SESSION)
                            && !text.contains("relay.example")
                            && !text.contains("INPUT:")
                            && !text.contains("STATE:"),
                    message + " does not include supplied private data or native prefixes");
            return;
        }
        throw new AssertionError(message + " was accepted");
    }

    private static String repeat(char value, int count) {
        char[] buffer = new char[count];
        java.util.Arrays.fill(buffer, value);
        return new String(buffer);
    }

    private static void relayProbeBoundary() throws Exception {
        int before = checks;
        RelayProbeNative.load();
        for (String secret : new String[] {null, "", repeat('0', 64), MARKER}) {
            fails(
                    IllegalArgumentException.class,
                    () -> RelayProbeNative.create(secret),
                    "invalid relay-check identity");
        }
        long handle = RelayProbeNative.create(SECRET);
        check(handle > 0, "relay-check creation returns an opaque handle");
        long second = RelayProbeNative.create(SECRET);
        check(second > 0 && second != handle, "relay-check handles are independent");
        try {
            fails(
                    IllegalStateException.class,
                    () -> RelayProbeNative.create(SECRET),
                    "relay-check session count is bounded");
            for (String relay :
                    new String[] {
                        null,
                        "ws://relay.example",
                        "https://relay.example",
                        "wss://" + MARKER + "@relay.example",
                        "wss://relay.example?secret=" + MARKER,
                        "wss://relay.example:8443",
                        "wss://127.0.0.1",
                        "wss://localhost",
                        "wss://relay.invalid",
                        "wss://relay.example/%0a",
                        "wss://relay.onion",
                        "wss://relay.i2p"
                    }) {
                fails(
                        IllegalArgumentException.class,
                        () -> RelayProbeNative.probe(handle, relay, 2000),
                        "unsafe relay-check address is rejected before networking");
            }
            for (int timeout : new int[] {-1, 0, 1999, 15001}) {
                fails(
                        IllegalArgumentException.class,
                        () -> RelayProbeNative.probe(handle, "wss://relay.example", timeout),
                        "relay-check timeout bounds are checked before networking");
            }
        } finally {
            RelayProbeNative.close(second);
            RelayProbeNative.close(handle);
        }
        fails(
                IllegalStateException.class,
                () -> RelayProbeNative.probe(handle, "wss://relay.example", 2000),
                "closed relay-check handle");
        RelayProbeNative.close(handle);
        RelayProbeNative.close(-1);
        check(true, "relay-check close is idempotent");
        long replacement = RelayProbeNative.create(SECRET);
        check(replacement != handle && replacement != second, "closed identifiers are not reused");
        RelayProbeNative.close(replacement);
        System.out.println(
                "PASS Relay probe JNI: " + (checks - before) + " checks; no connections opened");
    }

    public static void main(String[] args) throws Exception {
        NostrNative.load();
        String generated = NostrNative.generateSecret();
        String another = NostrNative.generateSecret();
        check(generated.matches("[0-9a-f]{64}"), "generated secret has the expected encoding");
        check(!generated.equals(another), "identities use independent randomness");
        String derived = NostrNative.publicKey(generated);
        check(derived.matches("[0-9a-f]{64}"), "generated public key crosses JNI");
        check(derived.equals(NostrNative.publicKey(generated)), "public identity is stable");
        check(PUBLIC.equals(NostrNative.publicKey(SECRET)), "known secp256k1 public key matches");

        for (String secret : new String[] {null, "", repeat('0', 64), MARKER}) {
            fails(
                    IllegalArgumentException.class,
                    () -> NostrNative.publicKey(secret),
                    "invalid identity");
        }
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.open(SECRET, "[]", PEERS),
                "empty relay list is rejected before networking");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.open(SECRET, "{\"" + MARKER + "\":true}", PEERS),
                "malformed relay list");
        for (String relay :
                new String[] {
                    "ws://relay.example",
                    "https://relay.example",
                    "wss://" + MARKER + "@relay.example",
                    "wss://relay.example?secret=" + MARKER,
                    "wss://relay.example:8443",
                    "wss://127.0.0.1",
                    "wss://relay.invalid",
                    "wss://relay.example/%0a"
                }) {
            fails(
                    IllegalArgumentException.class,
                    () -> NostrNative.open(SECRET, "[\"" + relay + "\"]", PEERS),
                    "unsafe relay address");
        }
        for (String peers : new String[] {"[]", "[\"" + PUBLIC + "\"]", "[\"" + MARKER + "\"]"}) {
            fails(
                    IllegalArgumentException.class,
                    () -> NostrNative.open(SECRET, RELAYS, peers),
                    "unpaired peer configuration is rejected before networking");
        }
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.open(null, RELAYS, PEERS),
                "null identity");

        fails(IllegalStateException.class, () -> NostrNative.status(-1), "closed handle status");
        fails(IllegalStateException.class, () -> NostrNative.poll(-1, 0), "closed handle poll");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.poll(-1, 1001),
                "unbounded poll timeout");
        fails(
                IllegalStateException.class,
                () -> NostrNative.send(-1, PEER, SESSION, "ping", "{}"),
                "closed handle send");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.send(-1, MARKER, SESSION, "ping", "{}"),
                "invalid recipient");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.send(-1, repeat('f', 64), SESSION, "ping", "{}"),
                "invalid curve point");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.send(-1, PEER, MARKER, "ping", "{}"),
                "invalid session");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.send(-1, PEER, SESSION, MARKER, "{}"),
                "unsupported signal type");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.send(-1, PEER, SESSION, "offer", "[]"),
                "non-object payload");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.send(-1, PEER, SESSION, "offer", MARKER),
                "malformed payload");
        fails(
                IllegalArgumentException.class,
                () ->
                        NostrNative.send(
                                -1,
                                PEER,
                                SESSION,
                                "offer",
                                "{\"secret\":\"" + MARKER + repeat('x', 8192) + "\"}"),
                "oversized payload");
        fails(
                IllegalArgumentException.class,
                () -> NostrNative.send(-1, PEER, SESSION, "offer", null),
                "null payload");
        NostrNative.close(-1);
        NostrNative.close(-1);
        check(true, "closing an unknown handle is idempotent");
        System.out.println("PASS Nostr JNI: " + checks + " checks; no relay connections opened");
        relayProbeBoundary();
    }
}
