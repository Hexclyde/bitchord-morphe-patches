package androidx.compose.ui.viewinterop;

import android.view.View;

import androidx.compose.runtime.ComposerImpl;
import androidx.compose.ui.Modifier;

import kotlin.jvm.functions.Function1;

/** Mirrors the host default mask: a null modifier with no default is invalid. */
public final class AndroidView_androidKt {
    public static void AndroidView(
            Function1 factory,
            Modifier modifier,
            Function1 update,
            ComposerImpl composer,
            int changed,
            int defaults) {
        if ((defaults & 2) != 0) modifier = new Modifier() {};
        if (modifier == null) throw new NullPointerException("modifier");
        composer.modifier = modifier;
        composer.defaults = defaults;
        if (composer.view == null) {
            composer.view = (View) factory.invoke(composer.activity);
            composer.content.addView(composer.view);
        }
        update.invoke(composer.view);
    }
}
