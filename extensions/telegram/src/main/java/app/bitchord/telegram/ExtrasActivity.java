package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** The native Patches & tweaks page. Installed features stay compact until enabled and opened. */
public final class ExtrasActivity extends Activity {
    private TelegramStyle style;
    private LinearLayout body;
    private ScrollView scroll;
    private String expanded;
    private final LinkedHashMap<String, Feature> features = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus = this::refreshStatus;
    private TextView batteryStatus, bluetoothStatus, djStatus;

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
        Outputs.refresh();
        handler.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    /** Builds the page again after a section's content changed, keeping the place. */
    private void rebuild() {
        int y = scroll.getScrollY();
        features.clear();
        body.removeAllViews();
        batteryStatus = bluetoothStatus = djStatus = null;
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
        handler.post(refreshStatus);
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
        if (audio) section(Strings.t("Audio tweaks"));
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
                    Strings.t("Fade out before the sleep timer pauses"),
                    () -> SoundExtras.sleepFade,
                    SoundExtras::setSleepFade,
                    parent -> {
                        slider(
                                parent,
                                Strings.t("Fade length"),
                                SleepFade.MIN_SECONDS,
                                SleepFade.MAX_SECONDS,
                                1,
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
                    Strings.t("Let the phone’s audio chip decode songs"),
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
                                                + " normalisation, Sing, spatial audio and"
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
                    Strings.t("Keep lossless sources available over Bluetooth"),
                    () -> SoundExtras.losslessBluetoothEnabled,
                    SoundExtras::setLosslessBluetoothEnabled,
                    this::bluetooth);
        }
        if (SoundExtras.headphoneApplied()) {
            feature(
                    "headphone-correction",
                    Strings.t("Headphone correction"),
                    Strings.t("Flatten your headphones’ own sound"),
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
                    Strings.t("Remember the equaliser for each output"),
                    OutputEq::enabled,
                    OutputEq::setEnabled,
                    this::outputEq);
        }
        if (SoundExtras.djApplied()) {
            feature(
                    "dj-mode",
                    Strings.t("DJ mode"),
                    Strings.t("Play the queue in an order that mixes well"),
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
                    Strings.t("Let JamesDSP do the effects"),
                    () -> EffectsApps.useJamesDsp,
                    on -> {
                        EffectsApps.setUseJamesDsp(on);
                        if (on && EffectsApps.hostEqualiserOn(this)) EffectsApps.hostEqualiserOff();
                        SoundExtras.effectsChanged();
                    },
                    this::jamesDsp);
        }
        if (SoundExtras.downloadQualityApplied()) {
            section(Strings.t("Downloads"));
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
                    5,
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
        EditText query = input(parent, Strings.t("Model, such as “HD 650” or “AirPods Pro”"));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        TextView progress = style.text("", 13, style.secondary, style.regular);
        progress.setPadding(0, style.dp(8), 0, 0);
        action(
                parent,
                Strings.t("Search"),
                () -> searchHeadphones(query.getText().toString(), results, progress, output));
        action(parent, Strings.t("Paste a profile"), () -> pasteProfile(output));
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
        action(
                parent,
                Strings.t("Copy code"),
                () -> {
                    String code = OutputEq.current().code();
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null)
                        clipboard.setPrimaryClip(ClipData.newPlainText("BitChord equaliser", code));
                    Toast.makeText(this, Strings.f("Copied {0}", code), Toast.LENGTH_SHORT).show();
                });
        action(parent, Strings.t("Paste code"), this::pasteCode);
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
        paragraph(
                parent,
                Strings.t(
                        "BitChord tells effect apps about its audio session when a song starts, so"
                                + " JamesDSP can work on the music. The rootless JamesDSP hears"
                                + " BitChord through Android’s audio capture, which BitChord"
                                + " allows."));
        action(
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
        paragraph(
                parent,
                Strings.t(
                        "While this is on, BitChord’s equaliser is turned off and Battery saver"
                                + " stands down, so the sound isn’t equalised twice and songs stay"
                                + " off the audio chip, where JamesDSP couldn’t hear them."));
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
        if (batteryStatus != null || bluetoothStatus != null || djStatus != null)
            handler.postDelayed(refreshStatus, 3_000);
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
