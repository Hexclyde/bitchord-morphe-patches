package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SyncAuthorizationTest {
    JSONObject pending() throws Exception {
        return new JSONObject()
                .put("state", "expected")
                .put("verifier", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
                .put("client", "clientid123456789")
                .put("created", 1000);
    }

    @Test
    public void pkceMatchesThePublishedS256Vector() throws Exception {
        assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                SpotifySyncAuth.challenge(pending().getString("verifier")));
        assertFalse(SpotifySyncAuth.random().equals(SpotifySyncAuth.random()));
    }

    @Test
    public void callbackRequiresExactUriStateAndFreshUnambiguousCode() throws Exception {
        assertEquals(
                "abc",
                SpotifySyncAuth.code(
                        pending(),
                        Uri.parse(SpotifySyncAuth.REDIRECT + "?code=abc&state=expected"),
                        2000));
        for (String uri :
                new String[] {
                    SpotifySyncAuth.REDIRECT + "?code=abc&state=wrong",
                    SpotifySyncAuth.REDIRECT + "?code=abc&state=expected&code=other",
                    SpotifySyncAuth.REDIRECT + "?code=abc&state=expected#error",
                    "app.evil://spotify/callback?code=abc&state=expected"
                })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SpotifySyncAuth.code(pending(), Uri.parse(uri), 2000));
        assertThrows(
                IllegalStateException.class,
                () ->
                        SpotifySyncAuth.code(
                                pending(),
                                Uri.parse(SpotifySyncAuth.REDIRECT + "?code=abc&state=expected"),
                                700000));
    }

    @Test
    public void refreshKeepsAnUnrotatedRefreshTokenAndGrantedScopes() throws Exception {
        JSONObject old =
                new JSONObject()
                        .put("refresh", "keep-me")
                        .put("scope", SpotifySyncAuth.SCOPES)
                        .put("account", "account-a");
        JSONObject grant =
                SpotifySyncAuth.grant(
                        new JSONObject().put("access_token", "new").put("expires_in", 3600),
                        old,
                        "clientid123456789");
        assertEquals("keep-me", grant.getString("refresh"));
        assertEquals("account-a", grant.getString("account"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        SpotifySyncAuth.grant(
                                new JSONObject()
                                        .put("access_token", "a")
                                        .put("refresh_token", "r")
                                        .put("scope", "playlist-read-private")
                                        .put("expires_in", 3600),
                                null,
                                "clientid123456789"));
    }

    @Test
    public void ownedHeaderCheckNeverTreatsTrackDeleteAsOwnership() throws Exception {
        JSONObject row =
                new JSONObject(
                        "{\"contents\":{\"musicPlaylistShelfRenderer\":{\"contents\":[{\"menuNavigationItemRenderer\":{\"icon\":{\"iconType\":\"DELETE\"}}}]}}}");
        assertFalse(YouTubePlaylistApi.editable(row));
        assertTrue(
                YouTubePlaylistApi.editable(
                        new JSONObject(
                                "{\"header\":{\"musicEditablePlaylistDetailHeaderRenderer\":{}}}")));
    }

    @Test
    public void authoritativeReaderScopesPlaylistRowsAndRejectsMissingShelf() throws Exception {
        JSONObject root =
                new JSONObject(
                        "{\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"secondaryContents\":{\"sectionListRenderer\":{\"contents\":[{\"musicPlaylistShelfRenderer\":{\"contents\":[]}},{\"musicShelfRenderer\":{\"title\":{\"runs\":[{\"text\":\"Suggestions\"}]}}}]}}}}}");
        assertTrue(YouTubePlaylistApi.shelf(root).has("contents"));
        assertThrows(
                IllegalStateException.class, () -> YouTubePlaylistApi.shelf(new JSONObject("{}")));
    }
}
