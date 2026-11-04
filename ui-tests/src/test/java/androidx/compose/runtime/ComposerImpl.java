package androidx.compose.runtime;

public class ComposerImpl {
    public final android.app.Activity activity;
    public final android.view.ViewGroup content;
    public android.view.View view;
    public androidx.compose.ui.Modifier modifier;
    public int defaults;

    /** What the player's tint resolves to for this composition; null means white. */
    public androidx.compose.ui.graphics.Color tint;

    /** What the player's chrome local resolves to; null means off. */
    public Boolean chrome;

    public int groupsOpen, groupsClosed;

    public void startReplaceGroup(int key) {
        groupsOpen++;
    }

    public void endReplaceGroup() {
        groupsClosed++;
    }

    /** Takes the base local type, as the host's does; the tint's own type is a subclass. */
    public Object consume(ProvidableCompositionLocal local) {
        if (local == com.music.bitchord.ui.player.PlayerChromeKt.LocalPlayerChrome)
            return chrome != null && chrome;
        return tint != null ? tint : new androidx.compose.ui.graphics.Color(0xffffffffL << 32);
    }

    public ComposerImpl(android.app.Activity activity, android.view.ViewGroup content) {
        this.activity = activity;
        this.content = content;
    }
}
