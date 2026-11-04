import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks source controls. Args: base.apk patchedApkOrDexDir [--combined]. */
public class VerifyYouTubeControlsApk {
    static final String POLICY = "Lapp/bitchord/telegram/YouTubePolicy;";
    static final String FALLBACK = "Lapp/bitchord/telegram/SourceFallback;";
    static final String RANKED =
            "Lcom/music/bitchord/data/sources/SourceRegistry;->activeForPlayback()Ljava/util/ArrayList;";
    static final String SWAPPED = FALLBACK + "->playbackSources()Ljava/util/ArrayList;";
    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static Map<String, ClassDef> load(File f) throws Exception {
        Map<String, ClassDef> out = new HashMap<>();
        if (f.isDirectory()) {
            for (File d : f.listFiles())
                if (d.getName().endsWith(".dex"))
                    for (ClassDef c :
                            DexFileFactory.loadDexFile(d, Opcodes.getDefault()).getClasses())
                        out.put(c.getType(), c);
        } else {
            var box = DexFileFactory.loadDexContainer(f, Opcodes.getDefault());
            for (String e : box.getDexEntryNames())
                for (ClassDef c : box.getEntry(e).getDexFile().getClasses())
                    out.put(c.getType(), c);
        }
        return out;
    }

    static String show(Instruction i) {
        return i.getOpcode()
                + " "
                + (i instanceof ReferenceInstruction r ? r.getReference() : "")
                + (i instanceof OneRegisterInstruction o ? " r" + o.getRegisterA() : "")
                + (i instanceof NarrowLiteralInstruction n ? " #" + n.getNarrowLiteral() : "");
    }

    static List<String> text(Method m) {
        List<String> l = new ArrayList<>();
        if (m.getImplementation() != null)
            for (Instruction i : m.getImplementation().getInstructions()) l.add(show(i));
        return l;
    }

    static Method method(Map<String, ClassDef> dex, String type, String name, int params) {
        ClassDef c = dex.get(type);
        if (c == null) return null;
        for (Method m : c.getMethods())
            if (m.getName().equals(name) && m.getParameterTypes().size() == params) return m;
        return null;
    }

    static boolean has(List<String> body, String needle) {
        for (String s : body) if (s.contains(needle)) return true;
        return false;
    }

