package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The last bolus in the pod state: Android `createLastBolus`, `updateLastBolusFromResponse`,
 * `markComplete` and what `handleCommandConfirmation` does on SET_BOLUS and CANCEL_BOLUS. Also what a
 * crash keeps: the store here holds the encoded bytes, as the Keychain does.
 */
class DashLastBolusTest {

    /** Keeps only the bytes, so every load is a decode, as after a restart of the app. */
    private class BytesStore : DashPodStateStore {

        var bytes: ByteArray? = null
        var writes = 0
        override fun load(): DashPodState? = bytes?.let { DashPodStateCodec.decode(it) }
        override fun save(state: DashPodState) {
            bytes = DashPodStateCodec.encode(state)
            writes++
        }

        override fun clear() {
            bytes = null
        }
    }

    private val store = BytesStore()
    private var manager = DashPodStateManager(store)

    /** The app is killed and starts again: everything not in the store is gone. */
    private fun restart() {
        manager = DashPodStateManager(store)
    }

    private fun status(pulsesLeft: Int, bolusRunning: Boolean, lastSequence: Int = 0x0f) = DashPodStatus(
        podStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        deliveryStatus = if (bolusRunning) DeliveryStatus.BOLUS_AND_BASAL_ACTIVE else DeliveryStatus.BASAL_ACTIVE,
        totalPulsesDelivered = 100,
        reservoirPulsesRemaining = 1000,
        minutesSinceActivation = 60,
        bolusPulsesRemaining = pulsesLeft,
        activeAlerts = emptySet(),
        lastProgrammingCommandSequence = lastSequence,
        receivedAtMs = 1_000L,
    )

