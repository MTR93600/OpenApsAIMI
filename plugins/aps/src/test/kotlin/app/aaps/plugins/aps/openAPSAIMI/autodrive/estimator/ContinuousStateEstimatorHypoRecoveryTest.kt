package app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator

import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ContinuousStateEstimatorHypoRecoveryTest {

    private val logger = mockk<AAPSLogger>(relaxed = true)

    @Test
    fun `post-hypo guard yields lower Ra than same state without guard on rising innovation`() {
        val base = AutoDriveState.createSafe(
            bg = 106.0,
            bgVelocity = 2.0,
            iob = 0.3,
            cob = 0.0,
            estimatedSI = 0.005,
            hour = 14,
            steps = 500,
            combinedDelta = 3.0,
            uamConfidence = 0.8
        )

        val damped = ContinuousStateEstimator(logger).apply {
            repeat(5) { updateAndPredict(base.copy(applyHypoRecoveryRaDampening = true)) }
        }.getLastRa()

        val normal = ContinuousStateEstimator(logger).apply {
            repeat(5) { updateAndPredict(base.copy(applyHypoRecoveryRaDampening = false)) }
        }.getLastRa()

        assertThat(normal - damped).isGreaterThan(0.01)
    }

    @Test
    fun `weight and physiological burden reduce Ra ceiling for same innovation`() {
        val relaxed = AutoDriveState.createSafe(
            bg = 118.0,
            bgVelocity = 8.0,
            iob = 0.6,
            cob = 0.0,
            estimatedSI = 0.005,
            patientWeightKg = 75.0,
            physiologicalStressMask = doubleArrayOf(0.0, 0.0, 0.0),
            hour = 6,
            steps = 0,
            combinedDelta = 6.0,
            uamConfidence = 0.8,
        )
        val burdened = relaxed.copy(
            physiologicalStressMask = doubleArrayOf(0.1, 0.9, 0.8),
        )

        val relaxedRa = ContinuousStateEstimator(logger).apply {
            repeat(12) { updateAndPredict(relaxed) }
        }.getLastRa()

        val burdenedRa = ContinuousStateEstimator(logger).apply {
            repeat(12) { updateAndPredict(burdened) }
        }.getLastRa()

        assertThat(relaxedRa).isGreaterThan(burdenedRa)
    }
}
