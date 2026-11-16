package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Step S3b: the merge of the live health snapshot (a fresh read that is not valid, on top of the last
 * valid one) must not carry forward a sleep or HRV signal the person turned off. That includes the
 * thermal belief, which was built from the old sleep debt and HRV depression and is read by the
 * latent state (`recoveryBurden`, `inflammationIndex`).
 */
class HealthContextCarryForwardTest {

    /** Built while sleep and HRV were on: sleep debt and low HRV are part of its recovery burden. */
    private val oldBelief = ThermalBeliefDigest(recoveryBurden = 0.5, inflammationIndex = 0.2)

    /** Built this tick from the readings the switches let through. */
    private val freshBelief = ThermalBeliefDigest(recoveryBurden = 0.0, inflammationIndex = 0.0)

    private val last = HealthContextSnapshot(
        stepsLast5m = 10,
        hrNow = 80,
        hrAvg15m = 80,
        hrMeasuredAtMs = 1_000,
        hrvRmssd = 22.0,
        hrvDepression = 0.6,
        sleepDebtMinutes = 150,
        sleepEfficiency = 0.6,
        hcSleepSessionActive = true,
        thermalBelief = oldBelief,
        timestamp = 1_000,
        confidence = 0.9,
        isValid = true,
    )

    private val fresh = HealthContextSnapshot(
        stepsLast5m = 300,
        hrNow = 0,
        thermalBelief = freshBelief,
        timestamp = 61_000,
        confidence = 0.0,
        isValid = false,
    )

    @Test
    fun `with both switches on the merge keeps the last thermal belief`() {
        val merged = HealthContextRepository.carryForward(last, fresh, PhysioDataSwitches.ALL_ON)
        assertEquals(oldBelief, merged.thermalBelief)
        assertEquals(150, merged.sleepDebtMinutes)
        assertEquals(300, merged.stepsLast5m)
        assertEquals(61_000, merged.timestamp)
    }

    @Test
    fun `sleep off drops the sleep debt and the old thermal belief from the merge`() {
        val merged = HealthContextRepository.carryForward(last, fresh, PhysioDataSwitches(sleep = false, hrv = true))
        assertEquals(0, merged.sleepDebtMinutes)
        assertEquals(0.0, merged.sleepEfficiency)
        assertFalse(merged.hcSleepSessionActive)
        assertEquals(freshBelief, merged.thermalBelief)
        assertEquals(0.0, merged.thermalBelief.recoveryBurden)
    }

    @Test
    fun `hrv off drops the hrv and the old thermal belief from the merge`() {
        val merged = HealthContextRepository.carryForward(last, fresh, PhysioDataSwitches(sleep = true, hrv = false))
        assertEquals(0.0, merged.hrvRmssd)
        assertEquals(0.0, merged.hrvDepression)
        assertEquals(freshBelief, merged.thermalBelief)
    }
}
