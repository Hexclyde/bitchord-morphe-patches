package app.bitchord.telegram;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Build;

import java.lang.reflect.Field;

/**
 * JamesDSP: BitChord's audio session is handed to JamesDSP, the open-source audio effect engine, so
 * its equaliser, convolver, crossfeed and the rest work on BitChord's music. Works with the
 * rootless app (which hears BitChord through Android's audio capture) and with the root or Magisk
 * version. BitChord already announces its audio session to effect apps; this makes sure JamesDSP
 * sees it, opens JamesDSP from BitChord, and can step BitChord's own effects aside.
 */
final class EffectsApps {
    private EffectsApps() {}

    static final String PREFERENCES = "bitchord_effects_apps";
    static final String ROOTLESS = "me.timschneeberger.rootlessjamesdsp";
    static final String ROOT = "james.dsp";
    static final String GET_IT =
            "https://f-droid.org/packages/me.timschneeberger.rootlessjamesdsp/";

    static final String SHIZUKU = "moe.shizuku.privileged.api";
    static final String GET_SHIZUKU = "https://shizuku.rikka.app/download/";

    /** The rootless JamesDSP's power switch, which it lets other apps use. */
    static final String POWER = "me.timschneeberger.rootlessjamesdsp.SET_POWER_STATE";

    static final String POWER_ENABLED = "rootlessjamesdsp.enabled";

    private static volatile SharedPreferences preferences;

    /** With this on BitChord's equaliser is left to JamesDSP and Battery saver stays off. */
    static volatile boolean useJamesDsp;

    static void init(Context context) {
        if (preferences != null || context == null) return;
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        useJamesDsp = preferences.getBoolean("useJamesDsp", false);
    }

    static void setUseJamesDsp(boolean on) {
        useJamesDsp = on;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("useJamesDsp", on).apply();
    }

