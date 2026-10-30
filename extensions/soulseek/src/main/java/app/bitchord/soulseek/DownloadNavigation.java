package app.bitchord.soulseek;

import android.content.Intent;

import app.bitchord.telegram.PatchStrings;

import kotlin.Lazy;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Adds one host-native navigation item without changing the host's four route numbers. */
public final class DownloadNavigation {
    private static Object previous;
    private static List<?> expanded;
    private static String language;
    private static Object callback, wrapped;
    private static int selected, previousSelected;
    private static boolean selecting;

    static boolean hasPlayer() {
        return callback instanceof Function1;
    }

    @SuppressWarnings("unchecked")
    static void selectLibrary() {
        if (!selecting) previousSelected = selected;
        selecting = true;
        ((Function1<Object, Object>) callback).invoke(2);
    }

    @SuppressWarnings("unchecked")
    static void returnToPlayer() {
        selecting = false;
        if (callback instanceof Function1)
            ((Function1<Object, Object>) callback).invoke(previousSelected);
    }

    private DownloadNavigation() {}

    public static synchronized Object tabs(Object original) {
        if (original == expanded) return original;
        String label = PatchStrings.t("Downloads");
        if (original == previous && label.equals(language)) return expanded;
        if (!(original instanceof List) || ((List<?>) original).size() != 4) return original;
        try {
            Class<?> icons = Class.forName("com.music.bitchord.ui.icons.BitChordIcons");
            java.lang.reflect.Field glyph = icons.getDeclaredField("Download$delegate");
            glyph.setAccessible(true);
            Object icon = ((Lazy<?>) glyph.get(null)).getValue();
            Class<?> type = Class.forName("com.music.bitchord.ui.components.BottomTab");
            Object tab = type.getConstructors()[0].newInstance(label, icon);
            List<Object> result = new ArrayList<>((List<?>) original);
            result.add(2, tab);
            previous = original;
            language = label;
            expanded = Collections.unmodifiableList(result);
            return expanded;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return original;
        }
    }

    public static synchronized int index(Object original, int selected) {
        DownloadNavigation.selected = selected;
        if (DownloadsShell.visible()) return 2;
        return original != expanded && tabs(original) != original && selected >= 2
                ? selected + 1
                : selected;
    }

    @SuppressWarnings("unchecked")
    public static synchronized Object callback(Object originalTabs, Object original) {
        if (originalTabs == expanded || tabs(originalTabs) == originalTabs) return original;
        if (original == callback) return wrapped;
        Function1<Object, Object> delegate = (Function1<Object, Object>) original;
        Function1<Object, Object> result =
                position -> {
                    int index = ((Number) position).intValue();
                    if (index == 2) {
                        try {
                            android.content.Context context = SoulseekBridge.get().context;
                            Intent target = new Intent(context, DownloadsHubActivity.class);
                            if (!DownloadsShell.open(target))
                                context.startActivity(
                                        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception | LinkageError unavailable) {
                            // Navigation never routes a failed launch to Library or Search.
                        }
                        return Unit.INSTANCE;
                    }
                    DownloadsShell.close();
                    selecting = false;
                    return delegate.invoke(index > 2 ? index - 1 : index);
                };
        callback = original;
        wrapped = result;
        return result;
    }
}
