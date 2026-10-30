package app.bitchord.soulseek;

import android.content.Context;

import com.music.bitchord.data.sources.MusicSource;
import com.music.bitchord.data.sources.SourceRegistry;
import com.music.bitchord.download.Downloads;
import com.music.bitchord.ui.components.BottomTab;
import com.music.bitchord.ui.icons.BitChordIcons;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;

import java.lang.reflect.Field;
import java.util.*;

public final class DownloadExplorerTest {
    private static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    public static final class Song {
        public final String videoId;

        Song(String id) {
            videoId = "src:fixture::" + id;
        }
    }

    public static final class Source implements MusicSource {
        boolean asynchronous;

        public String getConfigId() {
            return "fixture";
        }

        public String getDisplayName() {
            return "Fixture source";
        }

        public String getKind() {
            return "ADDON";
        }

        public Object search(
                String query,
                int limit,
                boolean all,
                Object request,
                Continuation<Object> continuation) {
            check(
                    query.equals("artist title") && limit == 12 && all && request == null,
                    "registry search preserves bounded query and source identity");
            List<Song> result = Collections.singletonList(new Song("matched"));
            if (!asynchronous) return result;
            new Thread(() -> continuation.resumeWith(result)).start();
            return IntrinsicsKt.getCOROUTINE_SUSPENDED();
        }
    }

    @SuppressWarnings("unchecked")
    static void navigation(Context context) throws Exception {
        List<BottomTab> original =
                Arrays.asList(
                        new BottomTab("Home", null),
                        new BottomTab("Explore", null),
                        new BottomTab("Library", null),
                        new BottomTab("Search", null));
        List<BottomTab> tabs = (List<BottomTab>) DownloadNavigation.tabs(original);
        check(
                tabs.size() == 5 && tabs.get(2).label.equals("Downloads"),
                "one Downloads tab inserted between Explore and Library");
        check(tabs.get(2).icon == BitChordIcons.ICON, "native downward download glyph reused");
        check(
                tabs.get(3) == original.get(2) && tabs.get(4) == original.get(3),
                "original tabs retain identity and order");
        check(
                DownloadNavigation.tabs(original) == tabs,
                "recomposition retains stable list identity");
        check(DownloadNavigation.tabs(tabs) == tabs, "restart cannot duplicate a navigation item");
        for (int i = 0; i < 4; i++)
            check(
                    DownloadNavigation.index(original, i) == (i < 2 ? i : i + 1),
                    "selection number maps correctly");
        check(DownloadNavigation.index(tabs, 4) == 4, "already mapped selection is idempotent");
        List<Integer> selected = new ArrayList<>();
        Function1<Object, Object> originalClick =
                n -> {
                    selected.add(((Number) n).intValue());
                    return Unit.INSTANCE;
                };
        Function1<Object, Object> click =
                (Function1<Object, Object>) DownloadNavigation.callback(original, originalClick);
        check(
                DownloadNavigation.callback(original, originalClick) == click,
                "callback identity stable for recomposition");
        click.invoke(2);
        check(
                context.launches == 1 && selected.isEmpty(),
                "Downloads opens manager without navigating the host");
        click.invoke(0);
        click.invoke(1);
        click.invoke(3);
        click.invoke(4);
        check(
                selected.equals(Arrays.asList(0, 1, 2, 3)),
                "all host routes receive their original numbers");
        check(
                DownloadNavigation.callback(tabs, click) == click,
                "already wrapped click is not wrapped twice");
    }

    @SuppressWarnings("unchecked")
    static void sourceBinding(Context context) throws Exception {
        Song first = new Song("first"), second = new Song("second"), now = new Song("now");
        check(
                !HostDownloadAccess.forbidFallback(first),
                "ordinary rows retain existing fallback policy");
        HostDownloadAccess.enqueue(context, first, "fixture", false);
        HostDownloadAccess.enqueue(context, second, "fixture", false);
        HostDownloadAccess.enqueue(context, now, "fixture", true);
        check(
                new ArrayList<>(Downloads.pending.keySet())
                        .equals(Arrays.asList(now.videoId, first.videoId, second.videoId)),
                "download now takes next slot; queued files preserve FIFO");
        check(
                HostDownloadAccess.forbidFallback(first),
                "explicit source selection cannot enter a different provider's fallback walk");
        Field pins = HostDownloadAccess.class.getDeclaredField("pinned");
        pins.setAccessible(true);
        ((Set<?>) pins.get(null)).clear();
        Field loaded = HostDownloadAccess.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.set(null, false);
        HostDownloadAccess.init(context);
        check(
                HostDownloadAccess.forbidFallback(first),
                "source binding survives a new process state");
        int before = Downloads.pending.size();
        SourceRegistry.CONFIG.allowed = false;
        try {
            HostDownloadAccess.enqueue(context, new Song("blocked"), "fixture", false);
            throw new AssertionError("streaming-only source accepted");
        } catch (java.io.IOException expected) {
        }
        SourceRegistry.CONFIG.allowed = true;
        SourceRegistry.CONFIG.enabled = false;
        try {
            HostDownloadAccess.enqueue(context, new Song("disabled"), "fixture", false);
            throw new AssertionError("disabled source accepted");
        } catch (java.io.IOException expected) {
        }
        SourceRegistry.CONFIG.enabled = true;
        try {
            HostDownloadAccess.enqueue(context, new Song("wrong"), "other", false);
            throw new AssertionError("mismatched provider accepted");
        } catch (java.io.IOException expected) {
        }
        context.prefs.success = false;
        try {
            HostDownloadAccess.enqueue(context, new Song("unsaved"), "fixture", false);
            throw new AssertionError("unrecorded source binding accepted");
        } catch (java.io.IOException expected) {
        }
        check(
                Downloads.pending.size() == before,
                "refused selections cannot enqueue or fall through");
        context.prefs.success = true;
        Source source = new Source();
        check(
                HostDownloadAccess.search(source, "artist title").size() == 1,
                "synchronous registry search returns its selected rows");
        source.asynchronous = true;
        check(
                HostDownloadAccess.search(source, "artist title").size() == 1,
                "suspended registry search resumes without Android UI blocking");
        HostDownloadAccess.cancel(first.videoId);
        check(
                !Downloads.pending.containsKey(first.videoId),
                "cancel reaches the real host identifier");
    }

    public static void main(String[] args) throws Exception {
        Context context = new Context();
        SoulseekBridge.instance = new SoulseekBridge(context);
        navigation(context);
        sourceBinding(context);
        System.out.println("PASS: " + checks + " integrated download navigation/source checks");
    }
}
