package app.bitchord.telegram;

/**
 * One pause for every call to the same web service, so a queue of songs backs off together when the
 * service answers "unavailable" instead of each song asking again.
 */
final class EndpointCooldown {
    interface Clock {
        long millis();
    }

    /** The first pause; each later failure without a retry time doubles it, up to the longest. */
    static final long SHORTEST_MILLIS = 30_000, LONGEST_MILLIS = 10 * 60_000L;

    static final long MAX_SERVER_RETRY_MILLIS = 3_600_000L;

    private final Clock clock;
    private long until, next = SHORTEST_MILLIS;

    EndpointCooldown(Clock clock) {
        this.clock = clock;
    }

    /**
     * Starts a pause after a refusal and returns how long it lasts. {@code retryMillis} is the
     * service's own retry time, or 0. Calls that were already running when the pause began and fail
     * after it don't lengthen it.
     */
    synchronized long failed(long retryMillis) {
        long now = clock.millis();
        if (until > now) return until - now;
        long wait =
                retryMillis > 0
                        ? Math.min(MAX_SERVER_RETRY_MILLIS, Math.max(1000, retryMillis))
                        : next;
        next = Math.min(LONGEST_MILLIS, next * 2);
        until = now + wait;
        return wait;
    }

    /** Milliseconds left in the pause, or 0. */
    synchronized long remaining() {
        return Math.max(0, until - clock.millis());
    }

    /** The service answered: the next pause starts short again. */
    synchronized void succeeded() {
        until = 0;
        next = SHORTEST_MILLIS;
    }
}
