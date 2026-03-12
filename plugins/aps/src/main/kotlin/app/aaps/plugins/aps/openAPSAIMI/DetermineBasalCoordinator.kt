package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.model.*
import app.aaps.plugins.aps.openAPSAIMI.ports.*
import app.aaps.plugins.aps.openAPSAIMI.smb.BypassHeuristics
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbEngine
import timber.log.Timber

class DetermineBasalCoordinator(
    private val clock: Clock,
    private val basalPlanner: (LoopContext) -> BasalPlan?,  // adapter minces
    private val hypoRisk: (LoopContext) -> Boolean,         // garde existante
    private val smbEngine: SmbEngine,
    private val safety: SafetyGuards,
    private val basalActuator: BasalActuator,
    private val smbActuator: SmbActuator
) {
    fun runOnce(ctx: LoopContext): Decision {
        Timber.d("Starting DetermineBasalCoordinator.runOnce() for context timestamp: ${ctx.timestampMs}")

        val basalPlan = basalPlanner(ctx)
        Timber.d("Basal plan computed: ${basalPlan?.let { "Present" } ?: "Null"}")

        val bypass = BypassHeuristics.computeBypass(ctx, hypoRisk(ctx))
        Timber.d("Bypass computation completed with reason: ${bypass.reason}")

        val smb = smbEngine.planSmb(ctx, bypass)
        val smbPlan = if (smb.units > 0.0) SmbPlan(smb.units, clock.now(), smb.reason) else null
        Timber.d("SMB plan computed: ${smbPlan?.let { "Present" } ?: "Null"}")

        val safetyReport = safety.apply(ctx, basalPlan, smbPlan)
        Timber.d("Safety check completed with report: ${safetyReport?.let { "Present" } ?: "Null"}")

        basalPlan?.let { 
            Timber.d("Setting temp basal: ${it.basalRate} U/h")
            basalActuator.setTempBasal(it) 
        }
        
        smbPlan?.let { 
            Timber.d("Delivering SMB: ${it.units} U")
            smbActuator.deliver(it) 
        }

        Timber.d("DetermineBasalCoordinator.runOnce() completed successfully")
        return Decision(basalPlan, smbPlan, safetyReport)
    }
}
