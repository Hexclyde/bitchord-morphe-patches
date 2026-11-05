package app.bitchord.soulseek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class BatchDownloadPage extends DownloadPage {
    private static final String NONE = "\u0000none", ALL = "\u0001all", ENUMERATION = "*";

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    // Candidate lookups are bounded and cancellable; they never share the submit executor.
    private final ThreadPoolExecutor lookupWork =
            new ThreadPoolExecutor(
                    3,
                    3,
                    20,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(24),
                    runnable -> {
                        Thread thread = new Thread(runnable, "batch-candidates");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final List<Future<?>> futures = new CopyOnWriteArrayList<>();
    private final AtomicLong lookupVersion = new AtomicLong();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Tile> tiles = new HashMap<>();
    private final Set<Integer> expanded = new HashSet<>();
    private final Map<Integer, Lookup> lookups = new HashMap<>();
    private final Map<Integer, String> filters = new HashMap<>();
    private final Map<Integer, Integer> shown = new HashMap<>();
    // What each card's search box holds; the requested track itself never changes.
    private final Map<Integer, String> drafts = new HashMap<>();
    DownloadBatch.Searcher searcher;
    InlinePreview.PlayerFactory previewFactory;
    private HorizontalScrollView sourceStrip;
    private LinearLayout rows, sourceRow;
    private TextView status;
    private String token, sourceSignature;
    private boolean foreground, busy;
    private Future<?> task;
    private long generation;

    BatchDownloadPage(Activity activity, Intent intent) {
        super(activity, intent);
    }

    private DownloadBatch.Searcher searcher() {
        if (searcher == null) searcher = DownloadBatch.searcher(getApplicationContext());
        return searcher;
    }

    @Override
    public void onCreate(Bundle saved) {
        token = getIntent().getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        text(body, PatchStrings.t("Batch source selection"), 28);
        text(
                body,
                PatchStrings.t(
                        "Choose each track, or find unique title and artist matches in one source."
                            + " Review reported quality before submitting. Unselected tracks stay"
                            + " out of the queue."),
                15);
        status = text(body, "", 15);
        // One scrolling strip keeps the track list on screen however many sources are enabled.
        sourceStrip = new HorizontalScrollView(this);
        sourceStrip.setHorizontalScrollBarEnabled(false);
        sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceStrip.addView(sourceRow);
        body.addView(sourceStrip);
        fillSources(Collections.emptyList());
        rows = new LinearLayout(this);
        rows.setOrientation(1);
        body.addView(rows);
        button(body, PatchStrings.t("Download selected now"), () -> confirm(true));
        button(body, PatchStrings.t("Add selected to download queue"), () -> confirm(false));
        setContentView(body);
    }

    @Override
    protected void onStart() {
        foreground = true;
        render();
        loadSources();
        // Expanded cards restart interrupted lookups.
        for (int index : new ArrayList<>(expanded))
            if (!lookups.containsKey(index)
                    && tiles.containsKey(index)
                    && tiles.get(index).toggle != null) startLookup(index);
    }

    @Override
    protected void onStop() {
        foreground = false;
        generation++;
        busy = false;
        if (task != null) task.cancel(true);
        for (Tile tile : tiles.values()) tile.dropPreview();
        lookupVersion.incrementAndGet();
        for (Future<?> search : futures) search.cancel(true);
        futures.clear();
        lookupWork.getQueue().clear();
        lookups.values().removeIf(lookup -> lookup.pending > 0);
    }

    @Override
    protected void onDestroy() {
        for (Tile tile : tiles.values()) tile.dropPreview();
        lookupVersion.incrementAndGet();
        lookupWork.shutdownNow();
        work.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Lists the sources off the UI thread; only the strip changes when the answer arrives. */
    private void loadSources() {
        DownloadBatch.Searcher searcher = searcher();
        long version = lookupVersion.get();
        submit(
                null,
                () -> {
                    List<DownloadBatch.SourceRef> found;
                    try {
                        found = searcher.sources();
                    } catch (Exception | LinkageError unavailable) {
                        return;
                    }
                    ui.post(
                            () -> {
                                if (!foreground || isFinishing() || version != lookupVersion.get())
                                    return;
                                fillSources(found);
                            });
                },
                () -> {});
    }

    private void fillSources(List<DownloadBatch.SourceRef> found) {
        boolean soulseek = DownloadSettings.enabled(this, DownloadSettings.SOULSEEK);
        List<DownloadBatch.SourceRef> shownSources =
                found.subList(0, Math.min(found.size(), DownloadBatch.SOURCES));
        StringBuilder signature = new StringBuilder(soulseek ? "soulseek" : "");
        for (DownloadBatch.SourceRef ref : shownSources)
            signature.append('\n').append(ref.id).append('\n').append(ref.name);
        if (signature.toString().equals(sourceSignature)) return;
        sourceSignature = signature.toString();
        sourceRow.removeAllViews();
        if (soulseek)
            capsule(
                    "batch-find:soulseek",
                    PatchStrings.t("Find matches in Soulseek"),
                    () -> run(() -> DownloadBatch.findSoulseek(token, getApplicationContext())));
        for (DownloadBatch.SourceRef ref : shownSources)
            capsule(
                    "batch-find:" + ref.id,
                    PatchStrings.f("Find matches in {0}", ref.name),
                    () ->
                            run(
                                    () ->
                                            DownloadBatch.find(
                                                    token, ref.source, getApplicationContext())));
        sourceStrip.setVisibility(sourceRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void capsule(String tag, String value, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setTag(tag);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
        space.setMarginEnd(downloadStyle.dp(8));
        sourceRow.addView(button, space);
        downloadStyle.button(button);
        button.setTextSize(14);
    }

    private String tracksStatus() {
        List<?> songs = SoulseekDownloadChoice.songs(token);
        return songs.isEmpty()
                ? PatchStrings.t(
                        "This batch expired. Return to the album or playlist and try again.")
                : PatchStrings.f("{0} tracks · review source and quality for each", songs.size());
    }

    private void render() {
        rows.removeAllViews();
        tiles.clear();
        status.setText(tracksStatus());
        List<?> songs = SoulseekDownloadChoice.songs(token);
        for (int i = 0; i < songs.size(); i++) {
            Tile tile = new Tile(i, songs.get(i));
            tiles.put(i, tile);
            rows.addView(tile.card);
        }
    }

    /** Refreshes only the cards whose stored choice changed, so focus and scroll stay put. */
    private void syncTiles() {
        for (Tile tile : new ArrayList<>(tiles.values())) tile.sync();
    }

    /** One requested track. Its card is built once; expanding only fills {@code body}. */
    private final class Tile {
        final int index;
        final LinearLayout card, summary, body, controls;
        final String origTitle, origArtist;
        final ImageButton clear;
        Button toggle;
        private String seen;
        private boolean built;
        private TextView state;
        private HorizontalScrollView chipScroll;
        private LinearLayout chipRow, failures, list, more, previewSlot;
        private EditText query;
        private DownloadBatch.Candidate previewFor;
        private String failureSignature = "";
        private final Map<String, Button> chips = new LinkedHashMap<>();
        private final Map<String, View> listed = new LinkedHashMap<>();
        private final Map<String, String> listedSignature = new HashMap<>();

        Tile(int index, Object song) {
            this.index = index;
            card = new LinearLayout(BatchDownloadPage.this);
            downloadStyle.card(card);
            origTitle = HostDownloadAccess.propertyText(song, "title");
            origArtist = HostDownloadAccess.propertyText(song, "artist");
            label(card, origTitle + " · " + origArtist, 17, true);
            label(
                    card,
                    PatchStrings.t("Duration")
                            + " · "
                            + DownloadDetails.referenceDuration(
                                    HostDownloadAccess.propertyText(song, "durationText")),
                    14,
                    false);
            summary = new LinearLayout(BatchDownloadPage.this);
            summary.setOrientation(LinearLayout.VERTICAL);
            summary.setTag("batch-selected:" + index);
            card.addView(summary);
            // One row: the wide source toggle and, when a file is chosen, a 48dp clear control.
            controls = new LinearLayout(BatchDownloadPage.this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(controls);
            clear = clearButton();
            DownloadBatch.Choice choice = DownloadBatch.choice(token, index);
            if (choice == null || !choice.submitted) {
                toggle = action(controls, "", "batch-toggle:" + index, () -> toggle(this));
                LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0, -2, 1);
                wide.setMarginEnd(downloadStyle.dp(8));
                wide.topMargin = downloadStyle.dp(6);
                wide.bottomMargin = downloadStyle.dp(4);
                toggle.setLayoutParams(wide);
            } else controls.setVisibility(View.GONE);
            body = new LinearLayout(BatchDownloadPage.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setTag("batch-candidates:" + index);
            card.addView(body);
            bindSummary();
            bindToggle();
            bindBody();
            seen = signature();
        }

        private String signature() {
            DownloadBatch.Choice choice = DownloadBatch.choice(token, index);
            return choice == null
                    ? ""
                    : System.identityHashCode(choice)
                            + ":"
                            + choice.submitted
                            + ":"
                            + choice.submitting
                            + ":"
                            + choice.error;
        }

        /** Applies a changed stored choice (bulk match, submission) without rebuilding the card. */
        void sync() {
            String now = signature();
            if (now.equals(seen)) return;
            seen = now;
            DownloadBatch.Choice choice = DownloadBatch.choice(token, index);
            if (choice != null && choice.submitted && toggle != null) {
                expanded.remove(index);
                controls.removeView(toggle);
                controls.setVisibility(View.GONE);
                toggle = null;
                dropPreview();
                body.removeAllViews();
                built = false;
                query = null;
                previewSlot = null;
                chips.clear();
                listed.clear();
                listedSignature.clear();
                failureSignature = "";
            }
            bindSummary();
            bindBody();
        }

        void selectionChanged() {
            seen = signature();
            bindSummary();
            bindBody();
        }

        void bindSummary() {
            summary.removeAllViews();
            DownloadBatch.Choice choice = DownloadBatch.choice(token, index);
            String submitted =
                    choice != null && choice.submitted ? " · " + PatchStrings.t("Submitted") : "";
            if (choice == null) label(summary, PatchStrings.t("Source not selected"), 14, false);
            else if (choice.candidate == null) label(summary, choice.detail + submitted, 14, false);
            else {
                DownloadBatch.Candidate c = choice.candidate;
                label(
                        summary,
                        PatchStrings.t("Selected audio file") + " · " + provider(c) + submitted,
                        14,
                        false);
                // The requested track is already the card's title; only a different version adds
                // one.
                if (differsFromRequest(c)) label(summary, DownloadBatch.titleArtist(c), 15, true);
                label(summary, DownloadBatch.qualityLine(c), 13, false);
                label(summary, DownloadBatch.clockLine(BatchDownloadPage.this, c), 13, false);
            }
            if (choice != null && !choice.error.isEmpty())
                label(summary, PatchStrings.t(choice.error), 14, false);
            bindClear(choice);
        }

        private boolean differsFromRequest(DownloadBatch.Candidate c) {
            return (!c.title.trim().isEmpty()
                            && !DownloadBatch.normalized(c.title)
                                    .equals(DownloadBatch.normalized(origTitle)))
                    || (!c.artist.trim().isEmpty()
                            && !DownloadBatch.normalized(c.artist)
                                    .equals(DownloadBatch.normalized(origArtist)));
        }

        private ImageButton clearButton() {
            ImageButton button = new ImageButton(BatchDownloadPage.this);
            button.setTag("batch-unselect:" + index);
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setImageDrawable(downloadStyle.viewIcon("minus"));
            button.setContentDescription(PatchStrings.t("Unselect") + ": " + origTitle);
            button.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(downloadStyle.dark ? 0x228e8e93 : 0x22000000),
                            downloadStyle.shape(downloadStyle.field, 24, false),
                            null));
            button.setOnClickListener(
                    v -> {
                        DownloadBatch.clear(token, index);
                        selectionChanged();
                    });
            downloadStyle.markCustom(button);
            return button;
        }

        /** Clearing only drops the stored choice; it never touches files or transfers. */
        private void bindClear(DownloadBatch.Choice choice) {
            boolean want = toggle != null && choice != null && !choice.submitted;
            if (want && clear.getParent() == null)
                controls.addView(
                        clear,
                        new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
            if (!want && clear.getParent() != null) controls.removeView(clear);
            clear.setEnabled(choice == null || !choice.submitting);
        }

        void bindToggle() {
            if (toggle == null) return;
            boolean open = expanded.contains(index);
            String value = PatchStrings.t(open ? "Hide sources" : "Choose source and quality");
            toggle.setText(value);
            toggle.setContentDescription(value);
            Drawable chevron = downloadStyle.viewIcon("down");
            toggle.setCompoundDrawablePadding(downloadStyle.dp(8));
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    null, null, open ? new Flipped(chevron) : chevron, null);
            if (Build.VERSION.SDK_INT >= 30)
                toggle.setStateDescription(PatchStrings.t(open ? "Expanded" : "Collapsed"));
        }

        private void build() {
            // The search box holds only the search text; the requested track stays as it was.
            LinearLayout box = new LinearLayout(BatchDownloadPage.this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            query = new EditText(BatchDownloadPage.this);
            query.setTag("batch-query:" + index);
            query.setSingleLine(true);
            query.setInputType(InputType.TYPE_CLASS_TEXT);
            query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            query.setHint(PatchStrings.t("Artist and song title"));
            query.setContentDescription(PatchStrings.t("Artist and song title"));
            query.setText(drafts.containsKey(index) ? drafts.get(index) : originalQuery(index));
            query.addTextChangedListener(
                    new TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                        public void onTextChanged(CharSequence s, int a, int b, int c) {}

                        public void afterTextChanged(Editable s) {
                            drafts.put(index, s.toString());
                        }
                    });
            query.setOnEditorActionListener(
                    (v, actionId, event) -> {
                        if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
                        startLookup(index, query.getText().toString());
                        return true;
                    });
            box.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
            downloadStyle.field(query);
            action(
                    box,
                    PatchStrings.t("Search"),
                    "batch-search:" + index,
                    () -> startLookup(index, query.getText().toString()));
            body.addView(box);
            state = label(body, "", 14, false);
            state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            previewSlot = new LinearLayout(BatchDownloadPage.this);
            previewSlot.setOrientation(LinearLayout.VERTICAL);
            previewSlot.setTag("batch-preview:" + index);
            body.addView(previewSlot);
            chipScroll = new HorizontalScrollView(BatchDownloadPage.this);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipRow = new LinearLayout(BatchDownloadPage.this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipScroll.addView(chipRow);
            body.addView(chipScroll);
            failures = new LinearLayout(BatchDownloadPage.this);
            failures.setOrientation(LinearLayout.VERTICAL);
            body.addView(failures);
            list = new LinearLayout(BatchDownloadPage.this);
            list.setOrientation(LinearLayout.VERTICAL);
            body.addView(list);
            more = new LinearLayout(BatchDownloadPage.this);
            more.setOrientation(LinearLayout.VERTICAL);
            body.addView(more);
            built = true;
        }

        /** Updates the open card in place: unchanged views keep their focus and scroll. */
        void bindBody() {
            // A submitted track has no toggle, so its candidates stay closed.
            boolean open = expanded.contains(index) && toggle != null;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            if (!open) {
                dropPreview();
                return;
            }
            if (!built) build();
            View focus = body.findFocus();
            Object focused = focus == null ? null : focus.getTag();
            Lookup lookup = lookups.get(index);
            boolean any = lookup != null && !lookup.items.isEmpty();
            if (lookup == null) state.setText(PatchStrings.t("Searching…"));
            else if (!lookup.note.isEmpty()) state.setText(lookup.note);
            else if (lookup.pending > 0)
                state.setText(
                        lookup.items.isEmpty()
                                ? PatchStrings.t("Searching…")
                                : PatchStrings.f(
                                        "Searching · {0} sources still working", lookup.pending));
            else
                state.setText(PatchStrings.f("Search finished · {0} results", lookup.items.size()));
            chipScroll.setVisibility(any ? View.VISIBLE : View.GONE);
            if (lookup != null) {
                syncChips(lookup);
                syncFailures(lookup);
                syncList(lookup);
            } else {
                syncChips(null);
                list.removeAllViews();
                listed.clear();
                listedSignature.clear();
                more.removeAllViews();
            }
            syncPreview();
            if (focused != null && body.findFocus() == null) {
                View again = body.findViewWithTag(focused);
                if (again != null && again.isEnabled()) again.requestFocus();
            }
        }

        void dropPreview() {
            if (previewSlot != null) previewSlot.removeAllViews();
            previewFor = null;
        }

        /** One preview for the chosen supported Soulseek file; it never starts by itself. */
        private void syncPreview() {
            DownloadBatch.Choice choice = DownloadBatch.choice(token, index);
            DownloadBatch.Candidate c = choice == null ? null : choice.candidate;
            boolean show =
                    c != null
                            && c.soulseek
                            && c.track != null
                            && !locked(choice)
                            && c.current.getAsBoolean()
                            && SoulseekPreview.available(BatchDownloadPage.this)
                            && SoulseekPreview.supported(c.track);
            if (!show) {
                dropPreview();
                return;
            }
            if (previewFor == c && previewSlot.getChildCount() == 1) return;
            previewSlot.removeAllViews();
            previewSlot.addView(
                    previewFactory == null
                            ? new InlinePreview(activity, c.track, c.session)
                            : new InlinePreview(activity, c.track, c.session, previewFactory),
                    new LinearLayout.LayoutParams(-1, -2));
            previewFor = c;
        }

        private void syncChips(Lookup lookup) {
            LinkedHashSet<String> specs = new LinkedHashSet<>();
            boolean unknown = false;
            if (lookup != null)
                for (DownloadBatch.Candidate c : lookup.items) {
                    String spec = DownloadBatch.spec(c);
                    if (spec.isEmpty()) unknown = true;
                    else specs.add(spec);
                }
            String filter = filters.get(index);
            if (filter != null && !(filter.equals(NONE) ? unknown : specs.contains(filter))) {
                filters.remove(index);
                filter = null;
            }
            List<String> wanted = new ArrayList<>();
            if (lookup != null && !lookup.items.isEmpty()) {
                wanted.add(ALL);
                wanted.addAll(specs);
                if (unknown) wanted.add(NONE);
            }
            for (Iterator<Map.Entry<String, Button>> it = chips.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<String, Button> entry = it.next();
                if (wanted.contains(entry.getKey())) continue;
                chipRow.removeView(entry.getValue());
                it.remove();
            }
            for (String key : wanted) {
                if (chips.containsKey(key)) continue;
                Button chip = chip(key);
                chips.put(key, chip);
                Button none = chips.get(NONE);
                // New formats slot in before "Not reported" so existing chips never move.
                int at =
                        key.equals(ALL)
                                ? 0
                                : none == null || key.equals(NONE)
                                        ? chipRow.getChildCount()
                                        : chipRow.indexOfChild(none);
                chipRow.addView(chip, at);
                styleChip(chip, key, filter);
            }
            for (Map.Entry<String, Button> entry : chips.entrySet())
                styleChip(entry.getValue(), entry.getKey(), filter);
        }

        private Button chip(String key) {
            Button chip = new Button(BatchDownloadPage.this);
            chip.setText(
                    key.equals(ALL)
                            ? PatchStrings.t("All")
                            : key.equals(NONE) ? PatchStrings.t("Not reported") : key);
            chip.setTag("batch-filter:" + index + ":" + (key.equals(ALL) ? "all" : key));
            chip.setOnClickListener(
                    v -> {
                        if (key.equals(ALL)) filters.remove(index);
                        else filters.put(index, key);
                        shown.remove(index);
                        bindBody();
                    });
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
            space.setMarginEnd(downloadStyle.dp(8));
            chip.setLayoutParams(space);
            downloadStyle.button(chip);
            chip.setTextSize(14);
            return chip;
        }

        private void styleChip(Button chip, String key, String filter) {
            boolean on = key.equals(ALL) ? filter == null : key.equals(filter);
            chip.setSelected(on);
            chip.setTextColor(on ? downloadStyle.background : downloadStyle.foreground);
            chip.setBackground(
                    downloadStyle.shape(
                            on ? downloadStyle.foreground : downloadStyle.field, 28, false));
        }

        private void syncFailures(Lookup lookup) {
            // Offers from an ended Soulseek session get one Retry that searches the source again.
            Map<String, String> stale = new LinkedHashMap<>();
            for (DownloadBatch.Candidate c : lookup.items)
                if (!c.current.getAsBoolean()
                        && lookup.searches.containsKey(c.sourceKey)
                        && !lookup.failures.containsKey(c.sourceKey)
                        && !lookup.running.contains(c.sourceKey))
                    stale.putIfAbsent(c.sourceKey, c.sourceName);
            StringBuilder signature = new StringBuilder();
            for (Failure failure : lookup.failures.values())
                signature.append(failure.key).append('\n').append(failure.message).append('\n');
            for (String key : stale.keySet()) signature.append("stale:").append(key).append('\n');
            if (signature.toString().equals(failureSignature)) return;
            failureSignature = signature.toString();
            failures.removeAllViews();
            for (Failure failure : new ArrayList<>(lookup.failures.values())) {
                label(
                        failures,
                        failure.name.isEmpty()
                                ? failure.message
                                : failure.name + ": " + failure.message,
                        14,
                        false);
                action(
                        failures,
                        PatchStrings.t("Retry"),
                        "batch-retry:" + index + ":" + failure.key,
                        () -> retry(index, failure));
            }
            for (Map.Entry<String, String> entry : stale.entrySet()) {
                Failure again =
                        new Failure(
                                entry.getKey(),
                                entry.getValue(),
                                "",
                                lookup.searches.get(entry.getKey()));
                action(
                        failures,
                        PatchStrings.t("Retry"),
                        "batch-retry:" + index + ":" + entry.getKey(),
                        () -> retry(index, again));
            }
        }

        private void syncList(Lookup lookup) {
            String filter = filters.get(index);
            List<DownloadBatch.Candidate> visible = new ArrayList<>();
            for (DownloadBatch.Candidate c : lookup.items)
                if (filter == null
                        || (filter.equals(NONE)
                                ? DownloadBatch.spec(c).isEmpty()
                                : filter.equals(DownloadBatch.spec(c)))) visible.add(c);
            int limit = shown.getOrDefault(index, DownloadBatch.PER_SOURCE);
            List<DownloadBatch.Candidate> wanted =
                    visible.subList(0, Math.min(limit, visible.size()));
            DownloadBatch.Choice choice = DownloadBatch.choice(token, index);
            for (Iterator<Map.Entry<String, View>> it = listed.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<String, View> entry = it.next();
                DownloadBatch.Candidate c = null;
                for (DownloadBatch.Candidate item : wanted)
                    if (item.key.equals(entry.getKey())) c = item;
                if (c != null && rowSignature(c, choice).equals(listedSignature.get(c.key)))
                    continue;
                list.removeView(entry.getValue());
                listedSignature.remove(entry.getKey());
                it.remove();
            }
            for (int i = 0; i < wanted.size(); i++) {
                DownloadBatch.Candidate c = wanted.get(i);
                View row = listed.get(c.key);
                if (row == null) {
                    row = candidateRow(c, choice);
                    listed.put(c.key, row);
                    listedSignature.put(c.key, rowSignature(c, choice));
                    list.addView(row, i);
                } else if (list.getChildAt(i) != row) {
                    list.removeView(row);
                    list.addView(row, i);
                }
            }
            more.removeAllViews();
            if (visible.size() > limit)
                action(
                        more,
                        PatchStrings.f(
                                "Show {0} more",
                                Math.min(DownloadBatch.PER_SOURCE, visible.size() - limit)),
                        "batch-more:" + index,
                        () -> {
                            shown.put(index, limit + DownloadBatch.PER_SOURCE);
                            bindBody();
                        });
        }

        private boolean locked(DownloadBatch.Choice choice) {
            return choice != null && (choice.submitted || choice.submitting);
        }

        private boolean chosen(DownloadBatch.Candidate c, DownloadBatch.Choice choice) {
            // A refreshed offer with the same reference is a new instance: it must be chosen again.
            return choice != null && c.sameOffer(choice.candidate);
        }

        private String rowSignature(DownloadBatch.Candidate c, DownloadBatch.Choice choice) {
            // The offer instance changes whenever a refresh reports new fields or a new callback.
            return System.identityHashCode(c)
                    + ":"
                    + chosen(c, choice)
                    + ":"
                    + locked(choice)
                    + ":"
                    + c.current.getAsBoolean();
        }

        /** A flat divided row: the radio is the 48dp target, the row forwards taps to it. */
        private View candidateRow(DownloadBatch.Candidate c, DownloadBatch.Choice choice) {
            boolean selected = chosen(c, choice);
            boolean stale = !c.current.getAsBoolean();
            LinearLayout row = new LinearLayout(BatchDownloadPage.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setTag("batch-candidate:" + c.key);
            row.setClickable(true);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            View divider = new View(BatchDownloadPage.this);
            divider.setBackgroundColor(downloadStyle.outline);
            row.addView(divider, new LinearLayout.LayoutParams(-1, downloadStyle.dp(1)));
            LinearLayout line = new LinearLayout(BatchDownloadPage.this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, downloadStyle.dp(6), 0, downloadStyle.dp(6));
            row.addView(line, new LinearLayout.LayoutParams(-1, -2));

            RadioButton radio = new RadioButton(BatchDownloadPage.this);
            radio.setTag("batch-choose:" + index + ":" + c.key);
            downloadStyle.applyTree(radio);
            radio.setMinWidth(downloadStyle.dp(48));
            radio.setMinimumWidth(downloadStyle.dp(48));
            radio.setMinHeight(downloadStyle.dp(48));
            radio.setMinimumHeight(downloadStyle.dp(48));
            radio.setChecked(selected);
            // A chosen offer from an ended session stays visible but cannot be picked again.
            radio.setEnabled(!c.tooLarge() && !stale && !locked(choice));
            radio.setContentDescription(
                    PatchStrings.t(selected ? "Selected audio file" : "Choose this source")
                            + ": "
                            + DownloadBatch.titleArtist(c)
                            + " · "
                            + c.sourceName
                            + " · "
                            + DownloadBatch.audioLine(c)
                            + " · "
                            + DownloadBatch.fileLine(BatchDownloadPage.this, c));
            radio.setOnClickListener(
                    v -> {
                        if (!selected) choose(this, c);
                        // A failed choice must not leave the radio looking selected.
                        radio.setChecked(chosen(c, DownloadBatch.choice(token, index)));
                    });
            line.addView(radio, new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(
                    v -> {
                        if (radio.isEnabled()) radio.performClick();
                    });

            LinearLayout meta = new LinearLayout(BatchDownloadPage.this);
            meta.setOrientation(LinearLayout.VERTICAL);
            line.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            // Flat rows: the provider leads unless this file is a different version of the track.
            boolean other = differsFromRequest(c);
            if (other) label(meta, DownloadBatch.titleArtist(c), 15, true);
            label(meta, provider(c), other ? 13 : 15, !other);
            label(meta, DownloadBatch.qualityLine(c), 13, false);
            label(meta, DownloadBatch.clockLine(BatchDownloadPage.this, c), 13, false);
            if (c.tooLarge())
                label(meta, PatchStrings.t("Selected files are limited to 256 MiB."), 13, false);
            else if (stale)
                label(
                        meta,
                        PatchStrings.t("This Soulseek result expired. Search again."),
                        13,
                        false);
            return row;
        }
    }

    private static String provider(DownloadBatch.Candidate c) {
        return c.sourceName + (c.soulseek ? " · " + PatchStrings.f("Peer: {0}", c.peer) : "");
    }

    private static final class Failure {
        final String key, name, message;
        final Search search;

        Failure(String key, String name, String message, Search search) {
            this.key = key;
            this.name = name;
            this.message = message;
            this.search = search;
        }
    }

    interface Search {
        List<DownloadBatch.Candidate> call() throws Exception;
    }

    /** Results of one expanded track. Touched only on the UI thread. */
    private final class Lookup {
        final long version = lookupVersion.get();
        final String query;
        final List<DownloadBatch.Candidate> items = new ArrayList<>();
        final Map<String, Failure> failures = new LinkedHashMap<>();
        final Map<String, Search> searches = new HashMap<>();
        final Map<String, Integer> runs = new HashMap<>();
        final Set<String> running = new HashSet<>();
        final List<Future<?>> futures = new CopyOnWriteArrayList<>();
        String note = "";
        int pending;

        Lookup(String query) {
            this.query = query;
        }
    }

    private void toggle(Tile tile) {
        if (!expanded.remove(tile.index)) {
            expanded.add(tile.index);
            if (!lookups.containsKey(tile.index)) startLookup(tile.index);
        }
        tile.bindToggle();
        tile.bindBody();
    }

    private boolean live(int index, Lookup lookup) {
        return foreground
                && !isFinishing()
                && lookups.get(index) == lookup
                && lookup.version == lookupVersion.get()
                && SoulseekDownloadChoice.available(token);
    }

    private void bindOpen(int index) {
        Tile tile = tiles.get(index);
        if (tile != null) tile.bindBody();
    }

    /** The requested track's own artist and title; the search box starts from it. */
    private String originalQuery(int index) {
        List<?> songs = SoulseekDownloadChoice.songs(token);
        if (index < 0 || index >= songs.size()) return "";
        Object song = songs.get(index);
        return (HostDownloadAccess.propertyText(song, "artist")
                        + " "
                        + HostDownloadAccess.propertyText(song, "title"))
                .trim();
    }

    private void startLookup(int index) {
        startLookup(index, drafts.containsKey(index) ? drafts.get(index) : originalQuery(index));
    }

    /** Starts this card's search over; only this card's older lookup is cancelled and ignored. */
    private void startLookup(int index, String text) {
        if (index < 0 || index >= SoulseekDownloadChoice.songs(token).size()) return;
        String query = text.trim();
        Lookup old = lookups.get(index);
        if (old != null) {
            for (Future<?> search : old.futures) search.cancel(true);
            lookupWork.purge();
        }
        filters.remove(index);
        shown.remove(index);
        Lookup lookup = new Lookup(query);
        lookups.put(index, lookup);
        if (query.length() < 2 || query.length() > 256)
            lookup.note = PatchStrings.t("Enter an artist and song title, up to 256 characters.");
        else enumerate(index, lookup);
        bindOpen(index);
    }

    private void submit(Lookup owner, Runnable task, Runnable rejected) {
        futures.removeIf(Future::isDone);
        try {
            Future<?> started = lookupWork.submit(task);
            futures.add(started);
            if (owner != null) owner.futures.add(started);
        } catch (RejectedExecutionException busy) {
            rejected.run();
        }
    }

    private String sourceFailed(String key) {
        return key.equals("soulseek")
                ? PatchStrings.t("Soulseek is not ready. Check account and setup.")
                : PatchStrings.t("Source did not answer. Check its connection and retry.");
    }

    private String searchBusy() {
        return PatchStrings.t("Search is busy. Wait for current sources and try again.");
    }

    /** Lists the enabled sources off the UI thread, then starts one bounded search per source. */
    private void enumerate(int index, Lookup lookup) {
        DownloadBatch.Searcher searcher = searcher();
        android.content.Context app = getApplicationContext();
        String query = lookup.query;
        lookup.pending++;
        lookup.failures.remove(ENUMERATION);
        submit(
                lookup,
                () -> {
                    List<DownloadBatch.SourceRef> refs = new ArrayList<>();
                    boolean soulseek = false, failed = false;
                    try {
                        refs.addAll(searcher.sources());
                        soulseek = searcher.soulseekEnabled();
                    } catch (Exception | LinkageError unavailable) {
                        failed = true;
                    }
                    final boolean withSoulseek = soulseek, bad = failed;
                    ui.post(
                            () -> {
                                if (!live(index, lookup)) return;
                                lookup.pending--;
                                if (bad)
                                    lookup.failures.put(
                                            ENUMERATION,
                                            new Failure(
                                                    ENUMERATION,
                                                    "",
                                                    sourceFailed(ENUMERATION),
                                                    null));
                                else {
                                    for (DownloadBatch.SourceRef ref :
                                            refs.subList(
                                                    0,
                                                    Math.min(refs.size(), DownloadBatch.SOURCES)))
                                        launch(
                                                index,
                                                lookup,
                                                ref.id,
                                                ref.name,
                                                () ->
                                                        DownloadBatch.fromSource(
                                                                ref.id,
                                                                ref.name,
                                                                searcher.search(ref, query),
                                                                app));
                                    if (withSoulseek)
                                        launch(
                                                index,
                                                lookup,
                                                "soulseek",
                                                "Soulseek",
                                                () ->
                                                        DownloadBatch.fromSoulseek(
                                                                searcher.soulseek(query), app));
                                }
                                bindOpen(index);
                            });
                },
                () -> {
                    lookup.pending--;
                    lookup.failures.put(
                            ENUMERATION, new Failure(ENUMERATION, "", searchBusy(), null));
                });
    }

    private void launch(int index, Lookup lookup, String key, String name, Search search) {
        lookup.pending++;
        lookup.failures.remove(key);
        lookup.searches.put(key, search);
        lookup.running.add(key);
        // Only the newest run of a source may replace its offers.
        int run = lookup.runs.merge(key, 1, Integer::sum);
        submit(
                lookup,
                () -> {
                    List<DownloadBatch.Candidate> found = null;
                    boolean failed = false;
                    try {
                        found = search.call();
                    } catch (Exception | LinkageError unavailable) {
                        failed = true;
                    }
                    if (Thread.currentThread().isInterrupted()) return;
                    final List<DownloadBatch.Candidate> result = found;
                    final boolean bad = failed;
                    ui.post(
                            () -> {
                                if (!live(index, lookup)) return;
                                lookup.pending--;
                                if (lookup.runs.get(key) != run) {
                                    bindOpen(index);
                                    return;
                                }
                                lookup.running.remove(key);
                                if (bad)
                                    lookup.failures.put(
                                            key, new Failure(key, name, sourceFailed(key), search));
                                else {
                                    lookup.items.removeIf(c -> c.sourceKey.equals(key));
                                    lookup.items.addAll(result);
                                }
                                bindOpen(index);
                            });
                },
                () -> {
                    lookup.pending--;
                    lookup.running.remove(key);
                    lookup.failures.put(key, new Failure(key, name, searchBusy(), search));
                });
    }

    private void retry(int index, Failure failure) {
        Lookup lookup = lookups.get(index);
        if (lookup == null || !live(index, lookup)) return;
        if (failure.search == null) enumerate(index, lookup);
        else launch(index, lookup, failure.key, failure.name, failure.search);
        bindOpen(index);
    }

    /** Stores the offer for later confirmation. Nothing is requested here. */
    private void choose(Tile tile, DownloadBatch.Candidate candidate) {
        if (!candidate.current.getAsBoolean()) {
            status.setText(PatchStrings.t("This Soulseek result expired. Search again."));
            tile.bindBody();
            return;
        }
        try {
            DownloadBatch.select(token, tile.index, candidate);
        } catch (IllegalStateException | IllegalArgumentException blocked) {
            status.setText(
                    PatchStrings.t(
                            "This track was submitted or the batch expired. Return to the batch"
                                    + " to review it."));
            return;
        }
        tile.selectionChanged();
    }

    private void confirm(boolean now) {
        if (busy) return;
        int selected = 0;
        for (int i = 0; i < SoulseekDownloadChoice.songs(token).size(); i++) {
            DownloadBatch.Choice choice = DownloadBatch.choice(token, i);
            if (choice != null && !choice.submitted) selected++;
        }
        if (selected == 0) {
            status.setText(PatchStrings.t("Select at least one track first."));
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(
                        PatchStrings.f(
                                "Submit {0} selected tracks? Existing transfers continue. Each"
                                        + " track keeps its chosen source.",
                                selected))
                .setPositiveButton(
                        PatchStrings.t("Continue"),
                        (dialog, which) -> run(() -> DownloadBatch.submit(token, now)))
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    interface Action {
        void run() throws Exception;
    }

    private void run(Action action) {
        if (busy || work.isShutdown()) return;
        busy = true;
        status.setText(PatchStrings.t("Working…"));
        final long operation = ++generation;
        task =
                work.submit(
                        () -> {
                            String error = "";
                            try {
                                action.run();
                            } catch (Exception failed) {
                                error =
                                        PatchStrings.t(
                                                "Batch action failed. Review the selections and"
                                                        + " retry.");
                            }
                            final String result = error;
                            ui.post(
                                    () -> {
                                        if (operation != generation) return;
                                        busy = false;
                                        if (foreground) {
                                            syncTiles();
                                            status.setText(
                                                    result.isEmpty() ? tracksStatus() : result);
                                        }
                                    });
                        });
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        parent.addView(text);
        return text;
    }

    private TextView label(LinearLayout parent, String value, int size, boolean primary) {
        TextView view =
                downloadStyle.text(
                        value,
                        size,
                        primary ? downloadStyle.foreground : downloadStyle.secondary,
                        primary ? downloadStyle.medium : downloadStyle.regular);
        view.setPadding(0, downloadStyle.dp(4), 0, downloadStyle.dp(4));
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        view.setGravity(Gravity.START);
        parent.addView(view);
        return view;
    }

    private Button action(LinearLayout parent, String value, String tag, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setTag(tag);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
        downloadStyle.button(button);
        return button;
    }

    private void button(LinearLayout parent, String value, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }

    /** The shared chevron turned upside down for the open state. */
    private static final class Flipped extends Drawable {
        private final Drawable inner;

        Flipped(Drawable inner) {
            this.inner = inner;
        }

        @Override
        public void draw(Canvas canvas) {
            inner.setBounds(getBounds());
            canvas.save();
            canvas.rotate(180, getBounds().exactCenterX(), getBounds().exactCenterY());
            inner.draw(canvas);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return inner.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return inner.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            inner.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
