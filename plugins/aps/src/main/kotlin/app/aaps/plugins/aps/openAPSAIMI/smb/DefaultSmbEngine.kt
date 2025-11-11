package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.ports.MlUamPort
import app.aaps.plugins.aps.openAPSAIMI.ports.PkpdPort
import app.aaps.plugins.aps.openAPSAIMI.smb.computeMealHighIobDecision
import kotlin.math.max
import kotlin.math.min

class DefaultSmbEngine(
    private val pkpd: PkpdPort,
    private val uam: MlUamPort
) : SmbEngine {

    override fun planSmb(ctx: LoopContext, bypassDamping: Boolean): SmbEngine.Plan {
        val snap = pkpd.snapshot(ctx)

        var smb = snap.smbProposalU ?: 0.0
        var reason = buildString {
            append("pkpd:ISF="); append("%.1f".format(snap.fusedIsf))
            append(", DIA="); append(snap.diaMin); append("m")
            append(", TP="); append(snap.peakMin); append("m")
            if (snap.smbProposalU != null) { append(", prop="); append("%.2f".format(snap.smbProposalU)); append("U") }
        }

        // Relax repas (inchangé)
        val mealActive = ctx.modes.meal || ctx.modes.breakfast || ctx.modes.lunch || ctx.modes.dinner || ctx.modes.highCarb || ctx.modes.snack
        if (mealActive) {
            val meal = computeMealHighIobDecision(
                mealModeActive = true,
                bg = ctx.bg.mgdl,
                delta = ctx.bg.delta5,
                eventualBg = ctx.eventualBg,
                targetBg = ctx.profile.targetMgdl,
                iob = ctx.iobU,
                maxIob = ctx.pump.maxBasal // si tu as un maxIOB spécifique, remplace ici
            )
            if (meal.relax) {
                smb *= meal.damping
                reason += ", mealRelax×" + "%.2f".format(meal.damping)
            }
        }

        // UAM delta
        val uamDelta = uam.predictSmbDelta(ctx)
        if (!uamDelta.isNaN()) {
            smb = max(0.0, smb + uamDelta)
            reason += ", uamΔ=" + "%.2f".format(uamDelta)
        }

        smb = SmbQuantizer.quantize(smb, step = ctx.pump.bolusStep, minU = 0.0, maxU = ctx.pump.maxSmb)
        reason += ", quant=" + "%.2f".format(smb) + "U (max=" + "%.2f".format(ctx.pump.maxSmb) + ")"

        // Damping PK/PD avec bypass (nouveau patch)
        val audit = pkpd.dampSmb(smb, ctx, bypassDamping)
        val finalU = max(0.0, audit.out)
        reason += if (audit.mealBypass) {
            ", damp→" + "%.2f".format(finalU) + " [BYPASS]"
        } else {
            ", damp→" + "%.2f".format(finalU) +
                " [tail×" + "%.2f".format(audit.tailMult) +
                ", ex×" + "%.2f".format(audit.exerciseMult) +
                ", fat×" + "%.2f".format(audit.lateFatMult) + "]"
        }

        pkpd.logCsv(ctx, snap, smbProposed = smb, smbFinal = finalU, audit = audit)

        return SmbEngine.Plan(units = finalU, reason = reason)
    }
}
