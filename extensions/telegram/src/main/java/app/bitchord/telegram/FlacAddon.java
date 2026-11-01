package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Independent BitChord adapter for the protocol observed in aztecempire's Spotube plugin. No Hetu
 * code or .smplug runtime is bundled. The host supplies its IO coroutine and player.
 */
public final class FlacAddon {
    public static final String ID = "flacdownloader-native";
    static final String ORIGIN = "https://flac.bitchord.invalid";
    static final String BACKEND = "https://flacdownloader.com";
    static final int[] FORMATS = {27, 7, 6, 5};
    static final int MAX_JSON = 2 * 1024 * 1024;

    interface Transport {
        JSONObject request(String path, JSONObject body, String token, long deadline)
                throws Exception;
    }

    interface Pause {
        void waitFor(long millis) throws InterruptedException;
    }

    static Transport transport = FlacAddon::http;
    static Pause pause = Thread::sleep;

    interface SessionStore {
        String read() throws Exception;

        void write(String value) throws Exception;
    }

    static volatile String providerSession;
    static SessionStore sessions =
            new SessionStore() {
                public String read() throws Exception {
                    android.content.Context app = SoundExtras.app();
                    return app == null ? null : new SyncSecrets(app).get("flacdownloader.session");
                }

                public void write(String value) throws Exception {
                    android.content.Context app = SoundExtras.app();
                    if (app == null) throw new IOException("Playback service is not ready");
                    new SyncSecrets(app).put("flacdownloader.session", value);
                }
            };

    public static boolean needsVerification() {
        return providerSession == null || providerSession.isEmpty();
    }

    public static void verify(android.app.Activity activity) {
        activity.startActivity(
                new android.content.Intent(activity, FlacVerificationActivity.class));
    }

    static final class VerificationRequired extends IOException {
        VerificationRequired() {
            super("Verify flacdownloader.com in provider setup, then retry this file.");
        }
    }

    static String session() throws Exception {
        if (providerSession == null) providerSession = sessions.read();
        if (providerSession == null || providerSession.isEmpty()) throw new VerificationRequired();
        return providerSession;
    }

    static void acceptSession(String pass) throws Exception {
        if (pass == null
                || pass.isEmpty()
                || pass.length() > 8192
                || pass.contains("\r")
                || pass.contains("\n")) throw new VerificationRequired();
        long deadline = System.nanoTime() + 20_000_000_000L;
        JSONObject proof = request("/prepare", new JSONObject().put("sp", pass), "", deadline);
        if (proof.optString("t").isEmpty()) throw new VerificationRequired();
        requireEnabled();
        sessions.write(pass);
        providerSession = pass;
    }

    private FlacAddon() {}

    public static boolean reserved(String value) {
        try {
            return "flac.bitchord.invalid".equalsIgnoreCase(new URI(value).getHost());
        } catch (Exception invalid) {
            return false;
        }
    }

    /** Registered off on first installation; preserve order and the user's native source switch. */
    public static void init() {
        try {
            register();
        } catch (Exception | LinkageError unavailable) {
            HostLog.d("Flac Downloader Audio could not register");
        }
    }

    static void register() throws Exception {
        Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
        Class<?> config = Class.forName("com.music.bitchord.data.sources.SourceConfig");
        Class<?> kind = Class.forName("com.music.bitchord.data.sources.SourceKind");
        Object flow = registry.getField("configs").get(null);
        List<?> current =
                (List<?>)
                        Class.forName("kotlinx.coroutines.flow.StateFlow")
                                .getMethod("getValue")
                                .invoke(flow);
        ArrayList<Object> next = new ArrayList<>();
        boolean found = false;
        for (Object row : current) {
            if (!ID.equals(config.getMethod("getId").invoke(row))) {
                next.add(row);
                continue;
            }
            if (found) continue;
            found = true;
            if ((ORIGIN).equals(config.getMethod("getBaseUrl").invoke(row))) return;
            next.add(
                    config.getConstructor(
                                    String.class,
                                    kind,
                                    String.class,
                                    String.class,
                                    boolean.class,
                                    boolean.class,
                                    boolean.class)
                            .newInstance(
                                    ID,
                                    kind.getField("ADDON").get(null),
                                    "Flac Downloader Audio",
                                    ORIGIN,
                                    config.getMethod("getEnabled").invoke(row),
                                    true,
                                    false));
        }
        if (!found)
            next.add(
                    config.getConstructor(
                                    String.class,
                                    kind,
                                    String.class,
                                    String.class,
                                    boolean.class,
                                    boolean.class,
                                    boolean.class)
                            .newInstance(
                                    ID,
                                    kind.getField("ADDON").get(null),
                                    "Flac Downloader Audio",
                                    ORIGIN,
                                    false,
                                    true,
                                    false));
        registry.getMethod("publish", ArrayList.class, boolean.class).invoke(null, next, true);
    }

