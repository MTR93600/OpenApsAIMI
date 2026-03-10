package info.nightscout.comboctl.android

import android.content.Context
import android.os.PowerManager
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger

private val logger = Logger.get("DozeMonitor")

/**
 * Utility class for monitoring Android Doze mode state.
 * 
 * Doze mode can significantly impact Bluetooth latency, causing delays
 * of 30-60 seconds in BT operations. This class helps detect and log
 * Doze mode to facilitate debugging of BT disconnections.
 */
object DozeMonitor {
    
    /**
     * Check if device is currently in Doze mode.
     * 
     * @param context Android context
     * @return true if device is in idle/doze mode, false otherwise
     */
    fun isInDozeMode(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isDeviceIdleMode == true
    }
    
    /**
     * Check if power save mode is enabled (battery saver).
     * 
     * @param context Android context
     * @return true if power save mode is active, false otherwise
     */
    fun isPowerSaveMode(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isPowerSaveMode == true
    }
    
    /**
     * Get a descriptive string about current power state.
     * 
     * @param context Android context
     * @return Human-readable power state description
     */
    fun getPowerStateDescription(context: Context): String {
        val isDoze = isInDozeMode(context)
        val isPowerSave = isPowerSaveMode(context)
        
        return when {
            isDoze -> "Doze Mode - BT latency 30-60s expected"
            isPowerSave -> "Power Save Mode - BT latency slightly elevated"
            else -> "Normal Mode - BT latency nominal"
        }
    }
    
    /**
     * Log current power state at INFO level.
     * 
     * Should be called before critical BT operations (connect, setTbr, etc.)
     * to help correlate disconnections with Doze mode.
     * 
     * @param context Android context
     * @param operation Name of the operation about to be performed
     */
    fun logPowerState(context: Context, operation: String) {
        val state = getPowerStateDescription(context)
        logger(LogLevel.INFO) { "Doze Monitor for $operation: $state" }
    }
    
    /**
     * Check if BT operations should use extended timeouts.
     * 
     * Returns true if device is in any power-saving mode that might
     * affect BT latency.
     * 
     * @param context Android context
     * @return true if extended timeouts recommended
     */
    fun shouldUseExtendedTimeouts(context: Context): Boolean {
        return isInDozeMode(context) || isPowerSaveMode(context)
    }
}
