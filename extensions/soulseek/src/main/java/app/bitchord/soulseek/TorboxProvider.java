package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** TorBox torrent, web and NZB-source jobs. Download links stay in memory only. */
public final class TorboxProvider implements DebridProvider {
    private static final String BASE = "/v1/api/";
    private static final int MAX_FILES = 1024;

    @Override
    public Kind kind() {
        return Kind.TORBOX;
    }

    @Override
    public void validate(String key, DebridHttp http, DebridModels.Cancellation cancellation)
            throws IOException {
        JSONObject response =
                success(
                        http.json(
                                kind(),
                                "GET",
                                BASE + "user/me",
                                key,
                                DebridHttp.params("settings", "false"),
                                cancellation));
        JSONObject user = response.optJSONObject("data");
        if (user == null || !user.has("id"))
            throw new IOException("TorBox did not return a valid account.");
    }

    @Override
    public DebridModels.Resolved resolve(
            String input, String key, DebridHttp http, DebridModels.Cancellation cancellation)
            throws IOException {
        String type = sourceType(input);
        cancellation.check();
        String endpoint, parameter, identity;
        if ("torrent".equals(type)) {
            endpoint = "torrents/createtorrent";
            parameter = "magnet";
            identity = "torrent_id";
        } else if ("usenet".equals(type)) {
            endpoint = "usenet/createusenetdownload";
            parameter = "link";
            identity = "usenetdownload_id";
        } else {
            endpoint = "webdl/createwebdownload";
            parameter = "link";
            identity = "webdownload_id";
        }
        java.util.Map<String, String> fields = DebridHttp.params(parameter, input);
        if ("torrent".equals(type)) fields.put("allow_zip", "false");
        JSONObject result =
                success(http.jsonMultipart(kind(), BASE + endpoint, key, fields, cancellation));
        JSONObject data = result.optJSONObject("data");
        if (data == null) throw new IOException("TorBox did not return a download job.");
        String id = numeric(data.opt(identity));
        return pending(type + ":" + id, input);
    }

