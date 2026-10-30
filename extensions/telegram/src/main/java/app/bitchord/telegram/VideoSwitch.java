package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.jvm.functions.Function0;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Audio and video switch: a Song | Video pill at the top of the player, as in YouTube Music. From
 * 1.9 BitChord can play a song's music video, and switches between the two from the song menu; the
 * pill does the same in one tap. It finds the other version the way BitChord does (its cache first,
 * then YouTube Music) and hands it to BitChord's own version swap, which keeps the place in the
 * queue and lines the two up in time.
 */
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

    private static volatile SharedPreferences preferences;
    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile WeakReference<Object> motion = new WeakReference<>(null);
    private static WeakReference<Pill> pill = new WeakReference<>(null);
    private static Handler main;

    /** Songs with no other version, so the pill doesn't ask again. */
    private static final Set<String> missing = Collections.synchronizedSet(new HashSet<>());

    /** The video id being switched from while a switch runs, or null. */
    private static volatile String switching;

    static void init(Context context) {
        if (preferences != null || context == null) return;
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", false);
    }

    static void setEnabled(boolean on) {
        enabled = on;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("enabled", on).apply();
        Pill shown = pill.get();
        if (shown != null) shown.refresh();
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    /** BitChord's player connection, each time the app's screen reads the player's state. */
    public static void controller(Object mediaController) {
        if (mediaController != null && controller.get() != mediaController)
            controller = new WeakReference<>(mediaController);
    }

    /** The player sheet's motion, made once per screen; it knows when the player is open. */
    public static void motion(Object playerSheetMotion) {
        if (!SoundExtras.videoSwitchApplied() || playerSheetMotion == null) return;
        motion = new WeakReference<>(playerSheetMotion);
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.post(VideoSwitch::attach);
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

    /** Whether the full player is open and settled, not being dragged. */
    static boolean playerOpen() {
        Object sheet = motion.get();
        if (sheet == null) return false;
        try {
            Field shown = sheet.getClass().getDeclaredField("shown");
            shown.setAccessible(true);
            Object getter = shown.get(sheet);
            if (!(getter instanceof Function0)) return false;
            if (!Boolean.TRUE.equals(((Function0<?>) getter).invoke())) return false;
            Method holding = sheet.getClass().getMethod("getHolding");
            return !Boolean.TRUE.equals(holding.invoke(sheet));
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
            if (version == null || from.equals(string(version, "videoId"))) {
                missing.add(from);
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
            Class.forName(CONNECTION)
                    .getMethod("swapToVersion", controllerType(), songType)
                    .invoke(null, mediaController, replacement);
            HostLog.d("Switched to the " + (video ? "video" : "song") + " of " + from);
        } catch (Exception | LinkageError e) {
            HostLog.d("Version switch failed: " + e);
            toast(context, Strings.t("Couldn’t switch versions."));
        } finally {
            switching = null;
            refreshPill();
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
            return failed(returned) ? null : returned;
        }
        if (!done.await(30, TimeUnit.SECONDS)) return null;
        Object value = result.get();
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

    private static void attach() {
        Object sheet = motion.get();
        if (sheet == null) return;
        Window window = null;
        try {
            Field field = sheet.getClass().getDeclaredField("window");
            field.setAccessible(true);
            window = (Window) field.get(sheet);
        } catch (Exception | LinkageError e) {
            HostLog.d("Player window not found: " + e);
        }
        if (window == null) {
            // The player sets its window once it's laid out.
            main.postDelayed(VideoSwitch::attach, 500);
            return;
        }
        ViewGroup content = window.getDecorView().findViewById(android.R.id.content);
        if (content == null) return;
        Pill old = pill.get();
        if (old != null && old.getParent() == content) return;
        if (old != null && old.getParent() instanceof ViewGroup)
            ((ViewGroup) old.getParent()).removeView(old);
        Pill made = new Pill(content.getContext());
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        content.addView(made, lp);
        pill = new WeakReference<>(made);
        made.refresh();
    }

    private static void refreshPill() {
        Pill shown = pill.get();
        if (shown != null) shown.refresh();
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    /** Song | Video, with headphones and a video frame, as in YouTube Music. */
    static final class Pill extends View {
        private final float density;
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chosen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final Runnable tick = this::refresh;
        private boolean video, busy, blocked;

        Pill(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            back.setColor(0x33FFFFFF);
            chosen.setColor(0x33FFFFFF);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
            icon.setStrokeWidth(2 * density);
            setVisibility(GONE);
            setContentDescription(Strings.t("Switch between song and video"));
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension((int) (112 * density), (int) (36 * density));
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) getLayoutParams();
            if (lp != null) {
                lp.topMargin = insets.getSystemWindowInsetTop() + (int) (12 * density);
                setLayoutParams(lp);
            }
            return insets;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestApplyInsets();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        void refresh() {
            removeCallbacks(tick);
            Object now = enabled && playerOpen() ? current() : null;
            boolean show = switchable(now);
            setVisibility(show ? VISIBLE : GONE);
            if (show) {
                String id = string(now, "videoId");
                video = isVideo(now);
                busy = switching != null;
                blocked = inParty() || missing.contains(id);
                setAlpha(busy || blocked ? 0.5f : 1f);
                bringToFront();
                invalidate();
            }
            if (isAttachedToWindow()) postDelayed(tick, show ? 300 : 600);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (event.getAction() != MotionEvent.ACTION_UP) return false;
            boolean wantVideo = event.getX() >= getWidth() / 2f;
            if (busy) return true;
            if (blocked && wantVideo != video) {
                toast(
                        getContext(),
                        inParty()
                                ? Strings.t(
                                        "Versions can’t be switched in a Listen Together party.")
                                : Strings.t("There’s no other version of this song."));
                return true;
            }
            performClick();
            switchTo(getContext(), wantVideo);
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            box.set(0, 0, w, h);
            canvas.drawRoundRect(box, r, r, back);
            float pad = 3 * density;
            box.set(video ? w / 2f : pad, pad, video ? w - pad : w / 2f, h - pad);
            canvas.drawRoundRect(box, r - pad, r - pad, chosen);
            icon.setColor(video ? 0xB3FFFFFF : 0xFFFFFFFF);
            headphones(canvas, w / 4f, h / 2f);
            icon.setColor(video ? 0xFFFFFFFF : 0xB3FFFFFF);
            videoFrame(canvas, w * 3f / 4f, h / 2f);
        }

        private void headphones(Canvas canvas, float cx, float cy) {
            float s = 8 * density;
            box.set(cx - s, cy - s, cx + s, cy + s);
            path.reset();
            path.addArc(box, 180, 180);
            canvas.drawPath(path, icon);
            canvas.drawLine(cx - s, cy, cx - s, cy + s * 0.55f, icon);
            canvas.drawLine(cx + s, cy, cx + s, cy + s * 0.55f, icon);
            Paint.Style style = icon.getStyle();
            icon.setStyle(Paint.Style.FILL_AND_STROKE);
            box.set(cx - s - density, cy + s * 0.05f, cx - s * 0.45f, cy + s * 0.9f);
            canvas.drawRoundRect(box, density, density, icon);
            box.set(cx + s * 0.45f, cy + s * 0.05f, cx + s + density, cy + s * 0.9f);
            canvas.drawRoundRect(box, density, density, icon);
            icon.setStyle(style);
        }

        private void videoFrame(Canvas canvas, float cx, float cy) {
            float wd = 9 * density, ht = 6.5f * density;
            box.set(cx - wd, cy - ht, cx + wd, cy + ht);
            canvas.drawRoundRect(box, 2 * density, 2 * density, icon);
            path.reset();
            path.moveTo(cx - 2.5f * density, cy - 3.5f * density);
            path.lineTo(cx + 3.5f * density, cy);
            path.lineTo(cx - 2.5f * density, cy + 3.5f * density);
            path.close();
            Paint.Style style = icon.getStyle();
            icon.setStyle(Paint.Style.FILL_AND_STROKE);
            canvas.drawPath(path, icon);
            icon.setStyle(style);
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
