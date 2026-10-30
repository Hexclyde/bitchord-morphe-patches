package android.content;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Context {
    private final File home;
    public final List<Class<?>> launches = new CopyOnWriteArrayList<>();

    public Context(File home) {
        this.home = home;
    }

    public Context getApplicationContext() {
        return this;
    }

    public File getNoBackupFilesDir() {
        return home;
    }

    public void startActivity(Intent intent) {
        launches.add(intent.target);
    }
}
