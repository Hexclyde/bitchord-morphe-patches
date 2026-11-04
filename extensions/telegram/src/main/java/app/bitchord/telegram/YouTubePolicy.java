package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Turns YouTube Music off from the Sources screen. The YouTube row's own enabled flag stays the
 * only on/off setting; this stores the selected disable mode.
 *
 * <p>Playback only stops YouTube audio and leaves search, browsing and recording checks working.
 * Full disable also stops YouTube's search, browse and visitor requests. Songs already saved as
 * files and copies from other sources play as before.
 */
public final class YouTubePolicy {
    private YouTubePolicy() {}

    public static final String PLAYBACK_ONLY = "playback";
    public static final String FULL = "full";

    static final String PREFERENCES = "youtube_controls";
    static final String MODE = "mode";
    private static final String SOURCES = "com.music.bitchord.data.sources.";
    private static final long LOG_GAP_MS = 5_000L;
    private static final long LOAD_WAIT_MS = 5_000L;

    /** The host's YouTube Music row and the calls that change it. */
    interface Registry {
        /** The row's id and flag, or null when the host has no YouTube Music row. */
        Row youtube() throws Exception;

        void setEnabled(String id, boolean on) throws Exception;

        /** Where the cache recorded the bytes behind this request from, or null if it did not. */
        String origin(DataSpec spec);

        /** Who filled the rendition this item plays from, or null if that is not proven. */
        String active(MediaItem item);

        /** Forgets remembered YouTube stream choices; copies from other sources stay chosen. */
        void dropChoices();

        /** Where the cache recorded the bytes under this key from, or null if it did not. */
        default String keyOrigin(String key) {
            return null;
        }
    }

    static final class Row {
        final String id;
        final boolean enabled;

        Row(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }
    }

    private static final List<Watch> players = new ArrayList<>();
    private static volatile boolean loadedOnce;
    private static volatile int epoch;
    private static volatile Registry registry = new Host();
    private static volatile SharedPreferences preferences;
    private static volatile WeakReference<Activity> top = new WeakReference<>(null);
    private static volatile Application watching;
    private static volatile AlertDialog showing;
    private static final ThreadLocal<Boolean> proceed = new ThreadLocal<>();
    private static final long[] lastLogged = new long[2];
    private static volatile CountDownLatch loading = new CountDownLatch(0);

    /** Opens the saved choice and starts following the visible screen. Safe to call twice. */
    public static void init(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        if (preferences == null)
            preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        Strings.init(app);
        if (loading.getCount() == 0 && !loadedOnce) loading = new CountDownLatch(1);
        if (app instanceof Application && watching != app) {
            watching = (Application) app;
            watching.registerActivityLifecycleCallbacks(new Screens());
        }
    }

    /** Playback-only mode is the default disable mode. */
    public static String mode() {
        SharedPreferences saved = preferences;
        String mode = saved == null ? null : saved.getString(MODE, null);
        return FULL.equals(mode) ? FULL : PLAYBACK_ONLY;
    }

    /** True while YouTube Music is off, so nothing may play YouTube audio. */
    public static boolean playbackBlocked() {
        return !enabled(true);
    }

    /** True once BitChord has read its saved sources, so the on/off flag can be believed. */
    static boolean ready() {
        return loadedOnce;
    }

    /** The id of the host's YouTube Music row, or null when it has none. */
    static String youtubeId() {
        try {
            Row row = registry.youtube();
            return row == null ? null : row.id;
        } catch (Throwable e) {
            return null;
        }
    }

    static Application application() {
        return watching;
    }

    /**
     * True when a key that was chosen while YouTube Music was on now only leads to YouTube's bytes,
     * or to nothing that was recorded, so a fresh key must be chosen.
     */
    static boolean refusesKey(String key) {
        if (!loadedOnce || !playbackBlocked()) return false;
        String origin = registry.keyOrigin(key);
        return origin == null || "YOUTUBE".equals(origin);
    }

