package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.OCCLUDED
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isTempBasal
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashTempBasal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Step 8: `getPumpStatus` as Android `OmnipodDashPumpPlugin.getPumpStatus` (`getPodStatus`, then
 * `checkPodKaput` and `syncPumpFlows`), the alarm status a faulted pod sends, the bolus a fault ended
 * (given to `onCommandDecided` as [DashBolusEndedByFault]) and the confirmed temp basal of Android
 * (`podStateManager.tempBasal`) with the "nothing to cancel" shortcut of `cancelTempBasal`.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, then each exchange is a command and
 * its ACK. A bolus of 1 U is 20 pulses, its estimate 43 s. The bolus wait runs on the virtual time of
 * `runTest`; tests move it with `advanceTimeBy`, never `advanceUntilIdle`.
 */
class IosDashPumpStatusTest {

    private var fixture = DashPumpOnFakePod()
    private val f: DashPumpOnFakePod get() = fixture

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    /** The app stops and starts again: a new pump from what is stored, on the same pod, connected again. */
    private fun restart() {
        val saved = f.store.saved
        f.pod.reconnect()
        val pod = f.pod
        fixture = DashPumpOnFakePod(store = MemoryDashPodStateStore().apply { this.saved = saved }, pod = pod)
    }

    // ---------------------------------------------------------------- status read

    @Test
    fun `a status read updates the state and the flows`() = runTest {
        f.pairAndConnect()
        f.reservoirPulses = 400
        f.nowMs = 50_000L
        val before = f.stateManager.statusResponseCount

        f.pump.getPumpStatus("status")

        assertEquals(before + 1, f.stateManager.statusResponseCount)
        assertEquals(PodStatus.RUNNING_ABOVE_MIN_VOLUME, f.stateManager.podStatus?.podStatus)
        assertEquals(400, f.stateManager.podStatus?.reservoirPulsesRemaining)
        assertEquals(50_000L, f.pump.lastDataTime.value, "Android lastDataTime: the time of the last status")
        assertEquals(20.0, f.pump.reservoirLevel.value.cU, 1e-9, "400 pulses x 0.05 U")
        assertTrue(f.pod.commands.single()[6] == CommandType.GET_STATUS.value, "one status read, nothing else")
    }

    @Test
    fun `a pod with more than 50 units shows the Android default`() = runTest {
        f.pairAndConnect()

        f.pump.getPumpStatus("status")

        assertEquals(75.0, f.pump.reservoirLevel.value.cU, "the reading 1023 means more than 50 U")
    }

    @Test
    fun `a reading of 1023 after a lower one keeps the lower one`() = runTest {
        f.pairAndConnect()
        f.reservoirPulses = 200
        f.pump.getPumpStatus("status")
        f.reservoirPulses = 0x3ff

        f.pump.getPumpStatus("status")

        assertEquals(10.0, f.pump.reservoirLevel.value.cU, 1e-9, "Android keeps the last reading below 1023")
        val commands = f.pod.commands.size
        val result = f.pump.deliverTreatment(bolus(20.0))
        assertFalse(result.success, result.comment)
        assertTrue(result.comment.contains("reservoir"), result.comment)
        assertEquals(commands, f.pod.commands.size, "nothing sent")
    }

    @Test
    fun `an alarm status keeps the last reservoir reading`() = runTest {
        f.pairAndConnect()
        f.reservoirPulses = 200
        f.pump.getPumpStatus("status")
        // The alarm status of the fake pod reports 1023 reservoir pulses.
        f.faultAlarmType = OCCLUDED

        f.pump.getPumpStatus("status")

        assertTrue(f.stateManager.isPodKaput)
        assertEquals(10.0, f.pump.reservoirLevel.value.cU, 1e-9)
    }

    @Test
    fun `the last reservoir reading below 1023 survives a restart`() = runTest {
        f.pairAndConnect()
        f.reservoirPulses = 200
        f.pump.getPumpStatus("status")
        f.reservoirPulses = 0x3ff
        f.pump.getPumpStatus("status")

        restart()
        f.pump.getPumpStatus("status")

        assertEquals(10.0, f.pump.reservoirLevel.value.cU, 1e-9)
    }

    @Test
    fun `the reservoir is 0 before the activation is completed`() = runTest {
        f.pairAndConnect(activated = false)
        f.reservoirPulses = 400

        f.pump.getPumpStatus(IosDashPump.REQUESTED_BY_USER)

        assertEquals(0.0, f.pump.reservoirLevel.value.cU)
    }

    @Test
    fun `a bolus larger than the reservoir is refused with nothing sent`() = runTest {
        f.pairAndConnect()
        f.reservoirPulses = 20
        f.pump.getPumpStatus("status")
        val commands = f.pod.commands.size

        val result = f.pump.deliverTreatment(bolus(1.5))

        assertFalse(result.success, result.comment)
        assertEquals(0.0, result.bolusDelivered)
        assertTrue(result.comment.contains("reservoir"), result.comment)
        assertEquals(commands, f.pod.commands.size, "nothing sent")
        assertEquals(0, f.bolusCommands())
        assertNull(f.stateManager.activeCommand)
    }

