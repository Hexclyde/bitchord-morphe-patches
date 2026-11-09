package android.media;

import java.io.Closeable;
import java.io.IOException;

/** The platform class throws "Stub!" from its constructor; this one is a plain base class. */
public abstract class MediaDataSource implements Closeable {
    public abstract int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException;

    public abstract long getSize() throws IOException;
}
