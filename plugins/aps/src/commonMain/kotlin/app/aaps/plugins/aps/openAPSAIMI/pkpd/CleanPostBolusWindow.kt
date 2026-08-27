package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Predicate for a **clean post-bolus** context where trajectory-based peak nudges are allowed (RFC A.3).
 *
 * Same numeric gates as [TrajectoryPeakBias] v1; CGM gap rules can extend this object later.
 */
object CleanPostBolusWindow {

    const val MIN_MINUTES_SINCE_BOLUS = 30
    const val MAX_MINUTES_SINCE_BOLUS = 90
    const val MAX_COB_GRAMS = 12.0

    /** Max gap between consecutive CGM samples (minutes) — reserved for future trajectory-ID use. */
    const val MAX_CGM_GAP_MINUTES = 15

    fun allowsTrajectoryPeakNudge(
        minutesSinceLastBolus: Int,
        cobGrams: Double,
    ): Boolean =
        minutesSinceLastBolus in MIN_MINUTES_SINCE_BOLUS..MAX_MINUTES_SINCE_BOLUS &&
            cobGrams <= MAX_COB_GRAMS
}
