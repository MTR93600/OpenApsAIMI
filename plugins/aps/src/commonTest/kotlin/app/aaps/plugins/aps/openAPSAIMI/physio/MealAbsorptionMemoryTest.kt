package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the episode onset stamp of [MealAbsorptionMemory]: it must mark the start of the episode,
 * not the last time the episode was seen.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `81e370bfbf` (unchanged on tip `c5db5a0333`).
 * Study source set: [MealAbsorptionMemory] is commonMain, so the tests live in `commonTest`
 * (`kotlin.test`) — same layout as [app.aaps.plugins.aps.openAPSAIMI.ISF.DynIsfCacheTest].
 */
class MealAbsorptionMemoryTest {

    private val t0 = 1_700_000_000_000L

    @BeforeTest
    fun setUp() = MealAbsorptionMemory.reset()

    @AfterTest
    fun tearDown() = MealAbsorptionMemory.reset()

    private fun output(phase: MealAbsorptionPhase) = MealAbsorptionPhaseEngine.Output(
        phase = phase,
        belief = 0.5,
        reason = "test",
        deltaMgdlPer5 = 1.0,
        gapMgdl = 10.0,
        bestTerminalMgdl = 150.0,
        memoryActive = phase.isActive,
        waveCount = 1,
        mealDeliveryPriority = false,
        chronoPrior = 0.0,
        kineticScore = 0.0,
        trajectoryScore = 0.0,
        physioScore = 0.0
    )

    @Test
    fun `onset is set once when the episode starts and does not move on later active ticks`() {
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0)
        assertEquals(t0, MealAbsorptionMemory.onsetAtMs)

        val later = t0 + 30 * 60_000L
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.SECOND_WAVE), later)
        assertEquals(t0, MealAbsorptionMemory.onsetAtMs)
        // Last activity does move, the onset does not. That is the whole point of the new field.
        assertEquals(later, MealAbsorptionMemory.lastActiveAtMs)
        assertEquals(30.0, MealAbsorptionMemory.onsetAgeMin(later)!!, 1e-9)
    }

    @Test
    fun `reset clears the onset`() {
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0)
        assertNotNull(MealAbsorptionMemory.onsetAgeMin(t0))

        MealAbsorptionMemory.reset()
        assertEquals(0L, MealAbsorptionMemory.onsetAtMs)
        assertNull(MealAbsorptionMemory.onsetAgeMin(t0))
    }

    @Test
    fun `onsetAgeMin is null when there is no episode`() {
        assertNull(MealAbsorptionMemory.onsetAgeMin(t0))
    }

    // ---- F10: the episode must close (2026-10-21) ---------------------------------------------

    private val min = 60_000L
    private val noEvidence = MealAbsorptionMemory.Evidence.NONE

    /** INTER_WAVE needs the memory to exist, so it is not evidence: it cannot keep the episode alive. */
    @Test
    fun memoryDerivedPhasesDoNotExtendTheWindow() {
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0)
        var t = t0 + 5 * min
        while (t < t0 + 120 * min) {
            MealAbsorptionMemory.update(output(MealAbsorptionPhase.INTER_WAVE), t, noEvidence)
            assertTrue(MealAbsorptionMemory.isActive(t))
            t += 5 * min
        }
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.INTER_WAVE), t0 + 120 * min, noEvidence)
        assertFalse(MealAbsorptionMemory.isActive(t0 + 120 * min))
        assertEquals(MealAbsorptionMemory.ClosedBy.NO_EVIDENCE, MealAbsorptionMemory.lastClosedBy)
        assertNull(MealAbsorptionMemory.onsetAgeMin(t0 + 120 * min))
    }

    /** An active phase with no evidence and no open episode does not open one. */
    @Test
    fun activePhaseWithoutEvidenceOpensNothing() {
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.INTER_WAVE), t0, noEvidence)
        assertFalse(MealAbsorptionMemory.isActive(t0))
        assertEquals(0L, MealAbsorptionMemory.onsetAtMs)
    }

    /** Carbs on board for hours: the episode closes at the max age and does not reopen on the same COB. */
    @Test
    fun stickyCobClosesAtMaxAgeAndDoesNotReopen() {
        val cob = MealAbsorptionMemory.Evidence(cob = true)
        var t = t0
        while (t <= t0 + 425 * min) {
            MealAbsorptionMemory.update(output(MealAbsorptionPhase.INTER_WAVE), t, cob)
            t += 5 * min
        }
        assertFalse(MealAbsorptionMemory.isActive(t0 + 425 * min))
        assertEquals(MealAbsorptionMemory.ClosedBy.MAX_AGE, MealAbsorptionMemory.lastClosedBy)
    }

    /** A new FIRST_WAVE 65 min after the last one is a new meal: new onset, wave count back to 1. */
    @Test
    fun firstWaveAfterAnHourRestampsTheOnset() {
        val cob = MealAbsorptionMemory.Evidence(cob = true)
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0)
        var t = t0 + 5 * min
        while (t < t0 + 65 * min) {
            MealAbsorptionMemory.update(output(MealAbsorptionPhase.INTER_WAVE), t, cob)
            t += 5 * min
        }
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0 + 65 * min)
        assertEquals(t0 + 65 * min, MealAbsorptionMemory.onsetAtMs)
        assertEquals(1, MealAbsorptionMemory.waveCount)
    }

    @Test
    fun firstWaveWithinTheHourKeepsTheOnset() {
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0)
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0 + 30 * min)
        assertEquals(t0, MealAbsorptionMemory.onsetAtMs)
    }
}
