package app.aaps.plugins.aps.openAPSAIMI.basal

/**
 * The activity basal ceiling: during a declared activity (a "sport" note or an AIMI Activity context)
 * the basal stays at or below profile × `OApsAIMIActivityBasalCapFactor`.
 *
 * Applied twice. Once early, where the basal paths merge (`capBasalRateForCorrectionAggression`), and
 * once at the very end of `setTempBasal`. The early one alone did not bound anything: the dynamic basal
 * multiplier (×0 to ×10) and the PKPD TBR boost run after it. Reported from the field: 4.0 U/h during a
 * SPORT_INTENSE context with a 1.3 U/h profile, where the ceiling was 1.69 U/h.
 *
 * Ceiling only: it never raises a rate and never touches a zero rate. The hyper-during-exercise
 * override (`ExerciseHyperOverridePolicy`) stays below it, at ×1.28 at most.
 */
object ActivityBasalCap {

    data class Result(val rateUph: Double, val capUph: Double?, val applied: Boolean)

    fun apply(rateUph: Double, profileBasalUph: Double, activityLockoutActive: Boolean, factor: Double): Result {
        if (!activityLockoutActive || profileBasalUph <= 0.0 || !factor.isFinite() || factor <= 0.0) {
            return Result(rateUph, capUph = null, applied = false)
        }
        val capUph = profileBasalUph * factor
        return if (rateUph > capUph) Result(capUph, capUph, applied = true) else Result(rateUph, capUph, applied = false)
    }
}
