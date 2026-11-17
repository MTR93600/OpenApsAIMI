package app.aaps.plugins.sync.tidepool.auth

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.tidepool.compose.TidepoolRepository
import app.aaps.plugins.sync.tidepool.keys.TidepoolBooleanKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

/**
 * JamOrHam
 *
 * The Tidepool login state, shared by Android and iOS. The login itself goes through [TidepoolOAuth],
 * which each platform does with its own browser API.
 */
@SingleIn(AppScope::class)
@Inject
class AuthFlowOut(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val tidepoolRepository: TidepoolRepository,
    private val oauth: TidepoolOAuth,
) {

    enum class ConnectionStatus {
        NONE, BLOCKED, NOT_LOGGED_IN, NO_SESSION, FETCHING_TOKEN, SESSION_ESTABLISHED, FAILED
    }

    var connectionStatus: ConnectionStatus = ConnectionStatus.NONE
        private set
        get() =
            if (field == ConnectionStatus.NONE) {
                if (hasSavedLogin) ConnectionStatus.NO_SESSION
                else ConnectionStatus.NOT_LOGGED_IN
            } else field

    /** A token from an earlier login is saved. It does not say that the token still works. */
    val hasSavedLogin: Boolean get() = oauth.hasSavedLogin

    fun updateConnectionStatus(newStatus: ConnectionStatus? = null, message: String? = null) {
        aapsLogger.debug(LTag.TIDEPOOL, "updateConnectionStatus: $newStatus $message")
        newStatus?.let { connectionStatus = it }
        message?.let { tidepoolRepository.addLog(it) }
        // The derived value, so a reset to NONE shows as NOT_LOGGED_IN or NO_SESSION
        tidepoolRepository.updateConnectionStatus(connectionStatus)
    }

    fun saveAuthState() = oauth.save()

    fun eraseAuthState(message: String?) {
        oauth.erase(includingServerConfiguration = false)
        updateConnectionStatus(ConnectionStatus.NONE, message)
    }

    fun initAuthState() = oauth.restore()

    fun clearAllSavedData() {
        oauth.erase(includingServerConfiguration = true)
        updateConnectionStatus(ConnectionStatus.NONE, "Credentials cleared")
    }

    /** See [TidepoolOAuth.freshToken]. */
    suspend fun freshToken(): TidepoolToken = oauth.freshToken()

    /** See [TidepoolOAuth.logins]. */
    val logins: Flow<Unit> get() = oauth.logins

    fun doTidePoolInitialLogin(@Suppress("unused") from: String) {
        tidepoolRepository.addLog("Opening login screen")
        oauth.startLogin(preferences.get(TidepoolBooleanKey.UseTestServers))
    }
}
