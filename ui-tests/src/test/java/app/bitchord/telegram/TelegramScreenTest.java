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
    public void start() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS");
        TelegramBridge.instance = null;
        controller = Robolectric.buildActivity(TelegramActivity.class).setup();
        activity = controller.get();
        activity.preparation.get(10, java.util.concurrent.TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        activity.pageWork.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
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
        return slider(view, "Telegram audio cache target");
    }

    /** The slider with this accessibility label. */
    SeekBar slider(View view, String label) {
        if (view instanceof SeekBar && label.contentEquals(view.getContentDescription()))
            return (SeekBar) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SeekBar found = slider(group.getChildAt(i), label);
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
        for (int i = 0; i < 8; i++) {
            // Dialog button handlers post the action that enqueues the background job.
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            activity.bridge.work.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(550));
            if (!activity.busy) return;
        }
        assertFalse("Telegram action did not settle", activity.busy);
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
    public void readyOverviewKeepsApplicationDetailsBehindAnExplicitControl() throws Exception {
        signedIn();
        View setup = activity.body.findViewWithTag("telegram-application-settings");
        assertNotNull(setup);
        assertNull(text(activity.body, "App API ID"));
        assertNotNull(text(activity.body, "Connected to Telegram"));
        assertTrue(
                activity.body.findViewWithTag("telegram-row:Music chats").getMinimumHeight() >= 48);
        setup.performClick();
        assertNotNull(input("App API ID"));
        assertTrue(
                input("App API hash (secret)").getTransformationMethod()
                        instanceof PasswordTransformationMethod);
    }

    @Test
    public void expandedSectionsAndTabSurviveActivityRecreation() throws Exception {
        signedIn();
        activity.advanced = true;
        activity.screen = "";
        activity.render();
        activity.body.findViewWithTag("telegram-section:Storage").performClick();
        long cache = activity.bridge.cacheLimitBytes();
        controller.recreate();
        activity = controller.get();
        activity.preparation.get(10, java.util.concurrent.TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(activity.advanced);
        assertEquals(
                View.VISIBLE,
                activity.body.findViewWithTag("telegram-section-content:Storage").getVisibility());
        assertEquals(cache, activity.bridge.cacheLimitBytes());
    }

    @Test
    public void advancedSectionsExpandWithoutChangingTheirSavedSettings() throws Exception {
        signedIn();
        long cache = activity.bridge.cacheLimitBytes();
        boolean importing = activity.bridge.importOption("import");
        activity.advanced = true;
        activity.screen = "";
        activity.render();
        View storage = activity.body.findViewWithTag("telegram-section-content:Storage");
        assertEquals(View.GONE, storage.getVisibility());
        View header = activity.body.findViewWithTag("telegram-section:Storage");
        header.performClick();
        assertEquals(View.VISIBLE, storage.getVisibility());
        assertEquals(cache, activity.bridge.cacheLimitBytes());
        assertEquals(importing, activity.bridge.importOption("import"));
        assertEquals("android.widget.Button", header.createAccessibilityNodeInfo().getClassName());
        header.performClick();
        assertEquals(View.GONE, storage.getVisibility());
        assertEquals(cache, activity.bridge.cacheLimitBytes());
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
        View button = activity.body.findViewWithTag("telegram-back");
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

    private void waitingForCode(int seconds, boolean next) {
        activity.editCredentials = false;
        activity.bridge.saveCredentials("12345", "0123456789abcdef0123456789abcdef");
        activity.bridge.auth =
                new org.drinkless.tdlib.TdApi.AuthorizationStateWaitCode(
                        new org.drinkless.tdlib.TdApi.AuthenticationCodeInfo(
                                "+353000000000",
                                new org.drinkless.tdlib.TdApi.AuthenticationCodeTypeTelegramMessage(
                                        5),
                                next
                                        ? new org.drinkless.tdlib.TdApi.AuthenticationCodeTypeSms(5)
                                        : null,
                                seconds));
        activity.bridge.codeResendDeadline =
                android.os.SystemClock.elapsedRealtime() + seconds * 1000L;
        activity.screen = "";
        activity.render();
    }

    @Test
    public void canCorrectThePhoneNumberWhileWaitingForCode() throws Exception {
        waitingForCode(30, true);
        click("Change phone number");
        assertEquals("+353000000000", input("Phone number").getText().toString());
        input("Phone number").setText("+44 7700 900123");
        click("Send code to this number");
        idle();
        org.drinkless.tdlib.TdApi.AuthorizationStateWaitCode state =
                (org.drinkless.tdlib.TdApi.AuthorizationStateWaitCode) activity.bridge.auth;
        assertEquals("+447700900123", state.codeInfo.phoneNumber);
        assertNotNull(input("Verification code"));
        assertFalse(activity.editingPhone);
    }

    @Test
    public void cancellingPhoneEditKeepsThePendingLogin() {
        waitingForCode(30, true);
        Object pending = activity.bridge.auth;
        click("Change phone number");
        click("Cancel");
        assertSame(pending, activity.bridge.auth);
        assertNotNull(input("Verification code"));
    }

    @Test
    public void resendHonorsCooldownWithoutClearingTypedCode() throws Exception {
        waitingForCode(3, true);
        EditText code = input("Verification code");
        code.setText("12345");
        assertFalse(text(activity.body, "Request a new code").isEnabled());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertTrue(text(activity.body, "Request a new code").isEnabled());
        assertSame(code, input("Verification code"));
        assertEquals("12345", code.getText().toString());
        click("Request a new code");
        idle();
        assertEquals(1, activity.bridge.resendRequests);
    }

    @Test
    public void resendUnavailableStillAllowsPhoneCorrection() {
        waitingForCode(0, false);
        assertFalse(text(activity.body, "Request a new code").isEnabled());
        assertTrue(text(activity.body, "Change phone number").isEnabled());
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
        assertNull("Storage is on the Advanced tab", slider(activity.body));
        openTab("Advanced");
        screenshot("advanced-settings");
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

    @org.junit.Test
    public void coordinatedStreamingStartsOffAndExposesItsBufferThreshold() throws Exception {
        signedIn();
        openTab("Advanced");
        assertFalse(activity.bridge.streamV2Enabled());
        assertEquals(15, activity.bridge.streamLowWaterSeconds());
        clickRow("Experimental Telegram streaming");
        idle();
        assertTrue(activity.bridge.streamV2Enabled());
        clickRow("Streaming buffer threshold");
        android.app.AlertDialog dialog =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        dialog.getListView().performItemClick(null, 3, 3);
        idle();
        assertEquals(20, activity.bridge.streamLowWaterSeconds());
        clickRow("Experimental Telegram streaming");
        idle();
        assertFalse(activity.bridge.streamV2Enabled());
    }

    /** Opens the Basic or Advanced tab. */
    void openTab(String name) {
        click(name);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(name + " tab open", text(activity.body, name).isSelected());
        if (name.equals("Advanced")) {
            for (String title :
                    new String[] {
                        "Main channel",
                        "Track details",
                        "Playback",
                        "Storage",
                        "Automatic backup",
                        "Backup and sharing"
                    }) {
                View header = activity.body.findViewWithTag("telegram-section:" + title);
                if (header != null && !header.isSelected()) header.performClick();
            }
        }
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
        View row = activity.body.findViewWithTag("telegram-row:" + title);
        if (row != null) {
            assertTrue(row.performClick());
            return;
        }
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
    public void foldersBackupSharingAndAlbums() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();

        // Music chats: where to pick from comes first, folders and archived chats included.
        clickRow("Music chats");
        android.app.AlertDialog lists =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        java.util.List<String> names = new java.util.ArrayList<>();
        android.widget.ListView view = lists.getListView();
        for (int i = 0; i < view.getAdapter().getCount(); i++)
            names.add(view.getAdapter().getItem(i).toString());
        assertEquals(java.util.Arrays.asList("All chats", "Music", "Archived chats"), names);
        lists.dismiss();

        // Indexing only on Wi-Fi.
        clickRow("Index only on Wi-Fi");
        idle();
        assertTrue(bridge.indexOption("wifi"));

        // A backup goes to a file the user picks, with the index unless unticked.
        openTab("Advanced");
        clickRow("Back up");
        android.app.AlertDialog ask =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        ask.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        android.content.Intent save =
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, save.getAction());
        assertTrue(
                save.getStringExtra(android.content.Intent.EXTRA_TITLE)
                        .startsWith("bitchord-telegram-backup-"));
        android.net.Uri file = android.net.Uri.parse("content://test/backup.gz");
        java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(file, written);
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(file, readBack(written));
        Shadows.shadowOf(activity)
                .receiveResult(
                        save,
                        android.app.Activity.RESULT_OK,
                        new android.content.Intent().setData(file));
        idle();
        assertEquals(java.util.Arrays.asList("backup true"), bridge.backups);
        assertTrue("A whole file, not 0 KB", written.size() > 20);
        IndexTransfer.check(new java.io.ByteArrayInputStream(written.toByteArray()));
        assertEquals("Backup saved.", bridge.status);

        // Restoring reads the file picked.
        clickRow("Restore");
        android.content.Intent open =
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, open.getAction());
        android.net.Uri picked = android.net.Uri.parse("content://test/picked.gz");
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(picked, new java.io.ByteArrayInputStream(new byte[1]));
        Shadows.shadowOf(activity)
                .receiveResult(
                        open,
                        android.app.Activity.RESULT_OK,
                        new android.content.Intent().setData(picked));
        idle();
        assertEquals("restore", bridge.backups.get(1));

        // Sharing a chat's index: posted after asking.
        bridge.selected.add(10L);
        clickRow("Share a chat’s index");
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(0);
        idle();
        android.app.AlertDialog where =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Post in Saved Messages", where.getListView().getAdapter().getItem(0));
        Shadows.shadowOf(where).clickOnItem(0);
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        idle();
        assertTrue(bridge.backups.get(2).startsWith("share 10 to 1"));

        // A chat's albums, saved as a playlist.
        openTab("Basic");
        clickRow("Playlists and downloads");
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(0);
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(2);
        idle();
        android.app.AlertDialog albums =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Night Sessions · 2 songs", albums.getListView().getAdapter().getItem(0));
        Shadows.shadowOf(albums).clickOnItem(0);
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(0);
        idle();
        assertTrue(bridge.collections.contains("album playlist Night Sessions"));
    }

    /** What a document provider hands back after a save: the bytes that were written. */
    static java.io.InputStream readBack(java.io.ByteArrayOutputStream written) {
        return new java.io.InputStream() {
            java.io.InputStream in;

            java.io.InputStream open() {
                if (in == null) in = new java.io.ByteArrayInputStream(written.toByteArray());
                return in;
            }

            @Override
            public int read() throws java.io.IOException {
                return open().read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                return open().read(b, off, len);
            }
        };
    }

    @Test
    public void aFailedOrEmptyBackupNeverSignsOutOrClaimsSuccess() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();
        for (int attempt = 0; attempt < 2; attempt++) {
            click("Sign out");
            idle();
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.content.Intent save =
                    Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            android.net.Uri file = android.net.Uri.parse("content://test/bad" + attempt + ".gz");
            if (attempt == 0)
                // The disk fills up part-way.
                Shadows.shadowOf(activity.getContentResolver())
                        .registerOutputStream(
                                file,
                                new java.io.OutputStream() {
                                    @Override
                                    public void write(int b) throws java.io.IOException {
                                        throw new java.io.IOException("Disk full");
                                    }
                                });
            else {
                // The provider takes the bytes and keeps nothing.
                Shadows.shadowOf(activity.getContentResolver())
                        .registerOutputStream(file, new java.io.ByteArrayOutputStream());
                Shadows.shadowOf(activity.getContentResolver())
                        .registerInputStream(file, new java.io.ByteArrayInputStream(new byte[0]));
            }
            Shadows.shadowOf(activity)
                    .receiveResult(
                            save,
                            android.app.Activity.RESULT_OK,
                            new android.content.Intent().setData(file));
            idle();
            assertTrue("Still signed in", bridge.ready());
            assertNull("No backup remembered", bridge.lastBackup());
            assertFalse(bridge.backups.contains("logout"));
            assertNotEquals("Backup saved.", bridge.status);
            assertEquals(IndexTransfer.CUT_SHORT, bridge.error);
        }
    }

    @Test
    public void aPrivateChannelBacksUpAndRestoresOnlyAfterAsking() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();
        openTab("Advanced");
        clickRow("Back up");
        android.app.AlertDialog ask =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        ask.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
        idle();
        assertEquals(
                "No private channel",
                Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                        .getTitle()
                        .toString());
        assertTrue("Nothing posted", bridge.backups.isEmpty());

        org.drinkless.tdlib.TdApi.Chat mine = new org.drinkless.tdlib.TdApi.Chat();
        mine.id = 77;
        mine.title = "My backups";
        bridge.owned.add(mine);
        clickRow("Back up");
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                .performClick();
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(0);
        idle();
        assertTrue("Choosing the channel posts nothing yet", bridge.backups.isEmpty());
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        idle();
        assertEquals(java.util.Arrays.asList("backup true to 77"), bridge.backups);

        clickRow("Restore from a private channel");
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(0);
        idle();
        assertEquals(1, bridge.backups.size());
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        idle();
        assertEquals("restore from 77", bridge.backups.get(1));
    }

    @Test
    public void automaticBackupCard() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();
        openTab("Advanced");
        AutoBackup backup = bridge.autoBackup();
        assertNotNull(
                "The card is on the Advanced tab",
                activity.body.findViewWithTag("telegram-section:Automatic backup"));
        assertEquals("Every 24 hours by default", 24, backup.intervalHours());
        assertNotNull(
                "The next backup is not planned while off", text(activity.body, "Back up now"));
        assertNull("No credentials on the card", text(activity.body, "App API hash"));

        // Pick the channel: remembered by the backup, apart from the main channel.
        org.drinkless.tdlib.TdApi.Chat mine = new org.drinkless.tdlib.TdApi.Chat();
        mine.id = 77;
        mine.title = "My backups";
        bridge.owned.add(mine);
        clickRow("Backup channel");
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(0);
        idle();
        assertEquals("Choosing alone changes nothing", 0, backup.target());
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        idle();
        assertEquals(77, backup.target());
        assertEquals("My backups", backup.targetTitle());
        assertEquals("The main channel is separate", 0, bridge.mainChannel.main());

        // A custom number of hours: a wrong entry keeps the dialog open and says why.
        clickRow("How often");
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(5);
        idle();
        android.app.AlertDialog custom =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        EditText hours = (EditText) findFirst(custom.getWindow().getDecorView(), EditText.class);
        hours.setText("0");
        custom.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue("Zero is refused", custom.isShowing() && hours.getError() != null);
        hours.setText("36");
        custom.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(36, backup.intervalHours());

        // A preset.
        clickRow("How often");
        idle();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(2);
        idle();
        assertEquals(6, backup.intervalHours());

        // Turning it on plans a persisted job that needs only a connection.
        backup.setEnabled(true);
        AutoBackupJob.schedule(RuntimeEnvironment.getApplication(), backup, true);
        android.app.job.JobScheduler jobs =
                (android.app.job.JobScheduler)
                        RuntimeEnvironment.getApplication()
                                .getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE);
        android.app.job.JobInfo job = jobs.getPendingJob(AutoBackupJob.JOB_ID);
        assertNotNull("A job is planned", job);
        assertTrue("and survives a reboot", job.isPersisted());
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        // Planning again at start-up keeps the planned time.
        long latency = job.getMinLatencyMillis();
        AutoBackupJob.schedule(RuntimeEnvironment.getApplication(), backup, false);
        assertEquals(latency, jobs.getPendingJob(AutoBackupJob.JOB_ID).getMinLatencyMillis());
        // Turning it off cancels it.
        backup.setEnabled(false);
        AutoBackupJob.schedule(RuntimeEnvironment.getApplication(), backup, true);
        assertNull(jobs.getPendingJob(AutoBackupJob.JOB_ID));

        clickRow("Back up now");
        idle();
        assertTrue(bridge.backups.contains("backup now"));
    }

    static View findFirst(View root, Class<? extends View> type) {
        if (type.isInstance(root)) return root;
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                View found = findFirst(((ViewGroup) root).getChildAt(i), type);
                if (found != null) return found;
            }
        return null;
    }

    @Test
    public void signOutWithBackupAndComeBack() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();
        assertNull("Nothing to restore yet", text(activity.body, "WELCOME BACK"));
        click("Sign out");
        idle();
        android.app.AlertDialog ask =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(
                Shadows.shadowOf(ask)
                        .getMessage()
                        .toString()
                        .contains("without indexing 223,776 files again"));
        // Cancelling the file picker signs nobody out.
        ask.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        android.content.Intent save =
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, save.getAction());
        assertTrue(
                (save.getFlags() & android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        != 0);
        Shadows.shadowOf(activity).receiveResult(save, android.app.Activity.RESULT_CANCELED, null);
        idle();
        assertTrue("Still signed in", bridge.ready());
        assertTrue(bridge.backups.isEmpty());

        click("Sign out");
        idle();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        save = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        android.net.Uri file = android.net.Uri.parse("content://test/signout.gz");
        java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        Shadows.shadowOf(activity.getContentResolver()).registerOutputStream(file, written);
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(file, readBack(written));
        Shadows.shadowOf(activity)
                .receiveResult(
                        save,
                        android.app.Activity.RESULT_OK,
                        new android.content.Intent().setData(file));
        idle();
        assertEquals(
                "Backed up with the index, then signed out",
                java.util.Arrays.asList("backup true", "logout"),
                bridge.backups);
        assertTrue(written.size() > 20);
        assertFalse(bridge.ready());
        assertEquals("content://test/signout.gz", bridge.lastBackup().getString("uri"));

        // Signed in again: one tap brings it all back.
        signedIn();
        assertNotNull(text(activity.body, "WELCOME BACK"));
        Shadows.shadowOf(activity.getContentResolver())
                .registerInputStream(file, new java.io.ByteArrayInputStream(new byte[1]));
        clickRow("Restore what you had before signing out");
        idle();
        assertEquals("restore", bridge.backups.get(2));
        assertNull("Offered once", bridge.lastBackup());
        assertNull(text(activity.body, "WELCOME BACK"));

        // Or start fresh; signing out without a backup offers nothing later.
        bridge.rememberBackup("content://test/old.gz");
        activity.screen = "";
        activity.render();
        clickRow("Start fresh");
        idle();
        assertNull(bridge.lastBackup());
        click("Sign out");
        idle();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                .performClick();
        idle();
        assertFalse(bridge.ready());
        assertEquals("logout", bridge.backups.get(bridge.backups.size() - 1));
    }

    @Test
    public void fakeLosslessChecksAndNamingBySound() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();
        openTab("Advanced");
        assertTrue("Songs that play are checked", bridge.checkOption("checkLossless"));
        assertNotNull(
                text(activity.body, "FLAC and WAV files that play are checked for a lossy source"));
        assertNotNull(text(activity.body, "Checked 12 files · 2 not really lossless"));
        assertFalse("The background starts off", bridge.checkOption("checkInBackground"));
        clickRow("Check in the background");
        idle();
        assertTrue(bridge.checkOption("checkInBackground"));
        assertNotNull(
                text(activity.body, "Every lossless file in your music chats, up to 1 GB a day"));
        clickRow("Check lossless files");
        idle();
        assertFalse(bridge.checkOption("checkLossless"));
        assertNotNull(text(activity.body, "Files are taken at their word"));

        openTab("Basic");
        assertNotNull(text(activity.body, "Needed to name files by sound"));
        clickRow("Name untagged files by sound");
        android.app.AlertDialog warning =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(Shadows.shadowOf(warning).getMessage().toString().contains("No audio is sent"));
        warning.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse("Not without a key", bridge.checkOption("recognizeUntagged"));
        assertEquals("Enter your AcoustID application key first.", bridge.error);

        clickRow("AcoustID key");
        android.app.AlertDialog keyDialog =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        EditText key = dialogInput(keyDialog.getWindow().getDecorView(), "");
        assertNotNull(key);
        key.setText("not a key");
        keyDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("", bridge.acoustIdKey());
        assertEquals("That doesn’t look like an AcoustID application key.", bridge.error);
        clickRow("AcoustID key");
        keyDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        dialogInput(keyDialog.getWindow().getDecorView(), "").setText("Key12345");
        keyDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("Key12345", bridge.acoustIdKey());
        assertNotNull(text(activity.body, "Saved on this phone only"));
        clickRow("Name untagged files by sound");
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        idle();
        assertTrue(bridge.checkOption("recognizeUntagged"));
        assertNotNull(text(activity.body, "Files without tags are looked up in AcoustID"));
    }

    @Test
    public void trackDetailsToggles() throws Exception {
        TelegramBridge bridge = activity.bridge;
        signedIn();
        assertNull(
                "Track details are on the Advanced tab",
                activity.body.findViewWithTag("telegram-section:Track details"));
        openTab("Advanced");
        assertNotNull(activity.body.findViewWithTag("telegram-section:Track details"));
        View root = activity.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 393, 852);
        activity.scroll.scrollTo(
                0,
                ((View) activity.body.findViewWithTag("telegram-section:Track details").getParent())
                                .getTop()
                        - activity.style.dp(8));
        screenshot("track-details");

        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        activity.scroll.scrollTo(
                0,
                ((View) activity.body.findViewWithTag("telegram-section:Track details").getParent())
                        .getTop());
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
        openTab("Basic");
        assertNotNull(
                "The index says how far it has got",
                text(activity.body, "12 files indexed from 1 chat"));
        openTab("Advanced");
        for (String option : new String[] {"import", "sync", "losslessOnly", "skipDuplicates"})
            assertFalse(option + " starts off", bridge.importOption(option));
        assertNotNull(text(activity.body, "Up to 100 files an hour"));
        clickRow("Copy into main channel");
        android.app.AlertDialog copyWarning =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(
                Shadows.shadowOf(copyWarning)
                        .getMessage()
                        .toString()
                        .contains("limited for a while"));
        copyWarning.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse("Not without a main channel", bridge.importOption("import"));
        assertEquals("Choose your main channel first.", bridge.error);
        org.drinkless.tdlib.TdApi.Chat library = new org.drinkless.tdlib.TdApi.Chat();
        library.id = -500;
        library.title = "My Library";
        bridge.setMainChannel(library);
        clickRow("Lossless only");
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        idle();
        assertTrue(bridge.importOption("losslessOnly"));
        clickRow("Copy into main channel");
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        idle();
        assertTrue("On once there's a main channel", bridge.importOption("import"));
        assertFalse("Other options stay off", bridge.importOption("sync"));
        assertNull("No Open BitChord button", text(activity.body, "Open BitChord"));
        assertTrue("Duplicates are hidden unless turned off", bridge.hideDuplicates());
        clickRow("Hide duplicate songs");
        idle();
        assertFalse(bridge.hideDuplicates());
        assertNotNull(text(activity.body, "Every copy of a song shows"));
        assertFalse("Sound check starts off", bridge.soundCheck());
        clickRow("Check matches by sound");
        android.app.AlertDialog warning =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(
                Shadows.shadowOf(warning)
                        .getMessage()
                        .toString()
                        .contains("up to the first 45 seconds"));
        warning.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertFalse("Cancel leaves it off", bridge.soundCheck());
        clickRow("Check matches by sound");
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        idle();
        assertTrue("Turn on turns it on", bridge.soundCheck());
        assertNotNull(
                text(
                        activity.body,
                        "Telegram files with another length are compared with the song"));
        openTab("Basic");
        clickRow("Saved index");
        idle();
        assertFalse(bridge.searchIndex());
        assertNotNull(text(activity.body, "Only Telegram’s own search is used"));
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
