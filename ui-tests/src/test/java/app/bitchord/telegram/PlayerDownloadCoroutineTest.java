package app.bitchord.telegram;

import static org.junit.Assert.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.CoroutineSingletons;

import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PlayerDownloadCoroutineTest {
    public interface ShrunkJob {}

    public static final class NativeKey implements CoroutineContext.Key<CoroutineContext.Element> {
        public static final NativeKey singleton = new NativeKey();
    }

    @Test
    public void shrunkJobWithoutAliasUsesItsTypedKeySingleton() throws Exception {
        assertEquals(0, ShrunkJob.class.getDeclaredFields().length);
        assertSame(
                NativeKey.singleton, PlayerDownloadHost.jobKey(ShrunkJob.class, NativeKey.class));
    }

    public static final class StandardJob {
        public static final CoroutineContext.Key<?> Key = NativeKey.singleton;
    }

    public static final class WrongAlias {
        public static final Object Key = "wrong";
    }

    public static final class AmbiguousKey
            implements CoroutineContext.Key<CoroutineContext.Element> {
        public static final AmbiguousKey first = new AmbiguousKey();
        public static final AmbiguousKey second = new AmbiguousKey();
    }

    public static final class NullKey implements CoroutineContext.Key<CoroutineContext.Element> {
        public static final NullKey singleton = null;
    }

    public static final class MissingKey
            implements CoroutineContext.Key<CoroutineContext.Element> {}

    @Test
    public void standardAliasWinsWithoutDependingOnTheFallbackFields() throws Exception {
        assertSame(
                NativeKey.singleton,
                PlayerDownloadHost.jobKey(StandardJob.class, AmbiguousKey.class));
    }

    @Test
    public void wrongAliasIsRejectedRatherThanSelectingAnotherKey() {
        assertThrows(
                IllegalStateException.class,
                () -> PlayerDownloadHost.jobKey(WrongAlias.class, NativeKey.class));
    }

    @Test
    public void ambiguousSingletonIsRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> PlayerDownloadHost.jobKey(ShrunkJob.class, AmbiguousKey.class));
    }

    @Test
    public void nullOrMissingSingletonIsRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> PlayerDownloadHost.jobKey(ShrunkJob.class, NullKey.class));
        assertThrows(
                IllegalStateException.class,
                () -> PlayerDownloadHost.jobKey(ShrunkJob.class, MissingKey.class));
    }

    @Test
    public void fallbackMustImplementTheCoroutineKeyContract() {
        assertThrows(
                IllegalStateException.class,
                () -> PlayerDownloadHost.jobKey(ShrunkJob.class, String.class));
    }

    PlayerDownloadHost host(CompletableJob parent) {
        return new PlayerDownloadHost(
                RuntimeEnvironment.getApplication(),
                new Continuation<Object>() {
                    public CoroutineContext getContext() {
                        return parent;
                    }

                    public void resumeWith(Object value) {
                        fail("The download parent must not be resumed");
                    }
                });
    }

    @Test
    public void suspendedLookupFinishesAndReleasesItsChildWithoutCancellingTheDownload()
            throws Exception {
        CompletableJob parent = JobKt.Job((Job) null);
        AtomicReference<Job> child = new AtomicReference<>();
        try {
            Object value =
                    host(parent)
                            .await(
                                    step -> {
                                        child.set(step.getContext().get(Job.Key));
                                        Thread resumed = new Thread(() -> step.resumeWith("kept"));
                                        resumed.start();
                                        return CoroutineSingletons.COROUTINE_SUSPENDED;
                                    });
            assertEquals("kept", value);
            assertNotSame(parent, child.get());
            assertTrue(child.get().isCancelled());
            assertTrue(parent.isActive());
        } finally {
            parent.cancel(new CancellationException());
        }
    }

    @Test
    public void cancellingNativeDownloadCancelsSuspendedLookupAndItsChild() throws Exception {
        CompletableJob parent = JobKt.Job((Job) null);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Job> child = new AtomicReference<>();
        try {
            Future<Object> result =
                    worker.submit(
                            () ->
                                    host(parent)
                                            .await(
                                                    step -> {
                                                        child.set(step.getContext().get(Job.Key));
                                                        entered.countDown();
                                                        return CoroutineSingletons
                                                                .COROUTINE_SUSPENDED;
                                                    }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            parent.cancel(new CancellationException());
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertTrue(child.get().isCancelled());
        } finally {
            parent.cancel(new CancellationException());
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void failedLookupKeepsOriginalFailureAndCancelsOnlyItsChild() throws Exception {
        CompletableJob parent = JobKt.Job((Job) null);
        AtomicReference<Job> child = new AtomicReference<>();
        IOException expected = new IOException("Owned fixture lookup failed");
        try {
            IOException failure =
                    assertThrows(
                            IOException.class,
                            () ->
                                    host(parent)
                                            .await(
                                                    step -> {
                                                        child.set(step.getContext().get(Job.Key));
                                                        throw expected;
                                                    }));
            assertSame(expected, failure);
            assertTrue(child.get().isCancelled());
            assertTrue(parent.isActive());
        } finally {
            parent.cancel(new CancellationException());
        }
    }
}
