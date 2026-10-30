package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Download format policy. Filename hints never establish a codec or source provenance. */
public final class AudioFormatPreferences {
    private static final String FILE = "bitchord_audio_formats", KEY = "format_order_v1";

    public enum Format {
        FLAC("FLAC", "audio/flac"),
        ALAC("ALAC", "audio/mp4"),
        WAV("WAV", "audio/wav"),
        AIFF("AIFF", "audio/aiff"),
        DSD("DSD", "audio/dsd"),
        OPUS("Opus", "audio/opus"),
        AAC("AAC", "audio/aac"),
        VORBIS("Vorbis", "audio/ogg"),
        MP3("MP3", "audio/mpeg"),
        UNKNOWN("Unknown / unverified", "application/octet-stream");
        public final String label, mime;

        Format(String label, String mime) {
            this.label = label;
            this.mime = mime;
        }
    }

    private static final List<Format> DEFAULT_ORDER =
            Collections.unmodifiableList(
                    Arrays.asList(
                            Format.FLAC,
                            Format.ALAC,
                            Format.WAV,
                            Format.AIFF,
                            Format.DSD,
                            Format.OPUS,
                            Format.AAC,
                            Format.VORBIS,
                            Format.MP3));

    /** Allows persistence logic to be tested independently of Android preferences. */
    public interface Store {
        String load() throws IOException;

        void save(String encoded) throws IOException;
    }

    public static final class Settings {
        public final List<Format> order;
        public final java.util.Set<Format> enabled;
        public final boolean configured;

        public Settings(List<Format> order, java.util.Set<Format> enabled, boolean configured) {
            if (order == null
                    || order.size() != DEFAULT_ORDER.size()
                    || !EnumSet.copyOf(order).equals(EnumSet.copyOf(DEFAULT_ORDER))
                    || enabled == null
                    || enabled.isEmpty()
                    || enabled.contains(Format.UNKNOWN)
                    || !order.containsAll(enabled))
                throw new IllegalArgumentException("Choose at least one valid audio format.");
            this.order = Collections.unmodifiableList(new ArrayList<>(order));
            this.enabled = Collections.unmodifiableSet(EnumSet.copyOf(enabled));
            this.configured = configured;
        }

        public Settings withEnabled(Format format, boolean selected) {
            if (!order.contains(format))
                throw new IllegalArgumentException("Unsupported audio format.");
            EnumSet<Format> next = EnumSet.copyOf(enabled);
            if (selected) next.add(format);
            else next.remove(format);
            return new Settings(order, next, configured);
        }

        public Settings move(int index, int direction) {
            if (index < 0 || index >= order.size() || (direction != -1 && direction != 1))
                throw new IllegalArgumentException("Invalid format order.");
            int target = index + direction;
            if (target < 0 || target >= order.size()) return this;
            ArrayList<Format> next = new ArrayList<>(order);
            Collections.swap(next, index, target);
            return new Settings(next, enabled, configured);
        }

        public int rank(Format format) {
            return enabled.contains(format) ? order.indexOf(format) : Integer.MAX_VALUE;
        }
    }

    private AudioFormatPreferences() {}

    public static Settings defaults() {
        return new Settings(DEFAULT_ORDER, EnumSet.copyOf(DEFAULT_ORDER), false);
    }

    public static String encode(Settings settings) {
        StringBuilder result = new StringBuilder("1|");
        for (Format format : settings.order) {
            if (result.length() > 2) result.append(',');
            result.append(format.name())
                    .append(':')
                    .append(settings.enabled.contains(format) ? '1' : '0');
        }
        return result.toString();
    }

