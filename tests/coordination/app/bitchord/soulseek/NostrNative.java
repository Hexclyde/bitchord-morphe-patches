package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class NostrNative {
    static final AtomicInteger opens = new AtomicInteger();
    static final AtomicInteger operations = new AtomicInteger();
    private static final AtomicLong identities = new AtomicLong(1);
    private static final AtomicLong handles = new AtomicLong(1);
    private static final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private static final BlockingQueue<Session> opened = new LinkedBlockingQueue<>();

    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);

        void hold() throws IOException {
            entered.countDown();
            await(released, "Test gate was not released");
        }

        void entered() throws IOException {
            await(entered, "Operation never reached its gate");
        }

        void release() {
            released.countDown();
        }
    }

    static final class Request {
        final String peer, session, type, nonce;

        Request(String peer, String session, String type, String payload) throws Exception {
            this.peer = peer;
            this.session = session;
            this.type = type;
            nonce = new JSONObject(payload).optString("nonce", "");
        }

        JSONObject response(String responsePeer, String responseSession, String responseNonce)
                throws Exception {
            return new JSONObject()
                    .put("peer", responsePeer)
                    .put("session", responseSession)
                    .put("type", "pong")
                    .put("payload", new JSONObject().put("nonce", responseNonce));
        }

        JSONObject response() throws Exception {
            return response(peer, session, nonce);
        }
    }

    static final class Session {
        final long handle;
        final String secret;
        final BlockingQueue<String> batches = new LinkedBlockingQueue<>();
        final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        final Semaphore polls = new Semaphore(0);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean isClosed, closeFails;
        volatile int acceptedRelays = 1;
        volatile String health = "running";
        volatile Gate closeGate;
        volatile Thread reader;

        Session(long handle, String secret) {
            this.handle = handle;
            this.secret = secret;
        }

        void idle() throws IOException {
            try {
                if (!polls.tryAcquire(5, TimeUnit.SECONDS))
                    throw new IOException("Coordinator did not return to polling");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for coordinator", e);
            }
        }

        void deliver(JSONObject message) throws Exception {
            batches.add(new JSONArray().put(message).toString());
            idle();
        }

        Request request() throws Exception {
            Request request = requests.poll(5, TimeUnit.SECONDS);
            if (request == null) throw new AssertionError("Coordinator did not send a request");
            return request;
        }

        void awaitClosed() throws IOException {
            await(closed, "Coordinator did not close its native handle");
        }

        void awaitStopped() throws Exception {
            awaitClosed();
            Thread worker = reader;
            if (worker == null) throw new AssertionError("Coordinator never started its reader");
            worker.join(5_000);
            if (worker.isAlive()) throw new AssertionError("Coordinator reader did not stop");
        }
    }

    static void load() {}

    static String generateSecret() {
        return String.format(java.util.Locale.ROOT, "%064x", identities.incrementAndGet());
    }

    static String publicKey(String secret) throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(secret.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte value : digest)
            key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return key.toString();
    }

    static long open(String secret, String relays, String peers) throws Exception {
        if (new JSONArray(relays).length() == 0 || new JSONArray(peers).length() == 0)
            throw new IOException("Missing fake relay or peer configuration");
        operations.incrementAndGet();
        opens.incrementAndGet();
        Session session = new Session(handles.incrementAndGet(), secret);
        sessions.put(session.handle, session);
        opened.add(session);
        return session.handle;
    }

    static Session opened() throws Exception {
        Session session = opened.poll(5, TimeUnit.SECONDS);
        if (session == null) throw new AssertionError("Coordinator did not open a native handle");
        session.idle();
        return session;
    }

    static String status(long handle) throws Exception {
        operations.incrementAndGet();
        Session session = active(handle);
        return new JSONObject().put("state", session.health).put("connected_relays", 1).toString();
    }

    static String send(long handle, String peer, String session, String type, String payload)
            throws Exception {
        operations.incrementAndGet();
        Session target = active(handle);
        target.requests.add(new Request(peer, session, type, payload));
        return new JSONObject().put("accepted_relays", target.acceptedRelays).toString();
    }

    static String poll(long handle, int timeoutMs) throws Exception {
        operations.incrementAndGet();
        Session session = active(handle);
        session.reader = Thread.currentThread();
        session.polls.release();
        String batch = session.batches.take();
        if (session.isClosed) throw new IOException("Native handle is closed");
        return batch;
    }

    static void close(long handle) throws Exception {
        operations.incrementAndGet();
        Session session = sessions.get(handle);
        if (session == null) return;
        Gate gate;
        synchronized (session) {
            gate = session.closeGate;
            session.closeGate = null;
            session.isClosed = true;
            session.batches.offer("[]");
        }
        if (gate != null) gate.hold();
        session.closed.countDown();
        if (session.closeFails) throw new IOException("Native shutdown failed");
    }

    private static Session active(long handle) throws IOException {
        Session session = sessions.get(handle);
        if (session == null || session.isClosed) throw new IOException("Native handle is closed");
        return session;
    }

    static void await(CountDownLatch latch, String message) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(message, e);
        }
    }
}
