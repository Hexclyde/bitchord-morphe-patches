package app.bitchord.telegram;

import androidx.media3.common.Player;

import java.util.function.BooleanSupplier;

/** The rendition suite keeps the optional Telegram diagnostics boundary out of its classpath. */
final class TelegramStreamPlayback {
    static void watch(Player player, BooleanSupplier current) {}

    static void activity(Player player) {}

    static void seek(Player player) {}
}
