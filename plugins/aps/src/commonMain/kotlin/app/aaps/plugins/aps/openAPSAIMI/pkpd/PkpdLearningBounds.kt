package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Normalizes the stored PK/PD learning pace (largest DIA and peak change per day).
 *
 * The fastest pace is FASTER (2 h/day DIA, 20 min/day peak), and it is the default. A stored value
 * above it (3.0 h/day was the key default before the pace chips) is capped to it. A pace the user
 * picked (SLOW, NORMAL, FAST) is kept.
 */
object PkpdLearningBounds {
    const val FASTER_MAX_DIA_CHANGE_PER_DAY_H = 2.0
    const val FASTER_MAX_PEAK_CHANGE_PER_DAY_MIN = 20.0

    fun coerceMaxDiaChangePerDayH(stored: Double): Double = stored.coerceAtMost(FASTER_MAX_DIA_CHANGE_PER_DAY_H)

    fun coerceMaxPeakChangePerDayMin(stored: Double): Double = stored.coerceAtMost(FASTER_MAX_PEAK_CHANGE_PER_DAY_MIN)
}
