package app.bitchord.telegram;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Describe a playlist and get one from songs already on this device, then review it before anything
 * is saved. The description leads the page; preferences stay folded until asked for, and one pinned
 * action always holds the next step. A service the user configured is an explicit alternative in
 * preferences, and only an explicit Generate or Choose model reaches it.
 */
public final class SmartPlaylistActivity extends Activity {
    private static final String PREFERENCES = "bitchord_smart_playlists";
    private static final String MODE = "mode", MODE_SERVICE = "service", MODE_LOCAL = "local";
    private static final int[] COUNTS = {10, 25, 50};
    static final String ROOT_TAG = "smart-playlist-root",
            SCROLL_TAG = "smart-playlist-scroll",
            ACTION_TAG = "smart-playlist-action",
            PRIMARY_TAG = "smart-playlist-primary";

    /** Replaceable by tests, which have no platform keystore or network. */
    static volatile SmartPlaylistService.Transport transport = SmartPlaylistService.NETWORK;

    static volatile SmartPlaylistStore.Secrets secrets;
    static volatile SmartPlaylistMatcher.Search search = SmartPlaylistMatcher::installedSearch;
    static volatile SmartPlaylistMatcher.Best best = PlaylistImport::best;
    static volatile long pauseBetweenSearches = 150;

    interface LocalGenerator {
        LocalPlaylist.Result run(
                Context app,
                String description,
                int count,
                boolean familiar,
                boolean avoidExplicit,
                BooleanSupplier cancelled)
                throws Exception;
    }

    static volatile LocalGenerator local = LocalPlaylist::run;

    private enum Phase {
        LOADING,
        IDLE,
        CONFIGURING,
        CHOOSING_MODEL,
        GENERATING,
        MATCHING,
        REVIEW,
        SAVING
    }

    private enum Primary {
        GENERATE,
        SET_UP,
        SAVE_SERVICE,
        SAVE_PLAYLIST
    }

    /** A song from this device in review. Songs without tags start out of the playlist. */
    private static final class LocalRow {
        final Object song;
        final String reasons;
        final boolean untagged;
        boolean included;

        LocalRow(Object song, String reasons, boolean untagged) {
            this.song = song;
            this.reasons = reasons;
            this.untagged = untagged;
            included = !untagged;
        }
    }

