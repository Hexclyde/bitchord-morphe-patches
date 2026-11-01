package app.bitchord.soulseek;

import java.io.Closeable;
import java.io.IOException;

/** Private selected-file input; neither filenames nor URLs are passed to FFmpeg. */
final class NativePreviewAudio implements Closeable {
    private final SoulseekPreview.Session session;
    private ProgressiveReader reader;
    private long position;

    static {
        System.loadLibrary("bitchord_preview");
    }

    NativePreviewAudio(SoulseekPreview.Session session) throws IOException {
        this.session = session;
        reader = session.reader(0, -1);
    }

    public int read(byte[] bytes, int offset, int length) throws IOException {
        session.check();
        int count = reader.read(bytes, offset, length);
        if (count > 0) position += count;
        return count;
    }

    public long seek(long offset, int whence) throws IOException {
        session.check();
        if ((whence & 0x10000) != 0) return session.offer.track.size;
        int mode = whence & ~0x20000;
        long base =
                mode == 0 ? 0 : mode == 1 ? position : mode == 2 ? session.offer.track.size : -1;
        if (base < 0 || offset > Long.MAX_VALUE - base || offset < -base)
            throw new IOException("Invalid preview seek");
        long target = base + offset;
        if (target < 0 || target > session.offer.track.size)
            throw new IOException("Invalid preview seek");
        if (target != position) {
            ProgressiveReader replacement = session.reader(target, -1);
            session.release(reader);
            reader = replacement;
            position = target;
        }
        return position;
    }

    @Override
    public void close() {
        session.release(reader);
    }

    static native long open(Object input) throws IOException;

    static native long[] info(long handle);

    static native String codec(long handle);

    static native byte[] next(long handle) throws IOException;

    static native long time(long handle);

    static native void close(long handle);
}
