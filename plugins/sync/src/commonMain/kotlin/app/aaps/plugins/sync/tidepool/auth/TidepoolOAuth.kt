package app.aaps.plugins.sync.tidepool.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The answer to "give me an access token that still works". */
sealed interface TidepoolToken {

    data class Valid(val accessToken: String) : TidepoolToken

    /** No token. [kind] says whether the saved login is still good; [reason] is for the log. */
    data class Unavailable(val kind: Kind, val reason: String) : TidepoolToken

    enum class Kind {

        /**
         * Only the network or the server failed. The saved login is still good, so the next upload
         * tries again in silence instead of opening the login page (issue #4989).
         */
        TRANSIENT,

        /** Tidepool refused the saved login, for example `invalid_grant`. A new login is needed. */
        REFUSED,

        /** There is no saved token at all. A new login is needed. */
        NO_TOKEN_RESPONSE,
    }
}

/**
 * The OpenID login to Tidepool, which each platform does with its own browser API: AppAuth and a
 * Custom Tab on Android, `ASWebAuthenticationSession` on iOS. Client id `aaps`, redirect
 * `aaps://callback/tidepool`, PKCE, scope `openid`.
 *
 * Everything around it (the connection status, when to log in again, the uploads) is shared code in
 * [AuthFlowOut] and `TidepoolUploader`.
 */
interface TidepoolOAuth {

    /** A token from an earlier login is saved. It does not say that the token still works. */
    val hasSavedLogin: Boolean

    /** Read the saved login from the preferences. */
    fun restore()

    /** Write the login to the preferences. A refresh can bring a new refresh token. */
    fun save()

    /** Forget the saved login, and the server configuration as well when [includingServerConfiguration]. */
    fun erase(includingServerConfiguration: Boolean)

    /**
     * An access token that is still valid. The platform refreshes it when it is about to expire, and
     * saves the login after a refresh.
     */
    suspend fun freshToken(): TidepoolToken

    /** Open the Tidepool login page. When the user has logged in, a session is started. */
    fun startLogin(useTestServers: Boolean)

    /**
     * Emits after a login finished and the user id is saved, so the plugin can open a session at once.
     * Empty on Android, where the redirect activity `AuthFlowIn` opens the session itself.
     */
    val logins: Flow<Unit> get() = emptyFlow()
}
