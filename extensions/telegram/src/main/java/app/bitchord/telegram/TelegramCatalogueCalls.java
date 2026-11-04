package app.bitchord.telegram;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.CoroutineSingletons;
import kotlin.coroutines.jvm.internal.ContinuationImpl;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Separates Telegram catalogue answers without cancelling their shared requests. */
public final class TelegramCatalogueCalls {
    private TelegramCatalogueCalls() {}

    private static final Map<Object, Boolean> caches =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static volatile Object revision = new Object();
    private static volatile String namespace = UUID.randomUUID().toString();
    private static final Object revisionLock = new Object();

    public static void register(String base, Object searches, Object streams) {
        try {
            URI uri = new URI(base);
            if (!"https".equals(uri.getScheme())
                    || !"telegram.bitchord.invalid".equalsIgnoreCase(uri.getRawAuthority())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) return;
        } catch (Exception invalid) {
            return;
        }
        if (searches != null) caches.put(searches, true);
        if (streams != null) caches.put(streams, true);
    }

    public static void changed(long catalogueRevision) {
        synchronized (revisionLock) {
            revision = new Object();
            namespace = catalogueRevision + ":" + UUID.randomUUID();
        }
    }

    static void requireCurrent(Object expected) throws IOException {
        if (expected != revision) throw new IOException("Telegram content changed. Search again.");
    }

    @SuppressWarnings("unchecked")
    public static Object get(
            Object cache, String key, Object describe, Object produce, Object continuation)
            throws Throwable {
        if (!caches.containsKey(cache))
            return original(cache, key, describe, produce, continuation);
        final Object expected;
        final String scoped;
        synchronized (revisionLock) {
            expected = revision;
            scoped = "telegram:" + namespace + ":" + key;
        }
        if (!(continuation instanceof ContinuationImpl))
            throw new IOException("The native catalogue continuation changed");
        Object result =
                original(
                        cache,
                        scoped,
                        describe,
                        produce,
                        new CatalogueContinuation((Continuation<Object>) continuation, expected));
        if (result != CoroutineSingletons.COROUTINE_SUSPENDED && expected != revision)
            return new kotlin.Result.Failure(
                    new IOException("Telegram content changed. Search again."));
        return result;
    }

    private static Object original(
            Object cache, String key, Object describe, Object produce, Object continuation)
            throws Throwable {
        try {
            return cache.getClass()
                    .getMethod(
                            "bitchordTelegramCatalogueGet",
                            String.class,
                            Object.class,
                            Object.class,
                            Object.class)
                    .invoke(cache, key, describe, produce, continuation);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private static Object boxedFailure(IOException changed) {
        try {
            java.lang.reflect.Constructor<?> constructor =
                    kotlin.Result.class.getDeclaredConstructor(Object.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new kotlin.Result.Failure(changed));
        } catch (ReflectiveOperationException unsupported) {
            throw new IllegalStateException("The native catalogue result ABI changed", unsupported);
        }
    }

    static final class CatalogueContinuation extends ContinuationImpl {
        private final Object expected;

        CatalogueContinuation(Continuation<Object> completion, Object expected) {
            super(completion);
            this.expected = expected;
        }

        @Override
        public Object invokeSuspend(Object result) {
            try {
                requireCurrent(expected);
            } catch (IOException changed) {
                return boxedFailure(changed);
            }
            return result;
        }
    }
}
