package app.bitchord.telegram;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.CoroutineSingletons;
import kotlin.coroutines.jvm.internal.ContinuationImpl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CancellationException;

public final class TelegramCatalogueCallsTest {
    static int checks;

    static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    static Object box(Object value) throws Exception {
        Constructor<?> constructor = kotlin.Result.class.getDeclaredConstructor(Object.class);
        constructor.setAccessible(true);
        return constructor.newInstance(value);
    }

    static Object unbox(Object value) throws Exception {
        Field field = kotlin.Result.class.getDeclaredField("value");
        field.setAccessible(true);
        return field.get(value);
    }

    static final class Completion implements Continuation<Object> {
        int calls;
        Object result;

        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        public void resumeWith(Object value) {
            calls++;
            result = value;
        }
    }

    static final class Caller extends ContinuationImpl {
        Caller(Completion completion) {
            super(completion);
        }

        public Object invokeSuspend(Object value) {
            return value;
        }
    }

    public static final class Cache {
        final Map<String, Object> values = new HashMap<>();
        final List<String> keys = new ArrayList<>();
        Object continuation;
        boolean pending;
        Runnable during;
        RuntimeException error;

        public Object bitchordTelegramCatalogueGet(
                String key, Object describe, Object produce, Object next) {
            keys.add(key);
            continuation = next;
            if (error != null) throw error;
            if (during != null) {
                Runnable action = during;
                during = null;
                action.run();
            }
            if (pending) return CoroutineSingletons.COROUTINE_SUSPENDED;
            return values.computeIfAbsent(key, ignored -> "answer-" + values.size());
        }
    }

    static Object get(Cache cache, String key, Completion completion) throws Throwable {
        return TelegramCatalogueCalls.get(cache, key, null, null, new Caller(completion));
    }

    public static void main(String[] args) throws Throwable {
        Cache search = new Cache(), stream = new Cache(), other = new Cache();
        TelegramCatalogueCalls.register(
                "https://telegram.bitchord.invalid/session", search, stream);
        Object first = get(search, "query", new Completion());
        check(
                first.equals(get(search, "query", new Completion())),
                "Same revision still shares its native cache key");
        String oldKey = search.keys.get(0);
        get(stream, "track", new Completion());
        TelegramCatalogueCalls.changed(1);
        check(
                !first.equals(get(search, "query", new Completion())),
                "A durable change asks the same query under a new key");
        check(
                !oldKey.equals(search.keys.get(search.keys.size() - 1)),
                "Old catalogue keys cannot be reused");
        get(stream, "track", new Completion());
        check(
                !stream.keys.get(0).equals(stream.keys.get(1)),
                "Stream answers use the same catalogue fence");
        Caller foreign = new Caller(new Completion());
        Object answer = TelegramCatalogueCalls.get(other, "plain", null, null, foreign);
        check(
                other.keys.equals(Collections.singletonList("plain"))
                        && other.continuation == foreign,
                "Unregistered providers retain exact keys and continuations");
        check(answer.equals("answer-0"), "Other provider answer unchanged");
        for (String base :
                Arrays.asList(
                        "http://telegram.bitchord.invalid/x",
                        "https://telegram.bitchord.invalid.evil/x",
                        "https://telegram.bitchord.invalid/x?q=1")) {
            Cache rejected = new Cache();
            TelegramCatalogueCalls.register(base, rejected, null);
            get(rejected, "plain", new Completion());
            check(
                    rejected.keys.get(0).equals("plain"),
                    "Only exact native reserved origins are registered");
        }
        Cache immediate = new Cache();
        TelegramCatalogueCalls.register("https://telegram.bitchord.invalid/x", immediate, null);
        immediate.during = () -> TelegramCatalogueCalls.changed(2);
        check(
                get(immediate, "old", new Completion()) instanceof kotlin.Result.Failure,
                "A stale immediate result is an unboxed failure");
        Cache asynchronous = new Cache();
        asynchronous.pending = true;
        TelegramCatalogueCalls.register("https://telegram.bitchord.invalid/x", asynchronous, null);
        Completion stale = new Completion();
        check(
                get(asynchronous, "pending", stale) == CoroutineSingletons.COROUTINE_SUSPENDED,
                "Suspended marker is preserved without premature resume");
        ContinuationImpl pending = (ContinuationImpl) asynchronous.continuation;
        TelegramCatalogueCalls.changed(3);
        pending.resumeWith(box("old rows"));
        check(stale.calls == 1, "Native suspended completion is forwarded exactly once");
        check(
                stale.result instanceof kotlin.Result,
                "A stale suspended answer retains boxed Result shape");
        check(
                unbox(stale.result) instanceof kotlin.Result.Failure,
                "Stale rows become an inner Result failure, not an outer coroutine failure");
        check(
                asynchronous.keys.size() == 1,
                "Coroutine resume did not re-enter the wrapper or recapture its epoch");
        Completion current = new Completion();
        get(asynchronous, "pending", current);
        ((ContinuationImpl) asynchronous.continuation).resumeWith(box("current rows"));
        check(
                current.calls == 1 && unbox(current.result).equals("current rows"),
                "Current boxed success is forwarded intact");
        check(
                !asynchronous.keys.get(0).equals(asynchronous.keys.get(1)),
                "New callers never join the obsolete pending key");
        Completion failure = new Completion();
        get(asynchronous, "failure", failure);
        Object nativeFailure =
                box(new kotlin.Result.Failure(new IllegalArgumentException("fixture")));
        ((ContinuationImpl) asynchronous.continuation).resumeWith(nativeFailure);
        check(
                failure.result == nativeFailure && failure.calls == 1,
                "Existing native boxed failures retain identity");
        Cache cancelled = new Cache();
        CancellationException expected = new CancellationException("fixture");
        cancelled.error = expected;
        try {
            get(cancelled, "cancel", new Completion());
            throw new AssertionError("Native cancellation swallowed");
        } catch (CancellationException actual) {
            check(actual == expected, "Native cancellation is unwrapped without replacement");
        }
        System.out.println("PASS: " + checks + " catalogue cache and native Kotlin result checks");
    }
}
