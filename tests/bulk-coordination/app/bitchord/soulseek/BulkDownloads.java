package app.bitchord.soulseek;

import android.content.Context;

import java.io.IOException;

final class BulkDownloads {
    static BulkDownloads instance = new BulkDownloads();

    static final class Share {
        final String fileId, json;

        Share(String fileId, String json) {
            this.fileId = fileId;
            this.json = json;
        }

        String offerJson() {
            return json;
        }
    }

    int queued, authorized, recipientGrants, punches;
    boolean punchFails;
    DownloadDestinations.Snapshot lastDestination;
    String lastFormatPolicy;

    static BulkDownloads get(Context context) {
        return instance;
    }

    void stop() {}

    void prepareRoutes(String peer, String offerJson) {}

    void authorizeRecipient(String fileId, String peer) {
        recipientGrants++;
    }

    String peerInfo() {
        return "{\"peerId\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}";
    }

    String selfDirectDescriptor() {
        return "{\"candidates\":[]}";
    }

    void authorize(String fileId, String peer, String peerId) {
        authorized++;
    }

    void onDirectCandidates(String peer, String fileId, String descriptor) throws IOException {
        punches++;
        if (punchFails) throw new IOException("NAT failed");
    }

    String queue(
            String peer, String json, DownloadDestinations.Snapshot destination, String formats) {
        queued++;
        lastDestination = destination;
        lastFormatPolicy = formats;
        return "job-" + queued;
    }

    void revokeShare(String fileId) {}
}
