package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStrategyHint
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the SMB-refinement feature schema: runtime vector layout, old-CSV fallback neutrals,
 * family-audit columns ignored by the trainer parser, and the conflict/fragility training gate.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `fe96b64f12`
 * (`plugins/aps/src/test/.../ml/SmbRefinementFeatureSchemaTest.kt`). Study source set:
 * [SmbRefinementFeatureSchema] is commonMain with no File/Android, so the tests live in
 * `commonTest` (`kotlin.test`) — same layout as [AimiSmbCorpusGuardTest] /
 * [SmbTrainingRowBufferTest], not `androidHostTest`. Assertions are the same locks as the
 * Truth/JUnit Jupiter reference corpus.
 *
 * Adaptations: `org.junit.jupiter` + Truth → `kotlin.test`; snake_case names → backticks without
 * commas (Kotlin/Native). No production change.
 */
class SmbRefinementFeatureSchemaTest {

    @Test
    fun `build runtime features appends latent physio axes and trend indicator`() {
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

        assertEquals(SmbRefinementFeatureSchema.INPUT_SIZE, features.size)
        assertEquals(
            listOf(0.41f, 0.28f, 0.91f, 0.64f),
            features.copyOfRange(10, 14).toList(),
        )
        assertEquals(
            listOf(0.90f, 0.18f, 0.84f),
            features.copyOfRange(14, 17).toList(),
        )
        assertEquals(
            listOf(0.83f, 0.12f, 0.79f),
            features.copyOfRange(17, 20).toList(),
        )
        assertEquals(0.73f, features.last())
    }

    @Test
    fun `parse training features keeps backward compatibility for old csv rows`() {
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

        val parsed = assertNotNull(SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols))
        assertEquals(SmbRefinementFeatureSchema.INPUT_SIZE - 1, parsed.size)
        assertEquals(
            listOf(0f, 0f, 1f, 0f, 0.45f, 0.22f, 0f, 0f, 0f, 1f),
            parsed.copyOfRange(10, 20).toList(),
        )
    }

    @Test
    fun `parse training features ignores family audit columns`() {
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

        val parsed = assertNotNull(SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols))
        assertEquals(SmbRefinementFeatureSchema.INPUT_SIZE - 1, parsed.size)
        assertEquals(
            listOf(0.71f, 0.12f, 0.82f),
            parsed.copyOfRange(17, 20).toList(),
        )
    }

    @Test
    fun `should use csv row for training rejects conflicted or fragile rows`() {
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

        val parsed = assertNotNull(SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols))
        assertFalse(SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(headers, cols, parsed))
    }

    /** Raw features with only the protective confidence and the learning quality set. */
    private fun causalFeatures(protective: Float, quality: Float): FloatArray {
        // Causal block starts after the base, latent (4) and mode (3) features.
        val causalStart = SmbRefinementFeatureSchema.BASE_FEATURE_COUNT + 4 + 3
        return FloatArray(SmbRefinementFeatureSchema.INPUT_SIZE - 1).also {
            it[causalStart + 1] = protective
            it[causalStart + 2] = quality
        }
    }

    @Test
    fun `should use for training uses the learning quality threshold`() {
        val threshold = SmbRefinementFeatureSchema.MIN_TRAINING_LEARNING_QUALITY

        assertTrue(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.2f, threshold)))
        assertFalse(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.2f, threshold - 0.01f)))
        // A real phone had quality 0.40 on average: such a row must be usable.
        assertTrue(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.2f, 0.45f)))
        assertFalse(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.9f, 0.60f)))
    }

    @Test
    fun `should use for training accepts a limit set by the user`() {
        // Quality 0.35: dropped by the standard limit, kept when the user lowers the limit to 0.30.
        assertFalse(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.2f, 0.35f)))
        assertTrue(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.2f, 0.35f), minLearningQuality = 0.30f))
        // Quality 0.45: kept by the standard limit, dropped when the user raises the limit to 0.50.
        assertTrue(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.2f, 0.45f)))
        assertFalse(SmbRefinementFeatureSchema.shouldUseForTraining(causalFeatures(0.2f, 0.45f), minLearningQuality = 0.50f))
    }

    @Test
    fun `relaxed filter keeps rows with only dual delivery flags`() {
        val headers = listOf(
            "dateStr", "bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta",
            "tdd7DaysPerHour", "tdd2DaysPerHour", "tddPerHour", "tdd24HrsPerHour",
            "mealProb", "endogenousGlucoseDrive", "circadianSiFactor", "transientResistanceProb",
            "patientModeMealBias", "patientModeProtectionBias", "contextIntentConfidence",
            "causalMealConfidence", "causalProtectiveConfidence", "causalLearningQuality",
            "eventMemoryPostHyperExhaustionScore", "eventMemoryCorrectionFragilityScore", "decisionConflictFlags",
        )

        fun used(flags: String, relaxed: Boolean): Boolean {
            val cols = listOf(
                "06/11/2026 08:00", "182", "1.8", "0.0", "2.5", "2.1", "1.5", "0.8", "0.7", "0.9", "1.0",
                "1.0", "0.18", "0.93", "0.28", "0.80", "0.20", "0.54", "0.72", "0.22", "0.78", "0.10", "0.10", flags,
            )
            val parsed = assertNotNull(SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols))
            return SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(headers, cols, parsed, relaxedFilter = relaxed)
        }

        assertTrue(used("", relaxed = true))
        assertTrue(used("dual_delivery|dual_delivery_tbr_up", relaxed = true))
        assertTrue(used("dual_delivery|dual_delivery_tbr_down", relaxed = true))
        assertFalse(used("dual_delivery|dual_delivery_suspend", relaxed = true))
        assertFalse(used("some_other_conflict", relaxed = true))
        // Strict mode: any flag blocks the row, as before.
        assertTrue(used("", relaxed = false))
        assertFalse(used("dual_delivery|dual_delivery_tbr_up", relaxed = false))
    }
}
