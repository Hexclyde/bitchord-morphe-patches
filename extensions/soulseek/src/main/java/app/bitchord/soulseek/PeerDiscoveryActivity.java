package app.bitchord.soulseek;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Optional public candidates. Finding a key never pairs with it or grants file access. */
public final class PeerDiscoveryActivity extends DownloadActivity {
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    action -> new Thread(action, "peer-discovery-settings"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong revision = new AtomicLong();
    private volatile PeerDiscovery discovery;
    private volatile boolean foreground = true, destroyed;
    private PeerDiscovery.Snapshot saved;
    private boolean busy, rendering, expanded, refreshQueued;
    private int visibleLimit = 50;
    private String renderedCandidates = "";
    private LinearLayout card, details, candidateRows;
    private CompoundButton enabled, advertise;
    private ImageButton gear;
    private Button find, stop, clear, more;
    private TextView summary, notice, status, counts, showing;
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    if (!foreground || destroyed) return;
                    if (!busy && !refreshQueued) readSnapshot();
                    handler.postDelayed(this, 1000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        if (state != null) {
            expanded = state.getBoolean("expanded");
            visibleLimit = Math.max(50, Math.min(512, state.getInt("visible", 50)));
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        scroll.addView(body);
        setContentView(scroll);
        text(body, PatchStrings.t("Peer discovery"), 28, false);
        text(
                body,
                PatchStrings.t(
                        "Find compatible clients through public relays and peer exchange. Found"
                            + " keys are not trusted contacts and do not grant access to files."),
                15,
                false);
        notice = text(body, PatchStrings.t("Loading peer discovery…"), 14, false);

        card = column();
        downloadStyle.card(card);
        body.addView(card);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(downloadStyle.dp(64));
        card.addView(header);
        LinearLayout labels = column();
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        text(labels, PatchStrings.t("Discover compatible peers"), 17, true);
        summary = text(labels, PatchStrings.t("Public discovery and peer exchange"), 13, false);
        enabled = downloadStyle.switchButton();
        enabled.setTag("discovery-enabled");
        enabled.setContentDescription(
                PatchStrings.f("Enable {0}", PatchStrings.t("Peer discovery")));
        header.addView(
                enabled, new LinearLayout.LayoutParams(downloadStyle.dp(52), downloadStyle.dp(48)));
        gear = downloadStyle.settingsButton();
        gear.setTag("discovery-gear");
        gear.setContentDescription(PatchStrings.t("Peer discovery settings"));
        header.addView(
                gear, new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
        details = column();
        details.setTag("discovery-details");
        details.setContentDescription(PatchStrings.t("Peer discovery controls"));
        card.addView(details);
        LinearLayout publishing = new LinearLayout(this);
        publishing.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(publishing);
        TextView publishLabel =
                text(null, PatchStrings.t("Discoverable while this page is open"), 16, true);
        publishing.addView(publishLabel, new LinearLayout.LayoutParams(0, -2, 1));
        advertise = downloadStyle.switchButton();
        advertise.setTag("discovery-advertise");
        advertise.setContentDescription(PatchStrings.t("Discoverable while this page is open"));
        publishing.addView(
                advertise,
                new LinearLayout.LayoutParams(downloadStyle.dp(52), downloadStyle.dp(48)));
        text(
                details,
                PatchStrings.t(
                        "Publishes only your app public key and supported transports. It does not"
                                + " publish your files, private contacts or connection addresses."),
                14,
                false);
        find = button(details, PatchStrings.t("Find peers"));
        find.setTag("discovery-find");
        stop = button(details, PatchStrings.t("Stop discovery"));
        stop.setTag("discovery-stop");
        clear = button(details, PatchStrings.t("Clear found peers"));
        clear.setTag("discovery-clear");
        status = text(details, "", 14, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        counts = text(details, "", 13, false);
        text(details, PatchStrings.t("Recent clients"), 17, true);
        candidateRows = column();
        details.addView(candidateRows);
        showing = text(details, "", 13, false);
        more = button(details, PatchStrings.t("Show more"));
        more.setTag("discovery-more");

        enabled.setOnCheckedChangeListener(
                (view, on) -> {
                    if (rendering) return;
                    render();
                    task(service -> service.setEnabled(on), on);
                });
        advertise.setOnCheckedChangeListener(
                (view, on) -> {
                    if (rendering) return;
                    render();
                    task(service -> service.setAdvertise(on), false);
                });
        gear.setOnClickListener(
                view -> {
                    expanded = !expanded;
                    render();
                });
        find.setOnClickListener(view -> task(PeerDiscovery::startScan, false));
        stop.setOnClickListener(view -> task(PeerDiscovery::stop, false));
        clear.setOnClickListener(view -> task(PeerDiscovery::clearCache, false));
        more.setOnClickListener(
                view -> {
                    visibleLimit = Math.min(512, visibleLimit + 50);
                    renderCandidates();
                });
        render();
        readSnapshot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        foreground = false;
        revision.incrementAndGet();
        busy = false;
        refreshQueued = false;
        handler.removeCallbacks(refresh);
        // This barrier follows any in-flight action on the same worker. Stale queued starts
        // check the revision before invoking the service and cannot reopen it after this stop.
        // Discard pending UI work; an earlier pending stop is superseded by this barrier.
        if (!worker.isShutdown()) worker.getQueue().clear();
        try {
            worker.execute(
                    () -> {
                        PeerDiscovery service = discovery;
                        if (service != null) service.stop();
                    });
        } catch (RejectedExecutionException destroyedAlready) {
            /* Destruction preserves its prior stop barrier. */
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        // Preserve the onStop cleanup barrier rather than interrupting it during destruction.
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("expanded", expanded);
        state.putInt("visible", visibleLimit);
        super.onSaveInstanceState(state);
    }

    private void readSnapshot() {
        if (!foreground || destroyed || refreshQueued) return;
        refreshQueued = true;
        long intent = revision.get();
        try {
            worker.execute(
                    () -> {
                        PeerDiscovery.Snapshot result = null;
                        boolean failed = false;
                        try {
                            if (!active(intent)) return;
                            if (discovery == null)
                                discovery = PeerDiscovery.get(getApplicationContext());
                            result = discovery.snapshot();
                        } catch (Exception | LinkageError error) {
                            failed = true;
                        } finally {
                            final PeerDiscovery.Snapshot snapshot = result;
                            final boolean failure = failed;
                            handler.post(
                                    () -> {
                                        if (!active(intent)) return;
                                        refreshQueued = false;
                                        boolean loading = saved == null;
                                        if (snapshot != null) saved = snapshot;
                                        if (failure) failure();
                                        else if (loading && !busy) notice.setText("");
                                        render();
                                    });
                        }
                    });
        } catch (RejectedExecutionException full) {
            refreshQueued = false;
        }
    }

    private interface Action {
        void run(PeerDiscovery service) throws Exception;
    }

    private void task(Action action, boolean expandOnSuccess) {
        if (busy || discovery == null || !foreground || destroyed) {
            render();
            return;
        }
        busy = true;
        long intent = revision.incrementAndGet();
        // A pending refresh belongs to the prior revision and cannot update these controls.
        refreshQueued = false;
        notice.setText(PatchStrings.t("Working…"));
        render();
        try {
            worker.execute(
                    () -> {
                        if (!active(intent)) return;
                        boolean success = false;
                        try {
                            action.run(discovery);
                            success = true;
                        } catch (Exception | LinkageError error) {
                            /* Report without internal or profile data. */
                        }
                        PeerDiscovery.Snapshot snapshot = discovery.snapshot();
                        final boolean complete = success;
                        handler.post(
                                () -> {
                                    if (!active(intent)) return;
                                    busy = false;
                                    saved = snapshot;
                                    if (complete) {
                                        notice.setText("");
                                        if (expandOnSuccess && saved.enabled) expanded = true;
                                    } else failure();
                                    render();
                                });
                    });
        } catch (RejectedExecutionException full) {
            busy = false;
            failure();
            render();
        }
    }

    private boolean active(long intent) {
        return foreground && !destroyed && revision.get() == intent;
    }

    private void failure() {
        notice.setText(
                PatchStrings.t(
                        "Could not update peer discovery. Check connection settings and try"
                                + " again."));
    }

    private void render() {
        boolean on = saved != null && saved.enabled;
        rendering = true;
        enabled.setChecked(on);
        advertise.setChecked(saved != null && saved.advertise);
        rendering = false;
        enabled.setEnabled(saved != null && !busy && foreground);
        if (saved != null && !on) expanded = false;
        details.setVisibility(on && expanded ? View.VISIBLE : View.GONE);
        gear.setEnabled(on && !busy && foreground);
        gear.setAlpha(gear.isEnabled() ? 1 : .35f);
        gear.setSelected(on && expanded);
        advertise.setEnabled(on && !busy && foreground);
        find.setEnabled(on && !busy && !saved.running && foreground);
        stop.setEnabled(on && !busy && saved.running && foreground);
        clear.setEnabled(on && !busy && foreground);
        summary.setText(
                on ? PatchStrings.t("Public discovery and peer exchange") : PatchStrings.t("Off"));
        if (saved != null) {
            String label = PatchStrings.t(saved.status);
            if (!label.contentEquals(status.getText())) status.setText(label);
            counts.setText(
                    PatchStrings.f(
                            "Relay connections: {0} · Profiles received: {1}",
                            saved.connectedRelays, saved.received));
        }
        renderCandidates();
    }

    private void renderCandidates() {
        if (saved == null) return;
        List<PeerDiscovery.Candidate> found = saved.candidates;
        int shown = Math.min(visibleLimit, found.size());
        StringBuilder signature =
                new StringBuilder().append(shown).append('/').append(found.size());
        for (int index = 0; index < shown; index++) {
            PeerDiscovery.Candidate peer = found.get(index);
            signature
                    .append('|')
                    .append(peer.key)
                    .append(':')
                    .append(peer.expires)
                    .append(peer.transports);
        }
        showing.setText(
                found.isEmpty() ? "" : PatchStrings.f("Showing {0} of {1}", shown, found.size()));
        more.setVisibility(shown < found.size() ? View.VISIBLE : View.GONE);
        if (signature.toString().equals(renderedCandidates)) return;
        renderedCandidates = signature.toString();
        View focused = getCurrentFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        candidateRows.removeAllViews();
        if (found.isEmpty())
            text(candidateRows, PatchStrings.t("No recent clients found yet."), 14, false);
        DateFormat format = DateFormat.getTimeInstance(DateFormat.SHORT, PatchStrings.locale());
        for (int index = 0; index < shown; index++) {
            PeerDiscovery.Candidate peer = found.get(index);
            LinearLayout row = column();
            row.setPadding(0, downloadStyle.dp(12), 0, downloadStyle.dp(12));
            candidateRows.addView(row);
            String key = peer.key;
            TextView identity =
                    text(
                            row,
                            key.substring(0, 12) + "…" + key.substring(key.length() - 8),
                            15,
                            true);
            identity.setTextDirection(View.TEXT_DIRECTION_LTR);
            identity.setTextIsSelectable(true);
            text(
                    row,
                    PatchStrings.f("Transports: {0}", transportLabels(peer.transports)),
                    13,
                    false);
            text(
                    row,
                    PatchStrings.f(
                            "Recent · expires at {0}",
                            format.format(new Date(peer.expires * 1000))),
                    13,
                    false);
            Button copy = button(row, PatchStrings.t("Copy key for pairing"));
            copy.setTag("copy-peer:" + key);
            copy.setOnClickListener(
                    view -> {
                        ClipboardManager clipboard =
                                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(
                                    ClipData.newPlainText("BitChord public key", key));
                            notice.setText(
                                    PatchStrings.t(
                                            "Peer key copied. Compare the full key before"
                                                    + " pairing."));
                        }
                    });
            if (copy.getTag().equals(focusTag)) copy.requestFocus();
        }
        downloadStyle.applyTree(candidateRows);
    }

    private String transportLabels(List<String> transports) {
        StringBuilder labels = new StringBuilder();
        for (String transport : transports) {
            String label;
            switch (transport) {
                case "direct":
                    label = PatchStrings.t("Direct UDP / QUIC");
                    break;
                case "tor":
                    label = "Tor";
                    break;
                case "i2p":
                    label = "I2P";
                    break;
                case "ipfs":
                    label = "IPFS";
                    break;
                default:
                    continue;
            }
            if (labels.length() > 0) labels.append(" · ");
            labels.append(label);
        }
        return labels.toString();
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView text(LinearLayout parent, String value, int size, boolean prominent) {
        TextView view =
                downloadStyle.text(
                        value,
                        size,
                        prominent || size >= 24
                                ? downloadStyle.foreground
                                : downloadStyle.secondary,
                        size >= 24
                                ? downloadStyle.heavy
                                : prominent ? downloadStyle.medium : downloadStyle.regular);
        view.setPadding(0, downloadStyle.dp(8), 0, downloadStyle.dp(8));
        if (parent != null) parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String title) {
        Button view = new Button(this);
        view.setText(title);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        downloadStyle.button(view);
        return view;
    }
}