    public static boolean enabled() {
        if (!SoundExtras.flacAddonApplied()) return false;
        try {
            Object row =
                    Class.forName("com.music.bitchord.data.sources.SourceRegistry")
                            .getMethod("config", String.class)
                            .invoke(null, ID);
            return row != null
                    && Boolean.TRUE.equals(row.getClass().getMethod("getEnabled").invoke(row));
        } catch (Exception | LinkageError unavailable) {
            return false;
        }
    }

    static void setEnabled(boolean on) throws Exception {
        Class.forName("com.music.bitchord.data.sources.SourceRegistry")
                .getMethod("setEnabled", String.class, boolean.class)
                .invoke(null, ID, on);
        if (enabled() != on) throw new IOException("Could not change Flac Downloader Audio source");
    }

    static void requireEnabled() throws IOException {
        if (!enabled()) throw new IOException("Flac Downloader Audio is disabled");
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Source request cancelled");
    }

    public static String bodyOrNull(String value) throws Exception {
        if (!reserved(value)) return null;
        URI uri = new URI(value);
        if (!"https".equals(uri.getScheme())
                || !"flac.bitchord.invalid".equals(uri.getRawAuthority())
                || uri.getRawFragment() != null)
            throw new IOException("Invalid FLAC source identifier");
        String path = uri.getPath();
        if ("/manifest.json".equals(path))
            return new JSONObject()
                    .put("id", ID)
                    .put("name", "Flac Downloader Audio")
                    .put("version", "1.1.0-beta.17")
                    .put("resources", new JSONArray().put("search").put("stream"))
                    .put(
                            "description",
                            "Independent BitChord adapter · flacdownloader.com · formats requested,"
                                    + " not file-verified")
                    .put("allowDownloads", true)
                    .put("checkValidLossless", false)
                    .toString();
        requireEnabled();
        Map<String, String> query = query(uri);
        long deadline =
                System.nanoTime() + ("/search".equals(path) ? 45_000_000_000L : 180_000_000_000L);
        if ("/search".equals(path))
            return search(
                            query.getOrDefault("q", ""),
                            query.getOrDefault("quality", "LOSSLESS"),
                            deadline)
                    .toString();
        if (path != null && path.startsWith("/stream/"))
            return resolve(path.substring(8), deadline).toString();
        throw new IOException("Unknown FLAC source operation");
    }

