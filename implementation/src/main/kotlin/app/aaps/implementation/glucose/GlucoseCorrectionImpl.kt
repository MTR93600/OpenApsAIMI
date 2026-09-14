package app.aaps.implementation.glucose

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.glucose.GlucoseCorrection
import app.aaps.core.interfaces.iob.IobCobCalculator
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.roundToInt

@Reusable
class GlucoseCorrectionImpl @Inject constructor(
    private val iobCobCalculator: IobCobCalculator
) : GlucoseCorrection {

    override fun correctedMgdl(timestamp: Long, storedMgdl: Double): Double? {
        if (storedMgdl <= 0.0) return null
        val corrected = correctedSeriesValueAt(timestamp) ?: return null
        // Plausibility net. It is not meant to limit calibration (a fit is already bounded by its own
        // slope and offset checks), only to make sure a broken corrected series can never send a wild
        // number to Nightscout, where a follower may dose on it, or silence a real hypo alarm.
        if (corrected < MIN_PLAUSIBLE_MGDL || corrected > MAX_PLAUSIBLE_MGDL) return null
        val ratio = corrected / storedMgdl
        if (ratio < MIN_PLAUSIBLE_RATIO || ratio > MAX_PLAUSIBLE_RATIO) return null
        // Whole mg/dL, like the stored reading and like the screen. Calibration, smoothing and the
        // interpolation below all return a plain Double, and before this class existed `sgv` was the
        // stored value, which is always whole: both sensors build it from an Int. So nothing on the
        // way out ever needed to round, and Nightscout was sent numbers such as 83.17856343415423
        // while the phone showed 83. The checks above run on the exact value; only the answer is
        // rounded, and the range makes the conversion safe.
        return corrected.roundToInt().toDouble()
    }

    /**
     * Value of the corrected (calibrated + smoothed) series at [timestamp].
     *
     * The series is ordered newest first and its points are five minutes apart, so a reading from a
     * sensor that speaks every minute usually falls between two points and is interpolated.
     */
    private fun correctedSeriesValueAt(timestamp: Long): Double? {
        val series: List<InMemoryGlucoseValue> = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        if (series.isEmpty()) return null
        // Newer than the newest corrected point: the series is rebuilt on every new reading but its
        // head sits on a five minute grid, so the last readings of a one minute sensor land here.
        // They take the newest corrected value, which is the number the dashboard shows right now.
        if (timestamp >= series.first().timestamp) return series.first().recalculated
        // Older than the whole series: there is nothing to map onto, keep the stored value.
        if (timestamp < series.last().timestamp) return null
        for (i in series.indices) {
            val older = series[i]
            if (older.timestamp > timestamp) continue
            if (older.timestamp == timestamp) return older.recalculated
            val newer = series[i - 1]
            val span = (newer.timestamp - older.timestamp).toDouble()
            if (span <= 0.0) return older.recalculated
            val part = (timestamp - older.timestamp) / span
            return older.recalculated + part * (newer.recalculated - older.recalculated)
        }
        return null
    }

    companion object {

        private const val MIN_PLAUSIBLE_MGDL = 39.0
        private const val MAX_PLAUSIBLE_MGDL = 400.0
        private const val MIN_PLAUSIBLE_RATIO = 0.5
        private const val MAX_PLAUSIBLE_RATIO = 2.0
    }
}
