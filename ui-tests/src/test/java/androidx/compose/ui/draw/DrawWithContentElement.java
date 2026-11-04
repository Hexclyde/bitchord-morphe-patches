package androidx.compose.ui.draw;

import androidx.compose.ui.Modifier;

import kotlin.jvm.functions.Function1;

public final class DrawWithContentElement implements Modifier {
    public final Function1<Object, ?> onDraw;

    public DrawWithContentElement(Function1<Object, ?> onDraw) {
        this.onDraw = onDraw;
    }
}
