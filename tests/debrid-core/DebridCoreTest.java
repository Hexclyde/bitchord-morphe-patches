package app.bitchord.soulseek;

import android.content.Context;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.net.ssl.*;

public final class DebridCoreTest {
    static int checks;
    static final String KEY = "fixture-key-01234567890123456789";
    static final String MAGNET = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789";
    static final DebridProvider.Kind KIND = DebridProvider.Kind.REAL_DEBRID;

    interface Checked {
        void run() throws Exception;
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static void rejected(Checked test, String message) throws Exception {
        try {
            test.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    static void until(Callable<Boolean> condition, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (condition.call()) return;
            Thread.sleep(5);
        }
        throw new AssertionError(message);
    }

    static final class Cancellation implements DebridModels.Cancellation {
        volatile boolean cancelled;
        final Set<Closeable> active = new HashSet<>();

        public void check() throws IOException {
            if (cancelled) throw new InterruptedIOException("Fixture cancelled");
        }

        public synchronized void bind(Closeable resource) throws IOException {
            check();
            active.add(resource);
        }

        public synchronized void clear(Closeable resource) {
            active.remove(resource);
        }
    }

    static final class FakeConnection extends HttpsURLConnection {
        final int code;
        final byte[] body;
        final Map<String, String> headers = new HashMap<>();
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean disconnected;

        FakeConnection(String url, int code, byte[] body) throws Exception {
            super(new URL(url));
            this.code = code;
            this.body = body;
        }

        public void disconnect() {
            disconnected = true;
        }

        public boolean usingProxy() {
            return false;
        }

        public void connect() {}

        public int getResponseCode() {
            return code;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }

        public OutputStream getOutputStream() {
            return sent;
        }

        public String getHeaderField(String key) {
            return headers.get(key);
        }

        public String getContentEncoding() {
            return headers.get("Content-Encoding");
        }

        public long getContentLengthLong() {
            return body.length;
        }

        public String getCipherSuite() {
            return "fixture";
        }

        public Certificate[] getLocalCertificates() {
            return null;
        }

        public Certificate[] getServerCertificates() {
            return null;
        }
    }

    static final class WireHttp extends DebridHttp {
        final Queue<FakeConnection> replies = new ArrayDeque<>();
        final List<FakeConnection> opened = new ArrayList<>();
        final List<String> urls = new ArrayList<>();

        protected HttpsURLConnection open(String url, DebridModels.Cancellation c)
                throws IOException {
            c.check();
            try {
                DebridTlsSockets.url(url);
            } catch (Exception invalid) {
                throw new IOException("Invalid fixture URL");
            }
            urls.add(url);
            FakeConnection next = replies.remove();
            opened.add(next);
            return next;
        }

        void add(String url, int status, String body) throws Exception {
            replies.add(new FakeConnection(url, status, body.getBytes("UTF-8")));
        }
    }

    static final class Provider implements DebridProvider {
        volatile boolean ready = true, wrongName, keyLeak, keyName, rawFailure, blockedControl;
        final AtomicInteger resolves = new AtomicInteger(),
                selects = new AtomicInteger(),
                downloads = new AtomicInteger();
        volatile List<String> selected = Collections.emptyList();
        CountDownLatch controlEntered = new CountDownLatch(1),
                controlRelease = new CountDownLatch(1);

        public Kind kind() {
            return KIND;
        }

        public void validate(String key, DebridHttp h, DebridModels.Cancellation c)
                throws IOException {
            c.check();
        }

        DebridModels.Resolved value(String source) throws IOException {
            return new DebridModels.Resolved(
                    KIND,
                    "remote-1",
                    source,
                    Arrays.asList(
                            new DebridModels.Entry("1", keyName ? KEY + ".flac" : "song.flac", 12),
                            new DebridModels.Entry("2", "other.flac", 12),
                            new DebridModels.Entry("3", "video.mkv", 12)),
                    ready ? "ready" : "preparing",
                    ready ? "Ready" : "Preparing",
                    ready);
        }

        public DebridModels.Resolved resolve(
                String input, String key, DebridHttp h, DebridModels.Cancellation c)
                throws IOException {
            resolves.incrementAndGet();
            if (blockedControl) {
                Closeable close = () -> controlRelease.countDown();
                c.bind(close);
                controlEntered.countDown();
                try {
                    while (!controlRelease.await(10, TimeUnit.MILLISECONDS)) c.check();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    c.clear(close);
                }
                c.check();
            }
            return value(input);
        }

        public DebridModels.Resolved refresh(
                DebridModels.Resolved old, String key, DebridHttp h, DebridModels.Cancellation c)
                throws IOException {
            c.check();
            return value(old.source);
        }

        public DebridModels.Resolved select(
                DebridModels.Resolved old,
                List<String> ids,
                String key,
                DebridHttp h,
                DebridModels.Cancellation c)
                throws IOException {
            c.check();
            selects.incrementAndGet();
            selected = new ArrayList<>(ids);
            return value(old.source);
        }

        public DebridModels.Download download(
                DebridModels.Resolved old,
                String id,
                String key,
                DebridHttp h,
                DebridModels.Cancellation c)
                throws IOException {
            downloads.incrementAndGet();
            if (rawFailure) throw new IOException(key);
            DebridModels.Entry entry = old.entry(id);
            return new DebridModels.Download(
                    "https://cdn.example/" + (keyLeak ? key : "file"),
                    wrongName ? "wrong.flac" : entry.name,
                    12);
        }
    }

    static final class CaptureHttp extends DebridHttp {
        volatile boolean blocked, badAudio;
        final AtomicInteger fetches = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final byte[] bytes = "fLaCfixture!".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        public long fetch(
                DebridModels.Download download,
                File target,
                DebridModels.Cancellation c,
                DebridModels.Progress p)
                throws IOException {
            fetches.incrementAndGet();
            Closeable close = () -> release.countDown();
            c.bind(close);
            entered.countDown();
            try {
                while (blocked && !release.await(10, TimeUnit.MILLISECONDS)) c.check();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                c.clear(close);
            }
            c.check();
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(badAudio ? "BAD!fixture!".getBytes("UTF-8") : bytes);
            }
            p.changed(bytes.length, 12);
            c.check();
            return bytes.length;
        }
    }

