package app.bitchord.telegram;

import com.music.bitchord.data.NerdStats.Snapshot;
import com.music.bitchord.data.TelemetryProvenance;

import kotlin.Pair;

public final class PlaybackStatsTest {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static Snapshot snapshot(String mime, Integer rate, String source, TelemetryProvenance origin) {
        Snapshot s = new Snapshot(mime, rate, source);
        PlaybackStats.capture(s, origin);
        return s;
    }

    public static void main(String[] args) throws Exception {
        String format = "FLAC · 16-bit · 44.1 kHz · Stereo";
        Snapshot flac =
                snapshot("audio/flac", 921, "Unofficial Telegram", TelemetryProvenance.MEASURED);
        check(
                PlaybackStats.decorate(format, flac)
                        .equals(format + "\nUnofficial Telegram · ≈921 kbps avg"),
                "Compressed average on separate source line");
        check(
                !PlaybackStats.decorate(format, flac).contains("1411"),
                "Never substitute uncompressed PCM throughput for FLAC bitrate");
        Snapshot unknown =
                snapshot("audio/flac", null, "Unofficial Telegram", TelemetryProvenance.UNKNOWN);
        check(
                PlaybackStats.decorate(format, unknown).endsWith("Bitrate unavailable"),
                "No fabricated missing bitrate");
        Pair<Integer, TelemetryProvenance> measured = PlaybackStats.measureLossless(() -> 875);
        check(
                measured.getFirst() == 875 && measured.getSecond() == TelemetryProvenance.MEASURED,
                "Host measurement receives correct provenance");
        check(
                PlaybackStats.measureLossless(() -> null) == null,
                "Pending measurement stays unknown");
        check(PlaybackStats.measureLossless(() -> 0) == null, "Zero measurement rejected");
        check(PlaybackStats.measureLossless(() -> -1) == null, "Invalid measurement rejected");
        check(
                PlaybackStats.measureLossless(() -> "160") == null,
                "Wrong measurement type rejected");
        check(PlaybackStats.measureLossless(null) == null, "Missing callback rejected");
        Snapshot opus = snapshot("audio/opus", 160, "YouTube", TelemetryProvenance.AUTHORITATIVE);
        String opusText = PlaybackStats.decorate("Opus · 48.0 kHz · 160 kbps · Stereo", opus);
        check(
                opusText.equals("Opus · 48.0 kHz · Stereo\nYouTube · 160 kbps"),
                "Existing bitrate moves without duplication");
        check(
                PlaybackStats.decorate(format, flac)
                        .endsWith("Unofficial Telegram · ≈921 kbps avg"),
                "Later snapshots do not change earlier source/provenance");
        Snapshot inferred = snapshot("audio/mpeg", 320, "Other addon", TelemetryProvenance.DERIVED);
        check(
                PlaybackStats.decorate("MP3 · 320 kbps", inferred).endsWith("≈320 kbps estimated"),
                "Tier guesses identified as estimated");
        Snapshot pcm = snapshot("audio/raw", 1411, "Local Storage", TelemetryProvenance.DERIVED);
        check(
                PlaybackStats.decorate("PCM · 16-bit · 44.1 kHz · 1411 kbps · Stereo", pcm)
                        .endsWith("1411 kbps PCM"),
                "PCM rate clearly identified for raw audio");
        check(
                PlaybackStats.decorate("FLAC · 16 bits · 44,1 kHz · Stéréo", flac)
                        .startsWith("FLAC · 16 bits · 44,1 kHz · Stéréo\n"),
                "Localized host format labels retained");
        check(
                PlaybackStats.decorate(format, snapshot("audio/flac", 0, null, null))
                        .endsWith("Unknown source · Bitrate unavailable"),
                "Missing source and zero bitrate handled");
        check(
                PlaybackStats.decorate(format, snapshot("audio/flac", -1, "  ", null))
                        .endsWith("Unknown source · Bitrate unavailable"),
                "Blank source and negative bitrate handled");
        check(
                PlaybackStats.decorate("", opus).equals("YouTube · 160 kbps"),
                "Empty format does not add empty line");
        check(
                PlaybackStats.decorate(null, opus).equals("YouTube · 160 kbps"),
                "Null format handled");
        check(
                PlaybackStats.decorate(format, null).equals(format),
                "Null snapshot leaves host description intact");
        check(
                PlaybackStats.sourceLabel("Telegram\n\t(on device)\u202e")
                        .equals("Telegram (on device)"),
                "Source controls cannot inject extra lines");
        String longName = "🎵".repeat(50);
        String shortened = PlaybackStats.sourceLabel(longName);
        check(
                shortened.codePointCount(0, shortened.length()) == 40 && shortened.endsWith("…"),
                "Long source shortened without splitting Unicode characters");
        Snapshot switched =
                snapshot("audio/mpeg", 256, "Different addon", TelemetryProvenance.AUTHORITATIVE);
        check(
                PlaybackStats.decorate("MP3 · 256 kbps", switched)
                        .endsWith("Different addon · 256 kbps"),
                "Actual fallback source replaces previous source");
        Snapshot later = snapshot("audio/flac", 723, "Telegram", TelemetryProvenance.MEASURED);
        Thread worker =
                new Thread(
                        () -> {
                            for (int i = 0; i < 40; i++)
                                PlaybackStats.capture(opus, TelemetryProvenance.AUTHORITATIVE);
                        });
        worker.start();
        worker.join();
        check(
                PlaybackStats.decorate(format, later).endsWith("≈723 kbps avg"),
                "Concurrent capture preserves snapshot identity");
        // A substituted FLAC played again from BitChord's song cache keeps its YouTube label.
        TelegramBridge.instance = null;
        check(
                PlaybackStats.sourceOf("YouTube", "audio/flac").equals("Unknown source"),
                "YouTube never sends FLAC");
        check(PlaybackStats.sourceOf("YouTube", "audio/opus").equals("YouTube"), "Opus is");
        check(
                PlaybackStats.sourceOf("Local files", "audio/flac").equals("Local files"),
                "Other sources stay as they are");
        check(
                PlaybackStats.decorate(
                                "FLAC · 24-bit · 96.0 kHz · Stereo",
                                snapshot(
                                        "audio/flac",
                                        2862,
                                        "YouTube",
                                        TelemetryProvenance.MEASURED))
                        .contains("Unknown source · ≈2862 kbps avg"),
                "The stats line says so");
        java.lang.reflect.Constructor<com.music.bitchord.data.sources.StreamFormat> ctor =
                com.music.bitchord.data.sources.StreamFormat.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        com.music.bitchord.data.sources.StreamFormat candidate = ctor.newInstance();
        java.lang.reflect.Field codec = candidate.getClass().getField("codec");
        codec.setAccessible(true);
        codec.set(candidate, "FLAC");
        Snapshot mixed =
                new Snapshot(
                        "audio/opus",
                        3296,
                        48000,
                        2,
                        null,
                        candidate,
                        "Unofficial Telegram",
                        null,
                        TelemetryProvenance.DERIVED,
                        null,
                        "rAeanjCnUmI");
        PlaybackStats.capture(mixed, TelemetryProvenance.DERIVED);
        AudioOrigin.Source unknownRateYoutube =
                new AudioOrigin.Source(
                        "youtube", "YouTube", "YOUTUBE", "served", "opus", null, null, 48000);
        AudioOrigin.Source measuredTelegram =
                new AudioOrigin.Source(
                        "telegram-native",
                        "Unofficial Telegram",
                        "ADDON",
                        "served",
                        "flac",
                        3296,
                        24,
                        96000);
        Snapshot pending = PlaybackStats.bound(mixed, null, unknownRateYoutube, true);
        check(
                pending.bitrateKbps == null
                        && pending.claimed == null
                        && pending.sourceName.equals("YouTube")
                        && pending.mimeType.equals("audio/opus"),
                "Downer: a verified but inaudible FLAC cannot label the Opus snapshot");
        Snapshot failed = PlaybackStats.bound(mixed, 116, unknownRateYoutube, true);
        check(
                failed.bitrateKbps == 116 && failed.claimed == null,
                "Aborted handover retains the encoded Opus measurement");
        Snapshot heardFlac =
                new Snapshot(
                        "audio/flac",
                        3296,
                        96000,
                        2,
                        24,
                        candidate,
                        "Unofficial Telegram",
                        null,
                        TelemetryProvenance.MEASURED,
                        null,
                        "rAeanjCnUmI");
        PlaybackStats.capture(heardFlac, TelemetryProvenance.MEASURED);
        Snapshot adopted = PlaybackStats.bound(heardFlac, null, measuredTelegram, true);
        check(
                adopted.claimed == null
                        && adopted.bitrateKbps == 3296
                        && adopted.sampleRateHz == 96000
                        && adopted.bitDepth == 24,
                "Confirmed FLAC keeps a coherent encoded-format snapshot");
        check(
                PlaybackStats.decorate("FLAC", adopted).contains("≈3296 kbps avg"),
                "Confirmed file average is labelled, not PCM throughput");
        Snapshot cancelled = PlaybackStats.bound(heardFlac, null, null, false);
        check(
                cancelled.claimed == null && cancelled.bitrateKbps == null,
                "Changed rendition or generation cannot reuse asynchronous candidate metadata");
        check(
                !PlaybackStats.compatible("audio/opus", "FLAC")
                        && !PlaybackStats.compatible("audio/raw", "FLAC"),
                "Decoded PCM does not prove FLAC encoding");
        AudioOrigin.Source youtube =
                new AudioOrigin.Source(
                        "youtube", "YouTube", "YOUTUBE", "served", "opus", 116, null, 48000);
        Snapshot audible = PlaybackStats.bound(mixed, null, youtube, true);
        check(
                audible.bitrateKbps == 116
                        && audible.claimed == null
                        && audible.sourceName.equals("YouTube")
                        && audible.bitDepth == null,
                "Publication uses the audible rendition record instead of the shared song claim");
        AudioOrigin.Source telegram =
                new AudioOrigin.Source(
                        "telegram-native",
                        "Unofficial Telegram",
                        "ADDON",
                        "served",
                        "flac",
                        3296,
                        24,
                        96000);
        Snapshot confirmed = PlaybackStats.bound(heardFlac, null, telegram, true);
        check(
                confirmed.bitrateKbps == 3296
                        && confirmed.bitDepth == 24
                        && confirmed.sampleRateHz == 96000
                        && confirmed.claimed == null,
                "A confirmed source supplies encoded depth without retaining unbound song claims");
        Snapshot unknownDepth = PlaybackStats.bound(heardFlac, null, null, true);
        check(
                unknownDepth.bitDepth == null && unknownDepth.sourceName.equals("Unknown source"),
                "Decoder PCM depth and codec cannot invent original bit depth or provider");
        Snapshot stale = PlaybackStats.bound(heardFlac, 3296, telegram, false);
        check(
                stale.bitrateKbps == null && stale.bitDepth == null && stale.claimed == null,
                "A stale generation discards even an otherwise valid old measurement");
        AudioOrigin.Source opusPcm =
                new AudioOrigin.Source(
                        "youtube", "YouTube", "YOUTUBE", "served", "opus", 116, 16, 48000);
        check(
                PlaybackStats.bound(mixed, null, opusPcm, true).bitDepth == null,
                "PCM depth is not the encoded depth of lossy Opus");
        System.out.println(
                "PASS: " + checks + " playback source/bitrate provenance and formatting checks.");
    }
}
