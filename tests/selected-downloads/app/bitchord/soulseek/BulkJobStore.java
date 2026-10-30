package app.bitchord.soulseek;

final class BulkJobStore {
    enum State {
        QUEUED,
        RUNNING,
        PAUSED,
        FAILED,
        CANCELLED,
        VERIFIED,
        PUBLISHING,
        COMPLETE
    }

    static final class Job {
        final String id;
        volatile State state = State.VERIFIED;
        volatile String phoneStatus = "pending", telegramStatus = "pending";
        volatile String publishedUri = "", telegramReceipt = "";

        Job(String id) {
            this.id = id;
        }
    }
}