    /** A delivery command sent and confirmed by the next status, as `IosDashPump` does it. */
    private fun confirmed(kind: DashCommandKind, statusAfter: (sequence: Int) -> DashPodStatus, bolusUnits: Double? = null): DashLastBolus? {
        val command = manager.createActiveCommand(kind, historyId = 7L, createdAtMs = 500L, requestedBolusUnits = bolusUnits)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(statusAfter(command.sequence))
        val resolved = manager.resolveActiveCommand(nowMs = 2_000L)
        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)
        assertNotNull(resolved.command)
        return resolved.endedBolus
    }

    private fun bolus(units: Double) {
        // The answer to the bolus: Android does not take the pulses left from it, the bolus is made after.
        confirmed(DashCommandKind.BOLUS, { status(pulsesLeft = 0, bolusRunning = true, lastSequence = it) }, bolusUnits = units)
    }

    @Test
    fun `a confirmed bolus becomes the last bolus with all of it left`() {
        bolus(2.0)

        val last = assertNotNull(manager.lastBolus)
        assertEquals(2.0, last.requestedUnits)
        assertEquals(2.0, last.bolusUnitsRemaining)
        assertFalse(last.deliveryComplete)
        assertNull(last.deliveredUnits(), "not ended yet")
        assertEquals(2_000L, last.startTimeMs)
        assertEquals(500L, last.createdAtMs, "the time of the bolus command, for the record")
        assertEquals(7L, last.historyId)
    }

    @Test
    fun `a denied bolus does not become the last bolus`() {
        val command = manager.createActiveCommand(DashCommandKind.BOLUS, historyId = 0L, createdAtMs = 0L, requestedBolusUnits = 2.0)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(pulsesLeft = 0, bolusRunning = false, lastSequence = (command.sequence + 1) and 0x0f))
        val resolved = manager.resolveActiveCommand(nowMs = 0L)

        assertEquals(DashCommandConfirmation.DENIED, resolved.confirmation)
        assertNotNull(resolved.command)
        assertNull(resolved.endedBolus)
        assertNull(manager.lastBolus)
    }

    @Test
    fun `each status sets what is left and zero left ends the bolus`() {
        bolus(2.0)

        manager.updateFromStatus(status(pulsesLeft = 30, bolusRunning = true))
        assertEquals(1.5, manager.lastBolus?.bolusUnitsRemaining)
        assertFalse(manager.lastBolus?.deliveryComplete == true)

        manager.updateFromStatus(status(pulsesLeft = 0, bolusRunning = false))
        assertTrue(manager.lastBolus?.deliveryComplete == true)
        assertEquals(2.0, manager.lastBolus?.deliveredUnits())
    }

    @Test
    fun `the units left are rounded to a pulse as Android`() {
        bolus(2.0)

        manager.updateFromStatus(status(pulsesLeft = 7, bolusRunning = true))

        assertEquals(0.35, manager.lastBolus?.bolusUnitsRemaining)
    }

    @Test
    fun `a confirmed stop ends the bolus with what was left in its answer`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 30, bolusRunning = true))

        // The answer to the stop: no bolus running, 24 pulses (1.2 U) not delivered.
        val ended = confirmed(DashCommandKind.STOP_BOLUS, { status(pulsesLeft = 24, bolusRunning = false, lastSequence = it) })

        assertNotNull(ended)
        assertTrue(ended.deliveryComplete)
        assertEquals(0.8, assertNotNull(ended.deliveredUnits()), 1e-9)
        assertEquals(ended, manager.lastBolus)
    }

    /**
     * Android writes the units left of every status into the last bolus, also after it ended. The
     * status after a stop shows 0 left (no bolus runs), and the stopped bolus would then count all of
     * it. Here an ended bolus does not change.
     */
    @Test
    fun `a status after the end does not change what went in`() {
        bolus(2.0)
        confirmed(DashCommandKind.STOP_BOLUS, { status(pulsesLeft = 24, bolusRunning = false, lastSequence = it) })

        manager.updateFromStatus(status(pulsesLeft = 0, bolusRunning = false))
        manager.updateFromStatus(status(pulsesLeft = 40, bolusRunning = false))

        assertEquals(0.8, assertNotNull(manager.lastBolus?.deliveredUnits()), 1e-9)
    }

    @Test
    fun `units left out of range count the whole bolus and never more`() {
        val tooMuchLeft = DashLastBolus(startTimeMs = 0L, requestedUnits = 2.0, bolusUnitsRemaining = 10.0, deliveryComplete = true)
        val negative = tooMuchLeft.copy(bolusUnitsRemaining = -0.5)

        assertEquals(2.0, tooMuchLeft.deliveredUnits())
        assertEquals(2.0, negative.deliveredUnits())
    }

    @Test
    fun `a new bolus replaces the last one`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 0, bolusRunning = false))

        bolus(1.0)

        assertEquals(1.0, manager.lastBolus?.requestedUnits)
        assertFalse(manager.lastBolus?.deliveryComplete == true)
    }

    @Test
    fun `marking an ended bolus again changes nothing`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 10, bolusRunning = true))
        val first = manager.markLastBolusComplete()

        assertEquals(first, manager.markLastBolusComplete())
        assertEquals(1.5, assertNotNull(first?.deliveredUnits()), 1e-9)
    }

    // ---------------------------------------------------------------- a crash during the wait

    @Test
    fun `a crash during the wait keeps the bolus and the next status counts it`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 30, bolusRunning = true))

        restart()

        val kept = assertNotNull(manager.lastBolus, "the bolus survives the restart")
        assertEquals(2.0, kept.requestedUnits)
        assertEquals(1.5, kept.bolusUnitsRemaining)
        assertFalse(kept.deliveryComplete)
        assertEquals(500L, kept.createdAtMs)

        // The pod went on and finished the bolus while the app was down.
        manager.updateFromStatus(status(pulsesLeft = 0, bolusRunning = false))
        assertEquals(2.0, manager.lastBolus?.deliveredUnits())
    }

    /**
     * The stop was sent, then the app died before its answer. After the restart the stop is still
     * open, and the status that decides it ends the bolus with the units left in that status. A real
     * pod may report 0 left once no bolus runs: the whole bolus is then counted, which is more than
     * went in, never less, and never more than asked.
     */
    @Test
    fun `a crash with the stop open ends the bolus when a status decides the stop`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 30, bolusRunning = true))
        val stop = manager.createActiveCommand(DashCommandKind.STOP_BOLUS, historyId = 0L, createdAtMs = 600L)
        manager.markActiveCommandSent()

        restart()

        assertEquals(DashCommandKind.STOP_BOLUS, manager.activeCommand?.kind, "the open stop survives")
        manager.updateFromDefaultStatus(status(pulsesLeft = 20, bolusRunning = false, lastSequence = stop.sequence))
        val resolved = manager.resolveActiveCommand(nowMs = 3_000L)
        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)
        val ended = assertNotNull(resolved.endedBolus)

        assertEquals(1.0, assertNotNull(ended.deliveredUnits()), 1e-9)
        restart()
        assertEquals(ended, manager.lastBolus, "the ended bolus is stored")
    }

    /**
     * A crash between closing the bolus command and storing the last bolus would leave neither: a
     * bolus the pod took, counted nowhere. Both go in one write.
     */
    @Test
    fun `closing a confirmed bolus and storing it as the last bolus is one write`() {
        val command = manager.createActiveCommand(DashCommandKind.BOLUS, historyId = 7L, createdAtMs = 500L, requestedBolusUnits = 2.0)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(pulsesLeft = 0, bolusRunning = true, lastSequence = command.sequence))
        val writesBefore = store.writes

        manager.resolveActiveCommand(nowMs = 2_000L)

        assertEquals(writesBefore + 1, store.writes)
        restart()
        assertNull(manager.activeCommand)
        assertEquals(2.0, manager.lastBolus?.requestedUnits)
    }

    /** The answer to a stop of all delivery: delivery suspended, [pulsesLeft] of the bolus not delivered. */
    private fun suspendedStatus(pulsesLeft: Int, lastSequence: Int) =
        status(pulsesLeft = pulsesLeft, bolusRunning = false, lastSequence = lastSequence).copy(deliveryStatus = DeliveryStatus.SUSPENDED)

    /**
     * Deliberate difference from Android: a confirmed suspend (StopDelivery ALL) also stops a running
     * bolus. Android does not end the last bolus there, so the next status (0 left) counts all of it.
     * Here the bolus ends with what the answer to the suspend left, as for a stop of the bolus.
     */
    @Test
    fun `a confirmed suspend ends a running bolus with what was left in its answer`() {
        bolus(6.0)
        manager.updateFromStatus(status(pulsesLeft = 100, bolusRunning = true))

        // 60 pulses (3 U) not delivered when the suspend stopped the bolus.
        val ended = confirmed(DashCommandKind.SUSPEND_DELIVERY, { suspendedStatus(pulsesLeft = 60, lastSequence = it) })

        assertNotNull(ended, "the ended bolus is given back, to be recorded with what went in")
        assertTrue(ended.deliveryComplete)
        assertEquals(3.0, assertNotNull(ended.deliveredUnits()), 1e-9)
        manager.updateFromStatus(status(pulsesLeft = 0, bolusRunning = false))
        restart()
        assertEquals(3.0, assertNotNull(manager.lastBolus?.deliveredUnits()), 1e-9, "a later status does not count the rest")
    }

    @Test
    fun `a confirmed stop of all delivery ends a running bolus with what was left in its answer`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 30, bolusRunning = true))

        val ended = confirmed(DashCommandKind.STOP_ALL, { suspendedStatus(pulsesLeft = 20, lastSequence = it) })

        assertEquals(1.0, assertNotNull(assertNotNull(ended).deliveredUnits()), 1e-9)
        assertEquals(ended, manager.lastBolus)
    }

    @Test
    fun `a confirmed suspend after the bolus ended gives no ended bolus`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 0, bolusRunning = false))
        val before = manager.lastBolus

        val ended = confirmed(DashCommandKind.SUSPEND_DELIVERY, { suspendedStatus(pulsesLeft = 0, lastSequence = it) })

        assertNull(ended, "nothing to correct: the bolus ended on its own")
        assertEquals(before, manager.lastBolus)
    }

    @Test
    fun `a denied suspend does not end a running bolus`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 30, bolusRunning = true))
        val command = manager.createActiveCommand(DashCommandKind.SUSPEND_DELIVERY, historyId = 0L, createdAtMs = 600L)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(pulsesLeft = 30, bolusRunning = true, lastSequence = (command.sequence + 1) and 0x0f))

        val resolved = manager.resolveActiveCommand(nowMs = 3_000L)

        assertEquals(DashCommandConfirmation.DENIED, resolved.confirmation)
        assertNull(resolved.endedBolus)
        assertFalse(manager.lastBolus?.deliveryComplete == true)
    }

    /** The same for a confirmed stop: the stop is closed and the bolus ended in one write. */
    @Test
    fun `closing a confirmed stop and ending the bolus is one write`() {
        bolus(2.0)
        manager.updateFromStatus(status(pulsesLeft = 30, bolusRunning = true))
        val stop = manager.createActiveCommand(DashCommandKind.STOP_BOLUS, historyId = 0L, createdAtMs = 600L)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(pulsesLeft = 30, bolusRunning = false, lastSequence = stop.sequence))
        val writesBefore = store.writes

        manager.resolveActiveCommand(nowMs = 3_000L)

        assertEquals(writesBefore + 1, store.writes)
        restart()
        assertNull(manager.activeCommand)
        val ended = assertNotNull(manager.lastBolus)
        assertTrue(ended.deliveryComplete)
        assertEquals(0.5, assertNotNull(ended.deliveredUnits()), 1e-9)
    }
}
