package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Byte-range reader over TDLib file downloads. */
final class NativeAudio {
    final TelegramBridge bridge;
    final Object cacheGuard = new Object();
    final Semaphore slots = new Semaphore(3);

    /** Background reads (checks, naming) never take a playback slot. */
    final Semaphore backgroundSlots = new Semaphore(1);

    final Map<Integer, Lease> leases = new HashMap<>();
    int active;

    /** When the cache was last trimmed as a read started; a trim at every read was wasteful. */
    long lastTrimNanos = Long.MIN_VALUE / 2;

    /** How long between the trims that reads start, in seconds. */
    static final int TRIM_EVERY_SECONDS = 300;

    static final class Lease {
        /** Every reader of the file; {@code background} of them are background reads. */
        int readers, background;
    }

    /**
     * Background work stops because playback needs the network or the file. Unchecked so it passes
     * through readers and extractors; it is no failure of the file.
     */
    static final class Deferred extends CancellationException {
        Deferred(String message) {
            super(message);
        }
    }

    /** True while playback itself reads the file: background reads leave its download alone. */
    private boolean played(Lease lease) {
        synchronized (cacheGuard) {
            return lease.readers > lease.background;
        }
    }

    NativeAudio(TelegramBridge bridge) {
        this.bridge = bridge;
    }

    boolean active() {
        synchronized (cacheGuard) {
            return active > 0;
        }
    }

    boolean applyCacheLimit() throws Exception {
        synchronized (cacheGuard) {
            if (active > 0) return false;
            bridge.optimize(bridge.cacheLimitBytes());
            lastTrimNanos = System.nanoTime();
            return true;
        }
    }

    void clearCache() throws Exception {
        synchronized (cacheGuard) {
            if (active > 0) throw new IOException("Stop playback before clearing the cache");
            bridge.optimize(0);
        }
    }

    Reader open(String url, long position, long length) throws IOException {
        return open(url, position, length, null);
    }

    /**
     * As above. With a {@code guard}, the read is background work: it uses its own slot, throws
     * {@link Deferred} from the guard when playback needs the network, and gives way when playback
     * opens the same file.
     */
    Reader open(String url, long position, long length, Runnable guard) throws IOException {
        if (position < 0 || length < -1) throw new IOException("Invalid audio range");
        Semaphore slot = guard == null ? slots : backgroundSlots;
        if (!slot.tryAcquire()) throw new IOException("Too many active Telegram reads");
        boolean counted = false;
        Lease lease = null;
        int fileId = 0;
        try {
            long generation = bridge.generation;
            String tag = bridge.sessionTag;
            bridge.requireReady();
            String id = InternalAddon.audioId(bridge, url);
            long permissionRevision = bridge.mediaRevision;
            JSONObject row = bridge.resolveTrack(id);
            if (row == null) throw new IOException("Telegram track is unavailable");
            fileId = row.getInt("file");
            long chat = row.getLong("chat");
            TdApi.File file = bridge.call(new TdApi.GetFile(fileId));
            // A song inside a zip is read from its place in the zip, in its own positions; a track
            // of a cue sheet is read as a WAV file of just that track.
            JSONObject zip = row.optJSONObject("zip"), cue = row.optJSONObject("cue");
            long size = zip == null && cue == null ? file.size : row.getLong("size");
            if (zip != null && zip.getLong("data") + zip.getLong("compressed") > file.size)
                throw new IOException("Damaged zip");
            if (size < 0 || position > size)
                throw new IOException("Audio position is outside the file");
            long available = size - position,
                    end = position + (length < 0 ? available : Math.min(length, available));
            synchronized (cacheGuard) {
                if (active == 0
                        && System.nanoTime() - lastTrimNanos
                                > TRIM_EVERY_SECONDS * 1_000_000_000L) {
                    bridge.optimize(bridge.cacheLimitBytes());
                    lastTrimNanos = System.nanoTime();
                }
                active++;
                counted = true;
                lease = leases.get(fileId);
                if (lease == null) {
                    lease = new Lease();
                    leases.put(fileId, lease);
                }
                lease.readers++;
                if (guard != null) lease.background++;
            }
            Reader reader =
                    new Reader(
                            this,
                            fileId,
                            chat,
                            row.getLong("message"),
                            generation,
                            tag,
                            position,
                            end,
                            size,
                            row.optString("mime"),
                            lease);
            reader.slot = slot;
            if (guard != null) {
                reader.background = true;
                reader.cancellation = guard;
            }
            if (cue != null)
                reader.entry =
                        new CueAudio(
                                zip == null
                                        ? reader::readRange
                                        : ZipAlbums.entryRange(
                                                reader::readRange,
                                                zip.getLong("data"),
                                                zip.getInt("method"),
                                                zip.getLong("compressed"),
                                                cue.getLong("total")),
                                cue);
            else if (zip != null)
                reader.entry =
                        new ZipDirectory.EntryReader(
                                reader::readRange,
                                zip.getLong("data"),
                                zip.getInt("method"),
                                zip.getLong("compressed"),
                                size);
            reader.fileSize = file.size;
            reader.revision = permissionRevision;
            reader.check();
            return reader;
        } catch (Exception e) {
            if (counted) release(fileId, lease, slot, guard != null, true);
            else slot.release();
            throw io(e);
        }
    }

