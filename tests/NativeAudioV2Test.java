package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

import java.io.*;
import java.nio.file.*;

/** Real reader, local disk and scripted TDLib facade; no native library/account/network. */
public final class NativeAudioV2Test {
    static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    static void enable(TelegramBridge bridge) {
        TelegramStreamCoordinator coordinator =
                new TelegramStreamCoordinator(
                        new TelegramStreamCoordinator.Backend() {
                            public long prefix(int file, long at) throws Exception {
                                return bridge.call(new TdApi.GetFileDownloadedPrefixSize(file, at))
                                        .size;
                            }

                            public void download(
                                    int file,
                                    int priority,
                                    long at,
                                    long count,
                                    TelegramStreamCoordinator.Reply reply) {
                                try {
                                    bridge.call(
                                            new TdApi.DownloadFile(
                                                    file, priority, at, count, false));
                                    reply.done(null, 0);
                                } catch (Exception e) {
                                    reply.done(new IOException(e), 0);
                                }
                            }

                            public void cancel(int file) {
                                bridge.cancellations++;
                            }
                        },
                        () -> System.nanoTime() / 1_000_000L);
        coordinator.fallbackMs = 5;
        bridge.audio.windows = coordinator;
    }

    static void content(byte[] data, int count, long at) {
        for (int n = 0; n < count; n++)
            check(
                    data[n] == TelegramBridge.expected(at + n),
                    "Correct cached bytes at " + (at + n));
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("telegram-v2-reader");
        try {
            TelegramBridge bridge = new TelegramBridge(dir.resolve("download.part").toFile());
            enable(bridge);
            String url = bridge.url() + "/audio/10_20";
            byte[] block = new byte[8192];
            try (NativeAudio.Reader reader = bridge.audio.open(url, 0, 32768)) {
                int n = reader.read(block, 0, block.length);
                content(block, n, 0);
                RandomAccessFile first = reader.handle;
                n = reader.read(block, 0, block.length);
                content(block, n, 8192);
                check(first == reader.handle, "File handle reused across cached reads");
                int downloads = bridge.downloads;
                File moved = dir.resolve("finished.flac").toFile();
                Files.move(bridge.file.toPath(), moved.toPath());
                bridge.file = moved;
                bridge.audio.windows.update(7, NativeAudio.state(bridge.file()));
                n = reader.read(block, 0, block.length);
                content(block, n, 16384);
                check(first != reader.handle, "Moved completed file reopens the handle");
                check(bridge.downloads == downloads, "File move does not trigger a new download");
            }
            check(
                    bridge.audio.windows.active() == 0 && !bridge.audio.active(),
                    "Normal close releases leases and registrations");
            int downloads = bridge.downloads;
            try (NativeAudio.Reader reader = bridge.audio.open(url, 100, 100)) {
                content(block, reader.read(block, 0, 100), 100);
            }
            check(bridge.downloads == downloads, "Warm cache needs no downloadFile");
            byte[] directory = bridge.audio.readRange(7, 0, ZipDirectory.MAX_DIRECTORY, () -> {});
            check(directory.length == bridge.length, "Existing ZIP directory limit is accepted");
            content(directory, directory.length, 0);
            try {
                bridge.audio.readRange(7, 0, ZipDirectory.MAX_DIRECTORY + 1, () -> {});
                throw new AssertionError("Unbounded metadata range accepted");
            } catch (IOException expected) {
            }
            bridge.available.clear();
            bridge.limit = 0;
            bridge.truncateNext = true;
            try (NativeAudio.Reader reader = bridge.audio.open(url, 40000, 4096)) {
                content(block, reader.read(block, 0, 4096), 40000);
            }
            check(bridge.partReads > 0, "Eviction attempts the readFilePart recovery path");
            check(bridge.audio.windows.active() == 0, "Eviction recovery leaves no registration");
            bridge.available.clear();
            bridge.limit = 0;
            bridge.revokeOnPrefix = true;
            try {
                bridge.audio.readRange(7, 45000, 128, () -> {});
                throw new AssertionError("Returned a range after its session changed");
            } catch (java.util.concurrent.CancellationException expected) {
            }
            check(
                    bridge.audio.windows.active() == 0,
                    "Session change during readiness releases the range registration");
            url = bridge.url() + "/audio/10_20";
            NativeAudio.Reader denied = bridge.audio.open(url, 0, 100);
            bridge.selected.clear();
            try {
                denied.read(block, 0, 100);
                throw new AssertionError("Read after deselection");
            } catch (IOException expected) {
            }
            check(bridge.audio.windows.active() == 0, "Permission failure releases registration");
            denied.close();
            check(bridge.audio.slots.availablePermits() == 3, "All foreground slots restored");
            System.out.println(
                    "PASS Telegram v2 reader: reusable handles, path move, warm cache, eviction and"
                            + " permission cleanup");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path file :
                        files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))
                    Files.deleteIfExists(file);
            }
        }
    }
}