    public static Settings decode(String encoded) throws IOException {
        if (encoded == null || encoded.length() > 160 || !encoded.startsWith("1|"))
            throw new IOException("Invalid saved audio formats.");
        String[] entries = encoded.substring(2).split(",", -1);
        if (entries.length != DEFAULT_ORDER.size())
            throw new IOException("Invalid saved audio format count.");
        ArrayList<Format> order = new ArrayList<>();
        EnumSet<Format> enabled = EnumSet.noneOf(Format.class);
        try {
            for (String entry : entries) {
                String[] fields = entry.split(":", -1);
                if (fields.length != 2 || (!fields[1].equals("0") && !fields[1].equals("1")))
                    throw new IllegalArgumentException();
                Format format = Format.valueOf(fields[0]);
                if (format == Format.UNKNOWN || order.contains(format))
                    throw new IllegalArgumentException();
                order.add(format);
                if (fields[1].equals("1")) enabled.add(format);
            }
            return new Settings(order, enabled, true);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid saved audio formats.", invalid);
        }
    }

    public static Settings load(Store store) throws IOException {
        String saved = store.load();
        if (saved == null) return defaults();
        try {
            return decode(saved);
        } catch (IOException corrupt) {
            return defaults();
        }
    }

    public static void save(Store store, Settings settings) throws IOException {
        store.save(encode(settings));
    }

    private static Store store(Context context) {
        final android.content.SharedPreferences preferences =
                context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return new Store() {
            public String load() {
                return preferences.getString(KEY, null);
            }

            public void save(String value) throws IOException {
                if (!preferences.edit().putString(KEY, value).commit())
                    throw new IOException("Cannot save audio format choices.");
            }
        };
    }

    public static Settings snapshot(Context context) {
        try {
            return load(store(context));
        } catch (Exception unavailable) {
            return defaults();
        }
    }

    public static void save(Context context, Settings settings) throws IOException {
        save(store(context), settings);
    }

    public static boolean ensureConfigured(Activity activity) {
        if (snapshot(activity).configured) return true;
        openSettings(activity);
        return false;
    }

    public static void openSettings(Activity activity) {
        activity.startActivity(new Intent(activity, AudioFormatSetupActivity.class));
    }

    public static List<Format> enabledFormats(Context context) {
        Settings settings = snapshot(context);
        ArrayList<Format> result = new ArrayList<>();
        for (Format format : settings.order)
            if (settings.enabled.contains(format)) result.add(format);
        return Collections.unmodifiableList(result);
    }

