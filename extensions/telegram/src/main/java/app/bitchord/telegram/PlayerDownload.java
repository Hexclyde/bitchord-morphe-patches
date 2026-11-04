package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.media3.common.MediaItem;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A player action backed by BitChord's ordinary persistent download queue. */
public final class PlayerDownload {
    private PlayerDownload() {}

    static final String PREFIX = "src:bitchord-player::";
    private static final String PREFS = "bitchord_player_download";
    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile SharedPreferences preferences;
    private static final Map<String, Request> live = new ConcurrentHashMap<>();
    private static Compose binding;
    static final Set<String> admitting = ConcurrentHashMap.newKeySet();
    static java.util.concurrent.Executor admissionWorker =
            action -> {
                Thread worker = new Thread(action, "bitchord-download-admission");
                worker.setDaemon(true);
                worker.start();
            };

    static String symbols() {
        return "";
    }

    public static void controller(Object value) {
        controller = new WeakReference<>(value);
    }

    public static void finished(String id) {
        if (id != null && id.startsWith(PREFIX)) live.remove(id);
    }

    static boolean owns(Object song) {
        return MixedSongCodec.text(song, "videoId").startsWith(PREFIX);
    }

    static final class Request {
        final String id;
        final JSONObject song;
        final int seconds;
        final String source;
        final Object stream;
        final Uri local;

        Request(String id, JSONObject song, int seconds, String source, Object stream, Uri local) {
            this.id = id;
            this.song = song;
            this.seconds =
                    seconds > 0
                            ? seconds
                            : Math.max(0, SourceFallback.seconds(song.optString("durationText")));
            this.source = source;
            this.stream = stream;
            this.local = local;
        }
    }

    static void init(Context context) {
        if (preferences == null) {
            SharedPreferences loaded =
                    context.getApplicationContext().getSharedPreferences(PREFS, 0);
            loaded.getAll();
            preferences = loaded;
        }
    }

    static Request request(String id) {
        Request found = live.get(id);
        if (found != null) return found;
        SharedPreferences saved = preferences;
        if (saved == null) return null;
        try {
            JSONObject json = new JSONObject(saved.getString(id, ""));
            return new Request(
                    id,
                    json.getJSONObject("song"),
                    json.getInt("seconds"),
                    json.optString("source", ""),
                    null,
                    json.has("local") ? Uri.parse(json.getString("local")) : null);
        } catch (Exception absent) {
            return null;
        }
    }

    static String jobId(Object song) {
        SharedPreferences saved = preferences;
        return saved == null
                ? ""
                : saved.getString("last." + MixedSongCodec.text(song, "videoId"), "");
    }

    static int durationSeconds(long duration, String metadata) {
        if (duration > 0 && duration < Integer.MAX_VALUE * 1000L) {
            int seconds = (int) Math.round(duration / 1000.0);
            if (seconds > 0) return seconds;
        }
        return Math.max(0, SourceFallback.seconds(metadata));
    }

    static void notice(Context context, String message, int length) {
        new Handler(Looper.getMainLooper())
                .post(() -> Toast.makeText(context, message, length).show());
    }

