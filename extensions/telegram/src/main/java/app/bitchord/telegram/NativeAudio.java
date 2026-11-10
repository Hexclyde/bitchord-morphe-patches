package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Byte-range reader over TDLib file downloads. */
final class NativeAudio {
    final TelegramBridge bridge;
    final TelegramStreamTrace trace =
            new TelegramStreamTrace(
                    Diagnostics::record, Diagnostics::active, () -> System.nanoTime() / 1_000_000L);
    final java.util.concurrent.atomic.AtomicLong seekGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    final Object cacheGuard = new Object();
    final Semaphore slots = new Semaphore(3);

    /** Background reads (checks, naming) never take a playback slot. */
    final Semaphore backgroundSlots = new Semaphore(1);

    volatile TelegramStreamCoordinator windows;

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

    static void checkFile(JSONObject row, TdApi.File file) throws IOException {
        if (file == null || file.id != row.optInt("file"))
            throw new IOException("Telegram file mapping changed");
        String unique = row.optString("unique");
        if (!unique.isEmpty() && (file.remote == null || !unique.equals(file.remote.uniqueId)))
            throw new IOException("Telegram file identity changed");
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
        return open(
                url,
                position,
                length,
                guard,
                guard == null
                        ? TelegramStreamContext.consumer()
                        : TelegramStreamCoordinator.Consumer.BACKGROUND);
    }