    static Method named(Map<String, ClassDef> dex, String type, String name, String... params) {
        ClassDef c = dex.get(type);
        if (c == null) return null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().size() != params.length)
                continue;
            boolean same = true;
            for (int k = 0; k < params.length; k++)
                if (!m.getParameterTypes().get(k).toString().equals(params[k])) same = false;
            if (same) return m;
        }
        return null;
    }

    static String fieldType(Map<String, ClassDef> dex, String type, String name) {
        ClassDef c = dex.get(type);
        if (c == null) return null;
        for (Field f : c.getFields()) if (f.getName().equals(name)) return f.getType();
        return null;
    }

    static int count(List<String> body, String needle) {
        int n = 0;
        for (String s : body) if (s.contains(needle)) n++;
        return n;
    }

    /** True when the ranking call is followed within 20 instructions by the YouTube comparison. */
    static boolean ranksYouTube(List<String> body, String call) {
        for (int k = 0; k < body.size(); k++)
            if (body.get(k).contains(call))
                for (int j = k + 1; j < Math.min(body.size(), k + 21); j++)
                    if (body.get(j).contains("SourceKind;->YOUTUBE")) return true;
        return false;
    }

    static void bitchordNativeFacts(Map<String, ClassDef> base) {
        String reg = "Lcom/music/bitchord/data/sources/SourceRegistry;";
        String player = "Landroidx/media3/common/Player;";
        String target = "Lcom/music/bitchord/data/sources/TrackMatcher$Target;";
        String cont = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;";
        String service = "Lcom/music/bitchord/playback/PlaybackService;";
        String local = "Lcom/music/bitchord/data/LocalMediaRepository;";
        String module = "Lcom/music/bitchord/playback/PlaybackService$Resolved$Module;";
        String song = "Lcom/music/bitchord/data/model/Song;";

        check(named(base, reg, "activeForPlayback") != null, "SourceRegistry.activeForPlayback()");
        check(named(base, reg, "active") != null, "SourceRegistry.active()");
        check(
                "Ljava/lang/Object;".equals(fieldType(base, reg, "instances")),
                "SourceRegistry.instances is the erased source map");
        check(
                named(base, "Lcom/music/bitchord/data/sources/MusicSource;", "getConfigId") != null,
                "MusicSource.getConfigId()");
        Method make =
                named(base, module, "<init>", "Lcom/music/bitchord/data/sources/SourceStream;");
        check(
                make != null && (make.getAccessFlags() & 1) != 0,
                "Resolved$Module(SourceStream) is public");
        check(
                named(
                                base,
                                "Lcom/music/bitchord/data/sources/SourceResolver;",
                                "substituteForYouTube",
                                target,
                                cont)
                        != null,
                "SourceResolver.substituteForYouTube(Target, ContinuationImpl)");
        check(
                "Lcom/music/bitchord/data/sources/SourceResolver;"
                        .equals(
                                fieldType(
                                        base,
                                        "Lcom/music/bitchord/data/sources/SourceResolver;",
                                        "INSTANCE")),
                "SourceResolver.INSTANCE");
        check(
                named(
                                base,
                                "Lcom/music/bitchord/download/Downloads;",
                                "verifiedSavedUri",
                                "Ljava/lang/String;")
                        != null,
                "Downloads.verifiedSavedUri(String)");
        check(
                named(
                                base,
                                service,
                                "access$resolveWithModulePriority",
                                service,
                                "Ljava/lang/String;",
                                target,
                                cont)
                        != null,
                "PlaybackService.access$resolveWithModulePriority(service, String, Target,"
                        + " ContinuationImpl)");
        for (String[] sig :
                new String[][] {
                    {"stop"},
                    {"prepare"},
                    {"play"},
                    {"getPlaybackState"},
                    {"getCurrentMediaItemIndex"},
                    {"getCurrentPosition"},
                    {"replaceMediaItem", "I", "Landroidx/media3/common/MediaItem;"},
                    {"seekTo", "I", "J"}
                }) {
            check(
                    named(base, player, sig[0], Arrays.copyOfRange(sig, 1, sig.length)) != null,
                    "Player." + sig[0] + " exists");
        }
        check(
                named(
                                base,
                                "Landroidx/media3/common/Player$Listener;",
                                "onPlayerError",
                                "Landroidx/media3/common/PlaybackException;")
                        != null,
                "Player.Listener.onPlayerError(PlaybackException)");
        check(
                named(base, "Landroidx/media3/common/MediaItem;", "buildUpon") != null
                        && named(
                                        base,
                                        "Landroidx/media3/common/MediaItem$Builder;",
                                        "setUri",
                                        "Ljava/lang/String;")
                                != null
                        && named(base, "Landroidx/media3/common/MediaItem$Builder;", "build")
                                != null,
                "MediaItem.buildUpon(), Builder.setUri(String) and Builder.build() exist");
        check(
                named(
                                base,
                                "Landroidx/media3/common/MediaItem$Builder;",
                                "setUri",
                                "Landroid/net/Uri;")
                        == null,
                "Builder.setUri(Uri) is stripped, so the swap goes through setUri(String)");

        String localMetadata = "Landroidx/media3/common/MediaMetadata;";
        String localBuilder = "Landroidx/media3/common/MediaMetadata$Builder;";
        boolean metadataExtras = false, itemMetadata = false;
        for (Field field : base.get(localBuilder).getFields())
            metadataExtras |=
                    field.getName().equals("extras")
                            && field.getType().equals("Landroid/os/Bundle;");
        for (Field field : base.get("Landroidx/media3/common/MediaItem$Builder;").getFields())
            itemMetadata |=
                    field.getName().equals("mediaMetadata")
                            && field.getType().equals("Ljava/lang/Object;");
        check(
                named(base, localMetadata, "buildUpon") != null
                        && named(base, localMetadata, "<init>", localBuilder) != null
                        && metadataExtras
                        && itemMetadata,
                "Local playback can copy metadata and bind its actual file URI");
        boolean localMime = false;
        for (Field field : base.get("Landroidx/media3/common/MediaItem$Builder;").getFields())
            localMime |=
                    field.getName().equals("mimeType")
                            && field.getType().equals("Ljava/lang/Object;");
        check(localMime, "Local file playback can clear a previous manifest MIME hint");
        List<String> itemBuild =
                text(named(base, "Landroidx/media3/common/MediaItem$Builder;", "build"));
        int metadataRead = -1;
        for (int k = 0; k < itemBuild.size(); k++)
            if (itemBuild.get(k).contains("->mediaMetadata:Ljava/lang/Object;")) metadataRead = k;
        check(
                metadataRead >= 0
                        && metadataRead + 1 < itemBuild.size()
                        && itemBuild.get(metadataRead + 1).contains("CHECK_CAST " + localMetadata),
                "Erased metadata field is cast to MediaMetadata when built");
        Method localSong =
                named(
                        base,
                        "Lcom/music/bitchord/playback/PlayerConnectionKt;",
                        "toSong",
                        "Landroidx/media3/common/MediaItem;");
        check(
                localSong != null && has(text(localSong), "bitchord.localUri"),
                "Native song reconstruction reads the local-file metadata");

        // Device music: a MediaStore query on a worker with no cache of its own.
        Method list = named(base, local, "getLocalMusic", "Landroid/content/Context;", cont);
        check(list != null, "LocalMediaRepository.getLocalMusic(Context, ContinuationImpl)");
        check(
                named(base, local, "hasStoragePermission", "Landroid/content/Context;") != null,
                "LocalMediaRepository.hasStoragePermission(Context)");
        List<String> listBody = list == null ? new ArrayList<>() : text(list);
        check(
                has(listBody, "Dispatchers;->Default") && has(listBody, "JobKt;->withContext"),
                "getLocalMusic switches to a background dispatcher");
        List<String> worker = null;
        for (Method m : base.get("Lcom/music/bitchord/wear/WatchInstaller$refresh$1;").getMethods())
            if (m.getName().equals("invokeSuspend")) worker = text(m);
        check(
                worker != null
                        && has(worker, local + "->hasStoragePermission(")
                        && has(worker, "ContentResolver;->query(")
                        && has(worker, "MediaStore$Audio$Media;->EXTERNAL_CONTENT_URI")
                        && has(worker, "AppSettings;->localMusicFolderUri")
                        && has(worker, local + "->isEligibleLocalMusic$app_prodRelease("),
                "the library worker checks permission, queries MediaStore and applies the folder"
                        + " and music filters");
        Set<String> statics = new TreeSet<>();
        for (Field f : base.get(local).getStaticFields()) statics.add(f.getName());
        check(
                !statics.contains("cache") && statics.size() == 4,
                "LocalMediaRepository keeps no library of its own " + statics);
        Set<String> songFields = new TreeSet<>();
        for (Field f : base.get(song).getInstanceFields())
            if (f.getType().equals("Ljava/lang/String;")) songFields.add(f.getName());
        check(
                songFields.containsAll(
                        Arrays.asList("title", "artist", "durationText", "localUri", "videoId")),
                "Song carries title, artist, durationText, localUri and videoId as fields");
        Set<String> targetFields = new TreeSet<>();
        for (Field f : base.get(target).getInstanceFields()) targetFields.add(f.getName());
        check(
                targetFields.containsAll(Arrays.asList("title", "artist", "durationSec")),
                "TrackMatcher$Target carries title, artist and durationSec as fields");
    }

    /** The five ranking checks, found from the dex the way the patch finds them. */
    static List<Method> sites(Map<String, ClassDef> dex, List<String> names) {
        String service = "Lcom/music/bitchord/playback/PlaybackService;";
        String resolver = "Lcom/music/bitchord/data/sources/SourceResolver;";
        String target = "Lcom/music/bitchord/data/sources/TrackMatcher$Target;";
        String cont = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;";
        String spec = "Landroidx/media3/datasource/DataSpec;";
        List<Method> out = new ArrayList<>();
        names.clear();
        String keys = fieldType(dex, "Lcom/music/bitchord/playback/AudioCache;", "keyFactory");
        out.add(named(dex, keys, "buildCacheKey", spec));
        names.add("cache key");
        out.add(named(dex, resolver, "substituteForYouTube", target, cont));
        names.add("substitute");
        out.add(named(dex, resolver, "prefetchSubstitute", target, cont));
        names.add("prefetch substitute");
        out.add(named(dex, fieldType(dex, service, "streamResolver"), "resolveDataSpec", spec));
        names.add("resolver");
        out.add(named(dex, service, "prefetchAround", "Landroidx/media3/exoplayer/ExoPlayer;"));
        names.add("read-ahead");
        return out;
    }

    public static void main(String[] a) throws Exception {
        Map<String, ClassDef> base = load(new File(a[0])), now = load(new File(a[1]));
        String reg = "Lcom/music/bitchord/data/sources/SourceRegistry;";
        String res = "Lcom/music/bitchord/data/innertube/StreamResolver;";
        String inn = "Lcom/music/bitchord/data/innertube/Innertube;";

        List<String> changed = new ArrayList<>();
        for (var e : now.entrySet()) {
            ClassDef b = base.get(e.getKey());
            if (b == null) continue;
            if (!e.getKey().startsWith("Lcom/music/bitchord/")
                    || e.getKey().contains("ApiModelOutline")
                    || e.getKey().contains("ExternalSyntheticOutline")) continue;
            boolean same = true;
            Map<String, Method> old = new HashMap<>();
            for (Method m : b.getMethods()) old.put(m.getName() + m.getParameterTypes(), m);
            for (Method m : e.getValue().getMethods()) {
                Method o = old.get(m.getName() + m.getParameterTypes());
                if (o == null || !text(o).equals(text(m))) same = false;
            }
            if (!same) changed.add(e.getKey());
        }
        Collections.sort(changed);
        if (a.length < 3 || !"--combined".equals(a[2]))
            check(changed.size() == 9, "exactly nine BitChord classes change " + changed);

        Method set = method(now, reg, "setEnabled", 2);
        List<String> s = text(set);
        int ask = -1;
        for (int k = 0; k < s.size(); k++)
            if (s.get(k).contains(POLICY + "->confirmDisable")) ask = k;
        check(
                ask > 0
                        && s.get(ask + 2).startsWith("IF_NEZ")
                        && s.get(ask + 3).startsWith("RETURN_VOID"),
                "setEnabled asks before its YouTube exit, which stays");
        if (ask >= 0) {
            List<Instruction> body = new ArrayList<>();
            set.getImplementation().getInstructions().forEach(body::add);
            Instruction question = body.get(ask);
            Instruction resumed = body.get(ask + 4);
            check(
                    resumed.getOpcode() == Opcode.SGET_OBJECT
                            && body.get(ask + 1) instanceof OneRegisterInstruction result
                            && resumed instanceof OneRegisterInstruction target
                            && result.getRegisterA() == target.getRegisterA(),
                    "Source confirmation preserves live registers before resuming the native list");
            int argument =
                    question instanceof RegisterRangeInstruction range
                            ? range.getStartRegister()
                            : question instanceof FiveRegisterInstruction registers
                                    ? registers.getRegisterC()
                                    : -1;
            check(
                    argument == set.getImplementation().getRegisterCount() - 2,
                    "Source confirmation receives the source id even with high parameter"
                            + " registers");
        }
        check(
                s.size() == text(method(base, reg, "setEnabled", 2)).size() + 3,
                "setEnabled gains only the question");

        Method loader = null;
        for (ClassDef c : now.values())
            for (Method m : c.getMethods())
                if (has(text(m), POLICY + "->init(") && has(text(m), reg + "->publish("))
                    loader = m;
        check(
                has(
                        text(
                                method(
                                        now,
                                        "Lcom/music/bitchord/BitChordApplication;",
                                        "onCreate",
                                        0)),
                        POLICY + "->init("),
                "application startup arms source loading before background requests");
        check(loader != null, "saved sources loader opens YouTubePolicy");
        List<String> l = text(loader);
        int youtube = -1;
        for (int k = 0; k < l.size(); k++)
            if (l.get(k).contains("SourceKind;->YOUTUBE")) {
                youtube = k;
                break;
            }
        int read = -1;
        for (int k = youtube; k < l.size(); k++)
            if (l.get(k).contains("getEnabled")) {
                read = k;
                break;
            }
        check(
                l.get(read + 1).startsWith("CONST_16")
                        && l.get(read + 1).endsWith("#1")
                        && l.get(read + 2).startsWith("IF_NEZ"),
                "start-up repair no longer sees YouTube as off");
        int init = -1, open = -1;
        for (int k = 0; k < l.size(); k++) {
            if (l.get(k).contains(POLICY + "->init(")) init = k;
            if (l.get(k).contains("EncryptedPrefs;->open(")) open = k;
        }
        check(init >= 0 && open == init + 1, "init runs right before the saved store opens");

        int cards = 0;
        for (ClassDef c : now.values())
            for (Method m : c.getMethods()) {
                List<String> t = text(m);
                if (has(t, "SearchScreenKt;->SourceRow")
                        && c.getType().contains("SourcesScreenKt")) {
                    boolean was = false;
                    Method o = method(base, c.getType(), m.getName(), m.getParameterTypes().size());
                    if (o != null && has(text(o), "SourceKind;->YOUTUBE")) was = true;
                    if (was) {
                        cards++;
                        check(
                                !has(t, "SourceKind;->YOUTUBE"),
                                "Sources card no longer special-cases YouTube");
                    }
                }
            }
        check(cards == 1, "one Sources card changed (" + cards + ")");

        String[][] gates = {
            {inn, "withRetry", "3", "catalogueGate"},
            {inn, "postMusic", "5", "catalogueGate"},
            {inn, "fetchVisitorData", "1", "catalogueGate"}
        };
        for (String[] g : gates) {
            List<String> t = text(method(now, g[0], g[1], Integer.parseInt(g[2])));
            check(t.get(0).contains(POLICY + "->" + g[3]), g[1] + " starts with " + g[3]);
        }

        // The audible boundary: the one DataSource.open above the cache, after the watch_read
        // branch.
        String wrapper = "Lcom/music/bitchord/playback/AudioCache$playbackFactory$1$1;";
        List<String> was = text(method(base, wrapper, "open", 1));
        List<String> o = text(method(now, wrapper, "open", 1));
        String sourceOpen = "DataSource;->open(Landroidx/media3/datasource/DataSpec;)J";
        int watchRead = -1;
        for (int k = 0; k < was.size(); k++) if (was.get(k).contains("watch_read")) watchRead = k;
        int before = 0, after = 0;
        for (int k = 0; k < was.size(); k++)
            if (was.get(k).contains(sourceOpen)) {
                if (k < watchRead) before++;
                else after++;
            }
        check(
                before == 1 && after == 1,
                "base: one open before watch_read (smb), one after (every other scheme)");
        int calls = 0, routed = -1;
        for (int k = 0; k < o.size(); k++) {
            if (o.get(k).contains(sourceOpen)) calls++;
            if (o.get(k)
                    .contains(
                            POLICY
                                    + "->open(Landroidx/media3/datasource/DataSource;Landroidx/media3/datasource/DataSpec;)J"))
                routed = k;
            if (o.get(k).contains("Lapp/bitchord/telegram/SourceFirst;->open(")) {
                Method hop = method(now, "Lapp/bitchord/telegram/SourceFirst;", "open", 3);
                // Without the extension merged in, the call itself is the evidence.
                if (hop == null) {
                    routed = k;
                    continue;
                }
                List<String> route = text(hop);
                check(has(route, POLICY + "->open("), "source preparation enforces source policy");
                check(!has(route, sourceOpen), "source preparation has no direct open bypass");
                if (has(route, POLICY + "->open(")) routed = k;
            }
        }
        check(
                calls == 1 && routed > 0 && o.get(routed + 1).startsWith("MOVE_RESULT_WIDE"),
                "the open after watch_read enforces source policy; smb stays direct");
        check(
                has(o, "watch_read")
                        && has(o, "automix_opus")
                        && has(o, "QualityUpgrade;->cacheTag"),
                "tagged variants (quality, watch_read, automix_opus) still branch above that open");

        // Native facts the policy relies on, read from the base dex.
        List<String> uri =
                text(
                        method(
                                base,
                                "Landroidx/media3/datasource/cache/CacheDataSource;",
                                "getUri",
                                0));
        check(
                uri.size() == 2
                        && uri.get(0).contains("CacheDataSource;->actualUri")
                        && uri.get(1).startsWith("RETURN_OBJECT"),
                "CacheDataSource.getUri returns the address actually read (redirect or upstream)");
        Method resolver = null;
        for (ClassDef c : base.values())
            for (Method m : c.getMethods())
                if (m.getName().equals("resolveDataSpec")
                        && has(text(m), "bc_origin")
                        && has(text(m), "googlevideo.com")
                        && has(text(m), "saavncdn.com")
                        && has(text(m), "AudioCache$Origin;->YOUTUBE")) resolver = m;
        check(
                resolver != null,
                "native resolver records bc_origin from the resolved host, not the track id");

        // Loader: loaded() directly after the one publish; resolver and downloads untouched.
        int publish = -1, done = -1;
        for (int k = 0; k < l.size(); k++) {
            if (l.get(k).contains(reg + "->publish")) publish = k;
            if (l.get(k).contains(POLICY + "->loaded()")) done = k;
        }
        check(
                publish > 0 && done == publish + 1,
                "loaded() runs right after the saved sources are published");
        for (String[] g :
                new String[][] {
                    {res, "resolve", "2"},
                    {res, "access$resolveUncached", "2"},
                    {res, "resolveForDownload", "4"}
                }) {
            Method m = method(now, g[0], g[1], Integer.parseInt(g[2]));
            check(m != null && !has(text(m), POLICY), g[1] + " untouched");
        }
        check(
                has(
                        text(
                                method(
                                        now,
                                        "Lcom/music/bitchord/BitChordApplication;",
                                        "onCreate",
                                        0)),
                        POLICY + "->init("),
                "Application.onCreate opens YouTubePolicy");
        // Audio that is already open: one watch call before the player is returned, one notice per
        // publish.
        String service = "Lcom/music/bitchord/playback/PlaybackService;";
        ClassDef serviceClass = now.get(service);
        Method built = null;
        for (Method m : serviceClass.getMethods())
            if (m.getName().equals("buildPlayer")
                    && m.getReturnType().startsWith("Landroidx/media3/exoplayer/")) built = m;
        List<String> b = text(built);
        int watch = -1;
        for (int k = 0; k < b.size(); k++)
            if (b.get(k).contains(POLICY + "->observe(Landroidx/media3/common/Player;)V"))
                watch = k;
        check(
                watch > 1
                        && b.get(watch - 2).contains("ExoPlayer$Builder;->build()")
                        && b.get(watch + 1).startsWith("RETURN_OBJECT"),
                "buildPlayer hands the built player to YouTubePolicy.observe right before returning"
                        + " it");
        int returns = 0;
        for (String line : b) if (line.startsWith("RETURN_OBJECT")) returns++;
        check(returns == 1, "buildPlayer has one return, so every player is watched");
        List<String> published = text(method(now, reg, "publish", 2));
        int update = -1, notice = -1;
        for (int k = 0; k < published.size(); k++) {
            if (published.get(k).contains("StateFlowImpl;->updateState")) update = k;
            if (published.get(k).contains(POLICY + "->sourcesChanged()V")) notice = k;
        }
        check(
                update >= 0 && notice == update + 1,
                "publish tells the policy right after the new flags are stored");

        // ABI the live check depends on, read from the base dex.
        ClassDef player = base.get("Landroidx/media3/common/Player;");
        for (String[] sig :
                new String[][] {
                    {"pause", "0"},
                    {"getPlayWhenReady", "0"},
                    {"getCurrentMediaItem", "0"},
                    {"getApplicationLooper", "0"},
                    {"addListener", "1"}
                }) {
            check(
                    method(
                                    base,
                                    "Landroidx/media3/common/Player;",
                                    sig[0],
                                    Integer.parseInt(sig[1]))
                            != null,
                    "Player." + sig[0] + " exists");
        }
        ClassDef listener = base.get("Landroidx/media3/common/Player$Listener;");
        boolean order = false, ready = false;
        for (Method m : listener.getMethods()) {
            String types = m.getParameterTypes().toString();
            if (m.getName().equals("onPlayWhenReadyChanged") && types.equals("[I, Z]"))
                ready = true;
            if (m.getName().equals("onMediaItemTransition")
                    && types.equals("[I, Landroidx/media3/common/MediaItem;]")) order = true;
        }
        check(
                ready && order,
                "Player.Listener has the reordered (int, boolean) and (int, MediaItem) callbacks"
                        + " the host uses");
        boolean implementsPlayer = false;
        for (String t = "Landroidx/media3/exoplayer/ExoPlayerImpl;";
                t != null && base.get(t) != null;
                t = base.get(t).getSuperclass())
            for (String i : base.get(t).getInterfaces()) {
                if (i.equals("Landroidx/media3/common/Player;")) implementsPlayer = true;
                ClassDef face = base.get(i);
                while (face != null && !implementsPlayer) {
                    if (face.getInterfaces().contains("Landroidx/media3/common/Player;"))
                        implementsPlayer = true;
                    face =
                            face.getInterfaces().isEmpty()
                                    ? null
                                    : base.get(face.getInterfaces().get(0));
                }
            }
        check(implementsPlayer, "the built ExoPlayerImpl is a Player");
        boolean nobody = true;
        for (ClassDef c : base.values())
            for (Method m : c.getMethods())
                for (String line : text(m))
                    if (line.contains("SourceRegistry;->configs")
                            && c.getType().startsWith("Lcom/music/bitchord/playback/"))
                        nobody = false;
        check(
                nobody,
                "base playback code never reads the source flags, so nothing stops open audio on"
                        + " its own");

        // Final exhaustion must not be retried: BitChord's retry delay treats a
        // FileNotFoundException anywhere in the cause chain as final.
        Method retry =
                named(
                        base,
                        "Landroidx/media3/exoplayer/hls/HlsManifest;",
                        "getRetryDelayMsFor",
                        "Lokio/PriorityQueue;");
        List<String> delay = retry == null ? new ArrayList<>() : text(retry);
        int notFound = -1, parser = -1, causes = -1;
        for (int k = 0; k < delay.size(); k++) {
            if (delay.get(k).contains("INSTANCE_OF")
                    && delay.get(k).contains("java/io/FileNotFoundException")) notFound = k;
            if (delay.get(k).contains("INSTANCE_OF") && delay.get(k).contains("ParserException"))
                parser = k;
            if (delay.get(k).contains("Throwable;->getCause()")) causes = k;
        }
        check(
                notFound > parser
                        && parser >= 0
                        && causes > notFound
                        && delay.get(notFound + 1).startsWith("IF_NEZ")
                        && has(delay, "Math;->min(II)I"),
                "the retry delay walks the cause chain, ends the retries for FileNotFoundException"
                        + " and backs off other read errors");
        check(
                named(base, "Landroidx/media3/common/Player;", "getPlayerError") != null,
                "Player.getPlayerError() exists");
        String resolverType = "Lcom/music/bitchord/data/sources/SourceResolver;";
        Method now0 = named(base, resolverType, "requestForNow");
        check(
                now0 != null
                        && (now0.getAccessFlags() & 8) != 0
                        && now0.getReturnType()
                                .equals("Lcom/music/bitchord/data/sources/StreamRequest;"),
                "SourceResolver.requestForNow() is static");
        check(
                "Lcom/music/bitchord/data/sources/StreamRequest$Best;"
                                .equals(
                                        fieldType(
                                                base,
                                                "Lcom/music/bitchord/data/sources/StreamRequest$Best;",
                                                "INSTANCE"))
                        && base.containsKey(
                                "Lcom/music/bitchord/data/sources/StreamRequest$Lossless;"),
                "StreamRequest$Best.INSTANCE and StreamRequest$Lossless exist");
        Method across = null;
        for (Method m : base.get(resolverType).getMethods())
            if (m.getName().equals("bestAcross$app_prodRelease$default")) across = m;
        check(
                across != null
                        && (across.getAccessFlags() & 8) != 0
                        && across.getParameterTypes().size() == 11
                        && across.getParameterTypes().get(0).toString().equals(resolverType)
                        && across.getParameterTypes().get(1).toString().equals("Ljava/util/List;")
                        && across.getParameterTypes()
                                .get(3)
                                .toString()
                                .equals("Lcom/music/bitchord/data/sources/StreamRequest;")
                        && across.getParameterTypes().get(10).toString().equals("I"),
                "SourceResolver.bestAcross$app_prodRelease$default(resolver, List, Target, request,"
                        + " ...mask)");
        List<String> substitute =
                text(
                        named(
                                base,
                                resolverType,
                                "substituteForYouTube",
                                "Lcom/music/bitchord/data/sources/TrackMatcher$Target;",
                                "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"));
        check(
                has(substitute, "#1016")
                        && has(substitute, "SourceResolver;->requestForNow()")
                        && has(substitute, "SourceResolver;->bestAcross$app_prodRelease$default(")
                        && !has(substitute, "StreamRequest$Best;->INSTANCE"),
                "native substitute asks once with the current request and mask 1016, never with"
                        + " Best");
        int bestUsers = 0;
        for (ClassDef c : base.values())
            if (c.getType().startsWith("Lcom/music/bitchord/data/sources/SourceResolver$bestAcross")
                    || c.getType()
                            .startsWith(
                                    "Lcom/music/bitchord/data/sources/SourceResolver$streamBest"))
                for (Method m : c.getMethods())
                    if (has(text(m), "StreamRequest$Best;->INSTANCE")) bestUsers++;
        check(
                bestUsers == 0,
                "native bestAcross and streamBest never retry with Best by themselves");
        // The open above the cache: the one hook point that runs before YouTube's resolver.
        check(
                has(o, POLICY + "->open(") || has(o, "SourceFirst;->open("),
                "the open above the cache runs YouTubePolicy.open before the cache data source");

        // While YouTube Music is off: other sources, saved files and music library files.
        bitchordNativeFacts(base);
        List<String> names = new ArrayList<>();
        List<Method> sitesBase = sites(base, names), sitesNow = sites(now, new ArrayList<>());
        String[] marks = {
            "#alt",
            "rankedAbove",
            "rankedAbove",
            "Stream resolution timed out for ",
            "getNextMediaItemIndex"
        };
        int natives = 0, swapped = 0;
        for (int k = 0; k < names.size(); k++) {
            check(
                    sitesBase.get(k) != null && sitesNow.get(k) != null,
                    names.get(k) + " check exists");
            List<String> older = text(sitesBase.get(k)), is = text(sitesNow.get(k));
            check(
                    count(older, RANKED) == 1
                            && ranksYouTube(older, RANKED)
                            && has(older, marks[k]),
                    "base: "
                            + names.get(k)
                            + " asks the native list once, then compares to YouTube");
            check(
                    count(is, RANKED) == 0 && count(is, SWAPPED) == 1 && ranksYouTube(is, SWAPPED),
                    names.get(k) + " asks SourceFallback.playbackSources instead");
            boolean combined = a.length > 2 && "--combined".equals(a[2]);
            if (!combined)
                check(
                        is.size() == older.size(),
                        names.get(k) + " swap changes one instruction only");
            else if (k == 0 && has(is, "SourceFirst;->cacheKey"))
                check(
                        count(is, "SourceFirst;->cacheKey") == 2,
                        "source preparation still wraps both cache key returns");
        }
        for (ClassDef c : base.values())
            for (Method m : c.getMethods()) natives += count(text(m), RANKED);
        for (ClassDef c : now.values())
            for (Method m : c.getMethods()) {
                List<String> t = text(m);
                natives -= count(t, RANKED);
                swapped += count(t, SWAPPED);
            }
        check(
                natives == 5 && swapped == 5,
                "only the five ranking checks changed (" + natives + ", " + swapped + ")");
        Method entry =
                named(
                        now,
                        service,
                        "access$resolveWithModulePriority",
                        service,
                        "Ljava/lang/String;",
                        "Lcom/music/bitchord/data/sources/TrackMatcher$Target;",
                        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;");
        Method entryWas =
                named(
                        base,
                        service,
                        "access$resolveWithModulePriority",
                        service,
                        "Ljava/lang/String;",
                        "Lcom/music/bitchord/data/sources/TrackMatcher$Target;",
                        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;");
        List<String> head = text(entry), orig = text(entryWas);
        check(
                head.size() >= 5
                        && head.get(0).startsWith("INVOKE_STATIC_RANGE")
                        && head.get(0)
                                .contains(
                                        FALLBACK
                                                + "->resolve(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;")
                        && head.get(1).startsWith("MOVE_RESULT_OBJECT")
                        && head.get(1).endsWith(" r0")
                        && head.get(2).startsWith("IF_EQZ")
                        && head.get(3).startsWith("RETURN_OBJECT")
                        && head.get(3).endsWith(" r0")
                        && head.get(4).startsWith("NOP")
                        && head.size() == orig.size() + 5
                        && head.subList(5, head.size()).equals(orig),
                "the resolution entry asks SourceFallback.resolve first and otherwise runs"
                        + " unchanged");
        check(
                orig.get(0).startsWith("MOVE_OBJECT_FROM16") && orig.get(0).endsWith(" r0"),
                "base: the entry writes v0 first, so it is free");
        check(
                count(text(method(now, service, "prefetchAround", 1)), FALLBACK) == 1,
                "read-ahead calls SourceFallback once");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
