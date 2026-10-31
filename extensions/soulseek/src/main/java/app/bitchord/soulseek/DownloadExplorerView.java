package app.bitchord.soulseek;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import java.util.*;

/** Native source selection with a scrolling result area and persistent admission controls. */
final class DownloadExplorerView {
    interface Admission {
        void submit(Option option, boolean now);
    }

    static final class Option {
        final String source, title, artist, quality, detail;
        final boolean lossless, reportedAvailable;
        final DownloadExplorerPage.Selection selection;
        final Runnable preview;
        View card;
        RadioButton radio;
        LinearLayout group;

        Option(
                String source,
                String title,
                String artist,
                String quality,
                String detail,
                boolean lossless,
                boolean reportedAvailable,
                DownloadExplorerPage.Selection selection,
                Runnable preview) {
            this.source = source;
            this.title = title;
            this.artist = artist;
            this.quality = quality;
            this.detail = detail;
            this.lossless = lossless;
            this.reportedAvailable = reportedAvailable;
            this.selection = selection;
            this.preview = preview;
        }
    }

    final LinearLayout root, results, footer;
    final EditText query;
    final TextView status;
    final Button now, queue;
    private final DownloadPage page;
    private final DownloadStyle style;
    private final LinearLayout searchBar;
    private final TextView selectedTitle, selectedDetail, empty, admissionNotice;
    private final List<Option> options = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private final boolean batch;
    private String filter = "all";
    private Option selected;
    private boolean busy;

