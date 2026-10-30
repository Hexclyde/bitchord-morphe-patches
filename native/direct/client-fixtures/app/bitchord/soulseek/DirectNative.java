package app.bitchord.soulseek;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class DirectNative {
    static final AtomicLong sequence = new AtomicLong();
    static final ConcurrentHashMap<Long, CountDownLatch> streams =
            new ConcurrentHashMap<Long, CountDownLatch>();
    static volatile String[] sources;
    static volatile Runnable starting;
    static volatile int stopped;
    static final CountDownLatch reading = new CountDownLatch(1);

    static long start(String[] values, int timeout) {
        sources = values;
        Runnable hook = starting;
        starting = null;
        if (hook != null) hook.run();
        return sequence.incrementAndGet();
    }

    static String endpoint(long node) {
        return "fixture-descriptor";
    }

    static long connect(long node, String descriptor, int timeout) {
        long id = sequence.incrementAndGet();
        streams.put(id, new CountDownLatch(1));
        return id;
    }

    static long accept(long node, int timeout) {
        return 0;
    }

    static void punch(long node, String descriptor, int timeout) {}

    static int read(long id, byte[] bytes, int offset, int length, int timeout) throws IOException {
        CountDownLatch latch = streams.get(id);
        reading.countDown();
        try {
            if (latch != null) latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IOException("cancelled");
    }

    static void write(long id, byte[] bytes, int offset, int length, int timeout) {}

    static void finish(long id, int timeout) {}

    static void closeConnection(long id) {
        CountDownLatch latch = streams.remove(id);
        if (latch != null) latch.countDown();
    }

    static void closeNode(long node) {
        stopped++;
    }
}
