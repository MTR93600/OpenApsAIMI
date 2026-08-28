package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.PreferenceKey

object TuningPreferenceLabels {

    fun shortLabel(key: PreferenceKey): String =
        when (key) {
            DoubleKey.OApsAIMIMaxSMB -> "Max SMB"
            DoubleKey.OApsAIMIHighBGMaxSMB -> "High BG Max SMB"
            DoubleKey.OApsAIMILunchFactor -> "Lunch factor"
            DoubleKey.OApsAIMIDinnerFactor -> "Dinner factor"
            DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor -> "PKPD relief min factor"
            DoubleKey.OApsAIMIRedCarpetRestoreThreshold -> "Red Carpet restore"
            DoubleKey.OApsAIMIPriorityMaxIobFactor -> "Priority MaxIOB factor"
            DoubleKey.OApsAIMIPriorityMaxIobExtraU -> "Priority MaxIOB extra U"
            DoubleKey.OApsAIMISmbTailDamping -> "SMB tail damping"
            DoubleKey.AimiTubeAggressiveness -> "Tube aggressiveness"
            DoubleKey.AimiTubeHypoFloorMgdl -> "Tube hypo floor"
            BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled -> "PKPD pragmatic relief"
            BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled -> "Straight-line tube"
            else -> key.key
        }

    fun formatValue(value: Any): String = when (value) {
        is Double -> NumberFormat.DECIMAL_3.format(value, NumberFormatPlatform.SEPARATOR_DOT)
        is Boolean -> if (value) "on" else "off"
        else -> value.toString()
    }
}

object TuningContextFormatSupport {

    fun formatChangeLine(change: TuningChange): String {
        val label = TuningPreferenceLabels.shortLabel(change.key)
        val oldS = TuningPreferenceLabels.formatValue(change.oldValue)
        val newS = TuningPreferenceLabels.formatValue(change.newValue)
        return "$label: $oldS → $newS (${change.tier.name.lowercase()} step)"
    }

    private fun formatEffectiveContext(context: AimiTuningContext): String = when (context) {
        AimiTuningContext.MEAL_RISE -> "Meal rise"
        AimiTuningContext.HYPO_GUARD -> "Hypo guard"
        AimiTuningContext.HYPER_STABLE -> "Hyper control"
        AimiTuningContext.MIXED_BALANCE -> "Mixed (hypo + hyper)"
        AimiTuningContext.AUTO_BALANCE -> "Auto"
    }

    fun formatPlanPreview(plan: TuningPlan): String {
        val sb = StringBuilder()
        sb.append("Effective context: ${formatEffectiveContext(plan.effectiveContext)}\n")
        if (plan.blockedReason != null) {
            sb.append(plan.blockedReason)
            return sb.toString()
        }
        if (plan.changes.isEmpty()) {
            sb.append("No parameter changes — values already match this context.")
            return sb.toString()
        }
        plan.changes.forEach { sb.append(formatChangeLine(it)).append('\n') }
        return sb.toString().trimEnd()
    }
}
