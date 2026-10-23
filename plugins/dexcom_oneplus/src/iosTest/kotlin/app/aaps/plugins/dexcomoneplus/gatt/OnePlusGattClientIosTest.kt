package app.aaps.plugins.dexcomoneplus.gatt

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleCharProperty
import app.aaps.core.interfaces.pump.ble.session.BleGattCharacteristic
import app.aaps.core.interfaces.pump.ble.session.BleGattService
import app.aaps.plugins.dexcomoneplus.FakeBleCentral
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS GATT client driven through [FakeBleCentral].
 *
 * The fake answers every request the moment it is made, the way CoreBluetooth answers on its own
 * queue, so the blocking client never has to wait for a second thread.
 *
 * No test name here may contain a comma: these really run on Kotlin/Native.
 */
class OnePlusGattClientIosTest {

    private val deviceId = "PERIPHERAL-1"
    private val cccd = OnePlusBluetoothUuids.CharacteristicUpdateNotification

    /**
     * The characteristics a real ONE+ offers.
     *
     * Control is INDICATE and not NOTIFY. That one fact cost the Android driver weeks of
     * "status 19", so it is what the fake sensor advertises here.
     */
    private fun onePlusCharacteristics() = listOf(
        BleGattCharacteristic(
            OnePlusBluetoothUuids.Authentication.lowercase(),
            setOf(BleCharProperty.Write, BleCharProperty.Indicate),
        ),
        BleGattCharacteristic(
            OnePlusBluetoothUuids.ExtraData.lowercase(),
            setOf(BleCharProperty.WriteNoResponse, BleCharProperty.Notify),
        ),
        BleGattCharacteristic(
            OnePlusBluetoothUuids.Control.lowercase(),
            setOf(BleCharProperty.Write, BleCharProperty.Indicate),
        ),
        BleGattCharacteristic(
            OnePlusBluetoothUuids.ProbablyBackfill.lowercase(),
            setOf(BleCharProperty.Notify),
        ),
    )

    private var clock = 0L

    private fun client(central: FakeBleCentral) = OnePlusGattClientIos(
        central = central,
        nowMs = { clock },
        sleep = { millis ->
            clock += millis
            true
        },
    )

    /** Wires the fake to walk the whole discovery the way CoreBluetooth would. */
    private fun scriptHealthySensor(
        central: FakeBleCentral,
        gatt: OnePlusGattClientIos,
        characteristics: List<BleGattCharacteristic> = onePlusCharacteristics(),
        services: List<BleGattService> = listOf(BleGattService(OnePlusBluetoothUuids.CgmService.lowercase(), emptyList())),
    ) {
        central.onConnect = { id -> gatt.onEvent(BleCentralEvent.Link(id, connected = true, status = 0)) }
        central.onDiscoverServices = { id -> gatt.onEvent(BleCentralEvent.Services(id, services, status = 0)) }
        central.onDiscoverCharacteristics = { id, serviceUuid ->
            gatt.onEvent(BleCentralEvent.Characteristics(id, serviceUuid, characteristics, status = 0))
        }
        central.onEnableNotify = { subscribe ->
            gatt.onEvent(BleCentralEvent.DescriptorWritten(subscribe.id, cccd, status = 0))
        }
        central.onWrite = { write ->
            if (write.withResponse) {
                gatt.onEvent(BleCentralEvent.Written(write.id, write.characteristicUuid, status = 0))
            }
        }
    }

    private fun connected(): Pair<FakeBleCentral, OnePlusGattClientIos> {
        val central = FakeBleCentral()
        val gatt = client(central)
        scriptHealthySensor(central, gatt)
        gatt.connect(deviceId, autoConnect = false)
        return central to gatt
    }

    // -- connect ------------------------------------------------------------------------------

    @Test
    fun connectSubscribesToExtraDataThenAuthentication() {
        val (central, gatt) = connected()

        assertTrue(gatt.isConnected())
        assertEquals(2, central.notifies.size)
        assertEquals(OnePlusBluetoothUuids.ExtraData, central.notifies[0].characteristicUuid)
        assertFalse(central.notifies[0].indicate)
        assertEquals(OnePlusBluetoothUuids.Authentication, central.notifies[1].characteristicUuid)
        // Authentication offers indications on a ONE+ and that is what is asked for.
        assertTrue(central.notifies[1].indicate)
    }

    @Test
    fun connectFailsWhenTheCgmServiceIsMissing() {
        val central = FakeBleCentral()
        val gatt = client(central)
        scriptHealthySensor(
            central,
            gatt,
            services = listOf(BleGattService("0000180a-0000-1000-8000-00805f9b34fb", emptyList())),
        )

        assertFailsWith<IllegalStateException> { gatt.connect(deviceId, autoConnect = false) }
        assertFalse(gatt.isConnected())
    }

