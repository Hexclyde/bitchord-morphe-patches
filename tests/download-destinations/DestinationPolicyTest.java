package app.bitchord.soulseek;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class DestinationPolicyTest {
    private static int assertions;

    public static void main(String[] arguments) throws Exception {
        rejects("", "", 0, 0, "missing choice never silently defaults to phone");
        rejects("unknown", "content://folder/tree/downloads", -1001, 12, "unknown policy rejected");
        rejects("phone", "", 0, 0, "phone requires a selected folder");
        rejects("telegram", "", 0, 12, "Telegram requires a pinned channel");
        rejects("telegram", "", -1001, 0, "Telegram requires a pinned account");
        rejects("both", "", -1001, 12, "Both requires a phone folder too");
        rejects(
                "both",
                "content://folder/tree/downloads",
                0,
                0,
                "Both requires a private channel too");
        DownloadDestinations.Snapshot phone =
                new DownloadDestinations.Snapshot(
                        "phone", "content://folder/tree/downloads", 0, 0, "");
        check(phone.needsPhone() && !phone.needsTelegram(), "phone policy has one destination");
        DownloadDestinations.Snapshot telegram =
                new DownloadDestinations.Snapshot("telegram", "", -1001, 12, "Private channel");
        check(
                !telegram.needsPhone() && telegram.needsTelegram(),
                "Telegram policy never adds a phone copy");
        DownloadDestinations.Snapshot both =
                new DownloadDestinations.Snapshot(
                        "both", "content://folder/tree/downloads", -1001, 12, "Private channel");
        check(both.needsPhone() && both.needsTelegram(), "Both tracks two destinations");
        new DownloadDestinations.Snapshot("telegram", "", -2002, 99, "Changed account");
        check(
                both.telegramChatId == -1001 && both.telegramUserId == 12,
                "job snapshot keeps original account and channel");
        for (Field field : DownloadDestinations.Snapshot.class.getFields())
            check(
                    Modifier.isFinal(field.getModifiers()),
                    "snapshot field is immutable: " + field.getName());
        System.out.println("Download destinations: " + assertions + " assertions passed");
    }

    private static void rejects(String mode, String tree, long chat, long account, String reason)
            throws Exception {
        try {
            new DownloadDestinations.Snapshot(mode, tree, chat, account, "");
            throw new AssertionError(reason);
        } catch (IOException expected) {
            assertions++;
        }
    }

    private static void check(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }
}
