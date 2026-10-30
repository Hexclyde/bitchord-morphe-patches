package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Validated metadata received only after Nostr signature, recipient and paired-key checks. */
public final class BulkOffer {
    public final String peer, fileId, token, name, sha256, manifestSha256, manifestCid;
    public final String i2p, tor, direct, ipfsPeer, ipfsAddresses, json;
    public final long size, expires;

    private BulkOffer(String peer, String text) throws Exception {
        if (!BulkManifest.hex64(peer) || text.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new IOException("Invalid signed file offer.");
        JSONObject value = new JSONObject(text);
        if (!"app.bitchord.bulk/1".equals(value.getString("protocol")))
            throw new IOException("Unsupported file offer.");
        this.peer = peer;
        this.json = text;
        fileId = value.getString("fileId");
        token = value.getString("token");
        name = audioName(value.getString("name"));
        size = value.getLong("size");
        sha256 = value.getString("sha256");
        manifestSha256 = value.getString("manifestSha256");
        manifestCid = value.getString("manifestCid");
        expires = value.getLong("expires");
        if (!fileId.matches("[0-9a-f]{32}")
                || !BulkManifest.hex64(token)
                || !BulkManifest.hex64(sha256)
                || !BulkManifest.hex64(manifestSha256)
                || !BulkManifest.cidForHash(manifestSha256).equals(manifestCid)
                || size <= 0
                || size > BulkManifest.MAX_SIZE
                || expires <= 0) throw new IOException("Invalid file offer identity.");
        JSONObject routes = value.optJSONObject("routes");
        i2p = route(routes, "i2p", 128);
        tor = route(routes, "tor", 128);
        direct = route(routes, "direct", 2048);
        if (!i2p.isEmpty() && !i2p.matches("[a-z2-7]{52}\\.b32\\.i2p"))
            throw new IOException("Invalid I2P destination.");
        if (!tor.isEmpty() && !tor.matches("[a-z2-7]{56}\\.onion"))
            throw new IOException("Invalid Tor destination.");
        JSONObject ipfs = value.optJSONObject("ipfs");
        ipfsPeer = ipfs == null ? "" : ipfs.optString("peerId", "");
        JSONArray addresses = ipfs == null ? new JSONArray() : ipfs.optJSONArray("addresses");
        if (addresses == null) addresses = new JSONArray();
        if (ipfsPeer.length() > 160 || addresses.length() > 8)
            throw new IOException("Invalid IPFS destination.");
        for (int i = 0; i < addresses.length(); i++)
            if (addresses.getString(i).length() > 256)
                throw new IOException("Invalid IPFS address.");
        ipfsAddresses = addresses.toString();
        if (i2p.isEmpty() && tor.isEmpty() && direct.isEmpty() && ipfsPeer.isEmpty())
            throw new IOException("File offer has no route.");
    }

    public static BulkOffer verified(String trustedNostrPeer, String signedPayload)
            throws IOException {
        try {
            return new BulkOffer(trustedNostrPeer, signedPayload);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Invalid signed file offer.", error);
        }
    }

    private static String route(JSONObject routes, String key, int max) throws IOException {
        String route = routes == null ? "" : routes.optString(key, "");
        if (route.length() > max) throw new IOException("Invalid download route.");
        return route;
    }

    public static String audioName(String raw) throws IOException {
        String name =
                raw.replace('\\', '_').replace('/', '_').replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.length() > 128) name = name.substring(name.length() - 128);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty()
                || !lower.matches(
                        ".*\\.(flac|alac|wav|wave|aif|aiff|aifc|dsf|dff|opus|aac|m4a|mp4|ogg|oga|vorbis|mp3)"))
            throw new IOException("Choose a supported audio file.");
        return name;
    }
}
