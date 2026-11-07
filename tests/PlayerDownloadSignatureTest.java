package app.bitchord.telegram;

import kotlin.jvm.functions.Function1;

import java.io.IOException;

/** Download matching arguments for each known SourceResolver.matchAndStream signature. */
public final class PlayerDownloadSignatureTest {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static final class Older {
        String seen;

        public final Object matchAndStream(
                Object source,
                Object target,
                Object request,
                boolean a,
                boolean b,
                boolean c,
                int drift,
                Object step) {
            seen = "" + a + b + c + drift;
            return seen;
        }
    }

    static final class Current {
        String seen;

        public final Object matchAndStream(
                Object source,
                Object target,
                Object request,
                boolean a,
                boolean b,
                boolean c,
                int drift,
                Function1<Object, Object> lengthLater,
                boolean checkFileLength,
                Object step) {
            seen = "" + a + b + c + drift + lengthLater + checkFileLength;
            return seen;
        }
    }

    static final class Unknown {
        public final Object matchAndStream(Object source, Object target, Object step) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Older older = new Older();
        Object[] first = PlayerDownloadHost.matchArguments(Older.class, "s", "t", "b", "c");
        check(first.length == 8, "1.9 argument count");
        check(
                "truetruetrue3".equals(PlayerDownloadHost.call(older, "matchAndStream", first)),
                "1.9 call keeps the download bars");

        Current current = new Current();
        Object[] second = PlayerDownloadHost.matchArguments(Current.class, "s", "t", "b", "c");
        check(
                second.length == 10 && second[7] == null && Boolean.FALSE.equals(second[8]),
                "1.9.1 defaults");
        check(
                "truetruetrue3nullfalse"
                        .equals(PlayerDownloadHost.call(current, "matchAndStream", second)),
                "1.9.1 call keeps the download bars and defaults");

        try {
            PlayerDownloadHost.matchArguments(Unknown.class, "s", "t", "b", "c");
            throw new AssertionError("unknown signature accepted");
        } catch (IOException expected) {
            checks++;
        }
        System.out.println("PASS PlayerDownloadSignatureTest " + checks);
    }
}
