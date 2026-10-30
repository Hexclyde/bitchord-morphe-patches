package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Screen-service fixtures deliberately do not load native code or contact peers. */
final class SoulseekBridge {
    static SoulseekBridge instance;
    final Context context;
    final ExecutorService work =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "soulseek-ui-fixture");
                        thread.setDaemon(true);
                        return thread;
                    });
    final Credentials credentials = new Credentials();
    final List<SoulseekTrack> searchRows = new ArrayList<>();
    volatile String username = "fixture-user", status = "Connected", sessionTag = "fixture-session";
    volatile boolean connected = true, searchFailure, ignoreInterrupts;
    volatile int searches, logins, forgets, clears;
    volatile int lastLoginMode = -1;
    volatile CountDownLatch loginBlock, loginEntered;
    volatile CountDownLatch searchBlock, searchEntered;
    String lastQuery;

    private SoulseekBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    static SoulseekBridge reset(Context context) {
        if (instance != null) instance.work.shutdownNow();
        instance = new SoulseekBridge(context);
        return instance;
    }

    static void boot(Context context) {
        if (instance == null) reset(context);
    }

    static SoulseekBridge get() throws IOException {
        if (instance == null) throw new IOException("Soulseek is unavailable. Restart the app.");
        return instance;
    }

    long ready() throws IOException {
        if (!DownloadSettings.soulseek) throw new IOException("Enable Unofficial Soulseek first.");
        if (!connected) throw new IOException("Sign in to Soulseek first.");
        return 1;
    }

    List<SoulseekTrack> search(String query) throws Exception {
        ready();
        searches++;
        lastQuery = query;
        if (searchEntered != null) searchEntered.countDown();
        CountDownLatch waiting = searchBlock;
        if (waiting != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (waiting.getCount() > 0 && System.nanoTime() < deadline) {
                try {
                    waiting.await(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException stopped) {
                    if (!ignoreInterrupts) throw stopped;
                }
            }
            if (waiting.getCount() != 0) throw new IOException("fixture search timed out");
        }
        if (searchFailure) throw new IOException("fixture-peer-secret must not be displayed");
        return Collections.unmodifiableList(new ArrayList<>(searchRows));
    }

    SoulseekTrack track(String id) throws IOException {
        ready();
        for (SoulseekTrack track : searchRows) if (track.id.equals(id)) return track;
        throw new IOException("Soulseek result expired. Search again.");
    }

    long beginLogin() {
        return ++logins;
    }

    void login(String username, String secret, int mode, long intent) throws Exception {
        if (loginEntered != null) loginEntered.countDown();
        if (loginBlock != null && !loginBlock.await(5, TimeUnit.SECONDS))
            throw new IOException("fixture login timed out");
        if (intent != logins || !DownloadSettings.soulseek) return;
        this.username = username;
        lastLoginMode = mode;
        if (mode == 1) credentials.saved = new String[] {username, secret};
        else if (mode == 2) credentials.saved = null;
        connected = true;
        status = "Connected";
    }

    void forget() {
        forgets++;
        logins++;
        credentials.saved = null;
        credentials.unreadable = false;
        connected = false;
        username = "";
        status = "Not signed in";
    }

    void clearTemporaryCache() {
        clears++;
    }

    static final class Credentials {
        String[] saved = {"fixture-user", "fixture-password"};
        boolean unreadable;

        String[] load() throws IOException {
            if (unreadable) throw new IOException("fixture protected storage failure");
            return saved;
        }
    }
}

final class DownloadSettings {
    static final String SOULSEEK = "soulseek", PEERS = "peer-downloads", DEBRID = "debrid";
    static boolean soulseek = true, peers = true, debrid = true;

    static void reset() {
        soulseek = peers = debrid = true;
    }

    static boolean enabled(Context context, String feature) {
        return SOULSEEK.equals(feature)
                ? soulseek
                : PEERS.equals(feature) ? peers : DEBRID.equals(feature) && debrid;
    }

    static void setEnabled(Context context, String feature, boolean enabled) {
        if (SOULSEEK.equals(feature)) soulseek = enabled;
        else if (PEERS.equals(feature)) peers = enabled;
        else if (DEBRID.equals(feature)) debrid = enabled;
    }

    static void requireEnabled(Context context, String feature) throws IOException {
        if (!enabled(context, feature)) throw new IOException("Enable the feature first.");
    }
}

final class DownloadDestinations {
    static final String PHONE = "phone", TELEGRAM = "telegram", BOTH = "both";
    static boolean configured = true;
    static String mode = PHONE;
    static int opened;

    static Snapshot snapshot(Context context) throws IOException {
        if (!configured) throw new IOException("Choose where download copies go first");
        return new Snapshot(mode);
    }

    static boolean ensureConfigured(Activity activity) {
        if (configured) return true;
        openSettings(activity);
        return false;
    }

    static void openSettings(Activity activity) {
        opened++;
        activity.startActivity(new Intent(activity, DownloadDestinationActivity.class));
    }

    static final class Snapshot {
        final String mode;

        Snapshot(String mode) {
            this.mode = mode;
        }
    }
}

final class SoulseekSelectedDownloads {
    static final List<String> selectedReferences = Collections.synchronizedList(new ArrayList<>());
    static boolean accepted = true;

    static boolean enqueueSelected(Context context, String reference) {
        selectedReferences.add(reference);
        return accepted;
    }

    static String status() {
        return "";
    }

    static int activeCount() {
        return selectedReferences.size();
    }
}

final class DownloadDestinationActivity extends Activity {}

final class BulkDownloadsActivity extends Activity {}

final class NostrSetupActivity extends Activity {}

// Navigation endpoint only; production explorer contracts have an independent runner.
final class DownloadExplorerActivity {}
