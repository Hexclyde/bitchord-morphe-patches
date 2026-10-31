package app.bitchord.soulseek;

import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import java.util.*;

/** Native source selection with a scrolling result area and inline admission controls. */
final class DownloadExplorerView {
    interface Admission {
        void submit(Option option, boolean now);
    }

    static final class Option {
        final String source, title, artist, quality, detail;
        final boolean lossless, reportedAvailable, availabilityKnown;
        final DownloadExplorerPage.Selection selection;
        final Runnable preview;
        View card;
        RadioButton radio;
        LinearLayout group;
        View previewAction;

        Option(
                String source,
                String title,
                String artist,
                String quality,
                String detail,
                boolean lossless,
                Boolean reportedAvailable,
                DownloadExplorerPage.Selection selection,
                Runnable preview) {
            this.source = source;
            this.title = title;
            this.artist = artist;
            this.quality = quality;
            this.detail = detail;
            this.lossless = lossless;
            this.reportedAvailable = Boolean.TRUE.equals(reportedAvailable);
            this.availabilityKnown = reportedAvailable != null;
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
    private final TextView empty, admissionNotice;
    private final TextView availabilityChip;
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
        root.setPadding(style.dp(6), style.dp(8), style.dp(6), style.dp(8));
        root.setBackgroundColor(page.surfaceColor());
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
        page.contentScroll = scroll;
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
            TextView chip = label(names[i], 14, false);
            chip.setTag("explorer-filter:" + id);
            chip.setMinHeight(style.dp(50));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(style.dp(14), 0, style.dp(14), 0);
            if (id.equals("lossless"))
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        style.explorerIcon("lossless", style.secondary), null, null, null);
            if (id.equals("available"))
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        style.explorerIcon("available", style.secondary), null, null, null);
            chip.setCompoundDrawablePadding(style.dp(6));
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
        availabilityChip = chips.get(2);
        availabilityChip.setTooltipText(
                PatchStrings.t(
                        "Peers reporting a free upload slot. Sources with unknown readiness remain"
                                + " under All."));
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

