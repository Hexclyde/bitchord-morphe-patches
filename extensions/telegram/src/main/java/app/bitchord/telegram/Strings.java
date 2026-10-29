package app.bitchord.telegram;

import java.util.*;

/**
 * The patch's own pages in the language picked in BitChord's settings, or the phone's language when
 * none is picked. Each phrase is looked up by its English text, so a phrase that hasn't been
 * translated yet shows in English. Phrases with numbers or names in them have {0}, {1} and so on
 * where those go.
 */
final class Strings {
    private Strings() {}

    private static volatile String language = "";
    private static volatile android.content.Context context;
    private static volatile Locale chosen;
    private static volatile long checkedAt;

    /** Where BitChord's own language choice is read from. */
    static void init(android.content.Context app) {
        if (app != null) context = app.getApplicationContext();
    }

    /**
     * BitChord's language: the one picked in its settings (Android keeps it from Android 13 on;
     * before that AndroidX saves it in a file of BitChord's), or else the phone's.
     */
    static Locale current() {
        long now = System.nanoTime();
        if (now - checkedAt > 2_000_000_000L) {
            chosen = picked();
            checkedAt = now;
        }
        Locale found = chosen;
        return found != null ? found : Locale.getDefault();
    }

    private static Locale picked() {
        android.content.Context app = context;
        if (app == null) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.LocaleList list =
                        app.getSystemService(android.app.LocaleManager.class)
                                .getApplicationLocales();
                return list.isEmpty() ? null : list.get(0);
            }
            java.io.File saved =
                    new java.io.File(
                            app.getFilesDir(),
                            "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file");
            if (!saved.isFile()) return null;
            String xml =
                    new String(
                            java.nio.file.Files.readAllBytes(saved.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
            return recorded(xml);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** The first language in AndroidX's saved choice, {@code <locales application_locales=…/>}. */
    static Locale recorded(String xml) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("application_locales=\"([^\"]*)\"").matcher(xml);
        if (!m.find()) return null;
        String first = m.group(1).split(",")[0].trim();
        return first.isEmpty() ? null : Locale.forLanguageTag(first);
    }

    private static volatile Map<String, String> table = Collections.emptyMap();

    static String t(String english) {
        if (english == null || english.isEmpty()) return english;
        String found = table().get(english);
        return found != null ? found : english;
    }

    static String f(String english, Object... values) {
        String text = t(english);
        for (int i = 0; i < values.length; i++)
            text = text.replace("{" + i + "}", String.valueOf(values[i]));
        return text;
    }

    /** 1,234 written the phone's way. */
    static String number(long n) {
        return java.text.NumberFormat.getIntegerInstance(current()).format(n);
    }

    /** True for languages written right to left. */
    static boolean rightToLeft() {
        String lang = language(current());
        return lang.equals("ar") || lang.equals("ur") || lang.equals("fa") || lang.equals("he");
    }

    static String language(Locale locale) {
        String lang = locale.getLanguage();
        // Older Android names Indonesian "in".
        return lang.equals("in") ? "id" : lang;
    }

    private static Map<String, String> table() {
        String lang = language(current());
        if (!lang.equals(language)) {
            String[] pairs = Translations.table(lang);
            Map<String, String> loaded = new HashMap<>();
            for (int i = 0; i + 1 < pairs.length; i += 2) loaded.put(pairs[i], pairs[i + 1]);
            table = loaded;
            language = lang;
        }
        return table;
    }
}
