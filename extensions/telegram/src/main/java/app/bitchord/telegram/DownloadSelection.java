package app.bitchord.telegram;

import android.content.Context;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Persisted source choices shared by independent download patches. */
public final class DownloadSelection {
    private DownloadSelection() {}

    private static final Set<String> pinned = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<String> filenameSelection = new ThreadLocal<>();
    private static final String PREFS = "bitchord_download_explorer";

    public static synchronized void init(Context context) {
        pinned.addAll(
                context.getSharedPreferences(PREFS, 0)
                        .getStringSet("pinned", Collections.emptySet()));
    }

    private static String text(Object song, String field) {
        try {
            Field member = song.getClass().getDeclaredField(field);
            member.setAccessible(true);
            Object value = member.get(song);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException absent) {
            return "";
        }
    }

    public static synchronized void keep(Context context, Object song) throws IOException {
        init(context);
        String reference = text(song, "videoId");
        if (!reference.startsWith("src:") || reference.indexOf("::") < 5) return;
        if (pinned.contains(reference)) return;
        if (pinned.size() >= 4096 && !pinned.contains(reference))
            throw new IOException("Download selection history is full");
        Set<String> next = new HashSet<>(pinned);
        next.add(reference);
        if (!context.getSharedPreferences(PREFS, 0)
                .edit()
                .putStringSet("pinned", next)
                .putString("title." + reference, text(song, "title"))
                .commit()) throw new IOException("Could not save the selected source");
        pinned.add(reference);
    }

    public static void remember(Context context, Object song, String from) {
        init(context);
        if (text(song, "videoId").startsWith("src:flacdownloader-native::")) {
            try {
                keep(context, song);
            } catch (IOException failed) {
                throw new IllegalStateException("Could not save the selected file", failed);
            }
        }
        DownloadControls.remember(context, song, from);
    }

    public static boolean forbidFallback(Object song) {
        return pinned.contains(text(song, "videoId"));
    }

    public static void fileNameStart(Object song) {
        DownloadFolder.naming(song);
        String reference = text(song, "videoId");
        if (pinned.contains(reference)) filenameSelection.set(reference);
        else filenameSelection.remove();
    }

    public static String fileNameFinish(String name) {
        String reference = filenameSelection.get();
        filenameSelection.remove();
        String result = reference == null ? name : selectedName(reference, name);
        DownloadFolder.named(result);
        return result;
    }

    public static String selectedFileName(Object song, String name) {
        String reference = text(song, "videoId");
        return pinned.contains(reference) ? selectedName(reference, name) : name;
    }

    private static String selectedName(String reference, String name) {
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                key.append("0123456789abcdef".charAt((digest[i] & 255) >>> 4));
                key.append("0123456789abcdef".charAt(digest[i] & 15));
            }
            String suffix = " [" + key + "]";
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 ? name.substring(dot) : "";
            String stem = dot > 0 ? name.substring(0, dot) : name;
            int end = Math.min(stem.length(), 120 - suffix.length());
            while (end > 0
                    && (Character.isHighSurrogate(stem.charAt(end - 1))
                            || stem.substring(0, end)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                            .length
                                    > 220)) end--;
            return stem.substring(0, end).trim() + suffix + extension;
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "Selected download identity is unavailable", unavailable);
        }
    }
}
