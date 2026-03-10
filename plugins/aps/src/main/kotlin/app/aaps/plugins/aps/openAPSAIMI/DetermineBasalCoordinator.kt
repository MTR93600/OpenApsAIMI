package app.aaps.plugins.aps.openAPSAIMI

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
    private val smbActuator: SmbActuator
) {
    fun runOnce(ctx: LoopContext): Decision {
        val basalPlan = basalPlanner(ctx)

        val bypass = BypassHeuristics.computeBypass(ctx, hypoRisk(ctx))
        val smb = smbEngine.planSmb(ctx, bypass)
        val smbPlan = if (smb.units > 0.0) SmbPlan(smb.units, clock.now(), smb.reason) else null

        val safetyReport = safety.apply(ctx, basalPlan, smbPlan)
        basalPlan?.let { basalActuator.setTempBasal(it) }
        smbPlan?.let { smbActuator.deliver(it) }

        return Decision(basalPlan, smbPlan, safetyReport)
    }
}
