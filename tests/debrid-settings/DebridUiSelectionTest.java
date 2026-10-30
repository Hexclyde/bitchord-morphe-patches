package app.bitchord.soulseek;

import android.widget.CheckBox;
import android.widget.LinearLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebridUiSelectionTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AudioFormatPreferences.Settings defaults = AudioFormatPreferences.defaults();
        AudioFormatPreferences.Settings flacOnly =
                new AudioFormatPreferences.Settings(
                        defaults.order, EnumSet.of(AudioFormatPreferences.Format.FLAC), true);
        android.content.Context.policy = AudioFormatPreferences.encode(flacOnly);
        DebridModels.Entry flac = new DebridModels.Entry("17", "Album/Artist - Track.flac", 1024);
        DebridModels.Entry mp3 = new DebridModels.Entry("18", "Artist - Track.mp3", 1024);
        DebridModels.Entry video = new DebridModels.Entry("19", "clip.mkv", 1024);
        DebridModels.Entry tooLarge =
                new DebridModels.Entry("20", "huge.flac", DebridModels.MAX_BYTES + 1);
        check(DebridDownloadsActivity.selectable(flac, flacOnly), "Enabled audio is selectable");
        check(!DebridDownloadsActivity.selectable(mp3, flacOnly), "Disabled audio format excluded");
        check(!DebridDownloadsActivity.selectable(video, flacOnly), "Video excluded");
        check(!DebridDownloadsActivity.selectable(tooLarge, flacOnly), "Oversized file excluded");
        check(
                !DebridDownloadsActivity.selectable(
                        new DebridModels.Entry("21", "empty.flac", 0), flacOnly),
                "Empty file excluded");
        check(
                DebridDownloadsActivity.selectable(
                        new DebridModels.Entry("22", "unknown-size.flac", -1), flacOnly),
                "Unknown size remains bounded during actual transfer");

        DebridModels.Resolved pending =
                new DebridModels.Resolved(
                        DebridProvider.Kind.REAL_DEBRID,
                        "123",
                        "magnet:?xt=urn:btih:0123456789012345678901234567890123456789",
                        Arrays.asList(flac, mp3, video, tooLarge),
                        "waiting_files_selection",
                        "Choose the files to download.",
                        false);
        Constructor<DebridDownloads.Prepared> constructor =
                DebridDownloads.Prepared.class.getDeclaredConstructor(
                        String.class, DebridModels.Resolved.class, String.class);
        constructor.setAccessible(true);
        DebridDownloads.Prepared prepared =
                constructor.newInstance(
                        "01234567890123456789012345678901", pending, "fixture-revision");
        DebridDownloadsActivity activity = new DebridDownloadsActivity();
        Field results = DebridDownloadsActivity.class.getDeclaredField("results");
        results.setAccessible(true);
        results.set(activity, new LinearLayout(activity));
        Method show =
                DebridDownloadsActivity.class.getDeclaredMethod(
                        "showPrepared", DebridDownloads.Prepared.class);
        show.setAccessible(true);
        show.invoke(activity, prepared);
        Field selections = DebridDownloadsActivity.class.getDeclaredField("selections");
        selections.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, CheckBox> rows = (Map<String, CheckBox>) selections.get(activity);
        check(
                rows.size() == 1 && rows.containsKey("17"),
                "Pending provider metadata filters to enabled audio IDs");
        check(
                rows.get("17").isEnabled(),
                "Real-Debrid waiting_files_selection remains selectable before ready");
        check(
                !prepared.ready && prepared.state.equals("waiting_files_selection"),
                "Selecting pending metadata does not claim provider download readiness");
        rows.get("17").setChecked(true);
        check(rows.get("17").isChecked(), "User can explicitly choose the exact waiting file ID");

        android.content.Context.home = Files.createTempDirectory("debrid-ui-reopen-").toFile();
        DebridCredentials vault =
                new DebridCredentials(
                        new DebridCredentials.Storage() {
                            public byte[] read() {
                                return null;
                            }

                            public void write(byte[] bytes) {}

                            public void clear() {}
                        },
                        new DebridCredentials.Crypto() {
                            public byte[] seal(byte[] bytes) {
                                return bytes.clone();
                            }

                            public byte[] open(byte[] bytes) {
                                return bytes.clone();
                            }
                        });
        Field credentialsInstance = DebridCredentials.class.getDeclaredField("instance");
        credentialsInstance.setAccessible(true);
        credentialsInstance.set(null, vault);
        DebridDownloads engine =
                new DebridDownloads(activity, vault, new DebridHttp(), Collections.emptyMap());
        Field engineInstance = DebridDownloads.class.getDeclaredField("instance");
        engineInstance.setAccessible(true);
        engineInstance.set(null, engine);
        Field controller = DebridDownloadsActivity.class.getDeclaredField("downloads");
        controller.setAccessible(true);
        controller.set(activity, engine);
        Field notice = DebridDownloadsActivity.class.getDeclaredField("notice");
        notice.setAccessible(true);
        notice.set(activity, new android.widget.TextView(activity));
        for (String name : new String[] {"jobs", "recent"}) {
            Field field = DebridDownloadsActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, new LinearLayout(activity));
        }
        engine.stop();
        Field stopped = DebridDownloads.class.getDeclaredField("stopped");
        stopped.setAccessible(true);
        check(stopped.getBoolean(engine), "Explicit Stop closes the existing engine");
        Method render = DebridDownloadsActivity.class.getDeclaredMethod("renderJobs");
        render.setAccessible(true);
        render.invoke(activity);
        check(
                stopped.getBoolean(engine),
                "Periodic status rendering does not reopen a stopped engine");
        CountDownLatch acted = new CountDownLatch(1);
        AtomicBoolean reopened = new AtomicBoolean();
        Method action =
                DebridDownloadsActivity.class.getDeclaredMethod(
                        "runAction", DebridDownloadsActivity.Action.class);
        action.setAccessible(true);
        action.invoke(
                activity,
                (DebridDownloadsActivity.Action)
                        () -> {
                            reopened.set(!stopped.getBoolean(engine));
                            acted.countDown();
                            return "Recovered";
                        });
        check(
                acted.await(3, TimeUnit.SECONDS),
                "An explicit UI action reaches its controller after Stop");
        check(reopened.get(), "Explicit UI action reacquires and reopens the preserved engine");
        check(
                DebridDownloads.existing() == engine,
                "Recovery preserves the existing preparation/job identity");
        Field worker = DebridDownloadsActivity.class.getDeclaredField("worker");
        worker.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) worker.get(activity);
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);
        engine.stop();
        engineInstance.set(null, null);
        credentialsInstance.set(null, null);
        System.out.println(
                "PASS: " + checks + " debrid audio selection and pending-provider UI checks.");
    }
}
