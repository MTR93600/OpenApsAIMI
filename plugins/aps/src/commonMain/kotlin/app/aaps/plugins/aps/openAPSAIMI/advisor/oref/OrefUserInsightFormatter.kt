package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.plugins.aps.ApsStrings

/**
 * Plain-language paragraphs for the Advisor UI (English strings in `ApsStrings`).
 */
object OrefUserInsightFormatter {

    fun buildParagraph(rh: TextResolver, o: OrefAnalysisReport): String {
        val lines = mutableListOf<String>()
        when (o.dataSufficiency) {
            OrefDataSufficiency.INSUFFICIENT ->
                lines += rh.gs(ApsStrings.aimi_adv_oref_user_data_insufficient)
            OrefDataSufficiency.LIMITED ->
                lines += rh.gs(ApsStrings.aimi_adv_oref_user_data_limited)
            OrefDataSufficiency.GOOD ->
                lines += rh.gs(ApsStrings.aimi_adv_oref_user_data_good)
        }
        lines += rh.gs(ApsStrings.aimi_adv_oref_user_priority, o.priority.name)
        when (o.personalMlStatus) {
            OrefPersonalMlStatus.OFF ->
                lines += rh.gs(ApsStrings.aimi_adv_oref_user_personal_off)
            OrefPersonalMlStatus.INSUFFICIENT_DATA ->
                lines += rh.gs(ApsStrings.aimi_adv_oref_user_personal_training)
            OrefPersonalMlStatus.TRAIN_FAILED ->
                lines += rh.gs(ApsStrings.aimi_adv_oref_user_personal_failed)
            // No number here on purpose. The personal score is not calibrated (see [OrefPersonalSignalGate]),
            // so showing it as a percentage would read as a risk figure that it is not.
            OrefPersonalMlStatus.TRAINED_AND_USED ->
                lines += rh.gs(ApsStrings.aimi_adv_oref_user_personal_uncalibrated)
        }
        return lines.joinToString("\n\n")
    }
}
