package app.bitchord.soulseek;

import android.content.Context;
import android.text.format.Formatter;

import app.bitchord.telegram.PatchStrings;

import java.text.NumberFormat;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reviewable per-track plan. A submitted row is never retried implicitly after partial failure. */
final class DownloadBatch {
    interface Selection {
        void start(boolean now) throws Exception;
    }

    static final int SOURCES = 20, PER_SOURCE = 12, PER_PEER = 20;
    static final long FILE_LIMIT = 256L * 1024 * 1024;

    /**
     * One reported file offer. Zero means the source did not report the value. Peer and addon
     * claims stay unverified; nothing here is probed, resolved or downloaded.
     */
    static final class Candidate {
        final String key, sourceKey, sourceName, title, artist, format, quality, durationText, peer;
        final String session;
        final SoulseekTrack track;
        final int durationSeconds, bitDepth, sampleRate, bitrate;
        final long size;
        final boolean soulseek;
        final BooleanSupplier current;
        final Selection selection;

        /** Same reference, same reported fields and, for Soulseek, the same search session. */
        boolean sameOffer(Candidate other) {
            return other != null
                    && key.equals(other.key)
                    && sourceName.equals(other.sourceName)
                    && title.equals(other.title)
                    && artist.equals(other.artist)
                    && format.equals(other.format)
                    && quality.equals(other.quality)
                    && durationText.equals(other.durationText)
                    && peer.equals(other.peer)
                    && session.equals(other.session)
                    && durationSeconds == other.durationSeconds
                    && bitDepth == other.bitDepth
                    && sampleRate == other.sampleRate
                    && bitrate == other.bitrate
                    && size == other.size;
        }

        private Candidate(
                String key,
                String sourceKey,
                String sourceName,
                String title,
                String artist,
                String format,
                String quality,
                String durationText,
                String peer,
                int durationSeconds,
                int bitDepth,
                int sampleRate,
                int bitrate,
                long size,
                boolean soulseek,
                SoulseekTrack track,
                String session,
                BooleanSupplier current,
                Selection selection) {
            this.track = track;
            this.session = session;
            this.key = key;
            this.sourceKey = sourceKey;
            this.sourceName = sourceName;
            this.title = title;
            this.artist = artist;
            this.format = format;
            this.quality = quality;
            this.durationText = durationText;
            this.peer = peer;
            this.durationSeconds = durationSeconds;
            this.bitDepth = bitDepth;
            this.sampleRate = sampleRate;
            this.bitrate = bitrate;
            this.size = size;
            this.soulseek = soulseek;
            this.current = current;
            this.selection = selection;
        }

        boolean tooLarge() {
            return size > FILE_LIMIT;
        }

        /** Addon quality text is kept raw; numbers come only from explicit unit-bearing tokens. */
        static Candidate addon(String id, String name, Object song, Context context) {
            Context app = context.getApplicationContext();
            String quality = text(song, "sourceQuality").trim();
            return new Candidate(
                    id + "\u0000" + text(song, "videoId"),
                    id,
                    name,
                    text(song, "title"),
                    text(song, "artist"),
                    quality,
                    quality,
                    text(song, "durationText"),
                    "",
                    0,
                    reportedBitDepth(quality),
                    reportedSampleRate(quality),
                    reportedBitrate(quality),
                    0,
                    false,
                    null,
                    "",
                    () -> true,
                    now -> HostDownloadAccess.enqueue(app, song, id, now));
        }

        static Candidate soulseek(SoulseekTrack file, SoulseekResult result, Context context) {
            Context app = context.getApplicationContext();
            return new Candidate(
                    "soulseek\u0000" + file.id,
                    "soulseek",
                    "Soulseek",
                    file.title,
                    file.artist,
                    file.advertisedExtension.toUpperCase(Locale.ROOT),
                    "",
                    "",
                    file.peer,
                    file.duration,
                    file.bitDepth,
                    file.sampleRate,
                    file.bitrate,
                    file.size,
                    true,
                    file,
                    result.session,
                    result.current,
                    now -> {
                        if (!result.current.getAsBoolean())
                            throw new java.io.IOException("Soulseek search result expired");
                        result.admit.start(app, file, result.session, now);
                    });
        }
    }