    @Test
    fun `a bolus up to the reservoir goes`() = runTest {
        f.pairAndConnect()
        f.reservoirPulses = 20
        f.pump.getPumpStatus("status")

        val result = f.pump.deliverTreatment(bolus(1.0))

        assertTrue(result.success, result.comment)
        assertEquals(1, f.bolusCommands())
    }

    // ---------------------------------------------------------------- a status decides an open command

    @Test
    fun `a status from getPumpStatus decides a temp basal whose answer was lost`() = runTest {
        f.pairAndConnect()
        f.nowMs = 10_000L
        // No status known: a cancel goes first (7-8), then the temp basal (9), whose answer is lost.
        f.pod.dropLinkWhileAnswering = 9 to 0

        val result = f.pump.setTempBasalAbsolute(1.5, 30, true, PumpSync.TemporaryBasalType.NORMAL)

        assertFalse(result.success, result.comment)
        assertEquals(DashCommandKind.TEMP_BASAL, f.stateManager.activeCommand?.kind)
        assertNull(f.stateManager.tempBasal, "not confirmed yet")

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.nowMs = 20_000L
        f.pump.getPumpStatus("decide the open temp basal")

        assertNull(f.stateManager.activeCommand)
        val outcome = f.decided.last()
        assertEquals(DashCommandKind.TEMP_BASAL, outcome.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, outcome.confirmation)
        assertTrue(outcome.decidedLate)
        assertEquals(DashTempBasal(startTimeMs = 10_000L, rate = 1.5, durationInMinutes = 30), f.stateManager.tempBasal, "from the send time")
        assertTrue(f.pump.tempBasalActive())
    }

    // ---------------------------------------------------------------- faulted pod

    @Test
    fun `a faulted pod is read from its alarm status and reports kaput`() = runTest {
        f.pairAndConnect()
        f.faultAlarmType = OCCLUDED

        f.pump.getPumpStatus("status")

        assertEquals(PodStatus.ALARM, f.stateManager.reportedPodStatus)
        assertTrue(f.stateManager.isPodKaput)
        assertEquals(AlarmType.ALARM_OCCLUDED, f.stateManager.alarmType)
        assertTrue(f.pump.isSuspended(), "an alarm status shows delivery suspended")
        assertTrue(f.endedByFault.isEmpty(), "no bolus to end")

        val commands = f.pod.commands.size
        assertFalse(f.pump.deliverTreatment(bolus(0.5)).success)
        assertFalse(f.pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)
        assertEquals(commands, f.pod.commands.size, "no delivery command goes to a pod in alarm")
    }

    @Test
    fun `a fault during the bolus ends it with what went in and gives the correction`() = runTest {
        f.pairAndConnect()
        f.nowMs = 30_000L
        f.bolusPulsesOnNextBolus = 20

        val delivery = async { f.pump.deliverTreatment(bolus(1.0)) }
        advanceTimeBy(20_000L)
        // The pod faults with 8 pulses (0.4 U) not given.
        f.bolusPulsesRemaining = 8
        f.faultAlarmType = OCCLUDED
        f.nowMs = 90_000L
        val result = delivery.await()

        assertTrue(result.success, result.comment)
        assertEquals(0.6, result.bolusDelivered, 1e-9)
        val correction = f.endedByFault.single()
        assertEquals(0.6, assertNotNull(correction.bolus.deliveredUnits()), 1e-9)
        assertEquals(1.0, correction.bolus.requestedUnits)
        assertEquals(30_000L, correction.bolus.createdAtMs, "recorded at the time of the bolus command")
        assertEquals(AlarmType.ALARM_OCCLUDED, correction.alarmType)
        assertEquals(90_000L, correction.decidedAtMs)
        assertEquals(listOf(DashCommandKind.BOLUS), f.decided.map { it.command.kind }, "the bolus itself, once")
        assertIs<DashCommandOutcome>(f.outcomes.first())
        assertIs<DashBolusEndedByFault>(f.outcomes.last())
        assertTrue(assertNotNull(f.stateManager.lastBolus).deliveryComplete)
    }

    /**
     * The receiver contract, pinned: a fault inside the bolus wait gives first the SUCCESS outcome of the
     * bolus (not late: Android `pumpSyncBolusStart`, the asked units), then the correction. Both name the
     * same bolus by its command time, as Android updates one record by its `pumpId`. The receiver
     * replaces the amount of that record; it never adds a second bolus. The `bolusDelivered` of the
     * result is for the caller only: no queue records a bolus from it (on Android the pump records it).
     */
    @Test
    fun `a fault in the bolus wait corrects the record of the same bolus`() = runTest {
        f.pairAndConnect()
        f.nowMs = 30_000L
        f.bolusPulsesOnNextBolus = 20

        val delivery = async { f.pump.deliverTreatment(bolus(1.0)) }
        advanceTimeBy(20_000L)
        f.bolusPulsesRemaining = 8
        f.faultAlarmType = OCCLUDED
        delivery.await()

        val start = f.decided.single()
        assertEquals(DashCommandConfirmation.SUCCESS, start.confirmation)
        assertFalse(start.decidedLate, "decided inside the call")
        assertEquals(1.0, start.command.requestedBolusUnits)
        val correction = f.endedByFault.single()
        assertEquals(30_000L, assertNotNull(start.command.createdAtMs))
        assertEquals(start.command.createdAtMs, correction.bolus.createdAtMs, "the key of the record to update")
        assertEquals(listOf<DashPumpOutcome>(start, correction), f.outcomes, "the start first, then the correction")
    }

