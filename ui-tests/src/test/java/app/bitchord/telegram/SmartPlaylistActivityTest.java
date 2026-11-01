package app.bitchord.telegram;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import com.music.bitchord.data.spotify.LocalPlaylistStore;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SmartPlaylistActivityTest {
    private static final String KEY = "sk-never-in-plaintext";
    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger secretReads = new AtomicInteger();
    private volatile String reply;
    private volatile CountDownLatch hold;
    private ActivityController<SmartPlaylistActivity> controller;

    @Before
    public void setup() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        for (String n : new String[] {"context", "prefs", "hostPrefs", "changed"}) {
            java.lang.reflect.Field f = MixedPlaylists.class.getDeclaredField(n);
            f.setAccessible(true);
            f.set(null, null);
        }
        app.getSharedPreferences(MixedPlaylists.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences(MixedPlaylists.HOST_PREFERENCES, 0).edit().clear().commit();
        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        MixedPlaylists.init(app);
        SmartPlaylistActivity.secrets =
                new SmartPlaylistStore.Secrets() {
                    @Override
                    public String get(String name) {
                        secretReads.incrementAndGet();
                        return secrets.get(name);
                    }

                    @Override
                    public void put(String name, String value) {
                        if (value == null) secrets.remove(name);
                        else secrets.put(name, value);
                    }
                };
        SmartPlaylistActivity.pauseBetweenSearches = 0;
        SmartPlaylistActivity.best = (track, songs) -> songs.get(0);
        SmartPlaylistActivity.search =
                track -> {
                    searches.incrementAndGet();
                    List<Object> found = new ArrayList<>();
                    if (track.title.equals("Missing")) return found;
                    if (track.title.equals("Broken")) throw new IllegalStateException("offline");
                    found.add(
                            song(
                                    id(track.title),
                                    track.title.equals("Vague") ? "Different words" : track.title,
                                    track.title.equals("Vague") ? "Someone else" : track.artist));
                    return found;
                };
        SmartPlaylistActivity.transport =
                (method, url, key, body, call) -> {
                    requests.incrementAndGet();
                    CountDownLatch gate = hold;
                    if (gate != null) {
                        try {
                            gate.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException stopped) {
                            throw new java.io.InterruptedIOException();
                        }
                    }
                    return new SmartPlaylistService.Reply(200, reply);
                };
    }

    @After
    public void teardown() {
        if (hold != null) hold.countDown();
        if (controller != null) controller.pause().stop().destroy();
        SmartPlaylistActivity.secrets = null;
        SmartPlaylistActivity.transport = SmartPlaylistService.NETWORK;
        SmartPlaylistActivity.search = SmartPlaylistMatcher::installedSearch;
        SmartPlaylistActivity.best = PlaylistImport::best;
        SmartPlaylistActivity.pauseBetweenSearches = 150;
        SmartPlaylistActivity.local = LocalPlaylist::run;
        com.music.bitchord.data.settings.AppSettings.themeMode.setValue("SYSTEM");
        RuntimeEnvironment.setFontScale(1f);
    }

    private static String id(String title) {
        return (title + "___________").replaceAll("[^A-Za-z0-9_-]", "_").substring(0, 11);
    }

    private static Object song(String id, String title, String artist) throws JSONException {
        try {
            return MixedSongCodec.decode(
                    new JSONObject().put("videoId", id).put("title", title).put("artist", artist));
        } catch (JSONException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static String wrap(String... rows) throws JSONException {
        JSONArray tracks = new JSONArray();
        for (String title : rows)
            tracks.put(new JSONObject().put("title", title).put("artist", "Band"));
        return new JSONObject()
                .put(
                        "choices",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put(
                                                        "message",
                                                        new JSONObject()
                                                                .put(
                                                                        "content",
                                                                        new JSONObject()
                                                                                .put(
                                                                                        "title",
                                                                                        "Night")
                                                                                .put(
                                                                                        "tracks",
                                                                                        tracks)
                                                                                .toString()))))
                .toString();
    }

    private SmartPlaylistActivity open() {
        controller = Robolectric.buildActivity(SmartPlaylistActivity.class).setup();
        settle(() -> !text(root()).contains("Loading…") || !isShown("Loading…"));
        return controller.get();
    }

    /** The same stored choice the page's own control writes; see the mode tests below. */
    private SmartPlaylistActivity openService() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_smart_playlists", 0)
                .edit()
                .putString("mode", "service")
                .commit();
        return open();
    }

    private View root() {
        return controller.get().findViewById(android.R.id.content);
    }

    private void settle(BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (done.getAsBoolean()) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException stopped) {
                throw new AssertionError(stopped);
            }
        }
        throw new AssertionError("Timed out; page shows: " + text(root()));
    }

    private static String text(View view) {
        StringBuilder out = new StringBuilder();
        collect(view, out);
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collect(((ViewGroup) view).getChildAt(i), out);
    }

    private boolean isShown(String label) {
        return text(root()).contains(label);
    }

    private TextView find(String label) {
        TextView found = find(root(), label);
        if (found == null) throw new AssertionError("No " + label + " in: " + text(root()));
        return found;
    }

    private static TextView find(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText()))
            return (TextView) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                TextView found = find(((ViewGroup) view).getChildAt(i), label);
                if (found != null) return found;
            }
        return null;
    }

    private static EditText field(View view, String description) {
        if (view instanceof EditText && description.contentEquals(view.getContentDescription()))
            return (EditText) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                EditText found = field(((ViewGroup) view).getChildAt(i), description);
                if (found != null) return found;
            }
        return null;
    }

    private TextView primary() {
        return (TextView) root().findViewWithTag(SmartPlaylistActivity.PRIMARY_TAG);
    }

    private View row(String title) {
        return (View) find(title).getParent().getParent();
    }

    private void click(View view) {
        view.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lays the page out at the qualifier's phone size, as the window would. */
    private LinearLayout laidOut() {
        LinearLayout page = root().findViewWithTag(SmartPlaylistActivity.ROOT_TAG);
        android.util.DisplayMetrics metrics = controller.get().getResources().getDisplayMetrics();
        page.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
        page.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
        return page;
    }

    private static int visibleFields(View view) {
        if (view.getVisibility() != View.VISIBLE) return 0;
        if (view instanceof EditText) return 1;
        int count = 0;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                count += visibleFields(((ViewGroup) view).getChildAt(i));
        return count;
    }

    private static void assertNothingOverflows(View view) {
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            assertTrue(
                    child + " is wider than its parent",
                    child.getRight() <= group.getWidth() && child.getLeft() >= 0);
            assertNothingOverflows(child);
        }
    }

    private void configure() throws Exception {
        new SmartPlaylistStore(SmartPlaylistActivity.secrets)
                .save("https://service.example/v1", "model-a", KEY);
    }

    private void generate(String prompt) {
        field(root(), "A mood, a moment, or artists you like").setText(prompt);
        shadowOf(Looper.getMainLooper()).idle();
        click(find("Generate"));
    }

    @Test
    public void openingAndRenderingSendsNothing() throws Exception {
        configure();
        openService();
        assertEquals(0, requests.get());
        assertTrue(isShown("service.example · model-a"));
        assertFalse(find("Generate").isEnabled());
        field(root(), "A mood, a moment, or artists you like").setText("x");
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(find("Generate").isEnabled());
        assertEquals("Typing does not send", 0, requests.get());
        assertTrue("The external service is named before sending", isShown("service.example."));
        assertTrue("And again beside the action", isShown("Sends to service.example"));
    }

    @Test
    public void aMissingServiceOffersLocalSetupInsteadOfADeadEnd() {
        openService();
        assertTrue(isShown("Not set up"));
        assertEquals("Stays folded until asked for", 1, visibleFields(root()));
        assertFalse(isShown("Service URL (HTTPS)"));
        TextView action = primary();
        assertEquals("Set up service", action.getText().toString());
        assertTrue(action.isEnabled());
        assertNull("Generate is not a disabled dead end", find(root(), "Generate"));
        click(action);
        assertEquals("The same control now saves", "Save service", primary().getText().toString());
        assertTrue(field(root(), "Service URL (HTTPS)").hasFocus());
        assertTrue(isShown("Service URL (HTTPS)"));
        assertEquals("Opening setup is local", 0, requests.get());
    }

    @Test
    public void savingAServiceClearsTheTypedKeyAndStoresNoPlaintext() throws Exception {
        openService();
        click(primary());
        field(root(), "Service URL (HTTPS)").setText("https://service.example/v1");
        field(root(), "Model name").setText("model-a");
        field(root(), "Service key, if required").setText(KEY);
        click(find("Save service"));
        settle(() -> isShown("Service saved on this device."));
        assertEquals("", field(root(), "Service key, if required").getText().toString());
        assertEquals(KEY, new SmartPlaylistStore(SmartPlaylistActivity.secrets).credentials().key);
        assertFalse("Setup folds away once saved", isShown("Service URL (HTTPS)"));
        assertEquals("Generate", primary().getText().toString());
        assertTrue(isShown("service.example · model-a"));
        File shared =
                new File(
                        RuntimeEnvironment.getApplication().getApplicationInfo().dataDir,
                        "shared_prefs");
        if (shared.isDirectory())
            for (File file : shared.listFiles())
                assertFalse(
                        file.getName(),
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                                .contains(KEY));
        assertEquals("Saving a service sends nothing", 0, requests.get());
    }

    @Test
    public void invalidServiceDetailsAreRefusedAtTheField() {
        openService();
        click(primary());
        EditText url = field(root(), "Service URL (HTTPS)");
        url.setText("https://user:pass@service.example/v1?key=abc");
        field(root(), "Model name").setText("model-a");
        click(find("Save service"));
        assertNotNull(url.getError());
        assertTrue(url.hasFocus());
        assertTrue(secrets.isEmpty());
        assertEquals("Still in setup", "Save service", primary().getText().toString());
    }

    @Test
    public void onlyChooseModelReachesTheServiceFromSetup() throws Exception {
        reply = "{\"data\":[{\"id\":\"model-b\"}]}";
        openService();
        click(primary());
        field(root(), "Service URL (HTTPS)").setText("https://service.example/v1");
        field(root(), "Model name").setText("m");
        assertEquals(0, requests.get());
        click(find("Choose model"));
        settle(() -> requests.get() == 1 && !isShown("Loading models…"));
        android.app.AlertDialog models =
                (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertEquals(1, models.getListView().getAdapter().getCount());
        assertEquals("Still in setup", "Save service", primary().getText().toString());
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    public void theFirstViewportIsTheTaskNotASettingsForm() throws Exception {
        for (boolean ready : new boolean[] {false, true}) {
            if (ready) configure();
            openService();
            LinearLayout page = laidOut();
            ScrollView scroll = page.findViewWithTag(SmartPlaylistActivity.SCROLL_TAG);
            assertEquals("Only the description is open", 1, visibleFields(root()));
            assertTrue(
                    "Everything fits without scrolling",
                    scroll.getChildAt(0).getMeasuredHeight() <= scroll.getHeight());
            int[] description = new int[2], preferences = new int[2], service = new int[2];
            field(root(), "A mood, a moment, or artists you like").getLocationInWindow(description);
            row("Preferences").getLocationInWindow(preferences);
            row("External text service").getLocationInWindow(service);
            assertTrue(description[1] < preferences[1] && preferences[1] < service[1]);
            assertEquals(
                    "One primary action, pinned below the scroll",
                    page.getHeight(),
                    ((View) primary().getParent()).getBottom());
            assertTrue(((View) primary().getParent()).getTop() >= scroll.getBottom());
            controller.pause().stop().destroy();
            controller = null;
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    public void theActionStaysAboveTheKeyboard() throws Exception {
        configure();
        openService();
        LinearLayout page = laidOut();
        int keyboard = page.getHeight() / 2;
        page.dispatchApplyWindowInsets(
                new WindowInsets.Builder()
                        .setInsets(
                                WindowInsets.Type.ime(),
                                android.graphics.Insets.of(0, 0, 0, keyboard))
                        .setInsets(
                                WindowInsets.Type.statusBars(),
                                android.graphics.Insets.of(0, 60, 0, 0))
                        .build());
        laidOut();
        View action = page.findViewWithTag(SmartPlaylistActivity.ACTION_TAG);
        ScrollView scroll = page.findViewWithTag(SmartPlaylistActivity.SCROLL_TAG);
        assertEquals(page.getHeight() - keyboard, action.getBottom());
        assertTrue(
                "The scrolling area gives way, not the action",
                scroll.getBottom() <= action.getTop());
        assertEquals(60, page.getPaddingTop());
        assertTrue(
                primary().getHeight()
                        >= 48 * controller.get().getResources().getDisplayMetrics().density);
    }

    @Test
    public void preferencesOpenInPlaceAndSummariseWhenClosed() throws Exception {
        configure();
        openService();
        assertTrue(isShown("25 songs · Favor well-known songs"));
        assertNull("Pills stay folded", find(root(), "50"));
        click(row("Preferences"));
        assertNotNull(find("50"));
        assertTrue(isShown("Avoid explicit lyrics"));
        click(find("50"));
        assertTrue("The summary follows the choice", isShown("50 songs · Favor well-known songs"));
        click(row("Preferences"));
        assertNull(find(root(), "10"));
        assertEquals(
                "Preferences persist locally",
                50,
                controller
                        .get()
                        .getSharedPreferences("bitchord_smart_playlists", 0)
                        .getInt("count", 0));
        assertEquals("Nothing was sent", 0, requests.get());
    }

    @Test
    public void onlyOnePanelIsOpenAndItSurvivesRotation() throws Exception {
        configure();
        openService();
        click(row("Preferences"));
        click(row("External text service"));
        assertNull("Opening one folds the other", find(root(), "50"));
        assertEquals("Save service", primary().getText().toString());
        assertFalse("No Generate notice during setup", isShown("Sends to service.example"));
        field(root(), "Model name").setText("draft-model");
        controller.recreate();
        settle(() -> isShown("Service URL (HTTPS)"));
        assertEquals("draft-model", field(root(), "Model name").getText().toString());
        click(row("External text service"));
        assertEquals("Closing returns to the task", "Generate", primary().getText().toString());
    }

    @Test
    public void unreadableDetailsFoldBehindALocalSetupAction() {
        secrets.put("smart_playlist_service", "not readable");
        openService();
        assertTrue(isShown("Saved details can't be read. Set up again."));
        assertEquals(1, visibleFields(root()));
        assertEquals("Set up service", primary().getText().toString());
    }

    @Test
    public void foldedRowsTellAccessibilityServicesTheirState() throws Exception {
        configure();
        openService();
        View row = row("External text service");
        assertEquals("Collapsed", row.getStateDescription().toString());
        android.view.accessibility.AccessibilityNodeInfo info =
                android.view.accessibility.AccessibilityNodeInfo.obtain();
        row.onInitializeAccessibilityNodeInfo(info);
        assertTrue(
                info.getActionList()
                        .contains(
                                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                        .ACTION_EXPAND));
        assertTrue(row.getContentDescription().toString().contains("service.example · model-a"));
        row.performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Expanded", row.getStateDescription().toString());
        assertTrue(isShown("Service URL (HTTPS)"));
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    public void largeTextKeepsEveryStateUsable() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        configure();
        reply = wrap("A very long song title that needs to wrap on a phone", "Second");
        openService();
        LinearLayout page = laidOut();
        assertNothingOverflows(page);
        click(row("Preferences"));
        page = laidOut();
        assertNothingOverflows(page);
        click(row("External text service"));
        page = laidOut();
        assertNothingOverflows(page);
        TextView chooser = find("Choose model");
        assertTrue("The pill keeps its whole label", chooser.getWidth() > 0);
        assertEquals(
                "The field keeps the full width at large text",
                ((View) field(root(), "Model name").getParent()).getWidth(),
                field(root(), "Model name").getWidth());
        click(row("External text service"));
        generate("late");
        settle(() -> isShown("0 to review"));
        page = laidOut();
        assertNothingOverflows(page);
        View action = page.findViewWithTag(SmartPlaylistActivity.ACTION_TAG);
        assertEquals(page.getHeight(), action.getBottom());
    }

    @Test
    public void bothThemesPaintThePage() throws Exception {
        configure();
        com.music.bitchord.data.settings.AppSettings.themeMode.setValue("LIGHT");
        openService();
        View page = root().findViewWithTag(SmartPlaylistActivity.ROOT_TAG);
        assertEquals(
                android.graphics.Color.WHITE,
                ((android.graphics.drawable.ColorDrawable) page.getBackground()).getColor());
        assertEquals(android.graphics.Color.WHITE, primary().getCurrentTextColor());
        controller.pause().stop().destroy();
        controller = null;
        com.music.bitchord.data.settings.AppSettings.themeMode.setValue("DARK");
        openService();
        page = root().findViewWithTag(SmartPlaylistActivity.ROOT_TAG);
        assertEquals(
                android.graphics.Color.BLACK,
                ((android.graphics.drawable.ColorDrawable) page.getBackground()).getColor());
        assertEquals(android.graphics.Color.BLACK, primary().getCurrentTextColor());
    }

    @Test
    public void workingStatesPutProgressAndCancelInTheActionArea() throws Exception {
        configure();
        reply = wrap("Zulu");
        hold = new CountDownLatch(1);
        openService();
        generate("late");
        settle(() -> requests.get() == 1);
        assertEquals(
                "The description stays visible",
                "late",
                field(root(), "A mood, a moment, or artists you like").getText().toString());
        assertEquals(View.GONE, primary().getVisibility());
        assertFalse(isShown("Preferences"));
        View area = root().findViewWithTag(SmartPlaylistActivity.ACTION_TAG);
        assertNotNull(find(area, "Cancel"));
        assertNotNull(find(area, "Waiting for the text service…"));
    }

    @Test
    public void disconnectAsksFirstThenRemovesTheService() throws Exception {
        configure();
        openService();
        click(row("External text service"));
        click(find("Disconnect service"));
        assertFalse("Nothing is removed until confirmed", secrets.isEmpty());
        android.app.AlertDialog dialog =
                (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        settle(() -> isShown("Service removed."));
        assertTrue(secrets.isEmpty());
        assertEquals("Set up service", primary().getText().toString());
        assertFalse("Removal folds the form away", isShown("Service URL (HTTPS)"));
    }

    @Test
    public void confidentMatchesSaveInTheSuggestedOrder() throws Exception {
        configure();
        reply = wrap("Zulu", "Alpha", "Mike");
        openService();
        generate("late night");
        settle(() -> isShown("3 matched · 0 to review"));
        assertEquals(1, requests.get());
        assertEquals("Night", field(root(), "Playlist name").getText().toString());
        TextView save = find("Save playlist");
        assertTrue(save.isEnabled());
        save.performClick();
        settle(() -> controller.get().isFinishing());
        List<Object> playlists = MixedPlaylists.nativePlaylists();
        assertEquals(1, playlists.size());
        assertEquals("Night", MixedSongCodec.text(playlists.get(0), "title"));
        List<Object> saved = MixedPlaylists.songs(playlists.get(0));
        assertEquals("Zulu", MixedSongCodec.text(saved.get(0), "title"));
        assertEquals("Alpha", MixedSongCodec.text(saved.get(1), "title"));
        assertEquals("Mike", MixedSongCodec.text(saved.get(2), "title"));
        assertEquals(id("Zulu"), MixedSongCodec.text(saved.get(0), "videoId"));
    }

    @Test
    public void lowConfidenceAndMissingTracksBlockSavingUntilReviewed() throws Exception {
        configure();
        reply = wrap("Good", "Vague", "Missing", "Broken");
        openService();
        generate("mix");
        settle(() -> isShown("1 matched · 3 to review"));
        assertTrue("Confidence is shown", text(root()).contains("Review match: "));
        assertTrue(isShown("Search failed · tap to review"));
        assertTrue(isShown("No match found · tap to skip"));
        assertFalse(find("Save playlist").isEnabled());
        find("Save playlist").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Blocked save writes nothing", MixedPlaylists.nativePlaylists().isEmpty());
        find("Skip unresolved tracks").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(isShown("1 matched · 0 to review"));
        assertFalse(isShown("Skip unresolved tracks"));
        find("Save playlist").performClick();
        settle(() -> controller.get().isFinishing());
        List<Object> saved = MixedPlaylists.songs(MixedPlaylists.nativePlaylists().get(0));
        assertEquals("Skipped tracks are not saved", 1, saved.size());
        assertEquals("Good", MixedSongCodec.text(saved.get(0), "title"));
    }

    @Test
    public void aLowConfidenceCandidateCanBeChosenExplicitly() throws Exception {
        configure();
        reply = wrap("Vague");
        openService();
        generate("mix");
        settle(() -> isShown("0 matched · 1 to review"));
        ((View) find("Vague · Band").getParent().getParent()).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        android.app.AlertDialog dialog =
                (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        ListView options = dialog.getListView();
        assertEquals("Candidate plus Skip", 2, options.getAdapter().getCount());
        options.performItemClick(options, 0, 0);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(isShown("1 matched · 0 to review"));
        assertTrue(find("Save playlist").isEnabled());
    }

    @Test
    public void cancellingDropsTheReplyAndLeavesNothingToSave() throws Exception {
        configure();
        reply = wrap("Zulu");
        hold = new CountDownLatch(1);
        openService();
        generate("late");
        settle(() -> requests.get() == 1);
        assertTrue(isShown("Waiting for the text service…"));
        find("Cancel").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(isShown("Cancelled. Nothing was saved."));
        hold.countDown();
        Thread.sleep(50);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("The stale reply is ignored", isShown("Save playlist"));
        assertTrue(MixedPlaylists.nativePlaylists().isEmpty());
        assertEquals(
                "The form is back",
                "late",
                field(root(), "A mood, a moment, or artists you like").getText().toString());
    }

    @Test
    public void leavingThePageCancelsWithoutAStaleSave() throws Exception {
        configure();
        reply = wrap("Zulu");
        hold = new CountDownLatch(1);
        openService();
        generate("late");
        settle(() -> requests.get() == 1);
        controller.pause().stop().destroy();
        controller = null;
        hold.countDown();
        Thread.sleep(50);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(MixedPlaylists.nativePlaylists().isEmpty());
    }

    @Test
    public void serviceFailuresShowARecoveryAndKeepTheDescription() throws Exception {
        configure();
        SmartPlaylistActivity.transport =
                (method, url, key, body, call) -> new SmartPlaylistService.Reply(401, "");
        openService();
        generate("late");
        settle(() -> isShown("The service rejected this key. Check it and try again."));
        assertEquals(
                "late",
                field(root(), "A mood, a moment, or artists you like").getText().toString());
        assertTrue(MixedPlaylists.nativePlaylists().isEmpty());
    }

    @Test
    public void reviewSurvivesRotationWithoutASecondRequest() throws Exception {
        configure();
        reply = wrap("Zulu", "Alpha");
        openService();
        generate("late");
        settle(() -> isShown("2 matched · 0 to review"));
        controller.recreate();
        settle(() -> isShown("2 matched · 0 to review"));
        assertEquals(1, requests.get());
        assertTrue(find("Save playlist").isEnabled());
    }

    @Test
    public void touchTargetsMeetTheMinimum() throws Exception {
        configure();
        reply = wrap("Zulu");
        openService();
        generate("late");
        settle(() -> isShown("1 matched · 0 to review"));
        float density = controller.get().getResources().getDisplayMetrics().density;
        assertTrue(find("Save playlist").getMinHeight() >= 48 * density);
        assertTrue(find("Start over").getMinHeight() >= 48 * density);
        assertTrue(
                ((View) find("Zulu · Band").getParent().getParent()).getMinimumHeight()
                        >= 48 * density);
    }

    // ── Local generation, the default ───────────────────────────────────────

    private static final String DESCRIPTION = "A mood, a moment, or artists you like";

    private static void savePlaylist(String title, Object... songs) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> list =
                new ArrayList<>((List<Object>) LocalPlaylistStore._playlists.getValue());
        list.add(
                MixedPlaylists.playlist(
                        "bc_mix_saved" + list.size(),
                        title,
                        new ArrayList<>(Arrays.asList(songs))));
        LocalPlaylistStore._playlists.setValue(list);
    }

    private void describe(String prompt) {
        field(root(), DESCRIPTION).setText(prompt);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private String storedMode() {
        return RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_smart_playlists", 0)
                .getString("mode", null);
    }

    private void assertNothingLeftThePage() {
        assertEquals("No service request", 0, requests.get());
        assertEquals("No source search", 0, searches.get());
        assertTrue(com.music.bitchord.download.Downloads.queued.isEmpty());
    }

    private List<Object> savedSongs() throws Exception {
        List<Object> playlists = MixedPlaylists.nativePlaylists();
        for (Object playlist : playlists)
            if (!MixedSongCodec.text(playlist, "id").startsWith("bc_mix_saved"))
                return MixedPlaylists.songs(playlist);
        return null;
    }

    private void settleReview() {
        settle(() -> isShown("Save playlist") && primary().getVisibility() == View.VISIBLE);
    }

    @Test
    public void localIsTheDefaultAndOpeningContactsNothing() throws Exception {
        configure();
        secretReads.set(0);
        open();
        assertNull("Opening doesn't choose a mode", storedMode());
        assertEquals("Generate", primary().getText().toString());
        assertFalse("No description yet", primary().isEnabled());
        assertEquals("Only the description is open", 1, visibleFields(root()));
        assertFalse("Even a saved service stays folded away", isShown("service.example"));
        assertNull(find(root(), "External text service"));
        assertNull("No setup in the way", find(root(), "Set up service"));
        assertTrue(isShown("Stays on this device"));
        describe("rock");
        assertTrue("Generate needs no setup", primary().isEnabled());
        assertNothingLeftThePage();
        assertEquals("The saved service isn't even read", 0, secretReads.get());
    }

    @Test
    public void theLibraryStatusIsRealAndNeverMentionsTags() throws Exception {
        open();
        settle(() -> isShown("Nothing saved yet."));
        savePlaylist("While away", song("ccccccccccc", "New", "Band"));
        controller.pause().stop().restart().start().resume();
        settle(() -> isShown("1 saved song,"));
        controller.pause().stop().destroy();
        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        savePlaylist(
                "Mine",
                song("abcdefghijk", "One", "Band"),
                song("src:telegram-native::-100_5", "Two", "Band"));
        open();
        settle(() -> isShown("2 saved songs"));
        assertFalse(isShown("tagged"));
        assertNothingLeftThePage();
    }

    @Test
    public void aLocalPlaylistShowsReasonsAndSavesRealIdentities() throws Exception {
        configure();
        Object catalogue = song("abcdefghijk", "Paranoid Android", "Radiohead");
        Object channel = song("src:telegram-native::-100_5", "Karma Police", "Radiohead");
        Object peer = song("src:soulseek-9::user\\track.flac", "No Surprises", "Radiohead");
        Object other = song("src:deezer-3::1", "Dancing Queen", "Abba");
        savePlaylist("Saved", catalogue, channel, peer, other);
        secretReads.set(0);
        open();
        generate("radiohead");
        settleReview();
        assertTrue(isShown("Review songs"));
        assertEquals("radiohead", field(root(), "Playlist name").getText().toString());
        assertTrue(isShown("Matches the artist"));
        assertFalse(isShown("Dancing Queen"));
        assertTrue(isShown("3 selected"));
        assertNothingLeftThePage();
        assertEquals(0, secretReads.get());

        // The save has to leave the main thread, whichever screen asked for it.
        Set<String> threads = ConcurrentHashMap.newKeySet();
        @SuppressWarnings("unchecked")
        List<Object> before = (List<Object>) LocalPlaylistStore._playlists.getValue();
        LocalPlaylistStore._playlists.setValue(
                new ArrayList<Object>(before) {
                    @Override
                    public Object[] toArray() {
                        threads.add(Thread.currentThread().getName());
                        return super.toArray();
                    }
                });
        click(find("Save playlist"));
        settle(() -> controller.get().isFinishing());
        assertEquals(Set.of("bitchord-smart-playlist"), threads);
        Set<String> ids = new HashSet<>();
        for (Object saved : savedSongs()) ids.add(MixedSongCodec.text(saved, "videoId"));
        assertEquals(
                Set.of(
                        "abcdefghijk",
                        "src:telegram-native::-100_5",
                        "src:soulseek-9::user\\track.flac"),
                ids);
        assertNothingLeftThePage();
    }

    @Test
    public void aSongRemovedInReviewIsNotSavedAndCanComeBack() throws Exception {
        savePlaylist(
                "Saved",
                song("aaaaaaaaaaa", "First", "Radiohead"),
                song("bbbbbbbbbbb", "Second", "Radiohead"));
        open();
        generate("radiohead");
        settleReview();
        click(row("First · Radiohead"));
        assertTrue(isShown("1 selected"));
        assertTrue(isShown("Removed · tap to add back"));
        click(row("First · Radiohead"));
        assertTrue(isShown("2 selected"));
        click(row("First · Radiohead"));
        click(row("Second · Radiohead"));
        assertFalse("Nothing selected, nothing to save", primary().isEnabled());
        click(primary());
        assertNull(savedSongs());
        click(row("Second · Radiohead"));
        click(primary());
        settle(() -> controller.get().isFinishing());
        List<Object> saved = savedSongs();
        assertEquals(1, saved.size());
        assertEquals("Second", MixedSongCodec.text(saved.get(0), "title"));
    }

    @Test
    public void aShortListSaysSoAndIsNotPadded() throws Exception {
        savePlaylist(
                "Saved",
                song("aaaaaaaaaaa", "First", "Radiohead"),
                song("bbbbbbbbbbb", "Other", "Abba"));
        open();
        generate("radiohead");
        settleReview();
        assertTrue(isShown("1 selected"));
        assertTrue(isShown("Only 1 of 25 songs fit your description."));
        assertFalse(isShown("Other · Abba"));
    }

    @Test
    public void anEmptyLibraryKeepsTheFormAndExplainsWhatToDo() throws Exception {
        open();
        generate("rock");
        settle(() -> isShown("Nothing is saved on this device yet."));
        assertEquals("rock", field(root(), DESCRIPTION).getText().toString());
        assertEquals("Generate", primary().getText().toString());
        assertTrue("It can be tried again", primary().isEnabled());
        assertFalse(isShown("Save playlist"));
        assertNothingLeftThePage();
    }

    @Test
    public void noMatchIsAnHonestDeadEndNotAnOfferOfRandomSongs() throws Exception {
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Radiohead"));
        open();
        generate("zzqqxx");
        settle(() -> isShown("No saved songs match that."));
        assertFalse(isShown("without tags"));
        assertFalse(isShown("Save playlist"));
    }

    @Test
    public void songsWithoutTagsAreOnlyReviewedWhenAskedForAndStartUnselected() throws Exception {
        savePlaylist(
                "Saved",
                song("aaaaaaaaaaa", "First", "Band"),
                song("bbbbbbbbbbb", "Second", "Art"));
        open();
        generate("chill");
        settle(() -> isShown("the tags this needs"));
        assertFalse("No review until asked for", isShown("Save playlist"));
        assertEquals("Generate", primary().getText().toString());
        assertNull(savedSongs());

        controller.recreate();
        settle(() -> isShown("Review 2 songs without tags"));
        assertEquals(
                "The description survives",
                "chill",
                field(root(), DESCRIPTION).getText().toString());

        click(find("Review 2 songs without tags"));
        assertTrue(isShown("Songs without tags"));
        assertTrue(isShown("No tags show this fits · tap to add"));
        assertFalse("Nothing is claimed about them", isShown("Matches"));
        assertTrue(isShown("0 selected"));
        assertFalse("Nothing to save yet", primary().isEnabled());
        click(primary());
        assertNull("Silence saves nothing", savedSongs());

        click(row("Second · Art"));
        assertTrue(isShown("1 selected"));
        assertTrue(isShown("Added by you · tap to remove"));
        controller.recreate();
        settle(() -> isShown("1 selected"));
        click(primary());
        settle(() -> controller.get().isFinishing());
        List<Object> saved = savedSongs();
        assertEquals("Only what was chosen", 1, saved.size());
        assertEquals("bbbbbbbbbbb", MixedSongCodec.text(saved.get(0), "videoId"));
    }

    @Test
    public void untaggedSongsStayOutOfAListThatHasRealMatches() throws Exception {
        savePlaylist("Chill evenings", song("aaaaaaaaaaa", "Evening", "Band"));
        savePlaylist("Other", song("bbbbbbbbbbb", "Mystery", "Art"));
        open();
        generate("chill");
        settleReview();
        assertTrue(isShown("From a matching playlist"));
        assertTrue(isShown("1 selected"));
        assertFalse(isShown("Mystery · Art"));
        assertTrue("Offered separately", isShown("Review 1 song without tags"));

        click(primary());
        settle(() -> controller.get().isFinishing());
        List<Object> saved = savedSongs();
        assertEquals(1, saved.size());
        assertEquals("aaaaaaaaaaa", MixedSongCodec.text(saved.get(0), "videoId"));
    }

    @Test
    public void openingTheOfferBesideRealMatchesKeepsBothApart() throws Exception {
        savePlaylist("Chill evenings", song("aaaaaaaaaaa", "Evening", "Band"));
        savePlaylist("Other", song("bbbbbbbbbbb", "Mystery", "Art"));
        open();
        generate("chill");
        settleReview();
        click(find("Review 1 song without tags"));
        assertFalse("The offer is used up", isShown("Review 1 song without tags"));
        assertTrue(isShown("Songs without tags"));
        assertTrue("Still only the real match", isShown("1 selected"));
        click(row("Mystery · Art"));
        assertTrue(isShown("2 selected"));
    }

    @Test
    public void editingTheDescriptionWithdrawsAnOfferForTheOldOne() throws Exception {
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Band"));
        open();
        generate("chill");
        settle(() -> isShown("Review 1 song without tags"));
        EditText description = field(root(), DESCRIPTION);
        description.requestFocus();
        description.setText("rock");
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(isShown("without tags"));
    }

    @Test
    public void realTagsBecomeReasonsAndAGenreIsNeverCalledATempo() throws Exception {
        Object tagged = song("aaaaaaaaaaa", "Tagged", "Band");
        Object guess = song("bbbbbbbbbbb", "Guess", "Crew");
        Object measured = song("ccccccccccc", "Measured", "Group");
        LocalPlaylistLibrary.Library library = new LocalPlaylistLibrary.Library();
        library.storesReadable = true;
        library.entries.add(
                new LocalPlaylistEngine.Entry(tagged, "stream:a", "Tagged", "Band", "")
                        .bpm(82, LocalPlaylistEngine.Tempo.TAGGED));
        library.entries.add(
                new LocalPlaylistEngine.Entry(guess, "stream:b", "Guess", "Crew", "")
                        .genre("Folk")
                        .year(1999));
        library.entries.add(
                new LocalPlaylistEngine.Entry(measured, "stream:c", "Measured", "Group", "")
                        .bpm(79, LocalPlaylistEngine.Tempo.MEASURED));
        SmartPlaylistActivity.local =
                (app, description, count, familiar, clean, cancelled) ->
                        LocalPlaylist.from(
                                library, description, count, familiar, clean, cancelled, null);
        open();
        generate("chill");
        settleReview();
        assertTrue(isShown("Tagged 82 BPM"));
        assertTrue(isShown("Measured 79 BPM"));
        View row = row("Guess · Crew");
        assertTrue(text(row).contains("Its genre suggests this energy"));
        assertFalse("A genre has no tempo to claim", text(row).contains("BPM"));
    }

    @Test
    public void preferencesReachTheGeneratorAndLocalLabelsAreHonest() throws Exception {
        AtomicInteger seenCount = new AtomicInteger();
        List<Boolean> flags = new CopyOnWriteArrayList<>();
        SmartPlaylistActivity.local =
                (app, description, count, familiar, clean, cancelled) -> {
                    seenCount.set(count);
                    flags.add(familiar);
                    flags.add(clean);
                    return LocalPlaylist.run(app, description, count, familiar, clean, cancelled);
                };
        open();
        assertTrue(
                "Offline, familiar means familiar to you", isShown("Favor songs you play often"));
        assertFalse(isShown("Favor well-known songs"));
        click(row("Preferences"));
        click(find("50"));
        click(findSwitch("Avoid explicit lyrics"));
        click(row("Preferences"));
        generate("rock");
        settle(() -> seenCount.get() == 50);
        settle(() -> primary().getVisibility() == View.VISIBLE);
        assertEquals(List.of(true, true), flags);
    }

    private View findSwitch(String description) {
        return findDescribed(root(), description);
    }

    private static View findDescribed(View view, String description) {
        if (view instanceof TelegramSwitch
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = findDescribed(((ViewGroup) view).getChildAt(i), description);
                if (found != null) return found;
            }
        return null;
    }

    /** Waits inside the generator until it is told to stop, like a long read of a big library. */
    private static final class Held implements SmartPlaylistActivity.LocalGenerator {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean sawCancel;

        @Override
        public LocalPlaylist.Result run(
                Context app,
                String description,
                int count,
                boolean familiar,
                boolean avoidExplicit,
                BooleanSupplier cancelled)
                throws Exception {
            runs.incrementAndGet();
            entered.countDown();
            try {
                while (release.getCount() > 0) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException();
                    Thread.sleep(2);
                }
            } catch (InterruptedException told) {
                sawCancel = true;
                throw told;
            }
            return LocalPlaylist.run(app, description, count, familiar, avoidExplicit, cancelled);
        }
    }

    @Test
    public void cancellingStopsTheReadAndLeavesNothingToSave() throws Exception {
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Radiohead"));
        Held held = new Held();
        SmartPlaylistActivity.local = held;
        open();
        generate("radiohead");
        assertTrue(held.entered.await(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();
        View area = root().findViewWithTag(SmartPlaylistActivity.ACTION_TAG);
        assertNotNull(find(area, "Looking through your library…"));
        assertEquals(View.GONE, primary().getVisibility());
        click(find(area, "Cancel"));
        assertTrue(isShown("Cancelled. Nothing was saved."));
        settle(() -> held.sawCancel);
        Thread.sleep(30);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(isShown("Save playlist"));
        assertEquals("Generate", primary().getText().toString());
        assertEquals("radiohead", field(root(), DESCRIPTION).getText().toString());
        assertNull(savedSongs());
    }

    @Test
    public void cancellingIsVisibleToAReadThatNeverNoticesInterrupts() throws Exception {
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Radiohead"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch told = new CountDownLatch(1);
        SmartPlaylistActivity.local =
                (app, description, count, familiar, clean, cancelled) -> {
                    entered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (System.nanoTime() < deadline) {
                        // Host calls can swallow an interrupt, so the supplier has to say it too.
                        Thread.interrupted();
                        if (cancelled.getAsBoolean()) {
                            told.countDown();
                            throw new InterruptedException();
                        }
                        Thread.yield();
                    }
                    throw new AssertionError("Never told to stop");
                };
        open();
        generate("radiohead");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();
        click(find("Cancel"));
        assertTrue(told.await(4, TimeUnit.SECONDS));
    }

    @Test
    public void aCancelledRunCannotResurfaceInALaterOne() throws Exception {
        savePlaylist(
                "Saved",
                song("aaaaaaaaaaa", "First", "Radiohead"),
                song("bbbbbbbbbbb", "Other", "Abba"));
        // The first run ignores cancelling and finishes late, like a call that can't be stopped.
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        SmartPlaylistActivity.local =
                (app, description, count, familiar, clean, cancelled) -> {
                    if (runs.incrementAndGet() == 1) {
                        entered.countDown();
                        try {
                            first.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ignoredOnPurpose) {
                            // Carries on, and its answer still has to be dropped.
                        }
                    }
                    return LocalPlaylist.from(
                            library(description.equals("abba") ? "Other" : "First"),
                            description,
                            count,
                            familiar,
                            clean,
                            () -> false,
                            null);
                };
        open();
        generate("radiohead");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();
        click(find("Cancel"));
        first.countDown();
        describe("abba");
        generate("abba");
        settleReview();
        assertTrue(isShown("Other · Abba"));
        assertFalse("The cancelled answer never appears", isShown("First · Radiohead"));
    }

    private static LocalPlaylistLibrary.Library library(String title) throws JSONException {
        LocalPlaylistLibrary.Library library = new LocalPlaylistLibrary.Library();
        library.storesReadable = true;
        boolean first = title.equals("First");
        library.entries.add(
                new LocalPlaylistEngine.Entry(
                        song(
                                first ? "aaaaaaaaaaa" : "bbbbbbbbbbb",
                                title,
                                first ? "Radiohead" : "Abba"),
                        "stream:" + title,
                        title,
                        first ? "Radiohead" : "Abba",
                        ""));
        return library;
    }

    @Test
    public void leavingThePageStopsTheRead() throws Exception {
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Radiohead"));
        Held held = new Held();
        SmartPlaylistActivity.local = held;
        open();
        generate("radiohead");
        assertTrue(held.entered.await(5, TimeUnit.SECONDS));
        controller.pause().stop().destroy();
        controller = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (!held.sawCancel && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertTrue(held.sawCancel);
        assertEquals(1, ((List<?>) LocalPlaylistStore._playlists.getValue()).size());
    }

    @Test
    public void aRunningReadSurvivesRotationAndFinishesOnce() throws Exception {
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Radiohead"));
        Held held = new Held();
        SmartPlaylistActivity.local = held;
        open();
        generate("radiohead");
        assertTrue(held.entered.await(5, TimeUnit.SECONDS));
        controller.recreate();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(isShown("Looking through your library…"));
        assertEquals(View.GONE, primary().getVisibility());
        assertFalse("Rotation isn't a cancel", held.sawCancel);
        held.release.countDown();
        settleReview();
        assertTrue(isShown("First · Radiohead"));
        assertEquals(1, held.runs.get());
    }

    @Test
    public void reviewChoicesSurviveRotationWithoutAnotherRead() throws Exception {
        savePlaylist(
                "Saved",
                song("aaaaaaaaaaa", "First", "Radiohead"),
                song("bbbbbbbbbbb", "Second", "Radiohead"));
        AtomicInteger runs = new AtomicInteger();
        SmartPlaylistActivity.local =
                (app, description, count, familiar, clean, cancelled) -> {
                    runs.incrementAndGet();
                    return LocalPlaylist.run(app, description, count, familiar, clean, cancelled);
                };
        open();
        generate("radiohead");
        settleReview();
        click(row("First · Radiohead"));
        controller.recreate();
        settle(() -> isShown("1 selected"));
        assertTrue(isShown("Removed · tap to add back"));
        assertEquals(1, runs.get());
        click(find("Start over"));
        assertFalse(isShown("Save playlist"));
        assertEquals("Generate", primary().getText().toString());
    }

    @Test
    public void aGeneratorFailureLeavesARetryAndNothingSaved() throws Exception {
        SmartPlaylistActivity.local =
                (app, description, count, familiar, clean, cancelled) -> {
                    throw new IllegalStateException("private detail");
                };
        open();
        generate("rock");
        settle(() -> isShown("Could not read your library. Try again."));
        assertFalse(isShown("private detail"));
        assertTrue(primary().isEnabled());
        assertNull(savedSongs());
    }

    @Test
    public void aFailedSaveKeepsTheReviewAndItsChoices() throws Exception {
        savePlaylist(
                "Saved",
                song("aaaaaaaaaaa", "First", "Radiohead"),
                song("bbbbbbbbbbb", "Second", "Radiohead"));
        open();
        generate("radiohead");
        settleReview();
        click(row("First · Radiohead"));
        field(root(), "Playlist name").setText("x".repeat(301));
        click(primary());
        assertNotNull("A bad name is refused here", field(root(), "Playlist name").getError());
        field(root(), "Playlist name").setText("Mine");

        @SuppressWarnings("unchecked")
        List<Object> before = (List<Object>) LocalPlaylistStore._playlists.getValue();
        LocalPlaylistStore._playlists.setValue(
                new ArrayList<Object>(before) {
                    @Override
                    public Object[] toArray() {
                        throw new IllegalStateException("The store refused");
                    }
                });
        click(primary());
        settle(() -> isShown("Could not save this playlist."));
        assertTrue("Still in review", isShown("1 selected"));
        assertTrue("With the choice intact", isShown("Removed · tap to add back"));

        LocalPlaylistStore._playlists.setValue(before);
        click(primary());
        settle(() -> controller.get().isFinishing());
        List<Object> saved = savedSongs();
        assertEquals(1, saved.size());
        assertEquals("Second", MixedSongCodec.text(saved.get(0), "title"));
    }

    // ── Choosing a mode ─────────────────────────────────────────────────────

    @Test
    public void choosingAModeContactsNothingAndPersistsOnlyThatChoice() throws Exception {
        configure();
        open();
        assertNull(storedMode());
        click(row("Preferences"));
        assertNotNull("The choice lives in preferences", find("External service"));
        assertNull(find(root(), "External text service"));

        click(find("External service"));
        assertEquals("service", storedMode());
        settle(() -> isShown("service.example · model-a"));
        assertTrue(isShown("External text service"));
        assertFalse(isShown("Your library"));
        assertNothingLeftThePage();

        click(find("This device"));
        assertEquals("local", storedMode());
        assertNull(find(root(), "External text service"));
        assertTrue(isShown("Your library"));
        assertEquals("Generate", primary().getText().toString());
        assertNothingLeftThePage();
    }

    @Test
    public void theChosenModeSurvivesRotationAndComingBack() throws Exception {
        open();
        click(row("Preferences"));
        click(find("External service"));
        settle(() -> isShown("Set up service"));
        controller.recreate();
        settle(() -> isShown("Set up service"));
        assertTrue(isShown("External text service"));
        controller.pause().stop().destroy();
        open();
        settle(() -> isShown("Set up service"));
        assertNothingLeftThePage();
    }

    @Test
    public void localModeNeverUsesASavedService() throws Exception {
        configure();
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Radiohead"));
        reply = wrap("Zulu");
        open();
        generate("radiohead");
        settleReview();
        assertTrue(isShown("First · Radiohead"));
        assertNothingLeftThePage();
    }

    @Test
    public void serviceModeChosenOnThePageGoesThroughExternalMatching() throws Exception {
        configure();
        reply = wrap("Zulu");
        AtomicInteger localRuns = new AtomicInteger();
        SmartPlaylistActivity.local =
                (app, description, count, familiar, clean, cancelled) -> {
                    localRuns.incrementAndGet();
                    return LocalPlaylist.run(app, description, count, familiar, clean, cancelled);
                };
        open();
        click(row("Preferences"));
        click(find("External service"));
        settle(() -> isShown("service.example · model-a"));
        assertEquals("Choosing it sends nothing", 0, requests.get());
        click(row("Preferences"));
        generate("late");
        settle(() -> isShown("1 matched · 0 to review"));
        assertEquals(1, requests.get());
        assertEquals(1, searches.get());
        assertEquals(0, localRuns.get());
        assertTrue(isShown("Review matches"));
    }

    @Test
    public void switchingAwayWhileTheServiceLoadsDropsThatRead() throws Exception {
        configure();
        CountDownLatch gate = new CountDownLatch(1);
        SmartPlaylistStore.Secrets real = SmartPlaylistActivity.secrets;
        SmartPlaylistActivity.secrets =
                new SmartPlaylistStore.Secrets() {
                    @Override
                    public String get(String name) throws Exception {
                        gate.await(5, TimeUnit.SECONDS);
                        return real.get(name);
                    }

                    @Override
                    public void put(String name, String value) throws Exception {
                        real.put(name, value);
                    }
                };
        open();
        click(row("Preferences"));
        click(find("External service"));
        click(find("This device"));
        gate.countDown();
        Thread.sleep(30);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("The late read can't bring it back", isShown("service.example"));
        assertEquals(
                "Not even into the folded fields",
                "",
                field(root(), "Service URL (HTTPS)").getText().toString());
        assertEquals("Generate", primary().getText().toString());
        describe("rock");
        assertTrue(primary().isEnabled());
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    public void theLocalFirstViewportIsJustTheTask() throws Exception {
        open();
        LinearLayout page = laidOut();
        ScrollView scroll = page.findViewWithTag(SmartPlaylistActivity.SCROLL_TAG);
        assertEquals(1, visibleFields(root()));
        assertTrue(
                "Everything fits without scrolling",
                scroll.getChildAt(0).getMeasuredHeight() <= scroll.getHeight());
        int[] description = new int[2], library = new int[2], preferences = new int[2];
        field(root(), DESCRIPTION).getLocationInWindow(description);
        row("Your library").getLocationInWindow(library);
        row("Preferences").getLocationInWindow(preferences);
        assertTrue(description[1] < library[1] && library[1] < preferences[1]);
        assertEquals(page.getHeight(), ((View) primary().getParent()).getBottom());
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp")
    public void largeTextKeepsLocalStatesUsable() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        savePlaylist(
                "Chill evenings",
                song(
                        "aaaaaaaaaaa",
                        "A very long song title that needs to wrap on a phone",
                        "Band"));
        savePlaylist("Other", song("bbbbbbbbbbb", "Mystery", "Art"));
        open();
        settle(() -> isShown("2 saved songs"));
        assertNothingOverflows(laidOut());
        click(row("Preferences"));
        assertNothingOverflows(laidOut());
        click(row("Preferences"));
        generate("chill");
        settleReview();
        LinearLayout page = laidOut();
        assertNothingOverflows(page);
        assertEquals(
                page.getHeight(),
                page.findViewWithTag(SmartPlaylistActivity.ACTION_TAG).getBottom());
        click(find("Review 1 song without tags"));
        assertNothingOverflows(laidOut());
    }

    @Test
    public void localControlsMeetTheMinimumTarget() throws Exception {
        savePlaylist("Chill evenings", song("aaaaaaaaaaa", "Evening", "Band"));
        savePlaylist("Other", song("bbbbbbbbbbb", "Mystery", "Art"));
        open();
        float density = controller.get().getResources().getDisplayMetrics().density;
        click(row("Preferences"));
        assertTrue(find("This device").getMinHeight() >= 48 * density);
        assertTrue(find("External service").getMinHeight() >= 48 * density);
        click(row("Preferences"));
        generate("chill");
        settleReview();
        assertTrue(find("Review 1 song without tags").getMinHeight() >= 48 * density);
        assertTrue(find("Save playlist").getMinHeight() >= 48 * density);
        assertTrue(row("Evening · Band").getMinimumHeight() >= 48 * density);
    }

    @Test
    public void bothThemesPaintTheLocalPage() throws Exception {
        for (String theme : new String[] {"LIGHT", "DARK"}) {
            com.music.bitchord.data.settings.AppSettings.themeMode.setValue(theme);
            open();
            boolean light = theme.equals("LIGHT");
            View page = root().findViewWithTag(SmartPlaylistActivity.ROOT_TAG);
            assertEquals(
                    light ? android.graphics.Color.WHITE : android.graphics.Color.BLACK,
                    ((android.graphics.drawable.ColorDrawable) page.getBackground()).getColor());
            click(row("Preferences"));
            TextView chosen = find("This device"), other = find("External service");
            assertTrue(chosen.isSelected());
            assertFalse(other.isSelected());
            assertEquals(
                    light ? android.graphics.Color.WHITE : android.graphics.Color.BLACK,
                    chosen.getCurrentTextColor());
            controller.pause().stop().destroy();
            controller = null;
        }
    }

    @Test
    public void localModeWorksWhenTheKeyStoreDoesNot() throws Exception {
        SmartPlaylistActivity.secrets =
                new SmartPlaylistStore.Secrets() {
                    @Override
                    public String get(String name) throws Exception {
                        throw new java.security.KeyStoreException("Unavailable");
                    }

                    @Override
                    public void put(String name, String value) throws Exception {
                        throw new java.security.KeyStoreException("Unavailable");
                    }
                };
        savePlaylist("Saved", song("aaaaaaaaaaa", "First", "Radiohead"));
        open();
        generate("radiohead");
        settleReview();
        assertTrue("Local mode needs no key store", isShown("First · Radiohead"));
    }
}
