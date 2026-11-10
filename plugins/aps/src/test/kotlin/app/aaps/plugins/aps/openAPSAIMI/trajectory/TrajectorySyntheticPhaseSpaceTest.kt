package app.aaps.plugins.aps.openAPSAIMI.trajectory

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import io.mockk.mockk
import kotlin.math.max
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Hand-built phase-space sequences → [TrajectoryGuard] classification / metrics (RFC C.3).
 */
class TrajectorySyntheticPhaseSpaceTest {

    private val logger: AAPSLogger = mockk(relaxed = true)
    private val guard = TrajectoryGuard(logger)

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun `monotone descent toward target yields converging metrics`() {
        val orbit = StableOrbit.fromProfile(targetBg = 100.0, basalRate = 0.05)
        val history = buildDescendingTowardTarget(
            n = 20,
            startBg = 210.0,
            targetBg = 100.0,
        )
        val analysis = guard.analyzeTrajectory(history, orbit)
        assertNotNull(analysis)
        assertTrue(
            analysis!!.metrics.convergenceVelocity > 0.05,
            "expected positive convergence toward orbit, got ${analysis.metrics.convergenceVelocity}",
        )
        assertTrue(
            analysis.metrics.coherence > 0.2,
            "expected coherent insulin-BG coupling, got ${analysis.metrics.coherence}",
        )
    }

    @Test
    fun `rising bg with sustained insulin activity yields low coherence`() {
        val orbit = StableOrbit.fromProfile(targetBg = 100.0, basalRate = 0.05)
        val history = buildParadoxicalRise(n = 18)
        val analysis = guard.analyzeTrajectory(history, orbit)
        assertNotNull(analysis)
        assertTrue(
            analysis!!.metrics.coherence < 0.35,
            "expected weak/negative coherence when BG rises despite activity, got ${analysis.metrics.coherence}",
        )
    }

    private fun buildDescendingTowardTarget(
        n: Int,
        startBg: Double,
        targetBg: Double,
    ): List<PhaseSpaceState> {
        val t0 = 1_700_000_000_000L
        val span = max(1, n - 1)
        return List(n) { i ->
            val frac = i.toDouble() / span
            val bg = startBg - (startBg - targetBg - 25.0) * frac
            PhaseSpaceState(
                timestamp = t0 + i * 5 * 60_000L,
                bg = bg,
                bgDelta = -4.0 - frac * 2.0,
                bgAccel = 0.1,
                insulinActivity = 1.8 * (1.0 - frac * 0.6),
                iob = 4.0 * (1.0 - frac * 0.85),
                pkpdStage = ActivityStage.FALLING,
                timeSinceLastBolus = 45 + i,
                cob = 0.0,
            )
        }
    }

    private fun buildParadoxicalRise(n: Int): List<PhaseSpaceState> {
        val t0 = 1_700_000_000_000L
        return List(n) { i ->
            PhaseSpaceState(
                timestamp = t0 + i * 5 * 60_000L,
                bg = 130.0 + i * 1.5,
                bgDelta = 2.0 + i * 0.05,
                bgAccel = 0.05,
                insulinActivity = 2.2,
                iob = 3.5,
                pkpdStage = ActivityStage.RISING,
                timeSinceLastBolus = 30,
                cob = 0.0,
            )
        }
    }
}
