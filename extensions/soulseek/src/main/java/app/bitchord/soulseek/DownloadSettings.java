package app.bitchord.soulseek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Persistent feature opt-ins for Patches & tweaks, independent of saved account credentials. */
public final class DownloadSettings {
    public static final String SOULSEEK = "soulseek";
    public static final String PEERS = "peer-downloads";
    public static final String DEBRID = "debrid";
    private static final String PREFERENCES = "bitchord_download_features";
    private static final Set<String> stopping = new HashSet<>();
    private static final Map<String, String> failures = new HashMap<>();

    private DownloadSettings() {}

    public static boolean enabled(Context context, String feature) {
        if (context == null || !known(feature)) return false;
        synchronized (DownloadSettings.class) {
            if (stopping.contains(feature) || failures.containsKey(feature)) return false;
        }
        if (SOULSEEK.equals(feature)) return SoulseekSource.enabled();
        try {
            SharedPreferences preferences = preferences(context);
            if (!preferences.contains(feature)) migrate(context, preferences, feature);
            return preferences.getBoolean(feature, false);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static void requireEnabled(Context context, String feature) throws IOException {
        if (!enabled(context, feature))
            throw new IOException("Enable " + label(feature) + " in Patches & tweaks first.");
    }

    /** Persists the gate immediately; native shutdown runs off the UI thread. */
    public static void setEnabled(Context context, String feature, boolean enabled)
            throws IOException {
        if (context == null || !known(feature)) throw new IOException("Unknown download feature.");
        Context app = context.getApplicationContext();
        SoulseekBridge bridge = null;
        if (SOULSEEK.equals(feature)) {
            // Boot and the bridge's session locks must never be acquired while holding the
            // feature-state lock: active readers check the feature state from those locks.
            SoulseekBridge.boot(app);
            bridge = SoulseekBridge.get();
            try {
                SoulseekSource.register(bridge.url());
            } catch (Exception unavailable) {
                throw new IOException("Soulseek settings could not be opened.", unavailable);
            }
        }
        boolean cleanup;
        synchronized (DownloadSettings.class) {
            if (enabled && stopping.contains(feature))
                throw new IOException(label(feature) + " is still stopping. Try again shortly.");
            if (enabled && failures.containsKey(feature))
                throw new IOException(
                        "Restart BitChord before enabling " + label(feature) + " again.");
            if (SOULSEEK.equals(feature)) {
                SoulseekSource.setEnabled(enabled);
            } else {
                try {
                    if (!preferences(app).edit().putBoolean(feature, enabled).commit())
                        throw new IOException("Download settings could not be saved. Try again.");
                } catch (RuntimeException unavailable) {
                    throw new IOException(
                            "Download settings could not be saved. Try again.", unavailable);
                }
            }
            cleanup = !enabled && stopping.add(feature);
        }
        if (enabled) {
            if (bridge != null) bridge.reconnectSaved();
            return;
        }
        if (bridge != null) bridge.beginLogin();
        if (!cleanup) return;
        Thread worker = new Thread(() -> disable(feature), "bitchord-disable-" + feature);
        worker.setDaemon(true);
        worker.start();
    }

    public static void open(Context context, String feature) throws IOException {
        open(
                context,
                feature,
                SOULSEEK.equals(feature)
                        ? "account"
                        : PEERS.equals(feature) ? "downloads" : "services");
    }

    public static void open(Context context, String feature, String action) throws IOException {
        requireEnabled(context, feature);
        String screen = null;
        if ("destinations".equals(action)) screen = "DownloadDestinationActivity";
        else if ("formats".equals(action)) screen = "AudioFormatSetupActivity";
        else if (SOULSEEK.equals(feature) && "account".equals(action)) screen = "SoulseekActivity";
        else if (PEERS.equals(feature) && "downloads".equals(action))
            screen = "BulkDownloadsActivity";
        else if (PEERS.equals(feature) && "connections".equals(action))
            screen = "NostrSetupActivity";
        else if (DEBRID.equals(feature) && "services".equals(action))
            screen = "DebridSetupActivity";
        else if (DEBRID.equals(feature) && "links".equals(action))
            screen = "DebridDownloadsActivity";
        if (screen == null) throw new IOException("Unknown download settings action.");
        try {
            context.startActivity(
                    new Intent(context, Class.forName("app.bitchord.soulseek." + screen)));
        } catch (ClassNotFoundException | RuntimeException | LinkageError unavailable) {
            throw new IOException(label(feature) + " settings could not be opened.", unavailable);
        }
    }

    private static void disable(String feature) {
        boolean failed = false;
        try {
            if (SOULSEEK.equals(feature)) {
                SoulseekSelectedDownloads.cancelAll();
                SoulseekBridge bridge = SoulseekBridge.instance;
                if (bridge != null) {
                    bridge.disconnect();
                    if (bridge.shutdownFailed())
                        throw new IOException("Soulseek did not finish stopping.");
                }
            } else if (PEERS.equals(feature)) {
                PeerDiscovery discovery = PeerDiscovery.existing();
                if (discovery != null) discovery.stop();
                BulkCoordinator coordinator = BulkCoordinator.existing();
                if (coordinator != null) coordinator.disablePeers();
                else {
                    NostrCoordinator nostr = NostrCoordinator.existing();
                    if (nostr != null) nostr.stop();
                    BulkDownloads downloads = BulkDownloads.existing();
                    if (downloads != null) downloads.pausePeers();
                    if (nostr != null && nostr.shutdownFailed())
                        throw new IOException("Coordination did not finish stopping.");
                }
                if (discovery != null && discovery.shutdownFailed())
                    throw new IOException("Peer discovery did not finish stopping.");
            } else if (DEBRID.equals(feature)) {
                DebridDownloads downloads = DebridDownloads.existing();
                if (downloads != null) downloads.stop();
            }
        } catch (Exception | LinkageError unavailable) {
            failed = true;
        } finally {
            synchronized (DownloadSettings.class) {
                if (failed) failures.put(feature, "shutdown-failed");
                stopping.remove(feature);
            }
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static synchronized void migrate(
            Context context, SharedPreferences preferences, String feature) {
        if (preferences.contains(feature)) return;
        boolean enabled = false;
        // The old provider switches were explicit opt-ins. Merely having a saved API key or
        // a coordination identity never constitutes enabling either download feature.
        if (DEBRID.equals(feature)
                && new File(
                                context.getNoBackupFilesDir(),
                                "debrid-downloads/credentials.sealed.json")
                        .isFile())
            try {
                DebridCredentials credentials = DebridCredentials.get(context);
                for (DebridProvider.Kind kind : DebridProvider.Kind.values())
                    enabled |= credentials.snapshot(kind).enabled;
            } catch (IOException | RuntimeException unavailable) {
                enabled = false;
            }
        preferences.edit().putBoolean(feature, enabled).apply();
    }

    private static boolean known(String feature) {
        return SOULSEEK.equals(feature) || PEERS.equals(feature) || DEBRID.equals(feature);
    }

    private static String label(String feature) {
        if (SOULSEEK.equals(feature)) return "Soulseek downloads";
        if (PEERS.equals(feature)) return "Peer downloads";
        if (DEBRID.equals(feature)) return "Debrid downloads";
        return "this download feature";
    }
}