    @Test
    fun `the correction is given once`() = runTest {
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 20

        val delivery = async { f.pump.deliverTreatment(bolus(1.0)) }
        advanceTimeBy(20_000L)
        f.bolusPulsesRemaining = 8
        f.faultAlarmType = OCCLUDED
        delivery.await()
        f.pump.getPumpStatus("status")
        f.pump.getPumpStatus("status")

        assertEquals(1, f.endedByFault.size)
    }

    @Test
    fun `a fault first seen after a crash still corrects the bolus`() = runTest {
        f.pairAndConnect()
        f.nowMs = 30_000L
        f.bolusPulsesOnNextBolus = 40

        // The app is stopped in the middle of the bolus wait (a cancel is the app shutdown).
        val delivery = async { f.pump.deliverTreatment(bolus(2.0)) }
        advanceTimeBy(10_000L)
        runCurrent()
        delivery.cancel()
        runCurrent()
        assertFalse(assertNotNull(f.stateManager.lastBolus).deliveryComplete, "the stored bolus has not ended")
        assertTrue(f.endedByFault.isEmpty())

        restart()
        // On the new start the pod is in alarm with 10 pulses (0.5 U) not given.
        f.bolusPulsesRemaining = 10
        f.faultAlarmType = OCCLUDED
        f.pump.getPumpStatus("first status after the restart")

        val correction = f.endedByFault.single()
        assertEquals(1.5, assertNotNull(correction.bolus.deliveredUnits()), 1e-9)
        assertEquals(30_000L, correction.bolus.createdAtMs)
        assertTrue(assertNotNull(f.stateManager.lastBolus).deliveryComplete)
    }

    /**
     * Deliberate difference from Android (it counts insulin wrong): the bolus answer is lost, then the
     * pod faults. The alarm status decides the bolus SUCCESS late and gives the pulses left, so the
     * correction is asked - left. Android makes the last bolus with all of it left and records 0 U.
     */
    @Test
    fun `a bolus decided late by the alarm status is corrected with what went in`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0

        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.bolusPulsesRemaining = 6
        f.faultAlarmType = OCCLUDED
        f.pump.getPumpStatus("decide the open bolus")

        val bolus = f.decided.single()
        assertEquals(DashCommandKind.BOLUS, bolus.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, bolus.confirmation)
        assertTrue(bolus.decidedLate)
        val correction = f.endedByFault.single()
        assertEquals(0.7, assertNotNull(correction.bolus.deliveredUnits()), 1e-9)
    }

    @Test
    fun `a pod discarded after a fault does not give the bolus again`() = runTest {
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 20
        val delivery = async { f.pump.deliverTreatment(bolus(1.0)) }
        advanceTimeBy(20_000L)
        f.bolusPulsesRemaining = 8
        f.faultAlarmType = OCCLUDED
        delivery.await()

        f.pump.discardPod()

        assertEquals(1, f.endedByFault.size)
        assertNull(f.stateManager.lastBolus, "the state is cleared")
    }

    // ---------------------------------------------------------------- confirmed temp basal

    @Test
    fun `nothing is sent to cancel when no temp basal runs and none is expected`() = runTest {
        f.pairAndConnect()
        f.expectedTempBasalRunning = false

        val result = f.pump.cancelTempBasal(false)

        assertTrue(result.success, result.comment)
        assertFalse(result.enacted)
        assertTrue(f.pod.commands.isEmpty(), "Android: nothing to cancel")
    }

    @Test
    fun `a cancel is sent while AAPS expects a temp basal`() = runTest {
        f.pairAndConnect()

        val result = f.pump.cancelTempBasal(false)

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertEquals(DashCommandKind.STOP_TEMP_BASAL, f.decided.single().command.kind)
    }

    @Test
    fun `a cancel is sent while the pod runs a confirmed temp basal`() = runTest {
        f.pairAndConnect()
        assertTrue(f.pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)
        assertNotNull(f.stateManager.tempBasal)
        f.expectedTempBasalRunning = false

        val result = f.pump.cancelTempBasal(false)

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertNull(f.stateManager.tempBasal, "a confirmed cancel clears it")
        assertFalse(f.pump.tempBasalActive())
    }

    @Test
    fun `a temp basal that ran out is not active`() = runTest {
        f.pairAndConnect()
        f.nowMs = 0L
        assertTrue(f.pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)
        assertTrue(f.pump.tempBasalActive())

        f.nowMs = 30 * 60_000L
        f.expectedTempBasalRunning = false

        assertFalse(f.pump.tempBasalActive())
        assertFalse(f.pump.cancelTempBasal(false).enacted, "nothing to cancel")
        assertEquals(1, f.pod.commands.count { isTempBasal(it) })
    }
}
