package app.bitchord.soulseek;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BulkCoordinatorTest {
    static final String SOURCE = repeat('a', 64),
            OTHER = repeat('b', 64),
            UNKNOWN = repeat('c', 64);
    static final String SESSION = repeat('1', 32),
            FILE = repeat('2', 32),
            PEER_ID = repeat('A', 32);
    static int checks;

    interface Action {
        void run() throws Exception;
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder();
        while (text.length() < count) text.append(value);
        return text.toString();
    }

    static void denied(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    static BulkCoordinator fresh() throws Exception {
        DownloadSettings.on = true;
        Field singleton = BulkCoordinator.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        NostrCoordinator.instance = new NostrCoordinator();
        NostrCoordinator.instance.peers = SOURCE + "\n" + OTHER;
        BulkDownloads.instance = new BulkDownloads();
        DownloadDestinations.current =
                new DownloadDestinations.Snapshot("content://original-folder");
        AudioFormatPreferences.current =
                new AudioFormatPreferences.Settings("original formats", true);
        BulkCoordinator coordinator =
                BulkCoordinator.get(
                        new Context(Files.createTempDirectory("bulk-control").toFile()));
        coordinator.start();
        return coordinator;
    }

    static JSONObject envelope(String action) throws Exception {
        return new JSONObject().put("protocol", BulkCoordinator.PROTOCOL).put("action", action);
    }

    static JSONObject file() throws Exception {
        String sha = repeat('d', 64), manifest = repeat('e', 64);
        return new JSONObject()
                .put("protocol", "app.bitchord.bulk/1")
                .put("fileId", FILE)
                .put("token", repeat('f', 64))
                .put("name", "selected.mp3")
                .put("size", 1)
                .put("sha256", sha)
                .put("manifestSha256", manifest)
                .put("manifestCid", BulkManifest.cidForHash(manifest))
                .put("expires", System.currentTimeMillis() + 3_600_000)
                .put("routes", new JSONObject().put("direct", "{\"candidates\":[]}"));
    }

    static void deliver(
            BulkCoordinator coordinator,
            String peer,
            String session,
            String type,
            JSONObject payload)
            throws Exception {
        coordinator.onMessage(NostrCoordinator.instance.generation, peer, session, type, payload);
        Field field = BulkCoordinator.class.getDeclaredField("control");
        field.setAccessible(true);
        ((ThreadPoolExecutor) field.get(coordinator)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    static void offer(BulkCoordinator coordinator, String session) throws Exception {
        deliver(coordinator, SOURCE, session, "offer", envelope("offer").put("file", file()));
    }

    static JSONObject grant(NostrCoordinator.Sent request) throws Exception {
        return envelope("grant")
                .put("fileId", FILE)
                .put("requestId", request.payload.getString("requestId"))
                .put("receiverIpfsPeerId", request.payload.getString("receiverIpfsPeerId"));
    }

    static void incomingAuthorization() throws Exception {
        BulkCoordinator coordinator = fresh();
        deliver(coordinator, UNKNOWN, SESSION, "offer", envelope("offer").put("file", file()));
        check(coordinator.offers().isEmpty(), "A key outside saved peers cannot offer a file");
        deliver(coordinator, SOURCE, "invalid", "offer", envelope("offer").put("file", file()));
        check(coordinator.offers().isEmpty(), "An invalid session is rejected before dispatch");
        deliver(
                coordinator,
                SOURCE,
                SESSION,
                "offer",
                envelope("offer").put("padding", repeat('z', 8192)).put("file", file()));
        check(coordinator.offers().isEmpty(), "Oversized payload is rejected before dispatch");
        JSONObject expired = file().put("expires", System.currentTimeMillis() - 1);
        deliver(coordinator, SOURCE, SESSION, "offer", envelope("offer").put("file", expired));
        check(
                coordinator.offers().isEmpty(),
                "An expired source capability cannot become a new offer");
        offer(coordinator, SESSION);
        check(
                coordinator.offers().size() == 1,
                "A saved source's bounded file offer reaches the inbox");
        deliver(coordinator, OTHER, SESSION, "offer", envelope("offer").put("file", file()));
        check(
                coordinator.offers().get(0).peer.equals(SOURCE),
                "A session collision cannot replace the source identity");
        deliver(coordinator, SOURCE, SESSION, "answer", envelope("grant").put("fileId", FILE));
        check(BulkDownloads.instance.queued == 0, "A sender cannot grant an unaccepted offer");
        AudioFormatPreferences.current =
                new AudioFormatPreferences.Settings("unconfirmed formats", false);
        denied(
                () -> coordinator.accept(SESSION),
                "Format setup must finish before consent starts a transfer");
        AudioFormatPreferences.current =
                new AudioFormatPreferences.Settings("original formats", true);
        coordinator.accept(SESSION);
        NostrCoordinator.Sent request = NostrCoordinator.instance.last();
        check(
                request.type.equals("answer")
                        && request.payload.getString("action").equals("request"),
                "Explicit recipient approval sends an authenticated request");
        check(
                request.payload.getString("receiverDirect").contains("candidates"),
                "Receiver direct candidates accompany its request");
        check(
                request.payload.getString("receiverIpfsPeerId").equals(PEER_ID),
                "IPFS grant is bound to this receiver node");
        DownloadDestinations.current =
                new DownloadDestinations.Snapshot("content://changed-folder");
        AudioFormatPreferences.current =
                new AudioFormatPreferences.Settings("changed formats", true);
        deliver(coordinator, OTHER, SESSION, "answer", grant(request));
        check(
                BulkDownloads.instance.queued == 0,
                "Another trusted peer cannot grant a different source's file");
        deliver(
                coordinator,
                SOURCE,
                SESSION,
                "answer",
                grant(request).put("fileId", repeat('3', 32)));
        check(BulkDownloads.instance.queued == 0, "A grant cannot switch the file");
        deliver(
                coordinator,
                SOURCE,
                SESSION,
                "answer",
                grant(request).put("requestId", repeat('4', 32)));
        check(
                BulkDownloads.instance.queued == 0,
                "A stale request nonce cannot authorize a download");
        deliver(
                coordinator,
                SOURCE,
                SESSION,
                "answer",
                grant(request).put("receiverIpfsPeerId", repeat('B', 32)));
        check(
                BulkDownloads.instance.queued == 0,
                "A grant for another IPFS identity cannot be reused");
        deliver(coordinator, SOURCE, SESSION, "answer", grant(request));
        check(
                BulkDownloads.instance.queued == 1,
                "A complete matching grant queues exactly one download");
        check(
                BulkDownloads.instance.lastDestination.phoneTreeUri.equals(
                        "content://original-folder"),
                "The job keeps the destination selected at acceptance");
        check(
                BulkDownloads.instance.lastFormatPolicy.equals("original formats"),
                "The job keeps the format choices selected at acceptance");
        deliver(coordinator, SOURCE, SESSION, "answer", grant(request));
        check(BulkDownloads.instance.queued == 1, "A repeated grant cannot duplicate a job");
    }

    static void outgoingAuthorization() throws Exception {
        BulkCoordinator coordinator = fresh();
        BulkDownloads.Share share = new BulkDownloads.Share(FILE, file().toString());
        denied(
                () -> coordinator.sendOffer(share, UNKNOWN),
                "Outgoing offers require an already saved app key");
        coordinator.sendOffer(share, SOURCE);
        NostrCoordinator.Sent sent = NostrCoordinator.instance.last();
        check(
                BulkDownloads.instance.recipientGrants == 1,
                "Source publication is scoped to the chosen paired recipient");
        JSONObject request =
                envelope("request")
                        .put("fileId", FILE)
                        .put("requestId", repeat('5', 32))
                        .put("receiverIpfsPeerId", PEER_ID)
                        .put("receiverDirect", "{\"candidates\":[]}");
        deliver(coordinator, OTHER, sent.session, "answer", request);
        check(
                BulkDownloads.instance.authorized == 0,
                "Another saved peer cannot use a recipient's offer session");
        deliver(
                coordinator,
                SOURCE,
                sent.session,
                "answer",
                new JSONObject(request.toString()).put("fileId", repeat('6', 32)));
        check(
                BulkDownloads.instance.authorized == 0,
                "An offer session cannot authorize an unrelated file");
        deliver(
                coordinator,
                SOURCE,
                sent.session,
                "answer",
                new JSONObject(request.toString())
                        .put("receiverIpfsPeerId", "/ip4/127.0.0.1/tcp/1"));
        check(
                BulkDownloads.instance.authorized == 0,
                "A multiaddress cannot substitute for an IPFS peer identity");
        BulkDownloads.instance.punchFails = true;
        deliver(coordinator, SOURCE, sent.session, "answer", request);
        check(
                BulkDownloads.instance.authorized == 1,
                "A source authorizes only a matching paired file request");
        check(
                BulkDownloads.instance.punches == 1,
                "The source punches toward the authenticated receiver candidates");
        check(
                NostrCoordinator.instance.last().payload.getString("action").equals("grant"),
                "A failed direct punch still permits the fallback routes");
        deliver(
                coordinator,
                SOURCE,
                sent.session,
                "answer",
                new JSONObject(request.toString()).put("receiverIpfsPeerId", repeat('B', 32)));
        check(
                BulkDownloads.instance.authorized == 1,
                "A granted session cannot change its recipient node");
        coordinator.revoke(FILE);
        check(
                NostrCoordinator.instance.last().type.equals("cancel"),
                "Revocation sends a bounded cancellation for its source session");
        deliver(coordinator, SOURCE, sent.session, "answer", request);
        check(BulkDownloads.instance.authorized == 1, "A revoked offer cannot issue a fresh grant");
    }

    static void expiryAndStop() throws Exception {
        BulkCoordinator coordinator = fresh();
        check(
                coordinator.isRunning(),
                "Explicitly started coordination keeps its foreground lifetime active");
        offer(coordinator, SESSION);
        check(coordinator.hasPendingHandshake(), "A pending offer retains the rendezvous lifetime");
        SystemClock.advance(120_001);
        check(
                coordinator.offers().isEmpty(),
                "Inbox approval is bounded by the signaling lifetime");
        check(
                !coordinator.hasPendingHandshake(),
                "Expired offers do not keep an idle service alive");
        denied(() -> coordinator.accept(SESSION), "An expired incoming offer cannot be accepted");
        offer(coordinator, repeat('7', 32));
        long old = NostrCoordinator.instance.generation;
        coordinator.stop();
        check(
                !coordinator.isRunning(),
                "Explicit stop releases the coordination foreground lifetime");
        check(coordinator.offers().isEmpty(), "Stopping clears pending incoming consent sessions");
        coordinator.onMessage(old, SOURCE, SESSION, "offer", envelope("offer").put("file", file()));
        check(
                coordinator.offers().isEmpty(),
                "Old native callbacks cannot restore a stopped inbox");
        denied(
                () -> coordinator.accept(SESSION),
                "Stopped coordination cannot start a grant exchange");
        denied(
                () -> BulkCoordinator.validateDirect("{\"padding\":\"" + repeat('z', 2048) + "\"}"),
                "Direct candidates have their own byte limit");
        denied(
                () -> BulkCoordinator.validateDirect("not-json"),
                "Direct candidates must be structured data");
    }

    static void featureGate() throws Exception {
        BulkCoordinator coordinator = fresh();
        DownloadSettings.on = false;
        denied(() -> coordinator.start(), "disabled peer patch cannot start coordination");
        denied(() -> coordinator.accept(SESSION), "disabled peer patch cannot accept a file");
        check(!coordinator.isRunning(), "disabled peer patch immediately stops reporting active");
        coordinator.onMessage(
                NostrCoordinator.instance.generation,
                SOURCE,
                SESSION,
                "offer",
                envelope("offer").put("file", file()));
        check(coordinator.offers().isEmpty(), "disabled peer patch ignores incoming offers");
        coordinator.disablePeers();
        check(BulkDownloads.instance.peerPauses == 1, "disabling peers uses targeted peer pause");
        DownloadSettings.on = true;
    }

    public static void main(String[] args) throws Exception {
        incomingAuthorization();
        outgoingAuthorization();
        expiryAndStop();
        featureGate();
        System.out.println("PASS: " + checks + " bulk coordination checks.");
    }
}
