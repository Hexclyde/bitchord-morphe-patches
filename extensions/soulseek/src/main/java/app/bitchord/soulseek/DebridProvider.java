package app.bitchord.soulseek;

import java.io.IOException;
import java.util.List;

/** Provider-specific account and link resolution; audio publication is shared. */
public interface DebridProvider {
    enum Kind {
        REAL_DEBRID("real-debrid", "Real-Debrid", "https://api.real-debrid.com"),
        ALLDEBRID("alldebrid", "AllDebrid", "https://api.alldebrid.com"),
        PREMIUMIZE("premiumize", "Premiumize", "https://www.premiumize.me"),
        TORBOX("torbox", "TorBox", "https://api.torbox.app");
        public final String id, label, apiOrigin;

        Kind(String id, String label, String apiOrigin) {
            this.id = id;
            this.label = label;
            this.apiOrigin = apiOrigin;
        }

        public static Kind fromId(String id) throws IOException {
            for (Kind value : values()) if (value.id.equals(id)) return value;
            throw new IOException("Unknown debrid service.");
        }
    }

    Kind kind();

    void validate(String key, DebridHttp http, DebridModels.Cancellation cancellation)
            throws IOException;

    DebridModels.Resolved resolve(
            String input, String key, DebridHttp http, DebridModels.Cancellation cancellation)
            throws IOException;

    DebridModels.Resolved refresh(
            DebridModels.Resolved resolved,
            String key,
            DebridHttp http,
            DebridModels.Cancellation cancellation)
            throws IOException;

    default DebridModels.Resolved select(
            DebridModels.Resolved resolved,
            List<String> fileIds,
            String key,
            DebridHttp http,
            DebridModels.Cancellation cancellation)
            throws IOException {
        return resolved;
    }

    DebridModels.Download download(
            DebridModels.Resolved resolved,
            String fileId,
            String key,
            DebridHttp http,
            DebridModels.Cancellation cancellation)
            throws IOException;
}
