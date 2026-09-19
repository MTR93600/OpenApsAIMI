package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPriority
import kotlin.math.roundToInt

/**
 * The four metric rules of the AIMI Profile Advisor.
 *
 * They read only the CGM/TDD metrics of the report, a snapshot of the user preferences and a text
 * resolver, so they are pure and can be unit tested without a pump, a database or Android. The
 * reason of an action is user visible - it is shown in the confirm dialog and stored in the advisor
 * history that the AI coach reads - so it is resolved rather than written out in code. The resolver
 * is nullable and falls back to English, the same way the score labels in `AimiAdvisorService` do. The service calls this and
 * runs the result through its own visibility filter
 * (`AimiAdvisorService.isRecommendationVisible`), which is where the 48h cooldown lives.
 *
 * Every rule keeps the thresholds it had before the recommendation code was moved to a plugin
 * system that was never registered.
 */
object AdvisorMetricRules {

    /** Hypo pressure above this share of the period asks for less insulin. */
    const val HYPO_PRESSURE_FRACTION = 0.04

    /** Below this time in range the control rule fires. */
    const val LOW_TIR_FRACTION = 0.70

    /** Above this share of time over 180 mg/dL the hyper rule fires. */
    const val HIGH_TIME_ABOVE_180_FRACTION = 0.20

    /** A rule only suggests more insulin while hypo pressure stays at or below this. */
    const val SAFE_HYPO_FRACTION = 0.03

    /** Above this basal share of the total daily dose the profile is basal dominant. */
    const val BASAL_DOMINANT_FRACTION = 0.55

    /** Max SMB is only cut back when it is above this, so an already small value is left alone. */
    const val MAX_SMB_CUT_FLOOR = 1.5

    /** The lunch factor is only raised while it is still below this. */
    const val LUNCH_FACTOR_RAISE_CEILING = 1.2

    /** Max SMB is cut back to 80% of its current value. */
    const val MAX_SMB_CUT_FACTOR = 0.8

    /** The lunch factor is raised one step of 0.1. */
    const val LUNCH_FACTOR_STEP = 0.1
}

/**
 * Builds the metric driven recommendations, in the order the advisor used to show them.
 *
 * Rule 4 (basal dominance) carries no action, so it is informational and the visibility filter of
 * the service passes it through untouched - the same result the original code got by skipping the
 * filter for that rule.
 */
fun metricRecommendations(
    metrics: AdvisorMetrics,
    prefs: AimiPrefsSnapshot,
    rh: TextResolver?,
): List<AimiRecommendation> {
    val recs = mutableListOf<AimiRecommendation>()

    // 1) Critical: too much time low -> propose a smaller Max SMB.
    if (metrics.timeBelow70 > AdvisorMetricRules.HYPO_PRESSURE_FRACTION) {
        val action = if (prefs.maxSmb > AdvisorMetricRules.MAX_SMB_CUT_FLOOR) {
            val newValue = (prefs.maxSmb * AdvisorMetricRules.MAX_SMB_CUT_FACTOR * 10.0).roundToInt() / 10.0
            AimiAction.PreferenceUpdate(
                key = DoubleKey.OApsAIMIMaxSMB,
                newValue = newValue,
                reason = rh?.gs(ApsStrings.aimi_adv_rec_hypos_action_reason)
                    ?: "Lower Max SMB by 20% because time below 70 mg/dL is high",
                domain = AimiDomain.Safety,
                priority = AimiPriority.Critical,
            )
        } else {
            null
        }
        recs += AimiRecommendation(
            title = ApsStrings.aimi_adv_rec_hypos_title,
            description = ApsStrings.aimi_adv_rec_hypos_desc.withArgs((metrics.timeBelow54 * 100).roundToInt()),
            priority = AimiPriority.Critical,
            domain = AimiDomain.Safety,
            action = action,
        )
    }

    // 2) High: low time in range while lows are rare -> propose a stronger lunch factor.
    if (metrics.tir70_180 < AdvisorMetricRules.LOW_TIR_FRACTION && metrics.timeBelow70 <= AdvisorMetricRules.SAFE_HYPO_FRACTION) {
        val action = if (prefs.lunchFactor < AdvisorMetricRules.LUNCH_FACTOR_RAISE_CEILING) {
            // The brackets matter. The original line was `(prefs.lunchFactor + 0.1 * 10.0).roundToInt() / 10.0`,
            // which by operator precedence is `lunchFactor + 1.0` and then `/ 10`, so a lunch factor of
            // 1.0 proposed 0.2 instead of 1.1. Round the stepped value, not the un-stepped one.
            val newValue = ((prefs.lunchFactor + AdvisorMetricRules.LUNCH_FACTOR_STEP) * 10.0).roundToInt() / 10.0
            AimiAction.PreferenceUpdate(
                key = DoubleKey.OApsAIMILunchFactor,
                newValue = newValue,
                reason = rh?.gs(ApsStrings.aimi_adv_rec_control_action_reason)
                    ?: "Raise the lunch factor by 0.1 because time in range is below the goal",
                domain = AimiDomain.Basal,
                priority = AimiPriority.High,
            )
        } else {
            null
        }
        recs += AimiRecommendation(
            title = ApsStrings.aimi_adv_rec_control_title,
            description = ApsStrings.aimi_adv_rec_control_desc.withArgs((metrics.tir70_180 * 100).roundToInt()),
            priority = AimiPriority.High,
            domain = AimiDomain.Basal,
            action = action,
        )
    }

    // 3) Medium: highs dominate while lows are rare. No safe automatic change, so this one only informs.
    if (metrics.timeAbove180 > AdvisorMetricRules.HIGH_TIME_ABOVE_180_FRACTION && metrics.timeBelow70 <= AdvisorMetricRules.SAFE_HYPO_FRACTION) {
        recs += AimiRecommendation(
            title = ApsStrings.aimi_adv_rec_hypers_title,
            description = ApsStrings.aimi_adv_rec_hypers_desc.withArgs((metrics.timeAbove180 * 100).roundToInt()),
            priority = AimiPriority.Medium,
            domain = AimiDomain.Isf,
            action = null,
        )
    }

    // 4) Medium: basal carries most of the daily dose. Informational only.
    if (metrics.basalPercent > AdvisorMetricRules.BASAL_DOMINANT_FRACTION) {
        recs += AimiRecommendation(
            title = ApsStrings.aimi_adv_rec_basal_title,
            description = ApsStrings.aimi_adv_rec_basal_desc.withArgs((metrics.basalPercent * 100).roundToInt()),
            priority = AimiPriority.Medium,
            domain = AimiDomain.Basal,
            action = null,
        )
    }

    return recs
}
