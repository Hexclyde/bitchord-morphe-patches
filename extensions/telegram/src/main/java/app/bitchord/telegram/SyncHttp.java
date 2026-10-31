package app.bitchord.telegram;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Bounded JSON transport. Mutation errors are never automatically retried. */
final class SyncHttp {
    static final class Response {
        final int status;
        final JSONObject json;
        final long retryAfter;

        Response(int status, JSONObject json, long retryAfter) {
            this.status = status;
            this.json = json;
            this.retryAfter = retryAfter;
        }
    }

    interface Transport {
        Response request(String method, String url, String token, String contentType, String body)
                throws Exception;
    }

    static final Transport NETWORK =
            (method, url, token, type, body) -> {
                URL endpoint = new URL(url);
                if (!endpoint.getProtocol().equals("https"))
                    throw new IOException("Sync requires HTTPS");
                HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept", "application/json");
                if (token != null)
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                try {
                    if (body != null) {
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", type);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        connection.setFixedLengthStreamingMode(bytes.length);
                        try (OutputStream output = connection.getOutputStream()) {
                            output.write(bytes);
                        }
                    }
                    int code = connection.getResponseCode();
                    long retry = 0;
                    try {
                        retry = Long.parseLong(connection.getHeaderField("Retry-After"));
                    } catch (Exception ignored) {
                    }
                    InputStream input =
                            code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (input != null)
                        try (InputStream source = input) {
                            byte[] block = new byte[8192];
                            int count;
                            while ((count = source.read(block)) != -1) {
                                if (bytes.size() + count > 8 * 1024 * 1024)
                                    throw new IOException("Service response exceeded the limit");
                                bytes.write(block, 0, count);
                            }
                        }
                    String raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    JSONObject json = raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
                    return new Response(code, json, retry);
                } finally {
                    connection.disconnect();
                }
            };

    static String form(String... values) throws Exception {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (i > 0) out.append('&');
            out.append(URLEncoder.encode(values[i], "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(values[i + 1], "UTF-8"));
        }
        return out.toString();
    }
}
