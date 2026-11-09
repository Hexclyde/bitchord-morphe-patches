package app.bitchord.telegram;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic checks of analysis memory accounting and priority-aware decoding. */
public final class SourceAudioPriorityTest {
    static final long MB = 1L << 20, MAX = 192 * MB, BIG = 512 * MB;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void reset() {
        PlaybackPriority.reset();
    }

    // A 192 MiB heap has no analysis headroom left once the player's reserve is kept (see below),
    // so the grant bookkeeping is checked on a larger one.
    /** Heap growth nobody reported is other memory: it must not release a pending grant. */
    static void unrelatedHeapGrowthDoesNotReleaseAGrant() {
        reset();
        long before = SourceAudio.analysisAvailable(BIG, 38 * MB);
        try (SourceAudio.JavaMemory grant = SourceAudio.reserveJava(BIG, 38 * MB, 16 * MB)) {
            check(grant != null, "granted");
            long promised = SourceAudio.analysisAvailable(BIG, 38 * MB);
            check(promised == before - 16 * MB, "a promise is charged in full: " + promised);
            // 16 MiB of playback or UI allocation lands; the grant is still entirely pending.
            long grown = SourceAudio.analysisAvailable(BIG, 54 * MB);
            check(grown == before - 32 * MB, "growth is charged on top of the promise: " + grown);
            check(grant.pending() == 16 * MB, "still pending");
        }
        check(SourceAudio.analysisAvailable(BIG, 38 * MB) == before, "released on close");
    }

    /** What the owner reports allocated moves from pending into the heap figure, once. */
    static void materializationIsCountedOnce() {
        reset();
        long before = SourceAudio.analysisAvailable(BIG, 38 * MB);
        try (SourceAudio.JavaMemory grant = SourceAudio.reserveJava(BIG, 38 * MB, 16 * MB)) {
            grant.materialized(4 * MB);
            check(grant.pending() == 12 * MB, "partly materialized");
            // 4 MiB really landed in the heap and 12 MiB is still promised.
            check(
                    SourceAudio.analysisAvailable(BIG, 42 * MB) == before - 16 * MB,
                    "no double count: " + SourceAudio.analysisAvailable(BIG, 42 * MB));
            grant.materialized(12 * MB);
            grant.materialized(12 * MB);
            grant.materialized(-5);
            check(grant.pending() == 0, "clamped to the grant, never negative");
            check(
                    SourceAudio.analysisAvailable(BIG, 54 * MB) == before - 16 * MB,
                    "fully landed, counted once");
        }
        check(SourceAudio.analysisAvailable(BIG, 38 * MB) == before, "released on close");
        // A closed grant ignores late reports and a second close is harmless.
        SourceAudio.JavaMemory late = SourceAudio.reserveJava(BIG, 38 * MB, 8 * MB);
        late.close();
        late.materialized(8 * MB);
        late.close();
        check(late.pending() == 0, "closed grants hold nothing");
        check(SourceAudio.analysisAvailable(BIG, 38 * MB) == before, "no residue");
    }

    /** Only the exact allocation the DSP owner reports is credited; the rest stays reserved. */
    static void dspCreditsOnlyWhatItReports() {
        reset();
        SourceAudio.DspMemory memory = SourceAudio.dspMemory(MAX, 38 * MB);
        // With no loading gate 5 MiB is available, so a 4 MiB claim fits.
        check(memory.claim(4 * MB, MAX, 38 * MB), "claimed");
        String claimed = SourceAudio.memoryDiagnostics(MAX, 38 * MB);
        check(claimed.contains("pendingMiB=4"), claimed);
        memory.materialized(1 * MB);
        check(SourceAudio.memoryDiagnostics(MAX, 39 * MB).contains("pendingMiB=3"), "one landed");
        memory.close();
        memory.materialized(1 * MB);
        memory.close();
        check(SourceAudio.memoryDiagnostics(MAX, 38 * MB).contains("pendingMiB=0"), "closed");
    }

