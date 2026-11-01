package app.bitchord.telegram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BitChord's song cache, read and changed only through the cache object it owns. Its files are
 * fragments rather than songs, so a row is a resource key with the byte ranges it holds, and
 * nothing here opens or deletes a fragment.
 */
final class StorageSenseHostCache implements StorageSense.Owner {
    static final int MAX_KEYS = 5000;

    static final class Span {
        final long position, length, lastTouch;

        Span(long position, long length, long lastTouch) {
            this.position = position;
            this.length = length;
            this.lastTouch = lastTouch;
        }
    }

    interface Backend {
        Collection<String> keys() throws Exception;

        /** Spans on disk now. The system can delete a file underneath a running cache. */
        List<Span> spans(String key) throws Exception;

        Map<String, byte[]> metadata(String key) throws Exception;

        void removeResource(String key) throws Exception;

        void removed(String mediaId);
    }

    interface BackendSource {
        Backend open() throws StorageSense.Unavailable;
    }

    private static final StorageSense.Category[] CATEGORIES = {
        StorageSense.Category.PLAYBACK_CACHE, StorageSense.Category.ADDON_CACHE
    };
    private final BackendSource source;
    private final StorageSenseEngine.Clock clock;

    StorageSenseHostCache(BackendSource source, StorageSenseEngine.Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public StorageSense.Category[] categories() {
        return CATEGORIES;
    }

    private static String text(Map<String, byte[]> metadata, String name) {
        byte[] value = metadata.get(name);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    private static long length(Map<String, byte[]> metadata) {
        byte[] value = metadata.get("exo_len");
        return value != null && value.length == 8 ? ByteBuffer.wrap(value).getLong() : -1;
    }

    @Override
    public StorageSense.Listing list(StorageSense.Active active, StorageSense.Cancel cancel)
            throws Exception {
        Backend backend = source.open();
        List<StorageSense.Item> items = new ArrayList<>();
        boolean truncated = false;
        long now = clock.now();
        for (String key : backend.keys()) {
            if (cancel.cancelled()) break;
            if (items.size() >= MAX_KEYS) {
                truncated = true;
                break;
            }
            StorageSense.Item item = describe(backend, key, active, now);
            if (item != null) items.add(item);
        }
        return new StorageSense.Listing(items, truncated);
    }

    private static StorageSense.Item describe(
            Backend backend, String key, StorageSense.Active active, long now) throws Exception {
        List<Span> spans = backend.spans(key);
        long bytes = 0, touched = 0;
        List<StorageSense.Range> ranges = new ArrayList<>();
        for (Span span : spans) {
            bytes += span.length;
            touched = Math.max(touched, span.lastTouch);
            ranges.add(new StorageSense.Range(span.position, span.position + span.length));
        }
        if (bytes <= 0) return null;
        Map<String, byte[]> metadata = backend.metadata(key);
        String origin = text(metadata, "bc_origin");
        String mediaId = text(metadata, "bc_media_id");
        StorageSense.Item item = new StorageSense.Item();
        item.id = "cache:" + key;
        item.location = key;
        item.mediaId = mediaId;
        item.category =
                "OTHER".equals(origin)
                        ? StorageSense.Category.ADDON_CACHE
                        : StorageSense.Category.PLAYBACK_CACHE;
        item.effect = StorageSense.Effect.CACHE_RECLAIM;
        String title = text(metadata, "bc_title");
        item.title = !title.isEmpty() ? title : !mediaId.isEmpty() ? mediaId : key;
        item.subtitle = text(metadata, "bc_artist");
        item.origin = "1".equals(text(metadata, "bc_src_mixed")) ? "" : origin;
        if (!"1".equals(text(metadata, "bc_src_mixed")))
            item.sourceName = text(metadata, "bc_src_name");
        item.bytes = bytes;
        item.lastUsedMs = touched;
        item.contentLength = length(metadata);
        item.ranges = StorageSense.coalesce(ranges);
        item.complete = StorageSense.coversWhole(item.ranges, item.contentLength);
        // An entry the host never tagged may not be what it looks like, so only a person chooses.
        item.automatic = !origin.isEmpty();
        item.block = active.cacheBlock(key, mediaId, touched, now);
        return item;
    }

    @Override
    public StorageSense.Block recheck(StorageSense.Item item, StorageSense.Active active)
            throws Exception {
        Backend backend = source.open();
        long touched = 0;
        for (Span span : backend.spans(item.location)) touched = Math.max(touched, span.lastTouch);
        long now = clock.now();
        // Gone since the scan, so its age no longer matters; being in use still does.
        if (touched == 0) touched = now - StorageSense.RECENT_MS - 1;
        return active.cacheBlock(item.location, item.mediaId, touched, now);
    }

    @Override
    public void remove(StorageSense.Item item) throws Exception {
        Backend backend = source.open();
        backend.removeResource(item.location);
        if (!backend.spans(item.location).isEmpty())
            throw new java.io.IOException(Strings.t("Part of this cached song is still in use."));
        backend.removed(item.mediaId);
    }

    /**
     * The host's cache by shape; a shape it doesn't recognise makes this category read as absent.
     */
    static final class Reflective implements Backend {
        private final Class<?> owner;
        private final Object cache;
        private final Method keys, spans, metadata, remove;
        private Field file, isCached, position, length, touch, values;

        private Reflective(Class<?> owner, Object cache) throws ReflectiveOperationException {
            this.owner = owner;
            this.cache = cache;
            Class<?> type = cache.getClass();
            keys = type.getMethod("getKeys");
            spans = type.getMethod("getCachedSpans", String.class);
            metadata = type.getMethod("getContentMetadata", String.class);
            remove = type.getMethod("removeResource", String.class);
            if (!Collection.class.isAssignableFrom(keys.getReturnType())
                    || !Collection.class.isAssignableFrom(spans.getReturnType()))
                throw new NoSuchMethodException("Unexpected cache shape");
        }

        static BackendSource of(String ownerClass) {
            return () -> {
                try {
                    Class<?> type = Class.forName(ownerClass);
                    Object cache = type.getField("cache").get(null);
                    if (cache == null)
                        throw new StorageSense.Unavailable(
                                Strings.t("BitChord's song cache isn't open yet."));
                    return new Reflective(type, cache);
                } catch (ReflectiveOperationException | LinkageError unknown) {
                    throw new StorageSense.Unavailable(
                            Strings.t("This version of BitChord doesn't expose its song cache."));
                }
            };
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                try {
                    Field found = at.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException next) {
                    // Spans keep their fields on a superclass.
                }
            }
            throw new NoSuchFieldException(name);
        }

        @Override
        public Collection<String> keys() throws Exception {
            List<String> out = new ArrayList<>();
            for (Object key : (Collection<?>) keys.invoke(cache))
                if (key instanceof String) out.add((String) key);
            return out;
        }

        @Override
        public List<Span> spans(String key) throws Exception {
            List<Span> out = new ArrayList<>();
            for (Object span : (Collection<?>) spans.invoke(cache, key)) {
                if (file == null) {
                    Class<?> type = span.getClass();
                    file = field(type, "file");
                    isCached = field(type, "isCached");
                    position = field(type, "position");
                    length = field(type, "length");
                    touch = field(type, "lastTouchTimestamp");
                }
                Object where = file.get(span);
                if (!isCached.getBoolean(span)
                        || !(where instanceof java.io.File)
                        || !((java.io.File) where).exists()) continue;
                out.add(
                        new Span(
                                position.getLong(span), length.getLong(span), touch.getLong(span)));
            }
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, byte[]> metadata(String key) throws Exception {
            Object content = metadata.invoke(cache, key);
            if (values == null) values = field(content.getClass(), "metadata");
            Object map = values.get(content);
            Map<String, byte[]> out = new HashMap<>();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) map).entrySet())
                if (entry.getKey() instanceof String && entry.getValue() instanceof byte[])
                    out.put((String) entry.getKey(), (byte[]) entry.getValue());
            return out;
        }

