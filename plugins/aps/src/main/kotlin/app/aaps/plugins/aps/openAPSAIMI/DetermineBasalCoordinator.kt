package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.model.*
import app.aaps.plugins.aps.openAPSAIMI.ports.*
import app.aaps.plugins.aps.openAPSAIMI.smb.BypassHeuristics
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbEngine

class DetermineBasalCoordinator(
    private val clock: Clock,
    private val basalPlanner: (LoopContext) -> BasalPlan?,  // adapter minces
    private val hypoRisk: (LoopContext) -> Boolean,         // garde existante
    private val smbEngine: SmbEngine,
    private val safety: SafetyGuards,
    private val basalActuator: BasalActuator,
    private val smbActuator: SmbActuator,
    private val aapsLogger: AAPSLogger
) {
    fun runOnce(ctx: LoopContext): Decision {
        aapsLogger.debug(LTag.APS, "Starting DetermineBasalCoordinator.runOnce() for context timestamp: ${ctx.nowEpochMillis}")

        val basalPlan = basalPlanner(ctx)
        aapsLogger.debug(LTag.APS, "Basal plan computed: ${basalPlan?.let { "Present" } ?: "Null"}")

        val bypass = BypassHeuristics.computeBypass(ctx, hypoRisk(ctx))
        aapsLogger.debug(LTag.APS, "Bypass computation completed: $bypass")

        val smb = smbEngine.planSmb(ctx, bypass)
        val smbPlan = if (smb.units > 0.0) SmbPlan(smb.units, clock.now(), smb.reason) else null
        aapsLogger.debug(LTag.APS, "SMB plan computed: ${smbPlan?.let { "Present" } ?: "Null"}")

        val safetyReport = safety.apply(ctx, basalPlan, smbPlan)
        aapsLogger.debug(LTag.APS, "Safety check completed with report: ${safetyReport?.let { "Present" } ?: "Null"}")

        basalPlan?.let { 
            aapsLogger.debug(LTag.APS, "Setting temp basal: ${it.rateUph} U/h")
            basalActuator.setTempBasal(it) 
        }
        
        smbPlan?.let { 
            aapsLogger.debug(LTag.APS, "Delivering SMB: ${it.units} U")
            smbActuator.deliver(it) 
        }

        aapsLogger.debug(LTag.APS, "DetermineBasalCoordinator.runOnce() completed successfully")
        return Decision(basalPlan, smbPlan, safetyReport)
    }
}
