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
    private static final long LEASE = 120_000;

    private SoulseekDownloadChoice() {}

    private static final class Pending {
        final Function2<Object, Object, Object> original;
        final List<?> songs;
        final Object from;
        final long expires = SystemClock.elapsedRealtime() + LEASE;

        Pending(Function2<Object, Object, Object> original, List<?> songs, Object from) {
            this.original = original;
            this.songs = new ArrayList<>(songs);
            this.from = from;
        }
    }

    /** The only patched call site is the manual downloadSong singleton-list dispatch. */
    @SuppressWarnings("unchecked")
    public static Object offer(Object original, Object songs, Object from) {
        if (!(original instanceof Function2))
            throw new IllegalArgumentException("Invalid download continuation");
        Function2<Object, Object, Object> continuation =
                (Function2<Object, Object, Object>) original;
        if (!(songs instanceof List) || !eligible((List<?>) songs, from))
            return continuation.invoke(songs, from);
        Context app = null;
        boolean enabled = false;
        try {
            app = SoulseekBridge.get().context;
            enabled = DownloadSettings.enabled(app, DownloadSettings.SOULSEEK);
        } catch (Exception unavailable) {
            // Before bridge boot the unmodified player remains usable.
        }
        // The original callback may itself throw. Never catch it as a bridge failure
        // and retry a second time: it owns permissions, queue admission and other patches.
        if (app == null || !enabled) return continuation.invoke(songs, from);
        final Context context = app;
        String token = UUID.randomUUID().toString();
        synchronized (pending) {
            pending.values().removeIf(value -> value.expires <= SystemClock.elapsedRealtime());
            if (pending.size() >= 8) {
                notice(context, "Finish the open download choice first.");
                return Unit.INSTANCE;
            }
            pending.put(token, new Pending(continuation, (List<?>) songs, from));
        }
        main.postDelayed(() -> forget(token), LEASE);
        try {
            Object song = ((List<?>) songs).get(0);
            String artist = value(song, "getArtist"), title = value(song, "getTitle");
            String query = (artist + " " + title).trim();
            if (query.length() > 256) query = query.substring(0, 256);
            context.startActivity(
                    new Intent(context, SoulseekSearchActivity.class)
                            .putExtra(EXTRA_CHOICE, token)
                            .putExtra(EXTRA_QUERY, query)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception unavailable) {
            forget(token);
            notice(context, "Could not open the download source choice. Try again.");
        }
        return Unit.INSTANCE;
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
        if (value == null || value.expires <= SystemClock.elapsedRealtime()) return false;
        try {
            value.original.invoke(value.songs, value.from);
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
    }

    private static void notice(Context context, String phrase) {
        main.post(() -> Toast.makeText(context, PatchStrings.t(phrase), Toast.LENGTH_LONG).show());
    }
}
