package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.io.IOException;

public class LoginCodePolicyTest {
    private TdApi.AuthorizationStateWaitCode waiting(boolean next) {
        return new TdApi.AuthorizationStateWaitCode(
                new TdApi.AuthenticationCodeInfo(
                        "+353000000000",
                        new TdApi.AuthenticationCodeTypeTelegramMessage(5),
                        next ? new TdApi.AuthenticationCodeTypeSms(5) : null,
                        30));
    }

    @Test
    public void phoneCorrectionUsesTdlibSupportedTransitionWithoutLogout() throws Exception {
        TdApi.SetAuthenticationPhoneNumber request =
                LoginCodePolicy.phone(waiting(true), "+44 (7700) 900-123");
        assertEquals("+447700900123", request.phoneNumber);
    }

    @Test
    public void changingNumberCannotReplaceAnAuthenticatedAccount() {
        assertThrows(
                IOException.class,
                () -> LoginCodePolicy.phone(new TdApi.AuthorizationStateReady(), "+447700900123"));
    }

    @Test
    public void resendRequiresBothTheNextDeliveryMethodAndElapsedTimeout() throws Exception {
        assertThrows(IOException.class, () -> LoginCodePolicy.resend(waiting(false), 1000, 1000));
        assertThrows(IOException.class, () -> LoginCodePolicy.resend(waiting(true), 1001, 1000));
        assertEquals(1, LoginCodePolicy.remaining(1001, 1000));
        assertEquals(0, LoginCodePolicy.remaining(1000, 1000));
        assertTrue(
                LoginCodePolicy.resend(waiting(true), 1000, 1000).reason
                        instanceof TdApi.ResendCodeReasonUserRequest);
    }
}
