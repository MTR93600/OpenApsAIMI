package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * How far below a person's own normal their heart rate variability is.
 *
 * This replaces three hardcoded bands that each compared the raw HRV against a fixed number
 * (`1.0..22.0`, `< 30`, `1.0..30.0`). Those numbers were calibrated for RMSSD, and they were wrong
 * twice over:
 *
 * - **Across platforms.** Health Connect hands over RMSSD, HealthKit only ever hands over SDNN,
 *   because that is the single HRV type Apple defines. The same wearable writes a different metric
 *   depending on which phone it is paired with, so a fixed band reads one of the two wrongly.
 * - **Across people.** Someone whose own resting RMSSD is 25 sat inside the "stressed" band every
 *   minute of every day, and someone whose normal is 90 never entered it at all.
 *
 * Measuring against the person's own rolling baseline fixes both. "Low" becomes low *for them*, in
 * whichever metric their history happens to be kept, so the metric identity stops mattering
 * downstream - as long as one person's history is never a mix of the two. See [HrvMetric].
 */
object HrvDepression {

    /**
     * How far below the mean counts as fully depressed.
     *
     * Two standard deviations is the conventional "clearly outside normal" mark, and it is a
     * starting point rather than a tuned value: the old bands were absolute, so there is no
     * previous figure to carry over. Both the old and the new verdict are exported on every tick
     * so the right number can be chosen from real data later instead of guessed now.
     */
    const val SIGMAS_FOR_FULL_DEPRESSION: Double = 2.0

    /** Fewer samples than the baseline needs to mean anything. Matches `MetricBaselineMTR.isValid`. */
    private const val NO_DEPRESSION = 0.0

    /**
     * `0.0` for normal, rising to `1.0` at [SIGMAS_FOR_FULL_DEPRESSION] below this person's mean.
     *
     * Answers `0.0` - the same as "no HRV at all" - in three cases where there is nothing to
     * compare against, because guessing would be worse than saying nothing:
     *
     * - no reading this tick,
     * - a baseline built from fewer than three days,
     * - a baseline with no spread, which happens when every sample so far is identical.
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
