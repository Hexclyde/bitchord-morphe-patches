package app.bitchord.telegram;

import app.bitchord.telegram.AudioOrigin.Config;
import app.bitchord.telegram.AudioOrigin.Request;
import app.bitchord.telegram.AudioOrigin.Source;
import app.bitchord.telegram.AudioOrigin.Stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AudioOriginTest {
    static int checks;

    static void check(boolean value, String text) {
        checks++;
        if (!value) throw new AssertionError(text);
    }

    static final String GOOGLE = "https://rr1.googlevideo.com/videoplayback?sig=secret";
    static final String SAAVN = "https://aac.saavncdn.com/1/song.mp4";
    static final String LOCAL = "http://127.0.0.1:8080/telegram/track?token=secret";
    static final String PROXY = "https://proxy.qobuz-mirror.example/track.flac";

    /** The host's records, in memory. Counts writes so re-opens can be proved silent. */
    static class Memory implements AudioOrigin.Host {
        final Map<String, Map<String, String>> tags = new HashMap<>();
        final Set<String> bytes = new HashSet<>();
        final Map<String, Config> configs = new HashMap<>();
        int writes;

        Memory() {
            add("yt-uuid", "YouTube Music", "YOUTUBE");
            add("saavn-uuid", "JioSaavn", "JIOSAAVN");
            add("telegram-native", "Unofficial Telegram", "ADDON");
            add("flacdownloader-native", "Flac Downloader", "ADDON");
            add("qobuz-uuid", "Qobuz", "ADDON");
        }

        void add(String id, String name, String kind) {
            configs.put(id, new Config(id, name, kind));
        }

        @Override
        public String tag(String key, String name) {
            Map<String, String> row = tags.get(key);
            return row == null ? null : row.get(name);
        }

        @Override
        public void write(String key, String[] pairs) {
            writes++;
            Map<String, String> row = tags.computeIfAbsent(key, k -> new HashMap<>());
            for (int i = 0; i < pairs.length; i += 2) row.put(pairs[i], pairs[i + 1]);
        }

        @Override
        public boolean hasBytes(String key) {
            return bytes.contains(key);
        }

        @Override
        public List<String> keysOf(String mediaId) {
            List<String> keys = new ArrayList<>();
            for (String key : tags.keySet()) if (key.startsWith(mediaId)) keys.add(key);
            return keys;
        }

        @Override
        public Config config(String id) {
            return configs.get(id);
        }

        @Override
        public Config ofKind(String kind) {
            for (Config config : configs.values()) if (kind.equals(config.kind)) return config;
            return null;
        }

        @Override
        public String keyOf(android.net.Uri uri) {
            throw new AssertionError("Not used by the host tests");
        }

        @Override
        public android.net.Uri renditionUri(androidx.media3.common.MediaItem item) {
            return AudioOrigin.uriOf(item);
        }

        @Override
        public String requestKey(androidx.media3.datasource.DataSpec spec) {
            throw new AssertionError("Not used by the host tests");
        }

        @Override
        public Stream forced(android.net.Uri uri) {
            return null;
        }

        @Override
        public Stream chosen(String videoId) {
            return null;
        }

        @Override
        public boolean substitute(String videoId) {
            return false;
        }

        @Override
        public Stream stream(Object raw) {
            return (Stream) raw;
        }

        @Override
        public android.content.Context context() {
            return null;
        }
    }

    static Stream stream(String url, String id) {
        return new Stream(url, id, null, null, null, null);
    }

    static Request watch() {
        return new Request("watch", null, "abc", false, false);
    }

    static Request row(String id) {
        return new Request("source", id, null, false, false);
    }

    static Source decide(
            Memory host,
            Request request,
            String served,
            Stream forced,
            Stream captured,
            Stream chosen,
            boolean substitute) {
        return AudioOrigin.decide(host, request, served, forced, captured, chosen, substitute);
    }

    static void decisions() {
        Memory h = new Memory();
        Source telegram =
                decide(h, watch(), LOCAL, null, null, stream(LOCAL, "telegram-native"), true);
        check(telegram.id.equals("telegram-native"), "A chosen Telegram stream keeps its id");
        check(telegram.name.equals("Unofficial Telegram"), "It keeps its configured name");
        check(!telegram.isYouTube(), "It is not YouTube");

        Source addonOnGoogle =
                decide(h, watch(), GOOGLE, null, null, stream(GOOGLE, "qobuz-uuid"), true);
        check(
                addonOnGoogle.id.equals("qobuz-uuid"),
                "An addon serving googlevideo stays the addon");
        check(!addonOnGoogle.isYouTube(), "A googlevideo URL does not outrank the chosen source");

        Source upgrade =
                decide(
                        h,
                        watch(),
                        LOCAL,
                        stream(LOCAL, "telegram-native"),
                        null,
                        stream(GOOGLE, null),
                        false);
        check(upgrade.id.equals("telegram-native"), "An upgrade's stream wins");

        Source servedRow =
                decide(
                        h,
                        row("flacdownloader-native"),
                        PROXY,
                        null,
                        stream(PROXY, "qobuz-uuid"),
                        null,
                        false);
        check(servedRow.id.equals("qobuz-uuid"), "The stream's source outranks the row's source");
        Source rowOnly = decide(h, row("qobuz-uuid"), PROXY, null, null, null, false);
        check(rowOnly.id.equals("qobuz-uuid"), "A source row with no stream uses s=");
        check(rowOnly.proof.equals(AudioOrigin.PROOF_URI), "That proof is recorded");
        Source rowOnGoogle = decide(h, row("qobuz-uuid"), GOOGLE, null, null, null, false);
        check(rowOnGoogle.id.equals("qobuz-uuid"), "A googlevideo row stays its source");

        check(
                decide(
                                h,
                                watch(),
                                PROXY,
                                null,
                                stream(GOOGLE, "telegram-native"),
                                stream(GOOGLE, "qobuz-uuid"),
                                true)
                        == null,
                "A stream for some other URL proves nothing");
        check(
                decide(h, watch(), PROXY, null, null, null, false) == null,
                "A provider is never invented from a proxy host");
        check(
                decide(h, watch(), GOOGLE, null, null, stream(GOOGLE, null), true) == null,
                "A substitute with no identity on googlevideo is not YouTube");
        check(
                decide(h, watch(), "bitchord://watch?v=abc", null, null, null, false) == null,
                "An unresolved request proves nothing");
        check(
                decide(
                                h,
                                row("qobuz-uuid"),
                                "bitchord://source?s=qobuz-uuid",
                                null,
                                null,
                                null,
                                false)
                        == null,
                "Nor does an unresolved source row");
        check(
                decide(h, watch(), "https://evilgooglevideo.com/a", null, null, null, false)
                        == null,
                "A look-alike host is not YouTube");

        Source youtube = decide(h, watch(), GOOGLE, null, null, stream(GOOGLE, null), false);
        check(youtube.isYouTube(), "A real YouTube stream is YouTube");
        check(youtube.id.equals("yt-uuid"), "It carries YouTube's config id");
        check(youtube.name.equals("YouTube"), "Named the way Stats names it");
        check(
                decide(h, watch(), GOOGLE, null, null, null, false).isYouTube(),
                "Held-by-YouTube continuation is YouTube");
        check(
                decide(
                                h,
                                new Request("watch", null, "abc", true, false),
                                GOOGLE,
                                null,
                                null,
                                null,
                                false)
                        .proof
                        .equals(AudioOrigin.PROOF_EXPLICIT),
                "An explicit rollback is YouTube by request");
        check(
                decide(
                                h,
                                new Request("watch", null, "abc", true, false),
                                LOCAL,
                                null,
                                null,
                                null,
                                false)
                        == null,
                "An explicit YouTube request still needs a matching served host");
        check(
                decide(
                                h,
                                new Request("watch", null, "abc", false, true),
                                GOOGLE,
                                null,
                                null,
                                null,
                                false)
                        .isYouTube(),
                "An analysis read tags its own entry truthfully");
        check(
                decide(
                                h,
                                new Request("watch", null, "abc", false, true),
                                LOCAL,
                                null,
                                null,
                                null,
                                false)
                        == null,
                "An analysis read on another host is not YouTube");
        check(
                decide(h, watch(), SAAVN, null, null, null, false).id.equals("saavn-uuid"),
                "JioSaavn's CDN is JioSaavn");
        Memory bare = new Memory();
        bare.configs.remove("yt-uuid");
        check(
                decide(bare, watch(), GOOGLE, null, null, null, false).id.equals("youtube"),
                "YouTube keeps a stable id with no config");

        Source advertised =
                decide(
                        h,
                        watch(),
                        LOCAL,
                        null,
                        null,
                        new Stream(LOCAL, "telegram-native", "flac", 900, 24, 96000),
                        true);
        check(
                "flac".equals(advertised.codec)
                        && advertised.kbps == 900
                        && advertised.bits == 24
                        && advertised.rate == 96000,
                "Advertised format is kept separately from identity");
    }

    static void hygiene() {
        Memory h = new Memory();
        h.add("token-source", "https://user:pw@host/path?key=1", "ADDON");
        h.add("spaced", " Mine\n\tAddon‮ ", "ADDON");
        h.add("x".repeat(40), "y".repeat(200), "ADDON");
        check(
                decide(h, row("token-source"), PROXY, null, null, null, false).name == null,
                "A credential-bearing name is dropped");
        check(
                decide(h, row("spaced"), PROXY, null, null, null, false).name.equals("Mine Addon"),
                "A name is cleaned");
        check(
                decide(h, row("x".repeat(40)), PROXY, null, null, null, false).name.length() <= 80,
                "A name is bounded");
        check(
                decide(h, row("https://host/a?token=1"), PROXY, null, null, null, false) == null,
                "A URL is not an id");
        check(
                decide(h, row("two words"), PROXY, null, null, null, false) == null,
                "A malformed id is not an id");

        AudioOrigin.host = h;
        AudioOrigin.record(
                "k", decide(h, watch(), LOCAL, null, null, stream(LOCAL, "qobuz-uuid"), true));
        String all = h.tags.get("k").toString();
        check(!all.contains("127.0.0.1") && !all.contains("secret"), "No URL or token is stored");
    }

    static Source telegram() {
        return new Source(
                "telegram-native",
                "Unofficial Telegram",
                "ADDON",
                AudioOrigin.PROOF_STREAM,
                "flac",
                900,
                24,
                96000);
    }

    static Source qobuz() {
        return new Source(
                "qobuz-uuid", "Qobuz", "ADDON", AudioOrigin.PROOF_STREAM, null, null, null, null);
    }

    static void writes() {
        Memory h = new Memory();
        AudioOrigin.host = h;

        AudioOrigin.record("a", telegram());
        check(h.writes == 1, "First open writes");
        Source read = AudioOrigin.cachedSource("a");
        check(read.id.equals("telegram-native") && read.name.equals("Unofficial Telegram"), "Read");
        check(read.kbps == 900 && read.bits == 24 && read.rate == 96000, "Format reads back");

        h.bytes.add("a");
        for (int i = 0; i < 5; i++) AudioOrigin.record("a", telegram());
        check(h.writes == 1, "Every re-open of the same source is silent");

        AudioOrigin.record(
                "partial",
                new Source("telegram-native", "Old", "ADDON", "stream", null, null, null, null));
        h.writes = 0;
        AudioOrigin.record("partial", telegram());
        check(h.writes == 1, "Missing advertised fields are filled in once");
        check(AudioOrigin.cachedSource("partial").codec.equals("flac"), "They are filled in");
        h.writes = 0;
        AudioOrigin.record(
                "partial",
                new Source("telegram-native", "Old", "ADDON", "stream", null, null, null, null));
        check(h.writes == 0, "Known fields are never erased");

        AudioOrigin.record("stale", qobuz());
        AudioOrigin.record("stale", telegram());
        check(
                AudioOrigin.cachedSource("stale").id.equals("telegram-native"),
                "A tag with no bytes behind it is replaced");
        check(AudioOrigin.cachedSource("stale").codec.equals("flac"), "All fields replaced");
        AudioOrigin.record("stale2", telegram());
        AudioOrigin.record("stale2", qobuz());
        check(AudioOrigin.cachedSource("stale2").codec == null, "Old format does not carry over");

        h.bytes.add("mix");
        AudioOrigin.record("mix", null);
        check(AudioOrigin.cachedSource("mix") == null, "No record stays unknown");
        check(h.tag("mix", AudioOrigin.META_MIXED) == null, "An unknown read writes nothing new");
        AudioOrigin.record("mix", telegram());
        check(AudioOrigin.cachedSource("mix") == null, "Untagged bytes plus a source are unknown");
        AudioOrigin.record("mix", telegram());
        check(AudioOrigin.cachedSource("mix") == null, "Mixed bytes stay unknown");

        AudioOrigin.record("two", qobuz());
        h.bytes.add("two");
        AudioOrigin.record("two", telegram());
        check(AudioOrigin.cachedSource("two") == null, "Two sources' bytes are unknown");
        h.bytes.remove("two");
        AudioOrigin.record("two", telegram());
        check(
                AudioOrigin.cachedSource("two").id.equals("telegram-native"),
                "Once the mixed bytes are gone the entry is clean");

        AudioOrigin.record("then", qobuz());
        h.bytes.add("then");
        AudioOrigin.record("then", null);
        check(AudioOrigin.cachedSource("then") == null, "An unprovable continuation is unknown");
        AudioOrigin.record("gone", qobuz());
        AudioOrigin.record("gone", null);
        check(AudioOrigin.cachedSource("gone") == null, "An unprovable refill clears a stale tag");
    }

    static void sameYouTube() {
        Memory h = new Memory();
        AudioOrigin.host = h;
        AudioOrigin.record(
                "yt",
                new Source(
                        "youtube",
                        "YouTube",
                        "YOUTUBE",
                        AudioOrigin.PROOF_HOST,
                        null,
                        null,
                        null,
                        null));
        h.bytes.add("yt");
        h.writes = 0;
        AudioOrigin.record(
                "yt",
                new Source(
                        "yt-uuid",
                        "YouTube",
                        "YOUTUBE",
                        AudioOrigin.PROOF_HOST,
                        null,
                        null,
                        null,
                        null));
        check(AudioOrigin.cachedSource("yt") != null, "YouTube under either id is one source");
        check(h.writes == 0, "So nothing is rewritten");
        check(AudioOrigin.provenYouTube("yt"), "Eviction may trust it");
        h.bytes.remove("yt");
        check(!AudioOrigin.provenYouTube("yt"), "Nothing to evict without bytes");
    }

    static void names() {
        Memory h = new Memory();
        AudioOrigin.host = h;
        AudioOrigin.record("a", qobuz());
        h.add("qobuz-uuid", "Renamed", "ADDON");
        check(AudioOrigin.cachedSource("a").name.equals("Renamed"), "A rename shows at once");
        h.configs.remove("qobuz-uuid");
        Source removed = AudioOrigin.cachedSource("a");
        check(removed.name.equals("Qobuz"), "A removed source keeps its name after a restart");
        check(removed.id.equals("qobuz-uuid"), "And its id");
        h.add("qobuz-uuid", "https://t.example/?token=1", "ADDON");
        check(
                AudioOrigin.cachedSource("a").name.equals("Qobuz"),
                "An unsafe rename falls back on the stored name");
    }

    static void legacy() {
        Memory h = new Memory();
        AudioOrigin.host = h;
        h.bytes.add("plain");
        h.write("plain", new String[] {AudioOrigin.META_ORIGIN, "YOUTUBE"});
        check(
                AudioOrigin.cachedSource("plain").isYouTube(),
                "Host origin on a plain key proves it");
        check(
                AudioOrigin.cachedSource("plain").proof.equals(AudioOrigin.PROOF_LEGACY_ORIGIN),
                "With a recorded proof");

        h.bytes.add("abc#alt");
        h.write("abc#alt", new String[] {AudioOrigin.META_ORIGIN, "YOUTUBE"});
        check(
                AudioOrigin.cachedSource("abc#alt") == null,
                "The substitution bucket's host origin is not proof of YouTube");

        h.bytes.add("abc#hifi-1");
        h.write("abc#hifi-1", new String[] {AudioOrigin.META_ORIGIN, "OTHER"});
        check(AudioOrigin.cachedSource("abc#hifi-1") == null, "OTHER never becomes a provider");

        h.bytes.add("saavn");
        h.write("saavn", new String[] {AudioOrigin.META_ORIGIN, "JIOSAAVN"});
        check(
                AudioOrigin.cachedSource("saavn").id.equals("saavn-uuid"),
                "JioSaavn origin proves it");

        h.bytes.add("qobuz-uuid|track9#stereo");
        h.write("qobuz-uuid|track9#stereo", new String[] {AudioOrigin.META_ORIGIN, "OTHER"});
        Source row = AudioOrigin.cachedSource("qobuz-uuid|track9#stereo");
        check(row.id.equals("qobuz-uuid"), "A stable source track key proves its source");
        check(row.proof.equals(AudioOrigin.PROOF_LEGACY_TRACK), "With its own proof");

        h.bytes.add("qobuz-uuid|track10");
        check(
                AudioOrigin.cachedSource("qobuz-uuid|track10").id.equals("qobuz-uuid"),
                "Even with no host origin");

        h.bytes.add("deleted|track");
        check(AudioOrigin.cachedSource("deleted|track") == null, "A removed source proves nothing");

        h.bytes.add("qobuz-uuid|track11");
        h.write("qobuz-uuid|track11", new String[] {AudioOrigin.META_ORIGIN, "YOUTUBE"});
        check(
                AudioOrigin.cachedSource("qobuz-uuid|track11") == null,
                "A row whose origin contradicts it is unknown");

        h.write("nobytes", new String[] {AudioOrigin.META_ORIGIN, "YOUTUBE"});
        check(AudioOrigin.cachedSource("nobytes") == null, "Proof needs bytes");

        h.bytes.add("video");
        check(AudioOrigin.cachedSource("video") == null, "No evidence is unknown, never YouTube");
        check(!AudioOrigin.provenYouTube("video"), "And is never evictable");
        check(!AudioOrigin.provenYouTube("abc#alt"), "Nor the substitution bucket");

        h.bytes.add("agree");
        h.write("agree", new String[] {AudioOrigin.META_ORIGIN, "YOUTUBE"});
        h.writes = 0;
        AudioOrigin.record("agree", AudioOrigin.cachedSource("agree"));
        check(h.writes == 1, "A proven backfill is written");
        check(AudioOrigin.cachedSource("agree").isYouTube(), "And reads back");
        AudioOrigin.record(
                "agree",
                new Source(
                        "yt-uuid",
                        "YouTube",
                        "YOUTUBE",
                        AudioOrigin.PROOF_HOST,
                        null,
                        null,
                        null,
                        null));
        check(AudioOrigin.cachedSource("agree").isYouTube(), "A later open agrees");
    }

    static void failures() {
        AudioOrigin.host =
                new Memory() {
                    @Override
                    public String tag(String key, String name) {
                        throw new IllegalStateException("Cache was released");
                    }

                    @Override
                    public void write(String key, String[] pairs) {
                        throw new NoSuchMethodError("Stripped");
                    }
                };
        check(AudioOrigin.cachedSource("a") == null, "A broken cache reads as unknown");
        AudioOrigin.record("a", telegram());
        check(!AudioOrigin.provenYouTube("a"), "And never proves YouTube");
    }

    static void statsLabels() {
        check(AudioOrigin.hostLabel("Qobuz").equals("Qobuz"), "A host name passes through");
        check(AudioOrigin.hostLabel("SMB").equals("SMB"), "So does SMB");
        check(AudioOrigin.hostLabel("YouTube") == null, "A bare YouTube is not proof");
        check(AudioOrigin.hostLabel(" youtube ") == null, "However it is written");
        check(AudioOrigin.hostLabel(null) == null, "Nothing stays nothing");
    }

    public static void main(String[] args) {
        decisions();
        hygiene();
        writes();
        sameYouTube();
        names();
        legacy();
        failures();
        statsLabels();
        System.out.println("PASS: " + checks + " source record checks.");
    }
}
