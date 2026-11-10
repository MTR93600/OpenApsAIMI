package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class OnlineLearnerStatusTest {

    @Test
    fun `initial prediction is not feedback and matched prediction increments once`() {
        val learner = OnlineLearner(mockk<AAPSLogger>(relaxed = true))
        val initial = state(bg = 120.0, velocity = 0.0)

        learner.learnAndUpdate(initial, 0L)
        val afterInitialTick = learner.statusSnapshot()
        assertEquals(0L, afterInitialTick.evaluatedFeedbackCount)
        assertEquals(1, afterInitialTick.pendingPredictionCount)

        learner.learnAndUpdate(state(bg = 110.0, velocity = 0.0), 30 * 60 * 1000L)
        val afterFeedback = learner.statusSnapshot()
        assertEquals(1L, afterFeedback.evaluatedFeedbackCount)
        assertEquals(1, afterFeedback.pendingPredictionCount)
        assertNotNull(afterFeedback.lastError)
    }

    @Test
    fun `exercise release is counted separately from feedback`() {
        val learner = OnlineLearner(mockk<AAPSLogger>(relaxed = true))
        learner.learnAndUpdate(state(bg = 120.0, velocity = 0.0), 0L)
        learner.learnAndUpdate(state(bg = 100.0, velocity = 0.0), 30 * 60 * 1000L)
        learner.learnAndUpdate(state(bg = 150.0, velocity = 1.0), 35 * 60 * 1000L)

        val snapshot = learner.statusSnapshot()
        assertEquals(1L, snapshot.evaluatedFeedbackCount)
        assertEquals(1L, snapshot.releaseCount)
    }

    private fun state(bg: Double, velocity: Double) = AutoDriveState(
        bg = bg,
        bgVelocity = velocity,
        iob = 1.0,
        physiologicalStressMask = DoubleArray(0),
    )
}