    DownloadExplorerView(
            DownloadPage page,
            boolean batch,
            Runnable search,
            Runnable settings,
            Admission admission) {
        this.page = page;
        this.batch = batch;
        style = page.downloadStyle;
        root = column();
        root.setPadding(style.dp(18), style.dp(8), style.dp(18), style.dp(8));
        root.setBackgroundColor(style.background);
        LinearLayout header = row();
        header.addView(
                style.backButton(page::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title = label(PatchStrings.t("Download Explorer"), 20, true);
        title.setMaxLines(2);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton gear = style.settingsButton();
        gear.setContentDescription(PatchStrings.t("Download settings"));
        gear.setOnClickListener(v -> settings.run());
        header.addView(gear, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        root.addView(header);

        ScrollView scroll = new ScrollView(page);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        String trackTitle = page.getIntent().getStringExtra("download.track.title");
        if (trackTitle != null && !trackTitle.isEmpty()) {
            LinearLayout summary = row();
            summary.setPadding(0, style.dp(14), 0, style.dp(14));
            ImageView artwork = new ImageView(page);
            artwork.setImageDrawable(style.viewIcon("music"));
            artwork.setBackground(style.shape(style.card, 10, false));
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            summary.addView(artwork, new LinearLayout.LayoutParams(style.dp(56), style.dp(56)));
            String cover = page.getIntent().getStringExtra("download.track.artwork");
            if (cover != null && !cover.isEmpty()) {
                artwork.setImageTintList(null);
                DownloadArtwork.bind(artwork, cover, "");
            }
            LinearLayout copy = column();
            copy.setPadding(style.dp(12), 0, 0, 0);
            copy.addView(label(trackTitle, 16, true));
            String artist = page.getIntent().getStringExtra("download.track.artist");
            String duration = page.getIntent().getStringExtra("download.track.duration");
            copy.addView(label(join(artist, duration), 13, false));
            summary.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(summary);
        }
        searchBar = row();
        query = new EditText(page);
        query.setTag("download-explorer-query");
        query.setSingleLine(true);
        query.setHint(PatchStrings.t("Artist and song title"));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setText(page.getIntent().getStringExtra(SoulseekDownloadChoice.EXTRA_QUERY));
        style.field(query);
        searchBar.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton find = new ImageButton(page);
        find.setImageDrawable(style.viewIcon("search"));
        find.setContentDescription(PatchStrings.t("Find available downloads"));
        find.setBackground(null);
        find.setPadding(style.dp(12), style.dp(12), style.dp(12), style.dp(12));
        style.markCustom(find);
        find.setOnClickListener(v -> search.run());
        query.setOnEditorActionListener(
                (v, action, event) -> {
                    if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                    search.run();
                    return true;
                });
        searchBar.addView(find, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        if (trackTitle != null && !trackTitle.isEmpty()) searchBar.setVisibility(View.GONE);
        content.addView(searchBar);
        HorizontalScrollView filters = new HorizontalScrollView(page);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = row();
        filters.addView(filterRow);
        String[] ids = {"all", "lossless", "available"};
        String[] names = {
            PatchStrings.t("All"), PatchStrings.t("Lossless"), PatchStrings.t("Available now")
        };
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            TextView chip = label(names[i], 13, false);
            chip.setTag("explorer-filter:" + id);
            chip.setMinHeight(style.dp(48));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(style.dp(14), 0, style.dp(14), 0);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(
                    v -> {
                        filter = id;
                        applyFilter();
                    });
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-2, -2);
            placement.setMarginEnd(style.dp(7));
            filterRow.addView(chip, placement);
            chips.add(chip);
        }
        content.addView(filters);
        status = label(PatchStrings.t("Nothing downloads until you choose a result."), 13, false);
        status.setPadding(0, style.dp(10), 0, style.dp(10));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        results = column();
        results.setTag("download-explorer-results");
        content.addView(results);
        empty = label(PatchStrings.t("No sources match this filter."), 15, false);
        empty.setPadding(0, style.dp(24), 0, style.dp(24));
        empty.setVisibility(View.GONE);
        content.addView(empty);
        TextView warning =
                label(
                        PatchStrings.t(
                                "Reported formats are unverified. Review the recording version"
                                        + " before selecting."),
                        12,
                        false);
        warning.setPadding(0, style.dp(12), 0, style.dp(12));
        content.addView(warning);
        content.addView(
                label(PatchStrings.t("Availability is reported and may change."), 12, false));

        footer = column();
        footer.setTag("download-explorer-selection");
        footer.setPadding(0, style.dp(10), 0, 0);
        selectedTitle = label(PatchStrings.t("Choose a source"), 16, true);
        selectedDetail =
                label(PatchStrings.t("Nothing downloads until you choose a result."), 12, false);
        selectedDetail.setPadding(0, style.dp(4), 0, style.dp(8));
        footer.addView(selectedTitle);
        footer.addView(selectedDetail);
        admissionNotice = label("", 13, false);
        admissionNotice.setVisibility(View.GONE);
        admissionNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(admissionNotice);
        now =
                action(
                        batch
                                ? PatchStrings.t("Use for this track")
                                : PatchStrings.t("Download now"),
                        true);
        queue = action(PatchStrings.t("Add to download queue"), false);
        now.setTag("download-explorer-now");
        queue.setTag("download-explorer-queue");
        now.setOnClickListener(
                v -> {
                    if (selected != null && !busy) admission.submit(selected, true);
                });
        queue.setOnClickListener(
                v -> {
                    if (selected != null && !busy) admission.submit(selected, false);
                });
        footer.addView(now, new LinearLayout.LayoutParams(-1, -2));
        if (!batch) {
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.topMargin = style.dp(7);
            footer.addView(queue, spacing);
        }
        root.addView(footer);
        root.setLayoutDirection(
                PatchStrings.rtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        choose(null);
        applyFilter();
    }

    void showSearch() {
        searchBar.setVisibility(View.VISIBLE);
        query.requestFocus();
    }

    void reset() {
        results.removeAllViews();
        options.clear();
        choose(null);
        empty.setVisibility(View.GONE);
        InputMethodManager keyboard =
                (InputMethodManager)
                        page.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    void busy(boolean value) {
        busy = value;
        updateActions();
    }

    void message(String value) {
        admissionNotice.setText(value);
        admissionNotice.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void add(LinearLayout group, Option option) {
        if (group.getChildCount() == 1
                && group.getChildAt(0) instanceof TextView
                && option.source.contentEquals(((TextView) group.getChildAt(0)).getText()))
            group.removeAllViews();
        option.group = group;
        LinearLayout card = column();
        card.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(12));
        option.card = card;
        LinearLayout heading = row();
        heading.addView(label(option.source, 16, true), new LinearLayout.LayoutParams(0, -2, 1));
        RadioButton radio = new RadioButton(page);
        radio.setButtonTintList(ColorStateList.valueOf(style.foreground));
        radio.setClickable(false);
        radio.setFocusable(false);
        radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(radio, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        option.radio = radio;
        card.addView(heading);
        TextView track = label(join(option.title, option.artist), 14, false);
        track.setMaxLines(2);
        card.addView(track);
        TextView quality =
                label(
                        option.quality.isEmpty()
                                ? PatchStrings.t("Reported format unavailable")
                                : option.quality,
                        14,
                        true);
        quality.setPadding(0, style.dp(9), 0, style.dp(4));
        card.addView(quality);
        if (!option.detail.isEmpty()) card.addView(label(option.detail, 12, false));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> choose(option));
        card.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName("android.widget.RadioButton");
                        info.setCheckable(true);
                        info.setChecked(selected == option);
                    }
                });
        if (option.preview != null) {
            Button preview = action(PatchStrings.t("Preview this file"), false);
            preview.setOnClickListener(v -> option.preview.run());
            card.addView(preview);
        }
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2);
        placement.bottomMargin = style.dp(10);
        group.addView(card, placement);
        options.add(option);
        applyFilter();
    }

