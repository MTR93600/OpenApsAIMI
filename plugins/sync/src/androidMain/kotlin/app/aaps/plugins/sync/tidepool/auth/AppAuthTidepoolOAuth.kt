package app.aaps.plugins.sync.tidepool.auth

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.net.toUri
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.crypto.CryptoUtil
import app.aaps.plugins.sync.tidepool.compose.TidepoolRepository
import app.aaps.plugins.sync.tidepool.keys.TidepoolStringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.browser.BrowserAllowList
import net.openid.appauth.browser.VersionedBrowserMatcher
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * JamOrHam
 *
 * The Tidepool OpenID login on Android: AppAuth with a Custom Tab. [AuthFlowIn] receives the redirect.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppAuthTidepoolOAuth(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val context: Context,
    private val cryptoUtil: CryptoUtil,
    private val tidepoolRepository: TidepoolRepository
) : TidepoolOAuth {

    companion object {

        private const val CLIENT_ID = "aaps"
        private const val REDIRECT_URI = "aaps://callback/tidepool"
        private const val INTEGRATION_BASE_URL = "https://auth.integration.tidepool.org/realms/integration"
        private const val PRODUCTION_BASE_URL = "https://auth.tidepool.org/realms/tidepool"

        /**
         * True when a token refresh failed only because of the network or the server. The saved
         * credentials are still good, so the next upload can try the silent refresh again. A refused
         * credential comes back as an OAuth token error instead (for example `invalid_grant`) and still
         * needs a new login in the browser.
         *
         * Three general errors of AppAuth mean "the connection failed", never "your login is bad":
         * - network error (`{"type":0,"code":3}`, the case reported in issue #4989)
         * - server error
         * - JSON error, which is what we get when the answer is not JSON at all. A hotel login page or
         *   an HTML error page of a proxy looks like this, so it is a connection problem as well.
         */
        /** What AppAuth's answer to a token request means for the uploader. */
        internal fun tokenFrom(accessToken: String?, ex: AuthorizationException?, hasTokenResponse: Boolean): TidepoolToken = when {
            accessToken == null && isTransientTokenError(ex) -> TidepoolToken.Unavailable(TidepoolToken.Kind.TRANSIENT, "$ex")
            accessToken == null                              -> TidepoolToken.Unavailable(TidepoolToken.Kind.REFUSED, "$ex")
            !hasTokenResponse                                -> TidepoolToken.Unavailable(TidepoolToken.Kind.NO_TOKEN_RESPONSE, "no token response")
            else                                             -> TidepoolToken.Valid(accessToken)
        }

        fun isTransientTokenError(ex: AuthorizationException?): Boolean =
            ex != null && ex.type == AuthorizationException.TYPE_GENERAL_ERROR &&
                (ex.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code ||
                    ex.code == AuthorizationException.GeneralErrors.SERVER_ERROR.code ||
                    ex.code == AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR.code)
    }

    /**
     * Built on first use, not at construction.
     *
     * `AuthorizationService`'s constructor inspects the installed browsers, which needs a real Android
     * environment - as a property initializer it made **building the object graph** do that, and threw
     * `ExceptionInInitializerError` in every plain-JVM graph test once Metro started constructing this
     * class. Nothing needs it before the first authorization request.
     */
    val authService: AuthorizationService by lazy {
        AuthorizationService(
            context, AppAuthConfiguration.Builder()
                .setBrowserMatcher(
                    BrowserAllowList(
                        VersionedBrowserMatcher.CHROME_CUSTOM_TAB,
                        VersionedBrowserMatcher.FIREFOX_CUSTOM_TAB,
                        VersionedBrowserMatcher.SAMSUNG_CUSTOM_TAB
                    )
                )
                .build()
        )
    }

    @Volatile var authState = AuthState()

    override val hasSavedLogin: Boolean get() = authState.lastTokenResponse != null

    override fun save() {
        preferences.put(TidepoolStringNonKey.AuthState, authState.jsonSerializeString())
    }

    @Synchronized
    override fun erase(includingServerConfiguration: Boolean) {
        if (includingServerConfiguration) preferences.put(TidepoolStringNonKey.ServiceConfiguration, "")
        preferences.put(TidepoolStringNonKey.AuthState, "")
        restore()
    }

    override suspend fun freshToken(): TidepoolToken = suspendCancellableCoroutine { continuation ->
        authState.performActionWithFreshTokens(authService) { accessToken, _, tokenException ->
            val token = tokenFrom(accessToken, tokenException, hasTokenResponse = authState.lastTokenResponse != null)
            if (token is TidepoolToken.Valid) save() // a refresh can also bring a new refresh token
            if (continuation.isActive) continuation.resume(token)
        }
    }

    @Synchronized
    override fun restore() {
        authState = AuthState()
        try {
            // Set from configuration if exists
            val serviceConfig = preferences.get(TidepoolStringNonKey.ServiceConfiguration)
            if (serviceConfig.isNotEmpty())
                authState = AuthState(AuthorizationServiceConfiguration.fromJson(serviceConfig))
            // Check if we have an authorized state
            val savedAuthState = preferences.get(TidepoolStringNonKey.AuthState)
            if (savedAuthState.isNotEmpty()) {
                authState = AuthState.jsonDeserialize(savedAuthState)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.TIDEPOOL, "Failed to restore auth state, resetting", e)
            authState = AuthState()
            preferences.put(TidepoolStringNonKey.AuthState, "")
            preferences.put(TidepoolStringNonKey.ServiceConfiguration, "")
        }
        // Never log the serialized state: it holds the access and refresh tokens, and logs get shared (#5206)
        aapsLogger.debug(LTag.TIDEPOOL, "Using auth state: authorized=${authState.isAuthorized} needsTokenRefresh=${authState.needsTokenRefresh}")
    }

    override fun startLogin(useTestServers: Boolean) {
        AuthorizationServiceConfiguration.fetchFromIssuer(
            if (useTestServers) INTEGRATION_BASE_URL.toUri()
            else PRODUCTION_BASE_URL.toUri(),
            AuthorizationServiceConfiguration.RetrieveConfigurationCallback { serviceConfiguration, exception ->
                if (exception != null || serviceConfiguration == null) {
                    tidepoolRepository.addLog("Failed to fetch configuration $exception")
                    return@RetrieveConfigurationCallback
                }
                preferences.put(TidepoolStringNonKey.ServiceConfiguration, serviceConfiguration.toJsonString())
                restore()

                val codeVerifierChallengeMethod = "S256"
                val encoding = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                val challenge = cryptoUtil.getRandomKey(64)
                val codeVerifier = Base64.encodeToString(challenge, encoding)
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(codeVerifier.toByteArray(StandardCharsets.UTF_8))
                val codeChallenge = Base64.encodeToString(hash, encoding)

                val authRequest =
                    AuthorizationRequest.Builder(
                        serviceConfiguration,  // the authorization service configuration
                        CLIENT_ID,  // the client ID, typically pre-registered and static
                        ResponseTypeValues.CODE,  // the response_type value: we want a code
                        REDIRECT_URI.toUri() // the redirect URI to which the auth response is sent
                    )
                        .setScopes("openid")
                        .setCodeVerifier(codeVerifier, codeChallenge, codeVerifierChallengeMethod)
                        .setPrompt("login")
                        .build()

                try {
                    authService.performAuthorizationRequest(
                        authRequest,
                        PendingIntent.getActivity(context, 0, Intent(context, AuthFlowIn::class.java), PendingIntent.FLAG_MUTABLE),
                        PendingIntent.getActivity(context, 0, Intent(context, AuthFlowIn::class.java), PendingIntent.FLAG_MUTABLE)
                    )
                } catch (e: ActivityNotFoundException) {
                    aapsLogger.error(LTag.TIDEPOOL, "No browser available for Tidepool login", e)
                    tidepoolRepository.addLog("No compatible browser installed. Please install Chrome or Firefox to log in to Tidepool.")
                }
            })
    }
}
