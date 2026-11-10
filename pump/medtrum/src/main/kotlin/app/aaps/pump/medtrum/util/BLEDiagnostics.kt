package app.aaps.pump.medtrum.util

import android.bluetooth.BluetoothGatt
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for diagnosing and logging BLE connection states.
 * Helps identify zombie states and connection issues.
 *
 * Usage:
 * - Call logConnectionState() at key BLE lifecycle points
 * - Call checkForZombieState() periodically to detect stuck connections
 * - Review logs with tag PUMPBTCOMM to diagnose issues
 */
@Singleton
class BLEDiagnostics @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    data class BLEConnectionSnapshot(
        val timestamp: Long,
        val gattExists: Boolean,
        val isConnected: Boolean,
        val isConnecting: Boolean,
        val lastActivityMs: Long,
        val pendingOperations: Int,
        val threadName: String
    )

    private val stateHistory = mutableListOf<BLEConnectionSnapshot>()
    private val maxHistory = 50

    /**
     * Log detailed BLE connection state.
     * Call this at all important BLE lifecycle events.
     */
    fun logConnectionState(
        context: String,
        gatt: BluetoothGatt?,
        isConnected: Boolean,
        isConnecting: Boolean,
        lastActivityTimestamp: Long,
        pendingOperations: Int = 0
    ) {
        val snapshot = BLEConnectionSnapshot(
            timestamp = System.currentTimeMillis(),
            gattExists = gatt != null,
            isConnected = isConnected,
            isConnecting = isConnecting,
            lastActivityMs = System.currentTimeMillis() - lastActivityTimestamp,
            pendingOperations = pendingOperations,
            threadName = Thread.currentThread().name
        )

        stateHistory.add(snapshot)
        if (stateHistory.size > maxHistory) {
            stateHistory.removeAt(0)
        }

        aapsLogger.debug(LTag.PUMPBTCOMM, buildStateLog(context, snapshot))
    }

    /**
     * Check if the current BLE state indicates a zombie connection.
     *
     * Zombie indicators:
     * - BluetoothGatt exists but no activity for >threshold while "connected"
     * - isConnecting=true for >connecting threshold
     * - Gatt exists but isConnected=false and isConnecting=false
     *
     * @return true if zombie state detected
     */
    fun checkForZombieState(
        gatt: BluetoothGatt?,
        isConnected: Boolean,
        isConnecting: Boolean,
        lastActivityTimestamp: Long,
        connectedStaleThresholdMs: Long = 90_000L,
        connectingStaleThresholdMs: Long = 30_000L
    ): Boolean {
        val now = System.currentTimeMillis()
        val inactivityMs = now - lastActivityTimestamp

        val isZombie = when {
            gatt != null && isConnected && inactivityMs > connectedStaleThresholdMs -> {
                aapsLogger.error(
                    LTag.PUMPBTCOMM,
                    "ZOMBIE DETECTED: Connected but no activity for ${inactivityMs}ms"
                )
                true
            }

            gatt != null && isConnecting && inactivityMs > connectingStaleThresholdMs -> {
                aapsLogger.error(
                    LTag.PUMPBTCOMM,
                    "ZOMBIE DETECTED: Connecting for ${inactivityMs}ms"
                )
                true
            }

            gatt != null && !isConnected && !isConnecting -> {
                aapsLogger.warn(
                    LTag.PUMPBTCOMM,
                    "INCONSISTENT STATE: Gatt exists but not connected/connecting"
                )
                true
            }

            else -> false
        }

        if (isZombie) {
            logStateHistory()
        }

        return isZombie
    }

    /**
     * Get a formatted report of the BLE state for debugging.
     */
    fun getStateReport(
        gatt: BluetoothGatt?,
        isConnected: Boolean,
        isConnecting: Boolean,
        lastActivityTimestamp: Long
    ): String {
        val now = System.currentTimeMillis()
        val snapshot = BLEConnectionSnapshot(
            timestamp = now,
            gattExists = gatt != null,
            isConnected = isConnected,
            isConnecting = isConnecting,
            lastActivityMs = now - lastActivityTimestamp,
            pendingOperations = 0,
            threadName = Thread.currentThread().name
        )

        return buildStateLog("STATE_REPORT", snapshot)
    }

    /**
     * Log the entire state history (useful when zombie is detected).
     */
    private fun logStateHistory() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "=== BLE STATE HISTORY (last ${stateHistory.size} events) ===")
        stateHistory.forEachIndexed { index, snapshot ->
            val relativeTime = if (index > 0) {
                "+${snapshot.timestamp - stateHistory[index - 1].timestamp}ms"
            } else {
                "START"
            }
            aapsLogger.debug(
                LTag.PUMPBTCOMM,
                "[$index] $relativeTime - ${formatSnapshot(snapshot)}"
            )
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "=== END HISTORY ===")
    }

    /**
     * Clear the state history (call on successful connect/disconnect).
     */
    fun clearHistory() {
        stateHistory.clear()
        aapsLogger.debug(LTag.PUMPBTCOMM, "BLE state history cleared")
    }

    private fun buildStateLog(context: String, snapshot: BLEConnectionSnapshot): String {
        return """
            === BLE State [$context] ===
            Timestamp: ${snapshot.timestamp}
            Gatt Exists: ${snapshot.gattExists}
            Is Connected: ${snapshot.isConnected}
            Is Connecting: ${snapshot.isConnecting}
            Last Activity: ${snapshot.lastActivityMs}ms ago
            Pending Ops: ${snapshot.pendingOperations}
            Thread: ${snapshot.threadName}
            ============================
        """.trimIndent()
    }

    private fun formatSnapshot(snapshot: BLEConnectionSnapshot): String {
        return "gatt=${snapshot.gattExists} conn=${snapshot.isConnected} " +
            "connecting=${snapshot.isConnecting} lastAct=${snapshot.lastActivityMs}ms " +
            "thread=${snapshot.threadName}"
    }
}
