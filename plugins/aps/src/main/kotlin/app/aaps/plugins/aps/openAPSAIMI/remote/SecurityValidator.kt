package app.aaps.plugins.aps.openAPSAIMI.remote

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates Security for Remote AIMI Commands.
 */
@Singleton
class SecurityValidator @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    /**
     * Checks if the provided PIN matches the stored one.
     * Returns TRUE if valid, FALSE otherwise.
     */
    fun validatePin(pin: String): Boolean {
        val storedPin = preferences.get(AimiStringKey.RemoteControlPin)
        
        if (storedPin.isBlank()) {
            aapsLogger.warn(LTag.APS, "[Remote] No PIN configured in preferences. Remote commands disabled.")
            return false
        }
        
        if (pin.trim() == storedPin.trim()) {
            return true
        } else {
            aapsLogger.warn(LTag.APS, "[Remote] Invalid PIN attempt: $pin")
            return false
        }
    }

    /**
     * WhiteList for Settings Modification.
     * Only "safe" settings can be changed remotely.
     */
    fun isSettingAllowed(key: String): Boolean {
        // Example allowed keys
        return when (key) {
            "example_key_safe_to_change" -> true
            else -> false
        }
    }
}