    void release(int id, Lease lease, Semaphore slot, boolean background, boolean cancel) {
        synchronized (cacheGuard) {
            try {
                if (background) lease.background--;
                if (--lease.readers == 0) {
                    leases.remove(id);
                    if (cancel) bridge.cancelDownload(id);
                }
            } finally {
                active--;
                slot.release();
            }
        }
    }

    /**
     * Bytes of a Telegram file, downloading only that part: how zips are looked into without
     * downloading them. Background work: the file counts as read for as long as this runs, so
     * playback closing it doesn't cancel this download, and when playback reads the same file this
     * stops with {@link Deferred} instead of replacing the one download window TDLib keeps per
     * file. {@code guard} throws {@link Deferred} when playback needs the network.
     */
    byte[] readRange(int file, long offset, int length, Runnable guard) throws Exception {
        Lease lease;
        synchronized (cacheGuard) {
            lease = leases.get(file);
            if (lease == null) leases.put(file, lease = new Lease());
            lease.readers++;
            lease.background++;
        }
        boolean aborted = true;
        try {
            byte[] out = readRangeLeased(file, offset, length, guard, lease);
            aborted = false;
            return out;
        } finally {
            synchronized (cacheGuard) {
                lease.background--;
                if (--lease.readers == 0) {
                    leases.remove(file);
                    if (aborted) bridge.cancelDownload(file);
                }
            }
        }
    }

    private byte[] readRangeLeased(int file, long offset, int length, Runnable guard, Lease lease)
            throws Exception {
        TdApi.File info = bridge.call(new TdApi.GetFile(file), 15000);
        if (offset < 0 || offset >= info.size) return new byte[0];
        int wanted = (int) Math.min(length, info.size - offset);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (int attempt = 1; ; attempt++) {
            if (guard != null) guard.run();
            long have =
                    bridge.call(new TdApi.GetFileDownloadedPrefixSize(file, offset), 15000).size;
            if (have < wanted) {
                if (played(lease)) throw new Deferred("Telegram file is playing");
                bridge.call(new TdApi.DownloadFile(file, 1, offset, wanted, false), 15000);
                while ((have =
                                bridge.call(
                                                new TdApi.GetFileDownloadedPrefixSize(file, offset),
                                                15000)
                                        .size)
                        < wanted) {
                    if (guard != null) guard.run();
                    if (played(lease)) throw new Deferred("Telegram file is playing");
                    if (System.nanoTime() > deadline)
                        throw new IOException("Telegram download timed out");
                    Thread.sleep(100);
                }
            }
            String path = bridge.call(new TdApi.GetFile(file), 15000).local.path;
            byte[] out = new byte[wanted];
            // TDLib may move the finished file or delete it to free space between asking and
            // opening, or while it is read; the part is then asked for again.
            try (RandomAccessFile input = new RandomAccessFile(path, "r")) {
                input.seek(offset);
                input.readFully(out);
                return out;
            } catch (FileNotFoundException | EOFException gone) {
                if (attempt >= MOVED_ATTEMPTS) throw gone;
                Thread.sleep(50L * attempt);
            }
        }
    }

