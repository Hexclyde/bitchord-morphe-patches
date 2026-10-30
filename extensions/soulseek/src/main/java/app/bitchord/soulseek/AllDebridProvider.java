package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** AllDebrid link and magnet originals. API credentials use the Bearer header. */
public final class AllDebridProvider implements DebridProvider {
    private final RealDebridProvider.Links links = new RealDebridProvider.Links();
    private final LinkedHashMap<String, Map<String, String>> magnetLinks = new LinkedHashMap<>();

    @Override
    public Kind kind() {
        return Kind.ALLDEBRID;
    }

    @Override
    public void validate(String key, DebridHttp http, DebridModels.Cancellation c)
            throws IOException {
        JSONObject data = request(http, "GET", "/v4/user", key, DebridHttp.params(), c);
        JSONObject user = data.optJSONObject("user");
        if (user == null || !(user.opt("isPremium") instanceof Boolean)) throw malformed();
    }

    @Override
    public DebridModels.Resolved resolve(
            String input, String key, DebridHttp http, DebridModels.Cancellation c)
            throws IOException {
        RealDebridProvider.validateInput(input);
        if (RealDebridProvider.isMagnet(input)) {
            JSONObject data =
                    request(
                            http,
                            "POST",
                            "/v4/magnet/upload",
                            key,
                            DebridHttp.params("magnets[]", input),
                            c);
            JSONArray magnets = RealDebridProvider.array(data, "magnets");
            if (magnets.length() != 1) throw malformed();
            JSONObject magnet = RealDebridProvider.object(magnets, 0);
            checkError(magnet);
            String id = RealDebridProvider.integerId(magnet, "id");
            return magnet(id, input, key, http, c);
        }
        JSONObject data =
                request(http, "POST", "/v4/link/unlock", key, DebridHttp.params("link", input), c);
        String name = filename(data);
        long size = size(data);
        DebridModels.Entry entry = new DebridModels.Entry("0", name, size);
        if (data.optLong("delayed", 0) > 0) {
            return new DebridModels.Resolved(
                    kind(),
                    "delayed:" + RealDebridProvider.integerId(data, "delayed"),
                    input,
                    Collections.singletonList(entry),
                    "preparing",
                    "AllDebrid is generating the link. Refresh in at least five seconds.",
                    false);
        }
        DebridModels.Download download = unlocked(data, entry);
        String token = "link:" + UUID.randomUUID().toString().replace("-", "");
        links.put(token, download);
        return new DebridModels.Resolved(
                kind(),
                token,
                input,
                Collections.singletonList(entry),
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
        if (resolved.remoteId.startsWith("delayed:")) {
            String id = numeric(resolved.remoteId.substring(8));
            JSONObject data =
                    request(http, "POST", "/v4/link/delayed", key, DebridHttp.params("id", id), c);
            int status = data.optInt("status", -1);
            if (status == 1)
                return new DebridModels.Resolved(
                        kind(),
                        resolved.remoteId,
                        resolved.source,
                        resolved.entries,
                        "preparing",
                        "AllDebrid is generating the link. Refresh in at least five seconds.",
                        false);
            if (status != 2)
                throw new IOException(
                        "AllDebrid could not generate this link. Check the item in your account.");
            DebridModels.Entry entry = resolved.entry("0");
            String link = data.optString("link");
            RealDebridProvider.https(link);
            links.put(resolved.remoteId, new DebridModels.Download(link, entry.name, entry.size));
            return new DebridModels.Resolved(
                    kind(),
                    resolved.remoteId,
                    resolved.source,
                    resolved.entries,
                    "ready",
                    "Original audio link is ready.",
                    true);
        }
        return magnet(numeric(resolved.remoteId), resolved.source, key, http, c);
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
        if (resolved.remoteId.startsWith("link:") || resolved.remoteId.startsWith("delayed:")) {
            if (!"0".equals(fileId)) throw malformed();
            if (!resolved.ready)
                throw new IOException(
                        "The provider is still generating this link. Refresh before resuming.");
            DebridModels.Download download = links.get(resolved.remoteId);
            RealDebridProvider.same(
                    expected, new DebridModels.Entry(fileId, download.name, download.size));
            return download;
        }
        DebridModels.Resolved current =
                magnet(numeric(resolved.remoteId), resolved.source, key, http, c);
        if (!current.ready)
            throw new IOException(
                    "AllDebrid is still preparing this magnet. Refresh before resuming.");
        RealDebridProvider.same(expected, current.entry(fileId));
        final String link;
        synchronized (magnetLinks) {
            Map<String, String> available = magnetLinks.get(resolved.remoteId);
            link = available == null ? null : available.get(fileId);
        }
        if (link == null)
            throw new IOException("The provider file link expired. Refresh the same transfer.");
        JSONObject data =
                request(http, "POST", "/v4/link/unlock", key, DebridHttp.params("link", link), c);
        if (data.optLong("delayed", 0) > 0)
            throw new IOException(
                    "AllDebrid is still generating this file link. Wait before resuming.");
        return unlocked(data, expected);
    }

    private DebridModels.Resolved magnet(
            String id, String source, String key, DebridHttp http, DebridModels.Cancellation c)
            throws IOException {
        JSONObject status =
                request(http, "POST", "/v4.1/magnet/status", key, DebridHttp.params("id", id), c);
        JSONObject magnet = exactMagnet(status, id);
        checkError(magnet);
        int state = magnet.optInt("statusCode", -1);
        if (state < 0 || state > 15) throw malformed();
        if (state > 4)
            throw new IOException(
                    "AllDebrid could not prepare this magnet. Check the transfer in your account.");
        if (state != 4)
            return new DebridModels.Resolved(
                    kind(),
                    id,
                    source,
                    Collections.emptyList(),
                    "preparing",
                    "The provider is preparing the magnet. Refresh to check again.",
                    false);

        // v4.1 status intentionally omits the file links. The documented files method remains v4.
        JSONObject files =
                request(http, "POST", "/v4/magnet/files", key, DebridHttp.params("id[]", id), c);
        JSONObject result = exactMagnet(files, id);
        checkError(result);
        List<DebridModels.Entry> entries = new ArrayList<>();
        Map<String, String> locations = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        int[] count = {0};
        tree(
                RealDebridProvider.array(result, "files"),
                "",
                id,
                0,
                count,
                entries,
                locations,
                paths);
        if (entries.isEmpty())
            throw new IOException(
                    "The magnet has no supported audio files within the download limit.");
        synchronized (magnetLinks) {
            while (magnetLinks.size() >= 32 && !magnetLinks.containsKey(id))
                magnetLinks.remove(magnetLinks.keySet().iterator().next());
            magnetLinks.put(id, locations);
        }
        return new DebridModels.Resolved(
                kind(), id, source, entries, "ready", "Original audio files are ready.", true);
    }

    private void tree(
            JSONArray nodes,
            String prefix,
            String magnet,
            int depth,
            int[] count,
            List<DebridModels.Entry> entries,
            Map<String, String> locations,
            Set<String> paths)
            throws IOException {
        if (depth > 16) throw new IOException("The provider file tree is too deep.");
        for (int i = 0; i < nodes.length(); i++) {
            if (++count[0] > 4096) throw new IOException("The provider file tree is too large.");
            JSONObject node = RealDebridProvider.object(nodes, i);
            final String name;
            try {
                name = RealDebridProvider.path(node.getString("n"));
            } catch (JSONException invalid) {
                throw malformed();
            }
            if (name.indexOf('/') >= 0) throw malformed();
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (path.length() > 4096) throw malformed();
            JSONArray children = node.optJSONArray("e");
            if (children != null) {
                if (children.length() > 4096 || node.has("l")) throw malformed();
                tree(children, path, magnet, depth + 1, count, entries, locations, paths);
                continue;
            }
            if (!RealDebridProvider.audio(path)) continue;
            Object amount = node.opt("s");
            if (!(amount instanceof Number)) throw malformed();
            long size = ((Number) amount).longValue();
            if (size <= 0 || size > DebridModels.MAX_BYTES) continue;
            if (!paths.add(path))
                throw new IOException("The provider returned duplicate file paths.");
            if (entries.size() >= 1024)
                throw new IOException("The provider returned too many audio files.");
            String link = node.optString("l");
            RealDebridProvider.https(link);
            String fileId = hash(magnet + "\0" + path + "\0" + size);
            entries.add(new DebridModels.Entry(fileId, path, size));
            locations.put(fileId, link);
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
        JSONObject response = http.json(kind(), method, path, key, parameters, c);
        c.check();
        if (!"success".equals(response.optString("status"))) {
            checkError(response);
            throw malformed();
        }
        JSONObject data = response.optJSONObject("data");
        if (data == null) throw malformed();
        return data;
    }

    private static void checkError(JSONObject object) throws IOException {
        JSONObject error = object.optJSONObject("error");
        if (error == null) return;
        String code = error.optString("code");
        if (code.startsWith("AUTH_") || code.equals("USER_BANNED"))
            throw new IOException(
                    "AllDebrid rejected the API key or account access. Check its settings.");
        if (code.contains("PREMIUM") || code.contains("QUOTA") || code.contains("LIMIT"))
            throw new IOException(
                    "AllDebrid requires account access or available quota for this file.");
        if (code.contains("RATE") || code.contains("TOO_MANY"))
            throw new IOException("AllDebrid is busy. Wait before retrying.");
        throw new IOException(
                "AllDebrid could not resolve this item. Check the transfer in your account.");
    }

    private JSONObject exactMagnet(JSONObject data, String id) throws IOException {
        Object value = data.opt("magnets");
        if (value instanceof JSONObject) {
            JSONObject item = (JSONObject) value;
            if (!id.equals(RealDebridProvider.integerId(item, "id"))) throw malformed();
            return item;
        }
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() > 4096) throw malformed();
        JSONObject found = null;
        JSONArray items = (JSONArray) value;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = RealDebridProvider.object(items, i);
            if (id.equals(RealDebridProvider.integerId(item, "id"))) {
                if (found != null) throw malformed();
                found = item;
            }
        }
        if (found == null) throw new IOException("The provider transfer is no longer available.");
        return found;
    }

