package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStrategyHint
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SmbRefinementFeatureSchemaTest {

    @Test
    fun build_runtime_features_appends_latent_physio_axes_and_trend_indicator() {
        val features = SmbRefinementFeatureSchema.buildRuntimeFeatures(
            baseFeatures = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f),
            trendIndicator = 0.73f,
            physioLatentState = PhysioLatentState(
                mealProb = 0.41,
                endogenousGlucoseDrive = 0.28,
                circadianSiFactor = 0.91,
                transientResistanceProb = 0.64,
                postHypoReboundProb = 0.22,
                sleepDebtScore = 0.15,
                sensorConfidence = 0.94,
                falseMealSuppression = false,
                source = "test",
            ),
            patientModeDecision = PatientModeOrchestrator.Decision(
                mode = PatientMode.FAST_MEAL,
                confidence = 0.89,
                strategyHint = PatientStrategyHint.SMB_PRIORITY,
                mealBias = 0.90,
                protectionBias = 0.18,
                userIntentConfidence = 0.84,
                reasonCodes = listOf("MEAL_FIRST_WAVE"),
            ),
            causalStatePosterior = CausalStatePosterior(
                fastMealProb = 0.83,
                prolongedMealProb = 0.18,
                dawnEndogenousProb = 0.12,
                postHypoRecoveryProb = 0.04,
                stressResistanceProb = 0.10,
                exerciseAfterburnProb = 0.06,
                inflammatoryDriftProb = 0.08,
                absorptionUncertainProb = 0.11,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.83,
                learningQuality = 0.79,
            ),
        )

        assertThat(features.size).isEqualTo(SmbRefinementFeatureSchema.INPUT_SIZE)
        assertThat(features.copyOfRange(10, 14).toList())
            .containsExactly(0.41f, 0.28f, 0.91f, 0.64f)
            .inOrder()
        assertThat(features.copyOfRange(14, 17).toList())
            .containsExactly(0.90f, 0.18f, 0.84f)
            .inOrder()
        assertThat(features.copyOfRange(17, 20).toList())
            .containsExactly(0.83f, 0.12f, 0.79f)
            .inOrder()
        assertThat(features.last()).isEqualTo(0.73f)
    }

    @Test
    fun parse_training_features_keeps_backward_compatibility_for_old_csv_rows() {
        val headers = listOf(
            "dateStr",
            "bg",
            "iob",
            "cob",
            "delta",
            "shortAvgDelta",
            "longAvgDelta",
            "tdd7DaysPerHour",
            "tdd2DaysPerHour",
            "tddPerHour",
            "tdd24HrsPerHour",
            "predictedSMB",
            "smbGiven",
        )
        val cols = listOf(
            "06/06/2026 08:00",
            "152",
            "1.2",
            "9.0",
            "3.5",
            "2.9",
            "1.7",
            "0.8",
            "0.7",
            "0.9",
            "1.0",
            "0.4",
            "0.35",
        )

        val parsed = SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols)

        assertThat(parsed).isNotNull()
        parsed!!
        assertThat(parsed.size).isEqualTo(SmbRefinementFeatureSchema.INPUT_SIZE - 1)
        assertThat(parsed.copyOfRange(10, 20).toList())
            .containsExactly(0f, 0f, 1f, 0f, 0.45f, 0.22f, 0f, 0f, 0f, 1f)
            .inOrder()
    }

    @Test
    fun parse_training_features_ignores_family_audit_columns() {
        val headers = listOf(
            "dateStr",
            "bg",
            "iob",
            "cob",
            "delta",
            "shortAvgDelta",
            "longAvgDelta",
            "tdd7DaysPerHour",
            "tdd2DaysPerHour",
            "tddPerHour",
            "tdd24HrsPerHour",
            "mealProb",
            "endogenousGlucoseDrive",
            "circadianSiFactor",
            "transientResistanceProb",
            "patientModeMealBias",
            "patientModeProtectionBias",
            "contextIntentConfidence",
            "causalMealConfidence",
            "causalProtectiveConfidence",
            "causalLearningQuality",
            "familyProtectionLevel",
            "familyMealLevel",
            "familyStabilityLevel",
            "familyPhysioLevel",
            "familyAutonomyLevel",
            "predictedSMB",
            "smbGiven",
        )
        val cols = listOf(
            "06/11/2026 08:00",
            "148",
            "1.0",
            "8.0",
            "3.0",
            "2.4",
            "1.6",
            "0.8",
            "0.7",
            "0.9",
            "1.0",
            "0.44",
            "0.19",
            "0.92",
            "0.58",
            "0.74",
            "0.33",
            "0.65",
            "0.71",
            "0.12",
            "0.82",
            "2",
            "4",
            "3",
            "1",
            "3",
            "0.35",
            "0.30",
        )

        val parsed = SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.size).isEqualTo(SmbRefinementFeatureSchema.INPUT_SIZE - 1)
        assertThat(parsed.copyOfRange(17, 20).toList())
            .containsExactly(0.71f, 0.12f, 0.82f)
            .inOrder()
    }

    @Test
    fun should_use_csv_row_for_training_rejects_conflicted_or_fragile_rows() {
        val headers = listOf(
            "dateStr",
            "bg",
            "iob",
            "cob",
            "delta",
            "shortAvgDelta",
            "longAvgDelta",
            "tdd7DaysPerHour",
            "tdd2DaysPerHour",
            "tddPerHour",
            "tdd24HrsPerHour",
            "mealProb",
            "endogenousGlucoseDrive",
            "circadianSiFactor",
            "transientResistanceProb",
            "patientModeMealBias",
            "patientModeProtectionBias",
            "contextIntentConfidence",
            "causalMealConfidence",
            "causalProtectiveConfidence",
            "causalLearningQuality",
            "eventMemoryPostHyperExhaustionScore",
            "eventMemoryCorrectionFragilityScore",
            "decisionConflictFlags",
        )
        val cols = listOf(
            "06/11/2026 08:00",
            "182",
            "1.8",
            "12.0",
            "2.5",
            "2.1",
            "1.5",
            "0.8",
            "0.7",
            "0.9",
            "1.0",
            "0.52",
            "0.18",
            "0.93",
            "0.28",
            "0.80",
            "0.20",
            "0.54",
            "0.72",
            "0.22",
            "0.78",
            "0.48",
            "0.75",
            "dual_delivery|dual_delivery_tbr_up",
        )

        val parsed = SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols)

        assertThat(parsed).isNotNull()
        assertThat(SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(headers, cols, parsed!!)).isFalse()
    }

    @Test
    fun should_use_for_training_uses_the_learning_quality_threshold() {
        val causalStart = SmbRefinementFeatureSchema.BASE_FEATURE_COUNT + 4 + 3
        fun features(protective: Float, quality: Float) = FloatArray(SmbRefinementFeatureSchema.INPUT_SIZE - 1).also {
            it[causalStart + 1] = protective
            it[causalStart + 2] = quality
        }

        val threshold = SmbRefinementFeatureSchema.MIN_TRAINING_LEARNING_QUALITY

        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.2f, threshold))).isTrue()
        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.2f, threshold - 0.01f))).isFalse()
        // A real phone had quality 0.40 on average: such a row must be usable.
        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.2f, 0.45f))).isTrue()
        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.9f, 0.60f))).isFalse()
    }

    @Test
    fun should_use_for_training_accepts_a_limit_set_by_the_user() {
        val causalStart = SmbRefinementFeatureSchema.BASE_FEATURE_COUNT + 4 + 3
        fun features(quality: Float) = FloatArray(SmbRefinementFeatureSchema.INPUT_SIZE - 1).also {
            it[causalStart + 1] = 0.2f
            it[causalStart + 2] = quality
        }

        // Row quality 0.30: dropped by the default limit, kept when the user lowers the limit to 0.25.
        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.30f))).isFalse()
        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.30f), minLearningQuality = 0.25f)).isTrue()
        // Row quality 0.45: kept by the default limit, dropped when the user raises the limit to 0.50.
        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.45f))).isTrue()
        assertThat(SmbRefinementFeatureSchema.shouldUseForTraining(features(0.45f), minLearningQuality = 0.50f)).isFalse()
    }

    @Test
    fun should_use_csv_row_for_training_keeps_rows_with_only_dual_delivery_flags() {
        val headers = listOf(
            "dateStr", "bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta",
            "tdd7DaysPerHour", "tdd2DaysPerHour", "tddPerHour", "tdd24HrsPerHour",
            "mealProb", "endogenousGlucoseDrive", "circadianSiFactor", "transientResistanceProb",
            "patientModeMealBias", "patientModeProtectionBias", "contextIntentConfidence",
            "causalMealConfidence", "causalProtectiveConfidence", "causalLearningQuality",
            "eventMemoryPostHyperExhaustionScore", "eventMemoryCorrectionFragilityScore", "decisionConflictFlags",
        )
        fun cols(flags: String) = listOf(
            "06/11/2026 08:00", "182", "1.8", "0.0", "2.5", "2.1", "1.5", "0.8", "0.7", "0.9", "1.0",
            "1.0", "0.18", "0.93", "0.28", "0.80", "0.20", "0.54", "0.72", "0.22", "0.78", "0.10", "0.10", flags,
        )

        fun used(flags: String, relaxed: Boolean = true): Boolean {
            val c = cols(flags)
            val parsed = SmbRefinementFeatureSchema.parseTrainingFeatures(headers, c)!!
            return SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(headers, c, parsed, relaxedFilter = relaxed)
        }

        assertThat(used("")).isTrue()
        assertThat(used("dual_delivery|dual_delivery_tbr_up")).isTrue()
        assertThat(used("dual_delivery|dual_delivery_tbr_down")).isTrue()
        assertThat(used("dual_delivery|dual_delivery_suspend")).isFalse()
        assertThat(used("some_other_conflict")).isFalse()
        // Strict mode: any flag blocks the row, as before.
        assertThat(used("", relaxed = false)).isTrue()
        assertThat(used("dual_delivery|dual_delivery_tbr_up", relaxed = false)).isFalse()
    }
}
