package app.bitchord.telegram;

import android.content.Context;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class DownloadUploadTest {
    private static int assertions;
    private static final long CHAT = -100123, USER = 42;

    public static void main(String[] arguments) throws Exception {
        File root = new File(arguments[0]);
        root.mkdirs();
        Context context = new Context(root);
        TelegramBridge value =
                new TelegramBridge(new File(context.getNoBackupFilesDir(), "telegram"));
        TelegramBridge.instance = value;
        File source = new File(context.getNoBackupFilesDir(), "verified.flac");
        byte[] audio = new byte[40000];
        for (int index = 0; index < audio.length; index++) audio[index] = (byte) (index * 31);
        Files.write(source.toPath(), audio);
        JSONObject destination =
                new JSONObject(DownloadUploadBridge.configuredDestination(context));
        check(
                destination.getLong("userId") == USER && destination.getLong("chatId") == CHAT,
                "configured account and channel validated");

        String firstToken = "download_fixture_confirmed_1";
        FutureTask<String> sending =
                start(
                        () ->
                                DownloadUploadBridge.upload(
                                        context,
                                        source,
                                        "Exact original.flac",
                                        CHAT,
                                        USER,
                                        firstToken));
        until(() -> value.sends.get() == 1, "document upload submitted");
        Thread.sleep(40);
        check(!sending.isDone(), "pending SendMessage response is not delivery success");
        check(value.sentPayload.exists(), "pending document payload retained");
        check(
                Arrays.equals(audio, Files.readAllBytes(value.sentPayload.toPath())),
                "document bytes unchanged");
        check(
                value.sentPayload.getName().equals("Exact original.flac"),
                "document keeps requested filename");
        TdApi.Message pending = value.lastSent;
        TdApi.Message confirmed = copy(pending, 901, null);
        DownloadUploadBridge.onUpdate(
                value, new TdApi.UpdateMessageSendSucceeded(confirmed, pending.id));
        JSONObject receipt = new JSONObject(sending.get(3, TimeUnit.SECONDS));
        check(
                receipt.getBoolean("confirmed") && receipt.getLong("messageId") == 901,
                "success update records final server ID");
        check(!value.sentPayload.exists(), "staged copy released only after confirmation");
        check(
                Arrays.equals(audio, Files.readAllBytes(source.toPath())),
                "verified source survives confirmation");
        DownloadUploadBridge.upload(context, source, "Exact original.flac", CHAT, USER, firstToken);
        check(value.sends.get() == 1, "confirmed token is idempotent");
        value.archiveMessage(confirmed);
        JSONObject verified =
                new JSONObject(DownloadUploadBridge.verifyArchive(context, firstToken));
        check(verified.getLong("readbackVerifiedAt") > 0, "fresh remote readback is journaled");
        check(
                value.cacheEvictions.get() >= 2 && value.readbacks.get() == 1,
                "cache is evicted before verification and released afterward");
        check(source.exists(), "verification never removes original");
        File restore = new File(context.getNoBackupFilesDir(), "restored.flac");
        DownloadUploadBridge.restoreArchive(context, firstToken, restore);
        check(
                Arrays.equals(audio, Files.readAllBytes(restore.toPath())),
                "restore preserves original bytes exactly");
        value.corruptReadback = true;
        fails(
                () -> DownloadUploadBridge.verifyArchive(context, firstToken),
                "remote hash mismatch rejects verification");
        check(source.exists(), "remote mismatch keeps original");
        value.corruptReadback = false;
        value.keepCache = true;
        // A still-complete cached local file cannot qualify as an independent remote read-back.
        value.cachedForTest();
        int reads = value.readbacks.get();
        fails(
                () -> DownloadUploadBridge.verifyArchive(context, firstToken),
                "failed cache eviction blocks verification");
        check(
                value.readbacks.get() == reads,
                "no cached bytes are accepted as remote verification");
        value.keepCache = false;
        value.missingMessage = true;
        fails(
                () -> DownloadUploadBridge.verifyArchive(context, firstToken),
                "deleted channel message blocks offloading");
        value.missingMessage = false;
        value.accountId = 99;
        fails(
                () -> DownloadUploadBridge.verifyArchive(context, firstToken),
                "changed account blocks archive verification");
        value.accountId = USER;
        File outside = new File(root, "outside-restore.flac");
        fails(
                () -> DownloadUploadBridge.restoreArchive(context, firstToken, outside),
                "restore refuses non-private destination");
        value.archiveMessage(null);

        value.throwAfterSend = true;
        String uncertain = "download_fixture_uncertain_2";
        fails(
                () ->
                        DownloadUploadBridge.upload(
                                context, source, "Uncertain.flac", CHAT, USER, uncertain),
                "submission transport timeout propagates");
        int submitted = value.sends.get();
        check(submitted == 2 && value.sentPayload.exists(), "uncertain payload remains available");
        value.throwAfterSend = false;
        fails(
                () ->
                        DownloadUploadBridge.upload(
                                context, source, "Uncertain.flac", CHAT, USER, uncertain),
                "uncertain empty search requires review");
        check(
                value.sends.get() == submitted && value.searches.get() == 1,
                "retry searches instead of blindly posting duplicate");

        value.findSubmitted = true;
        FutureTask<String> recovered =
                start(
                        () ->
                                DownloadUploadBridge.upload(
                                        context, source, "Uncertain.flac", CHAT, USER, uncertain));
        until(() -> value.searches.get() >= 2, "uncertain message recovered by marker");
        Thread.sleep(40);
        check(!recovered.isDone(), "recovered pending message still waits for delivery");
        DownloadUploadBridge.cancel(uncertain);
        failsFuture(recovered, "cancel stops local wait while retaining receipt");
        check(
                value.sentPayload.exists() && source.exists(),
                "cancel preserves exact original and upload payload");
        TdApi.Message recoveredPending = value.lastSent;
        TdApi.Message recoveredFinal = copy(recoveredPending, 902, null);
        DownloadUploadBridge.onUpdate(
                value, new TdApi.UpdateMessageSendSucceeded(recoveredFinal, recoveredPending.id));
        String recoveredReceipt =
                DownloadUploadBridge.upload(
                        context, source, "Uncertain.flac", CHAT, USER, uncertain);
        check(
                new JSONObject(recoveredReceipt).getLong("messageId") == 902
                        && value.sends.get() == submitted,
                "retry after cancel confirms same server message without repost");

        value.accountId = 99;
        fails(
                () ->
                        DownloadUploadBridge.upload(
                                context,
                                source,
                                "Wrong account.flac",
                                CHAT,
                                USER,
                                "download_fixture_wrong_account"),
                "changed account rejected");
        value.accountId = USER;
        value.privateOwned = false;
        fails(
                () ->
                        DownloadUploadBridge.upload(
                                context,
                                source,
                                "Public.flac",
                                CHAT,
                                USER,
                                "download_fixture_public_channel"),
                "public or unowned channel rejected");
        value.privateOwned = true;
        fails(
                () ->
                        DownloadUploadBridge.upload(
                                context,
                                source,
                                "../outside.flac",
                                CHAT,
                                USER,
                                "download_fixture_bad_filename"),
                "unsafe filename rejected");
        check(value.sends.get() == submitted, "account/channel/filename rejection sends nothing");

        FutureTask<String> failure =
                start(
                        () ->
                                DownloadUploadBridge.upload(
                                        context,
                                        source,
                                        "Rejected.flac",
                                        CHAT,
                                        USER,
                                        "download_fixture_failed_3"));
        until(() -> value.sends.get() == 3, "failed message initially submitted once");
        TdApi.Message failedPending = value.lastSent;
        TdApi.Message failed =
                copy(failedPending, failedPending.id, new TdApi.MessageSendingStateFailed());
        value.lastSent = failed;
        DownloadUploadBridge.onUpdate(
                value,
                new TdApi.UpdateMessageSendFailed(
                        failed, failedPending.id, new TdApi.Error(400, "fixture rejection")));
        failsFuture(failure, "failure update does not mark copy complete");
        fails(
                () ->
                        DownloadUploadBridge.upload(
                                context,
                                source,
                                "Rejected.flac",
                                CHAT,
                                USER,
                                "download_fixture_failed_3"),
                "nonretryable failed message stays failed");
        check(
                value.sends.get() == 3 && value.resends.get() == 0,
                "nonretryable failure cannot create duplicate");
        check(
                source.exists() && Arrays.equals(audio, Files.readAllBytes(source.toPath())),
                "failure keeps exact source for review or retry");

        ((TdApi.MessageSendingStateFailed) failed.sendingState).canRetry = true;
        JSONObject retried =
                new JSONObject(
                        DownloadUploadBridge.upload(
                                context,
                                source,
                                "Rejected.flac",
                                CHAT,
                                USER,
                                "download_fixture_failed_3"));
        check(
                retried.getBoolean("confirmed")
                        && value.resends.get() == 1
                        && value.sends.get() == 3,
                "retryable failure resends the known message instead of posting a new one");

        value.confirmBeforeReturn = true;
        JSONObject early =
                new JSONObject(
                        DownloadUploadBridge.upload(
                                context,
                                source,
                                "Early callback.flac",
                                CHAT,
                                USER,
                                "download_fixture_early_callback"));
        check(
                early.getBoolean("confirmed") && early.getLong("messageId") == 9004,
                "delivery update before SendMessage callback is retained");
        value.confirmBeforeReturn = false;

        byte[] changed = audio.clone();
        changed[0] ^= 1;
        Files.write(source.toPath(), changed);
        fails(
                () ->
                        DownloadUploadBridge.upload(
                                context, source, "Exact original.flac", CHAT, USER, firstToken),
                "same token with changed SHA is rejected");
        Files.write(source.toPath(), audio);
        check(value.sends.get() == 4, "changed source never reuses receipt or posts");
        check(
                !DownloadUploadBridge.matches(
                        confirmed,
                        CHAT + 1,
                        ((TdApi.MessageDocument) confirmed.content).caption.text,
                        audio.length),
                "receipt from a different channel is rejected");
        check(
                !DownloadUploadBridge.matches(confirmed, CHAT, "Different caption", audio.length),
                "receipt marker mismatch rejected");
        check(
                !DownloadUploadBridge.matches(
                        confirmed,
                        CHAT,
                        ((TdApi.MessageDocument) confirmed.content).caption.text,
                        audio.length + 1),
                "receipt byte count mismatch rejected");
        FutureTask<String> authChange =
                start(
                        () ->
                                DownloadUploadBridge.upload(
                                        context,
                                        source,
                                        "Auth transition.flac",
                                        CHAT,
                                        USER,
                                        "download_fixture_auth_transition"));
        until(() -> value.sends.get() == 5, "auth transition copy starts pending");
        value.authorizationRevision++;
        failsFuture(authChange, "authorization transition stops pending copy wait");
        check(
                value.sends.get() == 5 && source.exists() && value.sentPayload.exists(),
                "auth transition preserves source and cannot redirect copy");
        System.out.println("Download upload receipts: " + assertions + " assertions passed");
    }

    private static TdApi.Message copy(
            TdApi.Message previous, long id, TdApi.MessageSendingState state) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.chatId = previous.chatId;
        message.content = previous.content;
        message.sendingState = state;
        return message;
    }

    private static FutureTask<String> start(Operation operation) {
        FutureTask<String> task = new FutureTask<>(operation::run);
        Thread worker = new Thread(task, "upload-fixture");
        worker.setDaemon(true);
        worker.start();
        return task;
    }

    private static void failsFuture(FutureTask<String> task, String message) throws Exception {
        try {
            task.get(3, TimeUnit.SECONDS);
            throw new AssertionError(message);
        } catch (ExecutionException expected) {
            assertions++;
        }
    }

    private static void until(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(condition.getAsBoolean(), message);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void fails(Operation operation, String message) throws Exception {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            assertions++;
        }
    }

    private interface Operation {
        String run() throws Exception;
    }
}
