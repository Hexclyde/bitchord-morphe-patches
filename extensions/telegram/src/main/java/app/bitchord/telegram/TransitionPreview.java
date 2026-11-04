package app.bitchord.telegram;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** A paused listening session and two private, single-track audition decks. */
final class TransitionPreview {
    interface Deck extends AutoCloseable {
        Player player();

        void effects(TransitionEdit edit, int side, double progress) throws Exception;

        void close();
    }

    interface Factory {
        Deck create(Object service, int side) throws Exception;
    }

    static Factory factory = HostDeck::new;
    private static Session held;

    /** BitChord's optimized Media3 ABI groups remote/local values by threshold, not by source. */
    static Object[] bufferArguments(Object allocator) {
        return new Object[] {
            allocator,
            1000,
            1000, // Minimum buffer: remote, local.
            10000,
            10000, // Maximum buffer: remote, local.
            250,
            250, // Start threshold: remote, local.
            500,
            500, // Rebuffer threshold: remote, local.
            4 << 20,
            false,
            0,
            false,
            Collections.emptyMap()
        };
    }

    static String failureDetail(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null)
            failure = failure.getCause();
        // Report the failure class and site without leaking source URLs or account tokens.
        String site = "";
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith("androidx.media3.")
                    || frame.getClassName().startsWith("app.bitchord.")) {
                site = " at " + frame.getClassName() + "." + frame.getMethodName();
                break;
            }
        }
        return failure.getClass().getSimpleName() + site;
    }

    static boolean holding(Object controller) {
        Session session = held;
        return session != null && !session.closed && session.controller == controller;
    }

    static Session existing(TransitionRuntime.Pair pair) {
        return held != null && !held.closed && held.pair.recording().equals(pair.recording())
                ? held
                : null;
    }

    static Session acquire(TransitionRuntime.Pair pair) throws Exception {
        if (!AutomixAccess.enabled())
            throw new IllegalStateException("Enable Automix in Playback settings first");
        Session previous = existing(pair);
        if (previous != null && previous.unchanged()) return previous;
        if (held != null) held.close(false);
        return new Session(pair);
    }

    static void releaseService() {
        if (held != null) held.close(false);
    }

    static final class Session {
        final TransitionRuntime.Pair pair;
        final Object service, controller;
        final Player main;
        final List<String> queue;
        final int index;
        final long position;
        final boolean playing;
        final PlaybackParameters parameters;
        final float volume;
        private WeakReference<Object> editor = new WeakReference<>(null);
        Audition audition;
        boolean closed, applied;
        private long checkedAt;

        Session(TransitionRuntime.Pair pair) throws Exception {
            TransitionRuntime.Pair current = TransitionRuntime.pair();
            if (current == null
                    || current.index != pair.index
                    || !current.recording().equals(pair.recording()))
                throw new IllegalStateException("The queue changed; reopen this transition");
            this.pair = pair;
            service = SourceAutomix.owner();
            controller = TransitionRuntime.field(service, "crossfade");
            if (Boolean.TRUE.equals(
                    TransitionRuntime.call(controller, "isTransitioning", new Class<?>[0])))
                throw new IllegalStateException("Wait for the current transition to finish");
            main = (Player) TransitionRuntime.player();
            queue = TransitionRuntime.queue(main);
            index = main.getCurrentMediaItemIndex();
            playing = main.getPlayWhenReady();
            parameters = main.getPlaybackParameters();
            volume = Math.max(0, Math.min(1, main.getVolume()));
            held = this;
            try {
                main.pause();
                position = main.getCurrentPosition();
            } catch (RuntimeException failure) {
                held = null;
                throw failure;
            }
        }

        void attach(Object owner) {
            editor = new WeakReference<>(owner);
        }

        boolean ownedBy(Object owner) {
            return editor.get() == owner;
        }

        /** Per-tick check: the full queue walk is O(queue), so it runs once a second. */
        boolean unchangedQuick() {
            long now = SystemClock.elapsedRealtime();
            if (now - checkedAt >= 1000) {
                checkedAt = now;
                return unchanged();
            }
            try {
                return !closed
                        && SourceAutomix.owner() == service
                        && TransitionRuntime.player() == main
                        && main.getCurrentMediaItemIndex() == index
                        && main.getMediaItemCount() == queue.size()
                        && sameAt(index)
                        && sameAt(index + 1);
            } catch (Exception unavailable) {
                return false;
            }
        }

        private boolean sameAt(int at) {
            if (at < 0 || at >= queue.size()) return true;
            MediaItem item = main.getMediaItemAt(at);
            return queue.get(at).equals(item.mediaId + "|" + SourceFirst.renditionUri(item));
        }

        boolean unchanged() {
            try {
                return !closed
                        && SourceAutomix.owner() == service
                        && TransitionRuntime.player() == main
                        && main.getCurrentMediaItemIndex() == index
                        && queue.equals(TransitionRuntime.queue(main));
            } catch (Exception unavailable) {
                return false;
            }
        }

        Audition start(TransitionEdit edit, Runnable done) throws Exception {
            if (!unchanged() || main.getPlayWhenReady())
                throw new IllegalStateException("The queue changed; reopen this transition");
            if (audition != null) audition.stop();
            audition = new Audition(this, edit, done);
            return audition;
        }

        void close(boolean resume) {
            if (closed) return;
            if (audition != null) audition.stop();
            boolean restore =
                    resume
                            && unchanged()
                            && !main.getPlayWhenReady()
                            && Math.abs(main.getCurrentPosition() - position) <= 250;
            closed = true;
            if (held == this) held = null;
            editor.clear();
            if (restore) {
                // No seek: the real queue has remained paused at this exact position throughout.
                try {
                    TransitionRuntime.pcall(
                            main, "setPlayWhenReady", new Class<?>[] {boolean.class}, playing);
                } catch (Exception unavailable) {
                    HostLog.d("AutoMix could not restore listening state");
                }
            }
        }
    }

    static final class Audition {
        final Session session;
        final TransitionEdit draft;
        final Deck[] decks = new Deck[2];
        final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::step;
        private Runnable done;
        private final long deadline;
        private AudioManager focusManager;
        private AudioFocusRequest focus;
        private boolean started, incomingStarted, buffering;
        boolean closed, completed;
        String error;
        double progress = Double.NaN;

        Audition(Session session, TransitionEdit edit, Runnable done) throws Exception {
            edit.validate();
            this.session = session;
            draft = edit.copy();
            this.done = done;
            float rate = AutomixAccess.speed();
            if (!Float.isFinite(rate) || rate <= 0) rate = session.parameters.speed;
            deadline =
                    SystemClock.elapsedRealtime()
                            + 30000
                            + Math.round((draft.overlap + 3) / rate * 1000);
            try {
                focusManager = SoundExtras.app().getSystemService(AudioManager.class);
                focus =
                        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                .setAudioAttributes(
                                        new android.media.AudioAttributes.Builder()
                                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                                .setContentType(
                                                        android.media.AudioAttributes
                                                                .CONTENT_TYPE_MUSIC)
                                                .build())
                                .setOnAudioFocusChangeListener(
                                        change -> {
                                            if (change != AudioManager.AUDIOFOCUS_GAIN) stop();
                                        },
                                        handler)
                                .build();
                if (focusManager == null
                        || focusManager.requestAudioFocus(focus)
                                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    throw new IllegalStateException("Preview audio focus is unavailable");
                for (int side = 0; side < 2; side++) {
                    Deck deck = decks[side] = factory.create(session.service, side);
                    Player player = deck.player();
                    player.setAudioAttributes(session.main.getAudioAttributes(), false);
                    TransitionRuntime.pcall(player, "setRepeatMode", new Class<?>[] {int.class}, 0);
                    TransitionRuntime.pcall(
                            player, "setShuffleModeEnabled", new Class<?>[] {boolean.class}, false);
                    TransitionRuntime.pcall(
                            player,
                            "setMediaItem",
                            new Class<?>[] {MediaItem.class},
                            side == 0 ? session.pair.outgoing : session.pair.incoming);
                    player.setPlaybackParameters(
                            new PlaybackParameters(rate, session.parameters.pitch));
                    player.setVolume(side == 0 ? session.volume : 0);
                    player.seekTo(
                            0,
                            Math.round(
                                    (side == 0
                                                    ? Math.max(
                                                            0,
                                                            draft.start(
                                                                            session.pair
                                                                                    .outgoingDuration)
                                                                    - 2)
                                                    : draft.incomingCue)
                                            * 1000));
                    deck.effects(draft, side, 0);
                    player.prepare();
                }
                handler.post(tick);
            } catch (Exception | LinkageError failure) {
                releaseDecks();
                this.done = null;
                closed = true;
                throw failure;
            }
        }

        private void step() {
            if (closed) return;
            try {
                if (!session.unchangedQuick() || session.main.getPlayWhenReady()) {
                    session.close(false);
                    return;
                }
                if (SystemClock.elapsedRealtime() > deadline)
                    throw new IllegalStateException("Preview timed out");
                Player a = decks[0].player(), b = decks[1].player();
                for (Deck deck : decks) {
                    Object failure =
                            TransitionRuntime.pcall(
                                    deck.player(), "getPlayerError", new Class<?>[0]);
                    if (failure != null)
                        throw new IllegalStateException("Preview source is unavailable");
                }
                double position = a.getCurrentPosition() / 1000d;
                double start = draft.start(session.pair.outgoingDuration);
                progress = (position - start) / draft.overlap;
                if (started && incomingStarted && progress >= 1) {
                    completed = true;
                    stop();
                    return;
                }
                if (a.getPlaybackState() == Player.STATE_ENDED
                        || b.getPlaybackState() == Player.STATE_ENDED)
                    throw new IllegalStateException("Not enough preview audio");
                // The outgoing lead-in plays while the incoming deck is still loading; both
                // wait together only if the incoming deck is not ready when it is needed.
                boolean needB = incomingStarted || position >= start - .05;
                boolean ready =
                        a.getPlaybackState() == Player.STATE_READY
                                && (!needB || b.getPlaybackState() == Player.STATE_READY);
                if (!ready) {
                    if (started && !buffering) {
                        a.pause();
                        b.pause();
                        buffering = true;
                    }
                    handler.postDelayed(tick, 30);
                    return;
                }
                if (!started) {
                    a.play();
                    started = true;
                } else if (buffering) {
                    a.play();
                    if (incomingStarted) b.play();
                    buffering = false;
                } else if (!a.getPlayWhenReady() || (incomingStarted && !b.getPlayWhenReady())) {
                    stop();
                    return;
                }
                if (!incomingStarted && position >= start) {
                    long target =
                            Math.round((draft.incomingCue + Math.max(0, position - start)) * 1000);
                    // The prepared deck already sits at the cue; seeking again restarts its load.
                    if (Math.abs(b.getCurrentPosition() - target) > 150) b.seekTo(0, target);
                    incomingStarted = true;
                    b.play();
                }
                double p = Math.max(0, Math.min(1, progress));
                a.setVolume(session.volume * (float) draft.gain(0, p));
                b.setVolume(incomingStarted ? session.volume * (float) draft.gain(1, p) : 0);
                for (int side = 0; side < 2; side++) decks[side].effects(draft, side, p);
                handler.postDelayed(tick, 16);
            } catch (Exception | LinkageError failure) {
                error = failureDetail(failure);
                HostLog.d("AutoMix preview failed: " + error);
                stop();
            }
        }

        double progress() {
            return closed ? Double.NaN : progress;
        }

        boolean playing() {
            return !closed && started && !buffering;
        }

        void stop() {
            if (closed) return;
            closed = true;
            handler.removeCallbacks(tick);
            releaseDecks();
            if (session.audition == this) session.audition = null;
            Runnable notify = done;
            done = null;
            if (notify != null) notify.run();
        }

        private void releaseDecks() {
            for (Deck deck : decks) if (deck != null) deck.close();
            if (focusManager != null && focus != null) {
                focusManager.abandonAudioFocusRequest(focus);
                focus = null;
            }
        }
    }

    /**
     * Fresh host renderers/processors and bounded buffers, with no main-queue listeners/session.
     */
    private static final class HostDeck implements Deck {
        final Player player;
        final Object processor;
        private boolean closed;

        HostDeck(Object service, int side) throws Exception {
            Context app = SoundExtras.app();
            String base = "com.music.bitchord.playback.";
            Class<?> spatial = Class.forName(base + "SpatialAudioProcessor"),
                    equalizer = Class.forName(base + "EqualizerProcessor"),
                    transition = Class.forName(base + "TransitionFilterProcessor"),
                    loudness = Class.forName(base + "LoudnessProcessor");
            processor = transition.getConstructor().newInstance();
            Object previewSpatial = spatial.getConstructor().newInstance(),
                    previewEqualizer = equalizer.getConstructor().newInstance(),
                    previewLoudness = loudness.getConstructor().newInstance();
            SourceAutomix.set(
                    previewSpatial,
                    "enabled",
                    TransitionRuntime.field(
                            TransitionRuntime.field(service, "spatialAudioProcessorA"), "enabled"));
            SourceAutomix.set(
                    previewEqualizer,
                    "target",
                    TransitionRuntime.field(
                            TransitionRuntime.field(service, "equalizerProcessorA"), "target"));
            SourceAutomix.set(
                    previewLoudness,
                    "gainFor",
                    TransitionRuntime.field(
                            TransitionRuntime.field(service, "loudnessA"), "gainFor"));
            TransitionRuntime.Pair currentPair = TransitionRuntime.pair();
            if (currentPair != null)
                SourceAutomix.set(
                        previewLoudness,
                        "mediaId",
                        side == 0 ? currentPair.outgoingId : currentPair.incomingId);
            Object renderers =
                    Class.forName(base + "PlaybackService$silenceSkippingRenderers$1")
                            .getConstructor(
                                    service.getClass(), spatial, equalizer, transition, loudness)
                            .newInstance(
                                    service,
                                    previewSpatial,
                                    previewEqualizer,
                                    processor,
                                    previewLoudness);
            Class<?> builderType = Class.forName("androidx.media3.exoplayer.ExoPlayer$Builder");
            Object builder = builderType.getConstructor(Context.class).newInstance(app);
            builderType.getField("renderersFactorySupplier").set(builder, supplier(renderers));
            Class<?> allocator =
                    Class.forName("androidx.media3.exoplayer.upstream.DefaultAllocator");
            Object control =
                    Class.forName("androidx.media3.exoplayer.DefaultLoadControl")
                            .getConstructor(
                                    allocator,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    int.class,
                                    boolean.class,
                                    int.class,
                                    boolean.class,
                                    Map.class)
                            .newInstance(bufferArguments(allocator.getConstructor().newInstance()));
            builderType.getField("loadControlSupplier").set(builder, supplier(control));
            builderType
                    .getMethod(
                            "setMediaSourceFactory",
                            Class.forName("androidx.media3.exoplayer.source.MediaSource$Factory"))
                    .invoke(builder, TransitionRuntime.field(service, "mediaSourceFactory"));
            builderType.getField("handleAudioFocus").setBoolean(builder, false);
            builderType.getField("handleAudioBecomingNoisy").setBoolean(builder, true);
            player = (Player) builderType.getMethod("build").invoke(builder);
        }

        private static Object supplier(Object value) throws Exception {
            Class<?> type = Class.forName("com.google.common.base.Supplier");
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    (proxy, method, args) -> {
                        if (method.getName().equals("get")) return value;
                        if (method.getName().equals("equals")) return proxy == args[0];
                        if (method.getName().equals("hashCode"))
                            return System.identityHashCode(proxy);
                        return "AutoMix audition";
                    });
        }

        public Player player() {
            return player;
        }

        public void effects(TransitionEdit edit, int side, double progress) throws Exception {
            float[] hz = edit.cutoffs(side, progress);
            SourceAutomix.set(processor, "targetLowPassHz", hz[0]);
            SourceAutomix.set(processor, "targetHighPassHz", Math.min(2000, hz[1]));
            TransitionRuntime.previewProcessor(processor, edit, side);
        }

        public void close() {
            if (closed) return;
            closed = true;
            TransitionRuntime.detachProcessor(processor);
            try {
                Class.forName("androidx.media3.exoplayer.ExoPlayer")
                        .getMethod("release")
                        .invoke(player);
            } catch (Exception | LinkageError unavailable) {
                HostLog.d("AutoMix could not release an audition deck");
            }
        }
    }
}
