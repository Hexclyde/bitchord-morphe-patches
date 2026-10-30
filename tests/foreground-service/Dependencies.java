package app.bitchord.soulseek;

// Compile-only stand-ins: production transfer engines are checked in the full
// extension build. LifetimeTest does not instantiate the Android Service.
class BulkDownloads {
    static BulkDownloads existing() {
        return null;
    }

    boolean hasActiveWork() {
        return false;
    }

    java.util.List<Object> shares() {
        return java.util.Collections.emptyList();
    }

    java.util.List<BulkJobStore.Job> jobs() {
        return java.util.Collections.emptyList();
    }

    void stop() throws java.io.IOException {}
}

class BulkCoordinator {
    static BulkCoordinator existing() {
        return null;
    }

    boolean isRunning() {
        return false;
    }

    boolean hasPendingHandshake() {
        return false;
    }

    java.util.List<Object> offers() {
        return java.util.Collections.emptyList();
    }

    void stop() throws java.io.IOException {}
}

class SoulseekSelectedDownloads {
    static int activeCount() {
        return 0;
    }

    static void cancelAll() {}
}

class DebridDownloads {
    static DebridDownloads existing() {
        return null;
    }

    boolean hasActiveWork() {
        return false;
    }

    int activeCount() {
        return 0;
    }

    void stop() throws java.io.IOException {}
}

class BulkDownloadsActivity extends android.app.Activity {}

class BulkJobStore {
    enum State {
        QUEUED,
        RUNNING,
        PUBLISHING,
        COMPLETE
    }

    static class Job {
        State state;
    }
}
