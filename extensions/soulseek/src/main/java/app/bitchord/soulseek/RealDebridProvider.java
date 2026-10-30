package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Real-Debrid originals, with explicit torrent file selection and no transcoding. */
public final class RealDebridProvider implements DebridProvider {
    private static final String BASE = "/rest/1.0";
    private final Links links = new Links();

    @Override
    public Kind kind() {
        return Kind.REAL_DEBRID;
    }

    @Override
    public void validate(String key, DebridHttp http, DebridModels.Cancellation c)
            throws IOException {
        JSONObject user = request(http, "GET", "/user", key, DebridHttp.params(), c);
        if (!(user.opt("id") instanceof Number) || user.optString("type").isEmpty())
            throw malformed(kind());
    }

    @Override
    public DebridModels.Resolved resolve(
            String input, String key, DebridHttp http, DebridModels.Cancellation c)
            throws IOException {
        validateInput(input);
        if (isMagnet(input)) {
            JSONObject result =
                    request(
                            http,
                            "POST",
                            "/torrents/addMagnet",
                            key,
                            DebridHttp.params("magnet", input),
                            c);
            String id = id(result.optString("id"));
            return torrent(id, input, key, http, c);
        }
        JSONObject result =
                request(http, "POST", "/unrestrict/link", key, DebridHttp.params("link", input), c);
        DebridModels.Download download = unrestricted(result);
        String token = "link:" + UUID.randomUUID().toString().replace("-", "");
        links.put(token, download);
        return new DebridModels.Resolved(
                kind(),
                token,
                input,
                Collections.singletonList(
                        new DebridModels.Entry("0", download.name, download.size)),
                "ready",
                "Original audio link is ready.",
                true);
    }

    @Override
    public DebridModels.Resolved refresh(
            DebridModels.Resolved resolved,
            String key,
            DebridHttp http,
            DebridModels.Cancellation c)
            throws IOException {
        own(resolved);
        c.check();
        if (resolved.remoteId.startsWith("link:")) {
            links.get(resolved.remoteId);
            return resolved;
        }
        return torrent(id(resolved.remoteId), resolved.source, key, http, c);
    }

