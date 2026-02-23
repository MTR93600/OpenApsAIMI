package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

import java.util.Locale

/**
 * Generates short, explainable logs for the Thyroid module.
 */
object ThyroidDiagnosticsLogger {

    fun formatDecisionLog(
        inputs: ThyroidInputs,
        status: ThyroidStatus,
        effects: ThyroidEffects,
        confidence: Double,
        direction: String,
        reason: String
    ): String {
        if (!inputs.isEnabled || status == ThyroidStatus.EUTHYROID) return ""

        val effectStr = buildString {
            if (effects.diaMultiplier != 1.0) append(String.format(Locale.US, "dia*%.2f ", effects.diaMultiplier))
            if (effects.egpMultiplier != 1.0) append(String.format(Locale.US, "egp*%.2f ", effects.egpMultiplier))
            if (effects.carbRateMultiplier != 1.0) append(String.format(Locale.US, "cAbs*%.2f ", effects.carbRateMultiplier))
            if (effects.isfMultiplier != 1.0) append(String.format(Locale.US, "isf*%.2f ", effects.isfMultiplier))
        }.trim()

        val safetyStr = buildString {
            if (effects.blockSmb) append("blockSMB ")
            if (effects.smbCapUnits != null) append(String.format(Locale.US, "smbCap=%.2fU ", effects.smbCapUnits))
            if (effects.basalCapMultiplier != 1.5) append(String.format(Locale.US, "basalCap=*%.2f ", effects.basalCapMultiplier))
        }.trim()

        val parts = mutableListOf<String>()
        parts.add("thyroid=${status.name}")
        if (confidence < 1.0) parts.add(String.format(Locale.US, "conf=%.2f", confidence))
        if (direction.isNotBlank()) parts.add("dir=$direction")
        if (effectStr.isNotBlank()) parts.add("eff:[$effectStr]")
        if (safetyStr.isNotBlank()) parts.add("guard:[$safetyStr]")
        if (reason.isNotBlank()) parts.add("rsn:[$reason]")

        return parts.joinToString(" ")
    }
}
