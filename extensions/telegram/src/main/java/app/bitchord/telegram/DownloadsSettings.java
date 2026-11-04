package app.bitchord.telegram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * Patch download settings in one place. BitChord's four own controls stay where they are and keep
 * their preferences; this only shows their state, since their setters no longer exist on the host.
 */
final class DownloadsSettings {
    private DownloadsSettings() {}

    /** A read of one of BitChord's settings; empty when this host build doesn't have it. */
    private static Object hostValue(String getter) {
        try {
            Class<?> settings = Class.forName("com.music.bitchord.data.settings.AppSettings");
            Object flow;
            try {
                flow = settings.getMethod(getter).invoke(null);
            } catch (NoSuchMethodException removedGetter) {
                String name = Character.toLowerCase(getter.charAt(3)) + getter.substring(4);
                java.lang.reflect.Field field = settings.getDeclaredField(name);
                field.setAccessible(true);
                flow = field.get(null);
            }
            Method read = flow.getClass().getMethod("getValue");
            read.setAccessible(true);
            return read.invoke(flow);
        } catch (ReflectiveOperationException | LinkageError | NullPointerException absent) {
            return null;
        }
    }

    private static String onOff(Object value) {
        return Boolean.TRUE.equals(value) ? Strings.t("On") : Strings.t("Off");
    }

    /** One line about BitChord's own controls, or empty if none of them could be read. */
    static String hostSummary() {
        Object quality = hostValue("getDownloadQuality");
        Object wifi = hostValue("getWifiOnlyDownloads");
        Object export = hostValue("getExportDownloads");
        Object sync = hostValue("getSyncDownloadedPlaylists");
        if (quality == null && wifi == null && export == null && sync == null) return "";
        StringBuilder line = new StringBuilder();
        if (quality != null)
            line.append(Strings.f("Quality: {0}", DownloadChoice.label(String.valueOf(quality))));
        if (wifi != null)
            line.append(separator(line)).append(Strings.f("Wi-Fi only: {0}", onOff(wifi)));
        if (export != null)
            line.append(separator(line)).append(Strings.f("Save to Music: {0}", onOff(export)));
        if (sync != null)
            line.append(separator(line)).append(Strings.f("Playlist sync: {0}", onOff(sync)));
        return line.toString();
    }

    private static String separator(StringBuilder line) {
        return line.length() == 0 ? "" : " · ";
    }

    private static boolean exists(String name) {
        try {
            Class.forName(name, false, DownloadsSettings.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    private static void divider(LinearLayout group, TelegramStyle style) {
        if (group.getChildCount() == 0) return;
        View line = new View(style.activity);
        line.setBackgroundColor(style.outline);
        LinearLayout.LayoutParams place =
                new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f)));
        place.leftMargin = style.dp(16);
        place.rightMargin = style.dp(16);
        group.addView(line, place);
    }

