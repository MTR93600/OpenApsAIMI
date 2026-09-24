package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiSmbTrainerTest {

    @Test
    fun refine_returns_predicted_smb_when_feature_vector_size_is_unexpected() {
        val predictedSmb = 0.65f

        val refined = AimiSmbTrainer.refine(
            predictedSmb = predictedSmb,
            features = FloatArray(SmbRefinementFeatureSchema.INPUT_SIZE - 1) { 0f },
        )

        assertThat(refined).isEqualTo(predictedSmb)
    }

    @Test
    fun correction_clamp_respects_family_level_guardrails() {
        val predictedSmb = 0.80f

        val cautiousClamp = AimiSmbTrainer.correctionClamp(
            predictedSmb = predictedSmb,
            behaviorProfile = AimiBehaviorRuntimeProfile(
                protectionLevel = 0,
                mealCaptureLevel = 1,
                stabilityLevel = 1,
                physioLevel = 2,
                autonomyMode = AimiAutonomyMode.Observation,
            ),
        )
        val assertiveClamp = AimiSmbTrainer.correctionClamp(
            predictedSmb = predictedSmb,
            behaviorProfile = AimiBehaviorRuntimeProfile(
                protectionLevel = 3,
                mealCaptureLevel = 4,
                stabilityLevel = 3,
                physioLevel = 0,
                autonomyMode = AimiAutonomyMode.ControlledAuthority,
            ),
        )

        assertThat(cautiousClamp).isLessThan(assertiveClamp)
        assertThat(assertiveClamp).isAtMost(0.05f)
    }
}
