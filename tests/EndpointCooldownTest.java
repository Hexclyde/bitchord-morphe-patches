package app.bitchord.telegram;

import java.util.concurrent.atomic.AtomicLong;

/** One bounded pause for a web service that answered "unavailable". */
public final class EndpointCooldownTest {
    static int passed;

    static void check(boolean okay, String message) {
        passed++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        AtomicLong now = new AtomicLong(1_000_000);
        EndpointCooldown pause = new EndpointCooldown(now::get);
        check(pause.remaining() == 0, "no pause at first");
        check(pause.failed(0) == EndpointCooldown.SHORTEST_MILLIS, "the first pause is short");
        now.addAndGet(10_000);
        check(pause.remaining() == 20_000, "it counts down");
        // Calls already running when the pause began fail too; they don't lengthen it.
        check(pause.failed(0) == 20_000 && pause.remaining() == 20_000, "late failures join it");
        now.addAndGet(20_000);
        check(pause.remaining() == 0, "it ends");
        check(pause.failed(0) == 2 * EndpointCooldown.SHORTEST_MILLIS, "the next one doubles");
        for (int i = 0; i < 12; i++) {
            now.addAndGet(EndpointCooldown.LONGEST_MILLIS + 1);
            pause.failed(0);
        }
        check(pause.remaining() <= EndpointCooldown.LONGEST_MILLIS, "it never passes ten minutes");
        now.addAndGet(EndpointCooldown.LONGEST_MILLIS + 1);
        check(
                pause.failed(Long.MAX_VALUE) == EndpointCooldown.LONGEST_MILLIS,
                "a service's own retry time is capped too");
        now.addAndGet(EndpointCooldown.LONGEST_MILLIS + 1);
        check(pause.failed(5_000) == 5_000, "a short retry time is followed");
        pause.succeeded();
        check(pause.remaining() == 0, "an answer ends the pause");
        now.addAndGet(1);
        check(pause.failed(0) == EndpointCooldown.SHORTEST_MILLIS, "and the next starts short");
        System.out.println("PASS: " + passed + " shared endpoint pause checks.");
    }
}
