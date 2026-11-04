package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class TransitionStoreTest {
    Context app;

    @Before
    public void setup() throws Exception {
        TransitionStore.closeForTest();
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase(TransitionStore.DATABASE);
        app.getSharedPreferences(TransitionRuntime.PREFERENCES, 0).edit().clear().commit();
        Field field = SoundExtras.class.getDeclaredField("app");
        field.setAccessible(true);
        field.set(null, app);
    }

    @After
    public void close() throws Exception {
        TransitionStore.closeForTest();
    }

    private TransitionRuntime.Pair pair(String a, String b) {
        return new TransitionRuntime.Pair(a, b, a, b, 180, 180, 0);
    }

    private void idle() throws Exception {
        TransitionStore.get().awaitIdle();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private TransitionEdit read(TransitionRuntime.Pair pair) throws Exception {
        TransitionStore.get().lookup(pair);
        idle();
        TransitionStore.Entry entry = TransitionStore.get().lookup(pair);
        assertTrue(entry.known);
        assertNull(entry.error);
        return entry.edit();
    }

    private void write(TransitionRuntime.Pair pair, TransitionEdit edit) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        TransitionStore.get().write(pair, edit, failure::set);
        idle();
        assertNull(failure.get());
    }

    @Test
    public void autosavedDraftSurvivesReopenWithoutReplacingAnAppliedTransition() throws Exception {
        var pair = pair("a", "b");
        TransitionEdit active = new TransitionEdit();
        active.incomingCue = 2;
        write(pair, active);
        TransitionEdit draft = new TransitionEdit();
        draft.incomingCue = 9;
        draft.preset(TransitionEdit.RISE);
        TransitionStore.get().writeDraft(pair, draft, false, error -> assertNull(error));
        idle();
        TransitionStore.closeForTest();
        assertEquals(2, read(pair).incomingCue, 0);
        var found = new AtomicReference<TransitionStore.Draft>();
        TransitionStore.get().readDraft(pair, found::set);
        idle();
        assertNull(found.get().error);
        assertEquals(9, found.get().edit.incomingCue, 0);
        TransitionStore.get().commit(pair, found.get().edit, false, error -> assertNull(error));
        idle();
        assertEquals(9, read(pair).incomingCue, 0);
        TransitionStore.get().commit(pair, draft, true, error -> assertNull(error));
        idle();
        assertNull(read(pair));
        TransitionStore.get().readDraft(pair, found::set);
        idle();
        assertTrue(found.get().automatic);
    }

    @Test
    public void versionOneDatabaseUpgradePreservesAppliedRowsAndAddsDraftStorage()
            throws Exception {
        var pair = pair("a", "b");
        TransitionEdit edit = new TransitionEdit();
        edit.recording = pair.recording();
        try (var db = app.openOrCreateDatabase(TransitionStore.DATABASE, 0, null)) {
            db.execSQL(
                    "CREATE TABLE transitions(pair_key TEXT NOT NULL,recording_key TEXT NOT"
                        + " NULL,outgoing_id TEXT,incoming_id TEXT,edit_json TEXT NOT"
                        + " NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(pair_key,recording_key))");
            db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("INSERT INTO metadata VALUES('preferences_migrated','1')");
            db.execSQL(
                    "INSERT INTO transitions(pair_key,recording_key,edit_json,updated_at)"
                            + " VALUES(?,?,?,0)",
                    new Object[] {pair.key, pair.recording(), edit.encode()});
            db.setVersion(1);
        }
        assertNotNull(read(pair));
        TransitionStore.get().writeDraft(pair, edit, false, error -> assertNull(error));
        idle();
        var result = new AtomicReference<TransitionStore.Draft>();
        TransitionStore.get().readDraft(pair, result::set);
        idle();
        assertNotNull(result.get().edit);
    }

    @Test
    public void appliedCurvesAndEqSurviveDatabaseReopenAndKeepTheirDirection() throws Exception {
        var pair = pair("song-a", "song-b");
        TransitionEdit edit = new TransitionEdit();
        edit.preset(TransitionEdit.RISE);
        edit.eq[1][2] = -4;
        edit.incomingCue = 12;
        write(pair, edit);
        TransitionStore.closeForTest();
        TransitionEdit loaded = read(pair);
        assertNotNull(loaded);
        assertEquals(12, loaded.incomingCue, 0);
        assertEquals(-4, loaded.eq[1][2], 0);
        assertEquals(2, loaded.filter[1]);
        assertNull(read(pair("song-b", "song-a")));
        assertNull(read(pair("song-a", "different-song")));
    }

    @Test
    public void moreThanTwoHundredTransitionsRemainOnDiskAfterCacheEviction() throws Exception {
        for (int i = 0; i < 240; i++)
            TransitionStore.get()
                    .write(
                            pair("a-" + i, "b"),
                            new TransitionEdit(),
                            failure -> assertNull(failure));
        idle();
        TransitionStore.closeForTest();
        assertNotNull(read(pair("a-0", "b")));
        assertNotNull(read(pair("a-239", "b")));
        try (var db = app.openOrCreateDatabase(TransitionStore.DATABASE, 0, null);
                var cursor = db.rawQuery("SELECT COUNT(*) FROM transitions", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(240, cursor.getInt(0));
        }
    }

    @Test
    public void legacyPreferencesMigrateOnceAndAutomaticRemovalDoesNotResurrectThem()
            throws Exception {
        var pair = pair("legacy-a", "legacy-b");
        TransitionEdit edit = new TransitionEdit();
        edit.recording = pair.recording();
        edit.incomingCue = 7;
        app.getSharedPreferences(TransitionRuntime.PREFERENCES, 0)
                .edit()
                .putString(pair.key, edit.encode())
                .commit();
        assertEquals(7, read(pair).incomingCue, 0);
        write(pair, null);
        TransitionStore.closeForTest();
        assertNull(read(pair));
        assertTrue(app.getSharedPreferences(TransitionRuntime.PREFERENCES, 0).contains(pair.key));
    }

    @Test
    public void alternateRecordingsOfTheSamePairKeepSeparateSavedTransitions() throws Exception {
        var first = pair("a", "b");
        var second = new TransitionRuntime.Pair("a", "b", "a", "b", 181, 180, 0);
        TransitionEdit a = new TransitionEdit();
        a.incomingCue = 3;
        write(first, a);
        TransitionEdit b = new TransitionEdit();
        b.incomingCue = 11;
        write(second, b);
        TransitionStore.closeForTest();
        assertEquals(3, read(first).incomingCue, 0);
        assertEquals(11, read(second).incomingCue, 0);
    }

    @Test
    public void malformedStoredDataIsReportedWithoutPretendingItWasLoaded() throws Exception {
        var pair = pair("bad-a", "bad-b");
        write(pair, new TransitionEdit());
        TransitionStore.closeForTest();
        try (var db = app.openOrCreateDatabase(TransitionStore.DATABASE, 0, null)) {
            db.execSQL("UPDATE transitions SET edit_json='invalid'");
        }
        TransitionStore.Entry entry = TransitionStore.get().lookup(pair);
        idle();
        assertTrue(entry.known);
        assertNotNull(entry.error);
        assertNull(entry.json);
    }
}
