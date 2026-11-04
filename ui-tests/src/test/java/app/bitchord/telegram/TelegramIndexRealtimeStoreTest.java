package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TelegramIndexRealtimeStoreTest {
    private final List<SqliteIndexStore> databases = new ArrayList<>();

    private SqliteIndexStore open(File file) {
        SqliteIndexStore store = new SqliteIndexStore(file);
        databases.add(store);
        return store;
    }

    @After
    public void closeDatabases() throws Exception {
        java.lang.reflect.Field field = SqliteIndexStore.class.getDeclaredField("db");
        field.setAccessible(true);
        for (SqliteIndexStore store : databases)
            ((android.database.sqlite.SQLiteDatabase) field.get(store)).close();
    }

    private List<IndexStore> stores() {
        return List.of(new IndexStore.Memory(), open(file()));
    }

    private File file() {
        return new File(
                RuntimeEnvironment.getApplication().getFilesDir(),
                "realtime/" + UUID.randomUUID() + ".db");
    }

    private JSONObject row(long message, int entry, String title) throws Exception {
        return SqliteIndexStoreTest.row(-100, message, title, "Artist")
                .put("entry", entry)
                .put("id", "-100_" + message + (entry == 0 ? "" : "_" + entry));
    }

    private JSONObject job(long message, String name) throws Exception {
        return new JSONObject().put("chat", -100).put("message", message).put("fileName", name);
    }

    @Test
    public void replacesEveryArchiveEntryAndItsSearchWords() throws Exception {
        for (IndexStore store : stores()) {
            store.put(-100, List.of(row(1, 1, "Old first"), row(1, 2, "Old second")), 1);
            store.queueZips(-100, List.of(job(1, "old.zip")), 1);
            store.deferZip(-100, 1);
            store.putFound(-100, 1, "names", "-100_1_1", new JSONObject().put("title", "Known"));
            store.putFound(-200, 7, "check", "shared-file-hash", new JSONObject().put("ok", true));
            store.put(-100, List.of(row(2, 0, "Other")), 1);
            store.replaceMessage(-100, 1, List.of(row(1, 0, "Replacement")), List.of(), 1, 0);
            assertEquals(2, store.count(-100));
            assertNull(store.row(-100, 1, 1));
            assertNull(store.row(-100, 1, 2));
            assertNull(store.zip(-100, 1));
            assertNull(store.found("names", "-100_1_1"));
            assertNotNull(store.found("check", "shared-file-hash"));
            assertTrue(store.search(List.of(-100L), List.of("old"), 10).isEmpty());
            assertEquals(
                    "Replacement",
                    store.search(List.of(-100L), List.of("replacement"), 10)
                            .get(0)
                            .getString("title"));
            assertEquals("Other", store.row(-100, 2, 0).getString("title"));
        }
    }

    @Test
    public void tombstonesSurviveReopenAndRejectLateEventsAndOldPages() throws Exception {
        File file = file();
        IndexStore store = open(file);
        long readEpoch = store.epoch();
        store.replaceMessage(-100, 1, List.of(), List.of(), 1, 1);
        store = open(file);
        assertTrue(store.deleted(-100, 1));
        assertFalse(
                store.replaceMessage(-100, 1, List.of(row(1, 0, "Late event")), List.of(), 1, 0));
        assertFalse(store.replaceMessage(-100, 1, List.of(), List.of(), 1, 2));
        store.putPage(-100, List.of(row(1, 0, "Old scan")), List.of(), 1, readEpoch);
        store.putPage(-100, List.of(row(1, 0, "Later scan")), List.of(), 1, store.epoch());
        assertNull(store.row(-100, 1, 0));
        assertEquals(0, store.count(-100));
    }

    @Test
    public void inaccessibleMediaRequiresAnAuthoritativeLaterPage() throws Exception {
        for (IndexStore store : stores()) {
            long before = store.epoch();
            store.replaceMessage(-100, 1, List.of(), List.of(), 1, 2);
            store.putPage(-100, List.of(row(1, 0, "Too old")), List.of(), 1, before);
            assertNull(store.row(-100, 1, 0));
            assertFalse(store.deleted(-100, 1));
            store.putPage(-100, List.of(row(1, 0, "Accessible")), List.of(), 1, store.epoch());
            assertEquals("Accessible", store.row(-100, 1, 0).getString("title"));
            assertTrue(store.changed(-100, 1));
        }
    }

    @Test
    public void staleArchiveCompletionCannotFinishItsReplacement() throws Exception {
        for (IndexStore store : stores()) {
            store.replaceMessage(-100, 1, List.of(), List.of(job(1, "first.zip")), 1, 0);
            JSONObject old = store.nextZip(List.of(-100L));
            store.replaceMessage(-100, 1, List.of(), List.of(job(1, "second.zip")), 1, 0);
            assertFalse(store.finishZip(old, List.of(row(1, 1, "Stale")), 1, false));
            assertFalse(store.finishZip(old, List.of(), 1, true));
            assertEquals(1, store.zipsWaiting(List.of(-100L)));
            JSONObject current = store.nextZip(List.of(-100L));
            assertTrue(store.finishZip(current, List.of(row(1, 1, "Current")), 1, false));
            assertFalse(store.finishZip(current, List.of(row(1, 2, "Duplicate")), 1, false));
            assertFalse(
                    store.replaceMessage(-100, 1, List.of(), List.of(job(1, "second.zip")), 1, 0));
            assertEquals("Current", store.row(-100, 1, 1).getString("title"));
            assertEquals(0, store.zipsWaiting(List.of(-100L)));
        }
    }

    @Test
    public void cueTracksRequireBothTheAlbumAndSheetRevision() throws Exception {
        for (IndexStore store : stores()) {
            store.replaceMessage(-100, 20, List.of(row(20, 0, "Album")), List.of(), 1, 0);
            store.replaceMessage(-100, 10, List.of(), List.of(job(10, "album.cue")), 1, 0);
            JSONObject cue = store.nextZip(List.of(-100L));
            JSONObject track =
                    row(20, 10001, "Cue song").put(IndexStore.REVISION, store.revision(-100, 20));
            assertTrue(store.finishZip(cue, List.of(track), 1, false));
            JSONObject old = store.row(-100, 20, 10001);
            store.replaceMessage(-100, 10, List.of(), List.of(job(10, "changed.cue")), 1, 0);
            assertNull(store.row(-100, 20, 10001));
            assertNotNull(store.row(-100, 20, 0));
            store.putFound(
                    old, "names", old.getString("id"), new JSONObject().put("title", "Stale"));
            assertNull(store.found("names", old.getString("id")));
            JSONObject next = store.nextZip(List.of(-100L));
            store.replaceMessage(-100, 20, List.of(row(20, 0, "New album")), List.of(), 1, 0);
            assertFalse(store.finishZip(next, List.of(track), 1, false));
            assertEquals("New album", store.row(-100, 20, 0).getString("title"));
        }
    }

    @Test
    public void invalidReplacementLeavesExistingRowsAndJobUntouched() throws Exception {
        for (IndexStore store : stores()) {
            store.replaceMessage(
                    -100, 1, List.of(row(1, 0, "Original")), List.of(job(1, "a.zip")), 1, 0);
            long version = store.revision(-100, 1);
            try {
                store.replaceMessage(
                        -100,
                        1,
                        List.of(row(1, 0, "Partial"), row(2, 0, "Wrong message")),
                        List.of(),
                        1,
                        0);
                fail("Invalid mixed-message replacement accepted");
            } catch (IllegalArgumentException expected) {
            }
            assertEquals(version, store.revision(-100, 1));
            assertEquals("Original", store.row(-100, 1, 0).getString("title"));
            assertNotNull(store.zip(-100, 1));
        }
    }

    @Test
    public void resetFencesOldWorkWithoutClearingOtherChatsOrPermanentDeletion() throws Exception {
        for (IndexStore store : stores()) {
            store.put(-100, List.of(row(1, 0, "Before reset")), 1);
            store.put(-200, List.of(row(9, 0, "Other chat").put("chat", -200)), 1);
            store.queueZips(-100, List.of(job(3, "pending.zip")), 1);
            JSONObject oldJob = store.nextZip(List.of(-100L));
            IndexStore.State old = store.state(-100);
            Arrays.fill(old.complete, true);
            store.save(old);
            long before = store.epoch();
            store.replaceMessage(-100, 2, List.of(), List.of(), 1, 1);
            store.atomic(() -> store.resetChat(-100));
            store.putPage(-100, List.of(row(1, 0, "Old page")), List.of(), 1, before);
            store.save(old);
            assertNull(store.row(-100, 1, 0));
            assertTrue(store.deleted(-100, 2));
            assertFalse(store.finishZip(oldJob, List.of(row(3, 1, "Old archive")), 1, false));
            assertFalse(store.state(-100).built());
            assertEquals(1, store.count(-200));
            store.putPage(-100, List.of(row(1, 0, "Fresh page")), List.of(), 1, store.epoch());
            assertEquals("Fresh page", store.row(-100, 1, 0).getString("title"));
        }
    }

    @Test
    public void partialTopUpCursorsSurviveDatabaseReopen() throws Exception {
        File file = file();
        IndexStore store = open(file);
        IndexStore.State state = store.state(-100);
        state.topCursor[0] = 73;
        state.topComplete[1] = true;
        store.save(state);
        IndexStore.State reopened = open(file).state(-100);
        assertEquals(73, reopened.topCursor[0]);
        assertTrue(reopened.topComplete[1]);
        assertFalse(reopened.topComplete[0]);
    }

    @Test
    public void persistenceKeepsOnlyItsOwnLocalOwnershipFields() throws Exception {
        for (IndexStore store : stores()) {
            store.putPage(
                    -100,
                    List.of(
                            row(1, 0, "Native")
                                    .put("_indexCatalogueEpoch", 77)
                                    .put("_indexForeign", 88)),
                    List.of(),
                    1,
                    store.epoch());
            JSONObject saved = store.row(-100, 1, 0);
            assertFalse(saved.has("_indexCatalogueEpoch"));
            assertFalse(saved.has("_indexForeign"));
            assertTrue(saved.has(IndexStore.REVISION));
            store.fileFound(saved.put("_indexCatalogueEpoch", 99), 1);
            assertFalse(store.row(-100, 1, 0).has("_indexCatalogueEpoch"));
            store.replaceMessage(
                    -100,
                    2,
                    List.of(),
                    List.of(job(2, "new.zip").put("_indexCatalogueEpoch", 123)),
                    1,
                    0);
            assertFalse(store.nextZip(List.of(-100L)).has("_indexCatalogueEpoch"));
            store.fileFound(row(3, 0, "Live file check"), 1);
            assertFalse(store.frontier(-100, 3));
        }
    }
}
