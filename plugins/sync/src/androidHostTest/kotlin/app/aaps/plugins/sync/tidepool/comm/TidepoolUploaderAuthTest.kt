package app.aaps.plugins.sync.tidepool.comm

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.tidepool.auth.AuthFlowOut
import app.aaps.plugins.sync.tidepool.auth.TidepoolToken
import app.aaps.plugins.sync.tidepool.compose.TidepoolRepository
import app.aaps.plugins.sync.tidepool.utils.RateLimit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.after
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

/**
 * Tests for the token refresh in [TidepoolUploader.handleTokenLoginAndStartSession].
 *
 * A refresh that fails only because of the network must keep the saved login and try again later. Before
 * the fix for https://github.com/nightscout/AndroidAPS/issues/4989 every failure opened the Tidepool login
 * page in the browser, so users on a weak connection were asked to log in again and again. Which AppAuth
 * answer counts as temporary is tested in `AppAuthTokenMappingTest`.
 */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35])
class TidepoolUploaderAuthTest {

    private val aapsLogger: AAPSLogger = mock()
    private val context: Context = mock()
    private val preferences: Preferences = mock()
    private val uploadChunk: UploadChunk = mock()
    private val dateUtil: DateUtil = mock()
    private val receiverDelegate: TidepoolReceiverDelegate = mock()
    private val config: Config = mock()
    private val l: L = mock()
    private val authFlowOut: AuthFlowOut = mock()

    private lateinit var sut: TidepoolUploader

    @Before
    fun setUp() {
        // Fixed time, so the two calls in the rate limit test fall into the same window
        whenever(dateUtil.now()).thenReturn(1_000_000L)
        sut = TidepoolUploader(
            aapsLogger, TidepoolRepository(aapsLogger), AndroidTidepoolWakeLock(context, aapsLogger), preferences, uploadChunk, dateUtil,
            receiverDelegate, config, l, authFlowOut, RateLimit(dateUtil)
        )
    }

    /** The platform answers every token request with no token, for [kind]. */
    private fun failTokenRefreshWith(kind: TidepoolToken.Kind) {
        wheneverBlocking { authFlowOut.freshToken() }.thenReturn(TidepoolToken.Unavailable(kind, "test"))
    }

    @Test
    fun `network problem keeps the login and does not open the browser`() {
        failTokenRefreshWith(TidepoolToken.Kind.TRANSIENT)

        sut.handleTokenLoginAndStartSession(doUpload = false, from = "test")

        // NO_SESSION, so the next upload tries the silent refresh again
        verify(authFlowOut, timeout(2000)).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NO_SESSION), any())
        verify(authFlowOut, never()).doTidePoolInitialLogin(any())
    }

    @Test
    fun `refused credentials open the login page`() {
        failTokenRefreshWith(TidepoolToken.Kind.REFUSED)

        sut.handleTokenLoginAndStartSession(doUpload = false, from = "test")

        verify(authFlowOut, timeout(2000)).doTidePoolInitialLogin(any())
        verify(authFlowOut).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NOT_LOGGED_IN), any())
    }

    @Test
    fun `no saved token opens the login page`() {
        failTokenRefreshWith(TidepoolToken.Kind.NO_TOKEN_RESPONSE)

        sut.handleTokenLoginAndStartSession(doUpload = false, from = "test")

        verify(authFlowOut, timeout(2000)).doTidePoolInitialLogin(any())
    }

    @Test
    fun `login page is not opened twice in a short time`() {
        failTokenRefreshWith(TidepoolToken.Kind.REFUSED)

        sut.handleTokenLoginAndStartSession(doUpload = false, from = "first")
        sut.handleTokenLoginAndStartSession(doUpload = false, from = "second")

        verify(authFlowOut, after(500).times(1)).doTidePoolInitialLogin(any())
    }
}
