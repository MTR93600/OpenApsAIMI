package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

class AimiAdvisorStringsTextResolver(private val textResolver: TextResolver) : AimiAdvisorStrings {

    override val scoreLabelExcellent: String get() = textResolver.gs(ApsStrings.aimi_advisor_score_label_excellent)
    override val scoreLabelGood: String get() = textResolver.gs(ApsStrings.aimi_advisor_score_label_good)
    override val scoreLabelWarning: String get() = textResolver.gs(ApsStrings.aimi_advisor_score_label_warning)
    override val scoreLabelAttention: String get() = textResolver.gs(ApsStrings.aimi_advisor_score_label_attention)
    override val scoreLabelCritical: String get() = textResolver.gs(ApsStrings.aimi_advisor_score_label_critical)
    override val notEnoughData: String get() = textResolver.gs(ApsStrings.aimi_adv_not_enough_data)

    override val analysisIntroExcellent: String get() = textResolver.gs(ApsStrings.aimi_adv_analysis_intro_excellent)
    override val analysisIntroGood: String get() = textResolver.gs(ApsStrings.aimi_adv_analysis_intro_good)
    override val analysisIntroPoor: String get() = textResolver.gs(ApsStrings.aimi_adv_analysis_intro_poor)
    override val analysisIssuesHeader: String get() = textResolver.gs(ApsStrings.aimi_adv_analysis_issues_header)
    override val analysisAllGood: String get() = textResolver.gs(ApsStrings.aimi_adv_analysis_all_good)

    override val generatedFooter: String get() = textResolver.gs(ApsStrings.aimi_adv_generated_footer)

    override val basalStrategyNoProfile: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_strategy_no_profile)
    override val basalStrategySafetyReduction: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_strategy_safety_reduction)
    override val basalStrategyGentleIncrease: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_strategy_gentle_increase)
    override val basalStrategyHoldBaseline: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_strategy_hold_baseline)
    override val basalRationaleSafetyReduction: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_rationale_safety_reduction)
    override val basalRationaleGentleIncrease: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_rationale_gentle_increase)
    override val basalRationaleHoldBaseline: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_rationale_hold_baseline)
    override val basalExportHeaderTitle: String get() = textResolver.gs(ApsStrings.aimi_adv_basal_export_header_title)

    override fun gs(ref: TextRef): String = textResolver.gs(ref)
    override fun gs(ref: TextRef, vararg args: Any?): String = textResolver.gs(ref, *args)
    override fun gsNotLocalised(ref: TextRef): String = textResolver.gsNotLocalised(ref)
}
