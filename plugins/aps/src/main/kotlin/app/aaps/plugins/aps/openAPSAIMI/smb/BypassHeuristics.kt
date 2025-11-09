package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext

/**
 * Reprend fidèlement l’heuristique de ton patch :
 *  - bypass si mode repas
 *  - bypass si hyper hors repas et pente suffisante, IOB < maxSMB, pas de risque hypo
 */
object BypassHeuristics {

    fun computeBypass(ctx: LoopContext, hypoRisk: Boolean): Boolean {
        val mealModeRun = ctx.modes.meal || ctx.modes.breakfast || ctx.modes.lunch ||
            ctx.modes.dinner || ctx.modes.highCarb || ctx.modes.snack

        val delta = ctx.bg.delta5
        val combined = (ctx.bg.combinedDelta ?: 0.0)
        val highBgRiseActive =
            (ctx.bg.mgdl >= 150.0 && (delta >= 1.5 || combined >= 4.0)) &&
                (ctx.iobU < ctx.pump.maxSmb) &&
                !hypoRisk

        return mealModeRun || highBgRiseActive
    }
}
