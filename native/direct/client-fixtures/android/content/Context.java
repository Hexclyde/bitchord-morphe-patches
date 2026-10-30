package android.content;

import android.content.res.AssetManager;

public abstract class Context {
    public static final String CONNECTIVITY_SERVICE = "connectivity";

    public Context getApplicationContext() {
        return this;
    }

    public abstract Object getSystemService(String name);

    public abstract AssetManager getAssets();
}