    static final class Fixture implements AutoCloseable {
        final File home;
        final DebridCredentials credentials = new DebridCredentials();
        final Provider provider = new Provider();
        final CaptureHttp http = new CaptureHttp();
        final DebridDownloads engine;
        final DownloadDestinations.Snapshot destination =
                new DownloadDestinations.Snapshot("both", "content://fixed/tree", 456, 123);

        Fixture() throws Exception {
            home = Files.createTempDirectory("debrid-controller-").toFile();
            credentials.enable(KIND, KEY);
            Map<DebridProvider.Kind, DebridProvider> map = new EnumMap<>(DebridProvider.Kind.class);
            map.put(KIND, provider);
            engine = new DebridDownloads(new Context(home), credentials, http, map);
        }

        DebridDownloads.Prepared prepare() throws IOException {
            return engine.prepare(KIND, MAGNET);
        }

        String enqueue(DebridDownloads.Prepared p, String... ids) throws IOException {
            return engine.enqueue(p.id, Arrays.asList(ids), destination, "all").get(0);
        }

        DebridDownloads.Job job(String id) {
            for (DebridDownloads.Job j : engine.jobs()) if (j.id.equals(id)) return j;
            throw new AssertionError("Missing job");
        }

        public void close() throws Exception {
            http.release.countDown();
            provider.controlRelease.countDown();
            engine.stop();
        }
    }

