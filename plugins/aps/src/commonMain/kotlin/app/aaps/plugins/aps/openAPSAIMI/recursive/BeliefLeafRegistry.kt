package app.aaps.plugins.aps.openAPSAIMI.recursive

/**
 * Registry entry point — delegates to [BeliefLeafAdapterRegistry] (§15.10).
 */
object BeliefLeafRegistry {

    fun collect(tauMin: Int, ctx: RecursiveBeliefTickContext, includeShadow: Boolean): List<BeliefLeafReading> =
        BeliefLeafAdapterRegistry.collect(tauMin, ctx, includeShadow)
}
