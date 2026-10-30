package android.content;

import java.io.File;

public final class Context {
    private final File home;

    public Context(File home) {
        this.home = home;
    }

    public Context getApplicationContext() {
        return this;
    }

    public File getNoBackupFilesDir() {
        return home;
    }
}
