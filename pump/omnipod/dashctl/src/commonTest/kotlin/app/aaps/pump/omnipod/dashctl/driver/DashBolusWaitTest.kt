package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.pump.omnipod.dashctl.session.DashPodStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * The wait for the end of a bolus (Android `waitForBolusDeliveryToComplete`) on virtual time, against
 * a simulated pod that gives one pulse every [SimulatedPod.msPerPulse] and the real
 * [DashPodStateManager]. Nothing waits real time: the timer is `delay` on the test scheduler, and
 * each test waits only for the one bolus it starts.
 */
class DashBolusWaitTest {

    private class MemoryStore : DashPodStateStore {

        var saved: DashPodState? = null
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saved = state
        }

        override fun clear() {
            saved = null
        }
    }

    private class RecordingProgress : DashBolusProgress {

        val delivered = mutableListOf<Double>()
        var checks = 0
        override fun delivered(units: Double) {
            this.delivered += units
        }

        override fun checkingDeliveryStatus() {
            checks++
        }
    }

    /**
     * A pod that started a bolus of [pulses] at time 0 and gives one pulse every [msPerPulse]. After
     * a stop it reports no bolus and 0 left; the answer to the stop itself reports what was left.
     */
    private class SimulatedPod(
        val manager: DashPodStateManager,
        val clock: () -> Long,
        val pulses: Int,
        val msPerPulse: Long = 2_000L,
    ) : DashBolusWaitPod {

        var canceled = false
        var stoppedAtMs: Long? = null

        /** The next status reads that are lost. */
        var lostReads = 0
        var allReadsLost = false

        /** The next stops that fail. */
        var failedStops = 0

        /** The pod stops the bolus by itself with this many pulses left (a pod fault). */
        var podStopsWithPulsesLeft: Int? = null

        val reads = mutableListOf<Long>()
        val stops = mutableListOf<Long>()

        fun pulsesLeft(): Int {
            val at = stoppedAtMs ?: clock()
            return max(0, pulses - (at / msPerPulse).toInt())
        }

        fun status(pulsesLeft: Int, running: Boolean, lastSequence: Int = 0x0f) = DashPodStatus(
            podStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
            deliveryStatus = if (running) DeliveryStatus.BOLUS_AND_BASAL_ACTIVE else DeliveryStatus.BASAL_ACTIVE,
            totalPulsesDelivered = 0,
            reservoirPulsesRemaining = 1000,
            minutesSinceActivation = 60,
            bolusPulsesRemaining = pulsesLeft,
            activeAlerts = emptySet(),
            lastProgrammingCommandSequence = lastSequence,
            receivedAtMs = clock(),
        )

        override fun isCanceled(): Boolean = canceled

        override fun readStatus(): Boolean {
            reads += clock()
            if (allReadsLost) return false
            if (lostReads > 0) {
                lostReads--
                return false
            }
            val faultLeft = podStopsWithPulsesLeft
            val status = when {
                faultLeft != null    -> status(faultLeft, running = false)
                stoppedAtMs != null  -> status(0, running = false)
                else                 -> status(pulsesLeft(), running = pulsesLeft() > 0)
            }
            manager.updateFromStatus(status)
            return true
        }

        /** A stop of the bolus, confirmed by its answer, as `IosDashPump` sends it. */
        override fun cancelBolus(): Boolean {
            stops += clock()
            if (failedStops > 0) {
                failedStops--
                return false
            }
            val left = pulsesLeft()
            stoppedAtMs = clock()
            val command = manager.createActiveCommand(DashCommandKind.STOP_BOLUS, historyId = 0L, createdAtMs = clock())
            manager.markActiveCommandSent()
            manager.updateFromDefaultStatus(status(left, running = false, lastSequence = command.sequence))
            assertEquals(DashCommandConfirmation.SUCCESS, manager.resolveActiveCommand(clock()).confirmation)
            return true
        }

        override fun bolusDeliveringActive(): Boolean = manager.podStatus?.deliveryStatus?.bolusDeliveringActive() == true
        override fun lastBolus(): DashLastBolus? = manager.lastBolus
        override fun completeLastBolus(): DashLastBolus? = manager.markLastBolusComplete()
    }

    private val manager = DashPodStateManager(MemoryStore())
    private val progress = RecordingProgress()

    /** The pod took a bolus of [units] at time 0. */
    private fun TestScope.podWithBolus(units: Double, msPerPulse: Long = 2_000L): SimulatedPod {
        manager.createLastBolus(units, nowMs = 0L)
        return SimulatedPod(manager, { currentTime }, pulses = (units / 0.05).roundToInt(), msPerPulse = msPerPulse)
    }

    private fun waitFor(pod: SimulatedPod) = DashBolusWait(pod, progress, sleep = { delay(it) })

    @Test
    fun `the estimate is two seconds per pulse plus three as Android`() {
        assertEquals(5L, DashBolusWait.estimateBolusDeliverySeconds(0.05))
        assertEquals(43L, DashBolusWait.estimateBolusDeliverySeconds(1.0))
        assertEquals(83L, DashBolusWait.estimateBolusDeliverySeconds(2.0))
    }

    @Test
    fun `a bolus is followed to its end and the progress reaches all of it`() = runTest {
        val pod = podWithBolus(1.0)

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(1.0, isSmb = false)

        assertEquals(1.0, delivered)
        assertEquals(43_000L, currentTime, "the estimate, then one status")
        assertEquals(listOf(43_000L), pod.reads)
        assertEquals(43, progress.delivered.size, "one estimate each second")
        assertEquals(1.0, progress.delivered.last(), "the estimate ends at the whole bolus")
        assertEquals(progress.delivered.sorted(), progress.delivered, "the progress only goes up")
        assertEquals(1, progress.checks, "100 % while the status is checked")
        assertTrue(manager.lastBolus?.deliveryComplete == true)
    }

    @Test
    fun `a slow pod is read again after the time the rest should take`() = runTest {
        val pod = podWithBolus(1.0, msPerPulse = 3_000L)

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(1.0, isSmb = false)

        assertEquals(1.0, delivered)
        assertEquals(43_000L, pod.reads.first())
        assertTrue(pod.reads.size in 2..DashBolusWait.BOLUS_RETRIES, "${pod.reads}")
        // At 43 s the pod still has 6 pulses: the progress shows what went in, from the pod.
        assertTrue(progress.delivered.any { abs(it - 0.7) < 1e-9 }, "${progress.delivered}")
        assertTrue(currentTime >= 60_000L, "never ends before the pod")
    }

    @Test
    fun `a stop during the wait counts only what went in`() = runTest {
        val pod = podWithBolus(2.0)
        launch {
            delay(10_500L)
            pod.canceled = true
        }

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(2.0, isSmb = false)

        // The wait sees the stop at the next second (11 s): 5 pulses went in, 35 were left.
        assertEquals(listOf(11_000L), pod.stops)
        assertEquals(0.25, delivered, 1e-9)
        assertTrue(pod.reads.isEmpty(), "the stop answer decides, no status read")
        assertEquals(0.25, manager.lastBolus?.deliveredUnits() ?: -1.0, 1e-9)
    }

    @Test
    fun `a stop that fails is sent again every two seconds`() = runTest {
        val pod = podWithBolus(2.0)
        pod.canceled = true
        pod.failedStops = 2

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(2.0, isSmb = false)

        assertEquals(listOf(0L, 2_000L, 4_000L), pod.stops)
        // At 4 s: 2 pulses went in.
        assertEquals(0.1, delivered, 1e-9)
    }

    @Test
    fun `a lost status is read again two seconds later`() = runTest {
        val pod = podWithBolus(1.0)
        pod.lostReads = 2

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(1.0, isSmb = false)

        assertEquals(listOf(43_000L, 45_000L, 47_000L), pod.reads)
        assertEquals(1.0, delivered)
    }

    /**
     * No status at all: after the tries the asked amount is reported, as Android ("updated later").
     * The pod took the bolus, so it is the most that can have gone in, and nothing is sent again.
     */
    @Test
    fun `when every status is lost the asked amount is reported and never more`() = runTest {
        val pod = podWithBolus(1.0)
        pod.allReadsLost = true

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(1.0, isSmb = false)

        assertEquals(1.0, delivered)
        assertEquals(DashBolusWait.BOLUS_RETRIES, pod.reads.size)
        assertEquals(43_000L + DashBolusWait.BOLUS_RETRIES * DashBolusWait.BOLUS_RETRY_INTERVAL_MS, currentTime)
        assertTrue(pod.stops.isEmpty())
        assertTrue(manager.lastBolus?.deliveryComplete == false, "the next status ends it")
    }

    /**
     * The pod no longer delivers the bolus but had units left (a pod fault). Android reads
     * `deliveredUnits()!!`, which throws there, and reports the bolus as failed with nothing
     * delivered. Here the bolus ends and what went in is counted.
     */
    @Test
    fun `a bolus the pod stopped by itself counts what went in`() = runTest {
        val pod = podWithBolus(1.0)
        pod.podStopsWithPulsesLeft = 6

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(1.0, isSmb = false)

        assertEquals(0.7, delivered, 1e-9)
        assertTrue(manager.lastBolus?.deliveryComplete == true)
    }

    @Test
    fun `an SMB shows no estimated progress as Android`() = runTest {
        val pod = podWithBolus(0.5)

        val delivered = waitFor(pod).waitForBolusDeliveryToComplete(0.5, isSmb = true)

        assertEquals(0.5, delivered)
        assertTrue(progress.delivered.isEmpty(), "${progress.delivered}")
        assertEquals(1, progress.checks)
    }
}
