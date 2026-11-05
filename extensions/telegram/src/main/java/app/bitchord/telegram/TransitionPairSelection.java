package app.bitchord.telegram;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.media3.common.MediaItem;

import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Audio files chosen for a transition preview. The playing queue is never touched here. */
final class TransitionPairSelection implements AutoCloseable {
    static final int OUTGOING_REQUEST = 0x4d01, INCOMING_REQUEST = 0x4d02;
    private static final long MINIMUM_MS = 2000;
    private static final String PICK_OWNER = "bitchord.transitionPickerOwner",
            PICK_URI = "bitchord.transitionPickedUri",
            PICK_PERSISTABLE = "bitchord.transitionPersistableRead";

    /** What the file itself says about its audio. */
    static final class Info {
        final String title, artist;
        final long durationMs;

        Info(String title, String artist, long durationMs) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.durationMs = durationMs;
        }
    }

    interface Reader {
        Info read(Context context, Uri uri) throws IOException;
    }

    static Reader reader = TransitionPairSelection::frameworkRead;

    private Consumer<TransitionRuntime.Pair> selected;
    private Consumer<String> error;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Boolean> picked = new HashMap<>();
    private final String origin = UUID.randomUUID().toString();
    private TransitionRuntime.Pair current;
    private Context context;
    private ExecutorService worker;
    private Future<?> pending;
    private int generation;
    private boolean closed;
    private Retention open;

    TransitionPairSelection(
            TransitionRuntime.Pair initial,
            Consumer<TransitionRuntime.Pair> selected,
            Consumer<String> error) {
        current = initial;
        context = SoundExtras.app();
        this.selected = selected;
        this.error = error;
    }

    TransitionRuntime.Pair current() {
        return current;
    }

    /** Binds a recreated screen. The selection and any access being kept carry over. */
    void reattach(Consumer<TransitionRuntime.Pair> selected, Consumer<String> error) {
        if (closed) return;
        this.selected = selected;
        this.error = error;
    }

    /** Lets go of the screen's callbacks; reads still running are dropped. */
    void detach() {
        cancelPending();
        selected = null;
        error = null;
    }

    private void fail(String message) {
        Consumer<String> report = error;
        if (report != null) report.accept(message);
    }

    /** Opens the system file chooser for the outgoing (0) or incoming (1) side. */
    void launch(Activity activity, int side) {
        if (closed) return;
        if (side != 0 && side != 1) throw new IllegalArgumentException("side");
        cancelPending();
        context = activity.getApplicationContext();
        Intent choose =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("audio/*")
                        .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(
                    choose, side == 0 ? OUTGOING_REQUEST : INCOMING_REQUEST);
        } catch (ActivityNotFoundException absent) {
            fail(Strings.t("No file picker is available"));
        }
    }

    /** Returns false for results that belong to something else. */
    boolean handleResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != OUTGOING_REQUEST && requestCode != INCOMING_REQUEST) return false;
        if (closed) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            cancelPending();
            return true;
        }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme()) || context == null) {
            cancelPending();
            fail(Strings.t("Could not read this file"));
            return true;
        }
        boolean persistable = (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        cancelPending();
        int token = generation;
        int side = requestCode == OUTGOING_REQUEST ? 0 : 1;
        Context app = context;
        if (worker == null)
            worker =
                    Executors.newSingleThreadExecutor(
                            task -> {
                                Thread thread = new Thread(task, "automix-file");
                                thread.setDaemon(true);
                                return thread;
                            });
        pending = worker.submit(() -> read(app, token, side, uri, persistable));
        return true;
    }

    /** Swaps the sides of the current pair; the queue is not touched. */
    TransitionRuntime.Pair reverse() {
        cancelPending();
        TransitionRuntime.Pair from = current;
        TransitionRuntime.Pair back =
                new TransitionRuntime.Pair(
                        from.incomingId,
                        from.outgoingId,
                        from.incomingTitle,
                        from.outgoingTitle,
                        from.incomingDuration,
                        from.outgoingDuration,
                        from.index,
                        from.nextIndex);
        back.outgoing = from.incoming;
        back.incoming = from.outgoing;
        current = back;
        return back;
    }

    /** Continues from the pair the queue now holds. Chosen files are no longer pending. */
    void reset(TransitionRuntime.Pair base) {
        cancelPending();
        picked.clear();
        current = base;
    }

    private void cancelPending() {
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
    }

    private void read(Context app, int token, int side, Uri uri, boolean persistable) {
        MediaItem item = null;
        Info info = null;
        double incoming = 0;
        boolean failed = false;
        try {
            info = reader.read(app, uri);
            if (info.durationMs >= MINIMUM_MS) {
                item = item(uri, info);
                if (item.mediaMetadata.extras == null)
                    throw new IOException("The file metadata cannot hold its access receipt");
                item.mediaMetadata.extras.putString(PICK_OWNER, origin);
                item.mediaMetadata.extras.putString(PICK_URI, uri.toString());
                item.mediaMetadata.extras.putBoolean(PICK_PERSISTABLE, persistable);
                incoming = TransitionRuntime.duration(item);
                if (side == 1 && incoming <= 0) failed = true;
                // The stable fingerprint is read here so the page never has to on the main thread.
                Uri stable = SourceFirst.renditionUri(item);
                if (stable != null) SourceAutomix.prepareIdentity(stable).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception | LinkageError | OutOfMemoryError unreadable) {
            failed = true;
            HostLog.d("AutoMix file could not be read: " + unreadable.getClass().getSimpleName());
        }
        MediaItem built = item;
        double seconds = side == 0 && info != null ? info.durationMs / 1000d : incoming;
        boolean tooShort = !failed && built == null;
        boolean bad = failed;
        main.post(
                () -> {
                    if (closed || token != generation) return;
                    pending = null;
                    if (bad) {
                        fail(Strings.t("Could not read this file"));
                        return;
                    }
                    if (tooShort) {
                        fail(Strings.t("Not enough audio"));
                        return;
                    }
                    place(side, built, seconds, uri, persistable);
                });
    }

    private void place(int side, MediaItem item, double seconds, Uri uri, boolean persistable) {
        TransitionRuntime.Pair from = current;
        MediaItem outgoing = side == 0 ? item : from.outgoing;
        MediaItem incoming = side == 1 ? item : from.incoming;
        if (outgoing.mediaId.equals(incoming.mediaId)) {
            fail(Strings.t("Choose a different file"));
            return;
        }
        TransitionRuntime.Pair next =
                new TransitionRuntime.Pair(
                        outgoing.mediaId,
                        incoming.mediaId,
                        TransitionRuntime.title(outgoing),
                        TransitionRuntime.title(incoming),
                        side == 0 ? seconds : from.outgoingDuration,
                        side == 1 ? seconds : from.incomingDuration,
                        from.index,
                        from.nextIndex);
        next.outgoing = outgoing;
        next.incoming = incoming;
        picked.put(uri.toString(), persistable);
        // Older selections carry their own receipt; only the current pair needs this lookup.
        picked.keySet()
                .retainAll(
                        new HashSet<>(
                                Arrays.asList(
                                        outgoing.localConfiguration.uri.toString(),
                                        incoming.localConfiguration.uri.toString())));
        current = next;
        Consumer<TransitionRuntime.Pair> deliver = selected;
        if (deliver != null) deliver.accept(next);
    }

    /** A queue entry for the file, built the way BitChord builds one for a device track. */
    private static MediaItem item(Uri uri, Info info) throws Exception {
        long seconds = info.durationMs / 1000;
        String last = uri.getLastPathSegment();
        String title = !info.title.isEmpty() ? info.title : last == null ? "" : last;
        JSONObject song = MixedSongCodec.encode(MixedSongCodec.file(uri.toString(), title));
        song.put("artist", info.artist);
        song.put("durationText", String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        Object decoded = MixedSongCodec.decode(song);
        return (MediaItem)
                Class.forName("com.music.bitchord.playback.PlayerConnectionKt")
                        .getMethod("toMediaItem", decoded.getClass())
                        .invoke(null, decoded);
    }

    private static Info frameworkRead(Context app, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            extractor.setDataSource(app, uri, null);
            boolean audio = false, video = false;
            long extractorUs = 0;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("audio/")) {
                    audio = true;
                    if (format.containsKey(MediaFormat.KEY_DURATION))
                        extractorUs =
                                Math.max(extractorUs, format.getLong(MediaFormat.KEY_DURATION));
                } else if (mime.startsWith("video/")) video = true;
            }
            if (!audio || video) throw new IOException("Not an audio file");
            retriever.setDataSource(app, uri);
            long duration =
                    number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (duration <= 0) duration = extractorUs / 1000;
            if (duration <= 0) throw new IOException("No duration");
            return new Info(
                    title(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                            displayName(app.getContentResolver(), uri)),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    duration);
        } catch (RuntimeException unreadable) {
            throw new IOException("Unreadable", unreadable);
        } finally {
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
                // The retriever is still released below.
            }
            try {
                retriever.release();
            } catch (RuntimeException ignored) {
                // Nothing further holds the file.
            }
        }
    }

    private static long number(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    /** The file's own title, else the name the provider shows for it without its extension. */
    static String title(String tag, String displayName) {
        if (tag != null && !tag.trim().isEmpty()) return tag;
        if (displayName == null) return "";
        int dot = displayName.lastIndexOf('.');
        return dot > 0 ? displayName.substring(0, dot) : displayName;
    }

    static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor =
                resolver.query(
                        uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Keeps read access to the files in the selection for Save or Use. Only grants taken here are
     * ever released here, and only when the save does not go through.
     */
    Retention retainSelectionForSave(TransitionRuntime.Pair selection) throws IOException {
        if (closed) throw new IOException(Strings.t("This file can’t stay available"));
        if (open != null) throw new IllegalStateException("Another save is keeping files");
        Map<Uri, Boolean> wanted = new LinkedHashMap<>();
        for (MediaItem item : new MediaItem[] {selection.outgoing, selection.incoming}) {
            if (item == null || item.localConfiguration == null) continue;
            Uri uri = item.localConfiguration.uri;
            android.os.Bundle extras = item.mediaMetadata.extras;
            if (extras != null && extras.containsKey(PICK_OWNER)) {
                if (context != null && holdsRead(context.getContentResolver(), uri)) continue;
                if (!origin.equals(extras.getString(PICK_OWNER))
                        || !uri.toString().equals(extras.getString(PICK_URI)))
                    throw new IOException(Strings.t("This file can’t stay available"));
                wanted.put(uri, extras.getBoolean(PICK_PERSISTABLE));
            } else if (picked.containsKey(uri.toString()))
                wanted.put(uri, picked.get(uri.toString()));
        }
        Retention retention = new Retention();
        if (wanted.isEmpty()) {
            open = retention;
            return retention;
        }
        if (context == null) throw new IOException(Strings.t("This file can’t stay available"));
        ContentResolver resolver = context.getContentResolver();
        try {
            for (Uri uri : wanted.keySet()) {
                if (holdsRead(resolver, uri)) continue;
                if (!wanted.get(uri)
                        || context.checkUriPermission(
                                        uri,
                                        android.os.Process.myPid(),
                                        android.os.Process.myUid(),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    throw new IOException(Strings.t("This file can’t stay available"));
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                retention.taken.add(uri);
                if (!holdsRead(resolver, uri))
                    throw new IOException(Strings.t("This file can’t stay available"));
            }
        } catch (SecurityException refused) {
            retention.close();
            throw new IOException(Strings.t("This file can’t stay available"), refused);
        } catch (IOException refused) {
            retention.close();
            throw refused;
        }
        open = retention;
        return retention;
    }

    /** Whether the playing queue holds this exact file; no other lookup is made. */
    private static boolean queued(Uri uri) {
        try {
            Object service = SourceAutomix.owner();
            if (service == null) return false;
            Object player = TransitionRuntime.player();
            int count =
                    ((Number) TransitionRuntime.pcall(player, "getMediaItemCount", new Class<?>[0]))
                            .intValue();
            for (int at = 0; at < count; at++) {
                MediaItem item =
                        (MediaItem)
                                TransitionRuntime.pcall(
                                        player, "getMediaItemAt", new Class<?>[] {int.class}, at);
                if (item.localConfiguration != null && uri.equals(item.localConfiguration.uri))
                    return SourceAutomix.owner() == service && TransitionRuntime.player() == player;
            }
        } catch (Exception unavailable) {
            // An unavailable player cannot establish a queued URI.
        }
        return false;
    }

    private static boolean holdsRead(ContentResolver resolver, Uri uri) {
        for (UriPermission permission : resolver.getPersistedUriPermissions())
            if (permission.isReadPermission() && uri.equals(permission.getUri())) return true;
        return false;
    }

    final class Retention implements AutoCloseable {
        private final List<Uri> taken = new ArrayList<>();
        private boolean finished;

        /** The save went through; the access stays. */
        void commit() {
            finish();
        }

        /**
         * Without a commit, releases the access this retention took, except for a file that is
         * really in the playing queue because a rollback could not remove it.
         */
        public void close() {
            if (finished) return;
            for (Uri uri : taken) {
                if (queued(uri)) continue;
                try {
                    context.getContentResolver()
                            .releasePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (RuntimeException unavailable) {
                    HostLog.d("AutoMix could not release file access");
                }
            }
            finish();
        }

        private void finish() {
            finished = true;
            if (open == this) open = null;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        // A save in progress owns its Retention and finishes it itself.
        closed = true;
        selected = null;
        error = null;
        cancelPending();
        if (worker != null) worker.shutdownNow();
        worker = null;
    }
}
