package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public final class AudioFormatPreferencesTest {
    static int checks;

    interface Call {
        void run() throws Exception;
    }

    static void check(boolean okay, String why) {
        checks++;
        if (!okay) throw new AssertionError(why);
    }

    static void denied(Call call) throws Exception {
        try {
            call.run();
            throw new AssertionError("expected rejection");
        } catch (IOException | IllegalArgumentException expected) {
            checks++;
        }
    }

    static final class Memory implements AudioFormatPreferences.Store {
        String value;
        boolean fail;

        public String load() {
            return value;
        }

        public void save(String next) throws IOException {
            if (fail) throw new IOException("disk full");
            value = next;
        }
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static void text(byte[] out, int at, String text) {
        System.arraycopy(bytes(text), 0, out, at, text.length());
    }

    static void le(byte[] out, int at, long value, int count) {
        for (int i = 0; i < count; i++) out[at + i] = (byte) (value >>> (8 * i));
    }

    static void be(byte[] out, int at, long value, int count) {
        for (int i = 0; i < count; i++) out[at + count - i - 1] = (byte) (value >>> (8 * i));
    }

    static byte[] join(byte[]... values) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] v : values) out.write(v);
        return out.toByteArray();
    }

    static byte[] atom(String type, byte[] payload) throws Exception {
        byte[] h = new byte[8];
        be(h, 0, payload.length + 8, 4);
        text(h, 4, type);
        return join(h, payload);
    }

    static File file(File root, String name, byte[] body) throws Exception {
        File f = new File(root, name);
        Files.write(f.toPath(), body);
        return f;
    }

    static byte[] flac() {
        byte[] b = new byte[64];
        text(b, 0, "fLaC");
        b[4] = (byte) 128;
        b[7] = 34;
        be(b, 8, 1024, 2);
        be(b, 10, 1024, 2);
        b[18] = 10;
        b[19] = (byte) 196;
        b[20] = 66;
        b[21] = (byte) 240;
        return b;
    }

    static byte[] wave(int codec) {
        byte[] b = new byte[48];
        text(b, 0, "RIFF");
        le(b, 4, 40, 4);
        text(b, 8, "WAVEfmt ");
        le(b, 16, 16, 4);
        le(b, 20, codec, 2);
        le(b, 22, 2, 2);
        le(b, 24, 44100, 4);
        le(b, 28, 176400, 4);
        le(b, 32, 4, 2);
        le(b, 34, 16, 2);
        text(b, 36, "data");
        le(b, 40, 4, 4);
        return b;
    }

    static byte[] aiff() {
        byte[] b = new byte[58];
        text(b, 0, "FORM");
        be(b, 4, 50, 4);
        text(b, 8, "AIFFCOMM");
        be(b, 16, 18, 4);
        be(b, 20, 2, 2);
        be(b, 22, 1, 4);
        be(b, 26, 16, 2);
        be(b, 28, 0x400e, 2);
        b[30] = (byte) 172;
        b[31] = 68;
        text(b, 38, "SSND");
        be(b, 42, 12, 4);
        return b;
    }

    static byte[] aifc(String compression) throws Exception {
        byte[] original = aiff(), comm = new byte[24], form = new byte[12], sound = new byte[20];
        System.arraycopy(original, 20, comm, 0, 18);
        text(comm, 18, compression);
        text(form, 0, "FORM");
        be(form, 4, 56, 4);
        text(form, 8, "AIFC");
        text(sound, 0, "SSND");
        be(sound, 4, 12, 4);
        byte[] commHeader = new byte[8];
        text(commHeader, 0, "COMM");
        be(commHeader, 4, comm.length, 4);
        return join(form, commHeader, comm, sound);
    }

    static byte[] dsf() {
        byte[] b = new byte[96];
        text(b, 0, "DSD ");
        le(b, 4, 28, 8);
        le(b, 12, b.length, 8);
        text(b, 28, "fmt ");
        le(b, 32, 52, 8);
        le(b, 40, 1, 4);
        le(b, 48, 2, 4);
        le(b, 52, 2, 4);
        le(b, 56, 2822400, 4);
        le(b, 60, 1, 4);
        le(b, 64, 8, 8);
        le(b, 72, 4096, 4);
        text(b, 80, "data");
        le(b, 84, 16, 8);
        return b;
    }

    static byte[] ogg(boolean opus) throws Exception {
        byte[] packet = new byte[opus ? 19 : 30];
        if (opus) {
            text(packet, 0, "OpusHead");
            packet[8] = 1;
            packet[9] = 2;
            le(packet, 12, 48000, 4);
        } else {
            packet[0] = 1;
            text(packet, 1, "vorbis");
            packet[11] = 2;
            le(packet, 12, 44100, 4);
            packet[28] = (byte) 184;
            packet[29] = 1;
        }
        byte[] header = new byte[28];
        text(header, 0, "OggS");
        header[5] = 2;
        header[26] = 1;
        header[27] = (byte) packet.length;
        return join(header, packet);
    }

    static byte[] mp3() {
        byte[] b = new byte[834];
        for (int at : new int[] {0, 417}) {
            b[at] = (byte) 255;
            b[at + 1] = (byte) 251;
            b[at + 2] = (byte) 144;
        }
        return b;
    }

    static byte[] aac() {
        return new byte[] {
            (byte) 255,
            (byte) 241,
            80,
            (byte) 128,
            0,
            (byte) 255,
            (byte) 252,
            (byte) 255,
            (byte) 241,
            80,
            (byte) 128,
            0,
            (byte) 255,
            (byte) 252
        };
    }

    static byte[] mp4(boolean alac, boolean video) throws Exception {
        byte[] config;
        if (alac) {
            byte[] c = new byte[28];
            be(c, 4, 4096, 4);
            c[9] = 16;
            c[13] = 2;
            be(c, 24, 44100, 4);
            config = atom("alac", c);
        } else {
            byte[] c = new byte[23];
            c[4] = 4;
            c[5] = 17;
            c[6] = 64;
            c[7] = 21;
            c[19] = 5;
            c[20] = 2;
            c[21] = 18;
            c[22] = 16;
            config = atom("esds", c);
        }
        byte[] sample = new byte[36];
        be(sample, 0, sample.length + config.length, 4);
        text(sample, 4, alac ? "alac" : "mp4a");
        be(sample, 14, 1, 2);
        be(sample, 24, 2, 2);
        be(sample, 26, 16, 2);
        be(sample, 32, 44100L << 16, 4);
        byte[] descriptions = new byte[8];
        be(descriptions, 4, 1, 4);
        byte[] hdlr = new byte[12];
        text(hdlr, 8, video ? "vide" : "soun");
        byte[] media =
                atom(
                        "mdia",
                        join(
                                atom("hdlr", hdlr),
                                atom(
                                        "minf",
                                        atom(
                                                "stbl",
                                                atom(
                                                        "stsd",
                                                        join(descriptions, sample, config))))));
        return join(
                atom("ftyp", bytes("M4A \0\0\0\0")),
                atom("moov", atom("trak", media)),
                atom("mdat", new byte[] {0}));
    }

    static SoulseekTrack track(String artist, String ext, int duration) throws Exception {
        return new SoulseekTrack(
                new JSONObject()
                        .put("peer", ext + artist)
                        .put("path", artist + " - Song." + ext)
                        .put("size", 1000)
                        .put("duration", duration));
    }

    public static void main(String[] args) throws Exception {
        Memory memory = new Memory();
        AudioFormatPreferences.Settings defaults = AudioFormatPreferences.load(memory);
        check(
                !defaults.configured
                        && defaults.order.size() == 9
                        && defaults.order.get(0) == AudioFormatPreferences.Format.FLAC,
                "default order and first-run state");
        AudioFormatPreferences.Settings reordered =
                defaults.move(8, -1).withEnabled(AudioFormatPreferences.Format.FLAC, false);
        AudioFormatPreferences.save(memory, reordered);
        AudioFormatPreferences.Settings saved = AudioFormatPreferences.load(memory);
        check(
                saved.configured
                        && saved.order.get(7) == AudioFormatPreferences.Format.MP3
                        && !saved.enabled.contains(AudioFormatPreferences.Format.FLAC),
                "order and disabled choices persist");
        memory.fail = true;
        String before = memory.value;
        denied(() -> AudioFormatPreferences.save(memory, defaults));
        check(memory.value.equals(before), "failed save does not commit");
        memory.fail = false;
        memory.value = "1|FLAC:1,FLAC:1";
        check(
                !AudioFormatPreferences.load(memory).configured,
                "corrupt preferences require setup again");
        denied(
                () ->
                        AudioFormatPreferences.decode(
                                AudioFormatPreferences.encode(defaults)
                                        .replace("ALAC:1", "FLAC:1")));
        denied(
                () ->
                        AudioFormatPreferences.decode(
                                AudioFormatPreferences.encode(defaults).replace(":1", ":0")));
        denied(
                () ->
                        AudioFormatPreferences.decode(
                                "1|" + new String(new char[200]).replace('\0', 'A')));
        AudioFormatPreferences.Settings all =
                AudioFormatPreferences.decode(AudioFormatPreferences.encode(defaults));
        AudioFormatPreferences.Settings onlyAlac =
                new AudioFormatPreferences.Settings(
                        all.order, EnumSet.of(AudioFormatPreferences.Format.ALAC), true);
        denied(() -> onlyAlac.withEnabled(AudioFormatPreferences.Format.ALAC, false));
        check(
                AudioFormatPreferences.candidateFormats("one.m4a").size() == 2
                        && AudioFormatPreferences.candidateFormats("one.ogg").size() > 1,
                "container names remain ambiguous");
        check(
                AudioFormatPreferences.allowsCandidate(onlyAlac, "one.m4a")
                        && !AudioFormatPreferences.allowsCandidate(onlyAlac, "one.mp3"),
                "disabled candidate prefilter");
        denied(() -> AudioFormatPreferences.audioName("song.flac.exe"));
        SoulseekTrack first = track("Artist A", "mp3", 200),
                other = track("Artist B", "flac", 200),
                preferred = track("Artist A", "flac", 200);
        List<SoulseekTrack> ordered =
                AudioFormatPreferences.ordered(all, Arrays.asList(first, other, preferred));
        check(
                ordered.get(0) == preferred && ordered.get(1) == other && ordered.get(2) == first,
                "format ranking stays inside same recording metadata group");
        check(first.id.equals(ordered.get(2).id), "manual selected identity remains exact");
        SoulseekTrack different = track("Artist A", "flac", 201);
        check(
                AudioFormatPreferences.ordered(all, Arrays.asList(first, different)).get(0)
                        == first,
                "different duration is not substituted");
        check(
                track("Artist", "m4a", 200).format.equals("unknown")
                        && !preferred.metadata().getBoolean("formatVerified"),
                "peer codec and provenance never marked verified");
        File root = Files.createTempDirectory("audio-format-test-").toFile();
        try {
            Object[][] cases = {
                {"a.flac", flac(), AudioFormatPreferences.Format.FLAC},
                {"a.wav", wave(1), AudioFormatPreferences.Format.WAV},
                {"a.aiff", aiff(), AudioFormatPreferences.Format.AIFF},
                {"a.aifc", aifc("NONE"), AudioFormatPreferences.Format.AIFF},
                {"a.dsf", dsf(), AudioFormatPreferences.Format.DSD},
                {"a.opus", ogg(true), AudioFormatPreferences.Format.OPUS},
                {"a.ogg", ogg(false), AudioFormatPreferences.Format.VORBIS},
                {"a.mp3", mp3(), AudioFormatPreferences.Format.MP3},
                {"a.aac", aac(), AudioFormatPreferences.Format.AAC},
                {"alac.m4a", mp4(true, false), AudioFormatPreferences.Format.ALAC},
                {"aac.m4a", mp4(false, false), AudioFormatPreferences.Format.AAC}
            };
            for (Object[] row : cases) {
                File f = file(root, (String) row[0], (byte[]) row[1]);
                check(
                        AudioFormatPreferences.validateSelectedFile(all, f, f.getName()) == row[2],
                        "actual codec detected: " + row[0]);
            }
            File alacFile = new File(root, "alac.m4a"), aacFile = new File(root, "aac.m4a");
            check(
                    AudioFormatPreferences.validateSelectedFile(onlyAlac, alacFile, "one.m4a")
                            == AudioFormatPreferences.Format.ALAC,
                    "ALAC selected from actual M4A content");
            denied(() -> AudioFormatPreferences.validateSelectedFile(onlyAlac, aacFile, "one.m4a"));
            denied(
                    () ->
                            AudioFormatPreferences.validateSelectedFile(
                                    defaults, alacFile, "one.m4a"));
            denied(
                    () ->
                            AudioFormatPreferences.validateSelectedFile(
                                    all, new File(root, "a.mp3"), "fake.flac"));
            check(
                    AudioFormatPreferences.probe(file(root, "compressed.wav", wave(85)))
                            == AudioFormatPreferences.Format.UNKNOWN,
                    "compressed WAV is not classified PCM");
            check(
                    AudioFormatPreferences.probe(file(root, "compressed.aifc", aifc("ima4")))
                            == AudioFormatPreferences.Format.UNKNOWN,
                    "unsupported AIFC compression is not classified PCM");
            check(
                    AudioFormatPreferences.probe(file(root, "video.mp4", mp4(false, true)))
                            == AudioFormatPreferences.Format.UNKNOWN,
                    "video container rejected");
            byte[] tag = new byte[32];
            text(tag, 0, "ID3");
            check(
                    AudioFormatPreferences.probe(file(root, "tag.mp3", tag))
                            == AudioFormatPreferences.Format.UNKNOWN,
                    "ID3 tag alone never establishes MP3");
            byte[] fakeDsd = new byte[96];
            text(fakeDsd, 0, "DSD ");
            check(
                    AudioFormatPreferences.probe(file(root, "fake.dsf", fakeDsd))
                            == AudioFormatPreferences.Format.UNKNOWN,
                    "DSD magic alone insufficient");
            byte[] truncated = Arrays.copyOf(mp4(true, false), 40);
            check(
                    AudioFormatPreferences.probe(file(root, "bad.m4a", truncated))
                            == AudioFormatPreferences.Format.UNKNOWN,
                    "truncated atoms rejected");
        } finally {
            for (File f : root.listFiles()) f.delete();
            root.delete();
        }
        System.out.println(
                "PASS: " + checks + " ordered format, persistence and actual-codec checks.");
    }
}
