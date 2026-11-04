package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSpec;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Which source really filled each cache rendition and saved file. Null always means unknown.
 *
 * <p>A rendition's record lives in the content metadata of its own cache key, so it goes with the
 * bytes and survives a restart. Nothing here reads or changes audio, and no URL or header is kept.
 */
public final class AudioOrigin {
    private AudioOrigin() {}

    static final String PROOF_STREAM = "stream";
    static final String PROOF_EXPLICIT = "explicit";
    static final String PROOF_URI = "uri";
    static final String PROOF_HOST = "host";
    static final String PROOF_DOWNLOAD = "download";
    static final String PROOF_LEGACY_ORIGIN = "legacy-origin";
    static final String PROOF_LEGACY_TRACK = "legacy-track";

    static final String META_ID = "bc_src_id";
    static final String META_NAME = "bc_src_name";
    static final String META_KIND = "bc_src_kind";
    static final String META_PROOF = "bc_src_proof";
    static final String META_CODEC = "bc_src_codec";
    static final String META_KBPS = "bc_src_kbps";
    static final String META_BITS = "bc_src_bits";
    static final String META_RATE = "bc_src_rate";
    static final String META_MIXED = "bc_src_mixed";
    static final String META_ORIGIN = "bc_origin";

    private static final String KIND_YOUTUBE = "YOUTUBE";
    private static final String KIND_JIOSAAVN = "JIOSAAVN";
    private static final String YOUTUBE_ID = "youtube";
    private static final String YOUTUBE_NAME = "YouTube";
    private static final String JIOSAAVN_ID = "jiosaavn";
    private static final int MAX_NAME = 80;
    private static final int MAX_ID = 128;
    private static final int MAX_PENDING = 256;

    /** One source's identity, with what it advertised kept apart from anything measured. */
    public static final class Source {
        public final String id;

        /** Null when no safe name is known. Show "Unknown source" rather than guessing. */
        public final String name;

        /** The host's SourceKind name, or null when the source has been removed. */
        public final String kind;

        final String proof;

        /** What the source said about its stream. Advertised only, never a quality check. */
        public final String codec;

        public final Integer kbps;
        public final Integer bits;
        public final Integer rate;

        Source(
                String id,
                String name,
                String kind,
                String proof,
                String codec,
                Integer kbps,
                Integer bits,
                Integer rate) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.proof = proof;
            this.codec = codec;
            this.kbps = kbps;
            this.bits = bits;
            this.rate = rate;
        }

        public boolean isYouTube() {
            return KIND_YOUTUBE.equals(kind);
        }

        boolean sameSource(Source other) {
            return other != null && (id.equals(other.id) || (isYouTube() && other.isYouTube()));
        }

        Source withName(String nextName, String nextKind) {
            return new Source(id, nextName, nextKind, proof, codec, kbps, bits, rate);
        }

