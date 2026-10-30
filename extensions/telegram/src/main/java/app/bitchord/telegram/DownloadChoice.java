package app.bitchord.telegram;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.music.bitchord.data.model.Song;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-download quality: when you download a song, BitChord asks which quality you want it in,
 * instead of always using the Download quality setting. Several songs asked for together, such as
 * an album, are asked about once. The idea is from Stash by Rawnald Clark.
 *
 * <p>BitChord's three rungs are kept: Standard (about 128 kbps Opus), High (the best lossy copy)
 * and Lossless (a source's bit-exact file when one has it).
 */
public final class DownloadChoice {
    private DownloadChoice() {}

    static final String PREFERENCES = "bitchord_download_choice";
    static final String[] QUALITIES = {"STANDARD", "HIGH", "LOSSLESS"};

    private static volatile SharedPreferences preferences;
    static volatile boolean ask = false;
    static volatile String last = "LOSSLESS";

    /** The quality chosen for a song that's about to be queued, by its id. */
    private static final Map<String, String> chosen = new ConcurrentHashMap<>();

    static void init(Context context) {
        if (preferences != null || context == null) return;
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        ask = preferences.getBoolean("ask", false);
        last = preferences.getString("last", "LOSSLESS");
    }

    static void setAsk(boolean on) {
        ask = on;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("ask", on).apply();
    }

    static String label(String quality) {
        switch (quality) {
            case "STANDARD":
                return Strings.t("Standard");
            case "HIGH":
                return Strings.t("High");
            default:
                return Strings.t("Lossless");
        }
    }

    static String detail(String quality) {
        switch (quality) {
            case "STANDARD":
                return Strings.t("About 128 kbps Opus · around 4 MB a song");
            case "HIGH":
                return Strings.t("The best lossy copy on offer · around 8 MB a song");
            default:
                return Strings.t("A source’s exact file when one has it · around 35 MB a song");
        }
    }

    // ── The hook in BitChord's download queue ────────────────────────────────

    /** One song waiting for the answer. */
    private static final class Pending {
        final Context context;
        final Song song;
        final String from;

        Pending(Context context, Song song, String from) {
            this.context = context;
            this.song = song;
            this.from = from;
        }
    }

    private static final List<Pending> pending = new ArrayList<>();
    private static Dialog dialog;
    private static TextView heading;

    /**
     * Called first thing in BitChord's enqueue: true to hold the song until the quality is chosen,
     * false to let BitChord carry on. Songs are held only when asking is on, an activity is there
     * to ask in, and the song hasn't been answered for already.
     */
    public static boolean hold(Context context, Song song, String from) {
        if (!ask || song == null || song.videoId == null) return false;
        // An explicitly selected native file keeps its original encoding. These paths
        // bypass catalogue quality selection and cannot produce a different rendition.
        if (song.videoId.startsWith("src:soulseek-native::")
                || song.videoId.startsWith("src:telegram-native::")) return false;
        if (chosen.containsKey(song.videoId)) return false;
        Activity activity = activity(context);
        if (activity == null || activity.isFinishing()) return false;
        Runnable show =
                () -> {
                    synchronized (pending) {
                        pending.add(new Pending(context, song, from));
                    }
                    open(activity);
                };
        if (Looper.myLooper() == Looper.getMainLooper()) show.run();
        else new Handler(Looper.getMainLooper()).post(show);
        return true;
    }

    private static Activity activity(Context context) {
        for (Context c = context; c != null; ) {
            if (c instanceof Activity) return (Activity) c;
            if (c instanceof android.content.ContextWrapper)
                c = ((android.content.ContextWrapper) c).getBaseContext();
            else return null;
        }
        return null;
    }

    /**
     * The quality BitChord should download a song in: the one chosen for it, or the one given.
     * Called where BitChord reads its Download quality setting for a song.
     */
    public static Object quality(Song song, Object setting) {
        if (song == null || song.videoId == null || setting == null) return setting;
        String picked = chosen.remove(song.videoId);
        if (picked == null) return setting;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> type = (Class<? extends Enum>) setting.getClass();
            @SuppressWarnings("unchecked")
            Object value = Enum.valueOf(type, picked);
            HostLog.d("Downloading " + song.videoId + " as " + picked);
            return value;
        } catch (RuntimeException e) {
            return setting;
        }
    }

    /** Forgets a choice when BitChord refused the song, so the next tap asks again. */
    static void forget(String videoId) {
        if (videoId != null) chosen.remove(videoId);
    }

    private static void open(Activity activity) {
        if (dialog != null && dialog.isShowing()) {
            updateHeading();
            return;
        }
        TelegramStyle style = new TelegramStyle(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackground(style.shape(style.background, 22, false));
        body.setPadding(style.dp(22), style.dp(22), style.dp(22), style.dp(14));
        heading = style.text("", 20, style.foreground, style.heavy);
        body.addView(heading);
        TextView sub =
                style.text(
                        Strings.t("Pick the quality for this download."),
                        14,
                        style.secondary,
                        style.regular);
        sub.setPadding(0, style.dp(6), 0, style.dp(14));
        body.addView(sub);
        for (String quality : QUALITIES) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(12));
            row.setBackground(
                    style.shape(quality.equals(last) ? style.card : style.field, 14, false));
            row.addView(style.text(label(quality), 16, style.foreground, style.semibold));
            TextView more = style.text(detail(quality), 13, style.secondary, style.regular);
            more.setPadding(0, style.dp(3), 0, 0);
            row.addView(more);
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(label(quality));
            row.setOnClickListener(v -> answer(quality));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = style.dp(8);
            body.addView(row, lp);
        }
        TextView cancel = style.text(Strings.t("Cancel"), 15, style.secondary, style.medium);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, style.dp(12), 0, style.dp(6));
        cancel.setOnClickListener(v -> answer(null));
        body.addView(cancel);
        Dialog shown = new Dialog(activity);
        shown.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        shown.setContentView(body);
        if (shown.getWindow() != null) {
            shown.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            shown.getWindow()
                    .setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        shown.setOnCancelListener(d -> answer(null));
        dialog = shown;
        updateHeading();
        shown.show();
    }

    private static void updateHeading() {
        TextView view = heading;
        if (view == null) return;
        int count;
        String title;
        synchronized (pending) {
            count = pending.size();
            title = count == 1 ? String.valueOf(pending.get(0).song.title) : null;
        }
        view.setText(
                count == 1
                        ? Strings.f("Download “{0}”", title)
                        : Strings.f("Download {0} songs", String.valueOf(count)));
    }

    /** The answer for everything waiting: queued again with the choice, or dropped. */
    private static void answer(String quality) {
        List<Pending> waiting;
        synchronized (pending) {
            waiting = new ArrayList<>(pending);
            pending.clear();
        }
        Dialog shown = dialog;
        dialog = null;
        heading = null;
        if (shown != null)
            try {
                shown.dismiss();
            } catch (RuntimeException ignored) {
                // The activity went away.
            }
        if (quality == null) {
            HostLog.d("Download quality: cancelled " + waiting.size());
            return;
        }
        last = quality;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putString("last", quality).apply();
        for (Pending item : waiting) chosen.put(item.song.videoId, quality);
        for (Pending item : waiting) enqueue(item);
    }

    /** Hands a song back to BitChord's queue; the choice is read when it's prepared. */
    private static void enqueue(Pending item) {
        try {
            Class<?> type = Class.forName("com.music.bitchord.download.Downloads");
            Object downloads = type.getDeclaredField("INSTANCE").get(null);
            Method enqueue = type.getMethod("enqueue", Context.class, Song.class, String.class);
            enqueue.invoke(downloads, item.context, item.song, item.from);
        } catch (Exception | LinkageError e) {
            HostLog.d("Download quality couldn't queue the song: " + e);
            chosen.remove(item.song.videoId);
        }
    }
}
