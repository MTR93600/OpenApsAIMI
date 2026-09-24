package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrajectoryPeakMismatchScorerTest {

    @Test
    fun `grid prefers generating peak on synthetic aligned series`() {
        val truePeak = 65
        val minutes = generateSequence(30.0) { it + 5.0 }.takeWhile { it <= 80.0 }.toList()
        val observed = minutes.map { m ->
            -40.0 * TrajectoryPeakMismatchScorer.normalizedCurveSlopePerMinute(m, truePeak.toDouble())
        }
        val best = TrajectoryPeakMismatchScorer.bestPeakOnGrid(
            observedDeltaMgDlPer5m = observed,
            minutesSinceBolus = minutes,
            peakGridMinutes = IntProgression.fromClosedRange(45, 90, 5),
        )
        assertEquals(truePeak, best)
        val rssTrue = TrajectoryPeakMismatchScorer.rssAfterScaleFit(observed, minutes, truePeak.toDouble())
        val rssWrong = TrajectoryPeakMismatchScorer.rssAfterScaleFit(observed, minutes, 50.0)
        assertTrue(rssWrong > rssTrue + 1e-6)
    }

    @Test
    fun `history nudge positive when profile peak lags synthetic truth`() {
        val truePeak = 68
        val minutes = generateSequence(30.0) { it + 5.0 }.takeWhile { it <= 80.0 }.toList()
        val obs = minutes.map { m ->
            -3.0 * TrajectoryPeakMismatchScorer.normalizedCurveSlopePerMinute(m, truePeak.toDouble())
        }
        val interior = minutes.zip(obs).mapIndexed { idx, (m, d) ->
            PhaseSpaceState(
                timestamp = idx * 300_000L,
                bg = 120.0 - idx.toDouble(),
                bgDelta = d,
                bgAccel = 0.0,
                insulinActivity = 1.2,
                iob = 2.5,
                pkpdStage = ActivityStage.FALLING,
                timeSinceLastBolus = m.toInt(),
            )
        }
        val tail = PhaseSpaceState(
            timestamp = 9_999_000L,
            bg = 100.0,
            bgDelta = -1.0,
            bgAccel = 0.0,
            insulinActivity = 1.0,
            iob = 2.0,
            pkpdStage = ActivityStage.PEAK,
            timeSinceLastBolus = 45,
        )
        val history = interior + tail
        val n = TrajectoryPeakMismatchScorer.minutesNudgeFromHistoryOrZero(
            history = history,
            insulinPeakMinutes = 58,
            lastBolusAgeMinutes = 45,
            cobGrams = 5.0,
        )
        assertTrue(n > 0.05, "expected positive nudge, got $n")
        assertTrue(n <= 2.01)
    }

    @Test
    fun `history nudge zero when cob vetoes`() {
        val minutes = generateSequence(30.0) { it + 5.0 }.takeWhile { it <= 80.0 }.toList()
        val obs = minutes.map { 0.0 }
        val interior = minutes.zip(obs).mapIndexed { idx, (m, d) ->
            PhaseSpaceState(
                timestamp = idx * 300_000L,
                bg = 100.0,
                bgDelta = d,
                bgAccel = 0.0,
                insulinActivity = 1.0,
                iob = 2.0,
                pkpdStage = ActivityStage.TAIL,
                timeSinceLastBolus = m.toInt(),
            )
        }
        val history = interior + PhaseSpaceState(
            9_999_000L, 100.0, 0.0, 0.0, 1.0, 2.0, ActivityStage.TAIL, 45,
        )
        assertEquals(
            0.0,
            TrajectoryPeakMismatchScorer.minutesNudgeFromHistoryOrZero(
                history = history,
                insulinPeakMinutes = 60,
                lastBolusAgeMinutes = 45,
                cobGrams = 15.0,
            ),
            0.001,
        )
    }
}
