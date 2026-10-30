package app.bitchord.soulseek;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class BulkDownloads {
    static final BulkDownloads INSTANCE = new BulkDownloads();
    static volatile int imports;
    static volatile String policy, destination;
    static volatile byte[] bytes;

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
        bytes = Files.readAllBytes(source.toPath());
        imports++;
        policy = formats;
        destination = snapshot.mode;
        Files.delete(source.toPath());
        return token;
    }
}
