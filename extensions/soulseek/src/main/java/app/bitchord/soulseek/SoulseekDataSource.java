package app.bitchord.soulseek;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Keeps download-only identifiers out of the player and its HTTP data source. */
public final class SoulseekDataSource {
    private SoulseekDataSource() {}

    public static DataSource wrap(DataSource downstream) {
        return new Routing(downstream);
    }

    private static final class Routing implements DataSource {
        private final DataSource downstream;
        private boolean active;

        Routing(DataSource downstream) {
            if (downstream == null) throw new NullPointerException("downstream");
            this.downstream = downstream;
        }

        public void addTransferListener(TransferListener listener) {
            downstream.addTransferListener(listener);
        }

        public long open(DataSpec spec) throws IOException {
            if (active) throw new IOException("Data source is already open");
            if (SoulseekAddon.reserved(spec.uri.toString()))
                throw new IOException(
                        "Soulseek is download-only. Use Download to save this track.");
            active = true;
            try {
                return downstream.open(spec);
            } catch (IOException | RuntimeException error) {
                active = false;
                try {
                    downstream.close();
                } catch (IOException | RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!active) throw new IOException("Data source is not open");
            return downstream.read(buffer, offset, length);
        }

        public Uri getUri() {
            return active ? downstream.getUri() : null;
        }

        public Map<String, List<String>> getResponseHeaders() {
            return active ? downstream.getResponseHeaders() : Collections.emptyMap();
        }

        public void close() throws IOException {
            if (!active) return;
            active = false;
            downstream.close();
        }
    }
}
