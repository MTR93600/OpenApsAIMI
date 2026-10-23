package app.aaps.plugins.aps.openAPSAIMI.autodrive.controller

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of `MpcControllerCandidateGridTest` from `dev_OAPSAIMI` (plugins/aps/src/test).
 *
 * `MpcController.buildDoseCandidates` is the same code on both branches and is pure, so the test
 * lives in `commonTest` and runs on every target. JUnit asserts were changed to `kotlin.test`, and
 * the backtick names to camelCase.
 */
class MpcControllerCandidateGridTest {

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, message: String? = null) {
        assertTrue(abs(expected - actual) <= tolerance, message ?: "expected $expected but was $actual")
    }

    @Test
    fun candidateGridIsCapped() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = 50.0, searchStep = 0.005)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertEquals(0.0, candidates.first())
    }

    @Test
    fun nonFiniteMaxDoseYieldsZeroOnly() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = Double.NaN, searchStep = 0.005)
        assertEquals(listOf(0.0), candidates)
    }

    /**
     * The regression that mattered: the cap used to cut the domain, not the step.
     *
     * At the fine step the grid stopped at `399 * 0.005 = 1.995 U`, so on a rising tick the solver
     * could not score any dose above 1.995 U whatever `maxSafeDoseU` said.
     */
    @Test
    fun gridReachesTheTopOfTheSafeDomainAtTheFineStep() {
        val maxSafeDoseU = 2.5
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = maxSafeDoseU, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertNear(maxSafeDoseU, candidates.last(), 1e-9)
    }

    @Test
    fun gridReachesTheTopOfTheSafeDomainForAWideDomain() {
        val maxSafeDoseU = 12.0
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = maxSafeDoseU, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertNear(maxSafeDoseU, candidates.last(), 1e-9)
    }

    /** No candidate may exceed the safe domain - the grid widens, it never overshoots. */
    @Test
    fun noCandidateExceedsTheSafeDomain() {
        for (maxSafeDoseU in listOf(0.05, 0.3, 1.0, 1.995, 2.5, 5.0, 50.0)) {
            val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = maxSafeDoseU, searchStep = MpcController.FINE_SEARCH_STEP_U)
            assertTrue(
                candidates.all { it <= maxSafeDoseU + 1e-9 },
                "a candidate exceeded maxSafeDoseU=$maxSafeDoseU",
            )
            assertEquals(0.0, candidates.first())
        }
    }

    /** Below the point where the cap bites, the requested resolution is kept exactly. */
    @Test
    fun narrowDomainKeepsTheRequestedStep() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = 1.0, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertNear(0.0, candidates[0], 1e-12)
        assertNear(MpcController.FINE_SEARCH_STEP_U, candidates[1], 1e-12)
        assertEquals(201, candidates.size)
    }

    /** The CPU guard still holds on an absurd domain, and the domain is still complete. */
    @Test
    fun veryWideDomainStaysWithinTheCandidateCapAndStillSpansTheDomain() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = 50.0, searchStep = MpcController.FINE_SEARCH_STEP_U)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertNear(50.0, candidates.last(), 1e-9)
    }

    @Test
    fun zeroAndNegativeDomainsYieldZeroOnly() {
        assertEquals(listOf(0.0), MpcController.buildDoseCandidates(maxSafeDoseU = 0.0, searchStep = 0.005))
        assertEquals(listOf(0.0), MpcController.buildDoseCandidates(maxSafeDoseU = -1.0, searchStep = 0.005))
    }
}
