package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A bolus followed to its end, as Android `OmnipodDashPumpPlugin.deliverTreatment` and
 * `waitForBolusDeliveryToComplete`, on the real [IosDashPump] against the simulated pod.
 *
 * The wait runs on the virtual time of `runTest` (the pump sleeps with `delay`): tests move the time
 * with `advanceTimeBy` and change what the pod reports in between, never `advanceUntilIdle`.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, 7 the bolus and 8 its ACK, then
 * the exchanges of the wait from 9 on. A bolus of 1 U is 20 pulses, its estimate 43 s; 2 U is 40
 * pulses, 83 s.
 */
class IosDashPumpBolusTest {

    private var fixture: DashPumpOnFakePod? = null

    @AfterTest
    fun closePod() {
        fixture?.pod?.close()
        fixture?.pod?.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    private fun newFixture(): DashPumpOnFakePod = DashPumpOnFakePod().also { fixture = it }

    @Test
    fun `the progress follows the pod and reaches 100 at the end`() = runTest {
        val progress = dashBolusProgressData(backgroundScope)
        val f = DashPumpOnFakePod(bolusProgressData = progress).also { fixture = it }
        f.pairAndConnect()
        progress.start(insulin = 1.0, isSMB = false)
        f.bolusPulsesOnNextBolus = 20

        val delivery = async { f.pump.deliverTreatment(bolus(1.0)) }
        advanceTimeBy(20_000L)
        f.bolusPulsesRemaining = 10
        // The estimate ends at 43 s, then a status: 10 pulses left, the bolus still runs.
        advanceTimeBy(25_000L)

        assertEquals(50, progress.state.value?.percent, "half of it went in, from the pod")
        assertEquals(0.5, assertNotNull(progress.state.value?.delivered?.cU), 1e-9)
        assertEquals(1, f.bolusCommands())

        f.bolusPulsesRemaining = 0
        f.bolusRunning = false
        val result = delivery.await()

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertEquals(1.0, result.bolusDelivered)
        assertEquals(100, progress.state.value?.percent)
        assertTrue(f.stateManager.lastBolus?.deliveryComplete == true)
        assertEquals(1, f.bolusCommands(), "the bolus went to the pod once")
    }

    @Test
    fun `a stop during the bolus reports only the part that went in`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 40

        val delivery = async { f.pump.deliverTreatment(bolus(2.0)) }
        advanceTimeBy(10_000L)
        f.bolusPulsesRemaining = 30
        f.pump.stopBolusDelivering()
        val result = delivery.await()

        assertTrue(result.success, result.comment)
        assertEquals(0.5, result.bolusDelivered, 1e-9)
        assertEquals(listOf(30), f.bolusStops, "one stop, with 30 pulses left")
        assertEquals(1, f.bolusCommands())
        assertEquals(listOf(DashCommandKind.BOLUS, DashCommandKind.STOP_BOLUS), f.decided.map { it.command.kind })
        val stop = f.decided.last()
        assertEquals(DashCommandConfirmation.SUCCESS, stop.confirmation)
        val ended = assertNotNull(stop.endedBolus, "the receiver gets the bolus to record again")
        assertEquals(0.5, assertNotNull(ended.deliveredUnits()), 1e-9)
        assertEquals(f.decided.first().command.createdAtMs, ended.createdAtMs, "recorded at the time of the bolus")
        assertNull(f.stateManager.activeCommand)
    }

    /**
     * The status of the wait is lost and the link with it. The pod took the bolus, so the asked amount
     * is reported (Android "updated later"), never more, and the bolus is not sent again. The next
     * status ends the bolus with what the pod reports.
     */
    @Test
    fun `a lost status during the wait does not count more than asked nor deliver twice`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 20
        f.pod.dropLinkOnPhoneMessage = 9 to Packet.FIRST

        val result = f.pump.deliverTreatment(bolus(1.0))

        assertTrue(result.success, result.comment)
        assertEquals(1.0, result.bolusDelivered)
        assertEquals(1, f.bolusCommands(), "nothing sent again")
        assertEquals(1, f.decided.size, "only the bolus itself")
        assertFalse(f.stateManager.lastBolus?.deliveryComplete == true, "no status ended it yet")

        f.pod.dropLinkOnPhoneMessage = null
        f.bolusRunning = false
        f.bolusPulsesRemaining = 0
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.pump.getPumpStatus("after the bolus")

