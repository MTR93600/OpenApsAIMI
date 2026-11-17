package app.aaps.plugins.sync.tidepool.auth

import com.google.common.truth.Truth.assertThat
import net.openid.appauth.AuthorizationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import java.io.IOException

/**
 * How an AppAuth token answer is read. A refresh that fails only because of the network must keep the
 * saved login (issue #4989); a refused login must ask for a new one.
 *
 * Runs under Robolectric because [AuthorizationException] is turned into text with org.json.
 */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35])
class AppAuthTokenMappingTest {

    private fun kindOf(ex: AuthorizationException) =
        (AppAuthTidepoolOAuth.tokenFrom(null, ex, hasTokenResponse = true) as TidepoolToken.Unavailable).kind

    @Test
    fun `network, server and non JSON answers are temporary`() {
        val io = IOException("no route to host")
        assertThat(kindOf(AuthorizationException.fromTemplate(AuthorizationException.GeneralErrors.NETWORK_ERROR, io))).isEqualTo(TidepoolToken.Kind.TRANSIENT)
        assertThat(kindOf(AuthorizationException.fromTemplate(AuthorizationException.GeneralErrors.SERVER_ERROR, io))).isEqualTo(TidepoolToken.Kind.TRANSIENT)
        assertThat(kindOf(AuthorizationException.fromTemplate(AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR, io))).isEqualTo(TidepoolToken.Kind.TRANSIENT)
    }

    @Test
    fun `refused credentials need a new login`() {
        assertThat(kindOf(AuthorizationException.TokenRequestErrors.INVALID_GRANT)).isEqualTo(TidepoolToken.Kind.REFUSED)
    }

    @Test
    fun `a token without a saved token response needs a new login`() {
        val token = AppAuthTidepoolOAuth.tokenFrom("token-1", null, hasTokenResponse = false)
        assertThat((token as TidepoolToken.Unavailable).kind).isEqualTo(TidepoolToken.Kind.NO_TOKEN_RESPONSE)
    }

    @Test
    fun `a token is used`() {
        assertThat(AppAuthTidepoolOAuth.tokenFrom("token-1", null, hasTokenResponse = true)).isEqualTo(TidepoolToken.Valid("token-1"))
    }
}
