package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.TextResolver

/**
 * KMP-safe access to the advisor strings that live in Android resources
 * (`R.string.aimi_advisor_*`, `R.string.aimi_adv_*`).
 *
 * `R` and `ResourceHelper` only exist on Android, so commonMain code cannot name them.
 * Each property below maps 1:1 to one resource name (camelCased), and the service keeps
 * using the English literal as the fallback when no implementation is provided - exactly
 * the text the resource carries, so behavior is unchanged with `null`.
 *
 * This extends [TextResolver] because the service also resolves [app.aaps.core.keys.interfaces.TextRef]
 * values that do not live in this key set: [PkpdAdvisor.analysePkpd],
 * [metricRecommendations] and `OrefUserInsightFormatter.buildParagraph` all take a
 * [TextResolver]. One nullable dependency covers both paths, and the Android
 * implementation delegates the [TextResolver] part to the real `ResourceHelper`,
 * so localization keeps working there with no behavior change.
 */
interface AimiAdvisorStrings : TextResolver {

    val scoreLabelExcellent: String
    val scoreLabelGood: String
    val scoreLabelWarning: String
    val scoreLabelAttention: String
    val scoreLabelCritical: String

    val analysisIntroExcellent: String
    val analysisIntroGood: String
    val analysisIntroPoor: String
    val analysisIssuesHeader: String
    val analysisAllGood: String

    /**
     * Raw Android format template, e.g. "Generated on %1$s - OpenAPS AIMI".
     * Substitute the argument with [app.aaps.core.interfaces.resources.formatTemplate],
     * which is the KMP-safe equivalent of `Resources.getString(id, args)`.
     */
    val generatedFooter: String

    val basalStrategyNoProfile: String
    val basalStrategySafetyReduction: String
    val basalStrategyGentleIncrease: String
    val basalStrategyHoldBaseline: String
    val basalRationaleSafetyReduction: String
    val basalRationaleGentleIncrease: String
    val basalRationaleHoldBaseline: String
    val basalExportHeaderTitle: String
}
