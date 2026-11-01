package app.bitchord.telegram;

import android.content.Context;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.CoroutineSingletons;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs downloads inside the scheduler's job, with an independently cancellable coroutine scope. */
final class SmartDownloadTransfer {
    private SmartDownloadTransfer() {}

    interface Backend {
        Set<String> download(Context app, List<Object> songs, AtomicBoolean stopped)
                throws Exception;
    }

    static Backend backend = SmartDownloadTransfer::download;

    private static Set<String> download(Context app, List<Object> songs, AtomicBoolean stopped)
            throws Exception {
        if (stopped.get() || !SmartDownloads.enabled) return Collections.emptySet();
        Class<?> contract = Class.forName("kotlinx.coroutines.Job");
        Object job =
                Class.forName("kotlinx.coroutines.JobImpl")
                        .getConstructor(contract)
                        .newInstance(new Object[] {null});
        CoroutineContext context = (CoroutineContext) job;
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        Continuation<Object> completion =
                new Continuation<Object>() {
                    public CoroutineContext getContext() {
                        return context;
                    }

                    public void resumeWith(Object value) {
                        result.set(value);
                        complete.countDown();
                    }
                };
        Class<?> host = Class.forName("com.music.bitchord.download.Downloads");
        Method selected = null;
        for (Method method : host.getDeclaredMethods()) {
            if (!(method.getName().equals("downloadNow")
                    || method.getName().startsWith("downloadNow$"))) continue;
            if (!Arrays.equals(
                    method.getParameterTypes(),
                    new Class<?>[] {
                        Context.class,
                        ArrayList.class,
                        String.class,
                        kotlin.coroutines.jvm.internal.ContinuationImpl.class
                    })) continue;
            if (selected != null) throw new IOException("Ambiguous scheduled download interface");
            selected = method;
        }
        if (selected == null) throw new IOException("Scheduled download interface unavailable");
        selected.setAccessible(true);
        Object owner = host.getField("INSTANCE").get(null);
        try {
            Object returned =
                    selected.invoke(
                            owner,
                            app,
                            new ArrayList<>(songs),
                            SmartDownloads.FROM,
                            new VideoSwitch.Step(completion));
            if (returned != CoroutineSingletons.COROUTINE_SUSPENDED) result.set(returned);
            else {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
                while (!complete.await(200, TimeUnit.MILLISECONDS)) {
                    if (stopped.get()
                            || !SmartDownloads.enabled
                            || Thread.currentThread().isInterrupted())
                        throw new InterruptedException();
                    if (System.nanoTime() >= deadline)
                        throw new IOException("Scheduled download run timed out");
                }
            }
            Object value = result.get();
            if (value != null && value.getClass().getName().equals("kotlin.Result")) {
                Field field = value.getClass().getDeclaredField("value");
                field.setAccessible(true);
                value = field.get(value);
            }
            if (!(value instanceof Set))
                throw new IOException("Scheduled downloads did not complete");
            Set<String> finished = new HashSet<>();
            for (Object id : (Set<?>) value) if (id instanceof String) finished.add((String) id);
            return finished;
        } finally {
            contract.getMethod("cancel", CancellationException.class)
                    .invoke(job, new CancellationException("Scheduled download scope finished"));
        }
    }
}
