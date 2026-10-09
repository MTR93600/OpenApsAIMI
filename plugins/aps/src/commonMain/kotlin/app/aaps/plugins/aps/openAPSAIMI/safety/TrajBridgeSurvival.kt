package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Lets the trajectory bridge's basal reduction survive the basal schedule.
 *
 * ## Why this exists
 *
 * The bridge writes a lower rate into `rT.rate` early in the tick, and the whole basal schedule runs
 * after it and writes its own rate over the request. Measured on 2026-10-02 21:14: the bridge asked
 * **0.13 U/h** (0.528 x a 0.25 demand fraction, tag `STACKING_SPIRAL`) and the pump received
 * **4.84 U/h**, 37 times more. Over the 34 bridge ticks of that package, 30 ended far above the
 * request; the 4 that ended at zero were zeroed by a hypoglycaemia guard, not by the bridge.
 *
 * ## What it is, and is not
 *
 * It is pure: no clock, no preference, no state. The caller decides whether the opt-in key is armed
 * and passes the answer in. It can only ever LOWER a rate — [Decision.rateUph] is never above
 * `scheduledUph`. A request at or above the scheduled rate changes nothing, a missing request changes
 * nothing, and a non-finite number changes nothing.
 *
 * [Decision.wouldReduceToUph] is filled whether or not the key is armed, so a support package can be
 * used to count how often this bites, and by how much, before anyone turns it on.
 */
object TrajBridgeSurvival {

    /**
     * @param rateUph what the rest of the tick must use. Equal to `scheduledUph` unless the key was
     *   armed AND the bridge really asked for less.
     * @param wouldReduceToUph the rate an armed key would have produced, or null when the bridge asked
     *   for nothing usable or asked for more than the schedule. Observation only.
     * @param survived true only when [rateUph] is below `scheduledUph`, i.e. the reduction was really
     *   applied on this tick.
     */
    data class Decision(
        val rateUph: Double,
        val wouldReduceToUph: Double?,
        val survived: Boolean,
    )

    /**
     * @param scheduledUph the rate the basal schedule produced, after every stage that can raise it.
     * @param requestedUph what the bridge asked for earlier in the tick, or null if it never fired.
     * @param keyArmed the state of `BooleanKey.OApsAIMITrajBridgeBasalSurvives`.
     */
    fun resolve(scheduledUph: Double, requestedUph: Double?, keyArmed: Boolean): Decision {
        if (!scheduledUph.isFinite()) return Decision(scheduledUph, null, survived = false)
        val request = requestedUph?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return Decision(scheduledUph, null, survived = false)
        if (request >= scheduledUph) return Decision(scheduledUph, null, survived = false)
        if (!keyArmed) return Decision(scheduledUph, request, survived = false)
        return Decision(request, request, survived = true)
    }
}
