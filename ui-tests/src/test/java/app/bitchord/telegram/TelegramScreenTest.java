package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.graphics.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.io.*;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TelegramScreenTest {
    ActivityController<TelegramActivity> controller;
    TelegramActivity activity;

    @Before
    public void start() {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS");
        TelegramBridge.instance = null;
        controller = Robolectric.buildActivity(TelegramActivity.class).setup();
        activity = controller.get();
        assertTrue(
                "Telegram screen blocks capture",
                (activity.getWindow().getAttributes().flags
                                & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
    }

    @After
    public void stop() {
        if (activity == null) return;
        activity.handler.removeCallbacksAndMessages(null);
        activity.bridge.work.shutdownNow();
        controller.pause().stop().destroy();
    }

    TextView text(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(value))
            return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    EditText input(String hint) {
        return input(activity.body, hint);
    }

    EditText input(View view, String hint) {
        if (view instanceof EditText && hint.equals(((EditText) view).getHint().toString()))
            return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText result = input(g.getChildAt(i), hint);
                if (result != null) return result;
            }
        }
        return null;
    }

    SeekBar slider(View view) {
        if (view instanceof SeekBar) return (SeekBar) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SeekBar found = slider(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    void click(String label) {
        TextView button = text(activity.body, label);
        assertNotNull(label, button);
        button.performClick();
    }

    void idle() throws Exception {
        activity.bridge.work.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        for (int i = 0; i < 100 && activity.busy; i++) {
            Thread.sleep(10);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(550));
        assertFalse(activity.busy);
    }

    void screenshot(String name) throws Exception {
        View root = activity.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 393, 852);
        Bitmap bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File out = new File("target/previews/" + name + ".png");
        out.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }

    @Test
    public void dialogsAndBackgroundProtectSecrets() throws Exception {
        input("App API ID").setText("12345");
        input("App API hash (secret)").setText("0123456789abcdef0123456789abcdef");
        click("Show hash");
        assertNull(input("App API hash (secret)").getTransformationMethod());
        controller.pause();
        assertTrue(
                input("App API hash (secret)").getTransformationMethod()
                        instanceof PasswordTransformationMethod);
        assertEquals("12345", input("App API ID").getText().toString());
        assertNotNull(text(activity.body, "Show hash"));
        controller.resume();
        assertTrue(activity.body.getFilterTouchesWhenObscured());
        assertTrue(input("App API hash (secret)").getFilterTouchesWhenObscured());
        activity.confirm("Confirm", "Sensitive dialog", "Continue", () -> {});
        android.app.AlertDialog dialog =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(
                (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
        dialog.dismiss();
    }

    @Test
    public void backArrowIsCenteredAndNavigates() throws Exception {
        View button = ((ViewGroup) activity.body.getChildAt(0)).getChildAt(0);
        assertTrue(button instanceof ImageButton);
        assertEquals("Back", button.getContentDescription());
        int size = activity.style.dp(48);
        button.measure(
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        button.layout(0, 0, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        button.draw(new Canvas(bitmap));
        int minX = size, minY = size, maxX = -1, maxY = -1;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) > 220) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        assertTrue("Back arrow is visible", maxX >= minX && maxY >= minY);
        assertEquals("Horizontal visual center", size / 2f, (minX + maxX + 1) / 2f, 0.75f);
        assertEquals("Vertical visual center", size / 2f, (minY + maxY + 1) / 2f, 0.75f);
        assertTrue(button.performClick());
        assertTrue("Back exits first-time setup", activity.isFinishing());
    }

    @Test
    public void credentialsAndLoginFlow() throws Exception {
        assertNotNull(text(activity.body, "Application setup"));
        assertTrue(
                (activity.getWindow().getAttributes().flags
                                & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
        assertTrue(
                input("App API hash (secret)").getTransformationMethod()
                        instanceof PasswordTransformationMethod);
        assertFalse(input("App API hash (secret)").isSaveEnabled());
        screenshot("application-setup");
        input("App API ID").setText("12345");
        input("App API hash (secret)").setText("bad");
        click("Save and continue");
        assertFalse(activity.bridge.error.isEmpty());
        input("App API hash (secret)").setText("0123456789abcdef0123456789abcdef");
        click("Save and continue");
        idle();
        assertNotNull(input("Phone number"));
        screenshot("phone-login");
        input("Phone number").setText("+353000000000");
        click("Continue");
        idle();
        assertNotNull(input("Verification code"));
        input("Verification code").setText("12345");
        click("Verify and continue");
        idle();
        assertNotNull(text(activity.body, "Connected to Telegram"));
        assertTrue(
                (activity.getWindow().getAttributes().flags
                                & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
        assertNotNull(text(activity.body, "MUSIC LIBRARY"));
        assertNotNull(text(activity.body, "0 selected · Live Telegram search"));
        assertNull(text(activity.body, "Index newest messages"));
        assertNull(text(activity.body, "Load older messages"));
        screenshot("connected-library");
        SeekBar cacheSlider = slider(activity.body);
        assertNotNull(cacheSlider);
        assertEquals(CachePolicy.indexOf(CachePolicy.DEFAULT_BYTES), cacheSlider.getProgress());
        cacheSlider.setProgress(7);
        assertNotNull(text(activity.body, "8 GiB"));
        click("Apply cache size");
        idle();
        assertEquals(8L * 1024 * 1024 * 1024, activity.bridge.cacheLimitBytes());
        assertEquals(7, slider(activity.body).getProgress());
        activity.scroll.scrollTo(0, ((View) slider(activity.body).getParent()).getTop());
        screenshot("cache-settings");
    }

    void signedIn() {
        activity.bridge.credentials =
                ApplicationCredentials.parse("12345", "0123456789abcdef0123456789abcdef");
        activity.bridge.auth = new org.drinkless.tdlib.TdApi.AuthorizationStateReady();
        activity.editCredentials = false;
        activity.screen = "";
        activity.render();
    }

    void clickRow(String title) {
        TextView label = text(activity.body, title);
        assertNotNull(title, label);
        assertTrue(((View) label.getParent().getParent()).performClick());
    }

    EditText dialogInput(View view, String value) {
        if (view instanceof EditText && ((EditText) view).getText().toString().equals(value))
            return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText result = dialogInput(g.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }

    static org.json.JSONObject track(String id, String title, String artist, int duration)
            throws Exception {
        return new org.json.JSONObject()
                .put("id", id)
                .put("chat", 10)
                .put("title", title)
                .put("artist", artist)
                .put("duration", duration);
    }

    @Test
    public void trackNamesSuggestAndFix() throws Exception {
        TelegramBridge bridge = activity.bridge;
        bridge.heard("One More Light", "Linkin Park", 254_000, "H1LdQntDnFY");
        bridge.heard("Heavy", "Linkin Park", 171_000, "zzzzzzzzzzz");
        bridge.names.consider(
                "One More Light",
                "Linkin Park",
                254,
                java.util.Arrays.asList(track("10_1", "Ine More Light", "Linkin Park", 263)));
        signedIn();
        assertNotNull(text(activity.body, "TRACK NAMES"));
        assertNotNull(text(activity.body, "None yet"));
        assertNotNull(
                text(
                        activity.body,
                        "Linkin Park · 4:23 · Looks like a song that played from another source"));
        View root = activity.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 393, 852);
        activity.scroll.scrollTo(
                0, text(activity.body, "TRACK NAMES").getTop() - activity.style.dp(8));
        screenshot("track-names");

        clickRow("“Ine More Light” → “One More Light”");
        android.app.AlertDialog review =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(
                (review.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
        review.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(bridge.names.suggestions().isEmpty());
        assertEquals("One More Light", bridge.names.corrections().get(0).getString("title"));
        assertEquals(
                "Only BitChord's saved copy of the renamed song is removed",
                java.util.Arrays.asList("H1LdQntDnFY"),
                bridge.forgotten);
        assertNotNull(text(activity.body, "1 track"));
        assertNull(text(activity.body, "“Ine More Light” → “One More Light”"));

        bridge.searchResults.add(track("10_1", "Ine More Light", "Linkin Park", 263));
        bridge.searchResults.add(track("10_2", "Sharp Edgez", "Linkin Park", 180));
        input("Search your music chats").setText("edgez");
        click("Fix a track name");
        idle();
        android.app.AlertDialog pick =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Choose a track", Shadows.shadowOf(pick).getTitle().toString());
        Shadows.shadowOf(pick).clickOnItem(0);
        android.app.AlertDialog edit =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        View form = edit.getWindow().getDecorView();
        EditText title = dialogInput(form, "Sharp Edgez");
        assertNotNull(title);
        assertNotNull(dialogInput(form, "Linkin Park"));
        title.setText("Sharp Edges");
        edit.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(2, bridge.names.corrections().size());
        assertEquals("Sharp Edges", bridge.searchTracks("sharp edges").get(0).getString("title"));
        assertNotNull(text(activity.body, "2 tracks"));

        clickRow("Corrected names");
        android.app.AlertDialog list =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        Shadows.shadowOf(list).clickOnItem(1);
        android.app.AlertDialog restore =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        restore.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
        idle();
        assertEquals(1, bridge.names.corrections().size());

        input("Search your music chats").setText("typing");
        bridge.names.consider(
                "Heavy",
                "Linkin Park",
                171,
                java.util.Arrays.asList(track("10_3", "Heavvy", "Linkin Park", 171)));
        assertNull(text(activity.body, "“Heavvy” → “Heavy”"));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(550));
        assertNotNull(
                "A new suggestion appears without reopening",
                text(activity.body, "“Heavvy” → “Heavy”"));
        assertEquals(
                "Typing isn't lost when the list refreshes",
                "typing",
                input("Search your music chats").getText().toString());

        clickRow("Use corrected names");
        idle();
        assertFalse(bridge.useTrackNames());
        assertNotNull(text(activity.body, "Files keep their Telegram names; corrections are kept"));
        assertEquals("Ine More Light", bridge.searchTracks("light").get(0).getString("title"));
        assertEquals(1, bridge.names.corrections().size());
        clickRow("Use corrected names");
        idle();
        assertTrue(bridge.useTrackNames());

        clickRow("Suggest fixes");
        idle();
        assertFalse(bridge.suggestTrackNames());
        assertNotNull(text(activity.body, "No background searches"));

        input("Search your music chats").setText("nothing here");
        click("Fix a track name");
        idle();
        assertEquals(
                "No tracks found",
                Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                        .getTitle()
                        .toString());
    }

    @Test
    public void trackDetailsToggles() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();
        assertNotNull(text(activity.body, "TRACK DETAILS"));
        View root = activity.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 393, 852);
        activity.scroll.scrollTo(
                0, text(activity.body, "TRACK DETAILS").getTop() - activity.style.dp(8));
        screenshot("track-details");

        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        activity.scroll.scrollTo(0, text(activity.body, "TRACK DETAILS").getTop());
        int scrolled = activity.scroll.getScrollY();
        assertTrue(scrolled > 0);
        TelegramSwitch quality =
                (TelegramSwitch)
                        ((ViewGroup)
                                        text(activity.body, "Report audio quality")
                                                .getParent()
                                                .getParent())
                                .getChildAt(1);
        assertTrue(quality.isChecked());
        clickRow("Report audio quality");
        idle();
        assertFalse(bridge.reportQuality());
        assertFalse("The switch shows the new state", quality.isChecked());
        assertEquals(
                "Flipping a switch doesn't scroll the page",
                scrolled,
                activity.scroll.getScrollY());
        activity.run(() -> {});
        idle();
        assertNotSame(
                "An action rebuilds the page",
                quality,
                ((ViewGroup) text(activity.body, "Report audio quality").getParent().getParent())
                        .getChildAt(1));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(
                "A rebuilt page keeps its scroll position", scrolled, activity.scroll.getScrollY());
        assertNotNull(text(activity.body, "BitChord guesses from the file type"));
        clickRow("Album covers");
        idle();
        assertFalse(bridge.showArtwork());
        assertNotNull(text(activity.body, "No covers are downloaded"));
        clickRow("Names from file names");
        idle();
        assertFalse(bridge.namesFromFiles());
        assertNotNull(text(activity.body, "Untagged files show their file name"));
        clickRow("Album covers");
        idle();
        assertTrue(bridge.showArtwork());
        assertFalse("Surround mixing starts off", bridge.surroundAsStereo());
        assertNotNull(text(activity.body, "Android mixes surround files itself"));
        clickRow("Play surround as stereo");
        idle();
        assertTrue(bridge.surroundAsStereo());
        assertNotNull(text(activity.body, "5.1 and 7.1 files are mixed to stereo in BitChord"));
    }

    @Test
    @Config(sdk = {27, 28, 29, 30, 31, 32, 33, 34})
    public void interveningAndroidForms() throws Exception {
        assertNotNull(input("App API ID"));
        assertNotNull(input("App API hash (secret)"));
        assertNotNull(text(activity.body, "Create your Telegram API credentials ↗"));
    }

    @Test
    @Config(sdk = 26)
    public void androidEightForm() throws Exception {
        assertNotNull(input("App API ID"));
        assertNotNull(input("App API hash (secret)"));
        click("Show hash");
        assertNull(input("App API hash (secret)").getTransformationMethod());
        click("Hide hash");
        assertNotNull(input("App API hash (secret)").getTransformationMethod());
    }

    @Test
    @Config(sdk = 36)
    public void androidSixteenForm() throws Exception {
        assertNotNull(input("App API ID"));
        assertNotNull(text(activity.body, "Save and continue"));
    }
}
