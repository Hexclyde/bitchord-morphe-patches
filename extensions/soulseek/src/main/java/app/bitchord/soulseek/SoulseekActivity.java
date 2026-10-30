package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Account settings for the download-only Soulseek source. */
public final class SoulseekActivity extends Activity {
    private SoulseekBridge bridge;
    private EditText username, password;
    private TextView status;
    private Button connect;
    private CheckBox remember;
    private boolean destinationSetupAsked, formatSetupAsked;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh =
            new Runnable() {
                public void run() {
                    if (bridge != null && status != null) status.setText(bridge.status);
                    handler.postDelayed(this, 500);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        SoulseekBridge.boot(getApplicationContext());
        try {
            bridge = SoulseekBridge.get();
        } catch (Exception e) {
            finish();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        setContentView(scroll);
        Button back = button(body, "Back");
        back.setOnClickListener(v -> finish());
        text(body, "Unofficial Soulseek downloads", 24);
        text(
                body,
                "Download audio using your chosen formats and copy destinations. No custom server"
                        + " or separate Seeker app is required.",
                16);
        text(
                body,
                "Sign in, enable Unofficial Soulseek in Sources, then search in Addons and choose"
                        + " Download from the track menu. This source does not play live audio or"
                        + " replace YouTube. Use Peer downloads to share selected files with a"
                        + " paired phone.",
                16);
        username = new EditText(this);
        username.setHint("Soulseek username");
        username.setSingleLine(true);
        username.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        username.setText(bridge.username);
        username.setSaveEnabled(false);
        body.addView(username);
        password = new EditText(this);
        password.setHint("Soulseek password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) {
            username.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        body.addView(password);
        remember = new CheckBox(this);
        remember.setText("Remember this account on this phone (encrypted)");
        remember.setChecked(true);
        body.addView(remember);
        connect = button(body, "Sign in");
        connect.setOnClickListener(
                v -> {
                    String user = username.getText().toString().trim();
                    String secret = password.getText().toString();
                    boolean save = remember.isChecked();
                    password.setText("");
                    connect.setEnabled(false);
                    final long intent = bridge.beginLogin();
                    bridge.work.execute(
                            () -> {
                                try {
                                    bridge.login(user, secret, save ? 1 : 2, intent);
                                } catch (Throwable ignored) {
                                    /* Bridge sets a redacted, actionable status. */
                                }
                                runOnUiThread(
                                        () -> {
                                            if (!isFinishing()) connect.setEnabled(true);
                                        });
                            });
                });
        button(body, "Reconnect saved account")
                .setOnClickListener(
                        v -> {
                            final long intent = bridge.beginLogin();
                            bridge.work.execute(
                                    () -> {
                                        try {
                                            String[] saved = bridge.credentials.load();
                                            if (saved == null) {
                                                bridge.status =
                                                        "No saved account. Enter your username and"
                                                                + " password.";
                                                return;
                                            }
                                            bridge.login(saved[0], saved[1], 0, intent);
                                        } catch (Throwable e) {
                                            bridge.status =
                                                    "Saved account could not connect. Enter the"
                                                            + " account again to retry.";
                                        }
                                    });
                        });
        button(body, "Sign out and forget account")
                .setOnClickListener(
                        v -> {
                            password.setText("");
                            // A separate control worker can cancel a connect/search still on the IO
                            // worker.
                            new Thread(
                                            () -> {
                                                try {
                                                    bridge.forget();
                                                } catch (Throwable e) {
                                                    bridge.status =
                                                            "Signed out, but credentials could not"
                                                                + " be cleared. Retry forgetting"
                                                                + " the account.";
                                                }
                                            },
                                            "soulseek-signout")
                                    .start();
                        });
        button(body, "Clear temporary audio cache and disconnect")
                .setOnClickListener(
                        v -> {
                            bridge.beginLogin(); // Invalidate older queued sign-ins before this
                            // explicit action.
                            bridge.work.execute(
                                    () -> {
                                        try {
                                            bridge.clearTemporaryCache();
                                        } catch (Exception e) {
                                            bridge.status =
                                                    "Cache not cleared. Stop Soulseek downloads and"
                                                        + " retry. If the engine could not stop,"
                                                        + " restart BitChord first.";
                                        }
                                    });
                        });
        status = text(body, bridge.status, 16);
        button(body, "Debrid services and API keys")
                .setOnClickListener(
                        v -> startActivity(new Intent(this, DebridSetupActivity.class)));
        button(body, "Debrid link downloads")
                .setOnClickListener(
                        v -> startActivity(new Intent(this, DebridDownloadsActivity.class)));
        button(body, "Download connection setup")
                .setOnClickListener(v -> startActivity(new Intent(this, NostrSetupActivity.class)));
        button(body, "Peer downloads")
                .setOnClickListener(
                        v -> startActivity(new Intent(this, BulkDownloadsActivity.class)));
        button(body, "Where copies go")
                .setOnClickListener(v -> DownloadDestinations.openSettings(this));
        button(body, "Formats, best first")
                .setOnClickListener(v -> AudioFormatPreferences.openSettings(this));
        text(
                body,
                "Peer queues and mobile NAT can prevent connections. Search takes up to 12 seconds."
                    + " Downloads wait up to 30 seconds for peer data. Temporary storage is limited"
                    + " to 256 MiB. Format labels come from peer filenames and do not prove an"
                    + " original lossless source.",
                14);
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 10, 0, 10);
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String title) {
        Button view = new Button(this);
        view.setText(title);
        parent.addView(view);
        return view;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
        if (!destinationSetupAsked) {
            destinationSetupAsked = true;
            if (!DownloadDestinations.ensureConfigured(this)) return;
        }
        if (!formatSetupAsked) {
            try {
                DownloadDestinations.snapshot(this);
            } catch (Exception unconfigured) {
                return;
            }
            formatSetupAsked = true;
            AudioFormatPreferences.ensureConfigured(this);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (password != null) password.setText("");
        super.onDestroy();
    }
}
