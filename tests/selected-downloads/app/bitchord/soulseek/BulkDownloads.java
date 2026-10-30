package app.bitchord.soulseek;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class BulkDownloads {
    static final BulkDownloads INSTANCE = new BulkDownloads();
    static volatile int imports;
    static volatile String policy, destination;
    static volatile byte[] bytes;
    static final List<BulkJobStore.Job> entries = new CopyOnWriteArrayList<>();
    static volatile CountDownLatch importEntered, importRelease;
    static volatile boolean importInterrupted;

    List<BulkJobStore.Job> jobs() {
        return new ArrayList<>(entries);
    }

    static BulkDownloads get(Context context) {
        return INSTANCE;
    }

    String importVerified(
            File source,
            String name,
            DownloadDestinations.Snapshot snapshot,
            String token,
            String formats)
            throws IOException {
        if (importEntered != null) {
            importEntered.countDown();
            try {
                if (!importRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
            } catch (InterruptedException interrupted) {
                importInterrupted = true;
                throw new IOException(interrupted);
            }
        }
        bytes = Files.readAllBytes(source.toPath());
        imports++;
        policy = formats;
        destination = snapshot.mode;
        Files.delete(source.toPath());
        entries.add(new BulkJobStore.Job(token));
        return token;
    }
}
