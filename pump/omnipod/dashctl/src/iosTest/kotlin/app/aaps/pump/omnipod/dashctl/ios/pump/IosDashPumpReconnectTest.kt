package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * One pump across BLE connections: `DashPodConnection` makes a new link after a drop and gives it to
 * the same [IosDashPump]. The stored state (the 4-bit command sequence, the EAP SQN, the open
 * command) stays; the session is new and starts its outer counter at 1 (Android
 * `SharedBleManager.connect`: `establishSession(1)`).
 */
class IosDashPumpReconnectTest {

    private val f = DashPumpOnFakePod()

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    private fun commandSequence(command: ByteArray) = (command[4].toInt() and 0xff shr 2) and 0x0f

    @Test
    fun `a drop makes the pump refuse commands and send nothing until a new connection`() = runTest {
        f.pairAndConnect()
        assertTrue(f.pump.isConnected())

        f.dropLink()

        assertFalse(f.pump.isConnected())
        assertFalse(f.pump.isInitialized())
        val before = f.writesFromPump().size
        val result = f.pump.deliverTreatment(bolus(1.0))
        assertFalse(result.success)
        f.pump.getPumpStatus("lost link")
        assertEquals(before, f.writesFromPump().size, "nothing may be written on a lost link")
        assertNull(f.stateManager.activeCommand, "nothing opened")
    }

    @Test
    fun `after a drop the same pump keeps its counters and a new session starts at one`() = runTest {
        f.pairAndConnect()
        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)
        // The bolus and the status of the bolus wait, each +2.
        assertEquals(4, f.stateManager.messageSequenceNumber)
        val sqnBefore = f.stateManager.eapAkaSequenceNumber
        val pumpBefore = f.pump

        assertSame(DashConnectResult.SessionReady, f.reconnect())

        assertSame(pumpBefore, f.pump, "the pump lives on")
        assertTrue(f.pump.isConnected())
        assertEquals(4, f.stateManager.messageSequenceNumber, "the stored command sequence is kept")
        assertEquals(sqnBefore + 1, f.stateManager.eapAkaSequenceNumber, "one new challenge")
        // establishSession(1): the session counter starts at 1, so the challenge is 2 and SUCCESS 3.
        assertEquals(listOf(2, 3), f.pod.fromPhone.map { it.sequenceNumber.toInt() })

        assertTrue(f.pump.deliverTreatment(bolus(2.0)).success)

        assertEquals(4, commandSequence(f.pod.commands.last { DashPumpOnFakePod.isBolus(it) }), "built with the stored sequence")
        assertEquals(8, f.stateManager.messageSequenceNumber)
        // Challenge 2, SUCCESS 3, bolus 4, ACK 5, status of the bolus wait 6, ACK 7.
        assertEquals(listOf(2, 3, 4, 5, 6, 7), f.pod.fromPhone.map { it.sequenceNumber.toInt() })
    }

    @Test
    fun `an open command from before the drop blocks the next one and is decided by the next status`() = runTest {
        f.pairAndConnect()
        // The pod takes the bolus, then the link drops before its answer reaches the phone.
        f.pod.dropLinkWhileAnswering = 7 to 0
        val first = f.pump.deliverTreatment(bolus(5.0))
        assertFalse(first.success)
        assertEquals(0.0, first.bolusDelivered)
        val open = assertNotNull(f.stateManager.activeCommand)
        assertEquals(DashCommandKind.BOLUS, open.kind)
        f.pod.dropLinkWhileAnswering = null

        assertSame(DashConnectResult.SessionReady, f.reconnect())

        assertEquals(open, f.stateManager.activeCommand, "the new connection keeps the open command")
        val before = f.writesFromPump().size
        assertFalse(f.pump.deliverTreatment(bolus(5.0)).success, "a retry must not go while the bolus is open")
        assertEquals(before, f.writesFromPump().size, "nothing sent")
        assertTrue(f.decided.isEmpty())

        f.pump.getPumpStatus("decide the open bolus")

        assertNull(f.stateManager.activeCommand)
        val outcome = f.decided.single()
        assertEquals(DashCommandConfirmation.SUCCESS, outcome.confirmation)
        assertTrue(outcome.decidedLate)
        assertEquals(5.0, outcome.command.requestedBolusUnits)
        assertEquals(5.0, f.pump.lastBolusAmount.value?.cU)
        assertEquals(1, f.pod.commands.count { it[6] != CommandType.GET_STATUS.value }, "the bolus went to the pod once")
    }

    @Test
    fun `a link closed after an error is replaced by the next connection`() = runTest {
        f.pairAndConnect()
        f.pump.disconnect("test")
        assertFalse(f.pump.isConnected())
        assertEquals(1, f.central.cancelled.size, "disconnect cancels the BLE connection")

        assertSame(DashConnectResult.SessionReady, f.reconnect())

        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)
    }

    @Test
    fun `disconnect while a pairing runs is ignored as on Android`() = runTest {
        // Android OmnipodDashPumpPlugin.disconnect returns while the activation runs (#5219).
        assertSame(DashConnectResult.LinkReady, f.lastConnect)
        val reference = DashPumpOnFakePod()
        try {
            reference.pairAndConnect()

            f.pump.runActivation {
                assertTrue(f.pump.isBusy(), "the queue must wait while the pod is paired")
                f.pump.disconnect("idle")
                f.pump.pair(DashPumpOnFakePod.POD_ADDRESS)
                f.pump.disconnect("idle")
                f.stateManager.updateFromSetUniqueIdResponse(
                    SetUniqueIdResponse(parityHex(DashPumpOnFakePod.SET_UNIQUE_ID_RESPONSE)),
                    0L,
                )
                f.pump.establishSession()
            }

            assertTrue(f.central.cancelled.isEmpty(), "the BLE link was not cancelled")
            assertTrue(f.pump.isConnected())
            assertFalse(f.pump.isBusy())
            assertEquals(
                reference.pod.fromPhone.map { it.sequenceNumber.toInt() },
                f.pod.fromPhone.map { it.sequenceNumber.toInt() },
                "the session starts from the outer counter of the pair result, as without a disconnect"
            )
            assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)

            f.pump.disconnect("idle")
            assertEquals(1, f.central.cancelled.size, "after the activation disconnect works again")
        } finally {
            reference.pod.close()
        }
    }

    @Test
    fun `connect from inside a command is refused and does not block`() = runTest {
        f.pairAndConnect()
        val results = mutableListOf<DashConnectResult>()
        f.onDecided = {
            // Inside the command: the link is closed, then a connect is tried on the same thread.
            f.pump.disconnect("test")
            results += f.connection.connect()
        }

        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)

        assertEquals(listOf<DashConnectResult>(DashConnectResult.Busy), results)
        f.onDecided = {}
        assertSame(DashConnectResult.SessionReady, f.reconnect(), "after the command, connect works")
        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)
    }

    @Test
    fun `connect again on a usable link with a session does nothing`() = runTest {
        f.pairAndConnect()
        val before = f.pod.central.log.size

        assertSame(DashConnectResult.AlreadyConnected, f.connection.connect())

        assertEquals(before, f.pod.central.log.size)
        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)
    }
}
