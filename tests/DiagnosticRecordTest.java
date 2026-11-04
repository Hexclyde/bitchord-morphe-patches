package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.*;

public final class DiagnosticRecordTest {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        String[] secrets = {
            "person@example.invalid",
            "+15551234567",
            "https://host.invalid/a?token=PRIVATEKEY",
            "password=PRIVATEPASSWORD",
            "cookie=PRIVATECOOKIE",
            "/home/private/account"
        };
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sdk", 37);
        fields.put("patch", "1.1.0-beta.19");
        fields.put("route", "google-account");
        fields.put("code", -10);
        fields.put("main", true);
        fields.put("package", "app.revanced.android.gms");
        for (String secret : secrets) {
            fields.put("message", secret);
            fields.put("error", secret);
            fields.put("provider", secret);
            fields.put("version", secret);
            String safe = DiagnosticRecord.structured("login.web.error", fields);
            for (String canary : secrets)
                check(!safe.contains(canary), "Structured event exported private text");
            check(!safe.contains("message"), "Unknown payload key retained");
        }
        JSONObject event = new JSONObject(DiagnosticRecord.structured("login.web.error", fields));
        check(
                event.getInt("sdk") == 37
                        && event.getInt("code") == -10
                        && event.getBoolean("main"),
                "Useful bounded typed metadata lost");
        check(
                event.getString("route").equals("google-account")
                        && event.getString("patch").equals("1.1.0-beta.19"),
                "Fixed route/build metadata lost");
        for (String secret : secrets) {
            String safe =
                    DiagnosticRecord.safeLine(
                            'E', secret + " net::ERR_UNKNOWN_URL_SCHEME HTTP 403 " + secret);
            check(
                    safe.contains("ERR_UNKNOWN_URL_SCHEME")
                            && new JSONObject(safe).getInt("http_status") == 403,
                    "Fixed actionable WebView/HTTP codes lost");
            for (String canary : secrets)
                check(!safe.contains(canary), "Native log line leaked private payload");
        }
        check(
                new JSONObject(
                                DiagnosticRecord.safeLine(
                                        'I',
                                        "MicroG sign-in: asking MicroG RE for a YouTube Music"
                                                + " sign-in link"))
                        .getString("reason")
                        .equals("MICROG_REQUEST"),
                "MicroG request not classified");
        check(
                new JSONObject(DiagnosticRecord.safeLine('W', "MicroG sign-in cancelled"))
                        .getString("reason")
                        .equals("CANCELLED"),
                "Cancellation not classified");
        check(
                new JSONObject(DiagnosticRecord.safeLine('E', "renderer crashed PRIVATECOOKIE"))
                        .getString("reason")
                        .equals("RENDERER_GONE"),
                "Renderer failure not classified");
        for (String reason :
                "PASSED DURATION_INSUFFICIENT DURATION_MISMATCH IDENTITY_MISMATCH NETWORK_TIMEOUT DNS_FAILURE CONNECT_FAILURE TLS_FAILURE IO_INTERRUPTED UNEXPECTED_EOF FILE_MISSING DECODE_FAILURE IO_UNCLASSIFIED INTERNAL_UNCLASSIFIED NONE STOP_UNSPECIFIED STOP_CLOSED STOP_INTERRUPTED STOP_PLAYBACK_STOPPED STOP_CANCELLED STOP_DEADLINE STOP_BYTE_LIMIT STOP_READER_RELEASED STOP_READ_PERMISSION"
                        .split(" ")) {
            JSONObject recording =
                    new JSONObject(
                            DiagnosticRecord.safeLine(
                                    'D',
                                    "Recording check stopped at comparison after 12000 ms, 6 of 6"
                                            + " windows; reference PRIVATECOOKIE; reason="
                                            + reason));
            check(
                    recording.getString("reason").equals(reason)
                            && recording.getString("category").equals("SOURCE")
                            && recording.getInt("windows") == 6
                            && recording.getInt("total_windows") == 6
                            && !recording.toString().contains("PRIVATECOOKIE"),
                    "Fixed recording reason or bounded windows lost: " + reason);
        }
        check(
                !DiagnosticRecord.safeLine('D', "Recording check stopped; reason=PRIVATEPASSWORD")
                        .contains("PRIVATEPASSWORD"),
                "Unknown source reason retained");
        check(
                !new JSONObject(DiagnosticRecord.safeLine('D', "unrelated reason=DNS_FAILURE"))
                        .getString("reason")
                        .equals("DNS_FAILURE"),
                "Unqualified line treated as source diagnostic");
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("sdk", 10000);
        invalid.put("width", 999999);
        invalid.put("code", Double.NaN);
        invalid.put("main", "PRIVATEPASSWORD");
        invalid.put("at", "+15551234567");
        String bounded = DiagnosticRecord.structured("process.started", invalid);
        JSONObject boundedObject = new JSONObject(bounded);
        check(
                !boundedObject.has("sdk")
                        && !boundedObject.has("width")
                        && !boundedObject.has("code")
                        && !boundedObject.has("main")
                        && !boundedObject.has("at"),
                "Invalid numeric or boolean values retained");
        Throwable error = new IllegalStateException("person@example.invalid PRIVATEPASSWORD");
        error.setStackTrace(
                new StackTraceElement[] {
                    new StackTraceElement(
                            "com.music.bitchord.PrivateAccount",
                            "PRIVATECOOKIE",
                            "private-path",
                            123)
                });
        String failure = DiagnosticRecord.failure(error);
        check(
                failure.contains("STATE")
                        && failure.contains("frame_line")
                        && !failure.contains("PrivateAccount")
                        && !failure.contains("PRIVATE")
                        && !failure.contains("private-path")
                        && !failure.contains("example.invalid"),
                "Throwable export must classify, never stringify");
        check(
                DiagnosticRecord.sanitize("raw PRIVATEPASSWORD https://host.invalid") == null,
                "Unstructured raw export accepted");
        JSONObject loaded =
                new JSONObject(
                        DiagnosticRecord.sanitize(
                                "{\"type\":\"host.line\",\"message\":\"PRIVATEPASSWORD\",\"severity\":\"ERROR\"}"));
        check(
                !loaded.has("message")
                        && loaded.getString("type").equals("host.line")
                        && loaded.getString("severity").equals("ERROR"),
                "Loaded records were not schema-sanitized");
        check(
                DiagnosticRecord.sanitize("{\"type\":\"host.line\",\"private\":[[[[[0]]]]]}")
                        == null,
                "Nested payload accepted");
        String enormous = String.join("", Collections.nCopies(100000, "x"));
        check(
                DiagnosticRecord.safeLine('D', enormous).length() < 200,
                "Long input expanded retained data");
        for (String version : new String[] {"1.9", "1.9.1-beta1", "1.1.0-beta.19"}) {
            Map<String, Object> versions = new LinkedHashMap<>();
            versions.put("app_version", version);
            JSONObject versionEvent =
                    new JSONObject(DiagnosticRecord.structured("environment.sdk", versions));
            check(
                    version.equals(versionEvent.optString("app_version")),
                    "Numeric prerelease version is preserved");
        }
        System.out.println(
                "PASS: " + checks + " structured diagnostic privacy/classification checks");
    }
}
