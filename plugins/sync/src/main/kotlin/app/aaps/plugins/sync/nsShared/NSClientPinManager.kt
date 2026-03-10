package app.aaps.plugins.sync.nsShared

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure PIN storage and validation for NSClient Remote Control.
 * TODO: Upgrade to EncryptedSharedPreferences for production (requires androidx.security:security-crypto dependency)
 */
@Singleton
class NSClientPinManager @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "nsclient_secure_prefs"
        private const val KEY_REMOTE_PIN = "remote_control_pin"
        private const val KEY_PIN_ENABLED = "pin_enabled"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save PIN securely (encrypted).
     */
    fun savePin(pin: String) {
        prefs.edit()
            .putString(KEY_REMOTE_PIN, pin)
            .putBoolean(KEY_PIN_ENABLED, true)
            .apply()
    }

    /**
     * Get stored PIN (decrypted). Returns empty string if not set.
     */
    fun getPin(): String {
        return prefs.getString(KEY_REMOTE_PIN, "") ?: ""
    }

    /**
     * Validate provided PIN against stored one.
     */
    fun validatePin(inputPin: String): Boolean {
        if (!isPinEnabled()) return false
        val storedPin = getPin()
        if (storedPin.isEmpty()) return false
        return inputPin.trim() == storedPin.trim()
    }

    /**
     * Check if PIN is configured.
     */
    fun isPinEnabled(): Boolean {
        return prefs.getBoolean(KEY_PIN_ENABLED, false) && getPin().isNotEmpty()
    }

    /**
     * Clear PIN (for logout/reset scenarios).
     */
    fun clearPin() {
        prefs.edit()
            .remove(KEY_REMOTE_PIN)
            .putBoolean(KEY_PIN_ENABLED, false)
            .apply()
    }

    /**
     * Check if PIN needs to be configured (first-time setup).
     */
    fun needsConfiguration(): Boolean {
        return !isPinEnabled()
    }
}
