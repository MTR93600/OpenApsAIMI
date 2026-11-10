package app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The estimator must advance exactly once per loop tick.
 *
 * `AutodriveEngine.tick()` is reached from three places: the engaged Autodrive branch, the T3C shadow
 * tick, and `proposeBasalOnlyTbr` which delegates to `tick()`. On a T3C tick with Autodrive engaged it
 * therefore ran two or three times in a row.
 *
 * That is not harmless. Between two calls in the same tick `dtMin` is about zero, so `raDecay` is
 * about one and the prediction step separates nothing: the same observation is applied again, `pRa`
 * takes another `+ qRa` then another `(1 - kRa)`, and the effective Kalman gain doubles or triples.
 * The rate at which Ra moved depended on which preferences were switched on.
 *
 * These tests pin the invariant at the estimator, not at the call sites, because moving one call does
 * not protect against the next call site somebody adds.
 */
class ContinuousStateEstimatorTickIdempotenceTest {

    private val logger = mockk<AAPSLogger>(relaxed = true)

    /** A state that produces a clearly positive innovation, so a second advance would be visible. */
    private fun risingState(ra: Double) = AutoDriveState.createSafe(
        bg = 160.0,
        bgVelocity = 4.0,
        iob = 0.5,
        estimatedRa = ra,
        combinedDelta = 4.0,
        physiologicalStressMask = DoubleArray(4),
    )

    @Test
    fun `a second call in the same tick does not advance the estimate`() {
        val estimator = ContinuousStateEstimator(logger)
        val now = 1_000_000L
        val tick = 42L

        val first = estimator.updateAndPredict(risingState(0.0), nowMs = now, tickId = tick)
        val second = estimator.updateAndPredict(risingState(first.estimatedRa), nowMs = now, tickId = tick)
        val third = estimator.updateAndPredict(risingState(second.estimatedRa), nowMs = now, tickId = tick)

        assertThat(first.estimatedRa).isGreaterThan(0.0)
        assertThat(second.estimatedRa).isEqualTo(first.estimatedRa)
        assertThat(third.estimatedRa).isEqualTo(first.estimatedRa)
        assertThat(estimator.runCount).isEqualTo(1L)
        assertThat(estimator.replayedCallCount).isEqualTo(2L)
    }

    @Test
    fun `the replayed call still returns the current estimate to its caller`() {
        val estimator = ContinuousStateEstimator(logger)
        val tick = 7L
        val advanced = estimator.updateAndPredict(risingState(0.0), nowMs = 500_000L, tickId = tick)

        // A caller passing a stale Ra must still be handed the tick's value, not its own input back.
        val replayed = estimator.updateAndPredict(risingState(0.0), nowMs = 500_000L, tickId = tick)

        assertThat(replayed.estimatedRa).isEqualTo(advanced.estimatedRa)
        assertThat(replayed.estimatedRa).isEqualTo(estimator.getLastRa())
    }

    @Test
    fun `a new tick advances again`() {
        val estimator = ContinuousStateEstimator(logger)
        var now = 1_000_000L

        val t1 = estimator.updateAndPredict(risingState(0.0), nowMs = now, tickId = 1L)
        estimator.updateAndPredict(risingState(t1.estimatedRa), nowMs = now, tickId = 1L)
        now += 5 * 60_000L
        val t2 = estimator.updateAndPredict(risingState(t1.estimatedRa), nowMs = now, tickId = 2L)

        assertThat(estimator.runCount).isEqualTo(2L)
        assertThat(t2.estimatedRa).isNotEqualTo(t1.estimatedRa)
    }

    /**
     * The guard keys on tick identity, never on the clock: `lastUpdateMs` is fed by
     * `System.currentTimeMillis()` while the tick reasons in `dateUtil.now()`, and mixing two clocks
     * in a guard is the surest way to let a double call through.
     */
    @Test
    fun `two ticks sharing a timestamp are still two advances`() {
        val estimator = ContinuousStateEstimator(logger)
        val frozenClock = 2_000_000L

        estimator.updateAndPredict(risingState(0.0), nowMs = frozenClock, tickId = 10L)
        estimator.updateAndPredict(risingState(0.0), nowMs = frozenClock, tickId = 11L)

        assertThat(estimator.runCount).isEqualTo(2L)
        assertThat(estimator.replayedCallCount).isEqualTo(0L)
    }

    /** `tickId = 0` means "not part of a tick" — tests and off-tick callers keep the old behaviour. */
    @Test
    fun `an unset tick id never suppresses an advance`() {
        val estimator = ContinuousStateEstimator(logger)
        var now = 1_000_000L

        repeat(3) {
            now += 5 * 60_000L
            estimator.updateAndPredict(risingState(estimator.getLastRa()), nowMs = now)
        }

        assertThat(estimator.runCount).isEqualTo(3L)
        assertThat(estimator.replayedCallCount).isEqualTo(0L)
    }
}
