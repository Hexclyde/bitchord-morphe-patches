package app.bitchord.telegram;

import static org.junit.Assert.*;

import com.music.bitchord.data.sources.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.io.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        shadows = FlacAddonTest.Installed.class,
        instrumentedPackages = "app.bitchord.telegram.SoundExtras")
public class FlacAddonTest {
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class Installed {
        @Implementation
        protected static boolean flacAddonApplied() {
            return true;
        }
    }

    static final String PRIMARY = "https://www.deezer.com/track/12345";
    static final String URL = "https://open.qobuz.com/track/12345";
    List<String> paths;
    List<JSONObject> posts;

    long deadline() {
        return System.nanoTime() + 30_000_000_000L;
    }

    JSONObject object(String s) throws Exception {
        return new JSONObject(s);
    }

    @Before
    public void setup() throws Exception {
        SourceRegistry.configs.setValue(new ArrayList<>());
        FlacAddon.register();
        SourceRegistry.setEnabled(FlacAddon.ID, true);
        FlacAddon.providerSession = "verified-provider-session";
        FlacAddon.sessions =
                new FlacAddon.SessionStore() {
                    public String read() {
                        return "verified-provider-session";
                    }

                    public void write(String value) {}
                };
        paths = new ArrayList<>();
        posts = new ArrayList<>();
        FlacAddon.pause = ms -> {};
        FlacAddon.transport =
                (path, body, token, end) -> {
                    paths.add(path);
                    if (body != null) posts.add(body);
                    if (path.startsWith("/search?"))
                        return object(
                                "{tracks:[{link:'"
                                        + PRIMARY
                                        + "',title:'A /"
                                        + " B',artist:'Artist',album:'Album',duration:180,cover:'https://art.example/cover.jpg'}]}");
                    if (path.equals("/prepare")) return object("{t:'ephemeral-token'}");
                    if (path.equals("/api/qobuz/queue")) {
                        assertEquals("ephemeral-token", token);
                        return object("{id:'job-123'}");
                    }
                    return object("{status:'done',file:'original.flac',pretty_name:'A / B.flac'}");
                };
    }

    @After
    public void cleanup() {
        FlacAddon.transport = FlacAddon::http;
        FlacAddon.pause = Thread::sleep;
        Thread.interrupted();
    }

    String primaryId() throws Exception {
        return "flac."
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                new JSONObject()
                                        .put("u", PRIMARY)
                                        .put("t", "Mask Off")
                                        .put("a", "Ganja White Night")
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void providerNullProgressAndUppercaseQualityAreNotDowngrades() throws Exception {
        final int[] polls = {0};
        FlacAddon.transport =
                (path, post, token, deadline) -> {
                    if (path.equals("/prepare")) return object("{t:'token'}");
                    if (path.equals("/download")) return object("{job_id:'job'}");
                    if (polls[0]++ == 0)
                        return new JSONObject()
                                .put("status", "downloading")
                                .put("quality", JSONObject.NULL)
                                .put("filename", JSONObject.NULL)
                                .put("mirror", JSONObject.NULL);
                    if (polls[0] == 2)
                        return new JSONObject()
                                .put("status", "mirror")
                                .put("quality", JSONObject.NULL)
                                .put("mirror", new JSONObject().put("calidad", JSONObject.NULL));
                    return object("{status:'done',filename:'mask-off.flac',quality:'FLAC'}");
                };
        assertEquals("flac", FlacAddon.resolve(primaryId(), deadline()).getString("format"));
        assertEquals(3, polls[0]);
    }

    @Test
    public void acceptingHumanVerificationNeverStartsDownload() throws Exception {
        final String[] retained = {""};
        FlacAddon.sessions =
                new FlacAddon.SessionStore() {
                    public String read() {
                        return retained[0];
                    }

                    public void write(String value) {
                        retained[0] = value;
                    }
                };
        FlacAddon.providerSession = "";
        FlacAddon.transport =
                (path, post, token, deadline) -> {
                    paths.add(path);
                    assertEquals("/prepare", path);
                    assertEquals("human-verified-session", post.getString("sp"));
                    return object("{t:'short-lived-token'}");
                };
        FlacAddon.acceptSession("human-verified-session");
        assertEquals("human-verified-session", retained[0]);
        assertEquals(Collections.singletonList("/prepare"), paths);
        assertFalse(FlacAddon.needsVerification());
    }

    @Test
    public void primaryProtocolUsesVerifiedSessionAndExactFormat() throws Exception {
        FlacAddon.transport =
                (path, post, token, deadline) -> {
                    paths.add(path);
                    if (path.equals("/prepare")) {
                        assertEquals("verified-provider-session", post.getString("sp"));
                        return object("{t:'token'}");
                    }
                    if (path.equals("/download")) {
                        assertEquals("token", token);
                        assertEquals(PRIMARY, post.getString("url"));
                        assertEquals("flac", post.getString("format"));
                        assertFalse(post.getBoolean("accept_fallback"));
                        return object("{job_id:'mask-off'}");
                    }
                    return object(
                            "{status:'done',filename:'mask-off.flac',pretty_name:'Mask"
                                    + " Off.flac',format:'flac'}");
                };
        JSONObject file = FlacAddon.resolve(primaryId(), deadline());
        assertEquals("flac", file.getString("format"));
        assertTrue(file.getString("url").contains("/file/mask-off.flac"));
        assertEquals(Arrays.asList("/prepare", "/download", "/status/mask-off"), paths);
    }

    @Test
    public void missingVerificationNeverQueuesAProviderJob() throws Exception {
        FlacAddon.providerSession = "";
        FlacAddon.sessions =
                new FlacAddon.SessionStore() {
                    public String read() {
                        return "";
                    }

                    public void write(String value) {}
                };
        try {
            FlacAddon.resolve(primaryId(), deadline());
            fail();
        } catch (FlacAddon.VerificationRequired expected) {
            assertTrue(paths.isEmpty());
        }
    }

    @Test
    public void rejectsServerDowngradeAndDoesNotHideTransientFailures() throws Exception {
        String id = primaryId();
        FlacAddon.transport =
                (path, post, token, deadline) -> {
                    if (path.equals("/prepare")) return object("{t:'token'}");
                    if (path.equals("/download")) return object("{job_id:'job'}");
                    return object("{status:'failed'}");
                };
        try {
            FlacAddon.resolve(id, deadline());
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Retry"));
        }
        FlacAddon.transport =
                (path, post, token, deadline) -> {
                    if (path.equals("/prepare")) return object("{t:'token'}");
                    if (path.equals("/download")) return object("{job_id:'job'}");
                    return object("{status:'done',filename:'mask-off.mp3',format:'mp3_320'}");
                };
        try {
            FlacAddon.resolve(id, deadline());
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("another format"));
        }
    }

