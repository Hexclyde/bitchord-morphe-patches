package app.bitchord.telegram;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;

import androidx.media3.common.MediaItem;

import com.music.bitchord.data.model.Song;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        instrumentedPackages = {
            "app.bitchord.telegram.PlayerDownloadHost",
            "app.bitchord.telegram.DownloadSelection",
            "app.bitchord.telegram.AudioOrigin"
        },
        shadows = {
            PlayerDownloadAdmissionTest.Host.class,
            PlayerDownloadAdmissionTest.Pin.class,
            PlayerDownloadAdmissionTest.Origins.class
        })
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerDownloadAdmissionTest {
    static final String ORIGINAL = "2mXNRsyTitA";
    static Song recording;
    static final Map<String, Object> active = new ConcurrentHashMap<>();
    static volatile Object enqueued;
    static volatile Context enqueueContext;
    static volatile Thread enqueueThread, pinThread;
    static volatile boolean pinFails, nativeRefuses;

    static final class Queued {}

    static final class Failed {}

    @Implements(value = PlayerDownloadHost.class, isInAndroidSdk = false)
    public static class Host {
        @Implementation
        public static Object toSong(MediaItem item) {
            return recording;
        }

        @Implementation
        public static Object currentStream(MediaItem item, Uri uri) {
            return new Object();
        }

        @Implementation
        public static Object active(String id) {
            return active.get(id);
        }

        @Implementation
        public static boolean saved(String id) {
            return false;
        }

        @Implementation
        public static void enqueue(Context context, Object song) {
            assertTrue(PlayerDownload.admitting.contains(ORIGINAL));
            enqueueContext = context;
            enqueueThread = Thread.currentThread();
            enqueued = song;
            active.put(
                    MixedSongCodec.text(song, "videoId"),
                    nativeRefuses ? new Failed() : new Queued());
        }
    }

    @Implements(value = DownloadSelection.class, isInAndroidSdk = false)
    public static class Pin {
        @Implementation
        public static void keep(Context context, Object song) throws IOException {
            pinThread = Thread.currentThread();
            if (pinFails) throw new IOException("Fixture persistence failure");
        }
    }

    @Implements(value = AudioOrigin.class, isInAndroidSdk = false)
    public static class Origins {
        @Implementation
        public static AudioOrigin.Source savedSource(String id, String uri) {
            return null;
        }
    }

    public static final class Player {
        final long duration;
        final MediaItem item =
                new MediaItem.Builder()
                        .setMediaId(ORIGINAL)
                        .setUri("https://not-used.invalid/current")
                        .build();

        Player(long duration) {
            this.duration = duration;
        }

        public MediaItem getCurrentMediaItem() {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            return item;
        }

        public long getDuration() {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            return duration;
        }
    }

    Context app;
    Player currentPlayer;
    SharedPreferences stored, oldPreferences;
    java.util.concurrent.Executor oldExecutor;
    ExecutorService worker;
    AtomicInteger tasks, commits;
    AtomicBoolean failFirstCommit;
    volatile CountDownLatch entered, release;
    final List<Thread> commitThreads = new CopyOnWriteArrayList<>();
    Field preferences;

    @Before
    public void before() throws Exception {
        app = RuntimeEnvironment.getApplication();
        stored = app.getSharedPreferences("bitchord_player_download", 0);
        stored.edit().clear().commit();
        preferences = PlayerDownload.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        oldPreferences = (SharedPreferences) preferences.get(null);
        tasks = new AtomicInteger();
        commits = new AtomicInteger();
        failFirstCommit = new AtomicBoolean();
        SharedPreferences proxy =
                (SharedPreferences)
                        Proxy.newProxyInstance(
                                SharedPreferences.class.getClassLoader(),
                                new Class<?>[] {SharedPreferences.class},
                                (p, m, args) -> {
                                    if (m.getName().equals("edit")) {
                                        SharedPreferences.Editor delegate = stored.edit();
                                        return Proxy.newProxyInstance(
                                                SharedPreferences.Editor.class.getClassLoader(),
                                                new Class<?>[] {SharedPreferences.Editor.class},
                                                (editor, operation, values) -> {
                                                    if (operation.getName().equals("commit")) {
                                                        commitThreads.add(Thread.currentThread());
                                                        commits.incrementAndGet();
                                                        if (entered != null) {
                                                            entered.countDown();
                                                            if (!release.await(5, TimeUnit.SECONDS))
                                                                throw new AssertionError(
                                                                        "Admission fixture was not"
                                                                                + " released");
                                                        }
                                                        boolean success = delegate.commit();
                                                        return !failFirstCommit.getAndSet(false)
                                                                && success;
                                                    }
                                                    Object result =
                                                            operation.invoke(delegate, values);
                                                    return result
                                                                    instanceof
                                                                    SharedPreferences.Editor
                                                            ? editor
                                                            : result;
                                                });
                                    }
                                    return m.invoke(stored, args);
                                });
        preferences.set(null, proxy);
        oldExecutor = PlayerDownload.admissionWorker;
        worker =
                Executors.newSingleThreadExecutor(
                        action -> {
                            Thread t = new Thread(action, "download-admission-test");
                            t.setDaemon(true);
                            return t;
                        });
        PlayerDownload.admissionWorker =
                action -> {
                    tasks.incrementAndGet();
                    worker.execute(action);
                };
        recording = new Song(ORIGINAL, "Faint", "Linkin Park", "3:34");
        active.clear();
        PlayerDownload.admitting.clear();
        enqueued = null;
        enqueueContext = null;
        enqueueThread = null;
        pinThread = null;
        pinFails = false;
        nativeRefuses = false;
        currentPlayer = new Player(0);
        PlayerDownload.controller(currentPlayer);
    }

    void settled() throws Exception {
        worker.submit(() -> {}).get(8, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void after() throws Exception {
        if (release != null) release.countDown();
        settled();
        worker.shutdownNow();
        PlayerDownload.admissionWorker = oldExecutor;
        preferences.set(null, oldPreferences);
        PlayerDownload.controller(null);
        PlayerDownload.admitting.clear();
        Field live = PlayerDownload.class.getDeclaredField("live");
        live.setAccessible(true);
        ((Map<?, ?>) live.get(null)).clear();
        stored.edit().clear().commit();
    }

    @Test
    public void realTapPersistsPinsAndEnqueuesOffMainWithApplicationContext() throws Exception {
        PlayerDownload.start(app);
        settled();
        assertEquals(1, tasks.get());
        assertNotNull(
                "tasks="
                        + tasks.get()
                        + ", commits="
                        + commits.get()
                        + ", pin="
                        + pinThread
                        + ", prefs="
                        + stored.getAll()
                        + ", active="
                        + active
                        + ", staging="
                        + PlayerDownload.admitting,
                enqueued);
        assertSame(app, enqueueContext);
        assertNotSame(Thread.currentThread(), enqueueThread);
        assertSame(enqueueThread, pinThread);
        assertFalse(commitThreads.isEmpty());
        for (Thread t : commitThreads) assertSame(enqueueThread, t);
        String id = stored.getString("last." + ORIGINAL, "");
        assertTrue(id.startsWith(PlayerDownload.PREFIX));
        assertEquals(214, PlayerDownload.request(id).seconds);
        assertFalse(stored.getString(id, "").contains("not-used.invalid"));
        assertFalse(PlayerDownload.admitting.contains(ORIGINAL));
        assertTrue(active.get(id) instanceof Queued);
    }

    @Test
    public void repeatedTapDuringPersistenceCreatesOnlyOneQueueRequestAndTruthfulBusyState()
            throws Exception {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        PlayerDownload.start(app);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        PlayerDownload.start(app);
        assertEquals(1, tasks.get());
        assertTrue(PlayerDownload.admitting.contains(ORIGINAL));
        PlayerDownload.Entry entry = new PlayerDownload.Entry(app);
        entry.refresh();
        assertTrue(entry.busy);
        assertEquals(-1, entry.percent);
        assertFalse(entry.isEnabled());
        release.countDown();
        settled();
        assertNotNull(
                "tasks="
                        + tasks.get()
                        + ", commits="
                        + commits.get()
                        + ", pin="
                        + pinThread
                        + ", prefs="
                        + stored.getAll()
                        + ", active="
                        + active
                        + ", staging="
                        + PlayerDownload.admitting,
                enqueued);
        assertFalse(PlayerDownload.admitting.contains(ORIGINAL));
        assertEquals(1, active.size());
    }

    @Test
    public void failedCommitDoesNotPinOrEnqueueAndAllowsTheNextTap() throws Exception {
        failFirstCommit.set(true);
        PlayerDownload.start(app);
        settled();
        assertNull(enqueued);
        assertNull(pinThread);
        assertTrue(active.isEmpty());
        assertFalse(PlayerDownload.admitting.contains(ORIGINAL));
        assertEquals("", stored.getString("last." + ORIGINAL, ""));
        for (String key : stored.getAll().keySet())
            assertFalse(key.startsWith(PlayerDownload.PREFIX));
        assertEquals("Download action failed. Try again.", ShadowToast.getTextOfLatestToast());
        PlayerDownload.start(app);
        settled();
        assertNotNull(
                "tasks="
                        + tasks.get()
                        + ", commits="
                        + commits.get()
                        + ", pin="
                        + pinThread
                        + ", prefs="
                        + stored.getAll()
                        + ", active="
                        + active
                        + ", staging="
                        + PlayerDownload.admitting,
                enqueued);
        assertEquals(2, tasks.get());
    }

    @Test
    public void pinFailureDoesNotLeaveAnEnqueueOrPublishedRequest() throws Exception {
        pinFails = true;
        PlayerDownload.start(app);
        settled();
        assertNull(enqueued);
        assertNotNull(pinThread);
        assertEquals("", stored.getString("last." + ORIGINAL, ""));
        assertFalse(PlayerDownload.admitting.contains(ORIGINAL));
    }

    @Test
    public void nativeRefusalRetainsItsFailedRequestForRetryAndReleasesAdmission()
            throws Exception {
        nativeRefuses = true;
        PlayerDownload.start(app);
        settled();
        String id = stored.getString("last." + ORIGINAL, "");
        assertTrue(active.get(id) instanceof Failed);
        assertNotNull(PlayerDownload.request(id));
        assertFalse(PlayerDownload.admitting.contains(ORIGINAL));
    }

    @Test
    public void actualDurationTakesPriorityOverKnownMetadata() throws Exception {
        currentPlayer = new Player(240000);
        PlayerDownload.controller(currentPlayer);
        PlayerDownload.start(app);
        settled();
        assertEquals(240, PlayerDownload.request(stored.getString("last." + ORIGINAL, "")).seconds);
    }

    @Test
    public void unsetControllerUsesMetadataButUnknownMetadataStaysUnknown() {
        assertEquals(214, PlayerDownload.durationSeconds(0, "3:34"));
        assertEquals(214, PlayerDownload.durationSeconds(-9223372036854775807L, "3:34"));
        assertEquals(240, PlayerDownload.durationSeconds(240000, "3:34"));
        assertEquals(0, PlayerDownload.durationSeconds(0, "?"));
        assertEquals(0, PlayerDownload.durationSeconds(-1, "0:00"));
    }

    @Test
    public void oldPersistedZeroDurationRecoversOnlyKnownOriginalMetadata() throws Exception {
        String id = PlayerDownload.PREFIX + "old";
        stored.edit()
                .putString(
                        id,
                        new org.json.JSONObject()
                                .put("seconds", 0)
                                .put("song", new org.json.JSONObject().put("durationText", "3:34"))
                                .toString())
                .commit();
        assertEquals(214, PlayerDownload.request(id).seconds);
    }

    @Test
    public void constructingAndRenderingDoesNotDispatchAdmissionWork() {
        PlayerDownload.Entry entry = new PlayerDownload.Entry(app);
        entry.refresh();
        assertEquals(0, tasks.get());
        assertEquals(0, commits.get());
        assertNull(enqueued);
    }

    @Test
    public void clickingPausedRecordingResumesItsExistingUuidInsteadOfAddingAnother()
            throws Exception {
        DownloadControls.Host previousHost = DownloadControls.host;
        try {
            DownloadControls.host =
                    new DownloadControls.Host() {
                        public void cancel(String id) {
                            active.remove(id);
                        }

                        public void enqueue(Context context, Object song, String from) {
                            enqueueContext = context;
                            enqueueThread = Thread.currentThread();
                            enqueued = song;
                            active.put(MixedSongCodec.text(song, "videoId"), new Queued());
                        }

                        public boolean delete(Context context, String id) {
                            return false;
                        }
                    };
            PlayerDownload.start(app);
            settled();
            String id = stored.getString("last." + ORIGINAL, "");
            assertTrue(id.startsWith(PlayerDownload.PREFIX));
            DownloadControls.remember(app, enqueued, "Downloads");
            DownloadControls.pause(app, id);
            assertTrue(active.isEmpty());
            assertEquals(1, DownloadControls.paused(app).size());
            enqueued = null;
            PlayerDownload.start(app);
            settled();
            assertNotNull(enqueued);
            assertEquals(
                    "Paused player action must keep the native job identity",
                    id,
                    MixedSongCodec.text(enqueued, "videoId"));
            assertEquals(id, stored.getString("last." + ORIGINAL, ""));
            assertEquals(1, active.size());
            assertTrue(DownloadControls.paused(app).isEmpty());
            assertNotSame(Thread.currentThread(), enqueueThread);
            int requests = 0;
            for (String key : stored.getAll().keySet())
                if (key.startsWith(PlayerDownload.PREFIX)) requests++;
            assertEquals(1, requests);
        } finally {
            DownloadControls.host = previousHost;
            app.getSharedPreferences(DownloadControls.PREFS, 0).edit().clear().commit();
        }
    }
}
