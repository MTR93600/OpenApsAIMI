package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

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
            if (effects.diaMultiplier != 1.0) append("dia*${aimiFmt2(effects.diaMultiplier)} ")
            if (effects.egpMultiplier != 1.0) append("egp*${aimiFmt2(effects.egpMultiplier)} ")
            if (effects.carbRateMultiplier != 1.0) append("cAbs*${aimiFmt2(effects.carbRateMultiplier)} ")
            if (effects.isfMultiplier != 1.0) append("isf*${aimiFmt2(effects.isfMultiplier)} ")
        }.trim()

        val safetyStr = buildString {
            if (effects.blockSmb) append("blockSMB ")
            if (effects.smbCapUnits != null) append("smbCap=${aimiFmt2(effects.smbCapUnits)}U ")
            if (effects.basalCapMultiplier != 1.5) append("basalCap=*${aimiFmt2(effects.basalCapMultiplier)} ")
        }.trim()

        val parts = mutableListOf<String>()
        parts.add("thyroid=${status.name}")
        if (confidence < 1.0) parts.add("conf=${aimiFmt2(confidence)}")
        if (direction.isNotBlank()) parts.add("dir=$direction")
        if (effectStr.isNotBlank()) parts.add("eff:[$effectStr]")
        if (safetyStr.isNotBlank()) parts.add("guard:[$safetyStr]")
        if (reason.isNotBlank()) parts.add("rsn:[$reason]")

        return parts.joinToString(" ")
    }
}
