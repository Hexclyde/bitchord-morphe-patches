package androidx.compose.ui.draw;

import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.Shape;

public final class BlurKt {
    public static final class Clip implements Modifier {
        public final Shape shape;

        Clip(Shape shape) {
            this.shape = shape;
        }
    }

    public static Modifier clip(Modifier base, Shape shape) {
        return base.then(new Clip(shape));
    }
}
