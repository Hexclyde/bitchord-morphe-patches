package app.bitchord.telegram;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;

import com.music.bitchord.data.model.Song;
import com.music.bitchord.data.spotify.LocalPlaylistStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Reading BitChord's real library surfaces, against fixtures shaped like the shipped classes. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class LocalPlaylistLibraryTest {
    private static final String HERE = LocalPlaylistLibraryTest.class.getName() + "$";

    /** StateFlowImpl in the optimized APK: a concrete class with a public getValue. */
    public static final class Flow {
        private Object value;

        Flow(Object value) {
            this.value = value;
        }

        public Object getValue() {
            return value;
        }
    }

    public static final class OfflineList {
        public final String id, title;
        public final List<Object> songs;
        private final boolean liked;

        OfflineList(String id, String title, List<Object> songs, boolean liked) {
            this.id = id;
            this.title = title;
            this.songs = songs;
            this.liked = liked;
        }

        public boolean isLiked() {
            return liked;
        }
    }

    public static final class Offline {
        public static Flow _playlists = new Flow(new ArrayList<>());
        public static Flow _liked = new Flow(null);
    }

    public static final class Likes {
        public static Flow _overrides = new Flow(new HashMap<>());
    }

    public enum Status {
        LIKE,
        DISLIKE,
        INDIFFERENT
    }

    /** Field names of the real SavedSongMetadata; the names are what reflection relies on. */
    public static final class Saved {
        private final String videoId, title, artist, thumbnailUrl, durationText, albumName;
        private final String downloadFormat, uri;
        private final Long dateAddedSeconds;

        Saved(String videoId, String title, String artist, String album, String uri, Long added) {
            this.videoId = videoId;
            this.title = title;
            this.artist = artist;
            thumbnailUrl = null;
            durationText = "3:24";
            albumName = album;
            downloadFormat = "FLAC";
            this.uri = uri;
            dateAddedSeconds = added;
        }
    }

    public static final class Saves {
        public static Flow _saved = new Flow(new HashMap<>());
        public static Flow _savedMetadata = new Flow(new HashMap<>());
    }

    public static final class Media {
        static volatile boolean allowed = true;
        static volatile List<Object> files = new ArrayList<>();
        static volatile int reads;

        public static boolean hasStoragePermission(Context context) {
            return allowed;
        }

        public static Object getLocalMusic(
                Context context, kotlin.coroutines.jvm.internal.ContinuationImpl step) {
            reads++;
            return files;
        }
    }

    /** The system's media library for this test. */
    public static final class FakeMediaStore extends ContentProvider {
        static final List<Object[]> rows = new ArrayList<>();
        static volatile String[] lastProjection;
        static volatile boolean denied;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) {
            if (denied) throw new SecurityException("No media permission");
            lastProjection = columns;
            MatrixCursor cursor = new MatrixCursor(columns);
            for (Object[] row : rows) cursor.addRow(Arrays.copyOf(row, columns.length));
            return cursor;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            throw new AssertionError("Generation must not write to the media library");
        }

        @Override
        public int delete(Uri uri, String where, String[] args) {
            throw new AssertionError("Generation must not write to the media library");
        }

        @Override
        public int update(Uri uri, ContentValues values, String where, String[] args) {
            throw new AssertionError("Generation must not write to the media library");
        }
    }

    private Context app;
    private ExecutorService worker;

    @Before
    public void reset() throws Exception {
        app = RuntimeEnvironment.getApplication();
        worker = Executors.newSingleThreadExecutor();
        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        Offline._playlists = new Flow(new ArrayList<>());
        Offline._liked = new Flow(null);
        Likes._overrides = new Flow(new HashMap<>());
        Saves._saved = new Flow(new HashMap<>());
        Saves._savedMetadata = new Flow(new HashMap<>());
        Media.allowed = true;
        Media.files = new ArrayList<>();
        Media.reads = 0;
        FakeMediaStore.rows.clear();
        FakeMediaStore.denied = false;
        ProviderInfo info = new ProviderInfo();
        info.authority = MediaStore.AUTHORITY;
        Robolectric.buildContentProvider(FakeMediaStore.class).create(info);
    }

    @After
    public void stop() {
        worker.shutdownNow();
    }

    private static Song song(String id, String title, String artist) throws Exception {
        return (Song)
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", id)
                                .put("title", title)
                                .put("artist", artist));
    }

    private static Song file(String id, String title, String artist, String uri) throws Exception {
        return (Song)
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", id)
                                .put("title", title)
                                .put("artist", artist)
                                .put("localUri", uri));
    }

    private static void savePlaylist(String title, Object... songs) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> list =
                new ArrayList<>((List<Object>) LocalPlaylistStore._playlists.getValue());
        list.add(
                MixedPlaylists.playlist(
                        "bc_mix_" + list.size(), title, new ArrayList<>(Arrays.asList(songs))));
        LocalPlaylistStore._playlists.setValue(list);
    }

    private LocalPlaylistLibrary.Reflected host(BooleanSupplier cancelled) {
        return new LocalPlaylistLibrary.Reflected(
                app, cancelled, HERE + "Offline", HERE + "Likes", HERE + "Saves", HERE + "Media");
    }

    /** Off the main thread, as in the app, while the main thread keeps serving the queue read. */
    private <T> T offMain(Callable<T> work) throws Exception {
        Future<T> task = worker.submit(work);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            try {
                return task.get(5, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // Keep the main looper moving.
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
                throw failed;
            }
        }
        throw new AssertionError("Still running");
    }

    private LocalPlaylistLibrary.Library read() throws Exception {
        return offMain(() -> LocalPlaylistLibrary.collect(host(null), null));
    }

    private static LocalPlaylistEngine.Entry entry(
            LocalPlaylistLibrary.Library library, String title) {
        for (LocalPlaylistEngine.Entry entry : library.entries)
            if (entry.title.equals(title)) return entry;
        throw new AssertionError("Missing " + title);
    }

    @Test
    public void readsEverySurfaceAndKeepsRealIdentities() throws Exception {
        Song catalogue = song("abcdefghijk", "Catalogue", "Band");
        Song telegram = song("src:telegram-native::-100_5", "Channel", "Band");
        Song peer = song("src:soulseek-9::user\\track.flac", "Peer", "Crew");
        savePlaylist("Road trip", catalogue, telegram, peer);

        Song offlineSong = song("src:deezer-3::77", "Offline", "Crew");
        Song likedSong = song("src:qobuz-1::8", "Liked", "Crew");
        Offline._playlists =
                new Flow(
                        List.of(
                                new OfflineList(
                                        "local:offline:1", "Evening", List.of(offlineSong), false),
                                new OfflineList("liked", "Liked songs", List.of(likedSong), true)));
        Offline._liked =
                new Flow(new OfflineList("liked", "Liked songs", List.of(likedSong), true));

        File saved = File.createTempFile("saved", ".flac");
        Map<String, String> paths = new HashMap<>();
        Map<String, Object> metadata = new HashMap<>();
        paths.put("abcdefghijk", "file://" + saved.getPath());
        metadata.put(
                "abcdefghijk",
                new Saved("abcdefghijk", "Catalogue", "Band", "Saved Album", "x", 1234L));
        paths.put("src:hifi-1::4", "file://" + saved.getPath() + "-2");
        metadata.put("src:hifi-1::4", new Saved("src:hifi-1::4", "Gone", "Crew", "", "x", 5L));
        File second = File.createTempFile("second", ".flac");
        paths.put("src:hifi-1::5", "file://" + second.getPath());
        metadata.put("src:hifi-1::5", new Saved("src:hifi-1::5", "Download", "Crew", "", "x", 6L));
        paths.put("src:hifi-1::6", "file://" + second.getPath());
        Saves._saved = new Flow(paths);
        Saves._savedMetadata = new Flow(metadata);

        Likes._overrides =
                new Flow(
                        new HashMap<>(
                                Map.of(
                                        "abcdefghijk",
                                        Status.LIKE,
                                        "src:deezer-3::77",
                                        Status.DISLIKE,
                                        "src:soulseek-9::user\\track.flac",
                                        Status.INDIFFERENT)));

        String media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "/42";
        Song local = file("local:hash", "On Device", "<unknown>", media);
        Media.files = new ArrayList<>(List.of(local));
        FakeMediaStore.rows.add(new Object[] {42L, 1998, "Trip Hop"});
        FakeMediaStore.rows.add(new Object[] {43L, 0, "<unknown>"});

        File listening = new File(app.getFilesDir(), "listening");
        listening.mkdirs();
        Files.write(
                new File(listening, "2026-10.json").toPath(),
                ("{\"month\":\"2026-10\",\"tracks\":["
                     + "{\"id\":\"abcdefghijk\",\"title\":\"Catalogue\",\"artist\":\"Band\",\"plays\":6,\"last\":1},{\"id\":\"local:hash\",\"title\":\"On"
                     + " Device\",\"artist\":\"\",\"plays\":2,\"last\":1},{\"id\":\"src:tidal-2::9\",\"title\":\"Heard\",\"artist\":\"Crew\",\"album\":\"Heard"
                     + " Album\",\"plays\":4,\"last\":1}]}")
                        .getBytes(StandardCharsets.UTF_8));

        LocalPlaylistLibrary.Library library = read();

        assertSame(catalogue, entry(library, "Catalogue").song);
        assertEquals("Saved Album", entry(library, "Catalogue").album);
        assertEquals(Long.valueOf(1234), entry(library, "Catalogue").addedSeconds);
        assertEquals(Integer.valueOf(6), entry(library, "Catalogue").plays);
        assertEquals(Boolean.TRUE, entry(library, "Catalogue").favorite);
        assertTrue(entry(library, "Catalogue").playlists.contains("Road trip"));

        assertSame(telegram, entry(library, "Channel").song);
        assertSame(peer, entry(library, "Peer").song);
        assertNull(entry(library, "Peer").favorite);
        assertEquals(
                "stream:src:soulseek-9::user\\track.flac",
                MixedSongCodec.key(entry(library, "Peer").song));

        assertSame(offlineSong, entry(library, "Offline").song);
        assertTrue(entry(library, "Offline").disliked);
        assertTrue(entry(library, "Offline").playlists.contains("Evening"));
        assertEquals(Boolean.TRUE, entry(library, "Liked").favorite);
        assertFalse(entry(library, "Liked").playlists.contains("Liked songs"));

        Song download = (Song) entry(library, "Download").song;
        assertEquals("src:hifi-1::5", download.videoId);
        assertEquals("file://" + second.getPath(), download.localUri);
        assertEquals("3:24", download.durationText);
        assertEquals(Long.valueOf(204), entry(library, "Download").durationSeconds);
        for (LocalPlaylistEngine.Entry each : library.entries) {
            assertNotEquals("A deleted download isn't offered", "Gone", each.title);
            assertNotEquals("No metadata, no invented name", "src:hifi-1::6", each.title);
        }

        assertSame(local, entry(library, "On Device").song);
        assertEquals("", entry(library, "On Device").artist);
        assertEquals(Integer.valueOf(1998), entry(library, "On Device").year);
        assertEquals("Trip Hop", entry(library, "On Device").genre);
        assertEquals(Integer.valueOf(2), entry(library, "On Device").plays);
        assertEquals(1, Media.reads);
        assertArrayEquals(
                new String[] {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.YEAR, "genre"},
                FakeMediaStore.lastProjection);

        Song heard = (Song) entry(library, "Heard").song;
        assertEquals("src:tidal-2::9", heard.videoId);
        assertEquals("Heard Album", heard.albumName);
        assertNull(entry(library, "Heard").genre);
        assertNull(entry(library, "Heard").year);
    }

    @Test
    public void nothingPlayingIsAnEmptyQueueNotAFailure() throws Exception {
        savePlaylist("One", song("abcdefghijk", "Kept", "Band"));
        LocalPlaylistLibrary.Library library = read();
        assertEquals(1, library.entries.size());
        assertEquals(0, library.unreadable);
        assertFalse(library.unavailable());
    }

    /** Media3 refuses other threads, so this player does too. */
    public static final class PlayingService {
        final androidx.media3.common.Player player =
                (androidx.media3.common.Player)
                        java.lang.reflect.Proxy.newProxyInstance(
                                PlayingService.class.getClassLoader(),
                                new Class<?>[] {androidx.media3.common.Player.class},
                                (proxy, method, args) -> {
                                    if (Looper.myLooper() != Looper.getMainLooper())
                                        throw new IllegalStateException("Wrong thread");
                                    switch (method.getName()) {
                                        case "getCurrentMediaItemIndex":
                                            return 0;
                                        case "getMediaItemCount":
                                            return 2;
                                        case "getShuffleModeEnabled":
                                            return false;
                                        case "getCurrentTimeline":
                                            return androidx.media3.common.Timeline.EMPTY;
                                        case "getMediaItemAt":
                                            return new androidx.media3.common.MediaItem.Builder()
                                                    .setMediaId("src:qobuz-1::" + args[0])
                                                    .build();
                                        default:
                                            throw new AssertionError(method.getName());
                                    }
                                });
    }

    @Test
    public void theQueueIsReadOnTheMainThreadAndKeepsItsIds() throws Exception {
        PlayingService service = new PlayingService();
        GuestPlaylists.service(service);
        try {
            List<Object> queue = offMain(() -> host(null).queue());
            assertEquals(2, queue.size());
            assertEquals("src:qobuz-1::0", ((Song) queue.get(0)).videoId);
            LocalPlaylistLibrary.Library library = read();
            assertEquals(0, library.unreadable);
        } finally {
            GuestPlaylists.release(service);
        }
    }

    @Test
    public void aBusyMainThreadOnlyCostsTheQueue() throws Exception {
        PlayingService service = new PlayingService();
        GuestPlaylists.service(service);
        try {
            savePlaylist("One", song("abcdefghijk", "Kept", "Band"));
            // Nothing runs the main looper here, as when it's stuck on something long.
            Future<LocalPlaylistLibrary.Library> read =
                    worker.submit(() -> LocalPlaylistLibrary.collect(host(null), null));
            LocalPlaylistLibrary.Library library = read.get(15, TimeUnit.SECONDS);
            assertEquals(1, library.entries.size());
            assertEquals(1, library.unreadable);
        } finally {
            GuestPlaylists.release(service);
        }
    }

    @Test
    public void noMediaPermissionIsNoTagsNotAFailure() throws Exception {
        FakeMediaStore.denied = true;
        savePlaylist("One", song("abcdefghijk", "Kept", "Band"));
        LocalPlaylistLibrary.Library library = read();
        assertEquals(1, library.entries.size());
        assertEquals(0, library.unreadable);
    }

    @Test
    public void aBitChordWithoutOptionalClassesStillWorks() throws Exception {
        savePlaylist("One", song("abcdefghijk", "Kept", "Band"));
        LocalPlaylistLibrary.Library library =
                offMain(
                        () ->
                                LocalPlaylistLibrary.collect(
                                        new LocalPlaylistLibrary.Reflected(
                                                app,
                                                null,
                                                "absent.Offline",
                                                "absent.Likes",
                                                "absent.Downloads",
                                                "absent.Media"),
                                        null));
        assertEquals(1, library.entries.size());
        assertEquals("Kept", library.entries.get(0).title);
        assertEquals(4, library.unreadable);
        assertTrue(library.listeningKnown);
    }

    @Test
    public void localFilesWaitForPermissionAndAReadThatFailsIsOnlyThatPart() throws Exception {
        Media.allowed = false;
        Media.files = new ArrayList<>(List.of(file("local:a", "Private", "", "content://x/1")));
        assertTrue(read().entries.isEmpty());
        assertEquals(0, Media.reads);
    }

    @Test
    public void generateUsesTheRealPathFromPlaylistToResult() throws Exception {
        Song catalogue = song("abcdefghijk", "Paranoid Android", "Radiohead");
        Song channel = song("src:telegram-native::-100_5", "Karma Police", "Radiohead");
        Song other = song("src:deezer-3::1", "Dancing Queen", "Abba");
        savePlaylist("Saved", catalogue, channel, other);

        List<Object> songs =
                offMain(() -> LocalPlaylist.generate(app, "radiohead", 10, true, false, null));
        assertEquals(new HashSet<>(List.of(catalogue, channel)), new HashSet<>(songs));
        for (Object each : songs) assertNotSame(other, each);

        LocalPlaylist.Result empty =
                offMain(() -> LocalPlaylist.run(app, "something by nobody", 10, true, false, null));
        assertTrue(empty.songs.isEmpty());
        assertEquals(LocalPlaylist.Problem.NO_MATCH, empty.problem);
        assertEquals(3, empty.available);
        assertNotNull(empty.message());

        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        LocalPlaylist.Result none =
                offMain(() -> LocalPlaylist.run(app, "anything", 10, true, false, null));
        assertEquals(LocalPlaylist.Problem.EMPTY_LIBRARY, none.problem);
    }

    @Test
    public void generatingChangesNothing() throws Exception {
        Song kept = song("abcdefghijk", "Kept", "Band");
        savePlaylist("One", kept);
        Object before = LocalPlaylistStore._playlists.getValue();
        Set<String> prefsBefore = sharedPreferenceFiles();
        offMain(() -> LocalPlaylist.generate(app, "band", 10, true, true, null));
        assertSame(before, LocalPlaylistStore._playlists.getValue());
        assertEquals(1, ((List<?>) before).size());
        assertTrue(com.music.bitchord.download.Downloads.queued.isEmpty());
        assertEquals(prefsBefore, sharedPreferenceFiles());
        SharedPreferences mixed = app.getSharedPreferences(MixedPlaylists.PREFERENCES, 0);
        assertTrue(mixed.getAll().isEmpty());
    }

    private Set<String> sharedPreferenceFiles() {
        File directory = new File(app.getApplicationInfo().dataDir, "shared_prefs");
        String[] names = directory.list();
        return names == null ? new HashSet<>() : new HashSet<>(Arrays.asList(names));
    }

    @Test
    public void generateRefusesTheMainThread() {
        assertThrows(
                IllegalStateException.class,
                () -> LocalPlaylist.generate(app, "rock", 5, true, false, null));
    }

    @Test
    public void generateCanBeCancelledAtAnyStage() throws Exception {
        savePlaylist("One", song("abcdefghijk", "Kept", "Band"));
        ExecutionException stopped =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                worker.submit(
                                                () ->
                                                        LocalPlaylist.generate(
                                                                app,
                                                                "band",
                                                                10,
                                                                true,
                                                                false,
                                                                () -> true))
                                        .get(10, TimeUnit.SECONDS));
        assertTrue(stopped.getCause() instanceof InterruptedException);
    }

    @Test
    public void aSlowHostReadStopsWhenCancelled() throws Exception {
        SlowMedia.gate = new CountDownLatch(1);
        CountDownLatch cancel = new CountDownLatch(1);
        long started = System.nanoTime();
        LocalPlaylistLibrary.Reflected slow =
                new LocalPlaylistLibrary.Reflected(
                        app,
                        () -> cancel.getCount() == 0,
                        HERE + "Offline",
                        HERE + "Likes",
                        HERE + "Saves",
                        HERE + "SlowMedia");
        Future<List<Object>> read = worker.submit(slow::localFiles);
        assertTrue(SlowMedia.entered.await(5, TimeUnit.SECONDS));
        cancel.countDown();
        ExecutionException stopped =
                assertThrows(ExecutionException.class, () -> read.get(5, TimeUnit.SECONDS));
        assertTrue(stopped.getCause() instanceof InterruptedException);
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10));
        SlowMedia.gate.countDown();
    }

    public static final class SlowMedia {
        static volatile CountDownLatch gate;
        static final CountDownLatch entered = new CountDownLatch(1);

        public static boolean hasStoragePermission(Context context) {
            return true;
        }

        public static Object getLocalMusic(
                Context context, kotlin.coroutines.jvm.internal.ContinuationImpl step)
                throws Exception {
            entered.countDown();
            gate.await(30, TimeUnit.SECONDS);
            return new ArrayList<>();
        }
    }

    @Test
    public void aTaggedDownloadAnswersATempoRequest() throws Exception {
        File tagged = File.createTempFile("tagged", ".mp3");
        Files.write(tagged.toPath(), id3Tempo("126"));
        File plain = File.createTempFile("plain", ".mp3");
        Files.write(plain.toPath(), new byte[256]);
        savePlaylist(
                "Files",
                file("local:tagged", "Tagged", "Band", "file://" + tagged.getPath()),
                file("local:plain", "Plain", "Band", "file://" + plain.getPath()),
                song("abcdefghijk", "Stream", "Band"));
        LocalPlaylist.Result result =
                offMain(() -> LocalPlaylist.run(app, "125 bpm", 10, true, false, null));
        assertEquals(1, result.picks.size());
        LocalPlaylist.Song pick = result.picks.get(0);
        assertEquals("Tagged", MixedSongCodec.text(pick.song, "title"));
        assertTrue(pick.why.contains(LocalPlaylistEngine.Why.TEMPO_TAGGED));
        assertFalse(pick.why.contains(LocalPlaylistEngine.Why.TEMPO_MEASURED));
        assertEquals(126, pick.bpm, 0);
        assertEquals(
                "Tagged 126 BPM",
                LocalPlaylist.Result.reason(LocalPlaylistEngine.Why.TEMPO_TAGGED, pick.bpm));
        assertEquals(2, result.unverified.size());
    }

    @Test
    @Config(sdk = 28)
    public void olderAndroidReadsYearWithoutGenre() throws Exception {
        FakeMediaStore.rows.add(new Object[] {7L, 2001});
        Map<String, LocalPlaylistLibrary.MediaTags> tags =
                offMain(() -> LocalPlaylistLibrary.readMediaTags(app, null));
        assertEquals(Integer.valueOf(2001), tags.get("media:7").year);
        assertNull(tags.get("media:7").genre);
        assertEquals(2, FakeMediaStore.lastProjection.length);
    }

    private static byte[] id3Tempo(String value) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream frame = new DataOutputStream(body);
        byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
        frame.writeBytes("TBPM");
        frame.writeInt(text.length + 1);
        frame.writeShort(0);
        frame.write(0);
        frame.write(text);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream head = new DataOutputStream(file);
        head.writeBytes("ID3");
        head.write(3);
        head.write(0);
        head.write(0);
        head.write(0);
        head.write(0);
        head.write(body.size() >> 7 & 127);
        head.write(body.size() & 127);
        head.write(body.toByteArray());
        head.write(new byte[64]);
        return file.toByteArray();
    }

    /** The members every read relies on, checked by name, as the shipped classes declare them. */
    @Test
    public void reflectionSeesFixturesTheWayItSeesTheApp() throws Exception {
        for (String name : new String[] {"_playlists", "_liked"})
            assertNotNull(Offline.class.getDeclaredField(name));
        Method read =
                Media.class.getMethod(
                        "getLocalMusic",
                        Context.class,
                        kotlin.coroutines.jvm.internal.ContinuationImpl.class);
        assertNotNull(read);
        assertEquals(
                "com.music.bitchord.data.OfflinePlaylists", LocalPlaylistLibrary.Reflected.OFFLINE);
        assertEquals("com.music.bitchord.data.LikeState", LocalPlaylistLibrary.Reflected.LIKES);
        assertEquals(
                "com.music.bitchord.download.Downloads", LocalPlaylistLibrary.Reflected.DOWNLOADS);
        assertEquals(
                "com.music.bitchord.data.LocalMediaRepository",
                LocalPlaylistLibrary.Reflected.LOCAL_MEDIA);
    }
}
