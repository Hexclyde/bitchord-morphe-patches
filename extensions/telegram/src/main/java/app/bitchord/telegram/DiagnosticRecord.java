package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only fixed event names, bounded numbers and enumerated metadata leave the recorder. */
public final class DiagnosticRecord {
    private DiagnosticRecord() {}

    private static Set<String> set(String values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values.split(" "))));
    }

    private static final Set<String> TYPES =
            set(
                    "event host.line process.started process.restarted recording.started"
                        + " recording.stopped recording.expired environment.sdk webview.provider"
                        + " service.package activity.created activity.started activity.resumed"
                        + " activity.paused activity.stopped activity.destroyed login.view.created"
                        + " login.navigation.requested login.page.started login.page.finished"
                        + " login.history login.web.error login.http.error login.tls.error"
                        + " login.renderer.gone java.crash patch.event network.event playback.event"
                        + " source.event download.event");
    private static final Set<String> CATEGORIES =
            set(
                    "APP ACTIVITY SERVICE LOGIN WEBVIEW MICROG NETWORK PLAYER SOURCE DOWNLOAD"
                            + " LIBRARY CACHE TELEGRAM SOULSEEK PATCH OTHER");
    private static final Set<String> REASONS =
            set(
                    "OTHER RESOLVED SOURCE_PREPARATION SOURCE_SWITCH MICROG_REQUEST"
                        + " MICROG_UNAVAILABLE CANCELLED FAILED RENDERER_GONE SERVICE_DIED"
                        + " PROCESS_CRASH ERR_UNKNOWN_URL_SCHEME ERR_CLEARTEXT_NOT_PERMITTED"
                        + " ERR_NAME_NOT_RESOLVED ERR_CONNECTION_TIMED_OUT ERR_CONNECTION_REFUSED"
                        + " ERR_CONNECTION_RESET ERR_INTERNET_DISCONNECTED"
                        + " ERR_CERT_AUTHORITY_INVALID ERR_CERT_DATE_INVALID ERR_SSL_PROTOCOL_ERROR"
                        + " ERR_TOO_MANY_REDIRECTS ERR_ABORTED AUTOMIX_FAILED AUTOMIX_MEMORY"
                        + " AUTOMIX_READY AUTOMIX_DEFERRED AUTOMIX_PREVIEW_TIMING"
                        + " AUTOMIX_PREVIEW_FAILED");
    private static final Set<String> ANALYSIS_STAGES =
            set(
                    "starting restore native-restore memory-preview source-resolution"
                            + " decode-admission model-preflight format-probe bounded-DSP"
                            + " model-analysis result save before-decode after-decode unknown");
    private static final Pattern ANALYSIS_FIELD =
            Pattern.compile(
                    "(?:^|[:,]"
                        + " )(?:(stage|error)=([A-Za-z_-]{1,48})|(neededKiB|availableKiB)=([0-9]{1,10}))(?=$|[,;"
                        + " ])");
    private static final Pattern PREVIEW_FIELD =
            Pattern.compile(
                    "(?:^|"
                        + " )(ctorMs|outReadyMs|inReadyMs|outPlayMs|inPlayMs|stalls|stallMs)=(-?[0-9]{1,10})(?=$|"
                        + " )");
    private static final Pattern PREVIEW_END =
            Pattern.compile(
                    "(?:^|"
                        + " )end=(completed|stopped|error|setup-failed|queue-changed|main-playing|focus-lost|outgoing-paused|incoming-paused)(?=$|"
                        + " )");
    private static final Pattern PREVIEW_SOURCE =
            Pattern.compile("(?:^| )source=(none|outgoing|incoming)(?=$| )");
    private static final Pattern PREVIEW_CODE =
            Pattern.compile("(?:^| )code=(-?[0-9]{1,6})(?=$| )");
    private static final Pattern PREVIEW_CAUSE =
            Pattern.compile("(?:^| )cause=([A-Za-z-]{1,48})(?=$| )");
    private static final Pattern PREVIEW_ERROR =
            Pattern.compile("^AutoMix preview (?:setup )?failed: ([A-Za-z]+)(?=$|[@: ])");
    private static final Set<String> SOURCE_REASONS =
            set(
                    "PASSED DURATION_INSUFFICIENT DURATION_MISMATCH IDENTITY_MISMATCH"
                        + " NETWORK_TIMEOUT DNS_FAILURE CONNECT_FAILURE TLS_FAILURE IO_INTERRUPTED"
                        + " UNEXPECTED_EOF FILE_MISSING DECODE_FAILURE IO_UNCLASSIFIED"
                        + " INTERNAL_UNCLASSIFIED NONE STOP_UNSPECIFIED STOP_CLOSED"
                        + " STOP_INTERRUPTED STOP_PLAYBACK_STOPPED STOP_CANCELLED STOP_DEADLINE"
                        + " STOP_BYTE_LIMIT STOP_READER_RELEASED STOP_READ_PERMISSION STOP_PRIORITY"
                        + " STOP_MEMORY");
    private static final Pattern SOURCE_REASON =
            Pattern.compile("(?:^|; )reason=([A-Z_0-9]{1,40})(?:$|;| )");
    private static final Pattern CHECK_CONTEXT =
            Pattern.compile(
                    "(?:^| )(track|candidate|pair|attempt)=([A-Za-z0-9_-]{11}|[a-f0-9-]{36})(?=$|"
                            + " )");
    private static final Pattern CHECK_TIMING =
            Pattern.compile(" at (reference|length|window [123]|comparison) after ([0-9]{1,9}) ms");
    private static final Pattern MEMORY_FIELD =
            Pattern.compile(
                    "(?:^|,"
                        + " )(heapUsedMiB|heapMaxMiB|pendingMiB|playbackReserveMiB|dspAvailableMiB|systemFreeMiB|processRssMiB|nativeHeapMiB)=([0-9]{1,9})(?=$|[,"
                        + " (])");
    private static final Pattern WINDOWS =
            Pattern.compile(", ([0-9]{1,2}) of ([0-9]{1,2}) windows(?:;|$)");
    private static final Set<String> ERRORS =
            set(
                    "NONE IO TIMEOUT TLS SECURITY CANCELLED STATE ARGUMENT LINKAGE OUT_OF_MEMORY"
                            + " STACK_OVERFLOW OTHER");
    private static final Set<String> PACKAGES =
            set(
                    "app.revanced.android.gms com.google.android.gms com.android.vending"
                        + " com.google.android.webview com.android.webview"
                        + " com.google.android.trichromelibrary com.android.chrome com.chrome.beta"
                        + " com.chrome.dev org.chromium.webview com.music.bitchord"
                        + " com.music.bitchord.telegramtest com.music.bitchord.plus");
    private static final Set<String> ROUTES =
            set(
                    "google-account youtube-music play-store external-intent other-scheme other-web"
                            + " blank invalid unknown");
    private static final Set<String> BOOLEANS =
            set("main reload crashed visible connected available foreground enabled handled");
    private static final Pattern VERSION =
            Pattern.compile("[0-9]{1,6}(?:\\.[0-9]{1,6}){1,3}(?:-beta\\.?[0-9]{1,4})?");
    private static final Pattern HTTP =
            Pattern.compile("(?:HTTP(?:/1\\.[01]|/2)? |HTTP status[=: ]+)([1-5][0-9]{2})(?:\\b|$)");

    private static String enumValue(Object value, Set<String> allowed, String fallback) {
        return value instanceof String && ((String) value).length() <= 64 && allowed.contains(value)
                ? (String) value
                : fallback;
    }

    private static Set<String> reasons() {
        Set<String> all = new java.util.LinkedHashSet<>(REASONS);
        all.addAll(SOURCE_REASONS);
        return all;
    }

    public static String structured(String type, Map<String, ?> fields) {
        try {
            String safeType =
                    type != null && type.length() <= 64 && TYPES.contains(type) ? type : "event";
            String severity =
                    safeType.equals("java.crash")
                            ? "FATAL"
                            : safeType.endsWith(".error") || safeType.equals("login.renderer.gone")
                                    ? "ERROR"
                                    : "INFO";
            String category =
                    safeType.startsWith("activity.")
                            ? "ACTIVITY"
                            : safeType.startsWith("service.")
                                    ? "SERVICE"
                                    : safeType.startsWith("webview.")
                                                    || safeType.startsWith("login.")
                                                            && !safeType.equals(
                                                                    "login.view.created")
                                            ? "WEBVIEW"
                                            : safeType.startsWith("login.")
                                                    ? "LOGIN"
                                                    : safeType.startsWith("playback.")
                                                            ? "PLAYER"
                                                            : safeType.startsWith("network.")
                                                                    ? "NETWORK"
                                                                    : safeType.startsWith("source.")
                                                                            ? "SOURCE"
                                                                            : safeType.startsWith(
                                                                                            "download.")
                                                                                    ? "DOWNLOAD"
                                                                                    : safeType
                                                                                                    .startsWith(
                                                                                                            "patch.")
                                                                                            ? "PATCH"
                                                                                            : "APP";
            JSONObject out =
                    new JSONObject()
                            .put("type", safeType)
                            .put("severity", severity)
                            .put("category", category);
            if (fields == null) return out.toString();
            int examined = 0;
            for (Map.Entry<String, ?> field : fields.entrySet()) {
                if (++examined > 64) break;
                String key = field.getKey();
                Object value = field.getValue();
                if (key == null || key.length() > 32 || value == null) continue;
                switch (key) {
                    case "severity":
                        out.put(key, enumValue(value, set("DEBUG INFO WARN ERROR FATAL"), "INFO"));
                        break;
                    case "category":
                        out.put(key, enumValue(value, CATEGORIES, "OTHER"));
                        break;
                    case "phase":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set(
                                                "START END CREATE RESUME PAUSE STOP DESTROY REQUEST"
                                                        + " RESULT READY FAILED CANCELLED UNKNOWN"),
                                        "UNKNOWN"));
                        break;
                    case "reason":
                        out.put(key, enumValue(value, reasons(), "OTHER"));
                        break;
                    case "stage":
                        out.put(key, enumValue(value, ANALYSIS_STAGES, "unknown"));
                        break;
                    case "track":
                        if (value instanceof String
                                && ((String) value)
                                        .matches(
                                                "[A-Za-z0-9_-]{11}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                            out.put(key, value);
                        break;
                    case "candidate":
                    case "pair":
                    case "attempt":
                        if (value instanceof String
                                && ((String) value)
                                        .matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                            out.put(key, value);
                        break;
                    case "verification_stage":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set("reference length window1 window2 window3 comparison"),
                                        "unknown"));
                        break;
                    case "heapUsedMiB":
                    case "heapMaxMiB":
                    case "pendingMiB":
                    case "playbackReserveMiB":
                    case "dspAvailableMiB":
                    case "systemFreeMiB":
                    case "processRssMiB":
                    case "nativeHeapMiB":
                        integer(out, key, value, 0, 1 << 24);
                        break;
                    case "preview_source":
                        out.put(key, enumValue(value, set("none outgoing incoming"), "none"));
                        break;
                    case "preview_code":
                        integer(out, key, value, -1, 100000);
                        break;
                    case "ctorMs":
                    case "outReadyMs":
                    case "inReadyMs":
                    case "outPlayMs":
                    case "inPlayMs":
                    case "stallMs":
                        integer(out, key, value, -1, 3600000);
                        break;
                    case "stalls":
                        integer(out, key, value, 0, 100000);
                        break;
                    case "end":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set(
                                                "completed stopped error setup-failed queue-changed"
                                                        + " main-playing focus-lost outgoing-paused"
                                                        + " incoming-paused"),
                                        "error"));
                        break;
                    case "needed_kib":
                    case "available_kib":
                        integer(out, key, value, 0, 1 << 30);
                        break;
                    case "error":
                        out.put(key, enumValue(value, ERRORS, "OTHER"));
                        break;
                    case "kind":
                        out.put(key, enumValue(value, set("host patch other"), "other"));
                        break;
                    case "route":
                        out.put(key, enumValue(value, ROUTES, "unknown"));
                        break;
                    case "visibility":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set(
                                                "available unavailable visible hidden foreground"
                                                        + " background unknown"),
                                        "unknown"));
                        break;
                    case "provider":
                    case "package":
                        out.put(key, enumValue(value, PACKAGES, "OTHER"));
                        break;
                    case "patch":
                    case "app_version":
                    case "version":
                        if (value instanceof String
                                && ((String) value).length() <= 40
                                && VERSION.matcher((String) value).matches()) out.put(key, value);
                        break;
                    case "abi":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set("arm64-v8a armeabi-v7a x86_64 x86 unknown"),
                                        "unknown"));
                        break;
                    case "manufacturer":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set("samsung Google Xiaomi Waydroid unknown"),
                                        "unknown"));
                        break;
                    case "device_model":
                        if (value instanceof String
                                && (((String) value).matches("SM-[A-Z0-9]{4,12}")
                                        || ((String) value)
                                                .matches(
                                                        "Pixel [0-9]{1,2}(?:a| Pro| XL| Max| Fold|"
                                                                + " 5G){0,3}")))
                            out.put(key, value);
                        else out.put(key, "unknown");
                        break;
                    case "sdk":
                        integer(out, key, value, 16, 100);
                        break;
                    case "windows":
                    case "total_windows":
                        integer(out, key, value, 0, 64);
                        break;
                    case "progress":
                        integer(out, key, value, 0, 100);
                        break;
                    case "width":
                    case "height":
                        integer(out, key, value, 0, 16384);
                        break;
                    case "code":
                        integer(out, key, value, -65536, 65535);
                        break;
                    case "http_status":
                        integer(out, key, value, 100, 599);
                        break;
                    case "count":
                        integer(out, key, value, 0, 1000000);
                        break;
                    case "duration_ms":
                    case "elapsed_ms":
                        integer(out, key, value, 0, 3600000);
                        break;
                    case "at":
                        integer(out, key, value, 0, Long.MAX_VALUE);
                        break;
                    case "frame_line":
                        integer(out, key, value, 1, 1000000);
                        break;
                    case "frame":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set(
                                                "APPLICATION MAIN_ACTIVITY PLAYBACK_SERVICE"
                                                        + " MICROG_SIGN_IN DIAGNOSTICS"
                                                        + " DIAGNOSTICS_SCREEN PATCH_SETTINGS"
                                                        + " TELEGRAM_BRIDGE SOURCE_PREPARATION"
                                                        + " TRACK_LOG WEBVIEW OTHER"),
                                        "OTHER"));
                        break;
                    case "frame_method":
                        out.put(
                                key,
                                enumValue(
                                        value,
                                        set(
                                                "onCreate onStart onResume onPause onStop onDestroy"
                                                    + " onActivityResult init load viaMicroG"
                                                    + " requestToken configure update buildPlayer"
                                                    + " invokeSuspend other"),
                                        "other"));
                        break;
                    case "frame_category":
                        out.put(key, enumValue(value, CATEGORIES, "OTHER"));
                        break;
                    default:
                        if (BOOLEANS.contains(key) && value instanceof Boolean) out.put(key, value);
                }
            }
            return out.toString();
        } catch (Exception invalid) {
            return "{\"type\":\"event\"}";
        }
    }

    private static void integer(JSONObject out, String key, Object value, long min, long max)
            throws org.json.JSONException {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) return;
        long number = ((Number) value).longValue();
        if (number >= min && number <= max) out.put(key, number);
    }

    public static String sanitize(String encoded) {
        if (encoded == null || encoded.length() > DiagnosticStore.MAX_LINE) return null;
        try {
            String text = encoded.trim();
            if (!text.startsWith("{")
                    || !text.endsWith("}")
                    || text.indexOf('{', 1) >= 0
                    || text.indexOf('}') != text.length() - 1
                    || text.indexOf('[') >= 0
                    || text.indexOf(']') >= 0) return null;
            JSONObject input = new JSONObject(text);
            String type = input.optString("type", "");
            if (!TYPES.contains(type)) return null;
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Iterator<String> keys = input.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!key.equals("type")) fields.put(key, input.opt(key));
            }
            return structured(type, fields);
        } catch (Exception invalid) {
            return null;
        }
    }

    public static String safeLine(char severity, String message) {
        String text = message == null ? "" : message.substring(0, Math.min(4096, message.length()));
        String category = "APP", reason = "OTHER";
        if (text.startsWith("MicroG")) {
            category = "MICROG";
            if (text.contains("asking MicroG")) reason = "MICROG_REQUEST";
            else if (text.contains("cancelled")) reason = "CANCELLED";
            else if (text.contains("not offered")) reason = "MICROG_UNAVAILABLE";
            else if (text.contains("failed") || text.contains("couldn't")) reason = "FAILED";
        } else if (text.startsWith("Unofficial Telegram")) category = "TELEGRAM";
        else if (text.startsWith("Source preparation:")) {
            category = "SOURCE";
            reason = "SOURCE_PREPARATION";
        } else if (text.startsWith("substituted:")) {
            category = "SOURCE";
            reason = "SOURCE_SWITCH";
        } else if (text.startsWith("resolved ")) {
            category = "SOURCE";
            reason = "RESOLVED";
        } else if (text.startsWith("Soulseek") || text.startsWith("Streamseek"))
            category = "SOULSEEK";
        else if (text.startsWith("AutoMix") || text.startsWith("TIMING")) category = "PLAYER";
        else if (text.startsWith("Download")) category = "DOWNLOAD";
        for (String code : REASONS)
            if (code.startsWith("ERR_") && text.contains(code)) {
                category = "WEBVIEW";
                reason = code;
                break;
            }
        if (text.contains("renderer") || text.contains("RenderProcessGone")) {
            category = "WEBVIEW";
            reason = "RENDERER_GONE";
        }
        if (text.contains("DeadObjectException")) {
            category = "SERVICE";
            reason = "SERVICE_DIED";
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "severity",
                severity == 'E'
                        ? "ERROR"
                        : severity == 'W'
                                ? "WARN"
                                : severity == 'I' ? "INFO" : severity == 'F' ? "FATAL" : "DEBUG");
        values.put("category", category);
        values.put("reason", reason);
        if (text.startsWith("AutoMix ")) {
            if (text.startsWith("AutoMix will use a basic fade"))
                values.put("reason", "AUTOMIX_FAILED");
            else if (text.startsWith("AutoMix analysis is waiting for"))
                values.put("reason", "AUTOMIX_MEMORY");
            else if (text.startsWith("AutoMix measured")
                    || text.startsWith("AutoMix restored")
                    || text.startsWith("AutoMix reused")) values.put("reason", "AUTOMIX_READY");
            else if (text.startsWith("AutoMix analysis deferred"))
                values.put("reason", "AUTOMIX_DEFERRED");
            Matcher memory = MEMORY_FIELD.matcher(text);
            while (memory.find()) values.put(memory.group(1), Long.parseLong(memory.group(2)));
            Matcher field = ANALYSIS_FIELD.matcher(text);
            while (field.find()) {
                if ("stage".equals(field.group(1))) values.put("stage", field.group(2));
                else if ("error".equals(field.group(1)))
                    values.put("error", analysisError(field.group(2)));
                else if (field.group(3) != null)
                    values.put(
                            "neededKiB".equals(field.group(3)) ? "needed_kib" : "available_kib",
                            Long.parseLong(field.group(4)));
            }
        }
        if (text.startsWith("AutoMix preview timing: ")) {
            values.put("reason", "AUTOMIX_PREVIEW_TIMING");
            Matcher timing = PREVIEW_FIELD.matcher(text);
            while (timing.find()) values.put(timing.group(1), Long.parseLong(timing.group(2)));
            Matcher end = PREVIEW_END.matcher(text);
            if (end.find()) values.put("end", end.group(1));
            Matcher source = PREVIEW_SOURCE.matcher(text);
            if (source.find()) values.put("preview_source", source.group(1));
            Matcher code = PREVIEW_CODE.matcher(text);
            if (code.find()) values.put("preview_code", Long.parseLong(code.group(1)));
            Matcher cause = PREVIEW_CAUSE.matcher(text);
            if (cause.find()) {
                String kind = cause.group(1);
                values.put(
                        "error",
                        "none".equals(kind)
                                ? "NONE"
                                : "timeout".equals(kind)
                                        ? "TIMEOUT"
                                        : "deck-ended".equals(kind)
                                                ? "STATE"
                                                : analysisError(kind));
            }
        }
        Matcher previewError = PREVIEW_ERROR.matcher(text);
        if (previewError.find()) {
            values.put("reason", "AUTOMIX_PREVIEW_FAILED");
            values.put("error", analysisError(previewError.group(1)));
        }
        if (text.startsWith("Recording check ")) {
            values.put("category", "SOURCE");
            Matcher result = SOURCE_REASON.matcher(text);
            if (result.find()) {
                if (SOURCE_REASONS.contains(result.group(1))) values.put("reason", result.group(1));
                else if (result.group(1).matches("HTTP_[1-5][0-9]{2}")) {
                    values.put("reason", "FAILED");
                    values.put("http_status", Integer.parseInt(result.group(1).substring(5)));
                }
            }
            Matcher context = CHECK_CONTEXT.matcher(text);
            while (context.find()) values.put(context.group(1), context.group(2));
            Matcher timing = CHECK_TIMING.matcher(text);
            if (timing.find()) {
                values.put("verification_stage", timing.group(1).replace(" ", ""));
                values.put("elapsed_ms", Long.parseLong(timing.group(2)));
            }
            Matcher windows = WINDOWS.matcher(text);
            if (windows.find()) {
                values.put("windows", Integer.parseInt(windows.group(1)));
                values.put("total_windows", Integer.parseInt(windows.group(2)));
            }
        }
        Matcher status = HTTP.matcher(text);
        if (status.find()) values.put("http_status", Integer.parseInt(status.group(1)));
        return structured("host.line", values);
    }

    private static String analysisError(String value) {
        switch (value) {
            case "IOException":
            case "EOFException":
            case "FileNotFoundException":
                return "IO";
            case "SocketTimeoutException":
            case "TimeoutException":
                return "TIMEOUT";
            case "OutOfMemoryError":
                return "OUT_OF_MEMORY";
            case "IllegalStateException":
                return "STATE";
            case "IllegalArgumentException":
                return "ARGUMENT";
            case "SecurityException":
                return "SECURITY";
            case "UnsatisfiedLinkError":
            case "NoSuchMethodError":
            case "NoClassDefFoundError":
                return "LINKAGE";
            default:
                return "OTHER";
        }
    }

    public static String failure(Throwable error) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("severity", "FATAL");
        values.put("category", "APP");
        String kind = "OTHER";
        if (error instanceof OutOfMemoryError) kind = "OUT_OF_MEMORY";
        else if (error instanceof StackOverflowError) kind = "STACK_OVERFLOW";
        else if (error instanceof LinkageError) kind = "LINKAGE";
        else if (error instanceof SecurityException) kind = "SECURITY";
        else if (error instanceof CancellationException) kind = "CANCELLED";
        else if (error instanceof TimeoutException
                || error instanceof java.net.SocketTimeoutException) kind = "TIMEOUT";
        else if (error instanceof javax.net.ssl.SSLException) kind = "TLS";
        else if (error instanceof java.io.IOException) kind = "IO";
        else if (error instanceof IllegalArgumentException) kind = "ARGUMENT";
        else if (error instanceof IllegalStateException) kind = "STATE";
        values.put("error", kind);
        try {
            if (error != null && !(error instanceof OutOfMemoryError)) {
                StackTraceElement[] stack = error.getStackTrace();
                for (int at = 0; at < Math.min(32, stack.length); at++) {
                    String name = stack[at].getClassName();
                    String area =
                            name.startsWith("app.bitchord.")
                                    ? "PATCH"
                                    : name.startsWith("com.music.bitchord.")
                                            ? "APP"
                                            : name.startsWith("android.webkit.") ? "WEBVIEW" : null;
                    if (area == null) continue;
                    String base = name.split("\\$", 2)[0];
                    String frame;
                    switch (base) {
                        case "com.music.bitchord.BitChordApplication":
                            frame = "APPLICATION";
                            break;
                        case "com.music.bitchord.MainActivity":
                            frame = "MAIN_ACTIVITY";
                            break;
                        case "com.music.bitchord.playback.PlaybackService":
                            frame = "PLAYBACK_SERVICE";
                            break;
                        case "com.music.bitchord.data.TrackLog":
                            frame = "TRACK_LOG";
                            break;
                        case "app.bitchord.telegram.MicroGSignIn":
                            frame = "MICROG_SIGN_IN";
                            break;
                        case "app.bitchord.telegram.Diagnostics":
                            frame = "DIAGNOSTICS";
                            break;
                        case "app.bitchord.telegram.DiagnosticsActivity":
                            frame = "DIAGNOSTICS_SCREEN";
                            break;
                        case "app.bitchord.telegram.ExtrasActivity":
                            frame = "PATCH_SETTINGS";
                            break;
                        case "app.bitchord.telegram.TelegramBridge":
                            frame = "TELEGRAM_BRIDGE";
                            break;
                        case "app.bitchord.telegram.SourcePreparation":
                        case "app.bitchord.telegram.SourceFirst":
                        case "app.bitchord.telegram.SourcePreparationHost":
                            frame = "SOURCE_PREPARATION";
                            break;
                        case "android.webkit.WebView":
                            frame = "WEBVIEW";
                            break;
                        default:
                            frame = "OTHER";
                    }
                    values.put("frame", frame);
                    values.put("frame_category", area);
                    values.put("frame_method", stack[at].getMethodName());
                    values.put("frame_line", stack[at].getLineNumber());
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return structured("java.crash", values);
    }
}
