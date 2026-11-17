package app.aaps.plugins.sync.tidepool.auth

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.garmin.runOnMain
import app.aaps.plugins.sync.tidepool.comm.tidepoolHttpClient
import app.aaps.plugins.sync.tidepool.compose.TidepoolRepository
import app.aaps.plugins.sync.tidepool.keys.TidepoolStringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Tidepool OpenID login on iOS, with `ASWebAuthenticationSession`.
 *
 * The same client as Android: client id `aaps`, redirect `aaps://callback/tidepool`, PKCE (S256), scope
 * `openid`, `prompt=login`. `ASWebAuthenticationSession` catches the redirect itself, so the app does
 * not need to register the `aaps` URL scheme.
 *
 * What AppAuth does on Android is done here by hand: the OpenID discovery, the code exchange, the
 * refresh and the user id from the `userinfo` endpoint (`sub`), which every upload needs.
 *
 * The login is saved under the same preference as on Android, in an iOS format. A saved Android login
 * cannot be read here and simply counts as "not logged in".
 *
 * ⚠️ Not tested against the real Tidepool server yet: it needs a real account on a real iPhone.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosTidepoolOAuth(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val tidepoolRepository: TidepoolRepository,
) : TidepoolOAuth {

    /** A saved login. The endpoints come from the OpenID discovery of the server it was made with. */
    private data class Login(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
        val tokenEndpoint: String,
        val userInfoEndpoint: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private val refreshMutex = Mutex()
    private val loginEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var login: Login? = null

    // Held while the login page is open: ASWebAuthenticationSession is released if nothing holds it
    private var session: ASWebAuthenticationSession? = null
    private var anchorProvider: AnchorProvider? = null

    override val logins: Flow<Unit> = loginEvents.asSharedFlow()

    override val hasSavedLogin: Boolean get() = login != null

    override fun restore() {
        login = runCatching { readLogin(preferences.get(TidepoolStringNonKey.AuthState)) }.getOrNull()
        // Never log the tokens: they give full access to the data, and logs get shared (#5206)
        aapsLogger.debug(LTag.TIDEPOOL, "Using auth state: authorized=${login != null}")
    }

    override fun save() {
        preferences.put(TidepoolStringNonKey.AuthState, login?.let { writeLogin(it) } ?: "")
    }

    override fun erase(includingServerConfiguration: Boolean) {
        if (includingServerConfiguration) preferences.put(TidepoolStringNonKey.ServiceConfiguration, "")
        preferences.put(TidepoolStringNonKey.AuthState, "")
        login = null
    }

    override suspend fun freshToken(): TidepoolToken = refreshMutex.withLock {
        val current = login ?: return@withLock TidepoolToken.Unavailable(TidepoolToken.Kind.NO_TOKEN_RESPONSE, "not logged in")
        if (current.expiresAtMs - EXPIRY_MARGIN_MS > dateUtil.now()) return@withLock TidepoolToken.Valid(current.accessToken)
        val refreshToken = current.refreshToken ?: return@withLock TidepoolToken.Unavailable(TidepoolToken.Kind.REFUSED, "no refresh token")
        try {
            client().use { client ->
                val response = client.submitForm(current.tokenEndpoint, parameters {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", CLIENT_ID)
                })
                when {
                    response.status.value in 200..299 -> {
                        val refreshed = tokens(response, current.tokenEndpoint, current.userInfoEndpoint, refreshToken)
                            ?: return@withLock TidepoolToken.Unavailable(TidepoolToken.Kind.TRANSIENT, "token answer not readable")
                        login = refreshed
                        save() // a refresh can also bring a new refresh token
                        TidepoolToken.Valid(refreshed.accessToken)
                    }
                    // OAuth refuses a bad or expired refresh token with 400 (invalid_grant) or 401
                    response.status.value == 400 || response.status.value == 401 ->
                        TidepoolToken.Unavailable(TidepoolToken.Kind.REFUSED, "refresh refused ${response.status.value}")
                    // A server error or a captive portal: the saved login is still good
                    else -> TidepoolToken.Unavailable(TidepoolToken.Kind.TRANSIENT, "refresh ${response.status.value}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TidepoolToken.Unavailable(TidepoolToken.Kind.TRANSIENT, e.toString())
        }
    }

    override fun startLogin(useTestServers: Boolean) {
        scope.launch {
            try {
                val issuer = if (useTestServers) INTEGRATION_BASE_URL else PRODUCTION_BASE_URL
                val discovery = client().use { client ->
                    val response = client.get("$issuer/.well-known/openid-configuration")
                    if (response.status.value !in 200..299) error("discovery ${response.status.value}")
                    json.parseToJsonElement(response.bodyAsText()).jsonObject
                }
                val authorizationEndpoint = discovery.string("authorization_endpoint") ?: error("no authorization_endpoint")
                val tokenEndpoint = discovery.string("token_endpoint") ?: error("no token_endpoint")
                val userInfoEndpoint = discovery.string("userinfo_endpoint") ?: error("no userinfo_endpoint")
                preferences.put(TidepoolStringNonKey.ServiceConfiguration, discovery.toString())

                val codeVerifier = base64Url(randomBytes(64))
                val state = base64Url(randomBytes(16))
                val url = URLBuilder(authorizationEndpoint).apply {
                    parameters.append("client_id", CLIENT_ID)
                    parameters.append("response_type", "code")
                    parameters.append("redirect_uri", REDIRECT_URI)
                    parameters.append("scope", "openid")
                    parameters.append("prompt", "login")
                    parameters.append("state", state)
                    parameters.append("code_challenge", base64Url(sha256(codeVerifier.encodeToByteArray())))
                    parameters.append("code_challenge_method", "S256")
                }.buildString()
                runOnMain { openLoginPage(url, state, codeVerifier, tokenEndpoint, userInfoEndpoint) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tidepoolRepository.addLog("Failed to fetch configuration $e")
            }
        }
    }

    /** On the main queue: the session must be started there. */
    @OptIn(BetaInteropApi::class)
    private fun openLoginPage(url: String, state: String, codeVerifier: String, tokenEndpoint: String, userInfoEndpoint: String) {
        val authUrl = NSURL.URLWithString(url) ?: return tidepoolRepository.addLog("Bad login address")
        val webSession = ASWebAuthenticationSession(authUrl, CALLBACK_SCHEME) { callbackUrl, error ->
            session = null
            anchorProvider = null
            val code = callbackUrl?.queryValue("code")
            when {
                error != null                              -> tidepoolRepository.addLog("Login not finished: ${error.localizedDescription}")
                callbackUrl?.queryValue("state") != state -> tidepoolRepository.addLog("Login answer does not match the request")
                code == null                               -> tidepoolRepository.addLog("Login answer without a code")
                else                                       -> scope.launch { finishLogin(code, codeVerifier, tokenEndpoint, userInfoEndpoint) }
            }
        }
        val provider = AnchorProvider()
        webSession.presentationContextProvider = provider
        // A fresh login each time, like prompt=login on Android
        webSession.prefersEphemeralWebBrowserSession = true
        anchorProvider = provider
        session = webSession
        if (!webSession.start()) tidepoolRepository.addLog("Could not open the login page")
    }

    /** Exchange the code, save the login and the user id, then tell the plugin. */
    private suspend fun finishLogin(code: String, codeVerifier: String, tokenEndpoint: String, userInfoEndpoint: String) {
        try {
            val newLogin = client().use { client ->
                val response = client.submitForm(tokenEndpoint, parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", REDIRECT_URI)
                    append("client_id", CLIENT_ID)
                    append("code_verifier", codeVerifier)
                })
                if (response.status.value !in 200..299) error("token request ${response.status.value}")
                tokens(response, tokenEndpoint, userInfoEndpoint, previousRefreshToken = null) ?: error("token answer not readable")
            }
            aapsLogger.debug(LTag.TIDEPOOL, "Got first token")
            val userId = client().use { client ->
                val response = client.get(userInfoEndpoint) { header(HttpHeaders.Authorization, "Bearer ${newLogin.accessToken}") }
                if (response.status.value !in 200..299) error("userinfo ${response.status.value}")
                // Not the answer itself: it holds the user's name and e-mail (#5206)
                aapsLogger.debug(LTag.TIDEPOOL, "UserInfo received")
                json.parseToJsonElement(response.bodyAsText()).jsonObject.string("sub")
            }
            if (userId.isNullOrEmpty()) {
                aapsLogger.error(LTag.TIDEPOOL, "Could not get 'sub' field - cannot proceed")
                tidepoolRepository.addLog("Cannot read sub name")
                return
            }
            login = newLogin
            save()
            preferences.put(TidepoolStringNonKey.SubscriptionId, userId)
            loginEvents.tryEmit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.TIDEPOOL, "Login failed: $e")
            tidepoolRepository.addLog(e.toString())
        }
    }

    /** The login in a token answer, or null when it cannot be read. A refresh may leave the refresh token out. */
    private suspend fun tokens(response: HttpResponse, tokenEndpoint: String, userInfoEndpoint: String, previousRefreshToken: String?): Login? {
        val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return null
        val accessToken = body.string("access_token") ?: return null
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRES_IN_S
        return Login(
            accessToken = accessToken,
            refreshToken = body.string("refresh_token") ?: previousRefreshToken,
            expiresAtMs = dateUtil.now() + expiresIn * 1000L,
            tokenEndpoint = tokenEndpoint,
            userInfoEndpoint = userInfoEndpoint,
        )
    }

    private fun writeLogin(login: Login): String = buildJsonObject {
        put("format", "ios-1")
        put("accessToken", login.accessToken)
        login.refreshToken?.let { put("refreshToken", it) }
        put("expiresAtMs", login.expiresAtMs)
        put("tokenEndpoint", login.tokenEndpoint)
        put("userInfoEndpoint", login.userInfoEndpoint)
    }.toString()

    private fun readLogin(text: String): Login? {
        if (text.isBlank()) return null
        val obj = json.parseToJsonElement(text).jsonObject
        // A login saved by AppAuth on Android has another shape
        if (obj.string("format") != "ios-1") return null
        return Login(
            accessToken = obj.string("accessToken") ?: return null,
            refreshToken = obj.string("refreshToken"),
            expiresAtMs = (obj["expiresAtMs"] as? JsonPrimitive)?.long ?: 0L,
            tokenEndpoint = obj.string("tokenEndpoint") ?: return null,
            userInfoEndpoint = obj.string("userInfoEndpoint") ?: return null,
        )
    }

    private fun client(): HttpClient = tidepoolHttpClient { expectSuccess = false }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun NSURL.queryValue(name: String): String? =
        NSURLComponents(this, resolvingAgainstBaseURL = false).queryItems
            ?.firstOrNull { (it as? NSURLQueryItem)?.name == name }
            ?.let { (it as NSURLQueryItem).value }

    /** The window the login sheet is shown over. */
    private class AnchorProvider : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {

        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor {
            val windows = UIApplication.sharedApplication.connectedScenes
                .mapNotNull { it as? UIWindowScene }
                .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }
            return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: UIWindow()
        }
    }

    companion object {

        private const val CLIENT_ID = "aaps"
        private const val REDIRECT_URI = "aaps://callback/tidepool"
        private const val CALLBACK_SCHEME = "aaps"
        private const val INTEGRATION_BASE_URL = "https://auth.integration.tidepool.org/realms/integration"
        private const val PRODUCTION_BASE_URL = "https://auth.tidepool.org/realms/tidepool"

        // Refresh a little before the token runs out, so a call never starts with a token about to die
        private const val EXPIRY_MARGIN_MS = 60_000L

        // When the answer has no expires_in: Tidepool's access tokens live about 10 minutes
        private const val DEFAULT_EXPIRES_IN_S = 300L

        @OptIn(ExperimentalEncodingApi::class)
        private fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

        @OptIn(ExperimentalForeignApi::class)
        private fun randomBytes(size: Int): ByteArray {
            val bytes = ByteArray(size)
            val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
            check(status == 0) { "no secure random bytes" }
            return bytes
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun sha256(input: ByteArray): ByteArray {
            val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
            input.usePinned { inPin ->
                out.usePinned { outPin ->
                    CC_SHA256(if (input.isEmpty()) null else inPin.addressOf(0), input.size.convert(), outPin.addressOf(0))
                }
            }
            return out.toByteArray()
        }
    }
}
