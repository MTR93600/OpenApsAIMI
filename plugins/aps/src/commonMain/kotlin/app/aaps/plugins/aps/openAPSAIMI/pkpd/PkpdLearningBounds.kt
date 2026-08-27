package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Normalizes stored PK/PD learning-rate prefs from the pre-simplified UI era.
 * Legacy defaults (3.0 h/day DIA, 20 min/day peak) were far above the Simple-tab pace chips.
 */
object PkpdLearningBounds {
    const val LEGACY_DIA_CHANGE_THRESHOLD_H = 1.2
    const val LEGACY_PEAK_CHANGE_THRESHOLD_MIN = 12.0
    const val NORMAL_MAX_DIA_CHANGE_PER_DAY_H = 0.5
    const val NORMAL_MAX_PEAK_CHANGE_PER_DAY_MIN = 5.0

    fun coerceMaxDiaChangePerDayH(stored: Double): Double =
        if (stored <= LEGACY_DIA_CHANGE_THRESHOLD_H) stored else NORMAL_MAX_DIA_CHANGE_PER_DAY_H

    fun coerceMaxPeakChangePerDayMin(stored: Double): Double =
        if (stored <= LEGACY_PEAK_CHANGE_THRESHOLD_MIN) stored else NORMAL_MAX_PEAK_CHANGE_PER_DAY_MIN
}
