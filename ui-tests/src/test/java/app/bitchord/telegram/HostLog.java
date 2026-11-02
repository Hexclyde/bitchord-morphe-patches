package app.bitchord.telegram;

/** Fixture: BitChord's log isn't on the UI tests' classpath. */
final class HostLog {
    private HostLog() {}

    static java.util.function.Consumer<String> observer;

    static void d(String message) {
        if (observer != null) observer.accept(message);
    }

    static String length(int seconds) {
        return seconds <= 0 ? "?" : seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