    static IOException io(Exception e) {
        if (e instanceof CancellationException) throw (CancellationException) e;
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new InterruptedIOException("Telegram read interrupted");
        }
        return e instanceof IOException
                ? (IOException) e
                : new IOException("Telegram audio unavailable", e);
    }

    /** How many times a file that moved or was deleted while it was read is looked for. */
    static final int MOVED_ATTEMPTS = 5;

    static final class Reader implements Closeable {
        final NativeAudio owner;
        final int id;
        final long chat, message, generation, total;
        final String tag, mime;
        final Lease lease;
        Semaphore slot;
        boolean background;
        long revision, checkedAt;
        long position, end;
        volatile boolean cancelled;
        boolean released;
        Runnable cancellation = () -> {};
        // Set for a song inside a zip: positions are the song's, read through the zip.
        ZipDirectory.Source entry;
        long fileSize;

        Reader(
                NativeAudio owner,
                int id,
                long chat,
                long message,
                long generation,
                String tag,
                long position,
                long end,
                long total,
                String mime,
                Lease lease) {
            this.owner = owner;
            this.id = id;
            this.chat = chat;
            this.message = message;
            this.generation = generation;
            this.tag = tag;
            this.position = position;
            this.end = end;
            this.total = total;
            this.mime =
                    mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")
                            ? mime
                            : "application/octet-stream";
            this.lease = lease;
            this.slot = owner.slots;
            revision = owner.bridge.mediaRevision;
            checkedAt = System.nanoTime();
        }

        void check() throws IOException {
            cancellation.run();
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Telegram read cancelled");
            if (background && owner.played(lease)) throw new Deferred("Telegram file is playing");
            owner.bridge.requireReady();
            if (generation != owner.bridge.generation || !tag.equals(owner.bridge.sessionTag))
                throw new IOException("Telegram session changed");
            if (!owner.bridge.selected.contains(chat))
                throw new IOException("Music chat was deselected");
            if (revision != owner.bridge.mediaRevision
                    || System.nanoTime() - checkedAt > TimeUnit.SECONDS.toNanos(15)) {
                long checking = owner.bridge.mediaRevision;
                try {
                    owner.bridge.verifyMedia(chat, message, id);
                } catch (Exception e) {
                    throw io(e);
                }
                revision = checking;
                checkedAt = System.nanoTime();
                owner.bridge.requireReady();
                if (generation != owner.bridge.generation
                        || !tag.equals(owner.bridge.sessionTag)
                        || !owner.bridge.selected.contains(chat))
                    throw new IOException("Telegram access changed");
            }
        }

        synchronized long remaining() {
            return end - position;
        }

        synchronized int read(byte[] buffer, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > buffer.length - length)
                throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            check();
            if (position == end) return -1;
            try {
                int wanted = (int) Math.min(length, end - position);
                int count =
                        entry == null
                                ? physical(position, end, buffer, offset, wanted)
                                : entry.read(position, buffer, offset, wanted);
                if (count < 0) return -1;
                position += count;
                return count;
            } catch (Exception e) {
                throw io(e);
            }
        }

        /** Exactly {@code length} bytes of the zip at {@code at}, or fewer at its end. */
        byte[] readRange(long at, int length) throws Exception {
            byte[] out = new byte[length];
            int n = 0;
            while (n < length) {
                int got = physical(at + n, fileSize, out, n, length - n);
                if (got <= 0) break;
                n += got;
            }
            return n == length ? out : java.util.Arrays.copyOf(out, n);
        }

        /**
         * Reads up to 64 KB of the Telegram file at {@code at}, no further than {@code limit},
         * downloading that part first if it isn't here yet.
         */
        private int physical(long at, long limit, byte[] buffer, int offset, int length)
                throws Exception {
            if (at >= limit) return -1;
            synchronized (lease) {
                // TDLib moves a file out of its temp folder once the download completes, and
                // deletes cached files to free space, so the path it gave may be gone by the time
                // it's opened. Ask again, downloading the part again if it was deleted.
                for (int attempt = 1; ; attempt++) {
                    check();
                    // TDLib has one requested download window per file. Serialize each
                    // bounded read, then let another reader seek without cancelling it.
                    long prefix =
                            owner.bridge.call(new TdApi.GetFileDownloadedPrefixSize(id, at)).size;
                    if (prefix <= 0) {
                        long wanted = Math.min(limit - at, 4L * 1024 * 1024);
                        owner.bridge.call(
                                new TdApi.DownloadFile(id, background ? 1 : 32, at, wanted, false));
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
                        do {
                            check();
                            long millis =
                                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                            if (millis <= 0) throw new IOException("Telegram download timed out");
                            prefix =
                                    owner.bridge.call(
                                                    new TdApi.GetFileDownloadedPrefixSize(id, at),
                                                    Math.min(millis, 15000))
                                            .size;
                            if (prefix > 0) break;
                            Thread.sleep(100);
                        } while (true);
                    }
                    check();
                    TdApi.File file = owner.bridge.call(new TdApi.GetFile(id));
                    int count =
                            (int)
                                    Math.min(
                                            Math.min(prefix, Math.min(length, 64 * 1024)),
                                            limit - at);
                    try (RandomAccessFile input = new RandomAccessFile(file.local.path, "r")) {
                        input.seek(at);
                        input.readFully(buffer, offset, count);
                    } catch (java.io.FileNotFoundException | EOFException gone) {
                        if (attempt >= MOVED_ATTEMPTS) throw gone;
                        if (attempt == 1)
                            HostLog.d(
                                    "Unofficial Telegram file "
                                            + id
                                            + " moved or was deleted while it was read; fetching"
                                            + " it again");
                        // Asking for the part makes TDLib check the file is still there and
                        // download it again if it isn't; otherwise it keeps naming the old path.
                        owner.bridge.call(
                                new TdApi.DownloadFile(
                                        id,
                                        background ? 1 : 32,
                                        at,
                                        Math.min(limit - at, 4L * 1024 * 1024),
                                        false));
                        Thread.sleep(50L * attempt);
                        continue;
                    }
                    check();
                    return count;
                }
            }
        }

        public void close() {
            cancelled = true;
            if (entry != null) entry.close();
            synchronized (this) {
                if (!released) {
                    released = true;
                    owner.release(id, lease, slot, background, true);
                }
            }
        }
    }
}