    static void start(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            Object player = controller.get();
            if (player == null) throw new IllegalStateException();
            MediaItem item = (MediaItem) PlayerDownloadHost.call(player, "getCurrentMediaItem");
            if (item == null) throw new IllegalStateException();
            Object song = PlayerDownloadHost.toSong(item);
            String originalId = MixedSongCodec.text(song, "videoId");
            if (originalId.isEmpty()) throw new IllegalStateException();
            long duration = ((Number) PlayerDownloadHost.call(player, "getDuration")).longValue();
            int seconds = durationSeconds(duration, MixedSongCodec.text(song, "durationText"));
            Uri uri = new AudioOriginHost().renditionUri(item);
            Object stream = PlayerDownloadHost.currentStream(item, uri);
            Context app = context.getApplicationContext();
            if (app == null) throw new IllegalStateException();
            if (!admitting.add(originalId)) return;
            try {
                admissionWorker.execute(
                        () -> admit(app, song, item, uri, stream, seconds, originalId));
            } catch (RuntimeException unavailable) {
                admitting.remove(originalId);
                throw unavailable;
            }
        } catch (Exception | LinkageError unavailable) {
            notice(
                    context.getApplicationContext(),
                    Strings.t("Download action failed. Try again."),
                    Toast.LENGTH_LONG);
        }
    }

    private static void admit(
            Context app,
            Object song,
            MediaItem item,
            Uri uri,
            Object stream,
            int seconds,
            String originalId) {
        String id = null;
        boolean enqueued = false;
        try {
            init(app);
            String previous = jobId(song);
            if (!previous.isEmpty()) {
                Object state = PlayerDownloadHost.active(previous);
                if (state != null && state.getClass().getSimpleName().equals("Failed")) {
                    live.remove(previous);
                    DownloadControls.retry(app, previous);
                    return;
                }
                if (state != null) return;
                if (PlayerDownloadHost.saved(previous)) {
                    notice(app, Strings.t("Saved"), Toast.LENGTH_SHORT);
                    return;
                }
                Request pending = previous.startsWith(PREFIX) ? request(previous) : null;
                if (pending != null && originalId.equals(pending.song.optString("videoId"))) {
                    for (JSONObject paused : DownloadControls.paused(app)) {
                        if (!previous.equals(paused.optString("id"))) continue;
                        live.remove(previous);
                        DownloadControls.retry(app, previous);
                        return;
                    }
                }
            }
            AudioOrigin.Source origin =
                    uri == null
                            ? null
                            : "bitchord".equals(uri.getScheme())
                                    ? AudioOrigin.cachedSource(new AudioOriginHost().keyOf(uri))
                                    : AudioOrigin.savedSource(item.mediaId, uri.toString());
            String source = origin == null || origin.id == null ? "" : origin.id;
            Uri local =
                    uri != null
                                    && ("file".equals(uri.getScheme())
                                            || "content".equals(uri.getScheme()))
                            ? uri
                            : null;
            JSONObject original = MixedSongCodec.encode(song);
            id = PREFIX + UUID.randomUUID();
            Request pending = new Request(id, original, seconds, source, stream, local);
            JSONObject persisted =
                    new JSONObject()
                            .put("song", original)
                            .put("seconds", pending.seconds)
                            .put("source", source);
            if (local != null) persisted.put("local", local.toString());
            SharedPreferences.Editor edit = preferences.edit();
            if (preferences.getAll().size() > 2048) throw new IllegalStateException();
            edit.putString(id, persisted.toString()).putString("last." + originalId, id);
            if (!edit.commit()) throw new IllegalStateException();
            live.put(id, pending);
            JSONObject queued = new JSONObject(original.toString()).put("videoId", id);
            Object selected = MixedSongCodec.decode(queued);
            DownloadSelection.keep(app, selected);
            PlayerDownloadHost.enqueue(app, selected);
            enqueued = true;
        } catch (Exception | LinkageError unavailable) {
            if (id != null && !enqueued) {
                boolean accepted = false;
                try {
                    accepted =
                            PlayerDownloadHost.active(id) != null || PlayerDownloadHost.saved(id);
                } catch (Exception absent) {
                    /* No native receipt is available. */
                }
                if (!accepted) {
                    live.remove(id);
                    SharedPreferences saved = preferences;
                    if (saved != null) {
                        SharedPreferences.Editor rollback = saved.edit().remove(id);
                        if (id.equals(saved.getString("last." + originalId, "")))
                            rollback.remove("last." + originalId);
                        rollback.commit();
                    }
                }
            }
            notice(app, Strings.t("Download action failed. Try again."), Toast.LENGTH_LONG);
        } finally {
            admitting.remove(originalId);
        }
    }

    /** Runs only on the host download preparation dispatcher. Ordinary downloads return null. */
    public static Object route(Context context, Object song, Object continuation) {
        if (!owns(song)) return null;
        init(context);
        Request pending = request(MixedSongCodec.text(song, "videoId"));
        try {
            if (pending == null) throw new IllegalStateException();
            return new PlayerDownloadHost(context, continuation).select(pending);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        } catch (Exception | LinkageError unavailable) {
            return PlayerDownloadHost.failedRoute();
        }
    }

    static int percentage(Object state) {
        if (state == null) return -1;
        try {
            Object fraction = PlayerDownloadHost.field(state, "fraction");
            if (!(fraction instanceof Number)) return -1;
            float value = ((Number) fraction).floatValue();
            if (!Float.isFinite(value) || value <= 0) return -1;
            return Math.max(0, Math.min(99, (int) (value * 100)));
        } catch (Exception absent) {
            return -1;
        }
    }

    public static void compose(Object composer) {
        try {
            if (binding == null) binding = new Compose(symbols());
            Compose c = binding;
            c.start.invoke(composer, 0x50444f57);
            try {
                int color = c.color(composer);
                Object modifier = c.size.invoke(null, c.companion, 48f);
                if (c.blend != null && c.chrome(composer))
                    modifier = c.blend.invoke(null, modifier, true);
                Function1<Context, View> factory = Entry::new;
                Function1<View, Unit> update =
                        view -> {
                            ((Entry) view).color = color;
                            view.invalidate();
                            return Unit.INSTANCE;
                        };
                c.androidView.invoke(null, factory, modifier, update, composer, 0, 0);
            } finally {
                c.end.invoke(composer);
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            HostLog.d("Player download control is unavailable");
        }
    }

    static final class Entry extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        final Runnable tick = this::refresh;
        int color = 0xffffffff;
        int percent = -1;
        float arcPercent;
        android.animation.ValueAnimator progressAnimation;
        boolean busy, done, failed;

        Entry(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setTag("player-download");
            setClickable(true);
            setFocusable(true);
            setContentDescription(Strings.t("Download"));
            setOnClickListener(v -> start(context));
        }

        void refresh() {
            removeCallbacks(tick);
            try {
                Object player = controller.get();
                Object item =
                        player == null
                                ? null
                                : PlayerDownloadHost.call(player, "getCurrentMediaItem");
                Object song = item == null ? null : PlayerDownloadHost.toSong((MediaItem) item);
                String id = song == null ? "" : jobId(song);
                Object state = id.isEmpty() ? null : PlayerDownloadHost.active(id);
                String kind = state == null ? "" : state.getClass().getSimpleName();
                failed = kind.equals("Failed");
                busy =
                        (state != null && !failed)
                                || (song != null
                                        && admitting.contains(
                                                MixedSongCodec.text(song, "videoId")));
                done = !id.isEmpty() && PlayerDownloadHost.saved(id);
                int next = percentage(state);
                if (next != percent) {
                    if (progressAnimation != null) progressAnimation.cancel();
                    if (next >= 0
                            && percent >= 0
                            && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        progressAnimation =
                                android.animation.ValueAnimator.ofFloat(arcPercent, next);
                        progressAnimation.setDuration(150);
                        progressAnimation.addUpdateListener(
                                value -> {
                                    arcPercent = (Float) value.getAnimatedValue();
                                    invalidate();
                                });
                        progressAnimation.start();
                    } else arcPercent = Math.max(0, next);
                    percent = next;
                }
                setEnabled(song != null && !busy);
                String label =
                        busy
                                ? percent >= 0
                                        ? Strings.f("Downloading · {0}%", percent)
                                        : Strings.t("Downloading")
                                : done
                                        ? Strings.t("Saved")
                                        : failed ? Strings.t("Retry") : Strings.t("Download");
                setContentDescription(label);
                setTooltipText(label);
                invalidate();
            } catch (Exception unavailable) {
                setEnabled(false);
            }
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postDelayed(tick, 250);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            if (progressAnimation != null) progressAnimation.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Math.round(48 * density);
            setMeasuredDimension(resolveSize(s, w), resolveSize(s, h));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, d = density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((Math.round(0.18f * 255) << 24) | (color & 0xffffff));
            canvas.drawCircle(cx, cy, 17 * d, paint);
            paint.setColor(color);
            paint.setStrokeWidth(1.6f * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            if (busy && percent >= 0) {
                canvas.drawArc(
                        new RectF(cx - 12 * d, cy - 12 * d, cx + 12 * d, cy + 12 * d),
                        -90,
                        arcPercent * 3.6f,
                        false,
                        paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(9 * d * getResources().getConfiguration().fontScale);
                paint.setTextAlign(Paint.Align.CENTER);
                String label = Strings.f("{0}%", percent);
                float width = paint.measureText(label);
                if (width > 20 * d) paint.setTextSize(paint.getTextSize() * 20 * d / width);
                canvas.drawText(label, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
            } else if (done) {
                canvas.drawLine(cx - 6 * d, cy, cx - 1 * d, cy + 5 * d, paint);
                canvas.drawLine(cx - 1 * d, cy + 5 * d, cx + 7 * d, cy - 5 * d, paint);
            } else {
                canvas.drawLine(cx, cy - 8 * d, cx, cy + 3 * d, paint);
                canvas.drawLine(cx - 4 * d, cy - d, cx, cy + 3 * d, paint);
                canvas.drawLine(cx + 4 * d, cy - d, cx, cy + 3 * d, paint);
                canvas.drawLine(cx - 7 * d, cy + 3 * d, cx - 7 * d, cy + 8 * d, paint);
                canvas.drawLine(cx - 7 * d, cy + 8 * d, cx + 7 * d, cy + 8 * d, paint);
                canvas.drawLine(cx + 7 * d, cy + 8 * d, cx + 7 * d, cy + 3 * d, paint);
            }
        }
    }

    static final class Compose {
        final Method androidView, size, start, end, consume, blend;
        final Field tint, chrome;
        final Object companion;

        Compose(String table) throws ReflectiveOperationException {
            Map<String, String> refs = new HashMap<>();
            for (String line : table.split("\n")) {
                int split = line.indexOf('=');
                if (split > 0) refs.put(line.substring(0, split), line.substring(split + 1));
            }
            androidView = method(refs.get("androidView"));
            size = method(refs.get("size"));
            start = method(refs.get("startGroup"));
            end = method(refs.get("endGroup"));
            companion = member(refs.get("companion")).get(null);
            consume = refs.containsKey("consume") ? method(refs.get("consume")) : null;
            tint = refs.containsKey("tint") ? member(refs.get("tint")) : null;
            chrome = refs.containsKey("chrome") ? member(refs.get("chrome")) : null;
            blend = refs.containsKey("blend") ? method(refs.get("blend")) : null;
        }

        boolean chrome(Object composer) throws ReflectiveOperationException {
            return chrome != null
                    && Boolean.TRUE.equals(consume.invoke(composer, chrome.get(null)));
        }

        int color(Object composer) throws ReflectiveOperationException {
            if (tint == null) return 0xffffffff;
            Object color = consume.invoke(composer, tint.get(null));
            long packed = color.getClass().getField("value").getLong(color);
            return (packed & 63L) == 0 ? (int) (packed >>> 32) : 0xffffffff;
        }

        static Class<?> type(String descriptor) throws ClassNotFoundException {
            if (descriptor.equals("I")) return int.class;
            if (descriptor.equals("F")) return float.class;
            if (descriptor.equals("Z")) return boolean.class;
            if (descriptor.equals("J")) return long.class;
            return Class.forName(
                    descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }

        static Method method(String ref) throws ReflectiveOperationException {
            if (ref == null) throw new NoSuchMethodException("Player download symbols");
            int open = ref.indexOf('('), close = ref.indexOf(')');
            List<Class<?>> parameters = new ArrayList<>();
            String list = ref.substring(open + 1, close);
            for (int i = 0; i < list.length(); ) {
                int end = list.charAt(i) == 'L' ? list.indexOf(';', i) : i;
                parameters.add(type(list.substring(i, end + 1)));
                i = end + 1;
            }
            Method result =
                    type(ref.substring(0, ref.indexOf(';') + 1))
                            .getDeclaredMethod(
                                    ref.substring(ref.indexOf("->") + 2, open),
                                    parameters.toArray(new Class<?>[0]));
            result.setAccessible(true);
            return result;
        }

        static Field member(String ref) throws ReflectiveOperationException {
            Field result =
                    type(ref.substring(0, ref.indexOf(';') + 1))
                            .getDeclaredField(
                                    ref.substring(ref.indexOf("->") + 2, ref.indexOf(':')));
            result.setAccessible(true);
            return result;
        }
    }
}
