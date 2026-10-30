package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Peer metadata is untrusted. These fields describe advertisements, not verified provenance. */
final class SoulseekTrack {
    final String id, peer, path, format, advertisedExtension, title, artist, mime;
    final long size;
    final int duration, bitrate;

    SoulseekTrack(JSONObject row) throws Exception {
        peer = row.getString("peer");
        path = row.getString("path");
        size = row.getLong("size");
        if (peer.isEmpty()
                || peer.length() > 128
                || path.isEmpty()
                || path.length() > 4096
                || size <= 0
                || size > 2L * 1024 * 1024 * 1024
                || peer.indexOf('\0') >= 0
                || path.indexOf('\0') >= 0) throw new IOException("Invalid Soulseek result");
        int dot = path.lastIndexOf('.');
        advertisedExtension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!advertisedExtension.matches(
                "flac|alac|wav|wave|aif|aiff|aifc|dsf|dff|opus|aac|m4a|mp4|ogg|oga|vorbis|mp3"))
            throw new IOException("Unsupported advertised audio filename");
        // These remain peer claims. Ambiguous containers do not acquire a codec
        // label until the completed, selected bytes have been probed.
        switch (advertisedExtension) {
            case "m4a":
            case "mp4":
            case "ogg":
            case "oga":
                format = "unknown";
                break;
            case "wave":
                format = "wav";
                break;
            case "aif":
            case "aifc":
                format = "aiff";
                break;
            case "dsf":
            case "dff":
                format = "dsd";
                break;
            default:
                format = advertisedExtension;
        }
        String file = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        String name = file.substring(0, file.length() - advertisedExtension.length() - 1);
        int separator = name.indexOf(" - ");
        artist = separator > 0 ? name.substring(0, separator) : "Unknown artist";
        title = separator > 0 ? name.substring(separator + 3) : name;
        duration = Math.max(0, Math.min(row.optInt("duration"), 86400));
        bitrate = Math.max(0, Math.min(row.optInt("bitrate"), 100000));
        switch (advertisedExtension) {
            case "flac":
                mime = "audio/flac";
                break;
            case "m4a":
            case "mp4":
            case "alac":
                mime = "audio/mp4";
                break;
            case "wav":
            case "wave":
                mime = "audio/wav";
                break;
            case "aif":
            case "aiff":
            case "aifc":
                mime = "audio/aiff";
                break;
            case "dsf":
            case "dff":
                mime = "audio/dsd";
                break;
            case "opus":
                mime = "audio/opus";
                break;
            case "aac":
                mime = "audio/aac";
                break;
            case "ogg":
            case "oga":
            case "vorbis":
                mime = "audio/ogg";
                break;
            default:
                mime = "audio/mpeg";
        }
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(peer.getBytes(StandardCharsets.UTF_8));
        hash.update((byte) 0);
        hash.update(path.getBytes(StandardCharsets.UTF_8));
        hash.update((byte) 0);
        byte[] digest = hash.digest(Long.toString(size).getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value & 255));
        id = key.toString();
    }

    JSONObject metadata() throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("artist", artist)
                .put("duration", duration)
                .put("format", format)
                .put("formatVerified", false)
                .put(
                        "formatLabel",
                        format.equals("unknown")
                                ? "Unknown / unverified codec"
                                : format.toUpperCase(Locale.ROOT) + " (unverified)")
                .put("sourceProvenance", "unverified");
    }
}
