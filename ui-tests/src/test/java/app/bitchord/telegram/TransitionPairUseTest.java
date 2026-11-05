package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.IntConsumer;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class TransitionPairUseTest {
    public static final class Control {
        public boolean busy;

        public boolean isTransitioning() {
            return busy;
        }
    }

    public static final class Service {
        public Object player;
        public Control crossfade = new Control();
    }

    private static final String ENTRY = "bitchord.queueEntryId";

    private Service service;
    private final List<MediaItem> queue = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final List<AutoMixTestDeck> decks = new ArrayList<>();
    private int index, nextOverride, repeat, replaces, failReplace, prepares;
    private long position, duration;
    private boolean playing, exoShuffle;
    private PlaybackParameters speed;
    private float volume;
    private IntConsumer onReplace, afterReplace;
    private Runnable onItemRead;
    private TransitionPreview.Factory previousFactory;

    @Before
    public void setup() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Field appField = SoundExtras.class.getDeclaredField("app");
        appField.setAccessible(true);
        appField.set(null, app);
        previousFactory = TransitionPreview.factory;
        TransitionPreview.factory =
                (owner, side) -> {
                    AutoMixTestDeck deck = new AutoMixTestDeck();
                    decks.add(deck);
                    return deck;
                };
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        service = new Service();
        service.player =
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getCurrentMediaItemIndex":
                                    return index;
                                case "getNextMediaItemIndex":
                                    if (nextOverride >= 0) return nextOverride;
                                    if (index + 1 < queue.size()) return index + 1;
                                    return repeat == Player.REPEAT_MODE_ALL ? 0 : -1;
                                case "getCurrentMediaItem":
                                    return queue.get(index);
                                case "getMediaItemAt":
                                    MediaItem observed = queue.get((Integer) args[0]);
                                    if (onItemRead != null) onItemRead.run();
                                    return observed;
                                case "getMediaItemCount":
                                    return queue.size();
                                case "getDuration":
                                    return duration;
                                case "getCurrentPosition":
                                    return position;
                                case "getVolume":
                                    return volume;
                                case "getPlayWhenReady":
                                    return playing;
                                case "getPlaybackParameters":
                                    return speed;
                                case "getRepeatMode":
                                    return repeat;
                                case "getShuffleModeEnabled":
                                    return exoShuffle;
                                case "pause":
                                    calls.add("pause");
                                    playing = false;
                                    return null;
                                case "prepare":
                                    prepares++;
                                    return null;
                                case "setPlayWhenReady":
                                    calls.add("ready:" + args[0]);
                                    playing = (Boolean) args[0];
                                    return null;
                                case "replaceMediaItem":
                                    int slot = (Integer) args[0];
                                    calls.add("replace:" + slot);
                                    replaces++;
                                    if (onReplace != null) onReplace.accept(slot);
                                    if (replaces == failReplace)
                                        throw new IllegalStateException("write failed");
                                    queue.set(slot, (MediaItem) args[1]);
                                    if (slot == index) {
                                        position = 0;
                                        duration = C.TIME_UNSET;
                                    }
                                    // Native listeners run synchronously after the change.
                                    if (afterReplace != null) afterReplace.accept(slot);
                                    return null;
                                case "seekTo":
                                    if (args.length == 2) {
                                        calls.add("seek:" + args[0] + ":" + args[1]);
                                        index = (Integer) args[0];
                                        position = (Long) args[1];
                                    } else calls.add("seek");
                                    return null;
                                case "equals":
                                    return proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "Queue fixture";
                            }
                            if (method.getName().startsWith("set")
                                    || method.getName().startsWith("add")
                                    || method.getName().startsWith("remove")
                                    || method.getName().startsWith("move"))
                                calls.add(method.getName());
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            if (method.getReturnType() == float.class) return 0f;
                            return null;
                        });
        SourceAutomix.service(service);
        resetQueue();
    }

    @After
    public void close() {
        TransitionPreview.releaseService();
        SourceAutomix.release(service);
        TransitionPreview.factory = previousFactory;
        com.music.bitchord.playback.QueueShuffle.setEnabled(false);
        com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "";
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1f;
    }

    private void resetQueue() {
        queue.clear();
        queue.add(item("a", "entry-a", "CONTEXT", -1, -1));
        queue.add(item("b", "entry-b", "USER_QUEUE", 7, -1));
        queue.add(item("c", "entry-c", "CONTEXT", -1, 2));
        queue.add(item("d", "entry-d", "CONTEXT", -1, 4));
        queue.add(item("e", "entry-e", "AUTOPLAY", -1, -1));
        index = 1;
        nextOverride = 3;
        repeat = Player.REPEAT_MODE_OFF;
        replaces = 0;
        prepares = 0;
        failReplace = -1;
        position = 12000;
        duration = 180000;
        playing = true;
        exoShuffle = false;
        speed = new PlaybackParameters(1.25f, .95f);
        volume = 1f;
        onReplace = null;
        afterReplace = null;
        onItemRead = null;
        calls.clear();
        decks.clear();
        service.crossfade.busy = false;
        com.music.bitchord.playback.QueueShuffle.setEnabled(false);
        com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "";
    }

    private static MediaItem item(String id, String entry, String tier, long cycle, int canonical) {
        Bundle extras = new Bundle();
        extras.putString(ENTRY, entry);
        extras.putString("bitchord.queueTier", tier);
        if (cycle >= 0) extras.putLong("bitchord.contextCycleId", cycle);
        if (canonical >= 0) extras.putInt("bitchord.canonicalIndex", canonical);
        return new MediaItem.Builder()
                .setMediaId(id)
                .setUri("content://audio/" + id)
                .setMediaMetadata(
                        new MediaMetadata.Builder()
                                .setTitle(id.toUpperCase(Locale.ROOT))
                                .setExtras(extras)
                                .build())
                .build();
    }

    /** What BitChord's converter produces for a chosen device file: no queue entry yet. */
    private static MediaItem picked(String id, String length) {
        Bundle extras = new Bundle();
        extras.putString("bitchord.queueTier", "CONTEXT");
        extras.putString("bitchord.durationText", length);
        extras.putString("bitchord.localUri", "content://picked/" + id);
        return new MediaItem.Builder()
                .setMediaId("local:" + id)
                .setUri("content://picked/" + id)
                .setMediaMetadata(
                        new MediaMetadata.Builder()
                                .setTitle("Picked " + id)
                                .setArtist("Artist " + id)
                                .setExtras(extras)
                                .build())
                .build();
    }

    private static TransitionRuntime.Pair choose(
            TransitionRuntime.Pair from,
            MediaItem outgoing,
            MediaItem incoming,
            double outgoingSeconds,
            double incomingSeconds) {
        TransitionRuntime.Pair chosen =
                new TransitionRuntime.Pair(
                        outgoing.mediaId,
                        incoming.mediaId,
                        TransitionRuntime.title(outgoing),
                        TransitionRuntime.title(incoming),
                        outgoingSeconds,
                        incomingSeconds,
                        from.index,
                        from.nextIndex);
        chosen.outgoing = outgoing;
        chosen.incoming = incoming;
        return chosen;
    }

    private static String entry(MediaItem item) {
        return item.mediaMetadata.extras.getString(ENTRY);
    }

    private TransitionRuntime.Pair reversed(TransitionRuntime.Pair start) {
        try (TransitionPairSelection selection =
                new TransitionPairSelection(start, chosen -> {}, message -> {})) {
            return selection.reverse();
        }
    }

    private TransitionPreview.Session open(TransitionRuntime.Pair start) throws Exception {
        TransitionPreview.Session session = new TransitionPreview.Session(start);
        calls.clear();
        return session;
    }

    private static int count(List<String> calls, String prefix) {
        int found = 0;
        for (String call : calls) if (call.startsWith(prefix)) found++;
        return found;
    }

    @Test
    public void choosingAPairNeverTouchesTheQueueOrPlayback() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(chosen);
        assertTrue(calls.isEmpty());
        assertSame(chosen, session.selectedPair());
        assertTrue(session.ownsSelection(chosen));
        assertFalse(session.ownsSelection(start));
        assertSame(start, session.pair);
        assertFalse(playing);
        assertEquals(12000, position);
    }

    @Test
    public void reversingIsADifferentDirectedPairAndStillOnlyAPreview() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        assertTrue(calls.isEmpty());
        assertEquals(start.incomingId, back.outgoingId);
        assertEquals(start.outgoingId, back.incomingId);
        assertSame(start.incoming, back.outgoing);
        assertNotEquals(start.key, back.key);
        assertNotEquals(start.recording(), back.recording());
        assertEquals(start.index, back.index);
        assertEquals(start.nextIndex, back.nextIndex);
        assertSame(start, session.pair);
    }

    @Test
    public void selectingStopsTheAuditionWithoutAnyCallback() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        boolean[] done = {false};
        TransitionPreview.Audition audition =
                session.start(new TransitionEdit(), () -> done[0] = true);
        assertEquals(2, decks.size());
        session.selectPair(reversed(start));
        assertTrue(audition.closed);
        for (AutoMixTestDeck deck : decks) assertTrue(deck.closed);
        assertFalse(done[0]);
        assertNull(session.audition);
        assertFalse(calls.contains("replace:1"));
    }

    @Test
    public void usingPlacesBothSongsInExactlyTheCurrentAndNextSlots() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        MediaItem[] before = queue.toArray(new MediaItem[0]);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        TransitionPreview.Session used = session.usePair(back);
        assertEquals(Arrays.asList("replace:3", "replace:1"), calls);
        for (int slot : new int[] {0, 2, 4}) assertSame(before[slot], queue.get(slot));
        assertEquals(5, queue.size());
        assertEquals("d", queue.get(1).mediaId);
        assertEquals("b", queue.get(3).mediaId);
        Set<String> seen = new HashSet<>();
        for (MediaItem old : before) seen.add(entry(old));
        for (int slot : new int[] {1, 3}) {
            String fresh = entry(queue.get(slot));
            assertEquals(fresh, UUID.fromString(fresh).toString());
            assertTrue("Fresh occurrence " + fresh, seen.add(fresh));
        }
        Bundle current = queue.get(1).mediaMetadata.extras,
                next = queue.get(3).mediaMetadata.extras;
        assertEquals("USER_QUEUE", current.getString("bitchord.queueTier"));
        assertEquals(7L, current.getLong("bitchord.contextCycleId"));
        assertFalse(current.containsKey("bitchord.canonicalIndex"));
        assertEquals("CONTEXT", next.getString("bitchord.queueTier"));
        assertFalse(next.containsKey("bitchord.contextCycleId"));
        assertFalse(next.containsKey("bitchord.canonicalIndex"));
        assertEquals("entry-d", entry(before[3]));
        assertEquals(4, before[3].mediaMetadata.extras.getInt("bitchord.canonicalIndex"));
        assertEquals(1.25f, speed.speed, 0);
        assertEquals(.95f, speed.pitch, 0);
        assertEquals(1f, volume, 0);
        assertFalse(playing);
        assertEquals(0, position);
        assertTrue(used.isHeld());
        assertFalse(session.isHeld());
        assertEquals(1, used.index);
        assertEquals(3, used.nextIndex);
        assertSame(used.pair, used.selectedPair());
        assertEquals("d", used.pair.outgoingId);
        used.close(true);
        assertTrue(playing);
        assertEquals(1, count(calls, "ready:"));
        session.close(true);
        assertEquals(1, count(calls, "ready:"));
    }

    @Test
    public void keepingTheOutgoingSongKeepsItsSlotAndPosition() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        MediaItem[] before = queue.toArray(new MediaItem[0]);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(chosen);
        TransitionPreview.Session used = session.usePair(chosen);
        assertEquals(Collections.singletonList("replace:3"), calls);
        for (int slot : new int[] {0, 1, 2, 4}) assertSame(before[slot], queue.get(slot));
        assertEquals("local:x", queue.get(3).mediaId);
        assertEquals("content://picked/x", queue.get(3).localConfiguration.uri.toString());
        assertEquals(12000, position);
        assertEquals(12000, used.position);
        TransitionPreview.Session settled = used.settle();
        assertNotSame(used, settled);
        assertEquals(TransitionRuntime.pair().recording(), settled.pair.recording());
        assertEquals(185, settled.pair.incomingDuration, 1e-6);
        calls.clear();
        settled.close(true);
        assertEquals(Collections.singletonList("ready:true"), calls);
        assertTrue(playing);
        assertEquals(12000, position);
    }

    @Test
    public void aNewOutgoingSongStartsAtZeroAndSettlesOnceItsLengthIsKnown() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, picked("x", "3:20"), start.incoming, 200, start.incomingDuration);
        session.selectPair(chosen);
        TransitionPreview.Session used = session.usePair(chosen);
        assertEquals(Collections.singletonList("replace:1"), calls);
        assertEquals(0, position);
        assertFalse(playing);
        assertSame(used, used.settle());
        assertTrue(used.isHeld());
        duration = 200400;
        TransitionPreview.Session settled = used.settle();
        assertNotSame(used, settled);
        assertTrue(settled.isHeld());
        assertEquals(200.4, settled.pair.outgoingDuration, 1e-6);
        assertEquals(TransitionRuntime.pair().recording(), settled.pair.recording());
        assertSame(settled, settled.settle());
        calls.clear();
        settled.close(true);
        assertEquals(Collections.singletonList("ready:true"), calls);
        assertTrue(playing);
        assertEquals(0, position);
    }

    @Test
    public void settleStopsWaitingAfterItsDeadline() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, picked("x", "3:20"), start.incoming, 200, start.incomingDuration);
        session.selectPair(chosen);
        TransitionPreview.Session used = session.usePair(chosen);
        assertSame(used, used.settle());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        assertThrows(IllegalStateException.class, used::settle);
    }

    @Test
    public void settleRefusesAForeignSongUnderAReusedEntryButAcceptsTheHostsTag() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        MediaItem watch =
                picked("x", "3:05").buildUpon().setUri("bitchord://watch?v=vid&q=original").build();
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, watch, start.outgoingDuration, 185);
        session.selectPair(chosen);
        TransitionPreview.Session used = session.usePair(chosen);
        MediaItem written = queue.get(3);
        String reused = entry(written);
        MediaItem projected =
                written.buildUpon()
                        .setUri(
                                android.net.Uri.parse("bitchord://watch?v=vid&q=original")
                                        .buildUpon()
                                        .appendQueryParameter("bc_entry", reused)
                                        .build())
                        .build();
        queue.set(3, projected);
        assertTrue(used.unchanged());
        MediaItem provider =
                written.buildUpon().setUri("content://provider.test/x?bc_entry=" + reused).build();
        queue.set(3, provider);
        assertFalse("A provider query is not the host's tag", used.unchanged());
        assertThrows(IllegalStateException.class, used::settle);
        queue.set(3, item("evil", reused, "CONTEXT", -1, -1));
        assertFalse(used.unchanged());
        queue.set(3, projected);
        assertNotSame(used, used.settle());
    }

    @Test
    public void sameSongsWithFullerMetadataRebaseWithoutAnyQueueWrite() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair fuller =
                choose(start, start.outgoing, start.incoming, start.outgoingDuration + 0.4, 180);
        session.selectPair(fuller);
        TransitionPreview.Session rebased = session.usePair(fuller);
        assertNotSame(session, rebased);
        assertTrue(calls.isEmpty());
        assertTrue(rebased.isHeld());
        assertFalse(session.isHeld());
        TransitionPreview.Session settled = rebased.settle();
        assertNotSame(rebased, settled);
        assertEquals(TransitionRuntime.pair().recording(), settled.pair.recording());
        assertTrue(calls.isEmpty());
        settled.close(true);
        assertTrue(playing);
    }

    @Test
    public void aChosenFileStaysGrantedWhenAForeignActionPreventsTheRollback() throws Exception {
        android.app.Activity activity =
                Robolectric.buildActivity(android.app.Activity.class).setup().get();
        TransitionPairSelection.Reader previous = TransitionPairSelection.reader;
        TransitionPairSelection.reader =
                (context, uri) -> new TransitionPairSelection.Info("Real", "Artist", 245000);
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionRuntime.Pair[] chosen = new TransitionRuntime.Pair[1];
        android.net.Uri file = android.net.Uri.parse("content://docs.test/document/kept");
        android.content.ContentResolver resolver =
                RuntimeEnvironment.getApplication().getContentResolver();
        try (TransitionPairSelection picker =
                new TransitionPairSelection(start, pair -> chosen[0] = pair, message -> {})) {
            picker.launch(activity, 0);
            picker.handleResult(
                    TransitionPairSelection.OUTGOING_REQUEST,
                    android.app.Activity.RESULT_OK,
                    new android.content.Intent()
                            .setData(file)
                            .addFlags(
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | android.content.Intent
                                                    .FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
            for (int wait = 0; wait < 500 && chosen[0] == null; wait++) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertNotNull(chosen[0]);
            TransitionPreview.Session session = open(start);
            session.selectPair(chosen[0]);
            TransitionPairSelection.Retention retention = picker.retainSelectionForSave(chosen[0]);
            PlaybackParameters foreignSpeed = new PlaybackParameters(1.5f, .95f);
            afterReplace =
                    slot -> {
                        if (slot != 1) return;
                        position = 5000;
                        speed = foreignSpeed;
                    };
            assertThrows(IllegalStateException.class, () -> session.usePair(chosen[0]));
            assertEquals(file.toString(), queue.get(1).localConfiguration.uri.toString());
            assertEquals(5000, position);
            assertSame(foreignSpeed, speed);
            retention.close();
            boolean kept = false;
            for (android.content.UriPermission permission : resolver.getPersistedUriPermissions())
                kept |= file.equals(permission.getUri()) && permission.isReadPermission();
            assertTrue("The file still in the queue keeps its access", kept);
        } finally {
            TransitionPairSelection.reader = previous;
            for (android.content.UriPermission permission :
                    new ArrayList<>(resolver.getPersistedUriPermissions()))
                resolver.releasePersistableUriPermission(
                        permission.getUri(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @Test
    public void anUnchangedSelectionWritesNothing() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair same =
                choose(start, start.outgoing, start.incoming, start.outgoingDuration, 180);
        session.selectPair(same);
        assertSame(session, session.usePair(same));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void aShuffledNonAdjacentNextSlotIsWhatGetsReplaced() throws Exception {
        nextOverride = 4;
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        assertEquals(4, start.nextIndex);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(chosen);
        session.usePair(chosen);
        assertEquals(Collections.singletonList("replace:4"), calls);
        assertEquals("c", queue.get(2).mediaId);
        assertEquals("d", queue.get(3).mediaId);
    }

    @Test
    public void aWrappingNextSlotIsTheFirstOneInTheQueue() throws Exception {
        index = 4;
        repeat = Player.REPEAT_MODE_ALL;
        nextOverride = -1;
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        assertEquals(0, start.nextIndex);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(chosen);
        TransitionPreview.Session used = session.usePair(chosen);
        assertEquals(Collections.singletonList("replace:0"), calls);
        assertEquals(Player.REPEAT_MODE_ALL, repeat);
        assertEquals(0, used.nextIndex);
        assertEquals("e", queue.get(4).mediaId);
    }

    @Test
    public void aSlotCannotBeItsOwnNextSlot() throws Exception {
        TransitionRuntime.Pair old = new TransitionRuntime.Pair("a", "b", "A", "B", 180, 180, 2);
        assertEquals(3, old.nextIndex);
        queue.subList(1, 5).clear();
        index = 0;
        nextOverride = -1;
        repeat = Player.REPEAT_MODE_ALL;
        assertNull(TransitionRuntime.pair());
    }

    @Test
    public void anyDriftFromTheBaselineStopsTheWriteBeforeItStarts() throws Exception {
        Map<String, Runnable> drifts = new LinkedHashMap<>();
        drifts.put("queue", () -> queue.set(2, item("z", "entry-z", "CONTEXT", -1, -1)));
        drifts.put("entry", () -> queue.set(0, item("a", "other", "CONTEXT", -1, -1)));
        drifts.put(
                "rendition",
                () ->
                        queue.set(
                                3,
                                item("d", "entry-d", "CONTEXT", -1, 4)
                                        .buildUpon()
                                        .setUri("content://audio/elsewhere")
                                        .build()));
        drifts.put("count", () -> queue.add(item("f", "entry-f", "CONTEXT", -1, -1)));
        drifts.put("index", () -> index = 2);
        drifts.put("next", () -> nextOverride = 4);
        drifts.put("repeat", () -> repeat = Player.REPEAT_MODE_ONE);
        drifts.put("shuffle", () -> exoShuffle = true);
        drifts.put(
                "queue shuffle", () -> com.music.bitchord.playback.QueueShuffle.setEnabled(true));
        drifts.put("speed", () -> speed = new PlaybackParameters(1f, .95f));
        drifts.put("pitch", () -> speed = new PlaybackParameters(1.25f, 1f));
        drifts.put("volume", () -> volume = .5f);
        drifts.put("position", () -> position = 20000);
        drifts.put("playing", () -> playing = true);
        drifts.put("transition", () -> service.crossfade.busy = true);
        drifts.put(
                "party",
                () -> com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "ABCD");
        for (Map.Entry<String, Runnable> drift : drifts.entrySet()) {
            resetQueue();
            TransitionRuntime.Pair start = TransitionRuntime.pair();
            TransitionPreview.Session session = open(start);
            TransitionRuntime.Pair chosen =
                    choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
            session.selectPair(chosen);
            drift.getValue().run();
            calls.clear();
            MediaItem[] drifted = queue.toArray(new MediaItem[0]);
            assertThrows(
                    drift.getKey(), IllegalStateException.class, () -> session.usePair(chosen));
            assertEquals(drift.getKey(), 0, count(calls, "replace"));
            assertEquals(drift.getKey(), 0, count(calls, "seek"));
            assertArrayEquals(drift.getKey(), drifted, queue.toArray(new MediaItem[0]));
            assertTrue(drift.getKey(), session.isHeld());
            session.close(false);
        }
    }

    @Test
    public void onlyAPairThisSessionWasGivenCanBeUsed() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair given =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        TransitionRuntime.Pair other =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(given);
        assertThrows(IllegalStateException.class, () -> session.usePair(other));
        assertThrows(IllegalStateException.class, () -> session.usePair(start));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.selectPair(choose(start, start.outgoing, start.outgoing, 180, 180)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        session.selectPair(
                                choose(start, start.outgoing, picked("y", "3:05"), 180, 0)));
        session.close(false);
        assertFalse(session.ownsSelection(given));
        assertFalse(session.isHeld());
        assertThrows(IllegalStateException.class, () -> session.selectPair(given));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void aFailedSecondWriteRestoresTheFirstSlotAndKeepsTheSession() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        MediaItem[] before = queue.toArray(new MediaItem[0]);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        failReplace = 2;
        assertThrows(IllegalStateException.class, () -> session.usePair(back));
        assertEquals(Arrays.asList("replace:3", "replace:1", "replace:3"), calls);
        assertArrayEquals(before, queue.toArray(new MediaItem[0]));
        assertEquals(12000, position);
        assertFalse(playing);
        assertTrue(session.isHeld());
        session.close(true);
        assertTrue(playing);
    }

    @Test
    public void rollbackLeavesForeignChangesAloneAndRestoresOnlyWhatIsStillOurs() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        MediaItem[] before = queue.toArray(new MediaItem[0]);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        failReplace = 2;
        onReplace =
                slot -> {
                    if (slot == 1) {
                        // A foreign writer reuses the entry id of the slot already written.
                        String reused = entry(queue.get(3));
                        queue.set(3, item("evil", reused, "CONTEXT", -1, -1));
                    }
                };
        assertThrows(IllegalStateException.class, () -> session.usePair(back));
        assertEquals("evil", queue.get(3).mediaId);
        assertSame(before[1], queue.get(1));
        for (int slot : new int[] {0, 2, 4}) assertSame(before[slot], queue.get(slot));
        assertEquals(Arrays.asList("replace:3", "replace:1"), calls);
    }

    @Test
    public void aForeignChangeDuringTheWriteIsKeptWhileOurSlotsAreRestored() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        MediaItem[] before = queue.toArray(new MediaItem[0]);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        MediaItem foreign = item("evil", "entry-evil", "CONTEXT", -1, -1);
        onReplace =
                slot -> {
                    if (slot == 1) queue.set(4, foreign);
                };
        assertThrows(IllegalStateException.class, () -> session.usePair(back));
        assertSame(foreign, queue.get(4));
        assertSame(before[1], queue.get(1));
        assertSame(before[3], queue.get(3));
        assertEquals(12000, position);
        assertEquals(1, index);
        assertFalse(playing);
    }

    @Test
    public void reentrantCallsAreRefusedAndACloseWaitsForTheWrite() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        Throwable[] refused = new Throwable[2];
        onReplace =
                slot -> {
                    if (slot != 1) return;
                    try {
                        session.usePair(back);
                    } catch (Throwable failure) {
                        refused[0] = failure;
                    }
                    try {
                        session.selectPair(back);
                    } catch (Throwable failure) {
                        refused[1] = failure;
                    }
                    session.close(true);
                };
        TransitionPreview.Session used = session.usePair(back);
        assertTrue(refused[0] instanceof IllegalStateException);
        assertTrue(refused[1] instanceof IllegalStateException);
        assertTrue(used.closed);
        assertFalse(used.isHeld());
        assertTrue(playing);
        assertEquals(1, count(calls, "ready:"));
        assertEquals("d", queue.get(1).mediaId);
    }

    @Test
    public void usingIsRefusedInsteadOfDisturbingAnotherShuffleOrAParty() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(chosen);
        session.selectPair(chosen);
        exoShuffle = true;
        IllegalStateException shuffled =
                assertThrows(IllegalStateException.class, () -> session.usePair(chosen));
        assertTrue(shuffled.getMessage().contains("shuffle"));
        exoShuffle = false;
        com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "ABCD";
        IllegalStateException party =
                assertThrows(IllegalStateException.class, () -> session.usePair(chosen));
        assertTrue(party.getMessage().contains("Listen Together"));
        com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "";
        assertTrue(calls.isEmpty());
        session.selectPair(reversed(start));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void theOriginalPlaybackIntentSurvivesUseSettleAndClose() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, picked("x", "3:20"), start.incoming, 200, start.incomingDuration);
        session.selectPair(chosen);
        TransitionPreview.Session used = session.usePair(chosen);
        assertTrue(used.playing);
        duration = 200000;
        TransitionPreview.Session settled = used.settle();
        assertTrue(settled.playing);
        used.close(true);
        session.close(true);
        assertFalse(playing);
        settled.close(true);
        settled.close(true);
        assertTrue(playing);
        assertEquals(1, count(calls, "ready:"));
    }

    @Test
    public void aForeignCurrentSlotAfterTheIncomingWriteIsNeverOverwritten() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        MediaItem[] before = queue.toArray(new MediaItem[0]);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        MediaItem foreign = item("evil", "entry-evil", "CONTEXT", -1, -1);
        afterReplace =
                slot -> {
                    if (slot == 3) queue.set(1, foreign);
                };
        assertThrows(IllegalStateException.class, () -> session.usePair(back));
        assertFalse(calls.contains("replace:1"));
        assertEquals(0, count(calls, "seek"));
        assertSame(foreign, queue.get(1));
        assertSame(before[3], queue.get(3));
        for (int slot : new int[] {0, 2, 4}) assertSame(before[slot], queue.get(slot));
    }

    @Test
    public void aUserSeekAndSpeedChangeAfterTheCurrentWriteAreKept() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        PlaybackParameters changed = new PlaybackParameters(1.5f, .95f);
        afterReplace =
                slot -> {
                    if (slot != 1) return;
                    position = 5000;
                    speed = changed;
                };
        assertThrows(IllegalStateException.class, () -> session.usePair(back));
        int replaced = calls.indexOf("replace:1");
        assertTrue(replaced >= 0);
        assertEquals(
                "Nothing seeks after a manual seek",
                0,
                count(calls.subList(replaced, calls.size()), "seek"));
        assertEquals(5000, position);
        assertSame(changed, speed);
    }

    @Test
    public void aNewerSelectionSurvivesAProvisionalSettle() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, picked("x", "3:20"), start.incoming, 200, start.incomingDuration);
        session.selectPair(chosen);
        TransitionPreview.Session used = session.usePair(chosen);
        duration = 200000;
        TransitionRuntime.Pair newer =
                choose(used.pair, used.pair.outgoing, picked("y", "3:10"), 200, 190);
        boolean accepted = true;
        try {
            used.selectPair(newer);
        } catch (IllegalStateException | IllegalArgumentException refused) {
            accepted = false;
        }
        if (!accepted) assertSame(used.pair, used.selectedPair());
        TransitionPreview.Session settled = used.settle();
        if (accepted) {
            assertSame(newer, settled.selectedPair());
            assertTrue(settled.ownsSelection(newer));
        }
    }

    @Test
    public void partyStateIsFoundBehindTheConnectionFlowThatPrecedesIt() throws Exception {
        assertFalse(DjMode.inParty());
        com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "ABCD";
        assertTrue(DjMode.inParty());
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(chosen);
        assertThrows(IllegalStateException.class, () -> session.usePair(chosen));
        assertEquals(0, count(calls, "replace"));
        assertEquals(0, count(calls, "seek"));
        com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "";
        assertFalse(DjMode.inParty());
    }

    @Test
    public void aProviderQueryNamedLikeTheEntryTagSurvivesUse() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        MediaItem provider =
                picked("x", "3:05")
                        .buildUpon()
                        .setUri("content://docs.test/document/1?bc_entry=provider-value&a=b")
                        .build();
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, provider, start.outgoingDuration, 185);
        session.selectPair(chosen);
        session.usePair(chosen);
        assertEquals(
                "content://docs.test/document/1?bc_entry=provider-value&a=b",
                queue.get(3).localConfiguration.uri.toString());
    }

    @Test
    public void evenASmallSeekAfterNativeReplacementIsNeverOverwritten() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, picked("x", "3:20"), start.incoming, 200, start.incomingDuration);
        session.selectPair(chosen);
        afterReplace =
                slot -> {
                    if (slot == index) position = 100;
                };
        assertThrows(IllegalStateException.class, () -> session.usePair(chosen));
        assertEquals(100, position);
        assertEquals(0, count(calls, "seek"));
        assertEquals("local:x", queue.get(index).mediaId);
        assertFalse(session.isHeld());
    }

    @Test
    public void aSmallPositionDriftWithinToleranceIsKeptThroughUseAndClose() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        long original = position;
        MediaItem current = queue.get(index);
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair chosen =
                choose(start, start.outgoing, picked("x", "3:05"), start.outgoingDuration, 185);
        session.selectPair(chosen);
        afterReplace =
                slot -> {
                    if (slot == nextOverride) position = original + 100;
                };
        TransitionPreview.Session used = session.usePair(chosen);
        assertEquals(Collections.singletonList("replace:3"), calls);
        assertEquals(original + 100, position);
        assertSame(current, queue.get(index));
        assertEquals("local:x", queue.get(3).mediaId);
        used.close(true);
        assertEquals(original + 100, position);
        assertEquals(0, count(calls, "seek"));
        assertTrue(playing);
        assertEquals(1, count(calls, "ready:"));
    }

    @Test
    public void joiningAPartyDuringTheFirstWriteStopsAllFurtherQueueWrites() throws Exception {
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        TransitionRuntime.Pair back = reversed(start);
        session.selectPair(back);
        MediaItem originalCurrent = queue.get(index);
        afterReplace =
                slot -> {
                    if (slot == nextOverride)
                        com.music.bitchord.data.listentogether.ListenTogether.joinedCode = "ABCD";
                };
        assertThrows(IllegalStateException.class, () -> session.usePair(back));
        assertEquals(Collections.singletonList("replace:3"), calls);
        assertSame(originalCurrent, queue.get(index));
        assertEquals(0, prepares);
        assertFalse(session.isHeld());
    }

    @Test
    public void queuedGrantProofRejectsAServiceThatChangedDuringTheRead() throws Exception {
        android.net.Uri uri = queue.get(index).localConfiguration.uri;
        onItemRead = () -> SourceAutomix.release(service);
        java.lang.reflect.Method queued =
                TransitionPairSelection.class.getDeclaredMethod("queued", android.net.Uri.class);
        queued.setAccessible(true);
        assertEquals(false, queued.invoke(null, uri));
    }

    @Test
    public void aLegacySlotDoesNotAcquireTheSourcesAutoplayFlags() throws Exception {
        MediaItem legacy =
                new MediaItem.Builder().setMediaId("b").setUri("content://audio/b").build();
        queue.set(index, legacy);
        TransitionRuntime.Pair start = TransitionRuntime.pair();
        TransitionPreview.Session session = open(start);
        MediaItem source = picked("x", "3:20");
        source.mediaMetadata.extras.putString("bitchord.queueTier", "AUTOPLAY");
        source.mediaMetadata.extras.putBoolean("bitchord.fromAutoplay", true);
        TransitionRuntime.Pair chosen =
                choose(start, source, start.incoming, 200, start.incomingDuration);
        session.selectPair(chosen);
        session.usePair(chosen);
        Bundle actual = queue.get(index).mediaMetadata.extras;
        assertNotEquals("AUTOPLAY", actual.getString("bitchord.queueTier"));
        assertFalse(actual.getBoolean("bitchord.fromAutoplay"));
        assertEquals(1, prepares);
    }
}
