package androidx.compose.ui;

public interface Modifier {
    final class Companion implements Modifier {
        public static final Companion $$INSTANCE = new Companion();
    }

    default Modifier then(Modifier other) {
        return new Chain(this, other);
    }

    final class Chain implements Modifier {
        public final Modifier base, next;

        public Chain(Modifier base, Modifier next) {
            this.base = base;
            this.next = next;
        }
    }
}
