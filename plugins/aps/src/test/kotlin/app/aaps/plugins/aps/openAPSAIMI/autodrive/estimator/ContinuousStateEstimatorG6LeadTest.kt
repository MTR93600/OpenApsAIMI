package app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * G6 lead (+25% velocity) must stay [SourceSensor.DEXCOM_G6_NATIVE]-only.
 * One+ / G7 must pass velocity through unchanged.
 */
class ContinuousStateEstimatorG6LeadTest {

    private val logger = mockk<AAPSLogger>(relaxed = true)
    private val estimator = ContinuousStateEstimator(logger)

    @Test
    fun `applyG6LeadCompensation boosts only when isG6 and velocity above threshold`() {
        assertThat(estimator.applyG6LeadCompensation(2.0, isG6 = true)).isWithin(1e-9).of(2.5)
        assertThat(estimator.applyG6LeadCompensation(2.0, isG6 = false)).isWithin(1e-9).of(2.0)
        assertThat(estimator.applyG6LeadCompensation(0.4, isG6 = true)).isWithin(1e-9).of(0.4)
    }

    @Test
    fun `OnePlus sourceSensor does not engage G6 lead path in UKF update`() {
        val velocity = 2.0
        val onePlus = AutoDriveState.createSafe(
            bg = 140.0,
            bgVelocity = velocity,
            iob = 0.5,
            cob = 0.0,
            estimatedSI = 0.005,
            hour = 14,
            steps = 0,
            combinedDelta = 3.0,
            uamConfidence = 0.0,
            sourceSensor = SourceSensor.DEXCOM_ONEPLUS_NATIVE,
        )
        val g6 = onePlus.copy(sourceSensor = SourceSensor.DEXCOM_G6_NATIVE)

        val raOnePlus = ContinuousStateEstimator(logger).apply {
            updateAndPredict(onePlus)
        }.getLastRa()
        val raG6 = ContinuousStateEstimator(logger).apply {
            updateAndPredict(g6)
        }.getLastRa()

        // Same inputs except sensor: G6 lead increases innovation → higher Ra when rising
        assertThat(raG6).isGreaterThan(raOnePlus)
        assertThat(estimator.applyG6LeadCompensation(velocity, isG6 = false)).isWithin(1e-9).of(velocity)
    }

    @Test
    fun `native Libre 3 sourceSensor does not engage G6 lead path in UKF update`() {
        // Libre 3 is a fast sensor like ONE+ and G7. The G6 lead compensator exists to make up for
        // the slower G6 reading, so applying it here would push the loop on a lead that is not real.
        val velocity = 2.0
        val libre3 = AutoDriveState.createSafe(
            bg = 140.0,
            bgVelocity = velocity,
            iob = 0.5,
            cob = 0.0,
            estimatedSI = 0.005,
            hour = 14,
            steps = 0,
            combinedDelta = 3.0,
            uamConfidence = 0.0,
            sourceSensor = SourceSensor.LIBRE_3_NATIVE,
        )
        val g6 = libre3.copy(sourceSensor = SourceSensor.DEXCOM_G6_NATIVE)

        val raLibre3 = ContinuousStateEstimator(logger).apply {
            updateAndPredict(libre3)
        }.getLastRa()
        val raG6 = ContinuousStateEstimator(logger).apply {
            updateAndPredict(g6)
        }.getLastRa()

        assertThat(raG6).isGreaterThan(raLibre3)
        assertThat(estimator.applyG6LeadCompensation(velocity, isG6 = false)).isWithin(1e-9).of(velocity)
    }
}
