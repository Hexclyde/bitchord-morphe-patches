package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.List;

/** Answers the host's addon metadata calls locally; reserved identifiers never reach HTTP. */
public final class SoulseekAddon {
    static final String ORIGIN = "https://soulseek.bitchord.invalid/";

    private SoulseekAddon() {}

    public static boolean reserved(String value) {
        try {
            return "soulseek.bitchord.invalid".equalsIgnoreCase(new URI(value).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    static URI checked(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme())
                    || !"soulseek.bitchord.invalid".equals(uri.getRawAuthority())
                    || uri.getRawFragment() != null)
                throw new IOException("Invalid internal Soulseek identifier");
            return uri;
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid internal Soulseek identifier", e);
        }
    }

    static String route(SoulseekBridge bridge, URI uri) throws IOException {
        String path = uri.getRawPath(), prefix = "/" + bridge.sessionTag + "/";
        if (path == null || !path.startsWith(prefix))
            throw new IOException("Soulseek session changed. Search again.");
        return path.substring(prefix.length());
    }

    static String audioId(SoulseekBridge bridge, String value) throws IOException {
        URI uri = checked(value);
        String path = route(bridge, uri);
        if (uri.getRawQuery() != null
                || !path.startsWith("audio/")
                || !path.substring(6).matches("[a-f0-9]{64}"))
            throw new IOException("Invalid Soulseek audio identifier");
        return path.substring(6);
    }

    public static String bodyOrNull(String value) throws Exception {
        if (!reserved(value)) return null;
        SoulseekBridge bridge = SoulseekBridge.get();
        URI uri = checked(value);
        String tag = bridge.sessionTag;
        String path = route(bridge, uri);
        if (path.equals("manifest.json"))
            return new JSONObject()
                    .put("id", "bitchord.soulseek.native")
                    .put("name", "Unofficial Soulseek")
                    .put("version", "0.1.0-dev")
                    .put(
                            "description",
                            "Download-only Soulseek source with ordered format choices. Peer"
                                    + " filenames and source provenance are unverified.")
                    .put("allowDownloads", true)
                    .put("checkValidLossless", false)
                    .toString();
        if (path.equals("search")) {
            String query = "";
            boolean seen = false;
            if (uri.getRawQuery() != null)
                for (String item : uri.getRawQuery().split("&")) {
                    String[] pair = item.split("=", 2);
                    if (pair[0].equals("q")) {
                        if (seen) throw new IOException("Duplicate search query");
                        seen = true;
                        query = URLDecoder.decode(pair.length > 1 ? pair[1] : "", "UTF-8");
                    }
                }
            List<SoulseekTrack> rows =
                    AudioFormatPreferences.ordered(
                            AudioFormatPreferences.snapshot(bridge.context), bridge.search(query));
            if (!tag.equals(bridge.sessionTag))
                throw new IOException("Soulseek session changed. Search again.");
            JSONArray tracks = new JSONArray();
            for (SoulseekTrack row : rows) tracks.put(row.metadata());
            return new JSONObject().put("tracks", tracks).toString();
        }
        if (path.startsWith("stream/")) {
            String id = path.substring(7);
            if (!id.matches("[a-f0-9]{64}"))
                throw new IOException("Invalid Soulseek track reference");
            SoulseekTrack row = bridge.track(id);
            if (!tag.equals(bridge.sessionTag))
                throw new IOException("Soulseek session changed. Search again.");
            return new JSONObject()
                    .put("url", bridge.url() + "/audio/" + id)
                    .put("format", row.format)
                    .put("formatVerified", false)
                    .put("sourceProvenance", "unverified")
                    .put("mimeType", row.mime)
                    .put("manifest", "none")
                    .put("encrypted", false)
                    .toString();
        }
        throw new IOException("Unknown internal Soulseek operation");
    }
}
