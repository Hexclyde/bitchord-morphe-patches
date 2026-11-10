package app.bitchord.telegram;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.*;
import java.util.concurrent.*;

/** Java-only TDLib control adapter. All v2 download/cancel commands enter this coordinator. */
final class TelegramStreams {
    final TelegramBridge owner;
    final TelegramStreamCoordinator coordinator;
    private final ThreadPoolExecutor requests =
            new ThreadPoolExecutor(
                    2,
                    2,
                    10,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    task -> {
                        Thread thread = new Thread(task, "Telegram stream requests");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    TelegramStreams(TelegramBridge owner) {
        this.owner = owner;
        requests.allowCoreThreadTimeOut(true);
        coordinator =
                new TelegramStreamCoordinator(
                        new TelegramStreamCoordinator.Backend() {
                            @Override
                            public long prefix(int file, long offset) throws Exception {
                                return owner.call(
                                                new TdApi.GetFileDownloadedPrefixSize(file, offset),
                                                15000)
                                        .size;
                            }

                            @Override
                            public void download(
                                    int file,
                                    int priority,
                                    long offset,
                                    long length,
                                    TelegramStreamCoordinator.Reply reply) {
                                Client client = owner.client;
                                long session = owner.generation;
                                if (client == null) {
                                    reply.done(new IOException("Telegram is unavailable"), 0);
                                    return;
                                }
                                client.send(
                                        new TdApi.DownloadFile(
                                                file, priority, offset, length, false),
                                        result -> {
                                            if (session != owner.generation) return;
                                            if (result instanceof TdApi.Error) {
                                                TdApi.Error error = (TdApi.Error) result;
                                                long pause = retryAfter(error);
                                                reply.done(
                                                        new IOException(
                                                                pause > 0
                                                                        ? "Telegram download rate"
                                                                                + " limited"
                                                                        : "Telegram download"
                                                                                + " failed"),
                                                        pause);
                                            } else {
                                                if (result instanceof TdApi.File)
                                                    update((TdApi.File) result, false);
                                                reply.done(null, 0);
                                            }
                                        });
                            }

                            @Override
                            public void cancel(int file) {
                                Client client = owner.client;
                                if (client != null)
                                    client.send(new TdApi.CancelDownloadFile(file, false), null);
                            }
                        },
                        () -> System.nanoTime() / 1_000_000L);
    }

    static long retryAfter(TdApi.Error error) {
        String message = error.message == null ? "" : error.message;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                "(?:retry after |FLOOD_(?:PREMIUM_)?WAIT_)([0-9]+)",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(message);
        if (matcher.find()) {
            try {
                return Math.min(Long.MAX_VALUE / 4000, Long.parseLong(matcher.group(1))) * 1000;
            } catch (NumberFormatException ignored) {
                return Long.MAX_VALUE / 4;
            }
        }
        return error.code == 429 ? 60000 : 0;
    }

    void update(TdApi.File file) {
        update(file, true);
    }

    private void update(TdApi.File file, boolean pushed) {
        if (file == null || file.local == null) return;
        TdApi.LocalFile local = file.local;
        TelegramStreamCoordinator.State state =
                new TelegramStreamCoordinator.State(
                        local.path,
                        file.size,
                        local.downloadOffset,
                        local.downloadedPrefixSize,
                        local.downloadedSize,
                        local.isDownloadingCompleted,
                        local.isDownloadingActive);
        if (pushed) coordinator.update(file.id, state);
        else coordinator.seed(file.id, state);
    }

    TdApi.File download(
            TdApi.DownloadFile request, TelegramStreamCoordinator.Consumer consumer, long timeout)
            throws Exception {
        if (!owner.streamV2Enabled()) {
            TelegramStreamTrace.Request trace = legacy(request, consumer);
            try {
                TdApi.File file = owner.call(request, timeout);
                legacyResult(trace, request, file);
                return file;
            } catch (Exception failure) {
                if (trace != null) trace.end("failed");
                throw failure;
            }
        }
        owner.requireReady();
        long session = owner.generation;
        TdApi.File initial = owner.call(new TdApi.GetFile(request.fileId), 15000);
        try (TelegramStreamCoordinator.Registration registration =
                coordinator.register(request.fileId, consumer, 0)) {
            update(initial, false);
            long required =
                    request.limit == 0 ? Math.max(0, initial.size - request.offset) : request.limit;
            registration.trace =
                    owner.audio.trace.begin(
                            request.fileId,
                            request.offset,
                            required,
                            consumer.label(),
                            consumer.priority,
                            coordinator.generation(),
                            true,
                            System.nanoTime() / 1_000_000L);
            owner.audio.trace.update(
                    request.fileId, initial.local == null ? 0 : initial.local.downloadedSize);
            registration.await(
                    request.offset,
                    required,
                    timeout,
                    () -> {
                        owner.requireReady();
                        if (session != owner.generation || Thread.currentThread().isInterrupted())
                            throw new CancellationException("Telegram session changed");
                    });
            registration.end = "complete";
            return owner.call(new TdApi.GetFile(request.fileId), 15000);
        }
    }

    void send(
            TdApi.DownloadFile request,
            TelegramStreamCoordinator.Consumer consumer,
            Client.ResultHandler callback) {
        Client client = owner.client;
        if (!owner.streamV2Enabled()) {
            if (client != null) {
                TelegramStreamTrace.Request trace = legacy(request, consumer);
                client.send(
                        request,
                        result -> {
                            legacyResult(trace, request, result);
                            if (callback != null) callback.onResult(result);
                        });
            }
            return;
        }
        long session = owner.generation;
        try {
            requests.execute(
                    () -> {
                        TdApi.Object result;
                        try {
                            if (session != owner.generation)
                                throw new IOException("Telegram session changed");
                            result = download(request, consumer, 40000);
                        } catch (Exception failure) {
                            result = new TdApi.Error(400, "Telegram range unavailable");
                        }
                        if (callback != null) callback.onResult(result);
                    });
        } catch (RejectedExecutionException full) {
            if (callback != null)
                callback.onResult(new TdApi.Error(429, "Telegram stream queue busy"));
        }
    }

    private TelegramStreamTrace.Request legacy(
            TdApi.DownloadFile request, TelegramStreamCoordinator.Consumer consumer) {
        TelegramStreamTrace.Request trace =
                owner.audio.trace.begin(
                        request.fileId,
                        request.offset,
                        request.limit,
                        consumer.label(),
                        request.priority,
                        owner.audio.seekGeneration.get(),
                        false,
                        System.nanoTime() / 1_000_000L);
        if (trace != null) trace.window(request.offset, request.limit, request.priority);
        return trace;
    }

    private void legacyResult(
            TelegramStreamTrace.Request trace, TdApi.DownloadFile request, TdApi.Object result) {
        if (trace == null) return;
        if (result instanceof TdApi.File) {
            TdApi.File file = (TdApi.File) result;
            if (file.local != null) {
                owner.audio.trace.update(file.id, file.local.downloadedSize);
                long required =
                        request.limit == 0
                                ? Math.max(0, file.size - request.offset)
                                : request.limit;
                trace.readable(
                        request.offset, NativeAudio.state(file).prefixAt(request.offset), required);
            }
        }
        trace.end(result instanceof TdApi.Error ? "failed" : "complete");
    }

    void cancelIfUnused(int file) {
        if (owner.streamV2Enabled()) {
            // Closing registrations owns cancellation. Never cancel another consumer's window.
            return;
        }
        Client client = owner.client;
        if (client != null) client.send(new TdApi.CancelDownloadFile(file, false), null);
    }

    void cancelIfUnused(int file, long timeout) throws Exception {
        if (!owner.streamV2Enabled())
            owner.call(new TdApi.CancelDownloadFile(file, false), timeout);
    }

    boolean busy() {
        return requests.getActiveCount() > 0 || !requests.getQueue().isEmpty();
    }

    void reset(boolean shutdown) {
        coordinator.reset(shutdown);
        // Queued requests retain their session token and complete their callback as cancelled.
        // Dropping queue entries would strand callers waiting for those callbacks.
    }
}
