package app.bitchord.soulseek;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Explicit selected-file sharing and resumable downloads. Blocking controls run on a worker. */
public final class BulkDownloads {
    private static BulkDownloads instance;
    private static final int TIMEOUT = 30_000;
    private final Context context;
    private final File home, sourceHome;
    private final BulkJobStore store;
    private final Object lock = new Object(), nativeLock = new Object();
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "bitchord-bulk-writer"));
    private final ExecutorService servers =
            Executors.newFixedThreadPool(2, r -> new Thread(r, "bitchord-bulk-provider"));
    private final Map<String, BulkJobStore.Job> jobs = new LinkedHashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final Map<String, Share> shares = new LinkedHashMap<>();
    private final Set<String> downloadPeers = new HashSet<>();
    private final Set<Closeable> serving = new HashSet<>();
    private final Set<Closeable> stagingResources = new HashSet<>();
    private OverlayClient i2p, tor;
    private DirectClient direct;
    private volatile long ipfs;
    private volatile boolean stopped;
    private volatile boolean shutdownFailed;
    private boolean directAccepting, i2pAccepting, torAccepting;
    private volatile String runtimeStatus = "";
    private long stagingBytes;

    public final class Share {
        public final String fileId, name, sha256;
        public final long size;
        public volatile boolean revoked;
        private boolean nativePublished;
        private final String token;
        private final File file;
        private final BulkManifest manifest;
        private final Set<String> recipients = new HashSet<>();
        private final Map<String, String> peerGrants = new HashMap<>();
        private String directEndpoint = "", i2pEndpoint = "", torEndpoint = "", ipfsInfo = "{}";
        private final long expires = System.currentTimeMillis() + 24L * 60 * 60 * 1000;

        Share(String id, String name, String token, File file, BulkManifest manifest) {
            fileId = id;
            this.name = name;
            this.token = token;
            this.file = file;
            this.manifest = manifest;
            sha256 = manifest.fileSha256;
            size = manifest.size;
        }

        public String offerJson() throws IOException {
            if (revoked) throw new IOException("The selected file is no longer shared.");
            try {
                JSONObject routes =
                        new JSONObject()
                                .put("direct", directEndpoint)
                                .put("i2p", i2pEndpoint)
                                .put("tor", torEndpoint);
                JSONObject nativeInfo = new JSONObject(ipfsInfo);
                JSONObject ipfsRoute =
                        new JSONObject()
                                .put("peerId", nativeInfo.optString("peerId", ""))
                                .put(
                                        "addresses",
                                        nativeInfo.optJSONArray("listenAddresses") == null
                                                ? new JSONArray()
                                                : nativeInfo.getJSONArray("listenAddresses"));
                String result =
                        new JSONObject()
                                .put("protocol", "app.bitchord.bulk/1")
                                .put("fileId", fileId)
                                .put("name", name)
                                .put("size", size)
                                .put("sha256", sha256)
                                .put("manifestSha256", manifest.sha256)
                                .put("manifestCid", manifest.cid)
                                .put("token", token)
                                .put("expires", expires)
                                .put("routes", routes)
                                .put("ipfs", ipfsRoute)
                                .toString();
                if (result.getBytes(StandardCharsets.UTF_8).length > 7168)
                    throw new IOException("File offer is too large.");
                return result;
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Cannot create file offer.", error);
            }
        }
    }

    private static final class Run implements BulkTransfer.Cancellation {
        volatile boolean cancelled;
        volatile Closeable active;
        volatile Thread owner;
        Future<?> future;

        synchronized boolean claim() {
            if (cancelled) return false;
            owner = Thread.currentThread();
            return true;
        }

        synchronized boolean owned() {
            return owner != null;
        }

        public void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new IOException("Download paused or cancelled.");
        }

        synchronized void bind(Closeable connection) throws IOException {
            if (cancelled) {
                connection.close();
                check();
            }
            active = connection;
        }

        synchronized void clear(Closeable connection) {
            if (active == connection) active = null;
        }

        synchronized void stop() {
            cancelled = true;
            if (owner != null) owner.interrupt();
            if (active != null)
                try {
                    active.close();
                } catch (IOException ignored) {
                }
        }
    }

    private BulkDownloads(Context context) throws IOException {
        this.context = context.getApplicationContext();
        home = new File(this.context.getNoBackupFilesDir(), "bitchord-bulk");
        sourceHome = new File(home, "shares");
        if (!sourceHome.isDirectory() && !sourceHome.mkdirs())
            throw new IOException("Cannot create selected-file storage.");
        store = new BulkJobStore(new File(home, "jobs"));
        for (BulkJobStore.Job job : store.load()) jobs.put(job.id, job);
        // Sharing leases do not survive app restart. No selected file is silently re-published.
        File[] old = sourceHome.listFiles();
        if (old != null)
            for (File file : old)
                if (file.isFile() && !file.delete())
                    runtimeStatus = "A previous share needs storage cleanup.";
    }

    public static synchronized BulkDownloads get(Context context) throws IOException {
        if (instance != null && instance.shutdownFailed)
            throw new IOException("Restart BitChord before starting download runtimes again.");
        if (instance == null || instance.stopped) instance = new BulkDownloads(context);
        return instance;
    }

    public static synchronized BulkDownloads existing() {
        return instance;
    }

    public boolean hasActiveWork() {
        synchronized (lock) {
            return !runs.isEmpty()
                    || !shares.isEmpty()
                    || stagingBytes > 0
                    || !stagingResources.isEmpty();
        }
    }

    public String status() {
        return runtimeStatus;
    }

    public List<BulkJobStore.Job> jobs() {
        synchronized (lock) {
            return new ArrayList<>(jobs.values());
        }
    }

    public List<Share> shares() {
        synchronized (lock) {
            return new ArrayList<>(shares.values());
        }
    }

    /**
     * Copies only the user-selected file; caller retains no writable reference to this snapshot.
     */
    public Share stage(Uri selectedFile, String displayName) throws IOException {
        AudioFormatPreferences.Settings formatSettings = AudioFormatPreferences.snapshot(context);
        String name = BulkOffer.audioName(displayName), id = random(16);
        File copy = new File(sourceHome, id + ".audio");
        long length = 0, reserved = 0;
        String publishedCid = null;
        CancellationSignal opening = new CancellationSignal();
        Closeable cancelOpen = opening::cancel;
        InputStream input = null;
        synchronized (lock) {
            ensureOpen();
            if (shares.size() >= 8) throw new IOException("Stop an existing share first.");
            stagingResources.add(cancelOpen);
        }
        try {
            ParcelFileDescriptor descriptor =
                    context.getContentResolver().openFileDescriptor(selectedFile, "r", opening);
            if (descriptor == null) throw new IOException("Cannot open the selected file.");
            input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            synchronized (lock) {
                ensureOpen();
                stagingResources.add(input);
            }
            try (FileOutputStream output = new FileOutputStream(copy)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    if (count == 0) continue;
                    length += count;
                    synchronized (lock) {
                        ensureOpen();
                        if (length > BulkManifest.MAX_SIZE
                                || usedBytes() + count > BulkManifest.MAX_SIZE)
                            throw new IOException(
                                    "Selected-file storage is limited to 256 MiB. Remove a"
                                            + " completed download or stop a share.");
                        stagingBytes += count;
                        reserved += count;
                    }
                    output.write(bytes, 0, count);
                }
                output.getFD().sync();
            }
            input.close();
            synchronized (lock) {
                stagingResources.remove(input);
            }
            input = null;
            if (length == 0) throw new IOException("The selected file is empty.");
            AudioFormatPreferences.validateSelectedFile(formatSettings, copy, name);
            BulkManifest manifest;
            String info = peerInfo();
            if (ipfs != 0) {
                try {
                    JSONObject publication;
                    synchronized (nativeLock) {
                        publication =
                                new JSONObject(IpfsNative.publish(ipfs, copy.getAbsolutePath()));
                    }
                    publishedCid = publication.getString("manifestCid");
                    byte[] bytes =
                            publication.getString("manifestJson").getBytes(StandardCharsets.UTF_8);
                    manifest = BulkManifest.parse(bytes, publication.getString("manifestSha256"));
                    if (!manifest.cid.equals(publication.getString("manifestCid")))
                        throw new IOException("IPFS manifest identity mismatch.");
                } catch (Exception | LinkageError error) {
                    if (publishedCid != null) {
                        synchronized (nativeLock) {
                            if (ipfs != 0) IpfsNative.unpublish(ipfs, publishedCid);
                        }
                        publishedCid = null;
                    }
                    runtimeStatus =
                            "IPFS publication unavailable; direct, I2P and Tor remain available.";
                    info = "{}";
                    manifest = localManifest(copy);
                }
            } else manifest = localManifest(copy);
            Share share = new Share(id, name, random(32), copy, manifest);
            share.ipfsInfo = info;
            share.nativePublished = publishedCid != null;
            synchronized (lock) {
                ensureOpen();
                shares.put(id, share);
                stagingBytes -= reserved;
                reserved = 0;
            }
            startProviders(share);
            synchronized (lock) {
                ensureOpen();
            }
            BulkOffer.verified(repeatZeroKey(), share.offerJson());
            return share;
        } catch (Exception | LinkageError error) {
            if (publishedCid != null) {
                try {
                    synchronized (nativeLock) {
                        if (ipfs != 0) IpfsNative.unpublish(ipfs, publishedCid);
                    }
                } catch (Exception | LinkageError cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            copy.delete();
            synchronized (lock) {
                shares.remove(id);
                stagingBytes -= reserved;
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Cannot prepare the selected audio file.", error);
        } finally {
            if (input != null)
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            synchronized (lock) {
                stagingResources.remove(cancelOpen);
                stagingResources.remove(input);
            }
        }
    }

    public void authorizeRecipient(String fileId, String trustedRecipientKey) throws IOException {
        if (!BulkManifest.hex64(trustedRecipientKey))
            throw new IOException("Invalid paired identity.");
        synchronized (lock) {
            requireShare(fileId).recipients.add(trustedRecipientKey);
        }
    }

    public void authorize(String fileId, String trustedRecipientKey, String receiverIpfsPeerId)
            throws IOException {
        if (receiverIpfsPeerId == null || receiverIpfsPeerId.length() > 160)
            throw new IOException("Invalid receiver identity.");
        synchronized (lock) {
            Share share = requireShare(fileId);
            if (!share.recipients.contains(trustedRecipientKey))
                throw new IOException("This peer was not offered the selected file.");
            share.peerGrants.put(trustedRecipientKey, receiverIpfsPeerId);
            updatePeers();
            updatePublicationPeers(share);
        }
    }

    public void onDirectCandidates(String trustedPeer, String fileId, String descriptor)
            throws IOException {
        DirectClient client;
        synchronized (lock) {
            Share share = requireShare(fileId);
            if (!share.recipients.contains(trustedPeer))
                throw new IOException("Unsolicited direct candidates.");
            client = direct;
        }
        if (descriptor == null || descriptor.length() > 2048)
            throw new IOException("Invalid direct candidates.");
        if (client != null && !descriptor.isEmpty()) client.punch(descriptor, TIMEOUT);
    }

    public String selfDirectDescriptor() {
        try {
            return direct().endpoint(TIMEOUT);
        } catch (Exception | LinkageError error) {
            runtimeStatus = "Direct connection unavailable; fallback routes remain available.";
            return "";
        }
    }

    /** Starts user-requested foreground services while the Accept action is still active. */
    public void prepareRoutes(String trustedPeer, String verifiedOfferJson) throws IOException {
        BulkOffer offer = BulkOffer.verified(trustedPeer, verifiedOfferJson);
        if (!offer.direct.isEmpty()) selfDirectDescriptor();
        if (!offer.i2p.isEmpty())
            try {
                overlay(1);
            } catch (Exception | LinkageError error) {
                runtimeStatus = "I2P could not start; other routes remain available.";
            }
        if (!offer.tor.isEmpty())
            try {
                overlay(2);
            } catch (Exception | LinkageError error) {
                runtimeStatus = "Tor could not start; other routes remain available.";
            }
    }

    public String peerInfo() {
        synchronized (nativeLock) {
            try {
                synchronized (lock) {
                    ensureOpen();
                    if (ipfs == 0) {
                        File directory = new File(home, "ipfs");
                        if (!directory.isDirectory() && !directory.mkdirs())
                            throw new IOException("Cannot create IPFS storage.");
                        IpfsNative.load();
                        ipfs = IpfsNative.create(directory.getAbsolutePath(), "[]");
                    }
                }
                return IpfsNative.info(ipfs);
            } catch (Exception | LinkageError error) {
                runtimeStatus = "IPFS unavailable; other download routes remain available.";
                return "{\"peerId\":\"\",\"listenAddresses\":[]}";
            }
        }
    }

    /** verifiedSignedOfferJson must come from the paired, verified Nostr offer/grant exchange. */
    public String queue(
            String trustedSourceKey, String verifiedSignedOfferJson, Uri destinationTree)
            throws IOException {
        return queuePinned(
                trustedSourceKey,
                verifiedSignedOfferJson,
                destinationTree == null ? "" : destinationTree.toString(),
                "phone",
                0,
                0,
                AudioFormatPreferences.encode(AudioFormatPreferences.snapshot(context)));
    }

    public String queue(
            String trustedSourceKey,
            String verifiedSignedOfferJson,
            DownloadDestinations.Snapshot destination)
            throws IOException {
        return queue(
                trustedSourceKey,
                verifiedSignedOfferJson,
                destination,
                AudioFormatPreferences.encode(AudioFormatPreferences.snapshot(context)));
    }

    public String queue(
            String trustedSourceKey,
            String verifiedSignedOfferJson,
            DownloadDestinations.Snapshot destination,
            String formatPolicy)
            throws IOException {
        return queuePinned(
                trustedSourceKey,
                verifiedSignedOfferJson,
                destination.phoneTreeUri,
                destination.mode,
                destination.telegramChatId,
                destination.telegramUserId,
                formatPolicy);
    }

    private String queuePinned(
            String trustedSourceKey,
            String verifiedSignedOfferJson,
            String tree,
            String mode,
            long chat,
            long user,
            String formatPolicy)
            throws IOException {
        AudioFormatPreferences.decode(formatPolicy);
        BulkOffer offer = BulkOffer.verified(trustedSourceKey, verifiedSignedOfferJson);
        if (offer.expires < System.currentTimeMillis())
            throw new IOException("Ask the peer for a new file offer.");
        synchronized (lock) {
            ensureOpen();
            if (jobs.size() >= 128)
                throw new IOException(
                        "Remove a finished download from the list before adding another.");
            if (usedBytes() + offer.size > BulkManifest.MAX_SIZE)
                throw new IOException("Selected-file storage is limited to 256 MiB.");
            if (new File(home.getAbsolutePath()).getUsableSpace() < offer.size + 8L * 1024 * 1024)
                throw new IOException("Insufficient private storage for this download.");
            BulkJobStore.Job job = store.create(offer, tree, mode, chat, user, formatPolicy);
            jobs.put(job.id, job);
            submit(job);
            return job.id;
        }
    }

    /**
     * Takes ownership of a completed, private selected-file download without any network re-search.
     */
    public String importVerified(
            File privateSource,
            String displayName,
            DownloadDestinations.Snapshot destination,
            String durableToken)
            throws IOException {
        return importVerified(
                privateSource,
                displayName,
                destination,
                durableToken,
                AudioFormatPreferences.encode(AudioFormatPreferences.snapshot(context)));
    }

    public String importVerified(
            File privateSource,
            String displayName,
            DownloadDestinations.Snapshot destination,
            String durableToken,
            String formatPolicy)
            throws IOException {
        if (durableToken == null || durableToken.isEmpty() || durableToken.length() > 256)
            throw new IOException("Invalid durable download token.");
        String prefix = context.getNoBackupFilesDir().getCanonicalPath() + File.separator;
        if (!privateSource.getCanonicalPath().startsWith(prefix) || !privateSource.isFile())
            throw new IOException("Selected-file source must be private completed staging data.");
        String name = BulkOffer.audioName(displayName);
        AudioFormatPreferences.validateSelectedFile(
                AudioFormatPreferences.decode(formatPolicy), privateSource, name);
        BulkManifest manifest = localManifest(privateSource);
        String id =
                BulkManifest.hash(durableToken.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        synchronized (lock) {
            ensureOpen();
            BulkJobStore.Job previous = jobs.get(id);
            if (previous != null) {
                if (!previous.localOnly
                        || !previous.offer.sha256.equals(manifest.fileSha256)
                        || !previous.offer.name.equals(name)
                        || !previous.destination.equals(destination.phoneTreeUri)
                        || !previous.destinationMode.equals(destination.mode)
                        || previous.telegramChatId != destination.telegramChatId
                        || previous.telegramUserId != destination.telegramUserId
                        || !previous.formatPolicy.equals(formatPolicy))
                    throw new IOException("Durable download token has a different file identity.");
                if (previous.state == BulkJobStore.State.CANCELLED)
                    throw new IOException("This selected-file job was cancelled.");
                if (previous.state != BulkJobStore.State.COMPLETE) {
                    if (!store.complete(previous).isFile()) {
                        BulkJobStore.atomic(store.manifest(previous), manifest.bytes);
                        java.nio.file.Files.move(
                                privateSource.toPath(),
                                store.complete(previous).toPath(),
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } else if (!privateSource
                            .getCanonicalFile()
                            .equals(store.complete(previous).getCanonicalFile()))
                        delete(privateSource);
                    previous.verifiedChunks = manifest.blocks.size();
                    previous.state = BulkJobStore.State.VERIFIED;
                    store.save(previous);
                    if (!runs.containsKey(id)) submit(previous);
                } else delete(privateSource);
                return id;
            }
            if (usedBytes() + manifest.size > BulkManifest.MAX_SIZE)
                throw new IOException("Selected-file storage is limited to 256 MiB.");
            try {
                JSONObject offerJson =
                        new JSONObject()
                                .put("protocol", "app.bitchord.bulk/1")
                                .put("fileId", id)
                                .put("token", random(32))
                                .put("name", name)
                                .put("size", manifest.size)
                                .put("sha256", manifest.fileSha256)
                                .put("manifestSha256", manifest.sha256)
                                .put("manifestCid", manifest.cid)
                                .put("expires", Long.MAX_VALUE)
                                .put("routes", new JSONObject().put("direct", "local"));
                BulkJobStore.Job job =
                        store.createLocal(
                                id,
                                BulkOffer.verified(repeatZeroKey(), offerJson.toString()),
                                destination.phoneTreeUri,
                                destination.mode,
                                destination.telegramChatId,
                                destination.telegramUserId,
                                formatPolicy);
                jobs.put(id, job);
                BulkJobStore.atomic(store.manifest(job), manifest.bytes);
                java.nio.file.Files.move(
                        privateSource.toPath(),
                        store.complete(job).toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                job.verifiedChunks = manifest.blocks.size();
                store.save(job);
                submit(job);
                return id;
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Cannot import the selected download.", error);
            }
        }
    }

    public void resume(String id) throws IOException {
        synchronized (lock) {
            ensureOpen();
            BulkJobStore.Job job = requireJob(id);
            if (job.state == BulkJobStore.State.COMPLETE
                    || job.state == BulkJobStore.State.CANCELLED
                    || runs.containsKey(id)) return;
            if (!job.localOnly
                    && !store.complete(job).isFile()
                    && job.offer.expires < System.currentTimeMillis())
                throw new IOException("Ask the peer for a new file offer.");
            job.state = BulkJobStore.State.QUEUED;
            job.message = "";
            store.save(job);
            submit(job);
        }
    }

    public void pause(String id) throws IOException {
        halt(id, false);
    }

    public void cancel(String id) throws IOException {
        halt(id, true);
    }

    /** Removes a finished journal and its private staging; SAF and Telegram copies stay saved. */
    public void forget(String id) throws IOException {
        synchronized (lock) {
            BulkJobStore.Job job = requireJob(id);
            if (runs.containsKey(id)
                    || (job.state != BulkJobStore.State.CANCELLED
                            && job.state != BulkJobStore.State.COMPLETE))
                throw new IOException("Cancel this download before removing it.");
            if (uncertainTelegram(job))
                throw new IOException(
                        "Check the existing Telegram receipt before removing this job.");
            store.remove(job);
            jobs.remove(id);
        }
    }

    private void halt(String id, boolean cancel) throws IOException {
        Run run;
        boolean owned = false;
        BulkJobStore.Job job;
        synchronized (lock) {
            job = requireJob(id);
            if (job.state == BulkJobStore.State.COMPLETE && !cancel) return;
            job.state =
                    cancel && !uncertainTelegram(job)
                            ? BulkJobStore.State.CANCELLED
                            : BulkJobStore.State.PAUSED;
            store.save(job);
            run = runs.get(id);
            if (run != null) {
                run.stop();
                owned = run.owned();
                if (!owned) {
                    if (run.future != null) run.future.cancel(false);
                    runs.remove(id);
                }
            }
        }
        if (run != null && owned && run.future != null) {
            try {
                run.future.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Waiting for the download writer was interrupted.", error);
            } catch (Exception error) {
                throw new IOException("Download writer did not close cleanly.", error);
            }
        }
        if (cancel) {
            synchronized (lock) {
                if (uncertainTelegram(job)) {
                    job.state = BulkJobStore.State.PAUSED;
                    job.message =
                            "Telegram may still finish. Resume to check the existing receipt.";
                    store.save(job);
                    return;
                }
                delete(store.part(job));
                delete(store.manifest(job));
                // Completed user-selected SAF documents are retained; incomplete private bytes are
                // removed.
                delete(store.complete(job));
                job.verifiedChunks = 0;
                store.save(job);
            }
        }
    }

    private static boolean uncertainTelegram(BulkJobStore.Job job) {
        return job.needsTelegram()
                && ("uploading".equals(job.telegramStatus)
                        || "receipt-check-required".equals(job.telegramStatus));
    }

    private void submit(BulkJobStore.Job job) throws IOException {
        Run run = new Run();
        runs.put(job.id, run);
        try {
            run.future = writer.submit(() -> execute(job, run));
        } catch (RejectedExecutionException error) {
            runs.remove(job.id);
            throw new IOException("Downloads have stopped.", error);
        }
    }

    private void execute(BulkJobStore.Job job, Run run) {
        if (!run.claim()) return;
        List<BulkTransfer.Route> routes = new ArrayList<>();
        try {
            run.check();
            synchronized (lock) {
                run.check();
                job.state = BulkJobStore.State.RUNNING;
                store.save(job);
            }
            if (!job.localOnly && !job.offer.direct.isEmpty())
                routes.add(streamRoute("Direct", job.offer, run, 0));
            if (!job.offer.i2p.isEmpty()) routes.add(streamRoute("I2P", job.offer, run, 1));
            if (!job.offer.tor.isEmpty()) routes.add(streamRoute("Tor", job.offer, run, 2));
            if (!job.offer.ipfsPeer.isEmpty()) routes.add(ipfsRoute(job.offer, run));
            File verified;
            if (job.localOnly) {
                BulkManifest manifest =
                        BulkManifest.parse(
                                BulkJobStore.read(store.manifest(job), BulkManifest.MAX_MANIFEST),
                                job.offer.manifestSha256);
                verified = store.complete(job);
                BulkTransfer.verifyFile(verified, manifest, run);
            } else verified = new BulkTransfer(store, () -> {}).download(job, routes, run);
            synchronized (lock) {
                run.check();
                job.state = BulkJobStore.State.VERIFIED;
                store.save(job);
            }
            run.check();
            if (job.formatPolicy.isEmpty())
                throw new IOException(
                        "Reselect this download; its format choices were not recorded.");
            AudioFormatPreferences.Format actualFormat =
                    AudioFormatPreferences.validateSelectedFile(
                            AudioFormatPreferences.decode(job.formatPolicy),
                            verified,
                            job.offer.name);
            String lowerName = job.offer.name.toLowerCase(java.util.Locale.ROOT);
            String mime =
                    actualFormat == AudioFormatPreferences.Format.AAC
                                    && (lowerName.endsWith(".m4a") || lowerName.endsWith(".mp4"))
                            ? "audio/mp4"
                            : actualFormat.mime;
            BulkPublish.run(
                    job,
                    run,
                    () -> publish(job, verified, run, mime),
                    () -> uploadTelegram(job, verified, run),
                    () -> store.save(job));
            synchronized (lock) {
                run.check();
                job.state = BulkJobStore.State.COMPLETE;
                job.message = "Download verified and saved.";
                store.save(job);
            }
            if (!job.needsPhone() || !job.destination.isEmpty()) delete(verified);
        } catch (Exception | LinkageError error) {
            synchronized (lock) {
                if (!run.cancelled) {
                    job.state = BulkJobStore.State.FAILED;
                    job.message =
                            error instanceof IOException
                                    ? error.getMessage()
                                    : "Download runtime failed. Resume to retry.";
                    try {
                        store.save(job);
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            for (BulkTransfer.Route route : routes)
                try {
                    route.close();
                } catch (IOException ignored) {
                }
            synchronized (lock) {
                if (runs.get(job.id) == run) runs.remove(job.id);
            }
        }
    }

    private BulkTransfer.Route streamRoute(String name, BulkOffer offer, Run run, int kind) {
        return new BulkTransfer.Route() {
            public String name() {
                return name;
            }

            public byte[] read(int chunk) throws IOException {
                run.check();
                if (kind == 0) {
                    DirectClient.Connection connection =
                            direct().connect(offer.direct, Math.min(TIMEOUT, 5000));
                    run.bind(connection);
                    try {
                        byte[] bytes =
                                BulkFileProtocol.request(
                                        connection.input,
                                        connection.output,
                                        offer.fileId,
                                        offer.token,
                                        chunk);
                        connection.finish();
                        return bytes;
                    } finally {
                        try {
                            connection.close();
                        } finally {
                            run.clear(connection);
                        }
                    }
                }
                OverlayClient.Connection connection =
                        overlay(kind).connect(kind == 1 ? offer.i2p : offer.tor, TIMEOUT);
                run.bind(connection);
                try {
                    byte[] bytes =
                            BulkFileProtocol.request(
                                    connection.input,
                                    connection.output,
                                    offer.fileId,
                                    offer.token,
                                    chunk);
                    connection.finish();
                    return bytes;
                } finally {
                    try {
                        connection.close();
                    } finally {
                        run.clear(connection);
                    }
                }
            }

            public void close() {
                Closeable active = run.active;
                if (active != null)
                    try {
                        active.close();
                    } catch (IOException ignored) {
                    }
            }
        };
    }

    private BulkTransfer.Route ipfsRoute(BulkOffer offer, Run run) {
        return new BulkTransfer.Route() {
            boolean connected;
            BulkManifest manifest;

            public String name() {
                return "IPFS";
            }

            public byte[] read(int chunk) throws IOException {
                run.check();
                peerInfo();
                if (ipfs == 0) throw new IOException("IPFS runtime is unavailable.");
                Closeable cancellation =
                        () -> {
                            if (ipfs != 0) IpfsNative.cancel(ipfs);
                        };
                run.bind(cancellation);
                try {
                    synchronized (nativeLock) {
                        if (!connected) {
                            synchronized (lock) {
                                downloadPeers.add(offer.ipfsPeer);
                                updatePeers();
                            }
                            IpfsNative.connect(ipfs, offer.ipfsPeer, offer.ipfsAddresses, TIMEOUT);
                            connected = true;
                        }
                        if (manifest == null) {
                            byte[] bytes =
                                    IpfsNative.fetchBlock(
                                            ipfs, offer.ipfsPeer, offer.manifestCid, TIMEOUT);
                            manifest = BulkManifest.parse(bytes, offer.manifestSha256);
                            if (chunk == -1) return bytes;
                        }
                        return IpfsNative.fetchBlock(
                                ipfs,
                                offer.ipfsPeer,
                                chunk == -1 ? offer.manifestCid : manifest.blocks.get(chunk).cid,
                                TIMEOUT);
                    }
                } finally {
                    run.clear(cancellation);
                }
            }

            public void close() throws IOException {
                if (run.cancelled && ipfs != 0) IpfsNative.cancel(ipfs);
                synchronized (lock) {
                    downloadPeers.remove(offer.ipfsPeer);
                    updatePeers();
                }
            }
        };
    }

    private void publish(BulkJobStore.Job job, File file, Run run, String mime) throws IOException {
        run.check();
        if (job.destination.isEmpty()) {
            synchronized (lock) {
                run.check();
                job.publishedUri = Uri.fromFile(file).toString();
                store.save(job);
            }
            return;
        }
        if ("creating".equals(job.phoneStatus) && job.publishedUri.isEmpty())
            throw new IOException(
                    "A destination document may already exist. Inspect the destination before"
                            + " retrying the phone copy.");
        if (!job.publishedUri.isEmpty()) {
            if (verifyDocument(job, run)) return;
            run.check();
            if (!DocumentsContract.deleteDocument(
                    context.getContentResolver(), Uri.parse(job.publishedUri)))
                throw new IOException(
                        "Remove the interrupted destination document before retrying.");
            job.publishedUri = "";
            job.phoneStatus = "pending";
            store.save(job);
        }
        Uri tree = Uri.parse(job.destination);
        Uri parent =
                DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
        synchronized (lock) {
            run.check();
            job.state = BulkJobStore.State.PUBLISHING;
            job.phoneStatus = "creating";
            store.save(job);
        }
        Uri document =
                DocumentsContract.createDocument(
                        context.getContentResolver(), parent, mime, job.offer.name);
        if (document == null) throw new IOException("Cannot create the destination audio file.");
        synchronized (lock) {
            job.publishedUri = document.toString();
            job.phoneStatus = "writing";
            store.save(job);
        }
        try {
            CancellationSignal opening = new CancellationSignal();
            Closeable cancelOpen = opening::cancel;
            run.bind(cancelOpen);
            OutputStream output = null;
            try (InputStream input = new FileInputStream(file)) {
                ParcelFileDescriptor descriptor =
                        context.getContentResolver().openFileDescriptor(document, "wt", opening);
                if (descriptor == null)
                    throw new IOException("Cannot open the destination audio file.");
                output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
                run.bind(output);
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    run.check();
                    output.write(buffer, 0, count);
                }
                run.check();
                output.flush();
            } finally {
                if (output != null) {
                    try {
                        output.close();
                    } finally {
                        run.clear(output);
                    }
                }
                run.clear(cancelOpen);
            }
            // A document provider may transform/truncate writes. Verify its readable bytes before
            // success.
            if (!verifyDocument(job, run))
                throw new IOException("Saved audio file failed verification.");
        } catch (IOException error) {
            try {
                if (DocumentsContract.deleteDocument(context.getContentResolver(), document)) {
                    job.publishedUri = "";
                    job.phoneStatus = "pending";
                }
            } catch (Exception ignored) {
            }
            store.save(job);
            throw error;
        }
    }

    private boolean verifyDocument(BulkJobStore.Job job, Run run) throws IOException {
        MessageDigest digest = BulkManifest.digest();
        long size = 0;
        CancellationSignal opening = new CancellationSignal();
        Closeable cancelOpen = opening::cancel;
        if (run != null) run.bind(cancelOpen);
        InputStream input = null;
        try {
            ParcelFileDescriptor descriptor =
                    context.getContentResolver()
                            .openFileDescriptor(Uri.parse(job.publishedUri), "r", opening);
            if (descriptor == null) return false;
            input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            if (run != null) run.bind(input);
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (run != null) run.check();
                size += count;
                if (size > job.offer.size) return false;
                digest.update(bytes, 0, count);
            }
        } finally {
            if (input != null) {
                try {
                    input.close();
                } finally {
                    if (run != null) run.clear(input);
                }
            }
            if (run != null) run.clear(cancelOpen);
        }
        return size == job.offer.size && BulkManifest.hex(digest.digest()).equals(job.offer.sha256);
    }

    private void uploadTelegram(BulkJobStore.Job job, File verified, Run run) throws IOException {
        run.check();
        final Class<?> bridge;
        try {
            bridge = Class.forName("app.bitchord.telegram.DownloadUploadBridge");
        } catch (ClassNotFoundException missing) {
            throw new IOException("Telegram download support is not installed.", missing);
        }
        Closeable cancelUpload =
                () -> {
                    try {
                        bridge.getMethod("cancel", String.class).invoke(null, job.id);
                    } catch (Exception error) {
                        throw new IOException(
                                "Telegram receipt check could not be interrupted.", error);
                    }
                };
        job.telegramStatus = "uploading";
        store.save(job);
        run.bind(cancelUpload);
        try {
            String receipt =
                    (String)
                            bridge.getMethod(
                                            "upload",
                                            Context.class,
                                            File.class,
                                            String.class,
                                            long.class,
                                            long.class,
                                            String.class)
                                    .invoke(
                                            null,
                                            context,
                                            verified,
                                            job.offer.name,
                                            job.telegramChatId,
                                            job.telegramUserId,
                                            job.id);
            JSONObject result = new JSONObject(receipt);
            if (!result.optBoolean("confirmed", false)
                    || result.getLong("userId") != job.telegramUserId
                    || result.getLong("chatId") != job.telegramChatId
                    || result.getLong("bytes") != job.offer.size
                    || !result.getString("sha256").equals(job.offer.sha256)
                    || !result.getString("name").equals(job.offer.name)
                    || result.getLong("messageId") == 0)
                throw new IOException("Telegram did not confirm the pinned audio file.");
            job.telegramReceipt = receipt;
            job.telegramStatus = "complete";
            store.save(job);
        } catch (Exception error) {
            job.telegramStatus = "receipt-check-required";
            store.save(job);
            Throwable cause =
                    error instanceof java.lang.reflect.InvocationTargetException
                            ? ((java.lang.reflect.InvocationTargetException) error).getCause()
                            : error;
            throw new IOException(
                    "Telegram receipt check required before retry: "
                            + (cause == null || cause.getMessage() == null
                                    ? "upload status is uncertain."
                                    : cause.getMessage()),
                    cause);
        } finally {
            run.clear(cancelUpload);
        }
    }

    private synchronized DirectClient direct() throws IOException {
        DirectClient client;
        synchronized (lock) {
            ensureOpen();
            if (direct == null) direct = new DirectClient(context);
            client = direct;
        }
        client.start(TIMEOUT);
        synchronized (lock) {
            ensureOpen();
        }
        return client;
    }

    private synchronized OverlayClient overlay(int kind) throws IOException {
        OverlayClient client;
        synchronized (lock) {
            ensureOpen();
            client = kind == 1 ? i2p : tor;
            if (client == null) {
                client =
                        new OverlayClient(
                                context,
                                kind == 1 ? OverlayClient.Kind.I2P : OverlayClient.Kind.TOR);
                if (kind == 1) i2p = client;
                else tor = client;
            }
        }
        client.start(120_000);
        synchronized (lock) {
            ensureOpen();
        }
        return client;
    }

    private void startProviders(Share share) {
        share.directEndpoint = selfDirectDescriptor();
        try {
            share.i2pEndpoint = overlay(1).endpoint(120_000);
        } catch (Exception | LinkageError error) {
            runtimeStatus = "I2P is unavailable; other routes remain available.";
        }
        try {
            share.torEndpoint = overlay(2).endpoint(120_000);
        } catch (Exception | LinkageError error) {
            runtimeStatus = "Tor is unavailable; other routes remain available.";
        }
        synchronized (lock) {
            if (stopped) return;
            if (direct != null && !directAccepting) {
                directAccepting = true;
                acceptDirect(direct);
            }
            if (i2p != null && !i2pAccepting) {
                i2pAccepting = true;
                acceptOverlay(i2p);
            }
            if (tor != null && !torAccepting) {
                torAccepting = true;
                acceptOverlay(tor);
            }
        }
    }

    private void acceptDirect(DirectClient client) {
        Thread thread =
                new Thread(
                        () -> {
                            while (providerActive())
                                try {
                                    DirectClient.Connection connection = client.accept(1000);
                                    if (connection != null)
                                        serve(
                                                connection,
                                                connection.input,
                                                connection.output,
                                                connection::finish);
                                } catch (IOException error) {
                                    if (providerActive())
                                        runtimeStatus = "Direct provider connection failed.";
                                    return;
                                }
                        },
                        "bitchord-direct-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptOverlay(OverlayClient client) {
        Thread thread =
                new Thread(
                        () -> {
                            while (providerActive())
                                try {
                                    OverlayClient.Connection connection = client.accept(1000);
                                    if (connection != null)
                                        serve(
                                                connection,
                                                connection.input,
                                                connection.output,
                                                connection::finish);
                                } catch (IOException error) {
                                    if (providerActive())
                                        runtimeStatus = "Overlay provider connection failed.";
                                    return;
                                }
                        },
                        "bitchord-overlay-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean providerActive() {
        synchronized (lock) {
            return !stopped;
        }
    }

    private void serve(
            Closeable connection, InputStream input, OutputStream output, Closeable finish)
            throws IOException {
        synchronized (lock) {
            if (serving.size() >= 2 || stopped) {
                connection.close();
                return;
            }
            serving.add(connection);
        }
        try {
            servers.execute(
                    () -> {
                        try {
                            BulkFileProtocol.serve(input, output, this::readSource);
                            finish.close();
                        } catch (IOException ignored) {
                        } finally {
                            try {
                                connection.close();
                            } catch (IOException ignored) {
                            }
                            synchronized (lock) {
                                serving.remove(connection);
                            }
                        }
                    });
        } catch (RejectedExecutionException error) {
            synchronized (lock) {
                serving.remove(connection);
            }
            connection.close();
        }
    }

    private byte[] readSource(String fileId, String token, int chunk) throws IOException {
        synchronized (lock) {
            Share share = requireShare(fileId);
            if (share.expires < System.currentTimeMillis()
                    || share.peerGrants.isEmpty()
                    || !MessageDigest.isEqual(
                            share.token.getBytes(StandardCharsets.US_ASCII),
                            token.getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("File is unavailable.");
            if (chunk == -1) return share.manifest.bytes.clone();
            if (chunk >= share.manifest.blocks.size()) throw new IOException("Invalid block.");
            BulkManifest.Block block = share.manifest.blocks.get(chunk);
            byte[] bytes = new byte[block.size];
            try (RandomAccessFile source = new RandomAccessFile(share.file, "r")) {
                source.seek((long) chunk * BulkManifest.BLOCK_SIZE);
                source.readFully(bytes);
            }
            block.verify(bytes);
            return bytes;
        }
    }

    public void revokeShare(String fileId) throws IOException {
        synchronized (lock) {
            Share share = shares.get(fileId);
            if (share == null) return;
            share.revoked = true;
            share.recipients.clear();
            share.peerGrants.clear();
            updatePeers();
            updatePublicationPeers(share);
            if (ipfs != 0 && share.nativePublished) {
                IpfsNative.unpublish(ipfs, share.manifest.cid);
                share.nativePublished = false;
            }
            delete(share.file);
            shares.remove(fileId);
            updatePeers();
        }
    }

    private void updatePeers() throws IOException {
        if (ipfs == 0) return;
        Set<String> all = new HashSet<>(downloadPeers);
        for (Share share : shares.values())
            for (String peer : share.peerGrants.values()) if (!peer.isEmpty()) all.add(peer);
        IpfsNative.setPeers(ipfs, new JSONArray(all).toString());
    }

    private void updatePublicationPeers(Share share) throws IOException {
        if (ipfs == 0 || !share.nativePublished) return;
        Set<String> allowed = new HashSet<>();
        for (String peer : share.peerGrants.values()) if (!peer.isEmpty()) allowed.add(peer);
        IpfsNative.grantPublication(ipfs, share.manifest.cid, new JSONArray(allowed).toString());
    }

    public void stop() throws IOException {
        synchronized (BulkDownloads.class) {
            try {
                stopLocked();
            } catch (Exception | LinkageError error) {
                shutdownFailed = true;
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException(
                        "A download runtime did not stop. Restart BitChord before starting again.",
                        error);
            }
        }
    }

    private void stopLocked() throws IOException {
        List<String> ids;
        List<Closeable> stages;
        synchronized (lock) {
            stopped = true;
            ids = new ArrayList<>(runs.keySet());
            stages = new ArrayList<>(stagingResources);
            for (Run run : runs.values()) run.stop();
        }
        IOException failure = null;
        for (Closeable resource : stages) failure = closeResource(resource, failure);
        for (String id : ids) {
            try {
                pause(id);
            } catch (IOException error) {
                failure = combine(failure, error);
            }
        }
        List<Closeable> connections;
        List<Share> publications;
        synchronized (lock) {
            connections = new ArrayList<>(serving);
            publications = new ArrayList<>(shares.values());
            shares.clear();
        }
        for (Closeable connection : connections) failure = closeResource(connection, failure);
        failure = closeResource(direct, failure);
        failure = closeResource(i2p, failure);
        failure = closeResource(tor, failure);
        synchronized (nativeLock) {
            if (ipfs != 0) {
                long closing = ipfs;
                failure = closeResource(() -> IpfsNative.close(closing), failure);
                ipfs = 0;
            }
        }
        for (Share share : publications) {
            try {
                delete(share.file);
            } catch (IOException error) {
                failure = combine(failure, error);
            }
        }
        writer.shutdown();
        servers.shutdown();
        if (failure != null) throw failure;
    }

    private static IOException closeResource(Closeable resource, IOException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
            return failure;
        } catch (Exception | LinkageError error) {
            return combine(failure, new IOException("A download resource could not stop.", error));
        }
    }

    private static IOException combine(IOException previous, IOException next) {
        if (previous == null) return next;
        previous.addSuppressed(next);
        return previous;
    }

    private long usedBytes() {
        long used = stagingBytes;
        for (Share share : shares.values()) used += share.size;
        for (BulkJobStore.Job job : jobs.values())
            if (job.state == BulkJobStore.State.COMPLETE) used += store.complete(job).length();
            else if (job.state != BulkJobStore.State.CANCELLED) used += job.offer.size;
        return used;
    }

    private void ensureOpen() throws IOException {
        if (stopped)
            throw new IOException("Downloads are stopped. Restart BitChord to start again.");
    }

    private Share requireShare(String id) throws IOException {
        Share share = shares.get(id);
        if (share == null || share.revoked)
            throw new IOException("Selected file is no longer shared.");
        return share;
    }

    private BulkJobStore.Job requireJob(String id) throws IOException {
        BulkJobStore.Job job = jobs.get(id);
        if (job == null) throw new IOException("Unknown download.");
        return job;
    }

    private static void delete(File file) throws IOException {
        if (file.exists() && !file.delete())
            throw new IOException("Cannot remove private download data.");
    }

    private static String random(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return BulkManifest.hex(bytes);
    }

    private static String repeatZeroKey() {
        return "0000000000000000000000000000000000000000000000000000000000000000";
    }

    private static BulkManifest localManifest(File file) throws IOException {
        if (file.length() <= 0 || file.length() > BulkManifest.MAX_SIZE)
            throw new IOException("Selected file size must be between 1 byte and 256 MiB.");
        StringBuilder blocks = new StringBuilder();
        MessageDigest full = BulkManifest.digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[BulkManifest.BLOCK_SIZE];
            int count;
            while ((count = fill(input, bytes)) > 0) {
                byte[] chunk =
                        count == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, count);
                full.update(chunk);
                String sha = BulkManifest.hash(chunk);
                if (blocks.length() > 0) blocks.append(',');
                blocks.append("{\"cid\":\"")
                        .append(BulkManifest.cidForHash(sha))
                        .append("\",\"size\":")
                        .append(count)
                        .append(",\"sha256\":\"")
                        .append(sha)
                        .append("\"}");
            }
        }
        String json =
                "{\"version\":1,\"blockSize\":262144,\"size\":"
                        + file.length()
                        + ",\"sha256\":\""
                        + BulkManifest.hex(full.digest())
                        + "\",\"blocks\":["
                        + blocks
                        + "]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return BulkManifest.parse(bytes, BulkManifest.hash(bytes));
    }

    private static int fill(InputStream input, byte[] bytes) throws IOException {
        int offset = 0, count;
        while (offset < bytes.length
                && (count = input.read(bytes, offset, bytes.length - offset)) != -1)
            if (count > 0) offset += count;
        return offset;
    }
}
