package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** Player-launched, two-track transition editor. Waveforms always describe real decoded audio. */
public final class TransitionEditorActivity extends Activity {
    private static final int YELLOW = 0xffe6c86c, CYAN = 0xff80b8dd, GREEN = 0xff6fca8c;
    private static final double MIN_OVERLAP = .5;
    private static final int[] BARS = {2, 4, 8, 16};
    private TelegramStyle style;
    private TransitionRuntime.Pair pair;
    private TransitionEdit edit;
    private Timeline timeline, miniTimeline;
    private LinearLayout workspace, expanded, compactRow, preferenceHeader;
    private ScrollView controlScroll;
    private TextView miniPreview,
            miniLength,
            draftSave,
            saveState,
            playerText,
            playerTime,
            playerPlay,
            menuAnchor;
    private TelegramSwitch autoSaveSwitch, autoApplySwitch;
    private boolean compact, autoSave, autoApply, dirtyDraft, applyPending;
    private boolean compactLayoutPending, controlTouching, expandingGesture;
    private float controlTouchY;
    private String appliedState, observedState;
    private final Runnable saveDraftLater = () -> persistDraft(false);
    private final Runnable applyLater =
            () -> {
                if (this.alive
                        && !isFinishing()
                        && !isDestroyed()
                        && autoApply
                        && applyPending
                        && this.audition == null) apply(false);
            };
    private static final ExecutorService ARTWORK =
            Executors.newFixedThreadPool(
                    2,
                    task -> {
                        Thread t =
                                new Thread(
                                        () -> {
                                            android.os.Process.setThreadPriority(
                                                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
                                            task.run();
                                        },
                                        "automix-artwork");
                        t.setDaemon(true);
                        return t;
                    });
    private TextView status, preview, snap, save, overlapValue;
    private SeekBar overlapBar;
    private boolean overlapTracking;
    private TextView analysisSummary;
    private AlertDialog analysisDialog;
    private LinearLayout analysisDetails;
    private String analysisSignature;
    private long nextAnalysisRequest = android.os.SystemClock.elapsedRealtime() + 1500;
    private LinearLayout controls, presets, tabs;
    private int side, point = 1, tab;
    private boolean wide, snapping = true, automatic, alive = true, repeatPreview;
    private int previewGeneration;
    private LevelView levels;
    private String initial;
    private TransitionPreview.Audition audition;
    private TransitionPreview.Session listening;
    private boolean storeReady, saving;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private ExecutorService worker;
    private final Future<?>[] waveJobs = new Future<?>[2];
    private final int[] waveGenerations = new int[2];
    private final boolean[] waveBusy = new boolean[2], waveAgain = new boolean[2];
    private final double[] jobFrom = new double[2], jobTo = new double[2];
    private final double[] bpms = new double[2], firstBeats = new double[2];
    private final String[] keys = new String[2];
    private final float[][][] waves = new float[2][][];
    private final boolean[] waveLoading = new boolean[2];
    private final double[] waveFrom = new double[2], waveTo = new double[2];
    private final float[] waveScale = {1, 1};
    private final TextView[] meta = new TextView[2];
    private final Runnable reloadWaves = this::requestWaves;
    private final Runnable analysisTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!alive || pair == null || timeline == null) return;
                    if (listening != null && listening.closed) listening = null;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now >= nextAnalysisRequest && storeReady && !waveBusy[0] && !waveBusy[1]) {
                        SourceAutomix.requestEditor(pair, false);
                        nextAnalysisRequest = now + 15000;
                    }
                    updatePlayer();
                    updateAnalysis();
                    if (readAnalysis()) {
                        update();
                        scheduleWaves();
                    }
                    handler.postDelayed(this, 1000);
                }
            };
    private final Runnable animate =
            new Runnable() {
                @Override
                public void run() {
                    if (!alive || audition == null || timeline == null) return;
                    message(
                            Strings.t(
                                    audition.playing()
                                            ? "Previewing transition"
                                            : "Loading preview…"));
                    timeline.invalidate();
                    if (miniTimeline != null) miniTimeline.invalidate();
                    if (levels != null) levels.invalidate();
                    handler.postDelayed(this, 33);
                }
            };

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
        wide =
                getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        getWindow().setStatusBarColor(background());
        getWindow().setNavigationBarColor(background());
        LinearLayout root =
                new LinearLayout(this) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        super.onMeasure(width, height);
                        if (wide
                                || compact
                                || workspace == null
                                || controlScroll == null
                                || workspace.getParent() != this) return;
                        LinearLayout settings = (LinearLayout) controlScroll.getParent();
                        // The waveform gives space to the controls in shorter windows.
                        int reserved = getPaddingTop() + getPaddingBottom() + style.dp(96);
                        for (int i = 0; i < getChildCount(); i++) {
                            View child = getChildAt(i);
                            if (child != workspace
                                    && child != settings
                                    && child.getVisibility() != View.GONE)
                                reserved += child.getMeasuredHeight();
                        }
                        for (int i = 0; i < settings.getChildCount(); i++) {
                            View child = settings.getChildAt(i);
                            if (child != controlScroll && child.getVisibility() != View.GONE)
                                reserved += child.getMeasuredHeight();
                        }
                        int available = getMeasuredHeight() - reserved;
                        if (available < style.dp(260)) setCompact(true);
                        else {
                            ViewGroup.LayoutParams space = workspace.getLayoutParams();
                            int desired = Math.min(style.dp(expandedHeight()), available);
                            if (space.height == desired) return;
                            space.height = desired;
                            workspace.setLayoutParams(space);
                        }
                        super.onMeasure(width, height);
                    }
                };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag("transition-editor-root");
        root.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8));
        root.setBackgroundColor(background());
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    int left, top, right, bottom;
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Insets safe =
                                insets.getInsets(
                                        WindowInsets.Type.systemBars()
                                                | WindowInsets.Type.displayCutout()
                                                | WindowInsets.Type.ime());
                        left = safe.left;
                        top = safe.top;
                        right = safe.right;
                        bottom = safe.bottom;
                    } else {
                        left = insets.getSystemWindowInsetLeft();
                        top = insets.getSystemWindowInsetTop();
                        right = insets.getSystemWindowInsetRight();
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                    view.setPadding(
                            style.dp(16) + left,
                            style.dp(8) + top,
                            style.dp(16) + right,
                            style.dp(8) + bottom);
                    return insets;
                });
        root.requestApplyInsets();
        LinearLayout header = row();
        TextView close = chip("×", false, this::close);
        close.setTextSize(30);
        close.setContentDescription(Strings.t("Close"));
        close.setText("");
        close.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("close", style.foreground, 21), null, null, null);
        close.setPadding(style.dp(10), 0, style.dp(10), 0);
        close.setBackground(style.shape(style.dark ? 0xff30312f : style.card, 24, false));
        LinearLayout.LayoutParams closeSpace =
                new LinearLayout.LayoutParams(style.dp(40), style.dp(40));
        closeSpace.rightMargin = style.dp(12);
        header.addView(close, closeSpace);
        TextView heading = label(Strings.t("Automix Editor"), 18, true);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        snap =
                chip(
                        Strings.t("Beats"),
                        true,
                        () -> {
                            snapping = !snapping;
                            update();
                        });
        snap.setCompoundDrawablePadding(style.dp(5));
        snap.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("beats", GREEN, 19), null, null, null);
        snap.setMinHeight(style.dp(40));
        snap.setPadding(style.dp(9), 0, style.dp(9), 0);
        snap.setTag("transition-snap");
        snap.setContentDescription(Strings.t("Snap to beat grid"));
        header.addView(snap);
        TextView menu = chip("···", false, this::menu);
        menu.setContentDescription(Strings.t("Settings"));
        menuAnchor = menu;
        menu.setText("");
        menu.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("more", style.foreground, 21), null, null, null);
        menu.setPadding(style.dp(10), 0, style.dp(10), 0);
        menu.setBackground(style.shape(style.dark ? 0xff30312f : style.card, 24, false));
        LinearLayout.LayoutParams menuSpace =
                new LinearLayout.LayoutParams(style.dp(40), style.dp(40));
        menuSpace.leftMargin = style.dp(8);
        header.addView(menu, menuSpace);
        save = chip(Strings.t("Apply"), true, this::save);
        save.setTag("transition-save");
        save.setTextColor(0xff071b0f);
        save.setBackground(style.shape(GREEN, 24, false));
        root.addView(header, new LinearLayout.LayoutParams(-1, style.dp(64)));
        status = label("", 12, false);
        status.setTag("transition-status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(style.dp(4), style.dp(6), style.dp(4), style.dp(6));

        try {
            pair = TransitionRuntime.pair();
        } catch (Exception unavailable) {
            pair = null;
        }
        if (pair == null || pair.outgoingDuration <= 0 || pair.incomingDuration <= 0) {
            message(
                    pair == null
                            ? Strings.t("Add two tracks to the queue first.")
                            : Strings.t(
                                    "Track durations are not available yet. Start playback or"
                                            + " choose another source."));
            root.addView(status);
            root.addView(save);
            save.setEnabled(false);
            snap.setVisibility(View.GONE);
            setContentView(root);
            return;
        }
        android.content.SharedPreferences options =
                getSharedPreferences("bitchord_automix_editor", MODE_PRIVATE);
        autoSave = options.getBoolean("auto_save", true);
        autoApply = options.getBoolean("auto_apply", false);
        TransitionEdit found = TransitionRuntime.load(pair);
        automatic = found == null;
        edit = found == null ? TransitionRuntime.automaticDraft(pair) : found;
        initial = state();
        boolean preserveDraft =
                saved != null
                        && pair.key.equals(saved.getString("pair"))
                        && saved.getBoolean("storeReady", false);
        if (saved != null && pair.key.equals(saved.getString("pair"))) {
            try {
                edit = TransitionEdit.decode(saved.getString("draft"));
            } catch (Exception ignored) {
            }
            side = saved.getInt("side", 0);
            tab = saved.getInt("tab", 0);
            snapping = saved.getBoolean("snap", true);
            repeatPreview = saved.getBoolean("repeat", false);
            automatic = saved.getBoolean("automatic", false);
            applyPending = saved.getBoolean("applyPending", false);
            initial = saved.getString("initial", initial);
        }
        normalize();
        readAnalysis();
        buildLayout(root);
        setContentView(root);
        buildControls();
        update();
        if (saved != null) {
            dirtyDraft = saved.getBoolean("dirtyDraft", false);
            setCompact(saved.getBoolean("compact", false));
        }
        listening = TransitionPreview.existing(pair);
        if (listening != null) listening.attach(this);
        save.setEnabled(false);
        preview.setEnabled(false);
        message(Strings.t("Loading…"));
        requestWaves();
        TransitionStore.get()
                .read(
                        pair,
                        result -> {
                            if (isDestroyed() || isFinishing()) return;
                            TransitionStore.get()
                                    .readDraft(
                                            pair,
                                            draft -> {
                                                if (isDestroyed() || isFinishing()) return;
                                                storeReady = true;
                                                TransitionEdit stored =
                                                        result.error == null ? result.edit() : null;
                                                appliedState =
                                                        stored == null
                                                                ? state(
                                                                        TransitionRuntime
                                                                                .automaticDraft(
                                                                                        pair),
                                                                        true)
                                                                : state(stored, false);
                                                if (!preserveDraft && state().equals(initial)) {
                                                    automatic =
                                                            draft.edit != null
                                                                            && draft.error == null
                                                                    ? draft.automatic
                                                                    : stored == null;
                                                    edit =
                                                            draft.edit != null
                                                                            && draft.error == null
                                                                    ? draft.edit
                                                                    : stored == null
                                                                            ? TransitionRuntime
                                                                                    .automaticDraft(
                                                                                            pair)
                                                                            : stored;
                                                    normalize();
                                                    initial = state();
                                                    update();
                                                    buildControls();
                                                    scheduleWaves();
                                                }
                                                save.setEnabled(true);
                                                preview.setEnabled(true);
                                                miniPreview.setEnabled(true);
                                                requestWaves();
                                                if (dirtyDraft && autoSave)
                                                    handler.postDelayed(saveDraftLater, 450);
                                                if (autoApply && applyPending)
                                                    handler.postDelayed(applyLater, 750);
                                                message(
                                                        result.error == null && draft.error == null
                                                                ? ""
                                                                : Strings.t(
                                                                        "Could not load saved"
                                                                                + " transitions."));
                                            });
                        });
    }

    private void buildLayout(LinearLayout root) {
        workspace = column();
        workspace.setTag("automix-workspace");
        expanded = column();
        expanded.setTag("automix-expanded");
        expanded.addView(track(0));
        FrameLayout graph = new FrameLayout(this);
        timeline = new Timeline(false);
        timeline.setTag("transition-timeline");
        graph.addView(timeline, new FrameLayout.LayoutParams(-1, -1));
        preview = previewButton(60);
        preview.setTag("transition-preview");
        graph.addView(
                preview, new FrameLayout.LayoutParams(style.dp(60), style.dp(60), Gravity.CENTER));
        expanded.addView(graph, new LinearLayout.LayoutParams(-1, 0, 1));
        expanded.addView(overlapRow(), new LinearLayout.LayoutParams(-1, -2));
        expanded.addView(track(1));
        workspace.addView(expanded, new LinearLayout.LayoutParams(-1, -1));
        compactRow = column();
        compactRow.setTag("automix-compact");
        compactRow.setVisibility(View.GONE);
        LinearLayout names = row();
        for (int d = 0; d < 2; d++) {
            TextView title =
                    label(
                            (d == 0 ? "A  " : "B  ")
                                    + (d == 0 ? pair.outgoingTitle : pair.incomingTitle),
                            11,
                            true);
            title.setTextColor(d == 0 ? YELLOW : CYAN);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(title, new LinearLayout.LayoutParams(0, style.dp(28), 1));
        }
        TextView expand = chip("", false, this::expandWaveforms);
        expand.setTag("automix-expand");
        expand.setContentDescription(Strings.t("Expand waveforms"));
        expand.setMinHeight(0);
        expand.setPadding(0, 0, 0, 0);
        expand.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("expand", style.foreground, 20), null, null, null);
        expand.setPadding(style.dp(14), 0, 0, 0);
        names.addView(expand, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        compactRow.addView(names);
        FrameLayout miniGraph = new FrameLayout(this);
        miniTimeline = new Timeline(true);
        miniTimeline.setTag("automix-mini-timeline");
        miniTimeline.setContentDescription(Strings.t("Expand waveforms"));
        miniTimeline.setOnClickListener(v -> expandWaveforms());
        FrameLayout.LayoutParams miniature = new FrameLayout.LayoutParams(-1, -1);
        miniature.rightMargin = style.dp(56);
        miniGraph.addView(miniTimeline, miniature);
        miniPreview = previewButton(48);
        miniPreview.setTag("automix-mini-preview");
        miniGraph.addView(
                miniPreview,
                new FrameLayout.LayoutParams(
                        style.dp(48), style.dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        compactRow.addView(miniGraph, new LinearLayout.LayoutParams(-1, 0, 1));
        miniLength = label("", 10, false);
        compactRow.addView(miniLength);
        workspace.addView(compactRow, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout right = column();
        preferenceHeader = column();
        right.addView(preferenceHeader);
        LinearLayout options = row();
        options.setPadding(style.dp(2), style.dp(10), style.dp(2), style.dp(5));
        autoApplySwitch =
                optionSwitch(
                        "Auto-apply",
                        autoApply,
                        value -> {
                            autoApply = value;
                            getSharedPreferences("bitchord_automix_editor", 0)
                                    .edit()
                                    .putBoolean("auto_apply", value)
                                    .apply();
                            handler.removeCallbacks(applyLater);
                            applyPending = value && !state().equals(appliedState);
                            if (applyPending) handler.postDelayed(applyLater, 750);
                            updateSaveState();
                        });
        autoApplySwitch.setTag("automix-auto-apply");
        autoSaveSwitch =
                optionSwitch(
                        "Auto-save",
                        autoSave,
                        value -> {
                            autoSave = value;
                            getSharedPreferences("bitchord_automix_editor", 0)
                                    .edit()
                                    .putBoolean("auto_save", value)
                                    .apply();
                            handler.removeCallbacks(saveDraftLater);
                            if (value && dirtyDraft) persistDraft(false);
                            updateSaveState();
                        });
        autoSaveSwitch.setTag("automix-auto-save");
        options.addView(
                optionRow(autoApplySwitch, "Auto-apply"), new LinearLayout.LayoutParams(0, -2, 1));
        options.addView(
                optionRow(autoSaveSwitch, "Auto-save"), new LinearLayout.LayoutParams(0, -2, 1));
        preferenceHeader.addView(options);
        LinearLayout captions = row();
        TextView presetLabel = label(Strings.t("Presets"), 11, false);
        captions.addView(presetLabel, new LinearLayout.LayoutParams(0, style.dp(27), 1));
        saveState = label("", 11, false);
        saveState.setTag("automix-save-state");
        captions.addView(saveState);
        preferenceHeader.addView(captions);
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presets = row();
        presetScroll.addView(presets);
        preferenceHeader.addView(presetScroll, new LinearLayout.LayoutParams(-1, style.dp(46)));
        analysisSummary = chip("", false, this::showAnalysis);
        analysisSummary.setTag("automix-analysis");
        analysisSummary.setTextSize(11);
        analysisSummary.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        analysisSummary.setPadding(style.dp(8), 0, style.dp(8), 0);
        analysisSummary.setSingleLine(false);
        analysisSummary.setMaxLines(2);
        analysisSummary.setEllipsize(TextUtils.TruncateAt.END);
        analysisSummary.setBackground(style.shape(style.card, 10, false));
        right.addView(analysisSummary, new LinearLayout.LayoutParams(-1, style.dp(48)));
        tabs = row();
        right.addView(tabs, new LinearLayout.LayoutParams(-1, style.dp(48)));
        controlScroll = new ScrollView(this);
        controlScroll.setTag("automix-controls-scroll");
        controlScroll.setFillViewport(true);
        controls = column();
        controls.setPadding(0, style.dp(18), 0, style.dp(28));
        controlScroll.addView(controls);
        controlScroll.setOnScrollChangeListener(
                (v, x, y, oldX, oldY) -> {
                    if (wide || compactLayoutPending || expandingGesture) return;
                    if (y > style.dp(12)) setCompact(true);
                    else if (compact && y == 0 && !controlTouching) setCompact(false);
                });
        controlScroll.addOnLayoutChangeListener(
                (v, left, top, rightEdge, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        compactLayoutPending = false);
        controlScroll.setOnTouchListener(
                (v, event) -> {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            controlTouching = true;
                            expandingGesture = false;
                            controlTouchY = event.getY();
                            break;
                        case MotionEvent.ACTION_MOVE:
                            float down = event.getY() - controlTouchY;
                            if (compact
                                    && down > style.dp(8)
                                    && controlScroll.getScrollY() <= down) {
                                expandingGesture = true;
                                MotionEvent cancel = MotionEvent.obtain(event);
                                cancel.setAction(MotionEvent.ACTION_CANCEL);
                                controlScroll.onTouchEvent(cancel);
                                cancel.recycle();
                                expandWaveforms();
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            controlTouching = false;
                            boolean consumed = expandingGesture;
                            expandingGesture = false;
                            return consumed;
                        default:
                            break;
                    }
                    return expandingGesture;
                });
        right.addView(controlScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (wide) {
            LinearLayout panes = row();
            panes.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 1);
            left.rightMargin = style.dp(14);
            panes.addView(workspace, left);
            panes.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(workspace, new LinearLayout.LayoutParams(-1, style.dp(expandedHeight())));
            root.addView(right, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout footer = column();
        footer.setPadding(style.dp(3), style.dp(8), style.dp(3), 0);
        status.setTextSize(11);
        status.setPadding(style.dp(4), 0, style.dp(4), style.dp(6));
        footer.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        draftSave = chip(Strings.t("Save draft"), false, () -> persistDraft(true));
        draftSave.setTag("automix-save-draft");
        draftSave.setTextSize(13);
        draftSave.setMinHeight(style.dp(48));
        draftSave.setMinWidth(style.dp(88));
        draftSave.setMaxWidth(style.dp(160));
        draftSave.setPadding(style.dp(12), 0, style.dp(12), 0);
        draftSave.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        new InsetDrawable(
                                style.shape(background(), 20, true),
                                0,
                                style.dp(4),
                                0,
                                style.dp(4)),
                        null));
        LinearLayout.LayoutParams draftSpace = new LinearLayout.LayoutParams(-2, -2);
        draftSpace.rightMargin = style.dp(10);
        actions.addView(draftSave, draftSpace);
        save.setMinHeight(style.dp(48));
        save.setMinWidth(style.dp(88));
        save.setMaxWidth(style.dp(160));
        save.setBackground(
                new InsetDrawable(style.shape(GREEN, 20, false), 0, style.dp(4), 0, style.dp(4)));
        save.setTextSize(13);
        save.setPadding(style.dp(12), 0, style.dp(12), 0);
        actions.addView(save, new LinearLayout.LayoutParams(-2, -2));
        footer.addView(actions);
        LinearLayout player = row();
        player.setPadding(0, style.dp(5), 0, 0);
        playerPlay = chip("", false, this::togglePlayer);
        playerPlay.setTag("automix-player-play");
        playerPlay.setPadding(style.dp(7), 0, 0, 0);
        playerPlay.setMinHeight(0);
        player.addView(playerPlay, new LinearLayout.LayoutParams(style.dp(26), style.dp(30)));
        playerText = label("", 10, false);
        playerText.setSingleLine(true);
        playerText.setEllipsize(TextUtils.TruncateAt.END);
        player.addView(playerText, new LinearLayout.LayoutParams(0, -2, 1));
        playerTime = label("", 10, false);
        player.addView(playerTime);
        footer.addView(player);
        root.addView(footer);
        updateSaveState();
    }

    private int expandedHeight() {
        return Math.max(
                260,
                Math.min(487, (int) (getResources().getConfiguration().screenHeightDp * .51) - 48));
    }

    private void setCompact(boolean value) {
        if (wide || workspace == null || compact == value) return;
        compact = value;
        compactLayoutPending = true;
        expanded.setVisibility(value ? View.GONE : View.VISIBLE);
        compactRow.setVisibility(value ? View.VISIBLE : View.GONE);
        preferenceHeader.setVisibility(value ? View.GONE : View.VISIBLE);
        ViewGroup.LayoutParams layout = workspace.getLayoutParams();
        layout.height = style.dp(value ? 132 : expandedHeight());
        workspace.setLayoutParams(layout);
    }

    private void expandWaveforms() {
        setCompact(false);
        controlScroll.scrollTo(0, 0);
    }

    private TelegramSwitch optionSwitch(
            String text, boolean checked, java.util.function.Consumer<Boolean> change) {
        TelegramSwitch toggle = new TelegramSwitch(this, style);
        toggle.setContentDescription(Strings.t(text));
        toggle.setChecked(checked);
        toggle.setListener((v, on) -> change.accept(on));
        return toggle;
    }

    private View optionRow(TelegramSwitch toggle, String title) {
        LinearLayout row = row();
        row.setMinimumHeight(style.dp(48));
        row.addView(toggle, new LinearLayout.LayoutParams(style.dp(64), style.dp(48)));
        TextView text = label(Strings.t(title), 12, true);
        text.setTypeface(style.regular);
        text.setMaxLines(2);
        text.setOnClickListener(v -> toggle.toggle());
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private TextView previewButton(int size) {
        TextView button = chip("", false, this::preview);
        button.setContentDescription(Strings.t("Preview"));
        button.setMinHeight(0);
        int inset = (size - (size > 40 ? 27 : 15)) / 2;
        button.setPadding(style.dp(inset), 0, 0, 0);
        button.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("play", style.foreground, size > 40 ? 27 : 15), null, null, null);
        button.setBackground(style.shape(style.dark ? 0xff4c514d : 0xffcccccc, size / 2, true));
        return button;
    }

    private void updatePlayer() {
        if (playerText == null) return;
        try {
            Object current = TransitionRuntime.player();
            boolean playing =
                    (Boolean) TransitionRuntime.pcall(current, "getPlayWhenReady", new Class<?>[0]);
            long at =
                    ((Number)
                                    TransitionRuntime.pcall(
                                            current, "getCurrentPosition", new Class<?>[0]))
                            .longValue();
            androidx.media3.common.MediaItem item =
                    (androidx.media3.common.MediaItem)
                            TransitionRuntime.pcall(
                                    current, "getCurrentMediaItem", new Class<?>[0]);
            playerText.setText(
                    (listening != null && !listening.closed
                                    ? Strings.t("Current song paused")
                                    : Strings.t(playing ? "Playing" : "Paused"))
                            + " · "
                            + (item == null ? "" : TransitionRuntime.title(item)));
            playerTime.setText(clock(at / 1000d));
            playerPlay.setContentDescription(Strings.t(playing ? "Pause" : "Resume"));
            playerPlay.setCompoundDrawablesWithIntrinsicBounds(
                    new Glyph(playing ? "pause" : "play", style.foreground, 12), null, null, null);
        } catch (Exception unavailable) {
            playerText.setText(Strings.t("Playback unavailable"));
        }
    }

    private void togglePlayer() {
        if (listening != null && !listening.closed) {
            message(Strings.t("Apply or close to resume playback."));
            return;
        }
        try {
            Object player = TransitionRuntime.player();
            boolean playing =
                    (Boolean) TransitionRuntime.pcall(player, "getPlayWhenReady", new Class<?>[0]);
            TransitionRuntime.pcall(
                    player, "setPlayWhenReady", new Class<?>[] {boolean.class}, !playing);
            updatePlayer();
        } catch (Exception unavailable) {
            message(Strings.t("Playback unavailable"));
        }
    }

    private int background() {
        return style.dark ? 0xff202020 : style.background;
    }

    private boolean readAnalysis() {
        boolean changed = false;
        for (int d = 0; d < 2; d++) {
            Object analysis = TransitionRuntime.analysis(pair, d);
            TransitionAnalysis.Snapshot measured = TransitionAnalysis.read(pair, d);
            double bpm = measured.beatGrid() ? measured.bpm : 0;
            double first = measured.beatGrid() ? measured.firstBeat : 0;
            String key = TransitionRuntime.musicalKey(analysis);
            changed |=
                    bpm != bpms[d]
                            || first != firstBeats[d]
                            || !java.util.Objects.equals(key, keys[d]);
            bpms[d] = bpm;
            firstBeats[d] = first;
            keys[d] = key;
        }
        return changed;
    }

    private View track(int deck) {
        LinearLayout row = row();
        row.setTag("transition-track:" + deck);
        row.setPadding(0, style.dp(wide ? 4 : 10), 0, style.dp(wide ? 4 : 10));
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageDrawable(new Cover(deck));
        row.addView(cover, new LinearLayout.LayoutParams(style.dp(44), style.dp(44)));
        LinearLayout names = column();
        TextView title = label(deck == 0 ? pair.outgoingTitle : pair.incomingTitle, 16, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        androidx.media3.common.MediaItem item = deck == 0 ? pair.outgoing : pair.incoming;
        CharSequence artist = item == null ? null : item.mediaMetadata.artist;
        TextView artistLabel =
                label(
                        artist == null
                                ? Strings.t(deck == 0 ? "Outgoing" : "Incoming")
                                : artist.toString(),
                        13,
                        false);
        artistLabel.setSingleLine(true);
        artistLabel.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(artistLabel);
        LinearLayout.LayoutParams nameSpace = new LinearLayout.LayoutParams(0, -2, 1);
        nameSpace.leftMargin = style.dp(10);
        nameSpace.rightMargin = style.dp(10);
        row.addView(names, nameSpace);
        meta[deck] = label("", 11, false);
        meta[deck].setGravity(Gravity.END);
        row.addView(meta[deck]);
        loadCover(item, cover);
        return row;
    }

    private final class TabLine extends android.graphics.drawable.ColorDrawable {
        final boolean selected;
        final Paint paint = new Paint();

        TabLine(boolean selected) {
            super(Color.TRANSPARENT);
            this.selected = selected;
        }

        public void draw(Canvas canvas) {
            paint.setColor(selected ? GREEN : (style.dark ? 0xff30322d : style.outline));
            Rect b = getBounds();
            canvas.drawRect(
                    b.left, b.bottom - style.dp(selected ? 2 : 1), b.right, b.bottom, paint);
        }
    }

    private final class Glyph extends android.graphics.drawable.Drawable {
        final String name;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int size;

        Glyph(String name, int color, int dp) {
            this.name = name;
            size = style.dp(dp);
            paint.setColor(color);
            paint.setStrokeWidth(1.7f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setBounds(0, 0, size, size);
        }

        void line(Canvas c, float... xy) {
            Path p = new Path();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            c.drawPath(p, paint);
        }

        public void draw(Canvas canvas) {
            int save = canvas.save();
            Rect b = getBounds();
            canvas.translate(b.left, b.top);
            canvas.scale(b.width() / 24f, b.height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            switch (name) {
                case "expand":
                    line(canvas, 6, 9, 12, 15, 18, 9);
                    break;
                case "close":
                    line(canvas, 6, 6, 18, 18);
                    line(canvas, 18, 6, 6, 18);
                    break;
                case "more":
                    paint.setStyle(Paint.Style.FILL);
                    for (int x : new int[] {4, 12, 20}) canvas.drawCircle(x, 12, 1.6f, paint);
                    break;
                case "beats":
                    for (int i = 0; i < 5; i++) {
                        float h = new float[] {3, 7, 10, 6, 3}[i];
                        line(canvas, 3 + i * 4, 12 - h, 3 + i * 4, 12 + h);
                    }
                    break;
                case "volume":
                    line(canvas, 11, 4, 5, 9, 2, 9, 2, 15, 5, 15, 11, 20, 11, 4);
                    canvas.drawArc(8, 5, 21, 19, -62, 124, false, paint);
                    canvas.drawArc(10, 1, 25, 23, -62, 124, false, paint);
                    break;
                case "eq":
                    for (int i = 0; i < 3; i++) {
                        int x = 4 + i * 8, y = new int[] {11, 15, 8}[i];
                        line(canvas, x, 3, x, y - 3);
                        line(canvas, x, y + 3, x, 21);
                        canvas.drawCircle(x, y, 3, paint);
                    }
                    break;
                case "effects":
                    for (int i = 0; i < 3; i++) {
                        int y = 5 + i * 7, x = i == 1 ? 16 : 9;
                        line(canvas, 2, y, x - 3, y);
                        line(canvas, x + 3, y, 22, y);
                        canvas.drawCircle(x, y, 3, paint);
                    }
                    break;
                case "tools":
                    line(canvas, 8, 8, 21, 21);
                    line(canvas, 16, 8, 3, 21);
                    canvas.drawOval(2, 2, 9, 9, paint);
                    canvas.drawOval(15, 2, 22, 9, paint);
                    break;
                case "stop":
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(5, 5, 19, 19, 2, 2, paint);
                    break;
                case "pause":
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRect(5, 4, 10, 20, paint);
                    canvas.drawRect(14, 4, 19, 20, paint);
                    break;
                default:
                    paint.setStyle(Paint.Style.FILL);
                    Path p = new Path();
                    p.moveTo(7, 3);
                    p.lineTo(21, 12);
                    p.lineTo(7, 21);
                    p.close();
                    canvas.drawPath(p, paint);
            }
            canvas.restoreToCount(save);
        }

        public int getIntrinsicWidth() {
            return size;
        }

        public int getIntrinsicHeight() {
            return size;
        }

        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private final class Cover extends android.graphics.drawable.Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int deck;

        Cover(int deck) {
            this.deck = deck;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect r = getBounds();
            paint.setShader(
                    new LinearGradient(
                            r.left,
                            r.top,
                            r.right,
                            r.bottom,
                            deck == 0 ? 0xff485d2a : 0xff293e68,
                            0xff1a1d23,
                            Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(r), style.dp(5), style.dp(5), paint);
            paint.setShader(null);
            paint.setColor(deck == 0 ? YELLOW : CYAN);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(style.dp(18));
            paint.setTypeface(style.heavy);
            canvas.drawText(deck == 0 ? "A" : "B", r.centerX(), r.centerY() + style.dp(6), paint);
        }

        @Override
        public void setAlpha(int a) {
            paint.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter f) {
            paint.setColorFilter(f);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private ExecutorService worker() {
        if (worker == null || worker.isShutdown())
            worker =
                    Executors.newFixedThreadPool(
                            2,
                            task -> {
                                Thread thread =
                                        new Thread(
                                                () -> {
                                                    android.os.Process.setThreadPriority(
                                                            android.os.Process
                                                                    .THREAD_PRIORITY_BACKGROUND);
                                                    task.run();
                                                },
                                                "transition-waveform");
                                thread.setDaemon(true);
                                return thread;
                            });
        return worker;
    }

    private void loadCover(androidx.media3.common.MediaItem item, ImageView target) {
        if (item == null) return;
        ARTWORK.execute(
                () -> {
                    java.net.HttpURLConnection connection = null;
                    try {
                        Object value = TransitionRuntime.field(item.mediaMetadata, "artworkUri");
                        if (!(value instanceof android.net.Uri)) return;
                        android.net.Uri uri = (android.net.Uri) value;
                        java.io.InputStream stream;
                        if ("https".equals(uri.getScheme())) {
                            connection =
                                    (java.net.HttpURLConnection)
                                            new java.net.URL(uri.toString()).openConnection();
                            connection.setConnectTimeout(4000);
                            connection.setReadTimeout(4000);
                            stream = connection.getInputStream();
                        } else if ("content".equals(uri.getScheme())
                                || "file".equals(uri.getScheme())) {
                            stream = getContentResolver().openInputStream(uri);
                        } else return;
                        byte[] data;
                        try (java.io.InputStream input = stream;
                                java.io.ByteArrayOutputStream output =
                                        new java.io.ByteArrayOutputStream()) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                if (output.size() + read > 2 * 1024 * 1024
                                        || Thread.currentThread().isInterrupted()) return;
                                output.write(buffer, 0, read);
                            }
                            data = output.toByteArray();
                        }
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, options);
                        if (options.outWidth <= 0 || options.outHeight <= 0) return;
                        options.inSampleSize =
                                Math.max(1, Math.max(options.outWidth, options.outHeight) / 160);
                        options.inJustDecodeBounds = false;
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap cover = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                        if (cover != null)
                            handler.post(
                                    () -> {
                                        if (alive && !isDestroyed()) target.setImageBitmap(cover);
                                    });
                    } catch (Exception unavailable) {
                        /* Artwork is optional. */
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                });
    }

    private void normalize() {
        edit.outgoingTrim =
                Math.max(0, Math.min(edit.outgoingTrim, Math.min(120, pair.outgoingDuration - .5)));
        edit.incomingCue =
                Math.max(
                        0, Math.min(edit.incomingCue, Math.min(3600, pair.incomingDuration - 1.5)));
        edit.overlap = Math.max(.5, Math.min(edit.overlap, pair.maximumOverlap(edit)));
        edit.validate();
    }

    private void remember() {
        stopPreview();
        undo.addLast(state());
        while (undo.size() > 40) undo.removeFirst();
        redo.clear();
    }

    private String state() {
        return state(edit, automatic);
    }

    private String state(TransitionEdit edit, boolean automatic) {
        TransitionEdit copy = edit.copy();
        copy.recording = null;
        return (automatic ? "1" : "0") + copy.encode();
    }

    private void restore(String value) {
        automatic = value.charAt(0) == '1';
        edit = TransitionEdit.decode(value.substring(1));
        normalize();
        buildControls();
        update();
        scheduleWaves();
    }

    private void changed() {
        stopPreview();
        normalize();
        automatic = false;
        edit.preset = -1;
        update();
        scheduleWaves();
    }

    private void message(String text) {
        if (status == null) return;
        status.setText(text.isEmpty() ? defaultStatus() : text);
        status.setVisibility(View.VISIBLE);
    }

    private void updateAnalysis() {
        if (analysisSummary == null || pair == null) return;
        TransitionAnalysis.Snapshot a = TransitionAnalysis.read(pair, 0),
                b = TransitionAnalysis.read(pair, 1);
        analysisSummary.setText(
                Strings.t("Track analysis") + "  ›\nA  " + a.tempo() + "    B  " + b.tempo());
        analysisSummary.setContentDescription(
                Strings.t("Track analysis") + ", A " + a.tempo() + ", B " + b.tempo());
        String signature = a.signature() + b.signature();
        if (analysisDialog != null
                && analysisDialog.isShowing()
                && !signature.equals(analysisSignature)) {
            analysisSignature = signature;
            analysisDetails.removeAllViews();
            analysisCard(0, a);
            analysisCard(1, b);
        }
    }

    private void showAnalysis() {
        if (analysisDialog != null && analysisDialog.isShowing()) return;
        analysisSignature = null;
        ScrollView scroll = new ScrollView(this);
        analysisDetails = column();
        analysisDetails.setPadding(style.dp(18), style.dp(4), style.dp(18), style.dp(8));
        scroll.addView(analysisDetails);
        analysisDialog =
                new AlertDialog.Builder(this)
                        .setTitle(Strings.t("Track analysis"))
                        .setView(scroll)
                        .setPositiveButton(Strings.t("Close"), null)
                        .setNeutralButton(Strings.t("Retry"), null)
                        .create();
        analysisDialog.setOnShowListener(d -> style.tuneDialog(analysisDialog));
        analysisDialog.setOnDismissListener(
                d -> {
                    analysisDialog = null;
                    analysisDetails = null;
                    analysisSignature = null;
                });
        analysisDialog.show();
        analysisDialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(
                        v -> {
                            SourceAutomix.requestEditor(pair, true);
                            nextAnalysisRequest = android.os.SystemClock.elapsedRealtime() + 15000;
                            updateAnalysis();
                        });
        updateAnalysis();
    }

    private void analysisCard(int deck, TransitionAnalysis.Snapshot measured) {
        LinearLayout card = column();
        card.setTag("automix-analysis-track:" + deck);
        card.setPadding(style.dp(12), style.dp(12), style.dp(12), style.dp(12));
        card.setBackground(style.shape(style.card, 12, false));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = style.dp(10);
        analysisDetails.addView(card, space);
        TextView title =
                label(
                        (deck == 0 ? "A · " + pair.outgoingTitle : "B · " + pair.incomingTitle),
                        15,
                        true);
        title.setTextColor(deck == 0 ? YELLOW : CYAN);
        card.addView(title);
        TextView state = label(measured.state(), 11, false);
        state.setPadding(0, style.dp(5), 0, style.dp(10));
        card.addView(state);
        analysisMetric(
                card,
                Strings.t("Tempo"),
                measured.bpm > 0 ? measured.tempo() : Strings.t("Not available"));
        analysisMetric(
                card,
                Strings.t("Beat confidence"),
                TransitionAnalysis.percent(measured.beatConfidence));
        analysisMetric(
                card,
                Strings.t("First beat"),
                measured.beatGrid()
                        ? String.format(Strings.current(), "%.2f s", measured.firstBeat)
                        : Strings.t("Not available"));
        analysisMetric(
                card, Strings.t("Intro"), analysisInterval(measured.introStart, measured.introEnd));
        analysisMetric(
                card, Strings.t("Outro"), analysisInterval(measured.outroStart, measured.outroEnd));
        analysisMetric(card, Strings.t("Musical key"), measured.keyLabel());
        analysisMetric(
                card,
                Strings.t("Vocal likelihood (estimate)"),
                TransitionAnalysis.percent(measured.vocalProbability));
        TextView energy = label(Strings.t("Relative energy"), 12, true);
        energy.setPadding(0, style.dp(12), 0, style.dp(5));
        card.addView(energy);
        if (measured.energy.length > 1) {
            View graph = new EnergyGraph(measured, deck);
            graph.setTag("automix-analysis-energy:" + deck);
            graph.setContentDescription(
                    Strings.t("Relative energy")
                            + " · "
                            + (deck == 0 ? pair.outgoingTitle : pair.incomingTitle));
            card.addView(graph, new LinearLayout.LayoutParams(-1, style.dp(70)));
            TextView times = label("0:00 — " + clock(measured.duration), 10, false);
            card.addView(times);
        } else card.addView(label(Strings.t("Not available"), 12, false));
    }

    private void analysisMetric(LinearLayout card, String name, String value) {
        LinearLayout line = row();
        line.setGravity(Gravity.TOP);
        line.setPadding(0, style.dp(5), 0, style.dp(5));
        line.addView(label(name, 11, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView text = label(value, 12, true);
        text.setGravity(Gravity.END);
        line.addView(text, new LinearLayout.LayoutParams(0, -2, 1.3f));
        card.addView(line);
    }

    private String analysisInterval(double start, double end) {
        return Double.isFinite(start) && Double.isFinite(end)
                ? clock(start) + " — " + clock(end)
                : Strings.t("Not available");
    }

    private final class EnergyGraph extends View {
        private final TransitionAnalysis.Snapshot measured;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        EnergyGraph(TransitionAnalysis.Snapshot measured, int deck) {
            super(TransitionEditorActivity.this);
            this.measured = measured;
            color = deck == 0 ? YELLOW : CYAN;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            double low = 0, high = 0;
            for (double[] point : measured.energy) {
                low = Math.min(low, point[1]);
                high = Math.max(high, point[1]);
            }
            double range = Math.max(1e-12, high - low);
            Path line = new Path();
            float top = style.dp(5), bottom = getHeight() - style.dp(5);
            for (int i = 0; i < measured.energy.length; i++) {
                double[] point = measured.energy[i];
                float x = (float) (point[0] / measured.duration * getWidth());
                float y = (float) (bottom - (point[1] - low) / range * (bottom - top));
                if (i == 0) line.moveTo(x, y);
                else line.lineTo(x, y);
            }
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(style.dp(2));
            canvas.drawPath(line, paint);
            paint.setColor(style.outline);
            paint.setStrokeWidth(style.dp(1));
            canvas.drawLine(0, bottom, getWidth(), bottom, paint);
        }
    }

    private void update() {
        if (timeline == null) return;
        updateAnalysis();
        for (int deck = 0; deck < 2; deck++)
            if (meta[deck] != null) {
                meta[deck].setText(
                        (bpms[deck] > 0
                                        ? number(bpms[deck] * edit.tempoScale[deck]) + " bpm"
                                        : "— bpm")
                                + "\n"
                                + (keys[deck] == null ? "—" : keys[deck])
                                + "  "
                                + clock(deck == 0 ? pair.outgoingDuration : pair.incomingDuration));
            }
        syncOverlap();
        snap.setSelected(snapping);
        snap.setTextColor(snapping ? GREEN : style.foreground);
        snap.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("beats", snapping ? GREEN : style.foreground, 19), null, null, null);
        snap.setBackground(
                style.shape(
                        snapping ? (style.dark ? 0xff223c2b : 0xffd8eddf) : style.card, 13, false));
        timeline.invalidate();
        if (miniTimeline != null) miniTimeline.invalidate();
        if (miniLength != null)
            miniLength.setText(
                    clock(edit.start(pair.outgoingDuration))
                            + " → "
                            + clock(edit.incomingCue)
                            + "     "
                            + number(edit.overlap)
                            + " s · "
                            + number(AutomixAccess.speed())
                            + "×");
        noticeChange();
        updateSaveState();
        updatePlayer();
        rebuildPresets();
    }

    private void scheduleWaves() {
        handler.removeCallbacks(reloadWaves);
        handler.postDelayed(reloadWaves, 280);
    }

    /** Shows covering peaks at once; decodes the rest once the saved draft is known. */
    private void requestWaves() {
        if (!alive || pair == null || timeline == null) return;
        for (int deck = 0; deck < 2; deck++) {
            double[] range = TransitionWaveCache.window(pair, edit, deck);
            double from = range[0], to = range[1];
            if (waves[deck] != null && waveFrom[deck] <= from && waveTo[deck] >= to) continue;
            TransitionWaveCache.Window cached =
                    TransitionWaveCache.peek(TransitionWaveCache.identity(pair, deck), from, to);
            if (cached != null) {
                stopWave(deck);
                showWave(deck, cached);
                continue;
            }
            if (!storeReady) continue;
            if (waveBusy[deck]) {
                if (jobFrom[deck] <= from + .000001 && jobTo[deck] >= to - .000001) continue;
                // An overlapping decode finishes and is drawn; the latest window follows it.
                if (jobFrom[deck] < to && jobTo[deck] > from) {
                    waveAgain[deck] = true;
                    continue;
                }
                stopWave(deck);
            }
            startWave(deck, from, to);
        }
        timeline.invalidate();
        if (miniTimeline != null) miniTimeline.invalidate();
    }

    private void stopWave(int deck) {
        ++waveGenerations[deck];
        waveBusy[deck] = false;
        waveAgain[deck] = false;
        if (waveJobs[deck] != null) waveJobs[deck].cancel(true);
        waveJobs[deck] = null;
    }

    private void startWave(int deck, double from, double to) {
        int generation = ++waveGenerations[deck];
        waveAgain[deck] = false;
        TransitionRuntime.WaveRequest request = TransitionRuntime.waveform(pair, deck, from, to);
        waveLoading[deck] = request != null;
        if (request == null) return;
        String id = TransitionWaveCache.identity(pair, deck);
        waveBusy[deck] = true;
        jobFrom[deck] = from;
        jobTo[deck] = to;
        // Both decks resolve their sources together; SourceAudio still admits one PCM decode.
        waveJobs[deck] =
                worker().submit(
                                () -> {
                                    TransitionWaveCache.Window result = null;
                                    try {
                                        result =
                                                TransitionWaveCache.load(
                                                        id, from, to, request::read);
                                    } catch (Exception unavailable) {
                                    }
                                    if (Thread.currentThread().isInterrupted()) return;
                                    TransitionWaveCache.Window ready = result;
                                    handler.post(
                                            () -> {
                                                if (!alive || generation != waveGenerations[deck])
                                                    return;
                                                waveBusy[deck] = false;
                                                waveLoading[deck] = false;
                                                if (ready != null) showWave(deck, ready);
                                                // Peaks for an older window are redrawn from
                                                // the cache if the edit has moved since.
                                                if (ready != null || waveAgain[deck])
                                                    requestWaves();
                                                timeline.invalidate();
                                                if (miniTimeline != null) miniTimeline.invalidate();
                                            });
                                });
    }

    private void showWave(int deck, TransitionWaveCache.Window window) {
        waves[deck] = window.bands;
        waveFrom[deck] = window.from;
        waveTo[deck] = window.to;
        waveLoading[deck] = false;
        float peak = 0;
        for (float[] band : window.bands) for (float value : band) peak = Math.max(peak, value);
        waveScale[deck] = 1 / Math.max(.05f, peak);
    }

    private View overlapRow() {
        LinearLayout row = row();
        row.setTag("automix-overlap");
        row.setMinimumHeight(style.dp(48));
        TextView title = label(Strings.t("Overlap"), 12, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.setPadding(style.dp(4), 0, style.dp(4), 0);
        row.addView(title, new LinearLayout.LayoutParams(-2, -2));
        overlapBar = new SeekBar(this);
        overlapBar.setTag("automix-overlap-slider");
        overlapBar.setContentDescription(Strings.t("Overlap"));
        overlapBar.setMinHeight(style.dp(48));
        overlapBar.setPadding(style.dp(12), 0, style.dp(12), 0);
        overlapBar.setKeyProgressIncrement(1);
        overlapBar.setThumbTintList(ColorStateList.valueOf(style.foreground));
        overlapBar.setProgressTintList(ColorStateList.valueOf(GREEN));
        overlapBar.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
        overlapBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {
                        remember();
                        overlapTracking = true;
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        overlapTracking = false;
                        buildControls();
                        syncOverlap();
                    }

                    public void onProgressChanged(SeekBar b, int value, boolean user) {
                        if (!user) return;
                        double seconds = MIN_OVERLAP + value / 10d, beat = beat();
                        // Keyboard and accessibility steps arrive without a touch gesture and
                        // move a whole beat while snapping.
                        if (!overlapTracking) {
                            remember();
                            if (snapping && beat > 0 && Math.abs(seconds - edit.overlap) < beat)
                                seconds = edit.overlap + Math.signum(seconds - edit.overlap) * beat;
                        }
                        setOverlap(seconds, snapping);
                        if (!overlapTracking) buildControls();
                    }
                });
        row.addView(overlapBar, new LinearLayout.LayoutParams(0, style.dp(48), 1));
        overlapValue = chip("", false, this::exactOverlap);
        overlapValue.setTag("automix-overlap-value");
        overlapValue.setMinWidth(style.dp(64));
        overlapValue.setMinHeight(style.dp(48));
        overlapValue.setSingleLine(true);
        overlapValue.setPadding(style.dp(10), 0, style.dp(10), 0);
        overlapValue.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(background(), 24, true),
                        null));
        row.addView(overlapValue, new LinearLayout.LayoutParams(-2, style.dp(48)));
        return row;
    }

    /** Whole beats while beat snapping is on and the tempo is measured; tenths otherwise. */
    private void setOverlap(double seconds, boolean snap) {
        double maximum = pair.maximumOverlap(edit), beat = beat();
        if (snap && beat > 0) {
            double snapped = Math.round(seconds / beat) * beat;
            if (snapped >= MIN_OVERLAP && snapped <= maximum + .001) seconds = snapped;
        }
        stopPreview();
        edit.overlap = Math.max(MIN_OVERLAP, Math.min(maximum, seconds));
        edit.bars = 0;
        for (int bars : BARS)
            if (Math.abs(barSeconds(bars) - edit.overlap) < .000001) edit.bars = bars;
        automatic = false;
        normalize();
        update();
        scheduleWaves();
    }

    private double beat() {
        double bpm = bpms[0] * edit.tempoScale[0];
        return bpm > 0 ? 60 / bpm : 0;
    }

    /** The span of a bar preset at the outgoing grid tempo, or NaN without a measured tempo. */
    private double barSeconds(int bars) {
        try {
            return TransitionEdit.secondsForBars(bars, bpms[0] * edit.tempoScale[0]);
        } catch (IllegalArgumentException missing) {
            return Double.NaN;
        }
    }

    private void setBars(int bars) {
        try {
            double span = TransitionEdit.secondsForBars(bars, bpms[0] * edit.tempoScale[0]);
            if (span > pair.maximumOverlap(edit) + .001) {
                message(Strings.t("Not enough audio"));
                return;
            }
            remember();
            stopPreview();
            edit.overlap = span;
            edit.bars = bars;
            automatic = false;
            update();
            buildControls();
            scheduleWaves();
        } catch (IllegalArgumentException missing) {
            message(Strings.t("Nothing known yet"));
        }
    }

    private void exactOverlap() {
        exact(
                Strings.t("Overlap"),
                MIN_OVERLAP,
                pair.maximumOverlap(edit),
                edit.overlap,
                value -> {
                    remember();
                    setOverlap(value, false);
                    buildControls();
                });
    }

    private void syncOverlap() {
        if (overlapBar == null) return;
        double maximum = pair.maximumOverlap(edit);
        int steps = (int) Math.round((maximum - MIN_OVERLAP) * 10);
        if (overlapBar.getMax() != steps) overlapBar.setMax(steps);
        if (!overlapTracking)
            overlapBar.setProgress((int) Math.round((edit.overlap - MIN_OVERLAP) * 10));
        String seconds = number(edit.overlap) + " s",
                spoken =
                        seconds
                                + (edit.bars > 0
                                        ? " · " + edit.bars + " " + Strings.t("bars")
                                        : "");
        overlapValue.setText(seconds);
        overlapValue.setContentDescription(Strings.t("Overlap") + " · " + spoken);
        if (android.os.Build.VERSION.SDK_INT >= 30) overlapBar.setStateDescription(spoken);
        else overlapBar.setContentDescription(Strings.t("Overlap") + " · " + spoken);
    }

    private void rebuildPresets() {
        presets.removeAllViews();
        String[] names = {
            Strings.t("Custom"),
            Strings.t("Auto"),
            Strings.t("Fade"),
            Strings.t("Rise"),
            Strings.t("Blend"),
            Strings.t("Wave"),
            Strings.t("Melt"),
            Strings.t("Slam")
        };
        for (int i = 0; i < names.length; i++) {
            final int selected = i;
            boolean active =
                    i == 1
                            ? automatic
                            : !automatic && (i == 0 ? edit.preset == -1 : edit.preset == i - 2);
            TextView button =
                    chip(
                            names[i],
                            active,
                            () -> {
                                remember();
                                automatic = selected == 1;
                                if (selected == 0) {
                                    toCustom();
                                    edit.preset = -1;
                                } else if (selected == 1) {
                                    edit = TransitionRuntime.automaticDraft(pair);
                                    normalize();
                                } else edit.preset(selected - 2);
                                update();
                                buildControls();
                                scheduleWaves();
                            });
            button.setTag("transition-preset:" + i);
            button.setBackground(
                    style.shape(
                            active ? style.foreground : (style.dark ? 0xff363636 : style.card),
                            18,
                            false));
            button.setTextColor(active ? background() : style.foreground);
            button.setTextSize(13);
            button.setMinHeight(0);
            button.setPadding(style.dp(5), 0, style.dp(5), 0);
            int available =
                    getResources().getConfiguration().screenWidthDp
                            - (wide ? getResources().getConfiguration().screenWidthDp / 2 : 0)
                            - 32;
            LinearLayout.LayoutParams space =
                    new LinearLayout.LayoutParams(
                            style.dp(Math.max(65, (available - 24) / 4)), style.dp(37));
            space.rightMargin = style.dp(8);
            presets.addView(button, space);
        }
    }

    private void toCustom() {
        if (edit.curve == TransitionEdit.CUSTOM
                && edit.deckCurve[0] == -1
                && edit.deckCurve[1] == -1) return;
        for (int d = 0; d < 2; d++)
            for (int i = 0; i < 4; i++) edit.levels[d][i] = edit.baseGain(d, edit.times[d][i]);
        edit.curve = TransitionEdit.CUSTOM;
        edit.deckCurve[0] = -1;
        edit.deckCurve[1] = -1;
    }

    private void buildControls() {
        if (controls == null) return;
        controls.removeAllViews();
        levels = null;
        tabs.removeAllViews();
        String[] titles = {
            Strings.t("Volume"), Strings.t("EQ"), Strings.t("Effects"), Strings.t("Tools")
        };
        for (int i = 0; i < titles.length; i++) {
            final int t = i;
            TextView button =
                    chip(
                            titles[i],
                            tab == i,
                            () -> {
                                tab = t;
                                buildControls();
                                setCompact(true);
                            });
            button.setTag("transition-tab:" + i);
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setContentDescription(titles[i]);
            button.setTextSize(12);
            button.setMinHeight(0);
            button.setPadding(style.dp(5), 0, style.dp(5), 0);
            button.setTextColor(tab == i ? GREEN : style.foreground);
            button.setCompoundDrawablePadding(style.dp(6));
            button.setCompoundDrawablesWithIntrinsicBounds(
                    new Glyph(
                            new String[] {"volume", "eq", "effects", "tools"}[i],
                            tab == i ? GREEN : style.foreground,
                            19),
                    null,
                    null,
                    null);
            button.setBackground(new TabLine(tab == i));
            tabs.addView(button, new LinearLayout.LayoutParams(0, style.dp(48), 1));
        }
        TextView heading =
                label(
                        Strings.t(
                                new String[] {
                                            "Shape the transition",
                                            "Make room in the mix",
                                            "Give it a little movement",
                                            "Your transition, your sources"
                                        }
                                        [tab]),
                        16,
                        true);
        controls.addView(heading);
        TextView hint =
                label(
                        Strings.t(
                                new String[] {
                                            "Drag a curve point or fine-tune each track below.",
                                            "Independent three-band EQ for each track.",
                                            "Filter sweeps preserve tempo and pitch.",
                                            "Cue, beat grid and transition tools."
                                        }
                                        [tab]),
                        12,
                        false);
        hint.setPadding(0, style.dp(6), 0, style.dp(12));
        controls.addView(hint);
        LinearLayout timing = group(controls);
        if (tab == 0) {
            for (int d = 0; d < 2; d++) {
                final int deck = d;
                slider(
                        timing,
                        (d == 0 ? "A · " : "B · ")
                                + Strings.t(d == 0 ? "Outgoing" : "Incoming")
                                + " · "
                                + Strings.t("Volume"),
                        0,
                        100,
                        edit.volume[d] * 100,
                        "%",
                        value -> {
                            edit.volume[deck] = value / 100;
                            changed();
                        });
            }
            LinearLayout curveRow = row();
            timing.addView(curveRow);
            for (int d = 0; d < 2; d++) {
                final int deck = d;
                LinearLayout cell = column();
                curveRow.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
                int curve = edit.deckCurve[d] < 0 ? edit.curve : edit.deckCurve[d];
                choose(
                        cell,
                        Strings.t(d == 0 ? "Outgoing" : "Incoming") + " · " + Strings.t("Curve"),
                        new String[] {
                            Strings.t("Linear"),
                            Strings.t("Equal power"),
                            Strings.t("Smooth"),
                            Strings.t("Custom"),
                            Strings.t("DJ crossfader")
                        },
                        curve,
                        value -> {
                            remember();
                            edit.deckCurve[deck] = value;
                            changed();
                            buildControls();
                        });
            }
        }
        if (tab == 0) {
            barPresets(timing);
            slider(
                    timing,
                    Strings.t("Outgoing") + " · " + Strings.t("End"),
                    0,
                    Math.min(120, pair.outgoingDuration - .5),
                    edit.outgoingTrim,
                    " s",
                    value -> {
                        edit.outgoingTrim = value;
                        changed();
                    });
            slider(
                    timing,
                    Strings.t("Incoming") + " · " + Strings.t("Start"),
                    0,
                    Math.min(3600, pair.incomingDuration - 1.5),
                    edit.incomingCue,
                    " s",
                    value -> {
                        edit.incomingCue = value;
                        changed();
                    });
        }
        LinearLayout deckSelect = row();
        deckSelect.setPadding(style.dp(2), style.dp(10), style.dp(2), style.dp(12));
        for (int d = 0; d < 2; d++) {
            final int selected = d;
            TextView choice =
                    chip(
                            (d == 0 ? "A · " : "B · ")
                                    + Strings.t(d == 0 ? "Outgoing" : "Incoming"),
                            side == d,
                            () -> {
                                side = selected;
                                buildControls();
                                timeline.invalidate();
                            });
            choice.setBackground(
                    style.shape(
                            side == d ? (style.dark ? 0xff53594e : style.card) : background(),
                            9,
                            false));
            deckSelect.addView(choice, new LinearLayout.LayoutParams(0, style.dp(38), 1));
        }
        timing.addView(deckSelect);
        if (tab == 0) {
            choose(
                    timing,
                    Strings.t("Curve"),
                    new String[] {
                        Strings.t("Linear"),
                        Strings.t("Equal power"),
                        Strings.t("Smooth"),
                        Strings.t("Custom"),
                        Strings.t("DJ crossfader")
                    },
                    edit.curve,
                    value -> {
                        remember();
                        edit.curve = value;
                        edit.deckCurve[0] = -1;
                        edit.deckCurve[1] = -1;
                        changed();
                        buildControls();
                    });
            if (edit.curve == TransitionEdit.DJ)
                slider(
                        timing,
                        Strings.t("Crossfader sharpness"),
                        1,
                        10,
                        edit.faderSharpness,
                        "",
                        value -> {
                            edit.faderSharpness = value;
                            changed();
                        });
            choose(
                    timing,
                    Strings.t("Point"),
                    new String[] {"1", "2", "3", "4"},
                    point,
                    value -> {
                        point = value;
                        buildControls();
                        timeline.invalidate();
                    });
            if (point > 0 && point < 3) {
                double offset = side == 0 ? edit.start(pair.outgoingDuration) : edit.incomingCue;
                slider(
                        timing,
                        Strings.t("Time"),
                        offset
                                + (edit.times[side][point - 1] + TransitionEdit.MIN_GAP)
                                        * edit.overlap,
                        offset
                                + (edit.times[side][point + 1] - TransitionEdit.MIN_GAP)
                                        * edit.overlap,
                        offset + edit.times[side][point] * edit.overlap,
                        " s",
                        value -> {
                            toCustom();
                            edit.times[side][point] = (value - offset) / edit.overlap;
                            changed();
                        });
            }
            slider(
                    timing,
                    Strings.t("Volume"),
                    0,
                    100,
                    edit.baseGain(side, edit.times[side][point]) * 100,
                    "%",
                    value -> {
                        toCustom();
                        edit.levels[side][point] = value / 100;
                        changed();
                    });
        } else if (tab == 1) {
            String[] names = {Strings.t("Low"), Strings.t("Mid"), Strings.t("High")};
            for (int b = 0; b < 3; b++) {
                final int band = b;
                slider(
                        timing,
                        names[b],
                        -12,
                        12,
                        edit.eq[side][b],
                        " dB",
                        value -> {
                            edit.eq[side][band] = value;
                            changed();
                        });
            }
        } else if (tab == 2) {
            choose(
                    timing,
                    Strings.t("Filter"),
                    new String[] {Strings.t("None"), Strings.t("Low-pass"), Strings.t("High-pass")},
                    edit.filter[side],
                    value -> {
                        remember();
                        edit.filter[side] = value;
                        if (value == 2) edit.cutoff[side] = Math.min(edit.cutoff[side], 2000);
                        changed();
                        buildControls();
                    });
            if (edit.filter[side] != 0)
                slider(
                        timing,
                        Strings.t("Cutoff"),
                        20,
                        edit.filter[side] == 2 ? 2000 : 20000,
                        edit.cutoff[side],
                        " Hz",
                        value -> {
                            edit.cutoff[side] = value;
                            changed();
                        });
        } else {
            buildTools(timing);
        }
        TextView rate =
                label(Strings.t("Speed") + " · " + number(AutomixAccess.speed()) + "×", 12, false);
        rate.setPadding(style.dp(6), style.dp(16), style.dp(6), style.dp(20));
        controls.addView(rate);
    }

    private MixTools.Grid grid(int deck) {
        return edit.grid(deck, bpms[deck], firstBeats[deck]);
    }

    private void buildTools(LinearLayout parent) {
        TextView suggest =
                action(
                        Strings.t("Suggest transition"),
                        () -> {
                            try {
                                TransitionEdit suggestion = TransitionRuntime.suggest(pair, edit);
                                remember();
                                edit = suggestion;
                                changed();
                                buildControls();
                            } catch (Exception unavailable) {
                                message(
                                        Strings.t(
                                                "A measured beat grid and enough audio are"
                                                        + " required."));
                            }
                        });
        suggest.setTag("transition-suggest");
        parent.addView(suggest);
        TextView repeat =
                action(
                        Strings.t("Repeat preview")
                                + " · "
                                + Strings.t(repeatPreview ? "On" : "Off"),
                        () -> {
                            repeatPreview = !repeatPreview;
                            buildControls();
                        });
        repeat.setTag("transition-repeat");
        parent.addView(repeat);
        parent.addView(label(Strings.t("Beat jump"), 14, true));
        LinearLayout jumps = row();
        for (int n : new int[] {-4, 4}) {
            TextView button =
                    chip(
                            (n > 0 ? "+" : "−") + "4 " + Strings.t("Beats"),
                            false,
                            () -> {
                                if (!grid(side).valid()) {
                                    message(
                                            Strings.t(
                                                    "A measured beat grid and enough audio are"
                                                            + " required."));
                                    return;
                                }
                                double current =
                                        side == 0
                                                ? pair.outgoingDuration - edit.outgoingTrim
                                                : edit.incomingCue;
                                setCue(grid(side).jump(current, n));
                            });
            button.setTag("transition-jump:" + n);
            jumps.addView(button, new LinearLayout.LayoutParams(0, style.dp(48), 1));
        }
        parent.addView(jumps);
        slider(
                parent,
                Strings.t("Beat grid offset"),
                -8,
                8,
                edit.gridOffset[side],
                " s",
                value -> {
                    edit.gridOffset[side] = value;
                    changed();
                });
        LinearLayout nudges = row();
        for (int n : new int[] {-1, 1}) {
            TextView button =
                    chip(
                            (n > 0 ? "+" : "−") + "5 ms",
                            false,
                            () -> {
                                remember();
                                edit.nudgeGrid(side, n * .005);
                                changed();
                                buildControls();
                            });
            button.setTag("transition-grid:" + n);
            nudges.addView(button, new LinearLayout.LayoutParams(0, style.dp(48), 1));
        }
        parent.addView(nudges);
        LinearLayout beats = row();
        for (int n : new int[] {-1, 1}) {
            TextView button =
                    chip(
                            (n > 0 ? "+" : "−") + "1 ♩",
                            false,
                            () -> {
                                if (!grid(side).valid()) {
                                    message(
                                            Strings.t(
                                                    "A measured beat grid and enough audio are"
                                                            + " required."));
                                    return;
                                }
                                remember();
                                edit.nudgeGrid(side, n * grid(side).interval);
                                changed();
                                buildControls();
                            });
            button.setTag("transition-grid-beat:" + n);
            button.setContentDescription(
                    Strings.t("Beat grid offset")
                            + " · "
                            + (n > 0 ? "+1" : "−1")
                            + " "
                            + Strings.t("Beats"));
            beats.addView(button, new LinearLayout.LayoutParams(0, style.dp(48), 1));
        }
        parent.addView(beats);
        choose(
                parent,
                Strings.t("Beat grid tempo"),
                new String[] {"½", "1×", "2×"},
                edit.tempoScale[side] == .5 ? 0 : edit.tempoScale[side] == 2 ? 2 : 1,
                value -> {
                    remember();
                    edit.tempoScale[side] = new double[] {.5, 1, 2}[value];
                    edit.bars = 0;
                    changed();
                    buildControls();
                });
        parent.addView(label(Strings.t("Cue bookmarks"), 14, true));
        LinearLayout bookmarks = row();
        for (int i = 0; i < 4; i++) {
            final int cue = i;
            double stored = edit.cues[side][i];
            TextView button =
                    chip(
                            stored < 0 ? "+ " + (i + 1) : clock(stored),
                            stored >= 0,
                            () -> {
                                if (edit.cues[side][cue] < 0) storeCue(cue);
                                else setCue(edit.cues[side][cue]);
                            });
            button.setContentDescription(Strings.t("Cue bookmarks") + " " + (i + 1));
            button.setTag("transition-cue:" + i);
            button.setOnLongClickListener(
                    view -> {
                        new AlertDialog.Builder(this)
                                .setItems(
                                        new String[] {Strings.t("Save"), Strings.t("Remove")},
                                        (dialog, which) -> {
                                            if (which == 0) storeCue(cue);
                                            else {
                                                remember();
                                                edit.cues[side][cue] = -1;
                                                changed();
                                                buildControls();
                                            }
                                        })
                                .show();
                        return true;
                    });
            bookmarks.addView(button, new LinearLayout.LayoutParams(0, style.dp(52), 1));
        }
        parent.addView(bookmarks);
        TextView hint =
                label(
                        Strings.t(
                                "Hold a cue to replace or remove it. Grid edits do not change"
                                        + " playback speed."),
                        12,
                        false);
        hint.setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(12));
        parent.addView(hint);
        parent.addView(label(Strings.t("Deck levels before faders"), 14, true));
        levels = new LevelView();
        levels.setTag("transition-levels");
        parent.addView(levels, new LinearLayout.LayoutParams(-1, style.dp(72)));
    }

    private void setCue(double value) {
        double max =
                side == 0
                        ? pair.outgoingDuration
                        : Math.min(3600, pair.incomingDuration - edit.overlap * 3);
        double min = side == 0 ? Math.max(edit.overlap, pair.outgoingDuration - 120) : 0;
        if (!Double.isFinite(value) || value < min || value > max) {
            message(Strings.t("Not enough audio"));
            return;
        }
        remember();
        if (side == 0) edit.outgoingTrim = pair.outgoingDuration - value;
        else edit.incomingCue = value;
        changed();
        buildControls();
    }

    private void storeCue(int point) {
        remember();
        edit.cues[side][point] =
                side == 0 ? pair.outgoingDuration - edit.outgoingTrim : edit.incomingCue;
        changed();
        buildControls();
    }

    private final class LevelView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LevelView() {
            super(TransitionEditorActivity.this);
            setContentDescription(Strings.t("Deck levels before faders"));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setTextSize(style.dp(11));
            for (int d = 0; d < 2; d++) {
                float[] meter = TransitionRuntime.meter(d);
                float top = style.dp(8 + d * 32),
                        left = style.dp(26),
                        width = getWidth() - left - style.dp(8);
                paint.setColor(style.secondary);
                canvas.drawText(d == 0 ? "A" : "B", style.dp(5), top + style.dp(15), paint);
                for (int c = 0; c < 2; c++) {
                    float y = top + style.dp(c * 9);
                    paint.setColor(style.dark ? 0xff454545 : 0xffdddddd);
                    canvas.drawRect(left, y, left + width, y + style.dp(6), paint);
                    if (meter == null) continue;
                    paint.setColor(meter[4] == 1 ? 0xffff5544 : GREEN);
                    canvas.drawRect(
                            left,
                            y,
                            left + width * (meter[c + 2] + 60) / 60,
                            y + style.dp(6),
                            paint);
                    float peak = left + width * (meter[c] + 60) / 60;
                    canvas.drawRect(peak, y, peak + style.dp(1), y + style.dp(6), paint);
                }
            }
        }
    }

    interface Value {
        void set(double value);
    }

    interface Choice {
        void set(int value);
    }

    private void slider(
            LinearLayout parent,
            String title,
            double min,
            double max,
            double initial,
            String unit,
            Value change) {
        max = Math.max(min, max);
        final double upper = max;
        final double[] current = {initial};
        LinearLayout group = column();
        group.setPadding(style.dp(4), style.dp(6), style.dp(4), style.dp(6));
        TextView caption = label(title + " · " + number(initial) + unit, 13, false);
        caption.setMinHeight(style.dp(48));
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setClickable(true);
        caption.setFocusable(true);
        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setMinHeight(style.dp(48));
        bar.setPadding(style.dp(8), 0, style.dp(8), 0);
        bar.setThumbTintList(ColorStateList.valueOf(style.foreground));
        bar.setProgressTintList(
                ColorStateList.valueOf(tab == 0 ? (side == 0 ? YELLOW : CYAN) : GREEN));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
        bar.setContentDescription(title);
        Value describe =
                value -> {
                    String spoken = number(value) + unit;
                    if (android.os.Build.VERSION.SDK_INT >= 30) bar.setStateDescription(spoken);
                    else bar.setContentDescription(title + " · " + spoken);
                };
        describe.set(initial);
        bar.setProgress(
                upper == min ? 0 : (int) Math.round((initial - min) * 1000 / (upper - min)));
        group.addView(caption);
        group.addView(bar, new LinearLayout.LayoutParams(-1, style.dp(48)));
        parent.addView(group);
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {
                        remember();
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        buildControls();
                    }

                    public void onProgressChanged(SeekBar b, int value, boolean user) {
                        if (user) {
                            double actual = min + (upper - min) * value / 1000;
                            current[0] = actual;
                            change.set(actual);
                            caption.setText(title + " · " + number(actual) + unit);
                            describe.set(actual);
                        }
                    }
                });
        caption.setOnClickListener(
                v ->
                        exact(
                                title,
                                min,
                                upper,
                                current[0],
                                value -> {
                                    remember();
                                    change.set(value);
                                    buildControls();
                                }));
    }

    /** Typed entry for an exact value within the control's supported range. */
    private void exact(String title, double min, double max, double current, Value change) {
        PlaylistModal modal = new PlaylistModal(this, title, "");
        EditText value = modal.input(title);
        value.setInputType(
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        value.setText(number(current));
        value.setSelectAllOnFocus(true);
        modal.action(
                Strings.t("Save"),
                true,
                () -> {
                    try {
                        String text = value.getText().toString().trim();
                        java.text.ParsePosition at = new java.text.ParsePosition(0);
                        Number parsed = NumberFormat.getNumberInstance().parse(text, at);
                        if (parsed == null || at.getIndex() != text.length())
                            throw new IllegalArgumentException();
                        double actual = parsed.doubleValue();
                        if (!Double.isFinite(actual) || actual < min || actual > max)
                            throw new IllegalArgumentException();
                        modal.dismiss();
                        change.set(actual);
                    } catch (Exception invalid) {
                        value.setError(number(min) + " – " + number(max));
                    }
                });
        modal.action(Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    private void barPresets(LinearLayout parent) {
        double maximum = pair.maximumOverlap(edit);
        TextView title = label(Strings.t("Overlap") + " · " + Strings.t("bars"), 13, false);
        title.setMinHeight(style.dp(36));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(style.dp(4), style.dp(6), style.dp(4), 0);
        parent.addView(title);
        LinearLayout row = row();
        for (int bars : BARS) {
            boolean selected = edit.bars == bars;
            boolean fits = barSeconds(bars) <= maximum + .001;
            TextView button = chip(bars + " " + Strings.t("bars"), selected, () -> setBars(bars));
            button.setTag("transition-bars:" + bars);
            button.setTextColor(selected ? GREEN : style.foreground);
            // Unavailable lengths still explain why when tapped.
            button.setAlpha(fits || selected ? 1 : .5f);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, style.dp(48), 1);
            space.setMargins(style.dp(2), 0, style.dp(2), 0);
            row.addView(button, space);
        }
        parent.addView(row);
    }

    private void choose(
            LinearLayout parent, String title, String[] values, int selected, Choice choice) {
        TextView action =
                action(
                        title + " · " + values[selected],
                        () -> {
                            AlertDialog dialog =
                                    new AlertDialog.Builder(this)
                                            .setTitle(title)
                                            .setSingleChoiceItems(
                                                    values,
                                                    selected,
                                                    (d, index) -> {
                                                        choice.set(index);
                                                        d.dismiss();
                                                        buildControls();
                                                    })
                                            .setNegativeButton(Strings.t("Cancel"), null)
                                            .create();
                            dialog.setOnShowListener(d -> style.tuneDialog(dialog));
                            dialog.show();
                        });
        action.setBackground(null);
        action.setTextSize(15);
        action.setTypeface(style.medium);
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(8));
        action.setMinHeight(style.dp(48));
        parent.addView(action);
    }

    private void preview() {
        if (!storeReady || saving) return;
        if (audition != null) {
            stopPreview();
            return;
        }
        try {
            normalize();
            if (autoApply) applyPending = true;
            final int generation = ++previewGeneration;
            listening = TransitionPreview.acquire(pair);
            listening.attach(this);
            audition =
                    listening.start(
                            edit,
                            () -> {
                                boolean again =
                                        audition != null
                                                && audition.completed
                                                && repeatPreview
                                                && generation == previewGeneration
                                                && alive;
                                boolean failed = audition != null && audition.error != null;
                                audition = null;
                                handler.removeCallbacks(animate);
                                if (!isFinishing() && !isDestroyed()) {
                                    preview.setEnabled(true);
                                    miniPreview.setEnabled(true);
                                    previewIcon(false);
                                    preview.setContentDescription(Strings.t("Preview"));
                                    timeline.invalidate();
                                    message(
                                            Strings.t(
                                                    failed
                                                            ? "Could not preview this transition."
                                                            : "Current song paused"));
                                    updatePlayer();
                                    if (autoApply && applyPending && !failed)
                                        handler.post(applyLater);
                                    else if (again)
                                        handler.postDelayed(
                                                () -> {
                                                    if (alive
                                                            && generation == previewGeneration
                                                            && audition == null) preview();
                                                },
                                                250);
                                }
                            });
            previewIcon(true);
            updatePlayer();
            preview.setContentDescription(Strings.t("Stop"));
            message(Strings.t("Loading preview…"));
            handler.post(animate);
        } catch (Exception | LinkageError unavailable) {
            HostLog.d(
                    "AutoMix preview setup failed: "
                            + TransitionPreview.failureDetail(unavailable));
            message(
                    AutomixAccess.enabled()
                            ? Strings.t("Could not preview this transition.")
                            : Strings.t("Enable Automix in Playback settings first."));
        }
    }

    private void previewIcon(boolean playing) {
        preview.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph(playing ? "stop" : "play", style.foreground, 27), null, null, null);
        miniPreview.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph(playing ? "stop" : "play", style.foreground, 27), null, null, null);
        miniPreview.setContentDescription(Strings.t(playing ? "Stop" : "Preview"));
    }

    private void stopPreview() {
        previewGeneration++;
        if (audition != null) {
            if (preview != null) preview.setEnabled(false);
            audition.stop();
        }
    }

    private void noticeChange() {
        String value = state();
        if (storeReady && observedState != null && !value.equals(observedState)) {
            dirtyDraft = !value.equals(initial);
            applyPending = autoApply && !value.equals(appliedState);
            handler.removeCallbacks(saveDraftLater);
            handler.removeCallbacks(applyLater);
            if (autoSave && dirtyDraft) handler.postDelayed(saveDraftLater, 450);
            if (applyPending) handler.postDelayed(applyLater, 750);
            message("");
        }
        observedState = value;
    }

    private String defaultStatus() {
        if (listening != null && !listening.closed)
            return Strings.t("Current song paused")
                    + "\n"
                    + Strings.t("Preview again, or Apply to resume.");
        return Strings.t(
                        state().equals(appliedState)
                                ? "Applied to this track pair"
                                : "Ready to preview")
                + "\n"
                + Strings.t("Draft is separate from playback");
    }

    private void updateSaveState() {
        if (saveState == null) return;
        saveState.setText(
                Strings.t(
                        dirtyDraft
                                ? (autoSave ? "Saving draft…" : "Unsaved draft")
                                : "Draft saved"));
        saveState.setTextColor(dirtyDraft ? YELLOW : style.secondary);
        draftSave.setVisibility(autoSave ? View.GONE : View.VISIBLE);
        save.setText(Strings.t(autoApply ? "Apply now" : "Apply"));
    }

    private void persistDraft(boolean notify) {
        handler.removeCallbacks(saveDraftLater);
        if (!storeReady || edit == null) return;
        String snapshot = state();
        TransitionEdit draft = edit.copy();
        boolean auto = automatic;
        TransitionStore.get()
                .writeDraft(
                        pair,
                        draft,
                        auto,
                        error -> {
                            if (isDestroyed()) return;
                            if (error != null) {
                                message(Strings.t("Could not save this transition."));
                                return;
                            }
                            initial = snapshot;
                            dirtyDraft = !state().equals(snapshot);
                            updateSaveState();
                            if (notify)
                                Toast.makeText(this, Strings.t("Draft saved"), Toast.LENGTH_SHORT)
                                        .show();
                        });
    }

    private void save() {
        apply(true);
    }

    private void apply(boolean notify) {
        if (edit == null || !storeReady || saving) return;
        if (!notify && audition != null) return;
        try {
            normalize();
            TransitionRuntime.validateSave(pair, automatic ? null : edit);
            String snapshot = state();
            TransitionEdit draft = edit.copy();
            boolean auto = automatic;
            handler.removeCallbacks(saveDraftLater);
            handler.removeCallbacks(applyLater);
            applyPending = false;
            saving = true;
            stopPreview();
            save.setEnabled(false);
            preview.setEnabled(false);
            miniPreview.setEnabled(false);
            message(Strings.t("Saving…"));
            TransitionPreview.Session applying = listening;
            TransitionStore.get()
                    .commit(
                            pair,
                            draft,
                            auto,
                            error -> {
                                saving = false;
                                if (error == null && applying != null) applying.close(true);
                                if (isDestroyed()) {
                                    if (error != null) closeListening();
                                    return;
                                }
                                save.setEnabled(true);
                                preview.setEnabled(true);
                                miniPreview.setEnabled(true);
                                if (error != null) {
                                    message(Strings.t("Could not save this transition."));
                                    return;
                                }
                                initial = snapshot;
                                appliedState = snapshot;
                                dirtyDraft = !state().equals(snapshot);
                                if (listening == applying) listening = null;
                                updateSaveState();
                                updatePlayer();
                                message("");
                                if (notify)
                                    Toast.makeText(
                                                    this,
                                                    Strings.t("Applied and saved"),
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                if (dirtyDraft && autoSave)
                                    handler.postDelayed(saveDraftLater, 450);
                                if (dirtyDraft && autoApply) {
                                    applyPending = true;
                                    handler.postDelayed(applyLater, 750);
                                }
                            });
        } catch (Exception unavailable) {
            message(Strings.t("Could not save this transition."));
        }
    }

    private void close() {
        if (saving) return;
        handler.removeCallbacks(applyLater);
        handler.removeCallbacks(saveDraftLater);
        stopPreview();
        handler.removeCallbacks(applyLater);
        if (edit == null || !storeReady || state().equals(initial)) {
            closeListening();
            finish();
            return;
        }
        if (autoSave) {
            saving = true;
            String snapshot = state();
            TransitionStore.get()
                    .writeDraft(
                            pair,
                            edit.copy(),
                            automatic,
                            error -> {
                                saving = false;
                                if (isDestroyed()) return;
                                if (error == null) {
                                    initial = snapshot;
                                    closeListening();
                                    finish();
                                } else {
                                    message(Strings.t("Could not save this transition."));
                                    discardDialog();
                                }
                            });
        } else discardDialog();
    }

    private void discardDialog() {
        new AlertDialog.Builder(this)
                .setTitle(Strings.t("Discard changes?"))
                .setPositiveButton(
                        Strings.t("Discard"),
                        (dialog, which) -> {
                            closeListening();
                            finish();
                        })
                .setNegativeButton(Strings.t("Cancel"), null)
                .show();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void menu() {
        PopupMenu menu = new PopupMenu(this, menuAnchor);
        menu.getMenu().add(0, 1, 0, Strings.t("Undo")).setEnabled(!undo.isEmpty());
        menu.getMenu().add(0, 2, 1, Strings.t("Redo")).setEnabled(!redo.isEmpty());
        menu.getMenu().add(0, 3, 2, Strings.t("Automatic"));
        menu.getMenu().add(0, 4, 3, Strings.t("Refresh waveforms"));
        menu.setOnMenuItemClickListener(
                item -> {
                    stopPreview();
                    if (item.getItemId() == 1) {
                        redo.addLast(state());
                        restore(undo.removeLast());
                    }
                    if (item.getItemId() == 2) {
                        undo.addLast(state());
                        restore(redo.removeLast());
                    }
                    if (item.getItemId() == 3) {
                        remember();
                        edit = TransitionRuntime.automaticDraft(pair);
                        automatic = true;
                        normalize();
                        update();
                        buildControls();
                        scheduleWaves();
                    }
                    if (item.getItemId() == 4) {
                        waves[0] = null;
                        waves[1] = null;
                        TransitionWaveCache.clearMemory();
                        readAnalysis();
                        update();
                        requestWaves();
                    }
                    return true;
                });
        menu.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (edit != null) {
            out.putString("pair", pair.key);
            out.putString("draft", edit.encode());
            out.putString("initial", initial);
            out.putInt("side", side);
            out.putInt("tab", tab);
            out.putBoolean("snap", snapping);
            out.putBoolean("repeat", repeatPreview);
            out.putBoolean("automatic", automatic);
            out.putBoolean("storeReady", storeReady);
            out.putBoolean("compact", compact);
            out.putBoolean("dirtyDraft", dirtyDraft);
            out.putBoolean("applyPending", applyPending);
        }
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        alive = true;
        if (timeline != null) scheduleWaves();
        handler.post(analysisTick);
    }

    @Override
    protected void onStop() {
        alive = false;
        if (analysisDialog != null) analysisDialog.dismiss();
        stopPreview();
        handler.removeCallbacks(animate);
        handler.removeCallbacks(reloadWaves);
        handler.removeCallbacks(analysisTick);
        stopWave(0);
        stopWave(1);
        if (worker != null) worker.shutdownNow();
        handler.removeCallbacks(saveDraftLater);
        handler.removeCallbacks(applyLater);
        if (autoSave && dirtyDraft && !saving) persistDraft(false);
        if (isFinishing()) closeListening();
        super.onStop();
    }

    private void closeListening() {
        if (listening != null && listening.ownedBy(this)) listening.close(true);
        listening = null;
    }

    @Override
    protected void onDestroy() {
        if (!isChangingConfigurations() && !saving) closeListening();
        super.onDestroy();
    }

    private static String number(double value) {
        NumberFormat n = NumberFormat.getNumberInstance();
        n.setMaximumFractionDigits(3);
        return n.format(value);
    }

    private static String clock(double time) {
        int seconds = (int) Math.max(0, time);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private LinearLayout group(LinearLayout parent) {
        LinearLayout group = column();
        group.setPadding(style.dp(8), style.dp(4), style.dp(8), style.dp(4));
        group.setBackgroundColor(Color.TRANSPARENT);
        parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    private TextView label(String text, int size, boolean bold) {
        return style.text(
                text,
                size,
                bold ? style.foreground : style.secondary,
                bold ? style.semibold : style.regular);
    }

    private TextView chip(String text, boolean selected, Runnable run) {
        TextView button = style.text(text, 12, style.foreground, style.medium);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(style.dp(44));
        button.setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(6));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(view -> run.run());
        button.setSelected(selected);
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(
                                selected ? (style.dark ? 0xff414141 : style.card) : background(),
                                12,
                                false),
                        null));
        return button;
    }

    private TextView action(String title, Runnable run) {
        return chip(title, false, run);
    }

    private final class Timeline extends View {
        final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path curve = new Path();
        int dragging = -1, deck;
        float downX, downY;
        double baseCue, baseTrim;
        boolean panning, editingGesture;

        final boolean mini;

        Timeline(boolean mini) {
            super(TransitionEditorActivity.this);
            this.mini = mini;
            setContentDescription(Strings.t("Automix Editor") + " · " + Strings.t("Volume"));
            setFocusable(true);
        }

        float left() {
            return getWidth() * .15f;
        }

        float right() {
            return getWidth() * .85f;
        }

        float x(double time) {
            return left() + (right() - left()) * (float) time;
        }

        float deckTop(int deck) {
            return style.dp(mini ? 2 : 8) + deck * bandHeight();
        }

        float bandHeight() {
            return (getHeight() - style.dp(mini ? 8 : 14)) / 2f;
        }

        float y(int deck, double level) {
            return deckTop(deck) + bandHeight() * (.9f - .8f * (float) level);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float bottom = getHeight() - style.dp(mini ? 2 : 4);
            ink.setColor(style.dark ? 0xff292929 : 0xffeeeeee);
            canvas.drawRoundRect(
                    left(), style.dp(8), right(), bottom, style.dp(12), style.dp(12), ink);
            int clip = canvas.save();
            canvas.clipRect(0, style.dp(8), getWidth(), bottom);
            ink.setTypeface(style.regular);
            ink.setTextSize(style.dp(11));
            for (int d = 0; d < 2; d++) {
                double start = d == 0 ? edit.start(pair.outgoingDuration) : edit.incomingCue;
                MixTools.Grid grid = grid(d);
                if (grid.valid()) {
                    long first = (long) Math.floor(grid.at(start - edit.overlap * .22));
                    ink.setStrokeWidth(style.dp(.6f));
                    for (long i = first; i < first + 600; i++) {
                        double t = grid.time(i);
                        float at = x((t - start) / edit.overlap);
                        if (at > getWidth()) break;
                        if (at < 0) continue;
                        ink.setColor(
                                style.dark
                                        ? (i % 4 == 0 ? 0x226e6e6e : 0x126e6e6e)
                                        : (i % 4 == 0 ? 0x30000000 : 0x14000000));
                        canvas.drawLine(at, deckTop(d), at, deckTop(d) + bandHeight(), ink);
                    }
                }
                drawWave(canvas, d, start);
                int color = d == 0 ? YELLOW : CYAN;
                ink.setColor(color);
                ink.setAlpha(30);
                Path fill = new Path();
                fill.moveTo(x(0), y(d, 0));
                for (int i = 0; i <= 120; i++)
                    fill.lineTo(x(i / 120d), y(d, edit.gain(d, i / 120d)));
                fill.lineTo(x(1), y(d, 0));
                fill.close();
                canvas.drawPath(fill, ink);
                ink.setAlpha(255);
                ink.setStrokeWidth(style.dp(1.5f));
                ink.setStyle(Paint.Style.STROKE);
                curve.reset();
                for (int i = 0; i <= 120; i++) {
                    float at = x(i / 120d), level = y(d, edit.gain(d, i / 120d));
                    if (i == 0) curve.moveTo(at, level);
                    else curve.lineTo(at, level);
                }
                canvas.drawPath(curve, ink);
                ink.setStyle(Paint.Style.FILL);
                if (!automatic)
                    for (int i = 0; i < 4; i++) {
                        ink.setColor(color);
                        canvas.drawCircle(
                                x(edit.times[d][i]),
                                y(d, edit.gain(d, edit.times[d][i])),
                                style.dp(d == side && i == point ? 5 : 3),
                                ink);
                    }
                ink.setColor(style.secondary);
                ink.setAlpha(210);
                if (!mini)
                    canvas.drawText(
                            clock(start), left(), deckTop(d) + bandHeight() - style.dp(2), ink);
                String end = clock(start + edit.overlap);
                if (!mini)
                    canvas.drawText(
                            end,
                            right() - ink.measureText(end),
                            deckTop(d) + bandHeight() - style.dp(2),
                            ink);
                ink.setAlpha(255);
            }
            ink.setColor(YELLOW);
            ink.setStrokeWidth(style.dp(1));
            canvas.drawLine(left(), style.dp(8), left(), bottom, ink);
            canvas.drawLine(right(), style.dp(8), right(), bottom, ink);
            if (audition != null) {
                double progress = audition.progress();
                if (Double.isFinite(progress)) {
                    ink.setColor(GREEN);
                    ink.setStrokeWidth(style.dp(2));
                    canvas.drawLine(x(progress), style.dp(8), x(progress), bottom, ink);
                }
            }
            canvas.restoreToCount(clip);
        }

        private void drawWave(Canvas canvas, int deck, double start) {
            float centre = deckTop(deck) + bandHeight() * .5f;
            float[][] data = waves[deck];
            if (data == null) {
                ink.setColor(style.secondary);
                ink.setStrokeWidth(style.dp(1));
                canvas.drawLine(0, centre, getWidth(), centre, ink);
                ink.setTextSize(style.dp(mini ? 8 : 10));
                ink.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(
                        (waveLoading[deck]
                                ? Strings.t("Loading…")
                                : Strings.t("Waveform unavailable")),
                        getWidth() / 2f,
                        centre + style.dp(18),
                        ink);
                ink.setTextAlign(Paint.Align.LEFT);
                ink.setTextSize(style.dp(11));
                return;
            }
            int bins = data[0].length;
            float step =
                    (float)
                            ((waveTo[deck] - waveFrom[deck])
                                    / edit.overlap
                                    * (right() - left())
                                    / bins);
            ink.setStrokeWidth(Math.max(1, step));
            int[] colors = {0xff474fe3, 0xffd78743, 0xffffe1b2};
            for (int b = 0; b < 3; b++)
                for (int i = 0; i < bins; i++) {
                    double time =
                            waveFrom[deck] + (i + .5) * (waveTo[deck] - waveFrom[deck]) / bins;
                    float at = x((time - start) / edit.overlap);
                    if (at < -step || at > getWidth() + step) continue;
                    float height = Math.min(1, data[b][i] * waveScale[deck]) * bandHeight() * .37f;
                    ink.setColor(colors[b]);
                    if ((deck == 0 && at > right()) || (deck == 1 && at < left())) ink.setAlpha(70);
                    canvas.drawLine(at, centre - height, at, centre + height, ink);
                }
            ink.setAlpha(255);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (saving) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                panning = false;
                editingGesture = false;
                dragging = -1;
                deck = event.getY() < deckTop(1) ? 0 : 1;
                baseCue = edit.incomingCue;
                baseTrim = edit.outgoingTrim;
                float best = style.dp(24);
                for (int d = 0; d < 2; d++)
                    for (int i = 0; i < 4; i++) {
                        float distance =
                                (float)
                                        Math.hypot(
                                                event.getX() - x(edit.times[d][i]),
                                                event.getY()
                                                        - y(d, edit.gain(d, edit.times[d][i])));
                        if (distance < best) {
                            best = distance;
                            dragging = i;
                            deck = d;
                        }
                    }
                side = deck;
                if (dragging >= 0) {
                    point = dragging;
                }
                getParent().requestDisallowInterceptTouchEvent(dragging >= 0);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
                if (dragging < 0 && !panning && dy > style.dp(6) && dy > dx) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return false;
                }
                if (!editingGesture && Math.max(dx, dy) <= style.dp(5)) return true;
                if (!editingGesture) {
                    remember();
                    editingGesture = true;
                }
                if (dragging >= 0) {
                    toCustom();
                    double at = (event.getX() - left()) / (right() - left());
                    if (snapping && bpms[deck] > 0) {
                        double offset =
                                deck == 0 ? edit.start(pair.outgoingDuration) : edit.incomingCue;
                        at = (grid(deck).snap(offset + at * edit.overlap) - offset) / edit.overlap;
                    }
                    if (dragging > 0 && dragging < 3)
                        edit.times[deck][dragging] =
                                Math.max(
                                        edit.times[deck][dragging - 1] + TransitionEdit.MIN_GAP,
                                        Math.min(
                                                edit.times[deck][dragging + 1]
                                                        - TransitionEdit.MIN_GAP,
                                                at));
                    edit.levels[deck][dragging] =
                            Math.max(
                                    0,
                                    Math.min(
                                            1,
                                            (y(deck, 0) - event.getY())
                                                    / (.8
                                                            * bandHeight()
                                                            * Math.max(.0001, edit.volume[deck]))));
                    changed();
                } else if (Math.abs(event.getX() - downX) > style.dp(5)) {
                    panning = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    double delta = (downX - event.getX()) / (right() - left()) * edit.overlap;
                    if (deck == 0) {
                        double start = pair.outgoingDuration - baseTrim - edit.overlap + delta;
                        if (snapping) start = grid(0).snap(start);
                        edit.outgoingTrim =
                                Math.max(
                                        0,
                                        Math.min(
                                                Math.min(120, pair.outgoingDuration - edit.overlap),
                                                pair.outgoingDuration - edit.overlap - start));
                    } else {
                        double cue = baseCue + delta;
                        if (snapping) cue = grid(1).snap(cue);
                        edit.incomingCue =
                                Math.max(
                                        0, Math.min(pair.incomingDuration - edit.overlap * 3, cue));
                    }
                    changed();
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                boolean click =
                        event.getActionMasked() == MotionEvent.ACTION_UP
                                && !editingGesture
                                && !panning;
                dragging = -1;
                panning = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                buildControls();
                if (click) performClick();
                return true;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
