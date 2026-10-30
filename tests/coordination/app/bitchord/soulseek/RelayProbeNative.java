package app.bitchord.soulseek;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class RelayProbeNative {
    static final AtomicInteger loads = new AtomicInteger();
    static final AtomicInteger creates = new AtomicInteger();
    static final AtomicInteger closes = new AtomicInteger();
    static volatile boolean failCreate;
    private static final AtomicLong handles = new AtomicLong(1000);
    private static final Map<Long, Probe> probes = new ConcurrentHashMap<>();

    static final class Probe {
        final long handle;
        final String secret;
        volatile boolean closed, closeFails;
        volatile NostrNative.Gate closeGate;

        Probe(long handle, String secret) {
            this.handle = handle;
            this.secret = secret;
        }
    }

    static void load() {
        loads.incrementAndGet();
    }

    static long create(String secret) {
        creates.incrementAndGet();
        if (failCreate) return 0;
        Probe probe = new Probe(handles.incrementAndGet(), secret);
        probes.put(probe.handle, probe);
        return probe.handle;
    }

    static Probe get(long handle) {
        Probe probe = probes.get(handle);
        if (probe == null) throw new AssertionError("Unknown relay probe");
        return probe;
    }

    static void close(long handle) throws IOException {
        Probe probe = probes.get(handle);
        if (probe == null || probe.closed) return;
        closes.incrementAndGet();
        NostrNative.Gate gate;
        synchronized (probe) {
            gate = probe.closeGate;
            probe.closeGate = null;
        }
        if (gate != null) gate.hold();
        if (probe.closeFails) throw new IOException("Relay probe shutdown failed");
        probe.closed = true;
    }
}
