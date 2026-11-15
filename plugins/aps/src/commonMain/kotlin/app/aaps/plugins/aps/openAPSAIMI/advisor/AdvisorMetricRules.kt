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

    /**
     * The lunch factor (percent, 100 = neutral) is only raised while it is still below this, and a
     * raise never goes above it. The tuning engine (`TuningContextEngine`, meal rise) uses the same
     * ceiling.
     *
     * 100 (neutral) since step T2: an automatic suggestion never proposes more than the full meal
     * SMB. Only the user can set a value above 100, by hand. A value already at or above 100 gets no
     * raise and is never lowered by this rule. Accepting the raise again and again can take the
     * default 50 up to 100, twice the meal SMB. Each raise needs the user to accept it.
     */
    const val LUNCH_FACTOR_RAISE_CEILING = 100.0

    /** Max SMB is cut back to 80% of its current value. */
    const val MAX_SMB_CUT_FACTOR = 0.8

    /** The lunch factor is raised one step of 10 percent points. */
    const val LUNCH_FACTOR_STEP = 10.0
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

    // A metric that was not measured is null. A rule that needs it is skipped: no number is
    // guessed, so no recommendation can rest on one.
    val below70 = metrics.timeBelow70
    val below54 = metrics.timeBelow54
    val tir = metrics.tir70_180
    val above180 = metrics.timeAbove180
    val basalPercent = metrics.basalPercent

    // 1) Critical: too much time low -> propose a smaller Max SMB.
    // The text names the time below 54 mg/dL, so this rule needs that number as well.
    if (below70 != null && below54 != null && below70 > AdvisorMetricRules.HYPO_PRESSURE_FRACTION) {
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
            description = ApsStrings.aimi_adv_rec_hypos_desc.withArgs((below54 * 100).roundToInt()),
            priority = AimiPriority.Critical,
            domain = AimiDomain.Safety,
            action = action,
        )
    }

    // 2) High: low time in range while lows are rare -> propose a stronger lunch factor.
    if (tir != null && below70 != null && tir < AdvisorMetricRules.LOW_TIR_FRACTION && below70 <= AdvisorMetricRules.SAFE_HYPO_FRACTION) {
        val action = if (prefs.lunchFactor < AdvisorMetricRules.LUNCH_FACTOR_RAISE_CEILING) {
            // The lunch factor is percent (100 = neutral). Round the stepped value to one decimal and
            // never go above the ceiling (95 gives 100, not 105).
            val stepped = ((prefs.lunchFactor + AdvisorMetricRules.LUNCH_FACTOR_STEP) * 10.0).roundToInt() / 10.0
            val newValue = stepped.coerceAtMost(AdvisorMetricRules.LUNCH_FACTOR_RAISE_CEILING)
            AimiAction.PreferenceUpdate(
                key = DoubleKey.OApsAIMILunchFactor,
                newValue = newValue,
                reason = rh?.gs(ApsStrings.aimi_adv_rec_control_action_reason)
                    ?: "Raise the lunch factor by up to 10 points, at most to 100, because time in range is below the goal",
                domain = AimiDomain.Basal,
                priority = AimiPriority.High,
            )
        } else {
            null
        }
        recs += AimiRecommendation(
            title = ApsStrings.aimi_adv_rec_control_title,
            description = ApsStrings.aimi_adv_rec_control_desc.withArgs((tir * 100).roundToInt()),
            priority = AimiPriority.High,
            domain = AimiDomain.Basal,
            action = action,
        )
    }

    // 3) Medium: highs dominate while lows are rare. No safe automatic change, so this one only informs.
    if (above180 != null && below70 != null && above180 > AdvisorMetricRules.HIGH_TIME_ABOVE_180_FRACTION && below70 <= AdvisorMetricRules.SAFE_HYPO_FRACTION) {
        recs += AimiRecommendation(
            title = ApsStrings.aimi_adv_rec_hypers_title,
            description = ApsStrings.aimi_adv_rec_hypers_desc.withArgs((above180 * 100).roundToInt()),
            priority = AimiPriority.Medium,
            domain = AimiDomain.Isf,
            action = null,
        )
    }

    // 4) Medium: basal carries most of the daily dose. Informational only.
    if (basalPercent != null && basalPercent > AdvisorMetricRules.BASAL_DOMINANT_FRACTION) {
        recs += AimiRecommendation(
            title = ApsStrings.aimi_adv_rec_basal_title,
            description = ApsStrings.aimi_adv_rec_basal_desc.withArgs((basalPercent * 100).roundToInt()),
            priority = AimiPriority.Medium,
            domain = AimiDomain.Basal,
            action = null,
        )
    }

    return recs
}
