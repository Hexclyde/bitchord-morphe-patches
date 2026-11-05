package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class TransitionPairSelectionTest {
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int PERSISTABLE = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

    static final class Names extends ContentProvider {
        public boolean onCreate() {
            return true;
        }

        public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(new String[] {"_display_name"});
            cursor.addRow(new Object[] {"Song From Provider.mp3"});
            return cursor;
        }

        public String getType(Uri uri) {
            return "audio/mpeg";
        }

        public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        public int delete(Uri uri, String where, String[] args) {
            throw new UnsupportedOperationException();
        }

        public int update(Uri uri, ContentValues values, String where, String[] args) {
            throw new UnsupportedOperationException();
        }
    }

    private Context app;
    private ContentResolver resolver;
    private Activity activity;
    private TransitionRuntime.Pair start;
    private TransitionPairSelection selection;
    private final List<TransitionRuntime.Pair> selected = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<Uri> read = new ArrayList<>();
    private final List<Thread> readers = new ArrayList<>();
    private TransitionPairSelection.Reader previous;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        resolver = app.getContentResolver();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        previous = TransitionPairSelection.reader;
        TransitionPairSelection.reader =
                (context, uri) -> {
                    synchronized (read) {
                        read.add(uri);
                        readers.add(Thread.currentThread());
                    }
                    return new TransitionPairSelection.Info("Real Title", "Real Artist", 245000);
                };
        start = pair();
        selection = new TransitionPairSelection(start, selected::add, errors::add);
        selection.launch(activity, 0);
    }

    @After
    public void close() {
        selection.close();
        TransitionPairSelection.reader = previous;
        for (UriPermission permission : new ArrayList<>(resolver.getPersistedUriPermissions()))
            resolver.releasePersistableUriPermission(permission.getUri(), READ);
    }

    private static MediaItem queued(String id) {
        Bundle extras = new Bundle();
        extras.putString("bitchord.queueEntryId", "entry-" + id);
        return new MediaItem.Builder()
                .setMediaId(id)
                .setUri("content://audio/" + id)
                .setMediaMetadata(
                        new MediaMetadata.Builder()
                                .setTitle(id.toUpperCase(Locale.ROOT))
                                .setExtras(extras)
                                .build())
                .build();
    }

    private static TransitionRuntime.Pair pair() {
        TransitionRuntime.Pair pair =
                new TransitionRuntime.Pair("a", "b", "A", "B", 200, 180, 1, 3);
        pair.outgoing = queued("a");
        pair.incoming = queued("b");
        return pair;
    }

    private static Intent result(Uri uri, boolean persistable) {
        return new Intent().setData(uri).addFlags(READ | (persistable ? PERSISTABLE : 0));
    }

    private static String sha256(String text) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")))
            out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }

    private void await(BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (true) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (done.getAsBoolean()) return;
            if (System.nanoTime() > end) fail("Timed out waiting for the file");
            Thread.sleep(5);
        }
    }

    private Set<String> grants() {
        Set<String> out = new TreeSet<>();
        for (UriPermission permission : resolver.getPersistedUriPermissions())
            out.add(
                    permission.getUri()
                            + (permission.isReadPermission() ? " r" : "")
                            + (permission.isWritePermission() ? " w" : ""));
        return out;
    }

    private TransitionRuntime.Pair choose(int request, Uri uri, boolean persistable)
            throws Exception {
        int before = selected.size();
        assertTrue(selection.handleResult(request, Activity.RESULT_OK, result(uri, persistable)));
        await(() -> selected.size() > before || !errors.isEmpty());
        return selected.isEmpty() ? null : selected.get(selected.size() - 1);
    }

    @Test
    public void theChooserAsksForReadOnlyAudioDocuments() {
        ShadowActivity shadow = Shadows.shadowOf(activity);
        ShadowActivity.IntentForResult outgoing = shadow.getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, outgoing.intent.getAction());
        assertEquals("audio/*", outgoing.intent.getType());
        assertTrue(outgoing.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals(TransitionPairSelection.OUTGOING_REQUEST, outgoing.requestCode);
        assertEquals(
                READ | PERSISTABLE, outgoing.intent.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
        selection.launch(activity, 1);
        assertEquals(
                TransitionPairSelection.INCOMING_REQUEST,
                shadow.getNextStartedActivityForResult().requestCode);
        assertThrows(IllegalArgumentException.class, () -> selection.launch(activity, 2));
    }

    @Test
    public void cancelledAndForeignResultsDoNothing() throws Exception {
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.OUTGOING_REQUEST, Activity.RESULT_CANCELED, null));
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.INCOMING_REQUEST,
                        Activity.RESULT_OK,
                        new Intent()));
        assertFalse(
                selection.handleResult(
                        1234, Activity.RESULT_OK, result(Uri.parse("content://x/1"), true)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(selected.isEmpty());
        assertTrue(errors.isEmpty());
        assertTrue(read.isEmpty());
        assertTrue(grants().isEmpty());
        assertSame(start, selection.current());
    }

    @Test
    public void aChosenFileKeepsItsRealMetadataAndIsReadOffTheMainThread() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/song-1");
        TransitionRuntime.Pair chosen = choose(TransitionPairSelection.INCOMING_REQUEST, uri, true);
        assertNotNull(chosen);
        assertTrue(errors.isEmpty());
        assertEquals(1, read.size());
        assertNotSame(Thread.currentThread(), readers.get(0));
        assertEquals("local:" + sha256(uri.toString()), chosen.incomingId);
        assertEquals("Real Title", chosen.incomingTitle);
        assertEquals("Real Artist", chosen.incoming.mediaMetadata.artist.toString());
        assertEquals(uri, chosen.incoming.localConfiguration.uri);
        Bundle extras = chosen.incoming.mediaMetadata.extras;
        assertEquals(uri.toString(), extras.getString("bitchord.localUri"));
        assertEquals("4:05", extras.getString("bitchord.durationText"));
        assertEquals(245, chosen.incomingDuration, 1e-6);
        assertSame(start.outgoing, chosen.outgoing);
        assertEquals(start.outgoingDuration, chosen.outgoingDuration, 0);
        assertEquals(1, chosen.index);
        assertEquals(3, chosen.nextIndex);
        assertSame(chosen, selection.current());
        assertTrue("Choosing a file keeps no lasting access", grants().isEmpty());
    }

    @Test
    public void aChosenOutgoingFileUsesTheFilesOwnLength() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/song-2");
        TransitionRuntime.Pair chosen = choose(TransitionPairSelection.OUTGOING_REQUEST, uri, true);
        assertEquals("local:" + sha256(uri.toString()), chosen.outgoingId);
        assertEquals(245, chosen.outgoingDuration, 1e-6);
        assertSame(start.incoming, chosen.incoming);
        assertEquals(start.incomingDuration, chosen.incomingDuration, 0);
    }

    @Test
    public void theSameFileOnBothSidesIsRefused() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/song-1");
        choose(TransitionPairSelection.INCOMING_REQUEST, uri, true);
        selected.clear();
        TransitionRuntime.Pair again = choose(TransitionPairSelection.OUTGOING_REQUEST, uri, true);
        assertNull(again);
        assertEquals(Collections.singletonList(Strings.t("Choose a different file")), errors);
    }

    @Test
    public void unreadableShortAndNonDeviceFilesReportAnErrorWithoutALocation() throws Exception {
        Uri unreadable = Uri.parse("content://docs.test/document/secret-token-1");
        TransitionPairSelection.reader =
                (context, uri) -> {
                    throw new IOException("Not an audio file " + uri);
                };
        assertNull(choose(TransitionPairSelection.INCOMING_REQUEST, unreadable, true));
        assertEquals(Collections.singletonList(Strings.t("Could not read this file")), errors);
        assertFalse(errors.get(0).contains("secret-token"));
        errors.clear();
        TransitionPairSelection.reader =
                (context, uri) -> new TransitionPairSelection.Info("Tiny", "", 1000);
        assertNull(
                choose(
                        TransitionPairSelection.INCOMING_REQUEST,
                        Uri.parse("content://docs.test/document/tiny"),
                        true));
        assertEquals(Collections.singletonList(Strings.t("Not enough audio")), errors);
        errors.clear();
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.INCOMING_REQUEST,
                        Activity.RESULT_OK,
                        result(Uri.parse("file:///sdcard/Music/song.mp3"), true)));
        assertEquals(Collections.singletonList(Strings.t("Could not read this file")), errors);
        assertTrue(selected.isEmpty());
        assertSame(start, selection.current());
        assertTrue(grants().isEmpty());
    }

    @Test
    public void onlyTheLatestChoiceIsDeliveredAndNothingArrivesAfterClose() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TransitionPairSelection.reader =
                (context, uri) -> {
                    if (uri.getLastPathSegment().equals("slow")) {
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            throw new IOException("Interrupted");
                        }
                    }
                    return new TransitionPairSelection.Info("Title", "Artist", 245000);
                };
        Uri slow = Uri.parse("content://docs.test/document/slow");
        Uri fast = Uri.parse("content://docs.test/document/fast");
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.INCOMING_REQUEST,
                        Activity.RESULT_OK,
                        result(slow, true)));
        TransitionRuntime.Pair chosen =
                choose(TransitionPairSelection.INCOMING_REQUEST, fast, true);
        release.countDown();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, selected.size());
        assertEquals("local:" + sha256(fast.toString()), chosen.incomingId);
        assertTrue(errors.isEmpty());

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        TransitionPairSelection.reader =
                (context, uri) -> {
                    entered.countDown();
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        throw new IOException("Interrupted");
                    }
                    return new TransitionPairSelection.Info("Late", "Late", 245000);
                };
        selected.clear();
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.OUTGOING_REQUEST,
                        Activity.RESULT_OK,
                        result(slow, true)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        selection.close();
        gate.countDown();
        Thread.sleep(100);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(selected.isEmpty());
        assertTrue(errors.isEmpty());
    }

    @Test
    public void reversingSwapsTheDirectionWithoutReadingOrKeepingAnything() throws Exception {
        TransitionRuntime.Pair back = selection.reverse();
        assertSame(back, selection.current());
        assertSame(start.incoming, back.outgoing);
        assertSame(start.outgoing, back.incoming);
        assertEquals("b", back.outgoingId);
        assertEquals("A", back.incomingTitle);
        assertEquals(start.incomingDuration, back.outgoingDuration, 0);
        assertEquals(1, back.index);
        assertEquals(3, back.nextIndex);
        assertNotEquals(start.key, back.key);
        assertTrue(read.isEmpty());
        assertTrue(selected.isEmpty());
        assertTrue(grants().isEmpty());
        TransitionRuntime.Pair after =
                choose(
                        TransitionPairSelection.INCOMING_REQUEST,
                        Uri.parse("content://docs.test/document/song-3"),
                        true);
        assertSame(back.outgoing, after.outgoing);
    }

    @Test
    public void savingKeepsReadAccessOnlyForTheChosenFilesAndOnlyOnCommit() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/song-1");
        TransitionRuntime.Pair chosen = choose(TransitionPairSelection.INCOMING_REQUEST, uri, true);
        try (TransitionPairSelection.Retention retention =
                selection.retainSelectionForSave(chosen)) {
            assertEquals(Collections.singleton(uri + " r"), grants());
            retention.commit();
        }
        assertEquals("Committed access stays", Collections.singleton(uri + " r"), grants());
        selection.reset(start);
        TransitionPairSelection.Retention none = selection.retainSelectionForSave(chosen);
        none.close();
        assertEquals(Collections.singleton(uri + " r"), grants());
    }

    @Test
    public void aFailedSaveReleasesOnlyTheAccessItTook() throws Exception {
        Uri kept = Uri.parse("content://docs.test/document/already");
        Uri other = Uri.parse("content://docs.test/document/unrelated");
        Uri fresh = Uri.parse("content://docs.test/document/fresh");
        resolver.takePersistableUriPermission(kept, READ);
        resolver.takePersistableUriPermission(other, READ);
        choose(TransitionPairSelection.OUTGOING_REQUEST, kept, true);
        TransitionRuntime.Pair chosen =
                choose(TransitionPairSelection.INCOMING_REQUEST, fresh, true);
        TransitionPairSelection.Retention retention = selection.retainSelectionForSave(chosen);
        assertEquals(
                new TreeSet<>(Arrays.asList(kept + " r", other + " r", fresh + " r")), grants());
        assertThrows(IllegalStateException.class, () -> selection.retainSelectionForSave(chosen));
        retention.close();
        retention.close();
        assertEquals(new TreeSet<>(Arrays.asList(kept + " r", other + " r")), grants());
        TransitionPairSelection.Retention again = selection.retainSelectionForSave(chosen);
        again.commit();
        again.close();
        assertTrue(grants().contains(fresh + " r"));
    }

    @Test
    public void accessThatCannotLastIsReportedAndNothingIsLeftBehind() throws Exception {
        Uri lasting = Uri.parse("content://docs.test/document/lasting");
        Uri temporary = Uri.parse("content://docs.test/document/temporary");
        choose(TransitionPairSelection.OUTGOING_REQUEST, lasting, true);
        TransitionRuntime.Pair chosen =
                choose(TransitionPairSelection.INCOMING_REQUEST, temporary, false);
        IOException refused =
                assertThrows(IOException.class, () -> selection.retainSelectionForSave(chosen));
        assertEquals(Strings.t("This file can’t stay available"), refused.getMessage());
        assertTrue("Access taken for the first file was given back", grants().isEmpty());
        assertFalse(refused.getMessage().contains("docs.test"));
    }

    @Test
    public void aSaveStillRunningWhenThePickerClosesKeepsItsAccessOnCommit() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/song-1");
        TransitionRuntime.Pair chosen = choose(TransitionPairSelection.INCOMING_REQUEST, uri, true);
        TransitionPairSelection.Retention retention = selection.retainSelectionForSave(chosen);
        selection.close();
        assertEquals(
                "The running save owns the access", Collections.singleton(uri + " r"), grants());
        retention.commit();
        retention.close();
        assertEquals(Collections.singleton(uri + " r"), grants());
    }

    @Test
    public void aSaveThatFailsAfterThePickerClosedReleasesOnlyItsOwnAccess() throws Exception {
        Uri kept = Uri.parse("content://docs.test/document/already");
        Uri other = Uri.parse("content://docs.test/document/unrelated");
        Uri fresh = Uri.parse("content://docs.test/document/fresh");
        resolver.takePersistableUriPermission(kept, READ);
        resolver.takePersistableUriPermission(other, READ);
        choose(TransitionPairSelection.OUTGOING_REQUEST, kept, true);
        TransitionRuntime.Pair chosen =
                choose(TransitionPairSelection.INCOMING_REQUEST, fresh, true);
        TransitionPairSelection.Retention retention = selection.retainSelectionForSave(chosen);
        selection.close();
        retention.close();
        assertEquals(new TreeSet<>(Arrays.asList(kept + " r", other + " r")), grants());
    }

    @Test
    public void aLaunchedOrCancelledChooserInvalidatesAnEarlierSlowRead() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        TransitionPairSelection.reader =
                (context, uri) -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        return new TransitionPairSelection.Info("Late", "Late", 245000);
                    }
                    return new TransitionPairSelection.Info("Late", "Late", 245000);
                };
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.INCOMING_REQUEST,
                        Activity.RESULT_OK,
                        result(Uri.parse("content://docs.test/document/slow"), true)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        selection.launch(activity, 0);
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.OUTGOING_REQUEST, Activity.RESULT_CANCELED, null));
        release.countDown();
        Thread.sleep(150);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(selected.isEmpty());
        assertTrue(errors.isEmpty());
        assertSame(start, selection.current());
    }

    @Test
    public void onlyTheCurrentSelectionsFilesAreRemembered() throws Exception {
        java.lang.reflect.Field field = TransitionPairSelection.class.getDeclaredField("picked");
        field.setAccessible(true);
        Map<?, ?> remembered = (Map<?, ?>) field.get(selection);
        Uri existing = Uri.parse("content://docs.test/document/existing");
        resolver.takePersistableUriPermission(existing, READ);
        TransitionRuntime.Pair chosen = null;
        for (int i = 0; i < 6; i++)
            chosen =
                    choose(
                            i % 2 == 0
                                    ? TransitionPairSelection.INCOMING_REQUEST
                                    : TransitionPairSelection.OUTGOING_REQUEST,
                            Uri.parse("content://docs.test/document/many-" + i),
                            true);
        assertTrue("Remembered " + remembered.size(), remembered.size() <= 2);
        TransitionPairSelection.Retention retention = selection.retainSelectionForSave(chosen);
        assertEquals(3, grants().size());
        assertTrue(grants().contains(existing + " r"));
        assertTrue(grants().contains(chosen.incoming.localConfiguration.uri + " r"));
        assertTrue(grants().contains(chosen.outgoing.localConfiguration.uri + " r"));
        retention.close();
        assertEquals(Collections.singleton(existing + " r"), grants());
    }

    @Test
    public void aDelayedSaveKeepsTheOldPairsFileAfterANewerSelection() throws Exception {
        Uri first = Uri.parse("content://docs.test/document/old-draft");
        Uri second = Uri.parse("content://docs.test/document/new-preview");
        TransitionRuntime.Pair old = choose(TransitionPairSelection.OUTGOING_REQUEST, first, true);
        choose(TransitionPairSelection.OUTGOING_REQUEST, second, true);
        assertTrue(grants().isEmpty());
        try (TransitionPairSelection.Retention saved = selection.retainSelectionForSave(old)) {
            assertEquals(Collections.singleton(first + " r"), grants());
            saved.commit();
        }
        assertEquals(Collections.singleton(first + " r"), grants());
    }

    private static String receiptKey(String name) throws Exception {
        java.lang.reflect.Field key = TransitionPairSelection.class.getDeclaredField(name);
        key.setAccessible(true);
        return (String) key.get(null);
    }

    @Test
    public void alteredPickerReceiptsCannotAuthorizeAFile() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/receipt");
        TransitionRuntime.Pair chosen = choose(TransitionPairSelection.OUTGOING_REQUEST, uri, true);
        Bundle receipt = chosen.outgoing.mediaMetadata.extras;
        String ownerKey = receiptKey("PICK_OWNER"), uriKey = receiptKey("PICK_URI");
        String owner = receipt.getString(ownerKey);
        receipt.putString(ownerKey, "another-picker");
        assertThrows(IOException.class, () -> selection.retainSelectionForSave(chosen));
        receipt.putString(ownerKey, owner);
        receipt.putString(uriKey, "content://docs.test/document/another-file");
        assertThrows(IOException.class, () -> selection.retainSelectionForSave(chosen));
        assertTrue(grants().isEmpty());
    }

    @Test
    public void aPickerReceiptStillRequiresTheCurrentNativeReadGrant() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/revoked");
        TransitionRuntime.Pair chosen = choose(TransitionPairSelection.OUTGOING_REQUEST, uri, true);
        Context denied =
                new android.content.ContextWrapper(app) {
                    @Override
                    public int checkUriPermission(Uri target, int pid, int uid, int flags) {
                        return android.content.pm.PackageManager.PERMISSION_DENIED;
                    }
                };
        java.lang.reflect.Field context = TransitionPairSelection.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(selection, denied);
        assertThrows(IOException.class, () -> selection.retainSelectionForSave(chosen));
        assertTrue(grants().isEmpty());
    }

    @Test
    public void aRecreatedScreenKeepsTheSelectionAndItsProvenance() throws Exception {
        Uri uri = Uri.parse("content://docs.test/document/song-1");
        TransitionRuntime.Pair chosen = choose(TransitionPairSelection.INCOMING_REQUEST, uri, true);
        selection.detach();
        List<TransitionRuntime.Pair> later = new ArrayList<>();
        selection.reattach(later::add, errors::add);
        assertSame(chosen, selection.current());
        try (TransitionPairSelection.Retention retention =
                selection.retainSelectionForSave(chosen)) {
            assertEquals(Collections.singleton(uri + " r"), grants());
            retention.commit();
        }
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.OUTGOING_REQUEST,
                        Activity.RESULT_OK,
                        result(Uri.parse("content://docs.test/document/song-2"), true)));
        await(() -> !later.isEmpty());
        TransitionRuntime.Pair next = later.get(0);
        assertEquals(1, later.size());
        assertSame(next, selection.current());
        assertSame(chosen.incoming, next.incoming);
    }

    @Test
    public void aDetachedPickerDropsLateCompletionsAndNeverCallsTheOldScreen() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        TransitionPairSelection.reader =
                (context, uri) -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        // The result is dropped either way.
                    }
                    return new TransitionPairSelection.Info("Late", "Late", 245000);
                };
        assertTrue(
                selection.handleResult(
                        TransitionPairSelection.INCOMING_REQUEST,
                        Activity.RESULT_OK,
                        result(Uri.parse("content://docs.test/document/slow"), true)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        selection.detach();
        List<TransitionRuntime.Pair> later = new ArrayList<>();
        selection.reattach(later::add, errors::add);
        release.countDown();
        Thread.sleep(150);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(selected.isEmpty());
        assertTrue(later.isEmpty());
        assertSame(start, selection.current());
    }

    @Test
    public void titleFallsBackToTheProvidersFileNameWithoutItsExtension() {
        assertEquals("Tagged", TransitionPairSelection.title("Tagged", "File.mp3"));
        assertEquals("File", TransitionPairSelection.title(null, "File.mp3"));
        assertEquals("File", TransitionPairSelection.title("  ", "File"));
        assertEquals(".hidden", TransitionPairSelection.title(null, ".hidden"));
        assertEquals("", TransitionPairSelection.title(null, null));
    }

    @Test
    public void theProviderNameIsReadFromTheDocumentColumn() {
        Names provider = new Names();
        ProviderInfo info = new ProviderInfo();
        info.authority = "names.test";
        info.exported = true;
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        assertEquals(
                "Song From Provider.mp3",
                TransitionPairSelection.displayName(
                        resolver, Uri.parse("content://names.test/document/1")));
        assertNull(
                TransitionPairSelection.displayName(
                        resolver, Uri.parse("content://absent.test/document/1")));
    }
}
