package app.bitchord.telegram;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Owns the TDLib client, authorization state and selected chats. */
public final class TelegramBridge {
    public static volatile TelegramBridge instance;
    public static volatile String startupError = "";
    private static volatile Context appContext;
    public final Context context;
    final ExecutorService work = Executors.newSingleThreadExecutor();
    final TrackNames names;
    private volatile boolean useTrackNames, suggestTrackNames;
    private volatile boolean reportQuality, showArtwork, namesFromFiles;
    final Artwork artwork;
    final FormatProbe formats;
    final LegendMemory legends;
    private volatile long lastSuggestionSearch;
    private final Map<String, Boolean> heard = new LinkedHashMap<>();
    private final ExecutorService recordWork =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "Telegram track names");
                        thread.setDaemon(true);
                        return thread;
                    });
    // At most one pending check; newer songs replace older ones while one runs.
    private final ThreadPoolExecutor suggestionWork =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(1),
                    r -> {
                        Thread thread = new Thread(r, "Telegram name suggestions");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());
    final Set<Long> selected = ConcurrentHashMap.newKeySet();
    final File home;
    JSONObject config;
    private volatile long cacheLimitBytes = CachePolicy.DEFAULT_BYTES;
    private volatile ApplicationCredentials credentials = ApplicationCredentials.empty();
    private volatile ApplicationCredentials appliedCredentials = ApplicationCredentials.empty();
    final CredentialStore credentialStore;
    volatile Client client;
    volatile long generation;
    volatile CompletableFuture<Void> clientClosed;
    volatile boolean restarting;
    final NativeAudio audio = new NativeAudio(this);
    volatile String sessionTag;
    public volatile TdApi.AuthorizationState auth;
    public volatile String status = "Starting Telegram…";
    public volatile String error = "";
    volatile boolean paramsSent;
    volatile long mediaRevision;
    final ChannelSponsorship sponsorship = new ChannelSponsorship(request -> callAny(request));
    private volatile boolean foreground;
    private final LinkedHashMap<String, Boolean> playbackRead = new LinkedHashMap<>();
    final Map<Integer, TdApi.AccentColor> accentColors = new ConcurrentHashMap<>();
    final TelegramIndex index;
    final LiveSearch remoteSearch =
            new LiveSearch(
                    (request, timeout) -> {
                        try {
                            return call(request, timeout);
                        } catch (TelegramFailure e) {
                            if (e.code == 429) {
                                java.util.regex.Matcher wait =
                                        java.util.regex.Pattern.compile(
                                                        "(?:retry after |FLOOD_WAIT_)([0-9]+)",
                                                        java.util.regex.Pattern.CASE_INSENSITIVE)
                                                .matcher(e.getMessage());
                                int seconds = 60;
                                if (wait.find())
                                    try {
                                        seconds = Integer.parseInt(wait.group(1));
                                    } catch (NumberFormatException ignored) {
                                    }
                                throw new LiveSearch.RateLimited(seconds);
                            }
                            throw e;
                        }
                    });

    private void showRateLimit(int seconds) {
        String notice = LiveSearch.rateLimitNotice(seconds);
        new Handler(Looper.getMainLooper())
                .post(
                        () -> {
                            try {
                                android.widget.Toast.makeText(
                                                context, notice, android.widget.Toast.LENGTH_LONG)
                                        .show();
                            } catch (RuntimeException ignored) {
                                // No toast, e.g. while the app is being closed.
                            }
                        });
    }

    public static synchronized void init(Context context) {
        appContext = context.getApplicationContext();
        if (instance != null || !startupError.isEmpty()) return;
        try {
            // Explicit ordering also resolves the dynamic OpenSSL and C++ dependencies.
            System.loadLibrary("c++_shared");
            System.loadLibrary("cryptox");
            System.loadLibrary("sslx");
            System.loadLibrary("tdjni");
            instance = new TelegramBridge(context.getApplicationContext());
        } catch (Throwable t) {
            startupError = "Native extension could not start: " + t;
        }
    }

    public static void openSetup() {
        Context context = appContext;
        if (context != null)
            context.startActivity(
                    new Intent(context, TelegramActivity.class)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    public static int resourceId(String name, String type) {
        Context context = appContext;
        return context == null
                ? 0
                : context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private TelegramBridge(Context context) throws Exception {
        this.context = context;
        home = new File(context.getNoBackupFilesDir(), "telegram-native");
        if (!home.isDirectory() && !home.mkdirs())
            throw new IOException("Cannot create private directory");
        credentialStore = new CredentialStore(context, home);
        remoteSearch.onRateLimited = this::showRateLimit;
        index =
                new TelegramIndex(
                        new File(home, "index"), remoteSearch::page, System::currentTimeMillis);
        names = new TrackNames(new File(home, "track-names.json"));
        artwork = new Artwork(new File(home, "artwork"));
        formats = new FormatProbe(new File(home, "formats.json"));
        formats.onChange = FormatLegend::changed;
        legends = new LegendMemory(new File(home, "legends.json"));
        try {
            if (credentialStore.exists()) credentials = credentialStore.load();
            else
                try (InputStream input =
                        context.getAssets().open("telegram-native/application.json")) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] block = new byte[1024];
                    int n;
                    while ((n = input.read(block)) != -1) bytes.write(block, 0, n);
                    JSONObject seed =
                            new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                    if (seed.optInt("apiId") > 0 && !seed.optString("apiHash").isEmpty()) {
                        credentials =
                                ApplicationCredentials.parse(
                                        Integer.toString(seed.getInt("apiId")),
                                        seed.getString("apiHash"));
                        credentialStore.save(credentials);
                    }
                }
        } catch (Exception e) {
            error =
                    "Application configuration could not be loaded. Enter it again in Telegram"
                            + " settings.";
        }
        config = readJson(new File(home, "config.json"));
        cacheLimitBytes =
                CachePolicy.validOrDefault(
                        config.optLong("audioCacheBytes", CachePolicy.DEFAULT_BYTES));
        config.remove("apiId");
        config.remove("apiHash");
        // Migrate old HTTP state; the new session marker is not an access credential.
        config.remove("token");
        config.remove("port");
        useTrackNames = config.optBoolean("useTrackNames", true);
        suggestTrackNames = config.optBoolean("suggestTrackNames", true);
        reportQuality = config.optBoolean("reportQuality", true);
        showArtwork = config.optBoolean("showArtwork", true);
        namesFromFiles = config.optBoolean("namesFromFiles", true);
        Downmix.enabled = config.optBoolean("surroundAsStereo", Downmix.enabled);
        SmoothUpgrade.enabled = config.optBoolean("smoothUpgrades", SmoothUpgrade.enabled);
        FormatLegend.enabled = config.optBoolean("formatLegend", FormatLegend.enabled);
        index.enabled = config.optBoolean("searchIndex", true);
        JSONArray ids = config.optJSONArray("chats");
        if (ids != null) for (int i = 0; i < ids.length(); i++) selected.add(ids.getLong(i));
        // Legacy catalogue files are left on disk for rollback, but are no longer loaded or
        // required.
        saveConfig();
        // No logs of phone numbers, codes, passwords or session data.
        Client.execute(new TdApi.SetLogVerbosityLevel(0));
        startClient();
        Thread indexing = new Thread(this::runIndex, "Telegram index");
        indexing.setDaemon(true);
        indexing.setPriority(Thread.MIN_PRIORITY);
        indexing.start();
        if (context instanceof Application) TelegramLifecycle.install((Application) context, this);
    }

    private void startClient() throws Exception {
        final long current = ++generation;
        remoteSearch.clear();
        sponsorship.clear(current);
        mediaRevision++;
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        clientClosed = closed;
        auth = null;
        paramsSent = false;
        appliedCredentials = ApplicationCredentials.empty();
        restarting = false;
        sessionTag = UUID.randomUUID().toString();
        registerSource();
        client =
                Client.create(
                        object -> {
                            if (object instanceof TdApi.UpdateAuthorizationState
                                    && ((TdApi.UpdateAuthorizationState) object).authorizationState
                                            instanceof TdApi.AuthorizationStateClosed)
                                closed.complete(null);
                            if (current == generation) update(object);
                        },
                        e -> error = "Telegram update failed",
                        e -> error = "Telegram callback failed");
        work.execute(
                () -> {
                    if (current == generation) configure();
                });
    }

    public ApplicationCredentials credentials() {
        return credentials;
    }

    public void saveCredentials(String id, String hash) throws Exception {
        ApplicationCredentials next = ApplicationCredentials.parse(id, hash);
        if (audio.active())
            throw new IOException("Stop playback before changing the application configuration.");
        boolean reconnect = paramsSent && !appliedCredentials.sameAs(next);
        credentialStore.save(next);
        credentials = next;
        error = "";
        if (reconnect) {
            restarting = true;
            status = "Reconnecting Telegram…";
            try {
                CompletableFuture<Void> closed = clientClosed;
                call(new TdApi.Close());
                closed.get(40, TimeUnit.SECONDS);
                startClient();
            } finally {
                restarting = false;
            }
        } else if (auth instanceof TdApi.AuthorizationStateClosed) startClient();
        else configure();
    }

    private void update(TdApi.Object obj) {
        if (obj instanceof TdApi.UpdateAuthorizationState) {
            auth = ((TdApi.UpdateAuthorizationState) obj).authorizationState;
            if (auth instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
                status =
                        credentials.configured()
                                ? "Connecting to Telegram…"
                                : "Add your application credentials to connect.";
                work.execute(this::configure);
            } else if (auth instanceof TdApi.AuthorizationStateReady) {
                status = "Telegram connected. Select music chats, then search in BitChord.";
                setForeground(foreground);
            } else if (auth instanceof TdApi.AuthorizationStateClosed) {
                status = "Telegram session closed. Restart BitChord to connect again.";
            } else status = auth.getClass().getSimpleName().replace("AuthorizationState", "");
        } else if (obj instanceof TdApi.UpdateChatHasProtectedContent
                || obj instanceof TdApi.UpdateMessageContent
                || obj instanceof TdApi.UpdateDeleteMessages) {
            mediaRevision++;
            remoteSearch.clear();
        } else if (obj instanceof TdApi.UpdateAccentColors) {
            accentColors.clear();
            TdApi.AccentColor[] colors = ((TdApi.UpdateAccentColors) obj).colors;
            if (colors != null)
                for (TdApi.AccentColor color : colors) accentColors.put(color.id, color);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private TdApi.Object callAny(TdApi.Function<?> request) throws Exception {
        return call((TdApi.Function) request);
    }

    void setForeground(boolean value) {
        foreground = value;
        Client target = client;
        if (target != null && ready())
            target.send(new TdApi.SetOption("online", new TdApi.OptionValueBoolean(value)), null);
    }

    public String url() {
        return InternalAddon.ORIGIN + sessionTag;
    }

    public boolean ready() {
        return !restarting && auth instanceof TdApi.AuthorizationStateReady;
    }

    public void configure() {
        if (client == null) return;
        if (!(auth instanceof TdApi.AuthorizationStateWaitTdlibParameters) || paramsSent) return;
        if (!credentials.configured()) {
            status = "Add your application credentials to connect.";
            return;
        }
        try {
            ApplicationCredentials applying = credentials;
            paramsSent = true;
            File db = new File(home, "db"), files = new File(home, "files");
            call(
                    new TdApi.SetTdlibParameters(
                            false,
                            db.getAbsolutePath(),
                            files.getAbsolutePath(),
                            databaseKey(),
                            true,
                            true,
                            true,
                            false,
                            applying.apiId,
                            applying.apiHash,
                            Locale.getDefault().toLanguageTag(),
                            Build.MODEL,
                            Build.VERSION.RELEASE,
                            "1.0.6"));
            appliedCredentials = applying;
            error = "";
        } catch (Exception e) {
            paramsSent = false;
            fail(e);
        }
    }

    static final class TelegramFailure extends IOException {
        final int code;

        TelegramFailure(TdApi.Error error) {
            super("Telegram " + error.code + ": " + error.message);
            code = error.code;
        }
    }

    public <T extends TdApi.Object> T call(TdApi.Function<T> fn) throws Exception {
        return call(fn, 40000);
    }

    <T extends TdApi.Object> T call(TdApi.Function<T> fn, long timeoutMillis) throws Exception {
        Client target = client;
        if (target == null) throw new IOException("Telegram is starting. Try again shortly.");
        CompletableFuture<T> future = new CompletableFuture<>();
        target.send(
                fn,
                obj -> {
                    if (obj instanceof TdApi.Error)
                        future.completeExceptionally(new TelegramFailure((TdApi.Error) obj));
                    else {
                        @SuppressWarnings("unchecked")
                        T result = (T) obj;
                        future.complete(result);
                    }
                });
        try {
            return future.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Telegram request failed.");
        }
    }

    public void submit(String text) throws Exception {
        error = "";
        if (auth instanceof TdApi.AuthorizationStateWaitPhoneNumber)
            call(new TdApi.SetAuthenticationPhoneNumber(text.trim(), null));
        else if (auth instanceof TdApi.AuthorizationStateWaitCode)
            call(new TdApi.CheckAuthenticationCode(text.trim()));
        else if (auth instanceof TdApi.AuthorizationStateWaitPassword)
            call(new TdApi.CheckAuthenticationPassword(text));
        else if (auth instanceof TdApi.AuthorizationStateWaitEmailAddress)
            call(new TdApi.SetAuthenticationEmailAddress(text.trim()));
        else if (auth instanceof TdApi.AuthorizationStateWaitEmailCode)
            call(
                    new TdApi.CheckAuthenticationEmailCode(
                            new TdApi.EmailAddressAuthenticationCode(text.trim())));
        else
            throw new IOException(
                    "This login step isn't supported here. Sign in with an existing Telegram"
                            + " account.");
    }

    public List<TdApi.Chat> chats() throws Exception {
        requireReady();
        try {
            call(new TdApi.LoadChats(new TdApi.ChatListMain(), 100));
        } catch (IOException e) {
            if (!e.getMessage().contains("404")) throw e;
        }
        long[] ids = call(new TdApi.GetChats(new TdApi.ChatListMain(), 100)).chatIds;
        List<TdApi.Chat> chats = new ArrayList<>();
        for (long id : ids) {
            TdApi.Chat chat = call(new TdApi.GetChat(id));
            if (MediaPolicy.chatAllowed(chat)) chats.add(chat);
        }
        return chats;
    }

    public void addChat(String text) throws Exception {
        requireReady();
        TdApi.Chat chat;
        if (text.trim().matches("-?[0-9]+"))
            chat = call(new TdApi.GetChat(Long.parseLong(text.trim())));
        else chat = call(new TdApi.SearchPublicChat(text.trim().replaceFirst("^@", "")));
        if (!MediaPolicy.chatAllowed(chat))
            throw new IOException(
                    "Protected content and secret chats are not supported by this cached audio"
                            + " player.");
        selected.add(chat.id);
        remoteSearch.clear();
        saveConfig();
        status = "Selected " + chat.title + ". Search in BitChord → Addons.";
    }

    public void toggle(long id, boolean checked) throws Exception {
        if (checked) selected.add(id);
        else selected.remove(id);
        remoteSearch.clear();
        saveConfig();
    }

    public void selectChats(List<TdApi.Chat> chats, boolean[] checked) throws Exception {
        for (int i = 0; i < chats.size(); i++) {
            long id = chats.get(i).id;
            if (checked[i]) selected.add(id);
            else selected.remove(id);
        }
        remoteSearch.clear();
        saveConfig();
        status = "Selected " + selected.size() + " music chats. Search in BitChord → Addons.";
    }

    public List<JSONObject> searchTracks(String query) throws Exception {
        return searchTracks(query, false);
    }

    /** As above; a background search gives way to BitChord's own lookups. */
    public List<JSONObject> searchTracks(String query, boolean background) throws Exception {
        requireReady();
        long session = generation;
        LiveSearch.Result found = search(query, session, background);
        requireReady();
        if (session != generation) throw new IOException("Telegram session changed. Search again.");
        List<JSONObject> rows = present(found.tracks);
        if (useTrackNames)
            rows =
                    names.merge(
                            rows,
                            query,
                            id -> {
                                try {
                                    return resolveTrack(id);
                                } catch (Exception e) {
                                    return null;
                                }
                            });
        if (session != generation) throw new IOException("Telegram session changed. Search again.");
        error = found.warning;
        status = "Telegram returned " + rows.size() + " tracks. No history scan is required.";
        return rows;
    }

    /**
     * A background search is answered by the index once it covers every selected chat, without
     * asking Telegram. Anything else asks Telegram, and falls back to the index when Telegram can't
     * answer: rate limited, busy or timed out.
     */
    private LiveSearch.Result search(String query, long session, boolean background)
            throws Exception {
        Set<Long> chats = new HashSet<>(selected);
        if (background) {
            List<JSONObject> saved = index.search(chats, query, true);
            if (saved != null) return new LiveSearch.Result(saved, "");
        }
        try {
            return remoteSearch.search(chats, query, session, background);
        } catch (Exception e) {
            if (background) throw e;
            List<JSONObject> saved = index.search(chats, query, false);
            if (saved == null) throw e;
            HostLog.d(
                    "Unofficial Telegram search '"
                            + query
                            + "' failed ("
                            + e.getMessage()
                            + "); answered from the saved index with "
                            + saved.size()
                            + (saved.size() == 1 ? " file" : " files"));
            return new LiveSearch.Result(saved, "");
        }
    }

    /** Builds and tops up the index a page at a time while nothing else is searching. */
    private void runIndex() {
        while (true) {
            long wait = TelegramIndex.PACE_MILLIS;
            try {
                if (!ready() || !index.enabled) wait = 5000;
                else if (!remoteSearch.idle()) wait = 1000;
                else if (!index.step(new HashSet<>(selected))) wait = 60000;
            } catch (LiveSearch.RateLimited e) {
                wait = 1000L * e.retrySeconds + 1000;
            } catch (Exception e) {
                wait = 30000;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public boolean searchIndex() {
        return index.enabled;
    }

    public String indexStatus() {
        return index.status(new HashSet<>(selected));
    }

    public void setSearchIndex(boolean on) throws Exception {
        index.enabled = on;
        if (!on) index.clear();
        config.put("searchIndex", on);
        saveConfig();
    }

    /** Rows from the selected chats, as copies, with names from file names if that's on. */
    private List<JSONObject> present(List<JSONObject> found) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject row : found)
            if (selected.contains(row.getLong("chat")))
                rows.add(
                        namesFromFiles
                                ? TrackDetails.withFileName(row)
                                : new JSONObject(row.toString()));
        return rows;
    }

    /**
     * Adds album covers and lossless format details to search results, waiting up to a second for
     * ones that aren't known yet. The rest arrive in the background for later searches.
     */
    void decorate(List<JSONObject> rows) {
        Client target = client;
        if (target == null) return;
        long started = System.currentTimeMillis();
        java.util.concurrent.CountDownLatch probes =
                formatLegend() || reportQuality
                        ? formats.probe(rows, (file, done) -> probe(target, file, done), 8)
                        : new java.util.concurrent.CountDownLatch(0);
        if (showArtwork)
            try {
                artwork.fill(
                        rows,
                        (file, done) ->
                                target.send(
                                        new TdApi.DownloadFile(file, 4, 0, 0, true),
                                        result -> {
                                            TdApi.File local =
                                                    result instanceof TdApi.File
                                                            ? (TdApi.File) result
                                                            : null;
                                            done.finished(
                                                    local != null
                                                                    && local.local != null
                                                                    && local.local
                                                                            .isDownloadingCompleted
                                                            ? local.local.path
                                                            : null);
                                        }),
                        1000);
            } catch (Exception ignored) {
                // Covers are optional; a search without them is still a good search.
            }
        FormatProbe.await(probes, 1000 - (System.currentTimeMillis() - started));
        try {
            formats.annotate(rows);
            boolean changed = false;
            for (JSONObject row : rows)
                changed |=
                        legends.put(
                                FormatLegend.TELEGRAM + row.optString("id"),
                                LegendMatch.row(row, formats.get(row.optString("id"))));
            if (changed) {
                legends.save();
                FormatLegend.changed();
            }
        } catch (Exception ignored) {
            // Details are optional.
        }
    }

    /** Downloads just the start of an audio file and hands over its path for header reading. */
    private static void probe(Client target, int file, Artwork.Done done) {
        target.send(
                new TdApi.DownloadFile(file, 2, 0, FormatProbe.HEAD_BYTES, true),
                result -> {
                    TdApi.File local = result instanceof TdApi.File ? (TdApi.File) result : null;
                    done.finished(
                            local != null
                                            && local.local != null
                                            && local.local.path != null
                                            && !local.local.path.isEmpty()
                                            && local.local.downloadedPrefixSize >= 64
                                    ? local.local.path
                                    : null);
                });
    }

    /** Reads a track's header in the background if it isn't known yet. */
    void probeLater(JSONObject row) {
        Client target = client;
        if (target != null && (formatLegend() || reportQuality))
            formats.probe(
                    java.util.Collections.singletonList(row),
                    (file, done) -> probe(target, file, done),
                    1);
    }

    public boolean formatLegend() {
        return FormatLegend.enabled;
    }

    public void setFormatLegend(boolean on) throws Exception {
        FormatLegend.enabled = on;
        config.put("formatLegend", on);
        saveConfig();
    }

    public boolean reportQuality() {
        return reportQuality;
    }

    public void setReportQuality(boolean on) throws Exception {
        reportQuality = on;
        config.put("reportQuality", on);
        saveConfig();
    }

    public boolean showArtwork() {
        return showArtwork;
    }

    public void setShowArtwork(boolean on) throws Exception {
        showArtwork = on;
        config.put("showArtwork", on);
        saveConfig();
        if (!on) artwork.clear();
    }

    public boolean surroundAsStereo() {
        return Downmix.enabled;
    }

    public void setSurroundAsStereo(boolean on) throws Exception {
        Downmix.enabled = on;
        config.put("surroundAsStereo", on);
        saveConfig();
    }

    public boolean smoothUpgrades() {
        return SmoothUpgrade.enabled;
    }

    public void setSmoothUpgrades(boolean on) throws Exception {
        SmoothUpgrade.enabled = on;
        config.put("smoothUpgrades", on);
        saveConfig();
    }

    public boolean namesFromFiles() {
        return namesFromFiles;
    }

    public void setNamesFromFiles(boolean on) throws Exception {
        namesFromFiles = on;
        config.put("namesFromFiles", on);
        saveConfig();
    }

    public boolean useTrackNames() {
        return useTrackNames;
    }

    public void setUseTrackNames(boolean on) throws Exception {
        useTrackNames = on;
        config.put("useTrackNames", on);
        saveConfig();
    }

    public boolean suggestTrackNames() {
        return suggestTrackNames;
    }

    public void setSuggestTrackNames(boolean on) throws Exception {
        suggestTrackNames = on;
        config.put("suggestTrackNames", on);
        saveConfig();
    }

    /**
     * A song started playing from a source other than Telegram. Looks in the selected chats for a
     * file by the same artist whose title is a near miss, and records it as a suggestion. Runs in
     * the background, once per song per session, at most every 20 seconds.
     */
    void heard(String title, String artist, long durationMs, String mediaId) {
        if (useTrackNames || suggestTrackNames)
            recordWork.execute(
                    () -> {
                        try {
                            names.played(mediaId, title, artist, (int) (durationMs / 1000));
                        } catch (Exception ignored) {
                            // Only used to find a saved copy later; losing one entry is harmless.
                        }
                    });
        String key = TrackNames.clean(title) + "|" + TrackNames.clean(artist);
        if (!suggestTrackNames
                || !ready()
                || selected.isEmpty()
                || key.startsWith("|")
                || key.endsWith("|")) return;
        synchronized (heard) {
            if (heard.put(key, true) != null) return;
            while (heard.size() > 256) heard.remove(heard.keySet().iterator().next());
        }
        final long session = generation;
        suggestionWork.execute(
                () -> {
                    try {
                        long wait = lastSuggestionSearch + 20000 - System.currentTimeMillis();
                        if (wait > 0) Thread.sleep(wait);
                        if (session != generation || !ready() || !suggestTrackNames) return;
                        lastSuggestionSearch = System.currentTimeMillis();
                        LiveSearch.Result found =
                                search(TrackNames.primaryArtist(artist), session, true);
                        if (session != generation) return;
                        List<JSONObject> rows = present(found.tracks);
                        int added = names.consider(title, artist, (int) (durationMs / 1000), rows);
                        if (added > 0)
                            status =
                                    "A Telegram file may be misnamed. Review it under Track"
                                            + " names.";
                    } catch (Exception ignored) {
                        // A suggestion is optional; a failed or rate-limited search just skips it.
                    }
                });
    }

    /** Saves a corrected name and removes BitChord's saved copy of that song, if any. */
    public void rename(JSONObject row, String title, String artist) throws Exception {
        names.correct(row, title, artist);
        forgetSavedCopies(title, artist);
    }

    public void acceptSuggestion(String id) throws Exception {
        for (JSONObject suggestion : names.suggestions())
            if (id.equals(suggestion.optString("id"))) {
                names.accept(id);
                forgetSavedCopies(suggestion.optString("title"), suggestion.optString("artist"));
                return;
            }
    }

    private void forgetSavedCopies(String title, String artist) {
        if (!useTrackNames) return;
        int removed = SavedCopies.forget(names.playedAs(title, artist));
        status =
                removed > 0
                        ? "Saved “"
                                + TrackNames.clean(title)
                                + "”. Removed BitChord’s saved copy;"
                                + " play it again to use your Telegram file."
                        : "Saved “" + TrackNames.clean(title) + "”.";
    }

    public JSONObject resolveTrack(String id) throws Exception {
        requireReady();
        long session = generation;
        JSONObject row =
                TrackMetadata.resolve(
                        id,
                        selected,
                        (chat, message) -> {
                            TdApi.Chat source = call(new TdApi.GetChat(chat), 15000);
                            if (!MediaPolicy.chatAllowed(source))
                                throw new IOException(
                                        "Telegram does not allow this channel's content to be saved"
                                                + " by this player.");
                            TdApi.Message found = call(new TdApi.GetMessage(chat, message), 15000);
                            if (!MediaPolicy.savable(found)) return null;
                            TdApi.MessageProperties properties =
                                    call(new TdApi.GetMessageProperties(chat, message), 15000);
                            if (properties == null
                                    || !properties.canBeSaved
                                    || properties.hasProtectedContentByCurrentUser
                                    || properties.hasProtectedContentByOtherUser)
                                throw new IOException(
                                        "Telegram does not allow this message to be saved by this"
                                                + " player.");
                            return found;
                        });
        requireReady();
        if (session != generation) throw new IOException("Telegram session changed. Try again.");
        return row;
    }

    void verifyMedia(long chat, long message, int file) throws Exception {
        JSONObject row = resolveTrack(chat + "_" + message);
        if (row == null || row.getInt("file") != file)
            throw new IOException("Telegram media changed or is no longer accessible.");
    }

    void played(String id) {
        final long session = generation;
        work.submit(
                () -> {
                    try {
                        if (session != generation) return;
                        String key = session + "/" + id;
                        if (playbackRead.containsKey(key)) return;
                        JSONObject row = resolveTrack(id);
                        if (row == null || session != generation) return;
                        call(
                                new TdApi.ViewMessages(
                                        row.getLong("chat"),
                                        new long[] {row.getLong("message")},
                                        new TdApi.MessageSourceOther(),
                                        true));
                        if (session != generation) return;
                        playbackRead.put(key, true);
                        while (playbackRead.size() > 256)
                            playbackRead.remove(playbackRead.keySet().iterator().next());
                    } catch (Exception e) {
                        if (session == generation)
                            error =
                                    "Telegram could not acknowledge played audio. Reopen the track"
                                            + " to retry.";
                    }
                });
    }

    public TdApi.Chat channel(long id) throws Exception {
        requireReady();
        if (!selected.contains(id)) throw new IOException("Choose this music chat first.");
        TdApi.Chat chat = call(new TdApi.GetChat(id));
        if (!MediaPolicy.chatAllowed(chat))
            throw new IOException("Protected content and secret chats are not supported here.");
        if (!(chat.type instanceof TdApi.ChatTypeSupergroup)
                || !((TdApi.ChatTypeSupergroup) chat.type).isChannel)
            throw new IOException(
                    "Channel View supports Telegram channels. Open other chats in Telegram.");
        return chat;
    }

    public boolean hasSponsoredMessages(TdApi.Chat chat) throws Exception {
        if (chat.type instanceof TdApi.ChatTypeSupergroup)
            return ((TdApi.ChatTypeSupergroup) chat.type).isChannel;
        if (chat.type instanceof TdApi.ChatTypePrivate) {
            TdApi.User user = call(new TdApi.GetUser(((TdApi.ChatTypePrivate) chat.type).userId));
            return user.type instanceof TdApi.UserTypeBot;
        }
        return false;
    }

    public void openChannel(long chat) throws Exception {
        channel(chat);
        call(new TdApi.OpenChat(chat));
    }

    public void closeChannel(long chat) {
        Client current = client;
        if (current != null) current.send(new TdApi.CloseChat(chat), null);
    }

    public List<TdApi.Message> channelHistory(long chat, long before) throws Exception {
        channel(chat);
        TdApi.Messages messages = call(new TdApi.GetChatHistory(chat, before, 0, 30, false), 15000);
        List<TdApi.Message> result = new ArrayList<>();
        if (messages.messages != null)
            for (TdApi.Message message : messages.messages)
                if (message != null && message.chatId == chat) result.add(message);
        result.sort(Comparator.comparingLong(message -> message.id));
        return result;
    }

    public void viewedMessages(long chat, long[] ids, long session) throws Exception {
        if (session != generation) throw new IOException("Telegram account changed");
        channel(chat);
        call(new TdApi.ViewMessages(chat, ids, new TdApi.MessageSourceChatHistory(), false));
    }

    public String messageLink(long chat, long message) throws Exception {
        channel(chat);
        TdApi.GetMessageLink request = new TdApi.GetMessageLink();
        request.chatId = chat;
        request.messageId = message;
        request.pollOptionId = "";
        return call(request).link;
    }

    public String downloadSponsorFile(int file) throws Exception {
        requireReady();
        long session = generation;
        TdApi.File result = call(new TdApi.DownloadFile(file, 16, 0, 0, true), 40000);
        if (session != generation
                || result.local == null
                || !result.local.isDownloadingCompleted
                || result.local.path == null
                || !new File(result.local.path).isFile())
            throw new IOException("Sponsored media could not be fully downloaded");
        return result.local.path;
    }

    void releaseSponsorFile(int file) {
        Client target = client;
        if (target != null) {
            target.send(new TdApi.CancelDownloadFile(file, false), null);
            target.send(new TdApi.DeleteFile(file), null);
        }
    }

    int sponsorColor(int id, boolean dark) {
        TdApi.AccentColor color = accentColors.get(id);
        int[] colors = color == null ? null : dark ? color.darkThemeColors : color.lightThemeColors;
        if (colors != null && colors.length > 0) return 0xff000000 | colors[0];
        int[] defaults = {
            0xffcf4d55, 0xffcb7532, 0xff8969c2, 0xff438d59, 0xff258ca0, 0xff3b7bd2, 0xffc7619b
        };
        return defaults[Math.floorMod(id, 7)];
    }

    public void registerSource() throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) NativeSource.register(url());
        else {
            FutureTask<Boolean> registration = new FutureTask<>(() -> NativeSource.register(url()));
            new Handler(Looper.getMainLooper()).post(registration);
            registration.get(15, TimeUnit.SECONDS);
        }
        config.put("registered", true);
        saveConfig();
        status =
                ready()
                        ? "Telegram source ready. Open BitChord → Search → Addons."
                        : "Telegram source added. Sign in to choose music chats.";
    }

    public long cacheLimitBytes() {
        return cacheLimitBytes;
    }

    public void setCacheLimitBytes(long bytes) throws Exception {
        CachePolicy.requireValid(bytes);
        requireReady();
        synchronized (this) {
            long previous = cacheLimitBytes;
            config.put("audioCacheBytes", bytes);
            try {
                saveConfig();
            } catch (Exception e) {
                config.put("audioCacheBytes", previous);
                throw e;
            }
            cacheLimitBytes = bytes;
        }
        boolean applied = audio.applyCacheLimit();
        status =
                "Telegram cache target saved: "
                        + CachePolicy.label(bytes)
                        + (applied
                                ? "."
                                : ". Cleanup will apply before the next playback session.");
    }

    public void clearCache() throws Exception {
        audio.clearCache();
        status = "Telegram audio cache cleared.";
    }

    void cancelDownload(int id) {
        client.send(new TdApi.CancelDownloadFile(id, false), null);
    }

    void optimize(long bytes) throws Exception {
        call(
                new TdApi.OptimizeStorage(
                        bytes,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        0,
                        new TdApi.FileType[] {
                            new TdApi.FileTypeAudio(), new TdApi.FileTypeDocument()
                        },
                        new long[0],
                        new long[0],
                        false,
                        0));
    }

    public void logout() throws Exception {
        if (audio.active()) throw new IOException("Stop playback before signing out");
        call(new TdApi.LogOut());
        auth = null;
        selected.clear();
        remoteSearch.clear();
        index.clear();
        names.clear();
        artwork.clear();
        formats.clear();
        legends.clear();
        synchronized (heard) {
            heard.clear();
        }
        sponsorship.clear(++generation);
        mediaRevision++;
        saveConfig();
    }

    void requireReady() throws IOException {
        if (!ready()) throw new IOException("Sign in to Telegram first");
    }

    public void fail(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String secret = credentials.apiHash;
        error = secret.isEmpty() ? message : message.replace(secret, "[hidden]");
    }

    synchronized void saveConfig() throws Exception {
        config.put("chats", new JSONArray(selected));
        writeJson(new File(home, "config.json"), config);
    }

    static JSONObject readJson(File file) throws Exception {
        if (!file.exists()) return new JSONObject();
        return new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
    }

    static void writeJson(File file, JSONObject json) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (Exception e) {
            atomic.failWrite(out);
            throw e;
        }
    }

    byte[] databaseKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        String alias = context.getPackageName() + ".telegram.database";
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
            generator.generateKey();
        }
        javax.crypto.SecretKey key = (javax.crypto.SecretKey) store.getKey(alias, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        if (config.has("wrappedKey")) {
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            128, Base64.decode(config.getString("keyIv"), Base64.NO_WRAP)));
            return cipher.doFinal(Base64.decode(config.getString("wrappedKey"), Base64.NO_WRAP));
        }
        if (new File(home, "db").exists())
            throw new IOException(
                    "Session key missing; sign out/reset app data before creating a new session");
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        config.put("wrappedKey", Base64.encodeToString(cipher.doFinal(raw), Base64.NO_WRAP));
        config.put("keyIv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        saveConfig();
        return raw;
    }
}