    /** True while YouTube Music is fully off, so nothing may ask YouTube for songs or lists. */
    public static boolean catalogueBlocked() {
        return !enabled(false) && FULL.equals(mode());
    }

    /** For callers that start YouTube audio themselves, such as a video switch. */
    public static void playbackGate() throws IOException {
        if (playbackBlocked()) throw refused(0, "YouTube playback stopped: YouTube Music is off");
    }

    /**
     * Called once BitChord has read its saved sources. Until then the YouTube row is not there, and
     * a request that arrives first waits for it instead of reading an empty list as "on".
     */
    public static void loaded() {
        loadedOnce = true;
        loading.countDown();
        sourcesChanged();
    }

    /**
     * Replaces the open above BitChord's cache. Everything audible passes through here, so the
     * check is on the bytes: the address actually being read, and for a cached copy that still
     * carries only the logical watch address, what the cache recorded about where it came from.
     * Saved files, content files and copies from other sources open as before, whatever track id
     * their request names.
     */
    public static long open(DataSource source, DataSpec spec) throws IOException {
        if (!playbackBlocked()) return source.open(spec);
        // Decided before the cache and YouTube's resolver are asked, so no network is needed.
        if (spec.uri != null && "watch".equals(spec.uri.getAuthority()))
            SourceFallback.beforeOpen(spec, registry.origin(spec));
        long length = source.open(spec);
        boolean youtube;
        try {
            youtube = fromYouTube(source.getUri(), spec);
        } catch (RuntimeException e) {
            youtube = true;
        }
        if (youtube) {
            try {
                source.close();
            } catch (IOException | RuntimeException ignored) {
                // The stream is being refused either way.
            }
            throw refused(0, "YouTube playback stopped: YouTube Music is off");
        }
        return length;
    }

