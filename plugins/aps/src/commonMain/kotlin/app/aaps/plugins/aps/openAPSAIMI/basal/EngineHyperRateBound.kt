package app.aaps.plugins.aps.openAPSAIMI.basal

/**
 * Bound for the two engine branches that have none: "Slow rise" and "Eventual BG>110".
 *
 * "Slow rise" multiplies the final basal by `combinedDelta`, with no limit (199 U/h on 2026-10-20).
 * "Eventual BG>110" multiplies a final basal that already holds one glucose curve by a second glucose
 * factor (up to 37.5 U/h). max_basal bounds both later, but they still win the `max` merge with the
 * overlay. The bound keeps one glucose factor only: `profile basal × interpolateBasal(bg)` (0.5–5).
 *
 * Shadow first: exported on every tick these branches run, applied only when
 * `BooleanKey.OApsAIMIEngineHyperRateBound` is on (off by default).
 */
object EngineHyperRateBound {

    enum class Branch { SLOW_RISE, EVENTUAL_BG_110 }

    data class Verdict(
        val branch: Branch,
        val rawUph: Double,
        val boundUph: Double,
        /** The raw rate is above the bound (armed or not). */
        val wouldBind: Boolean,
        val applied: Boolean,
        /** Rate to use. */
        val rateOutUph: Double,
    )

    /** [glucoseFactor] is the engine's `interpolateBasal(bg)`, passed in so the curve exists only once. */
    fun evaluate(branch: Branch, rawUph: Double, profileBasalUph: Double, glucoseFactor: Double, armed: Boolean): Verdict {
        val bound = profileBasalUph * glucoseFactor
        val wouldBind = rawUph.isFinite() && profileBasalUph > 0.0 && bound.isFinite() && rawUph > bound
        val applied = wouldBind && armed
        return Verdict(branch, rawUph, bound, wouldBind, applied, if (applied) bound else rawUph)
    }
}