        assertEquals(1.0, f.stateManager.lastBolus?.deliveredUnits())
        assertEquals(1, f.bolusCommands())
        assertEquals(1, f.decided.size)
    }

    /**
     * The pod takes the stop but its answer is lost. The stop stays open and is never sent a second
     * time (it would hide what happened to the first one); the wait reports the asked amount. The
     * next status decides the stop and ends the bolus: never more than asked.
     */
    @Test
    fun `a lost answer to the stop is decided by the next status and never counts more than asked`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 40
        f.pod.dropLinkWhileAnswering = 9 to 0

        val delivery = async { f.pump.deliverTreatment(bolus(2.0)) }
        advanceTimeBy(10_000L)
        f.bolusPulsesRemaining = 30
        f.pump.stopBolusDelivering()
        val result = delivery.await()

        assertTrue(result.bolusDelivered <= 2.0, "${result.bolusDelivered}")
        assertEquals(listOf(30), f.bolusStops, "the stop went once")
        val open = assertNotNull(f.stateManager.activeCommand, "the stop stays open")
        assertEquals(DashCommandKind.STOP_BOLUS, open.kind)

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.pump.getPumpStatus("decide the stop")

        assertNull(f.stateManager.activeCommand)
        val stop = f.decided.last()
        assertEquals(DashCommandKind.STOP_BOLUS, stop.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, stop.confirmation)
        assertTrue(stop.decidedLate)
        val delivered = assertNotNull(stop.endedBolus?.deliveredUnits())
        assertTrue(delivered in 0.0..2.0, "$delivered")
        assertEquals(1, f.bolusCommands())
        assertEquals(1, f.bolusStops.size)
    }

    /**
     * The app dies during the wait. The store keeps the bolus (asked, left, not ended), so after the
     * restart the next status counts it.
     */
    @Test
    fun `a crash during the wait keeps enough to count the bolus`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 20

        var result: PumpEnactResult? = null
        val delivery = launch { result = f.pump.deliverTreatment(bolus(1.0)) }
        advanceTimeBy(10_000L)
        delivery.cancel()
        delivery.join()

        assertNull(result, "the wait never finished")
        val restarted = DashPodStateManager(f.store)
        val kept = assertNotNull(restarted.lastBolus, "the bolus is in the store")
        assertEquals(1.0, kept.requestedUnits)
        assertFalse(kept.deliveryComplete)
        assertNull(restarted.activeCommand, "the bolus itself was decided before the crash")
        assertFalse(f.pump.isBusy())

        // The pod finished the bolus while the app was down.
        f.bolusRunning = false
        f.bolusPulsesRemaining = 0
        f.pump.getPumpStatus("after the restart")

        assertEquals(1.0, f.stateManager.lastBolus?.deliveredUnits())
        assertEquals(1.0, DashPodStateManager(f.store).lastBolus?.deliveredUnits(), "stored too")
        assertEquals(1, f.bolusCommands())
    }

    @Test
    fun `a stop asked before the bolus is sent sends nothing as Android`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        // Another command holds the lock, so the bolus waits for it.
        assertTrue(f.pump.tryLockLink())
        val delivery = async { f.pump.deliverTreatment(bolus(1.0)) }
        runCurrent()

        f.pump.stopBolusDelivering()
        f.pump.unlockLink()
        val result = delivery.await()

        // Android `observeDeliveryNotCanceled`: `success(bolusCanceled)`, nothing enacted.
        assertTrue(result.success, result.comment)
        assertFalse(result.enacted)
        assertEquals(0.0, result.bolusDelivered)
        assertEquals(0, f.bolusCommands())
        assertNull(f.stateManager.activeCommand)
    }

    @Test
    fun `a stop outside a bolus does nothing and the next bolus goes`() = runTest {
        val f = newFixture()
        f.pairAndConnect()

        f.pump.stopBolusDelivering()
        val result = f.pump.deliverTreatment(bolus(1.0))

        assertTrue(result.success, result.comment)
        assertEquals(1.0, result.bolusDelivered)
        assertTrue(f.bolusStops.isEmpty())
    }

    @Test
    fun `a bolus is refused while the pod reports a bolus running`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusRunning = true
        f.bolusPulsesRemaining = 10
        f.pump.getPumpStatus("a bolus runs")

        val result = f.pump.deliverTreatment(bolus(1.0))

        assertFalse(result.success)
        assertTrue(result.comment.contains("already"), result.comment)
        assertEquals(0, f.bolusCommands())
        assertNull(f.stateManager.activeCommand)
    }

    /**
     * The pod takes the bolus but its answer is not a status (here a NAK), so the bolus is not
     * confirmed, and the user asked to stop it during the send. Android `waitForBolusDeliveryToComplete`
     * then reads a status first (`bolusCanceled && activeCommand != null`), which decides the bolus,
     * and sends the stop. The stop must reach the pod: without it the whole bolus goes in.
     */
    @Test
    fun `a stop asked while the bolus is not confirmed is still sent as Android`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 30
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            val answer = normal(command)
            if (DashPumpOnFakePod.isBolus(command)) {
                f.pump.stopBolusDelivering()
                parityHex(NAK)
            } else {
                answer
            }
        }

        val result = f.pump.deliverTreatment(bolus(2.0))

        assertEquals(listOf(30), f.bolusStops, "the stop reached the pod, with 30 pulses left")
        assertTrue(result.success, result.comment)
        assertEquals(0.5, result.bolusDelivered, 1e-9)
        assertEquals(listOf(DashCommandKind.BOLUS, DashCommandKind.STOP_BOLUS), f.decided.map { it.command.kind })
        assertTrue(f.decided.all { it.confirmation == DashCommandConfirmation.SUCCESS })
        assertFalse(f.decided.first().decidedLate, "decided inside the call: the result reports it, not a late outcome")
        assertEquals(0.5, assertNotNull(f.decided.last().endedBolus?.deliveredUnits()), 1e-9)
        assertNull(f.stateManager.activeCommand)
        assertEquals(1, f.bolusCommands(), "the bolus went to the pod once")
    }

    /**
     * The same, but no status can be read (the link is gone). Nothing is known, so nothing is reported
     * delivered and the bolus stays open until a status decides it (fail closed). No stop goes blind.
     */
    @Test
    fun `a stop asked while the bolus is not confirmed and no status can be read reports nothing`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 30
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            val answer = normal(command)
            if (DashPumpOnFakePod.isBolus(command)) {
                f.pump.stopBolusDelivering()
                parityHex(NAK)
            } else {
                answer
            }
        }
        // The status read after the bolus is message 9: the link drops on it.
        f.pod.dropLinkOnPhoneMessage = 9 to Packet.FIRST

        val result = f.pump.deliverTreatment(bolus(2.0))

        assertFalse(result.success, result.comment)
        assertEquals(0.0, result.bolusDelivered)
        assertTrue(f.bolusStops.isEmpty())
        assertTrue(f.decided.isEmpty(), "nothing decided")
        assertEquals(DashCommandKind.BOLUS, f.stateManager.activeCommand?.kind, "the bolus stays open")
        assertEquals(1, f.bolusCommands())
    }

    /**
     * The pod takes the stop at 30 pulses left (0.5 U went in), but its answer is not a status. The
     * next try reads a status: the pod no longer runs the bolus and reports 0 pulses left, so the stop
     * is decided SUCCESS and the whole bolus is counted. Android does the same
     * (`updateFromDefaultStatusResponse` runs before `handleCommandConfirmation`). Pinned on purpose:
     * it counts more than went in, never less (insulin on board too high, later doses lower). This is
     * the normal outcome of any lost answer to a stop, not only after a crash.
     */
    @Test
    fun `a stop whose answer is lost counts the whole bolus as Android`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 40
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            val answer = normal(command)
            if (DashPumpOnFakePod.isStopOf(command, DashPumpOnFakePod.BOLUS_BIT)) parityHex(NAK) else answer
        }

        val delivery = async { f.pump.deliverTreatment(bolus(2.0)) }
        advanceTimeBy(10_000L)
        f.bolusPulsesRemaining = 30
        f.pump.stopBolusDelivering()
        val result = delivery.await()

        assertEquals(listOf(30), f.bolusStops, "the stop went once; 0.5 U went in")
        assertTrue(result.success, result.comment)
        assertEquals(2.0, result.bolusDelivered, "the whole bolus, as Android")
        val stop = f.decided.last()
        assertEquals(DashCommandKind.STOP_BOLUS, stop.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, stop.confirmation)
        assertEquals(2.0, stop.endedBolus?.deliveredUnits())
        assertNull(f.stateManager.activeCommand)
        assertEquals(1, f.bolusCommands())
    }

    /**
     * Closing the bolus command and storing the last bolus must be one write. Between two writes, a
     * crash would leave no open command and no last bolus: a bolus the pod took, counted nowhere.
     */
    @Test
    fun `closing the bolus command and storing the last bolus are one write`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.store.history.clear()

        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)

        val afterOpen = f.store.history.dropWhile { it.activeCommand?.kind != DashCommandKind.BOLUS }
        assertTrue(afterOpen.isNotEmpty())
        afterOpen.filter { it.activeCommand == null }.forEach {
            assertEquals(1.0, it.lastBolus?.requestedUnits, "a crash after this write would lose the bolus")
        }
    }

    /**
     * The same for the stop: when the stop is closed, the bolus must already be ended in the same
     * write. Otherwise a crash leaves a bolus not ended, and the next status (0 left) counts all of it.
     */
    @Test
    fun `closing the stop and ending the bolus are one write`() = runTest {
        val f = newFixture()
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 40

        val delivery = async { f.pump.deliverTreatment(bolus(2.0)) }
        advanceTimeBy(10_000L)
        f.bolusPulsesRemaining = 30
        f.pump.stopBolusDelivering()
        assertTrue(delivery.await().success)

        val afterStop = f.store.history.dropWhile { it.activeCommand?.kind != DashCommandKind.STOP_BOLUS }
        assertTrue(afterStop.isNotEmpty())
        afterStop.filter { it.activeCommand == null }.forEach {
            val bolus = assertNotNull(it.lastBolus)
            assertTrue(bolus.deliveryComplete, "a crash after this write would count the whole bolus")
            assertEquals(0.5, assertNotNull(bolus.deliveredUnits()), 1e-9)
        }
    }

    private companion object {

        /** Android `NakResponseTest` (ILLEGAL_PARAM): an answer that is not a status. */
        const val NAK = "0603070009"
    }
}