    @Override
    public DebridModels.Resolved select(
            DebridModels.Resolved resolved,
            List<String> fileIds,
            String key,
            DebridHttp http,
            DebridModels.Cancellation c)
            throws IOException {
        own(resolved);
        if (fileIds == null || fileIds.isEmpty() || fileIds.size() > 1024)
            throw new IOException("Choose at least one audio file.");
        if (resolved.remoteId.startsWith("link:")) {
            if (fileIds.size() != 1 || !"0".equals(fileIds.get(0))) throw malformed(kind());
            resolved.entry("0");
            links.get(resolved.remoteId);
            return resolved;
        }
        JSONObject info = info(id(resolved.remoteId), key, http, c);
        List<DebridModels.Entry> current = entries(info);
        Set<String> chosen = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (String fileId : fileIds) {
            if (fileId == null || !fileId.matches("[1-9][0-9]{0,9}") || !chosen.add(fileId))
                throw new IOException("Invalid selected torrent file.");
            DebridModels.Entry before = resolved.entry(fileId), now = entry(current, fileId);
            same(before, now);
            if (!names.add(base(now.name) + "\0" + now.size))
                throw new IOException(
                        "These files have ambiguous names and sizes. Select one at a time.");
        }
        String status = status(info);
        if ("waiting_files_selection".equals(status)) {
            request(
                    http,
                    "POST",
                    "/torrents/selectFiles/" + id(resolved.remoteId),
                    key,
                    DebridHttp.params("files", join(fileIds)),
                    c);
            info = info(id(resolved.remoteId), key, http, c);
        } else {
            JSONArray files = array(info, "files");
            Set<String> selected = new HashSet<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = object(files, i);
                if (file.optInt("selected", 0) == 1) selected.add(integerId(file, "id"));
            }
            if (!selected.containsAll(chosen))
                throw new IOException(
                        "This torrent already has a different file selection. Add the magnet again"
                                + " to choose other files.");
        }
        Set<String> confirmed = new HashSet<>();
        JSONArray afterFiles = array(info, "files");
        for (int i = 0; i < afterFiles.length(); i++) {
            JSONObject file = object(afterFiles, i);
            if (file.optInt("selected", 0) == 1) confirmed.add(integerId(file, "id"));
        }
        if (!confirmed.containsAll(chosen))
            throw new IOException(
                    "Real-Debrid did not confirm the selected files. Refresh this transfer before"
                            + " retrying.");
        for (String fileId : fileIds) same(resolved.entry(fileId), entry(entries(info), fileId));
        return torrentInfo(id(resolved.remoteId), resolved.source, info);
    }

    @Override
    public DebridModels.Download download(
            DebridModels.Resolved resolved,
            String fileId,
            String key,
            DebridHttp http,
            DebridModels.Cancellation c)
            throws IOException {
        own(resolved);
        DebridModels.Entry expected = resolved.entry(fileId);
        c.check();
        if (resolved.remoteId.startsWith("link:")) {
            if (!"0".equals(fileId)) throw malformed(kind());
            DebridModels.Download download = links.get(resolved.remoteId);
            same(expected, new DebridModels.Entry(fileId, download.name, download.size));
            return download;
        }
        JSONObject info = info(id(resolved.remoteId), key, http, c);
        if (!"downloaded".equals(status(info)))
            throw new IOException(
                    "Real-Debrid is still preparing this torrent. Refresh before resuming.");
        JSONArray files = array(info, "files"), hostLinks = array(info, "links");
        int selectedCount = 0, linkIndex = -1;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = object(files, i);
            if (file.optInt("selected", 0) != 1) continue;
            if (fileId.equals(integerId(file, "id"))) {
                DebridModels.Entry actual = fileEntry(file);
                same(expected, actual);
                linkIndex = selectedCount;
            }
            selectedCount++;
        }
        if (linkIndex < 0 || hostLinks.length() != selectedCount)
            throw new IOException(
                    "Real-Debrid returned ambiguous file links. The selected file was not"
                            + " substituted.");
        final String hostLink;
        try {
            hostLink = hostLinks.getString(linkIndex);
        } catch (JSONException invalid) {
            throw malformed(kind());
        }
        https(hostLink);
        JSONObject response =
                request(
                        http,
                        "POST",
                        "/unrestrict/link",
                        key,
                        DebridHttp.params("link", hostLink),
                        c);
        DebridModels.Download download = unrestricted(response);
        if (!base(download.name).equals(base(expected.name))
                || (expected.size >= 0 && download.size >= 0 && expected.size != download.size))
            throw new IOException(
                    "Real-Debrid returned a different file. The download was stopped.");
        return new DebridModels.Download(
                download.url, expected.name, expected.size >= 0 ? expected.size : download.size);
    }

    private DebridModels.Resolved torrent(
            String id, String source, String key, DebridHttp http, DebridModels.Cancellation c)
            throws IOException {
        return torrentInfo(id, source, info(id, key, http, c));
    }

    private DebridModels.Resolved torrentInfo(String id, String source, JSONObject info)
            throws IOException {
        String state = status(info);
        if (state.equals("error")
                || state.equals("magnet_error")
                || state.equals("virus")
                || state.equals("dead"))
            throw new IOException(
                    "Real-Debrid could not prepare this torrent. Check the transfer in your"
                            + " account.");
        List<DebridModels.Entry> entries = entries(info);
        boolean ready = "downloaded".equals(state);
        if (ready && entries.isEmpty())
            throw new IOException(
                    "The torrent has no supported audio files within the download limit.");
        return new DebridModels.Resolved(
                kind(),
                id,
                source,
                entries,
                state,
                "waiting_files_selection".equals(state)
                        ? "Choose audio files to start the provider transfer."
                        : ready
                                ? "Selected audio files are ready."
                                : "The provider is preparing the torrent. Refresh to check again.",
                ready);
    }

    private JSONObject info(String id, String key, DebridHttp http, DebridModels.Cancellation c)
            throws IOException {
        JSONObject info = request(http, "GET", "/torrents/info/" + id, key, DebridHttp.params(), c);
        if (!id.equals(info.optString("id"))) throw malformed(kind());
        return info;
    }

    private List<DebridModels.Entry> entries(JSONObject info) throws IOException {
        if (!info.has("files") && !"downloaded".equals(status(info)))
            return Collections.emptyList();
        JSONArray files = array(info, "files");
        List<DebridModels.Entry> entries = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = object(files, i);
            DebridModels.Entry entry = fileEntry(file);
            if (audio(entry.name) && entry.size > 0 && entry.size <= DebridModels.MAX_BYTES)
                entries.add(entry);
        }
        return entries;
    }

    private DebridModels.Entry fileEntry(JSONObject file) throws IOException {
        try {
            return new DebridModels.Entry(
                    integerId(file, "id"), path(file.getString("path")), file.getLong("bytes"));
        } catch (JSONException invalid) {
            throw malformed(kind());
        }
    }

    private JSONObject request(
            DebridHttp http,
            String method,
            String path,
            String key,
            Map<String, String> parameters,
            DebridModels.Cancellation c)
            throws IOException {
        c.check();
        JSONObject response = http.json(kind(), method, BASE + path, key, parameters, c);
        c.check();
        if (response.has("error")) {
            int code = response.optInt("error_code", -1);
            if (code == 8)
                throw new IOException("Real-Debrid rejected the API key. Check its settings.");
            if (code == 9)
                throw new IOException(
                        "Real-Debrid denied account access for this file. Check the account and its"
                                + " available quota.");
            if (code == 5) throw new IOException("Real-Debrid is busy. Wait before retrying.");
            throw new IOException(
                    "Real-Debrid could not resolve this item. Check the transfer in your account.");
        }
        return response;
    }

    private DebridModels.Download unrestricted(JSONObject response) throws IOException {
        try {
            String filename = path(response.getString("filename"));
            if (!audio(filename))
                throw new IOException("The provider link is not a supported audio file.");
            String url = response.getString("download");
            https(url);
            long size = response.optLong("filesize", 0);
            if (size < 0 || size > DebridModels.MAX_BYTES)
                throw new IOException("Files are limited to 256 MiB.");
            return new DebridModels.Download(url, filename, size == 0 ? -1 : size);
        } catch (JSONException invalid) {
            throw malformed(kind());
        }
    }

    private void own(DebridModels.Resolved resolved) throws IOException {
        if (resolved == null || resolved.kind != kind())
            throw new IOException("The file belongs to another debrid service.");
    }

    private static String status(JSONObject info) throws IOException {
        String status = info.optString("status");
        if (!status.matches(
                "magnet_error|magnet_conversion|waiting_files_selection|queued|downloading|downloaded|error|virus|compressing|uploading|dead"))
            throw malformed(Kind.REAL_DEBRID);
        return status;
    }

    static IOException malformed(Kind kind) {
        return new IOException(kind.label + " returned an invalid response.");
    }

    static String id(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("Invalid provider transfer identity.");
        return value;
    }

    static String integerId(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        String text = value == null ? "" : value.toString();
        if (!text.matches("[1-9][0-9]{0,17}"))
            throw new IOException("Invalid provider file identity.");
        return text;
    }

    static JSONArray array(JSONObject object, String key) throws IOException {
        JSONArray array = object.optJSONArray(key);
        if (array == null || array.length() > 4096)
            throw new IOException("Invalid or oversized provider file list.");
        return array;
    }

    static JSONObject object(JSONArray array, int index) throws IOException {
        JSONObject result = array.optJSONObject(index);
        if (result == null) throw new IOException("Invalid provider file list.");
        return result;
    }

    static String path(String value) throws IOException {
        if (value == null
                || value.isEmpty()
                || value.length() > 4096
                || DebridModels.hasControl(value))
            throw new IOException("Invalid provider filename.");
        String normalized = value.replace('\\', '/');
        for (String part : normalized.split("/", -1))
            if (part.equals(".") || part.equals(".."))
                throw new IOException("Invalid provider filename.");
        if (base(normalized).isEmpty()) throw new IOException("Invalid provider filename.");
        return normalized;
    }

    static String base(String value) {
        return value.substring(Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')) + 1);
    }

    static boolean audio(String name) {
        return !AudioFormatPreferences.candidateFormats(base(name)).isEmpty();
    }

    static void same(DebridModels.Entry before, DebridModels.Entry now) throws IOException {
        if (!before.fileId.equals(now.fileId)
                || !before.name.equals(now.name)
                || before.size != now.size)
            throw new IOException("The selected provider file changed. Prepare the item again.");
    }

    static DebridModels.Entry entry(List<DebridModels.Entry> list, String id) throws IOException {
        for (DebridModels.Entry entry : list) if (entry.fileId.equals(id)) return entry;
        throw new IOException("The selected provider file is no longer available.");
    }

    static void validateInput(String input) throws IOException {
        if (input == null
                || input.isEmpty()
                || input.length() > 8192
                || DebridModels.hasControl(input))
            throw new IOException("Enter a supported HTTPS link or magnet.");
        if (!isMagnet(input)) https(input);
    }

    static boolean isMagnet(String input) {
        return input != null && input.regionMatches(true, 0, "magnet:?", 0, 8);
    }

    static void https(String url) throws IOException {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IOException("The provider must return a secure HTTPS download link.");
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("Invalid provider download link.");
        }
    }

    static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (text.length() != 0) text.append(',');
            text.append(value);
        }
        return text.toString();
    }

    /** Short-lived signed links remain memory-only and have a fixed count and lifetime. */
    static final class Links {
        private static final long TTL = TimeUnit.MINUTES.toNanos(15);
        private final LinkedHashMap<String, Cached> values = new LinkedHashMap<>();

        synchronized void put(String id, DebridModels.Download value) {
            trim();
            while (values.size() >= 64) values.remove(values.keySet().iterator().next());
            values.put(id, new Cached(value));
        }

        synchronized DebridModels.Download get(String id) throws IOException {
            trim();
            Cached value = values.get(id);
            if (value == null)
                throw new IOException(
                        "The temporary provider link expired. Prepare the item again.");
            return value.value;
        }

        private void trim() {
            long now = System.nanoTime();
            values.entrySet().removeIf(entry -> now - entry.getValue().created > TTL);
        }

        private static final class Cached {
            final long created = System.nanoTime();
            final DebridModels.Download value;

            Cached(DebridModels.Download value) {
                this.value = value;
            }
        }
    }
}
