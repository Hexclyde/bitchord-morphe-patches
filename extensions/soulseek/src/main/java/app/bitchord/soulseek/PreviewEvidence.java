package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.*;
import java.security.MessageDigest;

/** Describes exactly what was checked; a self-calculated checksum never asserts authenticity. */
final class PreviewEvidence {
    private PreviewEvidence() {}

    static JSONObject flacHeader(byte[] bytes) throws Exception {
        if (bytes.length < 38
                || bytes[0] != 'f'
                || bytes[1] != 'L'
                || bytes[2] != 'a'
                || bytes[3] != 'C'
                || (bytes[4] & 127) != 0
                || (bytes[5] & 255) != 0
                || (bytes[6] & 255) != 0
                || (bytes[7] & 255) != 34) return null;
        int rate = ((bytes[18] & 255) << 12) | ((bytes[19] & 255) << 4) | ((bytes[20] & 255) >> 4);
        int channels = ((bytes[20] >> 1) & 7) + 1,
                bits = (((bytes[20] & 1) << 4) | ((bytes[21] & 255) >> 4)) + 1;
        long samples =
                ((long) (bytes[21] & 15) << 32)
                        | ((long) (bytes[22] & 255) << 24)
                        | ((long) (bytes[23] & 255) << 16)
                        | ((bytes[24] & 255) << 8)
                        | (bytes[25] & 255);
        if (rate < 1 || rate > 655350 || bits < 4 || bits > 32) return null;
        return new JSONObject()
                .put("codec", "flac")
                .put("bit_depth", bits)
                .put("sample_rate_hz", rate)
                .put("channels", channels)
                .put("header_duration_seconds", samples / (double) rate)
                .put("evidence", "FLAC STREAMINFO header")
                .put("lossless_origin_verified", false);
    }

    static String sha256(File file, long expected, PreviewDecode.Check check) throws Exception {
        if (file == null || file.length() != expected)
            throw new IOException("Complete preview file is unavailable");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = in.read(buffer)) != -1) {
                check.check();
                count += length;
                if (count > expected) throw new IOException("Preview file size changed");
                digest.update(buffer, 0, length);
            }
        }
        check.check();
        if (count != expected || file.length() != expected)
            throw new IOException("Preview file size changed");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest())
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    static JSONObject report(
            SoulseekTrack track,
            long received,
            JSONObject header,
            JSONObject match,
            String sha,
            boolean confirmed)
            throws Exception {
        if (sha != null && !sha.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid file checksum");
        if (sha != null && received != track.size)
            throw new IOException("A complete file is required for its checksum report");
        if (confirmed && sha == null)
            throw new IOException("A complete file checksum is required for confirmation");
        JSONObject report =
                new JSONObject()
                        .put("schema", "bitchord.streamseek.evidence.v1")
                        .put("created_at_ms", System.currentTimeMillis())
                        .put(
                                "selection",
                                new JSONObject()
                                        .put("id", track.id)
                                        .put("peer", track.peer)
                                        .put("remote_path", track.path)
                                        .put("advertised_bytes", track.size)
                                        .put("advertised_format", track.advertisedExtension))
                        .put("received_bytes", Math.max(0, Math.min(track.size, received)))
                        .put("perfect_match_verified", false)
                        .put("trusted_reference_checksum_compared", false)
                        .put("lossless_origin_verified", false);
        if (header != null) report.put("inspected_audio", new JSONObject(header.toString()));
        if (match != null) report.put("audio_comparison", new JSONObject(match.toString()));
        if (sha != null) report.put("complete_file_sha256", sha);
        report.put(
                "user_attestation",
                new JSONObject()
                        .put("confirmed_correct_recording", confirmed)
                        .put(
                                "scope",
                                confirmed
                                        ? "User listening judgement bound to complete_file_sha256"
                                        : "Not confirmed")
                        .put("independent_certification", false));
        return report;
    }
}
