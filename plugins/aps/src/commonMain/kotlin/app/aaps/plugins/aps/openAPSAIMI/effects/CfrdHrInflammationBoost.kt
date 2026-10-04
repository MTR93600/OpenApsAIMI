package app.aaps.plugins.aps.openAPSAIMI.effects

/**
 * Heart-rate inflammation boost used by T3C CFRD mode.
 *
 * The reference wraps the wearable snapshot read in `runCatching { ... }.getOrDefault(0.0)`,
 * which swallows every throwable and leaves no log. This function does not read the snapshot
 * and does not catch. The Android shell reads the snapshot, and on failure logs and passes
 * a boost of 0.0 so the dose stays the one the reference produced.
 *
 * Bands, copied from the reference: resting-corrected rise of 25 bpm or more → 0.35,
 * 15..24 → 0.20, 8..14 → 0.10, otherwise 0. A missing heart rate (either value ≤ 0) → 0.
 */
internal fun cfrdHrInflammationBoostOf(hrNow: Int, rhr: Int): Double {
    if (hrNow <= 0 || rhr <= 0) return 0.0
    val rise = (hrNow - rhr).coerceAtLeast(0)
    return when (rise) {
        in 25..Int.MAX_VALUE -> 0.35
        in 15..24 -> 0.20
        in 8..14 -> 0.10
        else -> 0.0
    }
}
