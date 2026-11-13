package app.aaps.pump.omnipod.dashctl.ios.ble

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.CoreBluetoothCentral
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [QueuedBleCentral] on a real [CoreBluetoothCentral].
 *
 * The central is never opened, so no `CBCentralManager` is made and no Bluetooth permission is
 * asked. Its radio queue exists from the start, and that is what these tests need. With no
 * manager and no peripheral, every call gives the "unknown peripheral" answer.
 */
class QueuedBleCentralTest {

    private val radio = CoreBluetoothCentral()
    private val central = QueuedBleCentral(radio)

    @Test
    fun `calls from another thread give the answers of the central`() {
        assertEquals(BleAdapterState.Unknown, central.adapterState())
        assertFalse(central.write(POD, CMD, byteArrayOf(0x00), withResponse = true))
        assertFalse(central.enableNotify(POD, CMD, indicate = true))
        assertFalse(central.canSendWriteWithoutResponse(POD))
        assertFalse(central.connect(POD))
        assertNull(central.cachedName(POD))
        central.discoverServices(POD)
        central.discoverCharacteristics(POD, SERVICE)
        central.cancel(POD)
        central.release(POD)
        central.stopScan()
    }

    @Test
    fun `a call runs on the radio queue after the work already there`() {
        var done = false
        radio.post {
            NSThread.sleepForTimeInterval(0.2)
            done = true
        }
        // The queue is serial: the write can only run after the posted block.
        central.write(POD, CMD, byteArrayOf(0x00), withResponse = true)
        assertTrue(done)
    }

    @Test
    fun `an exception of the central reaches the caller`() {
        // startScan on a central that is not open throws on the radio queue.
        assertFailsWith<IllegalStateException> { central.startScan() }
        // The queue still works afterwards.
        assertFalse(central.write(POD, CMD, byteArrayOf(0x00), withResponse = true))
    }

    @Test
    fun `a call made on the radio queue runs at once`() {
        var started: Boolean? = null
        radio.sync { started = central.write(POD, CMD, byteArrayOf(0x00), withResponse = true) }
        assertEquals(false, started)
    }

    private companion object {

        const val POD = "6E1C0D2A-4B3F-4C6D-9E8F-0A1B2C3D4E5F"
        const val CMD = "1a7e2441-e3ed-4464-8b7e-751e03d0dc5f"
        const val SERVICE = "1a7e4024-e3ed-4464-8b7e-751e03d0dc5f"
    }
}
