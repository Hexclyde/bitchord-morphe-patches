package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the shipped JNI entry points against a loopback protocol server. */
public final class SoulseekJniWireTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void fails(Action action, String message) throws Exception {
        boolean rejected = false;
        try {
            action.run();
        } catch (Exception expected) {
            rejected = true;
        }
        check(rejected, message);
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String home = Paths.get(args[2]).toAbsolutePath().toString();
        String mode = args[3];
        SoulseekNative.load();
        long handle = SoulseekNative.create(home, host, port, 0);
        check(handle != 0, "create returns a nonzero session handle");
        try {
            if ("reject".equals(mode)) {
                fails(
                        () -> SoulseekNative.connect(handle, "fixture-user", "fixture-password"),
                        "rejected server login reaches Java as an exception");
            } else {
                SoulseekNative.connect(handle, "fixture-user", "fixture-password");
                fails(() -> SoulseekNative.search(handle, "", 1000), "empty query rejected");
                JSONObject search =
                        new JSONObject(SoulseekNative.search(handle, "fixture recording", 1000));
                JSONArray tracks = search.getJSONArray("tracks");
                check(tracks.length() == 0, "a real wire search may return an empty result list");
                JSONObject transfers = new JSONObject(SoulseekNative.transfers(handle));
                check(
                        transfers.getJSONArray("transfers").length() == 0,
                        "new session has no transfer state");
                fails(
                        () ->
                                SoulseekNative.download(
                                        handle, "fixture-peer", "music\\payload.exe", 1024),
                        "non-audio download rejected at the native boundary");
                AtomicReference<Throwable> searchFailure = new AtomicReference<>();
                Thread pending =
                        new Thread(
                                () -> {
                                    try {
                                        SoulseekNative.search(handle, "cancel this search", 15000);
                                    } catch (Throwable expectedOnClose) {
                                        searchFailure.set(expectedOnClose);
                                    }
                                },
                                "jni-search-close-test");
                pending.start();
                Thread.sleep(200);
                long closingAt = System.nanoTime();
                SoulseekNative.close(handle);
                pending.join(5000);
                check(!pending.isAlive(), "closing session unblocks an in-flight search");
                check(
                        (System.nanoTime() - closingAt) / 1_000_000 < 5000,
                        "close and cancelled search finish inside five seconds");
                fails(
                        () -> SoulseekNative.transfers(handle),
                        "a closed native handle cannot read another session's state");
                check(
                        searchFailure.get() == null
                                || !(searchFailure.get() instanceof UnsatisfiedLinkError),
                        "search cancellation crosses the actual JNI boundary");
            }
        } finally {
            // Teardown must not leak a live session if any assertion above fails.
            // Already-closed handles may be rejected by implementations; both are safe.
            try {
                SoulseekNative.close(handle);
            } catch (Exception alreadyClosed) {
                // The stale-handle rejection is itself checked above.
            }
        }
        System.out.println("PASS JNI " + mode + ": " + checks + " checks");
    }
}
