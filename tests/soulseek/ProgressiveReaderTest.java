package app.bitchord.soulseek;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ProgressiveReaderTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    interface Task {
        void run() throws Exception;
    }

    static void denied(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    static final class Transfer implements ProgressiveReader.Source {
        volatile File file;
        volatile long downloaded;
        final long total;
        volatile boolean complete;
        volatile String status = "downloading";
        final AtomicInteger releases = new AtomicInteger();

        Transfer(File file, long total) {
            this.file = file;
            this.total = total;
        }

        public ProgressiveReader.Snapshot snapshot() {
            return new ProgressiveReader.Snapshot(file, downloaded, total, complete, status);
        }

        public void release() {
            releases.incrementAndGet();
        }
    }

    public static void main(String[] args) throws Exception {
        File folder = Files.createTempDirectory("soulseek-reader-test-").toFile();
        File part = new File(folder, "test.part");
        try (RandomAccessFile out = new RandomAccessFile(part, "rw")) {
            // Physical length alone must not expose undownloaded bytes from a sparse file.
            out.setLength(16);
            out.write(new byte[] {10, 11, 12, 13});
        }
        Transfer transfer = new Transfer(part, 16);
        transfer.downloaded = 4;
        ProgressiveReader reader = new ProgressiveReader(transfer, 16, 0, -1, 1000);
        byte[] bytes = new byte[32];
        check(reader.read(bytes, 0, 32) == 4, "Only the known prefix is read");
        check(bytes[0] == 10 && bytes[3] == 13, "Original bytes preserved");
        Thread writer =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(150);
                                try (RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                                    out.seek(4);
                                    for (int i = 4; i < 16; i++) out.write(i + 10);
                                }
                                transfer.downloaded = 16;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        writer.start();
        check(
                reader.read(bytes, 0, 32) == 12 && bytes[0] == 14 && bytes[11] == 25,
                "A waiting read resumes when the prefix grows");
        writer.join();
        Thread finalizer =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(150);
                                File finalFile = new File(folder, "test.flac");
                                Files.move(part.toPath(), finalFile.toPath());
                                transfer.file = finalFile;
                                transfer.complete = true;
                                transfer.status = "complete";
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        finalizer.start();
        long started = System.nanoTime();
        check(reader.read(bytes, 0, 32) == -1, "EOF follows native completion");
        check(
                System.nanoTime() - started >= 100_000_000L,
                "Full-length EOF waits for finalization");
        finalizer.join();
        reader.close();
        reader.close();
        check(transfer.releases.get() == 1, "Exactly one lease release");
        denied(() -> reader.read(bytes, 0, 1), "Closed reader must fail");

        transfer.complete = false;
        transfer.downloaded = 4;
        try (ProgressiveReader range = new ProgressiveReader(transfer, 16, 1, 2, 300)) {
            check(
                    range.read(bytes, 0, 32) == 2 && bytes[0] == 11,
                    "Requested byte range respected");
            check(
                    range.read(bytes, 0, 1) == -1,
                    "A complete requested range can end before transfer completion");
        }
        try (ProgressiveReader missing = new ProgressiveReader(transfer, 16, 8, 2, 150)) {
            denied(
                    () -> missing.read(bytes, 0, 2),
                    "Sparse hole must time out, not return zero bytes");
        }
        transfer.downloaded = 16;
        transfer.complete = true;
        try (RandomAccessFile out = new RandomAccessFile(transfer.file, "rw")) {
            out.setLength(3);
        }
        try (ProgressiveReader truncated = new ProgressiveReader(transfer, 16, 4, -1, 150)) {
            denied(() -> truncated.read(bytes, 0, 2), "Truncated completed file rejected");
        }
        transfer.complete = false;
        transfer.downloaded = 0;
        ProgressiveReader waiting = new ProgressiveReader(transfer, 16, 0, -1, 5000);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread blocked =
                new Thread(
                        () -> {
                            try {
                                waiting.read(bytes, 0, 1);
                            } catch (Throwable e) {
                                failure.set(e);
                            }
                        });
        blocked.start();
        Thread.sleep(100);
        waiting.close();
        blocked.join(1000);
        check(
                !blocked.isAlive() && failure.get() instanceof IOException,
                "Close wakes blocked reads promptly");
        transfer.status = "failed";
        try (ProgressiveReader failed = new ProgressiveReader(transfer, 16, 0, -1, 100)) {
            denied(() -> failed.read(bytes, 0, 1), "Failed peer transfer throws");
        }
        denied(
                () -> new ProgressiveReader(transfer, 16, 17, -1, 100),
                "Out-of-range start rejected");
        denied(
                () -> new ProgressiveReader(transfer, 16, 0, -2, 100),
                "Invalid range length rejected");
        for (File file : folder.listFiles()) file.delete();
        folder.delete();
        System.out.println("PASS: " + checks + " Soulseek progressive reader checks.");
    }
}
