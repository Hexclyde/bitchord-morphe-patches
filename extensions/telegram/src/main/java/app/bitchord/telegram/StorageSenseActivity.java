package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browse what's stored, see what each deletion costs, and choose cleanup rules. */
public final class StorageSenseActivity extends Activity {
    private static final int PAGE = 30;
    private static final StorageSense.Category[] ORDER = {
        StorageSense.Category.PLAYBACK_CACHE,
        StorageSense.Category.ADDON_CACHE,
        StorageSense.Category.TELEGRAM_CACHE,
        StorageSense.Category.PATCH_CACHE,
        StorageSense.Category.SAVED
    };

    /** Replaced by tests with owners that need no host app. */
    static volatile java.util.function.Function<Context, StorageSenseEngine> engines =
            StorageSenseAuto::engine;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "storage-sense");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final EnumSet<StorageSense.Category> expanded =
            EnumSet.noneOf(StorageSense.Category.class);
    private final Map<StorageSense.Category, Integer> shown =
            new EnumMap<>(StorageSense.Category.class);
    private TelegramStyle style;
    private StorageSenseEngine engine;
    private StorageSense.Rules rules;
    private LinearLayout sections, rulesHost, duplicatesHost, otherHost;
    private TextView status;
    private StorageSenseEngine.Snapshot snapshot;
    private AtomicBoolean scan = new AtomicBoolean();
    private AtomicBoolean work = new AtomicBoolean();
    private boolean scanning, busy, otherOpen, started;
    private List<StorageSensePatchCaches.Unmanaged> other;
    private StorageSense.DuplicateReview review;
    private String refocus;

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Strings.refresh(this);
        style = new TelegramStyle(this);
        engine = engines.apply(this);
        rules = StorageSenseAuto.rules(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(style.background);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(32));
        scroll.addView(body);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title = style.text(Strings.t("Storage Sense"), 28, style.foreground, style.heavy);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.leftMargin = style.dp(12);
        header.addView(title, titleSpace);
        body.addView(header);
        status = style.text("", 14, style.secondary, style.regular);
        status.setPadding(0, style.dp(12), 0, style.dp(12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        sections = column(body);
        heading(body, Strings.t("Cleanup rules"));
        rulesHost = column(body);
        heading(body, Strings.t("Exact duplicates"));
        duplicatesHost = column(body);
        heading(body, Strings.t("Not managed here"));
        otherHost = column(body);
        setContentView(scroll);
        renderRules();
        renderDuplicates();
        renderOther();
        renderSections();
    }

    private LinearLayout column(LinearLayout parent) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new LinearLayout.LayoutParams(-1, -2));
        return column;
    }

    private void heading(LinearLayout parent, String text) {
        TextView heading = style.text(text, 18, style.foreground, style.semibold);
        heading.setPadding(0, style.dp(24), 0, style.dp(8));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        parent.addView(heading);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (!busy) load();
    }

    @Override
    protected void onStop() {
        started = false;
        scan.set(true);
        work.set(true);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        scan.set(true);
        work.set(true);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void onUi(Runnable action) {
        ui.post(
                () -> {
                    if (!isDestroyed()) action.run();
                });
    }

    /** Looks only. Nothing is removed by opening, scanning or rendering this page. */
    private void load() {
        scan.set(true);
        AtomicBoolean token = new AtomicBoolean();
        scan = token;
        scanning = true;
        if (snapshot == null) status.setText(Strings.t("Checking what's stored…"));
        renderSections();
        worker.execute(
                () -> {
                    StorageSenseEngine.Snapshot next = engine.load(token::get);
                    if (token.get()) return;
                    onUi(
                            () -> {
                                if (token != scan) return;
                                scanning = false;
                                snapshot = next;
                                renderSections();
                                renderRules();
                                summarize();
                            });
                });
    }

    private void summarize() {
        long cache = 0, saved = 0;
        for (StorageSenseEngine.Section section : snapshot.sections.values()) {
            if (section.category == StorageSense.Category.SAVED) saved += section.bytes;
            else cache += section.bytes;
        }
        status.setText(
                Strings.f(
                        "{0} in caches · {1} in saved downloads",
                        StorageSense.size(cache), StorageSense.size(saved)));
    }

    static String categoryTitle(StorageSense.Category category) {
        switch (category) {
            case PLAYBACK_CACHE:
                return Strings.t("BitChord song cache");
            case ADDON_CACHE:
                return Strings.t("Addon audio in the song cache");
            case TELEGRAM_CACHE:
                return Strings.t("Telegram media cache");
            case PATCH_CACHE:
                return Strings.t("Patch caches");
            default:
                return Strings.t("Saved downloads");
        }
    }

    static String reason(StorageSense.Block block) {
        switch (block) {
            case PLAYING:
                return Strings.t("Kept because it's playing or coming up next.");
            case DOWNLOADING:
                return Strings.t("Kept because a download is using it.");
            case TRANSFER_ACTIVE:
                return Strings.t("Kept because a transfer is using it.");
            case RECENTLY_USED:
                return Strings.t("Kept because it was used moments ago. Try again later.");
            case STATE_UNKNOWN:
                return Strings.t(
                        "Kept because BitChord couldn't confirm that it isn't in use. Try again"
                                + " while music is stopped.");
            case READ_ONLY:
                return Strings.t(
                        "Shown for review only. Clear it from the settings of the feature that"
                                + " owns it.");
            case LOCATION_UNPROVEN:
                return Strings.t(
                        "Kept because its location couldn't be confirmed as a file this app owns.");
            default:
                return "";
        }
    }

    private String sectionSummary(StorageSenseEngine.Section section) {
        if (section == null) return scanning ? Strings.t("Checking…") : Strings.t("Not available");
        switch (section.state) {
            case EMPTY:
                return Strings.t("Nothing stored here");
            case UNAVAILABLE:
                return section.message;
            case ERROR:
                return section.message.isEmpty()
                        ? Strings.t("This couldn't be read. Tap to try again.")
                        : Strings.f("This couldn't be read: {0}", section.message);
            default:
                String summary =
                        Strings.f(
                                section.items.size() == 1 ? "{0} item · {1}" : "{0} items · {1}",
                                Strings.number(section.items.size()),
                                StorageSense.size(section.bytes));
                return section.truncated
                        ? summary + " · " + Strings.t("Showing the first ones found")
                        : summary;
        }
    }

    private void renderSections() {
        sections.removeAllViews();
        for (StorageSense.Category category : ORDER) {
            StorageSenseEngine.Section section =
                    snapshot == null ? null : snapshot.sections.get(category);
            // A category whose owner isn't in this install has nothing to say.
            if (section == null && snapshot != null) continue;
            LinearLayout group = PlaylistRows.group(sections, style);
            LinearLayout.LayoutParams gap = (LinearLayout.LayoutParams) group.getLayoutParams();
            gap.bottomMargin = style.dp(8);
            boolean open = expanded.contains(category);
            boolean items = section != null && !section.items.isEmpty();
            View row =
                    PlaylistRows.add(
                            group,
                            style,
                            categoryTitle(category),
                            sectionSummary(section),
                            () -> {
                                if (section != null
                                        && section.state == StorageSenseEngine.State.ERROR) load();
                                else if (items) {
                                    if (!expanded.remove(category)) expanded.add(category);
                                    renderSections();
                                }
                            });
            row.setContentDescription(
                    categoryTitle(category)
                            + ", "
                            + sectionSummary(section)
                            + (items
                                    ? ", " + (open ? Strings.t("Expanded") : Strings.t("Collapsed"))
                                    : ""));
            if (open && items) renderItems(group, section);
        }
    }

    private void renderItems(LinearLayout group, StorageSenseEngine.Section section) {
        StorageSense.Plan clear = StorageSense.clearable(section.items);
        if (!clear.items.isEmpty())
            PlaylistRows.add(
                    group,
                    style,
                    Strings.f("Clear {0} that aren't in use", Strings.number(clear.items.size())),
                    Strings.f(
                            "Frees {0}. Saved downloads aren't touched.",
                            StorageSense.size(clear.bytes)),
                    () -> confirmClear(clear));
        List<StorageSense.Item> sorted = new ArrayList<>(section.items);
        java.util.Collections.sort(
                sorted, (a, b) -> Long.compare(Math.max(0, b.bytes), Math.max(0, a.bytes)));
        int limit = Math.min(sorted.size(), shown.getOrDefault(section.category, PAGE));
        for (int i = 0; i < limit; i++) {
            StorageSense.Item item = sorted.get(i);
            PlaylistRows.add(group, style, item.title, line(item), () -> info(item));
        }
        if (limit < sorted.size())
            PlaylistRows.add(
                    group,
                    style,
                    Strings.f(
                            "Show {0} more", Strings.number(Math.min(PAGE, sorted.size() - limit))),
                    "",
                    () -> {
                        shown.put(section.category, limit + PAGE);
                        renderSections();
                    });
    }

    private static String bytes(StorageSense.Item item) {
        return item.bytes < 0 ? Strings.t("Size unavailable") : StorageSense.size(item.bytes);
    }

    private static String originLabel(StorageSense.Item item) {
        switch (item.origin) {
            case "YOUTUBE":
                return "YouTube";
            case "JIOSAAVN":
                return "JioSaavn";
            case "OTHER":
                return Strings.t("Addon");
            case "TELEGRAM":
                return "Telegram";
            default:
                return "";
        }
    }

    private static String line(StorageSense.Item item) {
        StringBuilder line = new StringBuilder(bytes(item));
        if (!item.subtitle.isEmpty()) line.append(" · ").append(item.subtitle);
        if (item.effect == StorageSense.Effect.CACHE_RECLAIM)
            line.append(" · ")
                    .append(item.complete ? Strings.t("Whole song") : Strings.t("Partial"));
        if (item.block != StorageSense.Block.NONE && item.block != StorageSense.Block.READ_ONLY)
            line.append(" · ").append(Strings.t("Protected"));
        return line.toString();
    }

    private void info(StorageSense.Item item) {
        StringBuilder text = new StringBuilder(bytes(item));
        if (!item.subtitle.isEmpty()) text.append('\n').append(item.subtitle);
        String origin = originLabel(item);
        if (!origin.isEmpty()) text.append('\n').append(Strings.f("Source: {0}", origin));
        if (item.lastUsedMs > 0)
            text.append('\n')
                    .append(
                            Strings.f(
                                    "Last used: {0}",
                                    DateUtils.getRelativeTimeSpanString(item.lastUsedMs)));
        if (item.effect == StorageSense.Effect.CACHE_RECLAIM) {
            text.append('\n')
                    .append(
                            item.complete
                                    ? Strings.t("The whole song is cached.")
                                    : Strings.t("Only part of this song is cached."));
            if (!item.ranges.isEmpty()) {
                StringBuilder ranges = new StringBuilder();
                for (StorageSense.Range range : item.ranges) {
                    if (ranges.length() > 0) ranges.append(", ");
                    ranges.append(StorageSense.size(range.start))
                            .append('–')
                            .append(StorageSense.size(range.end));
                }
                text.append('\n').append(Strings.f("Cached ranges: {0}", ranges));
            }
        }
        if (!item.location.isEmpty())
            text.append('\n').append(Strings.f("Resource: {0}", item.location));
        if (item.block != StorageSense.Block.NONE) text.append("\n\n").append(reason(item.block));
        else if (item.effect == StorageSense.Effect.DELETE_SAVED_FILE)
            text.append("\n\n").append(Strings.t("Deleting this removes the file for good."));
        else text.append("\n\n").append(Strings.t("Clearing this only frees cache space."));
        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(item.title)
                        .setMessage(text)
                        .setNegativeButton(Strings.t("Close"), null);
        if (item.removable())
            builder.setPositiveButton(
                    item.effect == StorageSense.Effect.DELETE_SAVED_FILE
                            ? Strings.t("Delete file…")
                            : Strings.t("Clear from cache…"),
                    (d, w) -> confirmItem(item));
        show(builder, item.removable());
    }

    private void show(AlertDialog.Builder builder, boolean destructive) {
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(
                d -> {
                    style.tuneDialog(dialog);
                    Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (positive != null && destructive)
                        positive.setTextColor(style.dark ? 0xffff6961 : 0xffc4001a);
                });
        dialog.show();
    }

    private void confirmItem(StorageSense.Item item) {
        boolean saved = item.effect == StorageSense.Effect.DELETE_SAVED_FILE;
        String message =
                saved
                        ? Strings.f(
                                "\"{0}\" ({1}) is permanently deleted from this device. It won't"
                                        + " play offline until you download it again.",
                                item.title, bytes(item))
                        : Strings.f(
                                "\"{0}\" frees {1}. It can stream and cache again the next time it"
                                        + " plays. Saved downloads aren't touched.",
                                item.title, bytes(item));
        show(
                new AlertDialog.Builder(this)
                        .setTitle(
                                saved
                                        ? Strings.t("Delete this saved file?")
                                        : Strings.t("Clear this from the cache?"))
                        .setMessage(message)
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .setPositiveButton(
                                saved ? Strings.t("Delete file") : Strings.t("Clear from cache"),
                                (d, w) -> remove(java.util.Collections.singletonList(item))),
                true);
    }

    private void confirmClear(StorageSense.Plan plan) {
        String kept =
                plan.protectedCount > 0
                        ? " " + Strings.f("{0} in use stay.", Strings.number(plan.protectedCount))
                        : "";
        show(
                new AlertDialog.Builder(this)
                        .setTitle(
                                Strings.f(
                                        "Clear {0} cached items?",
                                        Strings.number(plan.items.size())))
                        .setMessage(
                                Strings.f(
                                                "Frees {0}. They can stream and cache again the"
                                                        + " next time they play. Saved downloads"
                                                        + " aren't touched.",
                                                StorageSense.size(plan.bytes))
                                        + kept)
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .setPositiveButton(
                                Strings.t("Clear from cache"), (d, w) -> remove(plan.items)),
                true);
    }

    private void remove(List<StorageSense.Item> items) {
        if (busy) return;
        busy = true;
        scan.set(true);
        AtomicBoolean token = new AtomicBoolean();
        work = token;
        status.setText(Strings.t("Removing…"));
        worker.execute(
                () -> {
                    StorageSenseEngine.Outcome outcome = engine.remove(items, token::get);
                    onUi(
                            () -> {
                                busy = false;
                                review = null;
                                renderDuplicates();
                                report(outcome);
                                if (started) load();
                            });
                });
    }

    private void report(StorageSenseEngine.Outcome outcome) {
        String message;
        if (outcome.removed > 0 && outcome.blocked + outcome.failed == 0)
            message =
                    Strings.f(
                            "Freed {0} from {1} items.",
                            StorageSense.size(outcome.bytes), Strings.number(outcome.removed));
        else if (outcome.removed > 0)
            message =
                    Strings.f(
                            "Freed {0} from {1} items. {2} couldn't be removed.",
                            StorageSense.size(outcome.bytes),
                            Strings.number(outcome.removed),
                            Strings.number(outcome.blocked + outcome.failed));
        else if (outcome.blocked > 0) message = reason(outcome.firstBlock);
        else if (outcome.failed > 0)
            message =
                    outcome.firstError.isEmpty()
                            ? Strings.t("That couldn't be removed. Nothing changed.")
                            : outcome.firstError;
        else message = Strings.t("Nothing was removed.");
        status.setText(message);
        status.announceForAccessibility(message);
    }

    private void switchRow(
            LinearLayout group,
            String tag,
            String title,
            String detail,
            boolean checked,
            TelegramSwitch.Listener listener) {
        LinearLayout row =
                DownloadsSettings.switchRow(group, style, title, detail, checked, listener);
        row.getChildAt(1).setTag(tag);
    }

    private void renderRules() {
        rulesHost.removeAllViews();
        TextView intro =
                style.text(
                        Strings.t(
                                "Off until you turn one on. Rules run at most once a day after"
                                        + " BitChord starts, only reach cache that's proven safe,"
                                        + " and never touch saved downloads."),
                        13,
                        style.secondary,
                        style.regular);
        intro.setPadding(0, 0, 0, style.dp(8));
        rulesHost.addView(intro);
        LinearLayout group = PlaylistRows.group(rulesHost, style);
        switchRow(
                group,
                "rule-age",
                Strings.t("Clear cache not played lately"),
                rules.ageOn
                        ? Strings.f("Older than {0} days", String.valueOf(rules.ageDays))
                        : Strings.t("Off"),
                rules.ageOn,
                (view, on) -> {
                    rules.ageOn = on;
                    refocus = "rule-age";
                    saveRules();
                });
        if (rules.ageOn) {
            String[] labels = new String[StorageSense.Rules.AGE_DAYS.length];
            int selected = 0;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = Strings.f("{0} days", String.valueOf(StorageSense.Rules.AGE_DAYS[i]));
                if (StorageSense.Rules.AGE_DAYS[i] == rules.ageDays) selected = i;
            }
            DownloadsSettings.chips(
                    group,
                    style,
                    labels,
                    selected,
                    index -> {
                        rules.ageDays = StorageSense.Rules.AGE_DAYS[index];
                        refocus = "chip-" + labels[index];
                        saveRules();
                    });
        }
        switchRow(
                group,
                "rule-size",
                Strings.t("Keep caches under a size"),
                rules.sizeOn
                        ? Strings.f("Under {0}, oldest first", StorageSense.size(rules.maxBytes))
                        : Strings.t("Off"),
                rules.sizeOn,
                (view, on) -> {
                    rules.sizeOn = on;
                    refocus = "rule-size";
                    saveRules();
                });
        if (rules.sizeOn) {
            String[] labels = new String[StorageSense.Rules.SIZE_BYTES.length];
            int selected = 0;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = StorageSense.size(StorageSense.Rules.SIZE_BYTES[i]);
                if (StorageSense.Rules.SIZE_BYTES[i] == rules.maxBytes) selected = i;
            }
            DownloadsSettings.chips(
                    group,
                    style,
                    labels,
                    selected,
                    index -> {
                        rules.maxBytes = StorageSense.Rules.SIZE_BYTES[index];
                        refocus = "chip-" + labels[index];
                        saveRules();
                    });
        }
        if (rules.any()) {
            String last = StorageSenseAuto.lastResult(this);
            PlaylistRows.add(
                    group,
                    style,
                    Strings.t("Review and run now"),
                    last.isEmpty() ? Strings.t("Shows what the rules would clear first") : last,
                    this::confirmRules);
        }
    }

    /** Saving changes the rows, so TalkBack is put back on the control that was just used. */
    private void saveRules() {
        StorageSenseAuto.save(this, rules);
        renderRules();
        View target = refocus == null ? null : rulesHost.findViewWithTag(refocus);
        refocus = null;
        android.view.accessibility.AccessibilityManager access =
                (android.view.accessibility.AccessibilityManager)
                        getSystemService(ACCESSIBILITY_SERVICE);
        if (target != null && access != null && access.isTouchExplorationEnabled())
            target.post(
                    () ->
                            target.performAccessibilityAction(
                                    android.view.accessibility.AccessibilityNodeInfo
                                            .ACTION_ACCESSIBILITY_FOCUS,
                                    null));
    }

    private void confirmRules() {
        if (snapshot == null) {
            status.setText(Strings.t("Checking what's stored…"));
            return;
        }
        StorageSense.Plan plan = StorageSense.plan(snapshot.items(), rules, engine.clock.now());
        if (plan.items.isEmpty()) {
            String message = Strings.t("Your rules have nothing to clear right now.");
            status.setText(message);
            status.announceForAccessibility(message);
            return;
        }
        confirmClear(plan);
    }

    private void renderDuplicates() {
        duplicatesHost.removeAllViews();
        LinearLayout group = PlaylistRows.group(duplicatesHost, style);
        PlaylistRows.add(
                group,
                style,
                Strings.t("Find exact duplicates"),
                Strings.t("Reads saved downloads and compares their contents. Names are ignored."),
                this::findDuplicates);
        if (review == null) return;
        if (review.groups.isEmpty()) {
            PlaylistRows.add(
                    group,
                    style,
                    Strings.t("No exact duplicates found"),
                    review.truncated
                            ? Strings.t("Some large files weren't compared.")
                            : review.unreadable > 0
                                    ? Strings.t("Some files couldn't be read.")
                                    : "",
                    () -> {});
            return;
        }
        for (List<StorageSense.Item> copies : review.groups) {
            StorageSense.Item keep = copies.get(0);
            PlaylistRows.add(
                    group,
                    style,
                    keep.title,
                    Strings.f(
                            "{0} identical copies · {1} each · This one stays",
                            Strings.number(copies.size()), bytes(keep)),
                    () -> info(keep));
            for (StorageSense.Item copy : copies.subList(1, copies.size()))
                PlaylistRows.add(
                        group,
                        style,
                        copy.title,
                        Strings.f("Identical copy · {0}", bytes(copy)),
                        () -> info(copy));
        }
    }

    private void findDuplicates() {
        if (busy || snapshot == null) return;
        StorageSenseEngine.Section saved = snapshot.sections.get(StorageSense.Category.SAVED);
        if (saved == null || saved.items.isEmpty()) {
            status.setText(Strings.t("Nothing stored here"));
            return;
        }
        busy = true;
        AtomicBoolean token = new AtomicBoolean();
        work = token;
        String progress = Strings.t("Comparing file contents…");
        status.setText(progress);
        status.announceForAccessibility(progress);
        StorageSense.Hasher hasher = hasher();
        List<StorageSense.Item> items = saved.items;
        worker.execute(
                () -> {
                    StorageSense.DuplicateReview found =
                            StorageSense.duplicates(items, hasher, token::get);
                    onUi(
                            () -> {
                                busy = false;
                                summarize();
                                if (found.cancelled) return;
                                review = found;
                                renderDuplicates();
                            });
                });
    }

    private StorageSense.Hasher hasher() {
        StorageSenseSaved owner = new StorageSenseSaved(this, new StorageSenseSaved.HostRecords());
        return hashers != null ? hashers : owner::sha256;
    }

    /** Replaced by tests that have no saved files to read. */
    static volatile StorageSense.Hasher hashers;

    private void renderOther() {
        otherHost.removeAllViews();
        LinearLayout group = PlaylistRows.group(otherHost, style);
        View row =
                PlaylistRows.add(
                        group,
                        style,
                        Strings.t("What this page leaves alone"),
                        Strings.t("Locations it never changes, and why"),
                        () -> {
                            otherOpen = !otherOpen;
                            if (otherOpen && other == null) loadOther();
                            renderOther();
                        });
        row.setContentDescription(
                Strings.t("What this page leaves alone")
                        + ", "
                        + (otherOpen ? Strings.t("Expanded") : Strings.t("Collapsed")));
        if (!otherOpen) return;
        if (other == null) {
            PlaylistRows.add(group, style, Strings.t("Checking…"), "", () -> {});
            return;
        }
        for (StorageSensePatchCaches.Unmanaged entry : other) {
            String size = entry.bytes < 0 ? "" : StorageSense.size(entry.bytes) + " · ";
            TextView text =
                    style.text(
                            entry.label + "\n" + size + entry.reason,
                            13,
                            style.secondary,
                            style.regular);
            text.setPadding(style.dp(16), style.dp(10), style.dp(16), style.dp(10));
            group.addView(text);
        }
    }

    private void loadOther() {
        Context app = getApplicationContext();
        AtomicBoolean token = scan;
        worker.execute(
                () -> {
                    List<StorageSensePatchCaches.Unmanaged> rows =
                            StorageSensePatchCaches.unmanaged(app, token::get);
                    onUi(
                            () -> {
                                other = rows;
                                renderOther();
                            });
                });
    }
}
