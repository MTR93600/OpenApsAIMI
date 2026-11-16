package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import kotlin.math.abs

/**
 * Normalizes the stored PK/PD learning pace (largest DIA and peak change per day).
 *
 * The fastest pace is FASTER (2 h/day DIA, 20 min/day peak), and it is the default. A stored value
 * above it (3.0 h/day was the key default before the pace chips) is capped to it. A pace the user
 * picked (SLOW, NORMAL, FAST) is kept.
 *
 * One exception, done once by [migrateOldWizardPaceOnce]: the old PK/PD wizard always saved the
 * NORMAL pace (0.5 h/day, 5 min/day) without asking. The owner wants faster learning, so exactly that
 * stored pair moves to FASTER once. Any other stored pace is kept.
 */
object PkpdLearningBounds {
    const val FASTER_MAX_DIA_CHANGE_PER_DAY_H = 2.0
    const val FASTER_MAX_PEAK_CHANGE_PER_DAY_MIN = 20.0

    fun coerceMaxDiaChangePerDayH(stored: Double): Double = stored.coerceAtMost(FASTER_MAX_DIA_CHANGE_PER_DAY_H)

    fun coerceMaxPeakChangePerDayMin(stored: Double): Double = stored.coerceAtMost(FASTER_MAX_PEAK_CHANGE_PER_DAY_MIN)

    /** The NORMAL pace that the old PK/PD wizard always saved. */
    const val OLD_WIZARD_MAX_DIA_CHANGE_PER_DAY_H = 0.5
    const val OLD_WIZARD_MAX_PEAK_CHANGE_PER_DAY_MIN = 5.0

    /**
     * One-shot migration of the learning pace, run at the start of a PK/PD tick. When the stored pace
     * is exactly the old wizard pair (0.5 h/day and 5 min/day), it becomes FASTER (2 h/day and
     * 20 min/day). Any other stored value, also when only one of the two matches, is kept. Nothing
     * stored means the defaults (FASTER) are in use, so nothing is written.
     *
     * After the first run `BooleanNonKey.OApsAIMIPkpdPaceMigrated` is set and this never looks again,
     * so a user who picks NORMAL after the update keeps it. The flag is exported (step D1) and an import
     * never clears it (import merges into the store). So:
     * - restoring an old backup on the same phone keeps the restored pace, also 0.5/5.0;
     * - a backup made after the update carries the flag, so a NORMAL pace picked on purpose stays
     *   NORMAL also on a new phone or a fresh install;
     * - a backup made before the update has no flag, so on a new phone the first tick moves its
     *   0.5/5.0 to FASTER.
     *
     * Changes dosing for the users it moves (the learner may move the DIA and peak four times faster).
     *
     * @return true when the pace was moved to FASTER.
     */
    fun migrateOldWizardPaceOnce(preferences: Preferences): Boolean {
        if (preferences.get(BooleanNonKey.OApsAIMIPkpdPaceMigrated)) return false
        val storedDia = preferences.getIfExists(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH)
        val storedPeak = preferences.getIfExists(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin)
        val isOldWizardPace = storedDia != null && storedPeak != null &&
            abs(storedDia - OLD_WIZARD_MAX_DIA_CHANGE_PER_DAY_H) < PACE_MATCH_TOLERANCE &&
            abs(storedPeak - OLD_WIZARD_MAX_PEAK_CHANGE_PER_DAY_MIN) < PACE_MATCH_TOLERANCE
        if (isOldWizardPace) {
            preferences.put(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, FASTER_MAX_DIA_CHANGE_PER_DAY_H)
            preferences.put(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, FASTER_MAX_PEAK_CHANGE_PER_DAY_MIN)
        }
        preferences.put(BooleanNonKey.OApsAIMIPkpdPaceMigrated, true)
        return isOldWizardPace
    }

    /** A stored double may come back with a tiny rounding error. */
    private const val PACE_MATCH_TOLERANCE = 1e-6
}
