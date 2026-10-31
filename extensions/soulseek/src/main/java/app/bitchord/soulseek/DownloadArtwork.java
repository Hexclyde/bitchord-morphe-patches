package app.bitchord.soulseek;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;

/** Bounded off-main cover loading; never replace real artwork with fabricated album images. */
final class DownloadArtwork {
    private static final LruCache<String, Bitmap> cache =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                protected int sizeOf(String key, Bitmap image) {
                    return image.getAllocationByteCount();
                }
            };
    private static final ExecutorService work =
            new ThreadPoolExecutor(
                    2,
                    2,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    r -> {
                        Thread t = new Thread(r, "download-artwork");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.DiscardPolicy());

    private DownloadArtwork() {}

    static void bind(ImageView view, String artwork, String audio) {
        String key = artwork + "|" + audio;
        view.setTag(key);
        Bitmap known = cache.get(key);
        if (known != null) {
            view.setImageBitmap(known);
            view.setVisibility(View.VISIBLE);
            return;
        }
        if (artwork.isEmpty() && audio.isEmpty()) return;
        Context context = view.getContext().getApplicationContext();
        WeakReference<ImageView> target = new WeakReference<>(view);
        work.execute(
                () -> {
                    Bitmap image = null;
                    try {
                        if (!artwork.isEmpty()) image = fromImage(context, artwork);
                    } catch (Exception | LinkageError unavailable) {
                        /* Try embedded artwork next. */
                    }
                    if (image == null && !audio.isEmpty())
                        try {
                            image = fromAudio(context, audio);
                        } catch (Exception | LinkageError unavailable) {
                            /* Keep the neutral music placeholder. */
                        }
                    if (image == null) return;
                    cache.put(key, image);
                    ImageView current = target.get();
                    if (current == null) return;
                    Bitmap ready = image;
                    current.post(
                            () -> {
                                if (key.equals(current.getTag())) {
                                    current.setImageBitmap(ready);
                                    current.setVisibility(View.VISIBLE);
                                }
                            });
                });
    }

    private static Bitmap fromImage(Context context, String value) throws Exception {
        Uri uri = Uri.parse(value);
        if ("file".equals(uri.getScheme()) || "content".equals(uri.getScheme())) {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                return read(in);
            }
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            return null;
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                return read(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Bitmap fromAudio(Context context, String value) throws Exception {
        Uri uri = Uri.parse(value);
        if (!"file".equals(uri.getScheme()) && !"content".equals(uri.getScheme())) return null;
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, uri);
            return decode(reader.getEmbeddedPicture());
        } finally {
            reader.release();
        }
    }

    private static Bitmap read(InputStream input) throws Exception {
        if (input == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        while ((n = input.read(block)) != -1) {
            if (out.size() + n > 4 * 1024 * 1024) return null;
            out.write(block, 0, n);
        }
        return decode(out.toByteArray());
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length > 4 * 1024 * 1024) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