    /** The JamesDSP package that's installed, or null. */
    static String installed(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String name : new String[] {ROOTLESS, ROOT})
            try {
                pm.getPackageInfo(name, 0);
                return name;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not this one.
            }
        return null;
    }

    static String describe(String installedPackage) {
        if (installedPackage == null) return Strings.t("JamesDSP isn’t installed");
        return ROOT.equals(installedPackage)
                ? Strings.t("JamesDSP (root) is installed")
                : Strings.t("JamesDSP (rootless) is installed");
    }

    /** Opens JamesDSP, or where to get it. */
    static void open(Context context) {
        String name = installed(context);
        Intent intent =
                name == null ? null : context.getPackageManager().getLaunchIntentForPackage(name);
        if (intent == null) intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GET_IT));
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            HostLog.d("JamesDSP couldn't be opened: " + e);
        }
    }

    /** BitChord's audio session id, as its service published it; 0 when nothing has played. */
    static int sessionId() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.data.settings.AppSettings");
            Field field = type.getDeclaredField("audioSessionId");
            field.setAccessible(true);
            Object flow = field.get(null);
            Object value = flow == null ? null : flow.getClass().getMethod("getValue").invoke(flow);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Exception | LinkageError e) {
            return 0;
        }
    }

    /**
     * Tells effect apps about BitChord's audio session again, as BitChord does when playback
     * starts. For JamesDSP installed while a song was already playing.
     */
    static boolean announce(Context context) {
        int session = sessionId();
        if (session <= 0) return false;
        Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        context.sendBroadcast(intent);
        HostLog.d("Announced audio session " + session + " to effect apps");
        return true;
    }

    /** Turns BitChord's own equaliser off, so the sound isn't equalised twice. */
    static boolean hostEqualiserOff() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.data.settings.AppSettings");
            Object settings = type.getDeclaredField("INSTANCE").get(null);
            type.getMethod("setEqualizerEnabled", boolean.class).invoke(settings, false);
            return true;
        } catch (Exception | LinkageError e) {
            HostLog.d("Couldn't turn BitChord's equaliser off: " + e);
            return false;
        }
    }

    static boolean hostEqualiserOn(Context context) {
        return context.getSharedPreferences(OutputEq.HOST_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean("equalizer_enabled", false);
    }

    // ── The rootless JamesDSP ───────────────────────────────────────────────

    /**
     * Whether the rootless JamesDSP has finished its one-time setup: the DUMP permission it uses to
     * find which app is playing, and permanent leave to capture sound, both granted over Shizuku or
     * ADB. The same two checks JamesDSP makes on itself.
     */
    static boolean rootlessReady(Context context) {
        PackageManager pm = context.getPackageManager();
        if (pm.checkPermission("android.permission.DUMP", ROOTLESS)
                != PackageManager.PERMISSION_GRANTED) return false;
        try {
            int uid = pm.getApplicationInfo(ROOTLESS, 0).uid;
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops == null
                    || ops.unsafeCheckOpNoThrow("android:project_media", uid, ROOTLESS)
                            == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            // Android didn't say; the DUMP permission is the part that needs Shizuku or ADB.
            return true;
        }
    }

    static boolean shizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * True while some app is capturing the sound that plays. Android doesn't say which app, so this
     * is only a hint: the rootless JamesDSP processes music this way, and so would a screen
     * recorder.
     */
    static boolean somethingCapturing(Context context) {
        if (Build.VERSION.SDK_INT < 29) return false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return false;
        try {
            for (AudioRecordingConfiguration recording : audio.getActiveRecordingConfigurations())
                if (recording.getClientAudioSource() == MediaRecorder.AudioSource.REMOTE_SUBMIX)
                    return true;
        } catch (RuntimeException e) {
            HostLog.d("Couldn't read Android's recordings: " + e);
        }
        return false;
    }

    /** Turns the rootless JamesDSP on or off through its power switch. */
    static void power(Context context, boolean on) {
        Intent intent = new Intent(POWER).setPackage(ROOTLESS).putExtra(POWER_ENABLED, on);
        context.sendBroadcast(intent);
        HostLog.d("Asked JamesDSP to turn " + (on ? "on" : "off"));
    }

    /** The commands that do the rootless JamesDSP's setup from a computer, for ADB users. */
    static String adbCommands() {
        return adbCommands(ROOTLESS);
    }

    static String adbCommands(String pkg) {
        return "adb shell pm grant "
                + pkg
                + " android.permission.DUMP\n"
                + "adb shell appops set "
                + pkg
                + " PROJECT_MEDIA allow\n"
                + "adb shell appops set "
                + pkg
                + " SYSTEM_ALERT_WINDOW allow\n";
    }

    /** Opens Shizuku, or where to get it. */
    static void openShizuku(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU);
        if (intent == null) intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GET_SHIZUKU));
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            HostLog.d("Shizuku couldn't be opened: " + e);
        }
    }

    /**
     * What's known about JamesDSP and BitChord's sound right now, most important first. Each part
     * says only what Android lets BitChord see.
     */
    static String status(Context context, String installedPackage) {
        if (installedPackage == null) return Strings.t("JamesDSP isn’t installed");
        if (BitPerfect.playing())
            return Strings.t(
                    "Bit-perfect output is sending this song straight to the DAC, where JamesDSP"
                            + " can’t hear it.");
        if (SoundExtras.batterySaver && !useJamesDsp)
            return Strings.t(
                    "Battery saver can send songs to the audio chip, where JamesDSP can’t hear"
                            + " them. Turn on Let JamesDSP do the effects.");
        if (ROOT.equals(installedPackage))
            return Strings.t(
                    "JamesDSP (root) works on the audio session BitChord announces when a song"
                            + " starts.");
        if (!rootlessReady(context))
            return Strings.t(
                    "JamesDSP hasn’t finished its setup. It needs Shizuku or ADB once: open"
                            + " JamesDSP and follow its steps, or use the commands below.");
        if (somethingCapturing(context))
            return Strings.t(
                    "JamesDSP is set up, and an app is capturing what plays. JamesDSP works this"
                            + " way, so it’s most likely processing BitChord now.");
        return Strings.t("JamesDSP is set up but isn’t capturing sound now. Start it below.");
    }
}
