package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

public final class ProvidersTest {
    static final String KEY = "fixture-api-key-not-a-real-account";
    static final String MAGNET = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789";
    static final String SOURCE = "https://files.example.com/song.flac";
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    interface Action {
        void run() throws Exception;
    }

    static void fails(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            check(!expected.getMessage().contains(KEY), message + ": key redacted");
        }
    }

    static JSONObject object(String text) throws Exception {
        return new JSONObject(text);
    }

    static JSONObject envelope(JSONObject data) throws Exception {
        return new JSONObject().put("status", "success").put("data", data);
    }

    static DebridModels.Cancellation cancellation() {
        return new DebridModels.Cancellation() {
            public void check() throws IOException {}

            public void bind(Closeable resource) throws IOException {}

            public void clear(Closeable resource) {}
        };
    }

    static final DebridModels.Cancellation C = cancellation();

    static final class Call {
        final DebridProvider.Kind kind;
        final String method, path;
        final Map<String, String> form;
        final JSONObject response;

        Call(
                DebridProvider.Kind kind,
                String method,
                String path,
                Map<String, String> form,
                JSONObject response) {
            this.kind = kind;
            this.method = method;
            this.path = path;
            this.form = form;
            this.response = response;
        }
    }

    static final class Fake extends DebridHttp {
        final Queue<Call> calls = new ArrayDeque<>();

        Fake add(
                DebridProvider.Kind kind,
                String method,
                String path,
                Map<String, String> form,
                JSONObject response) {
            calls.add(new Call(kind, method, path, form, response));
            return this;
        }

        @Override
        public JSONObject json(
                DebridProvider.Kind kind,
                String method,
                String path,
                String key,
                Map<String, String> form,
                DebridModels.Cancellation c)
                throws IOException {
            c.check();
            Call call = calls.poll();
            check(call != null, "No unexpected API request");
            check(
                    call.kind == kind && call.method.equals(method) && call.path.equals(path),
                    "Official API route/version/method");
            check(call.form.equals(form), "Exact bounded provider parameters");
            check(
                    KEY.equals(key)
                            && !path.contains(KEY)
                            && !form.containsKey("apikey")
                            && !form.containsKey("auth_token"),
                    "Key is passed only for the Bearer transport header");
            return call.response;
        }

        void done() {
            check(calls.isEmpty(), "Every expected request was consumed");
        }
    }

    static final DebridProvider.Kind RD = DebridProvider.Kind.REAL_DEBRID,
            AD = DebridProvider.Kind.ALLDEBRID;

    static JSONObject rdInfo(String state, boolean selected, boolean videoSelected)
            throws Exception {
        return object(
                "{\"id\":\"abc123\",\"status\":\""
                        + state
                        + "\",\"files\":["
                        + "{\"id\":1,\"path\":\"/cover.mkv\",\"bytes\":1000,\"selected\":"
                        + (videoSelected ? 1 : 0)
                        + "},"
                        + "{\"id\":2,\"path\":\"/other.mp3\",\"bytes\":200,\"selected\":0},"
                        + "{\"id\":3,\"path\":\"/Album/Song.flac\",\"bytes\":100,\"selected\":"
                        + (selected ? 1 : 0)
                        + "}],"
                        + "\"links\":"
                        + (videoSelected
                                ? "[\"https://host.example/video\",\"https://host.example/audio\"]"
                                : selected ? "[\"https://host.example/audio\"]" : "[]")
                        + "}");
    }

    static JSONObject rdUnlocked(String name, long size) throws Exception {
        return new JSONObject()
                .put("filename", name)
                .put("filesize", size)
                .put("download", "https://cdn.example.com/final.flac?signature=opaque");
    }

    static DebridModels.Resolved rdPrepared() throws Exception {
        return new DebridModels.Resolved(
                RD,
                "abc123",
                MAGNET,
                Arrays.asList(
                        new DebridModels.Entry("2", "/other.mp3", 200),
                        new DebridModels.Entry("3", "/Album/Song.flac", 100)),
                "waiting_files_selection",
                "Choose files",
                false);
    }

    static void realDebrid() throws Exception {
        RealDebridProvider provider = new RealDebridProvider();
        Fake validate =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/user",
                                params(),
                                object("{\"id\":4,\"type\":\"premium\"}"));
        provider.validate(KEY, validate, C);
        validate.done();
        Fake prepare =
                new Fake()
                        .add(
                                RD,
                                "POST",
                                "/rest/1.0/torrents/addMagnet",
                                params("magnet", MAGNET),
                                object("{\"id\":\"abc123\"}"))
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("waiting_files_selection", false, false));
        DebridModels.Resolved resolved = provider.resolve(MAGNET, KEY, prepare, C);
        prepare.done();
        check(
                !resolved.ready && resolved.entries.size() == 2,
                "RD exposes selectable audio metadata without claiming ready or selecting all");
        check(
                resolved.entry("3").name.equals("/Album/Song.flac"),
                "RD file identity includes original path");
        Fake select =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("waiting_files_selection", false, false))
                        .add(
                                RD,
                                "POST",
                                "/rest/1.0/torrents/selectFiles/abc123",
                                params("files", "3"),
                                new JSONObject())
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("queued", true, false));
        resolved = provider.select(resolved, Collections.singletonList("3"), KEY, select, C);
        select.done();
        check(
                !resolved.ready && resolved.state.equals("queued"),
                "Uncached selected torrent remains preparing");
        Fake refresh =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("downloaded", true, false));
        resolved = provider.refresh(resolved, KEY, refresh, C);
        refresh.done();
        check(resolved.ready, "Refresh polls same transfer without recreating it");
        Fake reselect =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("downloaded", true, false));
        provider.select(resolved, Collections.singletonList("3"), KEY, reselect, C);
        reselect.done();
        Fake download =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("downloaded", true, true))
                        .add(
                                RD,
                                "POST",
                                "/rest/1.0/unrestrict/link",
                                params("link", "https://host.example/audio"),
                                rdUnlocked("Song.flac", 100));
        DebridModels.Download file = provider.download(resolved, "3", KEY, download, C);
        download.done();
        check(
                file.name.equals("/Album/Song.flac") && file.size == 100,
                "RD maps the requested file through selected-only link order, skipping global"
                        + " video/unselected indices");
        final DebridModels.Resolved pinned = resolved;
        Fake wrong =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("downloaded", true, false))
                        .add(
                                RD,
                                "POST",
                                "/rest/1.0/unrestrict/link",
                                params("link", "https://host.example/audio"),
                                rdUnlocked("Wrong.flac", 100));
        fails(
                () -> provider.download(pinned, "3", KEY, wrong, C),
                "Wrong unrestrict file is rejected");
        wrong.done();
        JSONObject split = rdInfo("downloaded", true, false);
        split.put("links", new JSONArray());
        Fake splitLinks =
                new Fake().add(RD, "GET", "/rest/1.0/torrents/info/abc123", params(), split);
        fails(
                () -> provider.download(pinned, "3", KEY, splitLinks, C),
                "Ambiguous selected links are rejected");
        splitLinks.done();
        Fake changed =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("downloaded", true, false)
                                        .put(
                                                "files",
                                                new JSONArray()
                                                        .put(
                                                                object(
                                                                        "{\"id\":3,\"path\":\"/Album/Song.flac\",\"bytes\":101,\"selected\":1}"))));
        fails(
                () -> provider.download(pinned, "3", KEY, changed, C),
                "Changed selected size is rejected before resolving a link");
        changed.done();
        Fake otherSelection =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("downloaded", true, false));
        fails(
                () ->
                        provider.select(
                                pinned, Collections.singletonList("2"), KEY, otherSelection, C),
                "Already started selection cannot be silently changed");
        otherSelection.done();
        JSONObject noMetadata = object("{\"id\":\"abc123\",\"status\":\"magnet_conversion\"}");
        Fake pending =
                new Fake().add(RD, "GET", "/rest/1.0/torrents/info/abc123", params(), noMetadata);
        check(
                provider.refresh(pinned, KEY, pending, C).entries.isEmpty(),
                "Missing metadata during conversion remains pending");
        pending.done();
        Fake hoster =
                new Fake()
                        .add(
                                RD,
                                "POST",
                                "/rest/1.0/unrestrict/link",
                                params("link", SOURCE),
                                rdUnlocked("song.flac", 0));
        DebridModels.Resolved direct = provider.resolve(SOURCE, KEY, hoster, C);
        hoster.done();
        check(
                provider.download(direct, "0", KEY, new Fake(), C).size == -1,
                "Unknown direct size is left bounded to downloader rather than invented");
        check(
                !direct.remoteId.contains("https://") && !direct.remoteId.contains("signature"),
                "Signed link stays out of public provider identity");
        Fake rawError =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/user",
                                params(),
                                new JSONObject().put("error", KEY).put("error_code", 8));
        fails(
                () -> provider.validate(KEY, rawError, C),
                "RD error bodies never expose credentials");
        rawError.done();
    }

    static JSONObject adStatus(int code) throws Exception {
        return envelope(
                new JSONObject()
                        .put(
                                "magnets",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("id", 123)
                                                        .put("statusCode", code))));
    }

    static JSONObject adFiles(long songSize) throws Exception {
        JSONObject audio =
                new JSONObject()
                        .put("n", "Song.flac")
                        .put("s", songSize)
                        .put("l", "https://alldebrid.com/f/audio");
        JSONObject folder = new JSONObject().put("n", "Album").put("e", new JSONArray().put(audio));
        JSONArray files =
                new JSONArray()
                        .put(
                                object(
                                        "{\"n\":\"cover.mkv\",\"s\":50,\"l\":\"https://alldebrid.com/f/video\"}"))
                        .put(folder);
        return envelope(
                new JSONObject()
                        .put(
                                "magnets",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("id", "123")
                                                        .put("files", files))));
    }

    static JSONObject adUnlocked(String name, long size) throws Exception {
        return envelope(
                new JSONObject()
                        .put("filename", name)
                        .put("filesize", size)
                        .put("link", "https://cdn.example.com/adfile?signature=opaque"));
    }

    static void allDebrid() throws Exception {
        AllDebridProvider provider = new AllDebridProvider();
        Fake user =
                new Fake()
                        .add(
                                AD,
                                "GET",
                                "/v4/user",
                                params(),
                                envelope(
                                        object(
                                                "{\"user\":{\"isPremium\":false,\"isTrial\":true}}")));
        provider.validate(KEY, user, C);
        user.done();
        Fake prepare =
                new Fake()
                        .add(
                                AD,
                                "POST",
                                "/v4/magnet/upload",
                                params("magnets[]", MAGNET),
                                envelope(object("{\"magnets\":[{\"id\":123,\"ready\":false}]}")))
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(1));
        DebridModels.Resolved resolved = provider.resolve(MAGNET, KEY, prepare, C);
        prepare.done();
        check(!resolved.ready && resolved.entries.isEmpty(), "AD uncached magnet remains pending");
        Fake ready =
                new Fake()
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(4))
                        .add(AD, "POST", "/v4/magnet/files", params("id[]", "123"), adFiles(100));
        resolved = provider.refresh(resolved, KEY, ready, C);
        ready.done();
        check(
                resolved.ready
                        && resolved.entries.size() == 1
                        && resolved.entries.get(0).name.equals("Album/Song.flac"),
                "AD recursively lists audio and ignores video");
        String fileId = resolved.entries.get(0).fileId;
        Fake download =
                new Fake()
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(4))
                        .add(AD, "POST", "/v4/magnet/files", params("id[]", "123"), adFiles(100))
                        .add(
                                AD,
                                "POST",
                                "/v4/link/unlock",
                                params("link", "https://alldebrid.com/f/audio"),
                                adUnlocked("Song.flac", 100));
        DebridModels.Download item = provider.download(resolved, fileId, KEY, download, C);
        download.done();
        check(
                item.name.equals("Album/Song.flac") && item.size == 100,
                "AD preserves chosen path and byte length");
        final DebridModels.Resolved pinned = resolved;
        Fake changed =
                new Fake()
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(4))
                        .add(AD, "POST", "/v4/magnet/files", params("id[]", "123"), adFiles(101));
        fails(
                () -> provider.download(pinned, fileId, KEY, changed, C),
                "AD changed content identity is never substituted");
        changed.done();
        Fake wrong =
                new Fake()
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(4))
                        .add(AD, "POST", "/v4/magnet/files", params("id[]", "123"), adFiles(100))
                        .add(
                                AD,
                                "POST",
                                "/v4/link/unlock",
                                params("link", "https://alldebrid.com/f/audio"),
                                adUnlocked("wrong.flac", 100));
        fails(
                () -> provider.download(pinned, fileId, KEY, wrong, C),
                "AD wrong resolved file is rejected");
        wrong.done();
        Fake delayed =
                new Fake()
                        .add(
                                AD,
                                "POST",
                                "/v4/link/unlock",
                                params("link", SOURCE),
                                envelope(
                                        object(
                                                "{\"filename\":\"song.flac\",\"filesize\":100,\"delayed\":42}")));
        DebridModels.Resolved wait = provider.resolve(SOURCE, KEY, delayed, C);
        delayed.done();
        check(!wait.ready, "AD delayed hoster link remains pending");
        Fake still =
                new Fake()
                        .add(
                                AD,
                                "POST",
                                "/v4/link/delayed",
                                params("id", "42"),
                                envelope(object("{\"status\":1,\"time_left\":45}")));
        wait = provider.refresh(wait, KEY, still, C);
        still.done();
        check(
                wait.remoteId.equals("delayed:42") && !wait.ready,
                "AD refresh retains delayed identity");
        Fake available =
                new Fake()
                        .add(
                                AD,
                                "POST",
                                "/v4/link/delayed",
                                params("id", "42"),
                                envelope(
                                        object(
                                                "{\"status\":2,\"link\":\"https://cdn.example.com/delayed\"}")));
        wait = provider.refresh(wait, KEY, available, C);
        available.done();
        check(
                wait.ready
                        && provider.download(wait, "0", KEY, new Fake(), C)
                                .name
                                .equals("song.flac"),
                "AD ready delayed original can be downloaded without recreating it");
        Fake rawError =
                new Fake()
                        .add(
                                AD,
                                "GET",
                                "/v4/user",
                                params(),
                                object(
                                        "{\"status\":\"error\",\"error\":{\"code\":\"AUTH_BAD_APIKEY\",\"message\":\""
                                                + KEY
                                                + "\"}}"));
        fails(
                () -> provider.validate(KEY, rawError, C),
                "AD credentials echoed in error bodies are redacted");
        rawError.done();
        Fake gone =
                new Fake()
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(15));
        fails(
                () -> provider.refresh(pinned, KEY, gone, C),
                "AD no-peer/deleted/error state fails clearly");
        gone.done();
    }

    static void boundaries() throws Exception {
        RealDebridProvider rd = new RealDebridProvider();
        DebridModels.Resolved expected = rdPrepared();
        Fake unconfirmed =
                new Fake()
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("waiting_files_selection", false, false))
                        .add(
                                RD,
                                "POST",
                                "/rest/1.0/torrents/selectFiles/abc123",
                                params("files", "3"),
                                new JSONObject())
                        .add(
                                RD,
                                "GET",
                                "/rest/1.0/torrents/info/abc123",
                                params(),
                                rdInfo("downloaded", false, true));
        fails(
                () -> rd.select(expected, Collections.singletonList("3"), KEY, unconfirmed, C),
                "A completed/202 selection still requires the exact chosen bitset");
        unconfirmed.done();
        Fake badUrl =
                new Fake()
                        .add(
                                RD,
                                "POST",
                                "/rest/1.0/unrestrict/link",
                                params("link", SOURCE),
                                rdUnlocked("song.flac", 100)
                                        .put("download", "http://cdn.example.com/plaintext"));
        fails(() -> rd.resolve(SOURCE, KEY, badUrl, C), "Plaintext provider downloads fail closed");
        badUrl.done();
        AllDebridProvider ad = new AllDebridProvider();
        DebridModels.Resolved magnet =
                new DebridModels.Resolved(
                        AD, "123", MAGNET, Collections.emptyList(), "preparing", "Waiting", false);
        JSONObject incorrect =
                envelope(
                        new JSONObject()
                                .put(
                                        "magnets",
                                        new JSONArray()
                                                .put(
                                                        new JSONObject()
                                                                .put("id", 456)
                                                                .put("statusCode", 4))));
        Fake wrongJob =
                new Fake().add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), incorrect);
        fails(() -> ad.refresh(magnet, KEY, wrongJob, C), "Another provider job is never accepted");
        wrongJob.done();
        JSONArray nodes =
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("n", "Song.flac")
                                        .put("s", 100)
                                        .put("l", "https://alldebrid.com/f/audio"));
        for (int i = 0; i < 18; i++)
            nodes = new JSONArray().put(new JSONObject().put("n", "Folder" + i).put("e", nodes));
        JSONObject deep =
                envelope(
                        new JSONObject()
                                .put(
                                        "magnets",
                                        new JSONArray()
                                                .put(
                                                        new JSONObject()
                                                                .put("id", 123)
                                                                .put("files", nodes))));
        Fake depth =
                new Fake()
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(4))
                        .add(AD, "POST", "/v4/magnet/files", params("id[]", "123"), deep);
        fails(
                () -> ad.refresh(magnet, KEY, depth, C),
                "Untrusted provider file tree depth is bounded");
        depth.done();
        JSONObject duplicate = adFiles(100);
        JSONArray children =
                duplicate
                        .getJSONObject("data")
                        .getJSONArray("magnets")
                        .getJSONObject(0)
                        .getJSONArray("files")
                        .getJSONObject(1)
                        .getJSONArray("e");
        children.put(
                new JSONObject()
                        .put("n", "Song.flac")
                        .put("s", 100)
                        .put("l", "https://alldebrid.com/f/other"));
        Fake duplicates =
                new Fake()
                        .add(AD, "POST", "/v4.1/magnet/status", params("id", "123"), adStatus(4))
                        .add(AD, "POST", "/v4/magnet/files", params("id[]", "123"), duplicate);
        fails(
                () -> ad.refresh(magnet, KEY, duplicates, C),
                "Duplicate provider paths cannot create ambiguous identities");
        duplicates.done();
        DebridModels.Cancellation stopped =
                new DebridModels.Cancellation() {
                    public void check() throws IOException {
                        throw new java.io.InterruptedIOException("Cancelled");
                    }

                    public void bind(Closeable resource) {}

                    public void clear(Closeable resource) {}
                };
        fails(
                () -> rd.resolve(SOURCE, KEY, new Fake(), stopped),
                "Cancelled preparation makes no API request");
        fails(
                () -> ad.resolve(SOURCE, KEY, new Fake(), stopped),
                "Cancelled AllDebrid preparation makes no API request");
    }

    static Map<String, String> params(String... pairs) {
        return DebridHttp.params(pairs);
    }

    public static void main(String[] args) throws Exception {
        realDebrid();
        allDebrid();
        boundaries();
        System.out.println(
                "PASS: "
                        + checks
                        + " Real-Debrid/AllDebrid route, selection, identity and error checks.");
    }
}
