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

        /** The native blend headroom for this private deck; 1 leaves the level untouched. */
        default void trim(float gain) throws Exception {}

        void close();
    }

    /** BitChord's blend headroom: the summed faders are scaled back once they exceed unity. */
    static float headroom(double outgoing, double incoming) {
        double sum = outgoing + incoming;
        return sum > 1 ? (float) (1 / Math.sqrt(sum)) : 1f;
    }

    /** Longer blends are heard around their middle instead of from end to end. */
    private static final double HEARD_SECONDS = 12;

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

    /** Class name only, safe to log. */
    static String failureClass(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null)
            failure = failure.getCause();
        String name = failure.getClass().getSimpleName();
        return name.isEmpty() ? "unknown" : name;
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

    // Timing handed to the next audition: the tap, and how the session was obtained.
    private static long tapAt = -1, acquireMs = -1;
    private static boolean acquireReused;

    /** Marks the user's tap so the audition can report tap-to-start. */
    static void tapped() {
        tapAt = SystemClock.elapsedRealtime();
    }

    static Session acquire(TransitionRuntime.Pair pair) throws Exception {
        if (!AutomixAccess.enabled())
            throw new IllegalStateException("Enable Automix in Playback settings first");
        long began = SystemClock.elapsedRealtime();
        Session previous = existing(pair);
        acquireReused = previous != null && previous.unchanged();
        if (acquireReused) {
            acquireMs = SystemClock.elapsedRealtime() - began;
            return previous;
        }
        if (held != null) held.close(false);
        Session fresh = new Session(pair);
        acquireMs = SystemClock.elapsedRealtime() - began;
        return fresh;
    }

    static void releaseService() {
        if (held != null) held.close(false);
    }

    static final class Session {
        private static final String CHANGED = "The queue changed; reopen this transition";
        private static final String ENTRY_KEY = "bitchord.queueEntryId",
                TIER_KEY = "bitchord.queueTier",
                AUTOPLAY_KEY = "bitchord.fromAutoplay",
                CYCLE_KEY = "bitchord.contextCycleId",
                CANONICAL_KEY = "bitchord.canonicalIndex";

        /** The pair the queue held when this session started; the selection never replaces it. */
        final TransitionRuntime.Pair pair;

        final Object service, controller;
        final Player main;
        final List<String> queue, entries;
        final int index, nextIndex, repeat;
        final long position;
        final boolean playing, exoShuffle, queueShuffle;
        final PlaybackParameters parameters;
        final float volume;
        private final List<MediaItem> items;
        private final MediaItem outgoingWritten, incomingWritten;
        private final boolean provisional;
        private final long settleBy;
        private TransitionRuntime.Pair selected;
        private WeakReference<Object> editor = new WeakReference<>(null);
        Audition audition;
        boolean closed, applied;
        private boolean writing, closeLater, resumeLater;
        private long checkedAt;

        Session(TransitionRuntime.Pair pair) throws Exception {
            TransitionRuntime.Pair current = TransitionRuntime.pair();
            if (current == null
                    || current.index != pair.index
                    || current.nextIndex != pair.nextIndex
                    || !current.recording().equals(pair.recording()))
                throw new IllegalStateException(CHANGED);
            this.pair = pair;
            selected = pair;
            service = SourceAutomix.owner();
            controller = TransitionRuntime.field(service, "crossfade");
            if (Boolean.TRUE.equals(
                    TransitionRuntime.call(controller, "isTransitioning", new Class<?>[0])))
                throw new IllegalStateException("Wait for the current transition to finish");
            main = (Player) TransitionRuntime.player();
            items = snapshot(main);
            queue = keys(items);
            entries = entryIds(items);
            index = main.getCurrentMediaItemIndex();
            nextIndex = pair.nextIndex;
            if (nextIndex < 0 || nextIndex >= items.size() || nextIndex == index)
                throw new IllegalStateException(CHANGED);
            playing = main.getPlayWhenReady();
            parameters = main.getPlaybackParameters();
            volume = Math.max(0, Math.min(1, main.getVolume()));
            repeat = main.getRepeatMode();
            exoShuffle = main.getShuffleModeEnabled();
            queueShuffle = DjMode.queueShuffleOn();
            outgoingWritten = incomingWritten = null;
            provisional = false;
            settleBy = 0;
            held = this;
            try {
                main.pause();
                position = main.getCurrentPosition();
            } catch (RuntimeException failure) {
                held = null;
                throw failure;
            }
        }

        /** The same paused listening state after the pair's queue slots were written. */
        private Session(
                Session from,
                TransitionRuntime.Pair pair,
                MediaItem outgoingWritten,
                MediaItem incomingWritten,
                long position,
                boolean provisional) {
            this.pair = pair;
            selected = pair;
            service = from.service;
            controller = from.controller;
            main = from.main;
            items = snapshot(main);
            queue = keys(items);
            entries = entryIds(items);
            index = from.index;
            nextIndex = from.nextIndex;
            repeat = from.repeat;
            exoShuffle = from.exoShuffle;
            queueShuffle = from.queueShuffle;
            playing = from.playing;
            parameters = from.parameters;
            volume = from.volume;
            this.position = position;
            this.outgoingWritten = outgoingWritten;
            this.incomingWritten = incomingWritten;
            this.provisional = provisional;
            settleBy = SystemClock.elapsedRealtime() + 30000;
            editor = from.editor;
        }

        void attach(Object owner) {
            editor = new WeakReference<>(owner);
        }

        boolean ownedBy(Object owner) {
            return editor.get() == owner;
        }

        TransitionRuntime.Pair selectedPair() {
            return selected;
        }

        /** Preview ownership only: a pair this session was given, never permission to apply it. */
        boolean ownsSelection(TransitionRuntime.Pair candidate) {
            return isHeld() && candidate != null && candidate == selected;
        }

        boolean isHeld() {
            return !closed && held == this;
        }

        /** Chooses the directed pair to audition; the real queue is not touched. */
        void selectPair(TransitionRuntime.Pair candidate) {
            if (!isHeld() || writing) throw new IllegalStateException(CHANGED);
            // A different pair waits until settle has rebased, so it is never discarded by it.
            if (provisional && candidate != selected)
                throw new IllegalStateException(Strings.t("Wait for the pair to finish loading"));
            if (candidate == null
                    || candidate.outgoing == null
                    || candidate.incoming == null
                    || candidate.index != index
                    || candidate.nextIndex != nextIndex
                    || !candidate.outgoingId.equals(candidate.outgoing.mediaId)
                    || !candidate.incomingId.equals(candidate.incoming.mediaId)
                    || candidate.outgoingId.equals(candidate.incomingId)
                    || !usable(candidate.outgoingDuration)
                    || !usable(candidate.incomingDuration))
                throw new IllegalArgumentException(Strings.t("This pair cannot be previewed"));
            cancelAudition();
            selected = candidate;
        }

        private static boolean usable(double duration) {
            return duration > 0 && !Double.isInfinite(duration);
        }

        /** Stops the audition without telling the editor, which is the one that asked. */
        private void cancelAudition() {
            Audition running = audition;
            if (running == null) return;
            running.done = null;
            running.stop();
        }

        /**
         * Replaces the current and actual next queue slots with the selected pair. Every other
         * slot, the shuffle order, repeat mode and listening settings stay exactly as they were.
         */
        Session usePair(TransitionRuntime.Pair candidate) throws Exception {
            if (writing) throw new IllegalStateException(Strings.t("The queue is being changed"));
            if (!ownsSelection(candidate)) throw new IllegalStateException(CHANGED);
            if (DjMode.inParty())
                throw new IllegalStateException(
                        Strings.t("Leave Listen Together to change the queue"));
            if (main.getShuffleModeEnabled())
                throw new IllegalStateException(Strings.t("Turn shuffle off to change the queue"));
            cancelAudition();
            if (!baselineHolds()) throw new IllegalStateException(CHANGED);
            boolean changeOutgoing = !queue.get(index).equals(key(candidate.outgoing)),
                    changeIncoming = !queue.get(nextIndex).equals(key(candidate.incoming));
            if (!changeOutgoing && !changeIncoming) {
                if (candidate == pair || candidate.recording().equals(pair.recording()))
                    return this;
                // Same songs with fuller metadata: no queue write, only a canonical rebase.
                TransitionRuntime.Pair same = rebound(candidate);
                same.outgoing = items.get(index);
                same.incoming = items.get(nextIndex);
                return rebase(same, null, null, position, true);
            }
            MediaItem outgoingItem =
                    changeOutgoing ? occurrence(candidate.outgoing, items.get(index)) : null;
            MediaItem incomingItem =
                    changeIncoming ? occurrence(candidate.incoming, items.get(nextIndex)) : null;
            boolean wroteOutgoing = false, wroteIncoming = false;
            writing = true;
            try {
                // Each step is checked before the next one, so a listener that reacted to the
                // previous write is never written over.
                if (changeIncoming) {
                    wroteIncoming = true;
                    main.replaceMediaItem(nextIndex, incomingItem);
                    if (!landed(null, incomingItem, true)) throw new IllegalStateException(CHANGED);
                }
                if (changeOutgoing) {
                    wroteOutgoing = true;
                    main.replaceMediaItem(index, outgoingItem);
                    if (!landed(outgoingItem, incomingItem, false)
                            || main.getCurrentPosition() != 0)
                        throw new IllegalStateException(CHANGED);
                }
                main.prepare();
                if (!landed(outgoingItem, incomingItem, !changeOutgoing)
                        || (changeOutgoing && main.getCurrentPosition() != 0))
                    throw new IllegalStateException(CHANGED);
                TransitionRuntime.Pair rebound = rebound(candidate);
                rebound.outgoing = changeOutgoing ? outgoingItem : items.get(index);
                rebound.incoming = changeIncoming ? incomingItem : items.get(nextIndex);
                Session next =
                        rebase(
                                rebound,
                                outgoingItem,
                                incomingItem,
                                changeOutgoing ? 0 : position,
                                true);
                writing = false;
                if (closeLater) next.close(resumeLater);
                return next;
            } catch (RuntimeException | Error failure) {
                // Newest write first; a slot is only restored while it still holds what was
                // written.
                if (wroteOutgoing) restore(index, outgoingItem);
                if (wroteIncoming) restore(nextIndex, incomingItem);
                if (!baselineHolds()) {
                    closeLater = true;
                    resumeLater = false;
                }
                throw failure;
            } finally {
                boolean pendingClose = writing && closeLater && !closed;
                writing = false;
                if (pendingClose) close(resumeLater);
            }
        }

        /**
         * Rebases onto the native pair once the played song's duration is known. Returns this
         * session while that is still pending; callers poll without waiting.
         */
        Session settle() throws Exception {
            if (!provisional) return this;
            if (!isHeld() || writing || !baselineHolds()) throw new IllegalStateException(CHANGED);
            if (main.getDuration() <= 0) {
                if (SystemClock.elapsedRealtime() > settleBy)
                    throw new IllegalStateException(Strings.t("The track length is not available"));
                return this;
            }
            TransitionRuntime.Pair live = TransitionRuntime.pair();
            if (live == null
                    || live.index != index
                    || live.nextIndex != nextIndex
                    || !live.outgoingId.equals(pair.outgoingId)
                    || !live.incomingId.equals(pair.incomingId))
                throw new IllegalStateException(CHANGED);
            if (!usable(live.outgoingDuration) || !usable(live.incomingDuration))
                throw new IllegalStateException(Strings.t("The track length is not available"));
            return rebase(live, null, null, position, false);
        }

        private TransitionRuntime.Pair rebound(TransitionRuntime.Pair candidate) {
            return new TransitionRuntime.Pair(
                    candidate.outgoingId,
                    candidate.incomingId,
                    candidate.outgoingTitle,
                    candidate.incomingTitle,
                    candidate.outgoingDuration,
                    candidate.incomingDuration,
                    index,
                    nextIndex);
        }

        private Session rebase(
                TransitionRuntime.Pair onto,
                MediaItem outgoingItem,
                MediaItem incomingItem,
                long at,
                boolean pending) {
            Session next = new Session(this, onto, outgoingItem, incomingItem, at, pending);
            held = next;
            closed = true;
            editor = new WeakReference<>(null);
            return next;
        }

        private static List<MediaItem> snapshot(Player player) {
            int count = player.getMediaItemCount();
            List<MediaItem> out = new ArrayList<>(count);
            for (int at = 0; at < count; at++) out.add(player.getMediaItemAt(at));
            return Collections.unmodifiableList(out);
        }

        private static List<String> keys(List<MediaItem> items) {
            List<String> out = new ArrayList<>(items.size());
            for (MediaItem item : items) out.add(key(item));
            return Collections.unmodifiableList(out);
        }

        private static List<String> entryIds(List<MediaItem> items) {
            List<String> out = new ArrayList<>(items.size());
            for (MediaItem item : items) out.add(entry(item));
            return Collections.unmodifiableList(out);
        }

        private static String key(MediaItem item) {
            return item.mediaId + "|" + SourceFirst.renditionUri(item);
        }

        private static String entry(MediaItem item) {
            String value =
                    item.mediaMetadata.extras == null
                            ? null
                            : item.mediaMetadata.extras.getString(ENTRY_KEY);
            return value == null ? "" : value;
        }

        private static android.net.Uri rawUri(MediaItem item) {
            return item.localConfiguration == null ? null : item.localConfiguration.uri;
        }

        /** BitChord's own watch address with its queue-entry tag; providers' queries are theirs. */
        private static boolean tagged(android.net.Uri uri) {
            return uri != null
                    && "bitchord".equals(uri.getScheme())
                    && "watch".equals(uri.getAuthority())
                    && uri.isHierarchical()
                    && uri.getQueryParameter(SourcePreparation.ENTRY) != null;
        }

        private static android.net.Uri plain(android.net.Uri uri) {
            return tagged(uri) ? SourcePreparation.withoutEntry(uri) : uri;
        }

        /**
         * Whether a live slot is the occurrence written there. A reused entry id alone is not
         * enough: the song and its source, apart from the host's entry tag, must also match.
         */
        private static boolean sameWritten(MediaItem live, MediaItem written) {
            if (live == written) return true;
            if (live == null || written == null) return false;
            String id = entry(written);
            if (id.isEmpty() || !id.equals(entry(live)) || !written.mediaId.equals(live.mediaId))
                return false;
            return Objects.equals(plain(rawUri(live)), plain(rawUri(written)))
                    || Objects.equals(
                            SourceFirst.renditionUri(live), SourceFirst.renditionUri(written));
        }

        /** The queue entry for a song: a fresh occurrence in the slot it takes over. */
        private static MediaItem occurrence(MediaItem source, MediaItem slot) {
            try {
                android.os.Bundle extras =
                        source.mediaMetadata.extras == null
                                ? new android.os.Bundle()
                                : new android.os.Bundle(source.mediaMetadata.extras);
                android.os.Bundle target = slot.mediaMetadata.extras;
                extras.putString(ENTRY_KEY, UUID.randomUUID().toString());
                extras.remove(CANONICAL_KEY);
                extras.remove(CYCLE_KEY);
                extras.remove(TIER_KEY);
                extras.remove(AUTOPLAY_KEY);
                if (target != null) {
                    if (target.containsKey(TIER_KEY))
                        extras.putString(TIER_KEY, target.getString(TIER_KEY));
                    if (target.containsKey(AUTOPLAY_KEY))
                        extras.putBoolean(AUTOPLAY_KEY, target.getBoolean(AUTOPLAY_KEY));
                    if (target.containsKey(CYCLE_KEY))
                        extras.putLong(CYCLE_KEY, target.getLong(CYCLE_KEY));
                }
                Object builder = MediaItem.class.getMethod("buildUpon").invoke(source);
                android.net.Uri raw = rawUri(source);
                if (tagged(raw))
                    // The release host's setter returns void. Never chain its reflection result.
                    builder.getClass()
                            .getMethod("setUri", String.class)
                            .invoke(builder, SourcePreparation.withoutEntry(raw).toString());
                Object metadata = source.mediaMetadata;
                Object values = metadata.getClass().getMethod("buildUpon").invoke(metadata);
                Field field = values.getClass().getDeclaredField("extras");
                field.setAccessible(true);
                field.set(values, extras);
                Constructor<?> make = metadata.getClass().getDeclaredConstructor(values.getClass());
                make.setAccessible(true);
                Field slotField = builder.getClass().getDeclaredField("mediaMetadata");
                slotField.setAccessible(true);
                slotField.set(builder, make.newInstance(values));
                return SourceFirst.tag(
                        (MediaItem) builder.getClass().getMethod("build").invoke(builder));
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                throw new IllegalStateException(
                        Strings.t("Could not change the queue"), unavailable);
            }
        }

        private boolean slotHolds(int at, MediaItem outgoingItem, MediaItem incomingItem) {
            if (at < 0 || at >= items.size()) return true;
            MediaItem live = main.getMediaItemAt(at);
            MediaItem written = at == index ? outgoingItem : at == nextIndex ? incomingItem : null;
            if (written != null) return sameWritten(live, written);
            return queue.get(at).equals(key(live)) && entries.get(at).equals(entry(live));
        }

        private boolean slotsHold(MediaItem outgoingItem, MediaItem incomingItem) {
            if (main.getMediaItemCount() != items.size()) return false;
            for (int at = 0; at < items.size(); at++)
                if (!slotHolds(at, outgoingItem, incomingItem)) return false;
            return true;
        }

        /** Listening settings the pair change must leave alone. */
        private boolean settingsHold(boolean positionToo) {
            return main.getNextMediaItemIndex() == nextIndex
                    && main.getRepeatMode() == repeat
                    && main.getShuffleModeEnabled() == exoShuffle
                    && DjMode.queueShuffleOn() == queueShuffle
                    && !main.getPlayWhenReady()
                    && parameters.equals(main.getPlaybackParameters())
                    && volume == Math.max(0, Math.min(1, main.getVolume()))
                    && (!positionToo || Math.abs(main.getCurrentPosition() - position) <= 250);
        }

        private boolean baselineHolds() {
            try {
                return unchanged()
                        && !Boolean.TRUE.equals(
                                TransitionRuntime.call(
                                        controller, "isTransitioning", new Class<?>[0]))
                        && settingsHold(true);
            } catch (Exception unavailable) {
                return false;
            }
        }

        private boolean landed(
                MediaItem outgoingItem, MediaItem incomingItem, boolean positionToo) {
            try {
                return !closed
                        && SourceAutomix.owner() == service
                        && TransitionRuntime.player() == main
                        && main.getCurrentMediaItemIndex() == index
                        && slotsHold(outgoingItem, incomingItem)
                        && settingsHold(positionToo)
                        && !DjMode.inParty();
            } catch (Exception unavailable) {
                return false;
            }
        }

        private void restore(int at, MediaItem written) {
            try {
                if (SourceAutomix.owner() != service
                        || TransitionRuntime.player() != main
                        || DjMode.inParty()
                        || main.getMediaItemCount() != items.size()
                        || main.getCurrentMediaItemIndex() != index
                        || !sameWritten(main.getMediaItemAt(at), written)) return;
                // The current slot is only put back while nobody has acted on playback since.
                if (at == index && (!settingsHold(false) || main.getCurrentPosition() != 0)) return;
                main.replaceMediaItem(at, items.get(at));
                if (at == index) main.seekTo(index, position);
            } catch (Exception unavailable) {
                HostLog.d("AutoMix could not restore a queue entry");
            }
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
                        && slotHolds(index, outgoingWritten, incomingWritten)
                        && slotHolds(nextIndex, outgoingWritten, incomingWritten);
            } catch (Exception unavailable) {
                return false;
            }
        }

        boolean unchanged() {
            try {
                return !closed
                        && SourceAutomix.owner() == service
                        && TransitionRuntime.player() == main
                        && main.getCurrentMediaItemIndex() == index
                        && slotsHold(outgoingWritten, incomingWritten);
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
            if (writing) {
                // The queue write finishes first; its caller closes the session that results.
                resumeLater = closeLater ? resumeLater && resume : resume;
                closeLater = true;
                return;
            }
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
        final TransitionRuntime.Pair pair;
        final float rate;
        final Deck[] decks = new Deck[2];
        final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::step;
        private Runnable done;
        private final long deadline;
        private final double begin, end;
        private AudioManager focusManager;
        private AudioFocusRequest focus;
        // Ranks the audition above speculative preparation and background reads while it lives.
        private AutoCloseable priority;
        private final java.util.List<AutoCloseable> streamTags = new java.util.ArrayList<>();
        private boolean started, incomingStarted, buffering;
        // Milliseconds since construction began; -1 until observed.
        private final long created = SystemClock.elapsedRealtime();
        // Tap, session, deck, seek and prepare milliseconds; -1 when not reached.
        private long tapMs = -1, sessionMs = acquireMs, decksMs = -1, seekMs = -1, prepareMs = -1;
        private final boolean sessionReused = acquireReused;
        private long constructed = -1, outReady = -1, inReady = -1, outPlay = -1, inPlay = -1;
        private long stallStart, stallMs;
        private int stalls;
        // Why it ended and, for a failed deck, which side and the player's numeric error code.
        private String reason = "stopped", failedSide = "none", cause = "none";
        private int failedCode = -1;
        boolean closed, completed;
        String error;
        double progress = Double.NaN;

        Audition(Session session, TransitionEdit edit, Runnable done) throws Exception {
            edit.validate();
            if (tapAt >= 0) tapMs = SystemClock.elapsedRealtime() - tapAt;
            tapAt = -1;
            this.session = session;
            draft = edit.copy();
            pair = session.selectedPair();
            this.done = done;
            priority = PlaybackPriority.preview("transition-preview");
            double length = draft.sourceSpan(0);
            begin = length > HEARD_SECONDS ? .5 - HEARD_SECONDS / 2 / length : 0;
            end = length > HEARD_SECONDS ? .5 + HEARD_SECONDS / 2 / length : 1;
            float requested = AutomixAccess.speed();
            rate =
                    Float.isFinite(requested) && requested > 0
                            ? requested
                            : session.parameters.speed;
            deadline =
                    SystemClock.elapsedRealtime()
                            + 30000
                            + Math.round((draft.overlap + 3) / rate * 1000);
            try {
                for (MediaItem item : new MediaItem[] {pair.outgoing, pair.incoming}) {
                    streamTags.add(TelegramStreamContext.preview(item.mediaId));
                    if (item.localConfiguration != null)
                        streamTags.add(
                                TelegramStreamContext.preview(
                                        item.localConfiguration.uri.toString()));
                }
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
                                            if (change != AudioManager.AUDIOFOCUS_GAIN) {
                                                reason = "focus-lost";
                                                stop();
                                            }
                                        },
                                        handler)
                                .build();
                if (focusManager == null
                        || focusManager.requestAudioFocus(focus)
                                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    throw new IllegalStateException("Preview audio focus is unavailable");
                long made = 0, seeks = 0, prepares = 0, mark;
                for (int side = 0; side < 2; side++) {
                    mark = SystemClock.elapsedRealtime();
                    Deck deck = decks[side] = factory.create(session.service, side);
                    made += SystemClock.elapsedRealtime() - mark;
                    Player player = deck.player();
                    player.setAudioAttributes(session.main.getAudioAttributes(), false);
                    TransitionRuntime.pcall(player, "setRepeatMode", new Class<?>[] {int.class}, 0);
                    TransitionRuntime.pcall(
                            player, "setShuffleModeEnabled", new Class<?>[] {boolean.class}, false);
                    TransitionRuntime.pcall(
                            player,
                            "setMediaItem",
                            new Class<?>[] {MediaItem.class},
                            side == 0 ? pair.outgoing : pair.incoming);
                    player.setPlaybackParameters(
                            new PlaybackParameters(
                                    rate * (side == 0 ? 1f : (float) draft.overlapRate[1]),
                                    session.parameters.pitch));
                    player.setVolume(side == 0 ? session.volume : 0);
                    mark = SystemClock.elapsedRealtime();
                    player.seekTo(
                            0,
                            Math.round(
                                    (side == 0
                                                    ? Math.max(0, entry() - 2)
                                                    : draft.incomingCue
                                                            + begin
                                                                    * draft.sourceSpan(0)
                                                                    * draft.overlapRate[1]
                                                                    / draft.overlapRate[0])
                                            * 1000));
                    seeks += SystemClock.elapsedRealtime() - mark;
                    deck.effects(draft, side, 0);
                    mark = SystemClock.elapsedRealtime();
                    player.prepare();
                    prepares += SystemClock.elapsedRealtime() - mark;
                }
                decksMs = made;
                seekMs = seeks;
                prepareMs = prepares;
                constructed = since();
                handler.post(tick);
            } catch (Exception | LinkageError failure) {
                releaseDecks();
                this.done = null;
                closed = true;
                cause = failureClass(failure);
                report("setup-failed");
                throw failure;
            }
        }

        private long since() {
            return SystemClock.elapsedRealtime() - created;
        }

        /** One line of counts and milliseconds, with no track, source or account details. */
        private void report(String end) {
            HostLog.d(
                    "AutoMix preview timing: tapMs="
                            + tapMs
                            + " sessionMs="
                            + sessionMs
                            + " sessionReused="
                            + sessionReused
                            + " decksMs="
                            + decksMs
                            + " seekMs="
                            + seekMs
                            + " prepareMs="
                            + prepareMs
                            // The host's source resolver and verifier are not observable here.
                            + " resolveMs=unavailable verifyMs=unavailable ctorMs="
                            + constructed
                            + " outReadyMs="
                            + outReady
                            + " inReadyMs="
                            + inReady
                            + " outPlayMs="
                            + outPlay
                            + " inPlayMs="
                            + inPlay
                            + " stalls="
                            + stalls
                            + " stallMs="
                            + stallMs
                            + " source="
                            + failedSide
                            + " code="
                            + failedCode
                            + " cause="
                            + cause
                            + " end="
                            + end);
        }

        /** Side, numeric error code and cause class of a deck's player error; no source details. */
        private void sourceFailed(int side, Object failure) {
            failedSide = side == 0 ? "outgoing" : "incoming";
            try {
                failedCode = failure.getClass().getField("errorCode").getInt(failure);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                failedCode = -1;
            }
            Throwable inner =
                    failure instanceof Throwable ? ((Throwable) failure).getCause() : null;
            cause = inner == null ? "none" : failureClass(inner);
        }

        /** Outgoing position, in seconds, where the heard part of the blend begins. */
        private double entry() {
            return draft.start(pair.outgoingDuration) + begin * draft.sourceSpan(0);
        }

        private void step() {
            if (closed) return;
            try {
                if (!session.unchangedQuick()) {
                    reason = "queue-changed";
                    session.close(false);
                    return;
                }
                if (session.main.getPlayWhenReady()) {
                    reason = "main-playing";
                    session.close(false);
                    return;
                }
                if (SystemClock.elapsedRealtime() > deadline) {
                    cause = "timeout";
                    throw new IllegalStateException("Preview timed out");
                }
                Player a = decks[0].player(), b = decks[1].player();
                for (int side = 0; side < 2; side++) {
                    Object failure =
                            TransitionRuntime.pcall(
                                    decks[side].player(), "getPlayerError", new Class<?>[0]);
                    if (failure != null) {
                        sourceFailed(side, failure);
                        throw new IllegalStateException("Preview source is unavailable");
                    }
                }
                double position = a.getCurrentPosition() / 1000d;
                double start = draft.start(pair.outgoingDuration);
                progress = (position - start) / draft.sourceSpan(0);
                if (started && incomingStarted && progress >= end) {
                    completed = true;
                    stop();
                    return;
                }
                if (a.getPlaybackState() == Player.STATE_ENDED
                        || b.getPlaybackState() == Player.STATE_ENDED) {
                    cause = "deck-ended";
                    throw new IllegalStateException("Not enough preview audio");
                }
                if (outReady < 0 && a.getPlaybackState() == Player.STATE_READY) outReady = since();
                if (inReady < 0 && b.getPlaybackState() == Player.STATE_READY) inReady = since();
                // The outgoing lead-in plays while the incoming deck is still loading; both
                // wait together only if the incoming deck is not ready when it is needed.
                double heard = entry();
                boolean needB = incomingStarted || position >= heard - .05;
                boolean ready =
                        a.getPlaybackState() == Player.STATE_READY
                                && (!needB || b.getPlaybackState() == Player.STATE_READY);
                if (!ready) {
                    if (started && !buffering) {
                        a.pause();
                        b.pause();
                        buffering = true;
                        stalls++;
                        stallStart = since();
                    }
                    handler.postDelayed(tick, 30);
                    return;
                }
                if (!started) {
                    a.play();
                    started = true;
                    outPlay = since();
                } else if (buffering) {
                    a.play();
                    if (incomingStarted) b.play();
                    buffering = false;
                    stallMs += since() - stallStart;
                } else if (!a.getPlayWhenReady() || (incomingStarted && !b.getPlayWhenReady())) {
                    reason = a.getPlayWhenReady() ? "incoming-paused" : "outgoing-paused";
                    stop();
                    return;
                }
                if (!incomingStarted && position >= heard) {
                    long target =
                            Math.round(
                                    (draft.incomingCue
                                                    + Math.max(0, position - start)
                                                            * draft.overlapRate[1]
                                                            / draft.overlapRate[0])
                                            * 1000);
                    // The prepared deck already sits at the cue; seeking again restarts its load.
                    if (Math.abs(b.getCurrentPosition() - target) > 150) b.seekTo(0, target);
                    a.setPlaybackParameters(
                            new PlaybackParameters(
                                    rate * (float) draft.overlapRate[0], session.parameters.pitch));
                    incomingStarted = true;
                    b.play();
                    inPlay = since();
                }
                double p = Math.max(0, Math.min(1, progress));
                double outgoingGain = draft.gain(0, p),
                        incomingGain = incomingStarted ? draft.gain(1, p) : 0;
                a.setVolume(session.volume * (float) outgoingGain);
                b.setVolume(session.volume * (float) incomingGain);
                float trim = headroom(outgoingGain, incomingGain);
                for (int side = 0; side < 2; side++) {
                    decks[side].effects(draft, side, p);
                    decks[side].trim(trim);
                }
                handler.postDelayed(tick, 16);
            } catch (Exception | LinkageError failure) {
                error = failureDetail(failure);
                if ("none".equals(cause)) cause = failureClass(failure);
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
            if (buffering) stallMs += since() - stallStart;
            report(error != null ? "error" : completed ? "completed" : reason);
            releaseDecks();
            if (session.audition == this) session.audition = null;
            Runnable notify = done;
            done = null;
            if (notify != null) notify.run();
        }

        private void releaseDecks() {
            for (AutoCloseable tag : streamTags) {
                try {
                    tag.close();
                } catch (Exception ignored) {
                }
            }
            streamTags.clear();
            if (priority != null) {
                try {
                    priority.close();
                } catch (Exception ignored) {
                    /* A lease has nothing left to release. */
                }
                priority = null;
            }
            for (Deck deck : decks)
                if (deck != null) {
                    try {
                        deck.trim(1f);
                    } catch (Exception | LinkageError unavailable) {
                        /* The private deck is released next. */
                    }
                    deck.close();
                }
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
        private final Object loudness;
        private final Method trimmer;
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
            this.loudness = previewLoudness;
            Method trimmer = null;
            try {
                trimmer = previewLoudness.getClass().getMethod("setBlendTrim", float.class);
            } catch (NoSuchMethodException older) {
                /* Previews then refuse any level change they cannot apply. */
            }
            this.trimmer = trimmer;
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

        public void trim(float gain) throws Exception {
            if (trimmer != null) trimmer.invoke(loudness, gain);
            else if (gain != 1f) throw new IllegalStateException("Preview headroom is unavailable");
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
