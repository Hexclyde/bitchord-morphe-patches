package androidx.compose.ui;

public interface Alignment {
    final class Companion {
        public static final Alignment BottomEnd = new Alignment() {};
        public static final Alignment BottomStart = new Alignment() {};
        public static final Alignment TopCenter = new Alignment() {};
        public static final Alignment BottomCenter = new Alignment() {};
        public static final BiasAlignment.Horizontal CenterHorizontally =
                new BiasAlignment.Horizontal();
    }
}
