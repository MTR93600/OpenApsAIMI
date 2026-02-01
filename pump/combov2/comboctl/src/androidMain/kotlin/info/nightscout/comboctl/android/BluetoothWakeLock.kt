package info.nightscout.comboctl.android

import android.content.Context
import android.os.PowerManager
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = Logger.get("BluetoothWakeLock")

/**
 * RAII-style wake lock manager for Bluetooth operations.
 * 
 * Prevents Android from entering Doze mode during critical BT communication,
 * which would freeze the BT stack and cause timeouts/disconnections.
 * 
 * Forensic analysis (Jan 2026) identified Android 14 Doze deep sleep cycles as the
 * root cause of recurring Combo disconnections. This wake lock implementation ensures
 * the CPU stays awake during BT operations, preventing the OS from closing sockets.
 * 
 * Usage:
 * ```kotlin
 * bluetoothWakeLock.use(timeout = 3.minutes) {
 *     // BT operations here
 *     connect()
 *     sendData()
 * }
 * ```
 * 
 * Ref: docs/logs/FORENSIC_ANALYSIS_2026-01-02.md
 * 
 * @param context Android application context
 */
class BluetoothWakeLock(
    private val context: Context
) {
    private val wakeLock: PowerManager.WakeLock by lazy {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ComboCtl::BluetoothOperation"
        ).apply {
            setReferenceCounted(false) // Manual management for precise control
        }
    }
    
    @Volatile
    private var acquisitionCount = 0
    
    @Volatile
    private var lastAcquisitionTime = 0L
    
    /**
     * Acquire wake lock for a specified duration.
     * 
     * Prevents nested acquisitions from extending timeout indefinitely by tracking
     * the most recent acquisition and releasing earlier ones.
     * 
     * @param timeout Maximum duration to hold the wake lock (default: 3 minutes)
     * @throws IllegalArgumentException if timeout exceeds 10 minutes (safety limit)
     */
    @Synchronized
    fun acquire(timeout: Duration = 3.minutes) {
        require(timeout <= 10.minutes) {
            "Wake lock timeout must not exceed 10 minutes (requested: $timeout)"
        }
        
        val now = System.currentTimeMillis()
        val timeoutMs = timeout.inWholeMilliseconds
        
        if (wakeLock.isHeld) {
            logger(LogLevel.DEBUG) {
                "Wake lock already held (count=$acquisitionCount); " +
                "extending timeout to ${timeout.inWholeSeconds}s"
            }
            // Release and re-acquire with new timeout to "extend" properly
            wakeLock.release()
        }
        
        wakeLock.acquire(timeoutMs)
        acquisitionCount++
        lastAcquisitionTime = now
        
        logger(LogLevel.INFO) {
            "BT wake lock acquired (count=$acquisitionCount, timeout=${timeout.inWholeSeconds}s)"
        }
    }
    
    /**
     * Release wake lock if currently held.
     * 
     * Safe to call multiple times - no-op if not held.
     */
    @Synchronized
    fun release() {
        if (!wakeLock.isHeld) {
            logger(LogLevel.DEBUG) { "Wake lock already released - ignoring redundant call" }
            return
        }
        
        wakeLock.release()
        acquisitionCount--
        
        val heldDuration = System.currentTimeMillis() - lastAcquisitionTime
        logger(LogLevel.INFO) {
            "BT wake lock released (count=$acquisitionCount, held_duration=${heldDuration}ms)"
        }
    }
    
    /**
     * Check if wake lock is currently held.
     */
    fun isHeld(): Boolean = wakeLock.isHeld
    
    /**
     * Force release wake lock (for cleanup/emergency situations).
     * 
     * Ensures wake lock is released even if reference counting is off.
     */
    @Synchronized
    fun forceRelease() {
        if (wakeLock.isHeld) {
            logger(LogLevel.WARN) {
                "Force releasing wake lock (count=$acquisitionCount)"
            }
            wakeLock.release()
            acquisitionCount = 0
        }
    }
}

/**
 * Extension function for RAII-style wake lock management.
 * 
 * Automatically acquires wake lock before block execution and releases after,
 * even if an exception is thrown.
 * 
 * Example:
 * ```kotlin
 * bluetoothWakeLock.use(timeout = 2.minutes) {
 *     // BT operations
 *     if (error) throw Exception() // Wake lock still released
 * }
 * ```
 * 
 * @param timeout Max duration for wake lock
 * @param block Code to execute while holding wake lock
 * @return Result of block execution
 */
inline fun <T> BluetoothWakeLock.use(
    timeout: Duration = 3.minutes,
    block: () -> T
): T {
    acquire(timeout)
    try {
        return block()
    } finally {
        release()
    }
}

/**
 * Extension for suspending functions (coroutine-safe).
 * 
 * Properly handles cancellation - wake lock is released even if coroutine cancelled.
 */
suspend inline fun <T> BluetoothWakeLock.useSuspend(
    timeout: Duration = 3.minutes,
    crossinline block: suspend () -> T
): T {
    acquire(timeout)
    try {
        return block()
    } finally {
        release()
    }
}
