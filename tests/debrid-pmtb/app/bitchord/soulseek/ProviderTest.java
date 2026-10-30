package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;

public final class ProviderTest {
    static int checks;
    static final String KEY = "test-api-key-only";
    static final String MAGNET = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789";

    static final class Cancel implements DebridModels.Cancellation {
        boolean stopped;

        public void check() throws IOException {
            if (stopped) throw new IOException("cancelled");
        }

        public void bind(Closeable resource) throws IOException {
            check();
        }

        public void clear(Closeable resource) {}
    }

    interface Verify {
        void run(Map<String, String> params) throws Exception;
    }

    static final class Step {
        final String method, path, result;
        final boolean multipart;
        final Verify verify;

        Step(String method, String path, String result, boolean multipart, Verify verify) {
            this.method = method;
            this.path = path;
            this.result = result;
            this.multipart = multipart;
            this.verify = verify;
        }
    }

    static final class Http extends DebridHttp {
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        int calls;

        Http add(String method, String path, String response, Verify verify) {
            steps.add(new Step(method, path, response, false, verify));
            return this;
        }

        Http multi(String path, String response, Verify verify) {
            steps.add(new Step("POST", path, response, true, verify));
            return this;
        }

        @Override
        public JSONObject json(
                DebridProvider.Kind kind,
                String method,
                String path,
                String key,
                Map<String, String> params,
                DebridModels.Cancellation cancel)
                throws IOException {
            return next(method, path, key, params, false, cancel);
        }

        @Override
        public JSONObject jsonMultipart(
                DebridProvider.Kind kind,
                String path,
                String key,
                Map<String, String> params,
                DebridModels.Cancellation cancel)
                throws IOException {
            return next("POST", path, key, params, true, cancel);
        }

        JSONObject next(
                String method,
                String path,
                String key,
                Map<String, String> params,
                boolean multipart,
                DebridModels.Cancellation cancel)
                throws IOException {
            cancel.check();
            calls++;
            Step step = steps.poll();
            if (step == null)
                throw new AssertionError("Unexpected provider request " + method + " " + path);
            check(
                    step.method.equals(method)
                            && step.path.equals(path)
                            && step.multipart == multipart,
                    "Exact official endpoint/method");
            check(KEY.equals(key), "API key passed only to HTTP API boundary");
            try {
                if (step.verify != null) step.verify.run(params);
                return new JSONObject(step.result);
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        void done() {
            check(steps.isEmpty(), "Expected request sequence consumed without extra creation");
        }
    }

    interface Action {
        void run() throws Exception;
    }

    static void check(boolean value, String what) {
        checks++;
        if (!value) throw new AssertionError(what);
    }

    static void fail(Action action, String text) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected failure: " + text);
        } catch (IOException expected) {
            check(!expected.getMessage().contains(KEY), "Errors redact API key");
        }
    }

    static String direct(String path, long size, String link) {
        return "{\"status\":\"success\",\"content\":[{\"path\":\""
                + path
                + "\",\"size\":"
                + size
                + ",\"link\":\""
                + link
                + "\"}]}";
    }

    static String pm(String fields) {
        return "{\"status\":\"success\"," + fields + "}";
    }

    static String tb(String data) {
        return "{\"success\":true,\"error\":null,\"data\":" + data + "}";
    }

    static String tbItem(String id, boolean present, boolean finished, String files) {
        return "{\"id\":"
                + id
                + ",\"download_state\":\"completed\",\"download_present\":"
                + present
                + ",\"download_finished\":"
                + finished
                + ",\"files\":"
                + files
                + "}";
    }

    static String file(String id, String name, long size) {
        return "{\"id\":"
                + id
                + ",\"name\":\""
                + name
                + "\",\"size\":"
                + size
                + ",\"zipped\":false,\"infected\":false}";
    }

