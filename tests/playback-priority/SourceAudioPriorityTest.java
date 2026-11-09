package app.bitchord.telegram;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic checks of analysis memory accounting and priority-aware decoding. */
public final class SourceAudioPriorityTest {
    static final long MB = 1L << 20, MAX = 192 * MB;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void reset() {
        PlaybackPriority.reset();
    }

    /** Heap growth nobody reported is other memory: it must not release a pending grant. */
    static void unrelatedHeapGrowthDoesNotReleaseAGrant() {
        reset();
        long before = SourceAudio.analysisAvailable(MAX, 38 * MB);
        try (SourceAudio.JavaMemory grant = SourceAudio.reserveJava(MAX, 38 * MB, 16 * MB)) {
            check(grant != null, "granted");
            long promised = SourceAudio.analysisAvailable(MAX, 38 * MB);
            check(promised == before - 16 * MB, "a promise is charged in full: " + promised);
            // 16 MiB of playback or UI allocation lands; the grant is still entirely pending.
            long grown = SourceAudio.analysisAvailable(MAX, 54 * MB);
            check(grown == before - 32 * MB, "growth is charged on top of the promise: " + grown);
            check(grant.pending() == 16 * MB, "still pending");
        }
        check(SourceAudio.analysisAvailable(MAX, 38 * MB) == before, "released on close");
    }

    /** What the owner reports allocated moves from pending into the heap figure, once. */
    static void materializationIsCountedOnce() {
        reset();
        long before = SourceAudio.analysisAvailable(MAX, 38 * MB);
        try (SourceAudio.JavaMemory grant = SourceAudio.reserveJava(MAX, 38 * MB, 16 * MB)) {
            grant.materialized(4 * MB);
            check(grant.pending() == 12 * MB, "partly materialized");
            // 4 MiB really landed in the heap and 12 MiB is still promised.
            check(
                    SourceAudio.analysisAvailable(MAX, 42 * MB) == before - 16 * MB,
                    "no double count: " + SourceAudio.analysisAvailable(MAX, 42 * MB));
            grant.materialized(12 * MB);
            grant.materialized(12 * MB);
            grant.materialized(-5);
            check(grant.pending() == 0, "clamped to the grant, never negative");
            check(
                    SourceAudio.analysisAvailable(MAX, 54 * MB) == before - 16 * MB,
                    "fully landed, counted once");
        }
        check(SourceAudio.analysisAvailable(MAX, 38 * MB) == before, "released on close");
        // A closed grant ignores late reports and a second close is harmless.
        SourceAudio.JavaMemory late = SourceAudio.reserveJava(MAX, 38 * MB, 8 * MB);
        late.close();
        late.materialized(8 * MB);
        late.close();
        check(late.pending() == 0, "closed grants hold nothing");
        check(SourceAudio.analysisAvailable(MAX, 38 * MB) == before, "no residue");
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
        long before = SourceAudio.analysisAvailable(MAX, 38 * MB);
        SourceAudio.JavaMemory grant = SourceAudio.reserveJava(MAX, 38 * MB, 8 * MB);
        grant.close();
        grant.close();
        check(SourceAudio.analysisAvailable(MAX, 38 * MB) == before, "double close");
        try (SourceAudio.JavaMemory failed = SourceAudio.reserveJava(MAX, 38 * MB, MAX)) {
            check(failed == null, "refused when it cannot fit");
        }
        try (SourceAudio.JavaMemory thrown = SourceAudio.reserveJava(MAX, 38 * MB, 8 * MB)) {
            throw new IllegalStateException("analysis failed");
        } catch (IllegalStateException expected) {
            check(SourceAudio.analysisAvailable(MAX, 38 * MB) == before, "released on throw");
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
        System.out.println("SourceAudioPriorityTest: 15 passed");
    }
}
