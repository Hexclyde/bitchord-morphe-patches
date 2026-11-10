package app.bitchord.telegram;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class TelegramStreamCoordinatorTest {
    static final class FakeTdlib implements TelegramStreamCoordinator.Backend {
        final List<long[]> commands = Collections.synchronizedList(new ArrayList<>());
        final Map<Long, Long> ranges = new ConcurrentHashMap<>();
        final List<TelegramStreamCoordinator.Reply> replies =
                Collections.synchronizedList(new ArrayList<>());
        TelegramStreamCoordinator coordinator;
        int cancelled;
        final Map<Integer, Integer> prefixCalls = new ConcurrentHashMap<>();

        @Override
        public long prefix(int file, long at) {
            prefixCalls.merge(file, 1, Integer::sum);
            for (Map.Entry<Long, Long> range : ranges.entrySet())
                if (at >= range.getKey() && at < range.getValue()) return range.getValue() - at;
            return 0;
        }

        @Override
        public void download(
                int file,
                int priority,
                long at,
                long length,
                TelegramStreamCoordinator.Reply reply) {
            commands.add(new long[] {file, priority, at, length});
            replies.add(reply);
        }

        @Override
        public void cancel(int file) {
            cancelled++;
        }

        void update(long at, long count, boolean notify) {
            if (count > 0) ranges.put(at, at + count);
            if (notify)
                coordinator.update(
                        7,
                        new TelegramStreamCoordinator.State(
                                "cache", 10_000_000, at, count, count, false, true));
        }
    }

    static final ExecutorService pool = Executors.newCachedThreadPool();

    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    static void until(java.util.function.BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > end)
                throw new AssertionError("Timed out waiting for scripted request");
            Thread.sleep(2);
        }
    }

    static TelegramStreamCoordinator create(FakeTdlib fake) {
        TelegramStreamCoordinator c =
                new TelegramStreamCoordinator(fake, () -> System.nanoTime() / 1_000_000);
        c.fallbackMs = 30;
        fake.coordinator = c;
        return c;
    }

    static Future<Long> read(TelegramStreamCoordinator.Registration r, long at, int count) {
        return pool.submit(() -> r.await(at, count, 3000, () -> {}));
    }

    static void failed(Future<?> f) throws Exception {
        try {
            f.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Cancelled read succeeded");
        } catch (ExecutionException expected) {
            check(expected.getCause() instanceof IOException, "Classified read failure");
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            FakeTdlib fake = new FakeTdlib();
            TelegramStreamCoordinator c = create(fake);
            final FakeTdlib competing = fake;
            TelegramStreamCoordinator.Registration play =
                    c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 1_000_000);
            Future<Long> playing = read(play, 0, 8192);
            until(() -> competing.commands.size() == 1);
            TelegramStreamCoordinator.Registration preview =
                    c.register(7, TelegramStreamCoordinator.Consumer.PREVIEW, 1_000_000);
            Future<Long> previewing = read(preview, 1_000_000, 8192);
            Thread.sleep(50);
            check(
                    fake.commands.size() == 1 && fake.commands.get(0)[1] == 32,
                    "Preview never replaces playback window");
            fake.update(0, 4096, true);
            Thread.sleep(20);
            check(!playing.isDone(), "Progress is not proof of required range");
            fake.update(0, 8192, true);
            check(playing.get(1, TimeUnit.SECONDS) >= 8192, "Exact required range wakes playback");
            Thread.sleep(40);
            check(fake.commands.size() == 1, "Cached foreground range retains its ownership");
            c.playbackActive(false);
            until(() -> competing.commands.size() == 2);
            check(c.active() == 2, "Paused main reader yields its window without being closed");
            play.close();
            fake.update(1_000_000, 8192, true);
            previewing.get(1, TimeUnit.SECONDS);
            preview.close();
            check(c.active() == 0, "Registrations released");

            fake = new FakeTdlib();
            c = create(fake);
            final FakeTdlib missed = fake;
            TelegramStreamCoordinator.Registration r =
                    c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 128_000);
            TelegramStreamTrace measurements =
                    new TelegramStreamTrace(
                            (type, fields) -> {}, () -> true, () -> System.nanoTime() / 1_000_000L);
            r.trace =
                    measurements.begin(
                            7, 5000, 128, "playback", 32, 0, true, System.nanoTime() / 1_000_000L);
            Future<Long> waiting = read(r, 5000, 128);
            until(() -> missed.commands.size() == 1);
            fake.update(5000, 128, false);
            check(waiting.get(1, TimeUnit.SECONDS) == 128, "Fallback recovers a missed updateFile");
            check(
                    r.trace.unreadable == 0 && r.trace.polls > 0,
                    "Fallback readability uses the actual prefix");
            r.close();
            int requests = fake.commands.size();
            r = c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 128_000);
            check(r.await(5000, 128, 100, () -> {}) == 128, "Partly cached data readable");
            r.close();
            check(fake.commands.size() == requests, "Cache requires no downloadFile");

            fake = new FakeTdlib();
            c = create(fake);
            c.fallbackMs = 1000;
            final FakeTdlib isolated = fake;
            r = c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 128000);
            TelegramStreamCoordinator.Registration other =
                    c.register(8, TelegramStreamCoordinator.Consumer.BACKGROUND, 0);
            waiting = read(r, 0, 128);
            until(() -> isolated.commands.size() == 1);
            int before = fake.prefixCalls.get(7);
            for (int n = 0; n < 10; n++)
                c.update(
                        8, new TelegramStreamCoordinator.State("cache", 100, 0, n, n, false, true));
            Thread.sleep(50);
            check(
                    fake.prefixCalls.get(7) == before,
                    "Another file's update never wakes this reader");
            r.close();
            failed(waiting);
            other.close();

            fake = new FakeTdlib();
            c = create(fake);
            final FakeTdlib ahead = fake;
            r = c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 128_000);
            waiting = read(r, 0, 512);
            until(() -> ahead.commands.size() == 1);
            check(
                    fake.commands.get(0)[3] == 480_000,
                    "Read-ahead is 30 seconds at encoded bitrate");
            fake.update(0, 480_000, true);
            waiting.get(1, TimeUnit.SECONDS);
            r.consumed(200_000, 480_000);
            check(fake.commands.size() == 1, "Healthy buffer does not retarget");
            r.consumed(250_000, 480_000);
            check(fake.commands.size() == 2, "Low water extends before the end");
            check(
                    fake.commands.get(1)[3] - 250_000 <= c.maxAheadBytes,
                    "Speculative bytes ahead stay capped");
            c.seek();
            check(c.active() == 1, "Buffered seek keeps its live data source usable");
            r.close();

            fake = new FakeTdlib();
            c = create(fake);
            final FakeTdlib hiRes = fake;
            r = c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 10_000_000);
            waiting = read(r, 0, 512);
            until(() -> hiRes.commands.size() == 1);
            fake.update(0, 4 * 1024 * 1024, true);
            waiting.get(1, TimeUnit.SECONDS);
            for (int n = 1; n <= 16; n++) r.consumed(n * 65536L, 4 * 1024 * 1024L);
            check(
                    fake.commands.size() == 1,
                    "Capped hi-res read-ahead does not retarget each 64 KiB read");
            r.close();

            fake = new FakeTdlib();
            c = create(fake);
            final FakeTdlib limited = fake;
            r = c.register(7, TelegramStreamCoordinator.Consumer.BACKGROUND, 0);
            waiting = read(r, 0, 32);
            until(() -> limited.commands.size() == 1);
            fake.replies.get(0).done(new IOException("FLOOD_WAIT"), 60000);
            failed(waiting);
            r = c.register(8, TelegramStreamCoordinator.Consumer.PLAYBACK, 128000);
            failed(read(r, 0, 32));
            check(fake.commands.size() == 1, "Rate-limit backoff spans files");

            fake = new FakeTdlib();
            c = create(fake);
            final FakeTdlib unknown = fake;
            r = c.register(7, TelegramStreamCoordinator.Consumer.BACKGROUND, 0);
            waiting = read(r, 0, 0);
            until(() -> unknown.commands.size() == 1);
            check(!waiting.isDone(), "Unknown size is not treated as already complete");
            c.update(
                    7, new TelegramStreamCoordinator.State("cache", 100, 0, 100, 100, true, false));
            waiting.get(1, TimeUnit.SECONDS);
            r.close();

            fake = new FakeTdlib();
            c = create(fake);
            final FakeTdlib rapid = fake;
            r = c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 128_000);
            waiting = read(r, 0, 512);
            until(() -> rapid.commands.size() == 1);
            TelegramStreamCoordinator.Reply old = fake.replies.get(0);
            c.seek();
            r.close();
            failed(waiting);
            r = c.register(7, TelegramStreamCoordinator.Consumer.SEEK, 128_000);
            waiting = read(r, 9_000_000, 512);
            until(() -> rapid.commands.size() == 2);
            old.done(new IOException("Old request failed"), 0);
            fake.update(0, 512, true);
            Thread.sleep(20);
            check(!waiting.isDone(), "Old range cannot satisfy latest seek");
            fake.update(9_000_000, 512, true);
            waiting.get(1, TimeUnit.SECONDS);
            r.close();
            check(c.active() == 0 && c.generation() == 1, "Seek generation and cleanup");

            fake = new FakeTdlib();
            c = create(fake);
            final FakeTdlib ending = fake;
            r = c.register(7, TelegramStreamCoordinator.Consumer.ANALYSIS, 0);
            waiting = read(r, 800, 50);
            until(() -> ending.commands.size() == 1);
            c.reset(false);
            failed(waiting);
            check(c.active() == 0, "Session change cleans every registration");
            r = c.register(7, TelegramStreamCoordinator.Consumer.BACKGROUND, 0);
            waiting = read(r, 900, 50);
            until(() -> ending.commands.size() == 2);
            fake.replies.get(1).done(new IOException("Rejected"), 0);
            failed(waiting);
            check(c.active() == 0, "Failure releases registration");
            r = c.register(7, TelegramStreamCoordinator.Consumer.BACKGROUND, 0);
            waiting = read(r, 1000, 50);
            until(() -> ending.commands.size() == 3);
            r.close();
            failed(waiting);
            check(c.active() == 0, "Cancellation releases registration");
            r = c.register(7, TelegramStreamCoordinator.Consumer.BACKGROUND, 0);
            waiting = read(r, 1100, 50);
            until(() -> ending.commands.size() == 4);
            c.reset(true);
            failed(waiting);
            check(c.active() == 0, "Shutdown releases registration");
            try {
                c.register(7, TelegramStreamCoordinator.Consumer.PLAYBACK, 0);
                throw new AssertionError("Registered after shutdown");
            } catch (IOException expected) {
            }
            System.out.println(
                    "PASS Telegram coordinator: ownership, exact ranges, updates, fallback, cache,"
                            + " seeks and lifecycle");
        } finally {
            pool.shutdownNow();
        }
    }
}
