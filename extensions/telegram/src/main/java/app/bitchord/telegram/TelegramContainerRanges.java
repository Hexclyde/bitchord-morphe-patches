package app.bitchord.telegram;

/** Bounded ISO-BMFF header hints. Never guess an index offset from a bitrate. */
final class TelegramContainerRanges {
    static String format(String mime) {
        if (mime == null) return "unknown";
        if (mime.contains("flac")) return "flac";
        if (mime.contains("mp4") || mime.contains("m4a")) return "mp4";
        if (mime.contains("mpeg")) return "mp3";
        if (mime.contains("opus") || mime.contains("ogg")) return "ogg";
        if (mime.contains("wav")) return "wav";
        return "unknown";
    }

    static String sniff(byte[] data, int count, String fallback) {
        if (count >= 4 && tag(data, 0, "fLaC")) return "flac";
        if (count >= 4 && tag(data, 0, "OggS")) return "ogg";
        if (count >= 12 && tag(data, 4, "ftyp")) return "mp4";
        if (count >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') return "mp3";
        return fallback;
    }

    /** Return the atom after mdat/free/skip, where a trailing moov may begin; -1 if unknown. */
    static long next(byte[] data, int count, long base, long total) {
        if (count < 8 || total <= 0 || base < 0 || base > total) return -1;
        int at = 0;
        while (at <= count - 8) {
            long size = u32(data, at);
            int header = 8;
            if (size == 1) {
                if (at > count - 16 || (data[at + 8] & 0x80) != 0) return -1;
                size = (u32(data, at + 8) << 32) | u32(data, at + 12);
                header = 16;
            }
            if (size == 0 || size < header || size > total - base - at) return -1;
            if (tag(data, at + 4, "moov")) return -1; // Already available to the extractor.
            long end = base + at + size;
            if (size > count - at)
                return (tag(data, at + 4, "mdat")
                                        || tag(data, at + 4, "free")
                                        || tag(data, at + 4, "skip"))
                                && end <= total - 8
                        ? end
                        : -1;
            at += (int) size;
        }
        return -1;
    }

    private static long u32(byte[] data, int at) {
        long value = 0;
        for (int n = 0; n < 4; n++) value = (value << 8) | (data[at + n] & 255L);
        return value;
    }

    private static boolean tag(byte[] data, int at, String tag) {
        for (int n = 0; n < 4; n++) if (data[at + n] != tag.charAt(n)) return false;
        return true;
    }
}
