package app.bitchord.soulseek;

import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** The embedded i2pd router is owned only by :bitchord_i2p. */
public final class I2pService extends OverlayService {
    private static final String CERTIFICATES = "bitchord/i2p/certificates";
    private volatile long router;

    @Override
    protected String label() {
        return "I2P";
    }

    @Override
    protected String processSuffix() {
        return ":bitchord_i2p";
    }

    @Override
    protected void startRuntime(File home) throws IOException {
        File certificates = new File(home, "certificates");
        int[] count = {0};
        copyCertificates(getAssets(), CERTIFICATES, certificates, 0, count);
        if (count[0] == 0 || !new File(certificates, "reseed").isDirectory())
            throw new IOException("Bundled I2P certificates unavailable");
        long handle = I2pNative.start(home.getAbsolutePath());
        if (handle == 0) throw new IOException("I2P router unavailable");
        router = handle;
    }

    @Override
    protected String runtimeStatus() throws IOException {
        return I2pNative.status(router);
    }

    @Override
    protected String runtimeEndpoint() throws IOException {
        return I2pNative.localDestination(router);
    }

    @Override
    protected Transport connectRuntime(String endpoint, int timeoutMs) throws IOException {
        long stream = I2pNative.connect(router, endpoint, timeoutMs);
        if (stream == 0) throw new IOException("I2P connection unavailable");
        return new NativeStream(stream);
    }

    @Override
    protected Transport acceptRuntime(int timeoutMs) throws IOException {
        long stream = I2pNative.accept(router, timeoutMs);
        return stream == 0 ? null : new NativeStream(stream);
    }

    @Override
    protected void stopRuntime() {
        long handle = router;
        router = 0;
        if (handle != 0) I2pNative.stop(handle);
    }

    private static void copyCertificates(
            AssetManager assets, String source, File target, int depth, int[] count)
            throws IOException {
        if (depth > 3) throw new IOException("Invalid I2P certificate bundle");
        String[] children = assets.list(source);
        if (children == null) throw new IOException("I2P certificate bundle unavailable");
        if (children.length != 0) {
            privateDirectory(target);
            for (String child : children) {
                if (!child.matches("[a-zA-Z0-9_.-]{1,120}"))
                    throw new IOException("Invalid I2P certificate filename");
                copyCertificates(
                        assets, source + "/" + child, new File(target, child), depth + 1, count);
            }
            return;
        }
        if (depth == 0 || !target.getName().endsWith(".crt") || ++count[0] > 128)
            throw new IOException("Invalid I2P certificate bundle");
        privateDirectory(target.getParentFile());
        File temporary = File.createTempFile("certificate-", ".tmp", target.getParentFile());
        try {
            try (InputStream input = assets.open(source);
                    FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[8192];
                int total = 0;
                int length;
                while ((length = input.read(bytes)) != -1) {
                    total += length;
                    if (total > 262144) throw new IOException("I2P certificate too large");
                    output.write(bytes, 0, length);
                }
                if (total == 0) throw new IOException("Empty I2P certificate");
                output.getFD().sync();
            }
            try {
                Os.chmod(temporary.getAbsolutePath(), 0600);
                Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            } catch (ErrnoException failure) {
                throw new IOException("Cannot install I2P certificate", failure);
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private static final class NativeStream implements Transport {
        private final long stream;
        private final AtomicBoolean closed = new AtomicBoolean();

        NativeStream(long stream) {
            this.stream = stream;
        }

        @Override
        public int read(byte[] bytes, int timeoutMs) throws IOException {
            if (closed.get()) return -1;
            int count = I2pNative.read(stream, bytes, 0, bytes.length, timeoutMs);
            // Native timeout consumes the stream; retrying a dead handle cannot resume it.
            return count == 0 ? -1 : count;
        }

        @Override
        public void write(byte[] bytes, int length, int timeoutMs) throws IOException {
            int offset = 0;
            while (offset < length) {
                if (closed.get()) throw new IOException("I2P stream closed");
                int count = I2pNative.write(stream, bytes, offset, length - offset, timeoutMs);
                if (count <= 0 || count > length - offset)
                    throw new IOException("I2P stream write failed");
                offset += count;
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) I2pNative.closeStream(stream);
        }
    }
}
