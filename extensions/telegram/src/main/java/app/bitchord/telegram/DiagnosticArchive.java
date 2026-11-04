package app.bitchord.telegram;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded, local support attachments containing only the validated report snapshot. */
final class DiagnosticArchive {
    static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    static final int MAX_FILES = 4;

    private DiagnosticArchive() {}

    static synchronized void clear(Context context) {
        File directory = new File(context.getCacheDir(), "diagnostic-exports");
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files)
            if (file.getName().matches("bitchord-diagnostics-[a-f0-9]{32}\\.zip")) {
                try {
                    context.revokeUriPermission(
                            DiagnosticFiles.uri(context, file),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
                file.delete();
            }
    }

    static synchronized File create(Context context, String report) throws IOException {
        File directory = new File(context.getCacheDir(), "diagnostic-exports");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Diagnostic export directory is unavailable");
        File[] old = directory.listFiles();
        if (old != null) {
            java.util.Arrays.sort(
                    old, java.util.Comparator.comparingLong(File::lastModified).reversed());
            int kept = 0;
            for (File file : old)
                if (file.getName().matches("bitchord-diagnostics-[a-f0-9]{32}\\.zip")) {
                    if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
                            || ++kept >= MAX_FILES) file.delete();
                }
        }
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > DiagnosticStore.MAX_BYTES + 16384)
            throw new IOException("Diagnostic report exceeds the export limit");
        File file =
                new File(
                        directory,
                        "bitchord-diagnostics-"
                                + UUID.randomUUID().toString().replace("-", "")
                                + ".zip");
        boolean complete = false;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                zip.putNextEntry(new ZipEntry("bitchord-diagnostics.txt"));
                zip.write(bytes);
                zip.closeEntry();
            }
            complete = true;
        } finally {
            if (!complete) file.delete();
        }
        return file;
    }
}
