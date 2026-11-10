package app.bitchord.telegram;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

public final class AddonAvailabilityTest {
    private static int checks;

    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static void blocked(String address) throws Exception {
        try {
            AddonAvailability.before(address);
            throw new AssertionError("Unavailable endpoint must not be called");
        } catch (IOException expected) {
            check(!expected.getMessage().contains("secret"), "error omits credentials and paths");
        }
    }

    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(1000);
        AddonAvailability.clock = now::get;
        AddonAvailability.reset();
        String source = "https://provider.example/secret/search?q=one&token=secret";
        AddonAvailability.before(source);
        AddonAvailability.observe(source, 503, null);
        blocked("https://provider.example/other/manifest?token=secret");
        blocked("https://PROVIDER.example:443/stream/song-two");
        AddonAvailability.before("https://healthy.example/search");
        AddonAvailability.before("https://provider.example:444/search");
        check(true, "Other servers and ports continue");
        AddonAvailability.observe(source, 200, null);
        blocked(source);
        now.addAndGet(30_000);
        AddonAvailability.before(source);
        AddonAvailability.observe(source, 503, null);
        now.addAndGet(30_000);
        blocked(source);
        now.addAndGet(30_000);
        AddonAvailability.before(source);
        AddonAvailability.observe(source, 200, null);
        AddonAvailability.observe(source, 503, "5");
        now.addAndGet(4999);
        blocked(source);
        now.incrementAndGet();
        AddonAvailability.before(source);
        check(
                AddonAvailability.retryMillis("9223372036854775807") == 3_600_000,
                "Retry-After cannot overflow");
        check(
                AddonAvailability.retryMillis("not a date") == 0,
                "Invalid retry header uses backoff");
        AddonAvailability.observe("https://other.example/search", 404, null);
        AddonAvailability.before("https://other.example/search");
        check(true, "Missing tracks do not impose a server cooldown");
        AddonAvailability.reset();
        AddonAvailability.before(source);
        check(true, "Reset releases state");
        System.out.println("PASS: " + checks + " add-on availability checks");
    }
}
