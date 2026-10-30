package app.bitchord.soulseek;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.Toast;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Real source choice and continuation ownership; no host queue or network is simulated. */
public final class SourceChoiceTest {
    static int checks;

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static final class Song {
        final String id;

        Song(String id) {
            this.id = id;
        }

        public String getVideoId() {
            return id;
        }

        public String getArtist() {
            return "Fixture artist";
        }

        public String getTitle() {
            return "Fixture song";
        }
    }

    static final class Continuation implements Function2<Object, Object, Object> {
        int calls;
        boolean fail;
        Object songs, from;

        public Object invoke(Object songs, Object from) {
            calls++;
            this.songs = songs;
            this.from = from;
            if (fail) throw new IllegalStateException("fixture original callback refused");
            return Unit.INSTANCE;
        }
    }

    static Context reset() throws Exception {
        Field field = SoulseekDownloadChoice.class.getDeclaredField("pending");
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).clear();
        Handler.delayed.clear();
        SystemClock.now = 1;
        Toast.last = "";
        DownloadSettings.on = true;
        Context context = new Context();
        SoulseekBridge.instance = new SoulseekBridge(context);
        return context;
    }

    static List<Song> one() {
        return Collections.singletonList(new Song("abcDEF123_-"));
    }

    static String token(Context context) {
        return context.launched
                .get(context.launched.size() - 1)
                .getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
    }

    static void eligibilityAndPassthrough() throws Exception {
        Context app = reset();
        check(
                SoulseekDownloadChoice.eligible(one(), null),
                "eligible manual single YouTube identifier");
        for (String id :
                new String[] {
                    "src:soulseek-native::" + String.format("%064x", 1),
                    "src:telegram-native::catalog",
                    "src:user-addon::row",
                    "local:device-file",
                    "short",
                    "https://youtube.invalid/video"
                }) {
            Continuation original = new Continuation();
            List<Song> songs = Collections.singletonList(new Song(id));
            check(
                    SoulseekDownloadChoice.offer(original, songs, null) == Unit.INSTANCE,
                    "non-owned source result returned unchanged");
            check(
                    original.calls == 1 && original.songs == songs && original.from == null,
                    "native/local/other source passes original arguments exactly once");
        }
        Continuation album = new Continuation();
        List<Song> many = Arrays.asList(new Song("abcDEF123_-"), new Song("ZYXwvut9876"));
        Object from = new Object();
        SoulseekDownloadChoice.offer(album, many, from);
        check(
                album.calls == 1 && album.songs == many && album.from == from,
                "album preserves entire original continuation and collection metadata");
        Continuation collectionOne = new Continuation();
        List<Song> songs = one();
        SoulseekDownloadChoice.offer(collectionOne, songs, from);
        check(
                collectionOne.calls == 1 && collectionOne.from == from,
                "single-track collection preserves collection semantics");
        Continuation malformed = new Continuation();
        SoulseekDownloadChoice.offer(malformed, null, null);
        check(
                malformed.calls == 1 && malformed.songs == null,
                "unrecognized callback arguments stay with original handler");
        check(app.launched.isEmpty(), "passthrough never creates a source-choice page");
        DownloadSettings.on = false;
        Continuation disabled = new Continuation();
        SoulseekDownloadChoice.offer(disabled, one(), null);
        check(
                disabled.calls == 0 && app.launched.size() == 1,
                "disabled Soulseek still permits an explicit addon download choice");
        SoulseekBridge.instance = null;
        Continuation beforeBoot = new Continuation();
        SoulseekDownloadChoice.offer(beforeBoot, one(), null);
        check(beforeBoot.calls == 1, "unavailable integration does not break original player");
    }

    static void oneUseAndCancellation() throws Exception {
        Context app = reset();
        Continuation original = new Continuation();
        List<Song> songs = one();
        SoulseekDownloadChoice.offer(original, songs, null);
        check(
                original.calls == 0 && app.launched.size() == 1,
                "choice holds the manual request before original queue admission");
        Intent launch = app.launched.get(0);
        check(
                launch.target == DownloadExplorerActivity.class,
                "choice opens the private all-source download explorer");
        check(
                "Fixture artist Fixture song"
                        .equals(launch.getStringExtra(SoulseekDownloadChoice.EXTRA_QUERY)),
                "only editable artist/title query is prefilled");
        String token = token(app);
        check(SoulseekDownloadChoice.available(token), "held request is available during lease");
        check(
                SoulseekDownloadChoice.resume(token, app),
                "explicit original choice resumes continuation");
        check(
                original.calls == 1
                        && ((List<?>) original.songs).get(0) == songs.get(0)
                        && original.from == null,
                "same song identity reaches unchanged original single-song continuation");
        check(
                !SoulseekDownloadChoice.resume(token, app) && original.calls == 1,
                "original continuation is consumed once");

        Continuation cancelled = new Continuation();
        SoulseekDownloadChoice.offer(cancelled, one(), null);
        String discarded = token(app);
        SoulseekDownloadChoice.forget(discarded);
        check(
                !SoulseekDownloadChoice.available(discarded)
                        && !SoulseekDownloadChoice.resume(discarded, app),
                "cancelled request cannot be resumed");
        check(cancelled.calls == 0, "cancel/back never falls through to original download");
        check(
                !SoulseekDownloadChoice.available("lost-after-process-restart")
                        && !SoulseekDownloadChoice.resume("lost-after-process-restart", app),
                "lost process request cannot invent a continuation");
    }

    static void leaseBoundsAndFailure() throws Exception {
        Context app = reset();
        Continuation expired = new Continuation();
        SoulseekDownloadChoice.offer(expired, one(), null);
        String token = token(app);
        SystemClock.now += 120_000;
        check(
                !SoulseekDownloadChoice.available(token)
                        && !SoulseekDownloadChoice.resume(token, app),
                "request expires at two-minute boundary");
        check(expired.calls == 0, "lease expiry never starts a fallback download");
        app = reset();
        for (int i = 0; i < 8; i++) SoulseekDownloadChoice.offer(new Continuation(), one(), null);
        check(app.launched.size() == 8, "at most eight requests can be held");
        Continuation ninth = new Continuation();
        SoulseekDownloadChoice.offer(ninth, one(), null);
        check(
                app.launched.size() == 8 && ninth.calls == 0,
                "ninth request is visibly refused without automatic fallback");
        check(Toast.last.contains("Finish"), "capacity refusal is visible");
        SystemClock.now += 120_000;
        SoulseekDownloadChoice.offer(new Continuation(), one(), null);
        check(app.launched.size() == 9, "expired requests release bounded capacity");
        app = reset();
        app.fail = true;
        Continuation failedLaunch = new Continuation();
        SoulseekDownloadChoice.offer(failedLaunch, one(), null);
        check(
                failedLaunch.calls == 0 && app.launched.isEmpty(),
                "page launch failure cannot silently download original source");
        check(Toast.last.contains("Could not open"), "page launch failure gives retry feedback");
    }

    static void thrownOriginalRunsOnce() throws Exception {
        reset();
        DownloadSettings.on = false;
        SoulseekBridge.instance = null;
        Continuation disabled = new Continuation();
        disabled.fail = true;
        try {
            SoulseekDownloadChoice.offer(disabled, one(), null);
            throw new AssertionError("Original exception must propagate to its caller");
        } catch (IllegalStateException expected) {
            check(
                    disabled.calls == 1,
                    "disabled-source original exception cannot invoke continuation twice");
        }
        reset();
        SoulseekBridge.instance = null;
        Continuation unavailable = new Continuation();
        unavailable.fail = true;
        try {
            SoulseekDownloadChoice.offer(unavailable, one(), null);
            throw new AssertionError("Original exception must propagate to its caller");
        } catch (IllegalStateException expected) {
            check(
                    unavailable.calls == 1,
                    "unavailable-bridge original exception cannot invoke continuation twice");
        }
        Context app = reset();
        Continuation resumed = new Continuation();
        resumed.fail = true;
        SoulseekDownloadChoice.offer(resumed, one(), null);
        String token = token(app);
        check(
                !SoulseekDownloadChoice.resume(token, app),
                "explicit original failure reports failure");
        check(
                resumed.calls == 1 && !SoulseekDownloadChoice.resume(token, app),
                "failed explicit original is consumed and never retried automatically");
    }

    public static void main(String[] args) throws Exception {
        eligibilityAndPassthrough();
        oneUseAndCancellation();
        leaseBoundsAndFailure();
        thrownOriginalRunsOnce();
        System.out.println("Manual source choice: " + checks + " assertions passed (no network)");
    }
}