    private String filename(JSONObject data) throws IOException {
        String name = RealDebridProvider.path(data.optString("filename"));
        if (!RealDebridProvider.audio(name))
            throw new IOException("The provider link is not a supported audio file.");
        return name;
    }

    private long size(JSONObject data) throws IOException {
        long size = data.optLong("filesize", 0);
        if (size < 0 || size > DebridModels.MAX_BYTES)
            throw new IOException("Files are limited to 256 MiB.");
        return size == 0 ? -1 : size;
    }

    private DebridModels.Download unlocked(JSONObject data, DebridModels.Entry expected)
            throws IOException {
        String name = filename(data);
        long size = size(data);
        if (!RealDebridProvider.base(name).equals(RealDebridProvider.base(expected.name))
                || (size >= 0 && expected.size >= 0 && size != expected.size))
            throw new IOException("AllDebrid returned a different file. The download was stopped.");
        String url = data.optString("link");
        RealDebridProvider.https(url);
        return new DebridModels.Download(
                url, expected.name, expected.size >= 0 ? expected.size : size);
    }

    private void own(DebridModels.Resolved resolved) throws IOException {
        if (resolved == null || resolved.kind != kind())
            throw new IOException("The file belongs to another debrid service.");
    }

    private IOException malformed() {
        return RealDebridProvider.malformed(kind());
    }

    private static String numeric(String value) throws IOException {
        if (value == null || !value.matches("[1-9][0-9]{0,17}"))
            throw new IOException("Invalid provider transfer identity.");
        return value;
    }

    private static String hash(String text) throws IOException {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes)
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.");
        }
    }
}
