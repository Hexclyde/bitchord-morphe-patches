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
        // Google's and YouTube's sign-in cookies are expired before the link opens.
        java.util.List<String> gone =
                MicroGSignIn.expiries(
                        "SID=a; __Secure-3PSID=b; __Host-GAPS=c", "accounts.google.com");
        check(
                gone.contains(
                        "SID=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/;"
                                + " Domain=.google.com"),
                "a cookie on the parent domain");
        check(
                gone.contains("SID=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"),
                "and on the host");
        check(
                gone.contains(
                                "__Secure-3PSID=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT;"
                                        + " Path=/; Domain=.google.com; Secure")
                        && gone.stream()
                                .noneMatch(
                                        c ->
                                                c.startsWith("__Secure-3PSID")
                                                        && !c.endsWith("Secure")),
                "a __Secure- cookie only with Secure");
        check(
                gone.stream()
                        .filter(c -> c.startsWith("__Host-GAPS"))
                        .allMatch(c -> !c.contains("Domain") && c.endsWith("Secure")),
                "a __Host- cookie only on the host, with Secure");
        check(
                MicroGSignIn.expiries("VISITOR_INFO1_LIVE=x", "music.youtube.com").stream()
                        .anyMatch(c -> c.contains("Domain=.youtube.com")),
                "YouTube Music's cookies on youtube.com");
        check(MicroGSignIn.expiries(" ; ", "www.google.com").isEmpty(), "nothing to expire");
        System.out.println("PASS: " + checks + " MicroG sign-in checks.");
    }
}
