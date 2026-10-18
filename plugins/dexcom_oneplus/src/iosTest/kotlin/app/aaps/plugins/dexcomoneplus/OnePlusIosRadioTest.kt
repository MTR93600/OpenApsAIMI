package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleCharProperty
import app.aaps.core.interfaces.pump.ble.session.BleGattCharacteristic
import app.aaps.core.interfaces.pump.ble.session.BleGattService
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusBluetoothUuids
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientIos
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerIos
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanBudget
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fan-out that gives one central's events to both listeners.
 *
 * Wiring only one of them compiles perfectly and then shows up days later as a sensor that is
 * never found or a connection that never becomes ready, so it is worth a test of its own.
 */
class OnePlusIosRadioTest {

    private val deviceId = "PERIPHERAL-1"

    @BeforeTest
    fun resetBudget() = OnePlusScanBudget.reset()

    @AfterTest
    fun clearBudget() = OnePlusScanBudget.reset()

    @Test
    fun oneCentralFeedsBothTheScannerAndTheClient() {
        val central = FakeBleCentral()
        val radio = OnePlusIosRadio()
        val scanner = OnePlusBleScannerIos(central = central, nowMs = { 0L }, sleep = { true })
        val gatt = OnePlusGattClientIos(central = central, nowMs = { 0L }, sleep = { true })
        radio.scanner = scanner
        radio.gatt = gatt

        // Everything the fake sensor answers goes through the fan-out, never straight to one half.
        central.onConnect = { id -> radio.onEvent(BleCentralEvent.Link(id, connected = true, status = 0)) }
        central.onDiscoverServices = { id ->
            radio.onEvent(
                BleCentralEvent.Services(
                    id,
                    listOf(BleGattService(OnePlusBluetoothUuids.CgmService.lowercase(), emptyList())),
                    status = 0,
                ),
            )
        }
        central.onDiscoverCharacteristics = { id, serviceUuid ->
            radio.onEvent(
                BleCentralEvent.Characteristics(
                    id,
                    serviceUuid,
                    listOf(
                        BleGattCharacteristic(
                            OnePlusBluetoothUuids.Authentication.lowercase(),
                            setOf(BleCharProperty.Write, BleCharProperty.Indicate),
                        ),
                        BleGattCharacteristic(
                            OnePlusBluetoothUuids.Control.lowercase(),
                            setOf(BleCharProperty.Write, BleCharProperty.Indicate),
                        ),
                    ),
                    status = 0,
                ),
            )
        }
        central.onEnableNotify = { subscribe ->
            radio.onEvent(
                BleCentralEvent.DescriptorWritten(
                    subscribe.id,
                    OnePlusBluetoothUuids.CharacteristicUpdateNotification,
                    status = 0,
                ),
            )
        }

        val found = mutableListOf<OnePlusScanResult>()
        scanner.startScan { found += it }
        radio.onEvent(
            BleCentralEvent.ScanResult(name = "DX02aS", id = deviceId, advertisementBytes = null),
        )
        scanner.stopScan()

        gatt.connect(deviceId, autoConnect = false)
        radio.onEvent(
            BleCentralEvent.Value(deviceId, OnePlusBluetoothUuids.Control.lowercase(), byteArrayOf(7)),
        )

        assertEquals(1, found.size)
        assertTrue(gatt.isConnected())
        assertContentEquals(byteArrayOf(7), gatt.awaitControlNotify(500L))
    }

    @Test
    fun anEventIsHarmlessBeforeEitherListenerIsSet() {
        val radio = OnePlusIosRadio()

        radio.onEvent(BleCentralEvent.ScanResult(name = "DX02aS", id = deviceId, advertisementBytes = null))
    }
}
