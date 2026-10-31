package androidx.media3.datasource;

import android.net.Uri;

public final class DataSpec {
    public final Uri uri;
    public final long position, length;

    public DataSpec(Uri uri) {
        this(uri, 0, -1);
    }

    public DataSpec subrange(long offset) {
        return new DataSpec(uri, position + offset, length < 0 ? -1 : length - offset);
    }

    public DataSpec(Uri uri, long position, long length) {
        this.uri = uri;
        this.position = position;
        this.length = length;
    }
}
