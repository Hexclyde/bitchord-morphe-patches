package app.bitchord.telegram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Internal addon rows are built for whichever SourceConfig shape the host has, or refused. */
public final class NativeSourceConfigTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static Class<?> registry() throws Exception {
        return Class.forName("com.music.bitchord.data.sources.SourceRegistry");
    }

    @SuppressWarnings("unchecked")
    static List<Object> rows() throws Exception {
        return (List<Object>) registry().getField("rows").get(null);
    }

    static void setRows(List<Object> rows) throws Exception {
        registry().getField("rows").set(null, rows);
        registry().getField("publications").setInt(null, 0);
    }

    static int publications() throws Exception {
        return registry().getField("publications").getInt(null);
    }

    static Object field(Object row, String name) throws Exception {
        return row.getClass().getField(name).get(row);
    }

    static boolean has(Object row, String name) {
        try {
            row.getClass().getField(name);
            return true;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    static Object community(
            String id, String url, boolean enabled, List<String> options, String pick)
            throws Exception {
        Class<?> config = Class.forName("com.music.bitchord.data.sources.SourceConfig");
        Class<?> kind = Class.forName("com.music.bitchord.data.sources.SourceKind");
        Object addon = kind.getField("ADDON").get(null);
        try {
            return config.getConstructor(
                            String.class,
                            kind,
                            String.class,
                            String.class,
                            boolean.class,
                            boolean.class,
                            boolean.class,
                            List.class,
                            String.class)
                    .newInstance(id, addon, "Community", url, enabled, true, false, options, pick);
        } catch (NoSuchMethodException legacy) {
            return config.getConstructor(
                            String.class,
                            kind,
                            String.class,
                            String.class,
                            boolean.class,
                            boolean.class,
                            boolean.class)
                    .newInstance(id, addon, "Community", url, enabled, true, false);
        }
    }

    static void built(String variant) throws Exception {
        Object row =
                NativeSourceConfig.addon(
                        "telegram-native", "Unofficial Telegram", "u", true, true, false);
        check("telegram-native".equals(field(row, "id")), "id");
        check("Unofficial Telegram".equals(field(row, "label")), "label");
        check("u".equals(field(row, "url")), "address");
        check(Boolean.TRUE.equals(field(row, "enabled")), "enabled");
        check(Boolean.TRUE.equals(field(row, "downloads")), "downloads");
        check(Boolean.FALSE.equals(field(row, "lossless")), "lossless gate");
        if (variant.equals("quality")) {
            check(
                    ((List<?>) field(row, "qualityOptions")).isEmpty(),
                    "internal addons list no qualities");
            check(field(row, "pickedQuality") == null, "internal addons pick no quality");
        } else
            check(
                    !has(row, "qualityOptions") && !has(row, "pickedQuality"),
                    "legacy row has quality fields");
    }

    static void published(String variant) throws Exception {
        List<String> options = new ArrayList<>(List.of("flac", "mp3"));
        Object other = community("community", "http://c", false, options, "mp3");
        Object stale =
                NativeSourceConfig.addon(
                        "telegram-native", "Old", "http://old", false, true, false);
        List<Object> rows = new ArrayList<>(List.of(other, stale));
        setRows(rows);
        check(NativeSource.register("http://new"), "a changed address is published");
        List<Object> now = rows();
        check(now.size() == 2 && now.get(0) == other, "the community row is the same object");
        check(Boolean.FALSE.equals(field(other, "enabled")), "the community toggle is kept");
        if (variant.equals("quality")) {
            check(field(other, "pickedQuality").equals("mp3"), "the community pick is kept");
            check(field(other, "qualityOptions") == options, "the community options are kept");
        }
        Object own = now.get(1);
        check("http://new".equals(field(own, "url")), "the Telegram address is replaced");
        check(Boolean.FALSE.equals(field(own, "enabled")), "the Telegram toggle is kept");
        check(!NativeSource.register("http://new"), "the same address publishes nothing");
        check(publications() == 1, "one publication");
    }

    static void refused() throws Exception {
        setRows(new ArrayList<>());
        try {
            NativeSourceConfig.addon("a", "b", "c", true, true, false);
            check(false, "unknown shape built a row");
        } catch (IllegalStateException expected) {
            check(
                    expected.getMessage().contains("not a known"),
                    "clear refusal: " + expected.getMessage());
        }
        try {
            NativeSource.register("http://new");
            check(false, "unknown shape registered");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("not a known"), "register refuses clearly");
        }
        check(publications() == 0 && rows().isEmpty(), "registry untouched");
        try {
            Method register =
                    Class.forName("app.bitchord.soulseek.SoulseekSource")
                            .getDeclaredMethod("register", String.class);
            register.setAccessible(true);
            register.invoke(null, "http://s");
            check(false, "unknown shape registered Soulseek");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "Soulseek refuses clearly");
        }
        check(publications() == 0, "Soulseek published nothing");
    }

    static void soulseek() throws Exception {
        setRows(new ArrayList<>());
        Method register =
                Class.forName("app.bitchord.soulseek.SoulseekSource")
                        .getDeclaredMethod("register", String.class);
        register.setAccessible(true);
        register.invoke(null, "http://s");
        check(
                rows().size() == 1 && "soulseek-native".equals(field(rows().get(0), "id")),
                "Soulseek row");
        check(Boolean.FALSE.equals(field(rows().get(0), "enabled")), "Soulseek starts off");
    }

    public static void main(String[] args) throws Exception {
        String variant = args[0];
        if (variant.equals("future")) refused();
        else {
            built(variant);
            published(variant);
            soulseek();
        }
        System.out.println("NativeSourceConfigTest " + variant + " PASS " + checks + " checks");
    }
}
