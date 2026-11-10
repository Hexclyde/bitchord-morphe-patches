package app.bitchord.telegram;

import android.os.Handler;

import androidx.media3.common.Player;

import java.util.WeakHashMap;

/** Observes host seek commands. Advancing player position is explicitly only an output estimate. */
final class SeekTrace implements Player.Listener {
    private static final WeakHashMap<Player, SeekTrace> current = new WeakHashMap<>();
    private final Player player;
    private final Handler handler;
    private final PerformanceTrace.Span span;
    private final long began = System.nanoTime();
    private final Runnable poll = this::poll;
    private boolean accepted, ready, finished;
    private long readyPosition;

    static void requested(Object object, long target) {
        if (!PerformanceTrace.enabled() || !Diagnostics.active() || !(object instanceof Player))
            return;
        Player player = (Player) object;
        synchronized (current) {
            SeekTrace previous = current.remove(player);
            if (previous != null) previous.finish(PerformanceTrace.Outcome.SUPERSEDED);
            if (current.size() >= 16) return;
            SeekTrace trace = new SeekTrace(player, target);
            current.put(player, trace);
            player.addListener(trace);
            trace.handler.postDelayed(trace.poll, 100);
        }
    }

    private SeekTrace(Player player, long target) {
        this.player = player;
        handler = new Handler(player.getApplicationLooper());
        span = PerformanceTrace.begin(PerformanceTrace.Stage.SEEK);
        span.measure("original_ms", player.getCurrentPosition());
        span.measure("target_ms", target);
        span.measure("player", Integer.toUnsignedLong(System.identityHashCode(player)));
        span.progress(0, 0);
    }

    private void checkpoint(PerformanceTrace.Stage stage) {
        try (PerformanceTrace.Span point = PerformanceTrace.begin(stage, span)) {}
    }

    public void onPositionDiscontinuity(int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) accepted();
    }

    public void onPositionDiscontinuity(
            Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) accepted();
    }

    public void onPositionDiscontinuity(
            int reason, Player.PositionInfo oldPosition, Player.PositionInfo newPosition) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) accepted();
    }

    private void accepted() {
        if (finished || accepted) return;
        accepted = true;
        checkpoint(PerformanceTrace.Stage.SEEK_ACCEPTED);
    }

    public void onMediaItemTransition(androidx.media3.common.MediaItem item, int reason) {
        finish(PerformanceTrace.Outcome.CANCELLED);
    }

    public void onMediaItemTransition(int reason, androidx.media3.common.MediaItem item) {
        finish(PerformanceTrace.Outcome.CANCELLED);
    }

    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) poll();
    }

    private void poll() {
        handler.removeCallbacks(poll);
        if (finished) return;
        if (!Diagnostics.active()) {
            finish(PerformanceTrace.Outcome.CANCELLED);
            return;
        }
        if (System.nanoTime() - began > 30_000_000_000L) {
            finish(PerformanceTrace.Outcome.TIMEOUT);
            return;
        }
        if (accepted && player.getPlaybackState() == Player.STATE_READY) {
            if (!ready) {
                ready = true;
                readyPosition = player.getCurrentPosition();
                checkpoint(PerformanceTrace.Stage.SEEK_READY);
                if (!player.getPlayWhenReady()) {
                    finish(PerformanceTrace.Outcome.COMPLETE);
                    return;
                }
            } else if (player.isPlaying() && player.getCurrentPosition() > readyPosition) {
                checkpoint(PerformanceTrace.Stage.OUTPUT_PROGRESS_ESTIMATE);
                finish(PerformanceTrace.Outcome.COMPLETE);
                return;
            }
        }
        handler.postDelayed(poll, 100);
    }

    private void finish(PerformanceTrace.Outcome outcome) {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(poll);
        try {
            player.removeListener(this);
        } catch (RuntimeException released) {
            /* A released player has no useful callbacks. */
        }
        span.end(outcome);
        synchronized (current) {
            if (current.get(player) == this) current.remove(player);
        }
    }
}
