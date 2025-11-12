package app.aaps.plugins.aps.openAPSAIMI.wcycle

class WCycleFacade(
    private val adjuster: WCycleAdjuster,
    private val logger: WCycleCsvLogger
) {
    fun infoAndLog(contextRow: Map<String, Any?>): WCycleInfo {
        val info = adjuster.getInfo()
        val ok = logger.append(
            contextRow + mapOf(
                "phase" to info.phase.name,
                "cycleDay" to info.dayInCycle,
                "basalBase" to info.baseBasalMultiplier,
                "smbBase" to info.baseSmbMultiplier,
                "basalLearn" to info.learnedBasalMultiplier,
                "smbLearn" to info.learnedSmbMultiplier,
                "basalApplied" to info.basalMultiplier,
                "smbApplied" to info.smbMultiplier,
                "applied" to info.applied,
                "reason" to info.reason
            )
        )
        return if (ok) info else info.copy(basalMultiplier = 1.0, smbMultiplier = 1.0, reason = info.reason + " | CSV FAIL")
    }
}