        @Override
        public void removeResource(String key) throws Exception {
            remove.invoke(cache, key);
        }

        /**
         * What the host's own removal forgets, so an open folder and a later analysis stay right.
         */
        @Override
        public void removed(String mediaId) {
            if (mediaId != null && !mediaId.isEmpty())
                for (String name :
                        Arrays.asList(
                                "analysisHeads",
                                "analysisHeadsInFlight",
                                "analysisHeadWant",
                                "renditionKeys")) {
                    try {
                        Object held = field(owner, name).get(null);
                        if (held instanceof Map) ((Map<?, ?>) held).remove(mediaId);
                        else if (held instanceof Collection) ((Collection<?>) held).remove(mediaId);
                    } catch (ReflectiveOperationException | LinkageError absent) {
                        // Only a later analysis notices, and it asks for the track again.
                    }
                }
            try {
                Object flow = field(owner, "_contentsChanged").get(null);
                Method read = flow.getClass().getMethod("getValue");
                Method write = flow.getClass().getMethod("setValue", Object.class);
                read.setAccessible(true);
                write.setAccessible(true);
                write.invoke(flow, ((Integer) read.invoke(flow)) + 1);
            } catch (ReflectiveOperationException | ClassCastException | LinkageError absent) {
                // An open Cached songs folder refreshes the next time it's opened.
            }
        }
    }
}
