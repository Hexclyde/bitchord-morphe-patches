package app.bitchord.soulseek;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Bounded configuration for public relays and explicitly paired app identities. */
final class NostrConfig {
    private static final int MAX_TEXT = 2048;

    private NostrConfig() {}

    static List<String> relays(String text) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String entry : entries(text)) {
            result.add(relay(entry));
            if (result.size() > 3) throw new IOException("Configure at most three relays.");
        }
        if (result.isEmpty()) throw new IOException("Enter at least one public WSS relay.");
        return immutable(result);
    }

    static List<String> peers(String text) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String entry : entries(text)) {
            if (!entry.matches("[0-9a-fA-F]{64}"))
                throw new IOException("Each peer key must contain 64 hexadecimal characters.");
            result.add(entry.toLowerCase(Locale.ROOT));
            if (result.size() > 16) throw new IOException("Configure at most sixteen peer keys.");
        }
        if (result.isEmpty()) throw new IOException("Enter at least one trusted peer key.");
        return immutable(result);
    }

    static String lines(List<String> values) {
        return String.join("\n", values);
    }

    private static List<String> entries(String text) throws IOException {
        if (text == null || text.length() > MAX_TEXT)
            throw new IOException("Configuration must contain at most 2048 characters.");
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '\n') continue;
            if (value == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') continue;
            if (Character.isISOControl(value) || Character.getType(value) == Character.FORMAT)
                throw new IOException("Configuration contains an unsupported control character.");
        }
        List<String> result = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            String entry = line.trim();
            if (!entry.isEmpty()) result.add(entry);
        }
        return result;
    }

    private static String relay(String entry) throws IOException {
        if (entry.length() > 512) throw new IOException("A relay address is too long.");
        final URI uri;
        try {
            uri = new URI(entry);
        } catch (URISyntaxException e) {
            throw new IOException("Enter a valid WSS relay address.");
        }
        String host = uri.getHost();
        if (!"wss".equalsIgnoreCase(uri.getScheme())
                || uri.isOpaque()
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IOException(
                    "Use a public WSS relay on port 443 without login or query details.");
        host = host.toLowerCase(Locale.ROOT);
        if (!publicHostname(host))
            throw new IOException("Relay addresses must use a public DNS hostname.");
        String authority = uri.getRawAuthority();
        if (!authority.equalsIgnoreCase(host) && !authority.equalsIgnoreCase(host + ":443"))
            throw new IOException("Enter a valid WSS relay address.");
        String path;
        try {
            path = new URI(uri.toASCIIString()).getRawPath();
        } catch (URISyntaxException e) {
            throw new IOException("Enter a valid WSS relay path.");
        }
        if (path == null || path.equals("/")) path = "";
        String lowerPath = path.toLowerCase(Locale.ROOT);
        for (int i = 0; i + 2 < lowerPath.length(); i++) {
            if (lowerPath.charAt(i) != '%') continue;
            int code =
                    Character.digit(lowerPath.charAt(i + 1), 16) * 16
                            + Character.digit(lowerPath.charAt(i + 2), 16);
            if (code < 32 || code == 127)
                throw new IOException("Relay paths cannot contain encoded control characters.");
        }
        return "wss://" + host + path;
    }

    private static boolean publicHostname(String host) {
        if (host.length() > 253 || host.indexOf('.') < 0 || host.matches("[0-9.]+")) return false;
        for (String suffix :
                new String[] {".local", ".localhost", ".internal", ".invalid", ".test"})
            if (host.endsWith(suffix)) return false;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        }
        return true;
    }

    private static List<String> immutable(LinkedHashSet<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
