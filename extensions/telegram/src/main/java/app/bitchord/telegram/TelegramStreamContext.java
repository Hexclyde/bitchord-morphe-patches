package app.bitchord.telegram;

/** Explicit scopes at synchronous source-open boundaries; never infer mode from thread names. */
final class TelegramStreamContext implements AutoCloseable {
    private static final ThreadLocal<TelegramStreamCoordinator.Consumer> current =
            new ThreadLocal<>();
    private final TelegramStreamCoordinator.Consumer previous;

    TelegramStreamContext(TelegramStreamCoordinator.Consumer consumer) {
        previous = current.get();
        current.set(consumer);
    }

    static TelegramStreamCoordinator.Consumer consumer() {
        TelegramStreamCoordinator.Consumer consumer = current.get();
        return consumer == null ? TelegramStreamCoordinator.Consumer.PLAYBACK : consumer;
    }

    private static final java.util.Map<String, Integer> previews = new java.util.HashMap<>();

    static synchronized AutoCloseable preview(String key) {
        if (key == null || key.isEmpty() || previews.size() >= 16) return () -> {};
        previews.put(key, previews.getOrDefault(key, 0) + 1);
        return new AutoCloseable() {
            boolean closed;

            public void close() {
                synchronized (TelegramStreamContext.class) {
                    if (closed) return;
                    closed = true;
                    int count = previews.getOrDefault(key, 1) - 1;
                    if (count == 0) previews.remove(key);
                    else previews.put(key, count);
                }
            }
        };
    }

    static synchronized TelegramStreamCoordinator.Consumer forSource(String key, String uri) {
        TelegramStreamCoordinator.Consumer scoped = consumer();
        if (scoped != TelegramStreamCoordinator.Consumer.PLAYBACK) return scoped;
        String base = key == null ? "" : key.split("#", 2)[0];
        return previews.containsKey(base) || previews.containsKey(uri)
                ? TelegramStreamCoordinator.Consumer.PREVIEW
                : scoped;
    }

    @Override
    public void close() {
        if (previous == null) current.remove();
        else current.set(previous);
    }
}
