package app.bitchord.soulseek;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded in-memory provider data. Credentials and signed URLs are never journaled. */
public final class DebridModels {
    public static final long MAX_BYTES = 256L * 1024 * 1024;

    private DebridModels() {}

    public interface Cancellation {
        void check() throws IOException;

        void bind(Closeable resource) throws IOException;

        void clear(Closeable resource);

        default void checkDownloadUrl(String url) throws IOException {}
    }

    public interface Progress {
        void changed(long downloaded, long total);
    }

    public static final class Entry {
        public final String fileId, name;
        public final long size;

        public Entry(String fileId, String name, long size) throws IOException {
            if (fileId == null
                    || fileId.isEmpty()
                    || fileId.length() > 2048
                    || hasControl(fileId)
                    || name == null
                    || name.isEmpty()
                    || name.length() > 4096
                    || hasControl(name)
                    || size < -1) throw new IOException("Invalid provider file metadata.");
            this.fileId = fileId;
            this.name = name;
            this.size = size;
        }
    }

    public static final class Resolved {
        public final DebridProvider.Kind kind;
        public final String remoteId, source, state, message;
        public final List<Entry> entries;
        public final boolean ready;

        public Resolved(
                DebridProvider.Kind kind,
                String remoteId,
                String source,
                List<Entry> entries,
                String state,
                String message,
                boolean ready)
                throws IOException {
            if (kind == null
                    || remoteId == null
                    || remoteId.length() > 2048
                    || hasControl(remoteId)
                    || source == null
                    || source.length() > 8192
                    || hasControl(source)
                    || entries == null
                    || entries.size() > 1024
                    || state == null
                    || state.length() > 64
                    || message == null
                    || message.length() > 256) throw new IOException("Invalid provider response.");
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (Entry entry : entries)
                if (entry == null || !ids.add(entry.fileId))
                    throw new IOException("Duplicate provider file identity.");
            this.kind = kind;
            this.remoteId = remoteId;
            this.source = source;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.state = state;
            this.message = message;
            this.ready = ready;
        }

        public Entry entry(String id) throws IOException {
            for (Entry entry : entries) if (entry.fileId.equals(id)) return entry;
            throw new IOException("The selected provider file is no longer available.");
        }
    }

    public static final class Download {
        public final String url, name, sha256;
        public final long size;

        public Download(String url, String name, long size) throws IOException {
            this(url, name, size, "");
        }

        public Download(String url, String name, long size, String sha256) throws IOException {
            if (url == null
                    || url.isEmpty()
                    || url.length() > 16384
                    || hasControl(url)
                    || name == null
                    || name.length() > 4096
                    || hasControl(name)
                    || size < -1
                    || sha256 == null
                    || (!sha256.isEmpty() && !sha256.matches("[0-9a-fA-F]{64}")))
                throw new IOException("Invalid resolved download.");
            this.url = url;
            this.name = name;
            this.size = size;
            this.sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
        }
    }

    static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < 32 || text.charAt(i) == 127) return true;
        return false;
    }
}
