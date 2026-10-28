package app.bitchord.telegram;

import org.json.JSONObject;

/** Reads codec, bit depth, sample rate and channels from the first bytes of a lossless file. */
public final class AudioHeader {
    private AudioHeader() {}

    /** What a header says, or null when it isn't FLAC, WAV or AIFF or is cut short. */
    public static JSONObject parse(byte[] head, int length) {
        try {
            int start = id3Size(head, length);
            if (matches(head, length, start, "fLaC")) return flac(head, length, start + 4);
            if (matches(head, length, 0, "RIFF") || matches(head, length, 0, "RF64"))
                return wav(head, length);
            if (matches(head, length, 0, "FORM")
                    && (matches(head, length, 8, "AIFF") || matches(head, length, 8, "AIFC")))
                return aiff(head, length);
        } catch (RuntimeException e) {
            // A truncated or odd header just means no details.
        }
        return null;
    }

    /** The legend for a parsed header, such as "FLAC 24bit". */
    public static String label(JSONObject header) {
        if (header == null) return "";
        String codec = header.optString("codec").toUpperCase(java.util.Locale.ROOT);
        int bits = header.optInt("bits");
        return codec.isEmpty() ? "" : bits > 0 ? codec + " " + bits + "bit" : codec;
    }

    private static JSONObject flac(byte[] b, int length, int at) throws RuntimeException {
        // First metadata block must be STREAMINFO (type 0), 34 bytes.
        if (at + 4 + 18 > length || (b[at] & 0x7f) != 0) return null;
        int s = at + 4 + 10;
        int rate = ((b[s] & 0xff) << 12) | ((b[s + 1] & 0xff) << 4) | ((b[s + 2] & 0xff) >> 4);
        int channels = ((b[s + 2] & 0xff) >> 1 & 7) + 1;
        int bits = (((b[s + 2] & 1) << 4) | ((b[s + 3] & 0xff) >> 4)) + 1;
        return details("flac", bits, rate, channels);
    }

    private static JSONObject wav(byte[] b, int length) {
        if (!matches(b, length, 8, "WAVE")) return null;
        int at = 12;
        while (at + 8 <= length) {
            int size = le32(b, at + 4);
            if (matches(b, length, at, "fmt ") && at + 8 + 16 <= length) {
                int format = le16(b, at + 8);
                int channels = le16(b, at + 10);
                int rate = le32(b, at + 12);
                int bits = le16(b, at + 22);
                // 1 = PCM, 3 = float, 0xFFFE = extensible (sub-format follows; PCM in practice).
                if (format != 1 && format != 3 && format != 0xFFFE) return null;
                return details("wav", bits, rate, channels);
            }
            if (size < 0) return null;
            at += 8 + size + (size & 1);
        }
        return null;
    }

    private static JSONObject aiff(byte[] b, int length) {
        int at = 12;
        while (at + 8 <= length) {
            int size = be32(b, at + 4);
            if (matches(b, length, at, "COMM") && at + 8 + 18 <= length) {
                int channels = be16(b, at + 8);
                int bits = be16(b, at + 14);
                int rate = extended(b, at + 16);
                return details("aiff", bits, rate, channels);
            }
            if (size < 0) return null;
            at += 8 + size + (size & 1);
        }
        return null;
    }

    private static JSONObject details(String codec, int bits, int rate, int channels) {
        if (bits < 4 || bits > 64 || rate < 1000 || rate > 1_000_000 || channels < 1) return null;
        try {
            return new JSONObject()
                    .put("codec", codec)
                    .put("bits", bits)
                    .put("rate", rate)
                    .put("channels", channels);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Size of a leading ID3v2 tag, which some FLAC files carry, or 0. */
    static int id3Size(byte[] b, int length) {
        if (!matches(b, length, 0, "ID3") || length < 10) return 0;
        int size =
                ((b[6] & 0x7f) << 21)
                        | ((b[7] & 0x7f) << 14)
                        | ((b[8] & 0x7f) << 7)
                        | (b[9] & 0x7f);
        return 10 + size + ((b[5] & 0x10) != 0 ? 10 : 0);
    }

    /** 80-bit IEEE extended float, as AIFF stores its sample rate. */
    static int extended(byte[] b, int at) {
        int exponent = ((b[at] & 0x7f) << 8) | (b[at + 1] & 0xff);
        long mantissa = 0;
        for (int i = 0; i < 8; i++) mantissa = (mantissa << 8) | (b[at + 2 + i] & 0xff);
        int shift = 16383 + 63 - exponent;
        if (shift < 0 || shift > 63) return 0;
        return (int) (mantissa >>> shift);
    }

    static boolean matches(byte[] b, int length, int at, String text) {
        if (at < 0 || at + text.length() > length) return false;
        for (int i = 0; i < text.length(); i++) if (b[at + i] != text.charAt(i)) return false;
        return true;
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static int le32(byte[] b, int at) {
        return le16(b, at) | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private static int be16(byte[] b, int at) {
        return (b[at] & 0xff) << 8 | (b[at + 1] & 0xff);
    }

    private static int be32(byte[] b, int at) {
        return be16(b, at) << 16 | be16(b, at + 2);
    }
}
