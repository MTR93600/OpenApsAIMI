package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Insulin end time and peak time, in milliseconds, that the prediction IOB array uses when it is
 * built on the learned PK/PD kinetics (`OpenAPSAIMIPlugin.buildLearnedKineticsIobArray`).
 *
 * This was behind the key `OApsAIMIPkpdPredictionKinetics` (default on) until step P3. It is always
 * on now, so only the checks on the learned values below can make the caller fall back to the
 * profile array.
 */
data class LearnedPredictionKinetics(val insulinEndTimeMs: Long, val insulinPeakTimeMs: Long) {

    companion object {

        /**
         * The learned DIA and peak as times for the prediction array, or null when the caller must use
         * the profile array: a value is missing or not finite, the DIA is outside 3-12 h, the peak is
         * outside 20-240 min, or the peak is not below half the DIA (the exponential insulin model is
         * not valid there).
         */
        fun fromLearned(learnedDiaHrs: Double?, learnedPeakMin: Double?): LearnedPredictionKinetics? {
            val diaHrs = learnedDiaHrs?.takeIf { it.isFinite() && it in 3.0..12.0 } ?: return null
            val peakMin = learnedPeakMin?.takeIf { it.isFinite() && it in 20.0..240.0 } ?: return null
            val endMs = (diaHrs * 3_600_000.0).toLong()
            val peakMs = (peakMin * 60_000.0).toLong()
            if (peakMs * 2 >= endMs) return null
            return LearnedPredictionKinetics(insulinEndTimeMs = endMs, insulinPeakTimeMs = peakMs)
        }
    }
}
