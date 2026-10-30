package app.bitchord.telegram;

import android.content.Context;

import java.util.Locale;

/** Compile-only facade. The actual implementation is injected by the shared settings patch. */
public final class PatchStrings {
    private PatchStrings() {}

    public static void refresh(Context context) {}

    public static String t(String english) {
        return english;
    }

    public static String f(String english, Object... values) {
        for (int i = 0; i < values.length; i++)
            english = english.replace("{" + i + "}", String.valueOf(values[i]));
        return english;
    }

    public static Locale locale() {
        return Locale.getDefault();
    }

    public static String state(Object value) {
        return value == null ? "" : value.toString();
    }

    public static boolean rtl() {
        return false;
    }
}
