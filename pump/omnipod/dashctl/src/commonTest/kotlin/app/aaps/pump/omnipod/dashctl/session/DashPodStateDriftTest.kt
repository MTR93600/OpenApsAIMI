package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.response.AlarmStatusResponse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset

/**
 * The basal drift, the pod time zone and the PDM reference in [DashPodStateManager], with the cases of
 * Android `NeedsBasalCorrectionTest` and `UpdatePodStateBolusTrackingTest` (`:pump:omnipod:common`).
 * Android sets `podState` fields directly; here the state is put in place with `overwriteForTest`.
 *
 * Drift arithmetic (0.05 U a pulse): basal given = (total - bolus pulses) x 0.05, drift = given -
 * expected; a correction from -0.025 U down, a reset at +-0.1 U.
 */
class DashPodStateDriftTest {

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

    private var zone: TimeZone = FixedOffsetTimeZone(UtcOffset(hours = 0))
    private val store = MemoryStore()
    private val sut = DashPodStateManager(store) { zone }

    private fun status(total: Int, bolusRemaining: Int = 0, at: Long = NOW, delivery: DeliveryStatus = DeliveryStatus.BASAL_ACTIVE, pod: PodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME) =
        DashPodStatus(
            podStatus = pod,
            deliveryStatus = delivery,
            totalPulsesDelivered = total,
            reservoirPulsesRemaining = 1000,
            minutesSinceActivation = 60,
            bolusPulsesRemaining = bolusRemaining,
            activeAlerts = emptySet(),
            lastProgrammingCommandSequence = 0,
            receivedAtMs = at,
        )

    /** Android `setDrift`: drift = (total - bolus) x 0.05 - expected. */
    private fun setDrift(totalPulses: Int, bolusPulses: Int, expectedUnits: Double?, delivery: DeliveryStatus = DeliveryStatus.BASAL_ACTIVE, pod: PodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME) {
        sut.overwriteForTest {
            it.copy(
                activationProgress = ActivationProgress.COMPLETED,
                reportedPodStatus = pod,
                podStatus = status(totalPulses, delivery = delivery, pod = pod),
                bolusPulsesDelivered = bolusPulses,
                basalExpected = expectedUnits,
            )
        }
    }

    // ---- needsBasalCorrection: prerequisites

    @Test
    fun `activation not completed gives false`() {
        setDrift(10, 0, 0.55)
        sut.overwriteForTest { it.copy(activationProgress = ActivationProgress.NOT_STARTED) }
        assertFalse(sut.needsBasalCorrection(NOW))
    }

    @Test
    fun `a suspended pod gives false`() {
        setDrift(10, 0, 0.55, delivery = DeliveryStatus.SUSPENDED)
        assertFalse(sut.needsBasalCorrection(NOW))
    }

    @Test
    fun `a pod in alarm gives false`() {
        setDrift(10, 0, 0.55, pod = PodStatus.ALARM)
        assertFalse(sut.needsBasalCorrection(NOW))
    }

    @Test
    fun `a deactivated pod gives false`() {
        setDrift(10, 0, 0.55, pod = PodStatus.DEACTIVATED)
        assertFalse(sut.needsBasalCorrection(NOW))
    }

    @Test
    fun `within the cooldown gives false and after it true`() {
        setDrift(10, 0, 0.55)
        sut.updateLastBasalCorrectionTime(NOW - 30_000L)
        assertFalse(sut.needsBasalCorrection(NOW))
        sut.updateLastBasalCorrectionTime(NOW - 3 * 60_000L)
        assertTrue(sut.needsBasalCorrection(NOW))
    }

    @Test
    fun `no expected basal yet gives a drift of 0 and false`() {
        setDrift(10, 0, null)
        assertFalse(sut.needsBasalCorrection(NOW))
    }

    // ---- needsBasalCorrection: drift

    @Test
    fun `only the basal part drives the drift`() {
        setDrift(totalPulses = 20, bolusPulses = 10, expectedUnits = 0.55)
        assertTrue(sut.needsBasalCorrection(NOW))
    }

    @Test
    fun `a running temp basal that is not zero does not block`() {
        setDrift(10, 0, 0.55)
        sut.overwriteForTest { it.copy(tempBasal = DashTempBasal(startTimeMs = NOW - 10 * 60_000L, rate = 1.0, durationInMinutes = 30)) }
        assertTrue(sut.needsBasalCorrection(NOW))
    }

