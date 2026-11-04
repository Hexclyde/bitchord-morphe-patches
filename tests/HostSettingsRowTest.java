package app.bitchord.telegram;

import androidx.compose.runtime.ComposerImpl;
import androidx.compose.ui.graphics.painter.Painter;

import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

import java.util.ArrayList;
import java.util.List;

public final class HostSettingsRowTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    /** The 1.9 row: icon, texts, painter, click, trailing, composer, changed, default. */
    public static final class OldHost {
        static final List<Object[]> calls = new ArrayList<>();

        public static void SettingsRow(
                Object icon,
                String title,
                String subtitle,
                Function2<?, ?, ?> subtitleContent,
                String value,
                String badge,
                boolean enabled,
                Painter painter,
                Function0<?> click,
                Function2<?, ?, ?> trailing,
                ComposerImpl composer,
                int changed,
                int defaults) {
            calls.add(
                    new Object[] {
                        title, subtitle, enabled, painter, click, composer, changed, defaults
                    });
        }
    }

    /** The 1.9.1 row: an icon-content slot before the click handler and a second changed int. */
    public static final class NewHost {
        static final List<Object[]> calls = new ArrayList<>();

        public static void SettingsRow(
                Object icon,
                String title,
                String subtitle,
                Function2<?, ?, ?> subtitleContent,
                String value,
                String badge,
                boolean enabled,
                Painter painter,
                Function2<?, ?, ?> iconContent,
                Function0<?> click,
                Function2<?, ?, ?> trailing,
                ComposerImpl composer,
                int changed,
                int changedMore,
                int defaults) {
            calls.add(
                    new Object[] {
                        title,
                        subtitle,
                        enabled,
                        painter,
                        click,
                        composer,
                        changed,
                        defaults,
                        iconContent,
                        changedMore
                    });
        }
    }

    public static final class UnknownHost {
        public static void SettingsRow(String title) {}
    }

    public static void main(String[] args) {
        ComposerImpl composer = new ComposerImpl();
        Function0<?> click = () -> null;
        Painter painter = new Painter() {};

        HostSettingsRow.invoke(OldHost.class, "Title", "Sub", true, painter, click, composer);
        Object[] old = OldHost.calls.get(0);
        check(
                "Title".equals(old[0]) && "Sub".equals(old[1]) && Boolean.TRUE.equals(old[2]),
                "1.9 row receives the texts and enabled state");
        check(old[3] == painter && old[4] == click, "1.9 row receives the painter and click");
        check(
                old[5] == composer && (int) old[6] == 0 && (int) old[7] == 0,
                "1.9 row receives the live composer and no default bits");

        HostSettingsRow.invoke(NewHost.class, "Title", "Sub", false, null, click, composer);
        Object[] now = NewHost.calls.get(0);
        check(
                "Title".equals(now[0]) && "Sub".equals(now[1]) && Boolean.FALSE.equals(now[2]),
                "1.9.1 row receives the texts and enabled state");
        check(now[3] == null && now[4] == click, "1.9.1 click lands after the icon-content slot");
        check(now[8] == null, "1.9.1 icon-content slot stays empty");
        check(
                now[5] == composer && (int) now[6] == 0 && (int) now[9] == 0 && (int) now[7] == 0,
                "1.9.1 row receives the live composer and no default bits");

        boolean rejected = false;
        try {
            HostSettingsRow.invoke(UnknownHost.class, "Title", "Sub", true, null, click, composer);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        check(rejected, "An unknown row layout is refused");
        System.out.println("PASS: " + checks + " host settings row shape checks");
    }
}
