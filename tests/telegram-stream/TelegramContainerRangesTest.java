package app.bitchord.telegram;

import java.nio.ByteBuffer;

public final class TelegramContainerRangesTest {
    static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        byte[] head = new byte[32];
        ByteBuffer b = ByteBuffer.wrap(head);
        b.putInt(16).putInt(0x66747970).putLong(0).putInt(1_000_000).putInt(0x6d646174);
        check(
                TelegramContainerRanges.next(head, 32, 0, 1_010_000) == 1_000_016,
                "Exact trailing atom boundary");
        check(
                TelegramContainerRanges.next(head, 12, 0, 1_010_000) == -1,
                "Incomplete atom header gives no guess");
        b.putInt(16, Integer.MAX_VALUE);
        check(
                TelegramContainerRanges.next(head, 32, 0, 1_010_000) == -1,
                "Out-of-file atom rejected");
        b.putInt(16, 16).putInt(20, 0x6d6f6f76);
        check(
                TelegramContainerRanges.next(head, 32, 0, 1_010_000) == -1,
                "Fast-start moov needs no tail request");
        b.putInt(16, 1).putInt(20, 0x6d646174).putLong(24, Long.MIN_VALUE);
        check(
                TelegramContainerRanges.next(head, 32, 0, Long.MAX_VALUE) == -1,
                "Unsigned overflow rejected");
        check(
                TelegramContainerRanges.sniff(head, 32, "unknown").equals("mp4"),
                "Container sniff independent of advertised bitrate");
        try (AutoCloseable tag = TelegramStreamContext.preview("track")) {
            check(
                    TelegramStreamContext.forSource("track#hifi", "uri")
                            == TelegramStreamCoordinator.Consumer.PREVIEW,
                    "Preview rendition classified");
            try (TelegramStreamContext scope =
                    new TelegramStreamContext(TelegramStreamCoordinator.Consumer.VERIFICATION)) {
                check(
                        TelegramStreamContext.forSource("track", "uri")
                                == TelegramStreamCoordinator.Consumer.VERIFICATION,
                        "Explicit verification scope wins");
            }
        }
        check(
                TelegramStreamContext.forSource("track", "uri")
                        == TelegramStreamCoordinator.Consumer.PLAYBACK,
                "Preview tag released");
        System.out.println(
                "PASS container range hints: trailing moov, fast start, malformed atoms and"
                        + " consumer scopes");
    }
}
