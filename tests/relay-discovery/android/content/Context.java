package android.content;

import android.content.res.AssetManager;
import android.net.ConnectivityManager;

import java.io.File;

public final class Context {
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public final AssetManager assets = new AssetManager();
    public final ConnectivityManager connectivity = new ConnectivityManager();
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

    public AssetManager getAssets() {
        return assets;
    }

    public ContentResolver getContentResolver() {
        return new ContentResolver();
    }

    public Object getSystemService(String name) {
        if (!CONNECTIVITY_SERVICE.equals(name)) throw new AssertionError("Unexpected service");
        return connectivity;
    }
}