    @Test
    fun `the thresholds of Android`() {
        setDrift(10, 0, 0.50)
        assertFalse(sut.needsBasalCorrection(NOW), "drift 0")
        setDrift(10, 0, 0.45)
        assertFalse(sut.needsBasalCorrection(NOW), "drift +0.05: too much basal is not taken back")
        setDrift(10, 0, 0.52)
        assertFalse(sut.needsBasalCorrection(NOW), "drift -0.02, above -0.025")
        setDrift(10, 0, 0.53)
        assertTrue(sut.needsBasalCorrection(NOW), "drift -0.03")
        setDrift(10, 0, 0.55)
        assertTrue(sut.needsBasalCorrection(NOW), "drift -0.05, one pulse")
    }

    @Test
    fun `a drift beyond 0_1 U either way resets the expected basal`() {
        setDrift(10, 0, 0.65)
        assertFalse(sut.needsBasalCorrection(NOW))
        assertEquals(0.5, store.saved?.basalExpected, "reset to the basal given, and stored")
        setDrift(10, 0, 0.35)
        assertFalse(sut.needsBasalCorrection(NOW))
        assertEquals(0.5, store.saved?.basalExpected)
    }

    @Test
    fun `a zero temp basal blocks unless a bolus started less than 5 minutes ago`() {
        setDrift(10, 0, 0.55)
        sut.overwriteForTest { it.copy(tempBasal = DashTempBasal(startTimeMs = NOW - 10 * 60_000L, rate = 0.0, durationInMinutes = 30)) }
        assertFalse(sut.needsBasalCorrection(NOW))
        sut.createLastBolus(requestedUnits = 1.0, nowMs = NOW)
        assertTrue(sut.needsBasalCorrection(NOW))
        assertFalse(sut.needsBasalCorrection(NOW + 5 * 60_000L), "5 minutes after the bolus")
    }

    // ---- bolus pulse tracking (Android updatePodState)

    /** Android setUp: 100 total pulses, 60 bolus pulses, so 40 basal pulses = 2.0 U expected. */
    private fun baseline() {
        sut.overwriteForTest {
            it.copy(
                activationProgress = ActivationProgress.COMPLETED,
                reportedPodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
                podStatus = status(100, at = NOW - 1000),
                bolusPulsesDelivered = 60,
                basalExpected = 40 * 0.05,
                lastUpdatedSystemMs = NOW - 1000,
            )
        }
    }

    @Test
    fun `no bolus running and new pulses are not bolus pulses`() {
        baseline()
        sut.updateFromStatus(status(103))
        assertEquals(60, store.saved?.bolusPulsesDelivered)
    }

    @Test
    fun `an ended bolus and new pulses are not bolus pulses`() {
        baseline()
        sut.createLastBolus(1.0, NOW)
        sut.markLastBolusComplete()
        sut.updateFromStatus(status(103))
        assertEquals(60, store.saved?.bolusPulsesDelivered)
    }

    @Test
    fun `a running bolus gets its pulses`() {
        baseline()
        sut.createLastBolus(0.15, NOW)
        sut.updateFromStatus(status(103, bolusRemaining = 0))
        assertEquals(63, store.saved?.bolusPulsesDelivered)
    }

    @Test
    fun `basal pulses at the same time as a bolus are not bolus pulses`() {
        baseline()
        sut.createLastBolus(0.15, NOW)
        sut.updateFromStatus(status(105, bolusRemaining = 0))
        assertEquals(63, store.saved?.bolusPulsesDelivered, "60 + 3, not 60 + 5")
    }

    @Test
    fun `the pulses of a basal correction are basal`() {
        baseline()
        sut.createLastBolus(0.05, NOW, basalCorrection = true)
        sut.basalCorrectionInProgress = true
        sut.updateFromStatus(status(102, bolusRemaining = 0))
        assertEquals(60, store.saved?.bolusPulsesDelivered)
        assertEquals(102, store.saved?.podStatus?.totalPulsesDelivered)
    }

    @Test
    fun `after a basal correction bolus pulses count again`() {
        baseline()
        sut.createLastBolus(0.05, NOW, basalCorrection = true)
        sut.basalCorrectionInProgress = true
        sut.updateFromStatus(status(101, bolusRemaining = 0))
        sut.basalCorrectionInProgress = false
        sut.createLastBolus(0.10, NOW)
        sut.updateFromStatus(status(103, bolusRemaining = 0))
        assertEquals(62, store.saved?.bolusPulsesDelivered)
    }

