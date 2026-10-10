package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.R

/**
 * Android implementation of [AimiAdvisorStrings]: every property resolves through the
 * real [ResourceHelper], so localized strings keep working exactly as before, and the
 * [app.aaps.core.interfaces.resources.TextResolver] contract is delegated to it as well.
 */
class AimiAdvisorStringsAndroid(private val rh: ResourceHelper) : AimiAdvisorStrings {

    override val scoreLabelExcellent: String get() = rh.gs(R.string.aimi_advisor_score_label_excellent)
    override val scoreLabelGood: String get() = rh.gs(R.string.aimi_advisor_score_label_good)
    override val scoreLabelWarning: String get() = rh.gs(R.string.aimi_advisor_score_label_warning)
    override val scoreLabelAttention: String get() = rh.gs(R.string.aimi_advisor_score_label_attention)
    override val scoreLabelCritical: String get() = rh.gs(R.string.aimi_advisor_score_label_critical)

    override val analysisIntroExcellent: String get() = rh.gs(R.string.aimi_adv_analysis_intro_excellent)
    override val analysisIntroGood: String get() = rh.gs(R.string.aimi_adv_analysis_intro_good)
    override val analysisIntroPoor: String get() = rh.gs(R.string.aimi_adv_analysis_intro_poor)
    override val analysisIssuesHeader: String get() = rh.gs(R.string.aimi_adv_analysis_issues_header)
    override val analysisAllGood: String get() = rh.gs(R.string.aimi_adv_analysis_all_good)

    override val generatedFooter: String get() = rh.gs(R.string.aimi_adv_generated_footer)

    override val basalStrategyNoProfile: String get() = rh.gs(R.string.aimi_adv_basal_strategy_no_profile)
    override val basalStrategySafetyReduction: String get() = rh.gs(R.string.aimi_adv_basal_strategy_safety_reduction)
    override val basalStrategyGentleIncrease: String get() = rh.gs(R.string.aimi_adv_basal_strategy_gentle_increase)
    override val basalStrategyHoldBaseline: String get() = rh.gs(R.string.aimi_adv_basal_strategy_hold_baseline)
    override val basalRationaleSafetyReduction: String get() = rh.gs(R.string.aimi_adv_basal_rationale_safety_reduction)
    override val basalRationaleGentleIncrease: String get() = rh.gs(R.string.aimi_adv_basal_rationale_gentle_increase)
    override val basalRationaleHoldBaseline: String get() = rh.gs(R.string.aimi_adv_basal_rationale_hold_baseline)
    override val basalExportHeaderTitle: String get() = rh.gs(R.string.aimi_adv_basal_export_header_title)

    override fun gs(ref: TextRef): String = rh.gs(ref)
    override fun gs(ref: TextRef, vararg args: Any?): String = rh.gs(ref, *args)
    override fun gsNotLocalised(ref: TextRef): String = rh.gsNotLocalised(ref)
    override fun shortTextMode(): Boolean = rh.shortTextMode()
}
