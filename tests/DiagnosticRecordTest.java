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
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        JSONObject proof =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "Recording check stopped at window 2 after 1234 ms, 3 of 6 windows;"
                                        + " reason=STOP_PRIORITY track=1aokooixKIo candidate="
                                        + id
                                        + " pair="
                                        + id
                                        + " attempt="
                                        + id));
        check(proof.getString("track").equals("1aokooixKIo"), "Track context preserved");
        check(
                proof.getString("candidate").equals(id) && proof.getString("attempt").equals(id),
                "Opaque identities preserved");
        check(
                proof.getString("verification_stage").equals("window2")
                        && proof.getLong("elapsed_ms") == 1234,
                "Stage and elapsed time preserved");
        check(
                proof.getString("reason").equals("STOP_PRIORITY"),
                "Priority distinguished from read failure");
        JSONObject lengths =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "Recording check stopped at window 2 after 1705 ms, 2 of 6 windows;"
                                    + " reason=SOURCE_NO_DATA;"
                                    + " cause=IOException>SocketTimeoutException;"
                                    + " measuredReferenceMs=253000 measuredCandidateMs=254200"
                                    + " differenceMs=1200 limitMs=3000 advertisedReferenceMs=253000"
                                    + " advertisedCandidateMs=253000 probe=HOST_CONTAINER"
                                    + " failingSide=candidate window=2 fromMs=118500 untilMs=134500"
                                    + " try=2 decodedSamples=705600 decodedRate=44100"
                                    + " firstTimestampUs=118500000 lastTimestampUs=134500000"
                                    + " secret=PRIVATEKEY"));
        check(
                lengths.getLong("decodedSamples") == 705600
                        && lengths.getLong("decodedRate") == 44100
                        && lengths.getLong("lastTimestampUs") == 134500000,
                "Decoded window measurements survive export");
        check(
                lengths.getLong("differenceMs") == 1200
                        && lengths.getLong("advertisedCandidateMs") == 253000,
                "Measured and advertised lengths stay distinct");
        check(
                lengths.getString("failingSide").equals("candidate")
                        && lengths.getLong("fromMs") == 118500
                        && lengths.getInt("window") == 2,
                "Failed read position survives export");
        check(
                lengths.getString("reason").equals("SOURCE_NO_DATA")
                        && lengths.getString("probe").equals("HOST_CONTAINER"),
                "Probe and read cause survive export");
        check(
                lengths.getString("causeChain").equals("IOException>SocketTimeoutException"),
                "Underlying failure classes survive export");
        check(
                !lengths.toString().contains("PRIVATEKEY"),
                "Measurement metadata excludes free text");
        check(
                DiagnosticRecord.sanitize(lengths.toString()).equals(lengths.toString()),
                "Measurement metadata survives archive validation");
        JSONObject unknownCause =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "Recording check stopped at length after 1 ms, 0 of 6 windows;"
                                    + " cause=PrivateAccountException; reason=IO_UNCLASSIFIED"));
        check(
                unknownCause.getString("causeChain").equals("OTHER")
                        && !unknownCause.toString().contains("PrivateAccount"),
                "Unknown exception names do not leak into diagnostics");
        JSONObject stages =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "AutoMix preview timing: tapMs=4 sessionMs=2 sessionReused=true"
                                    + " decksMs=20 seekMs=1 prepareMs=4 resolveMs=unavailable"
                                    + " verifyMs=unavailable ctorMs=29 outReadyMs=100 inReadyMs=120"
                                    + " outPlayMs=110 inPlayMs=400 stalls=0 stallMs=0"
                                    + " end=completed"));
        check(
                stages.getLong("decksMs") == 20 && stages.getBoolean("sessionReused"),
                "Preview construction and session reuse are exported");
        check(
                stages.getLong("resolveMs") == -1 && stages.getLong("verifyMs") == -1,
                "Unavailable preview timings never become zero latency");
        JSONObject budget =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "AutoMix analysis is waiting for memory: stage=before-decode,"
                                    + " neededKiB=36000, availableKiB=7400, heapUsedMiB=180,"
                                    + " heapMaxMiB=256, pendingMiB=12,"
                                    + " playbackReserveMiB=48(enforced), dspAvailableMiB=7,"
                                    + " systemFreeMiB=8192, processRssMiB=260, nativeHeapMiB=25"));
        check(
                budget.getLong("heapMaxMiB") == 256
                        && budget.getLong("systemFreeMiB") == 8192
                        && budget.getLong("dspAvailableMiB") == 7,
                "System RAM and app analysis allowance stay separate");
        JSONObject mapping =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "Telegram file mapping message=0956f5cf-8d08-3db3-895a-9d4e3aa1e76b"
                                    + " file=72 unique=67d0f73b-1e64-3127-b49b-200e1460a0e3"
                                    + " advertisedMs=253000 sizeBytes=40000000 token=PRIVATEKEY"));
        check(
                mapping.getString("reason").equals("TELEGRAM_FILE_MAPPING")
                        && mapping.getInt("file_number") == 72
                        && mapping.getLong("advertised_ms") == 253000,
                "Mapped file and advertised length survive export");
        check(
                mapping.has("message_identity")
                        && mapping.has("file_identity")
                        && !mapping.toString().contains("PRIVATEKEY"),
                "Only bounded mapping identities exported");
        String[] secrets = {
            "person@example.invalid",
            "+15551234567",
            "https://host.invalid/a?token=PRIVATEKEY",
            "password=PRIVATEPASSWORD",
            "cookie=PRIVATECOOKIE",
            "/home/private/account"
        };
        JSONObject analysis =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "AutoMix will use a basic fade until audio can be measured:"
                                        + " stage=bounded-DSP, error=IOException, site=analyze:73"
                                        + " private@example.invalid"));
        check("AUTOMIX_FAILED".equals(analysis.optString("reason")), "AutoMix failure lost");
        check("bounded-DSP".equals(analysis.optString("stage")), "AutoMix stage lost");
        check("IO".equals(analysis.optString("error")), "AutoMix error kind lost");
        check(!analysis.toString().contains("private@example.invalid"), "Analysis leaked content");
        JSONObject memory =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "AutoMix analysis is waiting for memory: stage=before-decode,"
                                        + " neededKiB=38767, availableKiB=0, decoded=false"));
        check("AUTOMIX_MEMORY".equals(memory.optString("reason")), "Memory wait lost");
        check(
                memory.optInt("needed_kib", -1) == 38767 && memory.optInt("available_kib", -1) == 0,
                "Bounded memory facts lost");
        check(
                DiagnosticRecord.sanitize(analysis.toString()).equals(analysis.toString()),
                "Export lost safe analysis details");
        for (String secret : secrets) {
            String output =
                    DiagnosticRecord.safeLine(
                            'D',
                            "AutoMix will use a basic fade until audio can be measured: stage="
                                    + secret
                                    + ", error="
                                    + secret
                                    + ", neededKiB="
                                    + secret);
            check(!output.contains(secret), "AutoMix field exported private text");
        }
        String previewLine =
                "AutoMix preview timing: ctorMs=23 outReadyMs=4010 inReadyMs=-1 outPlayMs=4010"
                        + " inPlayMs=-1 stalls=2 stallMs=800 end=stopped";
        JSONObject preview = new JSONObject(DiagnosticRecord.safeLine('D', previewLine));
        check("AUTOMIX_PREVIEW_TIMING".equals(preview.optString("reason")), "Preview timing lost");
        check(
                preview.optInt("outPlayMs") == 4010 && preview.optInt("inPlayMs") == -1,
                "Preview readiness lost");
        check(
                preview.optInt("stalls") == 2 && preview.optInt("stallMs") == 800,
                "Preview buffering lost");
        check("stopped".equals(preview.optString("end")), "Preview outcome lost");
        check(
                DiagnosticRecord.sanitize(preview.toString()).equals(preview.toString()),
                "Export lost preview timing");
        JSONObject previewFailure =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "AutoMix preview failed: IOException@private.example:32"
                                        + " token=secret"));
        check(
                "AUTOMIX_PREVIEW_FAILED".equals(previewFailure.optString("reason"))
                        && "IO".equals(previewFailure.optString("error")),
                "Preview failure kind lost");
        check(
                !previewFailure.toString().contains("private.example")
                        && !previewFailure.toString().contains("secret"),
                "Preview failure leaked details");
        JSONObject overflow =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "AutoMix preview timing: ctorMs=9999999999 outReadyMs=-2"
                                        + " stalls=9999999999 end=private"));
        check(
                !overflow.has("ctorMs")
                        && !overflow.has("outReadyMs")
                        && !overflow.has("stalls")
                        && !overflow.has("end"),
                "Unbounded preview values exported");
        for (String secret : secrets) {
            String output =
                    DiagnosticRecord.safeLine(
                            'D', "AutoMix preview timing: ctorMs=" + secret + " end=" + secret);
            check(!output.contains(secret), "Preview timing exported private text");
        }
        JSONObject stopped =
                new JSONObject(
                        DiagnosticRecord.safeLine(
                                'D',
                                "AutoMix preview timing: ctorMs=32 source=incoming code=2001"
                                        + " cause=IOException end=queue-changed"));
        check(
                "queue-changed".equals(stopped.optString("end"))
                        && "incoming".equals(stopped.optString("preview_source")),
                "Preview stop and deck details lost");
        check(
                stopped.optInt("preview_code") == 2001 && "IO".equals(stopped.optString("error")),
                "Preview decoder failure lost");
        check(
                DiagnosticRecord.sanitize(stopped.toString()).equals(stopped.toString()),
                "Preview failure export changed");
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
