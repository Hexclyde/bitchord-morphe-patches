package app.bitchord.telegram;

import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;

import kotlinx.coroutines.JobKt;

import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Constructor;
import java.util.Locale;

/** The host's explicit-download flow uses the same private reader as playback. */
public final class NativeDownloads {
    private NativeDownloads() {}

    /**
     * BitChord 1.9 opens every download candidate's URL before letting it win, to see what's there.
     * A Telegram file has no web address to open, so its answer comes from the index instead:
     * BitChord's {@code Probe.File} with the file's container and size, or null for a file BitChord
     * couldn't keep. Called on BitChord's IO thread, only for Telegram's identifiers, which are
     * never opened over the network.
     */
    public static Object probe(String url, String statedCodec) {
        if (!InternalAddon.reserved(url)) return null;
        try {
            TelegramBridge bridge = InternalAddon.bridge();
            JSONObject row = bridge.resolveTrack(InternalAddon.audioId(bridge, url));
            String container = row == null ? null : container(row, statedCodec);
            if (container == null) {
                HostLog.d("Download check: no file BitChord can keep for " + url);
                return null;
            }
            long size = row.optLong("size");
            Class<?> type = Class.forName("com.music.bitchord.download.Downloader$Container");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) type, container);
            Class<?> file = Class.forName("com.music.bitchord.download.Downloader$Probe$File");
            Constructor<?> make = file.getDeclaredConstructor(type, Long.class);
            make.setAccessible(true);
            return make.newInstance(value, size > 0 ? size : null);
        } catch (Exception | LinkageError e) {
            HostLog.d("Download check for a Telegram file failed: " + e);
            return null;
        }
    }

    /** BitChord's container name for a Telegram file, from its type and name. */
    static String container(JSONObject row, String statedCodec) {
        String mime = row.optString("mime").toLowerCase(Locale.ROOT);
        String format = row.optString("format").toLowerCase(Locale.ROOT);
        String codec = statedCodec == null ? "" : statedCodec.toLowerCase(Locale.ROOT);
        if (mime.contains("flac") || format.equals("flac")) return "FLAC";
        if (mime.contains("wav") || format.equals("wav")) return "WAV";
        if (mime.contains("mpeg") || format.equals("mp3")) return "MP3";
        if (mime.contains("mp4")
                || mime.contains("m4a")
                || format.equals("m4a")
                || format.equals("mp4")
                || format.equals("alac")) return "MP4";
        if (format.equals("aac") || mime.equals("audio/aac") || codec.equals("aac")) return "ADTS";
        if (mime.contains("ogg")
                || mime.contains("opus")
                || format.equals("ogg")
                || format.equals("opus")) return "OGG";
        // BitChord's own check reads Matroska's header as WebM, the same EBML format.
        if (mime.contains("webm")
                || mime.contains("matroska")
                || format.equals("webm")
                || format.equals("mka")) return "WEBM";
        return null;
    }

    public static Long fetch(
            String url,
            OutputStream sink,
            Function2<Long, Long, ?> progress,
            CoroutineContext context)
            throws IOException {
        JobKt.ensureActive(context);
        TelegramBridge bridge = InternalAddon.bridge();
        try (NativeAudio.Reader reader = bridge.audio.open(url, 0, -1)) {
            reader.cancellation = () -> JobKt.ensureActive(context);
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            int count;
            while ((count = reader.read(buffer, 0, buffer.length)) != -1) {
                JobKt.ensureActive(context);
                sink.write(buffer, 0, count);
                written += count;
                progress.invoke(written, reader.total);
            }
            JobKt.ensureActive(context);
            sink.flush();
            if (written == 0) throw new IOException("Telegram audio file is empty");
            return written;
        }
    }
}
