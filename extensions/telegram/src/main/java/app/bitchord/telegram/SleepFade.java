package app.bitchord.telegram;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Player;

import java.lang.reflect.Field;

/**
 * The sleep timer's fade: the song gets quieter over the chosen seconds, then pauses, and the
 * volume goes back up for the next time you play. With the timer's "After this song" option the
 * last seconds of the song fade instead, so it isn't cut off at full volume.
 *
 * <p>The fade stands down the moment anything else touches playback: a pause or play, a seek
 * backwards, a song change, a new timer, or the volume being moved by BitChord's own fades. A song
 * on BitChord's bit-perfect output, whose volume is the DAC's, pauses without a fade.
 */
final class SleepFade {
    private SleepFade() {}

    static final int MIN_SECONDS = 3, MAX_SECONDS = 60;
    static final int STEP_MILLIS = 50;

    /** The fade running now, if any; one at a time. */
    private static volatile Fade running;

    /** Volume at {@code step} of {@code steps}: an even fade to the ear, not to the meter. */
    static float fade(int step, int steps) {
        float left = 1f - (float) step / steps;
        return Math.max(0f, left * left);
    }

    /** The timer's time is up: fade out, then pause. */
    static void pauseSoon(Player player, int seconds) {
        if (player == null) return;
        // A bit-perfect song ignores the player's volume, so a fade would only delay the pause.
        if (!player.isPlaying() || seconds <= 0 || BitPerfect.playing()) {
            player.pause();
            return;
        }
        start(player, seconds, true);
    }

    /**
     * "Sleep after this song" is set and the song is near its end: fade the rest of it. BitChord
     * pauses at the end itself; the volume goes back up then.
     */
    static void fadeEnding(Player player, int seconds) {
        if (player == null || !player.isPlaying() || seconds <= 0) return;
        if (running != null || BitPerfect.playing()) return;
        start(player, seconds, false);
    }

    /** Stops a fade and puts the volume back, after a pause or play from outside. */
    static void cancel() {
        Fade fade = running;
        if (fade != null) fade.stop(true);
    }

    /** A spare crossfade player's events must not cancel the active player's fade. */
    static void cancel(Player player) {
        Fade fade = running;
        if (fade != null && fade.player == player) fade.stop(true);
    }

    static boolean active() {
        return running != null;
    }

    private static void start(Player player, int seconds, boolean pauseAtEnd) {
        Fade before = running;
        if (before != null) before.stop(true);
        Looper looper = player.getApplicationLooper();
        Fade fade =
                new Fade(
                        player,
                        new Handler(looper == null ? Looper.getMainLooper() : looper),
                        seconds,
                        pauseAtEnd);
        running = fade;
        HostLog.d(
                "Sleep timer: fading out over "
                        + seconds
                        + " s"
                        + (pauseAtEnd ? "" : " to the end"));
        fade.handler.post(fade);
    }

    private static final class Fade implements Runnable {
        final Player player;
        final Handler handler;
        final int steps;
        final boolean pauseAtEnd;
        final float startVolume;
        final MediaItemId item;
        final Long initialDeadline;
        float expected;
        long lastPosition;
        int step;
        boolean done;
        boolean deadlineCleared;

        Fade(Player player, Handler handler, int seconds, boolean pauseAtEnd) {
            this.player = player;
            this.handler = handler;
            this.steps = Math.max(1, seconds * 1000 / STEP_MILLIS);
            this.pauseAtEnd = pauseAtEnd;
            this.startVolume = player.getVolume();
            this.expected = startVolume;
            this.item = new MediaItemId(player);
            this.lastPosition = player.getCurrentPosition();
            this.initialDeadline = SleepFade.deadline();
            this.deadlineCleared = initialDeadline == null;
        }

        @Override
        public void run() {
            if (done) return;
            try {
                float now = player.getVolume();
                long position = player.getCurrentPosition();
                boolean moved = Math.abs(now - expected) > 0.02f;
                boolean elsewhere = !item.same(player) || position + 1500 < lastPosition;
                boolean paused = !player.isPlaying() && !player.getPlayWhenReady();
                boolean retimed =
                        pauseAtEnd
                                ? timerSetAgain()
                                : !SleepFade.afterTrack() || SleepFade.deadline() != null;
                if (moved || elsewhere || paused || retimed) {
                    // Someone else is in charge: BitChord's own fade, a seek, the user.
                    HostLog.d(
                            "Sleep timer: fade stood down ("
                                    + (moved
                                            ? "volume changed"
                                            : elsewhere
                                                    ? "song changed"
                                                    : paused ? "paused" : "timer set again")
                                    + ")");
                    stop(!moved);
                    return;
                }
                lastPosition = position;
                if (++step >= steps) {
                    if (pauseAtEnd) {
                        player.pause();
                        stop(true);
                    } else {
                        // Silent until the song ends; the end puts the volume back.
                        player.setVolume(0f);
                        expected = 0f;
                        handler.postDelayed(this, 250);
                        if (player.getPlaybackState() == Player.STATE_ENDED || !player.isPlaying())
                            stop(true);
                    }
                    return;
                }
                expected = startVolume * fade(step, steps);
                player.setVolume(expected);
                handler.postDelayed(this, STEP_MILLIS);
            } catch (RuntimeException e) {
                stop(true);
                if (pauseAtEnd)
                    try {
                        player.pause();
                    } catch (RuntimeException ignored) {
                        // The player is gone.
                    }
            }
        }

        void stop(boolean restore) {
            if (done) return;
            done = true;
            handler.removeCallbacks(this);
            if (running == this) running = null;
            if (restore)
                try {
                    player.setVolume(startVolume);
                } catch (RuntimeException ignored) {
                    // The player is gone.
                }
        }

        /** After the timer fired BitChord cleared it; a deadline set again means a new timer. */
        private boolean timerSetAgain() {
            Long deadline = SleepFade.deadline();
            if (deadline == null) {
                deadlineCleared = true;
                return false;
            }
            return deadlineCleared || !deadline.equals(initialDeadline);
        }
    }

    /** The current song, by id and index, so a change is seen even between two equal songs. */
    private static final class MediaItemId {
        final String id;
        final int index;

        MediaItemId(Player player) {
            androidx.media3.common.MediaItem item = player.getCurrentMediaItem();
            id = item == null ? "" : String.valueOf(item.mediaId);
            index = player.getCurrentMediaItemIndex();
        }

        boolean same(Player player) {
            androidx.media3.common.MediaItem item = player.getCurrentMediaItem();
            return index == player.getCurrentMediaItemIndex()
                    && id.equals(item == null ? "" : String.valueOf(item.mediaId));
        }
    }

    // ── BitChord's timer ─────────────────────────────────────────────────────

    private static volatile Field deadlineField, afterTrackField;

    /** The timer's deadline on the phone's uptime clock, or null when no timer is set. */
    static Long deadline() {
        Object value = timerValue("deadline");
        return value instanceof Long ? (Long) value : null;
    }

    /** True while the timer's "After this song" option is set. */
    static boolean afterTrack() {
        return Boolean.TRUE.equals(timerValue("afterTrack"));
    }

    private static Object timerValue(String name) {
        try {
            Field field = name.equals("deadline") ? deadlineField : afterTrackField;
            if (field == null) {
                Class<?> timer = Class.forName("com.music.bitchord.playback.SleepTimer");
                field = timer.getDeclaredField(name);
                field.setAccessible(true);
                if (name.equals("deadline")) deadlineField = field;
                else afterTrackField = field;
            }
            Object flow = field.get(null);
            return flow == null ? null : flow.getClass().getMethod("getValue").invoke(flow);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