    static Map<String, String> query(URI uri) throws Exception {
        Map<String, String> result = new HashMap<>();
        if (uri.getRawQuery() != null)
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], "UTF-8");
                if (result.put(key, URLDecoder.decode(pair.length == 2 ? pair[1] : "", "UTF-8"))
                        != null) throw new IOException("Duplicate source parameter");
            }
        return result;
    }

    static JSONObject search(String query, String quality, long deadline) throws Exception {
        JSONArray rows = new JSONArray();
        query = query.trim();
        if (query.isEmpty()) return new JSONObject().put("tracks", rows);
        if (query.length() > 512) throw new IOException("Search query is too long");
        if (providerSession == null) {
            try {
                providerSession = sessions.read();
            } catch (Exception unavailable) {
                providerSession = "";
            }
        }
        JSONObject answer = request("/search?q=" + enc(query) + "&index=0", null, "", deadline);
        JSONArray tracks = answer.optJSONArray("tracks");
        if (tracks == null) throw new IOException("FLAC catalogue response is unavailable");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tracks.length() && seen.size() < 8; i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            String url = track.optString("link"),
                    title = track.optString("title"),
                    artist = track.optString("artist");
            if (title.trim().isEmpty()
                    || title.length() > 512
                    || artist.length() > 512
                    || !primaryUrl(url)
                    || !seen.add(url)) continue;
            for (String format : new String[] {"flac", "mp3_320", "mp3_128"}) {
                if ("HIGH".equalsIgnoreCase(quality) && !format.equals("mp3_320")) continue;
                if ("LOW".equalsIgnoreCase(quality) && !format.equals("mp3_128")) continue;
                String raw =
                        new JSONObject().put("u", url).put("t", title).put("a", artist).toString();
                String id =
                        format
                                + "."
                                + Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                if (unavailableFormats.contains(id)) continue;
                JSONObject row =
                        new JSONObject()
                                .put("id", id)
                                .put("title", title)
                                .put("artist", artist)
                                .put("album", track.optString("album"))
                                .put("duration", Math.max(0, track.optLong("duration")))
                                .put("format", format.equals("flac") ? "flac" : "mp3")
                                .put("audioQuality", label(format))
                                .put("formatVerified", false);
                String art = track.optString("cover");
                if (art.startsWith("https://")) row.put("artworkURL", art);
                rows.put(row);
            }
        }
        return new JSONObject().put("tracks", rows);
    }

    private static final Set<String> unavailableFormats =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static boolean primaryUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && ("www.deezer.com".equals(uri.getHost())
                            || "deezer.com".equals(uri.getHost()))
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getPath().matches("/(?:[a-z]{2}/)?track/[0-9]+/?");
        } catch (Exception invalid) {
            return false;
        }
    }

    static String label(String format) {
        switch (format) {
            case "flac":
                return "FLAC";
            case "mp3_320":
                return "MP3 320 kbps";
            case "mp3_128":
                return "MP3 128 kbps";
            default:
                return label(Integer.parseInt(format));
        }
    }

    static boolean catalogueUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return value.length() < 1024
                    && "https".equals(uri.getScheme())
                    && host != null
                    && (host.equals("qobuz.com") || host.endsWith(".qobuz.com"))
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getPath() != null
                    && uri.getPath().matches("/(?:[a-z]{2}-[a-z]{2}/)?track/[A-Za-z0-9]+/?");
        } catch (Exception invalid) {
            return false;
        }
    }

    static String identity(String url, String title, String artist, int format) throws Exception {
        String raw = new JSONObject().put("u", url).put("t", title).put("a", artist).toString();
        return format
                + "."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject decode(String id) throws Exception {
        if (id.length() > 7000 || !id.matches("(?:27|7|6|5|flac|mp3_320|mp3_128)\\.[A-Za-z0-9_-]+"))
            throw new IOException("Invalid FLAC track reference");
        JSONObject row =
                new JSONObject(
                        new String(
                                Base64.getUrlDecoder().decode(id.substring(id.indexOf('.') + 1)),
                                StandardCharsets.UTF_8));
        if (!(catalogueUrl(row.optString("u")) || primaryUrl(row.optString("u")))
                || row.optString("t").trim().isEmpty()
                || row.optString("t").length() > 512
                || row.optString("a").length() > 512)
            throw new IOException("Invalid FLAC track metadata");
        return row;
    }

    static String label(int format) {
        switch (format) {
            case 27:
                return "FLAC 24-bit / 192 kHz";
            case 7:
                return "FLAC 24-bit / 96 kHz";
            case 6:
                return "FLAC 16-bit / 44.1 kHz";
            case 5:
                return "MP3 320 kbps";
            default:
                throw new IllegalArgumentException("Unsupported format");
        }
    }

    /**
     * Download explorer uses the exact format encoded in the source ID, rather than a tier badge.
     */
    public static String choiceLabel(String reference) {
        String prefix = "src:" + ID + "::";
        if (reference == null || !reference.startsWith(prefix)) return "";
        try {
            String id = reference.substring(prefix.length());
            decode(id);
            return label(id.substring(0, id.indexOf('.')))
                    + " · requested, availability unverified";
        } catch (Exception invalid) {
            return "";
        }
    }

    static JSONObject resolve(String id, long deadline) throws Exception {
        JSONObject row = decode(id);
        String format = id.substring(0, id.indexOf('.'));
        if (catalogueUrl(row.getString("u"))) return legacyResolve(id, deadline);
        if (!Arrays.asList("flac", "mp3_320", "mp3_128").contains(format))
            throw new IOException("Invalid provider format");
        String token =
                request("/prepare", new JSONObject().put("sp", session()), "", deadline)
                        .optString("t");
        if (token.isEmpty()
                || token.length() > 8192
                || token.contains("\r")
                || token.contains("\n")) throw new VerificationRequired();
        JSONObject job =
                request(
                        "/download",
                        new JSONObject()
                                .put("url", row.getString("u"))
                                .put("title", row.getString("t"))
                                .put("artist", row.optString("a"))
                                .put("format", format)
                                .put("accept_fallback", false),
                        token,
                        deadline);
        String jobId = job.optString("job_id");
        if (!jobId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("Provider preparation job unavailable");
        while (System.nanoTime() < deadline) {
            JSONObject state = request("/status/" + jobId, null, "", deadline);
            String status = state.optString("status");
            String actual = state.optString("format", state.optString("quality"));
            JSONObject mirror = state.optJSONObject("mirror");
            if (mirror != null && !mirror.optString("calidad").isEmpty())
                actual = mirror.optString("calidad");
            if (actual.equals("mp3")) actual = "mp3_320";
            if ((!actual.isEmpty() && !actual.equals(format)) || state.optBoolean("fallback")) {
                if (unavailableFormats.size() < 512) unavailableFormats.add(id);
                throw new IOException(
                        "Selected provider format is unavailable. Choose another format"
                                + " explicitly.");
            }
            if (status.equals("done")) {
                String file = state.optString("filename");
                String extension = format.equals("flac") ? ".flac" : ".mp3";
                if (!file.matches("[A-Za-z0-9][A-Za-z0-9_. -]{0,255}")
                        || file.contains("..")
                        || !file.toLowerCase(Locale.ROOT).endsWith(extension))
                    throw new IOException("Provider returned a different file format");
                return new JSONObject()
                        .put(
                                "url",
                                BACKEND
                                        + "/file/"
                                        + enc(file)
                                        + "?name="
                                        + enc(state.optString("pretty_name", file)))
                        .put("format", format.equals("flac") ? "flac" : "mp3")
                        .put("codec", format.equals("flac") ? "flac" : "mp3")
                        .put("container", format.equals("flac") ? "flac" : "mp3")
                        .put("encrypted", false)
                        .put("manifest", "none")
                        .put(
                                "headers",
                                new JSONObject()
                                        .put("User-Agent", "BitChord/1.9")
                                        .put("Referer", BACKEND + "/"))
                        .put("audioQuality", label(format))
                        .put("formatVerified", false);
            }
            if (Arrays.asList("error", "failed", "cancelled").contains(status)) {
                throw new IOException("Provider preparation failed. Retry the selected format.");
            }
            if (!Arrays.asList(
                            "queued",
                            "pending",
                            "processing",
                            "downloading",
                            "running",
                            "converting",
                            "mirror")
                    .contains(status))
                throw new IOException("Unexpected provider preparation status");
            pause.waitFor(Math.min(1000, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
        }
        throw new IOException("Provider preparation timed out. Try again later.");
    }

    static JSONObject legacyResolve(String id, long deadline) throws Exception {
        JSONObject row = decode(id);
        int format = Integer.parseInt(id.substring(0, id.indexOf('.')));
        String token =
                request("/prepare", new JSONObject().put("sp", session()), "", deadline)
                        .optString("t");
        if (token.isEmpty()
                || token.length() > 8192
                || token.contains("\r")
                || token.contains("\n")) throw new IOException("FLAC download token unavailable");
        JSONObject queued =
                request(
                        "/api/qobuz/queue",
                        new JSONObject()
                                .put("url", row.getString("u"))
                                .put("title", row.getString("t"))
                                .put("artist", row.optString("a"))
                                .put("formatId", format),
                        token,
                        deadline);
        String job = queued.optString("id");
        if (!job.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("FLAC preparation job unavailable");
        for (int attempt = 0; attempt < 180; attempt++) {
            JSONObject state = request("/api/qobuz/jobs/" + job, null, "", deadline);
            String status = state.optString("status");
            if ("done".equals(status)) {
                String file = state.optString("file");
                if (!file.matches("[A-Za-z0-9][A-Za-z0-9_. -]{0,255}")
                        || file.contains("..")
                        || !file.toLowerCase(Locale.ROOT).endsWith(format == 5 ? ".mp3" : ".flac"))
                    throw new IOException("FLAC source returned an unexpected file format");
                requireEnabled();
                JSONObject stream =
                        new JSONObject()
                                .put(
                                        "url",
                                        BACKEND
                                                + "/qobuz/files/"
                                                + enc(file)
                                                + "?name="
                                                + enc(state.optString("pretty_name", file)))
                                .put("format", format == 5 ? "mp3" : "flac")
                                .put("codec", format == 5 ? "mp3" : "flac")
                                .put("container", format == 5 ? "mp3" : "flac")
                                .put("mimeType", format == 5 ? "audio/mpeg" : "audio/flac")
                                .put(
                                        "headers",
                                        new JSONObject()
                                                .put("User-Agent", "BitChord/1.9")
                                                .put("Referer", BACKEND + "/"))
                                .put("manifest", "none")
                                .put("encrypted", false)
                                .put(
                                        "audioQuality",
                                        format == 5 ? "MP3 · requested" : "FLAC · requested")
                                .put("formatVerified", false);
                // No synthetic bitrate, bit depth or sample rate: those require inspecting the
                // file.
                return stream;
            }
            if ("error".equals(status) || "failed".equals(status) || "cancelled".equals(status))
                throw new IOException(
                        "Selected FLAC source format is unavailable. Choose another format"
                                + " explicitly.");
            if (!Arrays.asList(
                            "queued",
                            "pending",
                            "processing",
                            "downloading",
                            "running",
                            "converting")
                    .contains(status)) throw new IOException("Unexpected FLAC preparation status");
            checkDeadline(deadline);
            pause.waitFor(Math.min(1000, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
        }
        throw new IOException("FLAC preparation timed out. Try again later.");
    }

    static String enc(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    static void checkDeadline(long deadline) throws IOException {
        requireEnabled();
        if (System.nanoTime() >= deadline) throw new IOException("FLAC source request timed out");
    }

    static JSONObject request(String path, JSONObject body, String token, long deadline)
            throws Exception {
        checkDeadline(deadline);
        JSONObject result = transport.request(path, body, token, deadline);
        checkDeadline(deadline);
        return result;
    }

    static JSONObject http(String path, JSONObject body, String token, long deadline)
            throws Exception {
        if (!path.startsWith("/")
                || path.startsWith("//")
                || path.contains("\r")
                || path.contains("\n")) throw new IOException("Invalid source operation");
        HttpURLConnection connection = (HttpURLConnection) new URL(BACKEND + path).openConnection();
        int timeout =
                (int) Math.min(10000, Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "BitChord/1.9");
        connection.setRequestProperty("Referer", BACKEND + "/");
        connection.setRequestProperty("Origin", BACKEND);
        if (!token.isEmpty()) connection.setRequestProperty("X-DL-Token", token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status == 403 && "/prepare".equals(path)) {
                providerSession = "";
                sessions.write(null);
                throw new VerificationRequired();
            }
            if (status == 429)
                throw new IOException("FLAC service is rate limited. Try again later.");
            if (status != 200)
                throw new IOException("FLAC service is unavailable (HTTP " + status + ")");
            try (InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = in.read(buffer)) != -1) {
                    checkDeadline(deadline);
                    if (out.size() + size > MAX_JSON)
                        throw new IOException("FLAC response is too large");
                    out.write(buffer, 0, size);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