        /** This record with whatever it lacks taken from [other], or itself when nothing is new. */
        Source fill(Source other) {
            if ((name != null || other.name == null)
                    && (kind != null || other.kind == null)
                    && (codec != null || other.codec == null)
                    && (kbps != null || other.kbps == null)
                    && (bits != null || other.bits == null)
                    && (rate != null || other.rate == null)) return this;
            return new Source(
                    id,
                    name != null ? name : other.name,
                    kind != null ? kind : other.kind,
                    proof,
                    codec != null ? codec : other.codec,
                    kbps != null ? kbps : other.kbps,
                    bits != null ? bits : other.bits,
                    rate != null ? rate : other.rate);
        }
    }

    static final class Config {
        final String id;
        final String name;
        final String kind;

        Config(String id, String name, String kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }
    }

    static final class Stream {
        final String url;
        final String configId;
        final String codec;
        final Integer kbps;
        final Integer bits;
        final Integer rate;

        Stream(
                String url,
                String configId,
                String codec,
                Integer kbps,
                Integer bits,
                Integer rate) {
            this.url = url;
            this.configId = configId;
            this.codec = codec;
            this.kbps = kbps;
            this.bits = bits;
            this.rate = rate;
        }
    }

    static final class Request {
        final String authority;
        final String source;
        final String video;
        final boolean direct;
        final boolean analysis;

        Request(String authority, String source, String video, boolean direct, boolean analysis) {
            this.authority = authority;
            this.source = source;
            this.video = video;
            this.direct = direct;
            this.analysis = analysis;
        }
    }

    /** The host, kept behind one seam because R8 merged or removed most of its real names. */
    interface Host {
        String tag(String key, String name);

        void write(String key, String[] namesAndValues);

        boolean hasBytes(String key);

        String keyOf(Uri uri);

        Uri renditionUri(MediaItem item);

        String requestKey(DataSpec spec);

        List<String> keysOf(String mediaId);

        Stream forced(Uri uri);

        Stream chosen(String videoId);

        boolean substitute(String videoId);

        Stream stream(Object hostStream);

        Config config(String id);

        Config ofKind(String kind);

        Context context();
    }

    static volatile Host host = new AudioOriginHost();
    static volatile Executor worker =
            Executors.newSingleThreadExecutor(
                    task -> {
                        Thread thread = new Thread(task, "BitChord source records");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static final Object lock = new Object();
    private static final ThreadLocal<Stream> resolving = new ThreadLocal<>();
    private static final ThreadLocal<MediaItem> publishing = new ThreadLocal<>();
    private static final ThreadLocal<Source> routing = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> adopting = new ThreadLocal<>();
    private static final Map<Object, Source> routes =
            Collections.synchronizedMap(new WeakHashMap<Object, Source>());
    private static final Map<String, Source> pending = new ConcurrentHashMap<>();
    private static volatile Context context;

    // Which source served a request.

    /** The stream a source row resolved to. That branch does not go through StreamChoice. */
    public static void streamResolved(Object hostStream) {
        try {
            resolving.set(hostStream == null ? null : host.stream(hostStream));
        } catch (Throwable failed) {
            resolving.remove();
            report(failed);
        }
    }

    public static void recordServed(DataSpec requested, DataSpec resolved) {
        recordServed(
                requested,
                resolved == null || resolved.uri == null ? null : resolved.uri.toString());
    }

    /** Runs on a loader thread, once for every open, before the entry's bytes are written. */
    public static void recordServed(DataSpec requested, String servedUrl) {
        Stream captured = resolving.get();
        resolving.remove();
        try {
            if (requested == null || requested.uri == null || servedUrl == null) return;
            Uri uri = requested.uri;
            if (!"bitchord".equals(uri.getScheme())) return;
            Host h = host;
            // Only CacheDataSource sets a key. A read with none is a check made below the cache,
            // which must not describe the entry playback reads.
            String key = h.requestKey(requested);
            if (key == null) return;
            String video = uri.getQueryParameter("v");
            record(
                    key,
                    decide(
                            h,
                            new Request(
                                    uri.getAuthority(),
                                    uri.getQueryParameter("s"),
                                    video,
                                    "1".equals(uri.getQueryParameter("direct_youtube")),
                                    "1".equals(uri.getQueryParameter("automix_opus"))),
                            servedUrl,
                            h.forced(uri),
                            captured,
                            video == null ? null : h.chosen(video),
                            video != null && h.substitute(video)));
        } catch (Throwable failed) {
            report(failed);
        }
    }

    /** A stream counts only if it is what was served: another open may have chosen since. */
    static Source decide(
            Host h,
            Request request,
            String served,
            Stream forced,
            Stream captured,
            Stream chosen,
            boolean substitute) {
        if (served.startsWith("bitchord:")) return null;
        Stream matched = null;
        for (Stream stream : new Stream[] {forced, captured, chosen})
            if (stream != null && served.equals(stream.url)) {
                if (stream.configId != null)
                    return identified(h, stream.configId, PROOF_STREAM, stream);
                if (matched == null) matched = stream;
            }
        String server = hostOf(served);
        boolean youtube = under(server, "googlevideo.com") || under(server, "youtube.com");
        if (request.direct) return youtube ? youtube(h, PROOF_EXPLICIT, matched) : null;
        if (request.analysis) return youtube ? youtube(h, PROOF_EXPLICIT, matched) : null;
        if (matched != null && matched == chosen && substitute) return null;
        if ("source".equals(request.authority) && request.source != null)
            return identified(h, request.source, PROOF_URI, matched);
        if (youtube) return youtube(h, PROOF_HOST, matched);
        if (under(server, "saavncdn.com") || under(server, "jiosaavn.com"))
            return jioSaavn(h, PROOF_HOST, matched);
        return null;
    }

    private static Source identified(Host h, String id, String proof, Stream stream) {
        if (!validId(id)) return null;
        Config config = h.config(id);
        if (config != null && KIND_YOUTUBE.equals(config.kind)) return youtube(h, proof, stream);
        return new Source(
                id,
                config == null ? null : cleanName(config.name),
                config == null ? null : config.kind,
                proof,
                stream == null ? null : stream.codec,
                stream == null ? null : stream.kbps,
                stream == null ? null : stream.bits,
                stream == null ? null : stream.rate);
    }

    private static Source youtube(Host h, String proof, Stream stream) {
        Config config = h.ofKind(KIND_YOUTUBE);
        return new Source(
                config == null ? YOUTUBE_ID : config.id,
                YOUTUBE_NAME,
                KIND_YOUTUBE,
                proof,
                stream == null ? null : stream.codec,
                stream == null ? null : stream.kbps,
                stream == null ? null : stream.bits,
                stream == null ? null : stream.rate);
    }

    private static Source jioSaavn(Host h, String proof, Stream stream) {
        Config config = h.ofKind(KIND_JIOSAAVN);
        return new Source(
                config == null ? JIOSAAVN_ID : config.id,
                config == null ? "JioSaavn" : cleanName(config.name),
                KIND_JIOSAAVN,
                proof,
                stream == null ? null : stream.codec,
                stream == null ? null : stream.kbps,
                stream == null ? null : stream.bits,
                stream == null ? null : stream.rate);
    }

    private static boolean under(String server, String domain) {
        return server.equals(domain) || server.endsWith("." + domain);
    }

    private static String hostOf(String url) {
        int start = url.indexOf("://");
        if (start < 0) return "";
        start += 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        String authority = url.substring(start, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        int port = authority.indexOf(':');
        if (port >= 0) authority = authority.substring(0, port);
        return authority.toLowerCase(Locale.ROOT);
    }

    private static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID || id.contains("://")) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }

    /** A blank-labelled addon's name may be derived from its address; never keep a whole URL. */
    private static String cleanName(String name) {
        if (name == null || name.contains("://")) return null;
        String clean = name.replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").trim();
        if (clean.isEmpty()) return null;
        return clean.length() <= MAX_NAME ? clean : clean.substring(0, MAX_NAME);
    }

    // The record on each cache key.

    /** Writes only what changes. Every seek and continuation passes through here. */
    static void record(String key, Source next) {
        try {
            Host h = host;
            synchronized (lock) {
                boolean bytes = h.hasBytes(key);
                boolean mixed = "1".equals(h.tag(key, META_MIXED));
                if (mixed && bytes) return;
                Source current = mixed ? null : stored(h, key);
                if (next == null) {
                    if (current != null) h.write(key, new String[] {META_MIXED, "1"});
                    return;
                }
                if (current != null) {
                    if (current.sameSource(next)) {
                        Source filled = current.fill(next);
                        if (filled != current) h.write(key, pairs(filled));
                    } else if (bytes) h.write(key, new String[] {META_MIXED, "1"});
                    else h.write(key, pairs(next));
                    return;
                }
                // Bytes with no record predate this one, so they must be proved to be the same.
                if (bytes && !mixed && !next.sameSource(legacy(h, key))) {
                    h.write(key, new String[] {META_MIXED, "1"});
                    return;
                }
                h.write(key, pairs(next));
            }
        } catch (Throwable failed) {
            report(failed);
        }
    }

    private static String[] pairs(Source source) {
        return new String[] {
            META_ID, source.id,
            META_NAME, text(source.name),
            META_KIND, text(source.kind),
            META_PROOF, text(source.proof),
            META_CODEC, text(source.codec),
            META_KBPS, text(source.kbps),
            META_BITS, text(source.bits),
            META_RATE, text(source.rate),
            META_MIXED, ""
        };
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Source stored(Host h, String key) {
        String id = blankless(h.tag(key, META_ID));
        if (id == null) return null;
        return new Source(
                id,
                blankless(h.tag(key, META_NAME)),
                blankless(h.tag(key, META_KIND)),
                blankless(h.tag(key, META_PROOF)),
                blankless(h.tag(key, META_CODEC)),
                number(h.tag(key, META_KBPS)),
                number(h.tag(key, META_BITS)),
                number(h.tag(key, META_RATE)));
    }

    private static String blankless(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static Integer number(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * What the host's own records prove about bytes that have no record of ours.
     *
     * <p>The host's origin is a last-writer value, and a read made below the cache can set it on
     * the entry playback uses, so it is not evidence for the "#" renditions that exist to hold
     * something other than YouTube's own stream.
     */
    private static Source legacy(Host h, String key) {
        if (!h.hasBytes(key)) return null;
        String origin = h.tag(key, META_ORIGIN);
        int pipe = key.indexOf('|');
        if (pipe > 0) {
            if ("YOUTUBE".equals(origin) || "JIOSAAVN".equals(origin)) return null;
            String id = key.substring(0, pipe);
            return validId(id) && h.config(id) != null
                    ? identified(h, id, PROOF_LEGACY_TRACK, null)
                    : null;
        }
        if (key.indexOf('#') >= 0) return null;
        if ("YOUTUBE".equals(origin)) return youtube(h, PROOF_LEGACY_ORIGIN, null);
        if ("JIOSAAVN".equals(origin)) return jioSaavn(h, PROOF_LEGACY_ORIGIN, null);
        return null;
    }

    /** Stored facts, with the name as the source is called now when it still exists. */
    private static Source current(Host h, Source source) {
        if (source.isYouTube()) return source.withName(YOUTUBE_NAME, KIND_YOUTUBE);
        Config config = h.config(source.id);
        if (config == null) return source;
        String name = cleanName(config.name);
        return source.withName(name != null ? name : source.name, config.kind);
    }

    /** Who filled the entry [key]. Reads no disk. */
    public static Source cachedSource(String key) {
        if (key == null) return null;
        try {
            Host h = host;
            synchronized (lock) {
                if ("1".equals(h.tag(key, META_MIXED))) return null;
                Source source = stored(h, key);
                return source != null ? current(h, source) : legacy(h, key);
            }
        } catch (Throwable failed) {
            report(failed);
            return null;
        }
    }

    /** Who filled the rendition [item] plays from, or the saved file it plays. */
    public static Source activeSource(MediaItem item) {
        try {
            Uri uri = host.renditionUri(item);
            if (uri == null) return null;
            if ("bitchord".equals(uri.getScheme())) return cachedSource(host.keyOf(uri));
            return savedSource(item.mediaId, uri.toString());
        } catch (Throwable failed) {
            report(failed);
            return null;
        }
    }

    /** Every cache key of [mediaId] with its source, null when unknown. Off the main thread. */
    public static Map<String, Source> renditions(String mediaId) {
        Map<String, Source> sources = new LinkedHashMap<>();
        if (mediaId == null) return sources;
        try {
            for (String key : host.keysOf(mediaId)) sources.put(key, cachedSource(key));
        } catch (Throwable failed) {
            report(failed);
        }
        return sources;
    }

    /** Whether [key] holds bytes known to be YouTube's own. Unknown is always false. */
    public static boolean provenYouTube(String key) {
        try {
            Source source = cachedSource(key);
            return source != null && source.isYouTube() && host.hasBytes(key);
        } catch (Throwable failed) {
            report(failed);
            return false;
        }
    }

    /** The track became current. A replay from disk never resolves, so old proof is saved here. */
    public static void played(MediaItem item) {
        try {
            Uri uri = host.renditionUri(item);
            if (uri == null || !"bitchord".equals(uri.getScheme())) return;
            worker.execute(() -> backfill(uri));
        } catch (Throwable failed) {
            report(failed);
        }
    }

    private static void backfill(Uri uri) {
        try {
            Host h = host;
            String key = h.keyOf(uri);
            if (key == null) return;
            synchronized (lock) {
                if (blankless(h.tag(key, META_ID)) != null || "1".equals(h.tag(key, META_MIXED)))
                    return;
                Source proven = legacy(h, key);
                if (proven != null) h.write(key, pairs(proven));
            }
        } catch (Throwable failed) {
            report(failed);
        }
    }

    static Uri uriOf(MediaItem item) {
        return item == null || item.localConfiguration == null ? null : item.localConfiguration.uri;
    }

    // What Stats shows.

    public static void statsItem(MediaItem item) {
        publishing.set(item);
    }

    /**
     * The name for the rendition being heard, replacing the host's name for the media id, which
     * read-ahead, analysis and auditions also write.
     */
    public static String statsName(String hostName) {
        MediaItem item = publishing.get();
        publishing.remove();
        try {
            Uri uri = host.renditionUri(item);
            if (uri == null) return hostLabel(hostName);
            Source source = activeSource(item);
            if (source != null) return source.name;
            String scheme = uri.getScheme();
            return "bitchord".equals(scheme) || "file".equals(scheme) || "content".equals(scheme)
                    ? null
                    : hostLabel(hostName);
        } catch (Throwable failed) {
            report(failed);
            return hostLabel(hostName);
        }
    }

    /** The host calls anything it cannot place YouTube, so that name alone proves nothing. */
    static String hostLabel(String hostName) {
        return hostName != null && YOUTUBE_NAME.equalsIgnoreCase(hostName.trim()) ? null : hostName;
    }

    // Saved files.

    public static void recordSaved(String id, String uri, Source source) {
        if (id == null || uri == null || source == null) return;
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null) AudioOriginSaved.put(prefs, id, uri, source);
        } catch (Throwable failed) {
            report(failed);
        }
    }

    /** Who a saved file came from. The file must be the one recorded, not just the same id. */
    public static Source savedSource(String id, String uri) {
        if (id == null || uri == null) return null;
        try {
            SharedPreferences prefs = prefs();
            Source saved = prefs == null ? null : AudioOriginSaved.get(prefs, id, uri);
            return saved == null ? null : current(host, saved);
        } catch (Throwable failed) {
            report(failed);
            return null;
        }
    }

    public static void forgetSaved(String id) {
        if (id == null) return;
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null) AudioOriginSaved.remove(prefs, id);
        } catch (Throwable failed) {
            report(failed);
        }
    }

    /** A row's id names the source it was picked from: {@code src:<config>::<track>}. */
    private static Source rowSource(String id) {
        if (!id.startsWith("src:")) return null;
        int separator = id.indexOf("::", 4);
        if (separator <= 4) return null;
        String config = id.substring(4, separator);
        return validId(config) && host.config(config) != null
                ? identified(host, config, PROOF_LEGACY_TRACK, null)
                : null;
    }

    private static SharedPreferences prefs() {
        Context app = context;
        if (app == null) app = host.context();
        return app == null ? null : app.getSharedPreferences(AudioOriginSaved.PREFS, 0);
    }

    // The download path. The host's Route has no source on it, so one is bound as it is made.

    public static void sourceRouting(Object kept) {
        try {
            Stream stream = host.stream(field(kept, "stream"));
            routing.set(
                    stream == null || stream.configId == null
                            ? null
                            : identified(host, stream.configId, PROOF_DOWNLOAD, stream));
        } catch (Throwable failed) {
            routing.remove();
            report(failed);
        }
    }

    public static void cacheRouting(Object copy) {
        try {
            routing.set(cachedSource((String) field(copy, "key")));
        } catch (Throwable failed) {
            routing.remove();
            report(failed);
        }
    }

    public static void routed(Object route) {
        Source source = routing.get();
        routing.remove();
        if (route != null && source != null) routes.put(route, source);
    }

    static void localRoute(Object route, String uri) {
        if (route == null || uri == null) return;
        Uri file = Uri.parse(uri);
        if (!"file".equals(file.getScheme()) && !"content".equals(file.getScheme())) return;
        routes.put(
                route,
                new Source(
                        "local",
                        Strings.t("This device"),
                        "LOCAL",
                        PROOF_DOWNLOAD,
                        null,
                        null,
                        null,
                        null));
    }

    /** The host's own YouTube download route, its last resort when no source had the track. */
    public static void youtubeRoute(Object route) {
        try {
            if (route != null) routes.put(route, youtube(host, PROOF_DOWNLOAD, null));
        } catch (Throwable failed) {
            report(failed);
        }
    }

    /** Null arguments are a resumed coroutine, which has already been through here. */
    public static void transferring(Context app, Object song, Object plan) {
        try {
            if (app != null) context = app.getApplicationContext();
            if (song == null || plan == null) return;
            String id = (String) field(song, "videoId");
            Object route = field(plan, "route");
            Source source = route == null ? null : routes.get(route);
            if (pending.size() >= MAX_PENDING) pending.clear();
            if (source == null) pending.remove(id);
            else pending.put(id, source);
        } catch (Throwable failed) {
            report(failed);
        }
    }

    /** Bit 8 is the omitted format, which only the two adopt-an-existing-file paths leave out. */
    public static void adopting(int defaults) {
        adopting.set((defaults & 8) != 0);
    }

    /**
     * A file was filed under the song's ids. An adopted file is whatever was already there, not
     * what the route would have fetched, so it only keeps a record that file already has.
     */
    public static void remembered(Object asked, Object fetched, Uri uri) {
        boolean adopted = Boolean.TRUE.equals(adopting.get());
        adopting.remove();
        try {
            String askedId = (String) field(asked, "videoId");
            String fetchedId = (String) field(fetched, "videoId");
            String file = uri.toString();
            Source source = askedId == null ? null : pending.remove(askedId);
            if (adopted) {
                SharedPreferences prefs = prefs();
                source = prefs == null ? null : AudioOriginSaved.ofFile(prefs, file);
            } else if (source == null) {
                source = askedId == null ? null : rowSource(askedId);
                if (source == null && fetchedId != null) source = rowSource(fetchedId);
            }
            if (source == null) return;
            recordSaved(askedId, file, source);
            if (fetchedId != null && !fetchedId.equals(askedId))
                recordSaved(fetchedId, file, source);
        } catch (Throwable failed) {
            report(failed);
        }
    }

    public static void downloadEnded(String id) {
        if (id != null) pending.remove(id);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        if (owner == null) return null;
        Field member = owner.getClass().getField(name);
        return member.get(owner);
    }

    private static volatile boolean reported;

    /** Source records must never stop audio. Anything wrong here reads as unknown. */
    private static void report(Throwable failed) {
        if (reported) return;
        reported = true;
        HostLog.d("Source records unavailable: " + failed.getClass().getSimpleName());
    }
}
