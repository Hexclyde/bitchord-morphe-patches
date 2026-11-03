package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.jvm.functions.Function1;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Measured audio/video controls using the host's version lookup and queue-preserving swap. */
public final class VideoSwitch {
    private VideoSwitch() {}

    static final String PREFERENCES = "bitchord_video_switch";
    private static final String SONG = "com.music.bitchord.data.model.Song";
    private static final String CONNECTION = "com.music.bitchord.playback.PlayerConnectionKt";
    private static final String REPOSITORY = "com.music.bitchord.data.YtMusicRepository";

    /** The fields a version takes from the song it replaces, so the queue stays as it was. */
    private static final String[] QUEUE_FIELDS = {
        "queueTier",
        "queueEntryId",
        "radioName",
        "playbackSource",
        "playbackSourceType",
        "playbackSourceId"
    };

    static volatile boolean enabled = false;
    static volatile boolean preferredVideo;
    private static volatile String attempted;
    private static Object eventListener;
    private static final Runnable ENFORCE = VideoSwitch::enforcePreference;

    private static volatile SharedPreferences preferences;
    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static WeakReference<Pill> pill = new WeakReference<>(null);
    private static Method androidView;
    private static Object headerModifier;
    private static final Function1<Context, View> HEADER_FACTORY = Header::new;
    private static Handler main;

    /** Songs with no other version, so the pill doesn't ask again. */
    private static final Set<String> missing = Collections.synchronizedSet(new HashSet<>());

    /** The video id being switched from while a switch runs, or null. */
    private static volatile String switching;

    private static final long VERSION_WAIT_MS = 30_000;
    private static LyricsSwitch lyricsSwitch;

    private static final class LyricsSwitch {
        final String from, target, entry;
        final boolean video;
        final long deadline;
        WeakReference<Object> model = new WeakReference<>(null);
        Object provider;
        List<?> key;
        long generation;
        boolean retryScheduled;

        LyricsSwitch(Object source, Object replacement, long now) {
            from = string(source, "videoId");
            target = string(replacement, "videoId");
            entry = string(source, "queueEntryId");
            video = isVideo(replacement);
            deadline = now + VERSION_WAIT_MS;
        }

        boolean belongs(Object playing, long now) {
            return enabled
                    && video == preferredVideo
                    && now < deadline
                    && entry != null
                    && entry.equals(string(playing, "queueEntryId"))
                    && (target.equals(string(playing, "videoId"))
                            || from.equals(string(playing, "videoId")));
        }
    }

    static void pendingLyricsSwitch(Object from, Object target, long now) {
        lyricsSwitch = null;
        String entry = string(from, "queueEntryId");
        if (enabled
                && entry != null
                && entry.equals(string(target, "queueEntryId"))
                && string(from, "videoId") != null
                && string(target, "videoId") != null
                && !Objects.equals(string(from, "videoId"), string(target, "videoId")))
            lyricsSwitch = new LyricsSwitch(from, target, now);
    }

    /** The native lookup still clears its results, jobs and old timed lyrics. */
    public static void lyricsBeforeReset(Object model, Object nextKey) {
        boolean onMain =
                Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        beforeLyrics(
                model,
                nextKey,
                onMain ? current() : null,
                SystemClock.elapsedRealtime(),
                onMain && !inParty());
    }

    public static void lyricsReady(Object model) {
        boolean onMain =
                Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        LyricsSwitch pending = lyricsSwitch;
        boolean waiting =
                afterLyrics(
                        model,
                        onMain ? current() : null,
                        SystemClock.elapsedRealtime(),
                        onMain && !inParty());
        if (waiting && pending != null && main != null && !pending.retryScheduled) {
            pending.retryScheduled = true;
            main.postDelayed(
                    () -> {
                        pending.retryScheduled = false;
                        if (lyricsSwitch != pending) return;
                        Object owner = pending.model.get();
                        if (owner == null) lyricsSwitch = null;
                        else lyricsReady(owner);
                    },
                    100);
        }
    }

