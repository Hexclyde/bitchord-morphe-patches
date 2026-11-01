package app.bitchord.soulseek;

import android.app.Activity;
import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.*;

/** Selected-file preview controls owned by a visible result, never by the host music queue. */
final class InlinePreview extends LinearLayout {
    interface Playback {
        boolean playing();

        default boolean requested() {
            return playing();
        }

        long position();

        void pause();

        void resume() throws Exception;

        String format();

        String error();

        void close();
    }

    interface PlayerFactory {
        Playback create(Activity activity, SoulseekPreview.Session session) throws Exception;
    }

    private static final ExecutorService STARTS =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "inline-preview-start");
                        t.setDaemon(true);
                        return t;
                    });
    private static final ScheduledExecutorService STATUS =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "inline-preview-status");
                        t.setDaemon(true);
                        return t;
                    });
    private static volatile WeakReference<InlinePreview> active = new WeakReference<>(null);
    private final Activity activity;
    private final SoulseekTrack track;
    private final String sourceSession;
    private final DownloadStyle style;
    private final PlayerFactory factory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageButton play, stop, details;
    private final TextView phase, time, information;
    private final ProgressBar progress;
    private volatile SoulseekPreview.Session session;
    private volatile long revision;
    private Playback playback;
    private Future<?> starting, polling;
    private boolean registered, complete;
    private String token;
    private final Application.ActivityLifecycleCallbacks lifecycle =
            new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, Bundle b) {}

                public void onActivityStarted(Activity a) {}

                public void onActivityResumed(Activity a) {}

                public void onActivityPaused(Activity a) {}

                public void onActivityStopped(Activity a) {
                    if (a == activity) stop("Preview stopped when leaving the screen");
                }

                public void onActivitySaveInstanceState(Activity a, Bundle b) {}

                public void onActivityDestroyed(Activity a) {
                    if (a == activity) stop("Preview closed");
                }
            };

    InlinePreview(Activity activity, SoulseekTrack track, String sourceSession) {
        this(
                activity,
                track,
                sourceSession,
                (owner, selected) -> {
                    PreviewPlayer actual = new PreviewPlayer(owner, selected);
                    return new Playback() {
                        public boolean playing() {
                            return actual.player.isPlaying();
                        }

                        public boolean requested() {
                            return actual.player.getPlayWhenReady();
                        }

                        public long position() {
                            return actual.player.getCurrentPosition();
                        }

                        public void pause() {
                            actual.player.pause();
                        }

                        public void resume() throws Exception {
                            actual.play();
                        }

                        public String format() {
                            return actual.format();
                        }

                        public String error() {
                            return actual.error();
                        }

                        public void close() {
                            actual.close();
                        }
                    };
                });
    }

    InlinePreview(
            Activity activity, SoulseekTrack track, String sourceSession, PlayerFactory factory) {
        super(activity);
        this.activity = activity;
        this.track = track;
        this.sourceSession = sourceSession;
        this.factory = factory;
        style = new DownloadStyle(activity);
        style.markCustom(this);
        setOrientation(VERTICAL);
        setTag("inline-preview:" + track.id);
        setPadding(0, style.dp(10), 0, 0);
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        play = control("Start preview", "play", true);
        play.setTag("preview-play");
        play.setOnClickListener(v -> toggle());
        bar.addView(play, new LayoutParams(style.dp(48), style.dp(48)));
        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(VERTICAL);
        copy.setPadding(style.dp(12), 0, style.dp(6), 0);
        phase = style.text(PatchStrings.t("Preview file"), 14, style.foreground, style.medium);
        phase.setTag("preview-state");
        phase.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        time = style.text("0:00 / 0:30", 12, style.secondary, style.regular);
        time.setTag("preview-time");
        copy.addView(phase);
        copy.addView(time);
        bar.addView(copy, new LayoutParams(0, -2, 1));
        stop = control("Stop preview", "stop", false);
        stop.setTag("preview-stop");
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stop("Preview stopped"));
        bar.addView(stop, new LayoutParams(style.dp(48), style.dp(48)));
        details = control("Check the recording", "inspect", false);
        details.setOnClickListener(
                v -> {
                    stop("Preview stopped");
                    try {
                        SoulseekPreview.open(activity, track, sourceSession);
                    } catch (Exception unavailable) {
                        state("Preview expired. Select the file again.");
                    }
                });
        bar.addView(details, new LayoutParams(style.dp(48), style.dp(48)));
        addView(bar);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(30_000);
        progress.setProgressTintList(ColorStateList.valueOf(0xff30d158));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
        progress.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        style.markCustom(progress);
        LayoutParams line = new LayoutParams(-1, style.dp(4));
        line.setMargins(0, style.dp(8), 0, style.dp(7));
        addView(progress, line);
        information =
                style.text(
                        PatchStrings.t("30-second preview · temporary data"),
                        12,
                        style.secondary,
                        style.regular);
        information.setTag("preview-information");
        addView(information);
    }

    private ImageButton control(String label, String kind, boolean primary) {
        ImageButton button = new ImageButton(activity);
        button.setContentDescription(PatchStrings.t(label));
        button.setPadding(style.dp(14), style.dp(14), style.dp(14), style.dp(14));
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(style.dark ? 0x33777777 : 0x22777777),
                        style.shape(primary ? style.foreground : Color.TRANSPARENT, 24, false),
                        null));
        button.setImageDrawable(glyph(kind, primary ? style.background : style.foreground));
        style.markCustom(button);
        return button;
    }

    private Drawable glyph(String kind, int ink) {
        if (kind.equals("info")) return style.viewIcon("info");
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                paint.setColor(ink);
                if (kind.equals("inspect")) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.7f);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    canvas.drawCircle(10, 10, 7, paint);
                    canvas.drawLine(15, 15, 21, 21, paint);
                    canvas.drawLine(7, 8, 7, 12, paint);
                    canvas.drawLine(10, 6, 10, 14, paint);
                    canvas.drawLine(13, 9, 13, 11, paint);
                } else if (kind.equals("pause")) {
                    canvas.drawRoundRect(5, 4, 10, 20, 1, 1, paint);
                    canvas.drawRoundRect(14, 4, 19, 20, 1, 1, paint);
                } else if (kind.equals("stop")) canvas.drawRoundRect(5, 5, 19, 19, 2, 2, paint);
                else {
                    Path p = new Path();
                    p.moveTo(7, 3);
                    p.lineTo(21, 12);
                    p.lineTo(7, 21);
                    p.close();
                    canvas.drawPath(p, paint);
                }
                canvas.restore();
            }

            public void setAlpha(int a) {
                paint.setAlpha(a);
            }

            public void setColorFilter(ColorFilter f) {
                paint.setColorFilter(f);
            }

            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }

            public int getIntrinsicWidth() {
                return style.dp(24);
            }

            public int getIntrinsicHeight() {
                return style.dp(24);
            }
        };
    }

    private boolean current(long version) {
        return revision == version
                && active.get() == this
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    private void state(String key) {
        String value = PatchStrings.t(key);
        if (!value.contentEquals(phase.getText())) phase.setText(value);
    }

    private void toggle() {
        if (playback != null && session != null && !session.closed.get() && !complete) {
            try {
                if (playback.requested()) {
                    playback.pause();
                    state("Resume preview");
                } else {
                    playback.resume();
                    state("Playing preview");
                }
                updatePlay();
            } catch (Exception failed) {
                stop("Preview could not resume");
            }
            return;
        }
        InlinePreview previous = active.get();
        if (previous != null) previous.stop("Preview stopped");
        active = new WeakReference<>(this);
        final long version = ++revision;
        complete = false;
        progress.setProgress(0);
        time.setText("0:00 / 0:30");
        play.setEnabled(false);
        stop.setEnabled(true);
        state("Preparing preview");
        try {
            token = SoulseekPreview.stage(activity, track, sourceSession);
            SoulseekPreview.Offer offer = SoulseekPreview.offer(token, activity);
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            registered = true;
            starting =
                    STARTS.submit(
                            () -> {
                                SoulseekPreview.Session created = null;
                                try {
                                    if (!current(version)) return;
                                    created = SoulseekPreview.start(activity, offer);
                                    session = created;
                                    if (!current(version)) {
                                        created.close();
                                        return;
                                    }
                                    SoulseekPreview.Session selected = created;
                                    main.post(
                                            () -> {
                                                if (!current(version)) {
                                                    selected.close();
                                                    return;
                                                }
                                                try {
                                                    playback = factory.create(activity, selected);
                                                    play.setEnabled(true);
                                                    updatePlay();
                                                    clock.run();
                                                    poll(version, selected);
                                                } catch (Exception | LinkageError invalid) {
                                                    stop(
                                                            "Preview player is unavailable for this"
                                                                    + " file or BitChord version");
                                                }
                                            });
                                } catch (Exception unavailable) {
                                    if (created != null) created.close();
                                    main.post(
                                            () -> {
                                                if (current(version))
                                                    stop(
                                                            "Preview could not start. Check"
                                                                    + " Soulseek account, queue and"
                                                                    + " free space.");
                                            });
                                }
                            });
        } catch (Exception unavailable) {
            stop("Preview expired. Select the file again.");
        }
    }

    private void poll(long version, SoulseekPreview.Session selected) {
        polling =
                STATUS.scheduleWithFixedDelay(
                        () -> {
                            try {
                                ProgressiveReader.Snapshot state = selected.snapshot();
                                main.post(
                                        () -> {
                                            if (!current(version) || playback == null) return;
                                            if ("failed".equals(state.status)
                                                    || "cancelled".equals(state.status)) {
                                                stop(
                                                        "Preview stopped. The session changed, the"
                                                                + " peer failed, or the temporary"
                                                                + " session expired.");
                                                return;
                                            }
                                            if (state.downloaded >= 64L * 1024 * 1024
                                                    && !state.complete) {
                                                stop(
                                                        "Preview reached its 64 MiB temporary-data"
                                                            + " limit. Choose Download to keep the"
                                                            + " full file.");
                                                return;
                                            }
                                            String bytes =
                                                    android.text.format.Formatter
                                                                    .formatShortFileSize(
                                                                            activity,
                                                                            state.downloaded)
                                                            + " / "
                                                            + android.text.format.Formatter
                                                                    .formatShortFileSize(
                                                                            activity, state.total);
                                            information.setText(
                                                    (playback.format().isEmpty()
                                                                    ? ""
                                                                    : playback.format() + " · ")
                                                            + bytes);
                                            if (!complete)
                                                state(
                                                        state.downloaded == 0
                                                                ? "Waiting for peer"
                                                                : playback.playing()
                                                                        ? "Playing preview"
                                                                        : "Buffering or paused");
                                        });
                            } catch (Exception failed) {
                                main.post(
                                        () -> {
                                            if (current(version))
                                                stop(
                                                        "Preview stopped. The session changed, the"
                                                                + " peer failed, or the temporary"
                                                                + " session expired.");
                                        });
                            }
                        },
                        0,
                        1,
                        TimeUnit.SECONDS);
    }

    private final Runnable clock =
            new Runnable() {
                public void run() {
                    if (playback == null) return;
                    if (session == null || session.closed.get()) {
                        stop("Preview stopped");
                        return;
                    }
                    String error = playback.error();
                    if (!error.isEmpty()) {
                        stop(error);
                        return;
                    }
                    long position = Math.min(30_000, Math.max(0, playback.position()));
                    progress.setProgress((int) position);
                    time.setText(String.format(Locale.ROOT, "0:%02d / 0:30", position / 1000));
                    if (position >= 30_000) {
                        playback.pause();
                        complete = true;
                        state("30-second preview complete");
                        updatePlay();
                    }
                    if (!complete) main.postDelayed(this, 250);
                }
            };

    private void updatePlay() {
        boolean playing = playback != null && playback.requested() && !complete;
        play.setImageDrawable(glyph(playing ? "pause" : "play", style.background));
        play.setContentDescription(
                PatchStrings.t(
                        playing ? "Pause preview" : complete ? "Start preview" : "Resume preview"));
    }

    void stop(String message) {
        ++revision;
        main.removeCallbacksAndMessages(null);
        if (starting != null) starting.cancel(true);
        if (polling != null) polling.cancel(true);
        starting = polling = null;
        SoulseekPreview.Session old = session;
        session = null;
        if (old != null) old.close();
        if (playback != null) playback.close();
        playback = null;
        if (registered) activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        registered = false;
        if (active.get() == this) active = new WeakReference<>(null);
        SoulseekPreview.forget(token);
        token = null;
        play.setEnabled(true);
        play.setContentDescription(PatchStrings.t("Start preview"));
        play.setImageDrawable(glyph("play", style.background));
        stop.setEnabled(false);
        state(message);
        progress.setProgress(0);
        time.setText("0:00 / 0:30");
        information.setText(PatchStrings.t("30-second preview · temporary data"));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (registered || session != null || starting != null)
            stop("Preview stopped when leaving the screen");
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (play != null
                && visibility != View.VISIBLE
                && (registered || session != null || starting != null))
            stop("Preview stopped when leaving the screen");
    }
}
