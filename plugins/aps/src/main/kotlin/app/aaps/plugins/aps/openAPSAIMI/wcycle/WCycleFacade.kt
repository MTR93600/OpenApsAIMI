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
                // NEW: accepte si le contextRow les fournit (sinon vide)
                "needBasalScale" to contextRow["needBasalScale"],
                "needSmbScale" to contextRow["needSmbScale"],
                "applied" to info.applied,
                "reason" to info.reason
            )
        )
        return if (ok) info else info.copy(basalMultiplier = 1.0, smbMultiplier = 1.0, reason = info.reason + " | CSV FAIL")
    }
    fun updateLearning(phase: CyclePhase, needScale: Double) {
        // 🔮 FCL 11.0: Active Learning Loop
        // We feed the same ratio to Basal and SMB for now (Simplification V1)
        // In V2 we could differentiate if resistance is Basal-only or Meal-only
        adjuster.listenerUpdate(phase, needScale, needScale)
    }
    
    fun getPhase(): CyclePhase {
        return adjuster.getInfo().phase
    }
}
