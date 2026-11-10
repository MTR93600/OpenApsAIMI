package app.aaps.plugins.aps.openAPSAIMI.autodrive.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MpcControllerCandidateGridTest {

    @Test
    fun `candidate grid is capped`() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = 50.0, searchStep = 0.005)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertEquals(0.0, candidates.first(), 0.0)
    }

    @Test
    fun `non finite max dose yields zero only`() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = Double.NaN, searchStep = 0.005)
        assertEquals(listOf(0.0), candidates)
    }

    /**
     * The regression that mattered: the cap used to truncate the domain, not the step.
     *
     * At the fine step the grid stopped at `399 * 0.005 = 1.995 U`, so on a rising tick — where
     * `isHyperPlateauQuiet` is false and the fine step is used — the solver could not score any dose
     * above 1.995 U whatever `maxSafeDoseU` said. Measured on 172 rising ticks at BG >= 140 with no
     * carbs on board, `model_output_u` had a maximum of 1.87 U against a safe domain of about 2.5 U.
     */
    @Test
    fun `grid reaches the top of the safe domain at the fine step`() {
        val maxSafeDoseU = 2.5
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = maxSafeDoseU, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertEquals(maxSafeDoseU, candidates.last(), 1e-9)
    }

    @Test
    fun `grid reaches the top of the safe domain for a wide domain`() {
        val maxSafeDoseU = 12.0
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = maxSafeDoseU, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertEquals(maxSafeDoseU, candidates.last(), 1e-9)
    }

    /** No candidate may exceed the safe domain — the grid widens, it never overshoots. */
    @Test
    fun `no candidate exceeds the safe domain`() {
        for (maxSafeDoseU in listOf(0.05, 0.3, 1.0, 1.995, 2.5, 5.0, 50.0)) {
            val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = maxSafeDoseU, searchStep = MpcController.FINE_SEARCH_STEP_U)
            assertTrue(
                candidates.all { it <= maxSafeDoseU + 1e-9 },
                "a candidate exceeded maxSafeDoseU=$maxSafeDoseU",
            )
            assertEquals(0.0, candidates.first(), 0.0)
        }
    }

    /** Below the point where the cap bites, the requested resolution is preserved exactly. */
    @Test
    fun `narrow domain keeps the requested step`() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = 1.0, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertEquals(0.0, candidates[0], 1e-12)
        assertEquals(MpcController.FINE_SEARCH_STEP_U, candidates[1], 1e-12)
        assertEquals(201, candidates.size)
    }

    /** The CPU guard still holds on an absurd domain, and the domain is still complete. */
    @Test
    fun `very wide domain stays within the candidate cap and still spans the domain`() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = 50.0, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertEquals(50.0, candidates.last(), 1e-9)
    }

    @Test
    fun `zero and negative domains yield zero only`() {
        assertEquals(listOf(0.0), MpcController.buildDoseCandidates(maxSafeDoseU = 0.0, searchStep = 0.005))
        assertEquals(listOf(0.0), MpcController.buildDoseCandidates(maxSafeDoseU = -1.0, searchStep = 0.005))
    }
}