    static void premiumize() throws Exception {
        PremiumizeProvider provider = new PremiumizeProvider();
        Cancel cancel = new Cancel();
        Http http =
                new Http()
                        .add(
                                "GET",
                                "/api/account/info",
                                pm("\"customer_id\":\"customer1\",\"premium_until\":null"),
                                p -> check(p.isEmpty(), "No key in account parameters"));
        provider.validate(KEY, http, cancel);
        http.done();
        String input = "https://host.example/album";
        String response =
                "{\"status\":\"success\",\"content\":[{\"path\":\"Album/Song.flac\",\"size\":12,\"link\":\"https://cdn.example/song?signature=one\"},{\"path\":\"Album/cover.jpg\",\"size\":8,\"link\":\"https://cdn.example/cover\"}]}";
        http =
                new Http()
                        .add(
                                "POST",
                                "/api/transfer/directdl",
                                response,
                                p ->
                                        check(
                                                input.equals(p.get("src"))
                                                        && !p.containsKey("apikey"),
                                                "Direct source with Bearer boundary"));
        DebridModels.Resolved ready = provider.resolve(input, KEY, http, cancel);
        http.done();
        check(ready.ready && ready.entries.size() == 1, "Original audio candidates only");
        String fileId = ready.entries.get(0).fileId;
        check(
                !fileId.contains("https") && !fileId.contains("signature"),
                "Signed URL is not stored as file identity");
        http =
                new Http()
                        .add(
                                "POST",
                                "/api/transfer/directdl",
                                direct(
                                        "Album/Song.flac",
                                        12,
                                        "https://cdn.example/song?signature=two"),
                                null);
        DebridModels.Download download = provider.download(ready, fileId, KEY, http, cancel);
        http.done();
        check(
                download.size == 12 && download.url.endsWith("two"),
                "Fresh direct link retains exact file binding");
        Http changed =
                new Http()
                        .add(
                                "POST",
                                "/api/transfer/directdl",
                                direct("Album/Song.flac", 13, "https://cdn.example/changed"),
                                null);
        fail(() -> provider.download(ready, fileId, KEY, changed, cancel), "changed size");
        changed.done();
        Http wrong = new Http();
        fail(() -> provider.download(ready, "other", KEY, wrong, cancel), "unselected file");
        check(wrong.calls == 0, "No API call for unknown file");

        http =
                new Http()
                        .add(
                                "POST",
                                "/api/transfer/create",
                                pm("\"id\":\"transfer_1\""),
                                p ->
                                        check(
                                                MAGNET.equals(p.get("src")),
                                                "Magnet cloud transfer requested explicitly"));
        DebridModels.Resolved pending = provider.resolve(MAGNET, KEY, http, cancel);
        http.done();
        check(
                !pending.ready && pending.remoteId.equals("transfer:transfer_1"),
                "Cloud job identity retained for refresh");
        http =
                new Http()
                        .add(
                                "GET",
                                "/api/transfer/list",
                                pm(
                                        "\"transfers\":[{\"id\":\"transfer_1\",\"status\":\"running\"}]"),
                                null);
        DebridModels.Resolved waiting = provider.refresh(pending, KEY, http, cancel);
        http.done();
        check(!waiting.ready, "Uncached transfer remains pending");
        http =
                new Http()
                        .add(
                                "GET",
                                "/api/transfer/list",
                                pm(
                                        "\"transfers\":[{\"id\":\"other\",\"status\":\"finished\"},{\"id\":\"transfer_1\",\"status\":\"finished\",\"folder_id\":\"folder_1\",\"file_id\":null}]"),
                                null)
                        .add(
                                "GET",
                                "/api/folder/list",
                                pm(
                                        "\"folder_id\":\"folder_1\",\"content\":[{\"id\":\"child_1\",\"name\":\"Disc1\",\"type\":\"folder\"},{\"id\":\"cover\",\"name\":\"cover.jpg\",\"type\":\"file\",\"size\":1}]"),
                                p ->
                                        check(
                                                "folder_1".equals(p.get("id")),
                                                "Exact transfer folder selected"))
                        .add(
                                "GET",
                                "/api/folder/list",
                                pm(
                                        "\"folder_id\":\"child_1\",\"content\":[{\"id\":\"audio_1\",\"name\":\"Song.flac\",\"type\":\"file\",\"size\":12}]"),
                                p ->
                                        check(
                                                "child_1".equals(p.get("id")),
                                                "Bounded nested folder read"));
        DebridModels.Resolved cloud = provider.refresh(waiting, KEY, http, cancel);
        http.done();
        check(
                cloud.ready && cloud.entries.get(0).name.equals("Disc1/Song.flac"),
                "Nested original audio found");
        http =
                new Http()
                        .add(
                                "GET",
                                "/api/item/details",
                                pm(
                                        "\"id\":\"audio_1\",\"name\":\"Song.flac\",\"size\":12,\"link\":\"https://cdn.example/item\""),
                                p ->
                                        check(
                                                "audio_1".equals(p.get("id")),
                                                "Exact cloud item selected"));
        download = provider.download(cloud, "item:audio_1", KEY, http, cancel);
        http.done();
        check(
                download.name.equals("Disc1/Song.flac"),
                "Selection path preserved without depending on array order");
        Http wrongId =
                new Http()
                        .add(
                                "GET",
                                "/api/item/details",
                                pm(
                                        "\"id\":\"different\",\"name\":\"Song.flac\",\"size\":12,\"link\":\"https://cdn.example/wrong\""),
                                null);
        fail(
                () -> provider.download(cloud, "item:audio_1", KEY, wrongId, cancel),
                "wrong object identity");
        wrongId.done();
        http =
                new Http()
                        .add(
                                "POST",
                                "/api/transfer/directdl",
                                "{\"status\":\"error\",\"code\":\"service_unsupported\"}",
                                null)
                        .add("POST", "/api/transfer/create", pm("\"id\":\"queued\""), null);
        check(
                !provider.resolve(input, KEY, http, cancel).ready,
                "Queue-only provider sources create one cloud job");
        http.done();
        Http rejected =
                new Http()
                        .add(
                                "POST",
                                "/api/transfer/directdl",
                                "{\"status\":\"error\",\"code\":\"authentication_failed\",\"message\":\""
                                        + KEY
                                        + "\"}",
                                null);
        fail(() -> provider.resolve(input, KEY, rejected, cancel), "invalid key");
        rejected.done();
        Http malformed =
                new Http()
                        .add(
                                "POST",
                                "/api/transfer/directdl",
                                pm(
                                        "\"content\":[{\"path\":\"Song.flac\",\"size\":\"12\",\"link\":\"https://cdn.example/x\"}]"),
                                null);
        fail(() -> provider.resolve(input, KEY, malformed, cancel), "malformed file size");
        malformed.done();
        Http empty = new Http();
        fail(
                () -> provider.resolve("http://plain.example/file", KEY, empty, cancel),
                "HTTPS required");
        check(empty.calls == 0, "Bad source never reaches API");
        Cancel stopped = new Cancel();
        stopped.stopped = true;
        fail(() -> provider.resolve(MAGNET, KEY, new Http(), stopped), "cancelled resolve");
    }

