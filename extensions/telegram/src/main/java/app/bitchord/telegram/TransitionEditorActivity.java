package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.*;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Player-launched, two-track transition editor. Waveforms always describe real decoded audio. */
public final class TransitionEditorActivity extends Activity {
    private static final int YELLOW = 0xffe6c86c, CYAN = 0xff80b8dd, GREEN = 0xff6fca8c;
    private static final double MIN_OVERLAP = .5;
    private static final int[] BARS = {2, 4, 8, 16};
    private static final int COMPACT_HEIGHT = 190;
    private TextView expandButton;
    private int wideNeed;

    /** The pair the queue held when the editor opened; the preview pair may differ from it. */
    private TransitionRuntime.Pair queuePair;

    private TransitionPairSelection selection;
    private int pairGeneration, bootGeneration;
    private TransitionPreview.Session settling;
    private boolean draftStored,
            resetPending,
            lastDetached,
            checking,
            liveWait,
            lastChecking,
            identityFailed;
    private DraftWrite draftWrite = new DraftWrite();

    private static final class DraftWrite {
        boolean busy;

        /** A chosen or reversed pair waiting for the shown pair's draft; carried by rotation. */
        TransitionRuntime.Pair pending;
    }

    private AlertDialog pendingDialog;

    /** Dialogs that edit the shown pair; the pair is not replaced under them. */
    private final Set<android.app.Dialog> editingDialogs = new HashSet<>();

    /** True while a held control or an open editing dialog belongs to the shown pair. */
    private boolean editingBusy() {
        editingDialogs.removeIf(dialog -> !dialog.isShowing());
        return !adjustments.isEmpty() || !editingDialogs.isEmpty();
    }

    private long liveCheckedAt = -501;
    private String liveKey;
    private final TextView[] titleViews = new TextView[2],
            artistViews = new TextView[2],
            compactTitles = new TextView[2];
    private final ImageView[] covers = new ImageView[2];

    /** What a rotation keeps: the chosen files, the queue baseline and a pending queue rebase. */
    private static final class Retained {
        final TransitionPairSelection selection;
        final TransitionRuntime.Pair queue, selected;
        final TransitionPreview.Session settling;
        final boolean resetPending;
        final DraftWrite draftWrite;

