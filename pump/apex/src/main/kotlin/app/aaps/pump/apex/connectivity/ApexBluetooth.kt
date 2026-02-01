package app.aaps.pump.apex.connectivity

import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.toHex
import app.aaps.pump.apex.ApexDriverStatus
import app.aaps.pump.apex.R
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.utils.keys.ApexStringKey
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class ApexBluetooth @Inject constructor(
    val aapsLogger: AAPSLogger,
    val preferences: Preferences,
    val context: Context,
    val uiInteraction: UiInteraction,
    val apexDriverStatus: ApexDriverStatus,
) {
    companion object {
        private val READ_SERVICE = ParcelUuid.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val WRITE_SERVICE = ParcelUuid.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")

        private val READ_UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        private val WRITE_UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9B34FB")
        private val CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val WRITE_DELAY_MS = 1500
    }

    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var callback: ApexBluetoothCallback? = null

    private var bluetoothDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    private var mtu: Int = 512

    private val readMutex = Mutex()
    private var lastCommand: PumpCommand? = null
    private var _status: Status = Status.DISCONNECTED

    val status: Status
        get() = _status

    private var prevCommandMs = SystemClock.uptimeMillis()
    private val connectionLock = Mutex()
    private var connectionId: Int = 0

    fun setCallback(callback: ApexBluetoothCallback) {
        this.callback = callback
    }

    private fun tickDelay() {
        if (!connectionLock.isLocked) prevCommandMs = SystemClock.uptimeMillis()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun send(command: DeviceCommand) = synchronized(connectionLock) {
        if (checkBT())  {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but BT is not ready")
            return
        }
        if (status != Status.CONNECTED) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but pump is disconnected")
            return
        }
        if (writeCharacteristic == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but the write characteristic is null")
            return
        }
        if (bluetoothGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but GATT is null")
            return
        }

        val delta = WRITE_DELAY_MS - SystemClock.uptimeMillis() + prevCommandMs
        if (delta > 0) SystemClock.sleep(delta)
        prevCommandMs = SystemClock.uptimeMillis()

        val data = command.serialize()
        var start = 0
        while (start < data.size) {
            val end = min(start + mtu, data.size)
            val chunk = data.copyOfRange(start, end)

            aapsLogger.debug(LTag.PUMPBTCOMM, "DEVICE[$start] -> ${chunk.toHex()}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt!!.writeCharacteristic(
                    writeCharacteristic!!,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                writeCharacteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeCharacteristic!!.setValue(chunk)
                bluetoothGatt!!.writeCharacteristic(writeCharacteristic!!)
            }

            start = end
        }
    }

    @SuppressLint("MissingPermission")
    fun connect() = synchronized (connectionLock) {
        if (_status != Status.DISCONNECTED) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Already connecting! Ignoring repeated request")
            return
        }

        aapsLogger.debug(LTag.PUMPBTCOMM, "Connect")
        if (preferences.get(ApexStringKey.SerialNumber).isEmpty()) return
        if (checkBT()) return
        _status = Status.CONNECTING
        connectionId++
        if (preferences.get(ApexStringKey.BluetoothAddress).isNotEmpty()) return reconnect()

        aapsLogger.debug(LTag.PUMPBTCOMM, "Scan started")
        bluetoothAdapter.bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() = synchronized(connectionLock) {
        if (bluetoothGatt == null && status == Status.CONNECTING) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Already connecting! Ignoring repeated request")
            return
        }

        aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnecting")
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnecting)
        stopScan()
        closeGatt()
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnected)
    }

    private fun checkBT(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
            uiInteraction.addNotification(
                Notification.PERMISSION_BT,
                context.getString(app.aaps.core.ui.R.string.need_connect_permission),
                Notification.URGENT)
            aapsLogger.error(LTag.PUMPBTCOMM, "No Bluetooth permission!")
            return true
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.location_permission_not_granted))
            uiInteraction.addNotification(
                Notification.PERMISSION_LOCATION,
                context.getString(app.aaps.core.ui.R.string.location_permission_not_granted),
                Notification.URGENT)
            aapsLogger.error(LTag.PUMPBTCOMM, "No coarse/fine location permission!")
            return true
        }

        if (bluetoothAdapter == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "No Bluetooth adapter!")
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun reconnect() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Setting up GATT")
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Connecting)
        bluetoothDevice = bluetoothAdapter!!.getRemoteDevice(preferences.get(ApexStringKey.BluetoothAddress))
        setupGatt()
    }

    @SuppressLint("MissingPermission")
    private fun setupGatt() {
        // Do not allow multiple GATTs
        if (bluetoothGatt != null) closeGatt()
        bluetoothGatt = bluetoothDevice!!.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        if (bluetoothGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Connecting | Failed to set up GATT")
            _status = Status.DISCONNECTED
            return
        }

        Thread {
            val prevConnId = connectionId
            SystemClock.sleep(25000)
            if (prevConnId != connectionId) return@Thread

            if (status == Status.CONNECTING) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Connecting | Timed out setting up GATT")
                synchronized (connectionLock) { closeGatt() }
            }
        }.start()
    }

    private fun onPumpData(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "PUMP <- ${value.toHex()}")
        tickDelay()
        when (characteristic.uuid) {
            READ_UUID -> synchronized(readMutex) {
                // Update command or create new one
                if (lastCommand?.isCompleteCommand() == false)
                    lastCommand!!.update(value)
                else if (value.size > PumpCommand.MIN_SIZE)
                    lastCommand = PumpCommand(value)
                else
                    aapsLogger.error(LTag.PUMPBTCOMM, "Got invalid command of length ${value.size}")

                while (lastCommand != null && lastCommand!!.isCompleteCommand()) {
                    if (!lastCommand!!.verify()) {
                        aapsLogger.error(LTag.PUMPBTCOMM, "[${lastCommand!!.id?.name}] Command checksum is invalid! Expected ${lastCommand!!.checksum.toHex()}")
                        return
                    }

                    callback?.onPumpCommand(lastCommand!!)
                    lastCommand = lastCommand!!.trailing
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Scan stopped")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Closing GATT")

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()

        SystemClock.sleep(100)
        bluetoothGatt = null
        _status = Status.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                BluetoothGatt.STATE_DISCONNECTING -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnecting")
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnected)
                    _status = Status.DISCONNECTED
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnected")

                    synchronized (connectionLock) { closeGatt() }
                    Thread { callback?.onDisconnect() }.start()
                }
                BluetoothGatt.STATE_CONNECTING -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting")
                }
                BluetoothGatt.STATE_CONNECTED -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Connected, discovering services")
                    _status = Status.CONNECTING
                    bluetoothGatt?.discoverServices()
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)

            writeCharacteristic = gatt?.getService(WRITE_SERVICE.uuid)?.getCharacteristic(WRITE_UUID)
            readCharacteristic = gatt?.getService(READ_SERVICE.uuid)?.getCharacteristic(READ_UUID)

            if (status != BluetoothGatt.GATT_SUCCESS || writeCharacteristic == null || readCharacteristic == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Failed to update MTU")
                disconnect()
                return
            }

            _status = Status.CONNECTING
            this@ApexBluetooth.mtu = mtu
            aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Updated MTU=$mtu, requesting notification")

            gatt!!.setCharacteristicNotification(readCharacteristic, true)

            val ccc = readCharacteristic!!.getDescriptor(CCC_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(ccc)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Failed to discover services")
                disconnect()
                return
            }

            if (gatt.getService(READ_SERVICE.uuid) == null || gatt.getService(WRITE_SERVICE.uuid) == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "R/W services were not found!")
                disconnect()
                return
            }

            _status = Status.CONNECTING
            aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Requested services, requesting MTU")
            gatt.requestMtu(512)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Thread {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Notification status: $status")
                gatt?.setCharacteristicNotification(readCharacteristic, true)
                prevCommandMs = SystemClock.uptimeMillis()
                SystemClock.sleep(1000)
                if (_status != Status.DISCONNECTED) {
                    _status = Status.CONNECTED
                    Thread { callback?.onConnect() }.start()
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connected")
                }
            }.start()
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            onPumpData(characteristic, characteristic.value)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            onPumpData(characteristic, characteristic.value)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onPumpData(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onPumpData(characteristic, value)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        @Synchronized
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (result == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Scan results empty $callbackType")
                synchronized (connectionLock) {
                    _status = Status.DISCONNECTED
                }
                return
            }
            if (!result.device.name.startsWith("APEX")) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Got not a pump (${result.device.name}) - skipping")
                synchronized (connectionLock) {
                    _status = Status.DISCONNECTED
                }
                return
            }

            aapsLogger.debug(LTag.PUMPBTCOMM, "Found device ${result.device.name}")
            stopScan()
            preferences.put(ApexStringKey.BluetoothAddress, result.device.address)
            reconnect()
        }

        @SuppressLint("MissingPermission")
        @Synchronized
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            aapsLogger.error(LTag.PUMPBTCOMM, "Scan failed $errorCode")
            synchronized (connectionLock) {
                _status = Status.DISCONNECTED
            }
            return
        }
    }

    private val scanFilters = listOf(
        ScanFilter.Builder()
            .setDeviceName("APEX${preferences.get(ApexStringKey.SerialNumber)}")
            .build(),
        ScanFilter.Builder()
            .setServiceUuid(READ_SERVICE)
            .build(),
        ScanFilter.Builder()
            .setServiceUuid(WRITE_SERVICE)
            .build(),
    )

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED;

        fun toLocalString(rh: ResourceHelper): String = when (this) {
            DISCONNECTED -> rh.gs(R.string.overview_connection_status_disconnected)
            CONNECTING -> rh.gs(R.string.overview_connection_status_connecting)
            CONNECTED -> rh.gs(R.string.overview_connection_status_connected)
        }
    }
}