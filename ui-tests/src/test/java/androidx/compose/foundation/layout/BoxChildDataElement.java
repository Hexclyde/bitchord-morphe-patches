package androidx.compose.foundation.layout;

import androidx.compose.ui.Alignment;
import androidx.compose.ui.Modifier;

public final class BoxChildDataElement implements Modifier {
    public final Alignment alignment;
    public final boolean matchParentSize;

    public BoxChildDataElement(Alignment alignment, boolean matchParentSize) {
        this.alignment = alignment;
        this.matchParentSize = matchParentSize;
    }
}
