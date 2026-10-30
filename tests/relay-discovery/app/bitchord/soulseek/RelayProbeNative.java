package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class RelayProbeNative {
    static final AtomicLong handles = new AtomicLong();
    static final Map<Long, State> states = new ConcurrentHashMap<>();
    static volatile State latest;
    static volatile CountDownLatch created = new CountDownLatch(1);
    static volatile boolean block;
    static volatile String failHost = "";

    static final class State {
        final String identity;
        final CountDownLatch firstTwo = new CountDownLatch(2), release = new CountDownLatch(1);
        final Map<String, Integer> calls = new ConcurrentHashMap<>();
        final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        volatile boolean closed;

        State(String identity) {
            this.identity = identity;
        }

        void entered() throws Exception {
            if (!firstTwo.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Two probes did not start");
        }

        void release() {
            release.countDown();
        }
    }

    static long create(String identity) {
        State state = new State(identity);
        latest = state;
        long handle = handles.incrementAndGet();
        states.put(handle, state);
        created.countDown();
        if (!block) state.release();
        return handle;
    }

    static String probe(long handle, String relay, int timeout) throws Exception {
        State state = states.get(handle);
        if (state == null || state.closed) throw new IOException("Probe closed");
        if (timeout < 1 || timeout > 8000) throw new AssertionError("Unbounded probe timeout");
        int active = state.active.incrementAndGet();
        state.maximum.accumulateAndGet(active, Math::max);
        state.calls.merge(relay, 1, Integer::sum);
        state.firstTwo.countDown();
        try {
            if (!state.release.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Probe gate not released");
            if (state.closed) throw new IOException("Probe cancelled");
            boolean failure = relay.contains(failHost) && !failHost.isEmpty();
            return new JSONObject()
                    .put("relay", relay)
                    .put("status", failure ? "auth_required" : "ok")
                    .put("online", true)
                    .put("usable", !failure)
                    .put("connect_ms", 7)
                    .put("roundtrip_ms", failure ? -1 : relay.contains("fast") ? 3 : 20)
                    .put("completed_ms", 30)
                    .put("samples", failure ? 0 : 3)
                    .toString();
        } finally {
            synchronized (state) {
                state.active.decrementAndGet();
                state.notifyAll();
            }
        }
    }

    static void close(long handle) throws Exception {
        State state = states.get(handle);
        if (state == null) return;
        synchronized (state) {
            state.closed = true;
            state.release();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (state.active.get() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("Native probe writers did not stop");
                TimeUnit.NANOSECONDS.timedWait(state, remaining);
            }
        }
    }
}