        footer = column();
        footer.setTag("download-explorer-selection");
        View divider = new View(page);
        divider.setBackgroundColor(style.outline);
        footer.addView(divider, new LinearLayout.LayoutParams(-1, style.dp(1)));
        LinearLayout actionRow = row();
        actionRow.setPadding(style.dp(16), style.dp(9), style.dp(16), style.dp(14));
        now = action(batch ? PatchStrings.t("Use for this track") : PatchStrings.t("Now"), true);
        queue = action(PatchStrings.t("Later"), false);
        now.setTag("download-explorer-now");
        queue.setTag("download-explorer-queue");
        now.setContentDescription(
                batch ? PatchStrings.t("Use for this track") : PatchStrings.t("Download now"));
        queue.setContentDescription(PatchStrings.t("Add to download queue"));
        if (batch)
            now.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    style.explorerIcon("check", style.foreground), null, null, null);
        now.setOnClickListener(
                v -> {
                    if (selected != null && !busy) admission.submit(selected, true);
                });
        queue.setOnClickListener(
                v -> {
                    if (selected != null && !busy) admission.submit(selected, false);
                });
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        if (!batch) first.setMarginEnd(style.dp(8));
        actionRow.addView(now, first);
        if (!batch) actionRow.addView(queue, new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(actionRow);
        admissionNotice = label("", 13, false);
        admissionNotice.setPadding(style.dp(16), 0, style.dp(16), style.dp(12));
        admissionNotice.setVisibility(View.GONE);
        admissionNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(admissionNotice);
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
        filter = "all";
        applyFilter();
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
        card.setClipToOutline(true);
        LinearLayout body = column();
        body.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(12));
        card.addView(body);
        option.card = card;
        FrameLayout heading = new FrameLayout(page);
        TextView source = label(option.source, 16, false);
        source.setTextColor(style.foreground);
        source.setGravity(Gravity.CENTER_VERTICAL);
        source.setMinHeight(style.dp(48));
        FrameLayout.LayoutParams sourcePlacement = new FrameLayout.LayoutParams(-1, -2);
        sourcePlacement.setMarginEnd(style.dp(40));
        heading.addView(source, sourcePlacement);
        RadioButton radio =
                new RadioButton(page) {
                    private final android.graphics.Paint ink =
                            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

                    @Override
                    protected void onDraw(android.graphics.Canvas canvas) {
                        float x = getWidth() / 2f, y = getHeight() / 2f;
                        ink.setColor(style.foreground);
                        ink.setStyle(android.graphics.Paint.Style.STROKE);
                        ink.setStrokeWidth(style.dp(2));
                        canvas.drawCircle(x, y, style.dp(9), ink);
                        if (isChecked()) {
                            ink.setStyle(android.graphics.Paint.Style.FILL);
                            canvas.drawCircle(x, y, style.dp(5), ink);
                        }
                    }
                };
        radio.setButtonDrawable((android.graphics.drawable.Drawable) null);
        radio.setBackground(null);
        radio.setClickable(false);
        radio.setFocusable(false);
        radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams radioPlacement =
                new FrameLayout.LayoutParams(style.dp(32), style.dp(32), Gravity.TOP | Gravity.END);
        radioPlacement.setMarginEnd(-style.dp(7));
        heading.addView(radio, radioPlacement);
        option.radio = radio;
        body.addView(heading);
        TextView track = label(join(option.title, option.artist), 14, false);
        track.setMaxLines(2);
        body.addView(track);
        TextView quality =
                label(
                        option.quality.isEmpty()
                                ? PatchStrings.t("Reported format unavailable")
                                : option.quality,
                        14,
                        true);
        quality.setPadding(0, style.dp(9), 0, style.dp(4));
        body.addView(quality);
        if (!option.detail.isEmpty()) body.addView(label(option.detail, 12, false));
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
            preview.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    style.explorerIcon("preview", style.foreground), null, null, null);
            preview.setOnClickListener(v -> option.preview.run());
            preview.setVisibility(View.GONE);
            option.previewAction = preview;
            body.addView(preview);
        }
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2);
        placement.bottomMargin = style.dp(10);
        group.addView(card, placement);
        options.add(option);
        applyFilter();
    }

    private void choose(Option option) {
        if (busy && option != null) return;
        if (footer.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) footer.getParent()).removeView(footer);
        selected = option;
        if (admissionNotice != null) message("");
        for (Option item : options) {
            item.radio.setChecked(item == option);
            item.card.setSelected(item == option);
            item.card.setBackground(style.shape(style.card, 22, false));
            if (item.previewAction != null)
                item.previewAction.setVisibility(item == option ? View.VISIBLE : View.GONE);
        }
        if (option != null) {
            ((LinearLayout) option.card).addView(footer, new LinearLayout.LayoutParams(-1, -2));
            option.card.post(
                    () -> {
                        android.graphics.Rect bounds =
                                new android.graphics.Rect(
                                        0, 0, footer.getWidth(), footer.getHeight());
                        footer.requestRectangleOnScreen(bounds, false);
                    });
        }
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
            item.card.setBackground(style.shape(style.card, 22, false));
        }
        for (Map.Entry<LinearLayout, Boolean> entry : groups.entrySet())
            entry.getKey().setVisibility(entry.getValue() ? View.VISIBLE : View.GONE);
        for (TextView chip : chips) {
            boolean active = chip.getTag().equals("explorer-filter:" + filter);
            chip.setSelected(active);
            chip.setTextColor(active ? android.graphics.Color.WHITE : style.secondary);
            android.graphics.drawable.GradientDrawable shape =
                    style.shape(active ? 0xff56649f : style.card, 25, false);
            shape.setStroke(
                    style.dp(1), active ? 0xff6371ac : (style.dark ? 0xff3b3b40 : style.outline));
            chip.setBackground(shape);
            String id = chip.getTag().toString();
            if (id.endsWith(":lossless") || id.endsWith(":available"))
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        style.explorerIcon(
                                id.endsWith(":lossless") ? "lossless" : "available",
                                active ? android.graphics.Color.WHITE : style.secondary),
                        null,
                        null,
                        null);
        }
        boolean knownAvailability = options.stream().anyMatch(item -> item.availabilityKnown);
        availabilityChip.setVisibility(knownAvailability ? View.VISIBLE : View.GONE);
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
        view.setBaselineAligned(false);
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
        Button button =
                new Button(page) {
                    @Override
                    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                        super.onSizeChanged(w, h, oldw, oldh);
                        float group = getPaint().measureText(getText().toString()) + style.dp(29);
                        int edge = Math.max(style.dp(10), (int) ((w - group) / 2f) - style.dp(2));
                        if (getPaddingStart() != edge)
                            setPaddingRelative(edge, style.dp(8), edge, style.dp(8));
                    }
                };
        button.setText(label);
        button.setSingleLine(true);
        style.button(button);
        button.setTypeface(style.regular);
        button.setMinHeight(style.dp(48));
        button.setMinimumHeight(style.dp(48));
        button.setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8));
        int fill =
                style.dark
                        ? (primary ? 0xff282735 : 0xff3e3e40)
                        : (primary ? 0xffe2e4f5 : style.card);
        android.graphics.drawable.GradientDrawable shape = style.shape(fill, 24, false);
        shape.setStroke(
                style.dp(1), style.dark ? (primary ? 0xff484651 : 0xff66666a) : style.outline);
        button.setBackground(shape);
        int ink = primary ? style.foreground : (style.dark ? 0xffb9b9be : style.secondary);
        button.setTextColor(ink);
        button.setCompoundDrawablePadding(style.dp(7));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(
                style.explorerIcon(primary ? "now" : "later", ink), null, null, null);
        return button;
    }

    private static String join(String left, String right) {
        if (left == null || left.isEmpty()) return right == null ? "" : right;
        return (left == null ? "" : left) + (right == null || right.isEmpty() ? "" : " · " + right);
    }
}
