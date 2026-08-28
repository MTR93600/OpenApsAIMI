package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState

internal object SmbRefinementFeatureSchema {

    const val BASE_FEATURE_COUNT = 10
    private const val LATENT_FEATURE_COUNT = 4
    private const val MODE_FEATURE_COUNT = 3
    private const val CAUSAL_FEATURE_COUNT = 3
    const val INPUT_SIZE = BASE_FEATURE_COUNT + LATENT_FEATURE_COUNT + MODE_FEATURE_COUNT + CAUSAL_FEATURE_COUNT + 1

    private const val NEUTRAL_MEAL_PROB = 0f
    private const val NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE = 0f
    private const val NEUTRAL_CIRCADIAN_SI_FACTOR = 1f
    private const val NEUTRAL_TRANSIENT_RESISTANCE_PROB = 0f
    private const val NEUTRAL_PATIENT_MODE_MEAL_BIAS = 0.45f
    private const val NEUTRAL_PATIENT_MODE_PROTECTION_BIAS = 0.22f
    private const val NEUTRAL_CONTEXT_INTENT_CONFIDENCE = 0f
    private const val NEUTRAL_CAUSAL_MEAL_CONFIDENCE = 0f
    private const val NEUTRAL_CAUSAL_PROTECTIVE_CONFIDENCE = 0f
    private const val NEUTRAL_CAUSAL_LEARNING_QUALITY = 1f

    val requiredTrainingFeatureNames: List<String> = listOf(
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
    )

    val latentFeatureNames: List<String> = listOf(
        "mealProb",
        "endogenousGlucoseDrive",
        "circadianSiFactor",
        "transientResistanceProb",
    )

    val modeFeatureNames: List<String> = listOf(
        "patientModeMealBias",
        "patientModeProtectionBias",
        "contextIntentConfidence",
    )

    val causalFeatureNames: List<String> = listOf(
        "causalMealConfidence",
        "causalProtectiveConfidence",
        "causalLearningQuality",
    )

    val familyAuditFeatureNames: List<String> = listOf(
        "familyProtectionLevel",
        "familyMealLevel",
        "familyStabilityLevel",
        "familyPhysioLevel",
        "familyAutonomyLevel",
    )

    val optionalTrainingAuditFeatureNames: List<String> = listOf(
        "eventMemoryPostHyperExhaustionScore",
        "eventMemoryCorrectionFragilityScore",
        "decisionConflictFlags",
    )

    val csvFeatureNames: List<String> = requiredTrainingFeatureNames + latentFeatureNames + modeFeatureNames + causalFeatureNames

    fun buildRuntimeFeatures(
        baseFeatures: FloatArray,
        trendIndicator: Float,
        physioLatentState: PhysioLatentState?,
        patientModeDecision: PatientModeOrchestrator.Decision?,
        causalStatePosterior: CausalStatePosterior?,
    ): FloatArray {
        require(baseFeatures.size == BASE_FEATURE_COUNT) {
            "Expected $BASE_FEATURE_COUNT base features, got ${baseFeatures.size}"
        }

        val latentFeatures = latentFeatureValues(physioLatentState)
        val modeFeatures = modeFeatureValues(patientModeDecision)
        val causalFeatures = causalFeatureValues(causalStatePosterior)
        return FloatArray(INPUT_SIZE).also { out ->
            baseFeatures.copyInto(out, endIndex = baseFeatures.size)
            latentFeatures.copyInto(out, destinationOffset = baseFeatures.size)
            modeFeatures.copyInto(out, destinationOffset = baseFeatures.size + latentFeatures.size)
            causalFeatures.copyInto(
                out,
                destinationOffset = baseFeatures.size + latentFeatures.size + modeFeatures.size,
            )
            out[INPUT_SIZE - 1] = trendIndicator
        }
    }

    fun parseTrainingFeatures(headers: List<String>, cols: List<String>): FloatArray? {
        val requiredFeatures = requiredTrainingFeatureNames.map { name ->
            val index = headers.indexOf(name)
            if (index == -1) {
                return null
            }
            cols.getOrNull(index)?.toFloatOrNull() ?: return null
        }
        val latentFeatures = latentFeatureNames.map { name ->
            val index = headers.indexOf(name)
            cols.getOrNull(index)?.toFloatOrNull() ?: neutralValueFor(name)
        }
        val modeFeatures = modeFeatureNames.map { name ->
            val index = headers.indexOf(name)
            cols.getOrNull(index)?.toFloatOrNull() ?: neutralValueFor(name)
        }
        val causalFeatures = causalFeatureNames.map { name ->
            val index = headers.indexOf(name)
            cols.getOrNull(index)?.toFloatOrNull() ?: neutralValueFor(name)
        }

        return (requiredFeatures + latentFeatures + modeFeatures + causalFeatures).toFloatArray()
    }

