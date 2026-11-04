package androidx.compose.foundation.shape;

import androidx.compose.ui.graphics.Shape;

public final class RoundedCornerShape implements Shape {
    public final float radiusDp;

    RoundedCornerShape(float radiusDp) {
        this.radiusDp = radiusDp;
    }
}
