package app.bitchord.soulseek;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One Tor invocation per dedicated Android service process. */
public final class TorNative implements TorRuntime.Engine {
    private static final AtomicBoolean CLAIMED = new AtomicBoolean();
    private volatile Thread worker;
    private volatile Throwable error;

    @Override
    public void start(final String[] arguments) throws IOException {
        if (!CLAIMED.compareAndSet(false, true))
            throw new IOException("Tor runtime requires a fresh service process");
        worker =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    int result = new org.torproject.jni.TorService().run(arguments);
                                    if (result != 0)
                                        error = new IOException("Tor exited with code " + result);
                                } catch (Throwable failure) {
                                    error = failure;
                                }
                            }
                        },
                        "bitchord-tor-native");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public boolean isAlive() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    @Override
    public Throwable error() {
        return error;
    }

    @Override
    public void awaitStopped(long timeoutMs) throws InterruptedException {
        Thread t = worker;
        if (t != null) t.join(timeoutMs);
    }
}
