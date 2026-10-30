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

    static void observeCancellationBeforeInterrupt(String id) throws Exception {
        Field field = SoulseekSelectedDownloads.class.getDeclaredField("TASKS");
        field.setAccessible(true);
        ConcurrentHashMap<?, ?> pending = (ConcurrentHashMap<?, ?>) field.get(null);
        Object active = pending.get(PREFIX + id);
        Field thread = active.getClass().getDeclaredField("thread");
        thread.setAccessible(true);
        Thread actual = (Thread) thread.get(active);
        thread.set(
                active,
                new Thread() {
                    @Override
                    public void interrupt() {
                        try {
                            for (Object task : pending.values()) {
                                Field cancelled = task.getClass().getDeclaredField("cancelled");
                                cancelled.setAccessible(true);
                                check(
                                        ((java.util.concurrent.atomic.AtomicBoolean)
                                                        cancelled.get(task))
                                                .get(),
                                        "Every pending task is cancelled before the worker wakes");
                            }
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                        actual.interrupt();
                    }
                });
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
        AudioFormatPreferences.verificationEntered = null;
        AudioFormatPreferences.verificationRelease = null;
        BulkDownloads.imports = 0;
        BulkDownloads.bytes = null;
        BulkDownloads.entries.clear();
        BulkDownloads.importEntered = null;
        BulkDownloads.importRelease = null;
        BulkDownloads.importInterrupted = false;
        SoulseekDownloads.fetches = 0;
        SoulseekDownloads.requests.clear();
        SoulseekDownloads.entered = null;
        SoulseekDownloads.release = null;
        SoulseekDownloads.pauseBeforeReturn = false;
        SoulseekDownloads.failure = null;
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
        for (int i = 2; i <= 257; i++) SoulseekSelectedDownloads.enqueueOrFalse(app, song(i));
        check(
                tasks() == 257 && worker().getQueue().size() == 256,
                "The selected queue is bounded to one active and 256 waiting");
        check(
                SoulseekSelectedDownloads.enqueueOrFalse(app, song(258)),
                "A full owned queue cannot fall through to the host");
        check(
                tasks() == 257 && SoulseekSelectedDownloads.status().contains("queue is full"),
                "Queue overflow fails closed without a hidden task");
        observeCancellationBeforeInterrupt(id(1));
        SoulseekSelectedDownloads.cancelAll();
        idle();
        check(
                BulkDownloads.imports == 0,
                "Cancelling active and pending selected files prevents publication");
        check(
                SoulseekDownloads.fetches == 1,
                "Cancelled pending selections never open a native transfer");
    }

    static void nowPrecedesWaitingQueue() throws Exception {
        Context app = fresh();
        SoulseekDownloads.entered = new CountDownLatch(1);
        SoulseekDownloads.release = new CountDownLatch(1);
        SoulseekSelectedDownloads.enqueueSelected(app, song(1).videoId, false);
        check(
                SoulseekDownloads.entered.await(3, TimeUnit.SECONDS),
                "First selection starts its peer transfer");
        SoulseekSelectedDownloads.enqueueSelected(app, song(2).videoId, false);
        SoulseekSelectedDownloads.enqueueSelected(app, song(3).videoId, false);
        SoulseekSelectedDownloads.enqueueSelected(app, song(4).videoId, true);
        check(SoulseekDownloads.fetches == 1, "Download now does not interrupt an active file");
        SoulseekDownloads.release.countDown();
        idle();
        check(
                SoulseekDownloads.requests.size() == 4,
                "Every admitted selection transfers exactly once");
        int[] expected = {1, 4, 2, 3};
        for (int i = 0; i < expected.length; i++)
            check(
                    SoulseekDownloads.requests.get(i).endsWith("/" + id(expected[i])),
                    "Now takes the next slot, queued files stay FIFO");
    }

    static SoulseekSelectedDownloads.Progress progress(String reference) {
        for (SoulseekSelectedDownloads.Progress item : SoulseekSelectedDownloads.snapshots())
            if (item.reference.equals(reference)) return item;
        throw new AssertionError("Missing named progress: " + reference);
    }

    static SoulseekSelectedDownloads.Progress awaitProgress(
            String reference, SoulseekSelectedDownloads.Phase phase, long queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        SoulseekSelectedDownloads.Progress current;
        do {
            current = progress(reference);
            if (current.phase == phase && current.queuePosition == queue) return current;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Progress did not reach " + phase + " / " + queue);
    }

    static void exactSelectionAndNamedPhases() throws Exception {
        Context app = fresh();
        check(
                !SoulseekSelectedDownloads.enqueueSelected(app, "ordinary-video"),
                "Explicit API rejects other sources instead of falling back");
        check(
                !SoulseekSelectedDownloads.enqueueSelected(app, PREFIX + "bad"),
                "Explicit API rejects a malformed selected ID");
        String reference = PREFIX + id(1);
        SoulseekBridge.instance.tracks.put(
                id(1), new SoulseekTrack(id(1), 4, "private/secret-folder/Artist - Song.flac"));
        DownloadDestinations.current = new DownloadDestinations.Snapshot("both");
        SoulseekDownloads.entered = new CountDownLatch(1);
        SoulseekDownloads.release = new CountDownLatch(1);
        AudioFormatPreferences.verificationEntered = new CountDownLatch(1);
        AudioFormatPreferences.verificationRelease = new CountDownLatch(1);
        check(
                SoulseekSelectedDownloads.enqueueSelected(app, reference),
                "Explicit file selection is accepted with the exact reference");
        check(SoulseekDownloads.entered.await(5, TimeUnit.SECONDS), "Transfer started");
        SoulseekSelectedDownloads.Progress waiting =
                awaitProgress(reference, SoulseekSelectedDownloads.Phase.WAITING_PEER, 7);
        check(
                waiting.phase == SoulseekSelectedDownloads.Phase.WAITING_PEER
                        && waiting.queuePosition == 7
                        && waiting.canCancel(),
                "Actual peer queue position appears before any byte progress");
        check(
                waiting.name.equals("Artist - Song.flac")
                        && !waiting.summary().contains("secret-folder"),
                "Named progress never exposes the remote directory");
        SoulseekBridge.instance.progress =
                new SoulseekBridge.TransferStatus("downloading", 2, 4, -1);
        SoulseekSelectedDownloads.Progress receiving =
                awaitProgress(reference, SoulseekSelectedDownloads.Phase.RECEIVING, -1);
        check(
                receiving.phase == SoulseekSelectedDownloads.Phase.RECEIVING
                        && receiving.downloaded == 2,
                "Receiving phase uses actual native byte facts");
        check(
                SoulseekSelectedDownloads.snapshots().get(0).reference.equals(reference),
                "Active transfer is shown before older queue or history rows");
        SoulseekDownloads.release.countDown();
        check(
                AudioFormatPreferences.verificationEntered.await(5, TimeUnit.SECONDS),
                "Completed bytes enter the codec check");
        check(
                progress(reference).phase == SoulseekSelectedDownloads.Phase.VERIFYING,
                "Byte completion does not claim a saved file before codec verification");
        AudioFormatPreferences.verificationRelease.countDown();
        idle();
        check(
                progress(reference).phase == SoulseekSelectedDownloads.Phase.DESTINATIONS_PENDING
                        && progress(reference).handedOff,
                "Verified staging waits for destination copies instead of claiming completion");
        BulkJobStore.Job job = BulkDownloads.entries.get(0);
        job.state = BulkJobStore.State.PUBLISHING;
        job.phoneStatus = "writing";
        check(
                progress(reference).phase == SoulseekSelectedDownloads.Phase.SAVING,
                "Publisher writing state is shown as saving");
        job.phoneStatus = "complete";
        job.publishedUri = "content://test/saved-audio";
        job.telegramStatus = "failed";
        job.state = BulkJobStore.State.FAILED;
        SoulseekSelectedDownloads.Progress partial = progress(reference);
        check(
                partial.phase == SoulseekSelectedDownloads.Phase.FAILED
                        && partial.phoneState.equals("complete")
                        && partial.telegramState.equals("failed"),
                "Phone success and Telegram failure are independent");
        check(
                partial.summary().contains("Saved and verified")
                        && partial.summary().contains("Copy failed"),
                "Partial completion has separate destination messages");
        job.state = BulkJobStore.State.COMPLETE;
        job.telegramStatus = "complete";
        check(
                progress(reference).phase != SoulseekSelectedDownloads.Phase.COMPLETE,
                "A complete flag without the Telegram receipt cannot report full completion");
        job.telegramReceipt = "confirmed-test-receipt";
        job.publishedUri = "";
        check(
                progress(reference).phase != SoulseekSelectedDownloads.Phase.COMPLETE,
                "A complete flag without the phone receipt cannot report full completion");
        job.publishedUri = "content://test/saved-audio";
        check(
                progress(reference).phase == SoulseekSelectedDownloads.Phase.COMPLETE
                        && !progress(reference).canCancel(),
                "All selected copies require their successful backend receipts");
    }

    static void cancelledAndRedactedProgress() throws Exception {
        Context app = fresh();
        SoulseekDownloads.entered = new CountDownLatch(1);
        SoulseekDownloads.release = new CountDownLatch(1);
        SoulseekSelectedDownloads.enqueueSelected(app, song(1).videoId);
        check(SoulseekDownloads.entered.await(5, TimeUnit.SECONDS), "Blocking transfer entered");
        String pending = song(2).videoId;
        SoulseekSelectedDownloads.enqueueSelected(app, pending);
        check(SoulseekSelectedDownloads.cancel(pending), "A queued exact file can be cancelled");
        check(
                progress(pending).phase == SoulseekSelectedDownloads.Phase.CANCELLED,
                "Cancellation is visible before the queue worker runs");
        SoulseekDownloads.release.countDown();
        idle();
        check(
                SoulseekDownloads.fetches == 1,
                "Cancelled queue entries never open a native transfer");
        app = fresh();
        SoulseekDownloads.failure = "https://private.endpoint.invalid/?api_key=secret-token";
        String failed = song(1).videoId;
        SoulseekSelectedDownloads.enqueueSelected(app, failed);
        idle();
        check(
                progress(failed).phase == SoulseekSelectedDownloads.Phase.FAILED,
                "Transfer failures retain a named failed state");
        check(
                !progress(failed).summary().contains("endpoint")
                        && !progress(failed).summary().contains("secret-token"),
                "Transport exception details never become status text");
    }

    static void cancellationHandoffBoundary() throws Exception {
        Context app = fresh();
        AudioFormatPreferences.verificationEntered = new CountDownLatch(1);
        AudioFormatPreferences.verificationRelease = new CountDownLatch(1);
        String reference = song(1).videoId;
        SoulseekSelectedDownloads.enqueueSelected(app, reference);
        check(
                AudioFormatPreferences.verificationEntered.await(5, TimeUnit.SECONDS),
                "Verification gate entered");
        check(SoulseekSelectedDownloads.cancel(reference), "Cancellation before handoff wins");
        AudioFormatPreferences.verificationRelease.countDown();
        idle();
        check(
                BulkDownloads.imports == 0
                        && progress(reference).phase == SoulseekSelectedDownloads.Phase.CANCELLED,
                "A cancelled verification cannot hand bytes to the publisher");
        app = fresh();
        BulkDownloads.importEntered = new CountDownLatch(1);
        BulkDownloads.importRelease = new CountDownLatch(1);
        reference = song(1).videoId;
        SoulseekSelectedDownloads.enqueueSelected(app, reference);
        check(
                BulkDownloads.importEntered.await(5, TimeUnit.SECONDS),
                "Publisher handoff gate entered");
        check(
                !SoulseekSelectedDownloads.cancel(reference),
                "Source cancellation cannot claim success after handoff starts");
        SoulseekSelectedDownloads.cancelAll();
        check(
                progress(reference).phase == SoulseekSelectedDownloads.Phase.DESTINATIONS_PENDING,
                "Global source cancellation does not falsely label an accepted copy as cancelled");
        BulkDownloads.importRelease.countDown();
        idle();
        check(
                BulkDownloads.imports == 1 && !BulkDownloads.importInterrupted,
                "Destination lifecycle retains ownership once its handoff has started");
    }

    static void nativePollingNeverBlocksDisplay() throws Exception {
        Context app = fresh();
        SoulseekBridge.instance.pollEntered = new CountDownLatch(1);
        SoulseekBridge.instance.pollRelease = new CountDownLatch(1);
        SoulseekDownloads.entered = new CountDownLatch(1);
        SoulseekDownloads.release = new CountDownLatch(1);
        String reference = song(1).videoId;
        SoulseekSelectedDownloads.enqueueSelected(app, reference);
        try {
            check(
                    SoulseekBridge.instance.pollEntered.await(3, TimeUnit.SECONDS),
                    "Background native poll entered");
            long start = System.nanoTime();
            SoulseekSelectedDownloads.Progress pending = progress(reference);
            check(
                    System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(200),
                    "A blocked native poll cannot block UI snapshot rendering");
            check(
                    pending.phase == SoulseekSelectedDownloads.Phase.WAITING_PEER
                            && pending.queuePosition == -1,
                    "Queue position remains unknown until a real background observation arrives");
        } finally {
            SoulseekBridge.instance.pollRelease.countDown();
        }
        awaitProgress(reference, SoulseekSelectedDownloads.Phase.WAITING_PEER, 7);
        SoulseekSelectedDownloads.cancelAll();
        SoulseekDownloads.release.countDown();
        idle();
    }

    static void selectedBatchMetadataSurvivesSearchCatalogEviction() throws Exception {
        Context app = fresh();
        SoulseekTrack selected = new SoulseekTrack(id(991), 4);
        check(
                !SoulseekBridge.get().tracks.containsKey(selected.id),
                "batch result is absent from the search catalog fixture");
        check(
                SoulseekSelectedDownloads.enqueueSelectedFile(
                        app, selected, SoulseekBridge.get().sessionTag, false),
                "an explicit batch file retains its exact metadata after later searches");
        idle();
        check(
                SoulseekDownloads.fetches == 1 && BulkDownloads.imports == 1,
                "the retained exact file reaches the existing verified destination path");
        check(
                !SoulseekSelectedDownloads.enqueueSelectedFile(
                        app, new SoulseekTrack(id(992), 4), "another-session", false),
                "retained metadata cannot cross a Soulseek session");
    }

    public static void main(String[] args) throws Exception {
        try {
            sourceOwnership();
            setupAndBinding();
            verifiedImportAndCleanup();
            boundedQueueAndCancel();
            selectedBatchMetadataSurvivesSearchCatalogEviction();
            nowPrecedesWaitingQueue();
            exactSelectionAndNamedPhases();
            cancelledAndRedactedProgress();
            cancellationHandoffBoundary();
            nativePollingNeverBlocksDisplay();
            System.out.println("PASS: " + checks + " selected download checks.");
        } finally {
            worker().shutdownNow();
        }
    }
}
