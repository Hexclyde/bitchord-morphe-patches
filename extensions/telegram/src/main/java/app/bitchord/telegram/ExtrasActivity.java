package app.bitchord.telegram;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Locale;

/** The native Patches & tweaks page. Installed features stay compact until enabled and opened. */
public final class ExtrasActivity extends Activity {
    private TelegramStyle style;
    private LinearLayout body;
    private ScrollView scroll;
    private String expanded;
    private final LinkedHashMap<String, Feature> features = new LinkedHashMap<>();

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
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(style.background);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        scroll.setLayoutDirection(
                Strings.rightToLeft() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
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
        render();
        if (saved != null) scroll.post(() -> scroll.scrollTo(0, saved.getInt("patchScroll", 0)));
        body.getViewTreeObserver()
                .addOnGlobalFocusChangeListener(
                        (oldFocus, newFocus) -> {
                            Feature target = containing(newFocus);
                            if (target != null && !target.id.equals(expanded)) {
                                expanded = null;
                                showExpanded();
                            }
                        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Feature feature : features.values()) feature.sync();
        showExpanded();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("expandedPatch", expanded);
        state.putInt("patchScroll", scroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    private void render() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(style.dp(20), style.dp(16), style.dp(20), style.dp(18));
        top.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title =
                style.text(Strings.t("Patches & tweaks"), 34, style.foreground, style.heavy);
        title.setPadding(0, style.dp(18), 0, 0);
        top.addView(title);
        body.addView(top);

        boolean downloads = exists("app.bitchord.soulseek.DownloadSettings");
        if (downloads) section(Strings.t("Patches"));
        if (downloads) {
            downloadFeature(
                    "soulseek",
                    Strings.t("Soulseek downloads"),
                    Strings.t("Find audio and save a complete copy"),
                    parent -> {
                        paragraph(
                                parent,
                                Strings.t(
                                        "Search Soulseek for audio files and download the version"
                                                + " you choose."));
                        action(
                                parent,
                                Strings.t("Account & download settings"),
                                () -> openDownload("soulseek", "account"));
                        copyActions(parent, "soulseek");
                    });
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
                        action(
                                parent,
                                Strings.t("Download queue"),
                                () -> openDownload("peer-downloads", "downloads"));
                        action(
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
                        action(
                                parent,
                                Strings.t("Services & API keys"),
                                () -> openDownload("debrid", "services"));
                        action(
                                parent,
                                Strings.t("Link downloads"),
                                () -> openDownload("debrid", "links"));
                        copyActions(parent, "debrid");
                    });
        }

        if (SoundExtras.singApplied()
                || SoundExtras.pitchApplied()
                || SoundExtras.loudnessApplied()
                || SoundExtras.sleepFadeApplied()
                || SoundExtras.batterySaverApplied()
                || SoundExtras.losslessBluetoothApplied()) section(Strings.t("Audio tweaks"));
        if (SoundExtras.singApplied()) {
            feature(
                    "sing",
                    Strings.t("Sing"),
                    Strings.t("Turn the singer down"),
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
                                10,
                                SoundExtras.singLevel,
                                level ->
                                        level == 0
                                                ? Strings.t("Off")
                                                : Strings.f("{0}% quieter", String.valueOf(level)),
                                SoundExtras::setSingLevel);
                        divider(parent);
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
                                        "Sing is ported from Auris by OreoMuncher45, a fork of"
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
                                1,
                                SoundExtras.pitchSemitones,
                                SoundExtras::semitones,
                                SoundExtras::setPitch);
                    });
        }
        if (SoundExtras.loudnessApplied()) {
            feature(
                    "loudness",
                    Strings.t("Loudness level"),
                    Strings.t("Adjust the normalised playback level"),
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
                                1,
                                SoundExtras.targetLufs,
                                SoundExtras::lufs,
                                SoundExtras::setTargetLufs);
                    });
        }
        if (SoundExtras.sleepFadeApplied()) {
            feature(
                    "sleep-fade",
                    Strings.t("Fade out at the sleep timer"),
                    Strings.t("Fade over 10 seconds before pausing"),
                    () -> SoundExtras.sleepFade,
                    SoundExtras::setSleepFade,
                    parent ->
                            paragraph(
                                    parent,
                                    Strings.t("The song fades out over 10 seconds, then pauses")));
        }
        if (SoundExtras.batterySaverApplied()) {
            feature(
                    "battery-saver",
                    Strings.t("Battery saver"),
                    Strings.t("Let the phone’s audio chip decode songs"),
                    () -> SoundExtras.batterySaver,
                    SoundExtras::setBatterySaver,
                    parent ->
                            paragraph(
                                    parent,
                                    Strings.t(
                                            "Saves battery on long listens. While a song plays this"
                                                + " way, BitChord’s equaliser, loudness"
                                                + " normalisation, Sing, spatial audio and"
                                                + " crossfade don’t work on it. Android decides"
                                                + " which songs and devices can; the rest play as"
                                                + " before.")));
        }
        if (SoundExtras.losslessBluetoothApplied()) {
            feature(
                    "lossless-bluetooth",
                    Strings.t("Lossless addons over Bluetooth"),
                    Strings.t("Keep lossless sources available over Bluetooth"),
                    () -> SoundExtras.losslessOverBluetooth,
                    SoundExtras::setLosslessOverBluetooth,
                    parent ->
                            paragraph(
                                    parent,
                                    Strings.t(
                                            "Bluetooth can’t carry lossless audio on most phones,"
                                                + " so BitChord normally skips lossless addons"
                                                + " there. With this on they’re used anyway, and"
                                                + " the phone sends them with its best Bluetooth"
                                                + " codec, such as LDAC or aptX. Starts with the"
                                                + " next song.")));
        }
        showExpanded();
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
                                        "enabled",
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
        action(parent, Strings.t("Where copies go"), () -> openDownload(feature, "destinations"));
        action(parent, Strings.t("Formats, best first"), () -> openDownload(feature, "formats"));
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

    private void section(String value) {
        TextView label =
                style.text(
                        value.toUpperCase(Locale.getDefault()),
                        11,
                        style.secondary,
                        style.semibold);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(style.dp(20), style.dp(24), style.dp(20), style.dp(8));
        body.addView(label, lp);
    }

    /** Adds a normal feature card; shared by installed patch entries and their native controls. */
    void feature(
            String id,
            String title,
            String summary,
            State state,
            Setting setting,
            Details details) {
        Feature feature = new Feature(id, title, state);
        features.put(id, feature);
        LinearLayout card = new LinearLayout(this);
        feature.card = card;
        card.setTag("patch:" + id);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(style.dp(16), style.dp(8), style.dp(12), style.dp(8));
        card.setBackground(style.shape(style.card, 14, false));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2);
        placement.setMargins(style.dp(16), 0, style.dp(16), style.dp(10));
        body.addView(card, placement);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(style.dp(64));
        header.setFocusable(true);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(style.text(title, 16, style.foreground, style.medium));
        TextView sub = style.text(summary, 13, style.secondary, style.regular);
        sub.setPadding(0, style.dp(3), 0, 0);
        labels.addView(sub);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton gear = style.settingsButton();
        feature.gear = gear;
        gear.setTag("settings:" + id);
        gear.setOnClickListener(
                view -> {
                    if (feature.enabled) {
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
        touch.setMinimumHeight(style.dp(48));
        FrameLayout.LayoutParams switchPlacement =
                new FrameLayout.LayoutParams(style.dp(52), style.dp(32), Gravity.CENTER);
        touch.addView(toggle, switchPlacement);
        touch.setOnClickListener(view -> toggle.toggle());
        touch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(touch, new LinearLayout.LayoutParams(style.dp(52), style.dp(48)));
        header.setOnClickListener(view -> focus(feature));
        card.addView(header);
        LinearLayout content = new LinearLayout(this);
        feature.details = content;
        content.setTag("details:" + id);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, style.dp(12), style.dp(4), style.dp(8));
        details.build(content);
        card.addView(content);
        toggle.setListener(
                (button, on) -> {
                    try {
                        setting.set(on);
                    } catch (Exception | LinkageError error) {
                        unavailable();
                    }
                    feature.sync();
                    expanded = on && feature.enabled ? id : null;
                    showExpanded();
                });
        feature.sync();
        content.setVisibility(id.equals(expanded) && feature.enabled ? View.VISIBLE : View.GONE);
    }

    private Feature containing(View view) {
        for (View at = view; at != null; ) {
            for (Feature feature : features.values()) if (feature.card == at) return feature;
            Object parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private void focus(Feature feature) {
        expanded = feature.enabled ? feature.id : null;
        showExpanded();
    }

    private void showExpanded() {
        Feature selected = features.get(expanded);
        if (selected == null || !selected.enabled) expanded = null;
        for (Feature feature : features.values()) {
            boolean open = feature.enabled && feature.id.equals(expanded);
            feature.details.setVisibility(open ? View.VISIBLE : View.GONE);
            feature.gear.setContentDescription(Strings.f("Settings for {0}", feature.title));
        }
    }

    private final class Feature {
        final String id, title;
        final State state;
        LinearLayout card, details;
        TelegramSwitch toggle;
        ImageButton gear;
        boolean enabled;

        Feature(String id, String title, State state) {
            this.id = id;
            this.title = title;
            this.state = state;
        }

        void sync() {
            try {
                enabled = state.get();
            } catch (Exception | LinkageError error) {
                enabled = false;
            }
            toggle.setChecked(enabled);
            gear.setEnabled(enabled);
            gear.setAlpha(enabled ? 1f : 0.38f);
        }
    }

    private void paragraph(LinearLayout parent, String value) {
        TextView text = style.text(value, 14, style.secondary, style.regular);
        text.setLineSpacing(style.dp(3), 1);
        text.setPadding(0, 0, 0, style.dp(8));
        parent.addView(text);
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
            int step,
            int value,
            Label label,
            Value setting) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(
                style.text(title, 16, style.foreground, style.medium),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView shown = style.text(label.of(value), 14, style.secondary, style.medium);
        row.addView(shown);
        parent.addView(row);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        bar.setProgress((value - min) / step);
        bar.setContentDescription(title);
        bar.setProgressTintList(ColorStateList.valueOf(style.foreground));
        bar.setThumbTintList(ColorStateList.valueOf(style.foreground));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
        parent.addView(bar, new LinearLayout.LayoutParams(-1, style.dp(48)));
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        Feature owner = containing(bar);
                        if (owner != null) focus(owner);
                    }

                    public void onStopTrackingTouch(SeekBar seekBar) {}

                    public void onProgressChanged(SeekBar seekBar, int at, boolean fromUser) {
                        int chosen = min + at * step;
                        shown.setText(label.of(chosen));
                        if (fromUser) setting.set(chosen);
                    }
                });
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
}
