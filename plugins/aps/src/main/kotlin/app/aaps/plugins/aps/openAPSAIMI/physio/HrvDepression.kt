package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * How far below a person's own normal their heart rate variability is.
 *
 * This replaces three fixed bands that each compared the raw HRV against a fixed number
 * (`1.0..22.0`, `< 30`, `1.0..30.0`). Those numbers were set for RMSSD, and they were wrong
 * across people: someone whose own resting RMSSD is 25 sat inside the "stressed" band every
 * minute of every day, and someone whose normal is 90 never entered it at all.
 *
 * Measuring against the person's own rolling baseline fixes that. "Low" becomes low *for them*.
 */
object HrvDepression {

    /**
     * How far below the mean counts as fully depressed.
     *
     * Two standard deviations is the usual "clearly outside normal" mark. It is a starting point,
     * not a tuned value: the old bands were absolute, so there is no old figure to carry over.
     * Both the old and the new verdict are exported in [HealthContextSnapshot.toJSON] so the right
     * number can be chosen from real data later.
     */
    const val SIGMAS_FOR_FULL_DEPRESSION: Double = 2.0

    private const val NO_DEPRESSION = 0.0

    /**
     * `0.0` for normal, rising to `1.0` at [SIGMAS_FOR_FULL_DEPRESSION] below this person's mean.
     *
     * Answers `0.0` - the same as "no HRV at all" - in three cases where there is nothing to
     * compare against, because a guess would be worse than no answer:
     *
     * - no reading this tick,
     * - a baseline built from fewer than three samples,
     * - a baseline with no spread, which happens when every sample so far is the same.
     *
     * The dose path already treats an absent HRV as neutral, so a cold start behaves exactly as it
     * did before any HRV arrived.
     */
    fun of(hrv: Double, baseline: MetricBaselineMTR): Double {
        if (hrv <= 0.0) return NO_DEPRESSION
        if (!baseline.isValid()) return NO_DEPRESSION
        if (baseline.stdDev <= 0.0) return NO_DEPRESSION

        val sigmasBelowMean = (baseline.mean - hrv) / baseline.stdDev
        return (sigmasBelowMean / SIGMAS_FOR_FULL_DEPRESSION).coerceIn(0.0, 1.0)
    }
}
