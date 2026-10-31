package app.bitchord.telegram;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Receives only the registered OAuth callback; state and PKCE are checked before accepting a grant.
 */
public final class PlaylistAuthActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        CapturePolicy.apply(this);
        TelegramStyle style = new TelegramStyle(this);
        TextView status =
                style.text(Strings.t("Completing sign-in…"), 17, style.foreground, style.regular);
        status.setPadding(style.dp(24), style.dp(32), style.dp(24), style.dp(24));
        status.setBackgroundColor(style.background);
        setContentView(status);
        if (getIntent().getData() == null) {
            finish();
            return;
        }
        PlaylistSyncEngine.IO.execute(
                () -> {
                    try {
                        new SpotifySyncAuth(getApplicationContext())
                                .complete(getIntent().getData());
                        runOnUiThread(
                                () -> {
                                    startActivity(
                                            new Intent(this, PlaylistSyncActivity.class)
                                                    .addFlags(
                                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                                    | Intent
                                                                            .FLAG_ACTIVITY_SINGLE_TOP));
                                    finish();
                                });
                    } catch (Exception invalid) {
                        runOnUiThread(
                                () -> {
                                    status.setText(
                                            Strings.t(
                                                    "Sign-in could not finish. Return to playlist"
                                                            + " sync and try connecting again."));
                                });
                    }
                });
    }
}