    @Override
    public DebridModels.Resolved refresh(
            DebridModels.Resolved previous,
            String key,
            DebridHttp http,
            DebridModels.Cancellation cancellation)
            throws IOException {
        own(previous);
        String[] binding = binding(previous.remoteId);
        JSONObject response =
                success(
                        http.json(
                                kind(),
                                "GET",
                                BASE + route(binding[0]) + "/mylist",
                                key,
                                DebridHttp.params("id", binding[1], "bypass_cache", "true"),
                                cancellation));
        Object raw = response.opt("data");
        JSONObject item = null;
        if (raw instanceof JSONObject) {
            item = (JSONObject) raw;
            if (!binding[1].equals(numeric(item.opt("id")))) throw changed();
        } else if (raw instanceof JSONArray) {
            JSONArray list = (JSONArray) raw;
            if (list.length() > 1024)
                throw new IOException("TorBox returned an oversized job list.");
            for (int i = 0; i < list.length(); i++) {
                cancellation.check();
                JSONObject candidate = list.optJSONObject(i);
                if (candidate == null)
                    throw new IOException("TorBox returned invalid job metadata.");
                if (binding[1].equals(numeric(candidate.opt("id")))) {
                    if (item != null)
                        throw new IOException("TorBox returned an ambiguous job identity.");
                    item = candidate;
                }
            }
        } else if (raw != null && raw != JSONObject.NULL) {
            throw new IOException("TorBox returned invalid job metadata.");
        }
        if (item == null) return pending(previous.remoteId, previous.source);
        String state = item.optString("download_state", "");
        if ("error".equalsIgnoreCase(state) || "failed".equalsIgnoreCase(state))
            throw new IOException(
                    "TorBox could not prepare this source. Check it in your provider account.");
        // TorBox explicitly says not to infer readiness from download_state='completed'.
        if (!item.optBoolean("download_present", false)
                || !item.optBoolean("download_finished", false))
            return pending(previous.remoteId, previous.source);
        JSONArray files = item.optJSONArray("files");
        if (files == null || files.length() > MAX_FILES)
            throw new IOException("TorBox returned an invalid or oversized file list.");
        List<DebridModels.Entry> entries = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            cancellation.check();
            JSONObject file = files.optJSONObject(i);
            if (file == null) throw new IOException("TorBox returned invalid file metadata.");
            String name = text(file, "name");
            if (AudioFormatPreferences.candidateFormats(name).isEmpty()) continue;
            if (file.optBoolean("zipped", false))
                throw new IOException(
                        "TorBox returned zipped audio. Use a source that provides individual"
                                + " original files.");
            if (file.optBoolean("infected", false))
                throw new IOException(
                        "TorBox marked an audio file as unsafe; it was not downloaded.");
            entries.add(new DebridModels.Entry(numeric(file.opt("id")), name, size(file)));
        }
        if (entries.isEmpty())
            throw new IOException("TorBox returned no supported audio files for this source.");
        return new DebridModels.Resolved(
                kind(),
                previous.remoteId,
                previous.source,
                entries,
                "ready",
                "Audio files are ready to download.",
                true);
    }

    @Override
    public DebridModels.Download download(
            DebridModels.Resolved previous,
            String fileId,
            String key,
            DebridHttp http,
            DebridModels.Cancellation cancellation)
            throws IOException {
        own(previous);
        if (!previous.ready)
            throw new IOException(
                    "TorBox is still preparing the source. Refresh its status first.");
        DebridModels.Entry selected = previous.entry(fileId);
        DebridModels.Resolved fresh = refresh(previous, key, http, cancellation);
        if (!fresh.ready)
            throw new IOException(
                    "TorBox no longer has the source ready. Refresh its status later.");
        DebridModels.Entry current = fresh.entry(fileId);
        if (!current.name.equals(selected.name) || current.size != selected.size) throw changed();
        String[] binding = binding(previous.remoteId);
        String parameter =
                "torrent".equals(binding[0])
                        ? "torrent_id"
                        : "usenet".equals(binding[0]) ? "usenet_id" : "web_id";
        // This one official API endpoint requires token as a query parameter. It is never
        // exposed as the media URL, persisted, logged, or redirected with its credentials.
        JSONObject result =
                success(
                        http.json(
                                kind(),
                                "GET",
                                BASE + route(binding[0]) + "/requestdl",
                                key,
                                DebridHttp.params(
                                        "token",
                                        key,
                                        parameter,
                                        binding[1],
                                        "file_id",
                                        numeric(fileId),
                                        "redirect",
                                        "false",
                                        "zip_link",
                                        "false"),
                                cancellation));
        Object data = result.opt("data");
        if (!(data instanceof String) || ((String) data).isEmpty())
            throw new IOException("TorBox did not return an original-file download link.");
        String url = (String) data;
        if (credentialInLink(url, key))
            throw new IOException(
                    "TorBox returned an account credential in the media link; it was rejected.");
        return new DebridModels.Download(url, selected.name, selected.size);
    }

    private DebridModels.Resolved pending(String remoteId, String source) throws IOException {
        return new DebridModels.Resolved(
                kind(),
                remoteId,
                source,
                Collections.emptyList(),
                "preparing",
                "TorBox is preparing the source. Refresh its status later.",
                false);
    }

    private void own(DebridModels.Resolved resolved) throws IOException {
        if (resolved == null || resolved.kind != kind())
            throw new IOException("The source belongs to a different debrid service.");
    }

    private static String[] binding(String id) throws IOException {
        String[] parts = id.split(":", -1);
        if (parts.length != 2
                || !("torrent".equals(parts[0])
                        || "web".equals(parts[0])
                        || "usenet".equals(parts[0])))
            throw new IOException("Invalid TorBox source identity.");
        numeric(parts[1]);
        return parts;
    }

    private static String route(String type) {
        return "torrent".equals(type) ? "torrents" : "usenet".equals(type) ? "usenet" : "webdl";
    }

    private static String sourceType(String input) throws IOException {
        if (input == null || input.length() > 8192 || DebridModels.hasControl(input))
            throw new IOException("Invalid TorBox source.");
        if (input.regionMatches(true, 0, "magnet:?", 0, 8)) return "torrent";
        try {
            URI uri = new URI(input);
            if ("https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null)
                return uri.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".nzb")
                        ? "usenet"
                        : "web";
        } catch (Exception invalid) {
            /* Fall through to a redacted error. */
        }
        throw new IOException("TorBox sources must be an HTTPS link, NZB link or magnet.");
    }

    private static JSONObject success(JSONObject object) throws IOException {
        if (Boolean.TRUE.equals(object.opt("success"))) return object;
        String error = object.optString("error", "");
        if ("BAD_TOKEN".equals(error) || "NO_AUTH".equals(error) || "AUTH_ERROR".equals(error))
            throw new IOException(
                    "TorBox API key is invalid or unavailable. Update it in Debrid settings.");
        if ("PLAN_RESTRICTED_FEATURE".equals(error))
            throw new IOException("Your TorBox plan does not allow this download type.");
        if ("ACTIVE_LIMIT".equals(error)
                || "MONTHLY_LIMIT".equals(error)
                || "COOLDOWN_LIMIT".equals(error))
            throw new IOException(
                    "TorBox account quota, active-download limit or cooldown has been reached.");
        if ("DOWNLOAD_TOO_LARGE".equals(error))
            throw new IOException("This source exceeds your TorBox account's download limit.");
        if ("LINK_OFFLINE".equals(error) || "ITEM_NOT_FOUND".equals(error))
            throw new IOException("TorBox cannot find this source or its files.");
        throw new IOException(
                "TorBox could not complete this request. Check the source and your provider"
                        + " account.");
    }

    private static String numeric(Object value) throws IOException {
        String id;
        if (value instanceof String) id = (String) value;
        else if (value instanceof Number
                && ((Number) value).doubleValue() == ((Number) value).longValue())
            id = Long.toString(((Number) value).longValue());
        else throw new IOException("TorBox returned an invalid object identifier.");
        if (!id.matches("0|[1-9][0-9]{0,18}"))
            throw new IOException("TorBox returned an invalid object identifier.");
        try {
            if (Long.parseLong(id) < 0) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new IOException("TorBox returned an invalid object identifier.");
        }
        return id;
    }

    private static long size(JSONObject file) throws IOException {
        Object value = file.opt("size");
        if (!(value instanceof Number)
                || ((Number) value).longValue() < 0
                || ((Number) value).doubleValue() != ((Number) value).longValue())
            throw new IOException("TorBox returned an invalid file size.");
        return ((Number) value).longValue();
    }

    private static String text(JSONObject object, String field) throws IOException {
        Object value = object.opt(field);
        if (!(value instanceof String)
                || ((String) value).isEmpty()
                || ((String) value).length() > 4096
                || DebridModels.hasControl((String) value))
            throw new IOException("TorBox returned invalid file metadata.");
        return (String) value;
    }

    private static IOException changed() {
        return new IOException("The selected TorBox file changed. Resolve the source again.");
    }

    private static boolean credentialInLink(String url, String key) throws IOException {
        String candidate = url;
        for (int depth = 0; depth < 4; depth++) {
            if (candidate.contains(key)) return true;
            try {
                String decoded = java.net.URLDecoder.decode(candidate, "UTF-8");
                if (candidate.equals(decoded)) return false;
                candidate = decoded;
            } catch (Exception invalid) {
                throw new IOException("TorBox returned an invalid media link.");
            }
        }
        return candidate.contains(key);
    }
}
