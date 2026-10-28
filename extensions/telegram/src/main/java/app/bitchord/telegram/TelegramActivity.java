package app.bitchord.telegram;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.util.*;

/** Themed application configuration, authorization and music-library screen. */
public final class TelegramActivity extends Activity {
    TelegramBridge bridge;
    TelegramStyle style;
    LinearLayout body;
    TextView status, error, hashVisibility;
    ScrollView scroll;
    final List<View> actions = new ArrayList<>();
    final List<EditText> secrets = new ArrayList<>();
    final Handler handler = new Handler(Looper.getMainLooper());
    String screen = "";
    volatile boolean busy;
    boolean editCredentials, resumed;
    long channelId;
    ChannelView channelView;
    LinearLayout namesList;
    int namesRevision;
    TextView indexLine;

    /** Keeps the Saved index line counting while the page is open. */
    final Runnable indexTicker =
            new Runnable() {
                public void run() {
                    TextView line = indexLine;
                    if (bridge != null && line != null && bridge.searchIndex())
                        bridge.work.execute(
                                () -> {
                                    String text = bridge.indexStatus();
                                    runOnUiThread(
                                            () -> {
                                                if (line == indexLine && bridge.searchIndex())
                                                    line.setText(text);
                                            });
                                });
                    handler.postDelayed(this, 2000);
                }
            };

