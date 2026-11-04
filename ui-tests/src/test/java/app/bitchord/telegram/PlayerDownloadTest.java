package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PlayerDownloadTest {
    static PlayerDownloadHost.Candidate candidate(
            String id, boolean lossless, int bits, int rate, int kbps) {
        return new PlayerDownloadHost.Candidate(id, lossless, bits, rate, kbps);
    }

    static final class Choices implements PlayerDownloadHost.Choices {
        PlayerDownloadHost.Candidate current, youtube;
        List<PlayerDownloadHost.Candidate> alternatives = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Exception currentError, alternativeError;

        public PlayerDownloadHost.Candidate current() throws Exception {
            calls.add("current");
            if (currentError != null) throw currentError;
            return current;
        }

        public List<PlayerDownloadHost.Candidate> alternatives() throws Exception {
            calls.add("others");
            if (alternativeError != null) throw alternativeError;
            return alternatives;
        }

        public PlayerDownloadHost.Candidate youtube() {
            calls.add("youtube");
            return youtube;
        }
    }

    @Test
    public void exactCurrentRouteWinsWithoutSearch() throws Exception {
        Choices c = new Choices();
        c.current = candidate("telegram-exact", true, 16, 44100, 0);
        c.alternatives.add(candidate("qobuz", true, 24, 192000, 0));
        assertEquals("telegram-exact", PlayerDownloadHost.choose(c));
        assertEquals(Arrays.asList("current"), c.calls);
    }

    @Test
    public void youtubePlayingDoesNotOutrankOtherSources() throws Exception {
        Choices c = new Choices();
        c.alternatives.add(candidate("telegram", true, 24, 48000, 0));
        c.youtube = candidate("youtube", false, 0, 0, 140);
        assertEquals("telegram", PlayerDownloadHost.choose(c));
        assertEquals(Arrays.asList("current", "others"), c.calls);
    }

    @Test
    public void highestQualityAcrossSourcesWins() throws Exception {
        Choices c = new Choices();
        c.alternatives.add(candidate("mp3", false, 0, 48000, 320));
        c.alternatives.add(candidate("cd", true, 16, 44100, 0));
        c.alternatives.add(candidate("hires", true, 24, 96000, 0));
        c.alternatives.add(candidate("highest", true, 24, 192000, 0));
        assertEquals("highest", PlayerDownloadHost.choose(c));
    }

    @Test
    public void localLosslessCanWinOverRemoteLossy() throws Exception {
        Choices c = new Choices();
        c.alternatives.add(candidate("local", true, 24, 96000, 0));
        c.alternatives.add(candidate("remote", false, 0, 0, 320));
        assertEquals("local", PlayerDownloadHost.choose(c));
    }

    @Test
    public void currentProbeFailureContinuesThroughAlternatives() throws Exception {
        Choices c = new Choices();
        c.currentError = new IOException();
        c.alternatives.add(candidate("alternate", true, 16, 44100, 0));
        assertEquals("alternate", PlayerDownloadHost.choose(c));
        assertFalse(c.calls.contains("youtube"));
    }

    @Test
    public void youtubeIsLastWhenNoOtherAudioAvailable() throws Exception {
        Choices c = new Choices();
        c.youtube = candidate("youtube", false, 0, 0, 140);
        assertEquals("youtube", PlayerDownloadHost.choose(c));
        assertEquals(Arrays.asList("current", "others", "youtube"), c.calls);
    }

    @Test
    public void blockedYoutubeAndNoOtherAudioReturnNoRoute() throws Exception {
        Choices c = new Choices();
        assertNull(PlayerDownloadHost.choose(c));
    }

    @Test
    public void failedSourceLookupDoesNotPretendNoAlternativeExists() throws Exception {
        Choices c = new Choices();
        c.alternativeError = new IOException();
        c.youtube = candidate("youtube", false, 0, 0, 140);
        try {
            PlayerDownloadHost.choose(c);
            fail();
        } catch (IOException expected) {
        }
        assertFalse(c.calls.contains("youtube"));
    }

    @Test
    public void cancellationStopsAllFurtherLookup() throws Exception {
        Choices c = new Choices();
        c.currentError = new CancellationException();
        try {
            PlayerDownloadHost.choose(c);
            fail();
        } catch (CancellationException expected) {
        }
        assertEquals(Collections.singletonList("current"), c.calls);
    }

    @Test
    public void durationRequiresKnownLengthsAndThreeSecondAgreement() {
        assertTrue(PlayerDownloadHost.sameDuration(214, 214));
        assertTrue(PlayerDownloadHost.sameDuration(214, 217));
        assertFalse(PlayerDownloadHost.sameDuration(214, 218));
        assertFalse(PlayerDownloadHost.sameDuration(0, 214));
        assertFalse(PlayerDownloadHost.sameDuration(214, 0));
        assertFalse(PlayerDownloadHost.sameDuration(-1, 214));
    }

    @Test
    public void matchingRejectsRemixEvenWhenDurationAgrees() {
        SourceFallback.Row row =
                new SourceFallback.Row("Faint (Remix)", "Linkin Park", 162, "content://audio/1");
        assertNull(
                SourceFallback.match(
                        Collections.singletonList(row),
                        "Faint",
                        "Linkin Park",
                        162,
                        s -> true,
                        Collections.emptySet()));
    }

    @Test
    public void matchingRejectsLiveDifferentArtistAndUnknownLength() {
        for (SourceFallback.Row row :
                Arrays.asList(
                        new SourceFallback.Row(
                                "Faint (Live)", "Linkin Park", 162, "content://audio/1"),
                        new SourceFallback.Row("Faint", "Other Artist", 162, "content://audio/2"),
                        new SourceFallback.Row("Faint", "Linkin Park", 0, "content://audio/3")))
            assertNull(
                    SourceFallback.match(
                            Collections.singletonList(row),
                            "Faint",
                            "Linkin Park",
                            162,
                            s -> true,
                            Collections.emptySet()));
    }

    static final class Running {
        public final float fraction;

        Running(float fraction) {
            this.fraction = fraction;
        }
    }

    @Test
    public void realProgressUsesNativeFractionAndWaitsForSavedBefore100() {
        assertEquals(36, PlayerDownload.percentage(new Running(.365f)));
        assertEquals(99, PlayerDownload.percentage(new Running(1)));
        assertEquals(-1, PlayerDownload.percentage(new Running(0)));
        assertEquals(-1, PlayerDownload.percentage(new Running(Float.NaN)));
        assertEquals(-1, PlayerDownload.percentage(null));
        assertEquals(-1, PlayerDownload.percentage(new Object()));
    }

    static final class Song {
        public final String videoId;

        Song(String id) {
            videoId = id;
        }
    }

    @Test
    public void onlyDedicatedRequestNamespaceOverridesNativeQueue() {
        assertTrue(PlayerDownload.owns(new Song("src:bitchord-player::one")));
        assertFalse(PlayerDownload.owns(new Song("2mXNRsyTitA")));
        assertFalse(PlayerDownload.owns(new Song("src:telegram-native::file")));
        assertNull(PlayerDownload.route(null, new Song("2mXNRsyTitA"), null));
    }

    @Test
    public void renderReserves48dpAndStartsNoTransfer() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        PlayerDownload.Entry entry = new PlayerDownload.Entry(activity);
        entry.measure(
                View.MeasureSpec.makeMeasureSpec(0, 0), View.MeasureSpec.makeMeasureSpec(0, 0));
        float d = activity.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(48 * d), entry.getMeasuredWidth());
        assertEquals(Math.round(48 * d), entry.getMeasuredHeight());
        assertTrue(entry.isFocusable());
        assertTrue(entry.isClickable());
        assertEquals("Download", entry.getContentDescription());
        activity.finish();
    }

    @Test
    public void safeRequestSurvivesCapabilityReleaseWithoutPersistingStream() throws Exception {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        PlayerDownload.init(app);
        String id = PlayerDownload.PREFIX + "persisted";
        org.json.JSONObject original =
                new org.json.JSONObject().put("videoId", "2mXNRsyTitA").put("title", "Faint");
        org.json.JSONObject stored =
                new org.json.JSONObject()
                        .put("song", original)
                        .put("seconds", 162)
                        .put("source", "telegram-native");
        app.getSharedPreferences("bitchord_player_download", 0)
                .edit()
                .putString(id, stored.toString())
                .commit();
        java.lang.reflect.Field field = PlayerDownload.class.getDeclaredField("live");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, PlayerDownload.Request> live =
                (java.util.Map<String, PlayerDownload.Request>) field.get(null);
        live.put(
                id,
                new PlayerDownload.Request(
                        id, original, 162, "telegram-native", new Object(), null));
        assertNotNull(PlayerDownload.request(id).stream);
        PlayerDownload.finished(id);
        PlayerDownload.Request restored = PlayerDownload.request(id);
        assertNull(restored.stream);
        assertEquals(162, restored.seconds);
        assertEquals("telegram-native", restored.source);
        assertFalse(
                app.getSharedPreferences("bitchord_player_download", 0)
                        .getString(id, "")
                        .contains("url"));
        app.getSharedPreferences("bitchord_player_download", 0).edit().remove(id).commit();
    }

    public static final class NativeObject {
        public static final NativeObject INSTANCE = new NativeObject();

        public String stream(String id) {
            return "stream:" + id;
        }
    }

    @Test
    public void nativeObjectMethodsResolveTheirSingleton() throws Exception {
        assertEquals(
                "stream:recording",
                PlayerDownloadHost.call(NativeObject.class, "stream", "recording"));
    }

    @Test
    public void fullPercentageStaysInsideTheIconAtLargeFontScale() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.res.Configuration configuration =
                activity.getResources().getConfiguration();
        float previous = configuration.fontScale;
        try {
            configuration.fontScale = 2;
            PlayerDownload.Entry entry = new PlayerDownload.Entry(activity);
            int pixels = Math.round(48 * activity.getResources().getDisplayMetrics().density);
            entry.layout(0, 0, pixels, pixels);
            entry.busy = true;
            entry.percent = 100;
            entry.arcPercent = 100;
            android.graphics.Bitmap bitmap =
                    android.graphics.Bitmap.createBitmap(
                            pixels, pixels, android.graphics.Bitmap.Config.ARGB_8888);
            entry.draw(new android.graphics.Canvas(bitmap));
            assertTrue(entry.paint.measureText("100%") > 0);
            assertTrue(entry.paint.measureText("100%") <= 20 * entry.density + 0.01f);
            assertEquals(pixels, entry.getWidth());
            assertEquals(pixels, entry.getHeight());
            bitmap.recycle();
        } finally {
            configuration.fontScale = previous;
            activity.finish();
        }
    }
}
