package app.bitchord.telegram;

import kotlin.coroutines.intrinsics.CoroutineSingletons;
import kotlin.coroutines.jvm.internal.ContinuationImpl;

import java.util.concurrent.atomic.AtomicReference;

/** The audio and video switch: which songs it offers, and how it waits for BitChord's lookups. */
public final class VideoSwitchTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(VideoSwitch.youTubeId("dQw4w9WgXcQ"), "a YouTube id");
        check(VideoSwitch.youTubeId("a-b_c1D2e3F"), "dashes and underscores");
        check(!VideoSwitch.youTubeId("tg:12345678"), "a Telegram track");
        check(!VideoSwitch.youTubeId("local:abcdef"), "a file");
        check(!VideoSwitch.youTubeId("dQw4w9WgXc"), "too short");
        check(!VideoSwitch.youTubeId("dQw4w9WgXcé"), "not ASCII");
        check(!VideoSwitch.youTubeId(null), "no id");

        // A lookup that answers at once.
        check("now".equals(VideoSwitch.await(c -> "now")), "an answer without waiting");

        // A lookup that suspends and is resumed from another thread, as BitChord's network calls
        // are.
        AtomicReference<ContinuationImpl> held = new AtomicReference<>();
        Thread resumer =
                new Thread(
                        () -> {
                            while (held.get() == null) Thread.yield();
                            held.get().resumeWith("later");
                        });
        resumer.start();
        Object later =
                VideoSwitch.await(
                        c -> {
                            held.set(c);
                            return CoroutineSingletons.COROUTINE_SUSPENDED;
                        });
        check("later".equals(later), "an answer after suspending: " + later);

        // A failed lookup reads as no version.
        Object failure = kotlin.ResultKt.createFailure(new RuntimeException("offline"));
        check(VideoSwitch.await(c -> failure) == null, "a failure");

        Object song =
                new Object() {
                    final String videoId = "dQw4w9WgXcQ";
                    final boolean isVideo = true;
                };
        check("dQw4w9WgXcQ".equals(VideoSwitch.string(song, "videoId")), "reads the id");
        check(VideoSwitch.bool(song, "isVideo"), "reads isVideo");
        check(VideoSwitch.string(song, "localUri") == null, "a missing field");
        System.out.println("PASS: " + checks + " audio and video switch checks.");
    }
}
