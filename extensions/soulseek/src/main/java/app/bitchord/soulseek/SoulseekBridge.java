package app.bitchord.soulseek;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-phone session, bounded catalog and shared transfer leases. No server or local HTTP port. */
public final class SoulseekBridge {
    static volatile SoulseekBridge instance;
    static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;
    private static final long FREE_RESERVE = 32L * 1024 * 1024;
    final Context context;
    final File home;
    final SoulseekCredentialStore credentials;
    final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Map<String, SoulseekTrack> catalog = new LinkedHashMap<>();
    private final SoulseekSearchCache searches = new SoulseekSearchCache();
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();
    private volatile long handle;
    private volatile boolean connected;
    private volatile boolean engineShutdownFailed;
    private long loginIntent;
    private final Object lifecycle = new Object();
    volatile String sessionTag = UUID.randomUUID().toString();
    volatile String status = "Not signed in";
    volatile String username = "";

    private SoulseekBridge(Context context) throws IOException {
        this.context = context.getApplicationContext();
        home = new File(this.context.getNoBackupFilesDir(), "soulseek-native");
        if (!home.isDirectory() && !home.mkdirs())
            throw new IOException("Cannot create Soulseek storage");
        credentials = new SoulseekCredentialStore(this.context, home);
        PublicSoulseekShares.initialize(this.context);
    }

    public static synchronized void boot(Context context) {
        if (instance != null || context == null || !mainProcess(context)) return;
        try {
            SoulseekBridge bridge = new SoulseekBridge(context);
            instance = bridge;
            bridge.publish();
            bridge.reconnectSaved();
        } catch (Exception ignored) {
            // Boot must not prevent the original music player starting.
        }
    }

    void reconnectSaved() {
        if (!DownloadSettings.enabled(context, DownloadSettings.SOULSEEK)) return;
        final long intent = beginLogin();
        work.execute(
                () -> {
                    try {
                        if (!DownloadSettings.enabled(context, DownloadSettings.SOULSEEK)) return;
                        String[] saved = credentials.load();
                        if (saved != null) login(saved[0], saved[1], 0, intent);
                    } catch (Throwable e) {
                        synchronized (this) {
                            if (loginIntent == intent)
                                status =
                                        "Saved Soulseek account could not connect. Open setup"
                                                + " to retry.";
                        }
                    }
                });
    }

    private static boolean mainProcess(Context context) {
        String process;
        if (android.os.Build.VERSION.SDK_INT >= 28)
            process = android.app.Application.getProcessName();
        else
            try (java.io.InputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
                byte[] bytes = new byte[512];
                int length = input.read(bytes), end = 0;
                while (end < length && bytes[end] != 0) end++;
                process = new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException unavailable) {
                return false;
            }
        return context.getApplicationInfo().processName.equals(process);
    }

    static SoulseekBridge get() throws IOException {
        SoulseekBridge value = instance;
        if (value == null) throw new IOException("Soulseek is unavailable. Restart the app.");
        return value;
    }

