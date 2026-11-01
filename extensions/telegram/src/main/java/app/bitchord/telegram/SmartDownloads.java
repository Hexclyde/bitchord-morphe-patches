package app.bitchord.telegram;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Smart downloads: the songs you play most are downloaded by themselves, on Wi-Fi, so they're
 * there when the signal isn't. It reads BitChord's own listening statistics (the monthly files
 * behind its Replay page), picks the most played YouTube Music songs of the last three months and
 * hands them to BitChord's download queue, in BitChord's Download quality. It only adds: a song
 * you delete isn't downloaded again, and nothing you downloaded yourself is touched.
 */
public final class SmartDownloads {
    private SmartDownloads() {}

    static final String PREFERENCES = "bitchord_smart_downloads";
    static final String FROM = "Smart downloads";
    static final int JOB_ID = 0x5D0A11;
    static final int[] COUNT_CHOICES = {10, 25, 50, 100};
    static final int MIN_PLAYS = 3;
    static final int MONTHS = 3;
    static final int PER_RUN = 25;
    static final long MIN_FREE_BYTES = 1L << 30;
    static final long PERIOD_MS = 12L * 60 * 60 * 1000;

    static volatile boolean enabled = false;
    static volatile int count = 25;
    static volatile boolean chargingOnly = true;
    static volatile String lastResult = "";

    private static volatile SharedPreferences preferences;
    private static volatile Context app;

    static void init(Context context) {
        if (preferences != null || context == null) return;
        app = context.getApplicationContext();
        SharedPreferences saved = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = saved.getBoolean("enabled", false);
        count = SteadyPlayback.nearest(saved.getInt("count", 25), COUNT_CHOICES);
        chargingOnly = saved.getBoolean("chargingOnly", true);
        lastResult = saved.getString("lastResult", "");
        preferences = saved;
        schedule();
    }

    static void setEnabled(boolean on) {
        enabled = on;
        edit().putBoolean("enabled", on).apply();
        schedule();
    }

    static void setCount(int value) {
        count = SteadyPlayback.nearest(value, COUNT_CHOICES);
        edit().putInt("count", count).apply();
    }

    static void setChargingOnly(boolean on) {
        chargingOnly = on;
        edit().putBoolean("chargingOnly", on).apply();
        schedule();
    }

    private static SharedPreferences.Editor edit() {
        SharedPreferences saved = preferences;
        if (saved == null) throw new IllegalStateException("Smart downloads not started");
        return saved.edit();
    }

    /** Songs Smart downloads has queued, so it never queues one twice. */
    static Set<String> queued() {
        SharedPreferences saved = preferences;
        return saved == null
                ? Collections.emptySet()
                : new HashSet<>(saved.getStringSet("queued", Collections.emptySet()));
    }

    // ── Scheduling ──────────────────────────────────────────────────────────

    /** A job Android runs about twice a day, on an unmetered network, while charging if asked. */
    static void schedule() {
        Context context = app;
        if (context == null) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        try {
            if (!enabled) {
                jobs.cancel(JOB_ID);
                return;
            }
            JobInfo job =
                    new JobInfo.Builder(
                                    JOB_ID, new ComponentName(context, SmartDownloadsJob.class))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                            .setRequiresCharging(chargingOnly)
                            .setRequiresStorageNotLow(true)
                            .setPeriodic(PERIOD_MS)
                            .build();
            jobs.schedule(job);
        } catch (RuntimeException e) {
            HostLog.d("Smart downloads couldn't be scheduled: " + e);
        }
    }

