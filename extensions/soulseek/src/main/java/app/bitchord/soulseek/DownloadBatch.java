package app.bitchord.soulseek;

import android.content.Context;

import java.util.*;

/** Reviewable per-track plan. A submitted row is never retried implicitly after partial failure. */
final class DownloadBatch {
    interface Selection {
        void start(boolean now) throws Exception;
    }

    static final class Choice {
        final String detail;
        final Selection selection;
        volatile boolean submitted, submitting;
        volatile String error = "";

        Choice(String detail, Selection selection) {
            this.detail = detail;
            this.selection = selection;
        }
    }

    private static final Map<String, Map<Integer, Choice>> plans = new HashMap<>();

    static synchronized Choice choice(String token, int index) {
        return plans.getOrDefault(token, Collections.emptyMap()).get(index);
    }

    static synchronized void select(String token, int index, String detail, Selection selection) {
        if (index < 0 || index >= SoulseekDownloadChoice.songs(token).size())
            throw new IllegalArgumentException("Invalid track index");
        if (!SoulseekDownloadChoice.available(token))
            throw new IllegalStateException("Download selection expired");
        Choice old = choice(token, index);
        if (old != null && (old.submitted || old.submitting))
            throw new IllegalStateException("Track was already submitted");
        plans.computeIfAbsent(token, key -> new HashMap<>())
                .put(index, new Choice(detail, selection));
    }

    static synchronized void clear(String token, int index) {
        Choice value = choice(token, index);
        if (value != null && !value.submitted && !value.submitting) plans.get(token).remove(index);
    }

    static synchronized void forget(String token) {
        plans.remove(token);
    }

    static String normalized(String value) {
        return java.text.Normalizer.normalize(
                        value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static boolean matches(Object requested, Object result) {
        String title = normalized(HostDownloadAccess.propertyText(requested, "title"));
        String artist = normalized(HostDownloadAccess.propertyText(requested, "artist"));
        return !title.isEmpty()
                && !artist.isEmpty()
                && title.equals(normalized(HostDownloadAccess.propertyText(result, "title")))
                && artist.equals(normalized(HostDownloadAccess.propertyText(result, "artist")));
    }

    static void find(String token, Object source, Context context) throws Exception {
        List<?> songs = SoulseekDownloadChoice.songs(token);
        String id = HostDownloadAccess.sourceText(source, "getConfigId"),
                name = HostDownloadAccess.sourceText(source, "getDisplayName");
        for (int i = 0; i < songs.size(); i++) {
            if (Thread.currentThread().isInterrupted() || !SoulseekDownloadChoice.available(token))
                return;
            if (choice(token, i) != null) continue;
            Object requested = songs.get(i);
            String query =
                    HostDownloadAccess.propertyText(requested, "artist")
                            + " "
                            + HostDownloadAccess.propertyText(requested, "title");
            if (query.length() > 256) continue;
            List<?> results = HostDownloadAccess.search(source, query);
            Object only = null;
            int matches = 0;
            for (Object result : results) {
                if (HostDownloadAccess.propertyText(result, "videoId")
                                .startsWith("src:" + id + "::")
                        && matches(requested, result)) {
                    only = result;
                    matches++;
                }
            }
            if (Thread.currentThread().isInterrupted()) return;
            if (matches == 1) {
                Object selected = only;
                Context app = context.getApplicationContext();
                synchronized (DownloadBatch.class) {
                    if (choice(token, i) == null)
                        select(
                                token,
                                i,
                                name
                                        + " · "
                                        + HostDownloadAccess.propertyText(
                                                selected, "sourceQuality"),
                                now -> HostDownloadAccess.enqueue(app, selected, id, now));
                }
            }
        }
    }

    static void findSoulseek(String token, Context context) throws Exception {
        SoulseekBridge bridge = SoulseekBridge.get();
        List<?> songs = SoulseekDownloadChoice.songs(token);
        for (int index = 0; index < songs.size(); index++) {
            if (Thread.currentThread().isInterrupted() || !SoulseekDownloadChoice.available(token))
                return;
            if (choice(token, index) != null) continue;
            Object requested = songs.get(index);
            String title = HostDownloadAccess.propertyText(requested, "title"),
                    artist = HostDownloadAccess.propertyText(requested, "artist");
            String query = artist + " " + title;
            if (query.length() > 256) continue;
            SoulseekTrack only = null;
            int count = 0;
            for (SoulseekTrack row : bridge.search(query))
                if (row.size <= 256L * 1024 * 1024
                        && !normalized(artist).isEmpty()
                        && normalized(title).equals(normalized(row.title))
                        && normalized(artist).equals(normalized(row.artist))) {
                    only = row;
                    count++;
                }
            if (Thread.currentThread().isInterrupted()) return;
            if (count != 1) continue;
            final SoulseekTrack file = only;
            final String session = bridge.sessionTag;
            Context app = context.getApplicationContext();
            synchronized (DownloadBatch.class) {
                if (choice(token, index) == null)
                    select(
                            token,
                            index,
                            "Soulseek · "
                                    + file.advertisedExtension.toUpperCase(Locale.ROOT)
                                    + " · "
                                    + file.peer
                                    + " · "
                                    + file.size
                                    + " bytes",
                            now -> {
                                if (!session.equals(bridge.sessionTag))
                                    throw new java.io.IOException("Soulseek search result expired");
                                if (!SoulseekSelectedDownloads.enqueueSelectedFile(
                                        app, file, session, now))
                                    throw new java.io.IOException(
                                            "Soulseek download was not accepted");
                            });
            }
        }
    }

    static int submit(String token, boolean now) throws Exception {
        int count = 0;
        List<?> songs = SoulseekDownloadChoice.songs(token);
        for (int i = 0; i < songs.size(); i++) {
            if (Thread.currentThread().isInterrupted()) break;
            Choice choice = choice(token, i);
            if (choice == null || choice.submitted) continue;
            synchronized (DownloadBatch.class) {
                if (choice.submitted || choice.submitting) continue;
                choice.submitting = true;
            }
            try {
                choice.selection.start(now);
                choice.submitted = true;
                choice.error = "";
                count++;
            } catch (Exception failed) {
                choice.error = "Submission failed. Choose again or retry.";
            } finally {
                choice.submitting = false;
            }
        }
        return count;
    }
}