    fun latentFeatureValues(physioLatentState: PhysioLatentState?): FloatArray =
        floatArrayOf(
            physioLatentState?.mealProb?.toFloat() ?: NEUTRAL_MEAL_PROB,
            physioLatentState?.endogenousGlucoseDrive?.toFloat() ?: NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE,
            physioLatentState?.circadianSiFactor?.toFloat() ?: NEUTRAL_CIRCADIAN_SI_FACTOR,
            physioLatentState?.transientResistanceProb?.toFloat() ?: NEUTRAL_TRANSIENT_RESISTANCE_PROB,
        )

    fun modeFeatureValues(patientModeDecision: PatientModeOrchestrator.Decision?): FloatArray =
        floatArrayOf(
            patientModeDecision?.mealBias?.toFloat() ?: NEUTRAL_PATIENT_MODE_MEAL_BIAS,
            patientModeDecision?.protectionBias?.toFloat() ?: NEUTRAL_PATIENT_MODE_PROTECTION_BIAS,
            patientModeDecision?.userIntentConfidence?.toFloat() ?: NEUTRAL_CONTEXT_INTENT_CONFIDENCE,
        )

    fun causalFeatureValues(causalStatePosterior: CausalStatePosterior?): FloatArray =
        floatArrayOf(
            causalStatePosterior?.mealConfidence?.toFloat() ?: NEUTRAL_CAUSAL_MEAL_CONFIDENCE,
            causalStatePosterior?.protectiveConfidence?.toFloat() ?: NEUTRAL_CAUSAL_PROTECTIVE_CONFIDENCE,
            causalStatePosterior?.learningQuality?.toFloat() ?: NEUTRAL_CAUSAL_LEARNING_QUALITY,
        )

    fun shouldUseForTraining(rawFeatures: FloatArray): Boolean {
        val causalStart = BASE_FEATURE_COUNT + LATENT_FEATURE_COUNT + MODE_FEATURE_COUNT
        val protectiveConfidence = rawFeatures.getOrElse(causalStart + 1) { NEUTRAL_CAUSAL_PROTECTIVE_CONFIDENCE }
        val learningQuality = rawFeatures.getOrElse(causalStart + 2) { NEUTRAL_CAUSAL_LEARNING_QUALITY }
        return learningQuality >= 0.52f && protectiveConfidence < 0.86f
    }

    fun shouldUseCsvRowForTraining(headers: List<String>, cols: List<String>, rawFeatures: FloatArray): Boolean {
        if (!shouldUseForTraining(rawFeatures)) return false

        val conflictFlags = headerValue(headers, cols, "decisionConflictFlags").orEmpty().trim()
        if (conflictFlags.isNotEmpty() && conflictFlags != "[]" && conflictFlags.lowercase() != "none") {
            return false
        }

        val fragility = headerValue(headers, cols, "eventMemoryCorrectionFragilityScore")?.toFloatOrNull()
        if (fragility != null && fragility >= 0.72f) {
            return false
        }

        val exhaustion = headerValue(headers, cols, "eventMemoryPostHyperExhaustionScore")?.toFloatOrNull()
        val causalStart = BASE_FEATURE_COUNT + LATENT_FEATURE_COUNT + MODE_FEATURE_COUNT
        val protectiveConfidence = rawFeatures.getOrElse(causalStart + 1) { NEUTRAL_CAUSAL_PROTECTIVE_CONFIDENCE }
        if (exhaustion != null && exhaustion >= 0.82f && protectiveConfidence >= 0.55f) {
            return false
        }

        return true
    }

    private fun headerValue(headers: List<String>, cols: List<String>, name: String): String? {
        val index = headers.indexOf(name)
        if (index == -1) return null
        return cols.getOrNull(index)
    }

    private fun neutralValueFor(name: String): Float =
        when (name) {
            "mealProb" -> NEUTRAL_MEAL_PROB
            "endogenousGlucoseDrive" -> NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE
            "circadianSiFactor" -> NEUTRAL_CIRCADIAN_SI_FACTOR
            "transientResistanceProb" -> NEUTRAL_TRANSIENT_RESISTANCE_PROB
            "patientModeMealBias" -> NEUTRAL_PATIENT_MODE_MEAL_BIAS
            "patientModeProtectionBias" -> NEUTRAL_PATIENT_MODE_PROTECTION_BIAS
            "contextIntentConfidence" -> NEUTRAL_CONTEXT_INTENT_CONFIDENCE
            "causalMealConfidence" -> NEUTRAL_CAUSAL_MEAL_CONFIDENCE
            "causalProtectiveConfidence" -> NEUTRAL_CAUSAL_PROTECTIVE_CONFIDENCE
            "causalLearningQuality" -> NEUTRAL_CAUSAL_LEARNING_QUALITY
            else -> 0f
        }
}
