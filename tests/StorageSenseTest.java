package app.bitchord.telegram;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class StorageSenseTest {
    static int checks;
    static final long NOW = 100L * 86_400_000L;

    static void check(boolean value, String text) {
        checks++;
        if (!value) throw new AssertionError(text);
    }

    static StorageSense.Active idle() {
        List<String> none = Collections.emptyList();
        return new StorageSense.Active(true, none, none, none, none);
    }

    static StorageSense.Item cache(String id, long bytes, long used) {
        StorageSense.Item item = new StorageSense.Item();
        item.id = id;
        item.location = id;
        item.category = StorageSense.Category.PLAYBACK_CACHE;
        item.effect = StorageSense.Effect.CACHE_RECLAIM;
        item.block = StorageSense.Block.NONE;
        item.bytes = bytes;
        item.lastUsedMs = used;
        item.automatic = true;
        return item;
    }

    static StorageSense.Item saved(String id, long bytes, long used) {
        StorageSense.Item item = cache(id, bytes, used);
        item.category = StorageSense.Category.SAVED;
        item.effect = StorageSense.Effect.DELETE_SAVED_FILE;
        item.automatic = false;
        return item;
    }

    static void rules() {
        StorageSense.Rules rules = new StorageSense.Rules();
        check(!rules.ageOn && !rules.sizeOn && !rules.any(), "Every rule starts off");
        List<StorageSense.Item> items =
                new ArrayList<>(
                        Arrays.asList(
                                cache("old", 100, 1),
                                cache("new", 100, NOW - 1000),
                                saved("offline", 9_000_000, 1)));
        check(
                StorageSense.plan(items, rules, NOW).items.isEmpty(),
                "With rules off, nothing is planned");
        rules.ageOn = true;
        rules.ageDays = 30;
        StorageSense.Plan plan = StorageSense.plan(items, rules, NOW);
        check(plan.items.size() == 1 && plan.items.get(0).id.equals("old"), "Only old cache");
        check(plan.bytes == 100, "Plan counts the bytes it frees");
        rules.ageOn = false;
        rules.sizeOn = true;
        rules.maxBytes = 100;
        plan = StorageSense.plan(items, rules, NOW);
        check(
                plan.items.size() == 1 && plan.items.get(0).id.equals("old"),
                "Size rule takes the oldest until under the limit");
        for (StorageSense.Item item : StorageSense.plan(items, rules, NOW).items)
            check(item.category != StorageSense.Category.SAVED, "Rules never reach saved files");
        rules.maxBytes = 1;
        for (StorageSense.Item item : StorageSense.plan(items, rules, NOW).items)
            check(!item.id.equals("offline"), "Even an unreachable target leaves saved files");
        StorageSense.Item playing = cache("playing", 500, 1);
        playing.block = StorageSense.Block.PLAYING;
        StorageSense.Item unknownAge = cache("unknownAge", 500, 0);
        StorageSense.Item untagged = cache("untagged", 500, 1);
        untagged.automatic = false;
        plan =
                StorageSense.plan(
                        new ArrayList<>(Arrays.asList(playing, unknownAge, untagged)), rules, NOW);
        check(plan.items.isEmpty(), "Protected, unknown-age and untagged entries stay");
        check(plan.protectedCount == 3, "What stayed is counted");
        rules.maxBytes = 1000;
        StorageSense.Item inUse = cache("inUse", 300, 1);
        inUse.block = StorageSense.Block.PLAYING;
        plan =
                StorageSense.plan(
                        new ArrayList<>(
                                Arrays.asList(inUse, cache("a", 500, 2), cache("b", 500, 3))),
                        rules,
                        NOW);
        check(
                plan.items.size() == 1 && plan.items.get(0).id.equals("a"),
                "Bytes that can't be removed still count toward the limit");
        check(StorageSense.Rules.validDays(13) == 30, "Tampered age falls back");
        check(StorageSense.Rules.validBytes(13) == 2048 * StorageSense.Rules.MIB, "And size");
        StorageSense.Plan clear =
                StorageSense.clearable(
                        Arrays.asList(saved("offline", 9, 1), playing, cache("old", 7, 1)));
        check(clear.items.size() == 1 && clear.bytes == 7, "Clearing only takes unprotected cache");
        check(clear.protectedCount == 1, "Saved files aren't counted as cache at all");
        check(
                StorageSenseAuto.due(rules, 0, NOW)
                        && !StorageSenseAuto.due(rules, NOW - 1000, NOW),
                "At most once a day");
        check(!StorageSenseAuto.due(new StorageSense.Rules(), 0, NOW), "Never with rules off");
    }

    static void guards() {
        long old = NOW - StorageSense.RECENT_MS - 1;
        StorageSense.Active unknown = StorageSense.Active.unknown();
        check(
                unknown.cacheBlock("k", "k", old, NOW) == StorageSense.Block.STATE_UNKNOWN,
                "Unknown state protects cache");
        check(
                unknown.savedBlock("u", Collections.singletonList("k"))
                        == StorageSense.Block.STATE_UNKNOWN,
                "Unknown state protects saved files");
        check(idle().cacheBlock("k", "k", old, NOW) == StorageSense.Block.NONE, "Idle and old");
        check(
                idle().cacheBlock("k", "k", NOW - 1000, NOW) == StorageSense.Block.RECENTLY_USED,
                "A recent read may still be in use");
        check(
                idle().cacheBlock("k", "k", NOW + 1000, NOW) == StorageSense.Block.RECENTLY_USED,
                "A clock in the future doesn't make it safe");
        check(
                idle().cacheBlock("k", "k", 0, NOW) == StorageSense.Block.RECENTLY_USED,
                "Neither does an unknown time");
        StorageSense.Active playing =
                new StorageSense.Active(
                        true,
                        Arrays.asList("vid", "src:deezer::77"),
                        Collections.singletonList("exact#key"),
                        Collections.singletonList("content://media/1"),
                        Collections.singletonList("dl"));
        for (String key :
                new String[] {
                    "vid",
                    "vid#alt",
                    "vid#alt-noatmos",
                    "vid#hifi",
                    "vid#watch",
                    "deezer|77",
                    "deezer|77#stereo",
                    "exact#key"
                })
            check(
                    playing.cacheBlock(key, "", old, NOW) == StorageSense.Block.PLAYING,
                    "Every rendition of what's playing: " + key);
        check(
                playing.cacheBlock("whatever", "vid", old, NOW) == StorageSense.Block.PLAYING,
                "The entry's own media id counts too");
        check(playing.cacheBlock("video", "", old, NOW) == StorageSense.Block.NONE, "Not a prefix");
        check(
                playing.cacheBlock("dl#alt", "", old, NOW) == StorageSense.Block.DOWNLOADING,
                "Active downloads are protected");
        check(
                playing.savedBlock("content://media/1", Collections.singletonList("x"))
                        == StorageSense.Block.PLAYING,
                "A saved file being played");
        check(
                playing.savedBlock("content://media/2", Arrays.asList("x", "dl"))
                        == StorageSense.Block.DOWNLOADING,
                "Any alias being downloaded");
    }

    static final class FakeOwner implements StorageSense.Owner {
        final List<String> removed = new ArrayList<>();
        final StorageSense.Category category;
        final List<StorageSense.Item> items = new ArrayList<>();
        StorageSense.Block recheck = StorageSense.Block.NONE;
        Exception listing;
        int lists;

        FakeOwner(StorageSense.Category category) {
            this.category = category;
        }

        @Override
        public StorageSense.Category[] categories() {
            return new StorageSense.Category[] {category};
        }

        @Override
        public StorageSense.Listing list(StorageSense.Active active, StorageSense.Cancel cancel)
                throws Exception {
            lists++;
            if (listing != null) throw listing;
            return new StorageSense.Listing(items, false);
        }

        @Override
        public StorageSense.Block recheck(StorageSense.Item item, StorageSense.Active active) {
            return recheck;
        }

        @Override
        public void remove(StorageSense.Item item) {
            removed.add(item.id);
        }
    }

    static void engine() {
        FakeOwner cacheOwner = new FakeOwner(StorageSense.Category.PLAYBACK_CACHE);
        FakeOwner broken = new FakeOwner(StorageSense.Category.TELEGRAM_CACHE);
        FakeOwner missing = new FakeOwner(StorageSense.Category.PATCH_CACHE);
        FakeOwner empty = new FakeOwner(StorageSense.Category.SAVED);
        cacheOwner.items.add(cache("one", 100, 1));
        cacheOwner.items.add(cache("two", 50, 1));
        broken.listing = new IOException("boom");
        missing.listing = new StorageSense.Unavailable("Sign in");
        int[] captures = {0};
        StorageSenseEngine engine =
                new StorageSenseEngine(
                        Arrays.asList(cacheOwner, broken, missing, empty),
                        () -> {
                            captures[0]++;
                            return idle();
                        },
                        () -> NOW);
        StorageSenseEngine.Snapshot snapshot = engine.load(StorageSense.NEVER);
        check(cacheOwner.removed.isEmpty(), "Loading removes nothing");
        check(
                snapshot.sections.get(StorageSense.Category.PLAYBACK_CACHE).bytes == 150,
                "Real byte totals");
        check(
                snapshot.sections.get(StorageSense.Category.PLAYBACK_CACHE).state
                        == StorageSenseEngine.State.READY,
                "A broken owner doesn't take the others with it");
        check(
                snapshot.sections.get(StorageSense.Category.TELEGRAM_CACHE).state
                        == StorageSenseEngine.State.ERROR,
                "Errors are their own state");
        check(
                snapshot.sections.get(StorageSense.Category.PATCH_CACHE).state
                        == StorageSenseEngine.State.UNAVAILABLE,
                "Unavailable is its own state");
        check(
                snapshot.sections.get(StorageSense.Category.SAVED).state
                        == StorageSenseEngine.State.EMPTY,
                "Empty is its own state");

        cacheOwner.recheck = StorageSense.Block.PLAYING;
        StorageSenseEngine.Outcome refused =
                engine.remove(new ArrayList<>(cacheOwner.items), StorageSense.NEVER);
        check(cacheOwner.removed.isEmpty(), "The state at removal wins over the scan");
        check(refused.blocked == 2 && refused.firstBlock == StorageSense.Block.PLAYING, "Reported");
        cacheOwner.recheck = StorageSense.Block.NONE;
        int before = captures[0];
        StorageSenseEngine.Outcome done =
                engine.remove(new ArrayList<>(cacheOwner.items), StorageSense.NEVER);
        check(done.removed == 2 && done.bytes == 150, "Removal reports real bytes");
        check(captures[0] - before == 2, "State is read again for every item");
        cacheOwner.removed.clear();
        int[] asked = {0};
        StorageSenseEngine.Outcome stopped =
                engine.remove(new ArrayList<>(cacheOwner.items), () -> asked[0]++ > 0);
        check(stopped.cancelled && cacheOwner.removed.size() == 1, "Cancels between items");
        StorageSense.Item readOnly = cache("readonly", 1, 1);
        readOnly.effect = StorageSense.Effect.NONE;
        readOnly.category = StorageSense.Category.PLAYBACK_CACHE;
        cacheOwner.removed.clear();
        engine.remove(Collections.singletonList(readOnly), StorageSense.NEVER);
        check(cacheOwner.removed.isEmpty(), "Read-only rows can't be removed");
        check(StorageSense.size(2048).equals("2 KB"), "Size label");
        check(StorageSense.size(1536L * 1024 * 1024).equals("1.5 GB"), "Large size label");
    }

    static void duplicates() {
        StorageSense.Item a = saved("a", 10, 1), b = saved("b", 10, 1), c = saved("c", 10, 1);
        StorageSense.Item sameNameOtherSize = saved("d", 11, 1);
        a.title = b.title = c.title = sameNameOtherSize.title = "Song.flac";
        for (StorageSense.Item item : Arrays.asList(a, b, c, sameNameOtherSize))
            item.location = "file:///" + item.id;
        Map<String, String> contents = new HashMap<>();
        contents.put("a", "same");
        contents.put("b", "same");
        contents.put("c", "different");
        contents.put("d", "same");
        StorageSense.DuplicateReview review =
                StorageSense.duplicates(
                        Arrays.asList(a, b, c, sameNameOtherSize),
                        (item, cancel) -> contents.get(item.id),
                        StorageSense.NEVER);
        check(review.groups.size() == 1, "One proven group");
        check(
                review.groups.get(0).containsAll(Arrays.asList(a, b))
                        && review.groups.get(0).size() == 2,
                "Same name and size with different contents isn't a duplicate");
        int[] hashed = {0};
        StorageSense.duplicates(
                Arrays.asList(a, sameNameOtherSize),
                (item, cancel) -> {
                    hashed[0]++;
                    return "x";
                },
                StorageSense.NEVER);
        check(hashed[0] == 0, "A size with one file isn't read at all");
        review =
                StorageSense.duplicates(
                        Arrays.asList(a, b),
                        (item, cancel) -> item == a ? "x" : null,
                        StorageSense.NEVER);
        check(review.groups.isEmpty() && review.unreadable == 1, "Unreadable proves nothing");
        review =
                StorageSense.duplicates(
                        Arrays.asList(a, b),
                        (item, cancel) -> {
                            throw new IOException("gone");
                        },
                        StorageSense.NEVER);
        check(review.groups.isEmpty() && review.unreadable == 2, "Read failures prove nothing");
        review = StorageSense.duplicates(Arrays.asList(a, b), (item, cancel) -> "x", () -> true);
        check(review.cancelled && review.groups.isEmpty(), "Cancelling stops the comparison");
        StorageSense.Item cached = cache("z", 10, 1);
        cached.location = "z";
        cached.title = "Song.flac";
        review =
                StorageSense.duplicates(
                        Arrays.asList(cached, a), (item, cancel) -> "x", StorageSense.NEVER);
        check(review.groups.isEmpty(), "Only saved files are compared");
    }

    static void ranges() {
        List<StorageSense.Range> merged =
                StorageSense.coalesce(
                        Arrays.asList(
                                new StorageSense.Range(10, 20),
                                new StorageSense.Range(0, 10),
                                new StorageSense.Range(30, 40),
                                new StorageSense.Range(35, 38),
                                new StorageSense.Range(50, 50)));
        check(merged.size() == 2, "Touching spans merge; empty ones vanish");
        check(merged.get(0).end == 20 && merged.get(1).start == 30, "In order");
        check(!StorageSense.coversWhole(merged, 40), "A hole means partial");
        check(
                StorageSense.coversWhole(
                        Collections.singletonList(new StorageSense.Range(0, 40)), 40),
                "Whole");
        check(
                !StorageSense.coversWhole(
                        Collections.singletonList(new StorageSense.Range(0, 40)), -1),
                "An unknown length can't be whole");
    }

    /** Shaped like the host: a public static cache whose object is the only way in. */
    public static final class FakeSpan implements Comparable<FakeSpan> {
        public final File file;
        public final boolean isCached;
        public final String key;
        public final long position, length, lastTouchTimestamp;

        FakeSpan(File file, boolean cached, String key, long position, long length, long touch) {
            this.file = file;
            this.isCached = cached;
            this.key = key;
            this.position = position;
            this.length = length;
            this.lastTouchTimestamp = touch;
        }

        @Override
        public int compareTo(FakeSpan other) {
            return Long.compare(position, other.position);
        }
    }

    public static final class FakeMetadata {
        public final Map<String, byte[]> metadata = new HashMap<>();
    }

    public static final class FakeCache {
        public final Map<String, TreeSet<FakeSpan>> spans = new HashMap<>();
        public final Map<String, FakeMetadata> meta = new HashMap<>();
        public final List<String> removed = new ArrayList<>();

        public HashSet<String> getKeys() {
            return new HashSet<>(spans.keySet());
        }

        public TreeSet<FakeSpan> getCachedSpans(String key) {
            TreeSet<FakeSpan> held = spans.get(key);
            return held == null ? new TreeSet<>() : new TreeSet<>(held);
        }

        public FakeMetadata getContentMetadata(String key) {
            return meta.computeIfAbsent(key, k -> new FakeMetadata());
        }

        public void removeResource(String key) {
            removed.add(key);
            spans.remove(key);
        }
    }

    public static final class FakeFlow {
        public int value;

        public Object getValue() {
            return value;
        }

        public void setValue(Object next) {
            value = (Integer) next;
        }
    }

    public static final class FakeAudioCache {
        public static FakeCache cache;
        public static final Map<String, String> analysisHeads = new HashMap<>();
        public static final Map<String, Object> renditionKeys = new HashMap<>();
        public static final FakeFlow _contentsChanged = new FakeFlow();
    }

    public static final class NoCacheField {}

    static void put(FakeCache cache, String key, String name, String value) {
        cache.getContentMetadata(key).metadata.put(name, value.getBytes());
    }

    static void hostCache() throws Exception {
        String owner = FakeAudioCache.class.getName();
        StorageSenseHostCache.BackendSource source = StorageSenseHostCache.Reflective.of(owner);
        StorageSenseHostCache cacheOwner = new StorageSenseHostCache(source, () -> NOW);
        FakeAudioCache.cache = null;
        try {
            cacheOwner.list(idle(), StorageSense.NEVER);
            check(false, "A cache that isn't open is unavailable");
        } catch (StorageSense.Unavailable expected) {
            checks++;
        }
        try {
            StorageSenseHostCache.Reflective.of(NoCacheField.class.getName()).open();
            check(false, "An unknown shape is unavailable");
        } catch (StorageSense.Unavailable expected) {
            checks++;
        }
        try {
            StorageSenseHostCache.Reflective.of("no.such.Host").open();
            check(false, "A missing owner is unavailable");
        } catch (StorageSense.Unavailable expected) {
            checks++;
        }
        File dir = Files.createTempDirectory("storage-sense").toFile();
        File fragment = new File(dir, "0.0.1.v3.exo");
        Files.write(fragment.toPath(), new byte[] {1, 2, 3});
        File vanished = new File(dir, "gone.exo");
        FakeCache cache = new FakeCache();
        FakeAudioCache.cache = cache;
        long old = NOW - StorageSense.RECENT_MS - 5000;
        TreeSet<FakeSpan> whole = new TreeSet<>();
        whole.add(new FakeSpan(fragment, true, "vid", 0, 600, old - 10));
        whole.add(new FakeSpan(fragment, true, "vid", 600, 400, old));
        cache.spans.put("vid", whole);
        put(cache, "vid", "bc_origin", "YOUTUBE");
        put(cache, "vid", "bc_media_id", "vid");
        put(cache, "vid", "bc_title", "A Song");
        put(cache, "vid", "bc_artist", "An Artist");
        cache.getContentMetadata("vid")
                .metadata
                .put("exo_len", ByteBuffer.allocate(8).putLong(1000).array());
        TreeSet<FakeSpan> addon = new TreeSet<>();
        addon.add(new FakeSpan(fragment, true, "s|t", 100, 50, old));
        cache.spans.put("s|t", addon);
        put(cache, "s|t", "bc_origin", "OTHER");
        put(cache, "s|t", "bc_media_id", "src:s::t");
        TreeSet<FakeSpan> gone = new TreeSet<>();
        gone.add(new FakeSpan(vanished, true, "gone", 0, 999, old));
        gone.add(new FakeSpan(fragment, false, "gone", 999, 999, old));
        cache.spans.put("gone", gone);

        StorageSense.Listing listing = cacheOwner.list(idle(), StorageSense.NEVER);
        check(listing.items.size() == 2, "Files the system deleted and holes aren't counted");
        StorageSense.Item song = null, stream = null;
        for (StorageSense.Item item : listing.items) {
            if (item.location.equals("vid")) song = item;
            else stream = item;
        }
        check(song.category == StorageSense.Category.PLAYBACK_CACHE, "YouTube is the song cache");
        check(stream.category == StorageSense.Category.ADDON_CACHE, "Addon origin is split out");
        check(song.title.equals("A Song") && song.subtitle.equals("An Artist"), "Owner's labels");
        check(song.bytes == 1000 && song.complete && song.contentLength == 1000, "Whole entry");
        check(song.ranges.size() == 1 && song.ranges.get(0).end == 1000, "Range through the owner");
        check(!stream.complete && stream.ranges.get(0).start == 100, "Partial entry and its range");
        check(song.effect == StorageSense.Effect.CACHE_RECLAIM, "Cache clears rather than deletes");
        check(song.block == StorageSense.Block.NONE && song.automatic, "Old, idle and tagged");
        check(cache.removed.isEmpty() && fragment.exists(), "Listing touches nothing");

        StorageSense.Active playing =
                new StorageSense.Active(
                        true,
                        Collections.singletonList("vid"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList());
        check(
                cacheOwner.recheck(song, playing) == StorageSense.Block.PLAYING,
                "Playing is found when removal is asked for");
        check(
                cacheOwner.recheck(song, StorageSense.Active.unknown())
                        == StorageSense.Block.STATE_UNKNOWN,
                "Unknown state refuses at removal");
        FakeAudioCache.analysisHeads.put("vid", "x");
        FakeAudioCache.renditionKeys.put("vid", "x");
        int version = FakeAudioCache._contentsChanged.value;
        cacheOwner.remove(song);
        check(cache.removed.equals(Collections.singletonList("vid")), "removeResource, by key");
        check(fragment.exists(), "The fragment file is the cache's to delete");
        check(
                FakeAudioCache.analysisHeads.isEmpty() && FakeAudioCache.renditionKeys.isEmpty(),
                "Bookkeeping about removed bytes goes too");
        check(FakeAudioCache._contentsChanged.value == version + 1, "An open folder is told");
        check(
                cacheOwner.recheck(song, idle()) == StorageSense.Block.NONE,
                "Already gone isn't an error");
        check(
                cacheOwner.recheck(song, playing) == StorageSense.Block.PLAYING,
                "Gone but in use again still refuses");
        fragment.delete();
        dir.delete();
    }

    public static void main(String[] args) throws Exception {
        rules();
        guards();
        engine();
        duplicates();
        ranges();
        hostCache();
        System.out.println("StorageSenseTest passed " + checks + " checks");
    }
}
