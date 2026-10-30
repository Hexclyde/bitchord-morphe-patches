package app.bitchord.telegram;

import android.content.Context;
import android.content.Intent;

import androidx.compose.runtime.ComposerImpl;
import androidx.compose.runtime.internal.ComposableLambdaImpl;

import com.music.bitchord.ui.screens.SettingsSheetKt;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

/** The Patches & tweaks entry in BitChord's Settings. */
public final class ExtrasUi {
    private static final Function0<Unit> OPEN =
            () -> {
                open();
                return Unit.INSTANCE;
            };
    private static final Function2<ComposerImpl, Integer, Unit> CONTENT =
            (composer, flags) -> {
                SettingsSheetKt.SettingsRow(
                        null,
                        Strings.t("Patches & tweaks"),
                        Strings.t("Settings of the patches you added"),
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
                return Unit.INSTANCE;
            };
    private static final ComposableLambdaImpl GROUP_CONTENT =
            new ComposableLambdaImpl(CONTENT, false, 0x741504);

    static volatile Context context;

    private ExtrasUi() {}

    public static void tweaksSection(ComposerImpl composer) {
        hostGroup(
                Strings.t("Patches & tweaks"),
                Strings.t("Added to BitChord by patches."),
                26f,
                GROUP_CONTENT,
                composer,
                0,
                0);
    }

    private static void open() {
        Context app = context;
        if (app != null)
            app.startActivity(
                    new Intent(app, ExtrasActivity.class)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** The app, kept from BitChord's start for opening the page. */
    public static void start(Context app) {
        context = app.getApplicationContext();
        Strings.init(context);
        SoundExtras.init(app);
    }

    /**
     * Filled with BitChord's SettingsGroup call by Morphe, without a dependency on the optional
     * Telegram patch.
     */
    public static void hostGroup(
            String header,
            String footer,
            float spacing,
            ComposableLambdaImpl content,
            ComposerImpl composer,
            int flags,
            int defaults) {
        // Morphe replaces this body with the pinned host SettingsGroup call.
    }
}
