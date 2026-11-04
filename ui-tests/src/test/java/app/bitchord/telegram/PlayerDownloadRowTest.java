package app.bitchord.telegram;

import static org.junit.Assert.*;

import com.music.bitchord.data.model.Song;
import com.music.bitchord.data.sources.*;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        instrumentedPackages = "app.bitchord.telegram.PlayerDownloadHost",
        shadows = PlayerDownloadRowTest.Probe.class)
public class PlayerDownloadRowTest {
    public static final class Stream {
        public final String sourceConfigId;
        public final Integer durationSec;
        final String label;

        Stream(String source, Integer duration, String label) {
            sourceConfigId = source;
            durationSec = duration;
            this.label = label;
        }
    }

    @Implements(value = PlayerDownloadHost.class, isInAndroidSdk = false)
    public static class Probe {
        @Implementation
        public PlayerDownloadHost.Candidate candidate(Object song, Object stream, int seconds) {
            return new PlayerDownloadHost.Candidate(((Stream) stream).label, true, 24, 48000, 0);
        }
    }

    static final String SOURCE = "owned-source", KEY = "src:owned-source::opaque:track/file";
    List<SourceConfig> old;
    Map<String, Object> offers;
    List<String> calls;
    PlayerDownloadHost host;
    Song song;

    @Before
    public void before() {
        old = new ArrayList<>(SourceRegistry.configs.getValue());
        offers = new HashMap<>(SourceResolver.offers);
        calls = new ArrayList<>(SourceResolver.calls);
        SourceRegistry.configs.setValue(
                Collections.singletonList(config(true, true, SourceKind.ADDON)));
        SourceResolver.offers.clear();
        SourceResolver.calls.clear();
        host = new PlayerDownloadHost(RuntimeEnvironment.getApplication(), null);
        song = new Song(KEY, "Fixture", "Verification", "0:30");
    }

    @After
    public void after() {
        SourceRegistry.configs.setValue(old);
        SourceResolver.offers.clear();
        SourceResolver.offers.putAll(offers);
        SourceResolver.calls.clear();
        SourceResolver.calls.addAll(calls);
    }

    SourceConfig config(boolean enabled, boolean downloads, SourceKind kind) {
        return new SourceConfig(
                SOURCE, kind, "Owned", "https://unused.invalid", enabled, downloads, false);
    }

    PlayerDownload.Request request(String key, String origin, Object stream) throws Exception {
        return new PlayerDownload.Request(
                PlayerDownload.PREFIX + "row",
                new JSONObject()
                        .put("videoId", key)
                        .put("title", "Fixture")
                        .put("artist", "Verification")
                        .put("sourceQuality", "FLAC · 24-bit · 48 kHz"),
                30,
                origin,
                stream,
                null);
    }

    @Test
    public void directSourceRowWithoutStreamChoiceResolvesItsOwnOpaqueIdWithoutSearch()
            throws Exception {
        SourceResolver.offers.put("row", new Stream(SOURCE, 30, "exact-row"));
        PlayerDownloadHost.Candidate result = host.current(request(KEY, SOURCE, null), song);
        assertNotNull("Explicit source rows must survive missing StreamChoice", result);
        assertEquals("exact-row", result.route);
        assertEquals(
                Collections.singletonList(
                        "row:owned-source:opaque:track/file:FLAC · 24-bit · 48 kHz:Best"),
                SourceResolver.calls);
    }

    @Test
    public void currentCapturedStreamStillWinsBeforeReresolvingItsRow() throws Exception {
        PlayerDownloadHost.Candidate result =
                host.current(request(KEY, SOURCE, new Stream(SOURCE, 30, "captured")), song);
        assertEquals("captured", result.route);
        assertTrue(SourceResolver.calls.isEmpty());
    }

    @Test
    public void disabledOrNonDownloadableSourceCannotBeResolved() throws Exception {
        for (SourceConfig config :
                Arrays.asList(
                        config(false, true, SourceKind.ADDON),
                        config(true, false, SourceKind.ADDON),
                        config(true, true, SourceKind.YOUTUBE))) {
            SourceRegistry.configs.setValue(Collections.singletonList(config));
            assertNull(host.current(request(KEY, SOURCE, null), song));
        }
        assertTrue(SourceResolver.calls.isEmpty());
    }

    @Test
    public void changedActualOriginDoesNotSilentlyResolveAnotherProvider() throws Exception {
        assertNull(host.current(request(KEY, "another-source", null), song));
        assertTrue(SourceResolver.calls.isEmpty());
    }

    @Test
    public void providerChangeOrKnownDurationMismatchRejectsTheResolvedRow() throws Exception {
        SourceResolver.offers.put("row", new Stream("wrong-source", 30, "wrong"));
        assertNull(host.current(request(KEY, SOURCE, null), song));
        SourceResolver.offers.put("row", new Stream(SOURCE, 34, "different-edit"));
        assertNull(host.current(request(KEY, SOURCE, null), song));
    }

    @Test
    public void sourceRowCanBeResolvedAgainForSafePersistedRetry() throws Exception {
        SourceResolver.offers.put("row", new Stream(SOURCE, 31, "renewed"));
        assertEquals("renewed", host.current(request(KEY, SOURCE, null), song).route);
        assertEquals("renewed", host.current(request(KEY, SOURCE, null), song).route);
        assertEquals(2, SourceResolver.calls.size());
    }

    @Test
    public void ordinaryOrMalformedIdsDoNotUseTheDirectRowPath() throws Exception {
        for (String id :
                new String[] {
                    "2mXNRsyTitA",
                    "src:owned-source::",
                    "src:::track",
                    "not-src:owned-source::track"
                }) assertNull(host.current(request(id, SOURCE, null), song));
        assertTrue(SourceResolver.calls.isEmpty());
    }
}