        Retained(
                TransitionPairSelection selection,
                TransitionRuntime.Pair queue,
                TransitionRuntime.Pair selected,
                TransitionPreview.Session settling,
                boolean resetPending,
                DraftWrite draftWrite) {
            this.selection = selection;
            this.queue = queue;
            this.selected = selected;
            this.settling = settling;
            this.resetPending = resetPending;
            this.draftWrite = draftWrite;
        }
    }

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
            applyState,
            saveHandle,
            applyHandle;
    private final TextView[] rate = new TextView[2], miniRate = new TextView[2];
    private View overlapRow;
    private boolean saveFailed, applyFailed;
    private final Set<View> adjustments = new HashSet<>();
    private boolean compact, autoSave, autoApply, dirtyDraft, applyPending;
    private boolean advancedCurve;
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
                        && adjustments.isEmpty()
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
    private boolean auditionBefore, nativePending;
    private final String[] badges = new String[2], reasons = new String[2];
    private final String[] shownKeys = new String[2];
    private final double[] shownBpm = new double[2];
    private final TextView[] keyBadges = new TextView[2];
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
    private final boolean[] waveFailed = new boolean[2];
    private final double[] failFrom = new double[2], failTo = new double[2];
    private final Map<ImageView, Future<?>> coverJobs = new HashMap<>();
    private boolean followRequested;
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
                    if (now >= nextAnalysisRequest && storeReady) {
                        SourceAutomix.requestEditor(pair, false);
                        nextAnalysisRequest = now + 15000;
                    }
                    updatePlayer();
                    if (settling != null) settleUse();
                    liveCheckedAt = -501;
                    boolean away = detached();
                    if (away != lastDetached || checking != lastChecking) refreshLive();
                    resolvePending();
                    followQueue(followRequested);
                    updateAnalysis();
                    adoptNativePlan();
                    syncPending();
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
                                                    : "Loading preview…")
                                    + (auditionBefore ? " · " + Strings.t("Before") : ""));
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
                        // Larger text wraps the track facts; the graph still needs a full target.
                        int need = 0;
                        if (getResources().getConfiguration().fontScale > 1f
                                && expanded != null
                                && overlapRow != null
                                && expanded.getChildCount() >= 4)
                            need =
                                    expanded.getChildAt(0).getMeasuredHeight()
                                            + overlapRow.getMeasuredHeight()
                                            + expanded.getChildAt(3).getMeasuredHeight()
                                            + style.dp(48);
                        if (available < style.dp(260) || need > available) setCompact(true);
                        else {
                            ViewGroup.LayoutParams space = workspace.getLayoutParams();
                            int desired =
                                    Math.max(need, Math.min(style.dp(expandedHeight()), available));
                            if (space.height == desired) return;
                            space.height = desired;
                            workspace.setLayoutParams(space);
                        }
                        super.onMeasure(width, height);
                    }
                };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag("transition-editor-root");
        root.setPadding(style.dp(16), 0, style.dp(16), style.dp(8));
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
                            style.dp(16) + left, top, style.dp(16) + right, style.dp(8) + bottom);
                    return insets;
                });
        root.requestApplyInsets();
        LinearLayout header = row();
        TextView close = chip("×", false, this::close);
        close.setTag("transition-close");
        close.setContentDescription(Strings.t("Close"));
        close.setText("");
        close.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("close", style.foreground, 22), null, null, null);
        close.setPadding(style.dp(13), 0, 0, 0);
        close.setBackground(style.shape(style.dark ? 0xff30312f : style.card, 24, false));
        LinearLayout.LayoutParams closeSpace =
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48));
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
                new Glyph("beats", GREEN, 22), null, null, null);
        snap.setPadding(style.dp(14), 0, style.dp(16), 0);
        snap.setTag("transition-snap");
        snap.setContentDescription(Strings.t("Snap to beat grid"));
        header.addView(snap);
        TextView menu = chip("···", false, this::menu);
        menu.setContentDescription(Strings.t("Settings"));
        menu.setTag("transition-menu");
        menu.setText("");
        menu.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("more", style.foreground, 22), null, null, null);
        menu.setPadding(style.dp(13), 0, 0, 0);
        menu.setBackground(style.shape(style.dark ? 0xff30312f : style.card, 24, false));
        LinearLayout.LayoutParams menuSpace =
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48));
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

        bind(saved, root, menu, 0);
    }

    /**
     * Binds the editor to the queue's pair. A local file whose fingerprint has not been read yet
     * leaves the page loading; its completion repeats this step once for each missing file.
     */
    private void bind(Bundle saved, LinearLayout root, TextView menu, int attempt) {
        @SuppressWarnings("deprecation")
        Retained kept = (Retained) getLastNonConfigurationInstance();
        SourceAutomix.IdentityPending waiting = null;
        try {
            TransitionRuntime.Pair live = TransitionRuntime.pair();
            queuePair = kept != null ? kept.queue : live;
            pair = kept != null ? kept.selected : live;
        } catch (Exception unavailable) {
            pair = null;
            waiting = pendingIdentity(unavailable);
        }
        if (waiting != null && attempt < 4) {
            awaitIdentity(waiting, saved, root, menu, attempt);
            return;
        }
        if (pair == null || pair.outgoingDuration <= 0 || pair.incomingDuration <= 0) {
            message(
                    waiting != null
                            ? Strings.t("Could not read this file")
                            : pair == null
                                    ? Strings.t("Add two tracks to the queue first.")
                                    : Strings.t(
                                            "Track durations are not available yet. Start playback"
                                                    + " or choose another source."));
            if (status.getParent() == null) root.addView(status);
            snap.setVisibility(View.GONE);
            menu.setVisibility(View.GONE);
            setContentView(root);
            return;
        }
        if (status.getParent() == root) root.removeView(status);
        snap.setVisibility(View.VISIBLE);
        menu.setVisibility(View.VISIBLE);
        if (kept != null) {
            selection = kept.selection;
            selection.reattach(this::pairChosen, this::pairFailed);
            settling = kept.settling;
            resetPending = kept.resetPending;
            draftWrite = kept.draftWrite;
        } else
            selection = new TransitionPairSelection(queuePair, this::pairChosen, this::pairFailed);
        android.content.SharedPreferences options =
                getSharedPreferences("bitchord_automix_editor", MODE_PRIVATE);
        autoSave = options.getBoolean("auto_save", true);
        autoApply = options.getBoolean("auto_apply", false);
        TransitionEdit found = TransitionRuntime.load(pair);
        automatic = found == null || found.automaticTiming;
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
            advancedCurve = saved.getBoolean("advancedCurve", false);
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
        listening = settling != null ? settling : TransitionPreview.existing(queuePair);
        if (listening != null) listening.attach(this);
        save.setEnabled(false);
        preview.setEnabled(false);
        message(Strings.t("Loading…"));
        requestWaves();
        final int started = pairGeneration;
        TransitionStore.get()
                .read(
                        pair,
                        result -> {
                            if (isDestroyed() || isFinishing() || started != pairGeneration) return;
                            TransitionStore.get()
                                    .readDraft(
                                            pair,
                                            draft -> {
                                                if (isDestroyed()
                                                        || isFinishing()
                                                        || started != pairGeneration) return;
                                                storeReady = true;
                                                SourceAutomix.requestEditor(pair, false);
                                                nextAnalysisRequest =
                                                        android.os.SystemClock.elapsedRealtime()
                                                                + 15000;
                                                draftStored =
                                                        draft.edit != null && draft.error == null;
                                                if (resetPending
                                                        && settling == null
                                                        && !draftWrite.busy
                                                        && draftStored) {
                                                    selection.reset(pair);
                                                    resetPending = false;
                                                }
                                                TransitionEdit stored =
                                                        result.error == null ? result.edit() : null;
                                                appliedState =
                                                        stored == null
                                                                ? state(
                                                                        TransitionRuntime
                                                                                .automaticDraft(
                                                                                        pair),
                                                                        true)
                                                                : state(
                                                                        stored,
                                                                        stored.automaticTiming);
                                                if (!preserveDraft && state().equals(initial)) {
                                                    automatic =
                                                            draft.edit != null
                                                                            && draft.error == null
                                                                    ? draft.automatic
                                                                    : stored == null
                                                                            || stored.automaticTiming;
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
                                                    observedState = initial;
                                                    update();
                                                    buildControls();
                                                    scheduleWaves();
                                                }
                                                updateSaveState();
                                                enablePreview(true);
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
                                                syncPending();
                                            });
                        });
    }

    private static SourceAutomix.IdentityPending pendingIdentity(Throwable failure) {
        for (int depth = 0; failure != null && depth < 5; depth++, failure = failure.getCause())
            if (failure instanceof SourceAutomix.IdentityPending)
                return (SourceAutomix.IdentityPending) failure;
        return null;
    }

    private void awaitIdentity(
            SourceAutomix.IdentityPending waiting,
            Bundle saved,
            LinearLayout root,
            TextView menu,
            int attempt) {
        final int generation = ++bootGeneration;
        message(Strings.t("Loading…"));
        if (status.getParent() == null) root.addView(status);
        snap.setVisibility(View.GONE);
        menu.setVisibility(View.GONE);
        setContentView(root);
        SourceAutomix.prepareIdentity(waiting.uri)
                .whenComplete(
                        (fingerprint, failure) ->
                                handler.post(
                                        () -> {
                                            if (isDestroyed()
                                                    || isFinishing()
                                                    || generation != bootGeneration
                                                    || pair != null) return;
                                            if (failure != null) {
                                                message(Strings.t("Could not read this file"));
                                                return;
                                            }
                                            bind(saved, root, menu, attempt + 1);
                                        }));
    }

    private void buildLayout(LinearLayout root) {
        workspace = column();
        workspace.setTag("automix-workspace");
        expanded = column();
        expanded.setTag("automix-expanded");
        expanded.addView(track(0));
        FrameLayout graph = new FrameLayout(this);
        graph.setMinimumHeight(style.dp(48));
        timeline = new Timeline(false);
        timeline.setTag("transition-timeline");
        graph.addView(timeline, new FrameLayout.LayoutParams(-1, -1));
        for (int d = 0; d < 2; d++) rate[d] = rateButton(d, "automix-rate:" + d, graph);
        preview = previewButton();
        preview.setTag("transition-preview");
        graph.addView(
                preview, new FrameLayout.LayoutParams(style.dp(48), style.dp(48), Gravity.CENTER));
        expanded.addView(graph, new LinearLayout.LayoutParams(-1, 0, 1));
        overlapRow = overlapRow();
        expanded.addView(overlapRow, new LinearLayout.LayoutParams(-1, -2));
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
            compactTitles[d] = title;
            names.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView expand = expandButton = chip("", false, this::expandWaveforms);
        expand.setTag("automix-expand");
        expand.setContentDescription(Strings.t("Expand waveforms"));
        expand.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("expand", style.foreground, 22), null, null, null);
        expand.setPadding(style.dp(13), 0, 0, 0);
        names.addView(expand, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        compactRow.addView(names);
        FrameLayout miniGraph = new FrameLayout(this);
        miniGraph.setMinimumHeight(style.dp(48));
        miniTimeline = new Timeline(true);
        miniTimeline.setTag("automix-mini-timeline");
        miniTimeline.setContentDescription(Strings.t("Expand waveforms"));
        miniTimeline.setOnClickListener(v -> expandWaveforms());
        miniGraph.addView(miniTimeline, new FrameLayout.LayoutParams(-1, -1));
        for (int d = 0; d < 2; d++)
            miniRate[d] = rateButton(d, "automix-mini-rate:" + d, miniGraph);
        miniPreview = previewButton();
        miniPreview.setTag("automix-mini-preview");
        miniGraph.addView(
                miniPreview,
                new FrameLayout.LayoutParams(style.dp(48), style.dp(48), Gravity.CENTER));
        compactRow.addView(miniGraph, new LinearLayout.LayoutParams(-1, 0, 1));
        miniLength = label("", 11, false);
        miniLength.setSingleLine(true);
        miniLength.setEllipsize(TextUtils.TruncateAt.END);
        miniLength.setVisibility(wide ? View.GONE : View.VISIBLE);
        compactRow.addView(miniLength);
        workspace.addView(compactRow, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout right = column();
        preferenceHeader = column();
        right.addView(preferenceHeader);
        TextView presetLabel = label(Strings.t("Presets"), 11, false);
        presetLabel.setPadding(style.dp(4), style.dp(8), 0, style.dp(4));
        preferenceHeader.addView(presetLabel);
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presets = row();
        presetScroll.addView(presets);
        preferenceHeader.addView(presetScroll, new LinearLayout.LayoutParams(-1, -2));
        tabs = row();
        right.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
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
        View dockView = buildDock();
        if (wide) {
            LinearLayout panes = row();
            panes.setGravity(Gravity.TOP);
            LinearLayout leftPane =
                    new LinearLayout(this) {
                        @Override
                        protected void onMeasure(int width, int height) {
                            super.onMeasure(width, height);
                            if (fitWide(this, dockView)) super.onMeasure(width, height);
                        }
                    };
            leftPane.setOrientation(LinearLayout.VERTICAL);
            leftPane.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
            leftPane.addView(dockView, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 1);
            left.rightMargin = style.dp(14);
            panes.addView(leftPane, left);
            panes.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(workspace, new LinearLayout.LayoutParams(-1, style.dp(expandedHeight())));
            root.addView(dockView, new LinearLayout.LayoutParams(-1, -2));
            root.addView(right, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout footer = column();
        footer.setPadding(style.dp(3), style.dp(4), style.dp(3), 0);
        status.setTextSize(11);
        status.setPadding(style.dp(4), 0, style.dp(4), style.dp(2));
        footer.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout player = row();
        playerPlay = chip("", false, this::togglePlayer);
        playerPlay.setTag("automix-player-play");
        playerPlay.setPadding(style.dp(13), 0, 0, 0);
        player.addView(playerPlay, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        playerText = label("", 11, false);
        playerText.setSingleLine(true);
        playerText.setEllipsize(TextUtils.TruncateAt.END);
        playerText.setPadding(style.dp(8), 0, style.dp(8), 0);
        player.addView(playerText, new LinearLayout.LayoutParams(0, -2, 1));
        playerTime = label("", 11, false);
        player.addView(playerTime);
        footer.addView(player);
        root.addView(footer);
        updateSaveState();
    }

    /** A 48dp deck multiplier in the waveform margin; its sheet carries every tempo operation. */
    private TextView rateButton(int deck, String tag, FrameLayout host) {
        TextView button = chip("", false, () -> rateSheet(deck));
        button.setTag(tag);
        button.setTextSize(12);
        button.setMinWidth(style.dp(48));
        button.setSingleLine(true);
        button.setTextColor(deck == 0 ? YELLOW : CYAN);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(style.dark ? 0xff30312f : style.card, 24, true),
                        null));
        button.setAccessibilityDelegate(rateDelegate(deck));
        button.setOnKeyListener(
                (v, code, event) -> {
                    boolean stepping =
                            code == KeyEvent.KEYCODE_DPAD_UP
                                    || code == KeyEvent.KEYCODE_PLUS
                                    || code == KeyEvent.KEYCODE_EQUALS
                                    || code == KeyEvent.KEYCODE_DPAD_DOWN
                                    || code == KeyEvent.KEYCODE_MINUS;
                    if (!stepping) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        endAdjustment(v);
                        return true;
                    }
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    beginAdjustment(v);
                    if (code == KeyEvent.KEYCODE_DPAD_UP
                            || code == KeyEvent.KEYCODE_PLUS
                            || code == KeyEvent.KEYCODE_EQUALS) stepRate(deck, 1);
                    else if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MINUS)
                        stepRate(deck, -1);
                    else return false;
                    return true;
                });
        button.setOnFocusChangeListener(
                (v, focused) -> {
                    if (!focused) endAdjustment(v);
                });
        FrameLayout.LayoutParams space =
                new FrameLayout.LayoutParams(
                        -2, style.dp(48), (deck == 0 ? Gravity.END : Gravity.START) | Gravity.TOP);
        host.addView(button, space);
        host.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, orr, ob) -> {
                    int top =
                            Math.max(
                                    0,
                                    Math.min(
                                            (b - t) - style.dp(48),
                                            Math.round((b - t) * (deck == 0 ? .27f : .68f))
                                                    - style.dp(24)));
                    if (space.topMargin != top) {
                        space.topMargin = top;
                        button.setLayoutParams(space);
                    }
                });
        return button;
    }

    private View.AccessibilityDelegate rateDelegate(int deck) {
        return new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(SeekBar.class.getName());
                if (edit == null) return;
                info.setRangeInfo(
                        android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                                android.view.accessibility.AccessibilityNodeInfo.RangeInfo
                                        .RANGE_TYPE_FLOAT,
                                .5f,
                                2f,
                                (float) edit.overlapRate[deck]));
                info.addAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                .ACTION_SCROLL_FORWARD);
                info.addAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                .ACTION_SCROLL_BACKWARD);
                info.addAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                .ACTION_SET_PROGRESS);
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    info.setStateDescription(rateSpoken(deck));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                        forward =
                                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                        .ACTION_SCROLL_FORWARD,
                        backward =
                                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                        .ACTION_SCROLL_BACKWARD,
                        progress =
                                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                        .ACTION_SET_PROGRESS;
                if (action == forward.getId()) stepRate(deck, 1);
                else if (action == backward.getId()) stepRate(deck, -1);
                else if (action == progress.getId() && arguments != null)
                    setRate(
                            deck,
                            Math.round(
                                            arguments.getFloat(
                                                            android.view.accessibility
                                                                    .AccessibilityNodeInfo
                                                                    .ACTION_ARGUMENT_PROGRESS_VALUE)
                                                    * 100)
                                    / 100d);
                else return super.performAccessibilityAction(host, action, arguments);
                return true;
            }
        };
    }

    private String rateText(double rate) {
        return String.format(java.util.Locale.ROOT, "%.2f×", rate);
    }

    private String rateOffset(double rate) {
        double percent = (rate - 1) * 100;
        return Math.abs(percent) < .05
                ? "0%"
                : (percent < 0 ? "−" : "+")
                        + number(Math.round(Math.abs(percent) * 10) / 10d)
                        + "%";
    }

    private String rateSpoken(int deck) {
        double rate = edit.overlapRate[deck], bpm = edit.overlapBpm(deck, bpms[deck]);
        return number(rate)
                + " "
                + Strings.t("times")
                + " · "
                + rateOffset(rate)
                + (bpm > 0 ? " · " + number(Math.round(bpm * 10) / 10d) + " bpm" : "")
                + " · "
                + Strings.t("overlap only");
    }

    private void syncRate() {
        for (int d = 0; d < 2; d++) {
            String name = (d == 0 ? "A · " : "B · ") + Strings.t("Overlap tempo");
            for (TextView button : new TextView[] {rate[d], miniRate[d]}) {
                if (button == null) continue;
                button.setText(rateText(edit.overlapRate[d]));
                button.setContentDescription(name + " · " + rateSpoken(d));
            }
        }
    }

    private void stepRate(int deck, int direction) {
        setRate(
                deck,
                Math.max(
                        .5,
                        Math.min(
                                2,
                                Math.round((edit.overlapRate[deck] + direction * .01) * 100)
                                        / 100d)));
    }

    private boolean setRate(int deck, double value) {
        if (!Double.isFinite(value) || value < .5 || value > 2) {
            message(Strings.t("Use a multiplier from 0.5× to 2×"));
            return false;
        }
        if (Math.abs(edit.overlapRate[deck] - value) < 1e-9) return true;
        remember();
        edit.overlapRate[deck] = value;
        edit.tempoExplicit = true;
        edit.bars = 0;
        changed();
        if (tab == 3) buildControls();
        return true;
    }

    private void rateSheet(int deck) {
        PlaylistModal modal =
                new PlaylistModal(
                        this,
                        (deck == 0 ? "A · " : "B · ")
                                + Strings.t(deck == 0 ? "Outgoing" : "Incoming"),
                        Strings.t("Overlap tempo"));
        EditText value = modal.input(Strings.t("Multiplier"));
        modal.body.removeView(value);
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        value.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        value.setGravity(Gravity.CENTER);
        value.setTag("automix-rate-value");
        TextView readout = label("", 12, false);
        readout.setGravity(Gravity.CENTER);
        readout.setPadding(style.dp(16), 0, style.dp(16), style.dp(10));
        Runnable[] refresh = {null};
        TextView down = chip("", false, () -> {});
        TextView up = chip("", false, () -> {});
        down.setTag("automix-rate-down");
        up.setTag("automix-rate-up");
        for (TextView step : new TextView[] {down, up}) {
            boolean faster = step == up;
            step.setCompoundDrawablesWithIntrinsicBounds(
                    new Glyph(faster ? "plus" : "minus", style.foreground, 22), null, null, null);
            step.setPadding(style.dp(13), 0, 0, 0);
            step.setContentDescription(Strings.t(faster ? "Faster by 1%" : "Slower by 1%"));
            step.setOnClickListener(
                    v -> {
                        stepRate(deck, faster ? 1 : -1);
                        refresh[0].run();
                    });
        }
        value.setOnEditorActionListener(
                (v, action, event) -> {
                    try {
                        java.text.ParsePosition at = new java.text.ParsePosition(0);
                        String text = value.getText().toString().trim();
                        Number parsed = NumberFormat.getNumberInstance().parse(text, at);
                        if (parsed == null || at.getIndex() != text.length())
                            throw new IllegalArgumentException();
                        if (!setRate(deck, parsed.doubleValue()))
                            throw new IllegalArgumentException();
                        value.setError(null);
                    } catch (Exception invalid) {
                        value.setError("0.5 – 2");
                    }
                    refresh[0].run();
                    return true;
                });
        LinearLayout line = row();
        line.setPadding(style.dp(16), 0, style.dp(16), style.dp(8));
        line.addView(down, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        line.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(up, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        modal.body.addView(line);
        modal.body.addView(readout);
        TextView reset =
                modalAction(
                        modal,
                        Strings.t("Original tempo"),
                        false,
                        () -> {
                            if (setRate(deck, 1)) refresh[0].run();
                        });
        reset.setTag("automix-rate-reset");
        TextView match =
                modalAction(
                        modal,
                        Strings.t(deck == 0 ? "Match A to B" : "Match B to A"),
                        false,
                        () -> {
                            if (!edit.canMatchTempo(deck, bpms[0], bpms[1])) return;
                            remember();
                            edit.matchTempo(deck, bpms[0], bpms[1]);
                            edit.bars = 0;
                            changed();
                            refresh[0].run();
                        });
        match.setTag("automix-rate-match");
        modalAction(modal, Strings.t("Done"), true, modal::dismiss).setTag("automix-rate-done");
        refresh[0] =
                () -> {
                    if (!value.hasFocus()) value.setText(number(edit.overlapRate[deck]));
                    readout.setText(rateSpoken(deck));
                    boolean can = edit.canMatchTempo(deck, bpms[0], bpms[1]);
                    match.setEnabled(can);
                    match.setAlpha(can ? 1 : .4f);
                    reset.setEnabled(Math.abs(edit.overlapRate[deck] - 1) > 1e-9);
                    reset.setAlpha(reset.isEnabled() ? 1 : .4f);
                    down.setEnabled(edit.overlapRate[deck] > .5);
                    up.setEnabled(edit.overlapRate[deck] < 2);
                };
        refresh[0].run();
        // Large text, a short window or the keyboard can outgrow the sheet, so it scrolls.
        ScrollView sheet = new ScrollView(this);
        sheet.setTag("automix-rate-scroll");
        modal.dialog.setContentView(sheet);
        sheet.addView(modal.body);
        modal.show();
    }

    /** Beside the controls, or at large text, the button says less; its spoken label stays full. */
    private boolean conciseAnalysis() {
        return wide || getResources().getConfiguration().fontScale > 1.15f;
    }

    private boolean stackedDock() {
        android.content.res.Configuration config = getResources().getConfiguration();
        return !wide && (config.fontScale > 1.15f || config.screenWidthDp < 360);
    }

    /** Draft, analysis and apply stay pinned under the waveforms and above the presets. */
    private View buildDock() {
        LinearLayout box = column();
        box.setTag("automix-dock");
        box.setPadding(0, style.dp(4), 0, style.dp(2));
        saveState = label("", 11, false);
        saveState.setTag("automix-save-state");
        saveState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        applyState = label("", 11, false);
        applyState.setTag("automix-apply-state");
        applyState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        draftSave = chip("", false, () -> persistDraft(true));
        draftSave.setTag("automix-save-draft");
        saveHandle = modeHandle(true);
        View saveColumn = dockColumn(draftSave, saveHandle, saveState, true);
        applyHandle = modeHandle(false);
        View applyColumn = dockColumn(save, applyHandle, applyState, false);
        analysisSummary = chip("", false, this::showAnalysis);
        analysisSummary.setTag("automix-analysis");
        analysisSummary.setTextSize(11);
        analysisSummary.setSingleLine(true);
        analysisSummary.setEllipsize(TextUtils.TruncateAt.END);
        analysisSummary.setPadding(style.dp(8), style.dp(4), style.dp(8), style.dp(4));
        analysisSummary.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(style.dark ? 0xff30312f : style.card, 24, false),
                        null));
        LinearLayout line =
                new LinearLayout(this) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        int available =
                                MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight();
                        int gap = style.dp(getChildCount() == 3 ? 12 : 8);
                        int side =
                                getChildCount() == 3
                                        ? Math.max(
                                                0,
                                                wide
                                                        ? (available - gap - analysisWidth()) / 2
                                                        : (int) ((available - gap) * .35f))
                                        : Math.max(0, (available - gap) / 2);
                        for (View column : new View[] {saveColumn, applyColumn}) {
                            LinearLayout.LayoutParams p =
                                    (LinearLayout.LayoutParams) column.getLayoutParams();
                            p.width = side;
                            p.weight = 0;
                            p.setMarginEnd(getChildCount() == 2 && column == saveColumn ? gap : 0);
                        }
                        if (getChildCount() == 3) {
                            LinearLayout.LayoutParams p =
                                    (LinearLayout.LayoutParams) analysisSummary.getLayoutParams();
                            p.width = Math.max(0, available - 2 * side - gap);
                            p.weight = 0;
                        }
                        super.onMeasure(width, height);
                        for (TextView action : new TextView[] {draftSave, save}) {
                            int actionWidth = Math.max(0, side - style.dp(48));
                            action.measure(
                                    MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY),
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        }
                        int tallest =
                                Math.max(
                                        style.dp(48),
                                        Math.max(
                                                draftSave.getMeasuredHeight(),
                                                save.getMeasuredHeight()));
                        if (analysisSummary.getParent() == this)
                            tallest = Math.max(tallest, analysisSummary.getMeasuredHeight());
                        boolean resize = false;
                        for (View column : new View[] {saveColumn, applyColumn}) {
                            View capsule = ((ViewGroup) column).getChildAt(0);
                            if (capsule.getLayoutParams().height != tallest) {
                                capsule.getLayoutParams().height = tallest;
                                resize = true;
                            }
                        }
                        for (TextView action : new TextView[] {draftSave, save}) {
                            if (action.getLayoutParams().height != tallest) {
                                action.getLayoutParams().height = tallest;
                                resize = true;
                            }
                        }
                        if (analysisSummary.getParent() == this
                                && analysisSummary.getLayoutParams().height != tallest) {
                            analysisSummary.getLayoutParams().height = tallest;
                            resize = true;
                        }
                        if (resize) super.onMeasure(width, height);
                    }
                };
        line.setGravity(Gravity.TOP);
        if (stackedDock()) {
            line.addView(saveColumn, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(applyColumn, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(line);
            LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, -2);
            full.topMargin = style.dp(4);
            box.addView(analysisSummary, full);
        } else {
            line.addView(saveColumn, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, -2, 1.3f);
            middle.leftMargin = style.dp(6);
            middle.rightMargin = style.dp(6);
            line.addView(analysisSummary, middle);
            line.addView(applyColumn, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(line);
        }
        return box;
    }

    /** The concise label beside the controls needs its own text width, whatever the font size. */
    private int analysisWidth() {
        return (int)
                        Math.ceil(
                                analysisSummary
                                        .getPaint()
                                        .measureText(analysisSummary.getText().toString()))
                + analysisSummary.getPaddingLeft()
                + analysisSummary.getPaddingRight()
                + style.dp(4);
    }

    private View dockColumn(TextView action, TextView handle, TextView state, boolean saving) {
        LinearLayout column = column();
        FrameLayout capsule = new FrameLayout(this);
        capsule.setTag(saving ? "automix-save-capsule" : "automix-apply-capsule");
        capsule.setMinimumHeight(style.dp(48));
        action.setTextSize(13);
        action.setMaxLines(2);
        action.setPadding(style.dp(4), style.dp(6), style.dp(4), style.dp(6));
        action.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(Color.TRANSPARENT, 24, false),
                        null));
        capsule.addView(action, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        capsule.addView(
                handle,
                new FrameLayout.LayoutParams(style.dp(48), style.dp(48), Gravity.CENTER_VERTICAL));
        column.addView(capsule, new LinearLayout.LayoutParams(-1, -2));
        state.setPadding(style.dp(4), style.dp(2), style.dp(4), 0);
        column.addView(state, new LinearLayout.LayoutParams(-1, -2));
        return column;
    }

    private TextView modeHandle(boolean saving) {
        TextView handle = chip("", false, () -> modeDialog(saving));
        handle.setTag(saving ? "automix-auto-save" : "automix-auto-apply");
        handle.setPadding(style.dp(13), 0, 0, 0);
        float[] origin = new float[2];
        boolean[] gesture = {false, false};
        handle.setOnTouchListener(
                (v, event) -> {
                    ViewGroup capsule = (ViewGroup) v.getParent();
                    boolean on = saving ? autoSave : autoApply;
                    float travel = Math.max(1, capsule.getWidth() - v.getWidth());
                    float sign = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            beginAdjustment(v);
                            origin[0] = event.getRawX();
                            origin[1] = event.getRawY();
                            gesture[0] = gesture[1] = false;
                            return true;
                        case MotionEvent.ACTION_POINTER_DOWN:
                            gesture[1] = true;
                            v.setTranslationX(0);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (gesture[1]) return true;
                            float dx = event.getRawX() - origin[0],
                                    dy = event.getRawY() - origin[1];
                            if (Math.abs(dy) > style.dp(22) && Math.abs(dy) > Math.abs(dx)) {
                                gesture[1] = true;
                                v.setTranslationX(0);
                                return true;
                            }
                            if (Math.abs(dx) > style.dp(7)) gesture[0] = true;
                            if (gesture[0]) {
                                capsule.requestDisallowInterceptTouchEvent(true);
                                float at =
                                        Math.max(0, Math.min(1, (on ? 1 : 0) + sign * dx / travel));
                                v.setTranslationX(sign * (at - (on ? 1 : 0)) * travel);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            endAdjustment(v);
                            v.setTranslationX(0);
                            if (gesture[1]) return true;
                            if (!gesture[0]) {
                                v.performClick();
                                return true;
                            }
                            float end =
                                    Math.max(
                                            0,
                                            Math.min(
                                                    1,
                                                    (on ? 1 : 0)
                                                            + sign
                                                                    * (event.getRawX() - origin[0])
                                                                    / travel));
                            if (!on && end >= .75f) setMode(saving, true);
                            else if (on && end <= .25f) setMode(saving, false);
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            endAdjustment(v);
                            v.setTranslationX(0);
                            return true;
                        default:
                            return true;
                    }
                });
        handle.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                        info.addAction(
                                new android.view.accessibility.AccessibilityNodeInfo
                                        .AccessibilityAction(
                                        MODE_MANUAL, Strings.t("Use manual mode")));
                        info.addAction(
                                new android.view.accessibility.AccessibilityNodeInfo
                                        .AccessibilityAction(
                                        MODE_AUTOMATIC, Strings.t("Use automatic mode")));
                    }

                    @Override
                    public boolean performAccessibilityAction(
                            View host, int action, Bundle arguments) {
                        if (action == MODE_MANUAL || action == MODE_AUTOMATIC) {
                            setMode(saving, action == MODE_AUTOMATIC);
                            return true;
                        }
                        return super.performAccessibilityAction(host, action, arguments);
                    }
                });
        return handle;
    }

    private static final int MODE_MANUAL = 0x00be0001, MODE_AUTOMATIC = 0x00be0002;

    private void setMode(boolean saving, boolean automaticMode) {
        if (saving) {
            autoSave = automaticMode;
            getSharedPreferences("bitchord_automix_editor", 0)
                    .edit()
                    .putBoolean("auto_save", automaticMode)
                    .apply();
            handler.removeCallbacks(saveDraftLater);
            if (automaticMode && dirtyDraft) persistDraft(false);
        } else {
            autoApply = automaticMode;
            getSharedPreferences("bitchord_automix_editor", 0)
                    .edit()
                    .putBoolean("auto_apply", automaticMode)
                    .apply();
            handler.removeCallbacks(applyLater);
            applyPending = automaticMode && !state().equals(appliedState);
            if (applyPending) handler.postDelayed(applyLater, 750);
        }
        updateSaveState();
    }

    private void modeDialog(boolean saving) {
        boolean on = saving ? autoSave : autoApply;
        PlaylistModal modal =
                new PlaylistModal(
                        this,
                        Strings.t(saving ? "Draft saving" : "Apply settings"),
                        Strings.t(
                                saving
                                        ? "Manual saves when you tap Save draft."
                                        : "Manual applies when you tap Apply."));
        TextView manual =
                modalAction(
                        modal,
                        Strings.t("Manual"),
                        !on,
                        () -> {
                            modal.dismiss();
                            setMode(saving, false);
                        });
        manual.setTag("automix-mode-manual");
        manual.setSelected(!on);
        TextView auto =
                modalAction(
                        modal,
                        Strings.t("Automatic"),
                        on,
                        () -> {
                            modal.dismiss();
                            setMode(saving, true);
                        });
        auto.setTag("automix-mode-automatic");
        auto.setSelected(on);
        modalAction(modal, Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    private int expandedHeight() {
        return Math.max(
                260,
                Math.min(487, (int) (getResources().getConfiguration().screenHeightDp * .51) - 48));
    }

    private void setCompact(boolean value) {
        if (workspace == null || compact == value) return;
        compact = value;
        compactLayoutPending = true;
        expanded.setVisibility(value ? View.GONE : View.VISIBLE);
        compactRow.setVisibility(value ? View.VISIBLE : View.GONE);
        // Presets stay in the side pane while the waveforms are condensed.
        if (!wide) preferenceHeader.setVisibility(value ? View.GONE : View.VISIBLE);
        // One overlap slider serves both layouts, so its state is never duplicated.
        ((ViewGroup) overlapRow.getParent()).removeView(overlapRow);
        (value ? compactRow : expanded)
                .addView(overlapRow, 2, new LinearLayout.LayoutParams(-1, -2));
        if (wide) return;
        ViewGroup.LayoutParams layout = workspace.getLayoutParams();
        layout.height = style.dp(value ? COMPACT_HEIGHT : expandedHeight());
        workspace.setLayoutParams(layout);
    }

    /**
     * Beside the controls the workspace keeps its weight. It condenses only when the measured track
     * rows, the shared overlap row and a 48dp graph no longer fit. True when something changed.
     */
    private boolean fitWide(LinearLayout pane, View dock) {
        if (!wide || workspace == null || expanded == null || workspace.getParent() != pane)
            return false;
        int room = pane.getMeasuredHeight() - dock.getMeasuredHeight();
        boolean was = compact;
        if (!compact) {
            wideNeed =
                    expanded.getChildAt(0).getMeasuredHeight()
                            + expanded.getChildAt(expanded.getChildCount() - 1).getMeasuredHeight()
                            + overlapRow.getMeasuredHeight()
                            + style.dp(48);
            if (room < wideNeed) setCompact(true);
        } else if (wideNeed > 0 && room >= wideNeed) setCompact(false);
        // A button that cannot restore the full view would only mislead.
        boolean hide = compact && wideNeed > 0 && room < wideNeed;
        int visibility = hide ? View.GONE : View.VISIBLE;
        boolean flipped = expandButton.getVisibility() != visibility;
        if (flipped) expandButton.setVisibility(visibility);
        return was != compact || flipped;
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
        text.setMinHeight(style.dp(48));
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setOnClickListener(v -> toggle.toggle());
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private TextView previewButton() {
        TextView button = chip("", false, this::preview);
        button.setContentDescription(Strings.t("Preview"));
        button.setPadding(style.dp(13), 0, 0, 0);
        button.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("play", style.foreground, 22), null, null, null);
        button.setBackground(style.shape(style.dark ? 0xff4c514d : 0xffcccccc, 24, true));
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
                    new Glyph(playing ? "pause" : "play", style.foreground, 22), null, null, null);
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

    /**
     * True when the previewed pair is provably not the one the queue holds now. While a local
     * file's fingerprint is being read again the answer is unknown, not detached; see checking.
     */
    private boolean detached() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - liveCheckedAt > 500) {
            liveCheckedAt = now;
            try {
                TransitionRuntime.Pair live = TransitionRuntime.pair();
                liveKey = live == null ? null : live.key + ":" + live.recording();
                checking = false;
            } catch (Exception | LinkageError unavailable) {
                liveKey = null;
                SourceAutomix.IdentityPending waiting = pendingIdentity(unavailable);
                checking = waiting != null;
                if (waiting != null) awaitLive(waiting);
            }
        }
        if (pair == null) return true;
        if (checking) return false;
        try {
            return liveKey == null || !liveKey.equals(pair.key + ":" + pair.recording());
        } catch (SourceAutomix.IdentityPending waiting) {
            liveKey = null;
            checking = true;
            awaitLive(waiting);
            return false;
        }
    }

    /** Reads the file's fingerprint off the main thread, then re-checks the actual queue pair. */
    private void awaitLive(SourceAutomix.IdentityPending waiting) {
        if (liveWait) return;
        liveWait = true;
        final int generation = pairGeneration;
        SourceAutomix.prepareIdentity(waiting.uri)
                .whenComplete(
                        (fingerprint, failure) ->
                                handler.post(
                                        () -> {
                                            liveWait = false;
                                            if (isDestroyed()
                                                    || isFinishing()
                                                    || generation != pairGeneration) return;
                                            if (failure != null || fingerprint == null) {
                                                checking = false;
                                                identityFailed = true;
                                                liveKey = null;
                                                liveCheckedAt =
                                                        android.os.SystemClock.elapsedRealtime();
                                                lastChecking = false;
                                                lastDetached = true;
                                                updateSaveState();
                                                enablePreview(false);
                                                message(Strings.t("Could not read this file"));
                                                return;
                                            }
                                            liveCheckedAt = -501;
                                            refreshLive();
                                        }));
    }

    /** Applies the latest answer of detached() to every control that depends on it. */
    private void refreshLive() {
        lastDetached = detached();
        lastChecking = checking;
        if (tab == 3) buildControls();
        updateSaveState();
        if (audition == null && !saving) enablePreview(storeReady);
        syncPending();
        if (status != null) {
            if (identityFailed && !checking && !lastDetached) {
                identityFailed = false;
                message("");
            }
            CharSequence shown = status.getText();
            if (checking && shown.length() == 0) message(Strings.t("Loading…"));
            else if (!checking && storeReady && Strings.t("Loading…").contentEquals(shown))
                message("");
        }
        if (!checking && autoApply && applyPending && alive && !lastDetached)
            handler.postDelayed(applyLater, 750);
    }

    private void pairFailed(String text) {
        message(text);
    }

    /**
     * Moves the editor onto the pair the queue holds now. The queue-following pair does it by
     * itself once its draft is safe; a chosen or reversed pair only on an explicit request. Nothing
     * here touches playback, the queue or the saved transitions.
     */
    private void followQueue(boolean explicit) {
        if (!alive || isDestroyed() || isFinishing() || pair == null || edit == null) return;
        if (editingBusy()) return;
        // A chosen pair that is still waiting for its turn outranks the automatic follow.
        if (!explicit && draftWrite.pending != null) return;
        if (!explicit && pair != queuePair) return;
        if (!storeReady || settling != null || saving || draftWrite.busy || audition != null)
            return;
        if (listening != null && !listening.closed) return;
        if (!explicit && (resetPending || checking)) return;
        TransitionRuntime.Pair live;
        try {
            live = TransitionRuntime.pair();
        } catch (Exception | LinkageError unavailable) {
            SourceAutomix.IdentityPending waiting = pendingIdentity(unavailable);
            if (waiting != null) {
                checking = true;
                awaitLive(waiting);
            }
            return;
        }
        // An unknown current or next song is waited for, never guessed.
        if (live == null || live.outgoingDuration <= 0 || live.incomingDuration <= 0) return;
        try {
            if ((live.key + ":" + live.recording()).equals(pair.key + ":" + pair.recording())) {
                followRequested = false;
                // A repeated song keeps its key at a new queue position; Preview and Use must
                // start from the actual positions.
                if ((live.index != pair.index || live.nextIndex != pair.nextIndex)
                        && (pair == queuePair || explicit)) {
                    selection.reset(live);
                    queuePair = live;
                    pair = live;
                    liveCheckedAt = -501;
                }
                return;
            }
        } catch (SourceAutomix.IdentityPending waiting) {
            checking = true;
            awaitLive(waiting);
            return;
        }
        if (dirtyDraft) {
            if (autoSave && !saveFailed) {
                persistDraft(false);
                return;
            }
            if (explicit) {
                followRequested = false;
                discardDialog(
                        () -> {
                            dirtyDraft = false;
                            followRequested = true;
                            followQueue(true);
                        });
            }
            return;
        }
        followRequested = false;
        draftWrite.pending = null;
        selection.reset(live);
        queuePair = live;
        saveFailed = applyFailed = false;
        pairSelected(live);
    }

    private void followCurrent() {
        followRequested = true;
        followQueue(true);
    }

    /**
     * A chosen or reversed pair waits until the shown pair's draft is safely stored; a failed save
     * keeps both the choice and the edit.
     */
    private void pairChosen(TransitionRuntime.Pair next) {
        if (next == null || edit == null) return;
        draftWrite.pending = next == pair ? null : next;
        resolvePending();
    }

    private void resolvePending() {
        TransitionRuntime.Pair next = draftWrite.pending;
        if (next == null || !alive || isDestroyed() || isFinishing() || edit == null) return;
        if (editingBusy()) return;
        if (next == pair) {
            draftWrite.pending = null;
            return;
        }
        if (!storeReady || draftWrite.busy || saving || settling != null) return;
        if (dirtyDraft) {
            if (autoSave) {
                if (!saveFailed) persistDraft(false);
            } else if (pendingDialog == null || !pendingDialog.isShowing())
                pendingDialog =
                        discardDialog(
                                () -> {
                                    pendingDialog = null;
                                    handler.removeCallbacks(saveDraftLater);
                                    dirtyDraft = false;
                                    saveFailed = false;
                                    resolvePending();
                                },
                                () -> {
                                    pendingDialog = null;
                                    draftWrite.pending = null;
                                    selection.reset(pair);
                                });
            return;
        }
        draftWrite.pending = null;
        pairSelected(next);
    }

    /** A chosen or reversed pair: only the preview side changes; the queue and playback do not. */
    private void pairSelected(TransitionRuntime.Pair next) {
        if (next == null || next == pair || edit == null) return;
        if (autoSave && dirtyDraft) persistDraft(false);
        stopPreview();
        pairGeneration++;
        handler.removeCallbacks(saveDraftLater);
        handler.removeCallbacks(applyLater);
        pair = next;
        for (int deck = 0; deck < 2; deck++) {
            stopWave(deck);
            waves[deck] = null;
            waveLoading[deck] = false;
            bpms[deck] = 0;
            firstBeats[deck] = 0;
            keys[deck] = null;
        }
        readAnalysis();
        storeReady = false;
        dirtyDraft = false;
        applyPending = false;
        preview.setEnabled(false);
        miniPreview.setEnabled(false);
        bindTracks();
        liveCheckedAt = -501;
        updateSaveState();
        message(Strings.t("Loading…"));
        final TransitionRuntime.Pair target = next;
        final int started = pairGeneration;
        TransitionStore.get()
                .read(
                        target,
                        result -> {
                            if (isDestroyed() || started != pairGeneration) return;
                            TransitionStore.get()
                                    .readDraft(
                                            target,
                                            draft -> {
                                                if (isDestroyed() || started != pairGeneration)
                                                    return;
                                                bindStored(
                                                        result.error == null ? result.edit() : null,
                                                        draft);
                                            });
                        });
    }

    /** The pair's own saved draft or applied edit; nothing here applies anything. */
    private void bindStored(TransitionEdit stored, TransitionStore.Draft draft) {
        boolean usable = draft.edit != null && draft.error == null;
        draftStored = usable;
        appliedState =
                stored == null
                        ? state(TransitionRuntime.automaticDraft(pair), true)
                        : state(stored, stored.automaticTiming);
        automatic = usable ? draft.automatic : stored == null || stored.automaticTiming;
        edit =
                usable
                        ? draft.edit
                        : stored == null ? TransitionRuntime.automaticDraft(pair) : stored;
        normalize();
        initial = state();
        observedState = initial;
        storeReady = true;
        undo.clear();
        redo.clear();
        update();
        buildControls();
        requestWaves();
        scheduleWaves();
        updateSaveState();
        enablePreview(true);
        message(draft.error == null ? "" : Strings.t("Could not load saved transitions."));
        syncPending();
    }

    private void bindTracks() {
        for (int d = 0; d < 2; d++) {
            String name = d == 0 ? pair.outgoingTitle : pair.incomingTitle;
            androidx.media3.common.MediaItem item = d == 0 ? pair.outgoing : pair.incoming;
            CharSequence artist = item == null ? null : item.mediaMetadata.artist;
            titleViews[d].setText(name);
            artistViews[d].setText(
                    artist == null
                            ? Strings.t(d == 0 ? "Outgoing" : "Incoming")
                            : artist.toString());
            compactTitles[d].setText((d == 0 ? "A  " : "B  ") + name);
            covers[d].setImageDrawable(new Cover(d));
            loadCover(item, covers[d]);
        }
    }

    /** Takes the queue's paused listening state for the first preview or use, once. */
    private TransitionPreview.Session holdSession() throws Exception {
        TransitionPreview.Session held = TransitionPreview.existing(queuePair);
        if (held == null) held = TransitionPreview.acquire(queuePair);
        held.attach(this);
        if (held.selectedPair() != pair) {
            held.selectPair(pair);
            clearAudition();
        }
        listening = held;
        return held;
    }

    /** The session ended our audition without a callback, so the editor's own state is reset. */
    private void clearAudition() {
        previewGeneration++;
        audition = null;
        handler.removeCallbacks(animate);
        if (preview != null) preview.setContentDescription(Strings.t("Preview"));
        enablePreview(storeReady);
        if (preview != null && miniPreview != null) previewIcon(false);
        if (timeline != null) timeline.invalidate();
        if (miniTimeline != null) miniTimeline.invalidate();
    }

    /** Replaces only the current and next queue entries with the previewed pair. */
    private void usePair() {
        if (settling != null || saving || !storeReady || !detached()) return;
        if (draftWrite.busy) {
            message(Strings.t("Saving…"));
            return;
        }
        TransitionPairSelection.Retention keep = null;
        try {
            handler.removeCallbacks(applyLater);
            applyPending = false;
            TransitionPreview.Session held = holdSession();
            keep = selection.retainSelectionForSave(pair);
            TransitionPreview.Session next = held.usePair(pair);
            keep.commit();
            clearAudition();
            listening = next;
            next.attach(this);
            if (next != held) settling = next;
            liveCheckedAt = -501;
            message(Strings.t("Using this pair…"));
            updateSaveState();
            buildControls();
        } catch (java.io.IOException unavailable) {
            if (keep != null) keep.close();
            message(unavailable.getMessage());
        } catch (Exception | LinkageError failure) {
            if (keep != null) keep.close();
            message(
                    failure.getMessage() == null
                            ? Strings.t("Could not use this pair.")
                            : Strings.t(failure.getMessage()));
        }
    }

    private void settleUse() {
        TransitionPreview.Session from = settling;
        try {
            TransitionPreview.Session next = from.settle();
            if (next != from) completeUse(next);
        } catch (Exception | LinkageError failure) {
            settling = null;
            message(Strings.t("The queue changed; reopen this transition"));
            updateSaveState();
        }
    }

    /** The queue's own pair is now known; the current edit moves to it unchanged. */
    private void completeUse(TransitionPreview.Session next) {
        settling = null;
        listening = next;
        next.attach(this);
        TransitionRuntime.Pair live = next.pair;
        stopPreview();
        pairGeneration++;
        for (int deck = 0; deck < 2; deck++) {
            stopWave(deck);
            waves[deck] = null;
            waveLoading[deck] = false;
        }
        queuePair = live;
        pair = live;
        liveCheckedAt = -501;
        readAnalysis();
        bindTracks();
        normalize();
        observedState = state();
        handler.removeCallbacks(applyLater);
        applyPending = false;
        update();
        scheduleWaves();
        final int started = pairGeneration;
        TransitionStore.get()
                .read(
                        live,
                        result -> {
                            if (isDestroyed() || started != pairGeneration) return;
                            TransitionEdit stored = result.error == null ? result.edit() : null;
                            appliedState =
                                    stored == null
                                            ? state(TransitionRuntime.automaticDraft(live), true)
                                            : state(stored, stored.automaticTiming);
                            updateSaveState();
                        });
        if (draftStored || dirtyDraft) {
            // The draft follows the pair under the queue's own key; it is not applied.
            resetPending = true;
            dirtyDraft = true;
            persistDraft(false);
        } else selection.reset(live);
        updateSaveState();
        if (tab == 3) buildControls();
        message("");
    }

    private void recallDraft() {
        final TransitionRuntime.Pair target = pair;
        final int started = pairGeneration;
        TransitionStore.get()
                .readDraft(
                        target,
                        draft -> {
                            if (isDestroyed() || started != pairGeneration) return;
                            if (draft.edit == null || draft.error != null) {
                                message(Strings.t("No saved draft"));
                                return;
                            }
                            remember();
                            edit = draft.edit;
                            automatic = draft.automatic;
                            normalize();
                            update();
                            buildControls();
                            scheduleWaves();
                        });
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
            String badge = measured.badge(), reason = measured.state();
            // Measured values stay readable when their confidence is too low to trust.
            double seen = measured.ready && measured.bpm > 0 ? measured.bpm : 0;
            String seenKey = measured.ready ? KeyTempo.camelotOf(measured.key) : null;
            changed |=
                    bpm != bpms[d]
                            || first != firstBeats[d]
                            || !java.util.Objects.equals(key, keys[d])
                            || !badge.equals(badges[d])
                            || !reason.equals(reasons[d])
                            || seen != shownBpm[d]
                            || !java.util.Objects.equals(seenKey, shownKeys[d]);
            shownBpm[d] = seen;
            shownKeys[d] = seenKey;
            badges[d] = badge;
            reasons[d] = reason;
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
        covers[deck] = cover;
        row.addView(cover, new LinearLayout.LayoutParams(style.dp(44), style.dp(44)));
        LinearLayout names = column();
        TextView title = label(deck == 0 ? pair.outgoingTitle : pair.incomingTitle, 16, true);
        titleViews[deck] = title;
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
        artistViews[deck] = artistLabel;
        names.addView(artistLabel);
        LinearLayout.LayoutParams nameSpace = new LinearLayout.LayoutParams(0, -2, 1);
        nameSpace.leftMargin = style.dp(10);
        nameSpace.rightMargin = style.dp(10);
        row.addView(names, nameSpace);
        LinearLayout facts =
                new LinearLayout(this) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = Math.max(style.dp(48), MeasureSpec.getSize(widthSpec) / 2);
                        keyBadges[deck].setMaxWidth(width);
                        meta[deck].setMaxWidth(width);
                        super.onMeasure(widthSpec, heightSpec);
                    }
                };
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView key = label("", 11, true);
        key.setTag("automix-key:" + deck);
        key.setGravity(Gravity.CENTER);
        key.setMinWidth(style.dp(36));
        key.setMinHeight(style.dp(20));
        key.setPadding(style.dp(8), style.dp(1), style.dp(8), style.dp(1));
        keyBadges[deck] = key;
        facts.addView(key, new LinearLayout.LayoutParams(-2, -2));
        meta[deck] = label("", 11, false);
        meta[deck].setTag("automix-meta:" + deck);
        meta[deck].setGravity(Gravity.END);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-2, -2);
        line.topMargin = style.dp(2);
        facts.addView(meta[deck], line);
        row.addView(facts, new LinearLayout.LayoutParams(-2, -2));
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
                case "plus":
                    line(canvas, 12, 5, 12, 19);
                    line(canvas, 5, 12, 19, 12);
                    break;
                case "minus":
                    line(canvas, 5, 12, 19, 12);
                    break;
                case "chevrons":
                    line(canvas, 6, 7, 11, 12, 6, 17);
                    line(canvas, 13, 7, 18, 12, 13, 17);
                    break;
                case "chevrons-back":
                    line(canvas, 11, 7, 6, 12, 11, 17);
                    line(canvas, 18, 7, 13, 12, 18, 17);
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
        // Artwork of a replaced selection stops downloading.
        Future<?> previous = coverJobs.remove(target);
        if (previous != null) previous.cancel(true);
        if (item == null) return;
        final int token = pairGeneration;
        coverJobs.put(
                target,
                ARTWORK.submit(
                        () -> {
                            java.net.HttpURLConnection connection = null;
                            try {
                                Object value =
                                        TransitionRuntime.field(item.mediaMetadata, "artworkUri");
                                if (!(value instanceof android.net.Uri)) return;
                                android.net.Uri uri = (android.net.Uri) value;
                                java.io.InputStream stream;
                                if ("https".equals(uri.getScheme())) {
                                    connection =
                                            (java.net.HttpURLConnection)
                                                    new java.net.URL(uri.toString())
                                                            .openConnection();
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
                                        Math.max(
                                                1,
                                                Math.max(options.outWidth, options.outHeight)
                                                        / 160);
                                options.inJustDecodeBounds = false;
                                options.inPreferredConfig = Bitmap.Config.RGB_565;
                                Bitmap cover =
                                        BitmapFactory.decodeByteArray(
                                                data, 0, data.length, options);
                                if (cover != null)
                                    handler.post(
                                            () -> {
                                                // Artwork for a replaced selection is dropped.
                                                if (alive
                                                        && !isDestroyed()
                                                        && token == pairGeneration)
                                                    target.setImageBitmap(cover);
                                            });
                            } catch (Exception unavailable) {
                                /* Artwork is optional. */
                            } finally {
                                if (connection != null) connection.disconnect();
                            }
                        }));
    }

    private void normalize() {
        edit.automaticTiming = automatic && edit.tempoExplicit;
        edit.outgoingTrim =
                Math.max(
                        0,
                        Math.min(
                                edit.outgoingTrim,
                                Math.min(120, pair.outgoingDuration - .5 * edit.overlapRate[0])));
        edit.incomingCue =
                Math.max(
                        0,
                        Math.min(
                                edit.incomingCue,
                                Math.min(3600, pair.incomingDuration - 1.5 * edit.overlapRate[1])));
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
        copy.automaticTiming = automatic && copy.tempoExplicit;
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
        automatic = false;
        normalize();
        edit.preset = -1;
        update();
        scheduleWaves();
    }

    private void message(String text) {
        if (status == null) return;
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateAnalysis() {
        if (analysisSummary == null || pair == null) return;
        TransitionAnalysis.Snapshot a = TransitionAnalysis.read(pair, 0),
                b = TransitionAnalysis.read(pair, 1);
        analysisSummary.setText(
                (conciseAnalysis() ? Strings.t("Analysis") : Strings.t("Track analysis")) + " ›");
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
                            for (int deck = 0; deck < 2; deck++)
                                if (waveFailed[deck]) stopWave(deck);
                            requestWaves();
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
                double tempo = edit.baseBpm(deck, bpms[deck]);
                boolean lowTempo = (!Double.isFinite(tempo) || tempo <= 0) && shownBpm[deck] > 0;
                String unknown =
                        lowTempo
                                ? number(Math.round(shownBpm[deck] * 10) / 10d)
                                        + " bpm · "
                                        + Strings.t("Estimated")
                                : Strings.t("Ready").equals(badges[deck]) ? "— bpm" : badges[deck];
                meta[deck].setText(
                        (tempo > 0
                                        ? number(Math.round(tempo * 10) / 10d)
                                                + (edit.overlapRate[deck] == 1
                                                        ? ""
                                                        : " → "
                                                                + number(
                                                                        Math.round(
                                                                                        edit
                                                                                                        .overlapBpm(
                                                                                                                deck,
                                                                                                                bpms[
                                                                                                                        deck])
                                                                                                * 10)
                                                                                / 10d))
                                                + " bpm"
                                        : unknown)
                                + " · "
                                + clock(deck == 0 ? pair.outgoingDuration : pair.incomingDuration));
                meta[deck].setContentDescription(
                        (lowTempo
                                        ? number(Math.round(shownBpm[deck] * 10) / 10d)
                                                + " bpm · "
                                                + Strings.t("Low confidence")
                                                + " · "
                                                + Strings.t("Estimated")
                                        : tempo > 0 ? meta[deck].getText() : reasons[deck])
                                .toString());
                TextView badge = keyBadges[deck];
                boolean known = keys[deck] != null, low = !known && shownKeys[deck] != null;
                badge.setText(
                        known
                                ? keys[deck]
                                : low ? shownKeys[deck] + " · " + Strings.t("Estimated") : "—");
                badge.setTextColor(
                        known ? 0xff071b0f : low ? (deck == 0 ? YELLOW : CYAN) : style.secondary);
                badge.setBackground(
                        style.shape(
                                known
                                        ? (deck == 0 ? YELLOW : CYAN)
                                        : (style.dark ? 0xff30312f : style.card),
                                12,
                                false));
                badge.setContentDescription(
                        Strings.t("Musical key")
                                + " · "
                                + (known
                                        ? keys[deck]
                                        : low
                                                ? shownKeys[deck]
                                                        + " · "
                                                        + Strings.t("Low confidence")
                                                        + " · "
                                                        + Strings.t("Estimated")
                                                : Strings.t("Not available")));
            }
        syncPending();
        syncOverlap();
        snap.setSelected(snapping);
        snap.setTextColor(snapping ? GREEN : style.foreground);
        snap.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph("beats", snapping ? GREEN : style.foreground, 22), null, null, null);
        snap.setBackground(
                style.shape(
                        snapping ? (style.dark ? 0xff223c2b : 0xffd8eddf) : style.card, 24, false));
        timeline.invalidate();
        if (miniTimeline != null) miniTimeline.invalidate();
        if (miniLength != null && nativePending)
            miniLength.setText(Strings.t("Automatic mix is not ready yet."));
        else if (miniLength != null)
            miniLength.setText(
                    clock(edit.start(pair.outgoingDuration))
                            + " → "
                            + clock(edit.incomingCue)
                            + "     "
                            + number(edit.overlap)
                            + " s · "
                            + (edit.overlapRate[0] == 1 && edit.overlapRate[1] == 1
                                    ? number(AutomixAccess.speed()) + "×"
                                    : "A "
                                            + rateText(edit.overlapRate[0])
                                            + " / B "
                                            + rateText(edit.overlapRate[1])));
        syncRate();
        noticeChange();
        updateSaveState();
        updatePlayer();
        rebuildPresets();
        syncPending();
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
            // The current track decodes first; the next one starts when it finishes or fails.
            if (deck == 1 && waveBusy[0]) continue;
            if (waveFailed[deck]
                    && failFrom[deck] <= from + .000001
                    && failTo[deck] >= to - .000001) continue;
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
        waveFailed[deck] = false;
        if (waveJobs[deck] != null) waveJobs[deck].cancel(true);
        waveJobs[deck] = null;
    }

    private void startWave(int deck, double from, double to) {
        int generation = ++waveGenerations[deck];
        waveAgain[deck] = false;
        waveFailed[deck] = false;
        TransitionRuntime.WaveRequest request = TransitionRuntime.waveform(pair, deck, from, to);
        waveLoading[deck] = request != null;
        if (request == null) return;
        String id = TransitionWaveCache.identity(pair, deck);
        TransitionWaveCache.yieldTo(id, from, to);
        waveBusy[deck] = true;
        jobFrom[deck] = from;
        jobTo[deck] = to;
        // The current window gets the decoder before an uncached next window.
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
                                                else {
                                                    waveFailed[deck] = true;
                                                    failFrom[deck] = from;
                                                    failTo[deck] = to;
                                                }
                                                // Peaks for an older window are redrawn from
                                                // the cache if the edit has moved since; the
                                                // next track starts once this one has settled.
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
                        beginAdjustment(b);
                        overlapTracking = true;
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        overlapTracking = false;
                        endAdjustment(b);
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
            return TransitionEdit.secondsForBars(
                    bars, edit.overlapBpm(0, bpms[0]) * edit.tempoScale[0]);
        } catch (IllegalArgumentException missing) {
            return Double.NaN;
        }
    }

    private void setBars(int bars) {
        try {
            double span =
                    TransitionEdit.secondsForBars(
                            bars, edit.overlapBpm(0, bpms[0]) * edit.tempoScale[0]);
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
        String seconds = number(edit.overlap) + " s", length = "";
        double bar = beat() * 4;
        if (edit.bars > 0) length = edit.bars + " " + Strings.t("bars");
        else if (bar > 0 && Math.round(edit.overlap / bar) >= 1)
            length = "≈" + Math.round(edit.overlap / bar) + " " + Strings.t("bars");
        String spoken = seconds + (length.isEmpty() ? "" : " · " + length);
        overlapValue.setText(nativePending ? "—" : length.isEmpty() ? seconds : spoken);
        overlapValue.setContentDescription(
                Strings.t("Overlap")
                        + " · "
                        + (nativePending ? Strings.t("Automatic mix is not ready yet.") : spoken));
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
                                TransitionEdit previous = edit;
                                automatic = selected == 1;
                                if (selected == 0) {
                                    toCustom();
                                    edit.preset = -1;
                                } else if (selected == 1) {
                                    TransitionEdit next = TransitionRuntime.automaticDraft(pair);
                                    // Only tempo the listener chose is kept; native rates are not.
                                    if (previous.tempoExplicit) next.copyTempoFrom(previous);
                                    edit = next;
                                    normalize();
                                } else edit.preset(selected - 2);
                                update();
                                buildControls();
                                scheduleWaves();
                            });
            button.setTag("transition-preset:" + i);
            // Only the capsule is inset; the full 48dp height stays touchable.
            button.setBackground(
                    new android.graphics.drawable.InsetDrawable(
                            style.shape(
                                    active
                                            ? style.foreground
                                            : (style.dark ? 0xff363636 : style.card),
                                    24,
                                    false),
                            0,
                            style.dp(7),
                            0,
                            style.dp(7)));
            button.setTextColor(active ? background() : style.foreground);
            button.setTextSize(13);
            button.setSingleLine(true);
            button.setPadding(style.dp(8), style.dp(7), style.dp(8), style.dp(7));
            // Single-line mode resets the minimum; the target is 48dp or the text plus the inset.
            android.graphics.Paint.FontMetricsInt face = button.getPaint().getFontMetricsInt();
            button.setMinHeight(Math.max(style.dp(48), face.bottom - face.top + style.dp(14)));
            int available =
                    getResources().getConfiguration().screenWidthDp
                            - (wide ? getResources().getConfiguration().screenWidthDp / 2 : 0)
                            - 32;
            button.setMinWidth(style.dp(Math.max(65, (available - 24) / 4)));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
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
            button.setMinHeight(style.dp(48));
            button.setPadding(style.dp(5), 0, style.dp(5), 0);
            button.setTextColor(tab == i ? GREEN : style.foreground);
            button.setCompoundDrawablePadding(style.dp(wide ? 6 : 2));
            Glyph icon =
                    new Glyph(
                            new String[] {"volume", "eq", "effects", "tools"}[i],
                            tab == i ? GREEN : style.foreground,
                            19);
            button.setCompoundDrawablesWithIntrinsicBounds(
                    wide ? icon : null, wide ? null : icon, null, null);
            button.setBackground(new TabLine(tab == i));
            tabs.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
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
            slider(
                    timing,
                    Strings.t("Incoming") + " · " + Strings.t("Start"),
                    0,
                    Math.max(0, Math.min(3600, pair.incomingDuration - 1.5 * edit.overlapRate[1])),
                    edit.incomingCue,
                    " s",
                    value -> {
                        edit.incomingCue = value;
                        changed();
                    });
            TextView advanced =
                    chip(
                            Strings.t("Advanced curve"),
                            advancedCurve,
                            () -> {
                                advancedCurve = !advancedCurve;
                                buildControls();
                            });
            advanced.setTag("transition-advanced-curve");
            advanced.setContentDescription(Strings.t("Advanced curve"));
            advanced.setAccessibilityDelegate(
                    new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(
                                View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName(Button.class.getName());
                            if (android.os.Build.VERSION.SDK_INT >= 30)
                                info.setStateDescription(
                                        Strings.t(advancedCurve ? "Expanded" : "Collapsed"));
                        }
                    });
            timing.addView(advanced);
        }
        if (tab == 1 || tab == 3 || (tab == 0 && advancedCurve)) {
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
                                24,
                                false));
                LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
                half.setMargins(style.dp(2), 0, style.dp(2), 0);
                deckSelect.addView(choice, half);
            }
            timing.addView(deckSelect);
        }
        if (tab == 0 && advancedCurve) {
            slider(
                    timing,
                    Strings.t("Outgoing") + " · " + Strings.t("End"),
                    0,
                    Math.min(120, pair.outgoingDuration - .5 * edit.overlapRate[0]),
                    edit.outgoingTrim,
                    " s",
                    value -> {
                        edit.outgoingTrim = value;
                        changed();
                    });
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
                double span = edit.sourceSpan(side);
                slider(
                        timing,
                        Strings.t("Time"),
                        offset + (edit.times[side][point - 1] + TransitionEdit.MIN_GAP) * span,
                        offset + (edit.times[side][point + 1] - TransitionEdit.MIN_GAP) * span,
                        offset + edit.times[side][point] * span,
                        " s",
                        value -> {
                            toCustom();
                            edit.times[side][point] = (value - offset) / span;
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
            TextView reset =
                    action(
                            Strings.t("Reset EQ"),
                            () -> {
                                remember();
                                java.util.Arrays.fill(edit.eq[side], 0);
                                changed();
                                buildControls();
                            });
            reset.setTag("transition-eq-reset");
            timing.addView(reset);
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
            LinearLayout filters = row();
            timing.addView(filters);
            for (int d = 0; d < 2; d++) {
                final int deck = d;
                LinearLayout cell = column();
                LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1);
                half.setMargins(style.dp(2), 0, style.dp(2), 0);
                filters.addView(cell, half);
                choose(
                        cell,
                        Strings.t(d == 0 ? "Outgoing effect" : "Incoming effect"),
                        new String[] {
                            Strings.t("None"), Strings.t("Low-pass"), Strings.t("High-pass")
                        },
                        edit.filter[d],
                        value -> {
                            remember();
                            edit.filter[deck] = value;
                            if (value == 2) edit.cutoff[deck] = Math.min(edit.cutoff[deck], 2000);
                            changed();
                            buildControls();
                        });
                View selector = cell.getChildAt(0);
                selector.setTag("transition-filter:" + d);
                selector.getLayoutParams().height = -1;
            }
            slider(
                    timing,
                    Strings.t("Filter depth"),
                    0,
                    100,
                    edit.filterDepth * 100,
                    "%",
                    value -> {
                        edit.filterDepth = value / 100;
                        changed();
                    });
            timing.addView(label(Strings.t("How far the selected filters sweep."), 12, false));
            for (int d = 0; d < 2; d++) {
                final int deck = d;
                if (edit.filter[d] != 0)
                    slider(
                            timing,
                            (d == 0 ? "A · " : "B · ") + Strings.t("Cutoff"),
                            20,
                            edit.filter[d] == 2 ? 2000 : 20000,
                            edit.cutoff[d],
                            " Hz",
                            value -> {
                                edit.cutoff[deck] = value;
                                changed();
                            });
            }
            TelegramSwitch again =
                    optionSwitch("Repeat preview", repeatPreview, value -> repeatPreview = value);
            again.setTag("transition-repeat");
            timing.addView(optionRow(again, "Repeat preview"));
        } else if (tab == 3) {
            buildTools(timing);
        }
        TextView speed =
                label(Strings.t("Speed") + " · " + number(AutomixAccess.speed()) + "×", 12, false);
        speed.setPadding(style.dp(6), style.dp(16), style.dp(6), style.dp(20));
        controls.addView(speed);
    }

    private MixTools.Grid grid(int deck) {
        return edit.grid(deck, bpms[deck], firstBeats[deck]);
    }

    private void tempoTools(LinearLayout parent) {
        TextView heading = label(Strings.t("Overlap tempo"), 14, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        parent.addView(heading);
        TextView hint =
                label(
                        Strings.t(
                                "Changes each track's speed during the overlap only. Pitch is"
                                        + " kept."),
                        12,
                        false);
        hint.setPadding(0, style.dp(4), 0, style.dp(8));
        parent.addView(hint);
        for (int d = 0; d < 2; d++) {
            final int deck = d;
            double base = edit.baseBpm(d, bpms[d]);
            TextView title =
                    label(
                            (d == 0 ? "A · " : "B · ")
                                    + Strings.t(d == 0 ? "Outgoing" : "Incoming"),
                            13,
                            true);
            title.setTextColor(d == 0 ? YELLOW : CYAN);
            parent.addView(title);
            LinearLayout line = row();
            TextView own =
                    action(
                            Strings.t("Track BPM")
                                    + " · "
                                    + (base > 0 ? number(Math.round(base * 10) / 10d) : "—"),
                            () ->
                                    exact(
                                            Strings.t("Track BPM"),
                                            30,
                                            300,
                                            base,
                                            value -> {
                                                remember();
                                                edit.manualBpm[deck] = value;
                                                edit.tempoExplicit = true;
                                                edit.bars = 0;
                                                changed();
                                                buildControls();
                                            }));
            own.setTag("transition-bpm:" + d);
            line.addView(own, new LinearLayout.LayoutParams(0, -2, 1));
            TextView target =
                    action(
                            Strings.t("Overlap BPM")
                                    + " · "
                                    + (base > 0
                                            ? number(
                                                    Math.round(edit.overlapBpm(d, bpms[d]) * 10)
                                                            / 10d)
                                            : "—"),
                            () ->
                                    exact(
                                            Strings.t("Overlap BPM"),
                                            base * .5,
                                            base * 2,
                                            edit.overlapBpm(deck, bpms[deck]),
                                            value -> {
                                                remember();
                                                edit.setOverlapBpm(deck, value, bpms[deck]);
                                                edit.bars = 0;
                                                changed();
                                                buildControls();
                                            }));
            target.setTag("transition-overlap-bpm:" + d);
            target.setEnabled(base > 0);
            target.setAlpha(base > 0 ? 1 : .4f);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, -2, 1);
            gap.leftMargin = style.dp(8);
            line.addView(target, gap);
            parent.addView(line);
            if (edit.manualBpm[d] > 0) {
                TextView measured =
                        action(
                                Strings.t("Use measured tempo"),
                                () -> {
                                    remember();
                                    edit.manualBpm[deck] = 0;
                                    edit.tempoExplicit = true;
                                    edit.bars = 0;
                                    changed();
                                    buildControls();
                                });
                measured.setTag("transition-bpm-measured:" + d);
                parent.addView(measured);
            }
            TextView readout =
                    label(
                            Strings.t("Multiplier")
                                    + " · "
                                    + rateText(edit.overlapRate[d])
                                    + " · "
                                    + rateOffset(edit.overlapRate[d]),
                            11,
                            false);
            readout.setPadding(style.dp(4), style.dp(4), 0, style.dp(8));
            parent.addView(readout);
        }
        LinearLayout match = row();
        for (int target : new int[] {1, 0}) {
            boolean can = edit.canMatchTempo(target, bpms[0], bpms[1]);
            TextView button =
                    action(
                            Strings.t(target == 1 ? "Match B to A" : "Match A to B"),
                            () -> {
                                remember();
                                edit.matchTempo(target, bpms[0], bpms[1]);
                                edit.bars = 0;
                                changed();
                                buildControls();
                            });
            button.setTag("transition-match:" + target);
            button.setEnabled(can);
            button.setAlpha(can ? 1 : .4f);
            if (!can)
                button.setContentDescription(
                        button.getText()
                                + " · "
                                + Strings.t("Needs both tempos and a ratio from 0.5× to 2×"));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
            space.rightMargin = style.dp(4);
            space.leftMargin = style.dp(4);
            match.addView(button, space);
        }
        parent.addView(match);
        if (!edit.canMatchTempo(0, bpms[0], bpms[1]) || !edit.canMatchTempo(1, bpms[0], bpms[1])) {
            TextView reason =
                    label(Strings.t("Needs both tempos and a ratio from 0.5× to 2×"), 11, false);
            reason.setPadding(style.dp(4), style.dp(4), style.dp(4), style.dp(8));
            parent.addView(reason);
        }
        TextView original =
                action(
                        Strings.t("Original tempo"),
                        () -> {
                            remember();
                            edit.resetOverlapRates();
                            edit.bars = 0;
                            changed();
                            buildControls();
                        });
        original.setTag("transition-tempo-reset");
        boolean moved = edit.overlapRate[0] != 1 || edit.overlapRate[1] != 1;
        original.setEnabled(moved);
        original.setAlpha(moved ? 1 : .4f);
        parent.addView(original);
    }

    private void pairTools(LinearLayout parent) {
        TextView heading = label(Strings.t("Tracks"), 14, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        parent.addView(heading);
        boolean ready = storeReady && settling == null && !saving;
        LinearLayout loads = row();
        for (int d = 0; d < 2; d++) {
            final int deck = d;
            TextView load =
                    action(
                            Strings.t(d == 0 ? "Load outgoing" : "Load incoming"),
                            () -> selection.launch(this, deck));
            load.setTag("transition-load:" + d);
            load.setEnabled(ready);
            load.setAlpha(ready ? 1 : .4f);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
            space.leftMargin = style.dp(d == 0 ? 0 : 8);
            loads.addView(load, space);
        }
        parent.addView(loads);
        LinearLayout more = row();
        TextView reverse = action(Strings.t("Reverse"), () -> pairChosen(selection.reverse()));
        reverse.setTag("transition-reverse");
        reverse.setEnabled(ready);
        reverse.setAlpha(ready ? 1 : .4f);
        more.addView(reverse, new LinearLayout.LayoutParams(0, -2, 1));
        TextView recall = action(Strings.t("Recall draft"), this::recallDraft);
        recall.setTag("transition-recall");
        recall.setEnabled(ready);
        recall.setAlpha(ready ? 1 : .4f);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, -2, 1);
        gap.leftMargin = style.dp(8);
        more.addView(recall, gap);
        parent.addView(more);
        if (detached()) {
            TextView use = action(Strings.t("Use this pair"), this::usePair);
            use.setTag("transition-use");
            use.setEnabled(ready);
            use.setAlpha(ready ? 1 : .4f);
            LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, -2);
            full.topMargin = style.dp(8);
            parent.addView(use, full);
            TextView hint =
                    label(
                            Strings.t(
                                    "Replaces the current and next song in the queue. The rest"
                                            + " stays as it is."),
                            11,
                            false);
            hint.setPadding(style.dp(4), style.dp(4), 0, style.dp(8));
            parent.addView(hint);
            TextView follow = action(Strings.t("Follow current pair"), this::followCurrent);
            follow.setTag("transition-follow");
            boolean followable = storeReady && settling == null && !saving && !checking;
            follow.setEnabled(followable);
            follow.setAlpha(followable ? 1 : .4f);
            LinearLayout.LayoutParams followSpace = new LinearLayout.LayoutParams(-1, -2);
            followSpace.bottomMargin = style.dp(8);
            parent.addView(follow, followSpace);
        }
    }

    private void buildTools(LinearLayout parent) {
        pairTools(parent);
        tempoTools(parent);
        barPresets(parent);
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
            jumps.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
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
            nudges.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
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
            beats.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        parent.addView(beats);
        choose(
                parent,
                Strings.t("Beat grid scale"),
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
                        editingDialogs.add(
                                new AlertDialog.Builder(this)
                                        .setItems(
                                                new String[] {
                                                    Strings.t("Save"), Strings.t("Remove")
                                                },
                                                (dialog, which) -> {
                                                    if (which == 0) storeCue(cue);
                                                    else {
                                                        remember();
                                                        edit.cues[side][cue] = -1;
                                                        changed();
                                                        buildControls();
                                                    }
                                                })
                                        .show());
                        return true;
                    });
            bookmarks.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
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
                        : Math.min(3600, pair.incomingDuration - edit.sourceSpan(1) * 3);
        double min = side == 0 ? Math.max(edit.sourceSpan(0), pair.outgoingDuration - 120) : 0;
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
                        beginAdjustment(b);
                    }

                    public void onStopTrackingTouch(SeekBar b) {
                        endAdjustment(b);
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
        trackAdjustment(caption);
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
        value.setText(Double.isFinite(current) ? number(current) : "");
        value.setSelectAllOnFocus(true);
        modalAction(
                modal,
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
        modalAction(modal, Strings.t("Cancel"), false, modal::dismiss);
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
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
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
                            editingDialogs.add(dialog);
                            dialog.show();
                        });
        action.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(style.dark ? 0xff30312f : style.card, 24, true),
                        null));
        action.setTextSize(13);
        action.setTypeface(style.medium);
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8));
        action.setMinHeight(style.dp(48));
        parent.addView(action);
    }

    private void preview() {
        startPreview(null);
    }

    /** The queue's own pair is on automatic timing but native AutoMix has not chosen it yet. */
    private boolean awaitingNativePlan() {
        return automatic && !detached() && TransitionRuntime.automaticPlan(pair) == null;
    }

    /** Both Preview buttons follow the stored draft and the native plan together. */
    private void enablePreview(boolean ready) {
        boolean waiting = ready && edit != null && awaitingNativePlan();
        boolean on = ready && !waiting && !checking;
        if (preview != null) {
            preview.setEnabled(on);
            if (audition == null)
                preview.setContentDescription(
                        waiting
                                ? Strings.t("Preview")
                                        + " · "
                                        + Strings.t("Automatic mix is not ready yet.")
                                : Strings.t("Preview"));
        }
        if (miniPreview != null) {
            miniPreview.setEnabled(on);
            if (audition == null)
                miniPreview.setContentDescription(
                        waiting
                                ? Strings.t("Preview")
                                        + " · "
                                        + Strings.t("Automatic mix is not ready yet.")
                                : Strings.t("Preview"));
        }
    }

    /** Tracks whether the timing on screen is only a placeholder until native AutoMix plans. */
    private void syncPending() {
        boolean waiting = edit != null && storeReady && awaitingNativePlan();
        boolean changed = waiting != nativePending;
        nativePending = waiting;
        String reason = Strings.t("Automatic mix is not ready yet.");
        if (status != null) {
            CharSequence shown = status.getText();
            if (waiting && shown.length() == 0) message(reason);
            else if (!waiting && reason.contentEquals(shown)) message("");
        }
        if (audition == null && !saving) enablePreview(storeReady);
        if (changed && timeline != null) {
            syncOverlap();
            timeline.invalidate();
            if (miniTimeline != null) miniTimeline.invalidate();
        }
    }

    /** Replaces untouched placeholder timing with the native plan once it exists. */
    private void adoptNativePlan() {
        if (edit == null || audition != null || !adjustments.isEmpty()) return;
        if (!automatic && edit.tempoExplicit) return;
        if (detached() || checking) return;
        TransitionEdit plan = TransitionRuntime.automaticPlan(pair);
        if (plan == null) return;
        boolean chosen = edit.tempoExplicit;
        boolean sameRates =
                plan.overlapRate[0] == edit.overlapRate[0]
                        && plan.overlapRate[1] == edit.overlapRate[1];
        if (!automatic) {
            if (sameRates) return;
            TransitionEdit custom = edit.copy();
            custom.overlapRate[0] = plan.overlapRate[0];
            custom.overlapRate[1] = plan.overlapRate[1];
            plan = custom;
        } else {
            if (plan.overlap == edit.overlap
                    && plan.outgoingTrim == edit.outgoingTrim
                    && plan.incomingCue == edit.incomingCue
                    && (chosen || sameRates)) return;
            if (chosen) plan.copyTempoFrom(edit);
        }
        String before = state();
        edit = plan;
        normalize();
        String after = state();
        if (before.equals(initial)) initial = after;
        if (before.equals(appliedState)) appliedState = after;
        observedState = after;
        update();
        buildControls();
        scheduleWaves();
    }

    /** Plays the native automatic mix; the draft, the store and the queue stay as they are. */
    private void previewBefore() {
        TransitionRuntime.AutomaticMix mix = TransitionRuntime.before(pair);
        if (mix.edit == null) {
            message(mix.reason);
            return;
        }
        startPreview(mix.edit);
    }

    private void startPreview(TransitionEdit automaticMix) {
        if (!storeReady || saving) return;
        if (audition != null) {
            stopPreview();
            return;
        }
        if (settling != null) {
            message(Strings.t("Using this pair…"));
            return;
        }
        if (automaticMix == null && awaitingNativePlan()) {
            message(Strings.t("Automatic mix is not ready yet."));
            return;
        }
        try {
            normalize();
            if (automaticMix == null && autoApply) applyPending = true;
            auditionBefore = automaticMix != null;
            holdSession();
            final int generation = ++previewGeneration;
            audition =
                    listening.start(
                            automaticMix == null ? edit : automaticMix,
                            () -> {
                                boolean again =
                                        audition != null
                                                && audition.completed
                                                && !auditionBefore
                                                && repeatPreview
                                                && generation == previewGeneration
                                                && alive;
                                boolean failed = audition != null && audition.error != null;
                                boolean heardBefore = auditionBefore;
                                auditionBefore = false;
                                audition = null;
                                handler.removeCallbacks(animate);
                                if (!isFinishing() && !isDestroyed()) {
                                    enablePreview(true);
                                    previewIcon(false);
                                    preview.setContentDescription(Strings.t("Preview"));
                                    timeline.invalidate();
                                    message(
                                            Strings.t(
                                                    failed
                                                            ? "Could not preview this transition."
                                                            : "Current song paused"));
                                    updatePlayer();
                                    if (autoApply && applyPending && !failed && !heardBefore)
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
            auditionBefore = false;
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
                new Glyph(playing ? "stop" : "play", style.foreground, 22), null, null, null);
        miniPreview.setCompoundDrawablesWithIntrinsicBounds(
                new Glyph(playing ? "stop" : "play", style.foreground, 22), null, null, null);
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
            saveFailed = applyFailed = false;
            applyPending = autoApply && !value.equals(appliedState);
            handler.removeCallbacks(saveDraftLater);
            handler.removeCallbacks(applyLater);
            if (autoSave && dirtyDraft) handler.postDelayed(saveDraftLater, 450);
            if (applyPending && adjustments.isEmpty()) handler.postDelayed(applyLater, 750);
            message("");
        }
        observedState = value;
    }

    private void updateSaveState() {
        if (saveState == null) return;
        saveState.setText(
                Strings.t(
                        saveFailed
                                ? "Draft not saved"
                                : draftWrite.busy
                                        ? "Saving draft…"
                                        : dirtyDraft
                                                ? (autoSave ? "Saving draft…" : "Unsaved draft")
                                                : "Draft saved"));
        saveState.setTextColor(dirtyDraft || saveFailed ? YELLOW : style.secondary);
        boolean applied = !applyFailed && state().equals(appliedState);
        applyState.setText(
                Strings.t(
                        applyFailed
                                ? "Mix not applied"
                                : applied ? "Mix applied" : "Mix not applied"));
        applyState.setTextColor(applyFailed ? YELLOW : style.secondary);
        // Beside the controls at large text the state moves into the actions' spoken labels.
        boolean terse = wide && getResources().getConfiguration().fontScale > 1.15f;
        saveState.setVisibility(terse ? View.GONE : View.VISIBLE);
        applyState.setVisibility(terse ? View.GONE : View.VISIBLE);
        draftSave.setText(Strings.t(autoSave ? "Auto-save" : "Save draft"));
        draftSave.setContentDescription(
                terse ? draftSave.getText() + " · " + saveState.getText() : null);
        save.setContentDescription(
                terse
                        ? Strings.t(autoApply ? "Auto-apply" : "Apply")
                                + " · "
                                + applyState.getText()
                        : null);
        draftSave.setEnabled(!autoSave && !draftWrite.busy);
        save.setText(Strings.t(autoApply ? "Auto-apply" : "Apply"));
        save.setEnabled(storeReady && !saving && !autoApply && !detached() && !checking);
        save.setTextColor(0xff071b0f);
        save.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(Color.TRANSPARENT, 24, false),
                        null));
        View applyCapsule = (View) save.getParent();
        applyCapsule.setBackground(style.shape(GREEN, 24, false));
        ((View) draftSave.getParent())
                .setBackground(
                        style.shape(
                                autoSave
                                        ? (style.dark ? 0xff263d2d : 0xffd8eddf)
                                        : (style.dark ? 0xff363636 : style.card),
                                24,
                                false));
        for (boolean saving : new boolean[] {true, false}) {
            TextView handle = saving ? saveHandle : applyHandle;
            boolean on = saving ? autoSave : autoApply;
            handle.setContentDescription(
                    Strings.t(saving ? "Save mode" : "Apply mode")
                            + ": "
                            + Strings.t(on ? "Automatic" : "Manual"));
            handle.setCompoundDrawablesWithIntrinsicBounds(
                    new Glyph(
                            on ? "chevrons-back" : "chevrons",
                            saving ? (on ? 0xff071b0f : style.foreground) : 0xff071b0f,
                            22),
                    null,
                    null,
                    null);
            handle.setBackground(
                    style.shape(
                            saving
                                    ? (on ? GREEN : (style.dark ? 0xff4c514d : 0xffcccccc))
                                    : (on ? 0x55ffffff : 0x33000000),
                            24,
                            false));
            FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) handle.getLayoutParams();
            at.gravity = (on ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
            handle.setLayoutParams(at);
            View action = saving ? draftSave : save;
            FrameLayout.LayoutParams margin = (FrameLayout.LayoutParams) action.getLayoutParams();
            margin.setMarginStart(on ? 0 : style.dp(48));
            margin.setMarginEnd(on ? style.dp(48) : 0);
            action.setLayoutParams(margin);
        }
    }

    private void persistDraft(boolean notify) {
        handler.removeCallbacks(saveDraftLater);
        if (isDestroyed() || !storeReady || edit == null) return;
        if (draftWrite.busy) {
            handler.postDelayed(saveDraftLater, 450);
            return;
        }
        // A detached draft is still saved: bounds are checked, but nothing is acquired or paused.
        final TransitionRuntime.Pair target = pair;
        final int started = pairGeneration;
        final String snapshot = state();
        final TransitionEdit draft = edit.copy();
        final boolean auto = automatic;
        final TransitionPairSelection.Retention keep;
        try {
            TransitionRuntime.validateDraftBounds(target, draft);
            keep = selection.retainSelectionForSave(target);
        } catch (Exception | LinkageError unavailable) {
            saveFailed = true;
            message(Strings.t("Could not save this transition."));
            updateSaveState();
            return;
        }
        draftWrite.busy = true;
        saveFailed = false;
        updateSaveState();
        TransitionStore.get()
                .writeDraft(
                        target,
                        draft,
                        auto,
                        error -> {
                            draftWrite.busy = false;
                            if (error == null) keep.commit();
                            else keep.close();
                            if (isDestroyed() || started != pairGeneration || target != pair)
                                return;
                            if (error == null && resetPending) {
                                selection.reset(target);
                                resetPending = false;
                            }
                            if (error == null) draftStored = true;
                            saveFailed = error != null;
                            if (error != null) {
                                message(Strings.t("Could not save this transition."));
                                updateSaveState();
                                return;
                            }
                            initial = snapshot;
                            dirtyDraft = !state().equals(snapshot);
                            updateSaveState();
                            if (notify)
                                Toast.makeText(this, Strings.t("Draft saved"), Toast.LENGTH_SHORT)
                                        .show();
                            resolvePending();
                        });
    }

    private void save() {
        apply(true);
    }

    private void apply(boolean notify) {
        if (edit == null || !storeReady || saving) return;
        if (!adjustments.isEmpty()) return;
        if (!notify && audition != null) return;
        // Only the pair the queue actually holds can be applied; a preview selection never can.
        liveCheckedAt = -501;
        if (detached()) {
            if (notify) message(Strings.t("Use this pair to apply it"));
            return;
        }
        if (checking) {
            if (notify) message(Strings.t("Loading…"));
            return;
        }
        try {
            normalize();
            TransitionRuntime.validateSave(pair, automatic ? null : edit);
            String snapshot = state();
            TransitionEdit draft = edit.copy();
            boolean auto = automatic;
            final TransitionRuntime.Pair target = pair;
            final int started = pairGeneration;
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
                            target,
                            draft,
                            auto,
                            error -> {
                                saving = false;
                                if (error == null && applying != null) applying.close(true);
                                if (isDestroyed()) {
                                    if (error != null) closeListening();
                                    return;
                                }
                                if (started != pairGeneration || target != pair) {
                                    if (listening == applying && error == null) listening = null;
                                    updateSaveState();
                                    return;
                                }
                                applyFailed = error != null;
                                updateSaveState();
                                enablePreview(true);
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
            SourceAutomix.IdentityPending waiting = pendingIdentity(unavailable);
            if (waiting == null) message(Strings.t("Could not save this transition."));
            else {
                checking = true;
                awaitLive(waiting);
                message(Strings.t("Loading…"));
                updateSaveState();
            }
        }
    }

    private void close() {
        if (saving || isDestroyed()) return;
        if (settling != null) {
            settleUse();
            if (settling != null) {
                message(Strings.t("Wait for the pair to finish loading"));
                return;
            }
        }
        if (draftWrite.busy) {
            message(Strings.t("Saving…"));
            handler.postDelayed(this::close, 100);
            return;
        }
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
            final TransitionRuntime.Pair target = pair;
            final TransitionEdit draft = edit.copy();
            final TransitionPairSelection.Retention keep;
            try {
                TransitionRuntime.validateDraftBounds(target, draft);
                keep = selection.retainSelectionForSave(target);
            } catch (Exception | LinkageError unavailable) {
                message(Strings.t("Could not save this transition."));
                discardDialog();
                return;
            }
            saving = true;
            draftWrite.busy = true;
            String snapshot = state();
            TransitionStore.get()
                    .writeDraft(
                            target,
                            draft,
                            automatic,
                            error -> {
                                saving = false;
                                draftWrite.busy = false;
                                if (error == null) keep.commit();
                                else keep.close();
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
        editingDialogs.add(
                discardDialog(
                        () -> {
                            closeListening();
                            finish();
                        }));
    }

    private AlertDialog discardDialog(Runnable discard) {
        return discardDialog(discard, null);
    }

    /** Cancel, Back and a tap outside all end in {@code cancelled}. */
    private AlertDialog discardDialog(Runnable discard, Runnable cancelled) {
        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(Strings.t("Discard changes?"))
                        .setPositiveButton(Strings.t("Discard"), (dialog, which) -> discard.run())
                        .setNegativeButton(
                                Strings.t("Cancel"),
                                (dialog, which) -> {
                                    if (cancelled != null) cancelled.run();
                                });
        if (cancelled != null) builder.setOnCancelListener(dialog -> cancelled.run());
        return builder.show();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void menu() {
        PlaylistModal modal = new PlaylistModal(this, Strings.t("Settings"), "");
        boolean differs = appliedState != null && !state().equals(appliedState);
        menuItem(
                modal,
                "Undo",
                "transition-menu-undo",
                !undo.isEmpty(),
                () -> {
                    redo.addLast(state());
                    restore(undo.removeLast());
                });
        menuItem(
                modal,
                "Redo",
                "transition-menu-redo",
                !redo.isEmpty(),
                () -> {
                    undo.addLast(state());
                    restore(redo.removeLast());
                });
        menuItem(
                modal,
                "Revert to applied transition",
                "transition-menu-revert",
                differs,
                () -> {
                    remember();
                    restore(appliedState);
                });
        menuItem(
                modal,
                "Reset to automatic",
                "transition-menu-automatic",
                true,
                () -> {
                    remember();
                    edit = TransitionRuntime.automaticDraft(pair);
                    automatic = true;
                    normalize();
                    update();
                    buildControls();
                    scheduleWaves();
                });
        TransitionRuntime.AutomaticMix mix = TransitionRuntime.before(pair);
        menuItem(
                modal,
                "Preview Before",
                "transition-menu-before",
                mix.edit != null && !detached(),
                mix.edit != null && detached()
                        ? Strings.t("Automatic mix is not ready yet.")
                        : mix.reason,
                this::previewBefore);
        menuItem(
                modal,
                "Refresh waveforms",
                "transition-menu-refresh",
                true,
                () -> {
                    waves[0] = null;
                    waves[1] = null;
                    TransitionWaveCache.clearMemory();
                    readAnalysis();
                    update();
                    requestWaves();
                });
        modal.show();
    }

    private void menuItem(
            PlaylistModal modal, String title, String tag, boolean enabled, Runnable run) {
        menuItem(modal, title, tag, enabled, null, run);
    }

    /** A disabled item says why when it is read aloud or tapped. */
    private void menuItem(
            PlaylistModal modal,
            String title,
            String tag,
            boolean enabled,
            String reason,
            Runnable run) {
        TextView item =
                modalAction(
                        modal,
                        Strings.t(title),
                        false,
                        () -> {
                            if (!enabled) {
                                if (reason != null) {
                                    modal.dismiss();
                                    message(reason);
                                }
                                return;
                            }
                            modal.dismiss();
                            stopPreview();
                            run.run();
                        });
        item.setTag(tag);
        item.setEnabled(enabled);
        item.setAlpha(enabled ? 1 : .4f);
        if (!enabled && reason != null)
            item.setContentDescription(Strings.t(title) + " · " + reason);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (edit != null) {
            out.putString("pair", pair.key);
            out.putString("draft", edit.encode());
            out.putString("initial", initial);
            out.putInt("side", side);
            out.putInt("tab", tab);
            out.putBoolean("advancedCurve", advancedCurve);
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
    protected void onResume() {
        super.onResume();
        SourceAutomix.editor(this, true);
    }

    @Override
    protected void onPause() {
        SourceAutomix.editor(this, false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        alive = false;
        adjustments.clear();
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
        SourceAutomix.editor(this, false);
        // Dismissing is not a cancel: the pending choice and the draft stay with the retained
        // state.
        if (pendingDialog != null) pendingDialog.dismiss();
        pendingDialog = null;
        for (android.app.Dialog dialog : new HashSet<>(editingDialogs)) dialog.dismiss();
        editingDialogs.clear();
        handler.removeCallbacks(saveDraftLater);
        handler.removeCallbacks(applyLater);
        for (Future<?> cover : coverJobs.values()) cover.cancel(true);
        coverJobs.clear();
        if (!isChangingConfigurations() && !saving) closeListening();
        if (selection != null) {
            // A rotation keeps the chosen files; only the final close releases the helper.
            if (isChangingConfigurations()) selection.detach();
            else selection.close();
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object onRetainNonConfigurationInstance() {
        return selection == null || pair == null || queuePair == null
                ? null
                : new Retained(selection, queuePair, pair, settling, resetPending, draftWrite);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (selection != null && selection.handleResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
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
        LinearLayout v =
                new LinearLayout(this) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        super.onMeasure(width, height);
                        int tallest = 0, actions = 0;
                        for (int i = 0; i < getChildCount(); i++) {
                            View child = getChildAt(i);
                            if (child instanceof TextView
                                    && !TextUtils.isEmpty(((TextView) child).getText())
                                    && child.isClickable()
                                    && child.getVisibility() != View.GONE) {
                                tallest = Math.max(tallest, child.getMeasuredHeight());
                                actions++;
                            }
                        }
                        if (actions < 2) return;
                        boolean changed = false;
                        for (int i = 0; i < getChildCount(); i++) {
                            View child = getChildAt(i);
                            if (child instanceof TextView
                                    && !TextUtils.isEmpty(((TextView) child).getText())
                                    && child.isClickable()
                                    && child.getVisibility() != View.GONE
                                    && child.getLayoutParams().height != tallest) {
                                child.getLayoutParams().height = tallest;
                                changed = true;
                            }
                        }
                        if (changed) super.onMeasure(width, height);
                    }
                };
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
        button.setMinHeight(style.dp(48));
        button.setPadding(style.dp(16), style.dp(6), style.dp(16), style.dp(6));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(view -> run.run());
        button.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                    }
                });
        trackAdjustment(button);
        button.setSelected(selected);
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x228e8e93),
                        style.shape(
                                selected ? (style.dark ? 0xff414141 : style.card) : background(),
                                24,
                                false),
                        null));
        return button;
    }

    private TextView modalAction(
            PlaylistModal modal, String title, boolean primary, Runnable action) {
        TextView view = modal.action(title, primary, action);
        editingDialogs.add(modal.dialog);
        trackAdjustment(view);
        return view;
    }

    private void trackAdjustment(View view) {
        view.setOnTouchListener(
                (target, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) beginAdjustment(target);
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        endAdjustment(target);
                    return false;
                });
        view.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View target) {}

                    public void onViewDetachedFromWindow(View target) {
                        endAdjustment(target);
                    }
                });
    }

    private void beginAdjustment(View view) {
        adjustments.add(view);
        handler.removeCallbacks(applyLater);
    }

    private void endAdjustment(View view) {
        if (!adjustments.remove(view) || !adjustments.isEmpty()) return;
        if (alive && autoApply && applyPending && audition == null) {
            handler.removeCallbacks(applyLater);
            handler.postDelayed(applyLater, 750);
        }
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

        // Scaled controls keep their own margins clear of the curve points.
        float left() {
            float margin = style.dp(52);
            for (TextView control : mini ? miniRate : rate)
                if (control != null)
                    margin = Math.max(margin, control.getMeasuredWidth() + style.dp(4));
            return Math.max(getWidth() * .15f, margin);
        }

        float right() {
            return getWidth() - left();
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
                    long first = (long) Math.floor(grid.at(start - edit.sourceSpan(d) * .22));
                    ink.setStrokeWidth(style.dp(.6f));
                    for (long i = first; i < first + 600; i++) {
                        double t = grid.time(i);
                        float at = x((t - start) / edit.sourceSpan(d));
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
                // Placeholder timing is not drawn as a transition.
                if (nativePending) continue;
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
                String end = clock(start + edit.sourceSpan(d));
                if (!mini)
                    canvas.drawText(
                            end,
                            right() - ink.measureText(end),
                            deckTop(d) + bandHeight() - style.dp(2),
                            ink);
                ink.setAlpha(255);
            }
            if (!nativePending) {
                ink.setColor(YELLOW);
                ink.setStrokeWidth(style.dp(1));
                canvas.drawLine(left(), style.dp(8), left(), bottom, ink);
                canvas.drawLine(right(), style.dp(8), right(), bottom, ink);
            }
            if (audition != null && !auditionBefore) {
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
                                    / edit.sourceSpan(deck)
                                    * (right() - left())
                                    / bins);
            ink.setStrokeWidth(Math.max(1, step));
            int[] colors = {0xff474fe3, 0xffd78743, 0xffffe1b2};
            for (int b = 0; b < 3; b++)
                for (int i = 0; i < bins; i++) {
                    double time =
                            waveFrom[deck] + (i + .5) * (waveTo[deck] - waveFrom[deck]) / bins;
                    float at = x((time - start) / edit.sourceSpan(deck));
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
                beginAdjustment(this);
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
                    endAdjustment(this);
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
                        at =
                                (grid(deck).snap(offset + at * edit.sourceSpan(deck)) - offset)
                                        / edit.sourceSpan(deck);
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
                    double delta =
                            (downX - event.getX()) / (right() - left()) * edit.sourceSpan(deck);
                    if (deck == 0) {
                        double start =
                                pair.outgoingDuration - baseTrim - edit.sourceSpan(0) + delta;
                        if (snapping) start = grid(0).snap(start);
                        edit.outgoingTrim =
                                Math.max(
                                        0,
                                        Math.min(
                                                Math.min(
                                                        120,
                                                        pair.outgoingDuration - edit.sourceSpan(0)),
                                                pair.outgoingDuration
                                                        - edit.sourceSpan(0)
                                                        - start));
                    } else {
                        double cue = baseCue + delta;
                        if (snapping) cue = grid(1).snap(cue);
                        edit.incomingCue =
                                Math.max(
                                        0,
                                        Math.min(
                                                pair.incomingDuration - edit.sourceSpan(1) * 3,
                                                cue));
                    }
                    changed();
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                endAdjustment(this);
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
