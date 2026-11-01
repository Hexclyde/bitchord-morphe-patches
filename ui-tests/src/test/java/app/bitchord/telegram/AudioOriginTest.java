package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheEvictor;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Records on a real SimpleCache, through real restarts and evictions, with real host types. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class AudioOriginTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    static final String TELEGRAM_URL = "http://127.0.0.1:8080/track?token=secret";
    static final String GOOGLE_URL = "https://rr1.googlevideo.com/videoplayback?sig=secret";
    static final String ADDON_URL = "https://mirror.example/track.flac?auth=secret";
    static final byte[] AUDIO = {'f', 'L', 'a', 'C', 0, 1, 2, 3, 4, 5, 6, 7};

    Context context;
    File directory;
    StandaloneDatabaseProvider database;
    SimpleCache cache;
    Real host;

    /** The host seam over a real cache, using each Media3 API under its unmerged name. */
    class Real implements AudioOrigin.Host {
        final Map<String, AudioOrigin.Config> configs = new HashMap<>();
        AudioOrigin.Stream forced, chosen;
        boolean substitute;
        final AtomicInteger writes = new AtomicInteger();
        final Map<MediaItem, Uri> renditions = new IdentityHashMap<>();

        Real() {
            add("yt-uuid", "YouTube Music", "YOUTUBE");
            add("telegram-native", "Unofficial Telegram", "ADDON");
            add("flacdownloader-native", "Flac Downloader", "ADDON");
            add("qobuz-uuid", "Qobuz", "ADDON");
        }

        void add(String id, String name, String kind) {
            configs.put(id, new AudioOrigin.Config(id, name, kind));
        }

        @Override
        public String tag(String key, String name) {
            return cache.getContentMetadata(key).get(name, (String) null);
        }

        @Override
        public void write(String key, String[] pairs) {
            writes.incrementAndGet();
            ContentMetadataMutations mutations = new ContentMetadataMutations();
            for (int i = 0; i < pairs.length; i += 2) mutations.set(pairs[i], pairs[i + 1]);
            try {
                cache.applyContentMetadataMutations(key, mutations);
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        }

        @Override
        public boolean hasBytes(String key) {
            return !cache.getCachedSpans(key).isEmpty();
        }

        @Override
        public String keyOf(Uri uri) {
            String video = uri.getQueryParameter("v");
            String marker = uri.getQueryParameter("q");
            if (video != null) return marker == null ? video + "#alt" : video + "#" + marker;
            String base = uri.getQueryParameter("s") + "|" + uri.getQueryParameter("t");
            return marker == null ? base : base + "#" + marker;
        }

        @Override
        public Uri renditionUri(MediaItem item) {
            Uri mapped = renditions.get(item);
            return mapped == null ? AudioOrigin.uriOf(item) : mapped;
        }

        @Override
        public String requestKey(DataSpec spec) {
            return spec.key;
        }

        @Override
        public List<String> keysOf(String mediaId) {
            List<String> keys = new ArrayList<>();
            for (String key : cache.getKeys()) if (key.startsWith(mediaId)) keys.add(key);
            return keys;
        }

        @Override
        public AudioOrigin.Stream forced(Uri uri) {
            return uri.getQueryParameter("q") == null ? null : forced;
        }

        @Override
        public AudioOrigin.Stream chosen(String videoId) {
            return chosen;
        }

        @Override
        public boolean substitute(String videoId) {
            return substitute;
        }

        @Override
        public AudioOrigin.Stream stream(Object raw) {
            return (AudioOrigin.Stream) raw;
        }

        @Override
        public AudioOrigin.Config config(String id) {
            return configs.get(id);
        }

        @Override
        public AudioOrigin.Config ofKind(String kind) {
            for (AudioOrigin.Config config : configs.values())
                if (kind.equals(config.kind)) return config;
            return null;
        }

        @Override
        public Context context() {
            return context;
        }
    }

    @Before
    public void open() throws Exception {
        context = RuntimeEnvironment.getApplication();
        directory = folder.newFolder("cache");
        database = new StandaloneDatabaseProvider(context);
        cache = new SimpleCache(directory, new NoOpCacheEvictor(), database);
        host = new Real();
        AudioOrigin.host = host;
        AudioOrigin.worker = Runnable::run;
    }

    @After
    public void close() {
        if (cache != null) cache.release();
    }

    void restart(CacheEvictor evictor) {
        cache.release();
        cache = new SimpleCache(directory, evictor, database);
    }

    void fill(String key, byte[] bytes) throws Exception {
        CacheSpan hole = cache.startReadWriteNonBlocking(key, 0, bytes.length);
        File file = cache.startFile(key, 0, bytes.length);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        cache.commitFile(file, bytes.length);
        cache.releaseHoleSpan(hole);
    }

    byte[] bytesOf(String key) throws Exception {
        return Files.readAllBytes(cache.getCachedSpans(key).first().file.toPath());
    }

    static DataSpec read(String uri, String key) {
        return new DataSpec.Builder().setUri(Uri.parse(uri)).setKey(key).build();
    }

    static DataSpec served(String url) {
        return new DataSpec.Builder().setUri(Uri.parse(url)).build();
    }

    static AudioOrigin.Stream stream(String url, String id) {
        return new AudioOrigin.Stream(url, id, null, null, null, null);
    }

    static MediaItem item(String mediaId, String uri) {
        return new MediaItem.Builder().setMediaId(mediaId).setUri(uri).build();
    }

    static String stats(MediaItem item, String hostName) {
        AudioOrigin.statsItem(item);
        return AudioOrigin.statsName(hostName);
    }

    @Test
    public void telegramRenditionSurvivesRestartAndKeepsItsBytes() throws Exception {
        host.chosen =
                new AudioOrigin.Stream(TELEGRAM_URL, "telegram-native", "flac", 900, 24, 96000);
        host.substitute = true;
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        fill("abc#alt", AUDIO);
        long length = cache.getCachedBytes("abc#alt", 0, AUDIO.length);

        restart(new NoOpCacheEvictor());
        host.chosen = null;
        host.substitute = false;

        AudioOrigin.Source source = AudioOrigin.cachedSource("abc#alt");
        assertEquals("telegram-native", source.id);
        assertEquals("Unofficial Telegram", source.name);
        assertFalse(source.isYouTube());
        assertEquals("flac", source.codec);
        assertEquals(Integer.valueOf(96000), source.rate);
        assertArrayEquals("Cache bytes stay exact", AUDIO, bytesOf("abc#alt"));
        assertEquals(length, cache.getCachedBytes("abc#alt", 0, AUDIO.length));
        assertEquals(
                "Unofficial Telegram", stats(item("abc", "bitchord://watch?v=abc"), "YouTube"));
    }

    @Test
    public void noUrlOrCredentialIsPersisted() throws Exception {
        host.chosen = stream(TELEGRAM_URL, "telegram-native");
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        for (String name :
                new String[] {
                    AudioOrigin.META_ID,
                    AudioOrigin.META_NAME,
                    AudioOrigin.META_KIND,
                    AudioOrigin.META_PROOF,
                    AudioOrigin.META_CODEC
                }) {
            String value = host.tag("abc#alt", name);
            assertFalse(name, value != null && (value.contains("secret") || value.contains("://")));
        }
    }

    @Test
    public void renditionsOfOneTrackNeverCollide() throws Exception {
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc"), served(GOOGLE_URL));
        host.chosen = stream(TELEGRAM_URL, "telegram-native");
        host.substitute = true;
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        host.forced = stream(ADDON_URL, "qobuz-uuid");
        AudioOrigin.recordServed(
                read("bitchord://watch?v=abc&q=hifi-1", "abc#hifi-1"), served(ADDON_URL));
        fill("abc", AUDIO);
        fill("abc#alt", AUDIO);
        fill("abc#hifi-1", AUDIO);

        Map<String, AudioOrigin.Source> all = AudioOrigin.renditions("abc");
        assertEquals(3, all.size());
        assertTrue(all.get("abc").isYouTube());
        assertEquals("telegram-native", all.get("abc#alt").id);
        assertEquals("qobuz-uuid", all.get("abc#hifi-1").id);
        assertTrue(AudioOrigin.provenYouTube("abc"));
        assertFalse(AudioOrigin.provenYouTube("abc#alt"));
        assertFalse(AudioOrigin.provenYouTube("abc#hifi-1"));
    }

    @Test
    public void statsReadsTheActiveRenditionNotTheLatestBackgroundWrite() throws Exception {
        host.chosen = stream(TELEGRAM_URL, "telegram-native");
        host.substitute = true;
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        fill("abc#alt", AUDIO);

        // Analysis and an audition each fill their own entries while the same track plays.
        host.chosen = null;
        AudioOrigin.recordServed(
                read("bitchord://watch?v=abc&automix_opus=1", "abc#opus"), served(GOOGLE_URL));
        host.forced = stream(ADDON_URL, "qobuz-uuid");
        AudioOrigin.recordServed(
                read("bitchord://watch?v=abc&q=hifi-1", "abc#hifi-1"), served(ADDON_URL));

        assertTrue(AudioOrigin.cachedSource("abc#opus").isYouTube());
        MediaItem playing = item("abc", "bitchord://watch?v=abc");
        assertEquals("Unofficial Telegram", stats(playing, "YouTube"));
        assertEquals("Unofficial Telegram", stats(playing, "Qobuz"));
        assertEquals("telegram-native", AudioOrigin.activeSource(playing).id);

        // The swap makes the upgraded rendition the one being heard.
        assertEquals("Qobuz", stats(item("abc", "bitchord://watch?v=abc&q=hifi-1"), "Telegram"));
    }

    @Test
    public void duplicateEntriesReadTheirOwnChosenRenditions() throws Exception {
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(GOOGLE_URL));
        host.forced = stream(TELEGRAM_URL, "telegram-native");
        AudioOrigin.recordServed(
                read("bitchord://watch?v=abc&q=hifi-1", "abc#hifi-1"), served(TELEGRAM_URL));
        MediaItem first = item("abc", "bitchord://watch?v=abc");
        MediaItem later = item("abc", "bitchord://watch?v=abc");
        assertNotSame(first, later);
        host.renditions.put(later, Uri.parse("bitchord://watch?v=abc&q=hifi-1"));

        assertTrue(
                "The playing period has not switched", AudioOrigin.activeSource(first).isYouTube());
        assertEquals("telegram-native", AudioOrigin.activeSource(later).id);
        assertEquals("YouTube", stats(first, "Telegram"));
        assertEquals("Unofficial Telegram", stats(later, "YouTube"));
    }

    @Test
    public void replayBackfillUsesTheChosenSourceRow() throws Exception {
        String key = "qobuz-uuid|t1";
        fill(key, AUDIO);
        MediaItem replay = item("abc", "bitchord://watch?v=abc");
        host.renditions.put(replay, Uri.parse("bitchord://source?s=qobuz-uuid&t=t1"));
        AudioOrigin.played(replay);
        assertEquals("qobuz-uuid", host.tag(key, AudioOrigin.META_ID));
        assertNull(host.tag("abc#alt", AudioOrigin.META_ID));
    }

    @Test
    public void unknownOldCacheIsNeverYouTube() throws Exception {
        fill("old#alt", AUDIO);
        MediaItem replay = item("old", "bitchord://watch?v=old");
        assertNull(stats(replay, "YouTube"));
        assertNull(AudioOrigin.activeSource(replay));
        assertFalse(AudioOrigin.provenYouTube("old#alt"));

        ContentMetadataMutations origin = new ContentMetadataMutations();
        origin.set("bc_origin", "YOUTUBE");
        cache.applyContentMetadataMutations("old#alt", origin);
        assertNull("The substitution bucket's origin is not proof", stats(replay, "YouTube"));
        assertEquals(0, host.writes.get());
    }

    @Test
    public void provedOldCacheIsBackfilledOnceOnPlay() throws Exception {
        fill("plain", AUDIO);
        ContentMetadataMutations origin = new ContentMetadataMutations();
        origin.set("bc_origin", "YOUTUBE");
        cache.applyContentMetadataMutations("plain", origin);
        Real keyed =
                new Real() {
                    @Override
                    public String keyOf(Uri uri) {
                        String video = uri.getQueryParameter("v");
                        return video == null ? super.keyOf(uri) : video;
                    }
                };
        AudioOrigin.host = keyed;

        MediaItem replay = item("plain", "bitchord://watch?v=plain");
        AudioOrigin.played(replay);
        AudioOrigin.played(replay);
        assertEquals("Proven once, written once", 1, keyed.writes.get());
        restart(new NoOpCacheEvictor());
        assertEquals(AudioOrigin.PROOF_LEGACY_ORIGIN, AudioOrigin.cachedSource("plain").proof);
        assertEquals("YouTube", stats(replay, "Saved copy"));

        fill("qobuz-uuid|t1", AUDIO);
        AudioOrigin.played(item("src:qobuz-uuid::t1", "bitchord://source?s=qobuz-uuid&t=t1"));
        assertEquals("Qobuz", host.tag("qobuz-uuid|t1", AudioOrigin.META_NAME));
    }

    @Test
    public void evictionTakesTheRecordWithTheBytes() throws Exception {
        host.chosen = stream(TELEGRAM_URL, "telegram-native");
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        fill("abc#alt", AUDIO);
        assertNotNull(AudioOrigin.cachedSource("abc#alt"));

        cache.removeResource("abc#alt");
        assertNull(AudioOrigin.cachedSource("abc#alt"));
        assertFalse(cache.getKeys().contains("abc#alt"));

        host.chosen = stream(GOOGLE_URL, null);
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(GOOGLE_URL));
        fill("abc#alt", AUDIO);
        assertTrue("A refill starts clean", AudioOrigin.cachedSource("abc#alt").isYouTube());
    }

    @Test
    public void budgetEvictionForgetsTheRecord() throws Exception {
        restart(new LeastRecentlyUsedCacheEvictor(AUDIO.length));
        host.chosen = stream(TELEGRAM_URL, "telegram-native");
        AudioOrigin.recordServed(read("bitchord://watch?v=one", "one#alt"), served(TELEGRAM_URL));
        fill("one#alt", AUDIO);
        host.chosen = stream(ADDON_URL, "qobuz-uuid");
        AudioOrigin.recordServed(read("bitchord://watch?v=two", "two#alt"), served(ADDON_URL));
        fill("two#alt", AUDIO);

        assertTrue(cache.getCachedSpans("one#alt").isEmpty());
        assertNull("The evicted rendition's record went too", AudioOrigin.cachedSource("one#alt"));
        assertEquals("qobuz-uuid", AudioOrigin.cachedSource("two#alt").id);

        restart(new NoOpCacheEvictor());
        assertNull(AudioOrigin.cachedSource("one#alt"));
        assertEquals("qobuz-uuid", AudioOrigin.cachedSource("two#alt").id);
    }

    @Test
    public void readsBelowTheCacheAndOtherSchemesNeverTag() {
        host.chosen = stream(GOOGLE_URL, null);
        AudioOrigin.recordServed(
                new DataSpec.Builder().setUri(Uri.parse("bitchord://watch?v=abc")).build(),
                served(GOOGLE_URL));
        AudioOrigin.recordServed(read("https://example.com/a", "abc#alt"), served(GOOGLE_URL));
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), (String) null);
        assertEquals(0, host.writes.get());
    }

    @Test
    public void sourceRowUsesTheStreamThatWasServed() {
        AudioOrigin.streamResolved(stream(ADDON_URL, "qobuz-uuid"));
        AudioOrigin.recordServed(
                read("bitchord://source?s=flacdownloader-native&t=1", "flacdownloader-native|1"),
                served(ADDON_URL));
        assertEquals("qobuz-uuid", AudioOrigin.cachedSource("flacdownloader-native|1").id);

        // The capture is used once, and only for the URL it was for.
        AudioOrigin.streamResolved(stream(ADDON_URL, "qobuz-uuid"));
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        assertNull(AudioOrigin.cachedSource("abc#alt"));
        AudioOrigin.recordServed(
                read("bitchord://source?s=flacdownloader-native&t=2", "flacdownloader-native|2"),
                served(ADDON_URL));
        assertEquals(
                "Falls back on the row itself",
                "flacdownloader-native",
                AudioOrigin.cachedSource("flacdownloader-native|2").id);
    }

    @Test
    public void concurrentOpensWriteOnce() throws Exception {
        host.chosen = stream(TELEGRAM_URL, "telegram-native");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> running = new ArrayList<>();
        for (int i = 0; i < 64; i++)
            running.add(
                    pool.submit(
                            () -> {
                                start.await();
                                AudioOrigin.recordServed(
                                        read("bitchord://watch?v=abc", "abc#alt"),
                                        served(TELEGRAM_URL));
                                return null;
                            }));
        start.countDown();
        for (java.util.concurrent.Future<?> task : running) task.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, host.writes.get());
        assertEquals("telegram-native", AudioOrigin.cachedSource("abc#alt").id);
    }

    @Test
    public void concurrentResolvedStreamsKeepTheirOwnSource() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> running = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                final int entry = i;
                running.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    String id = entry % 2 == 0 ? "telegram-native" : "qobuz-uuid";
                                    AudioOrigin.streamResolved(stream(ADDON_URL, id));
                                    AudioOrigin.recordServed(
                                            read(
                                                    "bitchord://watch?v=entry" + entry,
                                                    "entry" + entry + "#alt"),
                                            served(ADDON_URL));
                                    return null;
                                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> task : running) task.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(32, host.writes.get());
        for (int i = 0; i < 32; i++)
            assertEquals(
                    i % 2 == 0 ? "telegram-native" : "qobuz-uuid",
                    AudioOrigin.cachedSource("entry" + i + "#alt").id);
    }

    @Test
    public void statsKeepsHostNamesForWhatItDoesNotRecord() {
        assertEquals("SMB", stats(item("a", "smb://nas/a.flac"), "SMB"));
        assertNull(
                "A local file is not YouTube by name alone",
                stats(item("b", "file:///b.flac"), "YouTube"));
        assertNull(stats(item("c", "file:///c.flac"), "Local files"));
        assertNull(
                "A by-id provider label cannot identify an unrecorded local file",
                stats(item("c", "content://media/external/audio/media/90"), "Qobuz"));
        assertEquals("No item", "Qobuz", stats(null, "Qobuz"));
        assertNull(stats(null, "YouTube"));
    }

    // The download path, with the host's public field names.

    public static final class Song {
        public final String videoId;

        Song(String videoId) {
            this.videoId = videoId;
        }
    }

    public static final class Route {}

    public static final class Kept {
        public final Object stream;

        Kept(Object stream) {
            this.stream = stream;
        }
    }

    public static final class Copy {
        public final String key;

        Copy(String key) {
            this.key = key;
        }
    }

    public static final class Prepared {
        public final Object route;

        Prepared(Object route) {
            this.route = route;
        }
    }

    void start(String id, Object route) {
        AudioOrigin.transferring(context, new Song(id), new Prepared(route));
    }

    Route sourceRoute(String configId) {
        AudioOrigin.sourceRouting(new Kept(stream(ADDON_URL, configId)));
        Route route = new Route();
        AudioOrigin.routed(route);
        return route;
    }

    void finish(String asked, String fetched, String uri) {
        AudioOrigin.adopting(0);
        AudioOrigin.remembered(new Song(asked), new Song(fetched), Uri.parse(uri));
    }

    void adopt(String asked, String fetched, String uri) {
        AudioOrigin.adopting(24);
        AudioOrigin.remembered(new Song(asked), new Song(fetched), Uri.parse(uri));
    }

    static final String FILE = "content://media/external/audio/media/1";

    @Test
    public void savedFileKeepsItsSourceThroughARestart() {
        start("song", sourceRoute("flacdownloader-native"));
        finish("song", "track", FILE);

        AudioOrigin.host = new Real();
        AudioOrigin.Source asked = AudioOrigin.savedSource("song", FILE);
        assertEquals("flacdownloader-native", asked.id);
        assertEquals("Flac Downloader", asked.name);
        assertEquals("Filed under both ids", asked.id, AudioOrigin.savedSource("track", FILE).id);
        assertEquals("Flac Downloader", stats(item("song", FILE), "YouTube"));

        SharedPreferences prefs = context.getSharedPreferences(AudioOriginSaved.PREFS, 0);
        assertFalse(prefs.getAll().toString().contains("media/external"));
        assertFalse(prefs.getAll().toString().contains("secret"));
    }

    @Test
    public void savedSourceIsReloadedFromPreferenceBytes() throws Exception {
        start("disk-song", sourceRoute("flacdownloader-native"));
        finish("disk-song", "disk-track", FILE);
        SharedPreferences prefs = context.getSharedPreferences(AudioOriginSaved.PREFS, 0);
        assertTrue("All preceding writes reached disk", prefs.edit().commit());
        File xml =
                new File(
                        context.getApplicationInfo().dataDir,
                        "shared_prefs/" + AudioOriginSaved.PREFS + ".xml");
        assertTrue(xml.isFile());
        java.lang.reflect.Constructor<?> load =
                Class.forName("android.app.SharedPreferencesImpl")
                        .getDeclaredConstructor(File.class, int.class);
        load.setAccessible(true);
        SharedPreferences reopened = (SharedPreferences) load.newInstance(xml, 0);
        assertNotSame(prefs, reopened);
        assertEquals("flacdownloader-native", AudioOriginSaved.get(reopened, "disk-song", FILE).id);
        assertEquals("Flac Downloader", AudioOriginSaved.get(reopened, "disk-track", FILE).name);
        assertNull(
                AudioOriginSaved.get(
                        reopened, "disk-song", "content://media/external/audio/media/2"));
        String stored = Files.readString(xml.toPath());
        assertFalse(stored.contains("secret"));
        assertFalse(stored.contains("media/external"));
    }

    @Test
    public void aDifferentFileIsNotTheRecordedOne() {
        start("song", sourceRoute("qobuz-uuid"));
        finish("song", "song", FILE);
        assertNull(AudioOrigin.savedSource("song", "content://media/external/audio/media/2"));
        assertNull(AudioOrigin.savedSource("other", FILE));
    }

    @Test
    public void forgettingADownloadForgetsItsSource() {
        start("song", sourceRoute("qobuz-uuid"));
        finish("song", "song", FILE);
        AudioOrigin.forgetSaved("song");
        assertNull(AudioOrigin.savedSource("song", FILE));
    }

    @Test
    public void youtubeFallbackIsProvenByItsRoute() {
        Route route = new Route();
        AudioOrigin.youtubeRoute(route);
        start("yt", route);
        finish("yt", "yt", FILE);
        assertTrue(AudioOrigin.savedSource("yt", FILE).isYouTube());
    }

    @Test
    public void aRouteThatFailedLeavesNoSourceForTheNext() {
        AudioOrigin.sourceRouting(new Kept(stream(ADDON_URL, "qobuz-uuid")));
        AudioOrigin.routed(null);
        Route youtube = new Route();
        AudioOrigin.youtubeRoute(youtube);
        start("song", youtube);
        finish("song", "song", FILE);
        assertTrue(AudioOrigin.savedSource("song", FILE).isYouTube());
    }

    @Test
    public void cachedCopyCarriesItsRenditionsSource() {
        host.chosen = stream(TELEGRAM_URL, "telegram-native");
        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        AudioOrigin.cacheRouting(new Copy("abc#alt"));
        Route route = new Route();
        AudioOrigin.routed(route);
        start("abc", route);
        finish("abc", "abc", FILE);
        assertEquals("telegram-native", AudioOrigin.savedSource("abc", FILE).id);
    }

    @Test
    public void adoptedFileProvesNothingAboutTheRoute() {
        start("song", sourceRoute("qobuz-uuid"));
        adopt("song", "song", FILE);
        assertNull(
                "An old file is not what the route would fetch",
                AudioOrigin.savedSource("song", FILE));

        start("src:qobuz-uuid::9", new Route());
        adopt("src:qobuz-uuid::9", "src:qobuz-uuid::9", FILE);
        assertNull(
                "A row id cannot prove the source of an adopted file",
                AudioOrigin.savedSource("src:qobuz-uuid::9", FILE));
    }

    @Test
    public void adoptedFileKeepsTheRecordThatFileAlreadyHas() {
        start("first", sourceRoute("flacdownloader-native"));
        finish("first", "first", FILE);
        start("second", sourceRoute("qobuz-uuid"));
        adopt("second", "second", FILE);
        assertEquals("flacdownloader-native", AudioOrigin.savedSource("second", FILE).id);
    }

    @Test
    public void aFailedDownloadLeavesNothingBehind() {
        start("song", sourceRoute("qobuz-uuid"));
        AudioOrigin.downloadEnded("song");
        finish("song", "song", FILE);
        assertNull(AudioOrigin.savedSource("song", FILE));
    }

    @Test
    public void aRouteOfItsOwnFallsBackOnTheRowId() {
        start("src:qobuz-uuid::9", new Route());
        finish("src:qobuz-uuid::9", "src:qobuz-uuid::9", FILE);
        AudioOrigin.Source source = AudioOrigin.savedSource("src:qobuz-uuid::9", FILE);
        assertEquals("qobuz-uuid", source.id);
        assertEquals(
                "Survives the source being removed", AudioOrigin.PROOF_LEGACY_TRACK, source.proof);
        host.configs.remove("qobuz-uuid");
        assertEquals("Qobuz", AudioOrigin.savedSource("src:qobuz-uuid::9", FILE).name);
        assertNull(
                "An older row with no record needs a live source",
                AudioOrigin.savedSource("src:qobuz-uuid::10", "file:///other.flac"));
    }

    @Test
    public void aRowResumedCoroutineChangesNothing() {
        start("song", sourceRoute("qobuz-uuid"));
        AudioOrigin.transferring(null, null, null);
        finish("song", "song", FILE);
        assertEquals("qobuz-uuid", AudioOrigin.savedSource("song", FILE).id);
    }

    // The reflective adapter, against classes shaped like the real APK's.

    public static final class FakeCache {
        static SimpleCache cache;
        public static Context appContext;

        public static String bitchordOriginRead(String key, String name) {
            return cache.getContentMetadata(key).get(name, (String) null);
        }

        public static void bitchordOriginWrite(String key, String[] pairs) throws Exception {
            ContentMetadataMutations mutations = new ContentMetadataMutations();
            for (int i = 0; i < pairs.length; i += 2) mutations.set(pairs[i], pairs[i + 1]);
            cache.applyContentMetadataMutations(key, mutations);
        }

        public static boolean bitchordOriginHasBytes(String key) {
            return !cache.getCachedSpans(key).isEmpty();
        }

        public static String cacheKeyOf(Uri uri) {
            return uri.getQueryParameter("v") + "#alt";
        }

        public static ArrayList<String> keysOf(String mediaId) {
            return new ArrayList<>(Arrays.asList(mediaId + "#alt"));
        }
    }

    public static final class FakeFormat {
        public final String codec;
        public final Integer kbps;
        public final boolean kbpsInferred;
        public final Integer bitDepth;
        public final Integer sampleRateHz;

        FakeFormat(String codec, Integer kbps, boolean inferred, Integer depth, Integer rate) {
            this.codec = codec;
            this.kbps = kbps;
            this.kbpsInferred = inferred;
            this.bitDepth = depth;
            this.sampleRateHz = rate;
        }
    }

    public static final class FakeStream {
        public final String url;
        public final String sourceConfigId;
        public final FakeFormat format;

        FakeStream(String url, String id, FakeFormat format) {
            this.url = url;
            this.sourceConfigId = id;
            this.format = format;
        }
    }

    public static final class FakeSourceFirst {
        static final Map<MediaItem, Uri> chosen = new IdentityHashMap<>();

        public static Uri renditionUri(MediaItem item) {
            return chosen.get(item);
        }
    }

    public static final class FakeUpgrade {
        public static FakeStream forcedStream(Uri uri) {
            return null;
        }
    }

    public static final class FakeChoice {
        static FakeStream current;
        static boolean substitute;

        public static FakeStream of(String id) {
            return current;
        }

        public static boolean isSubstitute(String id) {
            return substitute;
        }
    }

    public enum FakeKind {
        ADDON,
        YOUTUBE
    }

    public static final class FakeConfig {
        final String id;
        final String name;
        final FakeKind kind;

        FakeConfig(String id, String name, FakeKind kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return name;
        }

        public FakeKind getKind() {
            return kind;
        }
    }

    public interface FakeFlow {
        Object getValue();
    }

    public static final class FakeRegistry {
        static final List<FakeConfig> rows =
                Arrays.asList(
                        new FakeConfig("yt", "YouTube Music", FakeKind.YOUTUBE),
                        new FakeConfig("telegram-native", "Unofficial Telegram", FakeKind.ADDON));
        public static final FakeFlow configs = () -> rows;

        public static FakeConfig config(String id) {
            for (FakeConfig row : rows) if (row.id.equals(id)) return row;
            return null;
        }
    }

    static AudioOriginHost adapter() {
        return new AudioOriginHost(
                FakeCache.class.getName(),
                FakeUpgrade.class.getName(),
                FakeChoice.class.getName(),
                FakeRegistry.class.getName(),
                FakeFlow.class.getName());
    }

    @Test
    public void adapterDrivesTheWholeFlowThroughReflection() throws Exception {
        FakeCache.cache = cache;
        FakeCache.appContext = context;
        FakeChoice.current =
                new FakeStream(
                        TELEGRAM_URL,
                        "telegram-native",
                        new FakeFormat("flac", 320, true, 24, 96000));
        FakeChoice.substitute = true;
        AudioOriginHost adapter = adapter();
        AudioOrigin.host = adapter;

        AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(TELEGRAM_URL));
        fill("abc#alt", AUDIO);
        assertTrue(adapter.hasBytes("abc#alt"));

        restart(new NoOpCacheEvictor());
        FakeCache.cache = cache;
        AudioOrigin.Source source = AudioOrigin.cachedSource("abc#alt");
        assertEquals("telegram-native", source.id);
        assertEquals("Unofficial Telegram", source.name);
        assertEquals("ADDON", source.kind);
        assertEquals("flac", source.codec);
        assertNull("An inferred bitrate is not an advertised one", source.kbps);
        assertEquals(Integer.valueOf(24), source.bits);
        assertEquals(
                "Unofficial Telegram", stats(item("abc", "bitchord://watch?v=abc"), "YouTube"));
        assertEquals("yt", adapter.ofKind("YOUTUBE").id);
        assertEquals(1, AudioOrigin.renditions("abc").size());
        assertEquals(context, adapter.context());
    }

    @Test
    public void adapterReadsPerEntryMappingAndFallsBackWhenOptionalRuntimeIsAbsent() {
        AudioOriginHost adapter =
                new AudioOriginHost(
                        FakeCache.class.getName(),
                        FakeUpgrade.class.getName(),
                        FakeChoice.class.getName(),
                        FakeRegistry.class.getName(),
                        FakeFlow.class.getName(),
                        FakeSourceFirst.class.getName());
        MediaItem first = item("abc", "bitchord://watch?v=abc");
        MediaItem later = item("abc", "bitchord://watch?v=abc");
        Uri upgraded = Uri.parse("bitchord://watch?v=abc&q=hifi-1");
        FakeSourceFirst.chosen.put(later, upgraded);
        assertEquals(first.localConfiguration.uri, adapter.renditionUri(first));
        assertEquals(upgraded, adapter.renditionUri(later));
        assertNull(adapter.renditionUri(null));
        AudioOriginHost absent =
                new AudioOriginHost("none", "none", "none", "none", "none", "none");
        assertEquals(later.localConfiguration.uri, absent.renditionUri(later));
        FakeSourceFirst.chosen.clear();
    }

    @Test
    public void sourceRowDoesNotLabelAnUnrecordedOrReplacedFile() {
        String id = "src:qobuz-uuid::9";
        assertNull(AudioOrigin.savedSource(id, FILE));
        start(id, new Route());
        finish(id, id, FILE);
        assertEquals("qobuz-uuid", AudioOrigin.savedSource(id, FILE).id);
        assertNull(AudioOrigin.savedSource(id, "content://media/external/audio/media/2"));
    }

    @Test
    public void adapterWithoutThePatchReadsUnknownAndStaysQuiet() {
        AudioOriginHost absent =
                new AudioOriginHost(
                        "no.such.Cache",
                        "no.such.Upgrade",
                        "no.such.Choice",
                        "no.such.Registry",
                        "no.such.Flow");
        AudioOrigin.host = absent;
        assertNull(absent.tag("k", "n"));
        absent.write("k", new String[] {"n", "v"});
        assertFalse(absent.hasBytes("k"));
        assertNull(absent.config("id"));
        assertNull(absent.ofKind("YOUTUBE"));
        assertNull(absent.keyOf(Uri.parse("bitchord://watch?v=a")));
        assertTrue(absent.keysOf("a").isEmpty());
        assertNull(absent.forced(Uri.parse("bitchord://watch?v=a")));
        assertNull(absent.chosen("a"));
        assertFalse(absent.substitute("a"));
        assertNull(absent.context());
        assertNull(AudioOrigin.cachedSource("k"));
        assertNull(stats(item("a", "bitchord://watch?v=a"), "YouTube"));
    }

    @Test
    public void aReleasedCacheReadsAsUnknownAndKeepsPlaybackAlive() {
        FakeCache.cache = cache;
        AudioOrigin.host = adapter();
        cache.release();
        try {
            assertNull(AudioOrigin.cachedSource("k"));
            AudioOrigin.recordServed(read("bitchord://watch?v=abc", "abc#alt"), served(GOOGLE_URL));
            assertFalse(AudioOrigin.provenYouTube("k"));
        } finally {
            cache = new SimpleCache(directory, new NoOpCacheEvictor(), database);
        }
    }
}
