package app.aaps.plugins.dexcomoneplus.session

import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleCharProperty
import app.aaps.core.interfaces.pump.ble.session.BleGattCharacteristic
import app.aaps.core.interfaces.pump.ble.session.BleGattService
import app.aaps.plugins.dexcomoneplus.FakeBleCentral
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusBluetoothUuids
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientIos
import jamorham.keks.Plugin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real KEKS handshake, running on Kotlin/Native over [OnePlusGattClientIos].
 *
 * [OnePlusSessionAuthKeks] used to be Android only. It is shared code now, and what these tests
 * cover is the **wiring**: libkeks is reached from Kotlin/Native, the guide certificates are
 * installed into it, its state machine reaches Round Start, and the frame it answers with goes
 * out through the CoreBluetooth client to a scripted central standing in for the sensor.
 *
 * The cryptography is **not** retested here. `KeksHandshakeTest` in `:plugins:libkeks` replays a
 * whole recorded four digit pairing message by message and runs on this same simulator, so the
 * P-256 maths is already proved on Apple there. A scripted central cannot answer a real round,
 * because the reply would have to be a genuine J-PAKE message from a second party.
 *
 * What none of this shows is a pairing with a sensor. Two branches of the real handshake have no
 * recorded exchange at all. One of them is pinned below: a ONE+ asks for an OS bond in the middle
 * of an unbonded four digit pairing. CoreBluetooth has no pairing API and needs none - it pairs on
 * the next encrypted operation - so the handshake must go on rather than stop there.
 *
 * No test name here may contain a comma: these really run on Kotlin/Native.
 */
class OnePlusSessionAuthKeksIosTest {

    private val deviceId = "PERIPHERAL-1"
    private val pairingCode = "1234"
    private val cccd = OnePlusBluetoothUuids.CharacteristicUpdateNotification

    /** libkeks keeps one instance per pairing code in a static field; channel 3 drops it. */
    @AfterTest
    fun dropTheSharedInstance() {
        Plugin.getInstance(pairingCode).setPersistence(3, ByteArray(0))
    }

    private var clock = 0L

    private fun client(central: FakeBleCentral) = OnePlusGattClientIos(
        central = central,
        nowMs = { clock },
        sleep = { millis ->
            clock += millis
            true
        },
    )

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

    /** Connects the client the way the sensor would answer, and hands both parts back. */
    private fun connected(): Pair<FakeBleCentral, OnePlusGattClientIos> {
        val central = FakeBleCentral()
        val gatt = client(central)
        central.onConnect = { id -> gatt.onEvent(BleCentralEvent.Link(id, connected = true, status = 0)) }
        central.onDiscoverServices = { id ->
            gatt.onEvent(
                BleCentralEvent.Services(
                    id,
                    listOf(BleGattService(OnePlusBluetoothUuids.CgmService.lowercase(), emptyList())),
                    status = 0,
                ),
            )
        }
        central.onDiscoverCharacteristics = { id, serviceUuid ->
            gatt.onEvent(BleCentralEvent.Characteristics(id, serviceUuid, onePlusCharacteristics(), status = 0))
        }
        central.onEnableNotify = { subscribe ->
            gatt.onEvent(BleCentralEvent.DescriptorWritten(subscribe.id, cccd, status = 0))
        }
        central.onWrite = { write ->
            if (write.withResponse) gatt.onEvent(BleCentralEvent.Written(write.id, write.characteristicUuid, status = 0))
        }
        gatt.connect(deviceId, autoConnect = false)
        return central to gatt
    }

    /** A short step timeout: nothing answers here and the test must not wait fifteen seconds. */
    private fun auth(gatt: OnePlusGattClientIos) = OnePlusSessionAuthKeks(gatt, stepTimeoutMs = 20L)

    @Test
    fun itRefusesBeforeTheLinkIsUp() {
        val central = FakeBleCentral()

        val result = auth(client(central)).authenticate(pairingCode)

        assertFalse(result.ok)
        assertContains(result.message ?: "", "GATT not connected")
        assertTrue(central.writes.isEmpty())
    }

    @Test
    fun itOpensTheHandshakeOverCoreBluetooth() {
        val (central, gatt) = connected()
        val subscriptions = central.notifies.size

        val result = auth(gatt).authenticate(pairingCode)

        // Nothing answers here, so the handshake can only end in a step timeout. What matters is
        // what it sent first. In Round Start libkeks answers with one short frame on the
        // Authentication characteristic; the long ExtraData rounds only follow the sensor's
        // reply, which a scripted central cannot give.
        assertFalse(result.ok)
        assertContains(result.message ?: "", "notify timeout")
        val authWrites = central.writes.filter {
            it.characteristicUuid.equals(OnePlusBluetoothUuids.Authentication, ignoreCase = true)
        }
        assertEquals(1, authWrites.size, "Round Start writes one Authentication frame")
        assertEquals(2, authWrites[0].data.size, "the Round Start frame is two bytes")
        assertTrue(authWrites[0].withResponse, "Authentication is written with a response")
        // The subscriptions were already in place before authenticate ran; it added none.
        assertEquals(subscriptions, central.notifies.size)
    }

    @Test
    fun theGuideCertificatesAreInstalledBeforeTheFirstWrite() {
        val (_, gatt) = connected()

        auth(gatt).authenticate(pairingCode)

        // Without these three, libkeks raises "Missing QR code" part way through an unbonded
        // pairing instead of failing at the start where it can be read.
        assertTrue(Plugin.getInstance(pairingCode).context.validateParts())
    }

    @Test
    fun anUnbondedPairingGoesOnAndLeavesThePairingToIos() {
        val (central, gatt) = connected()
        // TIME_EXTENDED is the frame a ONE+ sends to ask for an OS bond. It is queued before the
        // handshake starts waiting, which is how a scripted central delivers it to a blocking
        // caller.
        gatt.onEvent(
            BleCentralEvent.Value(deviceId, OnePlusBluetoothUuids.Authentication, byteArrayOf(0x06, 0x19)),
        )

        val result = OnePlusSessionAuthKeks(gatt, stepTimeoutMs = 20L, bondWaitMs = 20L).authenticate(pairingCode)

        // A scripted central cannot finish a real J-PAKE round, so the handshake still ends - but no
        // longer at the bond request: iOS is left to pair on the next encrypted operation.
        assertFalse(result.ok)
        assertFalse((result.message ?: "").contains("bond required"), result.message)
        assertFalse(gatt.isBonded())
        assertTrue(central.cancels.isEmpty())
    }
}
