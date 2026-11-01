package app.bitchord.telegram;

import android.content.Context;
import android.content.Intent;

import androidx.compose.runtime.ComposerImpl;

import com.music.bitchord.ui.screens.SettingsSheetKt;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** One row in BitChord's own Downloads settings, placed right after its four controls. */
public final class DownloadsSettingsUi {
    private static final Function0<Unit> OPEN =
            () -> {
                Context app = ExtrasUi.context;
                if (app != null)
                    app.startActivity(
                            new Intent(app, DownloadsSettingsActivity.class)
                                    .addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK
                                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return Unit.INSTANCE;
            };

    private DownloadsSettingsUi() {}

    /** Called by Morphe from the composable that draws BitChord's sync-playlists control. */
    public static void row(ComposerImpl composer) {
        SettingsSheetKt.SettingsRow(
                null,
                Strings.t("Patch downloads"),
                Strings.t("Smart downloads, sources and Storage Sense"),
                null,
                null,
                null,
                true,
                null,
                OPEN,
                null,
                composer,
                0,
                0);
    }
}
