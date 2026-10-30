package app.bitchord.soulseek;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Paired-key file offers and explicit recipient consent over encrypted Nostr messages. */
final class BulkCoordinator implements NostrCoordinator.MessageHandler {
    static final String PROTOCOL = "app.bitchord.bulk-signal/1";
    private static final long OFFER_LIFETIME_MS = 120_000;
    private static BulkCoordinator instance;
    private final Context context;
    private final NostrCoordinator nostr;
    private volatile BulkDownloads downloads;
    private final Object lock = new Object();
    private final Map<String, Incoming> incoming = new LinkedHashMap<>();
    private final Map<String, Outgoing> outgoing = new LinkedHashMap<>();
    private final ThreadPoolExecutor control =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(32),
                    runnable -> {
                        Thread thread = new Thread(runnable, "bitchord-download-control");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean enabled;
    private volatile String status = "Download coordination is stopped.";

    static final class Incoming {
        final String session, peer, fileId, name, json;
        final long size, deadline;
        volatile String state = "Waiting for your approval";
        String requestId = "", receiverPeerId = "", jobId = "";
        DownloadDestinations.Snapshot destination;
        String formatPolicy;

        Incoming(String session, String peer, BulkOffer offer) {
            this.session = session;
            this.peer = peer;
            this.fileId = offer.fileId;
            this.name = offer.name;
            this.size = offer.size;
            this.json = offer.json;
            this.deadline = SystemClock.elapsedRealtime() + OFFER_LIFETIME_MS;
        }
    }

    private static final class Outgoing {
        final String session, peer, fileId;
        final long deadline = SystemClock.elapsedRealtime() + OFFER_LIFETIME_MS;
        String requestId = "", receiverPeerId = "";

        Outgoing(String session, String peer, String fileId) {
            this.session = session;
            this.peer = peer;
            this.fileId = fileId;
        }
    }

    private BulkCoordinator(Context context) throws IOException {
        this.context = context.getApplicationContext();
        nostr = NostrCoordinator.get(this.context);
        downloads = BulkDownloads.get(this.context);
    }

    static synchronized BulkCoordinator get(Context context) throws IOException {
        if (instance == null) instance = new BulkCoordinator(context);
        return instance;
    }

    static synchronized BulkCoordinator existing() {
        return instance;
    }

    void start() throws Exception {
        BulkTransferService.ensureRunning(context);
        downloads = BulkDownloads.get(context);
        enabled = true;
        nostr.setMessageHandler(this);
        try {
            nostr.start();
            status = "Ready for file offers from saved peers.";
        } catch (Exception | LinkageError failure) {
            enabled = false;
            throw failure;
        }
    }

    void stop() throws IOException {
        enabled = false;
        nostr.stop();
        downloads.stop();
        status = "Downloads and coordination are stopped. Resume paused jobs explicitly.";
    }

    String status() {
        return status;
    }

    BulkDownloads downloads() {
        return downloads;
    }

    public boolean isRunning() {
        return enabled && nostr.running();
    }

    boolean hasPendingHandshake() {
        synchronized (lock) {
            expire();
            return enabled && (!incoming.isEmpty() || !outgoing.isEmpty());
        }
    }

    List<Incoming> offers() {
        synchronized (lock) {
            expire();
            return new ArrayList<>(incoming.values());
        }
    }

    void sendOffer(BulkDownloads.Share share, String peer) throws Exception {
        if (!enabled) throw new IOException("Start download coordination first.");
        List<String> peers = NostrConfig.peers(peer);
        if (peers.size() != 1 || !NostrConfig.peers(nostr.peersText()).contains(peers.get(0)))
            throw new IOException("Choose one saved peer's app public key.");
        peer = peers.get(0);
        JSONObject file = new JSONObject(share.offerJson());
        BulkOffer.verified(peer, file.toString());
        String session = token();
        Outgoing offer = new Outgoing(session, peer, share.fileId);
        synchronized (lock) {
            expire();
            if (outgoing.size() >= 16) throw new IOException("Wait for pending file offers.");
            outgoing.put(session, offer);
        }
        try {
            downloads.authorizeRecipient(share.fileId, peer);
            nostr.sendToPeer(peer, session, "offer", envelope("offer").put("file", file));
            status = "File offer sent. The recipient must approve it within two minutes.";
        } catch (Exception | LinkageError failure) {
            synchronized (lock) {
                outgoing.remove(session);
            }
            throw failure;
        }
    }

    void accept(String session) throws Exception {
        if (!enabled) throw new IOException("Start download coordination first.");
        BulkTransferService.ensureRunning(context);
        final Incoming offer;
        final DownloadDestinations.Snapshot destination = DownloadDestinations.snapshot(context);
        final AudioFormatPreferences.Settings formats = AudioFormatPreferences.snapshot(context);
        if (!formats.configured) throw new IOException("Choose download formats first.");
        final String formatPolicy = AudioFormatPreferences.encode(formats);
        synchronized (lock) {
            expire();
            offer = incoming.get(session);
            if (offer == null || !offer.requestId.isEmpty())
                throw new IOException("This offer expired or is already being accepted.");
            offer.requestId = token();
            offer.destination = destination;
            offer.formatPolicy = formatPolicy;
            offer.state = "Preparing the direct connection";
        }
        try {
            downloads.prepareRoutes(offer.peer, offer.json);
            String peerId = new JSONObject(downloads.peerInfo()).optString("peerId", "");
            validatePeerId(peerId);
            String direct = downloads.selfDirectDescriptor();
            validateDirect(direct);
            synchronized (lock) {
                if (!enabled || incoming.get(session) != offer || expired(offer.deadline))
                    throw new IOException("The file offer expired.");
                offer.receiverPeerId = peerId;
                offer.state = "Waiting for the sender's permission";
            }
            nostr.sendToPeer(
                    offer.peer,
                    session,
                    "answer",
                    envelope("request")
                            .put("fileId", offer.fileId)
                            .put("requestId", offer.requestId)
                            .put("receiverIpfsPeerId", peerId)
                            .put("receiverDirect", direct));
            status = "Waiting for the source to authorize this phone.";
        } catch (Exception | LinkageError failure) {
            synchronized (lock) {
                if (incoming.get(session) == offer && offer.jobId.isEmpty()) {
                    offer.requestId = "";
                    offer.receiverPeerId = "";
                    offer.state = "Could not request this download; retry before the offer expires";
                }
            }
            throw failure;
        }
    }

    void decline(String session) {
        synchronized (lock) {
            incoming.remove(session);
        }
    }

    void revoke(String fileId) throws Exception {
        downloads.revokeShare(fileId);
        List<Outgoing> revoked = new ArrayList<>();
        synchronized (lock) {
            Iterator<Outgoing> iterator = outgoing.values().iterator();
            while (iterator.hasNext()) {
                Outgoing offer = iterator.next();
                if (offer.fileId.equals(fileId)) {
                    revoked.add(offer);
                    iterator.remove();
                }
            }
        }
        for (Outgoing offer : revoked) {
            try {
                nostr.sendToPeer(
                        offer.peer,
                        offer.session,
                        "cancel",
                        envelope("cancel").put("fileId", fileId));
            } catch (Exception | LinkageError ignored) {
                // Revocation already removed the serving capability locally.
            }
        }
    }

    @Override
    public void onMessage(
            long generation, String peer, String session, String type, JSONObject payload) {
        if (!enabled
                || !nostr.isActive(generation)
                || !validKey(peer)
                || !validToken(session)
                || payload.toString().getBytes(StandardCharsets.UTF_8).length > 8192) return;
        final String encoded = payload.toString();
        try {
            control.execute(
                    () -> {
                        if (!enabled || !nostr.isActive(generation)) return;
                        try {
                            receive(peer, session, type, new JSONObject(encoded));
                        } catch (Exception | LinkageError invalid) {
                            status = "A file coordination message could not be accepted.";
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            status = "Download coordination is busy; retry the offer shortly.";
        }
    }

    @Override
    public void onStopped() {
        enabled = false;
        status = "Download coordination stopped. Start connections again to receive offers.";
        synchronized (lock) {
            incoming.clear();
            outgoing.clear();
        }
        control.getQueue().clear();
    }

    private void receive(String peer, String session, String type, JSONObject payload)
            throws Exception {
        if (!NostrConfig.peers(nostr.peersText()).contains(peer))
            throw new IOException("Download message is not from a saved peer.");
        if (!PROTOCOL.equals(payload.optString("protocol"))) return;
        String action = payload.getString("action");
        if ("offer".equals(type) && "offer".equals(action)) {
            BulkOffer file = BulkOffer.verified(peer, payload.getJSONObject("file").toString());
            if (file.expires <= System.currentTimeMillis())
                throw new IOException("Expired file offer.");
            synchronized (lock) {
                expire();
                if (incoming.containsKey(session)) return;
                if (incoming.size() >= 16) throw new IOException("Too many pending file offers.");
                incoming.put(session, new Incoming(session, peer, file));
            }
            status = "A saved peer offered a file. Approve it in Peer downloads.";
        } else if ("answer".equals(type) && "request".equals(action)) {
            grant(peer, session, payload);
        } else if ("answer".equals(type) && "grant".equals(action)) {
            queueGranted(peer, session, payload);
        } else if ("cancel".equals(type) && "cancel".equals(action)) {
            synchronized (lock) {
                Incoming offer = incoming.get(session);
                if (offer != null
                        && offer.peer.equals(peer)
                        && offer.fileId.equals(payload.optString("fileId"))
                        && offer.jobId.isEmpty()) incoming.remove(session);
            }
        }
    }

    private void grant(String peer, String session, JSONObject request) throws Exception {
        final Outgoing offer;
        String peerId = request.getString("receiverIpfsPeerId");
        String requestId = request.getString("requestId");
        String direct = request.optString("receiverDirect", "");
        validatePeerId(peerId);
        validateDirect(direct);
        if (!validToken(requestId)) throw new IOException("Invalid download request.");
        synchronized (lock) {
            expire();
            offer = outgoing.get(session);
            if (offer == null
                    || !offer.peer.equals(peer)
                    || !offer.fileId.equals(request.optString("fileId")))
                throw new IOException("The download request does not match a sent offer.");
            if (!offer.requestId.isEmpty()
                    && (!offer.requestId.equals(requestId) || !offer.receiverPeerId.equals(peerId)))
                throw new IOException("The download grant is already bound to another request.");
            offer.requestId = requestId;
            offer.receiverPeerId = peerId;
        }
        downloads.authorize(offer.fileId, peer, peerId);
        if (!direct.isEmpty()) {
            try {
                downloads.onDirectCandidates(peer, offer.fileId, direct);
            } catch (IOException | LinkageError unavailable) {
                status = "Direct connection unavailable. The download can use a fallback.";
            }
        }
        synchronized (lock) {
            if (!enabled || outgoing.get(session) != offer || expired(offer.deadline))
                throw new IOException("The download grant expired.");
        }
        nostr.sendToPeer(
                peer,
                session,
                "answer",
                envelope("grant")
                        .put("fileId", offer.fileId)
                        .put("requestId", requestId)
                        .put("receiverIpfsPeerId", peerId));
        status = "Recipient authorized. The source is ready for a direct connection or fallback.";
    }

    private void queueGranted(String peer, String session, JSONObject grant) throws Exception {
        final Incoming offer;
        synchronized (lock) {
            expire();
            offer = incoming.get(session);
            if (offer == null
                    || !offer.peer.equals(peer)
                    || offer.requestId.isEmpty()
                    || !offer.jobId.isEmpty()
                    || !offer.fileId.equals(grant.optString("fileId"))
                    || !offer.requestId.equals(grant.optString("requestId"))
                    || !offer.receiverPeerId.equals(grant.optString("receiverIpfsPeerId")))
                throw new IOException("The download grant does not match this phone's request.");
            offer.state = "Queueing the download";
        }
        String id = downloads.queue(peer, offer.json, offer.destination, offer.formatPolicy);
        synchronized (lock) {
            offer.jobId = id;
            offer.state = "Queued";
            incoming.remove(session);
        }
        status = "Download queued. The direct connection is attempted first.";
    }

    private void expire() {
        Iterator<Incoming> received = incoming.values().iterator();
        while (received.hasNext()) {
            Incoming offer = received.next();
            if (expired(offer.deadline) && offer.jobId.isEmpty()) received.remove();
        }
        Iterator<Outgoing> sent = outgoing.values().iterator();
        while (sent.hasNext()) if (expired(sent.next().deadline)) sent.remove();
    }

    static void validatePeerId(String peerId) throws IOException {
        if (!peerId.isEmpty() && !peerId.matches("[1-9A-HJ-NP-Za-km-z]{32,160}"))
            throw new IOException("Invalid recipient IPFS peer identity.");
    }

    static void validateDirect(String descriptor) throws IOException {
        if (descriptor.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IOException("Direct connection candidates exceed the size limit.");
        if (!descriptor.isEmpty()) {
            try {
                new JSONObject(descriptor);
            } catch (Exception invalid) {
                throw new IOException("Invalid direct candidates.");
            }
        }
    }

    private static boolean validKey(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean validToken(String value) {
        return value != null && value.matches("[0-9a-f]{32}");
    }

    private static boolean expired(long deadline) {
        return deadline <= SystemClock.elapsedRealtime();
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static JSONObject envelope(String action) throws Exception {
        return new JSONObject().put("protocol", PROTOCOL).put("action", action);
    }
}
