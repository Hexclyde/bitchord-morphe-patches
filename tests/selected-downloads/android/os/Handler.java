package android.os;

public final class Handler {
    public Handler(Looper looper) {}

    public boolean post(Runnable task) {
        task.run();
        return true;
    }
}
