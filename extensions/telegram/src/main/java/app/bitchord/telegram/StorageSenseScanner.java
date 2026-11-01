package app.bitchord.telegram;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Bounded walks that stay inside their root and never follow a symbolic link. */
final class StorageSenseScanner {
    private StorageSenseScanner() {}

    static final int MAX_DEPTH = 8, MAX_ENTRIES = 20_000;

    static final class Entry {
        final File file;
        final long bytes, modifiedMs;

        Entry(File file, long bytes, long modifiedMs) {
            this.file = file;
            this.bytes = bytes;
            this.modifiedMs = modifiedMs;
        }
    }

    static final class Tree {
        final List<Entry> files = new ArrayList<>();
        long bytes;
        int count;
        boolean truncated, cancelled;
    }

    /** Sizes every regular file under the root. Links are skipped, not followed. */
    static Tree walk(File root, int maxDepth, int maxEntries, boolean keep, StorageSense.Cancel c)
            throws IOException {
        Tree tree = new Tree();
        Path start = root.toPath();
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) return tree;
        Files.walkFileTree(
                start,
                java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                maxDepth,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                        return stop(c) ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                        if (stop(c)) return FileVisitResult.TERMINATE;
                        if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                        if (tree.count >= maxEntries) {
                            tree.truncated = true;
                            return FileVisitResult.TERMINATE;
                        }
                        tree.count++;
                        tree.bytes += a.size();
                        if (keep)
                            tree.files.add(
                                    new Entry(
                                            file.toFile(),
                                            a.size(),
                                            a.lastModifiedTime().toMillis()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        tree.truncated = true;
                        return FileVisitResult.CONTINUE;
                    }

                    private boolean stop(StorageSense.Cancel cancel) {
                        if (!cancel.cancelled()) return false;
                        tree.cancelled = true;
                        return true;
                    }
                });
        return tree;
    }

    /**
     * The file itself when it really lies under one of the roots: not a link, not the root, and not
     * reached through a link pointing elsewhere. Null means it can't be touched.
     */
    static File prove(File candidate, Collection<File> roots) {
        try {
            Path path = candidate.toPath();
            if (Files.isSymbolicLink(path)) return null;
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            Path real = path.toRealPath();
            for (File root : roots) {
                if (root == null || !root.exists()) continue;
                Path base = root.toPath().toRealPath();
                if (!real.equals(base) && real.startsWith(base)) return real.toFile();
            }
        } catch (IOException | RuntimeException unproven) {
            return null;
        }
        return null;
    }

    /** Deletes a proven folder. A link inside is removed as a link; its target is never touched. */
    static void deleteTree(File directory, Collection<File> roots) throws IOException {
        File proven = prove(directory, roots);
        if (proven == null) throw new IOException(Strings.t("This location can't be deleted"));
        Files.walkFileTree(
                proven.toPath(),
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException failed)
                            throws IOException {
                        if (failed != null) throw failed;
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** Audio containers by their first bytes; a name proves nothing. */
    static boolean audioHead(byte[] head, int length) {
        if (length >= 4) {
            if (ascii(head, 0, "fLaC") || ascii(head, 0, "OggS") || ascii(head, 0, "RIFF"))
                return true;
            if ((head[0] & 255) == 0x1a
                    && (head[1] & 255) == 0x45
                    && (head[2] & 255) == 0xdf
                    && (head[3] & 255) == 0xa3) return true;
        }
        if (length >= 3 && ascii(head, 0, "ID3")) return true;
        if (length >= 8 && ascii(head, 4, "ftyp")) return true;
        return length >= 2 && (head[0] & 255) == 0xff && (head[1] & 0xe0) == 0xe0;
    }

    private static boolean ascii(byte[] data, int at, String text) {
        for (int i = 0; i < text.length(); i++)
            if (data[at + i] != (byte) text.charAt(i)) return false;
        return true;
    }

    static boolean looksLikeAudio(File file) {
        byte[] head = new byte[12];
        try (InputStream in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            int read = 0, n;
            while (read < head.length && (n = in.read(head, read, head.length - read)) > 0)
                read += n;
            return audioHead(head, read);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** The stream's SHA-256, or null if cancelled before its end. */
    static String sha256(InputStream in, StorageSense.Cancel cancel) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException(Strings.t("Comparison is unavailable"), unavailable);
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (cancel.cancelled()) return null;
            digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append("0123456789abcdef".charAt((b & 255) >>> 4));
            hex.append("0123456789abcdef".charAt(b & 15));
        }
        return hex.toString();
    }
}
