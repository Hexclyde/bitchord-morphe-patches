package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

import java.io.IOException;

/**
 * TDLib's login transitions and server-controlled resend availability. No account data is logged.
 */
final class LoginCodePolicy {
    private LoginCodePolicy() {}

    static int remaining(long deadline, long now) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, deadline - now + 999) / 1000);
    }

    static TdApi.SetAuthenticationPhoneNumber phone(TdApi.AuthorizationState state, String input)
            throws IOException {
        if (!(state instanceof TdApi.AuthorizationStateWaitPhoneNumber
                || state instanceof TdApi.AuthorizationStateWaitCode
                || state instanceof TdApi.AuthorizationStateWaitPassword
                || state instanceof TdApi.AuthorizationStateWaitEmailAddress
                || state instanceof TdApi.AuthorizationStateWaitEmailCode
                || state instanceof TdApi.AuthorizationStateWaitRegistration
                || state instanceof TdApi.AuthorizationStateWaitPremiumPurchase))
            throw new IOException("Wait for the current Telegram login step to finish.");
        String number = input == null ? "" : input.trim().replaceAll("[ ()-]", "");
        if (!number.matches("\\+[1-9][0-9]{5,14}"))
            throw new IOException("Enter a phone number with + and its country code.");
        return new TdApi.SetAuthenticationPhoneNumber(number, null);
    }

    static boolean offered(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitEmailCode) return true;
        if (!(state instanceof TdApi.AuthorizationStateWaitCode)) return false;
        TdApi.AuthenticationCodeInfo info = ((TdApi.AuthorizationStateWaitCode) state).codeInfo;
        return info != null && info.nextType != null;
    }

    static TdApi.ResendAuthenticationCode resend(
            TdApi.AuthorizationState state, long deadline, long now) throws IOException {
        if (!offered(state))
            throw new IOException(
                    "Telegram has not offered another code delivery method. Check your existing"
                            + " Telegram app or change the phone number.");
        int seconds = remaining(deadline, now);
        if (seconds > 0)
            throw new IOException("You can request another code in " + seconds + " seconds.");
        return new TdApi.ResendAuthenticationCode(new TdApi.ResendCodeReasonUserRequest());
    }
}
