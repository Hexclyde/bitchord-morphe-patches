package app.bitchord.soulseek;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Bounded STUN configuration. Parsing and selection never resolve hostnames or open sockets. */
final class StunServers {
    static final String SOURCE_URL = "https://gist.github.com/mondain/b0ec1cf5f60ae726202e";
    static final String SNAPSHOT_REVISION = "2d2b96b4508a38d342e0228d46eab84dad2398a3";
    static final int MAX_TEXT_CHARS = 65536;
    static final int MAX_CANDIDATES = 512;
    static final int MAX_SELECTED = 3;
    private static final int MAX_LINES = 1024;
    private static final int MAX_LINE_CHARS = 300;
    private static final List<String> DEFAULTS =
            Collections.unmodifiableList(
                    Arrays.asList("stun:stun.l.google.com:19302", "stun:stun.cloudflare.com:3478"));

    private StunServers() {}

    /** Candidate defaults; availability is determined by the connection's ICE checks. */
    static List<String> defaults() {
        return DEFAULTS;
    }

    /** Imports raw gist-style lines, ignoring malformed entries and rejecting oversized inputs. */
    static List<String> parse(String raw) throws IOException {
        if (raw == null || raw.length() > MAX_TEXT_CHARS)
            throw new IOException("STUN list exceeds the import limit");
        Set<String> candidates = new LinkedHashSet<>();
        int lines = 0;
        int start = 0;
        while (start < raw.length()) {
            if (++lines > MAX_LINES) throw new IOException("STUN list has too many lines");
            int end = raw.indexOf('\n', start);
            if (end < 0) end = raw.length();
            if (end - start <= MAX_LINE_CHARS) {
                String line = raw.substring(start, end);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (line.isEmpty() || line.startsWith("#")) {
                    start = end + 1;
                    continue;
                }
                try {
                    candidates.add(normalize(line));
                } catch (IOException ignored) {
                    // An invalid entry does not discard the rest of an imported list.
                }
                if (candidates.size() > MAX_CANDIDATES)
                    throw new IOException("STUN list has too many servers");
            }
            start = end + 1;
        }
        return Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    /** Selects distinct hosts, also grouping known operators' multiple hostnames. */
    static List<String> select(List<String> candidates, Random random) throws IOException {
        if (candidates == null || candidates.size() > MAX_CANDIDATES || random == null)
            throw new IOException("Invalid STUN candidate selection");
        List<String> shuffled = new ArrayList<>();
        for (String candidate : candidates) shuffled.add(normalize(candidate));
        Collections.shuffle(shuffled, random);
        List<String> selected = new ArrayList<>();
        Set<String> providers = new HashSet<>();
        for (String candidate : shuffled) {
            String host = host(candidate);
            String provider = knownProvider(host);
            if (providers.add(provider)) selected.add(candidate);
            if (selected.size() == MAX_SELECTED) break;
        }
        return Collections.unmodifiableList(selected);
    }

    private static String normalize(String line) throws IOException {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE_CHARS)
            throw new IOException("Invalid STUN server");
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value < 33 || value > 126) throw new IOException("Invalid STUN server");
        }
        String endpoint = line;
        if (endpoint.regionMatches(true, 0, "stun:", 0, 5)) endpoint = endpoint.substring(5);
        if (endpoint.startsWith("//") || endpoint.indexOf('%') >= 0)
            throw new IOException("Invalid STUN server");
        String port = endpoint.substring(endpoint.lastIndexOf(':') + 1);
        if (!port.matches("[1-9][0-9]{0,4}")) throw new IOException("Invalid STUN port");
        try {
            URI uri = new URI("stun://" + endpoint);
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() < 1
                    || uri.getPort() > 65535) throw new IOException("Invalid STUN server");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.charAt(0) == '[') {
                String literal = host.substring(1, host.length() - 1);
                // The URI parser has already validated this as a numeric IPv6 literal.
                InetAddress address = InetAddress.getByName(literal);
                if (address.getAddress().length != 16)
                    throw new IOException("Use an IPv4 literal without brackets");
                host = "[" + address.getHostAddress().toLowerCase(Locale.ROOT) + "]";
            } else {
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                if (host.length() > 253 || host.indexOf('.') < 0)
                    throw new IOException("Use a fully qualified STUN hostname");
                if (host.matches("[0-9.]+")) {
                    String[] octets = host.split("\\.", -1);
                    if (octets.length != 4) throw new IOException("Invalid IPv4 literal");
                    for (String octet : octets) {
                        if (octet.isEmpty()
                                || octet.length() > 3
                                || (octet.length() > 1 && octet.charAt(0) == '0')
                                || Integer.parseInt(octet) > 255)
                            throw new IOException("Invalid IPv4 literal");
                    }
                }
            }
            return "stun:" + host + ":" + uri.getPort();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Invalid STUN server");
        }
    }

    private static String host(String candidate) {
        return candidate.substring(5, candidate.lastIndexOf(':'));
    }

    private static String knownProvider(String host) {
        if (host.equals("google.com") || host.endsWith(".google.com")) return "google.com";
        if (host.equals("cloudflare.com") || host.endsWith(".cloudflare.com"))
            return "cloudflare.com";
        return host;
    }
}