    static void torbox() throws Exception {
        TorboxProvider provider = new TorboxProvider();
        Cancel cancel = new Cancel();
        Http http =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/user/me",
                                tb("{\"id\":5,\"plan\":0}"),
                                p ->
                                        check(
                                                "false".equals(p.get("settings")),
                                                "No unnecessary user settings requested"));
        provider.validate(KEY, http, cancel);
        http.done();
        http =
                new Http()
                        .multi(
                                "/v1/api/torrents/createtorrent",
                                tb("{\"torrent_id\":42}"),
                                p ->
                                        check(
                                                MAGNET.equals(p.get("magnet"))
                                                        && "false".equals(p.get("allow_zip")),
                                                "Multipart magnet and original-file preference"));
        DebridModels.Resolved pending = provider.resolve(MAGNET, KEY, http, cancel);
        http.done();
        check(
                !pending.ready && pending.remoteId.equals("torrent:42"),
                "Exact TorBox job ID retained");
        http =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(tbItem("42", false, true, "[]")),
                                p ->
                                        check(
                                                "42".equals(p.get("id")),
                                                "Refresh exact job without new creation"));
        check(
                !provider.refresh(pending, KEY, http, cancel).ready,
                "Completed status alone is not readiness");
        http.done();
        String items =
                "["
                        + file("9", "Other.flac", 12)
                        + ","
                        + file("7", "Album/Song.flac", 12)
                        + ","
                        + file("8", "movie.mkv", 15)
                        + "]";
        http =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(
                                        "["
                                                + tbItem("999", true, true, "[]")
                                                + ","
                                                + tbItem("42", true, true, items)
                                                + "]"),
                                null);
        DebridModels.Resolved ready = provider.refresh(pending, KEY, http, cancel);
        http.done();
        check(
                ready.ready && ready.entries.size() == 2,
                "Ready audio list independent of array order");
        http =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(tbItem("42", true, true, items)),
                                null)
                        .add(
                                "GET",
                                "/v1/api/torrents/requestdl",
                                tb("\"https://cdn.example/song?signature=short\""),
                                p -> {
                                    check(
                                            "42".equals(p.get("torrent_id"))
                                                    && "7".equals(p.get("file_id")),
                                            "Exact job/file identifiers used, never cached-list"
                                                    + " offsets");
                                    check(
                                            KEY.equals(p.get("token"))
                                                    && "false".equals(p.get("redirect"))
                                                    && "false".equals(p.get("zip_link")),
                                            "Official API token exception never enables redirect or"
                                                    + " ZIP");
                                });
        DebridModels.Download download = provider.download(ready, "7", KEY, http, cancel);
        http.done();
        check(
                download.name.equals("Album/Song.flac") && !download.url.contains(KEY),
                "CDN original URL separated from account key");
        Http changed =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(
                                        tbItem(
                                                "42",
                                                true,
                                                true,
                                                "[" + file("7", "Album/Song.flac", 13) + "]")),
                                null);
        fail(
                () -> provider.download(ready, "7", KEY, changed, cancel),
                "changed selected metadata");
        changed.done();
        Http leaked =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(tbItem("42", true, true, items)),
                                null)
                        .add(
                                "GET",
                                "/v1/api/torrents/requestdl",
                                tb("\"https://cdn.example/song?token=" + KEY + "\""),
                                null);
        fail(() -> provider.download(ready, "7", KEY, leaked, cancel), "account key in media URL");
        leaked.done();
        Http encodedLeak =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(tbItem("42", true, true, items)),
                                null)
                        .add(
                                "GET",
                                "/v1/api/torrents/requestdl",
                                tb(
                                        "\"https://cdn.example/song?token=test%252Dapi%252Dkey%252Donly\""),
                                null);
        fail(
                () -> provider.download(ready, "7", KEY, encodedLeak, cancel),
                "encoded account key in media URL");
        encodedLeak.done();
        Http wrong =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(tbItem("43", true, true, items)),
                                null);
        fail(() -> provider.refresh(pending, KEY, wrong, cancel), "wrong exact job response");
        wrong.done();
        Http zipped =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(
                                        tbItem(
                                                "42",
                                                true,
                                                true,
                                                "[{\"id\":7,\"name\":\"Song.flac\",\"size\":12,\"zipped\":true}]")),
                                null);
        fail(() -> provider.refresh(pending, KEY, zipped, cancel), "zipped replacement");
        zipped.done();
        Http infected =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/torrents/mylist",
                                tb(
                                        tbItem(
                                                "42",
                                                true,
                                                true,
                                                "[{\"id\":7,\"name\":\"Song.flac\",\"size\":12,\"infected\":true}]")),
                                null);
        fail(() -> provider.refresh(pending, KEY, infected, cancel), "flagged file");
        infected.done();
        http =
                new Http()
                        .multi(
                                "/v1/api/webdl/createwebdownload",
                                tb("{\"webdownload_id\":51}"),
                                p ->
                                        check(
                                                "https://host.example/song".equals(p.get("link")),
                                                "Web link sent to web API"));
        DebridModels.Resolved web =
                provider.resolve("https://host.example/song", KEY, http, cancel);
        http.done();
        check(web.remoteId.equals("web:51"), "Web job namespace separated from torrent");
        http =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/webdl/mylist",
                                tb(tbItem("51", true, true, "[" + file("7", "song.mp3", 12) + "]")),
                                null);
        web = provider.refresh(web, KEY, http, cancel);
        http.done();
        http =
                new Http()
                        .add(
                                "GET",
                                "/v1/api/webdl/mylist",
                                tb(tbItem("51", true, true, "[" + file("7", "song.mp3", 12) + "]")),
                                null)
                        .add(
                                "GET",
                                "/v1/api/webdl/requestdl",
                                tb("\"https://cdn.example/web\""),
                                p ->
                                        check(
                                                "51".equals(p.get("web_id"))
                                                        && !p.containsKey("torrent_id"),
                                                "Web API uses web_id"));
        provider.download(web, "7", KEY, http, cancel);
        http.done();
        http =
                new Http()
                        .multi(
                                "/v1/api/usenet/createusenetdownload",
                                tb("{\"usenetdownload_id\":61}"),
                                p ->
                                        check(
                                                "https://host.example/album.nzb?download=1"
                                                        .equals(p.get("link")),
                                                "NZB URL sent via official Usenet link field"));
        DebridModels.Resolved nzb =
                provider.resolve("https://host.example/album.nzb?download=1", KEY, http, cancel);
        http.done();
        check(nzb.remoteId.equals("usenet:61"), "NZB job namespace retained");
        Http rejected =
                new Http()
                        .multi(
                                "/v1/api/torrents/createtorrent",
                                "{\"success\":false,\"error\":\"PLAN_RESTRICTED_FEATURE\",\"detail\":\""
                                        + KEY
                                        + "\"}",
                                null);
        fail(() -> provider.resolve(MAGNET, KEY, rejected, cancel), "plan restriction redacted");
        rejected.done();
        Http no = new Http();
        fail(
                () -> provider.resolve("https://user:password@host.example/audio", KEY, no, cancel),
                "URL userinfo refused");
        check(no.calls == 0, "Invalid input not sent");
        Cancel stopped = new Cancel();
        stopped.stopped = true;
        fail(() -> provider.resolve(MAGNET, KEY, new Http(), stopped), "cancelled TorBox request");
    }

    public static void main(String[] args) throws Exception {
        premiumize();
        torbox();
        System.out.println("Premiumize/TorBox provider fixtures: " + checks + " assertions passed");
    }
}