    public static void openSetup() {
        SoulseekBridge bridge = instance;
        if (bridge != null)
            bridge.context.startActivity(
                    new Intent(bridge.context, SoulseekActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    String url() {
        return SoulseekAddon.ORIGIN + sessionTag;
    }

    private void publish() {
        new Handler(Looper.getMainLooper())
                .post(
                        () -> {
                            try {
                                SoulseekSource.register(url());
                            } catch (Exception e) {
                                status =
                                        "Soulseek source could not be registered in this BitChord"
                                                + " version.";
                            }
                        });
    }

    synchronized long beginLogin() {
        searches.clear();
        return ++loginIntent;
    }

    // mode: 0 reconnect saved credentials unchanged, 1 remember, 2 explicitly do not remember.
    void login(String user, String password, int mode, long intent) throws Exception {
        synchronized (lifecycle) {
            loginSerialized(user, password, mode, intent);
        }
    }

    private void loginSerialized(String user, String password, int mode, long intent)
            throws Exception {
        DownloadSettings.requireEnabled(context, DownloadSettings.SOULSEEK);
        synchronized (this) {
            if (intent != loginIntent) throw new IOException("Sign-in cancelled");
        }
        if (user == null
                || (!user.matches("[ -~]{1,30}") || !user.equals(user.trim()))
                || password == null
                || password.isEmpty()
                || password.length() > 256) {
            synchronized (this) {
                if (intent == loginIntent)
                    status = "Enter a Soulseek username (1–30 printable characters) and password.";
            }
            throw new IOException("Enter a Soulseek username and password.");
        }
        synchronized (this) {
            if (intent != loginIntent) throw new IOException("Sign-in cancelled");
        }
        disconnect();
        if (engineShutdownFailed)
            throw new IOException("Restart BitChord before reconnecting Soulseek.");
        String generation = sessionTag;
        synchronized (this) {
            if (intent != loginIntent) throw new IOException("Sign-in cancelled");
            if (mode == 2) credentials.clear();
            status = "Connecting to Soulseek…";
        }
        long created = 0;
        try {
            DownloadSettings.requireEnabled(context, DownloadSettings.SOULSEEK);
            SoulseekNative.load();
            // Standard server only. This is not a user-configurable proxy or hosted backend.
            created =
                    SoulseekNative.create(home.getAbsolutePath(), "server.slsknet.org", 2242, 2234);
            synchronized (this) {
                if (intent != loginIntent || !generation.equals(sessionTag))
                    throw new IOException("Sign-in cancelled");
                handle = created;
            }
            SoulseekNative.connect(created, user, password);
            synchronized (this) {
                if (intent != loginIntent || handle != created || !generation.equals(sessionTag))
                    throw new IOException("Sign-in cancelled");
                if (mode == 1) credentials.save(user, password);
                username = user;
                connected = true;
                status = "Connected. Search Addons, then choose Download from the track menu.";
                try {
                    PublicSoulseekShares.reconcile(context);
                } catch (Exception unavailable) {
                    status = "Connected. Public sharing needs a rescan in Downloads.";
                }
            }
        } catch (Throwable e) {
            synchronized (this) {
                if (handle == created) {
                    handle = 0;
                    connected = false;
                }
            }
            if (created != 0) {
                try {
                    SoulseekNative.close(created);
                } catch (Throwable shutdownError) {
                    engineShutdownFailed = true;
                }
            }
            synchronized (this) {
                if (intent == loginIntent)
                    status =
                            "Could not sign in to Soulseek. Check the account and network; another"
                                    + " login may use this account.";
            }
            if (e instanceof Exception) throw (Exception) e;
            throw new IOException("The Soulseek native engine could not start", e);
        }
    }

    void disconnect() {
        long closing;
        synchronized (this) {
            connected = false;
            closing = handle;
            handle = 0;
            username = "";
            sessionTag = UUID.randomUUID().toString();
            catalog.clear();
            searches.clear();
            transfers.clear();
        }
        if (closing != 0) {
            try {
                SoulseekNative.close(closing);
            } catch (Throwable shutdownError) {
                engineShutdownFailed = true;
            }
        }
        status =
                engineShutdownFailed
                        ? "Soulseek stopped, but a transfer did not shut down. Restart BitChord"
                                + " before reconnecting."
                        : "Not signed in";
        publish();
    }

    boolean shutdownFailed() {
        return engineShutdownFailed;
    }

    void forget() throws Exception {
        try {
            synchronized (this) {
                ++loginIntent; // Invalidates queued sign-ins as well as the connecting session.
                credentials.clear();
            }
        } finally {
            // Keystore/storage failure must never leave the account connected.
            disconnect();
        }
        if (!engineShutdownFailed) status = "Signed out. Saved credentials removed.";
    }

    /** Explicit account-preserving cache clear. Never runs while a reader owns a transfer. */
    void clearTemporaryCache() throws IOException {
        synchronized (lifecycle) {
            synchronized (this) {
                for (Transfer transfer : transfers.values()) {
                    if (transfer.readers != 0)
                        throw new IOException(
                                "Stop Soulseek playback and downloads before clearing its cache.");
                }
                // Block a new reader before dropping the bridge lock and stopping the native
                // engine.
                connected = false;
            }
            status = "Stopping Soulseek before clearing temporary audio…";
            disconnect();
            if (engineShutdownFailed)
                throw new IOException(
                        "Soulseek has not stopped safely. Restart BitChord before clearing its"
                                + " cache.");
            // Successful native close is a worker-join barrier. A failed close must retain all
            // files.
            File cache = new File(home.getCanonicalFile(), "transfers");
            SoulseekCacheFiles.clear(cache);
            status =
                    "Temporary Soulseek audio cleared. Saved account kept; reconnect to use the"
                            + " source.";
        }
    }

    boolean connected() {
        return connected;
    }

    long sharingHandle() throws IOException {
        long current = handle;
        if (!connected || current == 0) throw new IOException("Soulseek is not connected");
        return current;
    }

    long ready() throws IOException {
        DownloadSettings.requireEnabled(context, DownloadSettings.SOULSEEK);
        long current = handle;
        if (!connected || current == 0)
            throw new IOException("Sign in to Soulseek from Patches & tweaks first.");
        return current;
    }

    List<SoulseekTrack> search(String query) throws Exception {
        if (query == null || query.trim().length() < 2 || query.length() > 256)
            throw new IOException("Search using 2–256 characters.");
        String terms = query.trim();
        AudioFormatPreferences.Settings settings = AudioFormatPreferences.snapshot(context);
        String policy = searchPolicy(settings);
        long current;
        String tag, scope;
        synchronized (this) {
            current = ready();
            tag = sessionTag;
            if (!policy.equals(searchPolicy(AudioFormatPreferences.snapshot(context))))
                throw new IOException("Audio format choices changed. Search again.");
            scope = tag + ":" + current + ":" + policy;
            List<SoulseekTrack> cached = searches.get(scope, terms);
            if (cached != null) {
                if (ready() != current
                        || !tag.equals(sessionTag)
                        || !policy.equals(searchPolicy(AudioFormatPreferences.snapshot(context)))) {
                    searches.clear();
                    throw new IOException("Soulseek settings changed. Search again.");
                }
                rememberTracks(cached);
                return cached;
            }
        }
        JSONObject result =
                new JSONObject(
                        SoulseekNative.searchAdaptive(
                                current,
                                terms,
                                12000,
                                AudioFormatPreferences.preferredSearchExtensions(settings)));
        JSONArray rows = result.optJSONArray("tracks");
        ArrayList<SoulseekTrack> found = new ArrayList<>();
        if (rows != null)
            for (int i = 0; i < Math.min(rows.length(), 200); i++) {
                try {
                    found.add(new SoulseekTrack(rows.getJSONObject(i)));
                } catch (Exception ignored) {
                }
            }
        synchronized (this) {
            if (ready() != current || !tag.equals(sessionTag))
                throw new IOException("Soulseek session changed. Search again.");
            if (!policy.equals(searchPolicy(AudioFormatPreferences.snapshot(context))))
                throw new IOException("Audio format choices changed. Search again.");
            searches.put(scope, terms, found);
            rememberTracks(found);
        }
        return found;
    }

    private static String searchPolicy(AudioFormatPreferences.Settings settings) {
        return AudioFormatPreferences.encode(settings) + ":" + settings.configured;
    }

    // Caller holds the bridge lock, including when a cache hit restores evicted catalog entries.
    private void rememberTracks(List<SoulseekTrack> found) {
        for (SoulseekTrack track : found) catalog.put(track.id, track);
        while (catalog.size() > 600) catalog.remove(catalog.keySet().iterator().next());
    }

    synchronized SoulseekTrack track(String id) throws IOException {
        ready();
        SoulseekTrack track = catalog.get(id);
        if (track == null) throw new IOException("Soulseek result expired. Search again.");
        return track;
    }

    private static final class Transfer {
        final String id;
        final SoulseekTrack track;
        final long handle;
        int readers;
        boolean previewOnly;

        Transfer(String id, SoulseekTrack track, long handle) {
            this.id = id;
            this.track = track;
            this.handle = handle;
        }
    }

    /** Read-only native transfer facts; no endpoint, peer key or private path is exposed. */
    static final class TransferStatus {
        final String state;
        final long downloaded, total, queuePosition;

        TransferStatus(String state, long downloaded, long total, long queuePosition) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
            this.queuePosition = queuePosition;
        }
    }

    TransferStatus transferStatus(String id, String expectedSession) throws IOException {
        final Transfer transfer;
        synchronized (this) {
            if (!expectedSession.equals(sessionTag))
                throw new IOException("Soulseek session changed. Search again.");
            ready();
            transfer = transfers.get(id);
            if (transfer == null) return null;
        }
        try {
            JSONArray rows =
                    new JSONObject(SoulseekNative.transfers(transfer.handle))
                            .getJSONArray("transfers");
            synchronized (this) {
                if (!expectedSession.equals(sessionTag) || ready() != transfer.handle)
                    throw new IOException("Soulseek session changed. Search again.");
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!transfer.id.equals(row.optString("id"))) continue;
                long position = -1;
                Object queue = row.opt("queuePosition");
                if (queue instanceof Number) {
                    double value = ((Number) queue).doubleValue();
                    if (value >= 0 && value <= 0xffffffffL && value == Math.rint(value))
                        position = (long) value;
                }
                return new TransferStatus(
                        row.optString("status"),
                        Math.max(0, Math.min(transfer.track.size, row.optLong("downloaded"))),
                        transfer.track.size,
                        position);
            }
            return null;
        } catch (IOException changed) {
            throw changed;
        } catch (Exception unavailable) {
            throw new IOException("Soulseek transfer state unavailable", unavailable);
        }
    }

    ProgressiveReader open(String id, long position, long length) throws IOException {
        synchronized (this) {
            return openSelected(track(id), sessionTag, position, length);
        }
    }

    /** Keeps a transfer alive across extractor seeks without granting arbitrary source playback. */
    synchronized ProgressiveReader holdPreview(SoulseekTrack track, String session)
            throws IOException {
        boolean existed = transfers.containsKey(track.id);
        ProgressiveReader hold = openSelected(track, session, 0, 0);
        if (!existed) transfers.get(track.id).previewOnly = true;
        return hold;
    }

    synchronized ProgressiveReader.Snapshot previewSnapshot(SoulseekTrack track, String session)
            throws IOException {
        if (!session.equals(sessionTag)) throw new IOException("Soulseek session changed");
        Transfer transfer = transfers.get(track.id);
        if (transfer == null) throw new IOException("Preview transfer expired");
        return snapshotFor(transfer);
    }

    ProgressiveReader openSelected(
            SoulseekTrack track, String expectedSession, long position, long length)
            throws IOException {
        final String id = track.id;
        Transfer transfer;
        synchronized (this) {
            ready();
            if (!expectedSession.equals(sessionTag))
                throw new IOException("Soulseek session changed. Select the result again.");
            if (position < 0 || position > track.size || length < -1)
                throw new IOException("Invalid Soulseek audio range");
            transfer = transfers.get(id);
            if (transfer == null) {
                if (track.size > MAX_CACHE_BYTES)
                    throw new IOException("This experimental source limits each file to 256 MiB.");
                trimFor(track.size);
                if (transfers.size() >= 2)
                    throw new IOException(
                            "Two Soulseek files are active. Stop another transfer first.");
                if (home.getUsableSpace() < track.size + FREE_RESERVE)
                    throw new IOException("Not enough free phone storage for this audio.");
                try {
                    long current = ready();
                    JSONObject started =
                            new JSONObject(
                                    SoulseekNative.download(
                                            current, track.peer, track.path, track.size));
                    transfer = new Transfer(started.getString("id"), track, current);
                    transfers.put(id, transfer);
                } catch (Exception e) {
                    throw new IOException(
                            "Soulseek transfer could not start. Try another peer.", e);
                }
            }
            transfer.readers++;
        }
        final Transfer lease = transfer;
        try {
            return new ProgressiveReader(
                    new ProgressiveReader.Source() {
                        public ProgressiveReader.Snapshot snapshot() throws IOException {
                            return snapshotFor(lease);
                        }

                        public void release() {
                            releaseTransfer(id, lease);
                        }
                    },
                    lease.track.size,
                    position,
                    length,
                    30000);
        } catch (IOException e) {
            releaseTransfer(id, lease);
            throw e;
        }
    }

    private void trimFor(long bytes) throws IOException {
        long occupied = 0;
        for (Transfer item : transfers.values()) occupied += item.track.size;
        java.util.Iterator<Map.Entry<String, Transfer>> iterator = transfers.entrySet().iterator();
        while (iterator.hasNext()
                && (occupied + bytes > MAX_CACHE_BYTES || transfers.size() >= 2)) {
            Transfer item = iterator.next().getValue();
            if (item.readers != 0) continue;
            ProgressiveReader.Snapshot state = snapshotFor(item);
            if (state.file != null && state.file.exists() && !state.file.delete()) continue;
            occupied -= item.track.size;
            iterator.remove();
        }
        if (occupied + bytes > MAX_CACHE_BYTES)
            throw new IOException("Soulseek cache is busy. Stop another transfer first.");
    }

    private ProgressiveReader.Snapshot snapshotFor(Transfer transfer) throws IOException {
        if (ready() != transfer.handle) throw new IOException("Soulseek session changed");
        try {
            JSONArray rows =
                    new JSONObject(SoulseekNative.transfers(transfer.handle))
                            .getJSONArray("transfers");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!transfer.id.equals(row.optString("id"))) continue;
                File local = null;
                String path = row.optString("localPath");
                if (!path.isEmpty()) {
                    local = new File(path).getCanonicalFile();
                    String allowed =
                            new File(home, "transfers").getCanonicalPath() + File.separator;
                    if (!local.getPath().startsWith(allowed))
                        throw new IOException("Invalid Soulseek cache path");
                }
                return new ProgressiveReader.Snapshot(
                        local,
                        row.getLong("downloaded"),
                        row.getLong("total"),
                        row.optBoolean("complete"),
                        row.optString("status"));
            }
            throw new IOException("Soulseek transfer expired");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Soulseek transfer state unavailable", e);
        }
    }

    private synchronized void releaseTransfer(String key, Transfer transfer) {
        if (transfer.readers > 0) transfer.readers--;
        if (transfer.readers != 0 || transfers.get(key) != transfer) return;
        try {
            ProgressiveReader.Snapshot state = snapshotFor(transfer);
            if (!state.complete) {
                SoulseekNative.cancel(transfer.handle, transfer.id);
                transfers.remove(key);
            } else if (transfer.previewOnly) {
                // Native completion is the write/rename barrier. Never unlink a live writer.
                if (state.file != null) state.file.delete();
                transfers.remove(key);
            }
        } catch (Exception ignored) {
            transfers.remove(key);
        }
    }
}