    static final class Lease implements AutoCloseable {
        int closed;

        public void close() {
            closed++;
        }
    }

    /**
     * A pinned lease whose loading ceiling is a quarter of the heap and nothing is retained yet.
     */
    static SourceAudio.DspMemory pinned(long maximum, long used, Lease lease) {
        return SourceAudio.dspMemory(
                maximum, used, lease, () -> maximum / 4, () -> !PlaybackPriority.analysisAllowed());
    }

    /**
     * What a constrained and a roomy heap may give analysis, with and without the pinned ceiling.
     */
    static void headroomScalesWithTheHeapAndTheCeiling() {
        reset();
        SourceAudio.physicalAvailable = () -> 2048 * MB;
        try (SourceAudio.DspMemory small = pinned(MAX, 38 * MB, new Lease());
                SourceAudio.DspMemory large = pinned(512 * MB, 38 * MB, new Lease())) {
            // The stock player may still grow to 141 MiB (192) or half the heap (512).
            check(SourceAudio.analysisAvailable(MAX, 38 * MB) == 13 * MB, "192 unpinned");
            check(SourceAudio.analysisAvailable(512 * MB, 38 * MB) == 218 * MB, "512 unpinned");
            // Pinned to a quarter of the heap: 48 MiB and 128 MiB of growth stay reserved.
            check(small.available(MAX, 38 * MB) == 98 * MB, "192 pinned " + small.available);
            check(large.available(512 * MB, 38 * MB) == 338 * MB, "512 pinned " + large.available);
            check(
                    SourceAudio.analysisAvailable(MAX, 38 * MB) <= small.available
                            && SourceAudio.analysisAvailable(512 * MB, 38 * MB) <= large.available,
                    "a pin never lowers the estimate");
        } finally {
            SourceAudio.physicalAvailable = () -> -1;
        }
    }

    /** Concurrent claims share one heap: each sees the others' promises, materialized or not. */
    static void concurrentClaimsShareTheHeadroom() {
        reset();
        SourceAudio.physicalAvailable = () -> 2048 * MB;
        try {
            Lease first = new Lease(), second = new Lease();
            try (SourceAudio.DspMemory a = pinned(512 * MB, 38 * MB, first);
                    SourceAudio.DspMemory b = pinned(512 * MB, 38 * MB, second)) {
                check(a.claim(200 * MB, 512 * MB, 38 * MB), "first claim");
                check(
                        b.available(512 * MB, 38 * MB) == 138 * MB,
                        "" + b.available(512 * MB, 38 * MB));
                check(!b.claim(138 * MB + 1, 512 * MB, 38 * MB), "one byte over");
                // 80 MiB lands in the heap: moved from pending into used, counted once.
                a.materialized(80 * MB);
                check(
                        b.available(512 * MB, 118 * MB) == 138 * MB,
                        "counted once: " + b.available(512 * MB, 118 * MB));
                check(b.claim(138 * MB, 512 * MB, 118 * MB), "exactly what is left");
            }
            check(first.closed == 1 && second.closed == 1, "leases released");
            check(
                    SourceAudio.memoryDiagnostics(512 * MB, 38 * MB).contains("pendingMiB=0"),
                    "none");
            // The constrained heap has room for one claim only.
            try (SourceAudio.DspMemory a = pinned(MAX, 38 * MB, new Lease());
                    SourceAudio.DspMemory b = pinned(MAX, 38 * MB, new Lease())) {
                check(a.claim(98 * MB, MAX, 38 * MB), "all of it");
                check(b.available(MAX, 38 * MB) == 0, "" + b.available(MAX, 38 * MB));
                check(!b.claim(1, MAX, 38 * MB), "nothing left for a second");
            }
        } finally {
            SourceAudio.physicalAvailable = () -> -1;
        }
    }