    final Runnable refresh =
            new Runnable() {
                public void run() {
                    render();
                    handler.postDelayed(this, 500);
                }
            };

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        int theme =
                getResources()
                        .getIdentifier(
                                dark ? "TelegramNativeTheme" : "TelegramNativeThemeLight",
                                "style",
                                getPackageName());
        setTheme(
                theme != 0
                        ? theme
                        : dark
                                ? android.R.style.Theme_Material_NoActionBar
                                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        secureWindow(getWindow());
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        style = new TelegramStyle(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(style.background);
        getWindow().setNavigationBarColor(style.background);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        style.dark
                                ? 0
                                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        TelegramBridge.init(getApplicationContext());
        bridge = TelegramBridge.instance;
        editCredentials = bridge != null && !bridge.credentials().configured();
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(style.background);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setFilterTouchesWhenObscured(true);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setFocusableInTouchMode(true);
        body.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.addView(body);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    body.setPadding(
                            0,
                            insets.getSystemWindowInsetTop(),
                            0,
                            style.dp(28) + insets.getSystemWindowInsetBottom());
                    return insets;
                });
        refresh.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (channelView != null) channelView.resume();
        handler.removeCallbacks(indexTicker);
        handler.postDelayed(indexTicker, 2000);
    }

    @Override
    protected void onDestroy() {
        if (channelView != null) channelView.close();
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(indexTicker);
        if (channelView != null) channelView.pause();
        for (EditText field : secrets)
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if (hashVisibility != null) hashVisibility.setText("Show hash");
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (channelId != 0) {
            channelId = 0;
            screen = "";
            render();
        } else super.onBackPressed();
    }

    void secureWindow(Window window) {
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true);
    }

    /** The page last built, kept so a rebuild of the same page can restore its scroll. */
    String shown = "";

    void render() {
        if (bridge != null && !bridge.ready()) channelId = 0;
        String state =
                bridge == null
                        ? "error"
                        : channelId != 0
                                ? "channel:" + channelId + ":" + bridge.generation
                                : editCredentials
                                        ? "credentials"
                                        : bridge.auth == null
                                                ? "starting"
                                                : bridge.auth.getClass().getSimpleName();
        if (state.equals(screen)) {
            updateStatus();
            return;
        }
        screen = state;
        // Rebuilding the same page (after an action) keeps the reader where they were.
        final int keepY = state.equals(shown) && scroll != null ? scroll.getScrollY() : 0;
        shown = state;
        if (keepY > 0)
            scroll.getViewTreeObserver()
                    .addOnPreDrawListener(
                            new android.view.ViewTreeObserver.OnPreDrawListener() {
                                int frames;

                                @Override
                                public boolean onPreDraw() {
                                    // Wait until the new page is laid out tall enough to hold
                                    // the old position, or give up after a few frames.
                                    View page = scroll.getChildAt(0);
                                    boolean ready =
                                            page != null
                                                    && page.getHeight()
                                                            >= keepY + scroll.getHeight();
                                    if (ready || ++frames > 10) {
                                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                                        scroll.scrollTo(0, keepY);
                                    }
                                    return true;
                                }
                            });
        if (channelView != null) {
            channelView.close();
            channelView = null;
        }
        body.removeAllViews();
        namesList = null;
        actions.clear();
        secrets.clear();
        hashVisibility = null;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(style.dp(20), style.dp(16), style.dp(20), style.dp(18));
        ImageButton back =
                style.backButton(
                        () -> {
                            if (channelId != 0) {
                                channelId = 0;
                                screen = "";
                                render();
                            } else if (editCredentials
                                    && bridge != null
                                    && bridge.credentials().configured()) {
                                editCredentials = false;
                                screen = "";
                                render();
                            } else finish();
                        });
        top.addView(back, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title =
                style.text(
                        channelId != 0
                                ? "Channel View"
                                : editCredentials ? "Application setup" : "Unofficial Telegram",
                        34,
                        style.foreground,
                        style.heavy);
        title.setLetterSpacing(-0.025f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = style.dp(26);
        top.addView(title, tl);
        TextView subtitle =
                style.text(
                        "An unofficial client using the Telegram API. Your account, inside"
                                + " BitChord.",
                        14,
                        style.secondary,
                        style.regular);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = style.dp(7);
        top.addView(subtitle, sl);
        body.addView(top);
        if (bridge == null) {
            paragraph(card("Connection"), TelegramBridge.startupError);
            return;
        }
        if (channelId != 0) {
            channelView = new ChannelView(this, channelId);
            body.addView(channelView);
            if (resumed) channelView.resume();
        } else if (editCredentials) credentialsForm();
        else {
            LinearLayout app = card("Application");
            row(
                    app,
                    "App API ID",
                    bridge.credentials().configured()
                            ? Integer.toString(bridge.credentials().apiId)
                            : "Not configured",
                    "Change",
                    () -> {
                        editCredentials = true;
                        screen = "";
                        render();
                    });
            divider(app);
            row(app, "App API hash", "••••••••••••••••", null, null);
            account();
            if (bridge.ready()) library();
        }
        if (channelId == 0) about();
        LinearLayout connection = card(null);
        status = style.text("", 14, style.secondary, style.regular);
        connection.addView(status);
        error = style.text("", 14, 0xfffa2d48, style.regular);
        error.setPadding(0, style.dp(8), 0, 0);
        connection.addView(error);
        updateStatus();
        if (keepY == 0) {
            // A different page starts at the top.
            body.requestFocus();
            scroll.post(() -> scroll.scrollTo(0, 0));
        }
    }

    void credentialsForm() {
        LinearLayout app = card("Application credentials");
        paragraph(
                app,
                "Enter the App API ID and App API hash from your Telegram application. The hash"
                        + " stays masked and is stored encrypted on this device.");
        ApplicationCredentials current = bridge.credentials();
        EditText id = input(app, "App API ID", false);
        id.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (current.configured()) id.setText(Integer.toString(current.apiId));
        EditText hash = input(app, "App API hash (secret)", true);
        if (current.configured()) hash.setText(current.apiHash);
        LinearLayout toggle = new LinearLayout(this);
        toggle.setGravity(Gravity.END);
        app.addView(toggle);
        TextView show = style.text("Show hash", 13, style.foreground, style.semibold);
        show.setPadding(style.dp(10), style.dp(10), 0, style.dp(10));
        hashVisibility = show;
        show.setOnClickListener(
                v -> {
                    boolean hidden = hash.getTransformationMethod() != null;
                    hash.setTransformationMethod(
                            hidden ? null : PasswordTransformationMethod.getInstance());
                    hash.setSelection(hash.length());
                    show.setText(hidden ? "Hide hash" : "Show hash");
                });
        toggle.addView(show);
        action(
                app,
                "Save and continue",
                true,
                () -> {
                    String number = id.getText().toString(), secret = hash.getText().toString();
                    try {
                        ApplicationCredentials.parse(number, secret);
                    } catch (IllegalArgumentException e) {
                        bridge.fail(e);
                        updateStatus();
                        return;
                    }
                    Runnable save =
                            () ->
                                    run(
                                            () -> {
                                                bridge.saveCredentials(number, secret);
                                                runOnUiThread(
                                                        () -> {
                                                            if (!isFinishing() && !isDestroyed()) {
                                                                hash.setText("");
                                                                editCredentials = false;
                                                                screen = "";
                                                                render();
                                                            }
                                                        });
                                            });
                    if (bridge.ready()
                            && !current.sameAs(ApplicationCredentials.parse(number, secret)))
                        confirm(
                                "Change application?",
                                "Telegram will reconnect using the new application configuration."
                                        + " Your music selection and local session files are kept.",
                                "Reconnect",
                                save);
                    else save.run();
                });
        paragraph(
                app,
                "Create your own application with a generic, descriptive name, such as My Client"
                        + " (short name: myclient). Telegram may show that name in sign-in"
                        + " alerts.");
        TextView help =
                style.text(
                        "Create your Telegram API credentials ↗",
                        14,
                        style.secondary,
                        style.regular);
        help.setPadding(style.dp(4), style.dp(16), 0, 0);
        help.setOnClickListener(
                v ->
                        startActivity(
                                new Intent(
                                        Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://my.telegram.org/apps"))));
        app.addView(help);
    }

    void account() {
        LinearLayout account = card("Account");
        TdApi.AuthorizationState auth = bridge.auth;
        if (!bridge.credentials().configured()) {
            paragraph(account, "Add your application credentials before signing in.");
            action(
                    account,
                    "Set up application",
                    true,
                    () -> {
                        editCredentials = true;
                        screen = "";
                        render();
                    });
            return;
        }
        if (bridge.ready()) {
            row(
                    account,
                    "Connected to Telegram",
                    "Your account is ready to stream music.",
                    null,
                    null);
            return;
        }
        if (auth instanceof TdApi.AuthorizationStateWaitPhoneNumber
                || auth instanceof TdApi.AuthorizationStateWaitCode
                || auth instanceof TdApi.AuthorizationStateWaitPassword
                || auth instanceof TdApi.AuthorizationStateWaitEmailAddress
                || auth instanceof TdApi.AuthorizationStateWaitEmailCode) {
            boolean phone = auth instanceof TdApi.AuthorizationStateWaitPhoneNumber,
                    password = auth instanceof TdApi.AuthorizationStateWaitPassword,
                    email = auth instanceof TdApi.AuthorizationStateWaitEmailAddress;
            String title =
                    phone
                            ? "Phone number"
                            : password
                                    ? "Two-step verification"
                                    : email ? "Email address" : "Verification code";
            paragraph(
                    account,
                    phone
                            ? "Sign in with your own Telegram account. Include your country code."
                            : password
                                    ? "Enter the password for your Telegram account."
                                    : email
                                            ? "Telegram requires an email address for this login."
                                            : "Enter the code delivered by Telegram. Check your"
                                                    + " existing Telegram app.");
            EditText field = input(account, title, password || !phone && !email);
            if (phone) field.setInputType(InputType.TYPE_CLASS_PHONE);
            if (email)
                field.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            action(
                    account,
                    phone ? "Continue" : "Verify and continue",
                    true,
                    () -> {
                        String value = field.getText().toString();
                        if (value.trim().isEmpty()) return;
                        field.setText("");
                        run(() -> bridge.submit(value));
                    });
        } else if (auth instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            paragraph(account, "Confirm this sign-in from another Telegram device.");
            String link = ((TdApi.AuthorizationStateWaitOtherDeviceConfirmation) auth).link;
            action(
                    account,
                    "Open Telegram",
                    true,
                    () ->
                            startActivity(
                                    new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))));
        } else if (auth instanceof TdApi.AuthorizationStateWaitRegistration)
            paragraph(
                    account,
                    "Sign in with an existing Telegram account. New accounts can't be created"
                            + " here.");
        else if (auth instanceof TdApi.AuthorizationStateWaitPremiumPurchase)
            paragraph(
                    account,
                    "Telegram is asking for a purchase to finish this login, which isn't"
                            + " supported here. Sign in with the official Telegram app first, then"
                            + " try again.");
        else {
            paragraph(
                    account,
                    auth instanceof TdApi.AuthorizationStateClosed
                            ? "Telegram is disconnected. Reopen the app, or save your application"
                                    + " configuration to reconnect."
                            : "Connecting to Telegram…");
            action(account, "Retry connection", false, () -> run(() -> bridge.configure()));
        }
    }

    void library() {
        LinearLayout music = card("Music library");
        row(
                music,
                "Music chats",
                bridge.selected.size() + " selected · Live Telegram search",
                "Choose",
                this::chooseChats);
        divider(music);
        row(
                music,
                "Channel View",
                "Read selected channels and official sponsored messages.",
                "Browse",
                this::chooseChannel);
        divider(music);
        row(
                music,
                "Playlists and downloads",
                "Save a chat’s music as a BitChord playlist, or download all of it.",
                "Choose",
                this::chooseCollection);
        divider(music);
        EditText chat = input(music, "@channel or numeric chat ID", false);
        action(
                music,
                "Add chat",
                false,
                () -> {
                    String value = chat.getText().toString();
                    run(() -> bridge.addChat(value));
                });
        paragraph(
                music,
                "Search your selected chats from BitChord’s Addons tab. Telegram searches the"
                        + " channel history. Audio is downloaded when played.");
        paragraph(
                music,
                "Each query shows up to 200 results across audio and music documents. Refine the"
                        + " title or artist for more specific matches.");
        divider(music);
        indexLine =
                toggle(
                        music,
                        "Saved index",
                        bridge.indexStatus(),
                        "Only Telegram’s own search is used",
                        bridge.searchIndex(),
                        bridge::setSearchIndex);
        paragraph(
                music,
                "Keeps a list of the music in your selected chats on this phone, built slowly in"
                        + " the background and topped up every few hours. It answers when"
                        + " Telegram’s search is rate limited or busy, and the format legend uses"
                        + " it instead of searching Telegram. Turning it off deletes it.");
        trackNames();
        trackDetails();
        LinearLayout playback = card("Playback");
        row(
                playback,
                "Telegram source",
                "Added automatically to BitChord.",
                "Repair",
                () -> run(() -> bridge.registerSource()));
        divider(playback);
        toggle(
                playback,
                "Smooth quality switch",
                "A better copy fades in without a gap",
                "BitChord switches copies with a short pause",
                bridge.smoothUpgrades(),
                bridge::setSmoothUpgrades);
        divider(playback);
        toggle(
                playback,
                "Play surround as stereo",
                "5.1 and 7.1 files are mixed to stereo in BitChord",
                "Android mixes surround files itself",
                bridge.surroundAsStereo(),
                bridge::setSurroundAsStereo);
        divider(playback);
        toggle(
                playback,
                "Check matches by sound",
                "Telegram files with another length are compared with the song",
                "Files with another length aren’t used",
                bridge.soundCheck(),
                bridge::setSoundCheck,
                "When your Telegram file has a song’s title but a different length, it’s usually"
                        + " another recording, so BitChord plays the song from elsewhere. With"
                        + " this on, the patch listens to the first 15 seconds of the song as it"
                        + " plays and compares them with the start of the Telegram file. If"
                        + " they’re the same recording, the file is used the next time the song"
                        + " plays.\n\nEach check downloads up to the first 45 seconds of the"
                        + " Telegram file, about 5 to 15 MB for a lossless file, and uses some"
                        + " battery for a few seconds. Nothing is uploaded or sent anywhere"
                        + " else.");
        paragraph(
                playback,
                "When BitChord finds a better copy of the playing song, for example your"
                        + " Telegram FLAC after it started from YouTube, the smooth switch loads it"
                        + " in the background and fades it in at the same point. If it can't, the"
                        + " switch happens the usual way.");
        paragraph(
                playback,
                "Play surround as stereo works for every song BitChord plays, not just Telegram"
                    + " files. The file isn’t changed. Centre and surround channels are mixed in at"
                    + " a lower level and the bass channel is left out, the same as converting with"
                    + " ffmpeg. Starts with the next song.");
        action(
                playback,
                "Open BitChord",
                true,
                () -> {
                    startActivity(
                            new Intent(Intent.ACTION_MAIN)
                                    .setClassName(
                                            getPackageName(), "com.music.bitchord.MainActivity")
                                    .addFlags(
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    finish();
                });
        LinearLayout storage = card("Storage");
        paragraph(storage, "Telegram audio cache target");
        TextView cacheValue =
                style.text(
                        CachePolicy.label(bridge.cacheLimitBytes()),
                        18,
                        style.foreground,
                        style.semibold);
        storage.addView(cacheValue);
        SeekBar cacheSlider = new SeekBar(this);
        cacheSlider.setMax(CachePolicy.count() - 1);
        cacheSlider.setProgress(CachePolicy.indexOf(bridge.cacheLimitBytes()));
        cacheSlider.setContentDescription("Telegram audio cache target");
        cacheSlider.setProgressTintList(
                android.content.res.ColorStateList.valueOf(style.foreground));
        cacheSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(style.foreground));
        cacheSlider.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(style.outline));
        storage.addView(cacheSlider, new LinearLayout.LayoutParams(-1, style.dp(48)));
        actions.add(cacheSlider);
        cacheSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar bar) {}

                    public void onStopTrackingTouch(SeekBar bar) {}

                    public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        cacheValue.setText(CachePolicy.label(CachePolicy.bytesAt(value)));
                    }
                });
        paragraph(
                storage,
                "64 MiB to 16 GiB · A soft target, not reserved space. Active audio may exceed it."
                        + " BitChord’s separate song cache and downloads use additional storage.");
        action(
                storage,
                "Apply cache size",
                false,
                () ->
                        run(
                                false,
                                () ->
                                        bridge.setCacheLimitBytes(
                                                CachePolicy.bytesAt(cacheSlider.getProgress()))));
        action(storage, "Clear Telegram audio cache", false, () -> run(() -> bridge.clearCache()));
        LinearLayout session = card("Session");
        action(
                session,
                "Sign out",
                false,
                () ->
                        confirm(
                                "Sign out?",
                                "This signs you out of Telegram on this phone.",
                                "Sign out",
                                () -> run(() -> bridge.logout())));
    }

    void trackDetails() {
        LinearLayout details = card("Track details");
        toggle(
                details,
                "Report audio quality",
                "FLAC, WAV and ALAC count as lossless; others by bitrate",
                "BitChord guesses from the file type",
                bridge.reportQuality(),
                bridge::setReportQuality);
        divider(details);
        toggle(
                details,
                "Album covers",
                "Uses the cover Telegram has for each file",
                "No covers are downloaded",
                bridge.showArtwork(),
                bridge::setShowArtwork);
        divider(details);
        toggle(
                details,
                "Names from file names",
                "Untagged files like “01 - Artist - Title.flac” get a proper name",
                "Untagged files show their file name",
                bridge.namesFromFiles(),
                bridge::setNamesFromFiles);
        divider(details);
        toggle(
                details,
                "Format legend",
                "Shows the format next to song titles, like FLAC 24bit or MP3 320kbps. Your"
                        + " searches also check the selected chats for a match",
                "Titles only",
                bridge.formatLegend(),
                bridge::setFormatLegend);
        paragraph(
                details,
                "BitChord uses the quality to choose between your Telegram file and other sources,"
                    + " so a low-bitrate MP3 may lose to a better copy elsewhere. Covers are saved"
                    + " on this phone. BitChord keeps search results for a few minutes, so changes"
                    + " show up in new searches. To tell 16-bit from 24-bit, the start of a few"
                    + " lossless files per search is downloaded.");
        details.getChildAt(details.getChildCount() - 1).setPadding(0, style.dp(12), 0, style.dp(6));
    }

    void trackNames() {
        LinearLayout names = card("Track names");
        namesList = new LinearLayout(this);
        namesList.setOrientation(LinearLayout.VERTICAL);
        names.addView(namesList);
        fillTrackNames();
        EditText find = input(names, "Search your music chats", false);
        action(names, "Fix a track name", false, () -> pickTrack(find.getText().toString()));
    }

    /** The part of the Track names card that changes; refreshed in place as names change. */
    void fillTrackNames() {
        LinearLayout names = namesList;
        names.removeAllViews();
        namesRevision = bridge.names.revision();
        List<JSONObject> suggestions = Collections.emptyList(), corrections = suggestions;
        try {
            suggestions = bridge.names.suggestions();
            corrections = bridge.names.corrections();
        } catch (Exception ignored) {
        }
        paragraph(
                names,
                "Fix a Telegram file’s title or artist on this phone, without uploading it again."
                        + " BitChord then finds and matches the file under the corrected name.");
        for (JSONObject suggestion : suggestions) {
            row(
                    names,
                    "“"
                            + suggestion.optString("fileTitle")
                            + "” → “"
                            + suggestion.optString("title")
                            + "”",
                    suggestion.optString("artist")
                            + " · "
                            + length(suggestion.optInt("duration"))
                            + " · Looks like a song that played from another source",
                    "Review",
                    () -> reviewSuggestion(suggestion));
            divider(names);
        }
        final List<JSONObject> fixed = corrections;
        row(
                names,
                "Corrected names",
                fixed.isEmpty()
                        ? "None yet"
                        : fixed.size() + (fixed.size() == 1 ? " track" : " tracks"),
                fixed.isEmpty() ? null : "Edit",
                fixed.isEmpty() ? null : () -> listCorrections(fixed));
        divider(names);
        toggle(
                names,
                "Use corrected names",
                "BitChord sees the corrected names",
                "Files keep their Telegram names; corrections are kept",
                bridge.useTrackNames(),
                bridge::setUseTrackNames);
        divider(names);
        toggle(
                names,
                "Suggest fixes",
                "Checks songs that play from other sources",
                "No background searches",
                bridge.suggestTrackNames(),
                bridge::setSuggestTrackNames);
        paragraph(
                names,
                "With suggestions on, when a song plays from another source the patch searches"
                        + " your selected chats for that artist and suggests a file whose title is"
                        + " a few letters off. Nothing is renamed until you accept. Saving a name"
                        + " also removes BitChord’s saved copy of that song, so its next play"
                        + " uses your Telegram file.");
        names.getChildAt(names.getChildCount() - 1).setPadding(0, style.dp(12), 0, style.dp(6));
    }

    static String length(int seconds) {
        return seconds <= 0
                ? "unknown length"
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    void reviewSuggestion(JSONObject suggestion) {
        String id = suggestion.optString("id");
        AlertDialog d =
                new AlertDialog.Builder(this)
                        .setTitle("Use this name?")
                        .setMessage(
                                "On Telegram: “"
                                        + suggestion.optString("fileTitle")
                                        + "” by "
                                        + suggestion.optString("fileArtist")
                                        + " ("
                                        + length(suggestion.optInt("duration"))
                                        + ")\n\nNew name: “"
                                        + suggestion.optString("title")
                                        + "” by "
                                        + suggestion.optString("artist")
                                        + "\n\nThe file on Telegram isn’t changed.")
                        .setPositiveButton(
                                "Use name", (x, y) -> run(() -> bridge.acceptSuggestion(id)))
                        .setNeutralButton("Dismiss", (x, y) -> run(() -> bridge.names.dismiss(id)))
                        .setNegativeButton("Cancel", null)
                        .create();
        secureWindow(d.getWindow());
        d.show();
        style.tuneDialog(d);
    }

    void listCorrections(List<JSONObject> corrections) {
        String[] labels = new String[corrections.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] =
                    "“"
                            + corrections.get(i).optString("fileTitle")
                            + "” → “"
                            + corrections.get(i).optString("title")
                            + "”";
        AlertDialog d =
                new AlertDialog.Builder(this)
                        .setTitle("Corrected names")
                        .setItems(labels, (x, index) -> editName(corrections.get(index)))
                        .setNegativeButton("Close", null)
                        .create();
        secureWindow(d.getWindow());
        d.show();
    }

    void pickTrack(String query) {
        if (query.trim().isEmpty()) {
            confirm(
                    "Search first",
                    "Enter part of the title, artist or file name.",
                    "OK",
                    () -> {});
            return;
        }
        run(
                false,
                () -> {
                    List<JSONObject> rows = bridge.searchTracks(query);
                    runOnUiThread(
                            () -> {
                                if (isFinishing() || isDestroyed()) return;
                                if (rows.isEmpty()) {
                                    confirm(
                                            "No tracks found",
                                            "Nothing in your selected chats matches “"
                                                    + query.trim()
                                                    + "”.",
                                            "OK",
                                            () -> {});
                                    return;
                                }
                                String[] labels = new String[rows.size()];
                                for (int i = 0; i < labels.length; i++)
                                    labels[i] =
                                            rows.get(i).optString("title")
                                                    + " — "
                                                    + rows.get(i).optString("artist")
                                                    + " · "
                                                    + length(rows.get(i).optInt("duration"));
                                AlertDialog d =
                                        new AlertDialog.Builder(this)
                                                .setTitle("Choose a track")
                                                .setItems(
                                                        labels,
                                                        (x, index) -> editName(rows.get(index)))
                                                .setNegativeButton("Cancel", null)
                                                .create();
                                secureWindow(d.getWindow());
                                d.show();
                            });
                });
    }

    void editName(JSONObject row) {
        boolean corrected = row.has("fileTitle");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(style.dp(24), style.dp(8), style.dp(24), 0);
        TextView file =
                style.text(
                        "On Telegram: “"
                                + row.optString(corrected ? "fileTitle" : "title")
                                + "” by "
                                + row.optString(corrected ? "fileArtist" : "artist"),
                        14,
                        style.secondary,
                        style.regular);
        form.addView(file);
        EditText title = dialogField(form, "Title", row.optString("title"));
        EditText artist = dialogField(form, "Artist", row.optString("artist"));
        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle("Track name")
                        .setView(form)
                        .setPositiveButton(
                                "Save",
                                (x, y) -> {
                                    String newTitle = title.getText().toString(),
                                            newArtist = artist.getText().toString();
                                    run(() -> bridge.rename(row, newTitle, newArtist));
                                })
                        .setNegativeButton("Cancel", null);
        if (corrected)
            builder.setNeutralButton(
                    "Use original", (x, y) -> run(() -> bridge.names.remove(row.optString("id"))));
        AlertDialog d = builder.create();
        secureWindow(d.getWindow());
        d.show();
        style.tuneDialog(d);
    }

    EditText dialogField(LinearLayout form, String label, String value) {
        TextView caption = style.text(label, 12, style.secondary, style.semibold);
        caption.setPadding(0, style.dp(14), 0, style.dp(6));
        form.addView(caption);
        EditText field = new EditText(this);
        field.setFilterTouchesWhenObscured(true);
        field.setSingleLine(true);
        field.setText(value);
        field.setTextColor(style.foreground);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    void reopenChannel(long id) {
        channelId = id;
        screen = "";
        render();
    }

    void chooseChannel() {
        pickChat("Channel View", chat -> reopenChannel(chat.id));
    }

    /** A playlist or a download of a chat's newest music. */
    void chooseCollection() {
        pickChat(
                "Playlists and downloads",
                chat ->
                        bridge.work.execute(
                                () -> {
                                    String[] labels;
                                    try {
                                        labels = bridge.collectionSummary(chat.id);
                                    } catch (Exception e) {
                                        bridge.fail(e);
                                        runOnUiThread(this::updateStatus);
                                        return;
                                    }
                                    runOnUiThread(() -> collectionActions(chat, labels));
                                }));
    }

    /** The menu for one chat; {@code summary} is its song count and download size. */
    void collectionActions(TdApi.Chat chat, String[] summary) {
        if (isFinishing() || isDestroyed()) return;
        String songs = summary[0], size = summary[1];
        String[] labels = {
            "Save as playlist (" + songs + ")", "Download all (" + songs + ", " + size + ")"
        };
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(chat.title)
                        .setItems(
                                labels,
                                (d, which) -> {
                                    if (which == 0)
                                        run(() -> bridge.saveChannelPlaylist(chat.id, chat.title));
                                    else
                                        confirm(
                                                "Download all",
                                                Character.toUpperCase(songs.charAt(0))
                                                        + songs.substring(1)
                                                        + " from "
                                                        + chat.title
                                                        + ", "
                                                        + size
                                                        + ". They’re saved at BitChord’s download"
                                                        + " quality, so set it to Lossless to keep"
                                                        + " FLAC files.",
                                                "Download",
                                                () ->
                                                        run(
                                                                () ->
                                                                        bridge.downloadChannel(
                                                                                chat.id,
                                                                                chat.title)));
                                })
                        .setNegativeButton("Cancel", null)
                        .create();
        secureWindow(dialog.getWindow());
        dialog.show();
    }

    /** Asks which selected chat to use, then hands it on. */
    void pickChat(String title, java.util.function.Consumer<TdApi.Chat> chosen) {
        run(
                () -> {
                    List<TdApi.Chat> chats = new ArrayList<>();
                    for (long id : bridge.selected)
                        try {
                            chats.add(bridge.channel(id));
                        } catch (Exception ignored) {
                        }
                    runOnUiThread(
                            () -> {
                                if (isFinishing() || isDestroyed()) return;
                                if (chats.isEmpty()) {
                                    confirm(
                                            "Choose music chats",
                                            "Select an accessible, unprotected music chat first.",
                                            "Done",
                                            () -> {});
                                    return;
                                }
                                String[] labels = new String[chats.size()];
                                for (int i = 0; i < labels.length; i++)
                                    labels[i] = chats.get(i).title;
                                AlertDialog dialog =
                                        new AlertDialog.Builder(this)
                                                .setTitle(title)
                                                .setItems(
                                                        labels,
                                                        (d, index) ->
                                                                chosen.accept(chats.get(index)))
                                                .setNegativeButton("Cancel", null)
                                                .create();
                                secureWindow(dialog.getWindow());
                                dialog.show();
                            });
                });
    }

    void about() {
        LinearLayout info = card("About");
        paragraph(
                info,
                "An unofficial add-on that uses the Telegram API with your own account. It isn’t"
                        + " made by or affiliated with Telegram or BitChord. Channel View can show"
                        + " Telegram’s own sponsored messages.");
        row(
                info,
                "Privacy and your account",
                "Storage, sessions and signing out",
                "Read",
                () ->
                        confirm(
                                "Privacy and your account",
                                "Your phone talks to Telegram directly. Your API details are"
                                        + " encrypted on this phone and your Telegram session is"
                                        + " stored privately. The session is a normal Telegram"
                                        + " login, so it can reach your whole account, not just"
                                        + " music. Audio BitChord has saved stays until you clear"
                                        + " it, even after you sign out. You can end the session"
                                        + " anytime in the official Telegram app under Settings →"
                                        + " Devices.",
                                "Done",
                                () -> {}));
        divider(info);
        row(
                info,
                "Your audio",
                "Only use recordings you’re allowed to share",
                "Read",
                () ->
                        confirm(
                                "Your audio",
                                "You’re responsible for the audio you upload, share and play."
                                        + " This add-on doesn’t provide any music. Only use"
                                        + " recordings you own or have permission to use, and"
                                        + " follow Telegram’s terms. Protected or self-destructing"
                                        + " media can’t be played here.",
                                "Done",
                                () -> {}));
        divider(info);
        row(
                info,
                "Telegram API terms",
                "Telegram’s rules for apps using its API",
                "Open",
                () ->
                        startActivity(
                                new Intent(
                                        Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                                "https://core.telegram.org/api/terms"))));
        paragraph(
                info,
                "Free and provided as-is. Use it responsibly: you’re responsible for your Telegram"
                        + " account and channels.");
    }

    void chooseChats() {
        run(
                () -> {
                    List<TdApi.Chat> chats = bridge.chats();
                    runOnUiThread(
                            () -> {
                                String[] names = new String[chats.size()];
                                boolean[] selected = new boolean[chats.size()];
                                for (int i = 0; i < names.length; i++) {
                                    names[i] = chats.get(i).title;
                                    selected[i] = bridge.selected.contains(chats.get(i).id);
                                }
                                AlertDialog dialog =
                                        new AlertDialog.Builder(this)
                                                .setTitle("Music chats")
                                                .setMultiChoiceItems(
                                                        names,
                                                        selected,
                                                        (d, index, value) ->
                                                                selected[index] = value)
                                                .setNegativeButton("Cancel", null)
                                                .setPositiveButton(
                                                        "Save",
                                                        (d, w) ->
                                                                run(
                                                                        () ->
                                                                                bridge.selectChats(
                                                                                        chats,
                                                                                        selected)))
                                                .create();
                                secureWindow(dialog.getWindow());
                                dialog.show();
                                style.tuneDialog(dialog);
                            });
                });
    }

    LinearLayout card(String title) {
        if (title != null) {
            TextView label =
                    style.text(title.toUpperCase(Locale.ROOT), 11, style.secondary, style.semibold);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.setMargins(style.dp(20), style.dp(24), style.dp(20), style.dp(8));
            body.addView(label, ll);
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(style.dp(16), style.dp(16), style.dp(16), style.dp(16));
        c.setBackground(style.shape(style.card, 14, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(style.dp(16), title == null ? style.dp(16) : 0, style.dp(16), 0);
        body.addView(c, lp);
        return c;
    }

    void paragraph(LinearLayout p, String value) {
        TextView t = style.text(value, 14, style.secondary, style.regular);
        t.setLineSpacing(style.dp(3), 1);
        t.setPadding(0, 0, 0, style.dp(6));
        p.addView(t);
    }

    void divider(LinearLayout p) {
        View line = new View(this);
        line.setBackgroundColor(style.outline);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, style.dp(1));
        lp.setMargins(0, style.dp(14), 0, style.dp(14));
        p.addView(line, lp);
    }

    void row(LinearLayout p, String title, String subtitle, String more, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(40));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(style.text(title, 16, style.foreground, style.medium));
        TextView sub = style.text(subtitle, 14, style.secondary, style.regular);
        sub.setPadding(0, style.dp(4), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (more != null) {
            TextView m = style.text(more + "  ›", 14, style.secondary, style.medium);
            m.setPadding(style.dp(16), style.dp(8), 0, style.dp(8));
            row.addView(m);
        }
        if (click != null) {
            row.setOnClickListener(
                    v -> {
                        if (!busy) click.run();
                    });
            row.setClickable(true);
            row.setFocusable(true);
        }
        p.addView(row);
    }

    interface Setting {
        void set(boolean on) throws Exception;
    }

    /**
     * A setting with a switch. Flipping it saves in the background without rebuilding the page, so
     * the screen stays where it is; if saving fails the switch flips back.
     */
    TextView toggle(
            LinearLayout p, String title, String on, String off, boolean value, Setting setting) {
        return toggle(p, title, on, off, value, setting, null);
    }

    /**
     * As above; turning it on first shows {@code warning} and only goes ahead when the user agrees.
     */
    TextView toggle(
            LinearLayout p,
            String title,
            String on,
            String off,
            boolean value,
            Setting setting,
            String warning) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(40));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(style.text(title, 16, style.foreground, style.medium));
        TextView sub = style.text(value ? on : off, 14, style.secondary, style.regular);
        sub.setPadding(0, style.dp(4), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TelegramSwitch toggle = new TelegramSwitch(this, style);
        toggle.setChecked(value);
        toggle.setContentDescription(title);
        Runnable[] apply = new Runnable[1];
        toggle.setListener(
                (button, checked) -> {
                    if (checked && warning != null) {
                        AlertDialog dialog =
                                new AlertDialog.Builder(this)
                                        .setTitle(title)
                                        .setMessage(warning)
                                        .setNegativeButton(
                                                "Cancel", (d, w) -> toggle.setChecked(false))
                                        .setPositiveButton("Turn on", (d, w) -> apply[0].run())
                                        .setOnCancelListener(d -> toggle.setChecked(false))
                                        .create();
                        secureWindow(dialog.getWindow());
                        dialog.show();
                        style.tuneDialog(dialog);
                        return;
                    }
                    apply[0].run();
                });
        apply[0] =
                () -> {
                    boolean checked = toggle.isChecked();
                    sub.setText(checked ? on : off);
                    bridge.work.execute(
                            () -> {
                                try {
                                    setting.set(checked);
                                } catch (Exception e) {
                                    bridge.fail(e);
                                    runOnUiThread(
                                            () -> {
                                                toggle.setChecked(!checked);
                                                sub.setText(!checked ? on : off);
                                                updateStatus();
                                            });
                                }
                            });
                };
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = style.dp(16);
        row.addView(toggle, gap);
        row.setOnClickListener(v -> toggle.toggle());
        row.setClickable(true);
        row.setFocusable(true);
        p.addView(row);
        return sub;
    }

    EditText input(LinearLayout p, String label, boolean secret) {
        TextView title = style.text(label, 12, style.secondary, style.semibold);
        title.setPadding(0, style.dp(14), 0, style.dp(7));
        p.addView(title);
        EditText field = new EditText(this);
        field.setFilterTouchesWhenObscured(true);
        field.setSingleLine(true);
        field.setTextSize(16);
        field.setTextColor(style.foreground);
        field.setHintTextColor(style.secondary);
        field.setHint(label);
        field.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (secret
                                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setImeOptions(
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (secret) secrets.add(field);
        field.setTypeface(style.regular);
        field.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12));
        field.setBackground(style.shape(style.field, 10, false));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setSaveEnabled(false);
        p.addView(field, new LinearLayout.LayoutParams(-1, style.dp(50)));
        actions.add(field);
        return field;
    }

    void action(LinearLayout p, String title, boolean primary, Runnable click) {
        TextView b =
                style.button(
                        title,
                        primary,
                        () -> {
                            if (!busy) click.run();
                        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = style.dp(12);
        p.addView(b, lp);
        actions.add(b);
    }

    void confirm(String title, String message, String positive, Runnable action) {
        AlertDialog d =
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(positive, (dialog, which) -> action.run())
                        .create();
        secureWindow(d.getWindow());
        d.show();
        style.tuneDialog(d);
    }

    void updateStatus() {
        if (bridge == null || status == null) return;
        if (namesList != null && !busy && bridge.names.revision() != namesRevision)
            fillTrackNames();
        status.setText((busy ? "Working…\n" : "") + bridge.status);
        error.setText(bridge.error);
        error.setVisibility(bridge.error.isEmpty() ? View.GONE : View.VISIBLE);
        for (View v : actions) {
            v.setEnabled(!busy);
            v.setAlpha(busy ? 0.45f : 1f);
        }
    }

    interface Job {
        void run() throws Exception;
    }

    void run(Job job) {
        run(true, job);
    }

    void run(boolean rebuild, Job job) {
        if (busy) return;
        busy = true;
        updateStatus();
        bridge.work.execute(
                () -> {
                    try {
                        bridge.error = "";
                        job.run();
                    } catch (Exception e) {
                        bridge.fail(e);
                    } finally {
                        busy = false;
                        runOnUiThread(
                                () -> {
                                    if (!isFinishing() && !isDestroyed()) {
                                        if (rebuild && bridge.ready() && !editCredentials)
                                            screen = "";
                                        render();
                                    }
                                });
                    }
                });
    }
}
