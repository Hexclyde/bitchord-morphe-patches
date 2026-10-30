package app.bitchord.soulseek;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** The embedded Tor daemon and onion listener live only in :bitchord_tor. */
public final class TorService extends OverlayService {
    private final TorRuntime runtime = new TorRuntime();

    @Override
    protected String label() {
        return "Tor";
    }

    @Override
    protected String processSuffix() {
        return ":bitchord_tor";
    }

    @Override
    protected void startRuntime(File home) throws IOException {
        runtime.start(home);
    }

    @Override
    protected String runtimeStatus() {
        return runtime.status();
    }

    @Override
    protected String runtimeEndpoint() {
        return runtime.onionAddress();
    }

    @Override
    protected Transport connectRuntime(String endpoint, int timeoutMs) throws IOException {
        return new SocketStream(runtime.connect(endpoint, timeoutMs));
    }

    @Override
    protected Transport acceptRuntime(int timeoutMs) throws IOException {
        try {
            return new SocketStream(runtime.accept(timeoutMs));
        } catch (SocketTimeoutException timeout) {
            return null;
        }
    }

    @Override
    protected void stopRuntime() {
        runtime.close();
    }

    private static final class SocketStream implements Transport {
        final Socket socket;
        final InputStream input;
        final OutputStream output;

        SocketStream(Socket socket) throws IOException {
            this.socket = socket;
            try {
                socket.setSoTimeout(30000);
                input = socket.getInputStream();
                output = socket.getOutputStream();
            } catch (IOException failure) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                throw failure;
            }
        }

        @Override
        public int read(byte[] bytes, int timeoutMs) throws IOException {
            return input.read(bytes);
        }

        @Override
        public void write(byte[] bytes, int length, int timeoutMs) throws IOException {
            output.write(bytes, 0, length);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
