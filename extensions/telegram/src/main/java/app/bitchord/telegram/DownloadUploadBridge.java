package app.bitchord.telegram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.util.AtomicFile;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Optional bridge for byte-preserving download copies to the selected private channel. */
public final class DownloadUploadBridge {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final Map<String, Thread> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, TdApi.Message> RECENT =
            new LinkedHashMap<String, TdApi.Message>() {
                protected boolean removeEldestEntry(Map.Entry<String, TdApi.Message> entry) {
                    return size() > 128;
                }
            };

    private DownloadUploadBridge() {}

    public static void openSetup(Activity activity) {
        activity.startActivity(new Intent(activity, TelegramActivity.class));
    }

    private static TelegramBridge bridge(Context context) throws IOException {
        worker();
        TelegramBridge.init(context);
        TelegramBridge value = TelegramBridge.instance;
        if (value == null || !value.ready())
            throw new IOException("Connect Telegram in its setup first.");
        return value;
    }

    public static String configuredDestination(Context context) throws Exception {
        TelegramBridge value = bridge(context);
        long user = value.call(new TdApi.GetMe()).id;
        long chat = value.mainChannel.main();
        validate(value, user, chat);
        return new JSONObject()
                .put("userId", user)
                .put("chatId", chat)
                .put("title", value.call(new TdApi.GetChat(chat)).title)
                .toString();
    }

    public static void validateDestination(Context context, long userId, long chatId)
            throws Exception {
        validate(bridge(context), userId, chatId);
    }

    private static void validate(TelegramBridge value, long user, long chat) throws Exception {
        if (!value.ready() || user <= 0 || chat == 0 || value.call(new TdApi.GetMe()).id != user)
            throw new IOException(
                    "The Telegram account selected for this download is not connected.");
        if (!Boolean.FALSE.equals(value.ownChannel(value.call(new TdApi.GetChat(chat)))))
            throw new IOException("Choose a private main channel owned by this Telegram account.");
    }