    static void httpTests() throws Exception {
        check(!DebridHttp.isPublic(InetAddress.getByName("127.0.0.1")), "loopback rejected");
        check(!DebridHttp.isPublic(InetAddress.getByName("10.4.1.8")), "private rejected");
        check(!DebridHttp.isPublic(InetAddress.getByName("100.64.1.1")), "CGNAT rejected");
        check(!DebridHttp.isPublic(InetAddress.getByName("169.254.1.1")), "link-local rejected");
        check(
                !DebridHttp.isPublic(InetAddress.getByName("2001:db8::1")),
                "documentation IPv6 rejected");
        check(!DebridHttp.isPublic(InetAddress.getByName("fc00::1")), "ULA rejected");
        check(DebridHttp.isPublic(InetAddress.getByName("8.8.8.8")), "public v4 admitted");
        check(
                DebridHttp.isPublic(InetAddress.getByName("2606:4700:4700::1111")),
                "public v6 admitted");
        rejected(
                () -> DebridTlsSockets.url("http://public.example/a.flac"),
                "cleartext URL rejected");
        rejected(
                () -> DebridTlsSockets.url("https://user:password@public.example/a"),
                "userinfo rejected");
        rejected(
                () -> DebridTlsSockets.url("https://public.example:444/a"),
                "alternate port rejected");
        rejected(
                () -> DebridHttp.rejectCredentialUrl("https://cdn.example/" + KEY, KEY),
                "raw credential sink blocked");
        String encoded = KEY.replace("-", "%2D");
        rejected(
                () -> DebridHttp.rejectCredentialUrl("https://cdn.example/?a=" + encoded, KEY),
                "encoded credential sink blocked");
        WireHttp api = new WireHttp();
        api.add("https://api.real-debrid.com/rest/1.0/user", 200, "{\"id\":1}");
        Cancellation c = new Cancellation();
        check(
                api.json(KIND, "GET", "/rest/1.0/user", KEY, DebridHttp.params(), c).getInt("id")
                        == 1,
                "actual JSON transport");
        check(
                ("Bearer " + KEY).equals(api.opened.get(0).getRequestProperty("Authorization")),
                "API bearer present");
        check(
                "identity".equals(api.opened.get(0).getRequestProperty("Accept-Encoding")),
                "API identity encoding");
        check(api.opened.get(0).disconnected && c.active.isEmpty(), "API resource cleanup");
        WireHttp redirect = new WireHttp();
        redirect.add("https://api.real-debrid.com/x", 302, "");
        rejected(
                () -> redirect.json(KIND, "GET", "/rest/1.0/user", KEY, DebridHttp.params(), c),
                "API redirects refused");
        check(redirect.opened.size() == 1, "no credential forwarding");
        WireHttp accepted = new WireHttp();
        accepted.add("https://api.real-debrid.com/x", 202, "");
        check(
                accepted.json(
                                        KIND,
                                        "POST",
                                        "/rest/1.0/torrents/selectFiles/1",
                                        KEY,
                                        DebridHttp.params("files", "1"),
                                        c)
                                .length()
                        == 0,
                "empty202 accepted for adapter verification");
        WireHttp bad = new WireHttp();
        bad.add("https://api.real-debrid.com/x", 401, "{\"key\":\"" + KEY + "\"}");
        try {
            bad.json(KIND, "GET", "/rest/1.0/user", KEY, DebridHttp.params(), c);
            throw new AssertionError();
        } catch (IOException denied) {
            check(!denied.getMessage().contains(KEY), "API failures redacted");
        }
        WireHttp large = new WireHttp();
        large.replies.add(
                new FakeConnection(
                        "https://api.real-debrid.com/x", 200, new byte[2 * 1024 * 1024 + 1]));
        rejected(
                () -> large.json(KIND, "GET", "/rest/1.0/user", KEY, DebridHttp.params(), c),
                "JSON bound");
        WireHttp file = new WireHttp();
        FakeConnection first = new FakeConnection("https://cdn.example/one", 302, new byte[0]);
        first.headers.put("Location", "https://other.example/two");
        file.replies.add(first);
        file.add("https://other.example/two", 200, "fLaCfixture!");
        File target = File.createTempFile("debrid-http-", ".part");
        check(
                file.fetch(
                                new DebridModels.Download(
                                        "https://cdn.example/one", "song.flac", 12),
                                target,
                                c,
                                (n, t) -> {})
                        == 12,
                "real file transport complete");
        check(file.opened.size() == 2, "bounded file redirect followed");
        for (FakeConnection connection : file.opened)
            check(connection.getRequestProperty("Authorization") == null, "no API auth at CDN");
        check(target.length() == 12 && c.active.isEmpty(), "complete file and resource cleanup");
        target.delete();
        WireHttp leakedRedirect = new WireHttp();
        FakeConnection leak = new FakeConnection("https://cdn.example/one", 302, new byte[0]);
        leak.headers.put("Location", "https://other.example/?credential=" + KEY);
        leakedRedirect.replies.add(leak);
        File leakTarget = File.createTempFile("debrid-http-", ".part");
        DebridModels.Cancellation keyGuard =
                new DebridModels.Cancellation() {
                    public void check() {}

                    public void bind(Closeable resource) {}

                    public void clear(Closeable resource) {}

                    public void checkDownloadUrl(String url) throws IOException {
                        DebridHttp.rejectCredentialUrl(url, KEY);
                    }
                };
        rejected(
                () ->
                        leakedRedirect.fetch(
                                new DebridModels.Download(
                                        "https://cdn.example/one", "song.flac", 12),
                                leakTarget,
                                keyGuard,
                                (n, t) -> {}),
                "redirect cannot expose provider key");
        check(leakedRedirect.opened.size() == 1, "key redirect blocked before connection");
        leakTarget.delete();
        WireHttp shortFile = new WireHttp();
        shortFile.add("https://cdn.example/file", 200, "tiny");
        File shortTarget = File.createTempFile("debrid-http-", ".part");
        rejected(
                () ->
                        shortFile.fetch(
                                new DebridModels.Download(
                                        "https://cdn.example/file", "song.flac", 12),
                                shortTarget,
                                c,
                                (n, t) -> {}),
                "size mismatch rejected");
        shortTarget.delete();
        rejected(
                () ->
                        new DebridModels.Resolved(
                                KIND,
                                "r",
                                MAGNET,
                                Arrays.asList(
                                        new DebridModels.Entry("1", "a.flac", 12),
                                        new DebridModels.Entry("1", "b.flac", 12)),
                                "ready",
                                "Ready",
                                true),
                "duplicate file IDs rejected");
    }

