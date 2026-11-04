package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;

import com.music.bitchord.data.model.Song;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DownloadFolderTest {
    static final Uri TREE = Uri.parse("content://folders.test/tree/drive");
    Context app;
    ContentResolver resolver;
    FolderProvider provider;

    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        ExtrasUi.context = app;
        resolver = app.getContentResolver();
        app.getSharedPreferences(DownloadFolder.PREFS, 0).edit().clear().commit();
        app.getSharedPreferences(DownloadFolder.SELECTIONS, 0).edit().clear().commit();
        app.getSharedPreferences(DownloadControls.PREFS, 0).edit().clear().commit();
        DownloadFolder.init(app);
        com.music.bitchord.data.settings.AppSettings.exportDownloads.setValue(false);
        com.music.bitchord.download.DownloadSession._state.setValue(
                new com.music.bitchord.download.DownloadSession.State());
        com.music.bitchord.download.Downloads._saved.setValue(new HashMap<>());
        com.music.bitchord.download.Downloads.cancelled.clear();
        com.music.bitchord.download.Downloads.forgotten.clear();
        provider = new FolderProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "folders.test";
        info.exported = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        info.grantUriPermissions = true;
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        resolver.takePersistableUriPermission(
                TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    Song song(String id) {
        return new Song(id, "Track", "Artist", "3:00");
    }

    void name(Song song, String name) {
        DownloadSelection.fileNameStart(song);
        assertEquals(name, DownloadSelection.fileNameFinish(name));
    }

    ContentValues values(String name) {
        ContentValues values = new ContentValues();
        values.put("_display_name", name);
        values.put("mime_type", "audio/flac");
        return values;
    }

    Uri begin(String id, String name) throws Exception {
        Song song = song(id);
        DownloadControls.remember(app, song, null);
        name(song, name);
        assertTrue(DownloadFolder.custom(name));
        assertNull(DownloadFolder.existing(app, name));
        return DownloadFolder.insert(
                resolver, Uri.parse("content://media/external/audio/media"), values(name));
    }

    @Test
    public void folderPersistsWithoutChangingNativeStoragePreference() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        DownloadFolder.init(app);
        assertEquals(TREE.toString(), DownloadFolder.selected(app));
        assertEquals("USB audio", DownloadFolder.label(app));
        assertEquals(0, provider.creates);
        assertEquals(0, provider.deletes);
    }

    @Test
    public void newRequestsUseFolderButQueuedAndRetriedRequestsKeepOriginalLocation()
            throws Exception {
        Song old = song("old"), fresh = song("fresh");
        DownloadControls.remember(app, old, null);
        DownloadFolder.choose(app, TREE.toString());
        DownloadControls.remember(app, old, null);
        DownloadControls.remember(app, fresh, null);
        name(old, "old.flac");
        assertFalse(DownloadFolder.custom("old.flac"));
        assertFalse(DownloadFolder.export(true));
        name(fresh, "fresh.flac");
        assertTrue(DownloadFolder.custom("fresh.flac"));
        assertTrue(DownloadFolder.export(false));
        DownloadFolder.choose(app, "");
        DownloadControls.remember(app, fresh, null);
        name(fresh, "fresh.flac");
        assertTrue(DownloadFolder.custom("fresh.flac"));
        assertEquals(0, provider.creates);
    }

    @Test
    public void oldQueuedMetadataIsFrozenBeforeFolderChanges() throws Exception {
        org.json.JSONObject old =
                new org.json.JSONObject()
                        .put("id", "legacy")
                        .put("song", MixedSongCodec.encode(song("legacy")));
        String key = "request." + TransitionRuntime.key("legacy", "download");
        app.getSharedPreferences(DownloadControls.PREFS, 0)
                .edit()
                .putString(key, old.toString())
                .commit();
        DownloadFolder.choose(app, TREE.toString());
        assertEquals("", DownloadControls.folder(app, "legacy").getString("tree"));
    }

    @Test
    public void nativeWriteAndTagBytesCommitUsingActualCreatedDocumentUri() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri document = begin("first", "Artist - Track.flac");
        assertEquals(
                "Artist - Track.flac",
                provider.names.get(DocumentsContract.getDocumentId(document)));
        byte[] bytes = new byte[] {1, 2, 3, 4, 5};
        try (OutputStream stream = DownloadFolder.open(resolver, document)) {
            stream.write(bytes);
        }
        Uri saved = DownloadFolder.commit(resolver, document, new ContentValues(), null, null);
        assertEquals(document, saved);
        assertEquals("drive", DocumentsContract.getTreeDocumentId(saved));
        assertArrayEquals(
                bytes,
                Files.readAllBytes(
                        provider.files.get(DocumentsContract.getDocumentId(saved)).toPath()));
        assertEquals(1, provider.creates);
        assertEquals(0, provider.renames);
        name(song("first"), "Artist - Track.flac");
        assertEquals(saved, DownloadFolder.existing(app, "Artist - Track.flac"));
    }

    @Test
    public void revokedPermissionFailsBeforeCreatingOrFallingBack() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        resolver.releasePersistableUriPermission(
                TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            begin("revoked", "Track.flac");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Reconnect"));
        }
        assertEquals(0, provider.creates);
        assertEquals(TREE.toString(), DownloadFolder.selected(app));
    }

    @Test
    public void unmountedAndReadOnlyFoldersFailWithoutMovingSelection() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        provider.mounted = false;
        try {
            begin("offline", "Track.flac");
            fail();
        } catch (IllegalStateException expected) {
        }
        provider.mounted = true;
        provider.writable = false;
        try {
            DownloadFolder.choose(app, TREE.toString());
            fail();
        } catch (IOException expected) {
        }
        assertEquals(0, provider.creates);
        assertEquals(TREE.toString(), DownloadFolder.selected(app));
    }

    @Test
    public void cancellationDeletesOnlyTheJournalledTemporaryDocument() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri stage = begin("cancelled", "Track.flac");
        assertEquals(1, DownloadFolder.delete(resolver, stage, null, null));
        assertEquals(1, provider.deletes);
        assertTrue(provider.files.isEmpty());
        Uri other = begin("changed", "Other.flac");
        provider.names.put(DocumentsContract.getDocumentId(other), "User renamed file.flac");
        assertEquals(0, DownloadFolder.delete(resolver, other, null, null));
        assertEquals(1, provider.deletes);
        assertEquals(1, provider.files.size());
    }

    @Test
    public void uncommittedFinalNameIsNotAdoptedAndRetryCreatesANewDocument() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri partial = begin("retry", "Track.flac");
        try (OutputStream out = DownloadFolder.open(resolver, partial)) {
            out.write(new byte[] {7, 7});
        }
        DownloadFolder.init(app);
        name(song("retry"), "Track.flac");
        assertNull(DownloadFolder.existing(app, "Track.flac"));
        Uri fresh = begin("retry", "Track.flac");
        assertNotEquals(partial, fresh);
        assertEquals(1, provider.deletes);
        assertEquals(2, provider.creates);
        assertFalse(provider.files.containsKey(DocumentsContract.getDocumentId(partial)));
        assertEquals(0, provider.files.get(DocumentsContract.getDocumentId(fresh)).length());
    }

    @Test
    public void filenameContextCannotRedirectAnUnrelatedLookup() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Song selected = song("selected");
        DownloadControls.remember(app, selected, null);
        name(selected, "one.flac");
        assertFalse(DownloadFolder.custom("different.flac"));
        assertFalse(DownloadFolder.export(false));
    }

    @Test
    public void nativeExportChoiceIsCapturedForQueuedRequests() throws Exception {
        com.music.bitchord.data.settings.AppSettings.exportDownloads.setValue(true);
        Song old = song("exported");
        DownloadControls.remember(app, old, null);
        com.music.bitchord.data.settings.AppSettings.exportDownloads.setValue(false);
        name(old, "exported.flac");
        assertFalse(DownloadFolder.custom("exported.flac"));
        assertTrue(DownloadFolder.export(false));
        assertFalse(com.music.bitchord.data.settings.AppSettings.exportDownloads.getValue());
    }

    @Test
    public void renderingSettingsDoesNotQueryStorageOrStartPicker() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        provider.mounted = false;
        DownloadsSettingsActivity activity =
                Robolectric.buildActivity(DownloadsSettingsActivity.class).setup().get();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertEquals(TREE.toString(), DownloadFolder.selected(app));
        assertEquals(0, provider.creates);
        activity.finish();
    }

    @Test
    public void explicitFolderActionUsesSystemPickerWithPersistableReadWriteAccess() {
        DownloadsSettingsActivity activity =
                Robolectric.buildActivity(DownloadsSettingsActivity.class).setup().get();
        activity.chooseFolder();
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.getAction());
        int flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        assertEquals(flags, intent.getFlags() & flags);
        assertEquals("", DownloadFolder.selected(app));
        activity.finish();
    }

    @Test
    public void defaultResolverPublicationIsUnchanged() {
        Uri untouched = Uri.parse("content://folders.test/document/native");
        assertEquals(
                untouched,
                DownloadFolder.commit(resolver, untouched, new ContentValues(), null, null));
        assertEquals(1, provider.nativeUpdates);
        assertEquals(0, provider.renames);
    }

    @Test
    public void revokedStorageDuringOpeningGivesFolderErrorAndSafeAbort() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri stage = begin("write-failure", "Track.flac");
        provider.openFails = true;
        try {
            DownloadFolder.open(resolver, stage);
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Reconnect"));
        }
        assertEquals(1, DownloadFolder.delete(resolver, stage, null, null));
        assertEquals(0, provider.renames);
    }

    @Test
    public void ambiguousCreationIsJournalledBeforeProviderCallAndNeverAutomaticallyRepeated()
            throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        provider.createFailsAfterCreate = true;
        try {
            begin("ambiguous", "Track.flac");
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(1, provider.creates);
        name(song("ambiguous"), "Track.flac");
        assertNull(DownloadFolder.existing(app, "Track.flac"));
        provider.createFailsAfterCreate = false;
        try {
            begin("ambiguous", "Track.flac");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Check the download folder"));
        }
        assertEquals(1, provider.creates);
        assertEquals(0, provider.deletes);
        DownloadFolder.removed(app, "ambiguous");
        name(song("ambiguous"), "Track.flac");
        assertNull(DownloadFolder.existing(app, "Track.flac"));
        assertEquals(0, provider.deletes);
        begin("ambiguous", "Track.flac");
        assertEquals(2, provider.creates);
    }

    @Test
    public void removingHistoryCannotPublishOrReopenAnUncommittedDocument() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri old = begin("detached", "Track.flac");
        DownloadFolder.removed(app, "detached");
        try {
            DownloadFolder.commit(resolver, old, new ContentValues(), null, null);
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            DownloadFolder.open(resolver, old);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(0, provider.deletes);
        Uri fresh = begin("detached", "Track.flac");
        assertNotEquals(old, fresh);
        assertEquals(2, provider.creates);
        assertEquals(1, DownloadFolder.delete(resolver, old, null, null));
        assertTrue(provider.files.containsKey(DocumentsContract.getDocumentId(fresh)));
    }

    @Test
    public void changingOrResettingDefaultKeepsExplicitCopyFolderAndExistingGrants()
            throws Exception {
        app.getSharedPreferences(DownloadFolder.SELECTIONS, 0)
                .edit()
                .putString("phoneTreeUri", "legacy-copy")
                .commit();
        assertEquals("", DownloadFolder.selected(app));
        DownloadFolder.choose(app, TREE.toString());
        assertEquals(
                TREE.toString(),
                app.getSharedPreferences(DownloadFolder.SELECTIONS, 0)
                        .getString("phoneTreeUri", ""));
        DownloadFolder.choose(app, "");
        assertEquals("", DownloadFolder.selected(app));
        assertEquals(
                TREE.toString(),
                app.getSharedPreferences(DownloadFolder.SELECTIONS, 0)
                        .getString("phoneTreeUri", ""));
        assertTrue(
                resolver.getPersistedUriPermissions().stream()
                        .anyMatch(p -> TREE.equals(p.getUri()) && p.isWritePermission()));
    }

    @Test
    public void writableProviderWithoutRenameCanCompleteANewDocument() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        provider.renameSupported = false;
        String foreign = provider.createDocument("drive", "audio/flac", "Unrelated.flac");
        Files.write(provider.files.get(foreign).toPath(), new byte[] {9, 8, 7});
        Uri document = begin("no-rename", "Track.flac");
        assertNotEquals(foreign, DocumentsContract.getDocumentId(document));
        try (OutputStream out = DownloadFolder.open(resolver, document)) {
            out.write(new byte[] {1, 2, 3});
        }
        assertEquals(
                document,
                DownloadFolder.commit(resolver, document, new ContentValues(), null, null));
        assertArrayEquals(
                new byte[] {1, 2, 3},
                Files.readAllBytes(
                        provider.files.get(DocumentsContract.getDocumentId(document)).toPath()));
        assertArrayEquals(
                new byte[] {9, 8, 7}, Files.readAllBytes(provider.files.get(foreign).toPath()));
        assertEquals(0, provider.renames);
        assertEquals(0, provider.deletes);
    }

    @Test
    public void preFixSessionAndPausedFailedMetadataKeepOriginalFolderAcrossRetry()
            throws Exception {
        Song queued = song("legacy-live"),
                paused = song("legacy-paused"),
                failed = song("legacy-failed"),
                completed = song("old-saved");
        com.music.bitchord.download.DownloadSession.State state =
                new com.music.bitchord.download.DownloadSession.State();
        state.items.add(
                new com.music.bitchord.download.DownloadSession.Item(
                        queued, "Original collection"));
        state.items.add(
                new com.music.bitchord.download.DownloadSession.Item(
                        completed, "Already completed"));
        com.music.bitchord.download.DownloadSession._state.setValue(state);
        com.music.bitchord.download.Downloads._saved.setValue(
                Collections.singletonMap("old-saved", "file:///existing.flac"));
        for (Song song : new Song[] {paused, failed}) {
            org.json.JSONObject old =
                    new org.json.JSONObject()
                            .put("id", song.videoId)
                            .put("song", MixedSongCodec.encode(song))
                            .put("from", "Original collection")
                            .put("paused", song == paused);
            app.getSharedPreferences(DownloadControls.PREFS, 0)
                    .edit()
                    .putString(
                            "request." + TransitionRuntime.key(song.videoId, "download"),
                            old.toString())
                    .commit();
        }
        DownloadFolder.choose(app, TREE.toString());
        assertNull(DownloadControls.folder(app, "old-saved"));
        DownloadControls.Host previous = DownloadControls.host;
        int[] resumed = {0};
        DownloadControls.host =
                new DownloadControls.Host() {
                    public void cancel(String id) {}

                    public boolean delete(Context context, String id) {
                        return true;
                    }

                    public void enqueue(Context context, Object song, String from) {
                        assertEquals("Original collection", from);
                        DownloadControls.remember(context, song, from);
                        name((Song) song, "legacy.flac");
                        assertFalse(DownloadFolder.custom("legacy.flac"));
                        assertFalse(DownloadFolder.export(true));
                        resumed[0]++;
                    }
                };
        try {
            for (Song song : new Song[] {queued, paused, failed})
                DownloadControls.retry(app, song.videoId);
            assertEquals(3, resumed[0]);
            assertTrue(DownloadControls.delete(app, failed.videoId));
            DownloadControls.remember(app, failed, "New request");
            name(failed, "new.flac");
            assertTrue(DownloadFolder.custom("new.flac"));
        } finally {
            DownloadControls.host = previous;
        }
        assertEquals(0, provider.creates);
    }

    @Test
    public void cancellingDuringCommitCannotPublishTheRequest() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri document = begin("cancel-publish", "Track.flac");
        provider.beforeOwnedQuery = () -> DownloadFolder.removed(app, "cancel-publish");
        try {
            DownloadFolder.commit(resolver, document, new ContentValues(), null, null);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(0, provider.renames);
        assertEquals(0, provider.deletes);
        assertEquals(1, provider.files.size());
    }

    @Test
    public void alreadyRemovedTemporaryDocumentCanBeRetriedAfterDriveReturns() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri stage = begin("gone", "Track.flac");
        provider.deleteDocument(DocumentsContract.getDocumentId(stage));
        provider.mounted = false;
        assertEquals(0, DownloadFolder.delete(resolver, stage, null, null));
        provider.mounted = true;
        assertEquals(1, DownloadFolder.delete(resolver, stage, null, null));
        begin("gone", "Track.flac");
        assertEquals(2, provider.creates);
    }

    @Test
    public void actualProviderNameAndTreeGrantSurviveMimeNormalization() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri document = begin("mime", "Track.audio");
        assertEquals(
                "Track.audio.flac", provider.names.get(DocumentsContract.getDocumentId(document)));
        try (OutputStream out = DownloadFolder.open(resolver, document)) {
            out.write(new byte[] {4, 5, 6});
        }
        assertEquals(
                document,
                DownloadFolder.commit(resolver, document, new ContentValues(), null, null));
        assertEquals("drive", DocumentsContract.getTreeDocumentId(document));
        assertTrue(
                resolver.getPersistedUriPermissions().stream()
                        .anyMatch(
                                p ->
                                        TREE.equals(p.getUri())
                                                && p.isReadPermission()
                                                && p.isWritePermission()));
    }

    @Test
    public void anotherRequestCannotAdoptOrOverwriteAnUncommittedDocument() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri first = begin("first-request", "Track.flac");
        try (OutputStream out = DownloadFolder.open(resolver, first)) {
            out.write(new byte[] {1});
        }
        Uri second = begin("second-request", "Track.flac");
        assertNotEquals(first, second);
        try (OutputStream out = DownloadFolder.open(resolver, second)) {
            out.write(new byte[] {2});
        }
        assertEquals(
                second, DownloadFolder.commit(resolver, second, new ContentValues(), null, null));
        assertArrayEquals(
                new byte[] {1},
                Files.readAllBytes(
                        provider.files.get(DocumentsContract.getDocumentId(first)).toPath()));
        assertArrayEquals(
                new byte[] {2},
                Files.readAllBytes(
                        provider.files.get(DocumentsContract.getDocumentId(second)).toPath()));
        name(song("second-request"), "Track.flac");
        assertEquals(second, DownloadFolder.existing(app, "Track.flac"));
    }

    @Test
    public void failedReceiptRemovalRestoresPublishingProtectionBeforeThrowing() throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri document = begin("persist-failure", "Track.flac");
        android.content.SharedPreferences original =
                app.getSharedPreferences(DownloadFolder.PREFS, 0);
        java.util.concurrent.atomic.AtomicBoolean failed =
                new java.util.concurrent.atomic.AtomicBoolean();
        android.content.SharedPreferences store =
                (android.content.SharedPreferences)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {android.content.SharedPreferences.class},
                                (proxy, method, args) -> {
                                    if (!method.getName().equals("edit"))
                                        return method.invoke(original, args);
                                    android.content.SharedPreferences.Editor editor =
                                            original.edit();
                                    boolean[] removing = {false};
                                    return java.lang.reflect.Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {
                                                android.content.SharedPreferences.Editor.class
                                            },
                                            (editProxy, editMethod, values) -> {
                                                if (editMethod.getName().equals("remove")
                                                        && ("write." + document).equals(values[0]))
                                                    removing[0] = true;
                                                Object result = editMethod.invoke(editor, values);
                                                if (editMethod.getName().equals("commit")
                                                        && removing[0]
                                                        && failed.compareAndSet(false, true))
                                                    return false;
                                                return editMethod.getReturnType()
                                                                == android.content.SharedPreferences
                                                                        .Editor.class
                                                        ? editProxy
                                                        : result;
                                            });
                                });
        Context wrapped =
                new ContextWrapper(app) {
                    public Context getApplicationContext() {
                        return this;
                    }

                    public android.content.SharedPreferences getSharedPreferences(
                            String name, int mode) {
                        return name.equals(DownloadFolder.PREFS)
                                ? store
                                : super.getSharedPreferences(name, mode);
                    }
                };
        DownloadFolder.init(wrapped);
        try {
            try {
                DownloadFolder.commit(resolver, document, new ContentValues(), null, null);
                fail();
            } catch (IllegalStateException expected) {
            }
            assertTrue(failed.get());
            assertEquals(
                    "publishing",
                    new org.json.JSONObject(original.getString("write." + document, "{}"))
                            .getString("phase"));
            assertEquals(0, DownloadFolder.delete(resolver, document, null, null));
            name(song("persist-failure"), "Track.flac");
            assertNull(DownloadFolder.existing(app, "Track.flac"));
            assertEquals(0, provider.deletes);
            assertTrue(provider.files.containsKey(DocumentsContract.getDocumentId(document)));
        } finally {
            DownloadFolder.init(app);
        }
    }

    Uri completed(String id, String name) throws Exception {
        DownloadFolder.choose(app, TREE.toString());
        Uri document = begin(id, name);
        try (OutputStream output = DownloadFolder.open(resolver, document)) {
            output.write(new byte[] {11, 22, 33});
        }
        return DownloadFolder.commit(resolver, document, new ContentValues(), null, null);
    }

    @Test
    public void completedDocumentUsesDocumentDeleteAndPreservesUnrelatedSavedFiles()
            throws Exception {
        Uri first = completed("delete-one", "first.flac");
        Uri other = completed("keep-other", "other.flac");
        Map<String, String> saved = new HashMap<>();
        saved.put("delete-one", first.toString());
        saved.put("keep-other", other.toString());
        com.music.bitchord.download.Downloads._saved.setValue(saved);
        assertTrue(DownloadControls.delete(app, "delete-one"));
        assertEquals(0, provider.legacyDeletes);
        assertEquals(1, provider.deletes);
        assertFalse(provider.files.containsKey(DocumentsContract.getDocumentId(first)));
        assertArrayEquals(
                new byte[] {11, 22, 33},
                Files.readAllBytes(
                        provider.files.get(DocumentsContract.getDocumentId(other)).toPath()));
        assertEquals(
                Collections.singletonMap("keep-other", other.toString()),
                com.music.bitchord.download.Downloads._saved.getValue());
        assertEquals(
                Collections.singletonList("delete-one"),
                com.music.bitchord.download.Downloads.forgotten);
    }

    @Test
    public void providerDeleteFailureKeepsSavedRowAndExactFile() throws Exception {
        Uri document = completed("delete-fails", "failed.flac");
        com.music.bitchord.download.Downloads._saved.setValue(
                new HashMap<>(Collections.singletonMap("delete-fails", document.toString())));
        provider.deleteFails = true;
        try {
            assertFalse(DownloadControls.delete(app, "delete-fails"));
        } catch (IOException | RuntimeException expected) {
        }
        assertEquals(
                document.toString(),
                com.music.bitchord.download.Downloads._saved.getValue().get("delete-fails"));
        assertTrue(provider.files.containsKey(DocumentsContract.getDocumentId(document)));
        assertTrue(com.music.bitchord.download.Downloads.forgotten.isEmpty());
        assertFalse(DownloadControls.hidden(app, "delete-fails"));
        assertEquals(0, provider.legacyDeletes);
    }

    @Test
    public void localFileDeleteUsesDocumentProtocolButOtherContentUrisKeepNativeDispatch()
            throws Exception {
        Uri document = completed("local-delete", "local.flac");
        assertTrue(DownloadControls.deleteLocalFile(app, document.toString()));
        assertEquals(1, provider.deletes);
        assertEquals(0, provider.legacyDeletes);
        Uri ordinary = Uri.parse("content://folders.test/ordinary/42");
        provider.legacyDeleteResult = 7;
        assertEquals(7, DownloadFolder.delete(resolver, ordinary, "owned=?", new String[] {"42"}));
        assertEquals(1, provider.legacyDeletes);
        assertEquals(ordinary, provider.legacyDeleteUri);
        assertEquals("owned=?", provider.legacyDeleteWhere);
        assertArrayEquals(new String[] {"42"}, provider.legacyDeleteArgs);
    }

    public static class FolderProvider extends ContentProvider {
        final Map<String, File> files = new LinkedHashMap<>();
        final Map<String, String> names = new HashMap<>();
        boolean mounted = true, writable = true, renameFails, openFails, createFailsAfterCreate;
        boolean renameSupported = true, deleteFails;
        int legacyDeletes, legacyDeleteResult = -1;
        Uri legacyDeleteUri;
        String legacyDeleteWhere;
        String[] legacyDeleteArgs;
        int creates, deletes, renames, nativeUpdates, opens;
        Runnable onRename, beforeOwnedQuery;

        public boolean onCreate() {
            return true;
        }

        public String getType(Uri uri) {
            return "audio/flac";
        }

        public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        public int delete(Uri uri, String where, String[] args) {
            legacyDeletes++;
            legacyDeleteUri = uri;
            legacyDeleteWhere = where;
            legacyDeleteArgs = args;
            if (legacyDeleteResult < 0) throw new UnsupportedOperationException();
            return legacyDeleteResult;
        }

        public int update(Uri uri, ContentValues values, String where, String[] args) {
            nativeUpdates++;
            return 1;
        }

        public Cursor query(
                Uri uri, String[] projection, String where, String[] args, String order) {
            try {
                if (uri.getPath().endsWith("/children"))
                    return queryChildDocuments(
                            DocumentsContract.getDocumentId(uri), projection, order);
                return queryDocument(DocumentsContract.getDocumentId(uri), projection);
            } catch (FileNotFoundException failure) {
                throw new IllegalStateException(failure);
            }
        }

        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return openDocument(DocumentsContract.getDocumentId(uri), mode, null);
        }

        public Bundle call(String method, String arg, Bundle extras) {
            try {
                Uri uri = extras.getParcelable("uri");
                String id = DocumentsContract.getDocumentId(uri);
                Bundle result = new Bundle();
                if (method.equals("android:createDocument")) {
                    String created =
                            createDocument(
                                    id,
                                    extras.getString("mime_type"),
                                    extras.getString("_display_name"));
                    result.putParcelable(
                            "uri", DocumentsContract.buildDocumentUri("folders.test", created));
                } else if (method.equals("android:renameDocument")) {
                    result.putParcelable(
                            "uri",
                            DocumentsContract.buildDocumentUri(
                                    "folders.test",
                                    renameDocument(id, extras.getString("_display_name"))));
                } else if (method.equals("android:deleteDocument")) deleteDocument(id);
                else throw new UnsupportedOperationException(method);
                return result;
            } catch (FileNotFoundException failure) {
                throw new IllegalStateException(failure);
            }
        }

        public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(new String[] {"root_id"});
        }

        public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
            if (!mounted) throw new FileNotFoundException("unmounted");
            if (beforeOwnedQuery != null
                    && projection != null
                    && projection.length == 1
                    && "_display_name".equals(projection[0])) beforeOwnedQuery.run();
            String[] columns =
                    projection == null
                            ? new String[] {"document_id", "_display_name", "mime_type", "flags"}
                            : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            if (!"drive".equals(id) && !files.containsKey(id)) return cursor;
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++)
                switch (columns[i]) {
                    case "document_id":
                        row[i] = id;
                        break;
                    case "_display_name":
                        row[i] = "drive".equals(id) ? "USB audio" : names.get(id);
                        break;
                    case "mime_type":
                        row[i] =
                                "drive".equals(id)
                                        ? DocumentsContract.Document.MIME_TYPE_DIR
                                        : "audio/flac";
                        break;
                    case "flags":
                        row[i] =
                                writable
                                        ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                                | DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                                                | (renameSupported
                                                        ? DocumentsContract.Document
                                                                .FLAG_SUPPORTS_RENAME
                                                        : 0)
                                        : 0;
                        break;
                }
            cursor.addRow(row);
            return cursor;
        }

        public Cursor queryChildDocuments(String id, String[] projection, String order)
                throws FileNotFoundException {
            MatrixCursor rows = new MatrixCursor(projection);
            for (String key : files.keySet())
                try (Cursor cursor = queryDocument(key, projection)) {
                    if (cursor.moveToFirst()) {
                        Object[] row = new Object[projection.length];
                        for (int i = 0; i < row.length; i++) row[i] = cursor.getString(i);
                        rows.addRow(row);
                    }
                }
            return rows;
        }

        public String createDocument(String parent, String mime, String name)
                throws FileNotFoundException {
            if (!mounted || !writable) throw new FileNotFoundException("unavailable");
            try {
                if ("audio/flac".equals(mime) && !name.endsWith(".flac")) name += ".flac";
                String id = "stage" + (++creates);
                files.put(
                        id,
                        File.createTempFile(
                                "download-folder", ".audio", getContext().getCacheDir()));
                names.put(id, name);
                if (createFailsAfterCreate) throw new IOException("provider response lost");
                return id;
            } catch (IOException failed) {
                throw new FileNotFoundException();
            }
        }

        public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
                throws FileNotFoundException {
            opens++;
            if (openFails) throw new FileNotFoundException("removed drive");
            return ParcelFileDescriptor.open(files.get(id), ParcelFileDescriptor.parseMode(mode));
        }

        public String renameDocument(String id, String name) throws FileNotFoundException {
            if (renameFails) throw new FileNotFoundException("rename unavailable");
            String renamed = "finished" + (++renames);
            files.put(renamed, files.remove(id));
            names.remove(id);
            names.put(renamed, name);
            if (onRename != null) onRename.run();
            return renamed;
        }

        public void deleteDocument(String id) throws FileNotFoundException {
            if (deleteFails) throw new FileNotFoundException("provider delete failed");
            File file = files.remove(id);
            if (file == null || !file.delete()) throw new FileNotFoundException();
            names.remove(id);
            deletes++;
        }

        public boolean isChildDocument(String parent, String document) {
            return "drive".equals(parent);
        }
    }
}