    interface Admit {
        void start(Context app, SoulseekTrack file, String session, boolean now) throws Exception;
    }

    /** Rows from one Soulseek search, tied to the session that produced them. */
    static final class SoulseekResult {
        final List<SoulseekTrack> rows;
        final String session;
        final BooleanSupplier current;
        final Admit admit;

        SoulseekResult(
                List<SoulseekTrack> rows, String session, BooleanSupplier current, Admit admit) {
            this.rows = rows;
            this.session = session;
            this.current = current;
            this.admit = admit;
        }

        static SoulseekResult of(SoulseekBridge bridge, List<SoulseekTrack> rows) {
            String session = bridge.sessionTag;
            return new SoulseekResult(
                    rows,
                    session,
                    () -> session.equals(bridge.sessionTag),
                    (app, file, tag, now) -> {
                        // The same route as the Explorer, so an active preview keeps the file.
                        if (!SoulseekPreview.enqueueSelected(app, file, tag, now))
                            throw new java.io.IOException("Soulseek download was not accepted");
                    });
        }
    }

    static final class SourceRef {
        final Object source;
        final String id, name;

        SourceRef(Object source, String id, String name) {
            this.source = source;
            this.id = id;
            this.name = name;
        }
    }

    /** Everything the batch page needs from the outside world; searches run off the UI thread. */
    interface Searcher {
        List<SourceRef> sources() throws Exception;

        List<?> search(SourceRef source, String query) throws Exception;

        boolean soulseekEnabled();

        SoulseekResult soulseek(String query) throws Exception;
    }

    static Searcher searcher(Context context) {
        Context app = context.getApplicationContext();
        return new Searcher() {
            public List<SourceRef> sources() throws Exception {
                List<SourceRef> out = new ArrayList<>();
                for (Object source : HostDownloadAccess.sources()) {
                    String id = HostDownloadAccess.sourceText(source, "getConfigId");
                    if (id.equals("soulseek-native")
                            || HostDownloadAccess.sourceText(source, "getKind").equals("YOUTUBE"))
                        continue;
                    out.add(
                            new SourceRef(
                                    source,
                                    id,
                                    HostDownloadAccess.sourceText(source, "getDisplayName")));
                    if (out.size() >= SOURCES) break;
                }
                return out;
            }

            public List<?> search(SourceRef source, String query) throws Exception {
                return HostDownloadAccess.search(source.source, query);
            }

            public boolean soulseekEnabled() {
                return DownloadSettings.enabled(app, DownloadSettings.SOULSEEK);
            }

            public SoulseekResult soulseek(String query) throws Exception {
                SoulseekBridge.boot(app);
                SoulseekBridge bridge = SoulseekBridge.get();
                return SoulseekResult.of(
                        bridge,
                        AudioFormatPreferences.ordered(
                                AudioFormatPreferences.snapshot(app), bridge.search(query)));
            }
        };
    }

