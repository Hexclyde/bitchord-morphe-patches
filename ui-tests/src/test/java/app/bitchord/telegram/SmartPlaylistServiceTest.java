package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SmartPlaylistServiceTest {
    private static final SmartPlaylistService.Preferences PREFERENCES =
            new SmartPlaylistService.Preferences(25, true, false);
    private static final String KEY = "sk-secret-value";

    private static SmartPlaylistService.Problem problem(ThrowingRun run) {
        try {
            run.run();
        } catch (SmartPlaylistService.Failure failure) {
            return failure.problem;
        } catch (Exception other) {
            throw new AssertionError(other);
        }
        throw new AssertionError("Expected a failure");
    }

    private interface ThrowingRun {
        void run() throws Exception;
    }

    private static String wrap(String content) {
        try {
            return new JSONObject()
                    .put(
                            "choices",
                            new JSONArray()
                                    .put(
                                            new JSONObject()
                                                    .put(
                                                            "message",
                                                            new JSONObject()
                                                                    .put("content", content))))
                    .toString();
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    public void endpointsMustBeCleanHttps() throws Exception {
        SmartPlaylistService.Endpoint full =
                SmartPlaylistService.endpoint(
                        " HTTPS://Service.Example:8443/v1/chat/completions/ ");
        assertEquals("https://service.example:8443", full.origin);
        assertEquals("service.example", full.host);
        assertEquals("https://service.example:8443/v1/chat/completions", full.chatUrl);
        assertEquals("https://service.example:8443/v1/models", full.modelsUrl);
        SmartPlaylistService.Endpoint base =
                SmartPlaylistService.endpoint("https://service.example/v1");
        assertEquals("https://service.example/v1/chat/completions", base.chatUrl);
        assertEquals(
                "https://service.example/chat/completions",
                SmartPlaylistService.endpoint("https://service.example").chatUrl);
        for (String bad :
                Arrays.asList(
                        "",
                        "   ",
                        "http://service.example/v1",
                        "ftp://service.example",
                        "//service.example",
                        "service.example/v1",
                        "https://user:pass@service.example/v1",
                        "https://token@service.example/v1",
                        "https://service.example/v1?key=abc",
                        "https://service.example/v1?",
                        "https://service.example/v1#key=abc",
                        "https://service.example/../admin",
                        "https://service.example/a/../b",
                        "https://service.example:0/v1",
                        "https://service.example\\@other.example/v1",
                        "https://service.example/v 1",
                        "https://service.example/v\n1",
                        "https://",
                        "javascript:alert(1)",
                        "https://service.example/" + "a".repeat(3000)))
            assertEquals(
                    bad,
                    SmartPlaylistService.Problem.INVALID_ENDPOINT,
                    problem(() -> SmartPlaylistService.endpoint(bad)));
    }

    @Test
    public void keysCannotInjectHeaders() throws Exception {
        assertEquals("abc-123", SmartPlaylistService.key(" abc-123 "));
        assertEquals("", SmartPlaylistService.key(""));
        for (String bad : Arrays.asList("a b", "a\r\nX-Evil: 1", "kéy", "a".repeat(5000)))
            assertEquals(
                    SmartPlaylistService.Problem.INVALID_KEY,
                    problem(() -> SmartPlaylistService.key(bad)));
    }

    @Test
    public void requestsCarryOnlyTheDescriptionAndPreferences() throws Exception {
        JSONObject body =
                new JSONObject(
                        SmartPlaylistService.requestBody("model-a", PREFERENCES, "rainy \"city\""));
        assertEquals("model-a", body.getString("model"));
        assertFalse(body.getBoolean("stream"));
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        String user = messages.getJSONObject(1).getString("content");
        assertTrue(user.contains("rainy \"city\""));
        assertTrue(user.contains("Number of tracks: 25"));
        assertFalse(body.toString().contains(KEY));
        Set<String> keys = new HashSet<>();
        body.keys().forEachRemaining(keys::add);
        assertEquals(new HashSet<>(Arrays.asList("model", "messages", "stream")), keys);
    }

    @Test
    public void promptsAreBoundedAndRequired() {
        assertEquals(
                SmartPlaylistService.Problem.INVALID_PROMPT,
                problem(() -> SmartPlaylistService.prompt("  ")));
        assertEquals(
                SmartPlaylistService.Problem.INVALID_PROMPT,
                problem(() -> SmartPlaylistService.prompt("x".repeat(1001))));
    }

    @Test
    public void parsesThePlaylistInOrder() throws Exception {
        SmartPlaylistService.Result result =
                SmartPlaylistService.parse(
                        wrap(
                                "{\"title\":\"Night\",\"tracks\":["
                                        + "{\"title\":\"B\",\"artist\":\"Two\",\"album\":\"X\"},"
                                        + "{\"title\":\"A\",\"artist\":\"One\"}]}"),
                        25);
        assertEquals("Night", result.title);
        assertEquals(2, result.tracks.size());
        assertEquals("B", result.tracks.get(0).title);
        assertEquals("Two", result.tracks.get(0).artist);
        assertEquals("X", result.tracks.get(0).album);
        assertEquals("A", result.tracks.get(1).title);
        assertEquals("", result.tracks.get(1).album);
    }

    @Test
    public void toleratesFencesCommentaryAndPartLists() throws Exception {
        String playlist = "{\"title\":\"N\",\"tracks\":[{\"title\":\"Song\",\"artist\":\"A\"}]}";
        assertEquals(
                "Song",
                SmartPlaylistService.parse(wrap("```json\n" + playlist + "\n```"), 5)
                        .tracks
                        .get(0)
                        .title);
        assertEquals(
                "Song",
                SmartPlaylistService.parse(
                                wrap("Thinking {not json} and [1,2] then " + playlist + " done"), 5)
                        .tracks
                        .get(0)
                        .title);
        assertEquals(
                "Song",
                SmartPlaylistService.parse(wrap("[{\"title\":\"Song\",\"artist\":\"A\"}]"), 5)
                        .tracks
                        .get(0)
                        .title);
        String parts =
                new JSONObject()
                        .put(
                                "choices",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put(
                                                                "message",
                                                                new JSONObject()
                                                                        .put(
                                                                                "content",
                                                                                new JSONArray()
                                                                                        .put(
                                                                                                new JSONObject()
                                                                                                        .put(
                                                                                                                "text",
                                                                                                                playlist))))))
                        .toString();
        assertEquals("Song", SmartPlaylistService.parse(parts, 5).tracks.get(0).title);
    }

    @Test
    public void untrustedFieldsAreCleanedDeduplicatedAndBounded() throws Exception {
        JSONArray tracks = new JSONArray();
        tracks.put(new JSONObject().put("title", "Bi\u202edi\n  Name").put("artist", "A\u200b"));
        tracks.put(new JSONObject().put("title", "bi di name").put("artist", "A"));
        tracks.put(new JSONObject().put("title", "x".repeat(300)).put("artist", "Too long"));
        tracks.put(new JSONObject().put("title", 42).put("artist", "Not text"));
        tracks.put("not an object");
        for (int i = 0; i < 80; i++)
            tracks.put(new JSONObject().put("title", "Song " + i).put("artist", "Band"));
        SmartPlaylistService.Result result =
                SmartPlaylistService.parse(
                        wrap(new JSONObject().put("tracks", tracks).toString()), 10);
        assertEquals(10, result.tracks.size());
        assertEquals("Bi di Name", result.tracks.get(0).title);
        assertEquals("Song 0", result.tracks.get(1).title);
        assertEquals(
                "Cap is absolute",
                SmartPlaylistService.MAX_TRACKS,
                SmartPlaylistService.parse(
                                wrap(new JSONObject().put("tracks", tracks).toString()), 500)
                        .tracks
                        .size());
    }

    @Test
    public void suggestionsCannotCarrySongIdentity() throws Exception {
        SmartPlaylistService.Result result =
                SmartPlaylistService.parse(
                        wrap(
                                "{\"tracks\":[{\"title\":\"Song\",\"artist\":\"A\","
                                        + "\"videoId\":\"dQw4w9WgXcQ\",\"id\":\"src:x::1\"}]}"),
                        5);
        SmartPlaylistMatcher.Row row = new SmartPlaylistMatcher.Row(0, result.tracks.get(0));
        assertNull(row.track.videoId);
        assertNull("A reply cannot produce a playable song by itself", row.track.direct());
    }

    @Test
    public void malformedRepliesAreCategorised() throws Exception {
        for (String bad :
                Arrays.asList(
                        "",
                        "not json",
                        "{}",
                        "{\"choices\":[]}",
                        "{\"choices\":[{\"message\":{}}]}",
                        "{\"choices\":[{\"message\":{\"content\":null}}]}",
                        wrap("I can't do that"),
                        wrap("{\"tracks\":\"none\"}"),
                        wrap("{\"title\":\"cut off\",\"tracks\":[{\"title\":\"A\""),
                        wrap("{".repeat(5000)),
                        "[".repeat(200000)))
            assertEquals(
                    bad.length() > 40 ? bad.substring(0, 40) : bad,
                    SmartPlaylistService.Problem.INVALID_RESPONSE,
                    problem(() -> SmartPlaylistService.parse(bad, 5)));
        assertEquals(
                SmartPlaylistService.Problem.NO_TRACKS,
                problem(() -> SmartPlaylistService.parse(wrap("{\"tracks\":[]}"), 5)));
        assertEquals(
                SmartPlaylistService.Problem.INVALID_RESPONSE,
                problem(
                        () ->
                                SmartPlaylistService.parse(
                                        "x".repeat(SmartPlaylistService.MAX_RESPONSE + 1), 5)));
    }

    @Test
    public void responseReadsAreBoundedAndCancellable() throws Exception {
        SmartPlaylistService.Call call = new SmartPlaylistService.Call();
        long deadline = System.nanoTime() + 60_000_000_000L;
        assertEquals(
                "hello",
                SmartPlaylistService.read(
                        new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                        16,
                        call,
                        deadline));
        assertEquals(
                SmartPlaylistService.Problem.TOO_LARGE,
                problem(
                        () ->
                                SmartPlaylistService.read(
                                        new ByteArrayInputStream(new byte[17]),
                                        16,
                                        call,
                                        deadline)));
        assertEquals(
                SmartPlaylistService.Problem.UNAVAILABLE,
                problem(
                        () ->
                                SmartPlaylistService.read(
                                        new ByteArrayInputStream(new byte[4]),
                                        16,
                                        call,
                                        System.nanoTime() - 1)));
        call.cancel();
        assertEquals(
                SmartPlaylistService.Problem.CANCELLED,
                problem(
                        () ->
                                SmartPlaylistService.read(
                                        new ByteArrayInputStream(new byte[4]),
                                        16,
                                        call,
                                        deadline)));
    }

    @Test
    public void statusCodesMapToFixedCategories() throws Exception {
        SmartPlaylistService.Endpoint endpoint =
                SmartPlaylistService.endpoint("https://service.example/v1");
        Map<Integer, SmartPlaylistService.Problem> expected = new HashMap<>();
        expected.put(301, SmartPlaylistService.Problem.REDIRECTED);
        expected.put(307, SmartPlaylistService.Problem.REDIRECTED);
        expected.put(400, SmartPlaylistService.Problem.REFUSED);
        expected.put(401, SmartPlaylistService.Problem.REJECTED_KEY);
        expected.put(403, SmartPlaylistService.Problem.REJECTED_KEY);
        expected.put(404, SmartPlaylistService.Problem.NOT_FOUND);
        expected.put(429, SmartPlaylistService.Problem.RATE_LIMITED);
        expected.put(503, SmartPlaylistService.Problem.UNAVAILABLE);
        for (Map.Entry<Integer, SmartPlaylistService.Problem> row : expected.entrySet()) {
            AtomicInteger sent = new AtomicInteger();
            SmartPlaylistService.Transport transport =
                    (method, url, key, body, call) -> {
                        sent.incrementAndGet();
                        return new SmartPlaylistService.Reply(row.getKey(), "");
                    };
            assertEquals(
                    row.getValue(),
                    problem(
                            () ->
                                    SmartPlaylistService.generate(
                                            transport,
                                            endpoint,
                                            KEY,
                                            "m",
                                            PREFERENCES,
                                            "x",
                                            new SmartPlaylistService.Call())));
            assertEquals("Requests are never retried", 1, sent.get());
        }
    }

    @Test
    public void failuresNeverExposeSecretsOrAddresses() throws Exception {
        SmartPlaylistService.Endpoint endpoint =
                SmartPlaylistService.endpoint("https://service.example/private-path");
        SmartPlaylistService.Transport transport =
                (method, url, key, body, call) -> {
                    throw new IOException("Failed for " + url + " using " + key);
                };
        try {
            SmartPlaylistService.generate(
                    transport,
                    endpoint,
                    KEY,
                    "m",
                    PREFERENCES,
                    "x",
                    new SmartPlaylistService.Call());
            fail();
        } catch (SmartPlaylistService.Failure failure) {
            assertEquals(SmartPlaylistService.Problem.NETWORK, failure.problem);
            StringWriter out = new StringWriter();
            failure.printStackTrace(new PrintWriter(out));
            assertFalse(out.toString().contains(KEY));
            assertFalse(out.toString().contains("private-path"));
            assertNull(failure.getCause());
        }
    }

    @Test
    public void theKeyGoesOnlyInTheCredentialArgumentToTheChosenEndpoint() throws Exception {
        SmartPlaylistService.Endpoint endpoint =
                SmartPlaylistService.endpoint("https://service.example/v1");
        List<String> urls = new ArrayList<>();
        SmartPlaylistService.Transport transport =
                (method, url, key, body, call) -> {
                    urls.add(url);
                    assertEquals(KEY, key);
                    assertFalse(url.contains(KEY));
                    if (body != null) assertFalse(body.contains(KEY));
                    return new SmartPlaylistService.Reply(
                            200,
                            body == null
                                    ? "{\"data\":[{\"id\":\"zeta\"},{\"id\":\"alpha\"},{\"id\":\"zeta\"},{}]}"
                                    : wrap("{\"tracks\":[{\"title\":\"S\",\"artist\":\"A\"}]}"));
                };
        assertEquals(
                Arrays.asList("alpha", "zeta"),
                SmartPlaylistService.models(
                        transport, endpoint, KEY, new SmartPlaylistService.Call()));
        assertEquals(
                1,
                SmartPlaylistService.generate(
                                transport,
                                endpoint,
                                KEY,
                                "m",
                                PREFERENCES,
                                "x",
                                new SmartPlaylistService.Call())
                        .tracks
                        .size());
        assertEquals(
                Arrays.asList(
                        "https://service.example/v1/models",
                        "https://service.example/v1/chat/completions"),
                urls);
    }

    @Test
    public void plainHttpNeverConnectsEvenIfForced() {
        assertEquals(
                SmartPlaylistService.Problem.INVALID_ENDPOINT,
                problem(
                        () ->
                                SmartPlaylistService.NETWORK.exchange(
                                        "POST",
                                        "http://127.0.0.1:1/v1",
                                        KEY,
                                        "{}",
                                        new SmartPlaylistService.Call())));
    }

    @Test
    public void cancellationStopsBeforeAndAfterTheExchange() throws Exception {
        SmartPlaylistService.Endpoint endpoint =
                SmartPlaylistService.endpoint("https://service.example");
        SmartPlaylistService.Call before = new SmartPlaylistService.Call();
        before.cancel();
        assertEquals(
                SmartPlaylistService.Problem.CANCELLED,
                problem(
                        () ->
                                SmartPlaylistService.generate(
                                        (method, url, key, body, call) -> {
                                            throw new AssertionError("Must not send");
                                        },
                                        endpoint,
                                        "",
                                        "m",
                                        PREFERENCES,
                                        "x",
                                        before)));
        SmartPlaylistService.Call during = new SmartPlaylistService.Call();
        assertEquals(
                SmartPlaylistService.Problem.CANCELLED,
                problem(
                        () ->
                                SmartPlaylistService.generate(
                                        (method, url, key, body, call) -> {
                                            call.cancel();
                                            return new SmartPlaylistService.Reply(
                                                    200,
                                                    wrap(
                                                            "{\"tracks\":[{\"title\":\"S\",\"artist\":\"A\"}]}"));
                                        },
                                        endpoint,
                                        "",
                                        "m",
                                        PREFERENCES,
                                        "x",
                                        during)));
    }

    private static final class MemorySecrets implements SmartPlaylistStore.Secrets {
        final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String name) {
            return values.get(name);
        }

        @Override
        public void put(String name, String value) {
            if (value == null) values.remove(name);
            else values.put(name, value);
        }
    }

    @Test
    public void aSavedKeyBelongsToItsOrigin() throws Exception {
        MemorySecrets secrets = new MemorySecrets();
        SmartPlaylistStore store = new SmartPlaylistStore(secrets);
        assertNull(store.load());
        store.save("https://one.example/v1", "m", KEY);
        SmartPlaylistStore.Config config = store.load();
        assertEquals("one.example", config.host);
        assertEquals("m", config.model);
        assertTrue(config.hasKey);
        assertEquals("https://one.example/v1/chat/completions", config.endpoint);
        assertEquals(KEY, store.credentials().key);

        store.save("https://one.example/v2", "n", null);
        assertEquals("Same origin keeps it", KEY, store.credentials().key);
        assertEquals(KEY, store.keyFor(SmartPlaylistService.endpoint("https://one.example/x")));
        assertEquals("", store.keyFor(SmartPlaylistService.endpoint("https://two.example/v1")));
        assertEquals("", store.keyFor(SmartPlaylistService.endpoint("https://one.example:8443")));

        store.save("https://two.example/v1", "n", null);
        assertEquals("Another origin drops it", "", store.credentials().key);
        assertFalse(store.load().hasKey);
        assertFalse(secrets.values.toString().contains(KEY));
    }

    @Test
    public void disconnectRemovesEverything() throws Exception {
        MemorySecrets secrets = new MemorySecrets();
        SmartPlaylistStore store = new SmartPlaylistStore(secrets);
        store.save("https://one.example/v1", "m", KEY);
        store.disconnect();
        assertTrue(secrets.values.isEmpty());
        assertNull(store.load());
        assertNull(store.credentials());
    }

    @Test
    public void invalidOrUnreadableRecordsNeverFallBack() throws Exception {
        MemorySecrets secrets = new MemorySecrets();
        SmartPlaylistStore store = new SmartPlaylistStore(secrets);
        assertThrows(
                SmartPlaylistService.Failure.class,
                () -> store.save("http://one.example", "m", KEY));
        assertThrows(
                SmartPlaylistService.Failure.class,
                () -> store.save("https://one.example", "m", "bad key"));
        assertTrue("Nothing partial is stored", secrets.values.isEmpty());
        secrets.values.put("smart_playlist_service", "not json");
        assertThrows(Exception.class, store::load);
        store.save("https://one.example/v1", "m", null);
        assertEquals("", store.credentials().key);
        SmartPlaylistStore failing =
                new SmartPlaylistStore(
                        new SmartPlaylistStore.Secrets() {
                            @Override
                            public String get(String name) throws Exception {
                                throw new IllegalStateException("Keystore unavailable");
                            }

                            @Override
                            public void put(String name, String value) throws Exception {
                                throw new IllegalStateException("Keystore unavailable");
                            }
                        });
        assertThrows(Exception.class, failing::load);
        assertThrows(Exception.class, () -> failing.save("https://one.example", "m", KEY));
    }
}
