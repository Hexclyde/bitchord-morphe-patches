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
    final int duration, bitrate, sampleRate, bitDepth, variableBitrate;
    final long queueLength;
    final java.util.Map<String, Long> attributes;
    // Untrusted advertisements: slots is a boolean flag, not a capacity count.
    // -1 means unknown. Speed is bytes/second; zero means unknown.
    final int freeSlots;
    final long speed;

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
                "flac|alac|wav|wave|rf64|w64|aif|aiff|aifc|dsf|dff|dsdiff|ape|wv|tta|tak|shn|wma|caf|mka|dts|dtshd|thd|mlp|opus|aac|m4a|mp4|ogg|oga|vorbis|mp3"))
            throw new IOException("Unsupported advertised audio filename");
        // These remain peer claims. Ambiguous containers do not acquire a codec
        // label until the completed, selected bytes have been probed.
        switch (advertisedExtension) {
            case "m4a":
            case "mp4":
            case "ogg":
            case "oga":
            case "caf":
            case "mka":
            case "wma":
                format = "unknown";
                break;
            case "wave":
            case "rf64":
            case "w64":
                format = "wav";
                break;
            case "aif":
            case "aifc":
                format = "aiff";
                break;
            case "dsf":
            case "dff":
            case "dsdiff":
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
        freeSlots = (int) unsignedHint(row.opt("freeSlots"), 1, -1);
        speed = unsignedHint(row.opt("speed"), 0xffffffffL, 0);
        sampleRate = (int) unsignedHint(row.opt("sampleRate"), 50_000_000, 0);
        bitDepth = (int) unsignedHint(row.opt("bitDepth"), 64, 0);
        variableBitrate = (int) unsignedHint(row.opt("variableBitrate"), 1, -1);
        queueLength = unsignedHint(row.opt("queueLength"), 0xffffffffL, -1);
        java.util.TreeMap<String, Long> values = new java.util.TreeMap<>();
        JSONObject advertised = row.optJSONObject("attributes");
        if (advertised != null) {
            java.util.Iterator<String> keys = advertised.keys();
            while (keys.hasNext() && values.size() < 32) {
                String key = keys.next();
                if (!key.matches("[0-9]{1,10}")) continue;
                long value = unsignedHint(advertised.opt(key), 0xffffffffL, -1);
                if (value >= 0) values.put(key, value);
            }
        }
        attributes = java.util.Collections.unmodifiableMap(values);

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
            case "rf64":
            case "w64":
                mime = "audio/wav";
                break;
            case "aif":
            case "aiff":
            case "aifc":
                mime = "audio/aiff";
                break;
            case "dsf":
            case "dff":
            case "dsdiff":
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
            case "mp3":
                mime = "audio/mpeg";
                break;
            default:
                mime = "application/octet-stream";
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
                .put("freeSlots", freeSlots < 0 ? JSONObject.NULL : freeSlots)
                .put("speed", speed)
                .put("sampleRate", sampleRate == 0 ? JSONObject.NULL : sampleRate)
                .put("bitDepth", bitDepth == 0 ? JSONObject.NULL : bitDepth)
                .put(
                        "variableBitrate",
                        variableBitrate < 0 ? JSONObject.NULL : variableBitrate == 1)
                .put("queueLength", queueLength < 0 ? JSONObject.NULL : queueLength)
                .put("attributes", new JSONObject(attributes))
                .put("peerHintsVerified", false)
                .put(
                        "formatLabel",
                        format.equals("unknown")
                                ? "Unknown / unverified codec"
                                : format.toUpperCase(Locale.ROOT) + " (unverified)")
                .put("sourceProvenance", "unverified");
    }

    private static long unsignedHint(Object raw, long max, long unknown) {
        if (!(raw instanceof Number)) return unknown;
        double number = ((Number) raw).doubleValue();
        if (Double.isNaN(number)
                || Double.isInfinite(number)
                || number < 0
                || number > max
                || number != Math.rint(number)) return unknown;
        return (long) number;
    }
}
