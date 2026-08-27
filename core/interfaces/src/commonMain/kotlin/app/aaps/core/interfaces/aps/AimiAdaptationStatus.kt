package app.aaps.core.interfaces.aps

enum class AimiAdaptationModuleId {
    BASAL_NEURAL_GOVERNANCE,
    UNIFIED_REACTIVITY,
    BASAL_MULTI_SCALE,
    PKPD,
    ONLINE_LEARNER,
    NIGHT_GROWTH_RESISTANCE,
    WCYCLE,
    PEAK_GOVERNOR,
    DIA_GOVERNOR,
}

enum class AimiAdaptationPhase {
    DISABLED,
    WAITING,
    LEARNING,
    READY,
    ACTIVE,
    STALE,
    BLOCKED,
}

enum class AimiAdaptationReasonCode {
    FEATURE_DISABLED,
    WAITING_FOR_SAMPLES,
    WAITING_FOR_EVENTS,
    WAITING_FOR_FEEDBACKS,
    WAITING_FOR_OBSERVATIONS,
    WARMUP,
    LEARNING_IN_PROGRESS,
    READY,
    ACTIVE,
    DATA_STALE,
    SAFETY_HOLD,
    CONTEXT_BLOCKED,
    DEPENDENCY_DISABLED,
    NO_RESULT,
}

enum class AimiAdaptationEvidenceType {
    SAMPLES,
    EVENTS,
    FEEDBACKS,
    OBSERVATIONS,
}

data class AimiAdaptationProgress(
    val type: AimiAdaptationEvidenceType,
    val completed: Int,
    val required: Int,
)

enum class AimiAdaptationMetricId {
    SAMPLE_COUNT,
    SHORT_BUFFER_COUNT,
    MEDIUM_BUFFER_COUNT,
    FASTING_SAMPLE_COUNT,
    SHORT_UPDATE_COUNT,
    MEDIUM_UPDATE_COUNT,
    LONG_UPDATE_COUNT,
    SHORT_ANALYSIS_COUNT,
    LONG_ANALYSIS_COUNT,
    PENDING_PREDICTION_COUNT,
    EVALUATED_FEEDBACK_COUNT,
    RELEASE_COUNT,
    ACCEPTED_UPDATE_COUNT,
    SHORT_MULTIPLIER,
    MEDIUM_MULTIPLIER,
    LONG_MULTIPLIER,
    COMBINED_MULTIPLIER,
    GLOBAL_FACTOR,
    SHORT_FACTOR,
    SEGMENT_FACTOR,
    SENSITIVITY_FACTOR,
    LAST_ERROR,
    DIA_HOURS,
    PEAK_MINUTES,
    TIR_PERCENT,
    CV_PERCENT,
    CONFIDENCE,
    SMB_MULTIPLIER,
    BASAL_MULTIPLIER,
    EXTRA_IOB_HEADROOM_UNITS,
    DAY_IN_CYCLE,
    IC_MULTIPLIER,
    EFFECTIVE_VALUE,
    PRIOR_VALUE,
    LEARNED_VALUE,
}

data class AimiAdaptationMetric(
    val id: AimiAdaptationMetricId,
    val value: Double,
)

data class AimiAdaptationModuleStatus(
    val moduleId: AimiAdaptationModuleId,
    val phase: AimiAdaptationPhase,
    val reason: AimiAdaptationReasonCode,
    val progress: AimiAdaptationProgress? = null,
    val metrics: List<AimiAdaptationMetric> = emptyList(),
    val updatedAt: Long? = null,
)

data class AimiAdaptationStatus(
    val updatedAt: Long,
    val modules: List<AimiAdaptationModuleStatus>,
    val activeCount: Int,
    val readyCount: Int,
    val waitingCount: Int,
    val learningCount: Int,
    val blockedCount: Int,
    val staleCount: Int,
    val disabledCount: Int,
)
