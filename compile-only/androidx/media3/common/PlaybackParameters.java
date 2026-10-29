package androidx.media3.common;

/** Compile-only host signature; never bundled in the extension. */
public final class PlaybackParameters {
    // Not constants, so reads of them aren't folded in at compile time.
    public final float speed;
    public final float pitch;

    public PlaybackParameters(float speed) {
        this(speed, 1f);
    }

    public PlaybackParameters(float speed, float pitch) {
        this.speed = speed;
        this.pitch = pitch;
    }
}