    static void engineTests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.credentials.disable(KIND);
            rejected(() -> f.prepare(), "disabled provider blocked");
            check(f.provider.resolves.get() == 0, "disabled provider has no request");
        }
        try (Fixture f = new Fixture()) {
            rejected(
                    () -> f.engine.prepare(KIND, "https://cdn.example/?token=" + KEY),
                    "credential input rejected");
            rejected(
                    () ->
                            f.engine.prepare(
                                    KIND, "https://cdn.example/?token=" + KEY.replace("-", "%2D")),
                    "encoded credential input rejected");
            check(f.provider.resolves.get() == 0, "credential input never sent to provider");
            f.provider.keyName = true;
            rejected(() -> f.prepare(), "credential filename rejected");
            check(
                    f.engine.prepared().isEmpty(),
                    "credential filename never reaches preparation UI");
        }
        try (Fixture f = new Fixture()) {
            f.provider.ready = false;
            DebridDownloads.Prepared p = f.prepare();
            check(!p.ready && p.entries.size() == 3, "nonready listing retained");
            String id = f.enqueue(p, "1");
            until(() -> f.job(id).state.equals("WAITING_PROVIDER"), "waiting provider state");
            check(
                    f.provider.selects.get() == 1 && f.provider.selected.equals(Arrays.asList("1")),
                    "exact selection once");
            check(f.http.fetches.get() == 0, "pending transfer not downloaded");
            f.provider.ready = true;
            f.engine.refresh(p.id);
            f.engine.resume(id);
            until(() -> f.job(id).state.equals("HANDED_OFF"), "ready file handed off");
            check(
                    f.provider.resolves.get() == 1 && f.provider.selects.get() == 1,
                    "refresh/resume does not recreate or reselect");
            check(
                    BulkDownloads.destination == f.destination
                            && BulkDownloads.formats.equals("all"),
                    "destination and format snapshot forwarded");
            check(f.job(id).bulkJobId.equals(id), "stable import token");
            int calls = f.provider.downloads.get();
            f.engine.pause(id);
            f.engine.resume(id);
            until(() -> f.job(id).state.equals("HANDED_OFF"), "handoff resume");
            check(
                    f.provider.downloads.get() == calls,
                    "handoff retry never refetches provider bytes");
        }
        try (Fixture f = new Fixture()) {
            DebridDownloads.Prepared p = f.prepare();
            rejected(() -> f.enqueue(p, "3"), "non-audio selection rejected");
            rejected(
                    () -> f.engine.enqueue(p.id, Arrays.asList("1"), f.destination, "mp3"),
                    "disabled codec candidate rejected");
            check(f.provider.selects.get() == 0, "invalid selections never mutate provider");
            rejected(() -> f.enqueue(p, "1", "1"), "duplicate selection rejected");
        }
        try (Fixture f = new Fixture()) {
            f.provider.wrongName = true;
            String id = f.enqueue(f.prepare(), "1");
            until(() -> f.job(id).state.equals("FAILED"), "wrong identity failed");
            check(f.http.fetches.get() == 0, "wrong file never fetched");
        }
        try (Fixture f = new Fixture()) {
            f.provider.keyLeak = true;
            String id = f.enqueue(f.prepare(), "1");
            until(() -> f.job(id).state.equals("FAILED"), "credential URL failed");
            check(
                    f.http.fetches.get() == 0 && !f.job(id).message.contains(KEY),
                    "credential sink blocked without exposure");
        }
        try (Fixture f = new Fixture()) {
            f.provider.rawFailure = true;
            String id = f.enqueue(f.prepare(), "1");
            until(() -> f.job(id).state.equals("FAILED"), "raw provider failure failed");
            check(!f.job(id).message.contains(KEY), "raw secret failure redacted");
        }
        try (Fixture f = new Fixture()) {
            f.http.badAudio = true;
            int before = BulkDownloads.imports;
            String id = f.enqueue(f.prepare(), "1");
            until(() -> f.job(id).state.equals("FAILED"), "bad audio failed");
            check(BulkDownloads.imports == before, "codec failure never published");
        }
        try (Fixture f = new Fixture()) {
            com.music.bitchord.data.settings.AppSettings.allowed = false;
            String id = f.enqueue(f.prepare(), "1");
            until(() -> f.job(id).state.equals("FAILED"), "metered policy failed");
            check(f.http.fetches.get() == 0, "host download policy respected");
            com.music.bitchord.data.settings.AppSettings.allowed = true;
        }
        try (Fixture f = new Fixture()) {
            f.http.blocked = true;
            DebridDownloads.Prepared p = f.prepare();
            List<String> ids =
                    f.engine.enqueue(p.id, Arrays.asList("1", "2"), f.destination, "all");
            check(f.http.entered.await(2, TimeUnit.SECONDS), "first file active");
            long start = System.nanoTime();
            f.engine.pause(ids.get(1));
            check(
                    System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1),
                    "queued pause does not await unrelated writer");
            check(f.job(ids.get(1)).state.equals("PAUSED"), "queued pause state");
            f.engine.pause(ids.get(0));
            check(f.http.fetches.get() == 1, "paused queued file never starts");
            check(!f.engine.hasActiveWork(), "pause writer barrier");
        }
        try (Fixture f = new Fixture()) {
            f.http.blocked = true;
            String id = f.enqueue(f.prepare(), "1");
            check(f.http.entered.await(2, TimeUnit.SECONDS), "rotation file active");
            f.credentials.enable(KIND, "another-fixture-key-0123456789");
            check(f.job(id).state.equals("PAUSED"), "key rotation pauses writer");
            rejected(
                    () -> f.engine.resume(id),
                    "old provider identity cannot resume with new account key");
            check(f.http.fetches.get() == 1, "rotation does not fetch new-account file");
        }
        try (Fixture f = new Fixture()) {
            f.provider.blockedControl = true;
            AtomicBoolean done = new AtomicBoolean();
            Thread control =
                    new Thread(
                            () -> {
                                try {
                                    f.prepare();
                                } catch (IOException expected) {
                                } finally {
                                    done.set(true);
                                }
                            });
            control.start();
            check(f.provider.controlEntered.await(2, TimeUnit.SECONDS), "control active");
            f.engine.stop();
            check(done.get(), "stop joins provider control request");
            check(!f.engine.hasActiveWork(), "stop leaves no active operation");
            control.join(1000);
        }
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 16; i++) f.prepare();
            int requests = f.provider.resolves.get();
            rejected(() -> f.prepare(), "preparation capacity bounded before API call");
            check(
                    f.provider.resolves.get() == requests,
                    "full preparation list cannot orphan another cloud job");
            f.engine.discardPrepared(f.engine.prepared().get(0).id);
            check(f.prepare() != null, "dismiss frees preparation capacity");
        }
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 70; i++) {
                String id = f.enqueue(f.prepare(), "1");
                until(() -> f.job(id).state.equals("HANDED_OFF"), "history handoff");
            }
            check(f.engine.jobs().size() <= 64, "finished status history bounded");
            check(
                    f.provider.downloads.get() == 70,
                    "completed history does not permanently consume queue capacity");
        }
    }

    public static void main(String[] args) throws Exception {
        httpTests();
        engineTests();
        System.out.println("Debrid core: " + checks + " assertions passed");
    }
}