    /** Taking the exact estimate works; a byte more, or a changed heap limit, does not. */
    static void claimsStopAtTheBoundary() {
        reset();
        SourceAudio.physicalAvailable = () -> 2048 * MB;
        try {
            try (SourceAudio.DspMemory memory = pinned(MAX, 38 * MB, new Lease())) {
                check(!memory.claim(98 * MB + 1, MAX, 38 * MB), "over");
                check(!memory.claim(98 * MB, 256 * MB, 38 * MB), "heap limit moved");
                check(memory.claim(98 * MB, MAX, 38 * MB), "exact");
                check(memory.claim(98 * MB, MAX, 38 * MB), "the same grant is not charged twice");
            }
            // A ceiling that stopped being readable gives nothing.
            try (SourceAudio.DspMemory lost =
                    SourceAudio.dspMemory(MAX, 38 * MB, new Lease(), () -> -1, () -> false)) {
                check(lost.available(MAX, 38 * MB) == 0, "pin lost");
            }
            // A ceiling at or above the free heap gives nothing.
            try (SourceAudio.DspMemory full =
                    SourceAudio.dspMemory(MAX, 38 * MB, new Lease(), () -> 154 * MB, () -> false)) {
                check(full.available(MAX, 38 * MB) == 0, "no room beyond the ceiling");
            }
        } finally {
            SourceAudio.physicalAvailable = () -> -1;
        }
    }

