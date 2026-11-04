package app.bitchord.telegram;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The patch's page behind the row added to BitChord's Downloads settings. */
public final class DownloadsSettingsActivity extends Activity {
    static final String PICK_DIRECTORY = "pick-download-folder";
    private static final int PICK_FOLDER = 4816;
    private final ExecutorService folderWorker = Executors.newSingleThreadExecutor();
    private boolean folderBusy;
    private TelegramStyle style;

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Strings.refresh(this);
        style = new TelegramStyle(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(style.background);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(32));
        scroll.addView(body);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title =
                style.text(Strings.t("Patch downloads"), 28, style.foreground, style.heavy);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.leftMargin = style.dp(12);
        header.addView(title, titleSpace);
        body.addView(header);
        DownloadsSettings.addSection(this, body, style);
        setContentView(scroll);
        if (getIntent().getBooleanExtra(PICK_DIRECTORY, false)) {
            getIntent().removeExtra(PICK_DIRECTORY);
            chooseFolder();
        }
    }

    void chooseFolder() {
        if (folderBusy) return;
        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        String current = DownloadFolder.selected(this);
        if (!current.isEmpty())
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(current));
        try {
            startActivityForResult(intent, PICK_FOLDER);
        } catch (RuntimeException unavailable) {
            notice(Strings.t("A folder picker is not available on this device."));
        }
    }

    void saveFolder(String uri) {
        if (folderBusy) return;
        folderBusy = true;
        folderWorker.execute(
                () -> {
                    String error = null;
                    try {
                        DownloadFolder.choose(this, uri);
                    } catch (Exception failure) {
                        error = failure.getMessage();
                    }
                    final String result = error;
                    runOnUiThread(
                            () -> {
                                folderBusy = false;
                                if (isDestroyed() || isFinishing()) return;
                                if (result != null) notice(result);
                                render();
                            });
                });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null)
            return;
        int flags =
                data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (flags
                != (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            notice(
                    Strings.t(
                            "The folder must allow lasting read and write access. Choose another"
                                    + " folder."));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(data.getData(), flags);
        } catch (RuntimeException unavailable) {
            notice(
                    Strings.t(
                            "The folder must allow lasting read and write access. Choose another"
                                    + " folder."));
            return;
        }
        saveFolder(data.getData().toString());
    }

    private void notice(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        folderWorker.shutdown();
        super.onDestroy();
    }
}