    @Test
    fun connectFailsWhenAuthenticationIsMissing() {
        val central = FakeBleCentral()
        val gatt = client(central)
        scriptHealthySensor(
            central,
            gatt,
            characteristics = onePlusCharacteristics().filterNot {
                it.uuid.equals(OnePlusBluetoothUuids.Authentication, ignoreCase = true)
            },
        )

        assertFailsWith<IllegalStateException> { gatt.connect(deviceId, autoConnect = false) }
    }

    @Test
    fun connectIsRefusedWhileBluetoothIsOff() {
        val central = FakeBleCentral()
        central.adapter = BleAdapterState.PoweredOff
        val gatt = client(central)

        assertFailsWith<IllegalStateException> { gatt.connect(deviceId, autoConnect = false) }
        assertTrue(central.connects.isEmpty())
    }

    // -- Control is INDICATE -------------------------------------------------------------------

    @Test
    fun controlIsSubscribedAsIndicateBecauseThatIsAllAOnePlusOffers() {
        val (central, gatt) = connected()

        gatt.enableControlNotifications()

        val last = central.notifies.last()
        assertEquals(OnePlusBluetoothUuids.Control, last.characteristicUuid)
        assertTrue(last.indicate)
    }

    @Test
    fun theIndicateFallbackSubscribesToTheSameCharacteristic() {
        val (central, gatt) = connected()

        gatt.enableControlIndications()

        val last = central.notifies.last()
        assertEquals(OnePlusBluetoothUuids.Control, last.characteristicUuid)
        assertTrue(last.indicate)
    }

    @Test
    fun aControlCharacteristicWithNeitherPropertyIsRefused() {
        val central = FakeBleCentral()
        val gatt = client(central)
        val crippled = onePlusCharacteristics().map {
            if (it.uuid.equals(OnePlusBluetoothUuids.Control, ignoreCase = true)) {
                it.copy(properties = setOf(BleCharProperty.Write))
            } else {
                it
            }
        }
        scriptHealthySensor(central, gatt, characteristics = crippled)
        gatt.connect(deviceId, autoConnect = false)

        assertFailsWith<IllegalStateException> { gatt.enableControlNotifications() }
    }

    @Test
    fun backfillIsSubscribedAsNotify() {
        val (central, gatt) = connected()

        gatt.enableBackfillNotifications()

        val last = central.notifies.last()
        assertEquals(OnePlusBluetoothUuids.ProbablyBackfill, last.characteristicUuid)
        assertFalse(last.indicate)
    }

    // -- traffic ---------------------------------------------------------------------------------

    @Test
    fun anIndicationOnControlReachesTheSession() {
        val (_, gatt) = connected()
        val frame = byteArrayOf(0x4E, 0x01, 0x02)

        gatt.onEvent(
            BleCentralEvent.Value(deviceId, OnePlusBluetoothUuids.Control.lowercase(), frame),
        )

        assertContentEquals(frame, gatt.awaitControlNotify(500L))
    }

    @Test
    fun authAndExtraDataFramesStayOnSeparateRoutes() {
        val (_, gatt) = connected()

        gatt.onEvent(
            BleCentralEvent.Value(deviceId, OnePlusBluetoothUuids.ExtraData.lowercase(), byteArrayOf(1)),
        )
        gatt.onEvent(
            BleCentralEvent.Value(deviceId, OnePlusBluetoothUuids.Authentication.lowercase(), byteArrayOf(2)),
        )

        val first = gatt.awaitKeksNotify(500L)
        val second = gatt.awaitKeksNotify(500L)
        assertEquals(OnePlusKeksNotifySource.EXTRA_DATA, first?.source)
        assertEquals(OnePlusKeksNotifySource.AUTHENTICATION, second?.source)
        // Nothing of the handshake leaked into the EGV route.
        assertNull(gatt.awaitControlNotify(20L))
    }

    @Test
    fun aBackfillFrameReachesTheHistoryRoute() {
        val (_, gatt) = connected()

        gatt.onEvent(
            BleCentralEvent.Value(deviceId, OnePlusBluetoothUuids.ProbablyBackfill.lowercase(), byteArrayOf(9)),
        )

        assertContentEquals(byteArrayOf(9), gatt.awaitBackfillNotify(500L))
    }

    @Test
    fun aControlRequestIsWrittenWithResponse() {
        val (central, gatt) = connected()

        gatt.writeControl(byteArrayOf(0x4E, 0x00))

        val write = central.writes.last()
        assertEquals(OnePlusBluetoothUuids.Control, write.characteristicUuid)
        assertTrue(write.withResponse)
    }

    @Test
    fun extraDataIsWrittenInChunksWithoutResponse() {
        val (central, gatt) = connected()

        gatt.writeExtraData(ByteArray(45) { it.toByte() })

        assertEquals(3, central.writes.size)
        assertTrue(central.writes.all { !it.withResponse })
        assertEquals(20, central.writes[0].data.size)
        assertEquals(20, central.writes[1].data.size)
        assertEquals(5, central.writes[2].data.size)
    }