    /** Playback needing the device ends analysis; the grant and the lease go back exactly once. */
    static void cancellationReleasesThePinnedGrant() {
        reset();
        SourceAudio.physicalAvailable = () -> 2048 * MB;
        try {
            Lease lease = new Lease();
            SourceAudio.DspMemory memory = pinned(512 * MB, 38 * MB, lease);
            check(memory.claim(100 * MB, 512 * MB, 38 * MB), "claimed");
            check(SourceAudio.analysisAvailable(512 * MB, 38 * MB) == 118 * MB, "promised");
            AutoCloseable upgrade = PlaybackPriority.upgrade("song");
            check(memory.cancelled(), "told to stop");
            check(memory.available(512 * MB, 38 * MB) == 0, "and offered nothing");
            // The grant stays charged until the owner returns: the memory is still in use.
            check(SourceAudio.analysisAvailable(512 * MB, 38 * MB) == 118 * MB, "still held");
            memory.close();
            memory.close();
            check(lease.closed == 1, "lease released once");
            check(SourceAudio.analysisAvailable(512 * MB, 38 * MB) == 218 * MB, "grant returned");
            try {
                upgrade.close();
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        } finally {
            SourceAudio.physicalAvailable = () -> -1;
        }
    }

    /** System memory is a diagnostic: neither low nor unreadable values change what heap allows. */
    static void systemMemoryDoesNotChangeHeapAdmission() {
        reset();
        try (SourceAudio.DspMemory memory = pinned(512 * MB, 38 * MB, new Lease())) {
            long[] free = {2048 * MB, 100 * MB, 0, -1};
            for (long value : free) {
                SourceAudio.physicalAvailable = () -> value;
                check(memory.available(512 * MB, 38 * MB) == 338 * MB, "free " + value / MB);
                check(memory.claim(338 * MB, 512 * MB, 38 * MB), "claim at free " + value / MB);
            }
            SourceAudio.physicalAvailable =
                    () -> {
                        throw new IllegalStateException("unreadable");
                    };
            check(memory.available(512 * MB, 38 * MB) == 338 * MB, "a failing reader is unknown");
        } finally {
            SourceAudio.physicalAvailable = () -> -1;
        }
    }

    static void releaseWakesBoundedDistinctListenersOnce() throws Exception {
        reset();
        Runnable[] added = new Runnable[6];
        int[] calls = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                int n = i;
                added[i] = () -> calls[n]++;
                SourceAudio.onMemoryAvailable(added[i]);
            }
            SourceAudio.onMemoryAvailable(added[0]);
            SourceAudio.onMemoryAvailable(null);
            SourceAudio.JavaMemory grant = SourceAudio.reserveJava(BIG, 38 * MB, 8 * MB);
            check(calls[0] + calls[1] + calls[2] + calls[3] == 0, "nothing before release");
            grant.close();
            grant.close();
            for (int i = 0; i < 4; i++) check(calls[i] == 1, "listener " + i + ": " + calls[i]);
            check(calls[4] == 0 && calls[5] == 0, "no more than four");
            // A lease and its grant wake once between them.
            SourceAudio.DspMemory memory = pinned(BIG, 38 * MB, new Lease());
            check(memory.claim(8 * MB, BIG, 38 * MB), "claimed");
            memory.close();
            memory.close();
            check(calls[0] == 2, "one wakeup for a lease: " + calls[0]);
            // A claim that never took memory is still a release of its lease.
            pinned(BIG, 38 * MB, new Lease()).close();
            check(calls[0] == 3, "idle lease: " + calls[0]);
            SourceAudio.removeMemoryListener(added[0]);
            SourceAudio.reserveJava(BIG, 38 * MB, 8 * MB).close();
            check(calls[0] == 3 && calls[1] == 4, "removed listener stays quiet");
            // A failing listener cannot stop the others.
            SourceAudio.removeMemoryListener(added[1]);
            Runnable failing =
                    () -> {
                        throw new IllegalStateException("listener");
                    };
            SourceAudio.onMemoryAvailable(failing);
            SourceAudio.reserveJava(BIG, 38 * MB, 8 * MB).close();
            check(calls[2] == 5 && calls[3] == 5, "others still woken " + calls[2]);
        } finally {
            for (Runnable listener : added) SourceAudio.removeMemoryListener(listener);
            SourceAudio.removeMemoryListener(null);
        }
    }

    /** The wakeup runs after the memory is back and while no accounting lock is held. */
    static void wakeupRunsOutsideTheLedgerLock() throws Exception {
        reset();
        long before = SourceAudio.analysisAvailable(BIG, 38 * MB);
        boolean[] seen = new boolean[3];
        java.util.concurrent.atomic.AtomicBoolean entered =
                new java.util.concurrent.atomic.AtomicBoolean();
        Runnable listener =
                () -> {
                    // The probe below releases memory too; only the first wakeup is examined.
                    if (!entered.compareAndSet(false, true)) return;
                    seen[0] = SourceAudio.analysisAvailable(BIG, 38 * MB) == before;
                    // Another thread needs the ledger lock; it cannot get it if this thread holds
                    // it.
                    Thread other =
                            new Thread(
                                    () -> {
                                        SourceAudio.JavaMemory probe =
                                                SourceAudio.reserveJava(BIG, 38 * MB, 1 * MB);
                                        seen[1] = probe != null;
                                        if (probe != null) probe.close();
                                    });
                    other.start();
                    try {
                        other.join(2_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    seen[2] = !other.isAlive();
                };
        SourceAudio.onMemoryAvailable(listener);
        try {
            SourceAudio.JavaMemory grant = SourceAudio.reserveJava(BIG, 38 * MB, 16 * MB);
            grant.close();
        } finally {
            SourceAudio.removeMemoryListener(listener);
        }
        check(seen[0], "memory already returned");
        check(
                seen[1] && seen[2],
                "ledger usable from another thread during the callback "
                        + java.util.Arrays.toString(seen));
    }

    static void openGrantsAreBounded() {
        reset();
        java.util.List<SourceAudio.JavaMemory> held = new java.util.ArrayList<>();
        SourceAudio.JavaMemory extra = null;
        try {
            for (int i = 0; i < 64; i++) {
                SourceAudio.JavaMemory grant = SourceAudio.reserveJava(MAX, 20 * MB, 64 * 1024);
                if (grant == null) break;
                held.add(grant);
            }
            check(held.size() == 16, "capped at sixteen: " + held.size());
        } finally {
            for (SourceAudio.JavaMemory grant : held) grant.close();
        }
        extra = SourceAudio.reserveJava(MAX, 20 * MB, 64 * 1024);
        check(extra != null, "room again after closing");
        extra.close();
    }

    static void grantsAreReleasedOnEveryExit() {
        reset();
        long before = SourceAudio.analysisAvailable(BIG, 38 * MB);
        SourceAudio.JavaMemory grant = SourceAudio.reserveJava(BIG, 38 * MB, 8 * MB);
        grant.close();
        grant.close();
        check(SourceAudio.analysisAvailable(BIG, 38 * MB) == before, "double close");
        try (SourceAudio.JavaMemory failed = SourceAudio.reserveJava(BIG, 38 * MB, BIG)) {
            check(failed == null, "refused when it cannot fit");
        }
        try (SourceAudio.JavaMemory thrown = SourceAudio.reserveJava(BIG, 38 * MB, 8 * MB)) {
            throw new IllegalStateException("analysis failed");
        } catch (IllegalStateException expected) {
            check(SourceAudio.analysisAvailable(BIG, 38 * MB) == before, "released on throw");
        }
    }

    static void growthReserveStaysConservativeWithoutAGate() {
        reset();
        // No loading gate is installed, so the whole stock player reserve is assumed.
        long available = SourceAudio.dspAvailable(MAX, 38 * MB);
        check(
                available == 5 * MB,
                "36 MiB can't be justified without an enforced ceiling: " + available);
        String line = SourceAudio.memoryDiagnostics(MAX, 38 * MB);
        check(line.contains("playbackReserveMiB=141(assumed)"), line);
        check(line.contains("heapUsedMiB=38") && line.contains("heapMaxMiB=192"), line);
        check(line.length() < 300, "bounded");
    }

    static void diagnosticsSeparateSystemMemoryFromHeap() {
        reset();
        SourceAudio.physicalAvailable = () -> 900 * MB;
        try {
            String line = SourceAudio.memoryDiagnostics(MAX, 38 * MB);
            check(line.contains("systemFreeMiB=900"), line);
            check(
                    line.contains("dspAvailableMiB=5"),
                    "system RAM does not raise the heap limit: " + line);
            SourceAudio.physicalAvailable = () -> -1;
            check(
                    SourceAudio.memoryDiagnostics(MAX, 38 * MB).contains("systemFreeMiB=?"),
                    "unreadable");
        } finally {
            SourceAudio.physicalAvailable = () -> -1;
        }
    }

    static void backgroundDecodeYieldsButVerificationDoesNot() throws Exception {
        reset();
        long deadline = System.nanoTime() + 30_000_000_000L;
        try (AutoCloseable upgrade = PlaybackPriority.upgrade("song")) {
            try (SourceAudio.Decoding ignored = SourceAudio.decode(deadline, () -> false)) {
                throw new AssertionError("background decode admitted during an upgrade");
            } catch (SourceAudio.Stopped stopped) {
                check(
                        stopped.reason == SourceAudio.StopReason.PRIORITY,
                        "reason " + stopped.reason);
            }
            try (SourceAudio.Decoding verification =
                    SourceAudio.decodeVerification(deadline, () -> false)) {
                verification.check();
            }
        }
        // A background decode already running stops the next time it checks.
        reset();
        try (SourceAudio.Decoding running = SourceAudio.decode(deadline, () -> false)) {
            running.check();
            AutoCloseable upgrade = PlaybackPriority.upgrade("song");
            try {
                running.check();
                throw new AssertionError("running decode kept going");
            } catch (SourceAudio.Stopped stopped) {
                check(
                        stopped.reason == SourceAudio.StopReason.PRIORITY,
                        "reason " + stopped.reason);
            } finally {
                upgrade.close();
            }
        }
        // The permit is free again afterwards.
        reset();
        try (SourceAudio.Decoding again = SourceAudio.decode(deadline, () -> false)) {
            again.check();
        }
    }

    static void verificationHoldsTheSharedPermit() throws Exception {
        reset();
        long soon = System.nanoTime() + 200_000_000L;
        try (SourceAudio.Decoding verification =
                SourceAudio.decodeVerification(System.nanoTime() + 5_000_000_000L, () -> false)) {
            Thread other = new Thread(() -> {});
            AtomicLong outcome = new AtomicLong();
            other =
                    new Thread(
                            () -> {
                                try (SourceAudio.Decoding ignored =
                                        SourceAudio.decode(soon, () -> false)) {
                                    outcome.set(1);
                                } catch (IOException stopped) {
                                    outcome.set(2);
                                }
                            });
            other.start();
            other.join();
            check(outcome.get() == 2, "one working set at a time");
        }
    }

    static void cancellationReachesFallbackMemoryLease() throws Exception {
        reset();
        try (SourceAudio.DspMemory memory = SourceAudio.dspMemory(MAX, 38 * MB)) {
            check(!memory.cancelled(), "quiet");
            AutoCloseable upgrade = PlaybackPriority.upgrade("song");
            check(memory.cancelled(), "analysis is told to stop");
            check(!memory.claim(1 * MB, MAX, 38 * MB), "and cannot claim memory");
            upgrade.close();
        }
    }

    /** A reader whose read blocks until its own connection is closed. */
    static final class Blocking implements androidx.media3.datasource.DataSource {
        final java.util.concurrent.CountDownLatch inRead =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch released =
                new java.util.concurrent.CountDownLatch(1);
        volatile boolean closed;

        public void addTransferListener(androidx.media3.datasource.TransferListener listener) {}

        public long open(androidx.media3.datasource.DataSpec spec) {
            return 1 << 20;
        }

        public int read(byte[] buffer, int offset, int length) throws IOException {
            inRead.countDown();
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                throw new IOException(interrupted);
            }
            throw new IOException("connection closed");
        }

        public android.net.Uri getUri() {
            return null;
        }

        public java.util.Map<String, java.util.List<String>> getResponseHeaders() {
            return java.util.Collections.emptyMap();
        }

        public void close() {
            closed = true;
            released.countDown();
        }
    }

    static Throwable blockedRead(boolean yields, Blocking connection, Runnable trigger)
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        androidx.media3.datasource.DataSpec spec = new androidx.media3.datasource.DataSpec(NO_URI);
        SourceAudio reader =
                new SourceAudio(
                        spec,
                        System.nanoTime() + 60_000_000_000L,
                        () -> false,
                        () -> connection,
                        yields);
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                reader.readAt(0, new byte[16], 0, 16);
                                outcome.set(new AssertionError("read returned"));
                            } catch (Throwable stopped) {
                                outcome.set(stopped);
                            }
                        });
        worker.start();
        check(connection.inRead.await(5, java.util.concurrent.TimeUnit.SECONDS), "read started");
        long began = System.nanoTime();
        trigger.run();
        worker.join(yields ? 5_000 : 600);
        long took = (System.nanoTime() - began) / 1_000_000;
        if (!yields) {
            check(worker.isAlive() && !connection.closed, "a foreground reader is left alone");
            connection.close();
            worker.join(5_000);
        } else {
            check(took < 3_000, "returned promptly: " + took + " ms");
        }
        check(!worker.isAlive(), "reader thread finished");
        reader.close();
        return outcome.get();
    }

    static final android.net.Uri NO_URI = android.net.Uri.EMPTY;

    static void blockedBackgroundReadIsCancelledByItsOwnReader() throws Exception {
        reset();
        Blocking connection = new Blocking();
        Throwable stopped =
                blockedRead(true, connection, () -> PlaybackPriority.playbackUrgent(1_000));
        check(connection.closed, "its own connection was closed");
        check(
                stopped instanceof SourceAudio.Stopped
                        && ((SourceAudio.Stopped) stopped).reason
                                == SourceAudio.StopReason.PRIORITY,
                "stopped for priority: " + stopped);
        check(SourceAudio.openReaders() == 0, "unregistered on close");
        // Every kind of higher-priority work does it.
        reset();
        Blocking second = new Blocking();
        blockedRead(true, second, () -> PlaybackPriority.nextPreparation("next"));
        check(second.closed, "next preparation too");
        reset();
        Blocking third = new Blocking();
        blockedRead(true, third, () -> PlaybackPriority.upgrade("song"));
        check(third.closed, "and an upgrade");
        reset();
    }

    static void foreignReadersAreNotCancelled() throws Exception {
        reset();
        Blocking connection = new Blocking();
        blockedRead(false, connection, () -> PlaybackPriority.playbackUrgent(1_000));
        reset();
    }

    static void urgencyFollowsThePlayersState() {
        check(SourceAutomix.urgentMillis(2, true, 0) == 5_000, "buffering while wanted");
        check(SourceAutomix.urgentMillis(2, false, 0) == 0, "paused is not urgent");
        check(SourceAutomix.urgentMillis(3, true, 500) == 4_000, "nearly empty buffer");
        check(SourceAutomix.urgentMillis(3, true, 20_000) == 0, "healthy buffer");
        check(SourceAutomix.urgentMillis(3, true, -1) == 0, "unknown buffer");
        check(SourceAutomix.urgentMillis(1, true, 0) == 0, "idle");
        check(SourceAutomix.urgentMillis(4, true, 0) == 0, "ended");
    }

    static void diagnosticsIncludeProcessMemory() {
        SourceAudio.processResident = () -> 321 * MB;
        SourceAudio.nativeHeap = () -> 77 * MB;
        try {
            String line = SourceAudio.memoryDiagnostics(MAX, 38 * MB);
            check(line.contains("processRssMiB=321") && line.contains("nativeHeapMiB=77"), line);
            check(line.length() < 360, "bounded: " + line.length());
            SourceAudio.processResident = () -> -1;
            SourceAudio.nativeHeap = () -> -1;
            check(
                    SourceAudio.memoryDiagnostics(MAX, 38 * MB).contains("nativeHeapMiB=?"),
                    "unknown");
        } finally {
            SourceAudio.processResident = () -> -1;
            SourceAudio.nativeHeap = () -> -1;
        }
    }

    static void memoryWaitsBackOff() {
        long previous = 0;
        for (int i = 0; i < 10; i++) {
            long delay = SourceAutomix.retryDelayNanos(i);
            check(delay >= previous, "never shrinks");
            previous = delay;
        }
        check(SourceAutomix.retryDelayNanos(0) == 15_000_000_000L, "starts at 15 s");
        check(SourceAutomix.retryDelayNanos(99) == 300_000_000_000L, "capped at five minutes");
        check(SourceAutomix.retryDelayNanos(-1) == 15_000_000_000L, "negative is the first wait");
    }

    public static void main(String[] args) throws Exception {
        unrelatedHeapGrowthDoesNotReleaseAGrant();
        materializationIsCountedOnce();
        dspCreditsOnlyWhatItReports();
        headroomScalesWithTheHeapAndTheCeiling();
        concurrentClaimsShareTheHeadroom();
        claimsStopAtTheBoundary();
        cancellationReleasesThePinnedGrant();
        systemMemoryDoesNotChangeHeapAdmission();
        releaseWakesBoundedDistinctListenersOnce();
        wakeupRunsOutsideTheLedgerLock();
        openGrantsAreBounded();
        grantsAreReleasedOnEveryExit();
        growthReserveStaysConservativeWithoutAGate();
        diagnosticsSeparateSystemMemoryFromHeap();
        backgroundDecodeYieldsButVerificationDoesNot();
        verificationHoldsTheSharedPermit();
        cancellationReachesFallbackMemoryLease();
        memoryWaitsBackOff();
        blockedBackgroundReadIsCancelledByItsOwnReader();
        foreignReadersAreNotCancelled();
        urgencyFollowsThePlayersState();
        diagnosticsIncludeProcessMemory();
        PlaybackPriority.reset();
        System.out.println("SourceAudioPriorityTest: 22 passed");
    }
}
