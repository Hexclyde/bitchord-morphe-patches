package app.bitchord.telegram;

import org.json.JSONObject;

/** The container BitChord 1.9's download check is told for each kind of Telegram file. */
public final class DownloadProbeTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static String of(String mime, String format, String codec) throws Exception {
        return NativeDownloads.container(
                new JSONObject().put("mime", mime).put("format", format), codec);
    }

    public static void main(String[] args) throws Exception {
        check("FLAC".equals(of("audio/flac", "flac", "flac")), "FLAC");
        check("FLAC".equals(of("audio/x-flac", "", null)), "FLAC by type alone");
        check("MP3".equals(of("audio/mpeg", "mp3", "mp3")), "MP3");
        check("MP4".equals(of("audio/mp4", "m4a", "alac")), "ALAC in .m4a");
        check("MP4".equals(of("audio/x-m4a", "m4a", "aac")), "AAC in .m4a is MP4, not ADTS");
        check("ADTS".equals(of("audio/aac", "aac", "aac")), "raw AAC");
        check("WAV".equals(of("audio/x-wav", "wav", null)), "WAV");
        check("OGG".equals(of("audio/ogg", "opus", "opus")), "Opus");
        check("WEBM".equals(of("audio/webm", "webm", null)), "WebM");
        check("WEBM".equals(of("audio/x-matroska", "mka", null)), "Matroska, as BitChord reads it");
        check(of("", "", null) == null, "unknown");
        check(NativeDownloads.probe("https://example.com/a.flac", "flac") == null, "not ours");
        java.io.File temporary =
                java.nio.file.Files.createTempFile("telegram-probe-", ".audio").toFile();
        try {
            TelegramBridge bridge = new TelegramBridge(temporary);
            String url = bridge.url() + "/audio/10_20";
            Object found = NativeDownloads.probe(url, "flac");
            check(
                    found instanceof com.music.bitchord.download.Downloader.Probe.File,
                    "The private file becomes the host's actual probe-file shape");
            com.music.bitchord.download.Downloader.Probe.File file =
                    (com.music.bitchord.download.Downloader.Probe.File) found;
            check(
                    file.container == com.music.bitchord.download.Downloader.Container.FLAC,
                    "The index supplies the expected host container enum");
            check(
                    file.totalBytes != null && file.totalBytes == bridge.length,
                    "The saved file size reaches the host download selector");
            check(
                    bridge.downloads == 0 && bridge.optimizations == 0,
                    "The metadata check never opens an audio download");
            bridge.sessionTag = "changed-after-search";
            check(
                    NativeDownloads.probe(url, "flac") == null,
                    "A stale session cannot pass the host's candidate check");
            bridge.signedIn = false;
            check(
                    NativeDownloads.probe(bridge.url() + "/audio/10_20", "flac") == null,
                    "A signed-out account cannot pass the candidate check");
        } finally {
            temporary.delete();
            TelegramBridge.instance = null;
        }
        System.out.println("PASS: " + checks + " download check containers.");
    }
}
