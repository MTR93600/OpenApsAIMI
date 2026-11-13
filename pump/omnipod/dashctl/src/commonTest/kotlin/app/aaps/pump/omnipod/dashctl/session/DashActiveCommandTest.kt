package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unconfirmed commands: a command that changes delivery stays open until a pod status says whether the
 * pod took it, and nothing new is sent meanwhile. Without it a retried bolus can be delivered twice.
 * The decisions are Android's (`OmnipodDashPodStateManagerImpl.getCommandConfirmationFromState`).
 *
 * Test names here carry no comma: Kotlin/Native rejects one inside a backtick quoted name.
 */
class DashActiveCommandTest {

    private class MemoryStore : DashPodStateStore {
        var saved: DashPodState? = null
        var saves = 0
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saved = state
            saves++
        }

        override fun clear() {
            saved = null
        }
    }

    private fun status(lastSequence: Int) = DashPodStatus(
        podStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        deliveryStatus = DeliveryStatus.BASAL_ACTIVE,
        totalPulsesDelivered = 1234,
        reservoirPulsesRemaining = 1023,
        minutesSinceActivation = 600,
        bolusPulsesRemaining = 0,
        activeAlerts = emptySet(),
        lastProgrammingCommandSequence = lastSequence,
        receivedAtMs = 1_800_000_000_000L,
    )

    private fun manager(store: MemoryStore = MemoryStore()) = DashPodStateManager(store)

    // -- The rule ------------------------------------------------------------------------------

    @Test
    fun `no command is no active command`() {
        assertEquals(DashCommandConfirmation.NO_ACTIVE_COMMAND, DashCommandConfirmation.of(null, 3, 5))
    }

    @Test
    fun `a later status with the same sequence is success`() {
        val command = DashActiveCommand(sequence = 5, kind = DashCommandKind.BOLUS, historyId = 1, statusCountAtCreation = 3, sent = true)
        assertEquals(DashCommandConfirmation.SUCCESS, DashCommandConfirmation.of(command, 4, 5))
    }

    @Test
    fun `a later status with another sequence is denied`() {
        val command = DashActiveCommand(sequence = 5, kind = DashCommandKind.BOLUS, historyId = 1, statusCountAtCreation = 3, sent = true)
        assertEquals(DashCommandConfirmation.DENIED, DashCommandConfirmation.of(command, 4, 4))
    }

    @Test
    fun `sent with no later status is not confirmed`() {
        val command = DashActiveCommand(sequence = 5, kind = DashCommandKind.BOLUS, historyId = 1, statusCountAtCreation = 3, sent = true)
        assertEquals(DashCommandConfirmation.NOT_CONFIRMED, DashCommandConfirmation.of(command, 3, 5))
    }

    @Test
    fun `never sent with no later status is a sending failure`() {
        val command = DashActiveCommand(sequence = 5, kind = DashCommandKind.BOLUS, historyId = 1, statusCountAtCreation = 3, sent = false)
        assertEquals(DashCommandConfirmation.SENDING_FAILURE, DashCommandConfirmation.of(command, 3, 5))
    }

    /**
     * Android bug, ported as is: only the 4-bit sequences are compared. Each answered exchange moves
     * the sequence by 2, so after 8 of them a new command gets the same sequence as the last
     * programming command the pod took. A status after it then shows that sequence and the rule says
     * SUCCESS, even when the pod never got the new command. `IosDashPump` does not build a delivery
     * command with that sequence (it reads a status first), so the rule is never asked this question.
     */
    @Test
    fun `the rule cannot tell a new command from an old one with the same sequence`() {
        // The pod never took this bolus; its last programming command is a TBR, also built with 4.
        val bolus = DashActiveCommand(sequence = 4, kind = DashCommandKind.BOLUS, historyId = 1, statusCountAtCreation = 3, sent = true)
        assertEquals(DashCommandConfirmation.SUCCESS, DashCommandConfirmation.of(bolus, 4, 4))
    }

    /** The status before the command shows the same sequence by chance: it says nothing about this command. */
    @Test
    fun `a status from before the command does not confirm it`() {
        val command = DashActiveCommand(sequence = 5, kind = DashCommandKind.BOLUS, historyId = 1, statusCountAtCreation = 3, sent = true)
        assertEquals(DashCommandConfirmation.NOT_CONFIRMED, DashCommandConfirmation.of(command, 3, 5))
    }

    // -- The manager ---------------------------------------------------------------------------

    @Test
    fun `a second command is refused while the first is not confirmed`() {
        val m = manager()
        m.createActiveCommand(DashCommandKind.BOLUS, historyId = 1, requestedBolusUnits = 5.0)
        m.markActiveCommandSent()
        assertFailsWith<DashCommandNotConfirmedException> { m.createActiveCommand(DashCommandKind.BOLUS, historyId = 2) }
    }

    @Test
    fun `the command takes the sequence number it is built with`() {
        val m = manager()
        repeat(9) { m.increaseMessageSequenceNumber() }
        assertEquals(9, m.createActiveCommand(DashCommandKind.TEMP_BASAL, historyId = 1).sequence)
    }

    @Test
    fun `a confirming status closes the command as success`() {
        val m = manager()
        repeat(9) { m.increaseMessageSequenceNumber() }
        m.createActiveCommand(DashCommandKind.BOLUS, historyId = 7, requestedBolusUnits = 5.0)
        m.markActiveCommandSent()
        m.updateFromStatus(status(lastSequence = 9))

        val (confirmation, command) = m.resolveActiveCommand()
        assertEquals(DashCommandConfirmation.SUCCESS, confirmation)
        assertEquals(7L, command?.historyId)
        assertEquals(5.0, command?.requestedBolusUnits)
        assertNull(m.activeCommand)
    }

    @Test
    fun `without a status the command stays open`() {
        val m = manager()
        m.createActiveCommand(DashCommandKind.BOLUS, historyId = 1)
        m.markActiveCommandSent()

        assertEquals(DashCommandConfirmation.NOT_CONFIRMED, m.resolveActiveCommand().first)
        assertNotNull(m.activeCommand)
    }

    @Test
    fun `a write that failed closes the command as not delivered`() {
        val m = manager()
        m.createActiveCommand(DashCommandKind.BOLUS, historyId = 1)
        m.markActiveCommandSent()
        m.markActiveCommandNotSent()

        assertEquals(DashCommandConfirmation.SENDING_FAILURE, m.resolveActiveCommand().first)
        assertNull(m.activeCommand)
    }

    @Test
    fun `the open command is stored before any byte goes`() {
        val store = MemoryStore()
        val m = manager(store)
        m.createActiveCommand(DashCommandKind.BOLUS, historyId = 1)
        assertNotNull(store.saved?.activeCommand)
        m.markActiveCommandSent()
        assertTrue(store.saved?.activeCommand?.sent == true)
    }

    /** The app is killed after the write: the new process still refuses a second bolus. */
    @Test
    fun `an open command survives a restart`() {
        val store = MemoryStore()
        manager(store).run {
            createActiveCommand(DashCommandKind.BOLUS, historyId = 1, requestedBolusUnits = 3.0)
            markActiveCommandSent()
        }
        val restarted = manager(store)
        assertEquals(DashCommandConfirmation.NOT_CONFIRMED, restarted.commandConfirmation())
        assertFailsWith<DashCommandNotConfirmedException> { restarted.createActiveCommand(DashCommandKind.BOLUS, historyId = 2) }
    }

    @Test
    fun `a status is stored and counted`() {
        val m = manager()
        m.updateFromStatus(status(lastSequence = 2))
        m.updateFromStatus(status(lastSequence = 3))
        assertEquals(2L, m.currentState().statusResponseCount)
        assertEquals(3, m.podStatus?.lastProgrammingCommandSequence)
    }

    // -- The stored form -----------------------------------------------------------------------

    @Test
    fun `a full state with status and open command roundtrips`() {
        val state = DashPodState(
            ltk = ByteArray(16) { it.toByte() },
            eapAkaSequenceNumber = 42,
            messageSequenceNumber = 9,
            uniqueId = 0x1f00ee87L,
            activationProgress = ActivationProgress.COMPLETED,
            bluetoothAddress = "AB:CD:EF:01:02:03",
            podStatus = status(lastSequence = 8).copy(activeAlerts = setOf(AlertType.LOW_RESERVOIR, AlertType.EXPIRATION)),
            statusResponseCount = 77,
            activeCommand = DashActiveCommand(
                sequence = 9, kind = DashCommandKind.TEMP_BASAL, historyId = 123456789L,
                statusCountAtCreation = 77, sent = true, tempBasalRate = 0.35, tempBasalMinutes = 30,
            ),
        )
        assertEquals(state, DashPodStateCodec.decode(DashPodStateCodec.encode(state)))
    }

    @Test
    fun `an empty state roundtrips`() {
        assertEquals(DashPodState(), DashPodStateCodec.decode(DashPodStateCodec.encode(DashPodState())))
    }

    /** A pod paired before this change keeps its key: version 1 is still read. */
    @Test
    fun `a version 1 state is still read`() {
        val ltk = ByteArray(16) { (it + 1).toByte() }
        val v1 = byteArrayOf(1, 0x03) + ltk +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5) + // eapAkaSequenceNumber 5
            byteArrayOf(0, 0, 0, 7) + // messageSequenceNumber 7
            byteArrayOf(0, 0, 0, 0, 0x1f, 0x00, 0xee.toByte(), 0x87.toByte()) + // uniqueId
            byteArrayOf(0, 0, 0, ActivationProgress.COMPLETED.ordinal.toByte())

        val state = DashPodStateCodec.decode(v1)

        assertTrue(state.ltk.contentEquals(ltk))
        assertEquals(5L, state.eapAkaSequenceNumber)
        assertEquals(7, state.messageSequenceNumber)
        assertEquals(0x1f00ee87L, state.uniqueId)
        assertEquals(ActivationProgress.COMPLETED, state.activationProgress)
        assertNull(state.activeCommand)
        assertNull(state.podStatus)
    }

    @Test
    fun `a truncated state fails closed`() {
        val state = DashPodState(activeCommand = DashActiveCommand(1, DashCommandKind.BOLUS, 1, 0, requestedBolusUnits = 1.0))
        val bytes = DashPodStateCodec.encode(state)
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes.copyOf(bytes.size - 3)) }
    }

    @Test
    fun `an unknown version fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        bytes[0] = 9
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes) }
    }
}
