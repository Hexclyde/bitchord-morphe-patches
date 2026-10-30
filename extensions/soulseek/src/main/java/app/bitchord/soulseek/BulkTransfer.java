package app.bitchord.soulseek;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.List;

/** A single writer consumes verified, identically addressed blocks across ordered routes. */
final class BulkTransfer {
    interface Cancellation {
        void check() throws IOException;
    }

    interface Route extends java.io.Closeable {
        String name();

        byte[] read(int chunk) throws IOException;
    }

    interface Progress {
        void changed();
    }

    private final BulkJobStore store;
    private final Progress progress;

    BulkTransfer(BulkJobStore store, Progress progress) {
        this.store = store;
        this.progress = progress;
    }

    File download(BulkJobStore.Job job, List<Route> routes, Cancellation cancellation)
            throws IOException {
        cancellation.check();
        BulkManifest manifest = cachedManifest(job);
        IOException failure = null;
        int firstRoute = 0;
        if (manifest == null) {
            for (; firstRoute < routes.size(); firstRoute++) {
                Route route = routes.get(firstRoute);
                try {
                    cancellation.check();
                    job.route = route.name();
                    progress.changed();
                    byte[] bytes = route.read(-1);
                    manifest = BulkManifest.parse(bytes, job.offer.manifestSha256);
                    validate(job, manifest);
                    cancellation.check();
                    BulkJobStore.atomic(store.manifest(job), bytes);
                    break;
                } catch (IOException | LinkageError error) {
                    failure = new IOException("Download route is unavailable.", error);
                    cancellation.check();
                    route.close();
                }
            }
        }
        if (manifest == null)
            throw new IOException("No route could retrieve the signed file manifest.", failure);
        validate(job, manifest);
        File finished = store.complete(job);
        if (finished.isFile()) {
            verifyFile(finished, manifest, cancellation);
            job.verifiedChunks = manifest.blocks.size();
            store.save(job);
            return finished;
        }
        File part = store.part(job);
        try (RandomAccessFile writer = new RandomAccessFile(part, "rw")) {
            // A journal is only a hint. Rehash every claimed block before accepting it after a
            // restart.
            int prefix = verifyPrefix(writer, manifest, job.verifiedChunks, cancellation);
            job.verifiedChunks = prefix;
            writer.setLength(job.verifiedBytes());
            writer.getFD().sync();
            store.save(job);
            boolean complete = prefix == manifest.blocks.size();
            for (int i = firstRoute; i < routes.size() && !complete; i++) {
                Route route = routes.get(i);
                try {
                    job.route = route.name();
                    progress.changed();
                    while (job.verifiedChunks < manifest.blocks.size()) {
                        cancellation.check();
                        int chunk = job.verifiedChunks;
                        byte[] bytes = route.read(chunk);
                        manifest.blocks.get(chunk).verify(bytes);
                        cancellation.check();
                        writer.seek((long) chunk * BulkManifest.BLOCK_SIZE);
                        writer.write(bytes);
                        writer.getFD().sync();
                        cancellation.check();
                        job.verifiedChunks = chunk + 1;
                        store.save(job);
                        progress.changed();
                    }
                    complete = true;
                } catch (IOException | LinkageError error) {
                    failure = new IOException("Download route failed.", error);
                    cancellation.check();
                } finally {
                    route.close();
                }
            }
            if (!complete)
                throw new IOException(
                        "All available download routes failed. Resume to retry.", failure);
        }
        // No writer remains open beyond this barrier, including during hash verification and
        // publication.
        cancellation.check();
        verifyFile(part, manifest, cancellation);
        cancellation.check();
        java.nio.file.Files.move(
                part.toPath(), finished.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        store.save(job);
        progress.changed();
        return finished;
    }

    private BulkManifest cachedManifest(BulkJobStore.Job job) throws IOException {
        File cached = store.manifest(job);
        if (!cached.exists()) return null;
        try {
            return BulkManifest.parse(
                    BulkJobStore.read(cached, BulkManifest.MAX_MANIFEST), job.offer.manifestSha256);
        } catch (IOException invalid) {
            if (!cached.delete())
                throw new IOException("Cannot replace damaged file manifest.", invalid);
            return null;
        }
    }

    private static void validate(BulkJobStore.Job job, BulkManifest manifest) throws IOException {
        if (manifest.size != job.offer.size
                || !manifest.fileSha256.equals(job.offer.sha256)
                || !manifest.cid.equals(job.offer.manifestCid))
            throw new IOException("File manifest does not match the signed file identity.");
    }

    private static int verifyPrefix(
            RandomAccessFile file, BulkManifest manifest, int claimed, Cancellation cancellation)
            throws IOException {
        int maximum = Math.min(claimed, manifest.blocks.size());
        for (int i = 0; i < maximum; i++) {
            cancellation.check();
            BulkManifest.Block block = manifest.blocks.get(i);
            if (file.length() < (long) i * BulkManifest.BLOCK_SIZE + block.size) return i;
            byte[] bytes = new byte[block.size];
            file.seek((long) i * BulkManifest.BLOCK_SIZE);
            file.readFully(bytes);
            if (!BulkManifest.hash(bytes).equals(block.sha256)) return i;
        }
        return maximum;
    }

    static void verifyFile(File file, BulkManifest manifest, Cancellation cancellation)
            throws IOException {
        if (file.length() != manifest.size)
            throw new IOException("Downloaded file has the wrong size.");
        MessageDigest digest = BulkManifest.digest();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancellation.check();
                digest.update(buffer, 0, count);
            }
        }
        if (!BulkManifest.hex(digest.digest()).equals(manifest.fileSha256))
            throw new IOException("Downloaded file failed final verification.");
    }
}
