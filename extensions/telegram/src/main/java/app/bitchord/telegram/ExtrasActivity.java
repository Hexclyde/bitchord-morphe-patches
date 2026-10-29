package app.bitchord.telegram;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** The Playback extras page, opened from BitChord's Equalizer page. */
public final class ExtrasActivity extends Activity {
    private TelegramStyle style;
    private LinearLayout body;

    interface Setting {
        void set(boolean on);
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
        SoundExtras.init(this);
        style = new TelegramStyle(this);
        getWindow().setStatusBarColor(style.background);
        getWindow().setNavigationBarColor(style.background);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        style.dark
                                ? 0
                                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
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
    }

    private void render() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(style.dp(20), style.dp(16), style.dp(20), style.dp(18));
        ImageButton back = style.backButton(this::finish);
        top.addView(back, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        top.addView(style.text(Strings.t("Playback extras"), 34, style.foreground, style.heavy));
        body.addView(top);

        LinearLayout sing = card(Strings.t("Sing"));
        paragraph(
                sing,
                Strings.t(
                        "Turns the singer down so you can sing over the song. Works best on studio"
                                + " recordings with the voice in the middle."));
        slider(
                sing,
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
        divider(sing);
        toggle(
                sing,
                Strings.t("Whole centre"),
                Strings.t("Removes everything in the middle, drums included"),
                Strings.t("Only the voice range, and only while someone sings"),
                SoundExtras.singMethod == VocalCut.CENTRE,
                on -> SoundExtras.setSingMethod(on ? VocalCut.CENTRE : VocalCut.VOICE_GATE),
                Strings.t("The cleaner choice for older recordings."));
        paragraph(
                sing, Strings.t("Sing is ported from Auris by OreoMuncher45, a fork of BitChord."));

        LinearLayout pitch = card(Strings.t("Pitch"));
        paragraph(
                pitch,
                Strings.t(
                        "Changes the key without changing the speed. BitChord’s speed setting keeps"
                                + " the pitch you set here."));
        slider(
                pitch,
                Strings.t("Pitch"),
                SoundExtras.MIN_PITCH,
                SoundExtras.MAX_PITCH,
                1,
                SoundExtras.pitchSemitones,
                SoundExtras::semitones,
                SoundExtras::setPitch);

        LinearLayout loudness = card(Strings.t("Loudness level"));
        paragraph(
                loudness,
                Strings.t(
                        "How loud songs play when BitChord’s loudness normalisation is on."
                            + " BitChord’s own level is -14 LUFS, as on YouTube and Spotify. Lower"
                            + " is quieter with more room for peaks; higher is louder."));
        slider(
                loudness,
                Strings.t("Level"),
                SoundExtras.MIN_TARGET_LUFS,
                SoundExtras.MAX_TARGET_LUFS,
                1,
                SoundExtras.targetLufs,
                SoundExtras::lufs,
                SoundExtras::setTargetLufs);

        LinearLayout other = card(Strings.t("Other"));
        toggle(
                other,
                Strings.t("Fade out at the sleep timer"),
                Strings.t("The song fades out over 10 seconds, then pauses"),
                Strings.t("The song pauses straight away"),
                SoundExtras.sleepFade,
                SoundExtras::setSleepFade,
                null);
        divider(other);
        toggle(
                other,
                Strings.t("Battery saver"),
                Strings.t("The phone’s audio chip plays songs it can decode"),
                Strings.t("BitChord decodes every song"),
                SoundExtras.batterySaver,
                SoundExtras::setBatterySaver,
                Strings.t(
                        "Saves battery on long listens. While a song plays this way, BitChord’s"
                            + " equaliser, loudness normalisation, Sing, spatial audio and"
                            + " crossfade don’t work on it. Android decides which songs and devices"
                            + " can; the rest play as before."));
        divider(other);
        toggle(
                other,
                Strings.t("Lossless addons over Bluetooth"),
                Strings.t("Lossless addons are used over Bluetooth too"),
                Strings.t("Lossless addons wait for wired or USB audio"),
                SoundExtras.losslessOverBluetooth,
                SoundExtras::setLosslessOverBluetooth,
                Strings.t(
                        "Bluetooth can’t carry lossless audio on most phones, so BitChord normally"
                            + " skips lossless addons there. With this on they’re used anyway, and"
                            + " the phone sends them with its best Bluetooth codec, such as LDAC or"
                            + " aptX. Starts with the next song."));
    }

    private LinearLayout card(String title) {
        TextView label =
                style.text(
                        title.toUpperCase(Locale.getDefault()),
                        11,
                        style.secondary,
                        style.semibold);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.setMargins(style.dp(20), style.dp(24), style.dp(20), style.dp(8));
        body.addView(label, ll);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(style.dp(16), style.dp(16), style.dp(16), style.dp(16));
        c.setBackground(style.shape(style.card, 14, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(style.dp(16), 0, style.dp(16), 0);
        body.addView(c, lp);
        return c;
    }

    private void paragraph(LinearLayout p, String value) {
        TextView t = style.text(value, 14, style.secondary, style.regular);
        t.setLineSpacing(style.dp(3), 1);
        t.setPadding(0, 0, 0, style.dp(6));
        p.addView(t);
    }

    private void divider(LinearLayout p) {
        View line = new View(this);
        line.setBackgroundColor(style.outline);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, style.dp(1));
        lp.setMargins(0, style.dp(14), 0, style.dp(14));
        p.addView(line, lp);
    }

    private void slider(
            LinearLayout p,
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
        p.addView(row);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        bar.setProgress((value - min) / step);
        bar.setContentDescription(title);
        bar.setProgressTintList(ColorStateList.valueOf(style.foreground));
        bar.setThumbTintList(ColorStateList.valueOf(style.foreground));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
        p.addView(bar, new LinearLayout.LayoutParams(-1, style.dp(48)));
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    public void onStopTrackingTouch(SeekBar seekBar) {}

                    public void onProgressChanged(SeekBar seekBar, int at, boolean fromUser) {
                        int chosen = min + at * step;
                        shown.setText(label.of(chosen));
                        if (fromUser) setting.set(chosen);
                    }
                });
    }

    private void toggle(
            LinearLayout p,
            String title,
            String on,
            String off,
            boolean value,
            Setting setting,
            String details) {
        String yes = on, no = off;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(40));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(style.text(title, 16, style.foreground, style.medium));
        TextView sub = style.text(value ? yes : no, 14, style.secondary, style.regular);
        sub.setPadding(0, style.dp(4), 0, 0);
        labels.addView(sub);
        if (details != null) {
            TextView more = style.text(details, 13, style.secondary, style.regular);
            more.setPadding(0, style.dp(6), 0, style.dp(4));
            labels.addView(more);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TelegramSwitch toggle = new TelegramSwitch(this, style);
        toggle.setChecked(value);
        toggle.setContentDescription(title);
        toggle.setListener(
                (button, checked) -> {
                    sub.setText(checked ? yes : no);
                    setting.set(checked);
                });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = style.dp(16);
        row.addView(toggle, gap);
        row.setOnClickListener(v -> toggle.toggle());
        row.setClickable(true);
        row.setFocusable(true);
        p.addView(row);
    }
}
