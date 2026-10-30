package app.bitchord.soulseek;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Opens account setup only for the Soulseek source, preserving other source editors. */
public final class SoulseekSourceEditorRoute implements Function1<Object, Object> {
    private final Function1<Object, Object> editor;

    private SoulseekSourceEditorRoute(Function1<Object, Object> editor) {
        this.editor = editor;
    }

    public static Function1<Object, Object> wrap(Function1<Object, Object> editor) {
        if (editor == null || editor instanceof SoulseekSourceEditorRoute) return editor;
        return new SoulseekSourceEditorRoute(editor);
    }

    public Object invoke(Object config) {
        boolean own = false;
        try {
            own =
                    config != null
                            && SoulseekSource.ID.equals(
                                    config.getClass().getMethod("getId").invoke(config));
        } catch (ReflectiveOperationException ignored) {
        }
        if (own) {
            SoulseekBridge.openSetup();
            return Unit.INSTANCE;
        }
        return editor.invoke(config);
    }

    public boolean equals(Object other) {
        return other instanceof SoulseekSourceEditorRoute
                && editor.equals(((SoulseekSourceEditorRoute) other).editor);
    }

    public int hashCode() {
        return editor.hashCode();
    }
}
