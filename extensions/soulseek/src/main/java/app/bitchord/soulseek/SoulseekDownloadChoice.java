package app.bitchord.soulseek;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import app.bitchord.telegram.PatchStrings;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An explicit choice at the host's manual single-song callback, never its queue or retry loop. */
public final class SoulseekDownloadChoice {
    static final String EXTRA_CHOICE = "soulseek.choice", EXTRA_QUERY = "soulseek.query";
    private static final Map<String, Pending> pending = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final long LEASE = 120_000, BATCH_LEASE = 30 * 60_000;

    private SoulseekDownloadChoice() {}

    private static final class Pending {
        final Function2<Object, Object, Object> original;
        final List<?> songs;
        final Object from;
        final long expires;

        Pending(Function2<Object, Object, Object> original, List<?> songs, Object from) {
            this.original = original;
            this.songs = new ArrayList<>(songs);
            this.from = from;
            this.expires =
                    SystemClock.elapsedRealtime()
                            + (songs.size() > 1 || from != null ? BATCH_LEASE : LEASE);
        }
    }

    /** The only patched call site is the manual downloadSong singleton-list dispatch. */
    @SuppressWarnings("unchecked")
    public static Object offer(Object original, Object songs, Object from) {
        if (!(original instanceof Function2))
            throw new IllegalArgumentException("Invalid download continuation");
        Function2<Object, Object, Object> continuation =
                (Function2<Object, Object, Object>) original;
        if (!(songs instanceof List)
                || !(eligible((List<?>) songs, from) || eligibleBatch((List<?>) songs, from)))
            return continuation.invoke(songs, from);
        Context app = null;
        try {
            app = SoulseekBridge.get().context;
        } catch (Exception unavailable) {
            // Before bridge boot the unmodified player remains usable.
        }
        // The original callback may itself throw. Never catch it as a bridge failure
        // and retry a second time: it owns permissions, queue admission and other patches.
        if (app == null) return continuation.invoke(songs, from);
        final Context context = app;
        if (((List<?>) songs).size() > 200) {
            notice(context, "Select up to 200 tracks for one source-selection batch.");
            return Unit.INSTANCE;
        }
        String token = UUID.randomUUID().toString();
        synchronized (pending) {
            pending.values().removeIf(value -> value.expires <= SystemClock.elapsedRealtime());
            if (pending.size() >= 8) {
                notice(context, "Finish the open download choice first.");
                return Unit.INSTANCE;
            }
            pending.put(token, new Pending(continuation, (List<?>) songs, from));
        }
        main.postDelayed(
                () -> forget(token),
                ((List<?>) songs).size() > 1 || from != null ? BATCH_LEASE : LEASE);
        try {
            boolean batch = ((List<?>) songs).size() > 1 || from != null;
            Object song = ((List<?>) songs).get(0);
            String artist = value(song, "getArtist"), title = value(song, "getTitle");
            String query = (artist + " " + title).trim();
            if (query.length() > 256) query = query.substring(0, 256);
            Intent target =
                    new Intent(
                                    context,
                                    batch
                                            ? BatchDownloadActivity.class
                                            : DownloadExplorerActivity.class)
                            .putExtra(EXTRA_CHOICE, token)
                            .putExtra(EXTRA_QUERY, query)
                            .putExtra("download.track.title", title)
                            .putExtra("download.track.artist", artist)
                            .putExtra("download.track.artwork", value(song, "getThumbnailUrl"))
                            .putExtra("download.track.duration", value(song, "getDurationText"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!DownloadsShell.open(target)) context.startActivity(target);
        } catch (Exception unavailable) {
            forget(token);
            notice(context, "Could not open the download source choice. Try again.");
        }
        return Unit.INSTANCE;
    }

    private static final ThreadLocal<Boolean> bypass = new ThreadLocal<>();

    public static boolean interceptBatch(Object original, Object songs, Object from) {
        if (Boolean.TRUE.equals(bypass.get())
                || !(songs instanceof List)
                || !eligibleBatch((List<?>) songs, from)) return false;
        offer(original, songs, from);
        return true;
    }

    private static boolean eligibleBatch(List<?> songs, Object from) {
        if (songs.isEmpty() || (songs.size() == 1 && from == null)) return false;
        for (Object song : songs)
            if (!value(song, "getVideoId").matches("[A-Za-z0-9_-]{11}")) return false;
        return true;
    }

    static List<?> songs(String token) {
        synchronized (pending) {
            Pending value = pending.get(token);
            return value == null || value.expires <= SystemClock.elapsedRealtime()
                    ? java.util.Collections.emptyList()
                    : new ArrayList<>(value.songs);
        }
    }

    static boolean isBatch(String token) {
        synchronized (pending) {
            Pending value = pending.get(token);
            return value != null && (value.songs.size() > 1 || value.from != null);
        }
    }

    static boolean eligible(List<?> songs, Object from) {
        return from == null
                && songs.size() == 1
                && value(songs.get(0), "getVideoId").matches("[A-Za-z0-9_-]{11}");
    }

    private static String value(Object song, String method) {
        if (song == null) return "";
        try {
            Object value = song.getClass().getMethod(method).invoke(song);
            return value instanceof String ? (String) value : "";
        } catch (Exception unavailable) {
            try {
                String field = Character.toLowerCase(method.charAt(3)) + method.substring(4);
                Object value = song.getClass().getField(field).get(song);
                return value instanceof String ? (String) value : "";
            } catch (Exception missing) {
                return "";
            }
        }
    }

    static boolean available(String token) {
        synchronized (pending) {
            Pending value = pending.get(token);
            return value != null && value.expires > SystemClock.elapsedRealtime();
        }
    }

    static boolean resume(String token, Context context) {
        final Pending value;
        synchronized (pending) {
            value = pending.remove(token);
        }
        DownloadBatch.forget(token);
        if (value == null || value.expires <= SystemClock.elapsedRealtime()) return false;
        try {
            bypass.set(true);
            try {
                value.original.invoke(value.songs, value.from);
            } finally {
                bypass.remove();
            }
            return true;
        } catch (Exception unavailable) {
            notice(context, "BitChord could not start this download. Try again.");
            return false;
        }
    }

    static void forget(String token) {
        synchronized (pending) {
            pending.remove(token);
        }
        DownloadBatch.forget(token);
    }

    private static void notice(Context context, String phrase) {
        main.post(() -> Toast.makeText(context, PatchStrings.t(phrase), Toast.LENGTH_LONG).show());
    }
}
