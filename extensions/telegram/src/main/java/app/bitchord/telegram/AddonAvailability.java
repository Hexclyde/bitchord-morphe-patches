package app.bitchord.telegram;

import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shares a failing add-on server's pause across its search, manifest and stream requests. */
public final class AddonAvailability {
    private AddonAvailability() {}

    private static final Map<String, EndpointCooldown> pauses = new LinkedHashMap<>();
    static EndpointCooldown.Clock clock = () -> System.nanoTime() / 1_000_000L;

    private static String origin(String address) {
        try {
            URI uri = new URI(address);
            String host = uri.getHost(), scheme = uri.getScheme();
            if (host == null || scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return null;
            int port = uri.getPort();
            if (port == -1) port = scheme.equals("https") ? 443 : 80;
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (Exception invalid) {
            return null;
        }
    }

    public static void before(String address) throws IOException {
        String key = origin(address);
        synchronized (pauses) {
            EndpointCooldown state = pauses.get(key);
            long remaining = state == null ? 0 : state.remaining();
            if (remaining > 0)
                throw new IOException(
                        "Add-on temporarily unavailable; retry in "
                                + (remaining + 999) / 1000
                                + " seconds");
        }
    }

    /** Observes the response without consuming or closing the body owned by the host. */
    public static void response(Response response) {
        if (response == null || response.request == null || response.request.url == null) return;
        observe(
                response.request.url.toString(),
                response.code,
                response.headers == null ? null : response.headers.get("Retry-After"));
    }

    static void observe(String address, int code, String retryAfter) {
        String key = origin(address);
        if (key == null) return;
        synchronized (pauses) {
            EndpointCooldown state = pauses.get(key);
            if (code == 503) {
                if (state == null) {
                    if (pauses.size() >= 64) pauses.remove(pauses.keySet().iterator().next());
                    state = new EndpointCooldown(clock);
                    pauses.put(key, state);
                }
                state.failed(retryMillis(retryAfter));
            } else if (code >= 200 && code < 300 && state != null && state.remaining() == 0) {
                // An older in-flight success cannot clear a newly imposed server pause.
                state.succeeded();
            }
        }
    }

    static long retryMillis(String value) {
        if (value == null) return 0;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? 0 : Math.min(seconds, 3600) * 1000L;
        } catch (NumberFormatException date) {
            try {
                long until =
                        java.time.ZonedDateTime.parse(
                                        value.trim(),
                                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant()
                                .toEpochMilli();
                return Math.max(0, Math.min(3_600_000L, until - System.currentTimeMillis()));
            } catch (RuntimeException invalid) {
                return 0;
            }
        }
    }

    static void reset() {
        synchronized (pauses) {
            pauses.clear();
        }
    }
}
