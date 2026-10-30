package app.bitchord.soulseek;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Resolves every selected destination before completing the first download setup. */
public final class DownloadDestinationActivity extends DownloadActivity {
    private static final int PICK_FOLDER = 8127;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> actions = new ArrayList<>();
    private volatile boolean destroyed;
    private boolean busy;
    private String mode = "", phoneTree = "", telegramTitle = "";
    private long telegramChatId, telegramUserId;
    private RadioGroup choices;
    private LinearLayout phoneSection, telegramSection;
    private TextView phoneSummary, telegramSummary, notice;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        if (state != null) {
            mode = state.getString("mode", "");
            phoneTree = state.getString("phoneTree", "");
            telegramTitle = state.getString("telegramTitle", "");
            telegramChatId = state.getLong("telegramChatId");
            telegramUserId = state.getLong("telegramUserId");
        } else {
            try {
                DownloadDestinations.Snapshot saved = DownloadDestinations.stored(this);
                mode = saved.mode;
                phoneTree = saved.phoneTreeUri;
                telegramTitle = saved.telegramTitle;
                telegramChatId = saved.telegramChatId;
                telegramUserId = saved.telegramUserId;
            } catch (IOException ignored) {
            }
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        setContentView(scroll);
        text(body, PatchStrings.t("Where copies go"), 24);
        text(
                body,
                PatchStrings.t(
                        "Choose where completed downloads are saved. You can change this later in"
                                + " settings."),
                16);
        choices = new RadioGroup(this);
        downloadStyle.card(choices);
        body.addView(choices);
        choice(PatchStrings.t("Phone"), DownloadDestinations.PHONE);
        choice(PatchStrings.t("Telegram main channel"), DownloadDestinations.TELEGRAM);
        choice(PatchStrings.t("Both"), DownloadDestinations.BOTH);

        phoneSection = section(body);
        text(phoneSection, PatchStrings.t("Phone folder"), 18);
        phoneSummary = text(phoneSection, "", 14);
        button(phoneSection, PatchStrings.t("Choose folder"))
                .setOnClickListener(view -> chooseFolder());

        telegramSection = section(body);
        text(telegramSection, PatchStrings.t("Telegram main channel"), 18);
        text(
                telegramSection,
                PatchStrings.t(
                        "Uses your configured private main channel. Downloads are uploaded as files"
                                + " with their original audio bytes."),
                14);
        telegramSummary = text(telegramSection, "", 14);
        button(telegramSection, PatchStrings.t("Use configured main channel"))
                .setOnClickListener(view -> selectTelegram());
        button(telegramSection, PatchStrings.t("Open Telegram settings"))
                .setOnClickListener(
                        view -> {
                            try {
                                DownloadDestinations.openTelegramSetup(this);
                            } catch (IOException failure) {
                                notice.setText(PatchStrings.t(failure.getMessage()));
                            }
                        });
        notice = text(body, "", 14);
        button(body, PatchStrings.t("Save")).setOnClickListener(view -> save());
        button(body, PatchStrings.t("Back")).setOnClickListener(view -> finish());
        choices.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    RadioButton selected = findViewById(checkedId);
                    if (selected != null) mode = (String) selected.getTag();
                    update();
                });
        update();
    }

    private void choice(String label, String value) {
        RadioButton radio = new RadioButton(this);
        radio.setId(View.generateViewId());
        radio.setText(label);
        radio.setTag(value);
        choices.addView(radio);
        if (value.equals(mode)) choices.check(radio.getId());
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (phoneTree.length() != 0)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(phoneTree));
        try {
            startActivityForResult(intent, PICK_FOLDER);
        } catch (RuntimeException failure) {
            notice.setText(PatchStrings.t("A folder picker is not available on this device."));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags =
                    data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (RuntimeException failure) {
            notice.setText(
                    PatchStrings.t(
                            "The folder must allow lasting read and write access. Choose another"
                                    + " folder."));
            return;
        }
        if (busy) return;
        setBusy(true, "Checking folder…");
        worker.execute(
                () -> {
                    try {
                        DownloadDestinations.verifyPhoneFolder(this, uri.toString());
                        result(
                                () -> {
                                    phoneTree = uri.toString();
                                    setBusy(false, "Folder selected.");
                                });
                    } catch (IOException failure) {
                        result(() -> setBusy(false, failure.getMessage()));
                    }
                });
    }

    private void selectTelegram() {
        if (busy) return;
        setBusy(true, "Checking your private main channel…");
        worker.execute(
                () -> {
                    try {
                        JSONObject selected = DownloadDestinations.configuredTelegram(this);
                        long chat = selected.getLong("chatId"), user = selected.getLong("userId");
                        String title = selected.optString("title", "Private main channel");
                        result(
                                () -> {
                                    telegramChatId = chat;
                                    telegramUserId = user;
                                    telegramTitle = title;
                                    setBusy(false, "Private main channel selected.");
                                });
                    } catch (Exception failure) {
                        result(() -> setBusy(false, message(failure)));
                    }
                });
    }

    private void save() {
        if (busy) return;
        final DownloadDestinations.Snapshot selection;
        try {
            selection =
                    new DownloadDestinations.Snapshot(
                            mode, phoneTree, telegramChatId, telegramUserId, telegramTitle);
        } catch (IOException failure) {
            notice.setText(PatchStrings.t(failure.getMessage()));
            return;
        }
        setBusy(true, "Saving destinations…");
        worker.execute(
                () -> {
                    try {
                        DownloadDestinations.save(this, selection);
                        result(
                                () -> {
                                    setResult(RESULT_OK);
                                    finish();
                                });
                    } catch (IOException failure) {
                        result(() -> setBusy(false, failure.getMessage()));
                    }
                });
    }

    private void update() {
        phoneSection.setVisibility(
                DownloadDestinations.PHONE.equals(mode) || DownloadDestinations.BOTH.equals(mode)
                        ? View.VISIBLE
                        : View.GONE);
        telegramSection.setVisibility(
                DownloadDestinations.TELEGRAM.equals(mode) || DownloadDestinations.BOTH.equals(mode)
                        ? View.VISIBLE
                        : View.GONE);
        phoneSummary.setText(
                phoneTree.length() == 0
                        ? PatchStrings.t("Choose a folder for downloaded files.")
                        : PatchStrings.f(
                                "Phone folder: {0}", Uri.parse(phoneTree).getLastPathSegment()));
        telegramSummary.setText(
                telegramChatId == 0
                        ? PatchStrings.t("No private main channel selected.")
                        : PatchStrings.f("Telegram main channel: {0}", telegramTitle));
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        for (Button button : actions) button.setEnabled(!value);
        for (int index = 0; index < choices.getChildCount(); index++)
            choices.getChildAt(index).setEnabled(!value);
        notice.setText(
                message == null
                        ? PatchStrings.t("Destination could not be saved.")
                        : PatchStrings.t(message));
        update();
    }

    private void result(Runnable action) {
        if (!destroyed)
            runOnUiThread(
                    () -> {
                        if (!destroyed && !isFinishing()) action.run();
                    });
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null
                ? "Telegram destination is unavailable."
                : failure.getMessage();
    }

    private LinearLayout section(LinearLayout parent) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        if (downloadStyle != null) downloadStyle.card(section);
        parent.addView(section);
        return section;
    }

    private TextView text(LinearLayout parent, String label, int size) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(size);
        text.setPadding(0, 8, 0, 8);
        parent.addView(text);
        return text;
    }

    private Button button(LinearLayout parent, String label) {
        Button button = new Button(this);
        button.setText(label);
        parent.addView(button);
        actions.add(button);
        return button;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("mode", mode);
        state.putString("phoneTree", phoneTree);
        state.putString("telegramTitle", telegramTitle);
        state.putLong("telegramChatId", telegramChatId);
        state.putLong("telegramUserId", telegramUserId);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
