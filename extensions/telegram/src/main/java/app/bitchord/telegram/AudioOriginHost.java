package app.bitchord.telegram;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSpec;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real host, by name and at run time, so this class links with any optional runtime absent.
 * AudioOriginPatch checks each of these members against the APK and adds the bitchordOrigin ones.
 */
final class AudioOriginHost implements AudioOrigin.Host {
    private final String cache;
    private final String upgrade;
    private final String choice;
    private final String registry;
    private final String flow;
    private final String sourceFirst;
    private final Map<String, Method> methods = new ConcurrentHashMap<>();

    AudioOriginHost() {
        this(
                "com.music.bitchord.playback.AudioCache",
                "com.music.bitchord.playback.QualityUpgrade",
                "com.music.bitchord.playback.StreamChoice",
                "com.music.bitchord.data.sources.SourceRegistry",
                "kotlinx.coroutines.flow.StateFlow");
    }

    AudioOriginHost(String cache, String upgrade, String choice, String registry, String flow) {
        this(cache, upgrade, choice, registry, flow, "app.bitchord.telegram.SourceFirst");
    }

    AudioOriginHost(
            String cache,
            String upgrade,
            String choice,
            String registry,
            String flow,
            String sourceFirst) {
        this.cache = cache;
        this.upgrade = upgrade;
        this.choice = choice;
        this.registry = registry;
        this.flow = flow;
        this.sourceFirst = sourceFirst;
    }

    private Object call(String owner, String name, Class<?>[] types, Object... values)
            throws ReflectiveOperationException {
        String id = owner + "." + name;
        Method method = methods.get(id);
        if (method == null) {
            method = Class.forName(owner).getMethod(name, types);
            methods.put(id, method);
        }
        Object target =
                Modifier.isStatic(method.getModifiers())
                        ? null
                        : Class.forName(owner).getField("INSTANCE").get(null);
        try {
            return method.invoke(target, values);
        } catch (InvocationTargetException failed) {
            // A released cache says so here. The caller reads that as unknown.
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failed;
        }
    }

    private static final Class<?>[] STRING = {String.class};
    private static final Class<?>[] URI = {Uri.class};

    @Override
    public String tag(String key, String name) {
        try {
            return (String)
                    call(
                            cache,
                            "bitchordOriginRead",
                            new Class<?>[] {String.class, String.class},
                            key,
                            name);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    @Override
    public void write(String key, String[] namesAndValues) {
        try {
            call(
                    cache,
                    "bitchordOriginWrite",
                    new Class<?>[] {String.class, String[].class},
                    key,
                    namesAndValues);
        } catch (ReflectiveOperationException absent) {
            // Not patched in. Nothing is recorded, and everything reads as unknown.
        }
    }

    @Override
    public boolean hasBytes(String key) {
        try {
            return Boolean.TRUE.equals(call(cache, "bitchordOriginHasBytes", STRING, key));
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    @Override
    public String keyOf(Uri uri) {
        try {
            return (String) call(cache, "cacheKeyOf", URI, uri);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    @Override
    public Uri renditionUri(MediaItem item) {
        if (item == null) return null;
        try {
            Object uri = call(sourceFirst, "renditionUri", new Class<?>[] {MediaItem.class}, item);
            if (uri instanceof Uri) return (Uri) uri;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            // Source preparation is optional. The item's own URI remains the fallback.
        }
        return AudioOrigin.uriOf(item);
    }

    @Override
    public String requestKey(DataSpec spec) {
        try {
            return (String) DataSpec.class.getField("key").get(spec);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> keysOf(String mediaId) {
        try {
            Object keys = call(cache, "keysOf", STRING, mediaId);
            return keys == null ? new ArrayList<String>() : (List<String>) keys;
        } catch (ReflectiveOperationException absent) {
            return new ArrayList<>();
        }
    }

    @Override
    public AudioOrigin.Stream forced(Uri uri) {
        try {
            return stream(call(upgrade, "forcedStream", URI, uri));
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    @Override
    public AudioOrigin.Stream chosen(String videoId) {
        try {
            return stream(call(choice, "of", STRING, videoId));
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    @Override
    public boolean substitute(String videoId) {
        try {
            return Boolean.TRUE.equals(call(choice, "isSubstitute", STRING, videoId));
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        if (owner == null) return null;
        Field member = owner.getClass().getField(name);
        return member.get(owner);
    }

    private static Integer integer(Object value) {
        return value instanceof Integer && (Integer) value > 0 ? (Integer) value : null;
    }

    @Override
    public AudioOrigin.Stream stream(Object raw) {
        if (raw == null) return null;
        try {
            Object format = field(raw, "format");
            Object codec = field(format, "codec");
            // An inferred bitrate is the app's estimate, not something the source said.
            boolean inferred = Boolean.TRUE.equals(field(format, "kbpsInferred"));
            return new AudioOrigin.Stream(
                    (String) field(raw, "url"),
                    (String) field(raw, "sourceConfigId"),
                    codec instanceof String ? (String) codec : null,
                    inferred ? null : integer(field(format, "kbps")),
                    integer(field(format, "bitDepth")),
                    integer(field(format, "sampleRateHz")));
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    private AudioOrigin.Config config(Object row) throws ReflectiveOperationException {
        if (row == null) return null;
        Object kind = row.getClass().getMethod("getKind").invoke(row);
        return new AudioOrigin.Config(
                (String) row.getClass().getMethod("getId").invoke(row),
                (String) row.getClass().getMethod("getDisplayName").invoke(row),
                kind instanceof Enum ? ((Enum<?>) kind).name() : null);
    }

    @Override
    public AudioOrigin.Config config(String id) {
        try {
            return config(call(registry, "config", STRING, id));
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    @Override
    public AudioOrigin.Config ofKind(String kind) {
        try {
            Object rows =
                    Class.forName(flow)
                            .getMethod("getValue")
                            .invoke(Class.forName(registry).getField("configs").get(null));
            if (!(rows instanceof List)) return null;
            for (Object row : (List<?>) rows) {
                AudioOrigin.Config config = config(row);
                if (config != null && kind.equals(config.kind)) return config;
            }
            return null;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    @Override
    public Context context() {
        try {
            return (Context) Class.forName(cache).getField("appContext").get(null);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }
}