    /** Whether the opened stream is YouTube's, or cannot be shown to be anyone else's. */
    static boolean fromYouTube(Uri actual, DataSpec spec) {
        if (actual == null)
            return !"file".equals(scheme(spec.uri)) && !"content".equals(scheme(spec.uri));
        String host = actual.getHost() == null ? "" : actual.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("youtube.com")
                || host.endsWith(".youtube.com")
                || host.equals("youtu.be")
                || host.endsWith(".googlevideo.com")) return true;
        if (!"bitchord".equals(actual.getScheme()) || !"watch".equals(actual.getAuthority()))
            return false;
        // Still the logical address, so the cache's own record is the only evidence.
        String origin = registry.origin(spec);
        return origin == null || "YOUTUBE".equals(origin);
    }

    /**
     * Called with each player BitChord builds. Only a weak reference is kept, and the listener
     * added to the player refers to it weakly too, so nothing here keeps a player alive.
     */
    public static void observe(Player player) {
        if (player == null) return;
        Looper home = player.getApplicationLooper();
        if (Looper.myLooper() == home) watch(player);
        else new Handler(home).post(() -> watch(player));
    }

    private static void watch(Player player) {
        Watch watch;
        synchronized (players) {
            for (int i = players.size() - 1; i >= 0; i--) {
                Player known = players.get(i).get();
                if (known == null) players.remove(i);
                else if (known == player) return;
            }
            watch = new Watch(player);
            players.add(watch);
        }
        player.addListener(new Guard(watch));
    }

    /**
     * Called after BitChord publishes its sources. Each watched player is checked on its own
     * thread, so a track that was opened while YouTube was on stops once it is off. Lookups for
     * other sources that are still running belong to the old settings and end.
     */
    public static void sourcesChanged() {
        epoch++;
        SourceFallback.forget();
        if (!loadedOnce || !playbackBlocked()) return;
        for (Watch watch : watchers()) {
            Player player = watch.get();
            if (player == null) continue;
            if (Looper.myLooper() == watch.home) stopYouTube(player);
            else new Handler(watch.home).post(() -> stopYouTube(watch.get()));
        }
    }

    /** A copy on the phone became known; every player re-checks its current song. */
    static void recheck() {
        for (Watch watch : watchers())
            new Handler(watch.home).post(() -> swapCurrent(watch, watch.get(), null));
    }

    private static List<Watch> watchers() {
        synchronized (players) {
            return new ArrayList<>(players);
        }
    }

    private static Watch watchOf(Player player) {
        for (Watch watch : watchers()) if (watch.get() == player) return watch;
        return null;
    }

    /**
     * Pauses [player] when it is about to play, or is playing, YouTube bytes while YouTube is off.
     */
    static void stopYouTube(Player player) {
        Watch watch = watchOf(player);
        if (watch != null) check(watch, player, false);
    }

    /** Whether the track that is playing came from YouTube, judged by the rendition, not its id. */
    static boolean fromYouTube(MediaItem item) {
        Uri uri = uriOf(item);
        if (uri == null) return false;
        String scheme = scheme(uri);
        if ("file".equals(scheme) || "content".equals(scheme)) return false;
        if ("bitchord".equals(scheme)) {
            String origin = registry.active(item);
            return origin == null || "YOUTUBE".equals(origin);
        }
        return fromYouTube(uri, new DataSpec(uri));
    }

    private static Uri uriOf(MediaItem item) {
        return item == null || item.localConfiguration == null ? null : item.localConfiguration.uri;
    }

    private static boolean isWatch(MediaItem item) {
        Uri uri = uriOf(item);
        return uri != null
                && "bitchord".equals(uri.getScheme())
                && "watch".equals(uri.getAuthority());
    }

    /** A watched player and what the policy has done to it; touched on the player's thread. */
    private static final class Watch extends WeakReference<Player> {
        final Looper home;
        int seen;
        String retried, warmed, toasted;
        MediaItem original;
        String file;

        Watch(Player player) {
            super(player);
            home = player.getApplicationLooper();
        }
    }

    private static String selection(Player player, MediaItem item) {
        return player.getCurrentMediaItemIndex()
                + "|"
                + SourceFallback.mediaId(item)
                + "|"
                + SourceFallback.entry(item);
    }

    /**
     * Decides what the current song needs while YouTube Music is off. A saved or library file
     * replaces a watch address. A rendition that is already buffered from YouTube is dropped once
     * and opened again, which asks the other sources. With [play] false the player is only paused.
     */
    private static void check(Watch watch, Player player, boolean play) {
        if (!loadedOnce || player == null || !playbackBlocked()) return;
        if (watch.seen != epoch) {
            watch.seen = epoch;
            watch.retried = watch.warmed = watch.toasted = null;
        }
        MediaItem item = player.getCurrentMediaItem();
        if (item == null) return;
        if (play && player.getPlayWhenReady()) {
            // A deck that failed while paused raised its error then; answer it now it is wanted.
            PlaybackException failed = player.getPlayerError();
            if (failed != null) {
                onError(watch, player, failed);
                return;
            }
        }
        boolean youtube;
        try {
            youtube = fromYouTube(item);
            if (isWatch(item)) {
                String file = SourceFallback.cached(item);
                if (file != null && swap(watch, player, item, file)) return;
                String key = selection(player, item);
                if (youtube && (play || player.getPlayWhenReady()) && !key.equals(watch.warmed)) {
                    watch.warmed = key;
                    SourceFallback.warm(
                            item, () -> post(watch, () -> swapCurrent(watch, player, key)), null);
                }
            }
        } catch (RuntimeException e) {
            // Not showing whose bytes these are counts as YouTube's.
            youtube = true;
        }
        if (!youtube) return;
        if (!play) {
            if (player.getPlayWhenReady()) {
                player.pause();
                refused(0, "YouTube playback paused: YouTube Music is off");
            }
            return;
        }
        // An item that has not opened yet is gated at the open; only buffered audio is a leak.
        if (!player.getPlayWhenReady()) return;
        if (player.getPlaybackState() != Player.STATE_READY) return;
        retry(watch, player, item);
    }

    /** Drops the buffered rendition and opens the song again, once per selection. */
    private static void retry(Watch watch, Player player, MediaItem item) {
        String key = selection(player, item);
        if (key.equals(watch.retried)) return;
        watch.retried = key;
        boolean was = player.getPlayWhenReady();
        player.pause();
        player.stop();
        player.prepare();
        if (was) player.play();
    }

    private static void post(Watch watch, Runnable action) {
        new Handler(watch.home).post(action);
    }

    /** Swaps in a file found in the background if the player is still on the same song. */
    private static void swapCurrent(Watch watch, Player player, String key) {
        if (player == null || !loadedOnce || !playbackBlocked()) return;
        MediaItem item = player.getCurrentMediaItem();
        if (!isWatch(item) || (key != null && !key.equals(selection(player, item)))) return;
        String file = SourceFallback.cached(item);
        if (file != null) swap(watch, player, item, file);
    }

    /**
     * Puts a file in for a watch address the way BitChord does when a download goes missing: the
     * same entry in the same place, then back to where it was.
     */
    private static boolean swap(Watch watch, Player player, MediaItem item, String file) {
        MediaItem replacement = withUri(item, file);
        if (replacement == null) return false;
        int index = player.getCurrentMediaItemIndex();
        long position = player.getCurrentPosition();
        watch.original = item;
        watch.file = file;
        if (AudioOrigin.savedSource(item.mediaId, file) == null)
            AudioOrigin.recordSaved(
                    item.mediaId,
                    file,
                    new AudioOrigin.Source(
                            "local",
                            Strings.t("On device"),
                            "LOCAL",
                            "local-file",
                            null,
                            null,
                            null,
                            null));
        player.replaceMediaItem(index, replacement);
        player.seekTo(index, position);
        player.prepare();
        return true;
    }

    private static MediaItem withUri(MediaItem item, String file) {
        try {
            Object builder = MediaItem.class.getMethod("buildUpon").invoke(item);
            // The release host's setter returns void. Never chain its reflection result.
            builder.getClass().getMethod("setUri", String.class).invoke(builder, file);
            Field mime = builder.getClass().getDeclaredField("mimeType");
            mime.setAccessible(true);
            mime.set(builder, null);
            Object metadata = item.mediaMetadata;
            Object values = metadata.getClass().getMethod("buildUpon").invoke(metadata);
            Field extras = values.getClass().getDeclaredField("extras");
            extras.setAccessible(true);
            Bundle copied =
                    item.mediaMetadata.extras == null
                            ? new Bundle()
                            : new Bundle(item.mediaMetadata.extras);
            copied.putString("bitchord.localUri", file);
            Uri uri = Uri.parse(file);
            if ("file".equals(uri.getScheme()))
                copied.putString("bitchord.localPath", uri.getPath());
            else copied.remove("bitchord.localPath");
            extras.set(values, copied);
            java.lang.reflect.Constructor<?> make =
                    metadata.getClass().getDeclaredConstructor(values.getClass());
            make.setAccessible(true);
            Field field = builder.getClass().getDeclaredField("mediaMetadata");
            field.setAccessible(true);
            field.set(builder, make.newInstance(values));
            return (MediaItem) builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            HostLog.d("Saved file could not be put in: " + unavailable.getClass().getSimpleName());
            return null;
        }
    }

    /** The file that was put in cannot be read, so the song goes back to being asked for. */
    private static void restore(Watch watch, Player player, String id) {
        SourceFallback.reject(id, watch.file);
        MediaItem original = watch.original;
        watch.original = null;
        watch.file = null;
        int index = player.getCurrentMediaItemIndex();
        long position = player.getCurrentPosition();
        player.replaceMediaItem(index, original);
        player.seekTo(index, position);
        player.prepare();
    }

    private static void onError(Watch watch, Player player, Throwable error) {
        if (!loadedOnce || !playbackBlocked()) return;
        MediaItem item = player.getCurrentMediaItem();
        if (item == null) return;
        String id = SourceFallback.mediaId(item), now = selection(player, item);
        Uri uri = uriOf(item);
        SourceFallback.LocalMatch local = SourceFallback.localMatch(error);
        if (local != null) {
            // The old watch loader found a file; it did not fail while reading that file.
            if (!local.mediaId.equals(id)) return;
            if (isWatch(item)) swap(watch, player, item, local.uri);
            else if (uri != null && uri.toString().equals(local.uri)) player.prepare();
            return;
        }
        if (watch.original != null && uri != null && uri.toString().equals(watch.file)) {
            restore(watch, player, id);
            return;
        }
        SourceFallback.NoAudio none = SourceFallback.noAudio(error);
        if (none != null) {
            if (none.mediaId.equals(id)) toast(watch, player, now);
            return;
        }
        if (!wasRefused(error) || !isWatch(item) || !player.getPlayWhenReady()) return;
        // Other sources have not been asked yet; the open does that.
        if (SourceFallback.hasAlternates() && !now.equals(watch.retried)) {
            retry(watch, player, item);
            return;
        }
        SourceFallback.warm(
                item,
                () -> post(watch, () -> swapCurrent(watch, player, now)),
                () -> post(watch, () -> toast(watch, player, now)));
    }

    private static boolean wasRefused(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof Refused) return true;
        return false;
    }

    /** Says once per song that nothing has it. Only the audible song counts. */
    private static void toast(Watch watch, Player player, String key) {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null
                || !playbackBlocked()
                || !player.getPlayWhenReady()
                || !key.equals(selection(player, item))
                || key.equals(watch.toasted)) return;
        watch.toasted = key;
        Application app = watching;
        if (app == null) return;
        int posted = epoch;
        new Handler(Looper.getMainLooper())
                .post(
                        () -> {
                            // The song, the play intent and the source settings can change
                            // before this runs.
                            Player now = watch.get();
                            if (epoch != posted || now == null) return;
                            if (Looper.myLooper() == watch.home) {
                                MediaItem current = now.getCurrentMediaItem();
                                if (current == null
                                        || !playbackBlocked()
                                        || !now.getPlayWhenReady()
                                        || !key.equals(selection(now, current))) return;
                            }
                            Toast.makeText(
                                            app,
                                            Strings.t("No audio track found"),
                                            Toast.LENGTH_SHORT)
                                    .show();
                        });
    }

    /**
     * Re-checks a player when playing is asked for or the track changes. R8 reordered these
     * arguments in BitChord's Media3, so both orders are answered; they are not marked @Override
     * because only one of them exists in any given Media3.
     */
    private static final class Guard implements Player.Listener {
        private final Watch watch;

        Guard(Watch watch) {
            this.watch = watch;
        }

        private void now(boolean play) {
            Player player = watch.get();
            if (player != null) check(watch, player, play);
        }

        private void moved() {
            watch.retried = watch.warmed = watch.toasted = null;
            now(true);
        }

        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (playWhenReady) now(true);
        }

        public void onPlayWhenReadyChanged(int reason, boolean playWhenReady) {
            if (playWhenReady) now(true);
        }

        public void onMediaItemTransition(MediaItem item, int reason) {
            moved();
        }

        public void onMediaItemTransition(int reason, MediaItem item) {
            moved();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying) now(true);
        }

        public void onPlayerError(PlaybackException error) {
            Player player = watch.get();
            if (player != null) onError(watch, player, error);
        }
    }

    /** Players being watched, for tests. */
    static int watched() {
        synchronized (players) {
            players.removeIf(ref -> ref.get() == null);
            return players.size();
        }
    }

    private static String scheme(Uri uri) {
        return uri == null ? null : uri.getScheme();
    }

    /** Called where the host sends a YouTube search, browse or visitor request. */
    public static void catalogueGate() throws IOException {
        if (catalogueBlocked()) throw refused(1, "YouTube request stopped: YouTube Music is off");
    }

    /**
     * Called when the Sources switch is turned off for YouTube Music. Asks which way to turn it off
     * and returns false; the host leaves the row alone. After a choice, the same call comes back
     * with the pick recorded and returns true, so the host switches the row off and saves it the
     * way it does for any source.
     */
    public static boolean confirmDisable(String id) {
        if (Boolean.TRUE.equals(proceed.get())) return true;
        try {
            Activity activity = top.get();
            if (activity == null || activity.isFinishing() || showing != null) return false;
            Handler main = new Handler(Looper.getMainLooper());
            if (Looper.myLooper() == Looper.getMainLooper()) ask(activity, id);
            else main.post(() -> ask(activity, id));
        } catch (Throwable e) {
            HostLog.d("YouTube Music choice failed: " + e.getClass().getSimpleName());
        }
        return false;
    }

    private static void ask(Activity activity, String id) {
        if (activity.isFinishing() || showing != null) return;
        Strings.init(activity);
        TelegramStyle style = new TelegramStyle(activity);
        int[] picked = {FULL.equals(mode()) ? 1 : 0};
        String[] items = {
            Strings.t("Playback only")
                    + "\n"
                    + Strings.t("YouTube audio stops. Search and your library keep working."),
            Strings.t("Full disable")
                    + "\n"
                    + Strings.t("YouTube audio, search and browsing all stop.")
        };
        AlertDialog dialog =
                new AlertDialog.Builder(
                                activity,
                                style.dark
                                        ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                                        : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setTitle(Strings.t("Turn off YouTube Music?"))
                        .setSingleChoiceItems(items, picked[0], (d, which) -> picked[0] = which)
                        .setPositiveButton(
                                Strings.t("Turn off"),
                                (d, which) -> disable(id, picked[0] == 1 ? FULL : PLAYBACK_ONLY))
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOwnerActivity(activity);
        dialog.setOnDismissListener(d -> showing = null);
        showing = dialog;
        dialog.show();
    }

    /** Saves the pick, then switches the row off through the host so it saves it too. */
    static void disable(String id, String mode) {
        SharedPreferences saved = preferences;
        if (saved == null) return;
        saved.edit().putString(MODE, mode).commit();
        proceed.set(Boolean.TRUE);
        try {
            registry.setEnabled(id, false);
        } catch (Throwable e) {
            HostLog.d("YouTube Music could not be turned off: " + e.getClass().getSimpleName());
            return;
        } finally {
            proceed.remove();
        }
        registry.dropChoices();
        HostLog.d(FULL.equals(mode) ? "YouTube Music off: full" : "YouTube Music off: playback");
    }

    /**
     * True unless the host says YouTube Music is off. A request that arrives before the saved
     * sources are read waits for them (not on the main thread); if they never arrive, audio stays
     * closed and lists stay open. An unreadable row never blocks anything.
     */
    private static boolean enabled(boolean audible) {
        CountDownLatch wait = loading;
        if (wait.getCount() > 0) {
            boolean done = false;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                try {
                    done = wait.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!done) return !audible;
        }
        try {
            Row row = registry.youtube();
            return row == null || row.enabled;
        } catch (Throwable e) {
            return true;
        }
    }

    /** The refusal to open YouTube audio; the guard tells it from other read errors. */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    private static IOException refused(int kind, String message) {
        long now = System.nanoTime() / 1_000_000L;
        synchronized (lastLogged) {
            if (now - lastLogged[kind] > LOG_GAP_MS) {
                lastLogged[kind] = now;
                HostLog.d(message);
            }
        }
        return new Refused(Strings.t("YouTube Music is turned off"));
    }

    /** Test seams. */
    static void use(Registry replacement) {
        registry = replacement == null ? new Host() : replacement;
    }

    static void reset() {
        preferences = null;
        top = new WeakReference<>(null);
        showing = null;
        loading = new CountDownLatch(0);
        loadedOnce = false;
        proceed.remove();
        synchronized (players) {
            players.clear();
        }
        epoch = 0;
        synchronized (lastLogged) {
            lastLogged[0] = lastLogged[1] = 0;
        }
        registry = new Host();
    }

    /** Remembers the visible screen so the question has somewhere to appear. */
    private static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            top = new WeakReference<>(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (top.get() == activity) top = new WeakReference<>(null);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

        @Override
        public void onActivityDestroyed(Activity activity) {
            AlertDialog open = showing;
            if (open != null && open.getOwnerActivity() == activity) {
                open.dismiss();
                showing = null;
            }
        }
    }

    /** BitChord's SourceRegistry, reached by name so nothing links against it. */
    private static final class Host implements Registry {
        @Override
        public Row youtube() throws Exception {
            Class<?> sources = Class.forName(SOURCES + "SourceRegistry");
            Object flow = sources.getField("configs").get(null);
            List<?> rows =
                    (List<?>)
                            Class.forName("kotlinx.coroutines.flow.StateFlow")
                                    .getMethod("getValue")
                                    .invoke(flow);
            for (Object row : rows) {
                Object kind = row.getClass().getMethod("getKind").invoke(row);
                if (kind instanceof Enum && "YOUTUBE".equals(((Enum<?>) kind).name()))
                    return new Row(
                            (String) row.getClass().getMethod("getId").invoke(row),
                            Boolean.TRUE.equals(
                                    row.getClass().getMethod("getEnabled").invoke(row)));
            }
            return null;
        }

        @Override
        public void setEnabled(String id, boolean on) throws Exception {
            Method set =
                    Class.forName(SOURCES + "SourceRegistry")
                            .getMethod("setEnabled", String.class, boolean.class);
            set.invoke(null, id, on);
        }

        @Override
        public String origin(DataSpec spec) {
            try {
                String key = AudioOrigin.host.requestKey(spec);
                if (key == null) key = AudioOrigin.host.keyOf(spec.uri);
                AudioOrigin.Source source = AudioOrigin.cachedSource(key);
                return source == null ? null : source.isYouTube() ? "YOUTUBE" : "OTHER";
            } catch (Throwable e) {
                return null;
            }
        }

        @Override
        public String active(MediaItem item) {
            try {
                AudioOrigin.Source source = AudioOrigin.activeSource(item);
                return source == null ? null : source.isYouTube() ? "YOUTUBE" : "OTHER";
            } catch (Throwable e) {
                return null;
            }
        }

        @Override
        public String keyOrigin(String key) {
            try {
                AudioOrigin.Source source = AudioOrigin.cachedSource(key);
                return source == null ? null : source.isYouTube() ? "YOUTUBE" : "OTHER";
            } catch (Throwable e) {
                return null;
            }
        }

        @Override
        public void dropChoices() {
            try {
                Class<?> choices = Class.forName("com.music.bitchord.playback.StreamChoice");
                Field chosen = choices.getDeclaredField("chosen");
                chosen.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) chosen.get(null);
                Field substituted = null;
                for (Object entry : map.values().toArray()) {
                    if (substituted == null) {
                        substituted = entry.getClass().getDeclaredField("substituted");
                        substituted.setAccessible(true);
                    }
                    if (!substituted.getBoolean(entry)) map.values().remove(entry);
                }
            } catch (Throwable e) {
                HostLog.d(
                        "YouTube stream choices were not cleared: " + e.getClass().getSimpleName());
            }
        }
    }
}
