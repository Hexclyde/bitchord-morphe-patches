package app.bitchord.telegram;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function2;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** BitChord's own members for {@link SourcePreparation}. The patch checks each one exists. */
final class SourcePreparationHost implements SourcePreparation.Host {
    private static final String PLAYBACK = "com.music.bitchord.playback.";
    private static final String SOURCES = "com.music.bitchord.data.sources.";
    private static final int THERMAL_SEVERE = 3, LOW_BATTERY = 15;

    private final Object service;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, File> manifestFiles = new ConcurrentHashMap<>();
    private final Map<String, String> claims = new HashMap<>(), parkedIdentities = new HashMap<>();
    private java.util.function.BiPredicate<String, String> holding;
    private volatile boolean detached;
    private final SourcePreparation.WeakIdentitySnapshots<Object, JSONObject> described =
            new SourcePreparation.WeakIdentitySnapshots<>();

    @Override
    public void describe(Object stream) throws Exception {
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge != null && InternalAddon.reserved(url(stream))) {
            long generation = bridge.generation, revision = bridge.mediaRevision;
            String session = bridge.sessionTag;
            JSONObject row = bridge.resolveTrack(InternalAddon.audioId(bridge, url(stream)));
            JSONObject snapshot = row == null ? null : new JSONObject(row.toString());
            synchronized (bridge.mediaChanges) {
                if (generation != bridge.generation
                        || revision != bridge.mediaRevision
                        || !java.util.Objects.equals(session, bridge.sessionTag))
                    throw new IOException("Source changed while describing its file");
                if (snapshot != null) {
                    TelegramMediaChanges.Watch watch =
                            bridge.mediaChanges.watch(
                                    snapshot.getLong("chat"), snapshot.getLong("message"));
                    if (!watch.valid) throw new IOException("Too many prepared file descriptions");
                    described.put(
                            stream,
                            snapshot.put("_generation", generation)
                                    .put("_revision", revision)
                                    .put("_session", session)
                                    .put("_watch", watch));
                }
            }
        }
    }

    @Override
    public void attach(SourcePreparation engine) {
        holding = engine::holds;
        TelegramMatch.held = holding;
    }

    @Override
    public void detach(SourcePreparation engine) {
        detached = true;
        if (TelegramMatch.held == holding) TelegramMatch.held = null;
        for (File file : manifestFiles.values()) file.delete();
        manifestFiles.clear();
    }

    private <T> T onPlayer(Callable<T> action) throws Exception {
        if (Looper.myLooper() == main.getLooper()) return action.call();
        FutureTask<T> work = new FutureTask<>(action);
        main.post(work);
        return work.get(3, TimeUnit.SECONDS);
    }

    SourcePreparationHost(Object service) {
        this.service = service;
        context = (Context) service;
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static Object call(String owner, String name, Object... arguments) throws Exception {
        return SmartDownloadSources.call(type(owner), name, arguments);
    }

    private static Object staticField(String owner, String name) throws Exception {
        return type(owner).getField(name).get(null);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field found = owner.getClass().getField(name);
        return found.get(owner);
    }

    static Player player(Object service, String name) throws Exception {
        Field field = service.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Player) field.get(service);
    }

    private List<?> activeSources() throws Exception {
        return (List<?>) call(SOURCES + "SourceRegistry", "activeForPlayback");
    }

    private static String sourceId(Object source) throws Exception {
        return (String) type(SOURCES + "MusicSource").getMethod("getConfigId").invoke(source);
    }

    @Override
    public boolean sourcesAvailable() {
        try {
            for (Object source : activeSources()) {
                Object kind = type(SOURCES + "MusicSource").getMethod("getKind").invoke(source);
                // Ranked ahead of YouTube; with nothing there, YouTube is all there is.
                return !(kind instanceof Enum) || !((Enum<?>) kind).name().equals("YOUTUBE");
            }
            return false;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private boolean tooHot() {
        if (Build.VERSION.SDK_INT < 29) return false;
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.getCurrentThermalStatus() >= THERMAL_SEVERE;
    }

    private static boolean offline() throws Exception {
        return Boolean.TRUE.equals(call(PLAYBACK + "AudioCache", "isOffline"));
    }

    @Override
    public boolean backgroundAllowed() {
        try {
            if (offline() || tooHot() || AutomixAccess.stopped()) return false;
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null && power.isPowerSaveMode()) return false;
            Intent battery =
                    context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return true;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return plugged != 0 || scale <= 0 || level * 100 / scale >= LOW_BATTERY;
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public boolean foregroundAllowed() {
        try {
            return !offline() && !tooHot() && !AutomixAccess.stopped();
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public boolean leftToHost(String mediaId) {
        try {
            if (call("com.music.bitchord.download.Downloads", "verifiedSavedUri", mediaId) != null)
                return true;
            if (Boolean.TRUE.equals(call(PLAYBACK + "OriginalVersion", "isPinned", mediaId)))
                return true;
            if (Boolean.TRUE.equals(call(PLAYBACK + "DirectSourceGate", "isActive"))) return true;
            Object refused = staticField(PLAYBACK + "StreamChoice", "refusedSubstitutes");
            if (((Map<?, ?>) refused).containsKey(mediaId)) return true;
            Object flow =
                    staticField("com.music.bitchord.data.listentogether.ListenTogether", "state");
            Object state = flow.getClass().getMethod("getValue").invoke(flow);
            return field(state, "code") != null;
        } catch (Exception unavailable) {
            // Not known to be free to act on.
            return true;
        }
    }

    @Override
    public Object target(Uri item) {
        try {
            return call(SOURCES + "SourceResolver", "targetIn", item);
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @Override
    public Object lookup(Object target) throws Exception {
        Object resolver = staticField(SOURCES + "SourceResolver", "INSTANCE");
        Method lookup = null;
        for (Method method : resolver.getClass().getMethods())
            if (method.getName().equals("substituteForYouTube")) lookup = method;
        if (lookup == null) throw new NoSuchMethodException("substituteForYouTube");
        Method found = lookup;
        return VideoSwitch.await(continuation -> found.invoke(resolver, target, continuation));
    }

    @Override
    public String url(Object stream) {
        try {
            return (String) field(stream, "url");
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @Override
    public String source(Object stream) {
        try {
            return (String) field(stream, "sourceConfigId");
        } catch (Exception unavailable) {
            return null;
        }
    }

    @Override
    public boolean sourceActive(String source) {
        if (source == null) return false;
        try {
            for (Object active : activeSources()) if (source.equals(sourceId(active))) return true;
        } catch (Exception unavailable) {
            // Not known to be enabled.
        }
        return false;
    }

    @Override
    public boolean manifest(Object stream) {
        try {
            return call(PLAYBACK + "StreamContainer", "manifestMimeOf", url(stream)) != null;
        } catch (Exception unavailable) {
            return true;
        }
    }

    @Override
    public String candidate(Object stream) {
        String url = url(stream);
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge == null || !InternalAddon.reserved(url)) return null;
        try {
            return TelegramMatch.TELEGRAM + InternalAddon.audioId(bridge, url);
        } catch (IOException unavailable) {
            return null;
        }
    }

    @Override
    public String terms(Object stream) {
        String url = url(stream);
        String policy;
        try {
            policy = String.valueOf(call(SOURCES + "SourceResolver", "requestForNow"));
        } catch (Exception unavailable) {
            return null;
        }
        if (!InternalAddon.reserved(url)) return policy;
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge == null) return null;
        try {
            JSONObject row = described.get(stream);
            if (row == null
                    || !bridge.selected.contains(row.getLong("chat"))
                    || row.optLong("_generation") != bridge.generation
                    || !java.util.Objects.equals(row.optString("_session"), bridge.sessionTag)
                    || !(row.opt("_watch") instanceof TelegramMediaChanges.Watch)
                    || !((TelegramMediaChanges.Watch) row.opt("_watch")).valid) return null;
            return policy + ":" + bridge.generation + ":" + bridge.sessionTag;
        } catch (Exception unavailable) {
            return null;
        }
    }

    @Override
    public String contentIdentity(Object stream) {
        String url = url(stream);
        if (manifest(stream)) return null; // An index's identity says nothing about its segments.
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge != null && InternalAddon.reserved(url))
            try {
                JSONObject row = described.get(stream);
                String unique = row == null ? "" : row.optString("unique");
                long size = row == null ? 0 : row.optLong("size");
                return unique.isEmpty() || size <= 0 ? null : "telegram:" + unique + ":" + size;
            } catch (Exception unavailable) {
                return null;
            }
        // These fields describe an immutable YouTube rendition, unlike an expiring URL alone.
        Uri uri = Uri.parse(url);
        String itag = uri.getQueryParameter("itag"),
                length = uri.getQueryParameter("clen"),
                modified = uri.getQueryParameter("lmt");
        String host = uri.getHost();
        return host != null
                        && (host.equals("googlevideo.com") || host.endsWith(".googlevideo.com"))
                        && itag != null
                        && length != null
                        && modified != null
                ? "file:" + itag + ":" + length + ":" + modified
                : null;
    }

    private DataSpec verificationSpec(Object stream, BooleanSupplier cancelled) throws Exception {
        if (!manifest(stream)) return spec(stream);
        String address = url(stream);
        File file = manifestFiles.get(address);
        if (file == null || !file.isFile()) {
            String codec = String.valueOf(field(field(stream, "format"), "codec"));
            String extension =
                    codec.toLowerCase(java.util.Locale.ROOT).contains("flac") ? "flac" : "m4a";
            File temporary =
                    File.createTempFile("source-check-", "." + extension, context.getCacheDir());
            try (OutputStream sink =
                    new FileOutputStream(temporary) {
                        long written;

                        private void check(int count) throws IOException {
                            written += count;
                            if (cancelled.getAsBoolean()
                                    || Thread.currentThread().isInterrupted()
                                    || written > 256L * 1024 * 1024)
                                throw new IOException("Manifest check stopped");
                        }

                        @Override
                        public void write(byte[] bytes, int offset, int count) throws IOException {
                            check(count);
                            super.write(bytes, offset, count);
                        }

                        @Override
                        public void write(int value) throws IOException {
                            check(1);
                            super.write(value);
                        }
                    }) {
                Function2<Long, Long, Unit> progress =
                        (done, total) -> {
                            if (cancelled.getAsBoolean())
                                throw new java.util.concurrent.CancellationException();
                            return Unit.INSTANCE;
                        };
                String mime =
                        (String) call(PLAYBACK + "StreamContainer", "manifestMimeOf", address);
                // The release host inlines write(), retaining its worker and native assemblers.
                Class<?> worker = type("com.music.bitchord.download.ManifestFile$write$2");
                java.lang.reflect.Constructor<?> constructor =
                        worker.getDeclaredConstructor(
                                String.class,
                                boolean.class,
                                Function2.class,
                                File.class,
                                String.class,
                                OutputStream.class,
                                Map.class,
                                Continuation.class);
                constructor.setAccessible(true);
                Continuation<Object> completion =
                        new Continuation<Object>() {
                            public CoroutineContext getContext() {
                                return EmptyCoroutineContext.INSTANCE;
                            }

                            public void resumeWith(Object result) {}
                        };
                Object task =
                        constructor.newInstance(
                                address,
                                "application/dash+xml".equals(mime),
                                progress,
                                context.getCacheDir(),
                                extension,
                                sink,
                                field(stream, "headers"),
                                completion);
                Method run = worker.getDeclaredMethod("invokeSuspend", Object.class);
                run.setAccessible(true);
                Object result = run.invoke(task, Unit.INSTANCE);
                if (result == kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED)
                    throw new IOException("Manifest reader did not complete");
                if (cancelled.getAsBoolean() || detached)
                    throw new IOException("Manifest check stopped");
                if (manifestFiles.size() >= SourcePreparation.KEPT) {
                    String oldest = manifestFiles.keySet().iterator().next();
                    File expired = manifestFiles.remove(oldest);
                    if (expired != null) expired.delete();
                }
                file = temporary;
                manifestFiles.put(address, file);
            } catch (Exception failure) {
                temporary.delete();
                throw failure;
            } catch (Error failure) {
                temporary.delete();
                throw failure;
            }
        }
        return new DataSpec(Uri.fromFile(file));
    }

    @Override
    public boolean reopenManifest(Uri original, Uri marked, Object stream) {
        try {
            String mime =
                    (String) call(PLAYBACK + "StreamContainer", "manifestMimeOf", url(stream));
            if (mime == null) return false;
            return onPlayer(
                    () -> {
                        boolean found = false;
                        for (String deck : new String[] {"player", "spare"}) {
                            Player player = player(service, deck);
                            if (player == null) continue;
                            int count =
                                    ((Number)
                                                    Player.class
                                                            .getMethod("getMediaItemCount")
                                                            .invoke(player))
                                            .intValue();
                            for (int i = 0; i < count; i++) {
                                MediaItem item = player.getMediaItemAt(i);
                                if (item.localConfiguration == null
                                        || !original.equals(item.localConfiguration.uri)) continue;
                                Object builder =
                                        MediaItem.class.getMethod("buildUpon").invoke(item);
                                builder.getClass()
                                        .getMethod("setUri", String.class)
                                        .invoke(builder, marked.toString());
                                Field declaredMime =
                                        builder.getClass().getDeclaredField("mimeType");
                                declaredMime.setAccessible(true);
                                declaredMime.set(builder, mime);
                                MediaItem replacement =
                                        (MediaItem)
                                                builder.getClass()
                                                        .getMethod("build")
                                                        .invoke(builder);
                                Player.class
                                        .getMethod("replaceMediaItem", int.class, MediaItem.class)
                                        .invoke(player, i, replacement);
                                found = true;
                            }
                        }
                        return found;
                    });
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** A stream's request with its own URL and headers. Builder setters aren't in the host. */
    private static DataSpec spec(Object stream) throws Exception {
        DataSpec plain = new DataSpec(Uri.parse((String) field(stream, "url")));
        Object builder = DataSpec.class.getMethod("buildUpon").invoke(plain);
        Field headers = builder.getClass().getDeclaredField("httpRequestHeaders");
        headers.setAccessible(true);
        Object own = field(stream, "headers");
        if (own != null) headers.set(builder, own);
        return (DataSpec) builder.getClass().getMethod("build").invoke(builder);
    }

    @Override
    public void verify(Uri marked, Object stream, BooleanSupplier cancelled, boolean background)
            throws IOException {
        try {
            SourcePreference.verifyPrepared(
                    new DataSpec(marked),
                    spec(stream),
                    verificationSpec(stream, cancelled),
                    cancelled,
                    background);
        } catch (IOException failed) {
            throw failed;
        } catch (Exception unavailable) {
            throw new IOException("Could not ask for this copy", unavailable);
        }
    }

    @Override
    public void trust(Uri marked, Object stream) {
        try {
            SourcePreference.trustPrepared(new DataSpec(marked), spec(stream));
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @Override
    public boolean rejected(Uri marked, Object stream) {
        try {
            return SourcePreference.rejectedPrepared(new DataSpec(marked), spec(stream));
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public boolean honest(Object stream) {
        try {
            Object format = field(stream, "format");
            Object lossless = format.getClass().getMethod("isLossless").invoke(format);
            if (!Boolean.TRUE.equals(lossless)) return true;
            String mime = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            MediaExtractor extractor = new MediaExtractor();
            DataSpec checked =
                    manifest(stream) ? verificationSpec(stream, () -> false) : spec(stream);
            try (SourceAudio file = new SourceAudio(checked, deadline)) {
                MediaDataSource view = file.view();
                extractor.setDataSource(view);
                for (int i = 0; i < extractor.getTrackCount() && mime == null; i++) {
                    String found = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (found != null && found.startsWith("audio/")) mime = found;
                }
            } finally {
                extractor.release();
            }
            return mime != null
                    && Boolean.TRUE.equals(
                            call("com.music.bitchord.data.NerdStats", "isLosslessMime", mime));
        } catch (Exception unknown) {
            return false;
        }
    }

    private static Map<String, Object> forced() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map =
                (Map<String, Object>) staticField(PLAYBACK + "QualityUpgrade", "forced");
        return map;
    }

    private static String keyOf(Uri uri) throws Exception {
        return (String) call(PLAYBACK + "AudioCache", "cacheKeyOf", uri);
    }

    private static Object cache() throws Exception {
        return staticField(PLAYBACK + "AudioCache", "cache");
    }

    private static Collection<?> spans(String key) throws Exception {
        return (Collection<?>) SmartDownloadSources.call(cache(), "getCachedSpans", key);
    }

    private boolean activeKey(String key) throws Exception {
        return onPlayer(
                () -> {
                    for (String deck : new String[] {"player", "spare"}) {
                        Player player = player(service, deck);
                        MediaItem item = player == null ? null : player.getCurrentMediaItem();
                        Uri uri = item == null ? null : SourceFirst.renditionUri(item);
                        if (uri != null && key.equals(keyOf(uri))) return true;
                    }
                    return false;
                });
    }

    @Override
    public boolean claim(Uri marked, Object stream) {
        try {
            String key = keyOf(marked), identity = contentIdentity(stream);
            if (identity == null)
                identity = source(stream) + "|" + url(stream) + "|" + terms(stream);
            synchronized (claims) {
                if (identity.equals(claims.get(key))) return true;
                if (!spans(key).isEmpty()) {
                    if (activeKey(key)) return false;
                    call(PLAYBACK + "AudioCache", "discardRendition", marked);
                    if (!spans(key).isEmpty()) return false;
                }
                claims.put(key, identity);
                return true;
            }
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public String cacheIdentity(Uri marked) {
        try {
            String key = keyOf(marked);
            if (!entryComplete(key)) return null;
            List<Object> parts = new ArrayList<>(spans(key));
            parts.sort(
                    Comparator.comparingLong(
                            part -> {
                                try {
                                    return ((Number) field(part, "position")).longValue();
                                } catch (Exception e) {
                                    return Long.MAX_VALUE;
                                }
                            }));
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768];
            for (Object part : parts) {
                File file = (File) field(part, "file");
                if (file == null) return null;
                hash.update(
                        (field(part, "position") + ":" + field(part, "length") + ":")
                                .getBytes(StandardCharsets.UTF_8));
                try (InputStream input = new FileInputStream(file)) {
                    int n;
                    while ((n = input.read(buffer)) >= 0) hash.update(buffer, 0, n);
                }
            }
            StringBuilder text = new StringBuilder();
            for (byte value : hash.digest()) text.append(String.format("%02x", value & 255));
            return parts.isEmpty() ? null : text.toString();
        } catch (Exception unavailable) {
            return null;
        }
    }

    @Override
    public boolean park(String mediaId, Object stream) {
        try {
            Object held = forced().get(mediaId);
            String identity =
                    source(stream)
                            + "|"
                            + contentIdentity(stream)
                            + "|"
                            + url(stream)
                            + "|"
                            + terms(stream);
            synchronized (parkedIdentities) {
                if (held != null && !identity.equals(parkedIdentities.get(mediaId))) {
                    boolean active =
                            onPlayer(
                                    () -> {
                                        for (String deck : new String[] {"player", "spare"}) {
                                            Player player = player(service, deck);
                                            MediaItem item =
                                                    player == null
                                                            ? null
                                                            : player.getCurrentMediaItem();
                                            Uri uri =
                                                    item == null
                                                            ? null
                                                            : SourceFirst.renditionUri(item);
                                            if (uri != null
                                                    && mediaId.equals(uri.getQueryParameter("v"))
                                                    && uri.getQueryParameter("q") != null)
                                                return true;
                                        }
                                        return false;
                                    });
                    if (active) return false;
                }
                forced().put(mediaId, stream);
                parkedIdentities.put(mediaId, identity);
                return true;
            }
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public void unpark(String mediaId, Object stream) {
        try {
            if (forced().remove(mediaId, stream))
                synchronized (parkedIdentities) {
                    parkedIdentities.remove(mediaId);
                }
        } catch (Exception ignored) {
            // BitChord refuses a stream it can't find.
        }
    }

    @Override
    public String parked(String mediaId) {
        Object stream = parkedStream(mediaId);
        return stream == null ? null : url(stream);
    }

    @Override
    public Object parkedStream(String mediaId) {
        try {
            return forced().get(mediaId);
        } catch (Exception unavailable) {
            return null;
        }
    }

    @Override
    public void holdUpgrades(String mediaId, boolean hold) {
        try {
            @SuppressWarnings("unchecked")
            Set<String> refused = (Set<String>) staticField(PLAYBACK + "QualityUpgrade", "refused");
            if (hold) refused.add(mediaId);
            else refused.remove(mediaId);
        } catch (Exception ignored) {
            // BitChord may then look for another copy, and the open refuses to mix them.
        }
    }

    /** The continuation warmRange takes, which asks for a suspending lambda. */
    private static final class Step extends SuspendLambda {
        Step(Continuation<Object> completion) {
            super(1, completion);
        }

        @Override
        public Object invokeSuspend(Object result) {
            return result;
        }
    }

    @Override
    public long warm(Uri marked, long bytes) throws Exception {
        Object cache = staticField(PLAYBACK + "AudioCache", "INSTANCE");
        Method warm = null;
        for (Method method : cache.getClass().getMethods())
            if (method.getName().equals("warmRange")) warm = method;
        if (warm == null) throw new NoSuchMethodException("warmRange");
        Object stream = forced().get(marked.getQueryParameter("v"));
        if (stream == null) throw new IOException("The prepared cache source is no longer parked");
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(new DataSpec(marked), spec(stream))) {
            long began = System.nanoTime();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Object> result = new AtomicReference<>();
            Continuation<Object> completion =
                    new Continuation<Object>() {
                        @Override
                        public CoroutineContext getContext() {
                            return read.context();
                        }

                        @Override
                        public void resumeWith(Object value) {
                            result.set(value);
                            done.countDown();
                        }
                    };
            Object returned = warm.invoke(cache, marked, bytes, new Step(completion));
            if (returned == kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED) {
                if (!done.await(30, TimeUnit.SECONDS)) throw new IOException("Too slow to read");
                returned = result.get();
            }
            if (returned != null && returned.getClass().getName().equals("kotlin.Result$Failure"))
                throw new IOException("The prepared cache read did not finish");
            return (System.nanoTime() - began) / 1_000_000;
        }
    }

    @Override
    public int kbps(Object stream) {
        try {
            Object kbps = field(field(stream, "format"), "kbps");
            return kbps instanceof Number ? ((Number) kbps).intValue() : 0;
        } catch (Exception unknown) {
            return 0;
        }
    }

    @Override
    public DataSpec rewrite(DataSpec spec, Uri marked) throws Exception {
        Object builder = DataSpec.class.getMethod("buildUpon").invoke(spec);
        Field uri = builder.getClass().getDeclaredField("uri");
        uri.setAccessible(true);
        uri.set(builder, marked);
        return (DataSpec) builder.getClass().getMethod("build").invoke(builder);
    }

    @Override
    public boolean complete(Uri marked) {
        try {
            return entryComplete(keyOf(marked));
        } catch (Exception unknown) {
            return false;
        }
    }

    @Override
    public long length(Uri marked) {
        try {
            return ((Number) call(PLAYBACK + "AudioCache", "contentLengthOf", marked)).longValue();
        } catch (Exception unknown) {
            return 0;
        }
    }

    @Override
    public List<String> youtubeEntries(String mediaId, Uri marked) {
        List<String> found = new ArrayList<>();
        try {
            String better = keyOf(marked);
            Object cache = cache();
            for (Object entry :
                    new ArrayList<>((Collection<?>) SmartDownloadSources.call(cache, "getKeys"))) {
                String key = (String) entry;
                if (!key.equals(mediaId) && !key.startsWith(mediaId + "#")) continue;
                if (key.equals(better) || key.endsWith("#watch")) continue;
                if (!Boolean.TRUE.equals(call(PLAYBACK + "AudioCache", "heldByYouTube", key)))
                    continue;
                Object meta = SmartDownloadSources.call(cache, "getContentMetadata", key);
                // Analysis keeps its own copy of a song's opening. That's not this.
                if (SmartDownloadSources.call(meta, "get", "bc_playback") != null) found.add(key);
            }
        } catch (Exception unavailable) {
            found.clear();
        }
        return found;
    }

    @Override
    public boolean entryComplete(String key) {
        try {
            return Boolean.TRUE.equals(call(PLAYBACK + "AudioCache", "isComplete", key));
        } catch (Exception unknown) {
            return false;
        }
    }

    @Override
    public boolean better(Object stream, String key, Uri marked, String seconds) {
        try {
            Object format = field(stream, "format");
            if (Boolean.TRUE.equals(format.getClass().getMethod("isLossless").invoke(format)))
                return true;
            int kbps = kbps(stream);
            long duration = seconds == null ? 0 : Long.parseLong(seconds);
            String item = marked.toString();
            int mark = item.indexOf("&q=");
            Uri plain = Uri.parse(mark < 0 ? item : item.substring(0, mark));
            long bytes =
                    ((Number) call(PLAYBACK + "AudioCache", "contentLengthOf", plain)).longValue();
            return kbps > 0 && duration > 0 && bytes > 0 && kbps > bytes * 8 / duration / 1000;
        } catch (Exception unknown) {
            return false;
        }
    }

    @Override
    public boolean remove(String key) {
        try {
            if (activeKey(key)) return false;
            SmartDownloadSources.call(cache(), "removeResource", key);
            return spans(key).isEmpty();
        } catch (Exception inUse) {
            return false;
        }
    }

    @Override
    public void later(Runnable task, long millis) {
        main.postDelayed(task, millis);
    }

    @Override
    public boolean stillPlaying(String mediaId, Uri entry, long position) {
        try {
            return onPlayer(
                    () -> {
                        Player player = player(service, "player"), spare = player(service, "spare");
                        MediaItem current = player == null ? null : player.getCurrentMediaItem();
                        MediaItem other = spare == null ? null : spare.getCurrentMediaItem();
                        Uri currentUri = current == null ? null : SourceFirst.renditionUri(current);
                        return current != null
                                && mediaId.equals(current.mediaId)
                                && currentUri != null
                                && SourcePreparation.entryKey(entry)
                                        .equals(SourcePreparation.entryKey(currentUri))
                                && entry.equals(currentUri)
                                && player.isPlaying()
                                && player.getCurrentPosition() > position
                                && (other == null || !mediaId.equals(other.mediaId));
                    });
        } catch (Exception unavailable) {
            return false;
        }
    }

    private File file() {
        return new File(context.getNoBackupFilesDir(), "source-preparation.json");
    }

    @Override
    public String read() {
        try {
            File file = file();
            return file.isFile()
                    ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    : "";
        } catch (Exception unreadable) {
            return "";
        }
    }

    @Override
    public void write(String saved) {
        try {
            File file = file(), part = new File(file.getPath() + ".part");
            Files.write(part.toPath(), saved.getBytes(StandardCharsets.UTF_8));
            if (!part.renameTo(file)) part.delete();
        } catch (Exception ignored) {
            // It's checked again next time.
        }
    }

    @Override
    public void log(String line) {
        HostLog.d(line);
    }
}