    Reader open(
            String url,
            long position,
            long length,
            Runnable guard,
            TelegramStreamCoordinator.Consumer consumer)
            throws IOException {
        long traceStarted = System.nanoTime() / 1_000_000L;
        if (position < 0 || length < -1) throw new IOException("Invalid audio range");
        Semaphore slot = guard == null ? slots : backgroundSlots;
        if (!slot.tryAcquire()) throw new IOException("Too many active Telegram reads");
        boolean counted = false;
        Lease lease = null;
        Reader opened = null;
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
            checkFile(row, file);
            SourcePreference.noteAdvertised(url, row.optLong("duration", 0));
            HostLog.d(
                    "Telegram file mapping candidate="
                            + java.util.UUID.nameUUIDFromBytes(
                                    url.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            + " message="
                            + java.util.UUID.nameUUIDFromBytes(
                                    id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            + " file="
                            + row.optInt("file")
                            + " unique="
                            + java.util.UUID.nameUUIDFromBytes(
                                    row.optString("unique")
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            + " advertisedMs="
                            + row.optLong("duration") * 1000L
                            + " sizeBytes="
                            + row.optLong("size"));
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
            reader.consumer = consumer;
            opened = reader;
            reader.slot = slot;
            try {
                if (windows != null) {
                    long bitrate =
                            row.optLong("duration") > 0
                                    ? file.size * 8 / row.optLong("duration")
                                    : 0;
                    reader.window = windows.register(fileId, consumer, bitrate);
                    windows.seed(fileId, state(file));
                }
                reader.trace =
                        trace.begin(
                                fileId,
                                position,
                                length,
                                consumer.label(),
                                reader.window == null
                                        ? (guard == null ? 32 : 1)
                                        : consumer.priority,
                                reader.window == null
                                        ? seekGeneration.get()
                                        : reader.window.generation,
                                reader.window != null,
                                traceStarted);
                if (reader.trace != null)
                    reader.trace.format = TelegramContainerRanges.format(reader.mime);
                if (reader.trace != null) trace.bind(fileId, url);
                if (reader.window != null) reader.window.trace = reader.trace;
                if (file.local != null) trace.update(fileId, file.local.downloadedSize);
            } catch (Exception failed) {
                if (reader.window != null) reader.window.close();
                throw failed;
            }
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
            try {
                reader.check();
            } catch (Exception failed) {
                if (reader.window != null) reader.window.close();
                throw failed;
            }
            return reader;
        } catch (Exception e) {
            if (opened != null && opened.window != null) opened.window.close();
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
        if (windows != null) return coordinatedRange(file, offset, length, guard);
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
        TelegramStreamTrace.Request request =
                trace.begin(
                        file,
                        offset,
                        length,
                        "background",
                        1,
                        seekGeneration.get(),
                        false,
                        System.nanoTime() / 1_000_000L);
        try {
            byte[] bytes = readRangeMeasured(file, offset, length, guard, lease, request);
            if (request != null) {
                request.served(offset, bytes.length);
                request.end("complete");
            }
            return bytes;
        } catch (Exception failure) {
            if (request != null)
                request.end(failure instanceof CancellationException ? "cancelled" : "failed");
            throw failure;
        }
    }

    private byte[] readRangeMeasured(
            int file,
            long offset,
            int length,
            Runnable guard,
            Lease lease,
            TelegramStreamTrace.Request request)
            throws Exception {
        TdApi.File info = bridge.call(new TdApi.GetFile(file), 15000);
        if (info.local != null) trace.update(file, info.local.downloadedSize);
        if (offset < 0 || offset >= info.size) return new byte[0];
        int wanted = (int) Math.min(length, info.size - offset);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (int attempt = 1; ; attempt++) {
            if (guard != null) guard.run();
            long have =
                    bridge.call(new TdApi.GetFileDownloadedPrefixSize(file, offset), 15000).size;
            if (request != null) {
                request.prefixCalls++;
                request.initial(have);
                request.readable(offset, have, wanted);
            }
            if (have < wanted) {
                if (played(lease)) throw new Deferred("Telegram file is playing");
                if (request != null) request.window(offset, wanted, 1);
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
                    if (request != null) {
                        request.prefixCalls++;
                        request.wake(false, false);
                    }
                }
                if (request != null) {
                    request.prefixCalls++;
                    request.readable(offset, have, wanted);
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

    static TelegramStreamCoordinator.State state(TdApi.File file) {
        TdApi.LocalFile local = file.local;
        return new TelegramStreamCoordinator.State(
                local == null ? "" : local.path,
                file.size,
                local == null ? 0 : local.downloadOffset,
                local == null ? 0 : local.downloadedPrefixSize,
                local == null ? 0 : local.downloadedSize,
                local != null && local.isDownloadingCompleted,
                local != null && local.isDownloadingActive);
    }

    private byte[] coordinatedRange(int file, long offset, int length, Runnable guard)
            throws Exception {
        if (length < 0 || length > 8 * 1024 * 1024)
            throw new IOException("Telegram range exceeds memory limit");
        if (guard != null) guard.run();
        bridge.requireReady();
        long session = bridge.generation;
        TdApi.File initial = bridge.call(new TdApi.GetFile(file), 15000);
        if (offset < 0 || offset >= initial.size) return new byte[0];
        int wanted = (int) Math.min(length, initial.size - offset);
        try (TelegramStreamCoordinator.Registration registration =
                windows.register(file, TelegramStreamCoordinator.Consumer.BACKGROUND, 0)) {
            windows.seed(file, state(initial));
            registration.trace =
                    trace.begin(
                            file,
                            offset,
                            wanted,
                            "background",
                            1,
                            registration.generation,
                            true,
                            System.nanoTime() / 1_000_000L);
            registration.await(
                    offset,
                    wanted,
                    60000,
                    () -> {
                        if (guard != null) guard.run();
                        if (session != bridge.generation)
                            throw new CancellationException("Telegram session changed");
                    });
            TdApi.File current = bridge.call(new TdApi.GetFile(file), 15000);
            byte[] bytes = new byte[wanted];
            try (RandomAccessFile input = new RandomAccessFile(current.local.path, "r")) {
                input.seek(offset);
                input.readFully(bytes);
            } catch (FileNotFoundException | EOFException moved) {
                bytes = bridge.call(new TdApi.ReadFilePart(file, offset, wanted), 15000).data;
                if (bytes.length != wanted)
                    throw new EOFException("Incomplete Telegram cached range");
            }
            if (registration.trace != null) registration.trace.served(offset, wanted);
            registration.end = "complete";
            return bytes;
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
        TelegramStreamTrace.Request trace;
        TelegramStreamCoordinator.Registration window;
        TelegramStreamCoordinator.Consumer consumer;
        RandomAccessFile handle;
        String handlePath;
        boolean background;
        long revision, checkedAt;
        long position, end;
        volatile boolean cancelled;
        boolean released;
        Runnable cancellation = () -> {};
        // Set for a song inside a zip: positions are the song's, read through the zip.
        ZipDirectory.Source entry;
        long fileSize;
        String availablePath;
        long availableFrom, availableUntil, availableLength, availableModified;
        long lastDownloaded = -1;
        byte[] containerHead;
        int containerHeadSize;
        boolean containerHinted;
        String containerFormat;
        volatile TelegramStreamCoordinator.Registration metadata;

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
            this.containerFormat = TelegramContainerRanges.format(this.mime);
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
            try {
                check();
                if (position == end) return -1;
                int wanted = (int) Math.min(length, end - position);
                int count =
                        entry == null
                                ? physical(position, end, buffer, offset, wanted)
                                : entry.read(position, buffer, offset, wanted);
                if (count < 0) return -1;
                if (entry == null && position == 0 && (window != null || trace != null)) {
                    containerFormat =
                            TelegramContainerRanges.sniff(
                                    java.util.Arrays.copyOfRange(buffer, offset, offset + count),
                                    count,
                                    containerFormat);
                    if (trace != null) trace.format = containerFormat;
                }
                if (entry == null && window != null)
                    prefetchContainer(position, buffer, offset, count);
                if (trace != null) trace.served(position, count);
                position += count;
                return count;
            } catch (Exception e) {
                if (trace != null)
                    trace.end(e instanceof CancellationException ? "cancelled" : "failed");
                if (window != null) {
                    window.end = "failed";
                    window.close();
                    close();
                }
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
            if (window != null) return coordinatedPhysical(at, limit, buffer, offset, length);
            synchronized (lease) {
                // TDLib moves a file out of its temp folder once the download completes, and
                // deletes cached files to free space, so the path it gave may be gone by the time
                // it's opened. Ask again, downloading the part again if it was deleted.
                for (int attempt = 1; ; attempt++) {
                    check();
                    if (availablePath != null && at >= availableFrom && at < availableUntil) {
                        int count =
                                (int)
                                        Math.min(
                                                Math.min(availableUntil - at, limit - at),
                                                Math.min(length, 64 * 1024));
                        File cached = new File(availablePath);
                        try (RandomAccessFile input = new RandomAccessFile(cached, "r")) {
                            if (trace != null) trace.handleOpens++;
                            if (input.length() == availableLength
                                    && cached.lastModified() == availableModified) {
                                input.seek(at);
                                input.readFully(buffer, offset, count);
                                if (trace != null) trace.readable(at, count, length);
                                check();
                                return count;
                            }
                        } catch (java.io.FileNotFoundException | EOFException gone) {
                            // A moved or trimmed cache is revalidated below.
                        }
                        availablePath = null;
                    }
                    // TDLib has one requested download window per file. Serialize each
                    // bounded read, then let another reader seek without cancelling it.
                    long prefix =
                            owner.bridge.call(new TdApi.GetFileDownloadedPrefixSize(id, at)).size;
                    if (trace != null) {
                        trace.prefixCalls++;
                        trace.initial(prefix);
                        trace.readable(at, prefix, length);
                    }
                    if (prefix <= 0) {
                        long wanted = Math.min(limit - at, 4L * 1024 * 1024);
                        if (trace != null) trace.window(at, wanted, background ? 1 : 32);
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
                            if (trace != null) trace.prefixCalls++;
                            if (trace != null) trace.readable(at, prefix, length);
                            if (prefix > 0) break;
                            Thread.sleep(100);
                            if (trace != null) trace.wake(false, false);
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
                        if (trace != null) trace.handleOpens++;
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
                    // A confirmed contiguous range stays readable without two RPCs per chunk.
                    availablePath = file.local.path;
                    File cached = new File(availablePath);
                    availableLength = cached.length();
                    availableModified = cached.lastModified();
                    availableFrom = at;
                    availableUntil = at + Math.min(prefix, limit - at);
                    return count;
                }
            }
        }

        private int coordinatedPhysical(long at, long limit, byte[] buffer, int offset, int length)
                throws Exception {
            int wanted = (int) Math.min(Math.min(length, 64 * 1024), limit - at);
            for (int attempt = 1; ; attempt++) {
                check();
                TelegramStreamCoordinator.State known = owner.windows.state(id);
                if (known != null) {
                    if (lastDownloaded >= 0 && known.downloaded < lastDownloaded) {
                        closeHandle();
                        availablePath = null;
                    }
                    lastDownloaded = known.downloaded;
                }
                if (known != null && availablePath != null && !availablePath.equals(known.path)) {
                    closeHandle();
                    availablePath = null;
                }
                if (availablePath == null || at < availableFrom || at + wanted > availableUntil) {
                    long have = window.await(at, wanted, 40000, this::check);
                    TdApi.File current = owner.bridge.call(new TdApi.GetFile(id), 15000);
                    check();
                    owner.windows.seed(id, state(current));
                    availablePath = current.local.path;
                    availableFrom = at;
                    availableUntil = at + Math.min(have, limit - at);
                }
                try {
                    if (availablePath == null || availablePath.isEmpty())
                        throw new FileNotFoundException();
                    File current = new File(availablePath);
                    if (!current.isFile()) throw new FileNotFoundException();
                    if (handle == null || !availablePath.equals(handlePath)) {
                        closeHandle();
                        handle = new RandomAccessFile(current, "r");
                        handlePath = availablePath;
                        if (trace != null) trace.handleOpens++;
                    }
                    handle.seek(at);
                    handle.readFully(buffer, offset, wanted);
                    check();
                    if (at + wanted < end) window.consumed(at + wanted, availableUntil);
                    return wanted;
                } catch (FileNotFoundException | EOFException moved) {
                    closeHandle();
                    availablePath = null;
                    try {
                        if (trace != null) trace.partFallbacks++;
                        byte[] bytes =
                                owner.bridge.call(new TdApi.ReadFilePart(id, at, wanted), 15000)
                                        .data;
                        if (bytes.length != wanted) throw new EOFException();
                        System.arraycopy(bytes, 0, buffer, offset, wanted);
                        check();
                        return wanted;
                    } catch (Exception unavailable) {
                        if (attempt >= MOVED_ATTEMPTS) throw unavailable;
                        owner.windows.invalidate(id);
                        window.refetch(at, wanted);
                    }
                }
            }
        }

        private void closeHandle() {
            if (handle != null) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                }
                handle = null;
                handlePath = null;
            }
        }

        private void prefetchContainer(long at, byte[] bytes, int offset, int count)
                throws Exception {
            if (containerHinted || !consumer.interactive() || at > 65536) return;
            String format = containerFormat;
            if (!format.equals("mp4")) {
                containerHinted = true;
                return;
            }
            if (containerHead == null) containerHead = new byte[65536];
            if (at != containerHeadSize) return;
            int copy = Math.min(count, containerHead.length - containerHeadSize);
            System.arraycopy(bytes, offset, containerHead, containerHeadSize, copy);
            containerHeadSize += copy;
            long hint = TelegramContainerRanges.next(containerHead, containerHeadSize, 0, total);
            if (hint < 0) return;
            containerHinted = true;
            containerHead = null;
            // A declared mdat end gives an exact atom boundary; never scan or fetch a whole file.
            for (int round = 0; hint >= 0 && round < 3; round++) {
                check();
                int needed = (int) Math.min(65536, total - hint);
                try (TelegramStreamCoordinator.Registration registration =
                        owner.windows.register(id, consumer, 0)) {
                    metadata = registration;
                    registration.readAhead = false;
                    registration.trace =
                            owner.trace.begin(
                                    id,
                                    hint,
                                    needed,
                                    consumer.label(),
                                    consumer.priority,
                                    registration.generation,
                                    true,
                                    System.nanoTime() / 1_000_000L);
                    if (registration.trace != null) {
                        registration.trace.format = "mp4";
                        registration.trace.rangeKind = "container-prefetch";
                    }
                    registration.await(hint, needed, 10000, this::check);
                    TdApi.File file = owner.bridge.call(new TdApi.GetFile(id), 15000);
                    byte[] header = new byte[needed];
                    try (RandomAccessFile input = new RandomAccessFile(file.local.path, "r")) {
                        input.seek(hint);
                        input.readFully(header);
                    }
                    check();
                    if (registration.trace != null) registration.trace.served(hint, needed);
                    registration.end = "complete";
                    hint = TelegramContainerRanges.next(header, needed, hint, total);
                } catch (IOException unavailable) {
                    check(); // Optional hint failure must not hide a permission/session change.
                    break; // The extractor can request its normal ranges.
                } finally {
                    metadata = null;
                }
            }
            availablePath = null; // Restore the foreground window on its next read.
        }

        public void close() {
            cancelled = true;
            TelegramStreamCoordinator.Registration hint = metadata;
            if (hint != null) hint.close();
            if (window != null) {
                window.end = position == end ? "complete" : "cancelled";
                window.close();
            }
            if (trace != null) trace.end(position == end ? "complete" : "cancelled");
            if (entry != null) entry.close();
            synchronized (this) {
                if (!released) {
                    released = true;
                    closeHandle();
                    owner.release(id, lease, slot, background, window == null);
                }
            }
        }
    }
}
