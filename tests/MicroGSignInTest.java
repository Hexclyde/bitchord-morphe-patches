package app.bitchord.telegram;

/**
 * Signing in with MicroG RE: only BitChord's sign-in page is taken over, MicroG RE is asked for a
 * link that signs a browser in to YouTube Music, and only a real link is opened.
 */
public final class MicroGSignInTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(
                MicroGSignIn.tokenType()
                        .equals("weblogin:continue=https%3A%2F%2Fmusic.youtube.com%2F"),
                "asks for a YouTube Music web sign-in: " + MicroGSignIn.tokenType());
        check(MicroGSignIn.ACCOUNT_TYPE.equals("app.revanced"), "MicroG RE's account type");
        check(
                MicroGSignIn.isSignIn(
                        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube"
                            + "&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F"),
                "BitChord's sign-in page is offered MicroG");
        check(
                !MicroGSignIn.isSignIn("https://music.youtube.com/"),
                "changing channel opens YouTube Music as before");
        check(!MicroGSignIn.isSignIn(null), "nothing to load");
        check(
                MicroGSignIn.usable(
                        "https://accounts.google.com/MergeSession?uberauth=x&continue=https%3A%2F%2Fmusic.youtube.com%2F"),
                "a sign-in link is opened");
        check(!MicroGSignIn.usable(null), "no token");
        check(!MicroGSignIn.usable("ya29.token"), "an OAuth token isn't a link");
        check(
                !MicroGSignIn.usable("https://accounts.google.com/?WILL_NOT_SIGN_IN"),
                "Google's refusal isn't opened");
        System.out.println("PASS: " + checks + " MicroG sign-in checks.");
    }
}
