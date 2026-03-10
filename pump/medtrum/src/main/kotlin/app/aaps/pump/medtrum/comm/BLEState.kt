package app.aaps.pump.medtrum.comm

import android.bluetooth.BluetoothGatt

/**
 * Sealed class representing the state of a BLE connection.
 * This is a thread-safe, type-safe representation that replaces the previous
 * Boolean flags (isConnected, isConnecting).
 * 
 * States are mutually exclusive - the connection can only be in ONE state at a time.
 */
sealed class BLEState {
    
    /**
     * No active BLE connection.
     * This is the initial state and the state after a clean disconnection.
     */
    object Disconnected : BLEState() {
        override fun toString() = "Disconnected"
    }
    
    /**
     * Connection in progress.
     * Scan may be active, or waiting for onConnectionStateChange callback.
     */
    object Connecting : BLEState() {
        override fun toString() = "Connecting"
    }
    
    /**
     * Successfully connected to the device.
     * BluetoothGatt instance is available and services have been discovered.
     * 
     * @property gatt The active BluetoothGatt connection
     * @property lastActivityTimestamp Timestamp of last BLE activity (for zombie detection)
     */
    data class Connected(
        val gatt: BluetoothGatt,
        val lastActivityTimestamp: Long = System.currentTimeMillis()
    ) : BLEState() {
        override fun toString() = "Connected(gatt=${gatt.device.address})"
        
        /**
         * Update the last activity timestamp (called on every BLE callback).
         */
        fun updateActivity() = copy(lastActivityTimestamp = System.currentTimeMillis())
        
        /**
         * Check if this connection might be in a zombie state.
         * @param thresholdMs Time without activity to consider zombie (default 90s)
         * @return true if no activity for longer than threshold
         */
        fun isPotentiallyZombie(thresholdMs: Long = 90_000): Boolean {
            return System.currentTimeMillis() - lastActivityTimestamp > thresholdMs
        }
    }
    
    /**
     * Disconnection in progress.
     * disconnect() has been called, waiting for cleanup to complete.
     */
    object Disconnecting : BLEState() {
        override fun toString() = "Disconnecting"
    }
    
    /**
     * An error occurred during connection or communication.
     * The connection should be considered unusable and will transition to Disconnected.
     * 
     * @property reason Human-readable error description
     * @property code Optional Android BLE error code (e.g., GATT_SUCCESS, GATT_FAILURE)
     * @property exception Optional exception that caused the error
     */
    data class Error(
        val reason: String,
        val code: Int? = null,
        val exception: Throwable? = null
    ) : BLEState() {
        override fun toString() = "Error(reason='$reason', code=$code)"
    }
    
    /**
     * Helper methods for state checks
     */
    fun isConnected(): Boolean = this is Connected
    fun isConnecting(): Boolean = this is Connecting
    fun isDisconnected(): Boolean = this is Disconnected
    fun isError(): Boolean = this is Error
    
    /**
     * Get the BluetoothGatt if connected, null otherwise.
     */
    fun gattOrNull(): BluetoothGatt? = (this as? Connected)?.gatt
}
