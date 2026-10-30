package app.bitchord.telegram;

import android.content.Context;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory TDLib server behavior for the production receipt state machine. */
public final class TelegramBridge {
    public static TelegramBridge instance;
    public final File home;
    public long generation = 1;
    public volatile long authorizationRevision;
    public String sessionTag = "fixture-session";
    public final MainChannel mainChannel = new MainChannel();
    public volatile long accountId = 42;
    public volatile boolean connected = true,
            privateOwned = true,
            throwAfterSend,
            findSubmitted,
            confirmBeforeReturn;
    public volatile TdApi.Message lastSent;
    public volatile File sentPayload;
    public final AtomicInteger sends = new AtomicInteger(),
            searches = new AtomicInteger(),
            resends = new AtomicInteger();

    public TelegramBridge(File home) {
        this.home = home;
        home.mkdirs();
    }

    public static void init(Context context) {}

    public boolean ready() {
        return connected;
    }

    Boolean ownChannel(TdApi.Chat chat) {
        return privateOwned ? Boolean.FALSE : Boolean.TRUE;
    }

    @SuppressWarnings("unchecked")
    <T extends TdApi.Object> T call(TdApi.Function<T> request) throws Exception {
        return call(request, 30000);
    }

    @SuppressWarnings("unchecked")
    <T extends TdApi.Object> T call(TdApi.Function<T> request, int timeoutMs) throws Exception {
        if (request instanceof TdApi.GetMe) {
            TdApi.User user = new TdApi.User();
            user.id = accountId;
            return (T) user;
        }
        if (request instanceof TdApi.GetChat) {
            TdApi.Chat chat = new TdApi.Chat();
            chat.id = ((TdApi.GetChat) request).chatId;
            chat.title = "Private download channel";
            return (T) chat;
        }
        if (request instanceof TdApi.SendMessage) {
            TdApi.SendMessage send = (TdApi.SendMessage) request;
            if (!(send.inputMessageContent instanceof TdApi.InputMessageDocument))
                throw new AssertionError("must send document");
            TdApi.InputMessageDocument content =
                    (TdApi.InputMessageDocument) send.inputMessageContent;
            if (!content.document.disableContentTypeDetection)
                throw new AssertionError("content detection must be disabled");
            sentPayload = new File(((TdApi.InputFileLocal) content.document.document).path);
            TdApi.File file = new TdApi.File();
            file.size = sentPayload.length();
            TdApi.Document document = new TdApi.Document();
            document.document = file;
            document.fileName = sentPayload.getName();
            TdApi.Message message = new TdApi.Message();
            message.chatId = send.chatId;
            message.id = -sends.incrementAndGet();
            message.sendingState = new TdApi.MessageSendingStatePending();
            message.content = new TdApi.MessageDocument(document, content.caption);
            lastSent = message;
            if (throwAfterSend) throw new IOException("Transport timed out after submission");
            if (confirmBeforeReturn) {
                TdApi.Message confirmed = new TdApi.Message();
                confirmed.chatId = message.chatId;
                confirmed.id = 9000 + sends.get();
                confirmed.content = message.content;
                DownloadUploadBridge.onUpdate(
                        this, new TdApi.UpdateMessageSendSucceeded(confirmed, message.id));
            }
            return (T) message;
        }
        if (request instanceof TdApi.GetMessage) {
            TdApi.GetMessage get = (TdApi.GetMessage) request;
            if (lastSent == null || get.messageId != lastSent.id)
                throw new IOException("message replaced");
            return (T) lastSent;
        }
        if (request instanceof TdApi.SearchChatMessages) {
            searches.incrementAndGet();
            return (T)
                    new TdApi.FoundChatMessages(
                            findSubmitted ? 1 : 0,
                            findSubmitted ? new TdApi.Message[] {lastSent} : new TdApi.Message[0],
                            0);
        }
        if (request instanceof TdApi.ResendMessages) {
            resends.incrementAndGet();
            TdApi.Message confirmed = new TdApi.Message();
            confirmed.chatId = lastSent.chatId;
            confirmed.id = 10000 + resends.get();
            confirmed.content = lastSent.content;
            lastSent = confirmed;
            return (T) new TdApi.Messages(1, new TdApi.Message[] {lastSent});
        }
        throw new AssertionError("Unexpected request: " + request.getClass().getName());
    }

    static final class MainChannel {
        long main() {
            return -100123;
        }
    }

    static final class TelegramFailure extends IOException {
        TelegramFailure(String message) {
            super(message);
        }
    }
}
