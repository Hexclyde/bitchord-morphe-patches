package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.InvocationTargetException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The native Patches & tweaks page. Installed features stay compact until enabled and opened. */
public final class ExtrasActivity extends Activity {
    private TelegramStyle style;
    private LinearLayout body;
    private ScrollView scroll;
    private String expanded;
    private final LinkedHashMap<String, Feature> features = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus = this::refreshStatus;
    private TextView batteryStatus, bluetoothStatus, djStatus, jamesStatus;
    private LinearLayout root, feedback;
    private EditText search;
    private TextView counts, empty, feedbackText;
    private ImageButton clearSearch;
    private View storageShortcut;
    private ModeSelector selector;
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    private final LinkedHashMap<String, TextView> categoryButtons = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> scrollPositions = new LinkedHashMap<>();
    private String query = "", category = "all", filterKey = "";
    private boolean enabledOnly, building, destroyed, foreground, resumed;
    private UndoChange undo;
    private long changes, equalizerRevision;
    private SharedPreferences hostPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener hostListener =
            (preferences, key) -> {
                if ("equalizer_enabled".equals(key)) equalizerRevision++;
            };
    private final Runnable dismissUndo = this::clearUndo;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "patch-settings");
                        thread.setDaemon(true);
                        return thread;
                    });

    interface Setting {
        void set(boolean on) throws Exception;
    }

    interface State {
        boolean get() throws Exception;
    }

    interface Details {
        void build(LinearLayout parent);
    }

    interface Value {
        void set(int value);
    }

    interface Label {
        String of(int value);
    }

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        int theme =
                getResources()
                        .getIdentifier(
                                dark ? "PatchNativeTheme" : "PatchNativeThemeLight",
                                "style",
                                getPackageName());
        setTheme(
                theme != 0
                        ? theme
                        : dark
                                ? android.R.style.Theme_Material_NoActionBar
                                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        SoundExtras.init(this);
        Strings.init(this);
        style = new TelegramStyle(this);
        if (saved != null) expanded = saved.getString("expandedPatch");
        getWindow().setStatusBarColor(style.background);
        getWindow().setNavigationBarColor(style.background);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        style.dark
                                ? 0
                                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (saved != null) {
            query = saved.getString("patchQuery", "");
            category = saved.getString("patchCategory", "all");
            enabledOnly = saved.getBoolean("patchEnabledOnly", false);
        }
        hostPreferences = getSharedPreferences(OutputEq.HOST_PREFERENCES, MODE_PRIVATE);
        hostPreferences.registerOnSharedPreferenceChangeListener(hostListener);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(style.background);
        root.setLayoutDirection(
                Strings.rightToLeft() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        toolbar();
        scroll = new ScrollView(this);
        scroll.setTag("patch-list");
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, style.dp(24));
        boolean compactHeight = getResources().getConfiguration().screenHeightDp < 480;
        if (compactHeight) {
            root.addView(body);
            scroll.addView(root);
        } else {
            scroll.addView(body);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        feedback = new LinearLayout(this);
        feedback.setGravity(Gravity.CENTER_VERTICAL);
        feedback.setPadding(style.dp(16), style.dp(8), style.dp(8), style.dp(8));
        feedback.setBackground(style.shape(style.card, 14, false));
        feedback.setVisibility(View.GONE);
        feedbackText = style.text("", 14, style.foreground, style.regular);
        feedbackText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.addView(feedbackText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView restore = style.button(Strings.t("Undo"), false, this::undoChange);
        restore.setTag("patch-undo");
        feedback.addView(restore, new LinearLayout.LayoutParams(-2, -2));
        if (compactHeight) {
            LinearLayout shell = new LinearLayout(this);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setBackgroundColor(style.background);
            shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            shell.addView(feedback);
            setContentView(shell);
        } else {
            root.addView(feedback);
            setContentView(root);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    root.setPadding(
                            0,
                            insets.getSystemWindowInsetTop(),
                            0,
                            insets.getSystemWindowInsetBottom());
                    return insets;
                });
        building = true;
        render();
        building = false;
        for (String key : new String[] {"sources", "playback", "audio", "library"}) {
            Group group = groups.get(key);
            if (group == null) continue;
            body.removeView(group.heading);
            body.removeView(group.card);
            body.addView(group.heading);
            body.addView(group.card);
        }
        empty = style.text("", 15, style.secondary, style.regular);
        empty.setTag("patch-empty");
        empty.setPadding(style.dp(24), style.dp(28), style.dp(24), style.dp(28));
        body.addView(empty);
        applyFilters(false);
        if (saved != null) scroll.post(() -> scroll.scrollTo(0, saved.getInt("patchScroll", 0)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (resumed) {
            for (Feature feature : features.values()) {
                feature.built = false;
                feature.details.removeAllViews();
            }
            batteryStatus = bluetoothStatus = djStatus = jamesStatus = null;
        }
        resumed = true;
        for (Feature feature : features.values()) feature.sync();
        showExpanded();
        applyFilters(false);
        Outputs.refresh();
        handler.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        foreground = false;
        changes++;
        handler.removeCallbacks(refreshStatus);
        clearUndo();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        hostPreferences.unregisterOnSharedPreferenceChangeListener(hostListener);
        super.onDestroy();
    }

    /** Rebuild only the explicitly open configuration after an edit changed its content. */
    private void rebuild() {
        Feature selected = features.get(expanded);
        if (selected == null) return;
        int y = scroll.getScrollY();
        selected.built = false;
        selected.details.removeAllViews();
        showExpanded();
        scroll.post(() -> scroll.scrollTo(0, y));
        handler.post(refreshStatus);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("expandedPatch", expanded);
        state.putInt("patchScroll", scroll.getScrollY());
        state.putString("patchQuery", query);
        state.putString("patchCategory", category);
        state.putBoolean("patchEnabledOnly", enabledOnly);
        super.onSaveInstanceState(state);
    }

    private void render() {
        if (exists("app.bitchord.telegram.TelegramActivity")) {
            feature(
                    "telegram",
                    Strings.t("Telegram music"),
                    Strings.t("Play audio from Telegram channels after signing in"),
                    () -> sourceEnabled("telegram-native"),
                    on -> setSourceEnabled("telegram-native", on),
                    parent ->
                            navigation(
                                    parent,
                                    Strings.t("Channels & account"),
                                    () -> {
                                        try {
                                            startActivity(
                                                    new android.content.Intent(
                                                            this,
                                                            Class.forName(
                                                                    "app.bitchord.telegram.TelegramActivity")));
                                        } catch (Exception unavailable) {
                                            unavailable();
                                        }
                                    }));
        }
        boolean downloads = exists("app.bitchord.soulseek.DownloadSettings");
        if (downloads) {
            downloadFeature(
                    "soulseek",
                    Strings.t("Soulseek downloads"),
                    Strings.t("Search shared audio files with your Soulseek account"),
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Search Soulseek for audio files and download the version"
                                                + " you choose."));
                        navigation(
                                parent,
                                Strings.t("Account & download settings"),
                                () -> openDownload("soulseek", "account"));
                        copyActions(parent, "soulseek");
                    });
            if (optionalInstalled("app.bitchord.soulseek.SoulseekPreview")) {
                feature(
                        "streamseek-preview",
                        Strings.t("Streamseek previews"),
                        Strings.t("Listen through a temporary Soulseek transfer before saving"),
                        () ->
                                (Boolean)
                                        invoke(
                                                "app.bitchord.soulseek.SoulseekPreview",
                                                "enabled",
                                                new Class<?>[] {Context.class},
                                                this),
                        on ->
                                invoke(
                                        "app.bitchord.soulseek.SoulseekPreview",
                                        "setEnabled",
                                        new Class<?>[] {Context.class, boolean.class},
                                        this,
                                        on),
                        parent ->
                                paragraph(
                                        parent,
                                        Strings.t(
                                                "Preview uses a temporary transfer. Keep download"
                                                    + " reuses its buffered bytes. Compare with a"
                                                    + " known local reference and inspect the"
                                                    + " evidence before confirming a recording.")));
            }
            downloadFeature(
                    "peer-downloads",
                    Strings.t("Peer downloads"),
                    Strings.t("Exchange selected files with a paired phone"),
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Pair a trusted phone and choose files to send or"
                                                + " download."));
                        navigation(
                                parent,
                                Strings.t("Download queue"),
                                () -> openDownload("peer-downloads", "downloads"));
                        navigation(
                                parent,
                                Strings.t("Connection setup"),
                                () -> openDownload("peer-downloads", "connections"));
                        copyActions(parent, "peer-downloads");
                    });
            downloadFeature(
                    "debrid",
                    Strings.t("Debrid downloads"),
                    Strings.t("Use your own Real-Debrid, AllDebrid, Premiumize or TorBox account"),
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Choose a service, enter its API key and download selected"
                                                + " audio from a link."));
                        navigation(
                                parent,
                                Strings.t("Services & API keys"),
                                () -> openDownload("debrid", "services"));
                        navigation(
                                parent,
                                Strings.t("Link downloads"),
                                () -> openDownload("debrid", "links"));
                        copyActions(parent, "debrid");
                    });
        }

        boolean audio =
                SoundExtras.singApplied()
                        || SoundExtras.pitchApplied()
                        || SoundExtras.loudnessApplied()
                        || SoundExtras.sleepFadeApplied()
                        || SoundExtras.batterySaverApplied()
                        || SoundExtras.losslessBluetoothApplied()
                        || SoundExtras.headphoneApplied()
                        || SoundExtras.outputEqApplied()
                        || SoundExtras.djApplied()
                        || SoundExtras.jamesDspApplied();
        if (audio && BitPerfect.wanted()) {
            LinearLayout note = new LinearLayout(this);
            note.setOrientation(LinearLayout.VERTICAL);
            note.setPadding(style.dp(20), 0, style.dp(20), 0);
            paragraph(
                    note,
                    Strings.t(
                            "Bit-perfect output is on in BitChord. Songs it plays reach your DAC"
                                    + " unchanged, so these effects, the sleep fade and JamesDSP"
                                    + " don’t apply to them."));
            body.addView(note);
        }
        if (SoundExtras.singApplied()) {
            feature(
                    "sing",
                    Strings.t("Karaoke"),
                    Strings.t("Reduce vocals with a filter or separation model"),
                    () -> SoundExtras.singEnabled,
                    SoundExtras::setSingEnabled,
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Turns the singer down so you can sing over the song. Works"
                                                + " best on studio recordings with the voice in the"
                                                + " middle."));
                        slider(
                                parent,
                                Strings.t("Voice"),
                                0,
                                100,
                                SoundExtras.singLevel,
                                level ->
                                        level == 0
                                                ? Strings.t("Off")
                                                : Strings.f("{0}% quieter", String.valueOf(level)),
                                SoundExtras::setSingLevel);
                        divider(parent);
                        LinearLayout methods = new LinearLayout(this);
                        for (int value : new int[] {VocalCut.VOICE_GATE, SeparationModel.METHOD}) {
                            final int selectedMethod = value;
                            TextView choice =
                                    style.button(
                                            Strings.t(
                                                    value == SeparationModel.METHOD
                                                            ? "Separation model"
                                                            : "Filter"),
                                            SoundExtras.singMethod == value
                                                    || value == VocalCut.VOICE_GATE
                                                            && SoundExtras.singMethod
                                                                    == VocalCut.CENTRE,
                                            () -> {
                                                SoundExtras.setSingMethod(selectedMethod);
                                                rebuild();
                                            });
                            choice.setTag(
                                    value == SeparationModel.METHOD
                                            ? "sing-method-model"
                                            : "sing-method-filter");
                            methods.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
                        }
                        parent.addView(methods);
                        TextView modelStatus =
                                new TextView(this) {
                                    private final Runnable update =
                                            new Runnable() {
                                                public void run() {
                                                    String value =
                                                            Strings.t(SeparationModel.status());
                                                    if (!value.contentEquals(getText()))
                                                        setText(value);
                                                    if (isAttachedToWindow())
                                                        postDelayed(this, 500);
                                                }
                                            };

                                    protected void onAttachedToWindow() {
                                        super.onAttachedToWindow();
                                        post(update);
                                    }

                                    protected void onDetachedFromWindow() {
                                        removeCallbacks(update);
                                        super.onDetachedFromWindow();
                                    }
                                };
                        modelStatus.setText(Strings.t(SeparationModel.status()));
                        modelStatus.setTextColor(style.secondary);
                        modelStatus.setTextSize(14);
                        modelStatus.setTag("sing-model-status");
                        modelStatus.setAccessibilityLiveRegion(
                                View.ACCESSIBILITY_LIVE_REGION_POLITE);
                        parent.addView(modelStatus);
                        paragraph(
                                parent,
                                Strings.t(
                                        "Separation adds about 1.6 seconds of audio delay. The"
                                                + " filter is used when the model cannot run."));
                        option(
                                parent,
                                Strings.t("Whole centre"),
                                Strings.t("Removes everything in the middle, drums included"),
                                Strings.t("Only the voice range, and only while someone sings"),
                                SoundExtras.singMethod == VocalCut.CENTRE,
                                on ->
                                        SoundExtras.setSingMethod(
                                                on ? VocalCut.CENTRE : VocalCut.VOICE_GATE));
                        paragraph(parent, Strings.t("The cleaner choice for older recordings."));
                        paragraph(
                                parent,
                                Strings.t(
                                        "Karaoke is ported from Auris by OreoMuncher45, a fork of"
                                                + " BitChord."));
                    });
        }
        if (SoundExtras.pitchApplied()) {
            feature(
                    "pitch",
                    Strings.t("Pitch"),
                    Strings.t("Change the key without changing speed"),
                    () -> SoundExtras.pitchEnabled,
                    SoundExtras::setPitchEnabled,
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Changes the key without changing the speed. BitChord’s"
                                                + " speed setting keeps the pitch you set here."));
                        slider(
                                parent,
                                Strings.t("Pitch"),
                                SoundExtras.MIN_PITCH,
                                SoundExtras.MAX_PITCH,
                                SoundExtras.pitchSemitones,
                                SoundExtras::semitones,
                                SoundExtras::setPitch);
                    });
        }
        if (SoundExtras.loudnessApplied()) {
            feature(
                    "loudness",
                    Strings.t("Loudness level"),
                    Strings.t("Set the target volume when loudness normalisation is on"),
                    () -> SoundExtras.loudnessEnabled,
                    SoundExtras::setLoudnessEnabled,
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "How loud songs play when BitChord’s loudness normalisation"
                                                + " is on. BitChord’s own level is -14 LUFS, as on"
                                                + " YouTube and Spotify. Lower is quieter with more"
                                                + " room for peaks; higher is louder."));
                        slider(
                                parent,
                                Strings.t("Level"),
                                SoundExtras.MIN_TARGET_LUFS,
                                SoundExtras.MAX_TARGET_LUFS,
                                SoundExtras.targetLufs,
                                SoundExtras::lufs,
                                SoundExtras::setTargetLufs);
                    });
        }
        if (SoundExtras.sleepFadeApplied()) {
            feature(
                    "sleep-fade",
                    Strings.t("Fade out at the sleep timer"),
                    Strings.t("Fade out before the sleep timer pauses"),
                    () -> SoundExtras.sleepFade,
                    SoundExtras::setSleepFade,
                    parent -> {
                        slider(
                                parent,
                                Strings.t("Fade length"),
                                SleepFade.MIN_SECONDS,
                                SleepFade.MAX_SECONDS,
                                SoundExtras.sleepFadeSeconds,
                                SoundExtras::seconds,
                                SoundExtras::setSleepFadeSeconds);
                        divider(parent);
                        option(
                                parent,
                                Strings.t("Fade the end of the last song"),
                                Strings.t("With “After this song”, its last seconds fade out"),
                                Strings.t("The last song plays to its end at full volume"),
                                SoundExtras.sleepFadeEnding,
                                SoundExtras::setSleepFadeEnding);
                        paragraph(
                                parent,
                                Strings.t(
                                        "A fade stands down as soon as you play, pause, seek or set"
                                            + " the timer again, and the volume goes back up for"
                                            + " the next time you play. The idea is from Prism by"
                                            + " Yigder."));
                    });
        }
        if (SoundExtras.batterySaverApplied()) {
            feature(
                    "battery-saver",
                    Strings.t("Battery saver"),
                    Strings.t("Use hardware decoding when available; effects and crossfade pause"),
                    () -> SoundExtras.batterySaver,
                    on -> {
                        SoundExtras.setBatterySaver(on);
                        handler.post(refreshStatus);
                    },
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Saves battery on long listens. While a song plays this"
                                                + " way, BitChord’s equaliser, loudness"
                                                + " normalisation, Karaoke, spatial audio and"
                                                + " crossfade don’t work on it. Android decides"
                                                + " which songs and devices can; the rest play as"
                                                + " before."));
                        batteryStatus = status(parent);
                    });
        }
        if (SoundExtras.losslessBluetoothApplied()) {
            feature(
                    "lossless-bluetooth",
                    Strings.t("Lossless addons over Bluetooth"),
                    Strings.t(
                            "Allow lossless source files over Bluetooth; its codec still applies"),
                    () -> SoundExtras.losslessBluetoothEnabled,
                    SoundExtras::setLosslessBluetoothEnabled,
                    this::bluetooth);
        }
        if (SoundExtras.headphoneApplied()) {
            feature(
                    "headphone-correction",
                    Strings.t("Headphone correction"),
                    Strings.t("Apply an AutoEq profile for your headphone model"),
                    () -> HeadphoneEq.enabled,
                    on -> {
                        HeadphoneEq.setEnabled(on);
                        SoundExtras.effectsChanged();
                    },
                    this::headphones);
        }
        if (SoundExtras.outputEqApplied()) {
            feature(
                    "output-eq",
                    Strings.t("Equaliser per output"),
                    Strings.t("Save a different equaliser for each audio output"),
                    OutputEq::enabled,
                    OutputEq::setEnabled,
                    this::outputEq);
        }
        if (SoundExtras.djApplied()) {
            feature(
                    "dj-mode",
                    Strings.t("DJ mode"),
                    Strings.t("Reorder queued songs using their musical analysis"),
                    () -> DjMode.enabled,
                    on -> {
                        DjMode.setEnabled(on);
                        SoundExtras.effectsChanged();
                        handler.post(refreshStatus);
                    },
                    this::dj);
        }
        if (SoundExtras.jamesDspApplied()) {
            feature(
                    "jamesdsp",
                    "JamesDSP",
                    Strings.t("Send audio effects to the installed JamesDSP app"),
                    () -> EffectsApps.useJamesDsp,
                    on -> {
                        boolean equaliserOn = on && EffectsApps.hostEqualiserOn(this);
                        EffectsApps.setUseJamesDsp(on);
                        if (equaliserOn) setHostBoolean("setEqualizerEnabled", false);
                        SoundExtras.effectsChanged();
                    },
                    this::jamesDsp);
        }
        if (SoundExtras.flacAddonApplied()) {
            feature(
                    "flac-audio",
                    "Flac Downloader Audio",
                    Strings.t(
                            "Download available FLAC or MP3 files; this is not a streaming source"),
                    FlacAddon::enabled,
                    FlacAddon::setEnabled,
                    parent ->
                            paragraph(
                                    parent,
                                    Strings.t(
                                            "Also available in Sources. Formats are requested from"
                                                    + " the service and may be unavailable. Choose"
                                                    + " another format explicitly if preparation"
                                                    + " fails.")));
        }
        if (SourceAutomix.applied()) {
            feature(
                    "source-automix",
                    Strings.t("Automix Editor"),
                    Strings.t("Extend BitChord’s AutoMix to the audio from supported sources"),
                    () -> hostBoolean("getSmartFadeEnabled"),
                    on -> setHostBoolean("setSmartFadeEnabled", on),
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "BitChord’s AutoMix analyses the audio that will play from"
                                            + " each source. It uses that analysis to choose entry"
                                            + " points and transitions. The transition editor"
                                            + " adjusts the blend, while your chosen playback speed"
                                            + " and pitch stay unchanged. Songs without suitable"
                                            + " analysis use a crossfade."));
                        navigation(
                                parent,
                                Strings.t("Automix Editor"),
                                () ->
                                        startActivity(
                                                new android.content.Intent(
                                                        this, TransitionEditorActivity.class)));
                    });
        }
        if (SoundExtras.mixedPlaylistsApplied()) {
            feature(
                    "mixed-playlists",
                    Strings.t("Mixed-source playlists"),
                    Strings.t("Keep files and streams together in device playlists"),
                    () -> MixedPlaylists.enabled,
                    MixedPlaylists::setEnabled,
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Use Add to playlist for a track, or New playlist in"
                                            + " Library. Device playlists keep file and source"
                                            + " identities; YouTube Music playlists still accept"
                                            + " YouTube tracks only."));
                        PlaylistRows.add(
                                parent,
                                style,
                                Strings.t("Playlist tools"),
                                Strings.t("Saved links, device playlists and imports"),
                                () ->
                                        startActivity(
                                                new android.content.Intent(
                                                        this, PlaylistToolsActivity.class)));
                    });
        }
        if (SoundExtras.steadyPlaybackApplied()) {
            feature(
                    "steady-playback",
                    Strings.t("Steady playback"),
                    Strings.t("Read further ahead and wait through a lost signal"),
                    () -> SteadyPlayback.enabled,
                    SteadyPlayback::setEnabled,
                    this::steadyPlayback);
        }
        if (SoundExtras.smartDownloadsApplied()) {
            feature(
                    "smart-downloads",
                    Strings.t("Smart downloads"),
                    Strings.t(
                            "Save frequently played songs from available download sources on"
                                    + " Wi-Fi"),
                    () -> SmartDownloads.enabled,
                    SmartDownloads::setEnabled,
                    this::smartDownloads);
        }
        if (SoundExtras.audioFocusApplied()) {
            feature(
                    "audio-focus",
                    Strings.t("Play alongside other apps"),
                    Strings.t("Play over other apps’ sound; calls still pause the music"),
                    () -> AudioFocus.alongside,
                    AudioFocus::setAlongside,
                    parent ->
                            paragraph(
                                    parent,
                                    Strings.t(
                                            "Normally BitChord pauses when another app plays"
                                                    + " sound. With this on, both play together."
                                                    + " Calls still pause the music, and it plays"
                                                    + " again when the call ends. Navigation"
                                                    + " prompts no longer lower its volume.")));
        }
        if (SoundExtras.videoSwitchApplied()) {
            feature(
                    "video-switch",
                    Strings.t("Song and video switch on the player"),
                    Strings.t(
                            "Switch YouTube songs and videos on the player, outside lyrics and"
                                    + " queue"),
                    () -> VideoSwitch.enabled,
                    VideoSwitch::setEnabled,
                    parent ->
                            paragraph(
                                    parent,
                                    Strings.t(
                                            "Tap the headphones for the song or the video frame"
                                                    + " for its music video. BitChord keeps the"
                                                    + " song’s place and, with Smart version"
                                                    + " alignment on, lines the two up in time."
                                                    + " Shown for YouTube Music songs, not for"
                                                    + " files, addons or Telegram.")));
        }
        if (SoundExtras.downloadQualityApplied()) {
            feature(
                    "download-quality",
                    Strings.t("Ask the quality for each download"),
                    Strings.t("Standard, High or Lossless, chosen per download"),
                    () -> DownloadChoice.ask,
                    DownloadChoice::setAsk,
                    parent ->
                            paragraph(
                                    parent,
                                    Strings.f(
                                                    "Last chosen: {0}",
                                                    DownloadChoice.label(DownloadChoice.last))
                                            + " · "
                                            + Strings.t(
                                                    "An album asked for in one go is asked about"
                                                            + " once. The idea is from Stash by"
                                                            + " Rawnald Clark.")));
        }
        showExpanded();
    }

    private void steadyPlayback(LinearLayout parent) {
        paragraph(
                parent,
                Strings.t(
                        "Choose how much audio to keep ready on Wi-Fi and mobile data. BitChord"
                                + " reads ahead in the current song and queue while memory allows."
                                + " The actual buffer can be smaller than your limit when playback"
                                + " or analysis needs more memory."));
        slider(
                parent,
                Strings.t("On Wi-Fi"),
                0,
                SteadyPlayback.WIFI_CHOICES.length - 1,
                indexOf(SteadyPlayback.WIFI_CHOICES, SteadyPlayback.wifiMb),
                index -> bufferLabel(SteadyPlayback.WIFI_CHOICES[index]),
                index -> SteadyPlayback.setWifiMb(SteadyPlayback.WIFI_CHOICES[index]));
        slider(
                parent,
                Strings.t("On mobile data"),
                0,
                SteadyPlayback.MOBILE_CHOICES.length - 1,
                indexOf(SteadyPlayback.MOBILE_CHOICES, SteadyPlayback.mobileMb),
                index -> bufferLabel(SteadyPlayback.MOBILE_CHOICES[index]),
                index -> SteadyPlayback.setMobileMb(SteadyPlayback.MOBILE_CHOICES[index]));
        paragraph(
                parent,
                Strings.t(
                        "Without a signal, a song that runs out of buffer waits for it to come"
                                + " back instead of stopping. The buffer is kept in memory, so"
                                + " the largest sizes suit phones with plenty of it. Mobile"
                                + " data is only used for songs you'd have played anyway,"
                                + " unless you skip them."));
    }

    private void smartDownloads(LinearLayout parent) {
        paragraph(
                parent,
                Strings.t(
                        "About twice a day on Wi-Fi, songs with at least three plays in the last"
                                + " three months are saved from available download sources using"
                                + " BitChord's Download quality. Accounts and source permissions"
                                + " still apply. They appear in Downloads; a song you delete there"
                                + " isn't downloaded again."));
        slider(
                parent,
                Strings.t("Songs to keep downloaded"),
                0,
                SmartDownloads.COUNT_CHOICES.length - 1,
                indexOf(SmartDownloads.COUNT_CHOICES, SmartDownloads.count),
                index -> String.valueOf(SmartDownloads.COUNT_CHOICES[index]),
                index -> SmartDownloads.setCount(SmartDownloads.COUNT_CHOICES[index]));
        option(
                parent,
                Strings.t("Only while charging"),
                Strings.t("Waits until the phone is charging, too"),
                Strings.t("Any time the phone is on Wi-Fi"),
                SmartDownloads.chargingOnly,
                SmartDownloads::setChargingOnly);
        TextView status = status(parent);
        Runnable show =
                () ->
                        status.setText(
                                Strings.f(
                                                "{0} songs remembered by Smart downloads",
                                                String.valueOf(SmartDownloads.queued().size()))
                                        + (SmartDownloads.lastResult.isEmpty()
                                                ? ""
                                                : " · " + SmartDownloads.lastResult));
        show.run();
        action(
                parent,
                Strings.t("Download now"),
                () -> {
                    Context context = getApplicationContext();
                    worker.execute(
                            () -> {
                                SmartDownloads.run(context);
                                handler.post(show);
                            });
                    Toast.makeText(
                                    this,
                                    Strings.t("Looking for songs to download…"),
                                    Toast.LENGTH_SHORT)
                            .show();
                });
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return 0;
    }

    /** A buffer size with roughly how much lossless music it holds (16-bit FLAC, ~6.5 MB/min). */
    static String bufferLabel(int mb) {
        int minutes = Math.max(1, Math.round(mb / 6.5f));
        return Strings.f(
                "{0} MB, about {1} min of lossless", String.valueOf(mb), String.valueOf(minutes));
    }

    private static boolean optionalInstalled(String type) {
        try {
            return Boolean.TRUE.equals(invoke(type, "installed", new Class<?>[] {}));
        } catch (Exception | LinkageError absent) {
            return false;
        }
    }

    private void downloadFeature(String id, String title, String summary, Details details) {
        feature(
                id,
                title,
                summary,
                () ->
                        (Boolean)
                                invoke(
                                        "app.bitchord.soulseek.DownloadSettings",
                                        "peekEnabled",
                                        new Class<?>[] {Context.class, String.class},
                                        this,
                                        id),
                on ->
                        invoke(
                                "app.bitchord.soulseek.DownloadSettings",
                                "setEnabled",
                                new Class<?>[] {Context.class, String.class, boolean.class},
                                this,
                                id,
                                on),
                details);
    }

    private void copyActions(LinearLayout parent, String feature) {
        navigation(
                parent, Strings.t("Where copies go"), () -> openDownload(feature, "destinations"));
        navigation(
                parent, Strings.t("Formats, best first"), () -> openDownload(feature, "formats"));
    }

    private void openDownload(String id, String action) {
        try {
            invoke(
                    "app.bitchord.soulseek.DownloadSettings",
                    "open",
                    new Class<?>[] {Context.class, String.class, String.class},
                    this,
                    id,
                    action);
        } catch (Exception error) {
            unavailable();
        }
    }

    private static boolean exists(String name) {
        try {
            Class.forName(name, false, ExtrasActivity.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    private static Object invoke(String name, String method, Class<?>[] types, Object... args)
            throws Exception {
        try {
            return Class.forName(name).getMethod(method, types).invoke(null, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new Exception("Patch setting unavailable", cause);
        }
    }

    private void unavailable() {
        Toast.makeText(
                        this,
                        Strings.t("This setting could not be changed. Try again."),
                        Toast.LENGTH_LONG)
                .show();
    }

    private void toolbar() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8));
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title =
                style.text(Strings.t("Patches & tweaks"), 26, style.foreground, style.heavy);
        title.setTag("patch-page-title");
        TelegramGlyph tweaks = new TelegramGlyph(style, "tweaks", style.secondary);
        tweaks.setBounds(0, 0, style.dp(24), style.dp(24));
        title.setCompoundDrawablesRelative(tweaks, null, null, null);
        title.setCompoundDrawablePadding(style.dp(10));
        title.setPadding(style.dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.FrameLayout downloads = new android.widget.FrameLayout(this);
        downloads.setTag("patch-download-settings");
        downloads.setContentDescription(Strings.t("Download settings"));
        downloads.setFocusable(true);
        downloads.setClickable(true);
        downloads.setBackground(style.shape(style.field, 24, false));
        android.widget.FrameLayout.LayoutParams iconPlace =
                new android.widget.FrameLayout.LayoutParams(
                        style.dp(24), style.dp(28), Gravity.CENTER);
        downloads.addView(new PatchIcon("download-quality"), iconPlace);
        downloads.setOnClickListener(
                v ->
                        startActivity(
                                new android.content.Intent(this, DownloadsSettingsActivity.class)));
        header.addView(downloads, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        root.addView(header);
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(style.shape(style.card, 24, false));
        field.setPadding(style.dp(14), 0, 0, 0);
        field.addView(
                new PatchIcon("search"), new LinearLayout.LayoutParams(style.dp(20), style.dp(24)));
        search = new EditText(this);
        search.setId(0x70ff01);
        search.setTag("patch-search");
        search.setHint(Strings.t("Search patches"));
        search.setContentDescription(Strings.t("Search patches"));
        search.setTextSize(15);
        search.setTypeface(style.regular);
        search.setTextColor(style.foreground);
        search.setHintTextColor(style.secondary);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(256)});
        search.setPadding(style.dp(10), 0, 0, 0);
        search.setText(query);
        field.addView(search, new LinearLayout.LayoutParams(0, style.dp(48), 1));
        clearSearch = new ImageButton(this);
        clearSearch.setTag("patch-clear-search");
        clearSearch.setContentDescription(Strings.t("Clear search"));
        clearSearch.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        clearSearch.setImageTintList(ColorStateList.valueOf(style.secondary));
        clearSearch.setBackground(null);
        clearSearch.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        clearSearch.setOnClickListener(
                v -> {
                    search.setText("");
                    search.requestFocus();
                });
        field.addView(clearSearch, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        LinearLayout.LayoutParams fieldSpace = new LinearLayout.LayoutParams(-1, -2);
        fieldSpace.setMargins(style.dp(16), 0, style.dp(16), style.dp(12));
        root.addView(field, fieldSpace);
        selector = new ModeSelector();
        LinearLayout.LayoutParams selectorSpace = new LinearLayout.LayoutParams(-1, -2);
        selectorSpace.setMargins(style.dp(16), 0, style.dp(16), style.dp(6));
        root.addView(selector, selectorSpace);
        HorizontalScrollView categories = new HorizontalScrollView(this);
        categories.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(style.dp(16), 0, style.dp(16), 0);
        for (String id : new String[] {"all", "sources", "playback", "audio", "library"}) {
            TextView chip = style.text(categoryLabel(id), 14, style.secondary, style.medium);
            chip.setTag("patch-category:" + id);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8));
            chip.setMinHeight(style.dp(48));
            chip.setOnClickListener(
                    v -> {
                        category = id;
                        applyFilters(true);
                    });
            chip.setFocusable(true);
            chip.setAccessibilityDelegate(
                    new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(
                                View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName("android.widget.RadioButton");
                            info.setCheckable(true);
                            info.setChecked(chip.isSelected());
                        }
                    });
            row.addView(chip);
            categoryButtons.put(id, chip);
        }
        categories.addView(row);
        root.addView(categories);
        if (exists("app.bitchord.telegram.StorageSenseActivity")) {
            LinearLayout utilities = new LinearLayout(this);
            utilities.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams utilitySpace = new LinearLayout.LayoutParams(-1, -2);
            utilitySpace.setMargins(style.dp(16), style.dp(6), style.dp(16), style.dp(4));
            root.addView(utilities, utilitySpace);
            storageShortcut =
                    PlaylistRows.add(
                            utilities,
                            style,
                            Strings.t("Storage Sense"),
                            Strings.t("Review cached audio, saved files and cleanup rules"),
                            () ->
                                    startActivity(
                                            new android.content.Intent(
                                                    this, StorageSenseActivity.class)));
            storageShortcut.setTag("patch-storage-sense");
            android.widget.ImageView storageIcon = new android.widget.ImageView(this);
            storageIcon.setImageDrawable(new TelegramGlyph(style, "storage", style.secondary));
            storageIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconSpace =
                    new LinearLayout.LayoutParams(style.dp(24), style.dp(28));
            iconSpace.setMarginEnd(style.dp(12));
            ((LinearLayout) storageShortcut).addView(storageIcon, 0, iconSpace);
            utilities.setBackground(style.shape(style.card, 16, false));
        }
        if (SoundExtras.diagnosticsApplied()) {
            LinearLayout tools = new LinearLayout(this);
            tools.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
            space.setMargins(style.dp(16), style.dp(6), style.dp(16), style.dp(4));
            tools.setBackground(style.shape(style.card, 16, false));
            root.addView(tools, space);
            View entry =
                    PlaylistRows.add(
                            tools,
                            style,
                            Strings.t("Diagnostics"),
                            Strings.t("Record app and patch events to diagnose a problem"),
                            () ->
                                    startActivity(
                                            new android.content.Intent(
                                                    this, DiagnosticsActivity.class)));
            entry.setTag("patch-diagnostics");
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(new TelegramGlyph(style, "diagnostics", style.secondary));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconSpace =
                    new LinearLayout.LayoutParams(style.dp(24), style.dp(28));
            iconSpace.setMarginEnd(style.dp(12));
            ((LinearLayout) entry).addView(icon, 0, iconSpace);
        }
        counts = style.text("", 13, style.secondary, style.regular);
        counts.setTag("patch-counts");
        counts.setPadding(style.dp(24), style.dp(4), style.dp(24), 0);
        root.addView(counts);
        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        query = s.toString();
                        clearSearch.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                        applyFilters(true);
                    }

                    public void afterTextChanged(Editable text) {}
                });
    }

    private static String searchable(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private void applyFilters(boolean remember) {
        if (building || body == null || counts == null) return;
        String key = category + "|" + enabledOnly + "|" + query;
        if (remember && !filterKey.equals(key)) {
            scrollPositions.put(filterKey, scroll.getScrollY());
            while (scrollPositions.size() > 64)
                scrollPositions.remove(scrollPositions.keySet().iterator().next());
            int restore =
                    scrollPositions.containsKey(key)
                            ? scrollPositions.get(key)
                            : scroll.getScrollY();
            scroll.post(() -> scroll.scrollTo(0, restore));
        }
        filterKey = key;
        String[] terms = searchable(query.trim()).split("\\s+");
        int enabled = 0, shown = 0;
        for (Group group : groups.values()) {
            group.card.setVisibility(View.GONE);
            group.heading.setVisibility(View.GONE);
        }
        java.util.HashSet<String> first = new java.util.HashSet<>();
        for (Feature feature : features.values()) {
            if (feature.enabled) enabled++;
            boolean visible =
                    (!enabledOnly || feature.enabled)
                            && (category.equals("all") || feature.category.equals(category));
            String haystack =
                    searchable(
                            feature.title
                                    + " "
                                    + feature.summary
                                    + " "
                                    + categoryLabel(feature.category)
                                    + " "
                                    + feature.category
                                    + " "
                                    + feature.id);
            for (String term : terms)
                if (!term.isEmpty() && !haystack.contains(term)) visible = false;
            feature.card.setVisibility(visible ? View.VISIBLE : View.GONE);
            feature.divider.setVisibility(
                    visible && !first.add(feature.category) ? View.VISIBLE : View.GONE);
            if (visible) {
                shown++;
                Group group = groups.get(feature.category);
                group.card.setVisibility(View.VISIBLE);
                group.heading.setVisibility(View.VISIBLE);
            }
        }
        for (Map.Entry<String, TextView> entry : categoryButtons.entrySet()) {
            boolean active = entry.getKey().equals(category);
            entry.getValue().setSelected(active);
            entry.getValue().setTextColor(active ? style.foreground : style.secondary);
            entry.getValue().setBackground(active ? style.shape(style.card, 24, false) : null);
            // Changing backgrounds can replace TextView padding with drawable padding.
            entry.getValue().setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8));
        }
        counts.setText(
                Strings.f("{0} of {1} enabled · {2} shown", enabled, features.size(), shown));
        boolean storageVisible =
                storageShortcut != null
                        && !enabledOnly
                        && (category.equals("all") || category.equals("library"));
        String storageText =
                searchable(
                        Strings.t("Storage Sense")
                                + " "
                                + Strings.t("Review cached audio, saved files and cleanup rules")
                                + " "
                                + categoryLabel("library"));
        for (String term : terms)
            if (!term.isEmpty() && !storageText.contains(term)) storageVisible = false;
        if (storageShortcut != null)
            ((View) storageShortcut.getParent())
                    .setVisibility(storageVisible ? View.VISIBLE : View.GONE);
        if (empty != null) {
            empty.setVisibility(shown == 0 && !storageVisible ? View.VISIBLE : View.GONE);
            empty.setText(
                    features.isEmpty()
                            ? Strings.t("No optional patches installed.")
                            : !query.trim().isEmpty()
                                    ? Strings.t(
                                            "No matching patches. Try another name or category.")
                                    : enabledOnly
                                            ? Strings.t(
                                                    "No enabled patches in this category. Choose"
                                                            + " All controls to see available"
                                                            + " features.")
                                            : Strings.t("No installed patches in this category."));
        }
    }

    private boolean soulseekEnabled() {
        Feature soulseek = features.get("soulseek");
        return soulseek != null && soulseek.enabled;
    }

    private boolean sourceEnabled(String id) throws Exception {
        Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
        Object config = registry.getMethod("config", String.class).invoke(null, id);
        return config != null
                && Boolean.TRUE.equals(config.getClass().getMethod("getEnabled").invoke(config));
    }

    private void setSourceEnabled(String id, boolean on) throws Exception {
        Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
        if (registry.getMethod("config", String.class).invoke(null, id) == null)
            throw new IllegalStateException("Source is not registered");
        registry.getMethod("setEnabled", String.class, boolean.class).invoke(null, id, on);
    }

    private boolean hostBoolean(String getter) throws Exception {
        Object flow =
                Class.forName("com.music.bitchord.data.settings.AppSettings")
                        .getMethod(getter)
                        .invoke(null);
        return Boolean.TRUE.equals(
                Class.forName("kotlinx.coroutines.flow.StateFlow")
                        .getMethod("getValue")
                        .invoke(flow));
    }

    private void setHostBoolean(String setter, boolean value) throws Exception {
        Class.forName("com.music.bitchord.data.settings.AppSettings")
                .getMethod(setter, boolean.class)
                .invoke(null, value);
    }

    private void change(Feature feature, boolean on, UndoChange restoring) {
        if (feature.busy) return;
        if (on && feature.id.equals("streamseek-preview") && !soulseekEnabled()) {
            feature.sync();
            Toast.makeText(this, Strings.t("Enable Soulseek downloads first."), Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        final boolean before;
        try {
            before = feature.state.get();
        } catch (Exception | LinkageError unavailable) {
            feature.sync();
            unavailable();
            return;
        }
        if (before == on) {
            feature.sync();
            return;
        }
        final boolean equalizerBefore = EffectsApps.hostEqualiserOn(this);
        final long sequence = ++changes;
        clearUndo();
        feature.busy = true;
        feature.sync();
        Runnable apply =
                () -> {
                    boolean success;
                    try {
                        if (feature.state.get() != before)
                            throw new IllegalStateException("Setting changed");
                        feature.setting.set(on);
                        if (restoring != null && restoring.restoreEqualizer)
                            setHostBoolean("setEqualizerEnabled", true);
                        success = feature.state.get() == on;
                    } catch (Exception | LinkageError unavailable) {
                        success = false;
                    }
                    final boolean changed = success;
                    Runnable complete =
                            () -> {
                                if (destroyed) return;
                                feature.busy = false;
                                feature.revision++;
                                for (Feature entry : features.values()) entry.sync();
                                showExpanded();
                                applyFilters(false);
                                if (sequence != changes || !foreground) return;
                                if (!changed) {
                                    unavailable();
                                    return;
                                }
                                if (restoring == null) {
                                    boolean restoreEqualizer =
                                            feature.id.equals("jamesdsp")
                                                    && !before
                                                    && on
                                                    && equalizerBefore
                                                    && !EffectsApps.hostEqualiserOn(this);
                                    showUndo(
                                            new UndoChange(
                                                    feature,
                                                    before,
                                                    on,
                                                    feature.revision,
                                                    equalizerRevision,
                                                    restoreEqualizer));
                                } else {
                                    root.announceForAccessibility(Strings.t("Change undone"));
                                }
                            };
                    if (feature.asynchronous) handler.post(complete);
                    else complete.run();
                };
        if (feature.asynchronous) worker.execute(apply);
        else apply.run();
    }

    private void showUndo(UndoChange change) {
        undo = change;
        feedbackText.setText(
                Strings.f(change.after ? "{0} enabled" : "{0} disabled", change.feature.title));
        feedback.setVisibility(View.VISIBLE);
        int duration = 8_000;
        AccessibilityManager accessibility =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 29 && accessibility != null)
            duration =
                    accessibility.getRecommendedTimeoutMillis(
                            duration,
                            AccessibilityManager.FLAG_CONTENT_TEXT
                                    | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        handler.postDelayed(dismissUndo, duration);
    }

    private void clearUndo() {
        handler.removeCallbacks(dismissUndo);
        undo = null;
        if (feedback != null) feedback.setVisibility(View.GONE);
    }

    private void undoChange() {
        UndoChange change = undo;
        if (change == null) return;
        try {
            if (change.feature.revision != change.revision
                    || change.feature.state.get() != change.after
                    || (change.feature.id.equals("jamesdsp")
                            && equalizerRevision != change.equalizerRevision)) {
                clearUndo();
                root.announceForAccessibility(Strings.t("Setting changed. Nothing to undo."));
                return;
            }
            change(change.feature, change.before, change);
        } catch (Exception | LinkageError unavailable) {
            clearUndo();
            unavailable();
        }
    }

    private final class UndoChange {
        final Feature feature;
        final boolean before, after, restoreEqualizer;
        final long revision, equalizerRevision;

        UndoChange(
                Feature feature,
                boolean before,
                boolean after,
                long revision,
                long equalizerRevision,
                boolean restoreEqualizer) {
            this.feature = feature;
            this.before = before;
            this.after = after;
            this.revision = revision;
            this.equalizerRevision = equalizerRevision;
            this.restoreEqualizer = restoreEqualizer;
        }
    }

    private final class ModeSelector extends FrameLayout {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextView all, enabled;
        float position = enabledOnly ? 1f : 0f;
        android.animation.ValueAnimator animation;

        ModeSelector() {
            super(ExtrasActivity.this);
            setWillNotDraw(false);
            setMinimumHeight(style.dp(48));
            setBackground(style.shape(style.card, 24, false));
            LinearLayout row = new LinearLayout(ExtrasActivity.this);
            all = tab(Strings.t("All controls"), false);
            enabled = tab(Strings.t("Enabled"), true);
            row.addView(all, new LinearLayout.LayoutParams(0, -1, 1));
            row.addView(enabled, new LinearLayout.LayoutParams(0, -1, 1));
            addView(row, new FrameLayout.LayoutParams(-1, style.dp(48)));
        }

        TextView tab(String label, boolean mode) {
            TextView view = style.text(label, 14, style.foreground, style.medium);
            view.setTag(mode ? "patch-mode:enabled" : "patch-mode:all");
            view.setGravity(Gravity.CENTER);
            view.setFocusable(true);
            view.setSelected(enabledOnly == mode);
            view.setOnClickListener(
                    v -> {
                        enabledOnly = mode;
                        all.setSelected(!mode);
                        enabled.setSelected(mode);
                        if (animation != null) animation.cancel();
                        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                            animation =
                                    android.animation.ValueAnimator.ofFloat(
                                            position, mode ? 1f : 0f);
                            animation.setDuration(160);
                            animation.addUpdateListener(
                                    a -> {
                                        position = (float) a.getAnimatedValue();
                                        invalidate();
                                    });
                            animation.start();
                        } else {
                            position = mode ? 1f : 0f;
                            invalidate();
                        }
                        applyFilters(true);
                    });
            view.setAccessibilityDelegate(
                    new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(
                                View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName("android.widget.RadioButton");
                            info.setCheckable(true);
                            info.setChecked(view.isSelected());
                        }
                    });
            return view;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float offset =
                    getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1f - position : position;
            float width = (getWidth() - style.dp(8)) / 2f;
            float left = style.dp(4) + offset * width;
            paint.setColor(style.field);
            canvas.drawRoundRect(
                    left,
                    style.dp(4),
                    left + width,
                    getHeight() - style.dp(4),
                    style.dp(22),
                    style.dp(22),
                    paint);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animation != null) animation.cancel();
            super.onDetachedFromWindow();
        }
    }

    private final class PatchIcon extends View {
        final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        final String kind;

        PatchIcon(String kind) {
            super(ExtrasActivity.this);
            this.kind = kind;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            float size = Math.min(getWidth(), getHeight());
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            ink.setColor(style.secondary);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(1.7f);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            switch (kind) {
                case "search":
                    canvas.drawCircle(10, 10, 6.5f, ink);
                    line(canvas, 15, 15, 21, 21);
                    break;
                case "sleep-fade":
                    path(canvas, 12, 3, 8, 4, 6, 8, 7, 12, 11, 14, 15, 13);
                    path(canvas, 12, 3, 11, 7, 13, 10, 17, 10);
                    path(canvas, 17, 2, 21, 2, 17, 6, 21, 6);
                    path(canvas, 3, 17, 6, 17, 10, 14, 10, 23, 6, 20, 3, 20, 3, 17);
                    path(canvas, 14, 16, 21, 22, 17, 22);
                    break;
                case "battery-saver":
                    canvas.drawRoundRect(5, 5, 19, 22, 2, 2, ink);
                    path(canvas, 9, 5, 9, 2, 15, 2, 15, 5);
                    path(canvas, 13, 8, 9, 14, 13, 14, 11, 19, 16, 12, 12, 12);
                    break;
                case "dj-mode":
                    canvas.drawRoundRect(2, 4, 22, 21, 2, 2, ink);
                    canvas.drawCircle(10, 12, 6, ink);
                    canvas.drawCircle(10, 12, 1.5f, ink);
                    path(canvas, 12, 15, 16, 10, 16, 6, 19, 6);
                    line(canvas, 19, 12, 19, 18);
                    line(canvas, 17.5f, 15, 20.5f, 15);
                    break;
                case "source-automix":
                    path(canvas, 2, 5, 5, 5, 18, 19, 22, 19);
                    path(canvas, 2, 19, 5, 19, 18, 5, 22, 5);
                    path(canvas, 19, 2, 22, 5, 19, 8);
                    path(canvas, 19, 16, 22, 19, 19, 22);
                    line(canvas, 2, 10, 2, 14);
                    line(canvas, 5, 9, 5, 15);
                    break;
                case "video-switch":
                    path(canvas, 4, 10, 4, 3, 12, 1, 12, 8);
                    line(canvas, 4, 6, 12, 4);
                    canvas.drawOval(1, 9, 4, 12, ink);
                    canvas.drawOval(9, 7, 12, 10, ink);
                    canvas.drawRoundRect(9, 14, 23, 23, 1, 1, ink);
                    path(canvas, 14, 16, 14, 21, 18, 18.5f, 14, 16);
                    path(canvas, 3, 16, 3, 20, 6, 20, 4, 18);
                    path(canvas, 21, 10, 21, 6, 17, 6, 19, 8);
                    break;
                case "telegram":
                    path(canvas, 2, 10, 22, 3, 16, 22, 10, 15, 2, 10);
                    line(canvas, 10, 15, 22, 3);
                    break;
                case "soulseek":
                    canvas.drawCircle(12, 5, 3, ink);
                    canvas.drawCircle(4, 18, 3, ink);
                    canvas.drawCircle(20, 18, 3, ink);
                    line(canvas, 10, 8, 5, 15);
                    line(canvas, 14, 8, 19, 15);
                    line(canvas, 7, 18, 17, 18);
                    break;
                case "peer-downloads":
                    path(canvas, 2, 16, 2, 12, 9, 12, 9, 19, 2, 19, 2, 16);
                    canvas.drawRoundRect(15, 2, 22, 9, 1, 1, ink);
                    path(canvas, 5, 8, 5, 5, 11, 5, 9, 3);
                    path(canvas, 19, 13, 19, 17, 13, 17, 15, 19);
                    break;
                case "debrid":
                    path(
                            canvas, 5, 17, 4, 17, 2, 15, 2, 12, 4, 10, 7, 10, 8, 6, 12, 4, 16, 6,
                            17, 9, 20, 9, 22, 12, 22, 15, 20, 17, 19, 17);
                    path(canvas, 12, 11, 12, 22, 8, 18);
                    line(canvas, 12, 22, 16, 18);
                    break;
                case "streamseek-preview":
                    path(canvas, 3, 6, 3, 18, 12, 12, 3, 6);
                    line(canvas, 16, 8, 16, 16);
                    line(canvas, 20, 4, 20, 20);
                    break;
                case "flac-audio":
                    path(canvas, 5, 2, 14, 2, 20, 8, 20, 22, 5, 22, 5, 2);
                    path(canvas, 14, 2, 14, 8, 20, 8);
                    path(canvas, 8, 15, 10, 15, 11, 12, 13, 18, 15, 14, 17, 14);
                    break;
                case "download-quality":
                    path(canvas, 5, 3, 5, 15, 1, 11);
                    line(canvas, 5, 15, 9, 11);
                    canvas.drawCircle(17, 10, 5, ink);
                    path(canvas, 14, 14, 13, 22, 17, 20, 21, 22, 20, 14);
                    break;
                case "sing":
                    canvas.drawRoundRect(9, 2, 15, 14, 3, 3, ink);
                    path(canvas, 5, 10, 5, 13, 7, 17, 12, 19, 17, 17, 19, 13, 19, 10);
                    line(canvas, 12, 19, 12, 23);
                    line(canvas, 8, 23, 16, 23);
                    break;
                case "pitch":
                    path(canvas, 4, 17, 4, 5, 13, 3, 13, 14);
                    canvas.drawOval(0.5f, 16, 4, 20, ink);
                    canvas.drawOval(9.5f, 13, 13, 17, ink);
                    path(canvas, 19, 20, 19, 8, 16, 11);
                    line(canvas, 19, 8, 22, 11);
                    break;
                case "loudness":
                    path(canvas, 2, 9, 6, 9, 11, 5, 11, 20, 6, 16, 2, 16, 2, 9);
                    canvas.drawArc(9, 5, 20, 20, -55, 110, false, ink);
                    canvas.drawArc(10, 1, 24, 24, -55, 110, false, ink);
                    break;
                case "lossless-bluetooth":
                    path(canvas, 7, 5, 18, 17, 12, 22, 12, 2, 18, 7, 7, 19);
                    line(canvas, 21, 10, 21, 14);
                    line(canvas, 3, 10, 3, 14);
                    break;
                case "headphone-correction":
                    canvas.drawArc(3, 2, 21, 20, 180, 180, false, ink);
                    canvas.drawRoundRect(2, 11, 6, 20, 2, 2, ink);
                    canvas.drawRoundRect(18, 11, 22, 20, 2, 2, ink);
                    line(canvas, 9, 12, 9, 18);
                    line(canvas, 12, 10, 12, 20);
                    line(canvas, 15, 12, 15, 18);
                    break;
                case "smart-downloads":
                    // A download arrow over a star.
                    line(canvas, 12, 2, 12, 12);
                    path(canvas, 8, 8, 12, 12, 16, 8);
                    path(
                            canvas, 12, 15, 13.5f, 18, 16.5f, 18.5f, 14.2f, 20.5f, 15, 23.5f, 12,
                            22, 9, 23.5f, 9.8f, 20.5f, 7.5f, 18.5f, 10.5f, 18, 12, 15);
                    line(canvas, 3, 14, 3, 20);
                    line(canvas, 21, 14, 21, 20);
                    break;
                case "steady-playback":
                    // A signal arc above a long buffer bar.
                    canvas.drawRoundRect(2, 15, 22, 21, 2, 2, ink);
                    line(canvas, 5, 18, 15, 18);
                    path(canvas, 7, 9, 12, 6, 17, 9);
                    path(canvas, 4, 6, 12, 1, 20, 6);
                    canvas.drawCircle(12, 11, 1, ink);
                    break;
                case "audio-focus":
                    // Two overlapping speakers: two sounds at once.
                    path(canvas, 2, 9, 5, 9, 9, 5, 9, 19, 5, 15, 2, 15, 2, 9);
                    path(canvas, 12, 8, 14, 10, 14, 14, 12, 16);
                    path(canvas, 16, 5, 19, 8, 19, 16, 16, 19);
                    path(canvas, 20, 3, 23, 7, 23, 17, 20, 21);
                    break;
                case "output-eq":
                    for (int x : new int[] {4, 12, 20}) {
                        float y = x == 12 ? 15 : 8;
                        line(canvas, x, 3, x, y - 3);
                        line(canvas, x, y + 3, x, 21);
                        canvas.drawCircle(x, y, 3, ink);
                    }
                    break;
                case "jamesdsp":
                    canvas.drawRoundRect(5, 5, 19, 19, 2, 2, ink);
                    path(canvas, 8, 12, 10, 12, 11, 9, 13, 15, 14, 12, 16, 12);
                    for (int x : new int[] {8, 12, 16}) {
                        line(canvas, x, 2, x, 5);
                        line(canvas, x, 19, x, 22);
                        line(canvas, 2, x, 5, x);
                        line(canvas, 19, x, 22, x);
                    }
                    break;
                case "mixed-playlists":
                    canvas.drawRoundRect(4, 7, 21, 22, 2, 2, ink);
                    line(canvas, 3, 2, 17, 2);
                    line(canvas, 1, 5, 18, 5);
                    line(canvas, 8, 12, 17, 12);
                    line(canvas, 8, 17, 15, 17);
                    break;
            }
            canvas.restore();
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
            canvas.drawLine(x1, y1, x2, y2, ink);
        }

        private void path(Canvas canvas, float... points) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, ink);
        }
    }

    private static String categoryOf(String id) {
        switch (id) {
            case "telegram":
            case "soulseek":
            case "peer-downloads":
            case "debrid":
            case "streamseek-preview":
            case "flac-audio":
            case "download-quality":
                return "sources";
            case "source-automix":
            case "dj-mode":
            case "sleep-fade":
            case "battery-saver":
            case "video-switch":
            case "audio-focus":
            case "steady-playback":
                return "playback";
            case "smart-downloads":
                return "library";
            case "mixed-playlists":
                return "library";
            default:
                return "audio";
        }
    }

    private String categoryLabel(String id) {
        switch (id) {
            case "sources":
                return Strings.t("Sources");
            case "playback":
                return Strings.t("Playback");
            case "audio":
                return Strings.t("Audio");
            case "library":
                return Strings.t("Library");
            default:
                return Strings.t("All");
        }
    }

    private Group group(String key) {
        Group group = groups.get(key);
        if (group != null) return group;
        group = new Group();
        group.heading = style.text(categoryLabel(key), 13, style.secondary, style.semibold);
        group.heading.setPadding(style.dp(32), style.dp(20), style.dp(20), style.dp(8));
        body.addView(group.heading);
        group.card = new LinearLayout(this);
        group.card.setOrientation(LinearLayout.VERTICAL);
        group.card.setBackground(style.shape(style.card, 16, false));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.setMargins(style.dp(16), 0, style.dp(16), style.dp(4));
        body.addView(group.card, space);
        groups.put(key, group);
        return group;
    }

    /**
     * Register only installed controls. Their backend is never created by filtering or searching.
     */
    void feature(
            String id,
            String title,
            String summary,
            State state,
            Setting setting,
            Details details) {
        Feature feature = new Feature(id, title, summary, state, setting, details);
        features.put(id, feature);
        Group group = group(feature.category);
        View line = new View(this);
        line.setBackgroundColor(style.outline);
        LinearLayout.LayoutParams inset =
                new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f)));
        inset.setMarginStart(style.dp(54));
        inset.setMarginEnd(style.dp(16));
        group.card.addView(line, inset);
        feature.divider = line;
        LinearLayout card = new LinearLayout(this);
        feature.card = card;
        card.setTag("patch:" + id);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8));
        group.card.addView(card, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(style.dp(72));
        PatchIcon icon = new PatchIcon(id);
        icon.setTag("patch-icon:" + id);
        header.addView(icon, new LinearLayout.LayoutParams(style.dp(24), style.dp(28)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(style.dp(10), 0, style.dp(6), 0);
        labels.addView(style.text(title, 16, style.foreground, style.medium));
        feature.subtitle = style.text(summary, 13, style.secondary, style.regular);
        feature.subtitle.setTag("summary:" + id);
        feature.subtitle.setPadding(0, style.dp(3), 0, 0);
        labels.addView(feature.subtitle);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton gear = style.settingsButton();
        feature.gear = gear;
        gear.setTag("settings:" + id);
        gear.setOnClickListener(
                view -> {
                    if (feature.enabled && !feature.busy && feature.configurable) {
                        expanded = id;
                        showExpanded();
                    }
                });
        header.addView(gear, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TelegramSwitch toggle = new TelegramSwitch(this, style);
        feature.toggle = toggle;
        toggle.setTag("enable:" + id);
        toggle.setContentDescription(Strings.f("Enable {0}", title));
        FrameLayout touch = new FrameLayout(this);
        touch.addView(
                toggle, new FrameLayout.LayoutParams(style.dp(64), style.dp(48), Gravity.CENTER));
        touch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(touch, new LinearLayout.LayoutParams(style.dp(64), style.dp(48)));
        card.addView(header);
        feature.details = new LinearLayout(this);
        feature.details.setTag("details:" + id);
        feature.details.setOrientation(LinearLayout.VERTICAL);
        feature.details.setPadding(style.dp(6), style.dp(12), style.dp(4), style.dp(8));
        feature.details.setVisibility(View.GONE);
        card.addView(feature.details);
        if (id.equals("flac-audio")) {
            TextView provider =
                    style.button(
                            Strings.t("Provider verification"),
                            false,
                            () -> FlacAddon.verify(this));
            provider.setTag("flac-provider-verification");
            card.addView(provider, new LinearLayout.LayoutParams(-1, -2));
        }
        toggle.setListener((button, on) -> change(feature, on, null));
        feature.sync();
        if (!building) applyFilters(false);
    }

    private Feature containing(View view) {
        for (View at = view; at != null; ) {
            for (Feature feature : features.values()) if (feature.card == at) return feature;
            Object parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private void showExpanded() {
        Feature selected = features.get(expanded);
        if (selected == null || !selected.enabled || !selected.configurable) expanded = null;
        for (Feature feature : features.values()) {
            boolean open = feature.enabled && feature.configurable && feature.id.equals(expanded);
            if (open && !feature.built) {
                feature.details.removeAllViews();
                feature.builder.build(feature.details);
                TextView done =
                        style.button(
                                Strings.t("Done"),
                                false,
                                () -> {
                                    expanded = null;
                                    showExpanded();
                                    feature.gear.requestFocus();
                                });
                done.setTag("done:" + feature.id);
                LinearLayout.LayoutParams doneSpace = new LinearLayout.LayoutParams(-1, -2);
                doneSpace.topMargin = style.dp(12);
                feature.details.addView(done, doneSpace);
                feature.built = true;
            }
            feature.details.setVisibility(open ? View.VISIBLE : View.GONE);
            feature.gear.setContentDescription(Strings.f("Settings for {0}", feature.title));
        }
        handler.removeCallbacks(refreshStatus);
        handler.post(refreshStatus);
    }

    private static final class Group {
        TextView heading;
        LinearLayout card;
    }

    private final class Feature {
        final String id, title, summary, category;
        final State state;
        final Setting setting;
        final Details builder;
        final boolean configurable, asynchronous;
        LinearLayout card, details;
        TextView subtitle;
        View divider;
        TelegramSwitch toggle;
        ImageButton gear;
        boolean enabled, busy, built, available = true;
        long revision;

        Feature(
                String id,
                String title,
                String summary,
                State state,
                Setting setting,
                Details builder) {
            configurable =
                    !id.equals("battery-saver")
                            && !id.equals("download-quality")
                            && !id.equals("video-switch")
                            && !id.equals("audio-focus")
                            && !id.equals("flac-audio")
                            && !id.equals("streamseek-preview");
            asynchronous =
                    id.equals("soulseek")
                            || id.equals("peer-downloads")
                            || id.equals("debrid")
                            || id.equals("streamseek-preview")
                            || id.equals("telegram")
                            || id.equals("flac-audio");
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.category = categoryOf(id);
            this.state = state;
            this.setting = setting;
            this.builder = builder;
        }

        void sync() {
            try {
                enabled = state.get();
                available = true;
            } catch (Exception | LinkageError error) {
                available = false;
            }
            View provider =
                    card == null ? null : card.findViewWithTag("flac-provider-verification");
            if (provider != null) provider.setVisibility(enabled ? View.VISIBLE : View.GONE);
            toggle.setChecked(enabled);
            toggle.setEnabled(available && !busy);
            gear.setEnabled(enabled && configurable && !busy);
            gear.setVisibility(enabled && configurable ? View.VISIBLE : View.GONE);
            gear.setAlpha(enabled ? 1f : .38f);
            boolean needsSoulseek = id.equals("streamseek-preview") && !soulseekEnabled();
            subtitle.setText(
                    !available
                            ? Strings.t("Settings unavailable. Reopen this page.")
                            : busy
                                    ? Strings.t("Applying change…")
                                    : needsSoulseek
                                            ? Strings.t("Requires Soulseek downloads")
                                            : summary);
        }
    }

    private void paragraph(LinearLayout parent, String value) {
        TextView text = style.text(value, 14, style.secondary, style.regular);
        text.setLineSpacing(style.dp(3), 1);
        text.setPadding(0, 0, 0, style.dp(8));
        parent.addView(text);
    }

    private void navigation(LinearLayout parent, String title, Runnable action) {
        PlaylistRows.add(parent, style, title, "", action);
    }

    private void actionPair(
            LinearLayout parent,
            String first,
            Runnable firstAction,
            String second,
            Runnable secondAction) {
        LinearLayout row = new LinearLayout(this);
        TextView left = style.button(first, false, firstAction);
        TextView right = style.button(second, false, secondAction);
        LinearLayout.LayoutParams start = new LinearLayout.LayoutParams(0, -2, 1);
        start.rightMargin = style.dp(8);
        row.addView(left, start);
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = style.dp(8);
        space.bottomMargin = style.dp(8);
        parent.addView(row, space);
    }

    private void action(LinearLayout parent, String title, Runnable action) {
        TextView button = style.button(title, false, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, style.dp(6), 0, style.dp(6));
        parent.addView(button, lp);
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(style.outline);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, style.dp(1));
        lp.setMargins(0, style.dp(14), 0, style.dp(14));
        parent.addView(line, lp);
    }

    private void slider(
            LinearLayout parent,
            String title,
            int min,
            int max,
            int value,
            Label label,
            Value setting) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(
                style.text(title, 16, style.foreground, style.medium),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView shown = style.text(label.of(value), 14, style.foreground, style.medium);
        row.addView(shown);
        parent.addView(row);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setContentDescription(title + ", " + label.of(value));
        bar.setProgressTintList(ColorStateList.valueOf(style.foreground));
        bar.setThumbTintList(ColorStateList.valueOf(style.foreground));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
        parent.addView(bar, new LinearLayout.LayoutParams(-1, style.dp(48)));
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar view) {}

                    public void onStopTrackingTouch(SeekBar view) {}

                    public void onProgressChanged(SeekBar view, int at, boolean fromUser) {
                        int chosen = min + at;
                        shown.setText(label.of(chosen));
                        bar.setContentDescription(title + ", " + label.of(chosen));
                        if (android.os.Build.VERSION.SDK_INT >= 30)
                            bar.setStateDescription(label.of(chosen));
                        if (fromUser) setting.set(chosen);
                    }
                });
        Value choose =
                chosen -> {
                    int bounded = Math.max(min, Math.min(max, chosen));
                    bar.setProgress(bounded - min);
                    setting.set(bounded);
                    shown.announceForAccessibility(label.of(bounded));
                };
        LinearLayout fine = new LinearLayout(this);
        TextView decrease = style.button("−", false, () -> choose.set(min + bar.getProgress() - 1));
        decrease.setPadding(0, 0, 0, 0);
        decrease.setContentDescription(Strings.f("Decrease {0}", title));
        TextView increase = style.button("+", false, () -> choose.set(min + bar.getProgress() + 1));
        increase.setPadding(0, 0, 0, 0);
        increase.setContentDescription(Strings.f("Increase {0}", title));
        fine.addView(decrease, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(style.dp(48), style.dp(48));
        gap.setMarginStart(style.dp(8));
        fine.addView(increase, gap);
        Feature owner = containing(parent);
        Integer initial = owner == null ? null : sliderDefault(owner.id);
        if (initial != null) {
            TextView reset = style.button(Strings.t("Reset"), false, () -> choose.set(initial));
            reset.setContentDescription(Strings.f("Reset {0}", title));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
            space.setMarginStart(style.dp(12));
            fine.addView(reset, space);
        }
        LinearLayout.LayoutParams fineSpace = new LinearLayout.LayoutParams(-1, -2);
        fineSpace.topMargin = style.dp(8);
        parent.addView(fine, fineSpace);
    }

    private Integer sliderDefault(String id) {
        switch (id) {
            case "sing":
                return SoundExtras.SING_DEFAULT;
            case "pitch":
                return 0;
            case "loudness":
                return SoundExtras.HOST_TARGET_LUFS;
            case "sleep-fade":
                return SoundExtras.FADE_SECONDS;
            case "headphone-correction":
                return 100;
            default:
                return null;
        }
    }

    private void option(
            LinearLayout parent,
            String title,
            String on,
            String off,
            boolean value,
            Setting setting) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(48));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(style.text(title, 16, style.foreground, style.medium));
        TextView sub = style.text(value ? on : off, 14, style.secondary, style.regular);
        sub.setPadding(0, style.dp(4), style.dp(8), 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TelegramSwitch toggle = new TelegramSwitch(this, style);
        toggle.setChecked(value);
        toggle.setContentDescription(title);
        toggle.setListener(
                (button, checked) -> {
                    try {
                        setting.set(checked);
                        sub.setText(checked ? on : off);
                    } catch (Exception error) {
                        toggle.setChecked(!checked);
                        unavailable();
                    }
                });
        row.addView(toggle);
        row.setOnClickListener(view -> toggle.toggle());
        parent.addView(row);
    }

    // ── Sections of the playback patches ───────────────────────────────────────

    private void bluetooth(LinearLayout parent) {
        paragraph(
                parent,
                Strings.t(
                        "Bluetooth can’t carry lossless audio on most phones, so BitChord normally"
                                + " skips lossless addons there. Allow them on the outputs you want"
                                + " anyway; the phone sends them with its best Bluetooth codec,"
                                + " such as LDAC or aptX. Starts with the next song."));
        bluetoothStatus = status(parent);
        divider(parent);
        option(
                parent,
                Strings.t("All Bluetooth outputs"),
                Strings.t("Lossless addons are used over any Bluetooth output"),
                Strings.t("Only the outputs allowed below"),
                SoundExtras.losslessOverBluetooth,
                on -> {
                    SoundExtras.setLosslessOverBluetooth(on);
                    handler.post(refreshStatus);
                });
        Outputs.Output now = Outputs.current();
        if (now.bluetooth) SoundExtras.rememberBluetooth(now);
        List<String> seen = SoundExtras.bluetoothSeen();
        if (seen.isEmpty()) {
            paragraph(parent, Strings.t("Connect a Bluetooth output and it will be listed here."));
            return;
        }
        divider(parent);
        heading(parent, Strings.t("Bluetooth outputs seen"));
        for (String key : seen) {
            String name = SoundExtras.bluetoothName(key);
            option(
                    parent,
                    key.equals(now.key) ? Strings.f("{0} (connected)", name) : name,
                    Strings.t("Lossless addons allowed"),
                    Strings.t("Left to BitChord"),
                    SoundExtras.losslessDevices.contains(key),
                    on -> SoundExtras.setLosslessDevice(key, on));
        }
    }

    private void headphones(LinearLayout parent) {
        Outputs.Output output = Outputs.current();
        paragraph(
                parent,
                Strings.t(
                        "Flattens a headphone’s own sound with a profile measured for that model."
                                + " Each output keeps its own profile: set one with the headphones"
                                + " connected."));
        paragraph(parent, Strings.f("Playing through: {0} ({1})", output.name, output.kind()));
        String assigned = HeadphoneEq.assignedName(output.key);
        HeadphoneEq.Profile active = HeadphoneEq.active;
        if (assigned != null) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(style.text(assigned, 16, style.foreground, style.medium));
            TextView sub =
                    style.text(
                            active != null && assigned.equals(active.name)
                                    ? active.summary()
                                    : Strings.t("Profile couldn’t be read"),
                            14,
                            style.secondary,
                            style.regular);
            sub.setPadding(0, style.dp(4), 0, 0);
            labels.addView(sub);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(
                    small(
                            Strings.t("Remove"),
                            () -> {
                                HeadphoneEq.clear(output.key);
                                SoundExtras.effectsChanged();
                                rebuild();
                            }));
            parent.addView(row);
            slider(
                    parent,
                    Strings.t("Strength"),
                    0,
                    100,
                    HeadphoneEq.strength,
                    value -> value + "%",
                    value -> {
                        HeadphoneEq.setStrength(value);
                        SoundExtras.effectsChanged();
                    });
        } else {
            paragraph(parent, Strings.t("No profile for this output yet."));
        }
        divider(parent);
        heading(parent, Strings.t("Find your headphones"));
        LinearLayout queryRow = new LinearLayout(this);
        queryRow.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(queryRow);
        EditText query = input(queryRow, Strings.t("Model, such as “HD 650” or “AirPods Pro”"));
        query.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        TextView progress = style.text("", 13, style.secondary, style.regular);
        progress.setPadding(0, style.dp(8), 0, 0);
        TextView search =
                style.button(
                        Strings.t("Search"),
                        true,
                        () ->
                                searchHeadphones(
                                        query.getText().toString(), results, progress, output));
        LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(-2, style.dp(50));
        searchSpace.leftMargin = style.dp(8);
        queryRow.addView(search, searchSpace);
        navigation(parent, Strings.t("Paste a profile"), () -> pasteProfile(output));
        parent.addView(progress);
        parent.addView(results);
        paragraph(
                parent,
                Strings.t(
                        "Profiles come from AutoEq by Jaakko Pasanen, which measures thousands of"
                                + " headphones. Any parametric EQ in Equalizer APO’s text form can"
                                + " be pasted too. The idea is from Prism by Yigder."));
    }

    private void searchHeadphones(
            String query, LinearLayout results, TextView progress, Outputs.Output output) {
        results.removeAllViews();
        if (query.trim().length() < 2) {
            progress.setText(Strings.t("Type part of the model name."));
            return;
        }
        progress.setText(Strings.t("Searching…"));
        new Thread(
                        () -> {
                            List<HeadphoneEq.Entry> found;
                            String problem = null;
                            try {
                                found = HeadphoneEq.search(HeadphoneEq.index(false), query, 25);
                            } catch (Exception e) {
                                found = Collections.emptyList();
                                problem = e.getMessage();
                            }
                            List<HeadphoneEq.Entry> shown = found;
                            String failed = problem;
                            runOnUiThread(
                                    () -> {
                                        if (isFinishing()) return;
                                        if (failed != null) {
                                            progress.setText(
                                                    Strings.f(
                                                            "Couldn’t load AutoEq’s list: {0}",
                                                            failed));
                                            return;
                                        }
                                        progress.setText(
                                                shown.isEmpty()
                                                        ? Strings.t(
                                                                "No headphones found with that"
                                                                        + " name.")
                                                        : Strings.f(
                                                                "{0} results",
                                                                String.valueOf(shown.size())));
                                        for (HeadphoneEq.Entry entry : shown) {
                                            TextView row =
                                                    style.text(
                                                            entry.label(),
                                                            15,
                                                            style.foreground,
                                                            style.regular);
                                            row.setPadding(
                                                    style.dp(12),
                                                    style.dp(12),
                                                    style.dp(12),
                                                    style.dp(12));
                                            row.setBackground(style.shape(style.field, 10, false));
                                            row.setClickable(true);
                                            row.setFocusable(true);
                                            row.setOnClickListener(
                                                    v -> useHeadphones(entry, progress, output));
                                            LinearLayout.LayoutParams lp =
                                                    new LinearLayout.LayoutParams(-1, -2);
                                            lp.topMargin = style.dp(6);
                                            results.addView(row, lp);
                                        }
                                    });
                        },
                        "autoeq-search")
                .start();
    }

    private void useHeadphones(HeadphoneEq.Entry entry, TextView progress, Outputs.Output output) {
        progress.setText(Strings.f("Loading {0}…", entry.name));
        new Thread(
                        () -> {
                            String text = null, problem = null;
                            try {
                                text = HeadphoneEq.download(entry);
                            } catch (Exception e) {
                                problem = e.getMessage();
                            }
                            HeadphoneEq.Profile profile =
                                    text == null ? null : HeadphoneEq.parse(entry.name, text);
                            String profileText = text;
                            String failed = problem;
                            runOnUiThread(
                                    () -> {
                                        if (isFinishing()) return;
                                        if (profile == null) {
                                            progress.setText(
                                                    Strings.f(
                                                            "Couldn’t load the profile: {0}",
                                                            failed == null
                                                                    ? Strings.t("no filters in it")
                                                                    : failed));
                                            return;
                                        }
                                        HeadphoneEq.assign(output.key, profile, profileText);
                                        SoundExtras.effectsChanged();
                                        Toast.makeText(
                                                        this,
                                                        Strings.f("Using {0}", profile.name),
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                        rebuild();
                                    });
                        },
                        "autoeq-profile")
                .start();
    }

    private void pasteProfile(Outputs.Output output) {
        EditText field =
                dialogField("Preamp: -6 dB\nFilter 1: ON PK Fc 105 Hz Gain -3.2 dB Q 0.70");
        field.setMinLines(5);
        field.setGravity(Gravity.TOP);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle(Strings.t("Paste a profile"))
                .setView(wrap(field))
                .setPositiveButton(
                        Strings.t("Use it"),
                        (d, w) -> {
                            String text = field.getText().toString();
                            HeadphoneEq.Profile profile =
                                    HeadphoneEq.parse(Strings.t("Pasted profile"), text);
                            if (profile == null) {
                                Toast.makeText(
                                                this,
                                                Strings.t("No filters found in the text."),
                                                Toast.LENGTH_LONG)
                                        .show();
                                return;
                            }
                            HeadphoneEq.assign(output.key, profile, text);
                            SoundExtras.effectsChanged();
                            rebuild();
                        })
                .setNegativeButton(Strings.t("Cancel"), null)
                .show();
    }

    private void outputEq(LinearLayout parent) {
        Outputs.Output output = Outputs.current();
        paragraph(
                parent,
                Strings.t(
                        "Each output gets back the equaliser you set with it connected: the"
                                + " speaker, wired, USB and each Bluetooth device."));
        paragraph(parent, Strings.f("Now: {0} · {1}", output.name, OutputEq.current().describe()));
        List<String> saved = OutputEq.savedOutputs();
        if (!saved.isEmpty()) {
            divider(parent);
            heading(parent, Strings.t("Saved for"));
            for (String key : saved) {
                OutputEq.Snapshot snapshot = OutputEq.saved(key);
                if (snapshot == null) continue;
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(style.dp(44));
                LinearLayout labels = new LinearLayout(this);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.addView(
                        style.text(OutputEq.savedName(key), 15, style.foreground, style.medium));
                labels.addView(style.text(snapshot.describe(), 13, style.secondary, style.regular));
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(
                        small(
                                Strings.t("Forget"),
                                () -> {
                                    OutputEq.forget(key);
                                    rebuild();
                                }));
                parent.addView(row);
            }
        }
        divider(parent);
        actionPair(
                parent,
                Strings.t("Copy code"),
                () -> {
                    String code = OutputEq.current().code();
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null)
                        clipboard.setPrimaryClip(ClipData.newPlainText("BitChord equaliser", code));
                    Toast.makeText(this, Strings.f("Copied {0}", code), Toast.LENGTH_SHORT).show();
                },
                Strings.t("Paste code"),
                this::pasteCode);
        paragraph(
                parent,
                Strings.t(
                        "A code holds the equaliser’s sliders, tone pad and balance, so a setting"
                                + " can be shared. The idea is from Prism by Yigder."));
    }

    private void pasteCode() {
        EditText field = dialogField(OutputEq.CODE_PREFIX + "…");
        field.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(Strings.t("Paste code"))
                .setView(wrap(field))
                .setPositiveButton(
                        Strings.t("Use it"),
                        (d, w) -> {
                            OutputEq.Snapshot snapshot =
                                    OutputEq.Snapshot.fromCode(field.getText().toString());
                            if (snapshot == null) {
                                Toast.makeText(
                                                this,
                                                Strings.t("That isn’t an equaliser code."),
                                                Toast.LENGTH_LONG)
                                        .show();
                                return;
                            }
                            OutputEq.apply(snapshot);
                            handler.postDelayed(this::rebuild, 300);
                        })
                .setNegativeButton(Strings.t("Cancel"), null)
                .show();
    }

    private void dj(LinearLayout parent) {
        String[] modes = {Strings.t("Steady"), Strings.t("Build up"), Strings.t("Wind down")};
        heading(parent, Strings.t("DJ journey"));
        android.widget.RadioGroup journeys = new android.widget.RadioGroup(this);
        journeys.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        for (int i = 0; i < modes.length; i++) {
            final int value = i;
            android.widget.RadioButton choice = new android.widget.RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setTag("dj-journey:" + i);
            choice.setText(modes[i]);
            choice.setTextColor(style.foreground);
            choice.setTextSize(14);
            choice.setMinHeight(style.dp(48));
            choice.setButtonTintList(ColorStateList.valueOf(style.dark ? 0xff30d158 : 0xff34c759));
            journeys.addView(choice, new android.widget.RadioGroup.LayoutParams(0, -2, 1));
            choice.setChecked(DjMode.journey == i);
            choice.setOnClickListener(view -> DjMode.setJourney(value));
        }
        parent.addView(journeys);

        navigation(parent, Strings.t("Save DJ order"), () -> DjPlaylists.choose(this));
        paragraph(
                parent,
                Strings.t(
                        "Songs you queued yourself keep their place, and nothing moves in a Listen"
                                + " Together party. Each song’s key and tempo are learned as it"
                                + " plays; BitChord’s own Automix analyses are used too."));
        djStatus = status(parent);
        paragraph(parent, Strings.t("The idea is from Prism by Yigder."));
    }

    private void jamesDsp(LinearLayout parent) {
        String installed = EffectsApps.installed(this);
        paragraph(parent, EffectsApps.describe(installed));
        jamesStatus = status(parent);
        paragraph(
                parent,
                Strings.t(
                        "BitChord tells effect apps about its audio session when a song starts, so"
                                + " JamesDSP can work on the music. The rootless JamesDSP hears"
                                + " BitChord through Android’s audio capture, which BitChord"
                                + " allows."));
        navigation(
                parent,
                installed == null ? Strings.t("Get JamesDSP") : Strings.t("Open JamesDSP"),
                () -> EffectsApps.open(this));
        action(
                parent,
                Strings.t("Announce the session again"),
                () ->
                        Toast.makeText(
                                        this,
                                        EffectsApps.announce(this)
                                                ? Strings.t(
                                                        "Told effect apps about BitChord’s audio"
                                                                + " session.")
                                                : Strings.t("Play a song first."),
                                        Toast.LENGTH_SHORT)
                                .show());
        if (EffectsApps.ROOTLESS.equals(installed)) rootlessJamesDsp(parent);
        paragraph(
                parent,
                Strings.t(
                        "While this is on, BitChord’s equaliser is turned off and Battery saver"
                                + " stands down, so the sound isn’t equalised twice and songs stay"
                                + " off the audio chip, where JamesDSP couldn’t hear them."));
    }

    /** Turning the rootless JamesDSP on and off, and its one-time Shizuku or ADB setup. */
    private void rootlessJamesDsp(LinearLayout parent) {
        actionPair(
                parent,
                Strings.t("Start JamesDSP"),
                () -> jamesPower(true),
                Strings.t("Stop JamesDSP"),
                () -> jamesPower(false));
        if (EffectsApps.rootlessReady(this)) return;
        divider(parent);
        heading(parent, Strings.t("JamesDSP’s setup"));
        paragraph(
                parent,
                Strings.t(
                        "The rootless JamesDSP needs two permissions that only Shizuku or a"
                                + " computer with ADB can grant, once. With Shizuku running, open"
                                + " JamesDSP and pick Shizuku in its setup. With ADB, run these"
                                + " commands; the last one lets JamesDSP start without opening."));
        navigation(
                parent,
                EffectsApps.shizukuInstalled(this)
                        ? Strings.t("Open Shizuku")
                        : Strings.t("Get Shizuku"),
                () -> EffectsApps.openShizuku(this));
        action(
                parent,
                Strings.t("Copy ADB commands"),
                () -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null)
                        clipboard.setPrimaryClip(
                                ClipData.newPlainText("JamesDSP setup", EffectsApps.adbCommands()));
                    Toast.makeText(this, Strings.t("Copied the ADB commands."), Toast.LENGTH_SHORT)
                            .show();
                });
        divider(parent);
    }

    private void jamesPower(boolean on) {
        EffectsApps.power(this, on);
        if (on) EffectsApps.announce(this);
        Toast.makeText(
                        this,
                        on
                                ? Strings.t(
                                        "Asked JamesDSP to start. If nothing changes, open"
                                                + " JamesDSP and tap its power button.")
                                : Strings.t("Asked JamesDSP to stop."),
                        Toast.LENGTH_LONG)
                .show();
        handler.removeCallbacks(refreshStatus);
        handler.postDelayed(refreshStatus, 1_500);
    }

    // ── Live status lines ────────────────────────────────────────────────────

    private void refreshStatus() {
        handler.removeCallbacks(refreshStatus);
        if (batteryStatus != null) {
            StringBuilder text = new StringBuilder();
            if (android.os.Build.VERSION.SDK_INT < 29)
                text.append(Strings.t("Needs Android 10 or newer."));
            else {
                List<String> formats = SoundExtras.offloadFormats(this);
                text.append(
                        formats.isEmpty()
                                ? Strings.t(
                                        "Android says this phone’s audio chip can’t play songs by"
                                                + " itself.")
                                : Strings.f(
                                        "This phone’s audio chip can play: {0}.",
                                        String.join(", ", formats)));
            }
            if (SoundExtras.batterySaver) {
                SoundExtras.offloadWanted();
                String held = SoundExtras.offloadHeldBack;
                if (held != null) text.append(' ').append(Strings.f("Waiting: {0}.", held));
                String decoder = SoundExtras.hostDecoder();
                text.append(' ')
                        .append(
                                decoder != null
                                        ? Strings.f("Now decoding with {0}.", decoder)
                                        : Strings.t("No decoder in use now."));
            }
            batteryStatus.setText(text.toString());
        }
        if (bluetoothStatus != null) {
            Outputs.Output output = Outputs.current();
            String text;
            if (!output.bluetooth)
                text = Strings.f("Playing through: {0} ({1})", output.name, output.kind());
            else {
                String codec = Outputs.bluetoothCodec();
                String about;
                if (codec != null)
                    about =
                            Outputs.losslessCapable()
                                    ? Strings.f(
                                            "{0}, which BitChord already counts as lossless", codec)
                                    : codec;
                else if (Outputs.bluetoothCodecUnknown())
                    about =
                            Strings.t(
                                    "codec unknown; allow Nearby devices on BitChord’s Sources"
                                            + " page");
                else about = Strings.t("codec not reported");
                text = Strings.f("Playing through: {0} over Bluetooth ({1})", output.name, about);
            }
            bluetoothStatus.setText(text);
        }
        if (djStatus != null) {
            DjMode.Info now = DjMode.info(DjMode.currentId());
            String text =
                    (now != null
                                    ? Strings.f("This song: {0}", now.describe())
                                    : DjMode.enabled
                                            ? Strings.t("This song: listening…")
                                            : Strings.t("This song: not known yet"))
                            + " · "
                            + Strings.f("{0} songs known", String.valueOf(DjMode.knownCount()));
            if (!DjMode.lastArrangement.isEmpty()) text += " · " + DjMode.lastArrangement;
            djStatus.setText(text);
        }
        if (jamesStatus != null)
            jamesStatus.setText(EffectsApps.status(this, EffectsApps.installed(this)));
        if (batteryStatus != null
                || bluetoothStatus != null
                || djStatus != null
                || jamesStatus != null) handler.postDelayed(refreshStatus, 3_000);
    }

    // ── More widgets ─────────────────────────────────────────────────────────

    private TextView status(LinearLayout parent) {
        TextView text = style.text("", 13, style.secondary, style.regular);
        text.setLineSpacing(style.dp(3), 1);
        text.setPadding(0, 0, 0, style.dp(8));
        parent.addView(text);
        return text;
    }

    private void heading(LinearLayout parent, String value) {
        TextView text = style.text(value, 12, style.secondary, style.semibold);
        text.setPadding(0, style.dp(4), 0, style.dp(6));
        parent.addView(text);
    }

    private EditText input(LinearLayout parent, String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(style.foreground);
        field.setHintTextColor(style.secondary);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTypeface(style.regular);
        field.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12));
        field.setBackground(style.shape(style.field, 10, false));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private EditText dialogField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(style.foreground);
        field.setHintTextColor(style.secondary);
        field.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12));
        return field;
    }

    private LinearLayout wrap(View view) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(style.dp(20), style.dp(8), style.dp(20), 0);
        wrap.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private TextView small(String label, Runnable action) {
        TextView button = style.text(label, 14, style.foreground, style.semibold);
        button.setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8));
        button.setBackground(style.shape(style.field, 18, false));
        button.setOnClickListener(view -> action.run());
        button.setClickable(true);
        button.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = style.dp(12);
        button.setLayoutParams(lp);
        return button;
    }
}
