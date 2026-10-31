package app.bitchord.soulseek;

import android.net.Uri;

import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

/**
 * A private player factory receives this capability; no generic HTTP/source router can create it.
 */
final class PreviewDataSource extends BaseDataSource {
    private final SoulseekPreview.Session session;
    private ProgressiveReader reader;

    PreviewDataSource(SoulseekPreview.Session session) {
        super(true);
        this.session = session;
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        if (reader != null) throw new IOException("Preview reader is already open");
        session.check();
        if (!session.uri.equals(spec.uri.toString()))
            throw new IOException("Preview file identity changed");
        ProgressiveReader.Snapshot current = session.snapshot();
        if (spec.position > current.downloaded)
            throw new IOException("That position is not buffered yet");
        transferInitializing(spec);
        reader = session.reader(spec.position, spec.length);
        transferStarted(spec);
        return reader.remaining();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (reader == null) throw new IOException("Preview reader is closed");
        try {
            int count = reader.read(bytes, offset, length);
            if (count > 0) bytesTransferred(count);
            return count;
        } catch (SoulseekPreview.PreviewStopped stopped) {
            throw (IOException) stopped.getCause();
        }
    }

    @Override
    public Uri getUri() {
        return reader == null ? null : Uri.parse(session.uri);
    }

    @Override
    public java.util.Map<String, java.util.List<String>> getResponseHeaders() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public void close() {
        ProgressiveReader current = reader;
        reader = null;
        if (current != null) {
            session.release(current);
            transferEnded();
        }
    }
}