    /**
     * Returns only after Telegram confirms delivery. Retrying a token never blindly posts again.
     */
    public static String upload(
            Context context,
            File source,
            String displayName,
            long chatId,
            long accountId,
            String durableToken)
            throws Exception {
        worker();
        if (durableToken == null || !durableToken.matches("[A-Za-z0-9_-]{16,96}"))
            throw new IOException("Invalid download receipt identifier.");
        if (displayName == null
                || displayName.length() > 180
                || displayName.isEmpty()
                || displayName.equals(".")
                || displayName.equals("..")
                || displayName.indexOf('/') >= 0
                || displayName.indexOf('\\') >= 0
                || displayName.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IOException("Invalid download filename.");
        TelegramBridge value = bridge(context);
        final long generation = value.generation;
        final long authorization = value.authorizationRevision;
        validate(value, accountId, chatId);
        File canonical = source.getCanonicalFile();
        String privateRoot = context.getNoBackupFilesDir().getCanonicalPath() + File.separator;
        String filesRoot = context.getFilesDir().getCanonicalPath() + File.separator;
        if (!(canonical.getPath().startsWith(privateRoot)
                        || canonical.getPath().startsWith(filesRoot))
                || !canonical.isFile()
                || canonical.length() <= 0
                || canonical.length() > MAX_BYTES)
            throw new IOException(
                    "Upload requires a completed private download of at most 256 MiB.");
        String sha = digest(canonical);
        String key =
                hex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(durableToken.getBytes(StandardCharsets.UTF_8)));
        if (ACTIVE.putIfAbsent(key, Thread.currentThread()) != null)
            throw new IOException("This Telegram copy is already being processed.");
        try {
            File directory = new File(value.home, "download-receipts/" + key);
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("Cannot create upload receipt.");
            AtomicFile ledger = new AtomicFile(new File(directory, "receipt.json"));
            JSONObject state;
            boolean existing = ledger.getBaseFile().exists();
            if (existing) {
                state = read(ledger);
                if (state.getLong("userId") != accountId
                        || state.getLong("chatId") != chatId
                        || state.getLong("bytes") != canonical.length()
                        || !state.getString("sha256").equals(sha)
                        || !state.getString("name").equals(displayName))
                    throw new IOException(
                            "The saved Telegram receipt belongs to a different file or"
                                    + " destination.");
                if (state.optBoolean("confirmed")) return state.toString();
            } else {
                state =
                        new JSONObject()
                                .put("version", 1)
                                .put("userId", accountId)
                                .put("chatId", chatId)
                                .put("bytes", canonical.length())
                                .put("sha256", sha)
                                .put("name", displayName)
                                .put("confirmed", false)
                                .put("phase", "prepared")
                                .put("messageId", 0);
                write(ledger, state);
            }
            String marker = "BitChord copy " + key;
            String caption = displayName + "\n" + marker + "\nSHA-256 " + sha;
            TdApi.Message message = null;
            long savedId = state.optLong("messageId");
            if (savedId != 0) {
                synchronized (RECENT) {
                    message = RECENT.get(recentKey(value, chatId, savedId));
                }
                if (message == null)
                    try {
                        message = value.call(new TdApi.GetMessage(chatId, savedId), 15000);
                    } catch (Exception unavailable) {
                        /* The successful final ID may replace the temporary ID. */
                    }
            }
            if (message == null && !"prepared".equals(state.optString("phase"))) {
                TdApi.FoundChatMessages found =
                        value.call(
                                new TdApi.SearchChatMessages(
                                        chatId,
                                        null,
                                        key,
                                        null,
                                        0,
                                        0,
                                        100,
                                        new TdApi.SearchMessagesFilterDocument()),
                                20000);
                if (found.messages != null)
                    for (TdApi.Message candidate : found.messages)
                        if (matches(candidate, chatId, caption, canonical.length())) {
                            message = candidate;
                            break;
                        }
                if (message == null)
                    throw new IOException(
                            "Telegram delivery is uncertain. Check the channel before retrying; no"
                                    + " duplicate was posted.");
            }
            if (message == null) {
                File payloadDirectory = new File(directory, "payload");
                if (!payloadDirectory.isDirectory() && !payloadDirectory.mkdirs())
                    throw new IOException("Cannot stage upload.");
                File payload = new File(payloadDirectory, displayName);
                if (!payload.exists()) {
                    try {
                        Files.createLink(payload.toPath(), canonical.toPath());
                    } catch (IOException | UnsupportedOperationException noLinks) {
                        Files.copy(
                                canonical.toPath(),
                                payload.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                if (payload.length() != canonical.length() || !digest(payload).equals(sha))
                    throw new IOException("The completed download changed before upload.");
                check(value, generation, authorization);
                state.put("phase", "submitting");
                write(ledger, state);
                try {
                    message =
                            value.call(
                                    new TdApi.SendMessage(
                                            chatId,
                                            null,
                                            null,
                                            null,
                                            null,
                                            new TdApi.InputMessageDocument(
                                                    new TdApi.InputDocument(
                                                            new TdApi.InputFileLocal(
                                                                    payload.getAbsolutePath()),
                                                            null,
                                                            true),
                                                    new TdApi.FormattedText(
                                                            caption, new TdApi.TextEntity[0]))),
                                    30000);
                } catch (TelegramBridge.TelegramFailure rejected) {
                    // A server rejection means no message was accepted; a transport timeout remains
                    // uncertain.
                    state.put("phase", "prepared");
                    write(ledger, state);
                    throw rejected;
                }
                state.put("messageId", message.id).put("phase", "pending");
                write(ledger, state);
            } else if (message.sendingState instanceof TdApi.MessageSendingStateFailed) {
                TdApi.MessageSendingStateFailed failed =
                        (TdApi.MessageSendingStateFailed) message.sendingState;
                if (!failed.canRetry
                        || failed.needAnotherSender
                        || failed.needAnotherReplyQuote
                        || failed.needDropReply
                        || failed.requiredPaidMessageStarCount != 0
                        || failed.retryAfter > 0)
                    throw new IOException(
                            "Telegram rejected this copy. Resolve it in the channel before"
                                    + " retrying.");
                state.put("phase", "retrying");
                write(ledger, state);
                TdApi.Messages retried =
                        value.call(
                                new TdApi.ResendMessages(chatId, new long[] {message.id}, null, 0),
                                30000);
                if (retried.messages == null
                        || retried.messages.length != 1
                        || retried.messages[0] == null)
                    throw new IOException("Telegram could not retry the original copy.");
                message = retried.messages[0];
                state.put("messageId", message.id).put("phase", "pending");
                write(ledger, state);
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
            final long pendingId = message.id;
            while (true) {
                check(value, generation, authorization);
                if (!matches(message, chatId, caption, canonical.length()))
                    throw new IOException("Telegram returned a different file receipt.");
                if (message.sendingState == null) {
                    state.put("confirmed", true)
                            .put("phase", "confirmed")
                            .put("messageId", message.id);
                    write(ledger, state);
                    File[] payloads = new File(directory, "payload").listFiles();
                    if (payloads != null) for (File payload : payloads) payload.delete();
                    return state.toString();
                }
                if (message.sendingState instanceof TdApi.MessageSendingStateFailed) {
                    state.put("phase", "failed").put("messageId", message.id);
                    write(ledger, state);
                    throw new IOException(
                            "The Telegram copy failed. The completed file is kept for retry.");
                }
                if (System.nanoTime() >= deadline)
                    throw new IOException(
                            "Telegram is still sending. Retry to check the existing copy.");
                synchronized (RECENT) {
                    TdApi.Message update = RECENT.get(recentKey(value, chatId, pendingId));
                    if (update != null) message = update;
                    else RECENT.wait(500);
                }
            }
        } finally {
            ACTIVE.remove(key, Thread.currentThread());
        }
    }

    public static void cancel(String durableToken) {
        try {
            String key =
                    hex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(durableToken.getBytes(StandardCharsets.UTF_8)));
            Thread worker = ACTIVE.get(key);
            if (worker != null) worker.interrupt();
        } catch (Exception ignored) {
            /* No pending local wait. Telegram's receipt is retained. */
        }
    }

    static void onUpdate(TelegramBridge owner, TdApi.Object object) {
        TdApi.Message message;
        long oldId;
        if (object instanceof TdApi.UpdateMessageSendSucceeded) {
            TdApi.UpdateMessageSendSucceeded update = (TdApi.UpdateMessageSendSucceeded) object;
            message = update.message;
            oldId = update.oldMessageId;
        } else if (object instanceof TdApi.UpdateMessageSendFailed) {
            TdApi.UpdateMessageSendFailed update = (TdApi.UpdateMessageSendFailed) object;
            message = update.message;
            oldId = update.oldMessageId;
        } else return;
        if (message == null) return;
        synchronized (RECENT) {
            RECENT.put(recentKey(owner, message.chatId, oldId), message);
            RECENT.notifyAll();
        }
    }

    private static String recentKey(TelegramBridge owner, long chat, long message) {
        return owner.sessionTag + ":" + owner.authorizationRevision + ":" + chat + ":" + message;
    }

    static boolean matches(TdApi.Message message, long chat, String caption, long size) {
        if (message == null
                || message.chatId != chat
                || !(message.content instanceof TdApi.MessageDocument)) return false;
        TdApi.MessageDocument content = (TdApi.MessageDocument) message.content;
        return content.caption != null
                && caption.equals(content.caption.text)
                && content.document != null
                && content.document.document != null
                && content.document.document.size == size;
    }

    private static void check(TelegramBridge value, long generation, long authorization)
            throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException(
                    "Telegram copy wait cancelled; check its saved receipt before retry.");
        if (value.generation != generation
                || value.authorizationRevision != authorization
                || !value.ready())
            throw new IOException(
                    "Telegram session changed; reconnect the selected account before retrying.");
    }

    private static void worker() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("Telegram download work must run off the main thread.");
    }

    private static String digest(File source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Upload cancelled.");
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data)
            text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static JSONObject read(AtomicFile file) throws Exception {
        byte[] bytes = file.readFully();
        if (bytes.length > 16384) throw new IOException("Invalid Telegram copy receipt.");
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void write(AtomicFile file, JSONObject value) throws Exception {
        FileOutputStream output = file.startWrite();
        try {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception failure) {
            file.failWrite(output);
            throw failure;
        }
    }
}
