package app.bitchord.soulseek;

import android.content.Context;

import com.music.bitchord.data.settings.AppSettings;
import com.music.bitchord.data.sources.SourceRegistry;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class SoulseekSelectedDownloadsTest {
    static int checks;
    static final String PREFIX = "src:soulseek-native::";

    public static final class Song {
        final String videoId;

        public Song(String id) {
            videoId = id;
        }

        public String getVideoId() {
            return videoId;
        }
    }

    public static final class BadSong {
        public Integer getVideoId() {
            return 1;
        }
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static String id(int index) {
        return String.format(java.util.Locale.ROOT, "%064x", index);
    }

    static Song song(int index) {
        SoulseekBridge.instance.tracks.put(id(index), new SoulseekTrack(id(index), 4));
        return new Song(PREFIX + id(index));
    }

    static ThreadPoolExecutor worker() throws Exception {
        Field field = SoulseekSelectedDownloads.class.getDeclaredField("WORK");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    static int tasks() throws Exception {
        Field field = SoulseekSelectedDownloads.class.getDeclaredField("TASKS");
        field.setAccessible(true);
        return ((ConcurrentHashMap<?, ?>) field.get(null)).size();
    }

    static void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((tasks() != 0 || worker().getActiveCount() != 0 || !worker().getQueue().isEmpty())
                && System.nanoTime() < deadline) Thread.sleep(1);
        check(
                tasks() == 0 && worker().getActiveCount() == 0,
                "Selected download workers must finish");
    }

    static Context fresh() throws Exception {
        idle();
        SoulseekBridge.instance = new SoulseekBridge();
        AppSettings.allowed = true;
        SourceRegistry.enabled = true;
        SourceRegistry.missing = false;
        DownloadDestinations.missing = false;
        DownloadDestinations.current = new DownloadDestinations.Snapshot("phone");
        AudioFormatPreferences.current = new AudioFormatPreferences.Settings("original", true);
        AudioFormatPreferences.failVerification = false;
        AudioFormatPreferences.flipAfterSnapshot = false;
        AudioFormatPreferences.snapshots = 0;
        AudioFormatPreferences.verified = null;
        BulkDownloads.imports = 0;
        BulkDownloads.bytes = null;
        SoulseekDownloads.fetches = 0;
        SoulseekDownloads.entered = null;
        SoulseekDownloads.release = null;
        SoulseekDownloads.pauseBeforeReturn = false;
        return new Context(Files.createTempDirectory("selected-download-").toFile());
    }

    static void sourceOwnership() throws Exception {
        Context app = fresh();
        check(
                !SoulseekSelectedDownloads.enqueueOrFalse(app, null),
                "Null coroutine placeholder leaves the host path unchanged");
        check(
                !SoulseekSelectedDownloads.enqueueOrFalse(app, new Object()),
                "An unrelated object does not intercept downloads");
        check(
                !SoulseekSelectedDownloads.enqueueOrFalse(app, new BadSong()),
                "A non-string videoId cannot crash host enqueue");
        check(
                !SoulseekSelectedDownloads.enqueueOrFalse(app, new Song("ordinary-video")),
                "Ordinary sources keep the host route");
        check(
                SoulseekSelectedDownloads.enqueueOrFalse(app, new Song(PREFIX + "bad")),
                "Malformed owned references fail closed");
        check(
                SoulseekSelectedDownloads.status().contains("Invalid selected"),
                "Malformed selected reference is actionable");
        check(
                SoulseekDownloads.fetches == 0 && tasks() == 0,
                "Malformed references cannot start a source transfer");
        AppSettings.allowed = false;
        check(
                SoulseekSelectedDownloads.enqueueOrFalse(app, song(1)),
                "A paused owned route never falls through to a phone output");
        check(
                SoulseekSelectedDownloads.status().contains("paused"),
                "Host download pause is preserved");
        AppSettings.allowed = true;
        SourceRegistry.enabled = false;
        check(
                SoulseekSelectedDownloads.enqueueOrFalse(app, song(1)),
                "Disabled source remains owned and fails closed");
        check(SoulseekDownloads.fetches == 0, "A disabled source does not fetch");
        SourceRegistry.enabled = true;
        SourceRegistry.missing = true;
        check(
                SoulseekSelectedDownloads.enqueueOrFalse(app, song(1)),
                "Missing source configuration cannot fall through");
        SourceRegistry.missing = false;
        check(
                SoulseekSelectedDownloads.enqueueOrFalse(app, new Song(PREFIX + id(999))),
                "Expired catalog selection remains owned");
        check(
                SoulseekDownloads.fetches == 0,
                "An expired selection is not replaced by a new search");
    }

    static void setupAndBinding() throws Exception {
        Context app = fresh();
        DownloadDestinations.missing = true;
        SoulseekSelectedDownloads.enqueueOrFalse(app, song(1));
        check(
                app.launches.contains(DownloadDestinationActivity.class),
                "Missing destination opens first setup");
        check(
                SoulseekDownloads.fetches == 0 && BulkDownloads.imports == 0,
                "Destination setup cannot be bypassed");
        DownloadDestinations.missing = false;
        AudioFormatPreferences.current = new AudioFormatPreferences.Settings("unconfirmed", false);
        SoulseekSelectedDownloads.enqueueOrFalse(app, song(1));
        check(
                app.launches.contains(AudioFormatSetupActivity.class),
                "Unconfirmed formats open format setup");
        check(SoulseekDownloads.fetches == 0, "Format setup cannot be bypassed");
        AudioFormatPreferences.current = new AudioFormatPreferences.Settings("original", true);
        SoulseekBridge.instance.changeSessionOnLookup = true;
        SoulseekSelectedDownloads.enqueueOrFalse(app, song(1));
        check(
                SoulseekSelectedDownloads.status().contains("session changed"),
                "A lookup crossing login sessions is rejected");
        check(
                SoulseekDownloads.fetches == 0 && tasks() == 0,
                "Session race cannot queue an old catalog row on a new login");
        SoulseekBridge.instance.tracks.put(
                id(2), new SoulseekTrack(id(2), SoulseekBridge.MAX_CACHE_BYTES + 1));
        SoulseekSelectedDownloads.enqueueOrFalse(app, new Song(PREFIX + id(2)));
        check(
                SoulseekDownloads.fetches == 0,
                "Oversized selected files are rejected before staging");
    }

    static void verifiedImportAndCleanup() throws Exception {
        Context app = fresh();
        File directory = new File(app.getNoBackupFilesDir(), "soulseek-selected");
        directory.mkdirs();
        File interrupted = new File(directory, "0123456789abcdef0123456789abcdef.part");
        Files.write(interrupted.toPath(), new byte[] {1, 2, 3});
        AudioFormatPreferences.flipAfterSnapshot = true;
        SoulseekSelectedDownloads.enqueueOrFalse(app, song(1));
        idle();
        check(
                !interrupted.exists(),
                "Interrupted private staging is removed before the next selected download");
        check(
                BulkDownloads.imports == 1,
                "A complete verified source file reaches the shared publisher");
        check(
                java.util.Arrays.equals(BulkDownloads.bytes, new byte[] {'f', 'L', 'a', 'C'}),
                "Staging preserves the selected bytes");
        check(
                BulkDownloads.policy.equals("original"),
                "Accepted format settings are captured exactly once");
        check(
                AudioFormatPreferences.snapshots == 1,
                "Queued tasks do not re-read a changed format policy");
        check(
                SoulseekDownloads.url.equals(SoulseekAddon.ORIGIN + "old-session/audio/" + id(1)),
                "Fetch is bound to the selected source ID and login session");
        check(
                directory.listFiles().length == 0,
                "A successful import leaves no orphan selected staging");
        app = fresh();
        AudioFormatPreferences.failVerification = true;
        SoulseekSelectedDownloads.enqueueOrFalse(app, song(1));
        idle();
        check(BulkDownloads.imports == 0, "A rejected actual codec is never published");
        check(
                new File(app.getNoBackupFilesDir(), "soulseek-selected").listFiles().length == 0,
                "Rejected codec staging is deleted");
        app = fresh();
        SoulseekDownloads.pauseBeforeReturn = true;
        SoulseekSelectedDownloads.enqueueOrFalse(app, song(1));
        idle();
        check(BulkDownloads.imports == 0, "A host settings pause before publication is respected");
    }

    static void boundedQueueAndCancel() throws Exception {
        Context app = fresh();
        SoulseekDownloads.entered = new CountDownLatch(1);
        SoulseekDownloads.release = new CountDownLatch(1);
        Song selected = song(1);
        SoulseekSelectedDownloads.enqueueOrFalse(app, selected);
        check(
                SoulseekDownloads.entered.await(5, TimeUnit.SECONDS),
                "Active source transfer reaches its blocking fixture");
        SoulseekSelectedDownloads.enqueueOrFalse(app, selected);
        check(tasks() == 1, "Repeated tapping deduplicates the same selected source");
        for (int i = 2; i <= 33; i++) SoulseekSelectedDownloads.enqueueOrFalse(app, song(i));
        check(
                tasks() == 33 && worker().getQueue().size() == 32,
                "The selected queue is bounded to one active and 32 waiting");
        check(
                SoulseekSelectedDownloads.enqueueOrFalse(app, song(34)),
                "A full owned queue cannot fall through to the host");
        check(
                tasks() == 33 && SoulseekSelectedDownloads.status().contains("queue is full"),
                "Queue overflow fails closed without a hidden task");
        SoulseekSelectedDownloads.cancelAll();
        idle();
        check(
                BulkDownloads.imports == 0,
                "Cancelling active and pending selected files prevents publication");
        check(
                SoulseekDownloads.fetches == 1,
                "Cancelled pending selections never open a native transfer");
    }

    public static void main(String[] args) throws Exception {
        try {
            sourceOwnership();
            setupAndBinding();
            verifiedImportAndCleanup();
            boundedQueueAndCancel();
            System.out.println("PASS: " + checks + " selected download checks.");
        } finally {
            worker().shutdownNow();
        }
    }
}
