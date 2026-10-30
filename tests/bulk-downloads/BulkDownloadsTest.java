package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BulkDownloadsTest {
    static int assertions;
    static final String PEER = String.join("", Collections.nCopies(64, "a"));
    static final String TOKEN = String.join("", Collections.nCopies(64, "b"));
    static final String ID = String.join("", Collections.nCopies(32, "c"));
    static final byte[] DATA = new byte[BulkManifest.BLOCK_SIZE * 2 + 71];
    static BulkManifest manifest;
    static BulkOffer offer;

    static void check(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }

    static void fails(Action action, String reason) throws Exception {
        boolean failed = false;
        try {
            action.run();
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, reason);
    }

    interface Action {
        void run() throws Exception;
    }

    static byte[] chunk(int index) {
        return index == -1
                ? manifest.bytes.clone()
                : Arrays.copyOfRange(
                        DATA,
                        index * BulkManifest.BLOCK_SIZE,
                        Math.min(DATA.length, (index + 1) * BulkManifest.BLOCK_SIZE));
    }

    static class Route implements BulkTransfer.Route {
        final String label;
        int corrupt = -2, failAt = -2, reads, closes;

        Route(String name) {
            label = name;
        }

        public String name() {
            return label;
        }

        public byte[] read(int index) throws IOException {
            reads++;
            if (index == failAt) throw new IOException("route disconnected");
            byte[] bytes = chunk(index);
            if (index == corrupt) bytes[0] ^= 7;
            return bytes;
        }

        public void close() {
            closes++;
        }
    }

    static BulkJobStore store() throws IOException {
        return new BulkJobStore(Files.createTempDirectory("bulk-test-").toFile());
    }

    static BulkJobStore.Job job(BulkJobStore store) throws IOException {
        return store.create(offer, "");
    }

    static void setup() throws Exception {
        for (int i = 0; i < DATA.length; i++) DATA[i] = (byte) (i * 17 + i / 256);
        JSONArray blocks = new JSONArray();
        for (int offset = 0; offset < DATA.length; offset += BulkManifest.BLOCK_SIZE) {
            byte[] bytes =
                    Arrays.copyOfRange(
                            DATA, offset, Math.min(DATA.length, offset + BulkManifest.BLOCK_SIZE));
            String sha = BulkManifest.hash(bytes);
            blocks.put(
                    new JSONObject()
                            .put("cid", BulkManifest.cidForHash(sha))
                            .put("size", bytes.length)
                            .put("sha256", sha));
        }
        byte[] bytes =
                new JSONObject()
                        .put("version", 1)
                        .put("blockSize", BulkManifest.BLOCK_SIZE)
                        .put("size", DATA.length)
                        .put("sha256", BulkManifest.hash(DATA))
                        .put("blocks", blocks)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
        manifest = BulkManifest.parse(bytes, BulkManifest.hash(bytes));
        JSONObject json =
                new JSONObject()
                        .put("protocol", "app.bitchord.bulk/1")
                        .put("fileId", ID)
                        .put("token", TOKEN)
                        .put("name", "selected.flac")
                        .put("size", DATA.length)
                        .put("sha256", BulkManifest.hash(DATA))
                        .put("manifestSha256", manifest.sha256)
                        .put("manifestCid", manifest.cid)
                        .put("expires", System.currentTimeMillis() + 120000)
                        .put("routes", new JSONObject().put("direct", "test"));
        offer = BulkOffer.verified(PEER, json.toString());
    }

    public static void main(String[] args) throws Exception {
        setup();
        check(manifest.cid.equals("b" + manifest.cid.substring(1)), "raw CID v1");
        fails(
                () -> BulkManifest.parse(manifest.bytes, TOKEN),
                "reject unsigned manifest substitution");
        JSONObject bad = new JSONObject(new String(manifest.bytes, StandardCharsets.UTF_8));
        bad.getJSONArray("blocks").getJSONObject(0).put("cid", manifest.cid);
        byte[] badBytes = bad.toString().getBytes(StandardCharsets.UTF_8);
        fails(
                () -> BulkManifest.parse(badBytes, BulkManifest.hash(badBytes)),
                "reject CID/hash disagreement");
        protocol();
        transfers();
        journal();
        destinations();
        System.out.println("Bulk downloads: " + assertions + " assertions passed");
    }

    static void protocol() throws Exception {
        try (ServerSocket server =
                new ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())) {
            Thread provider =
                    new Thread(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    BulkFileProtocol.serve(
                                            socket.getInputStream(),
                                            socket.getOutputStream(),
                                            (id, token, index) -> {
                                                if (!ID.equals(id) || !TOKEN.equals(token))
                                                    throw new IOException("denied");
                                                return chunk(index);
                                            });
                                } catch (IOException error) {
                                    throw new RuntimeException(error);
                                }
                            });
            provider.start();
            try (Socket socket =
                    new Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                InputStream tiny =
                        new FilterInputStream(socket.getInputStream()) {
                            public int read(byte[] b, int off, int len) throws IOException {
                                return super.read(b, off, Math.min(3, len));
                            }
                        };
                check(
                        Arrays.equals(
                                chunk(1),
                                BulkFileProtocol.request(
                                        tiny, socket.getOutputStream(), ID, TOKEN, 1)),
                        "loopback short reads preserve block");
            }
            provider.join(5000);
            check(!provider.isAlive(), "provider closes after one request");
        }
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        fails(
                () ->
                        BulkFileProtocol.request(
                                new ByteArrayInputStream(new byte[0]), request, ID, TOKEN, -1),
                "short response rejected");
        ByteArrayOutputStream denied = new ByteArrayOutputStream();
        fails(
                () ->
                        BulkFileProtocol.serve(
                                new ByteArrayInputStream(request.toByteArray()),
                                denied,
                                (id, token, index) -> {
                                    throw new IOException("denied");
                                }),
                "provider rejects bad token");
        fails(
                () ->
                        BulkFileProtocol.request(
                                new ByteArrayInputStream(denied.toByteArray()),
                                new ByteArrayOutputStream(),
                                ID,
                                TOKEN,
                                -1),
                "denial is not bytes");
        ByteArrayOutputStream good = new ByteArrayOutputStream();
        java.io.DataOutputStream fixture = new java.io.DataOutputStream(good);
        byte[] header =
                new JSONObject()
                        .put("ok", true)
                        .put("size", manifest.bytes.length)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
        fixture.writeInt(0x42434631);
        fixture.writeInt(header.length);
        fixture.write(header);
        fixture.write(manifest.bytes);
        byte[] truncated = Arrays.copyOf(good.toByteArray(), good.size() - 1);
        fails(
                () ->
                        BulkFileProtocol.request(
                                new ByteArrayInputStream(truncated),
                                new ByteArrayOutputStream(),
                                ID,
                                TOKEN,
                                -1),
                "truncated payload rejected");
        fails(
                () ->
                        BulkFileProtocol.request(
                                new ByteArrayInputStream(good.toByteArray()),
                                new ByteArrayOutputStream(),
                                ID,
                                TOKEN,
                                -1),
                "missing completion receipt rejected");
    }

    static void transfers() throws Exception {
        BulkJobStore store = store();
        BulkJobStore.Job job = job(store);
        Route direct = new Route("Direct");
        direct.failAt = 1;
        Route i2p = new Route("I2P");
        i2p.corrupt = 1;
        Route tor = new Route("Tor");
        tor.failAt = 2;
        Route ipfs = new Route("IPFS");
        File file =
                new BulkTransfer(store, () -> {})
                        .download(job, Arrays.asList(direct, i2p, tor, ipfs), () -> {});
        check(
                Arrays.equals(DATA, Files.readAllBytes(file.toPath())),
                "route changes never mix unverified bytes");
        check(
                direct.reads == 3 && i2p.reads == 1 && tor.reads == 2 && ipfs.reads == 1,
                "fallback starts at last verified block");
        check(
                direct.closes > 0 && i2p.closes > 0 && tor.closes > 0 && ipfs.closes > 0,
                "all attempted routes closed");
        check(!store.part(job).exists(), "writer closed and atomically renamed");

        BulkJobStore cancelStore = store();
        BulkJobStore.Job cancelled = job(cancelStore);
        AtomicBoolean stop = new AtomicBoolean();
        BulkTransfer.Cancellation cancellation =
                () -> {
                    if (stop.get()) throw new IOException("cancelled");
                };
        Route route = new Route("Direct");
        fails(
                () ->
                        new BulkTransfer(
                                        cancelStore,
                                        () -> {
                                            if (cancelled.verifiedChunks == 1) stop.set(true);
                                        })
                                .download(
                                        cancelled, Collections.singletonList(route), cancellation),
                "pause interrupts between blocks");
        check(
                cancelled.verifiedChunks == 1
                        && cancelStore.part(cancelled).length() == BulkManifest.BLOCK_SIZE,
                "pause keeps verified prefix only");
        check(
                route.closes > 0 && !cancelStore.complete(cancelled).exists(),
                "paused writer closed before publish");
        stop.set(false);
        Route resumed = new Route("Tor");
        File resumedFile =
                new BulkTransfer(cancelStore, () -> {})
                        .download(cancelled, Collections.singletonList(resumed), cancellation);
        check(
                resumed.reads == 2 && Arrays.equals(DATA, Files.readAllBytes(resumedFile.toPath())),
                "resume uses immutable manifest checkpoint");

        BulkJobStore tamperStore = store();
        BulkJobStore.Job tampered = job(tamperStore);
        BulkJobStore.atomic(tamperStore.manifest(tampered), manifest.bytes);
        try (RandomAccessFile partial = new RandomAccessFile(tamperStore.part(tampered), "rw")) {
            partial.write(DATA, 0, BulkManifest.BLOCK_SIZE * 2);
            partial.seek(1);
            partial.write(9);
        }
        tampered.verifiedChunks = 2;
        tamperStore.save(tampered);
        Route repair = new Route("I2P");
        File repaired =
                new BulkTransfer(tamperStore, () -> {})
                        .download(tampered, Collections.singletonList(repair), () -> {});
        check(
                repair.reads == 3 && Arrays.equals(DATA, Files.readAllBytes(repaired.toPath())),
                "restart rehash detects tampered prefix");
        BulkJobStore unavailableStore = store();
        BulkJobStore.Job unavailableJob = job(unavailableStore);
        BulkTransfer.Route missingRuntime =
                new BulkTransfer.Route() {
                    public String name() {
                        return "Direct";
                    }

                    public byte[] read(int chunk) {
                        throw new UnsatisfiedLinkError("fixture runtime unavailable");
                    }

                    public void close() {}
                };
        File fallback =
                new BulkTransfer(unavailableStore, () -> {})
                        .download(
                                unavailableJob,
                                Arrays.asList(missingRuntime, new Route("I2P")),
                                () -> {});
        check(
                Arrays.equals(DATA, Files.readAllBytes(fallback.toPath())),
                "native load failure permits fallback");

        JSONObject wrongManifest =
                new JSONObject(new String(manifest.bytes, StandardCharsets.UTF_8))
                        .put("sha256", TOKEN);
        byte[] wrongBytes = wrongManifest.toString().getBytes(StandardCharsets.UTF_8);
        String wrongHash = BulkManifest.hash(wrongBytes);
        JSONObject wrongOffer =
                new JSONObject(offer.json)
                        .put("sha256", TOKEN)
                        .put("manifestSha256", wrongHash)
                        .put("manifestCid", BulkManifest.cidForHash(wrongHash));
        BulkJobStore finalStore = store();
        BulkJobStore.Job finalJob =
                finalStore.create(BulkOffer.verified(PEER, wrongOffer.toString()), "");
        Route dishonest =
                new Route("Direct") {
                    public byte[] read(int index) {
                        return index == -1 ? wrongBytes : chunk(index);
                    }
                };
        fails(
                () ->
                        new BulkTransfer(finalStore, () -> {})
                                .download(finalJob, Collections.singletonList(dishonest), () -> {}),
                "full file SHA required after all valid chunks");
        check(!finalStore.complete(finalJob).exists(), "wrong full-file SHA never published");
    }

    static void destinations() throws Exception {
        BulkJobStore store = store();
        BulkJobStore.Job job =
                store.create(
                        offer, "content://chosen/tree", "both", -100123, 42, "captured-policy");
        int[] calls = new int[3];
        fails(
                () ->
                        BulkPublish.run(
                                job,
                                () -> {},
                                () -> {
                                    calls[0]++;
                                    throw new IOException("folder revoked");
                                },
                                () -> {
                                    calls[1]++;
                                    job.telegramReceipt = "confirmed-fixture";
                                },
                                () -> {
                                    calls[2]++;
                                    store.save(job);
                                }),
                "Both reports partial publication failure");
        check(
                calls[0] == 1 && calls[1] == 1 && calls[2] == 2,
                "Both attempts Telegram after phone failure");
        check(
                job.phoneStatus.equals("failed") && job.telegramStatus.equals("complete"),
                "independent destination status");
        BulkJobStore.Job restored = new BulkJobStore(store.home).load().get(0);
        check(
                restored.telegramStatus.equals("complete")
                        && restored.formatPolicy.equals("captured-policy"),
                "destination success and format snapshot durable");
        BulkPublish.run(
                restored,
                () -> {},
                () -> {
                    calls[0]++;
                },
                () -> {
                    calls[1]++;
                },
                () -> store.save(restored));
        check(
                calls[0] == 2 && calls[1] == 1,
                "retry completes phone without duplicate Telegram upload");
        BulkJobStore.Job reverse =
                store.create(offer, "content://chosen/tree", "both", -100123, 42);
        fails(
                () ->
                        BulkPublish.run(
                                reverse,
                                () -> {},
                                () -> {},
                                () -> {
                                    throw new IOException("offline");
                                },
                                () -> store.save(reverse)),
                "Telegram failure retains phone success");
        check(
                reverse.phoneStatus.equals("complete") && reverse.telegramStatus.equals("failed"),
                "phone receipt survives failed Telegram");
        int[] phoneRetries = new int[1];
        BulkPublish.run(
                reverse,
                () -> {},
                () -> {
                    phoneRetries[0]++;
                },
                () -> {},
                () -> store.save(reverse));
        check(phoneRetries[0] == 0, "Telegram retry skips completed phone copy");
        BulkJobStore.Job only = store.create(offer, "", "telegram", -100123, 42);
        BulkPublish.run(
                only,
                () -> {},
                () -> {
                    throw new AssertionError("phone called");
                },
                () -> {},
                () -> store.save(only));
        check(
                only.phoneStatus.equals("skipped") && only.telegramStatus.equals("complete"),
                "Telegram only skips permanent phone publication");
        BulkJobStore.Job cancelled =
                store.create(offer, "content://chosen/tree", "both", -100123, 42);
        AtomicBoolean stop = new AtomicBoolean();
        int[] uploads = new int[1];
        fails(
                () ->
                        BulkPublish.run(
                                cancelled,
                                () -> {
                                    if (stop.get()) throw new IOException("paused");
                                },
                                () -> {
                                    stop.set(true);
                                    throw new IOException("interrupted");
                                },
                                () -> {
                                    uploads[0]++;
                                },
                                () -> store.save(cancelled)),
                "cancellation interrupts destination plan");
        check(uploads[0] == 0, "cancel never starts second destination");
        BulkJobStore.Job badJournal =
                store.create(offer, "content://chosen/tree", "both", -100123, 42);
        fails(
                () ->
                        BulkPublish.run(
                                badJournal,
                                () -> {},
                                () -> {},
                                () -> {
                                    uploads[0]++;
                                },
                                () -> {
                                    throw new IOException("disk full");
                                }),
                "checkpoint failure stops further publication");
        check(uploads[0] == 0, "no upload without durable phone receipt");
    }

    static void journal() throws Exception {
        BulkJobStore store = store();
        BulkJobStore.Job job = store.create(offer, "content://chosen/tree", "both", -100123, 42);
        job.state = BulkJobStore.State.RUNNING;
        job.verifiedChunks = 1;
        job.phoneStatus = "complete";
        job.publishedUri = "content://chosen/audio";
        job.telegramStatus = "pending";
        store.save(job);
        BulkJobStore.Job restored = new BulkJobStore(store.home).load().get(0);
        check(restored.state == BulkJobStore.State.PAUSED, "restart requires explicit resume");
        check(
                restored.verifiedChunks == 1 && restored.offer.sha256.equals(offer.sha256),
                "restart retains immutable identity");
        check(
                restored.telegramUserId == 42
                        && restored.telegramChatId == -100123
                        && restored.destination.equals("content://chosen/tree"),
                "destination and account pinned");
        check(
                restored.phoneStatus.equals("complete")
                        && restored.telegramStatus.equals("pending"),
                "partial destination success survives restart");
        job.state = BulkJobStore.State.PUBLISHING;
        store.save(job);
        check(
                new BulkJobStore(store.home).load().get(0).state == BulkJobStore.State.PAUSED,
                "ambiguous publication never automatically duplicates");
        File journal = new File(store.home, job.id + ".json");
        Files.write(journal.toPath(), new byte[] {1});
        fails(() -> new BulkJobStore(store.home).load(), "damaged journal fails closed");
    }
}
