package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import app.aaps.core.keys.interfaces.PreferenceKey

/**
 * User-facing tuning intent. Distinct from loop [app.aaps.core.keys.StringKey.ContextMode]
 * (tick-level SMB modulation) — this bundle adjusts persistent AIMI preferences.
 */
enum class AimiTuningContext {
    /** Post-meal rises, slow corrections on high BG. */
    MEAL_RISE,
    /** Frequent time below 70 mg/dL — reduce aggression. */
    HYPO_GUARD,
    /** General hyper burden without dominant meal pattern. */
    HYPER_STABLE,
    /** Pick direction from 7-day CGM metrics. */
    AUTO_BALANCE,
    /**
     * Resolved from [AUTO_BALANCE] only — highs and lows both significant; applies split tuning.
     * Not shown as a separate UI chip.
     */
    MIXED_BALANCE,
}

/**
 * How large each numeric adjustment should be, derived from CGM burden vs clinical thresholds.
 */
enum class TuningStepTier {
    /** Barely over threshold — conservative nudge. */
    MICRO,
    /** Clear pattern — default step. */
    MODERATE,
    /** Strong imbalance — larger but still clamped to key bounds. */
    STRONG,
}

data class TuningChange(
    val key: PreferenceKey,
    val labelKey: String,
    val oldValue: Any,
    val newValue: Any,
    val reason: String,
    val tier: TuningStepTier,
)

data class TuningPlan(
    val requestedContext: AimiTuningContext,
    val effectiveContext: AimiTuningContext,
    val dominantTier: TuningStepTier,
    val changes: List<TuningChange>,
    val blockedReason: String? = null,
) {
    val isActionable: Boolean get() = changes.isNotEmpty() && blockedReason == null
}

enum class TuningExportStatus {
    SKIPPED_DISABLED,
    SKIPPED_NO_PASSWORD,
    SKIPPED_PASSWORD_EXPIRED,
    SUCCESS,
    FAILED,
}

data class TuningApplyResult(
    val plan: TuningPlan,
    val appliedCount: Int,
    val summaryLines: List<String>,
    val exportStatus: TuningExportStatus,
)
