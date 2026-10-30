package android.content.res;

import java.io.*;

public abstract class AssetManager {
    public abstract InputStream open(String name) throws IOException;
}
