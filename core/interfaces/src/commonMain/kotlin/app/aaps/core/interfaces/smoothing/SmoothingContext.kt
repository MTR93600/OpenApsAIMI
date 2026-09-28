package app.aaps.core.interfaces.smoothing

/**
 * Optional hints for [Smoothing.smooth] so callers can avoid redundant work
 * (e.g. IOB already computed outside a critical section).
 */
data class SmoothingContext(
    /**
     * Total IOB from bolus + temp basals (converted extended included), in insulin units.
     * When non-null, adaptive smoothing can use this for compression heuristics without calling
     * [app.aaps.core.interfaces.iob.IobCobCalculator] inside [Smoothing.smooth].
     */
    val cachedTotalIobUnits: Double? = null
) {

    companion object {

        val NONE = SmoothingContext()
    }
}