    static List<Candidate> fromSource(String id, String name, List<?> results, Context context) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object song : results) {
            if (out.size() >= PER_SOURCE) break;
            if (!text(song, "videoId").startsWith("src:" + id + "::")) continue;
            Candidate offer = Candidate.addon(id, name, song, context);
            // The first reported offer for a reference wins; a repeat is not a new variant.
            if (seen.add(offer.key)) out.add(offer);
        }
        return out;
    }

    static List<Candidate> fromSoulseek(SoulseekResult result, Context context) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SoulseekTrack row : result.rows) {
            if (out.size() >= PER_PEER) break;
            Candidate offer = Candidate.soulseek(row, result, context);
            if (seen.add(offer.key)) out.add(offer);
        }
        return out;
    }

    private static String text(Object song, String name) {
        return HostDownloadAccess.propertyText(song, name);
    }

    private static final Pattern BITS =
            Pattern.compile("(?<![\\d.,])(\\d{1,2})\\s*-?\\s*bits?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KHZ =
            Pattern.compile(
                    "(?<![\\d.,])(\\d{1,3}(?:[.,]\\d{1,3})?)\\s*khz\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern KBPS =
            Pattern.compile("(?<![\\d.,])(\\d{2,4})\\s*kbps\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE =
            Pattern.compile(
                    "\\b(?:up\\s+to|maximum|max|requested|supports?|options?|or|to)\\b"
                        + "|[<>≤≥]|\\d\\s*[-–—/]\\s*\\d|(?:^|\\s)[+−-]\\s*\\d|\\b\\d+[eE][+-]?\\d",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CODEC =
            Pattern.compile(
                    "\\b(FLAC|ALAC|WAV|WAVE|AIFF|AIF|DSD|DSF|DFF|MP3|AAC|OPUS|VORBIS|OGG|APE|WV|WAVPACK|TTA|TAK|SHN|WMA|PCM)\\b",
                    Pattern.CASE_INSENSITIVE);

    /** The one number the text states with this unit, or null when absent or contradictory. */
    private static String only(Pattern pattern, String quality) {
        if (quality != null && RANGE.matcher(quality).find()) return null;
        Set<String> found = new HashSet<>();
        Matcher match = pattern.matcher(quality == null ? "" : quality);
        while (match.find()) found.add(match.group(1).replace(',', '.'));
        return found.size() == 1 ? found.iterator().next() : null;
    }

    static int reportedBitDepth(String quality) {
        String value = only(BITS, quality);
        if (value == null) return 0;
        int bits = Integer.parseInt(value);
        return bits >= 8 && bits <= 64 ? bits : 0;
    }

    static int reportedSampleRate(String quality) {
        String value = only(KHZ, quality);
        if (value == null) return 0;
        long hertz = Math.round(Double.parseDouble(value) * 1000);
        return hertz >= 4000 && hertz <= 1_000_000 ? (int) hertz : 0;
    }

    static int reportedBitrate(String quality) {
        String value = only(KBPS, quality);
        if (value == null) return 0;
        int rate = Integer.parseInt(value);
        return rate >= 8 ? rate : 0;
    }

    static String sampleRateText(int hertz) {
        NumberFormat rate = NumberFormat.getNumberInstance();
        rate.setMaximumFractionDigits(3);
        return rate.format(hertz / 1000d) + " kHz";
    }

    /** Exact reported format and numeric specs; empty when the source reported none. */
    static String spec(Candidate c) {
        String format = c.format;
        if (!c.soulseek) {
            if (RANGE.matcher(c.quality).find()) return c.quality;
            Set<String> codecs = new HashSet<>();
            Matcher match = CODEC.matcher(c.quality);
            while (match.find()) codecs.add(match.group(1).toUpperCase(Locale.ROOT));
            if (codecs.size() != 1) return c.quality;
            format = codecs.iterator().next();
        }
        List<String> parts = new ArrayList<>();
        if (!format.isEmpty()) parts.add(format);
        if (Arrays.asList("MP3", "AAC", "OPUS", "VORBIS").contains(format)) {
            if (c.bitrate > 0) parts.add(c.bitrate + " kbps");
        } else {
            if (c.bitDepth > 0) parts.add(PatchStrings.f("{0} bits", c.bitDepth));
            if (c.sampleRate > 0) parts.add(sampleRateText(c.sampleRate));
        }
        return String.join(" · ", parts);
    }

    private static String named(String value) {
        return value == null || value.trim().isEmpty() ? PatchStrings.t("Not reported") : value;
    }

    static String titleArtist(Candidate c) {
        return named(c.title) + " · " + named(c.artist);
    }

    private static String labelled(String label, String value) {
        return PatchStrings.t(label)
                + ": "
                + (value.isEmpty() ? PatchStrings.t("Not reported") : value);
    }

    static String audioLine(Candidate c) {
        return labelled("Format", c.format)
                + " · "
                + labelled(
                        "Bit depth", c.bitDepth > 0 ? PatchStrings.f("{0} bits", c.bitDepth) : "")
                + " · "
                + labelled("Sample rate", c.sampleRate > 0 ? sampleRateText(c.sampleRate) : "")
                + " · "
                + labelled("Bitrate", c.bitrate > 0 ? c.bitrate + " kbps" : "");
    }

    /** One compact quality line: the addon's own text, or the peer's reported format and specs. */
    static String qualityLine(Candidate c) {
        String quality = c.soulseek ? spec(c) : c.quality;
        return quality.isEmpty() ? labelled("Format", "") : quality;
    }

    /** Known duration and size read as plain values; an unknown one says so explicitly. */
    static String clockLine(Context context, Candidate c) {
        String clock;
        if (c.soulseek)
            clock = c.durationSeconds > 0 ? DownloadDetails.duration(c.durationSeconds) : "";
        else {
            clock = DownloadDetails.referenceDuration(c.durationText);
            if (clock.equals(PatchStrings.t("Not reported"))) clock = "";
        }
        String size = c.size > 0 ? Formatter.formatShortFileSize(context, c.size) : "";
        return (clock.isEmpty() ? labelled("Duration", "") : clock)
                + " · "
                + (size.isEmpty() ? labelled("File size", "") : size);
    }

    static String fileLine(Context context, Candidate c) {
        String duration =
                c.soulseek
                        ? DownloadDetails.duration(c.durationSeconds)
                        : DownloadDetails.referenceDuration(c.durationText);
        return PatchStrings.t("Duration")
                + ": "
                + duration
                + " · "
                + labelled(
                        "File size",
                        c.size > 0 ? Formatter.formatShortFileSize(context, c.size) : "");
    }

    static String detail(Candidate c) {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {c.sourceName, c.title, spec(c)})
            if (part != null && !part.trim().isEmpty()) parts.add(part.trim());
        return String.join(" · ", parts);
    }

    static final class Choice {
        final String detail;
        final Selection selection;
        final Candidate candidate;
        volatile boolean submitted, submitting;
        volatile String error = "";

        Choice(String detail, Selection selection) {
            this(detail, selection, null);
        }

        Choice(String detail, Selection selection, Candidate candidate) {
            this.detail = detail;
            this.selection = selection;
            this.candidate = candidate;
        }
    }

    private static final Map<String, Map<Integer, Choice>> plans = new HashMap<>();

    static synchronized Choice choice(String token, int index) {
        return plans.getOrDefault(token, Collections.emptyMap()).get(index);
    }

    static synchronized void select(String token, int index, String detail, Selection selection) {
        select(token, index, detail, selection, null);
    }

    /** Stores the offer only; nothing is requested until {@link #submit}. */
    static synchronized void select(String token, int index, Candidate candidate) {
        select(token, index, detail(candidate), candidate.selection, candidate);
    }

    private static synchronized void select(
            String token, int index, String detail, Selection selection, Candidate candidate) {
        if (index < 0 || index >= SoulseekDownloadChoice.songs(token).size())
            throw new IllegalArgumentException("Invalid track index");
        if (!SoulseekDownloadChoice.available(token))
            throw new IllegalStateException("Download selection expired");
        Choice old = choice(token, index);
        if (old != null && (old.submitted || old.submitting))
            throw new IllegalStateException("Track was already submitted");
        plans.computeIfAbsent(token, key -> new HashMap<>())
                .put(index, new Choice(detail, selection, candidate));
    }

    static synchronized void clear(String token, int index) {
        Choice value = choice(token, index);
        if (value != null && !value.submitted && !value.submitting) plans.get(token).remove(index);
    }

    static synchronized void forget(String token) {
        plans.remove(token);
    }

    static String normalized(String value) {
        return java.text.Normalizer.normalize(
                        value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static boolean matches(Object requested, Object result) {
        String title = normalized(HostDownloadAccess.propertyText(requested, "title"));
        String artist = normalized(HostDownloadAccess.propertyText(requested, "artist"));
        return !title.isEmpty()
                && !artist.isEmpty()
                && title.equals(normalized(HostDownloadAccess.propertyText(result, "title")))
                && artist.equals(normalized(HostDownloadAccess.propertyText(result, "artist")));
    }

    static void find(String token, Object source, Context context) throws Exception {
        List<?> songs = SoulseekDownloadChoice.songs(token);
        String id = HostDownloadAccess.sourceText(source, "getConfigId"),
                name = HostDownloadAccess.sourceText(source, "getDisplayName");
        for (int i = 0; i < songs.size(); i++) {
            if (Thread.currentThread().isInterrupted() || !SoulseekDownloadChoice.available(token))
                return;
            if (choice(token, i) != null) continue;
            Object requested = songs.get(i);
            String query =
                    HostDownloadAccess.propertyText(requested, "artist")
                            + " "
                            + HostDownloadAccess.propertyText(requested, "title");
            if (query.length() > 256) continue;
            List<?> results = HostDownloadAccess.search(source, query);
            Object only = null;
            int matches = 0;
            for (Object result : results) {
                if (HostDownloadAccess.propertyText(result, "videoId")
                                .startsWith("src:" + id + "::")
                        && matches(requested, result)) {
                    only = result;
                    matches++;
                }
            }
            if (Thread.currentThread().isInterrupted()) return;
            if (matches == 1) {
                Candidate selected = Candidate.addon(id, name, only, context);
                synchronized (DownloadBatch.class) {
                    if (choice(token, i) == null) select(token, i, selected);
                }
            }
        }
    }

    static void findSoulseek(String token, Context context) throws Exception {
        SoulseekBridge bridge = SoulseekBridge.get();
        List<?> songs = SoulseekDownloadChoice.songs(token);
        for (int index = 0; index < songs.size(); index++) {
            if (Thread.currentThread().isInterrupted() || !SoulseekDownloadChoice.available(token))
                return;
            if (choice(token, index) != null) continue;
            Object requested = songs.get(index);
            String title = HostDownloadAccess.propertyText(requested, "title"),
                    artist = HostDownloadAccess.propertyText(requested, "artist");
            String query = artist + " " + title;
            if (query.length() > 256) continue;
            SoulseekTrack only = null;
            int count = 0;
            List<SoulseekTrack> found = bridge.search(query);
            for (SoulseekTrack row : found)
                if (row.size <= FILE_LIMIT
                        && !normalized(artist).isEmpty()
                        && normalized(title).equals(normalized(row.title))
                        && normalized(artist).equals(normalized(row.artist))) {
                    only = row;
                    count++;
                }
            if (Thread.currentThread().isInterrupted()) return;
            if (count != 1) continue;
            Candidate selected =
                    Candidate.soulseek(only, SoulseekResult.of(bridge, found), context);
            synchronized (DownloadBatch.class) {
                if (choice(token, index) == null) select(token, index, selected);
            }
        }
    }

    static int submit(String token, boolean now) throws Exception {
        int count = 0;
        List<?> songs = SoulseekDownloadChoice.songs(token);
        for (int i = 0; i < songs.size(); i++) {
            if (Thread.currentThread().isInterrupted()) break;
            Choice choice = choice(token, i);
            if (choice == null || choice.submitted) continue;
            synchronized (DownloadBatch.class) {
                if (choice.submitted || choice.submitting) continue;
                choice.submitting = true;
            }
            try {
                choice.selection.start(now);
                choice.submitted = true;
                choice.error = "";
                count++;
            } catch (Exception failed) {
                choice.error = "Submission failed. Choose again or retry.";
            } finally {
                choice.submitting = false;
            }
        }
        return count;
    }
}
