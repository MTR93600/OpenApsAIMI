package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The running basal program in the pod state: what Android `handleCommandConfirmation` does on
 * SET_BASAL_PROFILE, and what a crash keeps. The store holds the encoded bytes, as the Keychain does.
 */
class DashBasalProgramStateTest {

    private class BytesStore : DashPodStateStore {

        var bytes: ByteArray? = null
        val written = mutableListOf<DashPodState>()
        override fun load(): DashPodState? = bytes?.let { DashPodStateCodec.decode(it) }
        override fun save(state: DashPodState) {
            bytes = DashPodStateCodec.encode(state)
            written += DashPodStateCodec.decode(bytes!!)
        }

        override fun clear() {
            bytes = null
        }
    }

    private val store = BytesStore()
    private var manager = DashPodStateManager(store)

    private val oldProgram = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))
    private val newProgram = BasalProgram(listOf(BasalProgram.Segment(0, 10, 50), BasalProgram.Segment(10, 48, 120)))

    private fun status(lastSequence: Int, delivery: DeliveryStatus = DeliveryStatus.BASAL_ACTIVE) = DashPodStatus(
        podStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        deliveryStatus = delivery,
        totalPulsesDelivered = 100,
        reservoirPulsesRemaining = 1000,
        minutesSinceActivation = 60,
        bolusPulsesRemaining = 0,
        activeAlerts = emptySet(),
        lastProgrammingCommandSequence = lastSequence,
        receivedAtMs = 1_000L,
    )

    /** A program sent; the next status reports [lastSequence] (or the program's own sequence when null). */
    private fun sendProgram(program: BasalProgram, lastSequence: Int? = null): DashResolvedCommand {
        val command = manager.createActiveCommand(DashCommandKind.BASAL_PROGRAM, historyId = 1L, createdAtMs = 500L, basalProgram = program)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(lastSequence ?: command.sequence))
        return manager.resolveActiveCommand(nowMs = 2_000L)
    }

    @Test
    fun `a confirmed program becomes the running program`() {
        val resolved = sendProgram(newProgram)

        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)
        assertEquals(newProgram, manager.basalProgram)
        assertEquals(newProgram, resolved.command?.basalProgram)
    }

    @Test
    fun `a refused program keeps the program before`() {
        sendProgram(oldProgram)

        val resolved = sendProgram(newProgram, lastSequence = 0x0f)

        assertEquals(DashCommandConfirmation.DENIED, resolved.confirmation)
        assertEquals(oldProgram, manager.basalProgram)
    }

    @Test
    fun `an open program does not change the running one`() {
        sendProgram(oldProgram)
        manager.createActiveCommand(DashCommandKind.BASAL_PROGRAM, historyId = 2L, createdAtMs = 600L, basalProgram = newProgram)
        manager.markActiveCommandSent()

        assertEquals(DashCommandConfirmation.NOT_CONFIRMED, manager.resolveActiveCommand(3_000L).confirmation)
        assertEquals(oldProgram, manager.basalProgram)
    }

    /** A crash between closing the command and setting the program would lose a program the pod runs. */
    @Test
    fun `closing the command and setting the program are one write`() {
        sendProgram(newProgram)

        val closedWithOldProgram = store.written.filter { it.activeCommand == null && it.statusResponseCount == 1L && it.basalProgram == null }
        assertTrue(closedWithOldProgram.isEmpty(), "no stored state has the command closed and the program not set")
        assertEquals(newProgram, store.written.last().basalProgram)
    }

    @Test
    fun `an open program and the running one survive a restart`() {
        sendProgram(oldProgram)
        manager.createActiveCommand(DashCommandKind.BASAL_PROGRAM, historyId = 2L, createdAtMs = 600L, basalProgram = newProgram)
        manager.markActiveCommandSent()

        manager = DashPodStateManager(store)

        assertEquals(newProgram, assertNotNull(manager.activeCommand).basalProgram)
        assertEquals(oldProgram, manager.basalProgram)
        // The pod took it: the next status decides it after the restart.
        manager.updateFromDefaultStatus(status(manager.activeCommand!!.sequence))
        assertEquals(DashCommandConfirmation.SUCCESS, manager.resolveActiveCommand(4_000L).confirmation)
        assertEquals(newProgram, manager.basalProgram)
    }

    @Test
    fun `the stored program is a copy the caller cannot change`() {
        val program = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))
        manager.createActiveCommand(DashCommandKind.BASAL_PROGRAM, historyId = 1L, createdAtMs = 500L, basalProgram = program)

        program.addSegment(BasalProgram.Segment(0, 1, 5))

        assertEquals(1, manager.activeCommand?.basalProgram?.segments?.size)
    }

    @Test
    fun `activation completed and suspended read as Android`() {
        assertFalse(manager.isActivationCompleted)
        assertFalse(manager.isSuspended, "no status yet: not suspended, as Android")
        manager.updateFromDefaultStatus(status(0x0f, DeliveryStatus.SUSPENDED))
        assertTrue(manager.isSuspended)
        manager.updateFromDefaultStatus(status(0x0f, DeliveryStatus.BASAL_ACTIVE))
        assertFalse(manager.isSuspended)

        store.bytes = DashPodStateCodec.encode(DashPodState(activationProgress = ActivationProgress.COMPLETED))
        manager = DashPodStateManager(store)
        assertTrue(manager.isActivationCompleted)
        assertNull(manager.basalProgram)
    }
}