    /** Work and results outlive a rotation; only leaving the page cancels them. */
    private static final class Session {
        final ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "bitchord-smart-playlist");
                            thread.setDaemon(true);
                            return thread;
                        });
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicInteger generation = new AtomicInteger();
        SmartPlaylistActivity owner;
        Phase phase = Phase.LOADING;
        SmartPlaylistService.Call call = new SmartPlaylistService.Call();
        Future<?> task;
        SmartPlaylistStore.Config config;
        boolean unreadable, configOpen, preferencesOpen, draftsKept;
        String draftEndpoint = "", draftModel = "", draftKey = "";
        String message = "";
        // Applied by whichever page instance is current when a background result arrives.
        String nameToShow;
        boolean clearKey, clearService, fillService, saved;
        List<SmartPlaylistMatcher.Row> rows = new ArrayList<>();
        List<String> models;
        boolean serviceMode;
        List<LocalRow> localRows = new ArrayList<>(), untaggedRows = new ArrayList<>();
        // Offered, never included: these only become rows if the user explicitly asks for them.
        List<Object> untaggedOffer = Collections.emptyList();
        String localName = "";
        Integer librarySongs;
        boolean libraryUnreadable;
        volatile int libraryRun;

        boolean localReview() {
            return !localRows.isEmpty() || !untaggedRows.isEmpty();
        }

        boolean reviewing() {
            return !rows.isEmpty() || localReview();
        }

        List<Object> localSelection() {
            List<Object> songs = new ArrayList<>();
            for (LocalRow row : localRows) if (row.included) songs.add(row.song);
            for (LocalRow row : untaggedRows) if (row.included) songs.add(row.song);
            return songs;
        }

        void clearResults() {
            rows = new ArrayList<>();
            localRows = new ArrayList<>();
            untaggedRows = new ArrayList<>();
            untaggedOffer = Collections.emptyList();
        }

        /** Starts new work; anything still running from before can no longer publish. */
        int begin(Phase next) {
            stop();
            phase = next;
            message = "";
            call = new SmartPlaylistService.Call();
            return generation.incrementAndGet();
        }

        void stop() {
            generation.incrementAndGet();
            call.cancel();
            if (task != null) task.cancel(true);
            task = null;
        }

        void post(int token, Runnable change) {
            main.post(
                    () -> {
                        if (token != generation.get()) return;
                        change.run();
                        if (owner != null) owner.render();
                    });
        }

        boolean current(int token) {
            return token == generation.get() && !Thread.currentThread().isInterrupted();
        }

        void close() {
            owner = null;
            libraryRun++;
            stop();
            worker.shutdownNow();
            main.removeCallbacksAndMessages(null);
        }
    }

    private TelegramStyle style;
    private Session session;
    private SmartPlaylistStore store;
    private SharedPreferences preferences;
    private ScrollView scroll;
    private LinearLayout body, form, options, descriptionCard, preferencesPanel, servicePanel;
    private LinearLayout review, reviewRows, progress, libraryRow, localGroup, untaggedBox;
    private LinearLayout untaggedGroup;
    private Disclosure preferencesRow, serviceRow;
    private View actionEdge, libraryLine, serviceLine;
    private EditText prompt, endpoint, model, key, playlistName;
    private TextView status, caption, sharing, primary, disconnect, skip, restart, keyNote;
    private TextView busyLabel, cancel, counter, libraryText, offer, reviewTitle, familiarLabel;
    private TelegramSwitch familiarSwitch;
    private final TextView[] countChips = new TextView[COUNTS.length];
    private final TextView[] modeChips = new TextView[2];
    private final List<View> rowViews = new ArrayList<>();
    private final List<View> localViews = new ArrayList<>(), untaggedViews = new ArrayList<>();
    private List<SmartPlaylistMatcher.Row> shownRows;
    private List<LocalRow> shownLocal, shownUntagged;
    private AlertDialog dialog;
    private int count = 25;

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Strings.refresh(this);
        CapturePolicy.apply(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        style = new TelegramStyle(this);
        // The root applies the system bar and keyboard insets itself, so the action can stay
        // pinned.
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(style.background);
        getWindow().setNavigationBarColor(style.background);
        if (!style.dark)
            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        preferences = getSharedPreferences(PREFERENCES, 0);
        store = new SmartPlaylistStore(secretStore(getApplicationContext()));
        count = preferences.getInt("count", 25);
        Object kept = getLastNonConfigurationInstance();
        boolean returning = kept instanceof Session;
        session = returning ? (Session) kept : new Session();
        // A mode comes only from the user's own choice, which is why local is what's left.
        if (!returning) session.serviceMode = MODE_SERVICE.equals(preferences.getString(MODE, ""));
        session.owner = this;
        build();
        if (saved != null) {
            prompt.setText(saved.getString("prompt", ""));
            playlistName.setText(saved.getString("name", ""));
        }
        if (session.draftsKept) {
            endpoint.setText(session.draftEndpoint);
            model.setText(session.draftModel);
            key.setText(session.draftKey);
        }
        if (returning) {
            render();
        } else if (session.serviceMode) {
            loadConfig();
        } else {
            session.phase = Phase.IDLE;
            render();
            checkLibrary();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (!session.serviceMode) checkLibrary();
    }

    private static SmartPlaylistStore.Secrets secretStore(Context app) {
        SmartPlaylistStore.Secrets override = secrets;
        if (override != null) return override;
        SyncSecrets encrypted = new SyncSecrets(app);
        return new SmartPlaylistStore.Secrets() {
            @Override
            public String get(String name) throws Exception {
                return encrypted.get(name);
            }

            @Override
            public void put(String name, String value) throws Exception {
                encrypted.put(name, value);
            }
        };
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        session.draftsKept = true;
        session.draftEndpoint = endpoint.getText().toString();
        session.draftModel = model.getText().toString();
        session.draftKey = key.getText().toString();
        return session;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("prompt", prompt.getText().toString());
        state.putString("name", playlistName.getText().toString());
    }

    @Override
    protected void onDestroy() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (isChangingConfigurations()) session.owner = null;
        else session.close();
        super.onDestroy();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(style.background);
        root.setTag(ROOT_TAG);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setTag(SCROLL_TAG);
        scroll.setBackgroundColor(style.background);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title = style.text(Strings.t("Smart playlist"), 28, style.foreground, style.heavy);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.setMarginStart(style.dp(12));
        header.addView(title, titleSpace);
        body.addView(header);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        body.addView(form, new LinearLayout.LayoutParams(-1, -2));
        buildDescription();
        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        body.addView(options, new LinearLayout.LayoutParams(-1, -2));
        buildOptions();
        review = new LinearLayout(this);
        review.setOrientation(LinearLayout.VERTICAL);
        review.setVisibility(View.GONE);
        body.addView(review, new LinearLayout.LayoutParams(-1, -2));
        buildReview();
        actionEdge = new View(this);
        actionEdge.setBackgroundColor(style.outline);
        actionEdge.setVisibility(View.INVISIBLE);
        root.addView(actionEdge, new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f))));
        buildAction(root);
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    applyInsets(view, insets);
                    return insets;
                });
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> updateActionEdge());
        scroll.addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateActionEdge());
        setContentView(root);
        root.requestApplyInsets();
    }

    /** The pinned action rides on these bottom insets, keeping it above the keyboard. */
    private static void applyInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe =
                    insets.getInsets(
                            WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout()
                                    | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        } else {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
        }
    }

    private void updateActionEdge() {
        int wanted = scroll.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE;
        if (actionEdge.getVisibility() != wanted) actionEdge.setVisibility(wanted);
    }

    private EditText field(String description, int inputType) {
        EditText input = new EditText(this);
        input.setContentDescription(description);
        input.setInputType(inputType);
        input.setTextSize(16);
        input.setTypeface(style.regular);
        input.setTextColor(style.foreground);
        input.setHintTextColor(style.secondary);
        input.setMinHeight(style.dp(48));
        input.setPadding(style.dp(14), style.dp(10), style.dp(14), style.dp(10));
        input.setBackground(style.shape(style.field, 12, false));
        input.setBackgroundTintList(null);
        tuneTextControls(input);
        return input;
    }

    /** Carries the palette into the caret and selection, which otherwise stay platform teal. */
    private void tuneTextControls(EditText input) {
        input.setHighlightColor(style.dark ? 0x5530d158 : 0x5534c759);
        if (Build.VERSION.SDK_INT >= 29) {
            android.graphics.drawable.GradientDrawable caret =
                    new android.graphics.drawable.GradientDrawable();
            caret.setColor(style.foreground);
            caret.setSize(style.dp(2), 0);
            input.setTextCursorDrawable(caret);
        }
    }

    private EditText labelled(LinearLayout parent, String label, int inputType) {
        TextView title = style.text(label, 13, style.secondary, style.medium);
        title.setPadding(0, style.dp(12), 0, style.dp(4));
        parent.addView(title);
        EditText input = field(label, inputType);
        input.setId(View.generateViewId());
        title.setLabelFor(input.getId());
        return input;
    }

    private TextView pill(String label, Runnable action) {
        TextView pill = style.button(label, false, action);
        pill.setTextSize(15);
        pill.setMinHeight(style.dp(48));
        pill.setMinWidth(style.dp(48));
        pill.setPadding(style.dp(16), style.dp(6), style.dp(16), style.dp(6));
        return pill;
    }

    private void buildDescription() {
        descriptionCard = new LinearLayout(this);
        descriptionCard.setOrientation(LinearLayout.VERTICAL);
        descriptionCard.setBackground(style.shape(style.card, 16, false));
        descriptionCard.setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(12));
        LinearLayout.LayoutParams cardSpace = new LinearLayout.LayoutParams(-1, -2);
        cardSpace.topMargin = style.dp(12);
        form.addView(descriptionCard, cardSpace);
        TextView heading =
                style.text(
                        Strings.t("Describe the playlist"), 20, style.foreground, style.semibold);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        descriptionCard.addView(heading);
        String hint = Strings.t("A mood, a moment, or artists you like");
        prompt =
                field(
                        hint,
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setHint(hint);
        prompt.setTextSize(18);
        prompt.setSaveEnabled(false);
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP | Gravity.START);
        prompt.setBackground(null);
        prompt.setPadding(0, style.dp(8), 0, style.dp(4));
        prompt.setFilters(
                new android.text.InputFilter[] {
                    new android.text.InputFilter.LengthFilter(SmartPlaylistService.MAX_PROMPT)
                });
        prompt.setOnFocusChangeListener(
                (view, focused) -> {
                    android.graphics.drawable.GradientDrawable surface =
                            style.shape(style.card, 16, false);
                    if (focused) surface.setStroke(style.dp(1.5f), style.secondary);
                    descriptionCard.setBackground(surface);
                });
        prompt.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        descriptionEdited();
                    }
                });
        descriptionCard.addView(prompt, new LinearLayout.LayoutParams(-1, -2));
        counter = style.text("", 13, style.secondary, style.regular);
        counter.setGravity(Gravity.END);
        counter.setFontFeatureSettings("tnum");
        counter.setVisibility(View.GONE);
        descriptionCard.addView(counter, new LinearLayout.LayoutParams(-1, -2));
    }

    private void descriptionEdited() {
        int length = prompt.length();
        visible(counter, length >= SmartPlaylistService.MAX_PROMPT - 100);
        counter.setText(length + " / " + SmartPlaylistService.MAX_PROMPT);
        // A finished message stops being current once the description changes.
        if (prompt.hasFocus() && (!session.message.isEmpty() || !session.untaggedOffer.isEmpty())) {
            session.message = "";
            session.untaggedOffer = Collections.emptyList();
            renderMessage();
        }
        renderAction();
    }

    private void buildOptions() {
        LinearLayout group = PlaylistRows.group(options, style);
        ((LinearLayout.LayoutParams) group.getLayoutParams()).topMargin = style.dp(12);
        libraryRow = new LinearLayout(this);
        libraryRow.setGravity(Gravity.CENTER_VERTICAL);
        libraryRow.setMinimumHeight(style.dp(60));
        libraryRow.setPadding(style.dp(16), style.dp(10), style.dp(12), style.dp(10));
        ImageView libraryIcon = new ImageView(this);
        libraryIcon.setImageDrawable(new TelegramGlyph(style, "music", style.secondary));
        libraryIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams libraryIconSpace =
                new LinearLayout.LayoutParams(style.dp(24), style.dp(24));
        libraryIconSpace.setMarginEnd(style.dp(14));
        libraryRow.addView(libraryIcon, libraryIconSpace);
        LinearLayout libraryWords = new LinearLayout(this);
        libraryWords.setOrientation(LinearLayout.VERTICAL);
        libraryWords.addView(
                style.text(Strings.t("Your library"), 16, style.foreground, style.medium));
        libraryText = style.text("", 13, style.secondary, style.regular);
        libraryWords.addView(libraryText);
        libraryRow.addView(libraryWords, new LinearLayout.LayoutParams(0, -2, 1));
        group.addView(libraryRow, new LinearLayout.LayoutParams(-1, -2));
        libraryLine = hairline(group);
        preferencesRow =
                new Disclosure(
                        group,
                        "settings",
                        Strings.t("Preferences"),
                        () -> {
                            session.preferencesOpen = !session.preferencesOpen;
                            if (session.preferencesOpen) session.configOpen = false;
                            session.message = "";
                            render();
                            if (session.preferencesOpen) reveal(preferencesRow.row);
                        });
        preferencesPanel = new LinearLayout(this);
        preferencesPanel.setOrientation(LinearLayout.VERTICAL);
        preferencesPanel.setPadding(style.dp(16), 0, style.dp(16), style.dp(8));
        preferencesPanel.setVisibility(View.GONE);
        group.addView(preferencesPanel, new LinearLayout.LayoutParams(-1, -2));
        buildPreferences();
        serviceLine = hairline(group);
        serviceRow =
                new Disclosure(
                        group,
                        "key",
                        Strings.t("External text service"),
                        () -> {
                            session.configOpen = !session.configOpen;
                            if (session.configOpen) session.preferencesOpen = false;
                            session.message = "";
                            render();
                            if (session.configOpen) reveal(serviceRow.row);
                        });
        servicePanel = new LinearLayout(this);
        servicePanel.setOrientation(LinearLayout.VERTICAL);
        servicePanel.setPadding(style.dp(16), 0, style.dp(16), style.dp(12));
        servicePanel.setVisibility(View.GONE);
        group.addView(servicePanel, new LinearLayout.LayoutParams(-1, -2));
        buildService();
        sharing = style.text("", 13, style.secondary, style.regular);
        sharing.setPadding(style.dp(16), style.dp(12), style.dp(16), 0);
        options.addView(sharing);
    }

    private View hairline(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(style.outline);
        LinearLayout.LayoutParams space =
                new LinearLayout.LayoutParams(-1, Math.max(1, style.dp(.5f)));
        space.setMarginStart(style.dp(16));
        space.setMarginEnd(style.dp(16));
        parent.addView(line, space);
        return line;
    }

    /** A compact row that opens its panel in place, and says so to accessibility services. */
    private final class Disclosure {
        final LinearLayout row = new LinearLayout(SmartPlaylistActivity.this);
        final TextView title, summary;
        final ImageView icon = new ImageView(SmartPlaylistActivity.this);
        final ImageView chevron = new ImageView(SmartPlaylistActivity.this);
        private boolean open, drawn;
        private String glyph = "";

        Disclosure(LinearLayout parent, String kind, String label, Runnable toggle) {
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(style.dp(60));
            row.setPadding(style.dp(16), style.dp(10), style.dp(12), style.dp(10));
            row.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(style.dark ? 0x228e8e93 : 0x22000000),
                            new android.graphics.drawable.ColorDrawable(
                                    android.graphics.Color.TRANSPARENT),
                            null));
            setGlyph(kind, style.secondary);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconSpace =
                    new LinearLayout.LayoutParams(style.dp(24), style.dp(24));
            iconSpace.setMarginEnd(style.dp(14));
            row.addView(icon, iconSpace);
            LinearLayout text = new LinearLayout(SmartPlaylistActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            title = style.text(label, 16, style.foreground, style.medium);
            text.addView(title);
            summary = style.text("", 13, style.secondary, style.regular);
            summary.setMaxLines(3);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(summary);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            chevron.setImageDrawable(new TelegramGlyph(style, "down", style.secondary));
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams chevronSpace =
                    new LinearLayout.LayoutParams(style.dp(24), style.dp(24));
            chevronSpace.setMarginStart(style.dp(8));
            row.addView(chevron, chevronSpace);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> toggle.run());
            row.setAccessibilityDelegate(
                    new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(
                                View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName(Button.class.getName());
                            info.addAction(
                                    open
                                            ? android.view.accessibility.AccessibilityNodeInfo
                                                    .AccessibilityAction.ACTION_COLLAPSE
                                            : android.view.accessibility.AccessibilityNodeInfo
                                                    .AccessibilityAction.ACTION_EXPAND);
                        }

                        @Override
                        public boolean performAccessibilityAction(
                                View host, int action, Bundle arguments) {
                            if (action
                                            == android.view.accessibility.AccessibilityNodeInfo
                                                    .ACTION_EXPAND
                                    || action
                                            == android.view.accessibility.AccessibilityNodeInfo
                                                    .ACTION_COLLAPSE) return host.performClick();
                            return super.performAccessibilityAction(host, action, arguments);
                        }
                    });
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        void setGlyph(String kind, int color) {
            String wanted = kind + color;
            if (wanted.equals(glyph)) return;
            glyph = wanted;
            icon.setImageDrawable(new TelegramGlyph(style, kind, color));
        }

        void set(String detail, boolean expanded) {
            if (!detail.contentEquals(summary.getText())) summary.setText(detail);
            String state = Strings.t(expanded ? "Expanded" : "Collapsed");
            String description = title.getText() + ", " + detail;
            if (Build.VERSION.SDK_INT >= 30) row.setStateDescription(state);
            else description += ", " + state;
            if (!description.contentEquals(String.valueOf(row.getContentDescription())))
                row.setContentDescription(description);
            float rotation = expanded ? 180 : 0;
            if (!drawn) chevron.setRotation(rotation);
            else if (expanded != open)
                chevron.animate()
                        .rotation(rotation)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2))
                        .start();
            drawn = true;
            open = expanded;
        }
    }

    private void reveal(View view) {
        scroll.post(
                () -> {
                    int y = 0;
                    for (View at = view; at != null && at != body; at = (View) at.getParent())
                        y += at.getTop();
                    scroll.smoothScrollTo(0, Math.max(0, y - style.dp(8)));
                });
    }

    private void buildPreferences() {
        TextView modeTitle =
                style.text(Strings.t("Generate with"), 16, style.foreground, style.medium);
        modeTitle.setPadding(0, style.dp(4), 0, style.dp(8));
        preferencesPanel.addView(modeTitle);
        LinearLayout modeRow = new LinearLayout(this);
        String[] modes = {Strings.t("This device"), Strings.t("External service")};
        for (int i = 0; i < modes.length; i++) {
            boolean service = i == 1;
            TextView chip = pill(modes[i], () -> chooseMode(service));
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) place.setMarginStart(style.dp(6));
            modeRow.addView(chip, place);
            modeChips[i] = chip;
        }
        preferencesPanel.addView(modeRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout countRow = new LinearLayout(this);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        countRow.setMinimumHeight(style.dp(56));
        countRow.addView(
                style.text(Strings.t("Tracks"), 16, style.foreground, style.medium),
                new LinearLayout.LayoutParams(0, -2, 1));
        for (int i = 0; i < COUNTS.length; i++) {
            int value = COUNTS[i];
            TextView chip =
                    pill(
                            String.valueOf(value),
                            () -> {
                                count = value;
                                preferences.edit().putInt("count", value).apply();
                                paintChips();
                                render();
                            });
            chip.setContentDescription(Strings.f("{0} songs", value));
            chip.setMinWidth(style.dp(56));
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) place.setMarginStart(style.dp(6));
            countRow.addView(chip, place);
            countChips[i] = chip;
        }
        preferencesPanel.addView(countRow);
        paintChips();
        LinearLayout familiar = toggleRow(familiarTitle(), "familiar", true);
        familiarLabel = (TextView) familiar.getChildAt(0);
        familiarSwitch = (TelegramSwitch) familiar.getChildAt(1);
        toggleRow(Strings.t("Avoid explicit lyrics"), "clean", false);
    }

    /** Offline, "well known" can only mean well known to this user, so the label says that. */
    private String familiarTitle() {
        return session.serviceMode
                ? Strings.t("Favor well-known songs")
                : Strings.t("Favor songs you play often");
    }

    private void paintChips() {
        for (int i = 0; i < COUNTS.length; i++) paintChip(countChips[i], COUNTS[i] == count);
        paintChip(modeChips[0], !session.serviceMode);
        paintChip(modeChips[1], session.serviceMode);
    }

    private void paintChip(TextView chip, boolean on) {
        chip.setSelected(on);
        chip.setTextColor(on ? style.background : style.foreground);
        chip.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(style.dark ? 0x228e8e93 : 0x22000000),
                        style.shape(on ? style.foreground : style.field, 24, false),
                        null));
    }

    /**
     * Only ever the user's own tap. Nothing is contacted: service mode just reads the saved details
     * from this device, and any read still under way for the other mode can no longer publish.
     */
    private void chooseMode(boolean service) {
        if (session.serviceMode == service) return;
        if (session.phase != Phase.IDLE && session.phase != Phase.LOADING) return;
        preferences.edit().putString(MODE, service ? MODE_SERVICE : MODE_LOCAL).apply();
        session.stop();
        session.serviceMode = service;
        session.configOpen = false;
        session.message = "";
        session.untaggedOffer = Collections.emptyList();
        session.phase = Phase.IDLE;
        paintChips();
        if (service) {
            loadConfig();
        } else {
            session.config = null;
            session.unreadable = false;
            render();
            checkLibrary();
        }
    }

    /** Only what's already in memory and download records; files aren't opened just to count. */
    private void checkLibrary() {
        Session current = session;
        int run = ++current.libraryRun;
        Context app = getApplicationContext();
        BooleanSupplier stale = () -> run != current.libraryRun;
        current.worker.execute(
                () -> {
                    Integer songs = null;
                    boolean unreadable = false;
                    try {
                        LocalPlaylistLibrary.Library library =
                                LocalPlaylistLibrary.collect(new SavedSongs(app, stale), stale);
                        unreadable = library.unavailable();
                        songs = library.entries.size();
                    } catch (InterruptedException stopped) {
                        return;
                    } catch (RuntimeException | LinkageError failed) {
                        unreadable = true;
                    }
                    Integer count = songs;
                    boolean bad = unreadable;
                    current.main.post(
                            () -> {
                                if (run != current.libraryRun) return;
                                current.librarySongs = count;
                                current.libraryUnreadable = bad;
                                if (current.owner != null) current.owner.render();
                            });
                });
    }

    /** The library's saved parts, skipping the ones that would open files or wake the player. */
    private static final class SavedSongs implements LocalPlaylistLibrary.Host {
        private final LocalPlaylistLibrary.Host real;

        SavedSongs(Context app, BooleanSupplier cancelled) {
            real = new LocalPlaylistLibrary.Reflected(app, cancelled);
        }

        @Override
        public List<LocalPlaylistLibrary.Shelf> playlists() throws Exception {
            return real.playlists();
        }

        @Override
        public List<LocalPlaylistLibrary.Shelf> offlinePlaylists() throws Exception {
            return real.offlinePlaylists();
        }

        @Override
        public List<Object> downloads() throws Exception {
            return real.downloads();
        }

        @Override
        public List<Object> queue() {
            return Collections.emptyList();
        }

        @Override
        public Map<String, LocalPlaylistLibrary.Listened> listening() {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, String> likes() {
            return Collections.emptyMap();
        }

        @Override
        public List<Object> localFiles() {
            return Collections.emptyList();
        }

        @Override
        public Map<String, LocalPlaylistLibrary.MediaTags> mediaTags() {
            return Collections.emptyMap();
        }
    }

    private LinearLayout toggleRow(String title, String preference, boolean initial) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.dp(52));
        TextView label = style.text(title, 16, style.foreground, style.medium);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TelegramSwitch toggle = new TelegramSwitch(this, style);
        toggle.setContentDescription(title);
        toggle.setChecked(preferences.getBoolean(preference, initial));
        toggle.setListener(
                (view, checked) -> {
                    preferences.edit().putBoolean(preference, checked).apply();
                    render();
                });
        row.addView(toggle);
        preferencesPanel.addView(row);
        return row;
    }

    private void buildService() {
        TextView about =
                style.text(
                        Strings.t(
                                "Use any HTTPS service that accepts chat-style JSON requests. No"
                                        + " service is built in, and BitChord runs no server for"
                                        + " this."),
                        13,
                        style.secondary,
                        style.regular);
        servicePanel.addView(about);
        endpoint =
                labelled(
                        servicePanel,
                        Strings.t("Service URL (HTTPS)"),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        configureSensitive(endpoint);
        endpoint.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        servicePanel.addView(endpoint, new LinearLayout.LayoutParams(-1, -2));
        model =
                labelled(
                        servicePanel,
                        Strings.t("Model name"),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        configureSensitive(model);
        model.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        // The pill sits beside the field until large text would squeeze the field unusably.
        boolean roomy = getResources().getConfiguration().fontScale < 1.3f;
        LinearLayout modelLine = new LinearLayout(this);
        modelLine.setOrientation(roomy ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        modelLine.setGravity(roomy ? Gravity.CENTER_VERTICAL : Gravity.END);
        modelLine.addView(
                model,
                roomy
                        ? new LinearLayout.LayoutParams(0, -2, 1)
                        : new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams pillSpace = new LinearLayout.LayoutParams(-2, -2);
        if (roomy) pillSpace.setMarginStart(style.dp(8));
        else pillSpace.topMargin = style.dp(8);
        modelLine.addView(pill(Strings.t("Choose model"), this::chooseModel), pillSpace);
        servicePanel.addView(modelLine, new LinearLayout.LayoutParams(-1, -2));
        key =
                labelled(
                        servicePanel,
                        Strings.t("Service key, if required"),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        configureSensitive(key);
        servicePanel.addView(key, new LinearLayout.LayoutParams(-1, -2));
        keyNote = style.text("", 13, style.secondary, style.regular);
        keyNote.setPadding(0, style.dp(8), 0, 0);
        servicePanel.addView(keyNote);
        disconnect = pill(Strings.t("Disconnect service"), this::confirmDisconnect);
        LinearLayout.LayoutParams disconnectSpace = new LinearLayout.LayoutParams(-2, -2);
        disconnectSpace.topMargin = style.dp(12);
        disconnectSpace.gravity = Gravity.START;
        servicePanel.addView(disconnect, disconnectSpace);
    }

    /** Service details stay out of the saved view state, autofill and suggestion dictionaries. */
    private static void configureSensitive(EditText input) {
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        if (Build.VERSION.SDK_INT >= 26)
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    }

    /** One pinned primary control: only its meaning changes, so it never moves or duplicates. */
    private void buildAction(LinearLayout root) {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setTag(ACTION_TAG);
        area.setBackgroundColor(style.background);
        area.setPadding(style.dp(20), style.dp(10), style.dp(20), style.dp(12));
        root.addView(area, new LinearLayout.LayoutParams(-1, -2));
        status = style.text("", 14, style.foreground, style.regular);
        status.setPadding(0, 0, 0, style.dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        area.addView(status);
        offer = pill("", this::openUntagged);
        offer.setVisibility(View.GONE);
        LinearLayout.LayoutParams offerSpace = new LinearLayout.LayoutParams(-1, -2);
        offerSpace.bottomMargin = style.dp(8);
        area.addView(offer, offerSpace);
        caption = style.text("", 13, style.secondary, style.regular);
        caption.setPadding(0, 0, 0, style.dp(8));
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setVisibility(View.GONE);
        area.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        progress.setMinimumHeight(style.dp(50));
        progress.setVisibility(View.GONE);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(style.foreground));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        progress.addView(spinner, new LinearLayout.LayoutParams(style.dp(24), style.dp(24)));
        busyLabel = style.text("", 14, style.foreground, style.regular);
        LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(0, -2, 1);
        label.setMarginStart(style.dp(12));
        label.setMarginEnd(style.dp(8));
        progress.addView(busyLabel, label);
        cancel = pill(Strings.t("Cancel"), this::cancelWork);
        progress.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        area.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        primary = style.button("", true, this::primaryPressed);
        primary.setTag(PRIMARY_TAG);
        area.addView(primary, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildReview() {
        playlistName = labelled(review, Strings.t("Playlist name"), InputType.TYPE_CLASS_TEXT);
        playlistName.setSaveEnabled(false);
        playlistName.setSingleLine(true);
        review.addView(playlistName, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(0, style.dp(20), 0, style.dp(8));
        reviewTitle = style.text(Strings.t("Review matches"), 20, style.foreground, style.semibold);
        if (Build.VERSION.SDK_INT >= 28) reviewTitle.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.setMarginEnd(style.dp(8));
        heading.addView(reviewTitle, titleSpace);
        restart = pill(Strings.t("Start over"), this::startOver);
        heading.addView(restart, new LinearLayout.LayoutParams(-2, -2));
        review.addView(heading);
        skip =
                pill(
                        Strings.t("Skip unresolved tracks"),
                        () -> {
                            SmartPlaylistMatcher.skipUnresolved(session.rows);
                            render();
                        });
        LinearLayout.LayoutParams skipSpace = new LinearLayout.LayoutParams(-2, -2);
        skipSpace.bottomMargin = style.dp(8);
        skipSpace.gravity = Gravity.START;
        review.addView(skip, skipSpace);
        reviewRows = PlaylistRows.group(review, style);
        localGroup = PlaylistRows.group(review, style);
        untaggedBox = new LinearLayout(this);
        untaggedBox.setOrientation(LinearLayout.VERTICAL);
        untaggedBox.setVisibility(View.GONE);
        review.addView(untaggedBox, new LinearLayout.LayoutParams(-1, -2));
        TextView untaggedTitle =
                style.text(Strings.t("Songs without tags"), 16, style.foreground, style.semibold);
        untaggedTitle.setPadding(0, style.dp(16), 0, style.dp(4));
        if (Build.VERSION.SDK_INT >= 28) untaggedTitle.setAccessibilityHeading(true);
        untaggedBox.addView(untaggedTitle);
        TextView untaggedNote =
                style.text(
                        Strings.t(
                                "Nothing shows these fit your description. Only the ones you"
                                        + " choose are added."),
                        13,
                        style.secondary,
                        style.regular);
        untaggedNote.setPadding(0, 0, 0, style.dp(8));
        untaggedBox.addView(untaggedNote);
        untaggedGroup = PlaylistRows.group(untaggedBox, style);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        if (session.saved) {
            Toast.makeText(this, Strings.t("Saved"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        applyPending();
        Phase phase = session.phase;
        boolean results = session.reviewing();
        visible(form, !results);
        // While a request runs the description stays readable, and everything else steps aside.
        visible(options, !results && phase != Phase.GENERATING);
        visible(review, results);
        prompt.setEnabled(phase != Phase.GENERATING);
        renderMessage();
        renderOptions();
        renderRows();
        renderAction();
        if (session.models != null) showModels();
    }

    private void renderMessage() {
        if (!session.message.contentEquals(status.getText())) status.setText(session.message);
        visible(status, !session.message.isEmpty());
    }

    private void applyPending() {
        if (session.clearKey) {
            session.clearKey = false;
            session.draftKey = "";
            key.setText("");
        }
        if (session.clearService) {
            session.clearService = false;
            session.draftEndpoint = "";
            session.draftModel = "";
            endpoint.setText("");
            model.setText("");
        }
        if (session.fillService) {
            session.fillService = false;
            if (session.config != null && endpoint.length() == 0)
                endpoint.setText(session.config.endpoint);
            if (session.config != null && model.length() == 0) model.setText(session.config.model);
        }
        if (session.nameToShow != null) {
            playlistName.setText(session.nameToShow);
            session.nameToShow = null;
        }
    }

    private String busyText(Phase phase) {
        switch (phase) {
            case GENERATING:
                return session.serviceMode
                        ? Strings.t("Waiting for the text service…")
                        : Strings.t("Looking through your library…");
            case CHOOSING_MODEL:
                return Strings.t("Loading models…");
            case MATCHING:
                int done = 0;
                for (SmartPlaylistMatcher.Row row : session.rows) if (row.searched) done++;
                return Strings.f("Matching {0} of {1}", done, session.rows.size());
            case SAVING:
                return Strings.t("Saving…");
            default:
                return Strings.t("Loading…");
        }
    }

    private void renderOptions() {
        SmartPlaylistStore.Config config = session.config;
        boolean loading = session.phase == Phase.LOADING;
        boolean service = session.serviceMode;
        visible(libraryRow, !service);
        visible(libraryLine, !service);
        visible(serviceLine, service);
        visible(serviceRow.row, service);
        if (!service) {
            String library = libraryStatus();
            if (!library.contentEquals(libraryText.getText())) libraryText.setText(library);
        }
        String familiar = familiarTitle();
        if (!familiar.contentEquals(familiarLabel.getText())) {
            familiarLabel.setText(familiar);
            familiarSwitch.setContentDescription(familiar);
        }
        boolean switchable = session.phase == Phase.IDLE || loading;
        for (TextView chip : modeChips) {
            chip.setEnabled(switchable);
            chip.setAlpha(switchable ? 1 : .45f);
        }
        StringBuilder wanted =
                new StringBuilder(
                        service ? Strings.t("External service") : Strings.t("This device"));
        wanted.append(" · ").append(Strings.f("{0} songs", count));
        if (preferences.getBoolean("familiar", true)) wanted.append(" · ").append(familiar);
        if (preferences.getBoolean("clean", false))
            wanted.append(" · ").append(Strings.t("Avoid explicit lyrics"));
        preferencesRow.set(wanted.toString(), session.preferencesOpen);
        visible(preferencesPanel, session.preferencesOpen);
        String summary =
                loading
                        ? Strings.t("Loading…")
                        : config != null
                                ? config.host + " · " + config.model
                                : session.unreadable
                                        ? Strings.t("Saved details can't be read. Set up again.")
                                        : Strings.t("Not set up");
        serviceRow.set(summary, session.configOpen);
        serviceRow.setGlyph(
                config != null ? "check" : "key",
                config != null ? (style.dark ? 0xff30d158 : 0xff248a3d) : style.secondary);
        visible(servicePanel, service && session.configOpen);
        visible(disconnect, config != null || session.unreadable);
        keyNote.setText(
                config != null && config.hasKey
                        ? Strings.t(
                                "A key is saved encrypted on this device. Leave this empty to keep"
                                        + " it for the same service.")
                        : Strings.t(
                                "A key is stored encrypted on this device and is sent only to this"
                                        + " service."));
        // Setup has its own explanation; this notice returns beside Generate once it closes.
        visible(sharing, !loading && !session.configOpen);
        sharing.setText(
                !service
                        ? Strings.t(
                                "Uses only songs already on this device. Nothing is sent, searched"
                                        + " or downloaded.")
                        : config != null
                                ? Strings.f(
                                        "Generate sends your description and these preferences to"
                                            + " {0}. Audio, your library and listening history are"
                                            + " not sent.",
                                        config.host)
                                : Strings.t(
                                        "Optional. Set up an external text service to use this."
                                            + " Nothing is sent until you tap Generate, and your"
                                            + " other playlists don't need it."));
    }

    private String libraryStatus() {
        if (session.libraryUnreadable)
            return Strings.t("This version of BitChord doesn't expose its library.");
        if (session.librarySongs == null) return Strings.t("Checking…");
        if (session.librarySongs == 0)
            return Strings.t(
                    "Nothing saved yet. Device files and listening history are checked when you"
                            + " generate.");
        if (session.librarySongs == 1)
            return Strings.t(
                    "1 saved song, plus device files and listening history when you generate.");
        return Strings.f(
                "{0} saved songs, plus device files and listening history when you generate.",
                Strings.number(session.librarySongs));
    }

    private Primary primaryAction() {
        if (session.reviewing()) return Primary.SAVE_PLAYLIST;
        if (!session.serviceMode) return Primary.GENERATE;
        if (session.configOpen) return Primary.SAVE_SERVICE;
        if (session.config == null && session.phase != Phase.LOADING) return Primary.SET_UP;
        return Primary.GENERATE;
    }

    private void renderAction() {
        Phase phase = session.phase;
        boolean working =
                phase == Phase.CONFIGURING
                        || phase == Phase.CHOOSING_MODEL
                        || phase == Phase.GENERATING
                        || phase == Phase.MATCHING
                        || phase == Phase.SAVING;
        visible(progress, working);
        visible(primary, !working);
        visible(
                cancel,
                phase == Phase.GENERATING
                        || phase == Phase.MATCHING
                        || phase == Phase.CHOOSING_MODEL);
        String busy = busyText(phase);
        if (!busy.contentEquals(busyLabel.getText())) busyLabel.setText(busy);
        // A running count would talk over everything else, so only one-off states are announced.
        busyLabel.setAccessibilityLiveRegion(
                phase == Phase.MATCHING
                        ? View.ACCESSIBILITY_LIVE_REGION_NONE
                        : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Primary action = primaryAction();
        String label;
        boolean enabled = phase == Phase.IDLE;
        String note = "";
        switch (action) {
            case SAVE_PLAYLIST:
                label = Strings.t("Save playlist");
                enabled = phase == Phase.REVIEW && readyToSave();
                if (phase == Phase.REVIEW && session.localReview())
                    note = Strings.f("{0} selected", session.localSelection().size());
                else if (phase == Phase.REVIEW)
                    note =
                            Strings.f(
                                    "{0} matched · {1} to review",
                                    SmartPlaylistMatcher.selection(session.rows).size(),
                                    SmartPlaylistMatcher.unresolved(session.rows));
                break;
            case SAVE_SERVICE:
                label = Strings.t("Save service");
                break;
            case SET_UP:
                label = Strings.t("Set up service");
                break;
            default:
                label = Strings.t("Generate");
                enabled = enabled && prompt.getText().toString().trim().length() > 0;
                if (!session.serviceMode) note = Strings.t("Stays on this device");
                else if (session.config != null)
                    note = Strings.f("Sends to {0}", session.config.host);
        }
        if (!label.contentEquals(primary.getText())) primary.setText(label);
        primary.setEnabled(enabled);
        primary.setAlpha(enabled ? 1 : .45f);
        if (!note.contentEquals(caption.getText())) caption.setText(note);
        visible(caption, !note.isEmpty() && !working);
        int offered = session.untaggedOffer.size();
        boolean offering = offered > 0 && (phase == Phase.IDLE || phase == Phase.REVIEW);
        if (offering) {
            String words =
                    offered == 1
                            ? Strings.t("Review 1 song without tags")
                            : Strings.f("Review {0} songs without tags", Strings.number(offered));
            if (!words.contentEquals(offer.getText())) offer.setText(words);
        }
        visible(offer, offering);
    }

    private boolean readyToSave() {
        return session.localReview()
                ? !session.localSelection().isEmpty()
                : SmartPlaylistMatcher.ready(session.rows);
    }

    private void primaryPressed() {
        switch (primaryAction()) {
            case SAVE_PLAYLIST:
                savePlaylist();
                break;
            case SAVE_SERVICE:
                saveConfig();
                break;
            case SET_UP:
                openSetup();
                break;
            default:
                generate();
        }
    }

    /** Setup is local until the user acts on it: opening it reaches nothing over the network. */
    private void openSetup() {
        session.configOpen = true;
        session.preferencesOpen = false;
        session.message = "";
        render();
        endpoint.requestFocus();
        reveal(serviceRow.row);
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.showSoftInput(endpoint, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (focused == null || keyboard == null) return;
        keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    private void renderRows() {
        List<SmartPlaylistMatcher.Row> rows = session.rows;
        if (shownRows != rows || rowViews.size() != rows.size()) {
            shownRows = rows;
            reviewRows.removeAllViews();
            rowViews.clear();
            for (SmartPlaylistMatcher.Row row : rows) {
                View view =
                        PlaylistRows.add(
                                reviewRows,
                                style,
                                row.track.title,
                                Strings.t("Searching…"),
                                () -> chooseMatch(row));
                ImageView mark = new ImageView(this);
                mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                mark.setScaleType(ImageView.ScaleType.CENTER);
                LinearLayout.LayoutParams markSpace =
                        new LinearLayout.LayoutParams(style.dp(24), style.dp(24));
                markSpace.setMarginEnd(style.dp(12));
                ((LinearLayout) view).addView(mark, 0, markSpace);
                rowViews.add(view);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            SmartPlaylistMatcher.Row row = rows.get(i);
            View view = rowViews.get(i);
            setRow(view, title(row), detail(row));
            setMark((ImageView) ((LinearLayout) view).getChildAt(0), row.state());
        }
        boolean reviewing = session.phase == Phase.REVIEW;
        visible(reviewRows, !rows.isEmpty());
        visible(skip, reviewing && SmartPlaylistMatcher.unresolved(rows) > 0);
        visible(restart, reviewing);
        boolean local = session.localReview();
        String heading = local ? Strings.t("Review songs") : Strings.t("Review matches");
        if (!heading.contentEquals(reviewTitle.getText())) reviewTitle.setText(heading);
        if (shownLocal != session.localRows) {
            shownLocal = session.localRows;
            addLocalRows(localGroup, localViews, shownLocal);
        }
        if (shownUntagged != session.untaggedRows) {
            shownUntagged = session.untaggedRows;
            addLocalRows(untaggedGroup, untaggedViews, shownUntagged);
        }
        visible(localGroup, !session.localRows.isEmpty());
        visible(untaggedBox, !session.untaggedRows.isEmpty());
        paintLocalRows(localViews, session.localRows);
        paintLocalRows(untaggedViews, session.untaggedRows);
    }

    private void addLocalRows(LinearLayout group, List<View> views, List<LocalRow> rows) {
        group.removeAllViews();
        views.clear();
        for (LocalRow row : rows) {
            View view =
                    PlaylistRows.add(
                            group, style, name(row.song), localDetail(row), () -> toggle(row));
            ImageView mark = new ImageView(this);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            mark.setScaleType(ImageView.ScaleType.CENTER);
            LinearLayout.LayoutParams markSpace =
                    new LinearLayout.LayoutParams(style.dp(24), style.dp(24));
            markSpace.setMarginEnd(style.dp(12));
            ((LinearLayout) view).addView(mark, 0, markSpace);
            views.add(view);
        }
    }

    private void paintLocalRows(List<View> views, List<LocalRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            LocalRow row = rows.get(i);
            View view = views.get(i);
            view.setSelected(row.included);
            setRow(view, name(row.song), localDetail(row));
            setMark(
                    (ImageView) ((LinearLayout) view).getChildAt(0),
                    row.included
                            ? SmartPlaylistMatcher.State.MATCHED
                            : SmartPlaylistMatcher.State.SKIPPED);
        }
    }

    private static String localDetail(LocalRow row) {
        if (row.untagged)
            return row.included
                    ? Strings.t("Added by you · tap to remove")
                    : Strings.t("No tags show this fits · tap to add");
        return row.included ? row.reasons : Strings.t("Removed · tap to add back");
    }

    private void toggle(LocalRow row) {
        if (session.phase != Phase.REVIEW) return;
        row.included = !row.included;
        render();
    }

    /** The one way songs without tags reach review, and they arrive unselected. */
    private void openUntagged() {
        if (session.phase != Phase.IDLE && session.phase != Phase.REVIEW) return;
        List<LocalRow> rows = new ArrayList<>();
        for (Object song : session.untaggedOffer) rows.add(new LocalRow(song, "", true));
        if (rows.isEmpty()) return;
        session.untaggedRows = rows;
        session.untaggedOffer = Collections.emptyList();
        if (session.phase == Phase.IDLE) {
            session.phase = Phase.REVIEW;
            session.nameToShow = session.localName;
            session.message = "";
        }
        render();
    }

    /** A check for settled matches and an open ring for anything still needing a decision. */
    private void setMark(ImageView mark, SmartPlaylistMatcher.State state) {
        if (state == mark.getTag()) return;
        mark.setTag(state);
        if (state == SmartPlaylistMatcher.State.MATCHED) {
            mark.setImageDrawable(
                    new TelegramGlyph(style, "check", style.dark ? 0xff30d158 : 0xff248a3d));
            return;
        }
        android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setSize(style.dp(14), style.dp(14));
        boolean settled = state == SmartPlaylistMatcher.State.SKIPPED;
        ring.setStroke(style.dp(1.5f), settled ? style.outline : style.secondary);
        mark.setImageDrawable(ring);
    }

    private static String title(SmartPlaylistMatcher.Row row) {
        return row.track.artist.isEmpty()
                ? row.track.title
                : row.track.title + " · " + row.track.artist;
    }

    private String detail(SmartPlaylistMatcher.Row row) {
        switch (row.state()) {
            case SEARCHING:
                return Strings.t("Searching…");
            case MATCHED:
                return Strings.f(
                        "Matched: {0} · {1}%",
                        name(row.selected), Math.round(row.confidence * 100));
            case REVIEW:
                return Strings.f(
                        "Review match: {0} · {1}%",
                        name(row.candidates.get(0)), Math.round(row.confidence * 100));
            case FAILED:
                return Strings.t("Search failed · tap to review");
            case SKIPPED:
                return Strings.t("Skipped");
            default:
                return Strings.t("No match found · tap to skip");
        }
    }

    private static String name(Object song) {
        String artist = MixedSongCodec.text(song, "artist");
        String title = MixedSongCodec.text(song, "title");
        return artist.isEmpty() ? title : title + " · " + artist;
    }

    /** Keeps the shared row's views, so progress updates cannot move focus or the scroll. */
    private static void setRow(View row, String title, String subtitle) {
        LinearLayout content = (LinearLayout) ((LinearLayout) row).getChildAt(1);
        TextView heading = (TextView) content.getChildAt(0),
                detail = (TextView) content.getChildAt(1);
        if (!title.contentEquals(heading.getText())) heading.setText(title);
        if (!subtitle.contentEquals(detail.getText())) {
            detail.setText(subtitle);
            detail.setMaxLines(4);
        }
        String description = title + ", " + subtitle;
        if (!description.contentEquals(String.valueOf(row.getContentDescription())))
            row.setContentDescription(description);
    }

    private static void visible(View view, boolean shown) {
        int wanted = shown ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) view.setVisibility(wanted);
    }

    private void loadConfig() {
        int token = session.begin(Phase.LOADING);
        render();
        SmartPlaylistStore reader = store;
        session.task =
                session.worker.submit(
                        () -> {
                            SmartPlaylistStore.Config config = null;
                            boolean unreadable = false;
                            try {
                                config = reader.load();
                            } catch (Exception | LinkageError failed) {
                                unreadable = true;
                            }
                            SmartPlaylistStore.Config loaded = config;
                            boolean bad = unreadable;
                            session.post(
                                    token,
                                    () -> {
                                        session.config = loaded;
                                        session.unreadable = bad;
                                        session.phase = Phase.IDLE;
                                        session.fillService = true;
                                    });
                        });
    }

    private void saveConfig() {
        if (session.phase != Phase.IDLE) return;
        SmartPlaylistStore writer = store;
        String url = endpoint.getText().toString();
        String name = model.getText().toString();
        String typed = key.getText().toString().trim();
        try {
            SmartPlaylistService.endpoint(url);
        } catch (SmartPlaylistService.Failure invalid) {
            refuse(endpoint, invalid);
            return;
        }
        try {
            SmartPlaylistService.model(name);
        } catch (SmartPlaylistService.Failure invalid) {
            refuse(model, invalid);
            return;
        }
        try {
            SmartPlaylistService.key(typed);
        } catch (SmartPlaylistService.Failure invalid) {
            refuse(key, invalid);
            return;
        }
        hideKeyboard();
        int token = session.begin(Phase.CONFIGURING);
        render();
        session.task =
                session.worker.submit(
                        () -> {
                            try {
                                writer.save(url, name, typed.isEmpty() ? null : typed);
                                SmartPlaylistStore.Config config = writer.load();
                                session.post(
                                        token,
                                        () -> {
                                            session.config = config;
                                            session.unreadable = false;
                                            session.phase = Phase.IDLE;
                                            session.configOpen = false;
                                            session.clearKey = true;
                                            session.message =
                                                    Strings.t("Service saved on this device.");
                                        });
                            } catch (Exception | LinkageError failed) {
                                session.post(
                                        token,
                                        () -> {
                                            session.phase = Phase.IDLE;
                                            session.message =
                                                    Strings.t(
                                                            "Could not save this service securely."
                                                                    + " Nothing was stored.");
                                        });
                            }
                        });
    }

    /** Takes the user to the field at fault, rather than only marking it somewhere offscreen. */
    private void refuse(EditText field, SmartPlaylistService.Failure invalid) {
        field.setError(explain(invalid.problem));
        field.requestFocus();
        reveal(field);
    }

    private void confirmDisconnect() {
        String host = session.config != null ? session.config.host : "";
        dialog =
                new AlertDialog.Builder(this)
                        .setTitle(Strings.t("Disconnect service?"))
                        .setMessage(
                                host.isEmpty()
                                        ? Strings.t(
                                                "The saved details and key are removed from this"
                                                        + " device.")
                                        : Strings.f(
                                                "The address, model and key for {0} are removed"
                                                        + " from this device. Your playlists are"
                                                        + " not affected.",
                                                host))
                        .setNegativeButton(Strings.t("Keep"), null)
                        .setPositiveButton(Strings.t("Disconnect"), (d, w) -> disconnect())
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void disconnect() {
        if (session.phase != Phase.IDLE) return;
        SmartPlaylistStore writer = store;
        int token = session.begin(Phase.CONFIGURING);
        render();
        session.task =
                session.worker.submit(
                        () -> {
                            boolean removed = true;
                            try {
                                writer.disconnect();
                            } catch (Exception | LinkageError failed) {
                                removed = false;
                            }
                            boolean done = removed;
                            session.post(
                                    token,
                                    () -> {
                                        session.phase = Phase.IDLE;
                                        if (!done) {
                                            session.message =
                                                    Strings.t(
                                                            "Could not remove the saved service."
                                                                    + " Try again.");
                                            return;
                                        }
                                        session.config = null;
                                        session.unreadable = false;
                                        session.configOpen = false;
                                        session.clearKey = true;
                                        session.clearService = true;
                                        session.message = Strings.t("Service removed.");
                                    });
                        });
    }

    private void chooseModel() {
        if (session.phase != Phase.IDLE) return;
        SmartPlaylistStore reader = store;
        SmartPlaylistService.Endpoint target;
        String typed = key.getText().toString().trim();
        try {
            target = SmartPlaylistService.endpoint(endpoint.getText().toString());
        } catch (SmartPlaylistService.Failure invalid) {
            refuse(endpoint, invalid);
            return;
        }
        try {
            SmartPlaylistService.key(typed);
        } catch (SmartPlaylistService.Failure invalid) {
            refuse(key, invalid);
            return;
        }
        SmartPlaylistService.Transport network = transport;
        int token = session.begin(Phase.CHOOSING_MODEL);
        SmartPlaylistService.Call call = session.call;
        render();
        session.task =
                session.worker.submit(
                        () -> {
                            try {
                                String credential = typed.isEmpty() ? reader.keyFor(target) : typed;
                                List<String> names =
                                        SmartPlaylistService.models(
                                                network, target, credential, call);
                                session.post(
                                        token,
                                        () -> {
                                            session.phase = Phase.IDLE;
                                            if (names.isEmpty())
                                                session.message =
                                                        Strings.t("This service listed no models.");
                                            else session.models = names;
                                        });
                            } catch (SmartPlaylistService.Failure failed) {
                                session.post(token, () -> fail(failed.problem));
                            } catch (Exception | LinkageError failed) {
                                session.post(
                                        token, () -> fail(SmartPlaylistService.Problem.NETWORK));
                            }
                        });
    }

    private void showModels() {
        List<String> names = session.models;
        session.models = null;
        String[] labels = names.toArray(new String[0]);
        dialog =
                new AlertDialog.Builder(this)
                        .setTitle(Strings.t("Choose model"))
                        .setItems(labels, (d, at) -> model.setText(labels[at]))
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void generate() {
        if (session.phase != Phase.IDLE || (session.serviceMode && session.config == null)) return;
        String request;
        try {
            request = SmartPlaylistService.prompt(prompt.getText().toString());
        } catch (SmartPlaylistService.Failure invalid) {
            prompt.setError(explain(invalid.problem));
            return;
        }
        hideKeyboard();
        if (!session.serviceMode) {
            generateLocally(request);
            return;
        }
        SmartPlaylistService.Preferences wanted =
                new SmartPlaylistService.Preferences(
                        count,
                        preferences.getBoolean("familiar", true),
                        preferences.getBoolean("clean", false));
        SmartPlaylistStore reader = store;
        SmartPlaylistService.Transport network = transport;
        SmartPlaylistMatcher.Search finder = search;
        SmartPlaylistMatcher.Best chooser = best;
        int token = session.begin(Phase.GENERATING);
        SmartPlaylistService.Call call = session.call;
        session.clearResults();
        render();
        session.task =
                session.worker.submit(
                        () -> {
                            SmartPlaylistStore.Credentials saved;
                            try {
                                saved = reader.credentials();
                            } catch (Exception | LinkageError unreadable) {
                                saved = null;
                            }
                            if (saved == null) {
                                session.post(
                                        token,
                                        () -> {
                                            session.phase = Phase.IDLE;
                                            session.config = null;
                                            session.unreadable = true;
                                            session.message =
                                                    Strings.t(
                                                            "Saved details can't be read. Set up"
                                                                    + " again.");
                                        });
                                return;
                            }
                            try {
                                SmartPlaylistService.Result result =
                                        SmartPlaylistService.generate(
                                                network,
                                                saved.endpoint,
                                                saved.key,
                                                saved.model,
                                                wanted,
                                                request,
                                                call);
                                List<SmartPlaylistMatcher.Row> rows = new ArrayList<>();
                                for (SmartPlaylistService.Suggestion track : result.tracks)
                                    rows.add(new SmartPlaylistMatcher.Row(rows.size(), track));
                                session.post(
                                        token,
                                        () -> {
                                            session.rows = rows;
                                            session.phase = Phase.MATCHING;
                                            session.nameToShow =
                                                    result.title.isEmpty()
                                                            ? Strings.t("Smart playlist")
                                                            : result.title;
                                        });
                                match(token, rows, finder, chooser);
                            } catch (SmartPlaylistService.Failure failed) {
                                session.post(token, () -> fail(failed.problem));
                            } catch (InterruptedException cancelled) {
                                Thread.currentThread().interrupt();
                            } catch (Exception | LinkageError failed) {
                                session.post(
                                        token, () -> fail(SmartPlaylistService.Problem.NETWORK));
                            }
                        });
    }

    /** Reads and ranks away from the main thread. Cancelling makes the running read stop. */
    private void generateLocally(String request) {
        LocalGenerator generator = local;
        Context app = getApplicationContext();
        int wanted = count;
        boolean familiar = preferences.getBoolean("familiar", true);
        boolean clean = preferences.getBoolean("clean", false);
        Session current = session;
        int token = current.begin(Phase.GENERATING);
        current.clearResults();
        render();
        current.task =
                current.worker.submit(
                        () -> {
                            try {
                                LocalPlaylist.Result result =
                                        generator.run(
                                                app,
                                                request,
                                                wanted,
                                                familiar,
                                                clean,
                                                () -> !current.current(token));
                                current.post(
                                        token, () -> showLocal(current, result, request, wanted));
                            } catch (InterruptedException cancelled) {
                                Thread.currentThread().interrupt();
                            } catch (Exception | LinkageError failed) {
                                current.post(
                                        token,
                                        () -> {
                                            current.phase = Phase.IDLE;
                                            current.message =
                                                    Strings.t(
                                                            "Could not read your library. Try"
                                                                    + " again.");
                                        });
                            }
                        });
    }

    /** Runs on the main thread, possibly for a page instance that replaced this one. */
    private static void showLocal(
            Session session, LocalPlaylist.Result result, String request, int wanted) {
        session.localName =
                request.length() <= 60 && request.indexOf('\n') < 0
                        ? request
                        : Strings.t("Smart playlist");
        session.untaggedOffer = result.unverified;
        String incomplete =
                result.unreadableParts > 0
                        ? Strings.t(
                                "Some of your library couldn't be read, so this may be incomplete.")
                        : "";
        if (result.picks.isEmpty()) {
            session.phase = Phase.IDLE;
            String why = result.message() == null ? "" : result.message();
            session.message = incomplete.isEmpty() ? why : why + " " + incomplete;
            return;
        }
        List<LocalRow> rows = new ArrayList<>();
        for (LocalPlaylist.Song pick : result.picks)
            rows.add(new LocalRow(pick.song, reasons(pick), false));
        session.localRows = rows;
        session.phase = Phase.REVIEW;
        session.nameToShow = session.localName;
        String shortList =
                rows.size() < wanted
                        ? Strings.f(
                                "Only {0} of {1} songs fit your description. Nothing was added to"
                                        + " fill the list.",
                                rows.size(), wanted)
                        : "";
        session.message = (shortList + " " + incomplete).trim();
    }

    /** The real evidence for a song, in the user's language. No evidence means nothing claimed. */
    private static String reasons(LocalPlaylist.Song pick) {
        List<String> words = new ArrayList<>();
        for (LocalPlaylistEngine.Why why : pick.why) {
            if (words.size() >= 3) break;
            String reason = LocalPlaylist.Result.reason(why, pick.bpm);
            if (reason != null) words.add(reason);
        }
        return words.isEmpty() ? Strings.t("From your library") : String.join(" · ", words);
    }

    /** Searches in the suggested order, so rows and the saved playlist keep the same sequence. */
    private void match(
            int token,
            List<SmartPlaylistMatcher.Row> rows,
            SmartPlaylistMatcher.Search finder,
            SmartPlaylistMatcher.Best chooser)
            throws InterruptedException {
        long pause = pauseBetweenSearches;
        for (SmartPlaylistMatcher.Row row : rows) {
            if (!session.current(token)) return;
            SmartPlaylistMatcher.Outcome outcome =
                    SmartPlaylistMatcher.resolve(row.track, finder, chooser);
            session.post(token, () -> row.apply(outcome));
            if (pause > 0) Thread.sleep(pause);
        }
        session.post(token, () -> session.phase = Phase.REVIEW);
    }

    private void fail(SmartPlaylistService.Problem problem) {
        session.phase = Phase.IDLE;
        session.clearResults();
        session.message = explain(problem);
    }

    /** Cancelling drops suggestions and matches together, so none can be saved afterwards. */
    private void cancelWork() {
        Phase phase = session.phase;
        if (phase != Phase.GENERATING && phase != Phase.MATCHING && phase != Phase.CHOOSING_MODEL)
            return;
        session.stop();
        session.phase = Phase.IDLE;
        session.clearResults();
        session.message =
                phase == Phase.CHOOSING_MODEL ? "" : Strings.t("Cancelled. Nothing was saved.");
        render();
    }

    private void startOver() {
        if (session.phase != Phase.REVIEW) return;
        session.stop();
        session.phase = Phase.IDLE;
        session.clearResults();
        session.message = "";
        render();
    }

    private void chooseMatch(SmartPlaylistMatcher.Row row) {
        if (session.phase != Phase.REVIEW) return;
        String[] labels = new String[row.candidates.size() + 1];
        int chosen = labels.length - 1;
        for (int i = 0; i < row.candidates.size(); i++) {
            Object song = row.candidates.get(i);
            labels[i] =
                    MixedSongCodec.text(song, "title")
                            + "\n"
                            + MixedSongCodec.text(song, "artist")
                            + " · "
                            + Strings.f(
                                    "{0}% match",
                                    Math.round(ImportConfidence.score(row.track, song) * 100));
            if (row.selected != null
                    && MixedSongCodec.key(song).equals(MixedSongCodec.key(row.selected)))
                chosen = i;
        }
        labels[labels.length - 1] = Strings.t("Skip this track");
        dialog =
                new AlertDialog.Builder(this)
                        .setTitle(row.track.title)
                        .setSingleChoiceItems(
                                labels,
                                chosen,
                                (d, at) -> {
                                    if (at == row.candidates.size()) row.skip();
                                    else row.choose(row.candidates.get(at));
                                    d.dismiss();
                                    render();
                                })
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void savePlaylist() {
        if (session.phase != Phase.REVIEW || !readyToSave()) return;
        String name = playlistName.getText().toString().trim();
        if (name.isEmpty() || name.length() > 300) {
            playlistName.setError(Strings.t("Enter a playlist name"));
            playlistName.requestFocus();
            reveal(playlistName);
            return;
        }
        hideKeyboard();
        List<Object> songs =
                session.localReview()
                        ? session.localSelection()
                        : SmartPlaylistMatcher.selection(session.rows);
        int token = session.begin(Phase.SAVING);
        render();
        session.task =
                session.worker.submit(
                        () -> {
                            if (!session.current(token)) return;
                            try {
                                MixedPlaylists.createAll(name, songs);
                                session.post(token, () -> session.saved = true);
                            } catch (Exception | LinkageError failed) {
                                session.post(
                                        token,
                                        () -> {
                                            session.phase = Phase.REVIEW;
                                            session.message =
                                                    Strings.t("Could not save this playlist.");
                                        });
                            }
                        });
    }

    private static String explain(SmartPlaylistService.Problem problem) {
        switch (problem) {
            case INVALID_ENDPOINT:
                return Strings.t(
                        "Enter an HTTPS service URL without a password, query or fragment.");
            case INVALID_MODEL:
                return Strings.t("Enter a model name.");
            case INVALID_KEY:
                return Strings.t("This key contains characters a service can't accept.");
            case INVALID_PROMPT:
                return Strings.t("Describe the playlist first.");
            case REDIRECTED:
                return Strings.t(
                        "The service redirected this request. Enter its final address instead.");
            case REJECTED_KEY:
                return Strings.t("The service rejected this key. Check it and try again.");
            case NOT_FOUND:
                return Strings.t("The service could not find this address or model.");
            case RATE_LIMITED:
                return Strings.t("The service is over its limit. Try again later.");
            case UNAVAILABLE:
                return Strings.t("The service is unavailable. Try again later.");
            case REFUSED:
                return Strings.t("The service refused this request. Check the model and address.");
            case TOO_LARGE:
                return Strings.t("The service sent too much data. Nothing was saved.");
            case NO_TRACKS:
                return Strings.t(
                        "The service suggested no usable tracks. Try another description.");
            case INVALID_RESPONSE:
                return Strings.t(
                        "The service reply could not be read. It may not support this format.");
            case CANCELLED:
                return Strings.t("Cancelled. Nothing was saved.");
            default:
                return Strings.t("Could not reach the service. Check your connection.");
        }
    }
}
