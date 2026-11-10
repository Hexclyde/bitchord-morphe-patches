package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

public final class TelegramStreamRetryTest {
    static void check(long actual, long expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        check(TelegramStreams.retryAfter(new TdApi.Error(420, "FLOOD_WAIT_120")), 120000);
        check(TelegramStreams.retryAfter(new TdApi.Error(420, "FLOOD_PREMIUM_WAIT_15")), 15000);
        check(
                TelegramStreams.retryAfter(
                        new TdApi.Error(429, "Too Many Requests: retry after 172800")),
                172800000);
        check(TelegramStreams.retryAfter(new TdApi.Error(429, "Try later")), 60000);
        check(TelegramStreams.retryAfter(new TdApi.Error(500, "Unavailable")), 0);
        System.out.println(
                "PASS Telegram retries: explicit delays, premium waits and safe defaults");
    }
}
