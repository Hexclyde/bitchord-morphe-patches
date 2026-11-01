package app.bitchord.telegram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What can be reviewed and reclaimed, and the rules that protect it. Anything not proven safe stays
 * where it is. Holds no Android state, so every rule here runs in the host tests.
 */
final class StorageSense {
    private StorageSense() {}

    enum Category {
        PLAYBACK_CACHE,
        ADDON_CACHE,
        TELEGRAM_CACHE,
        PATCH_CACHE,
        SAVED
    }

    /** What deleting an item costs: bytes that can come back, or a file that can't. */
    enum Effect {
        CACHE_RECLAIM,
        DELETE_SAVED_FILE,
        NONE
    }

    enum Block {
        NONE,
        PLAYING,
        DOWNLOADING,
        TRANSFER_ACTIVE,
        RECENTLY_USED,
        STATE_UNKNOWN,
        READ_ONLY,
        LOCATION_UNPROVEN
    }

    interface Cancel {
        boolean cancelled();
    }

    static final Cancel NEVER = () -> false;

    /** A category's owner is missing or not ready. Its message says so on the page. */
    static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }

    /** Thrown when a re-check right before removal finds the item in use. */
    static final class Refused extends Exception {
        final Block reason;

        Refused(Block reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    static final class Range {
        final long start, end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    /** One real resource, as named by whoever owns it. */
    static final class Item {
        String id = "", title = "", subtitle = "", origin = "", location = "", mediaId = "";
        Category category = Category.PATCH_CACHE;
        Effect effect = Effect.NONE;
        Block block = Block.READ_ONLY;
        long bytes, lastUsedMs, contentLength = -1;
        boolean complete, automatic;
        List<Range> ranges = Collections.emptyList();
        List<String> aliases = Collections.emptyList();

        boolean removable() {
            return block == Block.NONE && effect != Effect.NONE;
        }
    }

    /** What's playing or transferring right now. Unknown protects everything. */
    static final class Active {
        final boolean known;
        final Set<String> mediaIds, keys, uris, downloads;

        Active(
                boolean known,
                Collection<String> mediaIds,
                Collection<String> keys,
                Collection<String> uris,
                Collection<String> downloads) {
            this.known = known;
            this.mediaIds = new HashSet<>(mediaIds);
            this.keys = new HashSet<>(keys);
            this.uris = new HashSet<>(uris);
            this.downloads = new HashSet<>(downloads);
        }

        static Active unknown() {
            List<String> none = Collections.emptyList();
            return new Active(false, none, none, none, none);
        }

        Block cacheBlock(String key, String keyMediaId, long lastUsedMs, long now) {
            if (!known) return Block.STATE_UNKNOWN;
            if (keys.contains(key)) return Block.PLAYING;
            for (String id : mediaIds) if (family(key, keyMediaId, id)) return Block.PLAYING;
            for (String id : downloads) if (family(key, keyMediaId, id)) return Block.DOWNLOADING;
            // Automix decks, crossfades and copies out of the cache read without a lock of any
            // kind, so a recent read is the only sign they leave.
            if (lastUsedMs <= 0 || lastUsedMs > now || now - lastUsedMs < RECENT_MS)
                return Block.RECENTLY_USED;
            return Block.NONE;
        }

        Block savedBlock(String uri, Collection<String> ids) {
            if (!known) return Block.STATE_UNKNOWN;
            if (uris.contains(uri)) return Block.PLAYING;
            for (String id : ids) {
                if (mediaIds.contains(id)) return Block.PLAYING;
                if (downloads.contains(id)) return Block.DOWNLOADING;
            }
            return Block.NONE;
        }
    }

    /** A cache entry read this recently may still be in use. */
    static final long RECENT_MS = 10 * 60 * 1000L;

    /** Whether a cache key belongs to a track, in any rendition the host files it under. */
    static boolean family(String key, String keyMediaId, String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return false;
        if (mediaId.equals(keyMediaId)) return true;
        if (key.equals(mediaId) || key.startsWith(mediaId + "#")) return true;
        if (mediaId.startsWith("src:")) {
            int split = mediaId.indexOf("::");
            if (split > 4) {
                String base = mediaId.substring(4, split) + "|" + mediaId.substring(split + 2);
                return key.equals(base) || key.startsWith(base + "#");
            }
        }
        return false;
    }

    static List<Range> coalesce(List<Range> ranges) {
        List<Range> sorted = new ArrayList<>(ranges);
        Collections.sort(sorted, Comparator.comparingLong(r -> r.start));
        List<Range> merged = new ArrayList<>();
        for (Range range : sorted) {
            if (range.end <= range.start) continue;
            Range last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range.start <= last.end)
                merged.set(merged.size() - 1, new Range(last.start, Math.max(last.end, range.end)));
            else merged.add(range);
        }
        return merged;
    }

    static boolean coversWhole(List<Range> merged, long contentLength) {
        return contentLength > 0
                && merged.size() == 1
                && merged.get(0).start == 0
                && merged.get(0).end >= contentLength;
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024d;
        if (value < 1024) return String.format(Locale.ROOT, "%.0f KB", value);
        value /= 1024;
        if (value < 1024) return String.format(Locale.ROOT, "%.1f MB", value);
        return String.format(Locale.ROOT, "%.1f GB", value / 1024);
    }

    /** Opt-in cleanup. Both rules start off, and neither can reach a saved file. */
    static final class Rules {
        static final int[] AGE_DAYS = {7, 14, 30, 60, 90};
        static final long MIB = 1024L * 1024;
        static final long[] SIZE_BYTES = {
            256 * MIB, 512 * MIB, 1024 * MIB, 2048 * MIB, 4096 * MIB, 8192 * MIB, 16384 * MIB
        };
        boolean ageOn, sizeOn;
        int ageDays = 30;
        long maxBytes = 2048 * MIB;

        boolean any() {
            return ageOn || sizeOn;
        }

        static int validDays(int days) {
            for (int choice : AGE_DAYS) if (choice == days) return days;
            return 30;
        }

        static long validBytes(long bytes) {
            for (long choice : SIZE_BYTES) if (choice == bytes) return bytes;
            return 2048 * MIB;
        }
    }

    static final class Plan {
        final List<Item> items;
        final long bytes;
        final int protectedCount;

        Plan(List<Item> items, long bytes, int protectedCount) {
            this.items = items;
            this.bytes = bytes;
            this.protectedCount = protectedCount;
        }
    }

    private static boolean cache(Item item) {
        return item.category != Category.SAVED && item.effect == Effect.CACHE_RECLAIM;
    }

    /** What a rule may take: proven cache only, with a known age, nothing protected. */
    static boolean eligible(Item item) {
        return cache(item)
                && item.block == Block.NONE
                && item.automatic
                && item.lastUsedMs > 0
                && item.bytes > 0;
    }

    static Plan plan(List<Item> items, Rules rules, long now) {
        List<Item> oldest = new ArrayList<>();
        long total = 0;
        int protectedCount = 0;
        for (Item item : items) {
            if (!cache(item)) continue;
            total += item.bytes;
            if (eligible(item)) oldest.add(item);
            else protectedCount++;
        }
        Collections.sort(oldest, Comparator.comparingLong(i -> i.lastUsedMs));
        Set<Item> chosen = new LinkedHashSet<>();
        long freed = 0;
        if (rules.ageOn) {
            long cutoff = now - rules.ageDays * 86_400_000L;
            for (Item item : oldest)
                if (item.lastUsedMs < cutoff) {
                    chosen.add(item);
                    freed += item.bytes;
                }
        }
        if (rules.sizeOn) {
            for (Item item : oldest) {
                if (total - freed <= rules.maxBytes) break;
                if (chosen.add(item)) freed += item.bytes;
            }
        }
        return new Plan(new ArrayList<>(chosen), freed, protectedCount);
    }

    /** Everything removable in one cache category, for "clear" with its own confirmation. */
    static Plan clearable(List<Item> items) {
        List<Item> chosen = new ArrayList<>();
        long bytes = 0;
        int protectedCount = 0;
        for (Item item : items) {
            if (!cache(item)) continue;
            if (item.block == Block.NONE && item.bytes > 0) {
                chosen.add(item);
                bytes += item.bytes;
            } else protectedCount++;
        }
        return new Plan(chosen, bytes, protectedCount);
    }

    interface Hasher {
        /** The file's SHA-256, or null if it can't be read completely. */
        String sha256(Item item, Cancel cancel) throws IOException;
    }

    static final class DuplicateReview {
        final List<List<Item>> groups = new ArrayList<>();
        boolean truncated, cancelled;
        int unreadable;
    }

    static final long MAX_HASH_BYTES = 16L * 1024 * 1024 * 1024;
    static final int MAX_HASH_FILES = 400;

    /**
     * Files proven identical by their contents. Names are never compared, and a file that can't be
     * read through to the end doesn't count as anything.
     */
    static DuplicateReview duplicates(List<Item> items, Hasher hasher, Cancel cancel) {
        DuplicateReview review = new DuplicateReview();
        Map<Long, List<Item>> bySize = new HashMap<>();
        for (Item item : items) {
            if (item.category != Category.SAVED || item.bytes <= 0 || item.location.isEmpty())
                continue;
            List<Item> same = bySize.get(item.bytes);
            if (same == null) bySize.put(item.bytes, same = new ArrayList<>());
            same.add(item);
        }
        long budget = MAX_HASH_BYTES;
        int files = MAX_HASH_FILES;
        for (List<Item> same : bySize.values()) {
            if (same.size() < 2) continue;
            Map<String, List<Item>> byHash = new LinkedHashMap<>();
            for (Item item : same) {
                if (cancel.cancelled()) {
                    review.cancelled = true;
                    return review;
                }
                if (files <= 0 || budget < item.bytes) {
                    review.truncated = true;
                    continue;
                }
                files--;
                budget -= item.bytes;
                String hash;
                try {
                    hash = hasher.sha256(item, cancel);
                } catch (IOException unreadable) {
                    hash = null;
                }
                if (hash == null) {
                    if (cancel.cancelled()) {
                        review.cancelled = true;
                        return review;
                    }
                    review.unreadable++;
                    continue;
                }
                List<Item> identical = byHash.get(hash);
                if (identical == null) byHash.put(hash, identical = new ArrayList<>());
                identical.add(item);
            }
            for (List<Item> identical : byHash.values())
                if (identical.size() > 1) review.groups.add(identical);
        }
        return review;
    }

    /** Owns one kind of storage: lists it, and removes one resource through its own API. */
    interface Owner {
        Category[] categories();

        Listing list(Active active, Cancel cancel) throws Exception;

        /** The item's state right now. Removal never relies on what a scan saw earlier. */
        Block recheck(Item item, Active active) throws Exception;

        void remove(Item item) throws Exception;
    }

    static final class Listing {
        final List<Item> items;
        final boolean truncated;

        Listing(List<Item> items, boolean truncated) {
            this.items = items;
            this.truncated = truncated;
        }
    }

    private static final List<Owner> registered = new ArrayList<>();

    /** How another patch adds a cache it can prove safe, without a dependency either way. */
    static synchronized void register(Owner owner) {
        if (!registered.contains(owner)) registered.add(owner);
    }

    static synchronized List<Owner> registered() {
        return new ArrayList<>(registered);
    }
}