    /** A grouped row with a capsule switch; the whole row toggles and reads as one control. */
    static LinearLayout switchRow(
            LinearLayout group,
            TelegramStyle style,
            String title,
            String detail,
            boolean checked,
            TelegramSwitch.Listener listener) {
        divider(group, style);
        LinearLayout row = new LinearLayout(style.activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(detail.isEmpty() ? 52 : 64));
        row.setPadding(style.dp(16), style.dp(10), style.dp(8), style.dp(10));
        row.setBackground(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                                style.dark ? 0x228e8e93 : 0x22000000),
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT),
                        null));
        LinearLayout text = new LinearLayout(style.activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(style.text(title, 16, style.foreground, style.medium));
        if (!detail.isEmpty()) text.addView(style.text(detail, 13, style.secondary, style.regular));
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TelegramSwitch control = new TelegramSwitch(style.activity, style);
        control.setChecked(checked);
        control.setContentDescription(detail.isEmpty() ? title : title + ", " + detail);
        control.setListener(listener);
        row.addView(control);
        row.setOnClickListener(v -> control.toggle());
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    interface Choice {
        void chosen(int index);
    }

    /** One choice out of a few, inline under the row it belongs to. Text sizes with the system. */
    static void chips(
            LinearLayout group, TelegramStyle style, String[] labels, int selected, Choice choice) {
        android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(style.activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(style.dp(12), 0, style.dp(12), style.dp(8));
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(style.activity);
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            boolean on = i == selected;
            TextView chip =
                    style.text(
                            labels[i],
                            14,
                            on ? style.background : style.foreground,
                            on ? style.semibold : style.regular);
            chip.setTag("chip-" + labels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(style.dp(48));
            chip.setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8));
            chip.setBackground(style.shape(on ? style.foreground : style.field, 24, false));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setSelected(on);
            chip.setOnClickListener(v -> choice.chosen(index));
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-2, -2);
            place.setMarginEnd(style.dp(6));
            row.addView(chip, place);
        }
        scroll.addView(row);
        group.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void unavailable(Context context) {
        android.widget.Toast.makeText(
                        context,
                        Strings.t("This setting isn't available right now."),
                        android.widget.Toast.LENGTH_SHORT)
                .show();
    }

    private static void note(LinearLayout parent, TelegramStyle style, String value) {
        TextView text = style.text(value, 13, style.secondary, style.regular);
        text.setPadding(0, 0, 0, style.dp(8));
        parent.addView(text);
    }

    private static void heading(LinearLayout parent, TelegramStyle style, String value) {
        TextView text = style.text(value, 18, style.foreground, style.semibold);
        text.setPadding(0, style.dp(20), 0, style.dp(8));
        if (android.os.Build.VERSION.SDK_INT >= 28) text.setAccessibilityHeading(true);
        parent.addView(text);
    }

    private static void openDownload(Context context, String id, String action) {
        try {
            Class.forName("app.bitchord.soulseek.DownloadSettings")
                    .getMethod("open", Context.class, String.class, String.class)
                    .invoke(null, context, id, action);
        } catch (ReflectiveOperationException | LinkageError absent) {
            unavailable(context);
        }
    }

    /** Adds the whole section; only what this install actually patched in shows up. */
    static void addSection(Activity activity, LinearLayout parent, TelegramStyle style) {
        String host = hostSummary();
        if (!host.isEmpty()) {
            heading(parent, style, Strings.t("BitChord's own settings"));
            note(parent, style, host);
            note(
                    parent,
                    style,
                    Strings.t("Change these in BitChord's Downloads settings, right above this."));
        }
        boolean quality = SoundExtras.downloadQualityApplied();
        boolean smart = SoundExtras.smartDownloadsApplied();
        boolean soulseek = exists("app.bitchord.soulseek.DownloadSettings");
        boolean hub = exists("app.bitchord.soulseek.DownloadsHubActivity");
        boolean uploads = exists("app.bitchord.telegram.DownloadUploadBridge");
        if (quality || smart) {
            heading(parent, style, Strings.t("How songs are chosen"));
            LinearLayout group = PlaylistRows.group(parent, style);
            if (quality)
                switchRow(
                        group,
                        style,
                        Strings.t("Ask the quality for each download"),
                        Strings.t("Standard, High or Lossless, chosen per download"),
                        DownloadChoice.ask,
                        (view, on) -> DownloadChoice.setAsk(on));
            if (smart) {
                LinearLayout details = new LinearLayout(activity);
                details.setOrientation(LinearLayout.VERTICAL);
                switchRow(
                        group,
                        style,
                        Strings.t("Smart downloads"),
                        Strings.t("Keeps your most played songs downloaded on Wi-Fi"),
                        SmartDownloads.enabled,
                        (view, on) -> {
                            SmartDownloads.setEnabled(on);
                            smartDetails(details, style);
                        });
                group.addView(details, new LinearLayout.LayoutParams(-1, -2));
                smartDetails(details, style);
            }
        }
        if (soulseek || hub || uploads) {
            heading(parent, style, Strings.t("Download sources"));
            LinearLayout group = PlaylistRows.group(parent, style);
            if (hub)
                PlaylistRows.add(
                        group,
                        style,
                        Strings.t("Download manager"),
                        Strings.t("Active, queued and saved downloads"),
                        () -> {
                            Intent intent =
                                    new Intent()
                                            .setClassName(
                                                    activity,
                                                    "app.bitchord.soulseek.DownloadsHubActivity");
                            if (intent.resolveActivity(activity.getPackageManager()) != null)
                                activity.startActivity(intent);
                            else unavailable(activity);
                        });
            if (soulseek) {
                PlaylistRows.add(
                        group,
                        style,
                        Strings.t("Soulseek downloads"),
                        Strings.t("Account & download settings"),
                        () -> openDownload(activity, "soulseek", "account"));
                PlaylistRows.add(
                        group,
                        style,
                        Strings.t("Peer downloads"),
                        Strings.t("Downloads and connections"),
                        () -> openDownload(activity, "peer-downloads", "downloads"));
                PlaylistRows.add(
                        group,
                        style,
                        Strings.t("Debrid downloads"),
                        Strings.t("Services and link downloads"),
                        () -> openDownload(activity, "debrid", "services"));
            }
            if (uploads)
                PlaylistRows.add(
                        group,
                        style,
                        Strings.t("Telegram archive"),
                        Strings.t("Choose where finished downloads are uploaded"),
                        () -> {
                            try {
                                Class.forName("app.bitchord.telegram.DownloadUploadBridge")
                                        .getMethod("openSetup", Activity.class)
                                        .invoke(null, activity);
                            } catch (ReflectiveOperationException | LinkageError absent) {
                                unavailable(activity);
                            }
                        });
        }
        heading(parent, style, Strings.t("Default download folder"));
        LinearLayout folder = PlaylistRows.group(parent, style);
        PlaylistRows.add(
                folder,
                style,
                DownloadFolder.label(activity),
                Strings.t("Choose folder"),
                () ->
                        activity.startActivity(
                                new Intent(activity, DownloadsSettingsActivity.class)
                                        .putExtra(DownloadsSettingsActivity.PICK_DIRECTORY, true)));
        if (!DownloadFolder.selected(activity).isEmpty())
            PlaylistRows.add(
                    folder,
                    style,
                    Strings.t("Use BitChord default"),
                    Strings.t("Use BitChord’s own storage setting for new downloads"),
                    () -> {
                        if (activity instanceof DownloadsSettingsActivity)
                            ((DownloadsSettingsActivity) activity).saveFolder("");
                    });
        TextView folderNote =
                style.text(
                        Strings.t(
                                        "New audio files use this folder. Queued downloads and"
                                                + " saved files keep their locations.")
                                + "\n"
                                + Strings.t(
                                        "SD cards and USB drives appear in Android’s folder picker."
                                                + " Offline HLS/DASH packages keep BitChord’s own"
                                                + " storage."),
                        13,
                        style.secondary,
                        style.regular);
        folderNote.setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(16));
        parent.addView(folderNote);
        heading(parent, style, Strings.t("Storage"));
        LinearLayout storage = PlaylistRows.group(parent, style);
        PlaylistRows.add(
                storage,
                style,
                Strings.t("Storage Sense"),
                Strings.t("See cached songs and saved files, and choose cleanup rules"),
                () -> activity.startActivity(new Intent(activity, StorageSenseActivity.class)));
    }

    private static void smartDetails(LinearLayout details, TelegramStyle style) {
        details.removeAllViews();
        if (!SmartDownloads.enabled) return;
        View line = new View(style.activity);
        line.setBackgroundColor(style.outline);
        LinearLayout.LayoutParams place =
                new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f)));
        place.leftMargin = style.dp(16);
        place.rightMargin = style.dp(16);
        details.addView(line, place);
        switchRow(
                details,
                style,
                Strings.t("Only while charging"),
                SmartDownloads.chargingOnly
                        ? Strings.t("Waits until the phone is charging, too")
                        : Strings.t("Any time the phone is on Wi-Fi"),
                SmartDownloads.chargingOnly,
                (view, on) -> SmartDownloads.setChargingOnly(on));
        TextView label =
                style.text(
                        Strings.t("Songs to keep downloaded"), 14, style.foreground, style.regular);
        label.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(4));
        details.addView(label);
        String[] labels = new String[SmartDownloads.COUNT_CHOICES.length];
        int selected = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = String.valueOf(SmartDownloads.COUNT_CHOICES[i]);
            if (SmartDownloads.COUNT_CHOICES[i] == SmartDownloads.count) selected = i;
        }
        chips(
                details,
                style,
                labels,
                selected,
                index -> {
                    SmartDownloads.setCount(SmartDownloads.COUNT_CHOICES[index]);
                    smartDetails(details, style);
                });
    }
}