    static void beforeLyrics(
            Object model, Object nextKey, Object playing, long now, boolean onMain) {
        LyricsSwitch pending = lyricsSwitch;
        if (pending == null) return;
        try {
            if (!onMain || !pending.belongs(playing, now) || !(nextKey instanceof List))
                throw new IllegalStateException();
            List<?> old = (List<?>) read(model, "lyricsFor");
            List<?> next = (List<?>) nextKey;
            Object provider = read(model, "selectedLyricsSource");
            if (old == null
                    || old.size() != 4
                    || next.size() != 4
                    || provider == null
                    || !pending.from.equals(old.get(0))
                    || !pending.target.equals(next.get(0))
                    || !old.subList(1, 4).equals(next.subList(1, 4))
                    || !pending.from.equals(string(read(model, "currentLyricsRequest"), "videoId")))
                throw new IllegalStateException();
            pending.key =
                    java.util.Arrays.asList(
                            next.get(0),
                            new HashSet<>((Set<?>) next.get(1)),
                            new ArrayList<>((List<?>) next.get(2)),
                            next.get(3));
            pending.model = new WeakReference<>(model);
            pending.provider = provider;
            pending.generation = ((Number) read(model, "lyricsGeneration")).longValue() + 1;
        } catch (Exception | LinkageError unavailable) {
            lyricsSwitch = null;
        }
    }

