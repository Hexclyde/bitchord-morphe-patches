package app.bitchord.soulseek;

import android.app.Activity;

import java.io.IOException;

public final class PhoneFolderFailureTest {
    private static int assertions;
    private static final String TREE = "content://fixture/tree/downloads";

    public static void main(String[] arguments) throws Exception {
        Activity activity = new Activity();
        check(
                !DownloadDestinations.ensureConfigured(activity) && activity.launches == 1,
                "first setup must open before queue can proceed");
        activity.resolver.queryFailure = true;
        fails(
                () -> DownloadDestinations.verifyPhoneFolder(activity, TREE),
                "provider query runtime failure is actionable IOException");
        activity.resolver.queryFailure = false;
        activity.resolver.cursorFailure = true;
        fails(
                () -> DownloadDestinations.verifyPhoneFolder(activity, TREE),
                "provider cursor runtime failure is actionable IOException");
        activity.resolver.cursorFailure = false;
        DownloadDestinations.verifyPhoneFolder(activity, TREE);
        check(true, "folder validation can retry after provider recovers");
        DownloadDestinations.save(
                activity, new DownloadDestinations.Snapshot("phone", TREE, 0, 0, ""));
        check(DownloadDestinations.ensureConfigured(activity), "resolved phone setup completes");
        fails(
                () ->
                        DownloadDestinations.save(
                                activity,
                                new DownloadDestinations.Snapshot("both", TREE, -1001, 12, "Main")),
                "Both cannot complete when optional Telegram integration is absent");
        check(
                DownloadDestinations.snapshot(activity).mode.equals("phone"),
                "failed edit preserves prior valid choice atomically");
        Activity telegramOnly = new Activity();
        fails(
                () ->
                        DownloadDestinations.save(
                                telegramOnly,
                                new DownloadDestinations.Snapshot(
                                        "telegram", "", -1001, 12, "Main")),
                "missing Telegram integration never defaults to phone");
        check(
                !DownloadDestinations.ensureConfigured(telegramOnly),
                "unresolved Telegram setup stays incomplete");
        activity.resolver.write = false;
        check(
                !DownloadDestinations.ensureConfigured(activity),
                "revoked folder permission blocks queue and reopens setup");
        System.out.println("Download folder recovery: " + assertions + " assertions passed");
    }

    private static void fails(Action action, String reason) throws Exception {
        try {
            action.run();
            throw new AssertionError(reason);
        } catch (IOException expected) {
            assertions++;
        }
    }

    private static void check(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }

    private interface Action {
        void run() throws Exception;
    }
}
