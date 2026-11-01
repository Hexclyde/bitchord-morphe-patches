package app.bitchord.telegram;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class StorageSenseActivityTest {
    static final long NOW = 100L * 86_400_000L;

    static final class Owner implements StorageSense.Owner {
        final StorageSense.Category[] categories;
        final List<StorageSense.Item> items = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        Exception failure;

        Owner(StorageSense.Category... categories) {
            this.categories = categories;
        }

        @Override
        public StorageSense.Category[] categories() {
            return categories;
        }

        @Override
        public StorageSense.Listing list(StorageSense.Active active, StorageSense.Cancel cancel)
                throws Exception {
            if (failure != null) throw failure;
            return new StorageSense.Listing(new ArrayList<>(items), false);
        }

        @Override
        public StorageSense.Block recheck(StorageSense.Item item, StorageSense.Active active) {
            return item.block;
        }

        @Override
        public void remove(StorageSense.Item item) {
            removed.add(item.id);
            items.remove(item);
        }
    }

    static StorageSense.Active idle() {
        List<String> none = Collections.emptyList();
        return new StorageSense.Active(true, none, none, none, none);
    }

    static StorageSense.Item item(
            StorageSense.Category category,
            StorageSense.Effect effect,
            String id,
            String title,
            long bytes,
            StorageSense.Block block) {
        StorageSense.Item item = new StorageSense.Item();
        item.id = id;
        item.title = title;
        item.category = category;
        item.effect = effect;
        item.bytes = bytes;
        item.block = block;
        item.lastUsedMs = 1;
        item.automatic = effect == StorageSense.Effect.CACHE_RECLAIM;
        item.location = id;
        return item;
    }

    Owner cache, saved, telegram, patches;
    Context app;
    ActivityController<StorageSenseActivity> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(StorageSenseAuto.PREFS, 0).edit().clear().commit();
        cache = new Owner(StorageSense.Category.PLAYBACK_CACHE, StorageSense.Category.ADDON_CACHE);
        telegram = new Owner(StorageSense.Category.TELEGRAM_CACHE);
        patches = new Owner(StorageSense.Category.PATCH_CACHE);
        saved = new Owner(StorageSense.Category.SAVED);
        StorageSenseActivity.engines =
                context ->
                        new StorageSenseEngine(
                                Arrays.asList(cache, telegram, patches, saved),
                                StorageSenseActivityTest::idle,
                                () -> NOW);
    }

    @After
    public void teardown() {
        if (controller != null) controller.pause().stop().destroy();
        StorageSenseActivity.engines = StorageSenseAuto::engine;
        StorageSenseActivity.hashers = null;
    }

    StorageSenseActivity open() throws Exception {
        controller = Robolectric.buildActivity(StorageSenseActivity.class).setup();
        settle();
        return controller.get();
    }

    /** Lets the page's worker finish and its results reach the main thread. */
    void settle() throws Exception {
        for (int i = 0; i < 40; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void collect(View view, List<View> out) {
        out.add(view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collect(((ViewGroup) view).getChildAt(i), out);
    }

    static List<View> views(StorageSenseActivity activity) {
        List<View> all = new ArrayList<>();
        collect(activity.findViewById(android.R.id.content), all);
        return all;
    }

    static String text(StorageSenseActivity activity) {
        StringBuilder out = new StringBuilder();
        for (View view : views(activity))
            if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        return out.toString();
    }

    static View row(StorageSenseActivity activity, String title) {
        for (View view : views(activity)) {
            CharSequence about = view.getContentDescription();
            if (view.isClickable() && about != null && about.toString().startsWith(title))
                return view;
        }
        throw new AssertionError("No row " + title + " in\n" + text(activity));
    }

    static String message(AlertDialog dialog) {
        return String.valueOf(shadowOf(dialog).getMessage());
    }

    static AlertDialog latest() {
        return (AlertDialog) ShadowAlertDialog.getLatestDialog();
    }

    @Test
    public void openingLooksAndRemovesNothing() throws Exception {
        cache.items.add(
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "c",
                        "Cached song",
                        2048,
                        StorageSense.Block.NONE));
        saved.items.add(
                item(
                        StorageSense.Category.SAVED,
                        StorageSense.Effect.DELETE_SAVED_FILE,
                        "s",
                        "Saved song",
                        1024,
                        StorageSense.Block.NONE));
        StorageSenseActivity activity = open();
        String page = text(activity);
        assertTrue(page, page.contains("BitChord song cache"));
        assertTrue(page, page.contains("Saved downloads"));
        assertTrue(page, page.contains("2 KB"));
        assertTrue(page, page.contains("Nothing stored here"));
        assertTrue(cache.removed.isEmpty());
        assertTrue(saved.removed.isEmpty());
        // Reopening and rendering again is still only looking.
        controller.pause().stop().start().resume();
        settle();
        assertTrue(cache.removed.isEmpty());
        assertTrue(saved.removed.isEmpty());
    }

    @Test
    public void statesAreShownAsTheyAre() throws Exception {
        telegram.failure = new StorageSense.Unavailable("Sign in to Telegram to see its cache.");
        patches.failure = new java.io.IOException("disk error");
        String page = text(open());
        assertTrue(page, page.contains("Sign in to Telegram to see its cache."));
        assertTrue(page, page.contains("disk error"));
        assertTrue(page, page.contains("Nothing stored here"));
    }

    @Test
    public void savedFileNeedsTwoStepsAndSaysItsPermanent() throws Exception {
        saved.items.add(
                item(
                        StorageSense.Category.SAVED,
                        StorageSense.Effect.DELETE_SAVED_FILE,
                        "s",
                        "Saved song",
                        1024,
                        StorageSense.Block.NONE));
        StorageSenseActivity activity = open();
        row(activity, "Saved downloads").performClick();
        assertFalse(
                "Saved files have no bulk clear", text(activity).contains("that aren't in use"));
        row(activity, "Saved song").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog info = latest();
        assertTrue(message(info), message(info).contains("removes the file for good"));
        info.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Info alone deletes nothing", saved.removed.isEmpty());
        AlertDialog confirm = latest();
        assertNotSame(info, confirm);
        assertTrue(message(confirm), message(confirm).contains("permanently deleted"));
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        settle();
        assertTrue("Cancelling keeps the file", saved.removed.isEmpty());
        row(activity, "Saved song").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals(Collections.singletonList("s"), saved.removed);
        assertFalse(text(activity).contains("Saved song"));
    }

    @Test
    public void cacheSaysItOnlyReclaimsSpace() throws Exception {
        cache.items.add(
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "c",
                        "Cached song",
                        2048,
                        StorageSense.Block.NONE));
        StorageSenseActivity activity = open();
        row(activity, "BitChord song cache").performClick();
        row(activity, "Cached song").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(message(latest()), message(latest()).contains("only frees cache space"));
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(message(latest()), message(latest()).contains("Saved downloads aren't touched"));
        assertFalse(message(latest()), message(latest()).contains("permanently"));
    }

    @Test
    public void protectedRowsOfferNoDelete() throws Exception {
        cache.items.add(
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "p",
                        "Playing song",
                        2048,
                        StorageSense.Block.PLAYING));
        StorageSenseActivity activity = open();
        row(activity, "BitChord song cache").performClick();
        assertFalse(text(activity).contains("that aren't in use"));
        row(activity, "Playing song").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog info = latest();
        assertTrue(message(info), message(info).contains("playing or coming up next"));
        Button delete = info.getButton(AlertDialog.BUTTON_POSITIVE);
        assertTrue(delete == null || delete.getVisibility() != View.VISIBLE);
    }

    @Test
    public void bulkClearStaysBehindConfirmation() throws Exception {
        cache.items.add(
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "c",
                        "Free song",
                        2048,
                        StorageSense.Block.NONE));
        cache.items.add(
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "p",
                        "Playing song",
                        2048,
                        StorageSense.Block.PLAYING));
        StorageSenseActivity activity = open();
        row(activity, "BitChord song cache").performClick();
        row(activity, "Clear 1").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(cache.removed.isEmpty());
        assertTrue(message(latest()), message(latest()).contains("1 in use stay"));
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals(Collections.singletonList("c"), cache.removed);
    }

    @Test
    public void rulesAreOffAndChoosingOneRemovesNothing() throws Exception {
        cache.items.add(
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "c",
                        "Old song",
                        2048,
                        StorageSense.Block.NONE));
        StorageSense.Rules before = StorageSenseAuto.rules(app);
        assertFalse(before.any());
        StorageSenseActivity activity = open();
        TelegramSwitch age = null;
        for (View view : views(activity))
            if (view instanceof TelegramSwitch && "rule-age".equals(view.getTag()))
                age = (TelegramSwitch) view;
        assertNotNull(age);
        assertFalse(age.isChecked());
        age.performClick();
        settle();
        assertTrue(StorageSenseAuto.rules(app).ageOn);
        assertTrue("Turning a rule on isn't running it", cache.removed.isEmpty());
        List<View> chips = new ArrayList<>();
        for (View view : views(activity))
            if (view.getTag() != null && view.getTag().toString().startsWith("chip-"))
                chips.add(view);
        assertEquals(StorageSense.Rules.AGE_DAYS.length, chips.size());
        for (View chip : chips) assertTrue(((TextView) chip).getMinHeight() >= dp(activity, 48));
        chips.get(0).performClick();
        settle();
        assertEquals(StorageSense.Rules.AGE_DAYS[0], StorageSenseAuto.rules(app).ageDays);
        assertTrue(cache.removed.isEmpty());
        // The rule's scan found it old: running needs its own confirmation.
        row(activity, "Review and run now").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(cache.removed.isEmpty());
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals(Collections.singletonList("c"), cache.removed);
    }

    static int dp(StorageSenseActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Test
    public void everyActionIsLargeEnoughAtAnyFontScale() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        saved.items.add(
                item(
                        StorageSense.Category.SAVED,
                        StorageSense.Effect.DELETE_SAVED_FILE,
                        "s",
                        "A very long saved song title that has to wrap instead of being clipped",
                        1024,
                        StorageSense.Block.NONE));
        StorageSenseActivity activity = open();
        row(activity, "Saved downloads").performClick();
        View content = activity.findViewById(android.R.id.content);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        content.layout(0, 0, 720, content.getMeasuredHeight());
        for (View view : views(activity)) {
            if (view.isClickable() && view.getVisibility() == View.VISIBLE) {
                assertTrue(
                        view + " is " + view.getHeight(),
                        view.getHeight() >= dp(activity, 48) || view.getHeight() == 0);
            }
        }
        assertTrue(
                "The row really was measured",
                row(activity, "Saved downloads").getHeight() >= dp(activity, 48));
        TextView title = null;
        for (View view : views(activity))
            if (view instanceof TextView
                    && ((TextView) view).getText().toString().startsWith("A very long"))
                title = (TextView) view;
        assertNotNull(title);
        assertTrue("Long titles wrap rather than clip", title.getMaxLines() >= 2);
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test
    public void duplicatesAreOnlyWhatContentsProve() throws Exception {
        for (String id : new String[] {"a", "b", "c"}) {
            StorageSense.Item item =
                    item(
                            StorageSense.Category.SAVED,
                            StorageSense.Effect.DELETE_SAVED_FILE,
                            id,
                            "Same name",
                            1024,
                            StorageSense.Block.NONE);
            item.location = "file:///" + id;
            saved.items.add(item);
        }
        StorageSenseActivity.hashers = (item, cancel) -> item.id.equals("c") ? "other" : "same";
        StorageSenseActivity activity = open();
        row(activity, "Find exact duplicates").performClick();
        settle();
        String page = text(activity);
        assertTrue(page, page.contains("2 identical copies"));
        assertTrue(page, page.contains("This one stays"));
        assertTrue("Finding them deletes nothing", saved.removed.isEmpty());
    }

    /** With no host app, or no way to read playback, nothing counts as safe. */
    @Test
    public void realActiveStateFailsClosedWithoutThePlaybackHook() {
        assertFalse(StorageSenseActive.capture().known);
    }

    @Test
    public void realEngineWithoutHostStillRendersAndProtects() throws Exception {
        StorageSenseEngine engine = StorageSenseAuto.engine(app);
        StorageSenseEngine.Snapshot snapshot = engine.load(StorageSense.NEVER);
        StorageSenseEngine.Section songs =
                snapshot.sections.get(StorageSense.Category.PLAYBACK_CACHE);
        assertNotNull(songs);
        assertEquals(StorageSenseEngine.State.UNAVAILABLE, songs.state);
        for (StorageSense.Item item : snapshot.items())
            assertNotEquals(StorageSense.Block.NONE, item.block);
    }

    static final class Records implements StorageSenseSaved.Records {
        final Map<String, String> saved = new LinkedHashMap<>();
        final List<String> forgotten = new ArrayList<>();

        @Override
        public Map<String, String> saved() {
            return saved;
        }

        @Override
        public Map<String, Object> metadata() {
            return new HashMap<>();
        }

        @Override
        public void forget(String id) {
            forgotten.add(id);
            saved.remove(id);
        }
    }

    StorageSense.Item only(StorageSenseSaved owner, StorageSense.Active active) throws Exception {
        List<StorageSense.Item> items = owner.list(active, StorageSense.NEVER).items;
        assertEquals(1, items.size());
        return items.get(0);
    }

    @Test
    public void savedFilesAreProvenForgottenUnderEveryIdAndNotOtherwiseTouched() throws Exception {
        DownloadControls.Host previous = DownloadControls.host;
        DownloadControls.host =
                new DownloadControls.Host() {
                    public void cancel(String id) {}

                    public void enqueue(Context context, Object song, String from) {}

                    public boolean delete(Context context, String id) {
                        return false;
                    }
                };
        try {
            File downloads = new File(app.getFilesDir(), "downloads");
            downloads.mkdirs();
            File song = new File(downloads, "Artist - Song.flac");
            Files.write(song.toPath(), new byte[77]);
            Records records = new Records();
            String uri = Uri.fromFile(song).toString();
            records.saved.put("video", uri);
            records.saved.put("catalogue", uri);
            StorageSenseSaved owner = new StorageSenseSaved(app, records);
            StorageSense.Item item = only(owner, idle());
            assertEquals(77, item.bytes);
            assertEquals(StorageSense.Effect.DELETE_SAVED_FILE, item.effect);
            assertEquals(StorageSense.Block.NONE, item.block);
            assertEquals("One file, two ids, one row", 2, item.aliases.size());
            assertFalse(StorageSense.eligible(item));

            StorageSense.Active playing =
                    new StorageSense.Active(
                            true,
                            Collections.emptyList(),
                            Collections.emptyList(),
                            Collections.singletonList(uri),
                            Collections.emptyList());
            assertEquals(StorageSense.Block.PLAYING, owner.recheck(item, playing));
            assertEquals(
                    StorageSense.Block.STATE_UNKNOWN,
                    owner.recheck(item, StorageSense.Active.unknown()));

            owner.remove(item);
            assertFalse(song.exists());
            assertEquals(Arrays.asList("video", "catalogue"), records.forgotten);
        } finally {
            DownloadControls.host = previous;
        }
    }

    @Test
    public void filesOutsideBitChordsDownloadsAreNeverRemoved() throws Exception {
        File session = new File(app.getNoBackupFilesDir(), "telegram-native/db/session.db");
        session.getParentFile().mkdirs();
        Files.write(session.toPath(), new byte[5]);
        Records records = new Records();
        records.saved.put("x", Uri.fromFile(session).toString());
        StorageSenseSaved owner = new StorageSenseSaved(app, records);
        StorageSense.Item item = only(owner, idle());
        assertEquals(StorageSense.Block.LOCATION_UNPROVEN, item.block);
        assertFalse(item.removable());
        try {
            owner.remove(item);
            fail("A record pointing elsewhere must not delete");
        } catch (StorageSense.Refused expected) {
            assertEquals(StorageSense.Block.LOCATION_UNPROVEN, expected.reason);
        }
        assertTrue(session.exists());
        assertTrue(records.forgotten.isEmpty());
    }

    @Test
    public void offlinePackageGoesWhole() throws Exception {
        File pack = new File(app.getFilesDir(), "offline-hls/abc");
        File segment = new File(pack, "segments/1.ts");
        segment.getParentFile().mkdirs();
        File playlist = new File(pack, "playlist.m3u8");
        Files.write(playlist.toPath(), new byte[3]);
        Files.write(segment.toPath(), new byte[40]);
        Records records = new Records();
        records.saved.put("x", Uri.fromFile(playlist).toString());
        DownloadControls.Host previous = DownloadControls.host;
        DownloadControls.host =
                new DownloadControls.Host() {
                    public void cancel(String id) {}

                    public void enqueue(Context context, Object song, String from) {}

                    public boolean delete(Context context, String id) {
                        return false;
                    }
                };
        try {
            StorageSenseSaved owner = new StorageSenseSaved(app, records);
            StorageSense.Item item = only(owner, idle());
            assertEquals("The whole package is measured", 43, item.bytes);
            assertNull(
                    "A package isn't one file to compare", owner.sha256(item, StorageSense.NEVER));
            owner.remove(item);
            assertFalse(pack.exists());
        } finally {
            DownloadControls.host = previous;
        }
    }

    @Test
    public void patchCachesAreRealFilesAndReadOnly() throws Exception {
        File folder = new File(app.getNoBackupFilesDir(), "soulseek-native/transfers");
        folder.mkdirs();
        File audio = new File(folder, "track.part");
        Files.write(audio.toPath(), new byte[] {'f', 'L', 'a', 'C', 0, 0, 0, 0, 0, 0, 0, 0});
        File other = new File(folder, "notes.flac");
        Files.write(other.toPath(), new byte[12]);
        StorageSensePatchCaches owner = new StorageSensePatchCaches(app);
        List<StorageSense.Item> items = owner.list(idle(), StorageSense.NEVER).items;
        assertEquals(1, items.size());
        StorageSense.Item item = items.get(0);
        assertEquals("track.part", item.title);
        assertFalse(item.removable());
        assertFalse(StorageSense.eligible(item));
        try {
            owner.remove(item);
            fail();
        } catch (StorageSense.Refused expected) {
            assertTrue(audio.exists());
        }
        boolean named = false;
        for (StorageSensePatchCaches.Unmanaged row :
                StorageSensePatchCaches.unmanaged(app, StorageSense.NEVER))
            named |= row.label.contains("sign-in");
        assertTrue("Sign-in data is listed as left alone", named);
    }

    @Test
    public void rulesPassTakesOnlyCacheAndSkipsWhenStateIsUnknown() {
        StorageSense.Item old =
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "c",
                        "Old",
                        2048,
                        StorageSense.Block.NONE);
        StorageSense.Item file =
                item(
                        StorageSense.Category.SAVED,
                        StorageSense.Effect.DELETE_SAVED_FILE,
                        "s",
                        "File",
                        1024,
                        StorageSense.Block.NONE);
        cache.items.add(old);
        saved.items.add(file);
        StorageSense.Rules rules = new StorageSense.Rules();
        rules.ageOn = true;
        StorageSenseEngine engine =
                new StorageSenseEngine(
                        Arrays.asList(cache, saved), StorageSenseActivityTest::idle, () -> NOW);
        assertTrue(StorageSenseAuto.runRules(engine, rules, app));
        assertEquals(Collections.singletonList("c"), cache.removed);
        assertTrue(saved.removed.isEmpty());

        cache.removed.clear();
        cache.items.clear();
        StorageSense.Item unknown =
                item(
                        StorageSense.Category.PLAYBACK_CACHE,
                        StorageSense.Effect.CACHE_RECLAIM,
                        "u",
                        "Unknown",
                        2048,
                        StorageSense.Block.STATE_UNKNOWN);
        cache.items.add(unknown);
        assertFalse(StorageSenseAuto.runRules(engine, rules, app));
        assertTrue(cache.removed.isEmpty());
    }

    @Test
    public void downloadsPageLeadsToStorageSense() {
        android.app.Activity activity =
                Robolectric.buildActivity(DownloadsSettingsActivity.class).setup().get();
        List<View> all = new ArrayList<>();
        collect(activity.findViewById(android.R.id.content), all);
        View storage = null;
        for (View view : all) {
            CharSequence about = view.getContentDescription();
            if (view.isClickable() && about != null && about.toString().startsWith("Storage Sense"))
                storage = view;
        }
        assertNotNull(storage);
        storage.performClick();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertEquals(StorageSenseActivity.class.getName(), next.getComponent().getClassName());
    }
}
