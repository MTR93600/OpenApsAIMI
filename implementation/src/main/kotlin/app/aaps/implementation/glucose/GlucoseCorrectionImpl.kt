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
        if (!storedMgdl.isFinite() || storedMgdl <= 0.0) return null
        return correctedFromSeries(iobCobCalculator.ads.getBucketedDataTableCopy(), timestamp, storedMgdl)
    }

    companion object {

        /** A reading newer than the series head may use the head's correction only this much later. */
        internal const val MAX_HEAD_AGE_MS = 5 * 60 * 1000L

        private const val MIN_PLAUSIBLE_MGDL = 39.0
        private const val MAX_PLAUSIBLE_MGDL = 400.0
        private const val MIN_PLAUSIBLE_RATIO = 0.5
        private const val MAX_PLAUSIBLE_RATIO = 2.0

        /**
         * The corrected value for a stored reading, or null to keep the stored value.
         *
         * Pure, so the rules are easy to test. [series] is the corrected (calibrated + smoothed)
         * series, newest first.
         *
         * A value that is not a finite number (NaN or infinite), stored or in the series, keeps the
         * stored value. NaN passes every `<` and `>` check below, and rounding it throws, which would
         * make every glucose alarm check and the Nightscout upload fail.
         */
        internal fun correctedFromSeries(series: List<InMemoryGlucoseValue>?, timestamp: Long, storedMgdl: Double): Double? {
            if (!storedMgdl.isFinite() || storedMgdl <= 0.0) return null
            val corrected = correctedSeriesValueAt(series, timestamp, storedMgdl) ?: return null
            if (!corrected.isFinite()) return null
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
         * Value of the corrected series at [timestamp].
         *
         * The series is ordered newest first and its points are five minutes apart, so a reading from a
         * sensor that sends a value every minute usually falls between two points and is interpolated.
         */
        private fun correctedSeriesValueAt(series: List<InMemoryGlucoseValue>?, timestamp: Long, storedMgdl: Double): Double? {
            if (series.isNullOrEmpty()) return null
            val head = series.first()
            if (timestamp == head.timestamp) return head.recalculated.finiteOrNull()
            if (timestamp > head.timestamp) {
                // Newer than the newest corrected point: the series head sits on a five minute grid, so
                // the last readings of a one minute sensor land here. They keep their own stored value
                // and get the head's correction added as an offset.
                //
                // It used to return the head's value itself, however old the head was. A fresh low
                // reading of 50 then took the value 100 of an old head (the ratio 2.0 still passed), so
                // the low alarm compared 100 and stayed quiet, and Nightscout got the previous reading.
                // A head older than MAX_HEAD_AGE_MS says nothing about this reading: keep the stored value.
                if (timestamp - head.timestamp > MAX_HEAD_AGE_MS) return null
                val headCorrected = head.recalculated.finiteOrNull() ?: return null
                val headStored = head.value.finiteOrNull() ?: return null
                return storedMgdl + (headCorrected - headStored)
            }
            // Older than the whole series: there is nothing to map onto, keep the stored value.
            if (timestamp < series.last().timestamp) return null
            for (i in series.indices) {
                val older = series[i]
                if (older.timestamp > timestamp) continue
                val olderValue = older.recalculated.finiteOrNull() ?: return null
                if (older.timestamp == timestamp) return olderValue
                val newer = series[i - 1]
                val span = (newer.timestamp - older.timestamp).toDouble()
                if (span <= 0.0) return olderValue
                val newerValue = newer.recalculated.finiteOrNull() ?: return null
                val part = (timestamp - older.timestamp) / span
                return olderValue + part * (newerValue - olderValue)
            }
            return null
        }

        private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }
    }
}
