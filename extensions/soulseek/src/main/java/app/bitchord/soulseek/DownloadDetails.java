package app.bitchord.soulseek;

import android.app.Activity;
import android.app.Dialog;
import android.text.format.Formatter;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.bitchord.telegram.PatchStrings;

import java.text.NumberFormat;
import java.util.Map;

/** Read-only metadata from the selected result; opening details never requests file bytes. */
final class DownloadDetails {
    private final Activity activity;
    private final DownloadStyle style;
    private final LinearLayout content;

    private DownloadDetails(Activity activity) {
        this.activity = activity;
        style = new DownloadStyle(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
    }

    static Dialog show(Activity activity, DownloadExplorerView.Option option) {
        if (option.soulseekTrack != null) return show(activity, option.soulseekTrack);
        DownloadDetails details = new DownloadDetails(activity);
        details.section("Recording");
        details.value("Title", option.title);
        details.value("Artist", option.artist);
        details.section("Source");
        details.value("Provider", option.source);
        details.value("Format", option.quality);
        details.value("Availability", option.detail);
        if (!option.filePath.isEmpty()) details.value("File path", option.filePath);
        return details.style.detailsPanel(
                PatchStrings.t("Source details"),
                PatchStrings.t("Source information is reported, not file-verified."),
                details.content);
    }

    static Dialog show(Activity activity, SoulseekTrack track) {
        DownloadDetails details = new DownloadDetails(activity);
        details.section("File");
        int separator = Math.max(track.path.lastIndexOf('/'), track.path.lastIndexOf('\\'));
        details.value("Filename", track.path.substring(separator + 1));
        details.value("Folder", separator < 0 ? "" : track.path.substring(0, separator));
        details.value(
                "File size",
                Formatter.formatFileSize(activity, track.size)
                        + " · "
                        + PatchStrings.f(
                                "{0} bytes", NumberFormat.getIntegerInstance().format(track.size)));
        details.value("Title from filename", track.title);
        details.value(
                "Artist from filename", "Unknown artist".equals(track.artist) ? "" : track.artist);
        details.section("Audio · peer reported");
        details.value(
                "File extension", track.advertisedExtension.toUpperCase(java.util.Locale.ROOT));
        details.value("Duration", track.duration > 0 ? duration(track.duration) : "");
        details.value("Bitrate", track.bitrate > 0 ? track.bitrate + " kbps" : "");
        NumberFormat rate = NumberFormat.getNumberInstance();
        rate.setMaximumFractionDigits(3);
        details.value(
                "Sample rate",
                track.sampleRate > 0 ? rate.format(track.sampleRate / 1000d) + " kHz" : "");
        details.value(
                "Bit depth", track.bitDepth > 0 ? PatchStrings.f("{0} bits", track.bitDepth) : "");
        details.value(
                "Bitrate mode",
                track.variableBitrate < 0
                        ? ""
                        : PatchStrings.t(track.variableBitrate == 1 ? "Variable" : "Constant"));
        details.section("Peer · reported availability");
        details.value("Peer", track.peer);
        details.value(
                "Free upload slot",
                track.freeSlots < 0
                        ? ""
                        : PatchStrings.t(
                                track.freeSlots == 1 ? "Available" : "No free slot reported"));
        details.value(
                "Reported upload speed",
                track.speed > 0 ? Formatter.formatShortFileSize(activity, track.speed) + "/s" : "");
        details.value(
                "Peer upload queue",
                track.queueLength < 0
                        ? ""
                        : NumberFormat.getIntegerInstance().format(track.queueLength));
        boolean extra = false;
        for (Map.Entry<String, Long> attribute : track.attributes.entrySet()) {
            if (java.util.Arrays.asList("0", "1", "2", "4", "5").contains(attribute.getKey()))
                continue;
            if (!extra) {
                details.section("Additional peer attributes");
                extra = true;
            }
            details.value(
                    PatchStrings.f("Attribute {0}", attribute.getKey()),
                    String.valueOf(attribute.getValue()));
        }
        return details.style.detailsPanel(
                PatchStrings.t("File details"),
                PatchStrings.t(
                        "Peer metadata is unverified. Tags, channels and audio quality require"
                                + " inspecting the file itself."),
                details.content);
    }

    private static String duration(int seconds) {
        return seconds >= 3600
                ? String.format(
                        java.util.Locale.getDefault(),
                        "%d:%02d:%02d",
                        seconds / 3600,
                        seconds / 60 % 60,
                        seconds % 60)
                : String.format(
                        java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private void section(String label) {
        TextView title = style.text(PatchStrings.t(label), 16, style.foreground, style.semibold);
        title.setPadding(0, style.dp(content.getChildCount() == 0 ? 0 : 20), 0, style.dp(6));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title);
    }

    private void value(String label, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, style.dp(8), 0, style.dp(8));
        row.addView(style.text(PatchStrings.t(label), 13, style.secondary, style.regular));
        TextView text =
                style.text(
                        value == null || value.trim().isEmpty()
                                ? PatchStrings.t("Not reported")
                                : value,
                        15,
                        style.foreground,
                        style.medium);
        text.setTextIsSelectable(true);
        text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        row.addView(text);
        content.addView(row);
        View divider = new View(activity);
        divider.setBackgroundColor(style.outline);
        content.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f))));
    }
}