    private void choose(Option option) {
        selected = option;
        if (admissionNotice != null) message("");
        for (Option item : options) {
            item.radio.setChecked(item == option);
            item.card.setSelected(item == option);
            item.card.setBackground(style.shape(style.card, 20, item == option));
        }
        selectedTitle.setText(
                option == null
                        ? PatchStrings.t("Choose a source")
                        : PatchStrings.f("Download from {0}", option.source));
        selectedDetail.setText(
                option == null
                        ? PatchStrings.t("Nothing downloads until you choose a result.")
                        : option.title + "\n" + option.quality);
        updateActions();
    }

    private void updateActions() {
        boolean enabled = selected != null && !busy;
        now.setEnabled(enabled);
        queue.setEnabled(enabled);
        now.setAlpha(enabled ? 1f : .38f);
        queue.setAlpha(enabled ? 1f : .38f);
    }

    private boolean matches(Option item) {
        return filter.equals("all")
                || filter.equals("lossless") && item.lossless
                || filter.equals("available") && item.reportedAvailable;
    }

    private void applyFilter() {
        if (selected != null && !matches(selected)) choose(null);
        int visible = 0;
        Map<LinearLayout, Boolean> groups = new HashMap<>();
        for (Option item : options) {
            boolean show = matches(item);
            item.card.setVisibility(show ? View.VISIBLE : View.GONE);
            groups.put(item.group, groups.getOrDefault(item.group, false) || show);
            if (show) visible++;
            item.card.setBackground(style.shape(style.card, 20, selected == item));
        }
        for (Map.Entry<LinearLayout, Boolean> entry : groups.entrySet())
            entry.getKey().setVisibility(entry.getValue() ? View.VISIBLE : View.GONE);
        for (TextView chip : chips) {
            boolean active = chip.getTag().equals("explorer-filter:" + filter);
            chip.setSelected(active);
            chip.setTextColor(active ? style.foreground : style.secondary);
            chip.setBackground(style.shape(active ? style.field : style.background, 22, !active));
        }
        empty.setVisibility(!options.isEmpty() && visible == 0 ? View.VISIBLE : View.GONE);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(page);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(page);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView label(String value, int size, boolean strong) {
        return style.text(
                value,
                size,
                strong ? style.foreground : style.secondary,
                strong ? style.semibold : style.regular);
    }

    private Button action(String label, boolean primary) {
        Button button = new Button(page);
        button.setText(label);
        style.button(button);
        button.setBackground(style.shape(primary ? style.foreground : style.field, 28, false));
        button.setTextColor(primary ? style.background : style.foreground);
        return button;
    }

    private static String join(String left, String right) {
        if (left == null || left.isEmpty()) return right == null ? "" : right;
        return (left == null ? "" : left) + (right == null || right.isEmpty() ? "" : " · " + right);
    }
}