    @Test
    fun anExtraDataChunkWaitsUntilTheStackHasRoom() {
        val (central, gatt) = connected()
        // No room for the first two questions, then room for every chunk after that.
        central.canSendAnswers.addAll(listOf(false, false))
        val before = clock

        gatt.writeExtraData(ByteArray(45) { it.toByte() })

        assertEquals(3, central.writes.size)
        // Two waits of 5 ms, two gaps of 40 ms between the chunks, 500 ms after them.
        assertEquals(10L + 80L + 500L, clock - before)
        assertEquals(5, central.canSendQuestions)
    }

    @Test
    fun anExtraDataChunkIsSentAnywayWhenTheRoomNeverComes() {
        val (central, gatt) = connected()
        central.canSendByDefault = false

        gatt.writeExtraData(ByteArray(10) { it.toByte() })

        // The old behaviour: the write goes out, and the handshake decides whether it arrived.
        assertEquals(1, central.writes.size)
        assertEquals(101, central.canSendQuestions)
    }

    // -- disconnect ------------------------------------------------------------------------------

    @Test
    fun disconnectCancelsTheLinkAndUnblocksEveryWait() {
        val (central, gatt) = connected()

        gatt.disconnect()

        assertFalse(gatt.isConnected())
        assertEquals(listOf(deviceId), central.cancels)
        assertNull(gatt.awaitControlNotify(500L))
        assertNull(gatt.awaitBackfillNotify(500L))
        assertNull(gatt.awaitKeksNotify(500L))
    }

    @Test
    fun aDroppedLinkUnblocksEveryWait() {
        val (_, gatt) = connected()

        gatt.onEvent(BleCentralEvent.Link(deviceId, connected = false, status = 19))

        assertFalse(gatt.isConnected())
        assertNull(gatt.awaitControlNotify(500L))
    }

    @Test
    fun anEmptyFrameFromTheSensorIsNotMistakenForADisconnect() {
        val (_, gatt) = connected()

        gatt.onEvent(BleCentralEvent.Value(deviceId, OnePlusBluetoothUuids.Control.lowercase(), ByteArray(0)))

        val received = gatt.awaitControlNotify(500L)
        assertNotNull(received)
        assertEquals(0, received.size)
    }

    // -- what iOS cannot do ------------------------------------------------------------------------

    /**
     * iOS pairs by itself on the next encrypted operation, so a bond request is answered yes and
     * the wait opens a pairing window. The bond state itself is still not claimed, and a bond cannot
     * be removed by an app.
     */
    @Test
    fun bondingIsLeftToIosWithoutClaimingAState() {
        val (central, gatt) = connected()

        assertTrue(gatt.createBond())
        assertTrue(gatt.awaitBondComplete(10L))
        assertFalse(gatt.isBonded())
        assertFalse(gatt.removeBond())
        // Nothing was asked of the central: there is no pairing call to make on iOS.
        assertTrue(central.cancels.isEmpty())
    }

    // -- state restoration -------------------------------------------------------------------------

    /**
     * A restored peripheral is never adopted: no link id, no connected flag, and nothing put into
     * any of the three queues. If it set the link id the client would look connected while the
     * central holds no characteristics to write to; if it fed the queues, frames from before the
     * app was ended would be read as a current glucose value.
     */
    @Test
    fun aRestoredPeripheralDoesNotBecomeTheLink() {
        val central = FakeBleCentral()
        val gatt = client(central)

        gatt.onEvent(BleCentralEvent.Restored(listOf(deviceId, "PERIPHERAL-2")))

        assertFalse(gatt.isConnected())
        // No link id: a write has nothing to write to and says so.
        assertFailsWith<IllegalStateException> { gatt.writeControl(byteArrayOf(1)) }
        assertNull(gatt.awaitKeksNotify(20L))
        assertNull(gatt.awaitControlNotify(20L))
        assertNull(gatt.awaitBackfillNotify(20L))
        // Hanging up is the radio's job, so this client does not also cancel.
        assertTrue(central.cancels.isEmpty())
    }

    /**
     * A restore that names the link this client believes it holds is treated as a fault.
     *
     * The link is dropped and every wait is woken, rather than carried on with. Nothing this
     * object knew about that link survived the relaunch that produced the restore.
     */
    @Test
    fun aRestoreNamingTheCurrentLinkDropsIt() {
        val (central, gatt) = connected()

        gatt.onEvent(BleCentralEvent.Restored(listOf(deviceId)))

        assertFalse(gatt.isConnected())
        assertEquals(listOf(deviceId), central.cancels)
        assertNull(gatt.awaitControlNotify(500L))
        assertFailsWith<IllegalStateException> { gatt.writeControl(byteArrayOf(1)) }
    }

    /** An empty restore is normal and must change nothing about a healthy link. */
    @Test
    fun anEmptyRestoreLeavesAHealthyLinkAlone() {
        val (central, gatt) = connected()

        gatt.onEvent(BleCentralEvent.Restored(emptyList()))

        assertTrue(gatt.isConnected())
        assertTrue(central.cancels.isEmpty())
    }
}
