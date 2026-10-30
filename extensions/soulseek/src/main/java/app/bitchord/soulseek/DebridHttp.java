package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/** HTTPS API and download transport. API credentials never follow redirects to file hosts. */
public class DebridHttp {
    private static final int JSON_BYTES = 2 * 1024 * 1024;
    private static final int TIMEOUT = 20_000;

    public static Map<String, String> params(String... pairs) {
        if ((pairs.length & 1) != 0) throw new IllegalArgumentException("Expected parameter pairs");
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2)
            values.put(pairs[index], pairs[index + 1]);
        return values;
    }

    public static String segment(String text) throws IOException {
        return encode(text).replace("+", "%20");
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] form(Map<String, String> values) throws IOException {
        if (values == null) values = params();
        if (values.size() > 64) throw new IOException("Too many provider parameters.");
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> item : values.entrySet()) {
            if (item.getKey() == null
                    || !item.getKey().matches("[A-Za-z0-9_\\[\\].-]{1,64}")
                    || item.getValue() == null
                    || item.getValue().length() > 16384)
                throw new IOException("Invalid provider parameter.");
            if (out.length() > 0) out.append('&');
            out.append(encode(item.getKey())).append('=').append(encode(item.getValue()));
        }
        byte[] result = out.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > 65536) throw new IOException("Provider request is too large.");
        return result;
    }

    public JSONObject json(
            DebridProvider.Kind kind,
            String method,
            String path,
            String key,
            Map<String, String> parameters,
            DebridModels.Cancellation cancellation)
            throws IOException {
        byte[] body = form(parameters);
        if ("GET".equals(method)) {
            String query = new String(body, StandardCharsets.UTF_8);
            return api(
                    kind,
                    method,
                    path,
                    query.isEmpty() ? "" : "?" + query,
                    key,
                    null,
                    null,
                    cancellation);
        }
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method))
            throw new IOException("Unsupported provider request method.");
        return api(
                kind,
                method,
                path,
                "",
                key,
                body,
                "application/x-www-form-urlencoded; charset=UTF-8",
                cancellation);
    }

    public JSONObject jsonMultipart(
            DebridProvider.Kind kind,
            String path,
            String key,
            Map<String, String> parameters,
            DebridModels.Cancellation cancellation)
            throws IOException {
        form(parameters);
        String boundary = "bitchord-" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            output.write(
                    ("--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\""
                                    + entry.getKey()
                                    + "\"\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        if (output.size() > 65536) throw new IOException("Provider request is too large.");
        return api(
                kind,
                "POST",
                path,
                "",
                key,
                output.toByteArray(),
                "multipart/form-data; boundary=" + boundary,
                cancellation);
    }

    private JSONObject api(
            DebridProvider.Kind kind,
            String method,
            String path,
            String query,
            String key,
            byte[] body,
            String contentType,
            DebridModels.Cancellation cancellation)
            throws IOException {
        if (kind == null
                || path == null
                || !path.matches("/[A-Za-z0-9_./%~-]*")
                || path.contains("..")) throw new IOException("Invalid provider API path.");
        validateKey(key);
        cancellation.check();
        final HttpsURLConnection connection = open(kind.apiOrigin + path + query, cancellation);
        Closeable closer = connection::disconnect;
        cancellation.bind(closer);
        try {
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", contentType);
                try (OutputStream output = connection.getOutputStream()) {
                    cancellation.check();
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            cancellation.check();
            if (status >= 300 && status < 400)
                throw new IOException("The provider API redirected unexpectedly.");
            if (status == 401 || status == 403)
                throw new IOException(kind.label + " rejected this API key or account access.");
            if (status == 429)
                throw new IOException(
                        kind.label + " is rate limiting requests. Wait before retrying.");
            if (status < 200 || status >= 300)
                throw new IOException(kind.label + " API request failed (HTTP " + status + ").");
            if (status == 204) return new JSONObject();
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = bounded(input, JSON_BYTES, cancellation);
            }
            if (status == 202 && bytes.length == 0) return new JSONObject();
            try {
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception malformed) {
                throw new IOException(kind.label + " returned an invalid JSON response.");
            }
        } catch (IOException failure) {
            cancellation.check();
            if (safeMessage(failure.getMessage())) throw failure;
            throw new IOException(kind.label + " could not be reached securely. Retry later.");
        } finally {
            connection.disconnect();
            cancellation.clear(closer);
        }
    }

    private static boolean safeMessage(String value) {
        return value != null
                && (value.startsWith("The provider API ")
                        || value.contains(" rejected this API key ")
                        || value.contains(" is rate limiting requests.")
                        || value.contains(" API request failed (HTTP ")
                        || value.contains(" returned an invalid JSON response.")
                        || value.equals("Provider response is too large."));
    }

    public static void validateKey(String key) throws IOException {
        if (key == null
                || key.length() < 8
                || key.length() > 4096
                || !key.equals(key.trim())
                || DebridModels.hasControl(key))
            throw new IOException("Enter a valid provider API key.");
        for (int i = 0; i < key.length(); i++)
            if (key.charAt(i) < 33 || key.charAt(i) > 126)
                throw new IOException("Enter a valid provider API key.");
    }

    private static byte[] bounded(
            InputStream input, int max, DebridModels.Cancellation cancellation) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            cancellation.check();
            if (output.size() + read > max)
                throw new IOException("Provider response is too large.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /** Fetches a complete original. No provider API credentials are attached to this connection. */
    public long fetch(
            DebridModels.Download download,
            File target,
            DebridModels.Cancellation cancellation,
            DebridModels.Progress progress)
            throws IOException {
        if (download.size > DebridModels.MAX_BYTES)
            throw new IOException("Audio files are limited to 256 MiB.");
        String current = download.url;
        for (int redirects = 0; redirects <= 5; redirects++) {
            cancellation.check();
            cancellation.checkDownloadUrl(current);
            final HttpsURLConnection connection = open(current, cancellation);
            Closeable closer = connection::disconnect;
            cancellation.bind(closer);
            try {
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                cancellation.check();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 5)
                        throw new IOException("The download redirected too many times.");
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (status != 200)
                    throw new IOException("The download server refused the complete file.");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding))
                    throw new IOException("The download server changed the file encoding.");
                long length = connection.getContentLengthLong();
                if (length > DebridModels.MAX_BYTES
                        || (download.size >= 0 && length >= 0 && length != download.size))
                    throw new IOException("The download length does not match the selected file.");
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (Exception impossible) {
                    throw new IOException("File verification is unavailable.");
                }
                long written = 0;
                try (InputStream input = connection.getInputStream();
                        FileOutputStream output = new FileOutputStream(target, false)) {
                    Closeable streams =
                            () -> {
                                try {
                                    input.close();
                                } finally {
                                    output.close();
                                }
                            };
                    cancellation.bind(streams);
                    try {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            cancellation.check();
                            written += read;
                            if (written > DebridModels.MAX_BYTES
                                    || (download.size >= 0 && written > download.size))
                                throw new IOException(
                                        "The download exceeded the selected file size.");
                            if (target.getParentFile().getUsableSpace() < read + 32L * 1024 * 1024)
                                throw new IOException(
                                        "The download has insufficient private storage.");
                            output.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            progress.changed(written, download.size >= 0 ? download.size : length);
                        }
                        cancellation.check();
                        output.getFD().sync();
                    } finally {
                        cancellation.clear(streams);
                    }
                }
                if (written <= 0
                        || (download.size >= 0 && written != download.size)
                        || (length >= 0 && written != length))
                    throw new IOException(
                            "The download ended before the complete audio file arrived.");
                if (!download.sha256.isEmpty() && !hex(digest.digest()).equals(download.sha256))
                    throw new IOException("The completed file failed provider hash verification.");
                cancellation.check();
                return written;
            } catch (IOException failure) {
                cancellation.check();
                String message = failure.getMessage();
                if (message != null
                        && (message.startsWith("The download ")
                                || message.startsWith("The completed file "))) throw failure;
                throw new IOException(
                        "The download connection failed. Resume to restart this file.");
            } finally {
                connection.disconnect();
                cancellation.clear(closer);
            }
        }
        throw new IOException("The download redirected too many times.");
    }

    protected HttpsURLConnection open(String value, DebridModels.Cancellation cancellation)
            throws IOException {
        HttpsURLConnection connection = DebridTlsSockets.open(value, cancellation, TIMEOUT);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "BitChord/1.1.0-beta.5");
        return connection;
    }

    public static URL publicHttps(String value) throws IOException {
        return publicHttps(
                value,
                new DebridModels.Cancellation() {
                    public void check() throws IOException {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedIOException("URL validation interrupted.");
                    }

                    public void bind(Closeable resource) throws IOException {
                        check();
                    }

                    public void clear(Closeable resource) {}
                });
    }

    static URL publicHttps(String value, DebridModels.Cancellation cancellation)
            throws IOException {
        URL url = DebridTlsSockets.url(value);
        DebridTlsSockets.resolve(url.getHost(), cancellation, TIMEOUT, InetAddress::getAllByName);
        return url;
    }

    public static void rejectCredentialUrl(String value, String key) throws IOException {
        rejectCredentialText(value, key);
    }

    public static void rejectCredentialText(String value, String key) throws IOException {
        String decoded = value;
        for (int round = 0; round < 4; round++) {
            if (decoded.contains(key))
                throw new IOException("The provider returned unsafe credential-bearing metadata.");
            StringBuilder scan = new StringBuilder();
            for (int i = 0; i < decoded.length(); i++) {
                char current = decoded.charAt(i);
                if (current == '%' && i + 2 < decoded.length()) {
                    int high = Character.digit(decoded.charAt(i + 1), 16),
                            low = Character.digit(decoded.charAt(i + 2), 16);
                    if (high >= 0 && low >= 0) {
                        scan.append((char) ((high << 4) | low));
                        i += 2;
                        continue;
                    }
                }
                scan.append(current);
            }
            final String next = scan.toString();
            if (next.equals(decoded)) return;
            decoded = next;
        }
        if (decoded.contains(key))
            throw new IOException("The provider returned unsafe credential-bearing metadata.");
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return public4(bytes, 0);
        if (bytes.length != 16) return false;
        int first = bytes[0] & 255, second = bytes[1] & 255;
        if (first == 0x20 && second == 0x01 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
            return false;
        if (first == 0 && second == 0x64 && (bytes[2] & 255) == 0xff && (bytes[3] & 255) == 0x9b) {
            for (int i = 4; i < 12; i++) if (bytes[i] != 0) return false;
            return public4(bytes, 12);
        }
        return (first & 0xe0) == 0x20;
    }

    private static boolean public4(byte[] b, int offset) {
        int a = b[offset] & 255, c = b[offset + 1] & 255, d = b[offset + 2] & 255;
        return !(a == 0
                || a == 10
                || a == 127
                || a >= 224
                || (a == 100 && c >= 64 && c <= 127)
                || (a == 169 && c == 254)
                || (a == 172 && c >= 16 && c <= 31)
                || (a == 192 && c == 168)
                || (a == 192 && c == 0)
                || (a == 192 && c == 2)
                || (a == 198 && (c == 18 || c == 19))
                || (a == 198 && c == 51 && d == 100)
                || (a == 203 && c == 0 && d == 113));
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