    public static String audioName(String raw) throws IOException {
        if (raw == null || raw.length() > 4096) throw new IOException("Invalid audio filename.");
        String name =
                raw.replace('\\', '_').replace('/', '_').replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.length() > 128) name = name.substring(name.length() - 128);
        if (candidateFormats(name).isEmpty())
            throw new IOException("Choose a supported audio file.");
        return name;
    }

    /** Candidate hints only. M4A/MP4 and Ogg are deliberately ambiguous. */
    public static java.util.Set<Format> candidateFormats(String name) {
        if (name == null) return Collections.emptySet();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "flac":
                return EnumSet.of(Format.FLAC);
            case "alac":
                return EnumSet.of(Format.ALAC);
            case "wav":
            case "wave":
                return EnumSet.of(Format.WAV);
            case "aif":
            case "aiff":
            case "aifc":
                return EnumSet.of(Format.AIFF);
            case "dsf":
            case "dff":
                return EnumSet.of(Format.DSD);
            case "opus":
                return EnumSet.of(Format.OPUS);
            case "aac":
                return EnumSet.of(Format.AAC);
            case "m4a":
            case "mp4":
                return EnumSet.of(Format.ALAC, Format.AAC);
            case "ogg":
            case "oga":
                return EnumSet.of(Format.VORBIS, Format.OPUS, Format.FLAC);
            case "vorbis":
                return EnumSet.of(Format.VORBIS);
            case "mp3":
                return EnumSet.of(Format.MP3);
            default:
                return Collections.emptySet();
        }
    }

    public static boolean allowsCandidate(Settings settings, String name) {
        for (Format candidate : candidateFormats(name))
            if (settings.enabled.contains(candidate)) return true;
        return false;
    }

    private static int hintRank(Settings settings, SoulseekTrack track) {
        java.util.Set<Format> candidates = candidateFormats(track.path);
        if (candidates.size() != 1) return Integer.MAX_VALUE - 1;
        return settings.rank(candidates.iterator().next());
    }

    /** Never changes an asset identity or substitutes one recording for another. */
    static List<SoulseekTrack> ordered(Settings settings, List<SoulseekTrack> tracks) {
        ArrayList<SoulseekTrack> result = new ArrayList<>();
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (SoulseekTrack track : tracks) {
            if (!allowsCandidate(settings, track.path)) continue;
            int index = result.size();
            result.add(track);
            // Only exact artist/title/duration metadata groups may be reordered.
            // Missing artist or duration stays in its original position.
            if (!track.artist.equals("Unknown artist") && track.duration > 0) {
                String key =
                        track.artist.toLowerCase(Locale.ROOT).trim()
                                + "\0"
                                + track.title.toLowerCase(Locale.ROOT).trim()
                                + "\0"
                                + track.duration;
                List<Integer> positions = groups.get(key);
                if (positions == null) {
                    positions = new ArrayList<>();
                    groups.put(key, positions);
                }
                positions.add(index);
            }
        }
        for (List<Integer> positions : groups.values()) {
            ArrayList<SoulseekTrack> same = new ArrayList<>();
            for (int position : positions) same.add(result.get(position));
            Collections.sort(same, Comparator.comparingInt(track -> hintRank(settings, track)));
            for (int i = 0; i < positions.size(); i++) result.set(positions.get(i), same.get(i));
        }
        return result;
    }

    public static Format validateSelectedFile(Context context, File file, String name)
            throws IOException {
        return validateSelectedFile(snapshot(context), file, name);
    }

    public static Format validateSelectedFile(Settings settings, File file, String name)
            throws IOException {
        if (!settings.configured)
            throw new IOException("Choose download formats in initial setup first.");
        audioName(name);
        Format format = probe(file);
        if (format == Format.UNKNOWN)
            throw new IOException(
                    "Audio codec could not be verified from the file. Choose another source.");
        if (!candidateFormats(name).contains(format))
            throw new IOException("The audio content does not match its advertised filename.");
        if (!settings.enabled.contains(format))
            throw new IOException(format.label + " is disabled in audio format choices.");
        return format;
    }

    /** Bounded structural codec probe. Encoding is identified; transcode history is unverified. */
    public static Format probe(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            if (size < 12) return Format.UNKNOWN;
            Probe p = new Probe(input, size);
            byte[] header = p.read(0, (int) Math.min(size, 64));
            if (ascii(header, 0, "fLaC")) return flac(header) ? Format.FLAC : Format.UNKNOWN;
            if (ascii(header, 0, "RIFF") && ascii(header, 8, "WAVE")) return wave(p);
            if (ascii(header, 0, "FORM") && (ascii(header, 8, "AIFF") || ascii(header, 8, "AIFC")))
                return aiff(p, ascii(header, 8, "AIFC"));
            if (ascii(header, 0, "DSD ")) return dsf(p);
            if (ascii(header, 0, "FRM8") && ascii(header, 12, "DSD ")) return dff(p);
            if (ascii(header, 0, "OggS")) return ogg(p);
            if (ascii(header, 4, "ftyp")) return mp4(p);
            long offset = 0;
            if (ascii(header, 0, "ID3")) {
                if ((header[6] | header[7] | header[8] | header[9]) < 0) return Format.UNKNOWN;
                offset =
                        10L
                                + ((header[6] & 127) << 21)
                                + ((header[7] & 127) << 14)
                                + ((header[8] & 127) << 7)
                                + (header[9] & 127);
                if ((header[5] & 16) != 0) offset += 10;
            }
            if (offset + 7 > size) return Format.UNKNOWN;
            byte[] audio = p.read(offset, (int) Math.min(65536, size - offset));
            int frame = mp3Frame(audio, 0);
            if (frame > 0
                    && offset + frame + 4 <= size
                    && mp3Frame(p.read(offset + frame, 4), 0) > 0) return Format.MP3;
            int aac = aacFrame(audio, 0);
            if (aac > 0 && offset + aac + 7 <= size && aacFrame(p.read(offset + aac, 7), 0) > 0)
                return Format.AAC;
            return Format.UNKNOWN;
        } catch (Invalid invalid) {
            return Format.UNKNOWN;
        }
    }

    private static final class Invalid extends IOException {}

    private static final class Probe {
        final RandomAccessFile file;
        final long size;
        int budget = 1024 * 1024, atoms;

        Probe(RandomAccessFile file, long size) {
            this.file = file;
            this.size = size;
        }

        byte[] read(long at, int count) throws IOException {
            if (at < 0 || count < 0 || count > budget || at > size - count) throw new Invalid();
            budget -= count;
            byte[] bytes = new byte[count];
            file.seek(at);
            file.readFully(bytes);
            return bytes;
        }
    }

    private static boolean ascii(byte[] bytes, int at, String value) {
        if (at < 0 || at > bytes.length - value.length()) return false;
        for (int i = 0; i < value.length(); i++)
            if ((bytes[at + i] & 255) != value.charAt(i)) return false;
        return true;
    }

    private static long u32be(byte[] b, int at) {
        return ((long) (b[at] & 255) << 24)
                | ((long) (b[at + 1] & 255) << 16)
                | ((long) (b[at + 2] & 255) << 8)
                | (b[at + 3] & 255);
    }

    private static long u32le(byte[] b, int at) {
        return ((long) (b[at + 3] & 255) << 24)
                | ((long) (b[at + 2] & 255) << 16)
                | ((long) (b[at + 1] & 255) << 8)
                | (b[at] & 255);
    }

    private static int u16le(byte[] b, int at) {
        return (b[at] & 255) | ((b[at + 1] & 255) << 8);
    }

    private static int u16be(byte[] b, int at) {
        return ((b[at] & 255) << 8) | (b[at + 1] & 255);
    }

    private static long u64be(byte[] b, int at) {
        long high = u32be(b, at);
        return high > 0x7fffffffL ? -1 : (high << 32) | u32be(b, at + 4);
    }

    private static long u64le(byte[] b, int at) {
        long high = u32le(b, at + 4);
        return high > 0x7fffffffL ? -1 : (high << 32) | u32le(b, at);
    }

    private static boolean flac(byte[] b) {
        if (b.length < 42 || (b[4] & 127) != 0 || (u32be(b, 4) & 0xffffff) != 34) return false;
        int sampleRate = ((b[18] & 255) << 12) | ((b[19] & 255) << 4) | ((b[20] & 240) >>> 4);
        int depth = (((b[20] & 1) << 4) | ((b[21] & 240) >>> 4)) + 1;
        return u16be(b, 8) >= 16
                && u16be(b, 10) >= u16be(b, 8)
                && sampleRate > 0
                && depth >= 4
                && depth <= 32;
    }

    private static Format wave(Probe p) throws IOException {
        byte[] root = p.read(0, 12);
        long end = u32le(root, 4) + 8;
        if (end > p.size || end < 12) return Format.UNKNOWN;
        boolean pcm = false, data = false;
        for (long at = 12; at + 8 <= end; ) {
            if (++p.atoms > 4096) throw new Invalid();
            byte[] chunk = p.read(at, 8);
            long size = u32le(chunk, 4);
            if (size > end - at - 8) throw new Invalid();
            if (ascii(chunk, 0, "fmt ")) {
                if (size < 16 || size > 4096) return Format.UNKNOWN;
                byte[] fmt = p.read(at + 8, (int) Math.min(size, 64));
                int code = u16le(fmt, 0);
                if (code == 0xfffe && size >= 40) {
                    byte[] tail = {
                        0, 0, 0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113
                    };
                    code =
                            Arrays.equals(Arrays.copyOfRange(fmt, 26, 40), tail)
                                    ? u16le(fmt, 24)
                                    : 0;
                }
                int channels = u16le(fmt, 2), depth = u16le(fmt, 14), align = u16le(fmt, 12);
                pcm =
                        (code == 1 || code == 3)
                                && u16le(fmt, 2) > 0
                                && u16le(fmt, 2) <= 32
                                && u32le(fmt, 4) > 0
                                && u32le(fmt, 4) <= 768000
                                && depth > 0
                                && depth <= 64
                                && align == (channels * depth + 7) / 8
                                && u32le(fmt, 8) == u32le(fmt, 4) * align;
            }
            if (ascii(chunk, 0, "data") && size > 0) data = true;
            at += 8 + size + (size & 1);
        }
        return pcm && data ? Format.WAV : Format.UNKNOWN;
    }

    private static Format aiff(Probe p, boolean compressedForm) throws IOException {
        long end = u32be(p.read(0, 12), 4) + 8;
        if (end > p.size || end < 12) return Format.UNKNOWN;
        boolean pcm = false, data = false;
        for (long at = 12; at + 8 <= end; ) {
            if (++p.atoms > 4096) throw new Invalid();
            byte[] chunk = p.read(at, 8);
            long size = u32be(chunk, 4);
            if (size > end - at - 8) throw new Invalid();
            if (ascii(chunk, 0, "COMM")) {
                if (size < (compressedForm ? 22 : 18)) return Format.UNKNOWN;
                byte[] comm = p.read(at + 8, compressedForm ? 22 : 18);
                pcm =
                        u16be(comm, 0) > 0
                                && u16be(comm, 0) <= 32
                                && u32be(comm, 2) > 0
                                && u16be(comm, 6) > 0
                                && u16be(comm, 6) <= 64
                                && u16be(comm, 8) > 0
                                && u16be(comm, 8) < 0x7fff
                                && (comm[10] & 128) != 0;
                if (compressedForm)
                    pcm &=
                            ascii(comm, 18, "NONE")
                                    || ascii(comm, 18, "sowt")
                                    || ascii(comm, 18, "fl32")
                                    || ascii(comm, 18, "fl64");
            }
            if (ascii(chunk, 0, "SSND") && size > 8) data = true;
            at += 8 + size + (size & 1);
        }
        return pcm && data ? Format.AIFF : Format.UNKNOWN;
    }

    private static Format dsf(Probe p) throws IOException {
        if (p.size < 93) return Format.UNKNOWN;
        byte[] header = p.read(0, 92);
        if (u64le(header, 4) != 28
                || u64le(header, 12) != p.size
                || !ascii(header, 28, "fmt ")
                || u64le(header, 32) != 52
                || u32le(header, 40) != 1
                || u32le(header, 44) != 0
                || u32le(header, 52) < 1
                || u32le(header, 52) > 32
                || u32le(header, 56) < 2822400
                || (u32le(header, 60) != 1 && u32le(header, 60) != 8)
                || u64le(header, 64) <= 0
                || u32le(header, 72) == 0
                || !ascii(header, 80, "data")
                || u64le(header, 84) <= 12
                || u64le(header, 84) > p.size - 80) return Format.UNKNOWN;
        return Format.DSD;
    }

    private static Format dff(Probe p) throws IOException {
        long declared = u64be(p.read(0, 16), 4);
        if (declared < 4 || declared > p.size - 12) return Format.UNKNOWN;
        boolean format = false, audio = false;
        long end = declared + 12;
        for (long at = 16; at + 12 <= end; ) {
            if (++p.atoms > 4096) throw new Invalid();
            byte[] chunk = p.read(at, 12);
            long size = u64be(chunk, 4);
            if (size < 0 || size > end - at - 12) throw new Invalid();
            if (ascii(chunk, 0, "DSD ") && size > 0) audio = true;
            if (ascii(chunk, 0, "PROP") && size >= 4 && ascii(p.read(at + 12, 4), 0, "SND ")) {
                for (long sub = at + 16; sub + 12 <= at + 12 + size; ) {
                    if (++p.atoms > 4096) throw new Invalid();
                    byte[] prop = p.read(sub, 12);
                    long length = u64be(prop, 4);
                    if (length < 0 || length > at + 12 + size - sub - 12) throw new Invalid();
                    if (ascii(prop, 0, "CMPR") && length >= 4) {
                        byte[] compression = p.read(sub + 12, 4);
                        format = ascii(compression, 0, "DSD ") || ascii(compression, 0, "DST ");
                    }
                    sub += 12 + length + (length & 1);
                }
            }
            if (ascii(chunk, 0, "DST ") && size > 0) audio = true;
            at += 12 + size + (size & 1);
        }
        return format && audio ? Format.DSD : Format.UNKNOWN;
    }

    private static Format ogg(Probe p) throws IOException {
        byte[] page = p.read(0, 27);
        if (page[4] != 0 || (page[5] & 2) == 0 || page[26] == 0) return Format.UNKNOWN;
        int count = page[26] & 255;
        byte[] lacing = p.read(27, count);
        int size = 0;
        boolean complete = false;
        for (byte segment : lacing) {
            size += segment & 255;
            if ((segment & 255) < 255) {
                complete = true;
                break;
            }
        }
        if (!complete || size < 8) return Format.UNKNOWN;
        byte[] packet = p.read(27 + count, size);
        if (ascii(packet, 0, "OpusHead") && size >= 19 && (packet[8] & 255) <= 15 && packet[9] != 0)
            return Format.OPUS;
        if (packet[0] == 1
                && ascii(packet, 1, "vorbis")
                && size >= 30
                && packet[11] != 0
                && u32le(packet, 12) > 0) return Format.VORBIS;
        if (packet[0] == 127
                && ascii(packet, 1, "FLAC")
                && size >= 51
                && ascii(packet, 9, "fLaC")
                && flac(Arrays.copyOfRange(packet, 9, packet.length))) return Format.FLAC;
        return Format.UNKNOWN;
    }

    private static int mp3Frame(byte[] b, int at) {
        if (at > b.length - 4 || (b[at] & 255) != 255 || (b[at + 1] & 224) != 224) return -1;
        int version = (b[at + 1] >>> 3) & 3, layer = (b[at + 1] >>> 1) & 3;
        int rate = (b[at + 2] >>> 4) & 15, sample = (b[at + 2] >>> 2) & 3;
        if (version == 1 || layer != 1 || rate == 0 || rate == 15 || sample == 3) return -1;
        int[] high = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        int[] low = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        int hz =
                new int[] {44100, 48000, 32000}[sample] / (version == 3 ? 1 : version == 2 ? 2 : 4);
        return (version == 3 ? 144000 * high[rate] : 72000 * low[rate]) / hz
                + ((b[at + 2] >>> 1) & 1);
    }

    private static int aacFrame(byte[] b, int at) {
        if (at > b.length - 7
                || (b[at] & 255) != 255
                || (b[at + 1] & 246) != 240
                || ((b[at + 2] >>> 2) & 15) >= 13) return -1;
        int size = ((b[at + 3] & 3) << 11) | ((b[at + 4] & 255) << 3) | ((b[at + 5] >>> 5) & 7);
        return size >= ((b[at + 1] & 1) != 0 ? 7 : 9) ? size : -1;
    }

    private static Format mp4(Probe p) throws IOException {
        EnumSet<Format> found = EnumSet.noneOf(Format.class);
        boolean[] video = {false, false, false}; // video handler, audio handler, media data
        atoms(p, 0, p.size, 0, found, video);
        return !video[0] && video[1] && video[2] && found.size() == 1
                ? found.iterator().next()
                : Format.UNKNOWN;
    }

    private static void atoms(
            Probe p, long begin, long end, int depth, EnumSet<Format> found, boolean[] video)
            throws IOException {
        if (depth > 8) throw new Invalid();
        for (long at = begin; at + 8 <= end; ) {
            if (++p.atoms > 4096) throw new Invalid();
            byte[] header = p.read(at, 8);
            long size = u32be(header, 0), head = 8;
            if (size == 1) {
                size = u64be(p.read(at + 8, 8), 0);
                head = 16;
            }
            if (size == 0) size = end - at;
            if (size < head || size > end - at) throw new Invalid();
            if (ascii(header, 4, "moov")
                    || ascii(header, 4, "trak")
                    || ascii(header, 4, "mdia")
                    || ascii(header, 4, "minf")
                    || ascii(header, 4, "stbl"))
                atoms(p, at + head, at + size, depth + 1, found, video);
            else if (ascii(header, 4, "hdlr") && size >= head + 12) {
                byte[] handler = p.read(at + head, 12);
                video[0] |= ascii(handler, 8, "vide");
                video[1] |= ascii(handler, 8, "soun");
            } else if (depth == 0 && ascii(header, 4, "mdat") && size > head) video[2] = true;
            else if (ascii(header, 4, "stsd")) sampleDescriptions(p, at + head, at + size, found);
            at += size;
        }
    }

    private static void sampleDescriptions(Probe p, long begin, long end, EnumSet<Format> found)
            throws IOException {
        if (end - begin < 8) throw new Invalid();
        long count = u32be(p.read(begin, 8), 4);
        if (count > 64) throw new Invalid();
        long at = begin + 8;
        for (long i = 0; i < count; i++) {
            byte[] entry = p.read(at, 36);
            long size = u32be(entry, 0);
            if (size < 36 || size > end - at) throw new Invalid();
            int version = u16be(entry, 16);
            long child = at + (version == 0 ? 36 : version == 1 ? 52 : version == 2 ? 72 : size);
            for (; child + 8 <= at + size; ) {
                byte[] config = p.read(child, 8);
                long length = u32be(config, 0);
                if (length < 8 || length > at + size - child || length > 65536) throw new Invalid();
                if (ascii(entry, 4, "alac") && ascii(config, 4, "alac") && length >= 36) {
                    byte[] alac = p.read(child + 8, 28);
                    int bits = alac[9] & 255;
                    if ((bits == 16 || bits == 20 || bits == 24 || bits == 32)
                            && alac[13] != 0
                            && u32be(alac, 24) > 0) found.add(Format.ALAC);
                }
                if (ascii(entry, 4, "mp4a") && ascii(config, 4, "esds") && length >= 16) {
                    byte[] descriptors = p.read(child + 8, (int) length - 8);
                    for (int index = 4; index + 3 < descriptors.length; index++) {
                        if (descriptors[index] != 4) continue;
                        int cursor = index + 1, descriptorLength = 0, n = 0, digit;
                        do {
                            if (cursor >= descriptors.length || ++n > 4) throw new Invalid();
                            digit = descriptors[cursor++] & 255;
                            descriptorLength = (descriptorLength << 7) | (digit & 127);
                        } while ((digit & 128) != 0);
                        if (descriptorLength < 13 || descriptorLength > descriptors.length - cursor)
                            continue;
                        int type = descriptors[cursor] & 255;
                        if (type == 0x66
                                || type == 0x67
                                || type == 0x68
                                || (type == 0x40
                                        && aacSpecificConfig(
                                                descriptors,
                                                cursor + 13,
                                                cursor + descriptorLength))) found.add(Format.AAC);
                    }
                }
                child += length;
            }
            at += size;
        }
    }

    private static boolean aacSpecificConfig(byte[] bytes, int begin, int end) {
        for (int index = begin; index + 3 < end; index++) {
            if (bytes[index] != 5) continue;
            int cursor = index + 1, size = 0, n = 0, digit;
            do {
                if (cursor >= end || ++n > 4) return false;
                digit = bytes[cursor++] & 255;
                size = (size << 7) | (digit & 127);
            } while ((digit & 128) != 0);
            if (size < 2 || size > end - cursor) continue;
            int object = (bytes[cursor] & 248) >>> 3;
            if (object == 31)
                object = 32 + (((bytes[cursor] & 7) << 3) | ((bytes[cursor + 1] & 224) >>> 5));
            switch (object) {
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 17:
                case 19:
                case 20:
                case 22:
                case 23:
                case 29:
                case 39:
                case 42:
                    return true;
                default:
                    return false;
            }
        }
        return false;
    }
}
