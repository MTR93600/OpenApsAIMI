package app.aaps.plugins.sync.nsclientV3.remotecontrol

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.protection.PasswordHasher
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The two locks of the AIMI Remote Control on the follower phone.
 *
 * - The **access password** unlocks the screen for [SESSION_DURATION_MS]. Only a salted hash of it is
 *   stored.
 * - The **PIN** must be typed for every command and is sent with it. The master checks it against
 *   its own AIMI remote PIN, so both phones must use the same one.
 *
 * Port of `RemoteAccessAuthManager` and `NSClientPinManager` from dev_OAPSAIMI. Dev kept both in
 * Android-only encrypted files; here they are [RemoteControlStringKey] values, so the same code runs
 * on every platform. The unlock session is kept in memory only, so a restart of the app locks the
 * screen again.
 */
@SingleIn(AppScope::class)
@Inject
class RemoteControlAccess(
    private val preferences: Preferences,
    private val passwordHasher: PasswordHasher,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) {

    /** The end of the current unlock session, or 0 when locked. */
    private var unlockedUntil = 0L

    fun isPasswordConfigured(): Boolean = preferences.get(RemoteControlStringKey.AccessPasswordHash).isNotEmpty()

    /** Store a new access password. The caller checks [isPasswordValid] first. */
    fun configurePassword(password: String) {
        require(isPasswordValid(password)) { "Password must have at least $MIN_PASSWORD_LENGTH characters" }
        preferences.put(RemoteControlStringKey.AccessPasswordHash, passwordHasher.hashPassword(password))
        aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Password configured")
    }

    /** Check [password] and, when it is right, unlock the screen for [SESSION_DURATION_MS]. */
    fun verifyPassword(password: String): Boolean {
        val storedHash = preferences.get(RemoteControlStringKey.AccessPasswordHash)
        if (storedHash.isEmpty()) {
            aapsLogger.warn(LTag.NSCLIENT, "[RemoteAccess] No password configured")
            return false
        }
        val valid = passwordHasher.checkPassword(password, storedHash)
        if (valid) {
            unlockedUntil = dateUtil.now() + SESSION_DURATION_MS
            aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Password verified - session started")
        } else {
            aapsLogger.warn(LTag.NSCLIENT, "[RemoteAccess] Invalid password attempt")
        }
        return valid
    }

    fun isUnlocked(): Boolean = dateUtil.now() < unlockedUntil

    fun lock() {
        unlockedUntil = 0L
        aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Session locked")
    }

    fun isPinConfigured(): Boolean = preferences.get(RemoteControlStringKey.FollowerPin).isNotBlank()

    fun savePin(pin: String) {
        preferences.put(RemoteControlStringKey.FollowerPin, pin.trim())
    }

    /** True when a PIN is set and [pin] is the same, spaces around it ignored. */
    fun validatePin(pin: String): Boolean {
        val stored = preferences.get(RemoteControlStringKey.FollowerPin).trim()
        return stored.isNotEmpty() && pin.trim() == stored
    }

    companion object {

        const val SESSION_DURATION_MS = 30 * 60 * 1000L
        const val MIN_PASSWORD_LENGTH = 6
        const val MIN_PIN_LENGTH = 4

        fun isPasswordValid(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH
    }
}
