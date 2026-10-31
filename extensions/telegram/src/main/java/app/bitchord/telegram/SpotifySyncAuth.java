package app.bitchord.telegram;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.*;

import java.nio.charset.StandardCharsets;
import java.security.*;

/** On-device OAuth PKCE; independent of the host's Spotify web-player cookie connection. */
final class SpotifySyncAuth {
    static final String REDIRECT = "app.bitchord.sync://spotify/callback";
    static final String SCOPES =
            "playlist-read-private playlist-read-collaborative playlist-modify-private"
                    + " playlist-modify-public";
    final SyncSecrets secrets;
    final SyncHttp.Transport http;

    SpotifySyncAuth(Context app) {
        this(new SyncSecrets(app), SyncHttp.NETWORK);
    }

    SpotifySyncAuth(SyncSecrets secrets, SyncHttp.Transport http) {
        this.secrets = secrets;
        this.http = http;
    }

    static String random() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static String challenge(String verifier) throws Exception {
        return Base64.encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static String authorization(JSONObject pending) throws Exception {
        return "https://accounts.spotify.com/authorize?"
                + SyncHttp.form(
                        "client_id",
                        pending.getString("client"),
                        "response_type",
                        "code",
                        "redirect_uri",
                        REDIRECT,
                        "scope",
                        SCOPES,
                        "state",
                        pending.getString("state"),
                        "code_challenge_method",
                        "S256",
                        "code_challenge",
                        challenge(pending.getString("verifier")));
    }

    String begin(String client) throws Exception {
        if (!client.matches("[A-Za-z0-9]{16,128}"))
            throw new IllegalArgumentException("Invalid Spotify client ID");
        JSONObject pending =
                new JSONObject()
                        .put("client", client)
                        .put("state", random())
                        .put("verifier", random())
                        .put("created", System.currentTimeMillis());
        secrets.put("spotify.pending", pending.toString());
        return authorization(pending);
    }

    static String code(JSONObject pending, Uri callback, long now) throws Exception {
        if (pending == null
                || !REDIRECT.equals(
                        callback.buildUpon().clearQuery().fragment(null).build().toString())
                || callback.getFragment() != null
                || callback.getQueryParameters("state").size() != 1
                || callback.getQueryParameters("code").size() != 1)
            throw new IllegalArgumentException("Invalid authorization callback");
        long age = now - pending.getLong("created");
        if (age < 0 || age > 10 * 60 * 1000)
            throw new IllegalStateException("Authorization expired");
        String state = callback.getQueryParameter("state");
        if (state == null
                || !MessageDigest.isEqual(
                        state.getBytes(StandardCharsets.UTF_8),
                        pending.getString("state").getBytes(StandardCharsets.UTF_8))
                || callback.getQueryParameter("error") != null)
            throw new IllegalArgumentException("Authorization was not completed");
        String code = callback.getQueryParameter("code");
        if (code == null || code.isEmpty())
            throw new IllegalArgumentException("Authorization code is missing");
        return code;
    }

    synchronized void complete(Uri callback) throws Exception {
        String stored = secrets.get("spotify.pending");
        JSONObject pending = stored == null ? null : new JSONObject(stored);
        String code = code(pending, callback, System.currentTimeMillis());
        secrets.put("spotify.pending", null);
        SyncHttp.Response response =
                http.request(
                        "POST",
                        "https://accounts.spotify.com/api/token",
                        null,
                        "application/x-www-form-urlencoded",
                        SyncHttp.form(
                                "grant_type",
                                "authorization_code",
                                "code",
                                code,
                                "redirect_uri",
                                REDIRECT,
                                "client_id",
                                pending.getString("client"),
                                "code_verifier",
                                pending.getString("verifier")));
        if (response.status != 200) throw new IllegalStateException("Spotify authorization failed");
        JSONObject token = grant(response.json, null, pending.getString("client"));
        SyncHttp.Response profile =
                http.request(
                        "GET",
                        "https://api.spotify.com/v1/me",
                        token.getString("access"),
                        null,
                        null);
        if (profile.status != 200
                || profile.json.optString("account_id", "").isEmpty()
                || profile.json.optString("id", "").isEmpty())
            throw new IllegalStateException("Spotify account identity is unavailable");
        token.put("account", profile.json.getString("account_id"))
                .put("owner", profile.json.getString("id"))
                .put("display", profile.json.optString("display_name", "Spotify"));
        secrets.put("spotify", token.toString());
    }

    static JSONObject grant(JSONObject response, JSONObject previous, String client)
            throws Exception {
        long lifetime = response.getLong("expires_in");
        if (lifetime < 30
                || lifetime > 86400
                || !"Bearer".equalsIgnoreCase(response.optString("token_type", "Bearer")))
            throw new IllegalStateException("Invalid Spotify authorization grant");
        String access = response.getString("access_token"),
                refresh =
                        response.optString(
                                "refresh_token",
                                previous == null ? "" : previous.optString("refresh", "")),
                scope =
                        response.optString(
                                "scope", previous == null ? "" : previous.optString("scope", ""));
        if (access.isEmpty() || refresh.isEmpty())
            throw new IllegalStateException("Spotify did not return an authorization grant");
        for (String needed : SCOPES.split(" "))
            if (!java.util.Arrays.asList(scope.split(" ")).contains(needed))
                throw new IllegalStateException("Spotify playlist permission was not granted");
        JSONObject token =
                previous == null ? new JSONObject() : new JSONObject(previous.toString());
        token.put("client", client)
                .put("access", access)
                .put("refresh", refresh)
                .put("scope", scope)
                .put(
                        "expires",
                        System.currentTimeMillis() + response.getLong("expires_in") * 1000 - 60000);
        return token;
    }

    synchronized JSONObject token(boolean force) throws Exception {
        String saved = secrets.get("spotify");
        if (saved == null) throw new IllegalStateException("Connect Spotify first");
        JSONObject token = new JSONObject(saved);
        if (!force && token.getLong("expires") > System.currentTimeMillis()) return token;
        SyncHttp.Response response =
                http.request(
                        "POST",
                        "https://accounts.spotify.com/api/token",
                        null,
                        "application/x-www-form-urlencoded",
                        SyncHttp.form(
                                "grant_type",
                                "refresh_token",
                                "refresh_token",
                                token.getString("refresh"),
                                "client_id",
                                token.getString("client")));
        if (response.status != 200)
            throw new IllegalStateException("Reconnect Spotify to continue");
        token = grant(response.json, token, token.getString("client"));
        secrets.put("spotify", token.toString());
        return token;
    }

    void disconnect() throws Exception {
        secrets.put("spotify", null);
        secrets.put("spotify.pending", null);
    }
}
