package androidx.compose.foundation.layout;

import androidx.compose.ui.Modifier;

/** What `Modifier.size(x)` returns: all four limits are x. */
public final class SizeElement implements Modifier {
    private final float minWidth, minHeight, maxWidth, maxHeight;

    public SizeElement(float minWidth, float minHeight, float maxWidth, float maxHeight) {
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }
}
