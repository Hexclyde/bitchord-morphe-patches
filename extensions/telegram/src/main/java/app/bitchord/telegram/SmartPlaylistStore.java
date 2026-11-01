package app.bitchord.telegram;

import org.json.*;

/**
 * Service details kept as one encrypted record, with no plaintext fallback. A saved key belongs to
 * its service: changing the origin drops it, so a typo can never send it to another host.
 */
final class SmartPlaylistStore {
    private static final String RECORD = "smart_playlist_service";

    interface Secrets {
        String get(String name) throws Exception;

        void put(String name, String value) throws Exception;
    }

    /** Safe to show: holds no credential. */
    static final class Config {
        final String endpoint, host, model;
        final boolean hasKey;

        Config(String endpoint, String host, String model, boolean hasKey) {
            this.endpoint = endpoint;
            this.host = host;
            this.model = model;
            this.hasKey = hasKey;
        }
    }

    static final class Credentials {
        final SmartPlaylistService.Endpoint endpoint;
        final String model, key;

        Credentials(SmartPlaylistService.Endpoint endpoint, String model, String key) {
            this.endpoint = endpoint;
            this.model = model;
            this.key = key;
        }
    }

    private final Secrets secrets;

    SmartPlaylistStore(Secrets secrets) {
        this.secrets = secrets;
    }

    /** Null when nothing is saved; throws when a saved record cannot be read. */
    Config load() throws Exception {
        Credentials saved = credentials();
        if (saved == null) return null;
        return new Config(
                saved.endpoint.chatUrl, saved.endpoint.host, saved.model, !saved.key.isEmpty());
    }

    Credentials credentials() throws Exception {
        String stored = secrets.get(RECORD);
        if (stored == null) return null;
        JSONObject record = new JSONObject(stored);
        return new Credentials(
                SmartPlaylistService.endpoint(record.getString("endpoint")),
                SmartPlaylistService.model(record.getString("model")),
                SmartPlaylistService.key(record.optString("key", "")));
    }

    /** The saved key, only if it was entered for this same origin. */
    String keyFor(SmartPlaylistService.Endpoint endpoint) throws Exception {
        Credentials saved = credentials();
        return saved != null && saved.endpoint.origin.equals(endpoint.origin) ? saved.key : "";
    }

    /** A null key keeps the existing one for the same origin and drops it for any other. */
    void save(String rawEndpoint, String rawModel, String rawKey) throws Exception {
        SmartPlaylistService.Endpoint endpoint = SmartPlaylistService.endpoint(rawEndpoint);
        String model = SmartPlaylistService.model(rawModel);
        String key;
        if (rawKey != null) key = SmartPlaylistService.key(rawKey);
        else {
            try {
                key = keyFor(endpoint);
            } catch (Exception unreadable) {
                key = "";
            }
        }
        secrets.put(
                RECORD,
                new JSONObject()
                        .put("endpoint", endpoint.chatUrl)
                        .put("model", model)
                        .put("key", key)
                        .toString());
    }

    void disconnect() throws Exception {
        secrets.put(RECORD, null);
    }
}
