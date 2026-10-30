package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Premiumize original-file links and explicitly submitted cloud transfers. */
public final class PremiumizeProvider implements DebridProvider {
    private static final int MAX_FILES = 1024, MAX_FOLDERS = 32, MAX_DEPTH = 8;

    @Override
    public Kind kind() {
        return Kind.PREMIUMIZE;
    }

    @Override
    public void validate(String key, DebridHttp http, DebridModels.Cancellation cancellation)
            throws IOException {
        JSONObject account =
                success(
                        http.json(
                                kind(),
                                "GET",
                                "/api/account/info",
                                key,
                                DebridHttp.params(),
                                cancellation));
        if (account.optString("customer_id", "").isEmpty())
            throw new IOException("Premiumize did not return a valid account.");
    }

    @Override
    public DebridModels.Resolved resolve(
            String input, String key, DebridHttp http, DebridModels.Cancellation cancellation)
            throws IOException {
        source(input);
        cancellation.check();
        if (!input.regionMatches(true, 0, "magnet:", 0, 7)) {
            JSONObject direct =
                    http.json(
                            kind(),
                            "POST",
                            "/api/transfer/directdl",
                            key,
                            DebridHttp.params("src", input),
                            cancellation);
            if ("success".equals(direct.optString("status")))
                return direct(input, direct, cancellation);
            String code = direct.optString("code", "");
            if (!"link_generation_failed".equals(code)
                    && !"transient_error".equals(code)
                    && !"service_unsupported".equals(code)) throw failure(code);
        }
        JSONObject created =
                success(
                        http.json(
                                kind(),
                                "POST",
                                "/api/transfer/create",
                                key,
                                DebridHttp.params("src", input),
                                cancellation));
        String id = identifier(created.optString("id", ""));
        // A pending result is refreshed by this exact ID; refresh never creates another job.
        return pending("transfer:" + id, input);
    }

