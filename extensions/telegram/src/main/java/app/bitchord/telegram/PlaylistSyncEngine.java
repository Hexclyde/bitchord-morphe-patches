package app.bitchord.telegram;

import android.content.*;

import org.json.*;

import java.util.*;

/** Native paired-playlist sync with reviewed mappings, a shared baseline and a write journal. */
final class PlaylistSyncEngine {
    static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "playlist-sync");
                        thread.setDaemon(true);
                        return thread;
                    });
    final Context app;
    final SharedPreferences prefs;

    interface SpotifyPort {
        String account() throws Exception;

        SpotifyPlaylistApi.Playlist read(String id) throws Exception;

        java.util.List<SpotifyPlaylistApi.Track> search(PlaylistImport.Track source)
                throws Exception;

        void assertRevision(String id, String revision) throws Exception;

        String add(String id, java.util.List<String> tracks, String revision) throws Exception;

        String remove(String id, java.util.List<String> tracks, String revision) throws Exception;
    }

    interface YouTubePort {
        String account() throws Exception;

        YouTubePlaylistApi.Playlist read(String id) throws Exception;

        void assertUnchanged(YouTubePlaylistApi.Playlist expected) throws Exception;

        void add(YouTubePlaylistApi.Playlist expected, java.util.List<String> tracks)
                throws Exception;

        void remove(
                YouTubePlaylistApi.Playlist expected,
                java.util.List<YouTubePlaylistApi.Track> tracks)
                throws Exception;
    }

    final SpotifyPort spotify;
    final YouTubePort youtube;

    PlaylistSyncEngine(Context app) {
        this(app, new SpotifyPlaylistApi(new SpotifySyncAuth(app)), new YouTubePlaylistApi(app));
    }

    PlaylistSyncEngine(Context app, SpotifyPort spotify, YouTubePort youtube) {
        this.app = app.getApplicationContext();
        prefs = this.app.getSharedPreferences("bitchord_playlist_sync", 0);
        this.spotify = spotify;
        this.youtube = youtube;
    }

    static final class Job {
        String id = UUID.randomUUID().toString(),
                name,
                spotifyId,
                youtubeId,
                spotifyAccount = "",
                youtubeAccount = "",
                phase = "new";
        boolean removals;
        int maxAdds = 100, maxRemovals = 20;
        long interval;
        final LinkedHashMap<String, String> links = new LinkedHashMap<>();
        final Set<String> excludeSpotify = new HashSet<>(), excludeYoutube = new HashSet<>();
        PlaylistSyncPlan.Baseline baseline;
    }

    static JSONObject baseline(PlaylistSyncPlan.Baseline value) throws Exception {
        if (value == null) return null;
        JSONObject peers = new JSONObject();
        for (String key : value.accounts.keySet())
            peers.put(
                    key,
                    new JSONObject()
                            .put("account", value.accounts.get(key))
                            .put("entries", new JSONObject(value.entries.get(key)))
                            .put(
                                    "missing",
                                    new JSONArray(
                                            value.missing.getOrDefault(
                                                    key, Collections.emptySet()))));
        return new JSONObject()
                .put("canonical", new JSONArray(value.canonical))
                .put("peers", peers);
    }

    static PlaylistSyncPlan.Baseline baseline(JSONObject value) throws Exception {
        if (value == null) return null;
        PlaylistSyncPlan.Baseline result = new PlaylistSyncPlan.Baseline();
        result.canonical.addAll(strings(value.getJSONArray("canonical")));
        JSONObject peers = value.getJSONObject("peers");
        Iterator<String> keys = peers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject peer = peers.getJSONObject(key);
            result.accounts.put(key, peer.getString("account"));
            result.entries.put(key, map(peer.getJSONObject("entries")));
            result.missing.put(key, new HashSet<>(strings(peer.getJSONArray("missing"))));
        }
        return result;
    }

    static List<String> strings(JSONArray array) throws Exception {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    static LinkedHashMap<String, String> map(JSONObject object) throws Exception {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, object.getString(key));
        }
        return result;
    }

    static JSONObject encode(Job job) throws Exception {
        return new JSONObject()
                .put("version", 1)
                .put("id", job.id)
                .put("name", job.name)
                .put("spotify", job.spotifyId)
                .put("youtube", job.youtubeId)
                .put("spotifyAccount", job.spotifyAccount)
                .put("youtubeAccount", job.youtubeAccount)
                .put("phase", job.phase)
                .put("removals", job.removals)
                .put("maxAdds", job.maxAdds)
                .put("maxRemovals", job.maxRemovals)
                .put("interval", job.interval)
                .put("links", new JSONObject(job.links))
                .put("excludeSpotify", new JSONArray(job.excludeSpotify))
                .put("excludeYoutube", new JSONArray(job.excludeYoutube))
                .put("baseline", baseline(job.baseline));
    }

    static Job decode(JSONObject json) throws Exception {
        if (json.getInt("version") != 1)
            throw new IllegalArgumentException("Unsupported sync data");
        Job job = new Job();
        job.id = json.getString("id");
        job.name = json.getString("name");
        job.spotifyId = SpotifyPlaylistApi.id(json.getString("spotify"));
        job.youtubeId = YouTubePlaylistApi.rawId(json.getString("youtube"));
        job.spotifyAccount = json.optString("spotifyAccount", "");
        job.youtubeAccount = json.optString("youtubeAccount", "");
        job.phase = json.optString("phase", "new");
        job.removals = json.optBoolean("removals");
        job.maxAdds = json.optInt("maxAdds", 100);
        job.maxRemovals = json.optInt("maxRemovals", 20);
        if (job.maxAdds < 0 || job.maxAdds > 5000 || job.maxRemovals < 0 || job.maxRemovals > 5000)
            throw new IllegalArgumentException("Invalid sync limits");
        job.interval = json.optLong("interval", 0);
        job.links.putAll(map(json.getJSONObject("links")));
        job.excludeSpotify.addAll(strings(json.getJSONArray("excludeSpotify")));
        job.excludeYoutube.addAll(strings(json.getJSONArray("excludeYoutube")));
        job.baseline = baseline(json.optJSONObject("baseline"));
        return job;
    }

    List<Job> jobs() throws Exception {
        List<Job> jobs = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
            if (entry.getKey().startsWith("job."))
                jobs.add(decode(new JSONObject((String) entry.getValue())));
        jobs.sort(Comparator.comparing(job -> job.name));
        return jobs;
    }

    synchronized void save(Job job) throws Exception {
        if (!prefs.edit().putString("job." + job.id, encode(job).toString()).commit())
            throw new IllegalStateException("Could not save sync state");
    }

    synchronized void remove(Job job) {
        prefs.edit().remove("job." + job.id).remove("journal." + job.id).commit();
    }

    private void journal(Job job, String action) throws Exception {
        job.phase = "needs_verification";
        save(job);
        if (!prefs.edit()
                .putString(
                        "journal." + job.id,
                        new JSONObject()
                                .put("action", action)
                                .put("time", System.currentTimeMillis())
                                .toString())
                .commit()) throw new IllegalStateException("Could not save the write journal");
    }

    static final class Candidate {
        final String id;
        final Object song;

        Candidate(String id, Object song) {
            this.id = id;
            this.song = song;
        }
    }

    static final class Unmatched {
        final boolean fromSpotify;
        final String sourceId;
        final PlaylistImport.Track source;
        final List<Candidate> candidates;

        Unmatched(
                boolean fromSpotify,
                String sourceId,
                PlaylistImport.Track source,
                List<Candidate> candidates) {
            this.fromSpotify = fromSpotify;
            this.sourceId = sourceId;
            this.source = source;
            this.candidates = candidates;
        }
    }

    static final class Preview {
        final Job job;
        final SpotifyPlaylistApi.Playlist spotify;
        final YouTubePlaylistApi.Playlist youtube;
        final List<Unmatched> unresolved = new ArrayList<>();
        PlaylistSyncPlan plan;
        Map<String, PlaylistSyncPlan.Snapshot> snapshots;

        Preview(Job job, SpotifyPlaylistApi.Playlist spotify, YouTubePlaylistApi.Playlist youtube) {
            this.job = job;
            this.spotify = spotify;
            this.youtube = youtube;
        }
    }

    interface Progress {
        void report(int complete, int total);
    }

    private static String canonical(String youtube) {
        return "yt:" + youtube;
    }

    static String spotifyId(String input) {
        android.net.Uri uri = android.net.Uri.parse(input.trim());
        if ("spotify".equals(uri.getScheme())) {
            String[] parts = input.split(":");
            if (parts.length == 3 && parts[1].equals("playlist"))
                return SpotifyPlaylistApi.id(parts[2]);
        }
        if ("https".equalsIgnoreCase(uri.getScheme())
                && "open.spotify.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null) {
            List<String> parts = uri.getPathSegments();
            int index = parts.indexOf("playlist");
            if (index >= 0 && index + 1 < parts.size())
                return SpotifyPlaylistApi.id(parts.get(index + 1));
        }
        return SpotifyPlaylistApi.id(input.trim());
    }

    Preview preview(Job job, Progress progress) throws Exception {
        SpotifyPlaylistApi.Playlist first = spotify.read(job.spotifyId);
        YouTubePlaylistApi.Playlist second = youtube.read(job.youtubeId);
        String account = TransitionRuntime.key("spotify", first.account);
        if (!job.spotifyAccount.isEmpty()
                && (!account.equals(job.spotifyAccount)
                        || !second.account.equals(job.youtubeAccount)))
            throw new IllegalStateException("A linked account changed; reconnect this pair");
        job.spotifyAccount = account;
        job.youtubeAccount = second.account;
        Preview preview = new Preview(job, first, second);
        int done = 0, total = first.tracks.size() + second.tracks.size();
        for (SpotifyPlaylistApi.Track track : first.tracks) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            progress.report(++done, total);
            if (job.links.containsKey(track.id) || job.excludeSpotify.contains(track.id)) continue;
            List<Candidate> choices = new ArrayList<>();
            for (YouTubePlaylistApi.Track existing : second.tracks)
                if (!job.excludeYoutube.contains(existing.id))
                    choices.add(new Candidate(existing.id, existing.song));
            Candidate best = automatic(track.metadata, choices);
            if (best == null) {
                choices.clear();
                try {
                    for (Object song : PlaylistImport.search(track.metadata))
                        if (!job.excludeYoutube.contains(MixedSongCodec.text(song, "videoId")))
                            choices.add(new Candidate(MixedSongCodec.text(song, "videoId"), song));
                } catch (Exception unavailable) {
                }
                best = automatic(track.metadata, choices);
            }
            if (best != null && !job.links.containsValue(best.id)) job.links.put(track.id, best.id);
            else
                preview.unresolved.add(
                        new Unmatched(
                                true, track.id, track.metadata, ranked(track.metadata, choices)));
        }
        for (YouTubePlaylistApi.Track track : second.tracks) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            progress.report(++done, total);
            if (job.links.containsValue(track.id) || job.excludeYoutube.contains(track.id))
                continue;
            List<Candidate> choices = new ArrayList<>();
            for (SpotifyPlaylistApi.Track existing : first.tracks)
                if (!job.excludeSpotify.contains(existing.id))
                    choices.add(new Candidate(existing.id, existing.song()));
            Candidate best = automatic(track.metadata, choices);
            if (best == null) {
                choices.clear();
                try {
                    for (SpotifyPlaylistApi.Track candidate : spotify.search(track.metadata))
                        if (!job.excludeSpotify.contains(candidate.id))
                            choices.add(new Candidate(candidate.id, candidate.song()));
                } catch (Exception unavailable) {
                }
                best = automatic(track.metadata, choices);
            }
            if (best != null && !job.links.containsKey(best.id)) job.links.put(best.id, track.id);
            else
                preview.unresolved.add(
                        new Unmatched(
                                false, track.id, track.metadata, ranked(track.metadata, choices)));
        }
        update(preview);
        return preview;
    }

    private static List<Candidate> ranked(PlaylistImport.Track source, List<Candidate> choices) {
        choices.sort(
                (a, b) ->
                        Double.compare(
                                ImportConfidence.score(source, b.song),
                                ImportConfidence.score(source, a.song)));
        return choices.size() > 10 ? new ArrayList<>(choices.subList(0, 10)) : choices;
    }

    private static Candidate automatic(PlaylistImport.Track source, List<Candidate> choices)
            throws Exception {
        if (source.artist.isEmpty()) return null;
        List<Object> songs = new ArrayList<>();
        for (Candidate candidate : choices) songs.add(candidate.song);
        Object best = PlaylistImport.best(source, songs);
        if (best == null || ImportConfidence.score(source, best) < .85) return null;
        String id = MixedSongCodec.text(best, "videoId");
        for (Candidate candidate : choices) if (candidate.id.equals(id)) return candidate;
        return null;
    }

    static void resolve(Preview preview, Unmatched row, Candidate candidate) {
        Map<String, String> links = new LinkedHashMap<>(preview.job.links);
        Set<String> excludedSpotify = new HashSet<>(preview.job.excludeSpotify),
                excludedYoutube = new HashSet<>(preview.job.excludeYoutube);
        List<Unmatched> unresolved = new ArrayList<>(preview.unresolved);
        PlaylistSyncPlan previous = preview.plan;
        try {
            if (candidate == null) {
                if (row.fromSpotify) preview.job.excludeSpotify.add(row.sourceId);
                else preview.job.excludeYoutube.add(row.sourceId);
            } else {
                String spotify = row.fromSpotify ? row.sourceId : candidate.id,
                        youtube = row.fromSpotify ? candidate.id : row.sourceId;
                for (Map.Entry<String, String> link : preview.job.links.entrySet())
                    if (link.getValue().equals(youtube) && !link.getKey().equals(spotify))
                        throw new IllegalArgumentException(
                                "This recording is already paired with another track");
                preview.job.links.put(spotify, youtube);
            }
            preview.unresolved.remove(row);
            update(preview);
        } catch (RuntimeException failure) {
            preview.job.links.clear();
            preview.job.links.putAll(links);
            preview.job.excludeSpotify.clear();
            preview.job.excludeSpotify.addAll(excludedSpotify);
            preview.job.excludeYoutube.clear();
            preview.job.excludeYoutube.addAll(excludedYoutube);
            preview.unresolved.clear();
            preview.unresolved.addAll(unresolved);
            preview.plan = previous;
            throw failure;
        }
    }

    static void update(Preview preview) {
        if (!preview.unresolved.isEmpty()) {
            preview.plan = null;
            return;
        }
        Map<String, PlaylistSyncPlan.Snapshot> snapshots =
                snapshots(preview.job, preview.spotify, preview.youtube);
        preview.snapshots = snapshots;
        preview.plan =
                PlaylistSyncPlan.plan(
                        preview.job.baseline,
                        snapshots,
                        preview.job.removals,
                        preview.job.maxAdds,
                        preview.job.removals ? preview.job.maxRemovals : 0);
    }

    private static Map<String, PlaylistSyncPlan.Snapshot> snapshots(
            Job job, SpotifyPlaylistApi.Playlist spotify, YouTubePlaylistApi.Playlist youtube) {
        Map<String, String> first = new LinkedHashMap<>(), second = new LinkedHashMap<>();
        for (SpotifyPlaylistApi.Track track : spotify.tracks) {
            String match = job.links.get(track.id);
            if (match == null) {
                if (!job.excludeSpotify.contains(track.id))
                    throw new IllegalStateException("A track match is unresolved");
                continue;
            }
            if (first.put(canonical(match), track.occurrence) != null)
                throw new IllegalStateException(
                        "Repeated recordings need review before syncing this pair");
        }
        Set<String> linked = new HashSet<>(job.links.values());
        for (YouTubePlaylistApi.Track track : youtube.tracks) {
            if (!linked.contains(track.id)) {
                if (!job.excludeYoutube.contains(track.id))
                    throw new IllegalStateException("A track match is unresolved");
                continue;
            }
            if (second.put(canonical(track.id), track.occurrence) != null)
                throw new IllegalStateException(
                        "Repeated recordings need review before syncing this pair");
        }
        Map<String, PlaylistSyncPlan.Snapshot> peers = new LinkedHashMap<>();
        peers.put(
                "spotify",
                new PlaylistSyncPlan.Snapshot(
                        TransitionRuntime.key("spotify", spotify.account),
                        spotify.revision,
                        first,
                        true));
        peers.put(
                "youtube",
                new PlaylistSyncPlan.Snapshot(youtube.account, youtube.revision, second, true));
        return peers;
    }

    synchronized String apply(Preview preview) throws Exception {
        if (preview.plan == null || !preview.unresolved.isEmpty())
            throw new IllegalStateException("Review every track match first");
        Job job = preview.job;
        PlaylistSyncPlan plan = preview.plan;
        if (!TransitionRuntime.key("spotify", spotify.account()).equals(job.spotifyAccount)
                || !youtube.account().equals(job.youtubeAccount))
            throw new IllegalStateException("A linked account changed");
        spotify.assertRevision(job.spotifyId, preview.spotify.revision);
        youtube.assertUnchanged(preview.youtube);
        if (plan.awaitingConfirmation) {
            job.baseline = plan.observedBaseline(job.baseline);
            job.phase = "confirming_removals";
            save(job);
            return "confirming_removals";
        }
        Map<String, String> inverse = new HashMap<>();
        for (Map.Entry<String, String> link : job.links.entrySet())
            inverse.put(link.getValue(), link.getKey());
        PlaylistSyncPlan.Change spotChange = plan.changes.get("spotify"),
                ytChange = plan.changes.get("youtube");
        List<String> spotAdd = new ArrayList<>(),
                ytAdd = new ArrayList<>(),
                spotRemove = new ArrayList<>();
        for (String key : spotChange.add) {
            String id = inverse.get(key.substring(3));
            if (id == null) throw new IllegalStateException("A Spotify match is missing");
            spotAdd.add(id);
        }
        for (String key : ytChange.add) ytAdd.add(key.substring(3));
        for (String key : spotChange.remove) {
            String id = inverse.get(key.substring(3));
            if (id == null)
                throw new IllegalStateException("A Spotify removal identity is missing");
            spotRemove.add(id);
        }
        try {
            if (!spotAdd.isEmpty()) {
                journal(job, "spotify.add");
                spotify.add(job.spotifyId, spotAdd, preview.spotify.revision);
            }
            if (!ytAdd.isEmpty()) {
                journal(job, "youtube.add");
                youtube.add(preview.youtube, ytAdd);
            }
            SpotifyPlaylistApi.Playlist currentSpot = spotify.read(job.spotifyId);
            YouTubePlaylistApi.Playlist currentYt = youtube.read(job.youtubeId);
            Set<String> presentSpot = new HashSet<>(), presentYt = new HashSet<>();
            for (SpotifyPlaylistApi.Track track : currentSpot.tracks) presentSpot.add(track.id);
            for (YouTubePlaylistApi.Track track : currentYt.tracks) presentYt.add(track.id);
            if (!presentSpot.containsAll(spotAdd) || !presentYt.containsAll(ytAdd))
                throw new IllegalStateException("Additions could not be verified");
            if (!spotRemove.isEmpty()) {
                journal(job, "spotify.remove");
                spotify.remove(job.spotifyId, spotRemove, currentSpot.revision);
            }
            if (!ytChange.remove.isEmpty()) {
                List<YouTubePlaylistApi.Track> remove = new ArrayList<>();
                for (YouTubePlaylistApi.Track track : currentYt.tracks)
                    if (ytChange.remove.contains(canonical(track.id))) remove.add(track);
                if (remove.size() != ytChange.remove.size())
                    throw new IllegalStateException("YouTube removal identities changed");
                journal(job, "youtube.remove");
                youtube.remove(currentYt, remove);
            }
            currentSpot = spotify.read(job.spotifyId);
            currentYt = youtube.read(job.youtubeId);
            job.baseline = plan.verified(snapshots(job, currentSpot, currentYt));
            job.phase = "synced";
            save(job);
            prefs.edit().remove("journal." + job.id).commit();
            return "synced";
        } catch (Exception failed) {
            job.phase = "needs_verification";
            save(job);
            throw failed;
        }
    }
}
