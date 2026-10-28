package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

/** Rejects protected, secret and self-destructing media, since the player caches files. */
public final class MediaPolicy {
    private MediaPolicy() {}

    public static boolean savable(TdApi.Message message) {
        return message != null
                && message.canBeSaved
                && message.selfDestructType == null
                && message.selfDestructIn <= 0
                && message.ephemeralContent == null
                && message.restrictionInfo == null;
    }

    public static boolean chatAllowed(TdApi.Chat chat) {
        return chat != null
                && chat.type != null
                && !chat.hasProtectedContent
                && !(chat.type instanceof TdApi.ChatTypeSecret);
    }

    public static boolean wholeView(
            boolean foreground,
            boolean focused,
            boolean shown,
            boolean drawn,
            float alpha,
            int width,
            int height,
            int visibleWidth,
            int visibleHeight) {
        return foreground
                && focused
                && shown
                && drawn
                && alpha == 1f
                && width > 0
                && height > 0
                && visibleWidth >= width
                && visibleHeight >= height;
    }
}