    /** One run: queues the most played songs that aren't downloaded yet. Background thread. */
    static int run(Context context) {
        if (!enabled || context == null) return 0;
        init(context);
        File directory = new File(context.getFilesDir(), "listening");
        List<Track> top = choose(readMonths(directory, YearMonth.now(), MONTHS), count);
        Set<String> saved = savedIds();
        Set<String> mine = queued();
        long free = freeBytes(context);
        List<Object> songs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Track track : top) {
            if (songs.size() >= PER_RUN) break;
            if (saved.contains(track.id) || mine.contains(track.id)) continue;
            if (free < MIN_FREE_BYTES) break;
            try {
                songs.add(MixedSongCodec.decode(track.song()));
                ids.add(track.id);
            } catch (Exception | LinkageError e) {
                HostLog.d("Smart downloads couldn't describe " + track.id + ": " + e);
            }
        }
        if (!songs.isEmpty()) enqueue(context, songs);
        mine.addAll(ids);
        String result =
                free < MIN_FREE_BYTES
                        ? Strings.t("Stopped: less than 1 GB free")
                        : Strings.f("Last run queued {0} songs", String.valueOf(ids.size()));
        lastResult = result;
        edit().putStringSet("queued", mine).putString("lastResult", result).apply();
        HostLog.d("Smart downloads queued " + ids.size() + " songs");
        return ids.size();
    }

    /** Hands the songs to BitChord's download queue, on the main thread like its own buttons. */
    private static void enqueue(Context context, List<Object> songs) {
        Runnable queue =
                () -> {
                    try {
                        Class<?> songType = Class.forName("com.music.bitchord.data.model.Song");
                        Method enqueue =
                                Class.forName("com.music.bitchord.download.Downloads")
                                        .getMethod(
                                                "enqueue", Context.class, songType, String.class);
                        for (Object song : songs) enqueue.invoke(null, context, song, FROM);
                    } catch (Exception | LinkageError e) {
                        HostLog.d("Smart downloads couldn't queue songs: " + e);
                    }
                };
        new Handler(Looper.getMainLooper()).post(queue);
    }

    /** Songs BitChord has downloaded, by id. */
    private static Set<String> savedIds() {
        Set<String> ids = new HashSet<>();
        try {
            Class<?> type = Class.forName("com.music.bitchord.download.Downloads");
            Field field = type.getDeclaredField("_saved");
            field.setAccessible(true);
            Object flow = field.get(null);
            Object value = flow.getClass().getMethod("getValue").invoke(flow);
            if (value instanceof Map) for (Object key : ((Map<?, ?>) value).keySet()) ids.add(String.valueOf(key));
        } catch (Exception | LinkageError e) {
            HostLog.d("Smart downloads couldn't read BitChord's downloads: " + e);
        }
        return ids;
    }

    private static long freeBytes(Context context) {
        try {
            File where = context.getExternalFilesDir(null);
            if (where == null) where = context.getFilesDir();
            return new StatFs(where.getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    // ── Choosing ────────────────────────────────────────────────────────────

    /** A song from BitChord's statistics, with its plays added up over the months read. */
    static final class Track {
        final String id;
        String title = "", artist = "", album, albumId, artistId, art;
        int plays;
        long last;

        Track(String id) {
            this.id = id;
        }

        JSONObject song() throws Exception {
            JSONObject json = new JSONObject().put("videoId", id).put("title", title).put("artist", artist);
            if (art != null) json.put("thumbnailUrl", art);
            if (album != null) json.put("albumName", album);
            if (albumId != null) json.put("albumId", albumId);
            if (artistId != null) json.put("artistId", artistId);
            return json;
        }
    }

    /** The tracks of the last few monthly statistics files, added up by song. */
    static Map<String, Track> readMonths(File directory, YearMonth now, int months) {
        Map<String, Track> tracks = new HashMap<>();
        String oldest = now.minusMonths(months - 1).toString();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return tracks;
        for (File file : files) {
            try {
                String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String month = new JSONObject(json).optString("month");
                // "2026-10" style months compare correctly as text.
                if (month.isEmpty() || month.compareTo(oldest) < 0) continue;
                add(tracks, json);
            } catch (Exception e) {
                HostLog.d("Smart downloads couldn't read " + file.getName() + ": " + e);
            }
        }
        return tracks;
    }

    static void add(Map<String, Track> tracks, String json) throws Exception {
        JSONArray list = new JSONObject(json).optJSONArray("tracks");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id");
            if (!VideoSwitch.youTubeId(id)) continue;
            Track track = tracks.get(id);
            if (track == null) tracks.put(id, track = new Track(id));
            track.plays += Math.max(0, entry.optInt("plays"));
            long last = entry.optLong("last");
            if (last >= track.last) {
                track.last = last;
                track.title = entry.optString("title", track.title);
                track.artist = entry.optString("artist", track.artist);
                track.album = text(entry, "album", track.album);
                track.albumId = text(entry, "albumId", track.albumId);
                track.artistId = text(entry, "artistId", track.artistId);
                track.art = text(entry, "art", track.art);
            }
        }
    }

    private static String text(JSONObject entry, String name, String fallback) {
        String value = entry.optString(name, "");
        return value.isEmpty() || entry.isNull(name) ? fallback : value;
    }

    /** The most played songs with at least {@link #MIN_PLAYS} plays, most recent first on ties. */
    static List<Track> choose(Map<String, Track> tracks, int limit) {
        List<Track> list = new ArrayList<>();
        for (Track track : tracks.values()) if (track.plays >= MIN_PLAYS) list.add(track);
        list.sort(
                (a, b) ->
                        a.plays != b.plays
                                ? Integer.compare(b.plays, a.plays)
                                : Long.compare(b.last, a.last));
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }
}
