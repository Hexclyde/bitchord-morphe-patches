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
        View previewAction, infoAction;
        SoulseekTrack previewTrack;
        String previewSession;
        String family = "addons", filePath = "";
        long bytes = -1;
        boolean requested;

        Option inlinePreview(SoulseekTrack track, String session) {
            previewTrack = track;
            previewSession = session;
            return this;
        }

        Option fromFamily(String family, long bytes, boolean requested) {
            this.family = family;
            this.bytes = bytes;
            this.requested = requested;
            return this;
        }

        Option filePath(String path) {
            filePath = path;
            return this;
        }

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
    private boolean busy, searching, searched;
    private String family = "all", sort = "order";
    private Spinner familyPicker;
    private TextView resultCount, availabilityHint;
    private final List<String> familyIds = new ArrayList<>();

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
        query.setBackground(null);
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
        LinearLayout controls = row();
        familyPicker = new Spinner(page);
        familyPicker.setTag("explorer-source-family");
        familyPicker.setContentDescription(PatchStrings.t("Filter download sources"));
        familyPicker.setBackground(null);
        familyPicker.setMinimumHeight(style.dp(48));
        style.markCustom(familyPicker);
        controls.addView(familyPicker, new LinearLayout.LayoutParams(-2, style.dp(48)));
        familyPicker.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    public void onNothingSelected(AdapterView<?> parent) {}

                    public void onItemSelected(
                            AdapterView<?> parent, View child, int position, long id) {
                        if (position >= familyIds.size()) return;
                        String next = familyIds.get(position);
                        if (!family.equals(next)) {
                            family = next;
                            applyFilter();
                        }
                    }
                });
        resultCount = label("", 12, false);
        resultCount.setTag("explorer-result-count");
        resultCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        resultCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(resultCount, new LinearLayout.LayoutParams(0, style.dp(48), 1));
        ImageButton sortButton = new ImageButton(page);
        sortButton.setImageDrawable(style.viewIcon("sort"));
        sortButton.setContentDescription(PatchStrings.t("Sort source results"));
        sortButton.setBackground(null);
        sortButton.setPadding(style.dp(13), style.dp(13), style.dp(13), style.dp(13));
        style.markCustom(sortButton);
        sortButton.setOnClickListener(v -> sortDialog());
        controls.addView(sortButton, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        content.addView(controls);
        availabilityHint =
                label(
                        PatchStrings.t(
                                "Free peer slot reported. Sources with unknown readiness are under"
                                        + " All."),
                        12,
                        false);
        availabilityHint.setPadding(style.dp(2), 0, style.dp(2), style.dp(10));
        availabilityHint.setVisibility(View.GONE);
        content.addView(availabilityHint);
        status = label(PatchStrings.t("Nothing downloads until you choose a result."), 13, false);
        status.setPadding(0, style.dp(10), 0, style.dp(10));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        results = column();
        results.setTag("download-explorer-results");
        content.addView(results);
        empty =
                label(
                        PatchStrings.t("No matching sources. Try All formats or another source."),
                        15,
                        false);
        empty.setPadding(0, style.dp(24), 0, style.dp(24));
        empty.setVisibility(View.GONE);
        content.addView(empty);
        TextView clearFilters = label(PatchStrings.t("Clear filters"), 14, true);
        clearFilters.setTag("explorer-clear-filters");
        clearFilters.setGravity(Gravity.CENTER);
        clearFilters.setMinHeight(style.dp(48));
        clearFilters.setBackground(style.shape(style.card, 24, false));
        clearFilters.setOnClickListener(
                v -> {
                    filter = "all";
                    family = "all";
                    applyFilter();
                });
        content.addView(clearFilters);
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
        updateFamilies();
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
        searching = true;
        family = "all";
        choose(null);
        filter = "all";
        updateFamilies();
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
        LinearLayout body = column();
        body.setPadding(style.dp(15), style.dp(15), style.dp(15), style.dp(13));
        FrameLayout stack = new FrameLayout(page);
        stack.addView(body, new FrameLayout.LayoutParams(-1, -2));
        card.addView(stack);
        option.card = card;
        FrameLayout heading = new FrameLayout(page);
        TextView source = label(option.source, 16, false);
        source.setTextColor(style.foreground);
        source.setGravity(Gravity.CENTER_VERTICAL);
        source.setMinHeight(style.dp(26));
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
                        ink.setColor(
                                isChecked()
                                        ? (style.dark ? 0xffb2bce4 : 0xff586591)
                                        : (style.dark ? 0xffa7a7af : 0xff787884));
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
        radioPlacement.setMarginEnd(-style.dp(4));
        heading.addView(radio, radioPlacement);
        option.radio = radio;
        body.addView(heading);
        TextView track = label(join(option.title, option.artist), 13, false);
        track.setTag("explorer-track-title");
        track.setPadding(0, style.dp(5), style.dp(40), 0);
        body.addView(track);
        String qualityText =
                option.quality.isEmpty()
                        ? PatchStrings.t("Reported format unavailable")
                        : option.quality;
        int qualifier = qualityText.indexOf(" · ");
        String qualityLine = qualifier < 0 ? qualityText : qualityText.substring(0, qualifier);
        String[] formatParts = qualityLine.split("\\s+", 2);
        String formatName =
                formatParts[0].matches(
                                "(?i)FLAC|ALAC|WAV|WAVE|AIFF|AIFC|DSD|DSF|DFF|MP3|AAC|OPUS|VORBIS|OGG|APE|WV|WAVPACK|TTA|TAK|SHN|WMA|PCM")
                        ? formatParts[0]
                        : PatchStrings.t("Audio");
        String qualityValue =
                formatName.equals(PatchStrings.t("Audio"))
                        ? qualityLine
                        : (formatParts.length > 1 ? formatParts[1] : "");
        LinearLayout quality = row();
        quality.setPadding(0, style.dp(11), 0, style.dp(4));
        TextView badge = label(formatName, 11, true);
        badge.setTag("explorer-format-badge");
        badge.setPadding(style.dp(6), style.dp(3), style.dp(6), style.dp(3));
        android.graphics.drawable.GradientDrawable badgeShape =
                style.shape(style.dark ? 0xff242427 : 0xffffffff, 6, false);
        badgeShape.setStroke(Math.max(1, style.dp(1)), style.dark ? 0xff484852 : 0xffc7c7d0);
        badge.setBackground(badgeShape);
        quality.addView(badge);
        if (!qualityValue.isEmpty()) {
            TextView qualityLabel = label(qualityValue, 14, true);
            qualityLabel.setTag("explorer-track-format");
            qualityLabel.setPadding(style.dp(8), 0, 0, 0);
            quality.addView(qualityLabel, new LinearLayout.LayoutParams(0, -2, 1));
        }
        body.addView(quality);
        TextView evidence =
                label(
                        PatchStrings.t(
                                option.requested
                                        ? "Requested quality · availability unverified"
                                        : "Reported quality · recording unverified"),
                        12,
                        false);
        evidence.setPadding(0, 0, 0, style.dp(8));
        body.addView(evidence);
        if (!option.detail.isEmpty()) body.addView(label(option.detail, 12, false));
        String readiness =
                option.reportedAvailable
                        ? "Free peer slot reported"
                        : option.availabilityKnown
                                ? "Peer slot busy"
                                : option.requested
                                        ? "Prepared after selection"
                                        : "Readiness not reported";
        TextView ready = label(PatchStrings.t(readiness), 12, false);
        if (option.reportedAvailable) ready.setTextColor(style.dark ? 0xff9cc8a7 : 0xff386647);
        ready.setCompoundDrawablesRelativeWithIntrinsicBounds(
                style.explorerIcon(
                        option.reportedAvailable ? "available" : "clock",
                        ready.getCurrentTextColor()),
                null,
                null,
                null);
        ready.setCompoundDrawablePadding(style.dp(5));
        ready.setPadding(0, style.dp(2), 0, 0);
        body.addView(ready);
        ImageButton info = new ImageButton(page);
        info.setImageDrawable(style.viewIcon("info"));
        info.setBackground(null);
        info.setContentDescription(PatchStrings.f("Details for {0}", option.source));
        info.setPadding(style.dp(15), style.dp(15), style.dp(15), style.dp(15));
        style.markCustom(info);
        info.setOnClickListener(v -> details(option));
        FrameLayout.LayoutParams infoPlacement =
                new FrameLayout.LayoutParams(style.dp(48), style.dp(48), Gravity.TOP | Gravity.END);
        infoPlacement.topMargin = style.dp(38);
        infoPlacement.setMarginEnd(style.dp(2));
        stack.addView(info, infoPlacement);
        info.setVisibility(View.GONE);
        option.infoAction = info;
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> choose(option));
        body.setOnClickListener(v -> choose(option));
        body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        if (option.previewTrack != null) {
            InlinePreview preview =
                    new InlinePreview(page.activity, option.previewTrack, option.previewSession);
            preview.setVisibility(View.GONE);
            option.previewAction = preview;
            body.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        } else if (option.preview != null) {
            Button preview = action(PatchStrings.t("Preview file"), false);
            preview.setTextSize(13);
            preview.setBackground(style.shape(android.graphics.Color.TRANSPARENT, 12, true));
            preview.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    style.explorerIcon("preview", style.foreground), null, null, null);
            preview.setOnClickListener(v -> option.preview.run());
            preview.setVisibility(View.GONE);
            option.previewAction = preview;
            LinearLayout.LayoutParams previewPlacement = new LinearLayout.LayoutParams(-1, -2);
            previewPlacement.topMargin = style.dp(10);
            body.addView(preview, previewPlacement);
        }
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2);
        placement.bottomMargin = style.dp(10);
        group.setVisibility(View.GONE);
        results.addView(card, placement);
        options.add(option);
        updateFamilies();
        applyFilter();
    }

    private void choose(Option option) {
        if (busy && option != null) return;
        if (selected == option && option != null) return;
        if (footer.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) footer.getParent()).removeView(footer);
        selected = option;
        if (admissionNotice != null) message("");
        for (Option item : options) {
            item.radio.setChecked(item == option);
            item.card.setSelected(item == option);
            surface(item);
            if (item.infoAction != null)
                item.infoAction.setVisibility(item == option ? View.VISIBLE : View.GONE);
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
        return (family.equals("all") || family.equals(item.family))
                && (filter.equals("all")
                        || filter.equals("lossless") && item.lossless
                        || filter.equals("available") && item.reportedAvailable);
    }

    private void applyFilter() {
        if (filter.equals("available")
                && options.stream()
                        .noneMatch(
                                item ->
                                        (family.equals("all") || family.equals(item.family))
                                                && item.availabilityKnown)) filter = "all";
        if (selected != null && !matches(selected)) choose(null);
        int visible = 0;
        Map<LinearLayout, Boolean> groups = new HashMap<>();
        List<Option> ordered = new ArrayList<>(options);
        if (sort.equals("quality"))
            ordered.sort(
                    Comparator.comparing((Option item) -> item.lossless)
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingDouble(
                                                    (Option item) -> reportedRate(item.quality))
                                            .reversed()));
        if (sort.equals("size"))
            ordered.sort(
                    Comparator.comparingLong(item -> item.bytes > 0 ? item.bytes : Long.MAX_VALUE));
        int position = 0;
        for (Option item : ordered) {
            if (results.indexOfChild(item.card) != position) {
                results.removeView(item.card);
                results.addView(item.card, position);
            }
            position++;
        }
        for (Option item : options) {
            boolean show = matches(item);
            item.card.setVisibility(show ? View.VISIBLE : View.GONE);
            groups.put(item.group, groups.getOrDefault(item.group, false) || show);
            if (show) visible++;
            surface(item);
        }
        for (int i = 0; i < results.getChildCount(); i++) {
            View child = results.getChildAt(i);
            if (child instanceof LinearLayout
                    && options.stream().noneMatch(item -> item.card == child))
                child.setVisibility(
                        !searching && options.isEmpty() && filter.equals("all")
                                ? View.VISIBLE
                                : View.GONE);
        }
        for (TextView chip : chips) {
            boolean active = chip.getTag().equals("explorer-filter:" + filter);
            chip.setSelected(active);
            chip.setTextColor(
                    active
                            ? (style.dark ? android.graphics.Color.WHITE : 0xff293251)
                            : style.secondary);
            android.graphics.drawable.GradientDrawable shape =
                    style.shape(
                            active ? (style.dark ? 0xff56649f : 0xffe2e6f5) : style.card,
                            25,
                            false);
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
        resultCount.setText(
                visible == 1 ? PatchStrings.t("1 result") : PatchStrings.f("{0} results", visible));
        availabilityHint.setVisibility(filter.equals("available") ? View.VISIBLE : View.GONE);
        root.findViewWithTag("explorer-clear-filters")
                .setVisibility(
                        !searching
                                        && visible == 0
                                        && (!filter.equals("all") || !family.equals("all"))
                                ? View.VISIBLE
                                : View.GONE);
        boolean knownAvailability =
                options.stream()
                        .anyMatch(
                                item ->
                                        (family.equals("all") || family.equals(item.family))
                                                && item.availabilityKnown);
        availabilityChip.setVisibility(knownAvailability ? View.VISIBLE : View.GONE);
        empty.setVisibility(searched && !searching && visible == 0 ? View.VISIBLE : View.GONE);
    }

    void searching(boolean value) {
        searching = value;
        if (value) searched = true;
        status.setVisibility(value ? View.VISIBLE : options.isEmpty() ? View.VISIBLE : View.GONE);
        applyFilter();
    }

    private void surface(Option item) {
        boolean selected = item == this.selected;
        android.graphics.drawable.GradientDrawable shape =
                style.shape(
                        selected ? (style.dark ? 0xff202126 : 0xfff0f2fb) : style.card, 22, false);
        shape.setStroke(Math.max(1, style.dp(1)), selected ? 0xff6977ac : style.outline);
        item.card.setBackground(shape);
    }

    private void updateFamilies() {
        List<String> next = new ArrayList<>();
        next.add("all");
        for (String id : new String[] {"addons", "soulseek", "telegram"})
            if (options.stream().anyMatch(option -> option.family.equals(id))) next.add(id);
        if (next.equals(familyIds)) return;
        familyIds.clear();
        familyIds.addAll(next);
        List<String> labels = new ArrayList<>();
        for (String id : familyIds)
            labels.add(
                    PatchStrings.t(
                            id.equals("all")
                                    ? "All sources"
                                    : id.equals("addons")
                                            ? "Add-ons"
                                            : id.equals("soulseek") ? "Soulseek" : "Telegram"));
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(page, android.R.layout.simple_spinner_item, labels) {
                    @Override
                    public View getView(int at, View recycled, android.view.ViewGroup parent) {
                        TextView view = (TextView) super.getView(at, recycled, parent);
                        view.setTextColor(style.foreground);
                        view.setTextSize(13);
                        view.setTypeface(style.regular);
                        android.graphics.drawable.Drawable arrow = style.viewIcon("down");
                        arrow.setBounds(0, 0, style.dp(14), style.dp(14));
                        view.setCompoundDrawablesRelative(null, null, arrow, null);
                        view.setCompoundDrawablePadding(style.dp(7));
                        return view;
                    }
                };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        familyPicker.setAdapter(adapter);
        if (!familyIds.contains(family)) family = "all";
        familyPicker.setSelection(familyIds.indexOf(family));
    }

    private void sortDialog() {
        new android.app.AlertDialog.Builder(page)
                .setTitle(PatchStrings.t("Sort source results"))
                .setSingleChoiceItems(
                        new String[] {
                            PatchStrings.t("Best match"),
                            PatchStrings.t("Reported quality"),
                            PatchStrings.t("Smallest reported file")
                        },
                        Arrays.asList("order", "quality", "size").indexOf(sort),
                        (dialog, which) -> {
                            sort = Arrays.asList("order", "quality", "size").get(which);
                            applyFilter();
                            dialog.dismiss();
                        })
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    private static double reportedRate(String quality) {
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile(
                                "([0-9]+(?:\\.[0-9]+)?)\\s*(kHz|Hz)",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(quality);
        if (!match.find()) return -1;
        try {
            return Double.parseDouble(match.group(1))
                    * (match.group(2).equalsIgnoreCase("kHz") ? 1000 : 1);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private void details(Option option) {
        String message =
                join(option.title, option.artist) + "\n\n" + option.quality + "\n" + option.detail;
        if (!option.filePath.isEmpty()) message += "\n\n" + option.filePath;
        new android.app.AlertDialog.Builder(page)
                .setTitle(option.source)
                .setMessage(message)
                .setPositiveButton(PatchStrings.t("Done"), null)
                .show();
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
                        ? (primary ? 0xff30354d : 0xff303033)
                        : (primary ? 0xffe2e6f5 : 0xffeeeef2);
        android.graphics.drawable.GradientDrawable shape = style.shape(fill, 24, false);
        shape.setStroke(
                style.dp(1),
                style.dark
                        ? (primary ? 0xff50597e : 0xff505055)
                        : (primary ? 0xffb9c0dd : 0xffc5c5cd));
        button.setBackground(shape);
        int ink = style.dark ? 0xfff5f5f7 : (primary ? 0xff293251 : 0xff36363d);
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
