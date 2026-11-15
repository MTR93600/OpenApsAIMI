package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * DIA and peak of the insulin in the running profile (`iCfg.dia`, `iCfg.peak`).
 *
 * This is the one source of the PK/PD learner: it starts from these values, it is pulled back
 * toward them, and it stays inside [PkpdProfileInsulinBounds] around them.
 */
data class ProfileInsulin(val diaHours: Double, val peakMinutes: Double) {

    companion object {

        /** `null` when one of the values is not a usable positive number. */
        fun of(diaHours: Double, peakMinutes: Double): ProfileInsulin? =
            if (diaHours.isFinite() && diaHours > 0.0 && peakMinutes.isFinite() && peakMinutes > 0.0) {
                ProfileInsulin(diaHours, peakMinutes)
            } else {
                null
            }
    }
}

/**
 * Bounds of the learned DIA and peak, around the profile insulin.
 *
 * - DIA: 0.75 x to 1.25 x the profile DIA.
 * - Peak: `max(35, 0.75 x profile peak)` to 1.35 x the profile peak. 35 min is the hard limit of
 *   the insulin editor, and a peak of 35 min is valid (a modified insulin can peak near 28 min).
 *
 * Only the learner uses these bounds. The DIA and peak governors keep their fixed envelope
 * (`InsulinKineticsAuthority.GOVERNOR_*`).
 */
object PkpdProfileInsulinBounds {

    const val DIA_LOW_FACTOR = 0.75
    const val DIA_HIGH_FACTOR = 1.25
    const val PEAK_LOW_FACTOR = 0.75
    const val PEAK_HIGH_FACTOR = 1.35

    /** Hard lower limit of the insulin editor, in minutes. */
    const val PEAK_FLOOR_MIN = 35.0

    fun diaMinH(profile: ProfileInsulin): Double = profile.diaHours * DIA_LOW_FACTOR

    fun diaMaxH(profile: ProfileInsulin): Double = profile.diaHours * DIA_HIGH_FACTOR

    fun peakMinMin(profile: ProfileInsulin): Double = max(PEAK_FLOOR_MIN, profile.peakMinutes * PEAK_LOW_FACTOR)

    /** Never below [peakMinMin], so a profile peak under 35 min still gives a valid range. */
    fun peakMinMax(profile: ProfileInsulin): Double = max(peakMinMin(profile), profile.peakMinutes * PEAK_HIGH_FACTOR)

    fun bounds(profile: ProfileInsulin, maxDiaChangePerDayH: Double, maxPeakChangePerDayMin: Double): PkPdBounds =
        PkPdBounds(
            diaMinH = diaMinH(profile),
            diaMaxH = diaMaxH(profile),
            peakMinMin = peakMinMin(profile),
            peakMinMax = peakMinMax(profile),
            maxDiaChangePerDayH = maxDiaChangePerDayH,
            maxPeakChangePerDayMin = maxPeakChangePerDayMin,
        ).normalized()

    /**
     * The profile insulin inside its own bounds. It is the seed of the learner and the anchor it is
     * pulled back to. It equals the profile values, except a profile peak below 35 min, which becomes 35.
     */
    fun seed(profile: ProfileInsulin): PkPdParams =
        PkPdParams(
            diaHrs = profile.diaHours.coerceIn(diaMinH(profile), diaMaxH(profile)),
            peakMin = profile.peakMinutes.coerceIn(peakMinMin(profile), peakMinMax(profile)),
        )
}

/**
 * The profile insulin the learner last started from, kept in [LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin]
 * and [LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin] (whole minutes). Internal state, not a setting.
 *
 * `PkPdIntegration` compares it with the running profile insulin on every tick. When they differ,
 * the learned state starts again from the profile values.
 */
object PkpdProfileInsulinMemory {

    /** Nothing stored yet: the first start after the update. The learned state is kept and clamped. */
    const val NEVER_SEEN = 0L

    /** The user asked for a reset. The next tick starts again from the profile insulin. */
    const val RESET_REQUESTED = -1L

    /** The stored form of [profile]: DIA and peak in whole minutes. */
    fun storedMinutes(profile: ProfileInsulin): Pair<Long, Long> =
        (profile.diaHours * 60.0).roundToLong() to profile.peakMinutes.roundToLong()

    /** The last profile insulin seen, or `null` when there is none (never seen, or reset asked). */
    fun lastSeen(preferences: Preferences): ProfileInsulin? {
        val diaMin = preferences.get(LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin)
        val peakMin = preferences.get(LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin)
        if (diaMin <= 0L || peakMin <= 0L) return null
        return ProfileInsulin.of(diaMin / 60.0, peakMin.toDouble())
    }

    fun remember(preferences: Preferences, profile: ProfileInsulin) {
        val (diaMin, peakMin) = storedMinutes(profile)
        preferences.put(LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin, diaMin)
        preferences.put(LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin, peakMin)
    }

    /**
     * Asks the loop to start the learned DIA and peak again from the profile insulin. It takes
     * effect on the next loop tick, with PK/PD on or off. The learned values shown on screen change then.
     */
    fun requestReset(preferences: Preferences) {
        preferences.put(LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin, RESET_REQUESTED)
        preferences.put(LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin, RESET_REQUESTED)
    }
}