    @Override
    public DebridModels.Resolved refresh(
            DebridModels.Resolved previous,
            String key,
            DebridHttp http,
            DebridModels.Cancellation cancellation)
            throws IOException {
        own(previous);
        cancellation.check();
        if ("direct".equals(previous.remoteId)) {
            JSONObject result =
                    success(
                            http.json(
                                    kind(),
                                    "POST",
                                    "/api/transfer/directdl",
                                    key,
                                    DebridHttp.params("src", previous.source),
                                    cancellation));
            return direct(previous.source, result, cancellation);
        }
        String id = transferId(previous.remoteId);
        JSONObject response =
                success(
                        http.json(
                                kind(),
                                "GET",
                                "/api/transfer/list",
                                key,
                                DebridHttp.params(),
                                cancellation));
        JSONArray transfers = response.optJSONArray("transfers");
        if (transfers == null || transfers.length() > 4096)
            throw new IOException("Premiumize returned an invalid transfer list.");
        JSONObject found = null;
        for (int i = 0; i < transfers.length(); i++) {
            cancellation.check();
            JSONObject item = transfers.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                if (found != null)
                    throw new IOException("Premiumize returned duplicate transfer identities.");
                found = item;
            }
        }
        if (found == null) return pending(previous.remoteId, previous.source);
        String state = found.optString("status", "");
        if ("error".equals(state))
            throw new IOException(
                    "Premiumize could not prepare this source. Check it in your provider account.");
        if (!"finished".equals(state) && !"seeding".equals(state))
            return pending(previous.remoteId, previous.source);
        List<DebridModels.Entry> files = new ArrayList<>();
        String file = nullable(found, "file_id");
        String folder = nullable(found, "folder_id");
        if (!file.isEmpty()) {
            JSONObject details = item(identifier(file), key, http, cancellation);
            addItem(files, details, "", cancellation);
        } else if (!folder.isEmpty()) {
            folder(files, identifier(folder), key, http, cancellation);
        } else {
            throw new IOException(
                    "Premiumize finished the transfer outside your cloud; no downloadable files"
                            + " were returned.");
        }
        return ready(previous.remoteId, previous.source, files);
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
        DebridModels.Entry selected = previous.entry(fileId);
        if (!previous.ready)
            throw new IOException(
                    "Premiumize is still preparing this file. Refresh its status first.");
        cancellation.check();
        if ("direct".equals(previous.remoteId)) {
            JSONObject response =
                    success(
                            http.json(
                                    kind(),
                                    "POST",
                                    "/api/transfer/directdl",
                                    key,
                                    DebridHttp.params("src", previous.source),
                                    cancellation));
            JSONArray content = content(response);
            JSONObject matched = null;
            for (int i = 0; i < content.length(); i++) {
                cancellation.check();
                JSONObject item = content.optJSONObject(i);
                if (item == null)
                    throw new IOException("Premiumize returned invalid file metadata.");
                String path = text(item, "path");
                long size = size(item);
                if (fileId.equals(directId(path, size))) {
                    if (matched != null)
                        throw new IOException("Premiumize returned an ambiguous file identity.");
                    matched = item;
                }
            }
            if (matched == null
                    || !selected.name.equals(text(matched, "path"))
                    || selected.size != size(matched)) throw changed();
            return new DebridModels.Download(text(matched, "link"), selected.name, selected.size);
        }
        transferId(previous.remoteId);
        if (!fileId.startsWith("item:")) throw changed();
        JSONObject details = item(identifier(fileId.substring(5)), key, http, cancellation);
        String actualName = text(details, "name");
        if (!basename(selected.name).equals(actualName) || selected.size != size(details))
            throw changed();
        return new DebridModels.Download(text(details, "link"), selected.name, selected.size);
    }

    private DebridModels.Resolved direct(
            String source, JSONObject response, DebridModels.Cancellation cancellation)
            throws IOException {
        JSONArray content = content(response);
        List<DebridModels.Entry> files = new ArrayList<>();
        for (int i = 0; i < content.length(); i++) {
            cancellation.check();
            JSONObject item = content.optJSONObject(i);
            if (item == null) throw new IOException("Premiumize returned invalid file metadata.");
            String path = text(item, "path");
            long size = size(item);
            if (AudioFormatPreferences.candidateFormats(path).isEmpty()) continue;
            text(item, "link");
            files.add(new DebridModels.Entry(directId(path, size), path, size));
        }
        return ready("direct", source, files);
    }

    private void folder(
            List<DebridModels.Entry> files,
            String root,
            String key,
            DebridHttp http,
            DebridModels.Cancellation cancellation)
            throws IOException {
        ArrayDeque<Folder> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(new Folder(root, "", 0));
        while (!pending.isEmpty()) {
            cancellation.check();
            Folder folder = pending.remove();
            if (!visited.add(folder.id) || visited.size() > MAX_FOLDERS || folder.depth > MAX_DEPTH)
                throw new IOException(
                        "Premiumize folder is too large or cyclic. Choose a smaller source.");
            JSONObject response =
                    success(
                            http.json(
                                    kind(),
                                    "GET",
                                    "/api/folder/list",
                                    key,
                                    DebridHttp.params("id", folder.id),
                                    cancellation));
            String returnedId = response.optString("folder_id", "");
            if (!returnedId.isEmpty() && !returnedId.equals(folder.id)) throw changed();
            JSONArray children = content(response);
            for (int i = 0; i < children.length(); i++) {
                cancellation.check();
                JSONObject child = children.optJSONObject(i);
                if (child == null)
                    throw new IOException("Premiumize returned invalid folder metadata.");
                String type = child.optString("type", "");
                if ("folder".equals(type)) {
                    if (pending.size() + visited.size() >= MAX_FOLDERS)
                        throw new IOException(
                                "Premiumize folder is too large. Choose a smaller source.");
                    pending.add(
                            new Folder(
                                    identifier(text(child, "id")),
                                    folder.prefix + text(child, "name") + "/",
                                    folder.depth + 1));
                } else if ("file".equals(type)) addItem(files, child, folder.prefix, cancellation);
            }
        }
    }

    private static final class Folder {
        final String id, prefix;
        final int depth;

        Folder(String id, String prefix, int depth) {
            this.id = id;
            this.prefix = prefix;
            this.depth = depth;
        }
    }

    private static void addItem(
            List<DebridModels.Entry> entries,
            JSONObject item,
            String prefix,
            DebridModels.Cancellation cancellation)
            throws IOException {
        cancellation.check();
        String name = prefix + text(item, "name");
        if (AudioFormatPreferences.candidateFormats(name).isEmpty()) return;
        if (entries.size() >= MAX_FILES)
            throw new IOException("Premiumize returned too many audio files.");
        entries.add(
                new DebridModels.Entry("item:" + identifier(text(item, "id")), name, size(item)));
    }

    private JSONObject item(
            String id, String key, DebridHttp http, DebridModels.Cancellation cancellation)
            throws IOException {
        JSONObject details =
                success(
                        http.json(
                                kind(),
                                "GET",
                                "/api/item/details",
                                key,
                                DebridHttp.params("id", id),
                                cancellation));
        if (!id.equals(text(details, "id"))) throw changed();
        return details;
    }

    private DebridModels.Resolved ready(String id, String source, List<DebridModels.Entry> files)
            throws IOException {
        if (files.isEmpty())
            throw new IOException("Premiumize returned no supported audio files for this source.");
        return new DebridModels.Resolved(
                kind(), id, source, files, "ready", "Audio files are ready to download.", true);
    }

    private DebridModels.Resolved pending(String id, String source) throws IOException {
        return new DebridModels.Resolved(
                kind(),
                id,
                source,
                Collections.emptyList(),
                "preparing",
                "Premiumize is preparing the source. Refresh its status later.",
                false);
    }

    private void own(DebridModels.Resolved resolved) throws IOException {
        if (resolved == null || resolved.kind != kind())
            throw new IOException("The source belongs to a different debrid service.");
    }

    private static JSONObject success(JSONObject object) throws IOException {
        if (!"success".equals(object.optString("status")))
            throw failure(object.optString("code", ""));
        return object;
    }

    private static IOException failure(String code) {
        if ("authentication_failed".equals(code))
            return new IOException(
                    "Premiumize API key is invalid or expired. Update it in Debrid settings.");
        if ("account_limit_reached".equals(code) || "service_limit_reached".equals(code))
            return new IOException("Premiumize account or service quota has been reached.");
        if ("rate_limit_reached".equals(code))
            return new IOException("Premiumize requests are rate limited. Wait before retrying.");
        if ("service_unsupported".equals(code) || "not_found".equals(code))
            return new IOException("Premiumize cannot resolve this source or it is unavailable.");
        return new IOException(
                "Premiumize could not complete this request. Check the source and your provider"
                        + " account.");
    }

    private static JSONArray content(JSONObject response) throws IOException {
        JSONArray content = response.optJSONArray("content");
        if (content == null || content.length() > MAX_FILES)
            throw new IOException("Premiumize returned an invalid or oversized file list.");
        return content;
    }

    private static String text(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String)
                || ((String) value).isEmpty()
                || ((String) value).length() > 16384
                || DebridModels.hasControl((String) value))
            throw new IOException("Premiumize returned invalid file metadata.");
        return (String) value;
    }

    private static String nullable(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "");
    }

    private static long size(JSONObject object) throws IOException {
        Object value = object.opt("size");
        if (!(value instanceof Number)
                || ((Number) value).longValue() < 0
                || ((Number) value).doubleValue() != ((Number) value).longValue())
            throw new IOException("Premiumize returned an invalid file size.");
        return ((Number) value).longValue();
    }

    private static String identifier(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]{1,256}"))
            throw new IOException("Premiumize returned an invalid object identifier.");
        return id;
    }

    private static String transferId(String id) throws IOException {
        if (!id.startsWith("transfer:"))
            throw new IOException("Invalid Premiumize transfer identity.");
        return identifier(id.substring(9));
    }

    private static String basename(String name) {
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }

    private static String directId(String name, long size) throws IOException {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((name + "\0" + size).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("direct:");
            for (byte value : digest)
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("Cannot bind Premiumize file identity.", impossible);
        }
    }

    private static void source(String input) throws IOException {
        if (input == null || input.length() > 8192 || DebridModels.hasControl(input))
            throw new IOException("Invalid Premiumize source.");
        if (input.regionMatches(true, 0, "magnet:?", 0, 8)) return;
        try {
            URI uri = new URI(input);
            if ("https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null) return;
        } catch (Exception invalid) {
            /* Fall through to a redacted error. */
        }
        throw new IOException("Premiumize sources must be an HTTPS link or magnet.");
    }

    private static IOException changed() {
        return new IOException("The selected Premiumize file changed. Resolve the source again.");
    }
}
