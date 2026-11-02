package app.aaps.plugins.eversense

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelUuid
import androidx.core.content.edit
import app.aaps.plugins.eversense.callbacks.EversenseScanCallback
import app.aaps.plugins.eversense.callbacks.EversenseWatcher
import app.aaps.plugins.eversense.enums.CalibrationReadiness
import app.aaps.plugins.eversense.models.EversenseState
import app.aaps.plugins.eversense.models.EversenseTransmitterSettings
import app.aaps.plugins.eversense.packets.Eversense365Communicator
import app.aaps.plugins.eversense.packets.EversenseE3Communicator
import app.aaps.plugins.eversense.packets.e3.EnterDiagnosticModePacket
import app.aaps.plugins.eversense.packets.e3.ExitDiagnosticModePacket
import app.aaps.plugins.eversense.packets.e365.EnterDiagnosticMode365Packet
import app.aaps.plugins.eversense.packets.e365.ExitDiagnosticMode365Packet
import app.aaps.plugins.eversense.packets.e365.GetSignalStrengthPacket
import app.aaps.plugins.eversense.packets.e365.SetBloodGlucosePointPacket365
import app.aaps.plugins.eversense.packets.e3.GetCalibrationReadinessPacket
import app.aaps.plugins.eversense.packets.e3.GetSignalStrengthRawPacket
import app.aaps.plugins.eversense.util.EversenseCrypto365Util
import app.aaps.plugins.eversense.util.EversenseLogger
import app.aaps.plugins.eversense.util.EversenseScanner
import app.aaps.plugins.eversense.util.StorageKeys
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class EversenseCGMPlugin {

    // FIX 1: Use ApplicationContext to avoid leaking Activity context.
    private var context: Context? = null

    private var bluetoothManager: BluetoothManager? = null
    private var preferences: SharedPreferences? = null
    private var gattCallback: EversenseGattCallback? = null

    // FIX 2: Lock object for synchronized access to connection state.
    private val connectionLock = Any()

    private var scanner: EversenseScanner? = null

    // Thread-safe: watchers are added/removed on main thread but iterated from bleExecutor.
    val watchers: MutableList<EversenseWatcher> = CopyOnWriteArrayList()

    // Credentials set by AAPS layer before any DMS login attempt
    var username: String = ""
    var password: String = ""

    fun setContext(context: Context, loggingEnabled: Boolean) {
        // FIX 1: Always store applicationContext.
        this.context = context.applicationContext
        EversenseLogger.instance.enableLogging(loggingEnabled)

        val preference = context.applicationContext.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        bluetoothManager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        preferences = preference
        gattCallback = EversenseGattCallback(this, preference)
    }


    fun addWatcher(watcher: EversenseWatcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher)
    }

    fun removeWatcher(watcher: EversenseWatcher) {
        watchers.remove(watcher)
    }

    fun isConnected(): Boolean = gattCallback?.isConnected() ?: false
    fun is365(): Boolean = gattCallback?.is365() ?: false

    fun getCurrentState(): EversenseState? {
        val preferences = preferences ?: run {
            EversenseLogger.error(TAG, "No preferences available. Make sure setContext has been called")
            return null
        }
        val stateJson = preferences.getString(StorageKeys.STATE, null) ?: "{}"
        return JSON.decodeFromString<EversenseState>(stateJson)
    }

    @SuppressLint("MissingPermission")
    fun startScan(callback: EversenseScanCallback) {
        val bluetoothScanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: run {
            EversenseLogger.error(TAG, "No bluetooth manager available. Make sure setContext has been called")
            return
        }
        scanner = EversenseScanner(callback)
        // Scan without service UUID filter — Eversense transmitters may not always advertise
        // the service UUID before pairing. Show all BLE devices so user can identify their transmitter.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetoothScanner.startScan(null, settings, scanner)
        EversenseLogger.info(TAG, "BLE scan started")
    }

    // FIX 4: Added public stopScan() so callers can cancel scanning independently.
    @SuppressLint("MissingPermission")
    fun stopScan() {
        val bluetoothScanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: run {
            EversenseLogger.error(TAG, "No bluetooth scanner available when trying to stop scan")
            return
        }
        scanner?.let {
            bluetoothScanner.stopScan(it)
            scanner = null
            EversenseLogger.info(TAG, "Scan stopped")
        } ?: EversenseLogger.info(TAG, "stopScan called but no active scan found")
    }

    // Android doesn't always deliver onConnectionStateChange when the Bluetooth radio itself
    // is powered off (observed in the wild: no disconnect callback at all across a BT toggle),
    // so gattCallback's internal "connected" flag can be left stale true. A live BLE connection
    // cannot survive the whole adapter going off, so plain connect() would wrongly short-circuit
    // on isConnected() and do nothing. Call this instead of connect() when reacting to
    // BluetoothAdapter.ACTION_STATE_CHANGED -> STATE_ON.
    @SuppressLint("MissingPermission")
    fun forceReconnect(): Boolean {
        gattCallback?.cleanUp()
        return connect(null)
    }

    // FIX 5: connect() now explicitly differentiates between supplied device vs stored device.
    // FIX 6: Synchronized block prevents race condition on connection state check.
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice? = null): Boolean {
        val bluetoothManager = this.bluetoothManager ?: run {
            EversenseLogger.error(TAG, "No bluetooth manager available. Make sure setContext has been called")
            return false
        }
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.error(TAG, "No gattCallback available. Make sure setContext has been called")
            return false
        }

        stopScan()

        synchronized(connectionLock) {
            if (gattCallback.isConnected()) {
                EversenseLogger.info(TAG, "Already connected, skipping reconnect")
                return true
            }

            if (bluetoothManager.adapter?.isEnabled != true) {
                // connectGatt() while radio off never fires any callback.
                // EversensePlugin's ACTION_STATE_CHANGED receiver calls forceReconnect() when STATE_ON.
                EversenseLogger.warning(TAG, "Bluetooth is disabled — skipping connect attempt, will retry once it's back on")
                return false
            }

            // Clean up stale GATT connections before connecting
            gattCallback.cleanUp()

            return if (device != null) {
                EversenseLogger.info(TAG, "Connecting to supplied device: ${device.name}")
                // Save address so we can auto-reconnect after app restart or phone reboot
                preferences?.edit()?.putString(StorageKeys.REMOTE_DEVICE_KEY, device.address)?.apply()
                EversenseLogger.info(TAG, "Saved device address for auto-reconnect: ${device.address}")
                // A transmitter picked from the scan list is not necessarily the one the cached
                // "shortcut auth" session was agreed with. canUseShortcut() is a plain saved flag,
                // it is not tied to any transmitter identity, so for newly picked hardware the
                // cached shortcut is wrong. Without this, the 365 auth flow tries it anyway, the
                // new transmitter rejects it and drops the link, and we only recover after
                // SHORTCUT_FAIL_THRESHOLD (3) failed connect/auth/disconnect cycles. Ask for the
                // full WhoAmI + DMS login + fleet certificate handshake on the first attempt
                // instead. E3 is unaffected: its auth flow has no shortcut.
                preferences?.let { EversenseCrypto365Util(it).disallowUseShortcut() }
                // autoConnect = false here, unlike the stored-device branch below. This device was
                // just found by an active scan and has never been bonded or connected before.
                // autoConnect = true asks Android to connect "whenever the device shows up", which
                // is meant for a device it already knows. For a brand new device it is a known way
                // to get no GATT callback and no pairing prompt at all, silently and forever.
                // false asks for one direct connection attempt right now, which is what we want
                // for a first pairing. If that attempt fails, onConnectionStateChange fires with a
                // real status and scheduleReconnect() retries through the stored-device branch,
                // which still uses autoConnect = true.
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                true
            } else {
                val address = preferences?.getString(StorageKeys.REMOTE_DEVICE_KEY, null) ?: run {
                    EversenseLogger.error(TAG, "No device supplied and no stored device address found.")
                    return false
                }
                val remoteDevice = bluetoothManager.adapter.getRemoteDevice(address) ?: run {
                    EversenseLogger.error(TAG, "Could not retrieve remote device for address $address")
                    return false
                }
                EversenseLogger.info(TAG, "Reconnecting to stored device: $address")
                remoteDevice.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                true
            }
        }
    }

    // FIX 7: Added disconnect() which calls both disconnect() and close() on the GATT client.
    fun clearStoredDevice() {
        preferences?.edit()?.remove(StorageKeys.REMOTE_DEVICE_KEY)?.apply()
        EversenseLogger.info(TAG, "Cleared stored device address")
    }

    fun disconnect() {
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.info(TAG, "disconnect() called but no gattCallback exists")
            return
        }
        if (!gattCallback.isConnected()) {
            EversenseLogger.info(TAG, "disconnect() called but not currently connected")
            return
        }
        gattCallback.disconnect()
        EversenseLogger.info(TAG, "Disconnected from transmitter")
    }

    // Flag to track positioning mode state for safe diagnostic mode toggling across BLE reconnects
    var isPositioningMode: Boolean = false

    fun enterPositioningMode() {
        isPositioningMode = true
        setDiagnosticMode(true)
        EversenseLogger.info(TAG, "Diagnostic Mode ENABLED: Positioning active")
    }

    fun exitPositioningMode() {
        isPositioningMode = false
        setDiagnosticMode(false)
        EversenseLogger.info(TAG, "Diagnostic Mode DISABLED: Power saving active")
    }

    /**
     * Enable or disable diagnostic mode on the transmitter.
     * Ported from iOS PlacementGuideViewModel which calls setDiagnosticMode(true)
     * on init and setDiagnosticMode(false) in stop(). Diagnostic mode increases
     * signal-strength update frequency to ~500ms for accurate placement feedback.
     * Must be called from a background thread as it performs a BLE write.
     */
    fun setDiagnosticMode(isEnabled: Boolean) {
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.warning(TAG, "Cannot set diagnostic mode — no gattCallback")
            return
        }
        if (!gattCallback.isConnected()) {
            EversenseLogger.warning(TAG, "Cannot set diagnostic mode — not connected")
            return
        }
        try {
            // Run on the bleExecutor, like every other write, so this cannot race a write that
            // the Keep Alive sync cycle already started.
            val future = gattCallback.submitToExecutor { writeDiagnosticMode(gattCallback, isEnabled) }
            future.get(BLE_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            EversenseLogger.warning(TAG, "setDiagnosticMode failed: $e")
        }
    }

    /**
     * Same as [setDiagnosticMode], but for callers that already run on the bleExecutor thread.
     * The bleExecutor has one thread only, so such a caller must never submit a task and then
     * wait for it: the new task cannot start until the caller returns.
     */
    fun setDiagnosticModeOnExecutor(isEnabled: Boolean) {
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.warning(TAG, "Cannot set diagnostic mode — no gattCallback")
            return
        }
        try {
            writeDiagnosticMode(gattCallback, isEnabled)
        } catch (e: Exception) {
            EversenseLogger.warning(TAG, "setDiagnosticModeOnExecutor failed: $e")
        }
    }

    // Writes the enter/exit diagnostic mode packets. Must run on the bleExecutor thread.
    // Diagnostic mode increases how often the transmitter reports signal strength, which the
    // placement guide needs. E3 uses Enter/ExitDiagnosticMode commands; 365 uses Operation
    // packets with enter/exit operation IDs.
    private fun writeDiagnosticMode(gattCallback: EversenseGattCallback, isEnabled: Boolean) {
        if (gattCallback.is365()) {
            if (isEnabled) {
                gattCallback.writePacket<EnterDiagnosticMode365Packet.Response>(EnterDiagnosticMode365Packet())
            } else {
                gattCallback.writePacket<ExitDiagnosticMode365Packet.Response>(ExitDiagnosticMode365Packet())
            }
            EversenseLogger.info(TAG, "Diagnostic mode set to $isEnabled (365)")
        } else {
            if (isEnabled) {
                gattCallback.writePacket<EnterDiagnosticModePacket.Response>(EnterDiagnosticModePacket())
            } else {
                gattCallback.writePacket<ExitDiagnosticModePacket.Response>(ExitDiagnosticModePacket())
            }
            EversenseLogger.info(TAG, "Diagnostic mode set to $isEnabled (E3)")
        }
    }

    fun writeSettings(settings: EversenseTransmitterSettings): Boolean {
        val preferences = preferences ?: run {
            EversenseLogger.error(TAG, "No preferences available. Make sure setContext has been called")
            return false
        }
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.error(TAG, "No gattCallback available. Make sure transmitter is connected before writing settings")
            return false
        }
        if (!gattCallback.isConnected()) {
            EversenseLogger.error(TAG, "Transmitter is not connected")
            return false
        }
        return EversenseE3Communicator.writeSettings(gattCallback, preferences, settings)
    }


    // Send a blood glucose calibration value to the transmitter.
    // Returns true if the packet was sent successfully, false otherwise.
    fun sendCalibration(glucoseMgDl: Int, timestampMs: Long = System.currentTimeMillis()): Boolean {
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.error(TAG, "No gattCallback available. Make sure transmitter is connected before calibrating")
            return false
        }
        if (!gattCallback.isConnected()) {
            EversenseLogger.error(TAG, "Transmitter is not connected")
            return false
        }
        if (getCurrentState() == null) {
            EversenseLogger.error(TAG, "Cannot calibrate: state is null")
            return false
        }
        return try {
            val future = gattCallback.submitToExecutor {
                if (gattCallback.is365()) {
                    val packet = SetBloodGlucosePointPacket365(glucoseMgDl, timestampMs)
                    gattCallback.writePacket<SetBloodGlucosePointPacket365.Response>(packet)
                    EversenseLogger.info(TAG, "365 calibration sent: $glucoseMgDl mg/dL")
                } else {
                    EversenseE3Communicator.sendCalibration(gattCallback, glucoseMgDl)
                }
            }
            future.get(BLE_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val prefs = preferences ?: return true
            val stateJson = prefs.getString(StorageKeys.STATE, null) ?: "{}"
            val updatedState = JSON.decodeFromString<EversenseState>(stateJson)
            updatedState.lastCalibrationDate = timestampMs
            updatedState.nextCalibrationDate = timestampMs + 24 * 60 * 60 * 1000L
            updatedState.calibrationReadiness = CalibrationReadiness.WAITING_POST_CALIBRATION
            prefs.edit(commit = true) {
                putString(StorageKeys.STATE, JSON.encodeToString(updatedState))
            }
            EversenseLogger.info(TAG, "Updated calibration state: lastCalibrationDate=$timestampMs, readiness=WAITING_POST_CALIBRATION")
            if (!gattCallback.is365()) {
                gattCallback.submitToExecutor {
                    try {
                        val readinessResponse = gattCallback.writePacket<GetCalibrationReadinessPacket.Response>(
                            GetCalibrationReadinessPacket()
                        )
                        val currentStateJson = prefs.getString(StorageKeys.STATE, null) ?: "{}"
                        val currentState = JSON.decodeFromString<EversenseState>(currentStateJson)
                        currentState.calibrationReadiness = readinessResponse.readiness
                        prefs.edit(commit = true) {
                            putString(StorageKeys.STATE, JSON.encodeToString(currentState))
                        }
                        EversenseLogger.info(TAG, "Post-calibration readiness re-read: ${readinessResponse.readiness}")
                        watchers.forEach { it.onStateChanged(currentState) }
                    } catch (e: Exception) {
                        EversenseLogger.warning(TAG, "Post-calibration readiness re-read failed (non-fatal): $e")
                    }
                }
            }
            true
        } catch (e: Exception) {
            EversenseLogger.error(TAG, "Failed to send calibration: $e")
            false
        }
    }

    // Submit fullSync to bleExecutor so it runs on the same thread as BLE callbacks.
    // Called from onTransmitterReady to sync immediately on connect without waiting for Keep Alive.
    fun submitToExecutorAndSync(force: Boolean = false) {
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.error(TAG, "Cannot sync — no gattCallback available")
            return
        }
        val preferences = preferences ?: run {
            EversenseLogger.error(TAG, "Cannot sync — no preferences available")
            return
        }
        if (!gattCallback.isConnected()) {
            EversenseLogger.error(TAG, "Cannot sync — not connected")
            return
        }
        if (gattCallback.is365()) {
            EversenseLogger.info(TAG, "365 transmitter — skipping submitToExecutorAndSync (authV2flow handles it)")
            return
        }
        gattCallback.submitToExecutor {
            if (isPositioningMode) {
                // Already on the bleExecutor thread here, so write directly.
                setDiagnosticModeOnExecutor(true)
                EversenseLogger.info(TAG, "Re-enabled Diagnostic Mode after reconnect")
            }
            EversenseLogger.info(TAG, "Running E3 fullSync on bleExecutor after connect")
            EversenseE3Communicator.fullSync(gattCallback, preferences, watchers.toList(), force)
        }
    }

    // Triggers both a full sync and a glucose read on the connected transmitter.
    fun triggerFullSync(force: Boolean = false) {
        val gattCallback = this.gattCallback ?: run {
            EversenseLogger.error(TAG, "Cannot sync — no gattCallback available")
            return
        }
        val preferences = preferences ?: run {
            EversenseLogger.error(TAG, "Cannot sync — no preferences available")
            return
        }
        if (!gattCallback.isConnected()) {
            EversenseLogger.error(TAG, "Cannot sync — not connected")
            return
        }
        EversenseLogger.info(TAG, "Triggering full sync on user request")
        gattCallback.submitToExecutor {
            if (gattCallback.is365()) {
                Eversense365Communicator.fullSync(gattCallback, preferences, watchers.toList(), force)
                Eversense365Communicator.readGlucose(gattCallback, preferences, watchers.toList())
            } else {
                EversenseE3Communicator.fullSync(gattCallback, preferences, watchers.toList(), force)
                EversenseE3Communicator.readGlucose(gattCallback, preferences, watchers.toList())
            }
            gattCallback.readRssi()
        }
    }

    // Called by EversenseGattCallback when RSSI is read
    fun onRssiRead(rssi: Int) {
        val preferences = preferences ?: return
        val stateJson = preferences.getString(StorageKeys.STATE, null) ?: "{}"
        val state = JSON.decodeFromString<EversenseState>(stateJson)
        state.placementSignalRssi = rssi
        state.sensorSignalStrength = rssiToStrength(rssi)
        preferences.edit()?.putString(StorageKeys.STATE, JSON.encodeToString(state))?.apply()
        EversenseLogger.debug(TAG, "RSSI updated: $rssi dBm")
        watchers.forEach { it.onStateChanged(state) }
    }


    // Read transmitter-to-sensor signal strength.
    // Tries the Eversense 365 ReadSignalStrength packet first.
    // Falls back to BLE RSSI for E3 transmitters which don't support the packet.
    fun readSignalStrength() {
        val gattCallback = this.gattCallback ?: run { EversenseLogger.error(TAG, "Cannot read signal strength — no gattCallback"); return }
        val preferences = this.preferences ?: run { EversenseLogger.error(TAG, "Cannot read signal strength — no preferences"); return }
        if (!gattCallback.isConnected()) { EversenseLogger.warning(TAG, "Cannot read signal strength — not connected"); return }
        try {
            // Run on the bleExecutor, like every other write, so this cannot race a write that
            // the Keep Alive sync cycle already started.
            // Callers must not be on the bleExecutor thread — see submitToExecutor.
            val future = gattCallback.submitToExecutor {
                if (gattCallback.is365()) {
                    val response = gattCallback.writePacket<GetSignalStrengthPacket.Response>(GetSignalStrengthPacket())
                    response.signalStrength
                } else {
                    val response = gattCallback.writePacket<GetSignalStrengthRawPacket.Response>(GetSignalStrengthRawPacket())
                    EversenseLogger.info(TAG, "E3 signal raw: ${response.rawValue} -> ${response.signalStrength}%")
                    response.signalStrength
                }
            }
            val signalStrength = future.get(BLE_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val stateJson = preferences.getString(StorageKeys.STATE, null) ?: "{}"
            val state = JSON.decodeFromString<EversenseState>(stateJson)
            state.sensorSignalStrength = signalStrength
            preferences.edit()?.putString(StorageKeys.STATE, JSON.encodeToString(state))?.apply()
            EversenseLogger.info(TAG, "Signal strength: $signalStrength%")
            watchers.forEach { it.onStateChanged(state) }
        } catch (e: Exception) {
            EversenseLogger.warning(TAG, "readSignalStrength failed: $e")
            gattCallback.readRssi()
        }
    }

        private fun rssiToStrength(rssi: Int): Int = when {
        rssi == 0   -> 0
        rssi >= -65 -> 100
        rssi >= -75 -> 80
        rssi >= -85 -> 60
        rssi >= -95 -> 40
        else        -> 20
    }

        fun readRssi() {
        gattCallback?.readRssi()
    }

    companion object {
        private const val TAG = "EversenseCGMManager"

        // How long a caller waits for a task it queued on the single-threaded bleExecutor.
        private const val BLE_TASK_TIMEOUT_MS = 20_000L

        // ignoreUnknownKeys: tolerates firmware version differences between E3 and 365 transmitters.
        private val JSON = Json { ignoreUnknownKeys = true }

        val instance: EversenseCGMPlugin by lazy {
            EversenseCGMPlugin()
        }
    }
}









