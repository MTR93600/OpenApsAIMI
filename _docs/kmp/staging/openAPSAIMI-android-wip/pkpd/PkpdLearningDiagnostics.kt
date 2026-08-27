package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior

/**
 * Per-tick PKPD learning gate outcome — exported in [app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiIntelligenceSnapshot].
 */
data class PkpdLearningDiagnostics(
    val learningQuality: Double,
    val learningGatePass: Boolean,
    val learningAttempted: Boolean,
    val dominant: String,
    val skipReason: String?,
) {
    companion object {
        fun from(
            causalStatePosterior: CausalStatePosterior?,
            allowLearning: Boolean,
            exerciseFlag: Boolean,
            iobU: Double,
            carbsActiveG: Double,
            bg: Double,
            deltaMgDlPer5: Double,
        ): PkpdLearningDiagnostics {
            val quality = causalStatePosterior?.learningQuality ?: 1.0
            val gatePass = causalStatePosterior?.learningContextClean() ?: true
            val dominant = causalStatePosterior?.dominant?.name ?: CausalStateId.UNKNOWN.name
            if (!allowLearning) {
                return PkpdLearningDiagnostics(
                    learningQuality = quality,
                    learningGatePass = gatePass,
                    learningAttempted = false,
                    dominant = dominant,
                    skipReason = "read_only_path",
                )
            }
            val skip = when {
                !gatePass -> "causal_learning_unclean"
                exerciseFlag -> "exercise"
                iobU < LEARNING_MIN_IOB_U -> "iob_below_min"
                carbsActiveG > LEARNING_MAX_COB_G -> "cob_above_max"
                bg < AdaptivePkPdEstimator.HYPO_LEARN_SKIP_BG_MGDL -> "hypo_bg"
                bg < AdaptivePkPdEstimator.HYPO_CAUTION_BG_MGDL && deltaMgDlPer5 < -1.0 -> "hypo_caution_fall"
                deltaMgDlPer5 > LEARNING_MAX_RISE_DELTA -> "rise_too_fast"
                deltaMgDlPer5 <= AdaptivePkPdEstimator.FAST_FALL_DELTA_MGDL5 -> "fast_fall"
                else -> null
            }
            return PkpdLearningDiagnostics(
                learningQuality = quality,
                learningGatePass = gatePass,
                learningAttempted = skip == null,
                dominant = dominant,
                skipReason = skip,
            )
        }

        private const val LEARNING_MIN_IOB_U = 0.3
        private const val LEARNING_MAX_COB_G = 15.0
        private const val LEARNING_MAX_RISE_DELTA = 5.0
    }
}
