package app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Ra must return towards zero when nothing supports it any more.
 *
 * The estimator had no prediction step: `innovation` subtracted `lastRa` itself, so as soon as Ra
 * explained the observed velocity the innovation went to zero and Ra stayed put. Production trace of
 * 2026-08-07 showed it pinned at 1.89 for two hours, then 1.24, then 1.18 for three and a half
 * hours. The three gates that read it (0.6 / 0.7 / 0.8) were therefore open on 82 % of ticks.
 */
class ContinuousStateEstimatorRaDecayTest {

    private val logger = mockk<AAPSLogger>(relaxed = true)

    private fun quietState(ra: Double) = AutoDriveState.createSafe(
        bg = 110.0,
        bgVelocity = 0.0,
        iob = 0.0,
        estimatedRa = ra,
        physiologicalStressMask = DoubleArray(4),
    )

    private fun risingState(ra: Double) = AutoDriveState.createSafe(
        bg = 150.0,
        bgVelocity = 3.0,
        iob = 1.0,
        estimatedRa = ra,
        combinedDelta = 3.0,
        physiologicalStressMask = DoubleArray(4),
    )

    /**
     * Time must be injected. An earlier version of this test called `updateAndPredict` in a tight
     * loop, so `dtMin` was ~0 on every iteration and the decay never ran — it passed on the
     * `lastUpdateMs == 0L -> 5.0` branch alone and could not have detected a regression.
     */
    private fun advance(
        estimator: ContinuousStateEstimator,
        state: AutoDriveState,
        ticks: Int,
        startMs: Long,
        build: (Double) -> AutoDriveState,
    ): Pair<AutoDriveState, Long> {
        var s = state
        var now = startMs
        repeat(ticks) {
            now += 5 * 60_000L
            s = estimator.updateAndPredict(build(s.estimatedRa), nowMs = now)
        }
        return s to now
    }

    @Test
    fun `Ra decays towards zero over a quiet stretch instead of holding a plateau`() {
        val estimator = ContinuousStateEstimator(logger)

        // Feed a rise so the estimator picks a meal up.
        var (state, now) = advance(estimator, risingState(0.0), 6, 1_000_000L) { risingState(it) }
        val raAfterRise = state.estimatedRa
        assertThat(raAfterRise).isGreaterThan(0.5)

        // Then three hours of quiet: BG flat, no insulin, nothing left to explain.
        val (quiet, _) = advance(estimator, state, 36, now) { quietState(it) }

        assertThat(quiet.estimatedRa).isLessThan(raAfterRise)
        assertThat(quiet.estimatedRa).isLessThan(0.6) // below the lowest gate that reads it
    }

    @Test
    fun `the decay is real time based, not call based`() {
        val fast = ContinuousStateEstimator(logger)
        val slow = ContinuousStateEstimator(logger)

        val (fastRisen, fastNow) = advance(fast, risingState(0.0), 6, 1_000_000L) { risingState(it) }
        val (slowRisen, slowNow) = advance(slow, risingState(0.0), 6, 1_000_000L) { risingState(it) }
        assertThat(fastRisen.estimatedRa).isWithin(1e-9).of(slowRisen.estimatedRa)

        // Same number of quiet calls, very different elapsed time.
        var f = fastRisen
        repeat(6) { f = fast.updateAndPredict(quietState(f.estimatedRa), nowMs = fastNow + 1_000L * (it + 1)) }
        val (s, _) = advance(slow, slowRisen, 6, slowNow) { quietState(it) }

        assertThat(s.estimatedRa).isLessThan(f.estimatedRa)
    }

    @Test
    fun `a sustained rise still holds Ra up`() {
        val estimator = ContinuousStateEstimator(logger)

        val (state, _) = advance(estimator, risingState(0.0), 12, 1_000_000L) { risingState(it) }

        // The decay must not fight a meal that is genuinely still going.
        assertThat(state.estimatedRa).isGreaterThan(0.8)
    }

    @Test
    fun `runCount advances once per call so the once-per-tick invariant is checkable`() {
        val estimator = ContinuousStateEstimator(logger)
        assertThat(estimator.runCount).isEqualTo(0L)

        advance(estimator, risingState(0.0), 3, 1_000_000L) { risingState(it) }

        assertThat(estimator.runCount).isEqualTo(3L)
    }

    @Test
    fun `a frozen estimator is what the once-per-tick guard has to prevent`() {
        val estimator = ContinuousStateEstimator(logger)

        // Rise, then a stretch during which nothing calls the estimator at all — the production
        // failure: 37 consecutive ticks with an identical Ra because updateAndPredict sat behind
        // `if (gate.engage)`.
        val (risen, now) = advance(estimator, risingState(0.0), 6, 1_000_000L) { risingState(it) }
        val frozen = estimator.getLastRa()
        val runsAfterRise = estimator.runCount

        // Three hours pass with no call.
        assertThat(estimator.getLastRa()).isWithin(1e-9).of(frozen)
        assertThat(estimator.runCount).isEqualTo(runsAfterRise)

        // One call, three hours later, and the decay finally applies — capped by RA_DECAY_MAX_DT_MIN
        // so a long gap cannot wipe the state in a single step.
        val after = estimator.updateAndPredict(quietState(risen.estimatedRa), nowMs = now + 3 * 3_600_000L)
        assertThat(after.estimatedRa).isLessThan(frozen)
        assertThat(after.estimatedRa).isGreaterThan(0.0)
        assertThat(estimator.runCount).isEqualTo(runsAfterRise + 1)
    }

    @Test
    fun `decay constants stay physiological`() {
        // Half-life around 30 min, and a single long gap must not wipe the state in one step.
        assertThat(ContinuousStateEstimator.RA_DECAY_TAU_MIN).isAtLeast(20.0)
        assertThat(ContinuousStateEstimator.RA_DECAY_TAU_MIN).isAtMost(90.0)
        assertThat(ContinuousStateEstimator.RA_DECAY_MAX_DT_MIN).isAtMost(60.0)
    }
}