    static boolean afterLyrics(Object model, Object playing, long now, boolean onMain) {
        LyricsSwitch pending = lyricsSwitch;
        if (pending == null) return false;
        try {
            if (!onMain
                    || !pending.belongs(playing, now)
                    || pending.model.get() != model
                    || pending.provider == null
                    || pending.key == null
                    || !pending.key.equals(read(model, "lyricsFor"))
                    || pending.generation != ((Number) read(model, "lyricsGeneration")).longValue()
                    || read(model, "selectedLyricsSource") != null
                    || !pending.target.equals(
                            string(read(model, "currentLyricsRequest"), "videoId"))) {
                lyricsSwitch = null;
                return false;
            }
            if (!pending.target.equals(string(playing, "videoId"))) return true;
            lyricsSwitch = null;
            // This bridge invokes the provider chooser captured by BitChord's player UI.
            model.getClass()
                    .getMethod("bitchordSelectLyricsProvider", Object.class)
                    .invoke(model, pending.provider);
        } catch (Exception | LinkageError unavailable) {
            if (lyricsSwitch == pending) lyricsSwitch = null;
            HostLog.d("Version lyrics selection unavailable: " + unavailable);
        }
        return false;
    }

    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }

    static void init(Context context) {
        if (preferences != null || context == null) return;
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", false);
        preferredVideo = preferences.getBoolean("preferredVideo", false);
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.post(ENFORCE);
    }

    static void setEnabled(boolean on) {
        enabled = on;
        if (!on) lyricsSwitch = null;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("enabled", on).apply();
        attempted = null;
        if (main != null) main.post(ENFORCE);
        Pill shown = pill.get();
        if (shown != null) shown.refresh();
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    /** BitChord's player connection, each time the app's screen reads the player's state. */
    public static void controller(Object mediaController) {
        if (mediaController != null && controller.get() != mediaController) {
            lyricsSwitch = null;
            Object previous = controller.get();
            controller = new WeakReference<>(mediaController);
            if (main == null) main = new Handler(Looper.getMainLooper());
            main.post(() -> observe(previous, mediaController));
        }
    }

    /** A saved YouTube preference, independent of the current song's available versions. */
    static void setPreferredVideo(boolean video) {
        preferredVideo = video;
        if (lyricsSwitch != null && lyricsSwitch.video != video) lyricsSwitch = null;
        attempted = null;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("preferredVideo", video).apply();
        if (main != null) main.post(ENFORCE);
        refreshPill();
    }

    private static void observe(Object previous, Object current) {
        try {
            Class<?> listener = Class.forName("androidx.media3.common.Player$Listener");
            if (previous != null && eventListener != null)
                controllerType()
                        .getMethod("removeListener", listener)
                        .invoke(previous, eventListener);
            eventListener =
                    Proxy.newProxyInstance(
                            listener.getClassLoader(),
                            new Class<?>[] {listener},
                            (proxy, method, args) -> {
                                if (method.getName().equals("onMediaItemTransition"))
                                    trackChanged();
                                if (method.getName().equals("onMediaItemTransition")
                                        || method.getName().equals("onEvents")) {
                                    main.removeCallbacks(ENFORCE);
                                    main.post(ENFORCE);
                                }
                                if (method.getName().equals("equals")) return proxy == args[0];
                                if (method.getName().equals("hashCode"))
                                    return System.identityHashCode(proxy);
                                if (method.getName().equals("toString"))
                                    return "BitChord version preference";
                                return null;
                            });
            controllerType().getMethod("addListener", listener).invoke(current, eventListener);
        } catch (Exception | LinkageError unavailable) {
            HostLog.d("Version preference listener unavailable: " + unavailable);
        }
        main.post(ENFORCE);
    }

    /** Each queue entry gets a fresh attempt, even when the same recording plays again. */
    static void trackChanged() {
        attempted = null;
        missing.clear();
        LyricsSwitch pending = lyricsSwitch;
        Object playing = current();
        if (pending != null && (!pending.belongs(playing, SystemClock.elapsedRealtime())))
            lyricsSwitch = null;
        if (main != null) main.post(ENFORCE);
    }

    static boolean needsPreferredVersion(Object song) {
        return enabled && switchable(song) && isVideo(song) != preferredVideo;
    }

    private static void enforcePreference() {
        Context context = SoundExtras.app();
        Object song = current();
        if (context == null || !needsPreferredVersion(song) || inParty() || switching != null)
            return;
        String key = string(song, "videoId") + "|" + preferredVideo;
        if (key.equals(attempted) || missing.contains(key)) return;
        attempted = key;
        switchTo(context, preferredVideo);
    }

    // ── What's playing ──────────────────────────────────────────────────────

    /** The song playing now, as BitChord's Song, or null. */
    static Object current() {
        Object mediaController = controller.get();
        if (mediaController == null) return null;
        try {
            Object item = controllerType().getMethod("getCurrentMediaItem").invoke(mediaController);
            if (item == null) return null;
            return connection("toSong").invoke(null, item);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** Whether the song can switch: a YouTube Music song, not a file, addon or Telegram track. */
    static boolean switchable(Object song) {
        if (song == null) return false;
        String id = string(song, "videoId");
        if (!youTubeId(id)) return false;
        return string(song, "localUri") == null && string(song, "localPath") == null;
    }

    static boolean youTubeId(String id) {
        if (id == null || id.length() != 11) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_') || c > 'z') return false;
        }
        return true;
    }

    static boolean isVideo(Object song) {
        return song != null && bool(song, "isVideo");
    }

    /** True in a Listen Together party, where BitChord doesn't swap versions. */
    static boolean inParty() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.data.listentogether.ListenTogether");
            Field field = type.getDeclaredField("state");
            field.setAccessible(true);
            Object state = flowValue(field.get(null));
            if (state == null) return false;
            Method method = state.getClass().getMethod("getInParty");
            return Boolean.TRUE.equals(method.invoke(state));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    // ── Switching ───────────────────────────────────────────────────────────

    /** Switches the song playing now to its video (true) or its song (false). */
    static void switchTo(Context context, boolean video) {
        Object now = current();
        if (!switchable(now) || isVideo(now) == video || switching != null) return;
        if (inParty()) {
            toast(context, Strings.t("Versions can’t be switched in a Listen Together party."));
            return;
        }
        String from = string(now, "videoId");
        switching = from;
        refreshPill();
        new Thread(
                        () -> {
                            Object version = null;
                            try {
                                version = find(now, video);
                            } catch (Exception | LinkageError e) {
                                HostLog.d("Couldn't find the other version: " + e);
                            }
                            Object found = version;
                            main.post(() -> finish(context, now, found, video));
                        },
                        "bitchord-video-switch")
                .start();
    }

    private static void finish(Context context, Object now, Object version, boolean video) {
        String from = string(now, "videoId");
        try {
            Object playing = current();
            if (playing == null || !from.equals(string(playing, "videoId"))) return;
            String entry = string(now, "queueEntryId");
            if (entry != null && !entry.equals(string(playing, "queueEntryId"))) return;
            if (!enabled || video != preferredVideo || inParty()) return;
            if (version == null || from.equals(string(version, "videoId"))) {
                missing.add(from + "|" + video);
                toast(
                        context,
                        video
                                ? Strings.t("There’s no video for this song.")
                                : Strings.t("There’s no song version of this video."));
                return;
            }
            Object replacement = asReplacement(version, now, video);
            Object mediaController = controller.get();
            if (mediaController == null) return;
            Class<?> songType = Class.forName(SONG);
            pendingLyricsSwitch(now, replacement, SystemClock.elapsedRealtime());
            Class.forName(CONNECTION)
                    .getMethod("swapToVersion", controllerType(), songType)
                    .invoke(null, mediaController, replacement);
            HostLog.d("Requested the " + (video ? "video" : "song") + " version of " + from);
        } catch (Exception | LinkageError e) {
            lyricsSwitch = null;
            HostLog.d("Version switch failed: " + e);
            toast(context, Strings.t("Couldn’t switch versions."));
        } finally {
            switching = null;
            refreshPill();
            if (main != null) main.post(ENFORCE);
        }
    }

    private static Class<?> controllerType() throws ClassNotFoundException {
        return Class.forName("androidx.media3.session.MediaController");
    }

    /** The other version: BitChord's cached one, or one looked up on YouTube Music. */
    static Object find(Object song, boolean video) throws Exception {
        Class<?> repository = Class.forName(REPOSITORY);
        String id = string(song, "videoId");
        Method cached =
                repository.getMethod(
                        video ? "cachedVideoVersion" : "cachedAudioVersion", String.class);
        Object version = cached.invoke(null, id);
        if (version != null) return version;
        Object instance = repository.getField("INSTANCE").get(null);
        Method resolve =
                repository.getMethod(
                        video ? "resolveVideo" : "resolveAudio",
                        Class.forName(SONG),
                        ContinuationImpl.class);
        return await(continuation -> resolve.invoke(instance, song, continuation));
    }

    /**
     * The version as the queue's entry: the song's place, radio and source are kept, as BitChord's
     * menu does. Made from a fresh copy so BitChord's cache isn't changed.
     */
    static Object asReplacement(Object version, Object replaced, boolean video) throws Exception {
        Class<?> connection = Class.forName(CONNECTION);
        Class<?> songType = Class.forName(SONG);
        Object item = connection.getMethod("toMediaItem", songType).invoke(null, version);
        Object copy = connection("toSong").invoke(null, item);
        for (String name : QUEUE_FIELDS) {
            Field field = songType.getDeclaredField(name);
            field.setAccessible(true);
            field.set(copy, field.get(replaced));
        }
        if (!video) {
            Field origin = songType.getDeclaredField("isVideoOrigin");
            origin.setAccessible(true);
            origin.setBoolean(copy, true);
        }
        return copy;
    }

    // ── Calling BitChord's suspending lookups ──────────────────────────────

    interface Suspending {
        Object call(ContinuationImpl continuation) throws Exception;
    }

    /** Runs one of BitChord's suspending functions to the end, on this (background) thread. */
    static Object await(Suspending body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        Continuation<Object> completion =
                new Continuation<Object>() {
                    @Override
                    public CoroutineContext getContext() {
                        return EmptyCoroutineContext.INSTANCE;
                    }

                    @Override
                    public void resumeWith(Object value) {
                        result.set(value);
                        done.countDown();
                    }
                };
        Object returned = body.call(new Step(completion));
        if (returned != kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED) {
            return lookupResult(returned);
        }
        if (!done.await(VERSION_WAIT_MS, TimeUnit.MILLISECONDS)) return null;
        Object value = result.get();
        return lookupResult(value);
    }

    private static Object lookupResult(Object value) throws ReflectiveOperationException {
        if (value != null && value.getClass().getName().equals("kotlin.Result")) {
            Field payload = value.getClass().getDeclaredField("value");
            payload.setAccessible(true);
            value = payload.get(value);
        }
        return failed(value) ? null : value;
    }

    private static boolean failed(Object value) {
        return value != null && value.getClass().getName().equals("kotlin.Result$Failure");
    }

    /** The continuation BitChord's lookups resume; it passes their result straight on. */
    static final class Step extends ContinuationImpl {
        Step(Continuation<Object> completion) {
            super(completion);
        }

        @Override
        protected Object invokeSuspend(Object result) {
            return result;
        }
    }

    // ── The pill ────────────────────────────────────────────────────────────

    /** A real child of the host player Column, after its drag strip and before its artwork. */
    public static void header(Object composer) {
        try {
            initHeader();
            int tint = 0xffffffff;
            try {
                Object local =
                        Class.forName("com.music.bitchord.ui.player.PlayerControlsKt")
                                .getMethod("getLocalPlayerTint")
                                .invoke(null);
                Object color =
                        composer.getClass()
                                .getMethod(
                                        "consume",
                                        Class.forName(
                                                "androidx.compose.runtime.ProvidableCompositionLocal"))
                                .invoke(composer, local);
                long packed = color.getClass().getField("value").getLong(color);
                if ((packed & 63L) == 0) tint = (int) (packed >>> 32);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                // White is the player's fallback tint over artwork.
            }
            final int foreground = tint;
            Function1<View, Unit> update =
                    view -> {
                        Header row = (Header) view;
                        row.control.tint(foreground);
                        row.control.refresh();
                        return Unit.INSTANCE;
                    };
            androidView.invoke(null, HEADER_FACTORY, headerModifier, update, composer, 0, 0);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot embed player mode controls", failure);
        }
    }

    private static synchronized void initHeader() throws ReflectiveOperationException {
        if (androidView != null) return;
        Class<?> modifier = Class.forName("androidx.compose.ui.Modifier");
        Object fill =
                Class.forName("androidx.compose.foundation.layout.SizeKt")
                        .getField("FillWholeMaxWidth")
                        .get(null);
        Object layer =
                Class.forName("androidx.compose.ui.ZIndexElement")
                        .getConstructor(float.class)
                        .newInstance(1f);
        Object bounds = modifier.getMethod("then", modifier).invoke(fill, layer);
        Method found = null;
        for (Method method :
                Class.forName("androidx.compose.ui.viewinterop.AndroidView_androidKt").getMethods())
            if (method.getName().equals("AndroidView") && method.getParameterCount() == 6)
                found = method;
        if (found == null) throw new NoSuchMethodException("Player AndroidView ABI");
        headerModifier = bounds;
        androidView = found;
    }

    static final class Header extends FrameLayout {
        final Pill control;

        Header(Context context) {
            super(context);
            setTag("player-mode-header");
            control = new Pill(context);
            addView(control, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            pill = new WeakReference<>(control);
        }
    }

    private static void refreshPill() {
        Pill shown = pill.get();
        if (shown != null) shown.refresh();
    }

    private static boolean mainSurface() {
        try {
            Field field =
                    Class.forName("com.music.bitchord.data.settings.AppSettings")
                            .getDeclaredField("lastPlayerScreen");
            field.setAccessible(true);
            Object flow = field.get(null);
            Method read = flow.getClass().getMethod("getValue");
            read.setAccessible(true);
            Object screen = read.invoke(flow);
            return "MAIN".equals(String.valueOf(screen));
        } catch (ReflectiveOperationException unavailable) {
            return false;
        }
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    /** Two 52 × 48 dp targets; only icons are painted, while each exposes its own label. */
    static final class Pill extends LinearLayout {
        private final float density;
        private final Paint selection = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable tick = this::refresh;
        final ModeIcon audio, video;
        private boolean busy;

        Pill(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setOrientation(HORIZONTAL);
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            audio = new ModeIcon(context, false);
            video = new ModeIcon(context, true);
            addView(
                    audio,
                    new LinearLayout.LayoutParams((int) (52 * density), (int) (48 * density)));
            addView(
                    video,
                    new LinearLayout.LayoutParams((int) (52 * density), (int) (48 * density)));
            setVisibility(GONE);
            tint(0xffffffff);
        }

        void tint(int color) {
            selection.setColor(color);
            selection.setStrokeWidth(2 * density);
            selection.setStrokeCap(Paint.Cap.ROUND);
            audio.icon.setColor(color);
            video.icon.setColor(color);
            invalidate();
            audio.invalidate();
            video.invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        void refresh() {
            removeCallbacks(tick);
            Object now = enabled ? current() : null;
            boolean show = mainSurface() && switchable(now);
            setVisibility(show ? VISIBLE : GONE);
            busy = switching != null;
            boolean allowed = !busy && !inParty();
            audio.setEnabled(allowed);
            video.setEnabled(allowed);
            audio.setSelected(!preferredVideo);
            video.setSelected(preferredVideo);
            String id = string(now, "videoId");
            audio.setAlpha(!allowed || missing.contains(id + "|false") ? 0.4f : 1f);
            video.setAlpha(!allowed || missing.contains(id + "|true") ? 0.4f : 1f);
            invalidate();
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postDelayed(tick, 300);
        }

        private void choose(boolean wantVideo) {
            if (busy) return;
            if (inParty()) {
                toast(
                        getContext(),
                        Strings.t("Versions can’t be switched in a Listen Together party."));
                return;
            }
            setPreferredVideo(wantVideo);
            enforcePreference();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float center = getWidth() * (preferredVideo ? .75f : .25f);
            canvas.drawLine(
                    center - 10 * density,
                    44 * density,
                    center + 10 * density,
                    44 * density,
                    selection);
        }

        final class ModeIcon extends View {
            final boolean isVideo;
            final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path path = new Path();
            final RectF bounds = new RectF();

            ModeIcon(Context context, boolean isVideo) {
                super(context);
                this.isVideo = isVideo;
                String label = Strings.t(isVideo ? "Video" : "Audio");
                setContentDescription(label);
                setTooltipText(label);
                setTag(isVideo ? "player-mode-video" : "player-mode-audio");
                setFocusable(true);
                setClickable(true);
                icon.setStyle(Paint.Style.STROKE);
                icon.setStrokeCap(Paint.Cap.ROUND);
                icon.setStrokeJoin(Paint.Join.ROUND);
                icon.setStrokeWidth(2 * density);
                setOnClickListener(view -> choose(isVideo));
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.RadioButton");
                info.setCheckable(true);
                info.setChecked(preferredVideo == isVideo);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                if (isVideo) {
                    float wd = 9 * density, ht = 6.5f * density;
                    bounds.set(cx - wd, cy - ht, cx + wd, cy + ht);
                    canvas.drawRoundRect(bounds, 2 * density, 2 * density, icon);
                    path.reset();
                    path.moveTo(cx - 2.5f * density, cy - 3.5f * density);
                    path.lineTo(cx + 3.5f * density, cy);
                    path.lineTo(cx - 2.5f * density, cy + 3.5f * density);
                    path.close();
                    icon.setStyle(Paint.Style.FILL);
                    canvas.drawPath(path, icon);
                    icon.setStyle(Paint.Style.STROKE);
                } else {
                    float size = 8 * density;
                    bounds.set(cx - size, cy - size, cx + size, cy + size);
                    path.reset();
                    path.addArc(bounds, 180, 180);
                    canvas.drawPath(path, icon);
                    canvas.drawLine(cx - size, cy, cx - size, cy + size * 0.55f, icon);
                    canvas.drawLine(cx + size, cy, cx + size, cy + size * 0.55f, icon);
                    icon.setStyle(Paint.Style.FILL_AND_STROKE);
                    bounds.set(
                            cx - size - density,
                            cy + size * 0.05f,
                            cx - size * 0.45f,
                            cy + size * 0.9f);
                    canvas.drawRoundRect(bounds, density, density, icon);
                    bounds.set(
                            cx + size * 0.45f,
                            cy + size * 0.05f,
                            cx + size + density,
                            cy + size * 0.9f);
                    canvas.drawRoundRect(bounds, density, density, icon);
                    icon.setStyle(Paint.Style.STROKE);
                }
            }
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────────

    private static Method connection(String name) throws Exception {
        return Class.forName(CONNECTION)
                .getMethod(name, Class.forName("androidx.media3.common.MediaItem"));
    }

    private static Object flowValue(Object flow) throws Exception {
        if (flow == null) return null;
        for (Class<?> type = flow.getClass(); type != null; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields())
                if (field.getName().equals("$$delegate_0")) {
                    field.setAccessible(true);
                    return flowValue(field.get(flow));
                }
        return flow.getClass().getMethod("getValue").invoke(flow);
    }

    static String string(Object song, String name) {
        try {
            Field field = song.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(song);
            return value instanceof String ? (String) value : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static boolean bool(Object song, String name) {
        try {
            Field field = song.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(song);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