    @Test
    fun `no bolus pulses yet are seeded from the first status`() {
        baseline()
        sut.overwriteForTest { it.copy(bolusPulsesDelivered = null) }
        sut.updateFromStatus(status(42))
        assertEquals(42, store.saved?.bolusPulsesDelivered)
        assertEquals(0.0, store.saved?.basalExpected, "Android: the basal given falls back to 0 while the bolus pulses are not known")
    }

    @Test
    fun `no expected basal yet is seeded from the basal given`() {
        baseline()
        sut.overwriteForTest { it.copy(basalExpected = null) }
        sut.updateFromStatus(status(100))
        assertEquals(2.0, store.saved?.basalExpected)
    }

    @Test
    fun `the expected basal grows with the program between two statuses`() {
        baseline()
        sut.overwriteForTest {
            it.copy(
                timeZoneOffsetMs = 0,
                basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100))),
                lastUpdatedSystemMs = NOW - 3_600_000L,
                basalExpected = 2.0,
            )
        }
        sut.updateFromStatus(status(100))
        val expected = store.saved?.basalExpected ?: 0.0
        assertTrue(abs(expected - 3.0) < 1e-9, "1 h at 1 U/h on top of 2.0 U: $expected")
    }

    @Test
    fun `no pod time zone seeds the expected basal again as Android`() {
        baseline()
        sut.overwriteForTest { it.copy(timeZoneOffsetMs = null, basalExpected = 1.0) }
        sut.updateFromStatus(status(100))
        assertEquals(2.0, store.saved?.basalExpected, "integrate gives null: the drift starts again from 0")
    }

    @Test
    fun `nothing is tracked before the activation is completed`() {
        sut.updateFromStatus(status(10))
        assertNull(store.saved?.bolusPulsesDelivered)
        assertNull(store.saved?.basalExpected)
    }

    // ---- pod time zone

    @Test
    fun `the time zone of the phone is stored when the pod takes a program`() {
        zone = FixedOffsetTimeZone(UtcOffset(hours = 2))
        assertFalse(sut.sameTimeZone(NOW), "no pod time zone: not the same, as Android")

        sut.updateTimeZone(NOW)

        assertEquals(2 * 3_600_000, store.saved?.timeZoneOffsetMs)
        assertEquals(zone.id, store.saved?.timeZoneId)
        assertEquals(NOW, store.saved?.timeZoneUpdatedMs)
        assertTrue(sut.sameTimeZone(NOW))
        zone = FixedOffsetTimeZone(UtcOffset(hours = 3))
        assertFalse(sut.sameTimeZone(NOW), "the phone moved to another time zone")
    }

    @Test
    fun `the time on the pod comes from the activation time and the last status`() {
        sut.overwriteForTest {
            it.copy(activationTime = NOW - 3_600_000L, podStatus = status(10, at = NOW - 60_000L), lastUpdatedSystemMs = NOW - 60_000L, timeZoneOffsetMs = 0)
        }
        // 60 minutes since activation, and 1 minute since the status.
        assertEquals(NOW - 3_600_000L + 60 * 60_000L + 60_000L, sut.podTimeMs(NOW))
        assertEquals(60 * 60_000L + 60_000L - 3_600_000L, sut.timeDriftMs(NOW))
        sut.overwriteForTest { it.copy(timeZoneOffsetMs = null) }
        assertNull(sut.podTimeMs(NOW), "Android: no pod time without the pod offset")
    }

    // ---- PDM reference and counters

    @Test
    fun `the PDM reference of the first alarm status is kept with the alarm`() {
        val first = AlarmStatusResponse(parityHex("021602080100000501BD14000003FF01950000000000670A"))
        val later = AlarmStatusResponse(parityHex("021602080100000501BD24000003FF01950000000000670A"))

        sut.updateFromAlarmStatus(first, NOW)
        sut.updateFromAlarmStatus(later, NOW + 60_000L)

        assertEquals(AlarmType.ALARM_OCCLUDED, sut.alarmType)
        assertEquals(first.pdmRef, sut.pdmRef)
        assertEquals("17-00006-02251-020", sut.pdmRef)
    }

    @Test
    fun `the connection counters give the Android ratio`() {
        assertEquals(0.0F, sut.connectionSuccessRatio())
        repeat(3) { sut.incrementSuccessfulConnectionAttemptsAfterRetries() }
        sut.incrementFailedConnectionsAfterRetries()
        assertEquals(0.75F, sut.connectionSuccessRatio())
        assertEquals(3, store.saved?.successfulConnectionAttemptsAfterRetries)
        assertEquals(1, store.saved?.failedConnectionsAfterRetries)
    }

    private companion object {

        const val NOW = 1_767_225_600_000L
    }
}