    @Test
    public void registryDefaultsOffAndKeepsOrderAndSwitch() throws Exception {
        SourceConfig other =
                new SourceConfig(
                        "other",
                        SourceKind.ADDON,
                        "Other",
                        "https://example.com",
                        true,
                        true,
                        false);
        SourceRegistry.configs.setValue(new ArrayList<>(Arrays.asList(other)));
        FlacAddon.register();
        assertSame(other, SourceRegistry.configs.getValue().get(0));
        assertFalse(FlacAddon.enabled());
        SourceRegistry.setEnabled(FlacAddon.ID, true);
        FlacAddon.register();
        assertTrue(FlacAddon.enabled());
        assertEquals(2, SourceRegistry.configs.getValue().size());
    }

    @Test
    public void searchReturnsDistinctExplicitChoicesAndReversibleIds() throws Exception {
        JSONArray rows =
                FlacAddon.search("Artist A & B", "LOSSLESS", deadline()).getJSONArray("tracks");
        assertEquals(3, rows.length());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            JSONObject row = rows.getJSONObject(i);
            assertTrue(ids.add(row.getString("id")));
            assertEquals(PRIMARY, FlacAddon.decode(row.getString("id")).getString("u"));
            assertFalse(row.getBoolean("formatVerified"));
            assertFalse(
                    FlacAddon.choiceLabel("src:" + FlacAddon.ID + "::" + row.getString("id"))
                            .isEmpty());
            assertEquals(180, row.getDouble("duration"), 0.01);
            assertFalse(row.getString("audioQuality").contains("kHz"));
        }
        assertTrue(paths.get(0).contains("A%20%26%20B"));
        assertEquals(1, FlacAddon.search("A", "HIGH", deadline()).getJSONArray("tracks").length());
        int count = paths.size();
        assertEquals(1, FlacAddon.search("A", "LOW", deadline()).getJSONArray("tracks").length());
        assertEquals(count + 1, paths.size());
    }

    @Test
    public void exactFormatIsQueuedOnceAndFileUrlIsEncoded() throws Exception {
        JSONObject stream =
                FlacAddon.resolve(FlacAddon.identity(URL, "A / B", "Artist", 7), deadline());
        assertEquals("verified-provider-session", posts.get(0).getString("sp"));
        assertEquals(7, posts.get(1).getInt("formatId"));
        assertEquals(2, posts.size());
        assertTrue(stream.getString("url").contains("name=A%20%2F%20B.flac"));
        assertEquals("flac", stream.getString("codec"));
        assertFalse(stream.has("bitDepth"));
        assertFalse(stream.has("sampleRate"));
        assertFalse(stream.has("bitrate"));
        assertFalse(stream.getBoolean("formatVerified"));
    }

    @Test
    public void requestedQualityNeverFallsBackAfterBackendFailure() throws Exception {
        FlacAddon.Transport original = FlacAddon.transport;
        FlacAddon.transport =
                (p, b, t, d) ->
                        p.startsWith("/api/qobuz/jobs")
                                ? object("{status:'error'}")
                                : original.request(p, b, t, d);
        try {
            FlacAddon.resolve(FlacAddon.identity(URL, "Track", "Artist", 27), deadline());
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Choose another format"));
        }
        assertEquals(2, posts.size());
        assertEquals(27, posts.get(1).getInt("formatId"));
    }

    @Test
    public void pendingJobsPollWithDelayAndRespectDisable() throws Exception {
        int[] calls = {0}, pauses = {0};
        FlacAddon.Transport original = FlacAddon.transport;
        FlacAddon.transport =
                (p, b, t, d) ->
                        p.startsWith("/api/qobuz/jobs") && calls[0]++ == 0
                                ? object("{status:'processing'}")
                                : original.request(p, b, t, d);
        FlacAddon.pause =
                ms -> {
                    assertTrue(ms > 0);
                    pauses[0]++;
                };
        FlacAddon.resolve(FlacAddon.identity(URL, "Track", "Artist", 6), deadline());
        assertEquals(1, pauses[0]);
        FlacAddon.pause = ms -> SourceRegistry.setEnabled(FlacAddon.ID, false);
        FlacAddon.transport =
                (p, b, t, d) ->
                        p.startsWith("/api/qobuz/jobs")
                                ? object("{status:'processing'}")
                                : original.request(p, b, t, d);
        try {
            FlacAddon.resolve(FlacAddon.identity(URL, "Track", "Artist", 6), deadline());
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("disabled"));
        }
    }

    @Test
    public void malformedMetadataCannotChangeBackendOrFilePath() throws Exception {
        for (String u :
                new String[] {
                    "http://open.qobuz.com/track/1",
                    "https://open.qobuz.com.evil.test/track/1",
                    "https://user@open.qobuz.com/track/1",
                    "https://open.qobuz.com/track/1?x=1",
                    "https://127.0.0.1/track/1"
                }) assertFalse(FlacAddon.catalogueUrl(u));
        FlacAddon.Transport original = FlacAddon.transport;
        for (String file :
                new String[] {
                    "../bad.flac", "wrong.mp3", "https://evil.test/a.flac", "folder/a.flac"
                }) {
            FlacAddon.transport =
                    (p, b, t, d) ->
                            p.startsWith("/api/qobuz/jobs")
                                    ? new JSONObject().put("status", "done").put("file", file)
                                    : original.request(p, b, t, d);
            try {
                FlacAddon.resolve(FlacAddon.identity(URL, "Track", "Artist", 6), deadline());
                fail(file);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("unexpected file"));
            }
        }
    }

    @Test
    public void reservedRoutingIsIsolatedAndDisabledSourceMakesNoRequests() throws Exception {
        assertNull(FlacAddon.bodyOrNull("https://example.com/manifest.json"));
        SourceRegistry.setEnabled(FlacAddon.ID, false);
        assertTrue(
                new JSONObject(FlacAddon.bodyOrNull(FlacAddon.ORIGIN + "/manifest.json"))
                        .getBoolean("allowDownloads"));
        try {
            FlacAddon.bodyOrNull(FlacAddon.ORIGIN + "/search?q=A");
            fail();
        } catch (IOException expected) {
        }
        assertTrue(paths.isEmpty());
        for (String url :
                new String[] {
                    "http://flac.bitchord.invalid/search?q=A",
                    "https://user@flac.bitchord.invalid/search?q=A",
                    "https://flac.bitchord.invalid:443/search?q=A"
                })
            try {
                FlacAddon.bodyOrNull(url);
                fail(url);
            } catch (IOException expected) {
            }
    }

    @Test
    public void expiredInterruptedAndUnexpectedJobsStopCleanly() throws Exception {
        try {
            FlacAddon.resolve(FlacAddon.identity(URL, "Track", "Artist", 6), System.nanoTime() - 1);
            fail();
        } catch (IOException expected) {
        }
        assertTrue(paths.isEmpty());
        Thread.currentThread().interrupt();
        try {
            FlacAddon.resolve(FlacAddon.identity(URL, "Track", "Artist", 6), deadline());
            fail();
        } catch (InterruptedIOException expected) {
        } finally {
            Thread.interrupted();
        }
        FlacAddon.Transport original = FlacAddon.transport;
        FlacAddon.transport =
                (p, b, t, d) ->
                        p.startsWith("/api/qobuz/jobs")
                                ? object("{status:'unknown'}")
                                : original.request(p, b, t, d);
        try {
            FlacAddon.resolve(FlacAddon.identity(URL, "Track", "Artist", 6), deadline());
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unexpected"));
        }
    }
}
