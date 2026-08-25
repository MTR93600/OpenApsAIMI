package app.aaps.pump.medtrum.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.BleAdapter
import app.aaps.core.interfaces.pump.ble.BleGatt
import app.aaps.core.interfaces.pump.ble.BleScanner
import app.aaps.core.interfaces.pump.ble.BleTransportListener
import app.aaps.core.interfaces.pump.ble.PairingState
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowSnackbar
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.comm.ManufacturerData
import app.aaps.pump.medtrum.comm.ReadDataPacket
import app.aaps.pump.medtrum.comm.WriteCommandPackets
import app.aaps.pump.medtrum.extension.toInt
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import app.aaps.pump.medtrum.util.BLEDiagnostics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("MissingPermission")
@Singleton
class MedtrumBleTransportImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val bleDiagnostics: BLEDiagnostics
) : MedtrumBleTransport {

    companion object {

        private const val WRITE_DELAY_MILLIS = 10L
        private const val DISCONNECT_FORCE_RESET_TIMEOUT_MS = 1500L
        private const val ZOMBIE_CHECK_INTERVAL_MS = 30_000L
        private const val CONNECTING_ZOMBIE_THRESHOLD_MS = 45_000L
        private const val CONNECTED_STALE_THRESHOLD_MS = 360_000L
        private const val GATT_REFRESH_DELAY_MS = 150L
        private const val SERVICE_UUID = "669A9001-0008-968F-E311-6050405558B3"
        private const val READ_UUID = "669a9120-0008-968f-e311-6050405558b3"
        private const val WRITE_UUID = "669a9101-0008-968f-e311-6050405558b3"
        private const val CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val NEEDS_ENABLE_NOTIFICATION = 0x10
        private const val NEEDS_ENABLE_INDICATION = 0x20
        private const val NEEDS_ENABLE = 0x30
        private const val MANUFACTURER_ID = 18305
    }

    private val handler = Handler(HandlerThread("MedtrumBleHandler").also { it.start() }.looper)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    // GATT state
    private var bluetoothGatt: BluetoothGatt? = null
    private var uartRead: BluetoothGattCharacteristic? = null
    private var uartWrite: BluetoothGattCharacteristic? = null

    // Connection tracking
    private var isConnected = false
    private var isConnecting = false
    private var lastBleActivityMs = 0L
    private val pendingRunnables = mutableListOf<Runnable>()
    private var zombieCheckRunnable: Runnable? = null
    private var disconnectTimeoutRunnable: Runnable? = null

    // Write/read packet reassembly
    private var writePackets: WriteCommandPackets? = null
    private var writeSequenceNumber = 0
    private var readPacket: ReadDataPacket? = null
    private val readLock = Any()

    // Cached device for reconnection (in-memory, cleared on app restart → triggers new scan)
    private var cachedDeviceAddress: String? = null
    private var cachedDeviceSN: Long = 0

    // Address pre-seeded from wizard BLE scan selection (consumed on first connect)
    private var wizardSelectedAddress: String? = null

    // Callbacks / listeners
    private var medtrumCallback: MedtrumBleCallback? = null
    private var transportListener: BleTransportListener? = null

    // Connection scan callback (auto-connects on SN match)
    private var connectionScanCallback: ScanCallback? = null

    // --- BleTransport ---

    override val adapter: BleAdapter = MedtrumAdapterImpl()
    override val scanner: BleScanner = MedtrumScannerImpl()
    override val gatt: BleGatt = MedtrumGattImpl()

    private val _pairingState = MutableStateFlow(PairingState())
    override val pairingState: StateFlow<PairingState> = _pairingState

    override fun updatePairingState(state: PairingState) {
        _pairingState.value = state
    }

    override fun setListener(listener: BleTransportListener?) {
        transportListener = listener
    }

    // --- MedtrumBleTransport ---

    override fun setMedtrumCallback(callback: MedtrumBleCallback?) {
        medtrumCallback = callback
    }

    override fun setCachedAddress(address: String) {
        wizardSelectedAddress = address
    }

    @Synchronized
    override fun connect(from: String, deviceSN: Long): Boolean {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT) || !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            rxBus.send(EventShowSnackbar(context.getString(app.aaps.core.ui.R.string.need_connect_permission), EventShowSnackbar.Type.Error))
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return false
        }
        if (bluetoothAdapter == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "no BluetoothAdapter: $from")
            return false
        }

        isConnected = false
        isConnecting = true
        writePackets = null
        readPacket = null
        touchBleActivity()
        startZombieWatchdog()
        bleDiagnostics.logConnectionState(
            "connect",
            bluetoothGatt,
            isConnected,
            isConnecting,
            lastBleActivityMs,
            pendingRunnables.size
        )

        val wizardAddr = wizardSelectedAddress?.also { wizardSelectedAddress = null }
        when {
            wizardAddr != null                                        -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Using wizard-selected address: $wizardAddr")
                cachedDeviceAddress = wizardAddr
                cachedDeviceSN = deviceSN
                bluetoothAdapter?.getRemoteDevice(wizardAddr)?.let { connectGatt(it) }
            }

            cachedDeviceAddress != null && cachedDeviceSN == deviceSN -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Skipping scan, connecting directly to cached address")
                bluetoothAdapter?.getRemoteDevice(cachedDeviceAddress)?.let { connectGatt(it) }
            }

            // The wizard retries on every disconnect and the command queue calls connect() on its
            // own, and both land here while the service state machine is still Idle. Without this,
            // each of them starts one more scan for the same pump.
            connectionScanCallback != null && cachedDeviceSN == deviceSN -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Connection scan for SN $deviceSN already running ($from)")
            }

            else                                                      -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "No cached address, scanning for deviceSN: $deviceSN")
                cachedDeviceAddress = null
                cachedDeviceSN = deviceSN
                startConnectionScan(deviceSN)
            }
        }
        return true
    }

    @Synchronized
    override fun disconnect(from: String) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
        if (isConnecting) {
            isConnecting = false
            stopConnectionScan()
            SystemClock.sleep(100)
        }
        cancelDisconnectTimeout()
        if (bluetoothGatt != null) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Gatt present, requesting disconnect with force-reset fallback")
            val timeoutRunnable = Runnable {
                synchronized(this@MedtrumBleTransportImpl) {
                    if (bluetoothGatt != null) {
                        aapsLogger.warn(
                            LTag.PUMPBTCOMM,
                            "Disconnect timeout (${DISCONNECT_FORCE_RESET_TIMEOUT_MS}ms), forcing GATT reset"
                        )
                        forceResetBluetoothGatt("disconnect_timeout")
                        medtrumCallback?.onDisconnected()
                    }
                }
            }
            disconnectTimeoutRunnable = timeoutRunnable
            pendingRunnables.add(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, DISCONNECT_FORCE_RESET_TIMEOUT_MS)
            bluetoothGatt?.disconnect()
        } else {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Gatt is null, ensuring closed state")
            forceResetBluetoothGatt("disconnect_null_gatt")
            medtrumCallback?.onDisconnected()
        }
    }

    @Synchronized
    override fun sendMessage(message: ByteArray) {
        touchBleActivity()
        aapsLogger.debug(LTag.PUMPBTCOMM, "sendMessage: ${message.contentToString()}")
        if (writePackets?.allPacketsConsumed() == false) {
            aapsLogger.error(LTag.PUMPBTCOMM, "sendMessage: previous packets not consumed, dropping")
            return
        }
        writePackets = WriteCommandPackets(message, writeSequenceNumber)
        writeSequenceNumber = (writeSequenceNumber + 1) % 256
        val first = writePackets?.getNextPacket()
        if (first != null) {
            writeCharacteristicInternal(uartWriteChar, first)
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "sendMessage: error building write packet")
            medtrumCallback?.onSendMessageError("error in writePacket!", false)
        }
    }

    // --- GATT callback ---

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            touchBleActivity()
            onConnectionStateChangeSynchronized(gatt, status, newState)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            touchBleActivity()
            aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) findCharacteristic()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            touchBleActivity()
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicChanged UUID: ${characteristic.uuid}")
            val value = characteristic.value
            when (characteristic.uuid) {
                UUID.fromString(READ_UUID)  -> medtrumCallback?.onNotification(value)
                UUID.fromString(WRITE_UUID) -> handleIndication(value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            touchBleActivity()
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicWrite status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writePackets?.let { packets ->
                    synchronized(packets) {
                        val next = packets.getNextPacket()
                        if (next != null) writeCharacteristicInternal(uartWriteChar, next)
                    }
                }
            } else {
                medtrumCallback?.onSendMessageError("onCharacteristicWrite failure", true)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            touchBleActivity()
            aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorWrite status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) readDescriptor(descriptor)
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            touchBleActivity()
            aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorRead status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) checkDescriptor(descriptor)
        }
    }

    // --- Internal helpers ---

    private fun handleIndication(value: ByteArray) {
        synchronized(readLock) {
            if (readPacket == null) {
                readPacket = ReadDataPacket(value)
            } else {
                readPacket?.addData(value)
            }
            if (readPacket?.allDataReceived() == true) {
                if (readPacket?.failed() == true) {
                    medtrumCallback?.onSendMessageError("ReadDataPacket failed", false)
                } else {
                    readPacket?.getData()?.let { medtrumCallback?.onIndication(it) }
                }
                readPacket = null
            }
        }
    }

    // connectGatt(Context, ...) is deprecated from API 37 in favour of an overload taking
    // BluetoothGattConnectionSettings, a class that does not exist below API 37 while our minSdk is 31.
    // This module does not depend on :core:utils, so it cannot use connectGattCompat from there.
    @Suppress("DEPRECATION")
    @Synchronized
    private fun connectGatt(device: BluetoothDevice) {
        stopConnectionScan()
        writeSequenceNumber = 0
        touchBleActivity()
        forceResetBluetoothGatt("connectGatt_prepare")
        handler.post {
            cancelBluetoothDiscoveryBeforeConnect()
            MedtrumBleBondUtil.logBondStateIfRelevant(device, aapsLogger)
            val gatt = openGattConnection(device)
            if (gatt == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "connectGatt failed for ${device.address}")
                isConnecting = false
                medtrumCallback?.onDisconnected()
            } else {
                bluetoothGatt = gatt
            }
        }
    }

    private fun openGattConnection(device: BluetoothDevice): BluetoothGatt? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        } else {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

    @Synchronized
    private fun onConnectionStateChangeSynchronized(gatt: BluetoothGatt, status: Int, newState: Int) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "onConnectionStateChange newState: $newState status: $status")
        if (bluetoothGatt != null && bluetoothGatt !== gatt) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Ignoring stale GATT callback for ${gatt.device.address}")
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.error(LTag.PUMPBTCOMM, "GATT connect failed status=$status, force reset")
                isConnecting = false
                isConnected = false
                clearCachedAddressIfScanOnError("connect_failed")
                forceResetBluetoothGatt("connect_failed")
                medtrumCallback?.onDisconnected()
                return
            }
            cancelDisconnectTimeout()
            isConnected = true
            isConnecting = false
            touchBleActivity()
            startZombieWatchdog()
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (status != BluetoothGatt.GATT_SUCCESS && status != 0) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "GATT disconnected with status=$status")
            }
            cancelDisconnectTimeout()
            if (isConnecting) {
                clearCachedAddressIfScanOnError("disconnected_while_connecting")
                SystemClock.sleep(2000)
            }
            forceResetBluetoothGatt("onConnectionStateChange_disconnected")
            medtrumCallback?.onDisconnected()
            aapsLogger.debug(LTag.PUMPBTCOMM, "Device disconnected: ${gatt.device.name}")
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            handleNotInitialized(); return
        }
        bluetoothGatt?.setCharacteristicNotification(characteristic, enabled)
        characteristic?.getDescriptor(UUID.fromString(CONFIG_UUID))?.let { descriptor ->
            val gatt = bluetoothGatt ?: return@let
            when {
                characteristic.properties and NEEDS_ENABLE_NOTIFICATION > 0 -> {
                    writeDescriptorValue(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }

                characteristic.properties and NEEDS_ENABLE_INDICATION > 0   -> {
                    writeDescriptorValue(gatt, descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                }
            }
        }
    }

    @Synchronized
    private fun readDescriptor(descriptor: BluetoothGattDescriptor?) {
        if (bluetoothAdapter == null || bluetoothGatt == null || descriptor == null) {
            handleNotInitialized(); return
        }
        bluetoothGatt?.readDescriptor(descriptor)
    }

    @Suppress("DEPRECATION")
    private fun checkDescriptor(descriptor: BluetoothGattDescriptor) {
        val service = getGattService() ?: return
        if (descriptor.value.toInt() <= 0) return
        val allEnabled = service.characteristics.all { char ->
            val cfg = char.getDescriptor(UUID.fromString(CONFIG_UUID))
            cfg?.value != null && cfg.value.toInt() > 0
        }
        if (allEnabled) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "All notifications enabled, connected!")
            cachedDeviceAddress = bluetoothGatt?.device?.address
            touchBleActivity()
            bleDiagnostics.clearHistory()
            startZombieWatchdog()
            medtrumCallback?.onConnected()
        }
    }

    private fun findCharacteristic() {
        val service = getGattService() ?: return
        service.characteristics.forEachIndexed { i, char ->
            if (char.properties and NEEDS_ENABLE > 0) {
                handler.postDelayed({
                                        val uuid = char.uuid.toString()
                                        setCharacteristicNotification(char, true)
                                        if (READ_UUID == uuid) uartRead = char
                                        if (WRITE_UUID == uuid) uartWrite = char
                                    }, i * 600L)
            }
        }
    }

    private fun getGattService(): BluetoothGattService? {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            handleNotInitialized(); return null
        }
        return bluetoothGatt?.getService(UUID.fromString(SERVICE_UUID))
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun writeCharacteristicInternal(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        handler.postDelayed({
            val gatt = bluetoothGatt
            if (bluetoothAdapter == null || gatt == null) {
                handleNotInitialized()
            } else {
                aapsLogger.debug(LTag.PUMPBTCOMM, "writeCharacteristic: ${data.contentToString()}")
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    characteristic.value = data
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(characteristic)
                }
                if (!ok) {
                    medtrumCallback?.onSendMessageError("Failed to write characteristic", true)
                }
            }
        }, WRITE_DELAY_MILLIS)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorValue(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    private val uartWriteChar: BluetoothGattCharacteristic
        get() = uartWrite ?: BluetoothGattCharacteristic(UUID.fromString(WRITE_UUID), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, 0).also { uartWrite = it }

    private fun handleNotInitialized() {
        aapsLogger.error(LTag.PUMPBTCOMM, "BluetoothAdapter or Gatt not initialized")
        isConnecting = false
        isConnected = false
        medtrumCallback?.onDisconnected()
    }

    private fun touchBleActivity() {
        lastBleActivityMs = System.currentTimeMillis()
    }

    private fun clearCachedAddressIfScanOnError(context: String) {
        if (preferences.get(MedtrumBooleanKey.MedtrumScanOnConnectionErrors)) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "$context: clearing cached BLE address (scan on connection errors enabled)")
            cachedDeviceAddress = null
        }
    }

    private fun cancelBluetoothDiscoveryBeforeConnect() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Cancelling classic BT discovery before GATT connect")
                bluetoothAdapter?.cancelDiscovery()
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "cancelDiscovery failed: ${e.message}")
        }
    }

    private fun cancelDisconnectTimeout() {
        disconnectTimeoutRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            pendingRunnables.remove(runnable)
        }
        disconnectTimeoutRunnable = null
    }

    private fun clearPendingRunnablesExceptZombie() {
        val zombie = zombieCheckRunnable
        pendingRunnables.forEach { runnable ->
            if (runnable !== zombie) {
                handler.removeCallbacks(runnable)
            }
        }
        pendingRunnables.removeAll { it !== zombie }
        disconnectTimeoutRunnable = null
    }

    @Synchronized
    private fun forceResetBluetoothGatt(reason: String) {
        aapsLogger.warn(LTag.PUMPBTCOMM, "=== FORCE RESET BLUETOOTH GATT ($reason) START ===")
        bleDiagnostics.logConnectionState(
            "forceReset_before",
            bluetoothGatt,
            isConnected,
            isConnecting,
            lastBleActivityMs,
            pendingRunnables.size
        )

        val gattToClose = bluetoothGatt
        stopZombieWatchdog()
        clearPendingRunnablesExceptZombie()
        stopConnectionScan()

        if (gattToClose != null) {
            try {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Force reset: calling disconnect()")
                gattToClose.disconnect()
                SystemClock.sleep(GATT_REFRESH_DELAY_MS)
                try {
                    val refreshMethod = gattToClose.javaClass.getMethod("refresh")
                    val refreshResult = refreshMethod.invoke(gattToClose) as? Boolean
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Force reset: GATT cache refresh result: $refreshResult")
                    SystemClock.sleep(GATT_REFRESH_DELAY_MS)
                } catch (e: Exception) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Force reset: failed to refresh GATT cache", e)
                }
                aapsLogger.debug(LTag.PUMPBTCOMM, "Force reset: calling close()")
                gattToClose.close()
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Force reset: exception during cleanup", e)
            }
        }

        bluetoothGatt = null
        uartRead = null
        uartWrite = null
        writePackets = null
        readPacket = null
        isConnected = false
        isConnecting = false
        bleDiagnostics.clearHistory()
        aapsLogger.warn(LTag.PUMPBTCOMM, "=== FORCE RESET BLUETOOTH GATT ($reason) COMPLETE ===")
    }

    private fun startZombieWatchdog() {
        if (zombieCheckRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                synchronized(this@MedtrumBleTransportImpl) {
                    val now = System.currentTimeMillis()
                    val inactivityMs = now - lastBleActivityMs
                    val gatt = bluetoothGatt

                    if (gatt != null && isConnecting && inactivityMs > CONNECTING_ZOMBIE_THRESHOLD_MS) {
                        aapsLogger.error(
                            LTag.PUMPBTCOMM,
                            "ZOMBIE: connecting stalled for ${inactivityMs}ms (threshold ${CONNECTING_ZOMBIE_THRESHOLD_MS}ms)"
                        )
                        clearCachedAddressIfScanOnError("connecting_zombie")
                        forceResetBluetoothGatt("connecting_zombie")
                        medtrumCallback?.onDisconnected()
                    } else if (gatt != null && isConnected && inactivityMs > CONNECTED_STALE_THRESHOLD_MS) {
                        aapsLogger.error(
                            LTag.PUMPBTCOMM,
                            "ZOMBIE: connected but stale for ${inactivityMs}ms (threshold ${CONNECTED_STALE_THRESHOLD_MS}ms)"
                        )
                        forceResetBluetoothGatt("connected_stale")
                        medtrumCallback?.onDisconnected()
                    } else if (gatt != null && isConnected && inactivityMs > CONNECTED_STALE_THRESHOLD_MS / 2) {
                        aapsLogger.warn(
                            LTag.PUMPBTCOMM,
                            "BLE communication slow: ${inactivityMs}ms since last activity"
                        )
                    }

                    if (zombieCheckRunnable != null) {
                        handler.postDelayed(this, ZOMBIE_CHECK_INTERVAL_MS)
                    }
                }
            }
        }
        zombieCheckRunnable = runnable
        pendingRunnables.add(runnable)
        handler.postDelayed(runnable, ZOMBIE_CHECK_INTERVAL_MS)
    }

    private fun stopZombieWatchdog() {
        zombieCheckRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            pendingRunnables.remove(runnable)
        }
        zombieCheckRunnable = null
    }

    // --- Connection scan (auto-connects on SN match) ---

    @Synchronized
    private fun startConnectionScan(deviceSN: Long) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        aapsLogger.debug(LTag.PUMPBTCOMM, "startConnectionScan for SN: $deviceSN")
        // Never overwrite a running scan. The old callback is the only handle the platform accepts
        // for stopScan, so losing it leaves a SCAN_MODE_LOW_LATENCY scan registered until the
        // process dies. A few of those and Android silently stops delivering results to this app,
        // which looks exactly like a pump that cannot be found any more.
        stopConnectionScan()
        connectionScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mfData = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)?.let { ManufacturerData(it) }
                aapsLogger.debug(LTag.PUMPBTCOMM, "ConnectionScan found SN: ${mfData?.getDeviceSN()}")
                if (mfData?.getDeviceSN() == deviceSN) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Found target device! SN: ${mfData.getDeviceSN()}")
                    stopConnectionScan()
                    cachedDeviceAddress = result.device.address
                    connectGatt(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Connection scan failed: $errorCode")
                stopConnectionScan()
                isConnecting = false
                medtrumCallback?.onDisconnected()
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setDeviceName("MT").build())
        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, connectionScanCallback)
        } catch (_: IllegalStateException) { /* BT off */
        }
    }

    @Synchronized
    private fun stopConnectionScan() {
        try {
            connectionScanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
        } catch (_: IllegalStateException) { /* BT off */
        }
        connectionScanCallback = null
    }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // --- BleAdapter ---

    private inner class MedtrumAdapterImpl : BleAdapter {

        override fun enable() = Unit

        override fun getDeviceName(address: String): String? {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return null
            return bluetoothAdapter?.getRemoteDevice(address)?.name
        }

        override fun isDeviceBonded(address: String): Boolean {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return false
            return bluetoothAdapter?.getRemoteDevice(address)?.bondState != BluetoothDevice.BOND_NONE
        }

        override fun createBond(address: String): Boolean {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return false
            return bluetoothAdapter?.getRemoteDevice(address)?.createBond() == true
        }

        override fun removeBond(address: String) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
            try {
                bluetoothAdapter?.bondedDevices?.firstOrNull { it.address == address }?.let {
                    it.javaClass.getMethod("removeBond").invoke(it)
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Error removing bond", e)
            }
        }
    }

    // --- BleScanner (discovery — used by wizard BLE scan step) ---

    private inner class MedtrumScannerImpl : BleScanner {

        private var scanCallback: ScanCallback? = null
        private val _scannedDevices = MutableSharedFlow<ScannedDevice>(extraBufferCapacity = 10)
        override val scannedDevices: SharedFlow<ScannedDevice> = _scannedDevices

        override fun startScan() {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device?.name ?: return
                    val mfData = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)?.let { ManufacturerData(it) }
                    val sn = mfData?.getDeviceSN()
                    val displayName = if (sn != null && sn > 0) "MT-${sn.toString(16).uppercase()}" else name
                    _scannedDevices.tryEmit(
                        ScannedDevice(
                            name = displayName,
                            address = result.device.address,
                            scanRecordBytes = result.scanRecord?.bytes
                        )
                    )
                }

                override fun onScanFailed(errorCode: Int) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Discovery scan failed: $errorCode")
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val filters = listOf(ScanFilter.Builder().setDeviceName("MT").build())
            try {
                bluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            } catch (_: IllegalStateException) { /* BT off */
            }
        }

        override fun stopScan() {
            try {
                scanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
            } catch (_: IllegalStateException) { /* BT off */
            }
            scanCallback = null
        }
    }

    // --- BleGatt ---

    private inner class MedtrumGattImpl : BleGatt {

        override fun connect(address: String): Boolean {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return false
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
            isConnecting = true
            connectGatt(device)
            return true
        }

        override fun disconnect() {
            bluetoothGatt?.disconnect()
        }

        override fun close() {
            forceResetBluetoothGatt("gatt_close")
        }

        override fun discoverServices() {
            bluetoothGatt?.discoverServices()
        }

        override fun findCharacteristics(): Boolean = uartRead != null && uartWrite != null

        override fun enableNotifications() {
            // Handled internally via findCharacteristic() after services are discovered
        }

        override fun writeCharacteristic(data: ByteArray) {
            writeCharacteristicInternal(uartWriteChar, data)
        }
    }
}
