package app.bitchord.soulseek;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the exact lifetime state used by the Android foreground service. */
public final class LifetimeTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] arguments) throws Exception {
        BulkTransferService.Lifetime state = new BulkTransferService.Lifetime();
        check(!state.idle(1_000_000, false), "unrequested service never activates itself");
        check(state.activate(1000), "explicit activation accepted");
        check(!state.idle(60_999, false), "one minute grace is retained");
        check(state.idle(61_000, false), "idle service stops after bounded grace");
        state.touch(60_500);
        check(!state.idle(61_000, false), "new explicit action refreshes the idle lease");
        check(!state.idle(200_000, true), "active provider or download retains service");
        check(
                !state.idle(259_999, false) && state.idle(260_000, false),
                "idle time begins after last active work");
        check(!state.idle(0, false), "clock rollback does not invent an elapsed deadline");
        AtomicInteger cleanups = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] callers = new Thread[16];
        for (int i = 0; i < callers.length; i++) {
            callers[i] =
                    new Thread(
                            () -> {
                                try {
                                    go.await();
                                } catch (InterruptedException error) {
                                    throw new AssertionError(error);
                                }
                                if (state.beginStop()) cleanups.incrementAndGet();
                            });
            callers[i].start();
        }
        go.countDown();
        for (Thread caller : callers) caller.join();
        check(
                cleanups.get() == 1 && state.stopping(),
                "concurrent stop, timeout and destruction clean up once");
        check(!state.activate(300_000), "stopping service cannot restart from late callbacks");
        state.touch(400_000);
        check(!state.idle(500_000, true), "late work cannot revive stopping service");
        check(
                new BulkTransferService.Lifetime().activate(500_000),
                "fresh user-created lifetime may start again");
        System.out.println("PASS: " + checks + " foreground lifetime checks.");
    }
}
