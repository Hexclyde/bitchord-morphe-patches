package androidx.compose.runtime;

public class ComposerImpl {
    public final android.app.Activity activity;
    public final android.view.ViewGroup content;
    public android.view.View view;
    public androidx.compose.ui.Modifier modifier;
    public int defaults;

    public ComposerImpl(android.app.Activity activity, android.view.ViewGroup content) {
        this.activity = activity;
        this.content = content;
    }
}
