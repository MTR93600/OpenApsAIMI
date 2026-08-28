package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HRVDataMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.RHRDataMTR
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Infers a thermal-style delta timeline from Health Connect recovery vitals
 * (RHR + HRV) when Garmin / Oura do not export skin temperature to HC.
 */
internal object HcRecoveryProxyThermalSource {

    /** Maps ~5 bpm RHR elevation to ~0.25 °C warming proxy. */
    private const val RHR_BPM_TO_DELTA_C = 20.0

    fun build(
        rhrPoints: List<RHRDataMTR>,
        hrvPoints: List<HRVDataMTR>,
        daysBack: Int,
        nowMs: Long = aimiWallClockMs(),
    ): List<ThermalSampleMTR> {
        val validRhr = rhrPoints.filter { it.hasValidData() }
        if (validRhr.size < 2) return emptyList()

        val baselineRhr = medianInt(validRhr.map { it.bpm })
        val validHrv = hrvPoints.filter { it.hasValidData() }
        val baselineHrv = if (validHrv.size >= 2) validHrv.map { it.rmssd }.average() else null

        val zone = TimeZone.currentSystemDefault()
        val cutoffMs = nowMs - daysBack * 24L * 3_600_000L
        val byDay = validRhr
            .filter { it.timestamp >= cutoffMs }
            .groupBy { Instant.fromEpochMilliseconds(it.timestamp).toLocalDateTime(zone).date }

        val samples = mutableListOf<ThermalSampleMTR>()
        for ((date, points) in byDay.entries.sortedBy { it.key }) {
            val latest = points.maxByOrNull { it.timestamp } ?: continue
            val rhrProxy = (latest.bpm - baselineRhr) / RHR_BPM_TO_DELTA_C

            val dayStart = date.atStartOfDayIn(zone).toEpochMilliseconds()
            val dayEnd = date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
            val dayHrv = validHrv.filter { it.timestamp in dayStart until dayEnd }
            val hrvProxy = if (baselineHrv != null && dayHrv.isNotEmpty()) {
                val dayAvg = dayHrv.map { it.rmssd }.average()
                ((baselineHrv - dayAvg) / 40.0) * 0.35
            } else {
                0.0
            }

            val delta = (rhrProxy * 0.65 + hrvProxy * 0.35).coerceIn(-1.2, 1.2)
            val timestampMs = LocalDateTime(date, LocalTime(8, 0)).toInstant(zone).toEpochMilliseconds()
            samples += ThermalSampleMTR(
                timestampMs = timestampMs,
                deltaCelsius = delta,
                measurementLocation = "WRIST",
                dataOrigin = inferOriginLabel(points.map { it.source }),
            )
        }
        return samples.sortedBy { it.timestampMs }
    }

    private fun medianInt(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid].toDouble()
        }
    }

    private fun inferOriginLabel(sources: List<String>): String {
        val joined = sources.joinToString(" ").lowercase()
        return when {
            "garmin" in joined && "oura" in joined -> "${ThermalDataOrigins.HC_INFERRED}:Garmin+Oura"
            "garmin" in joined -> "${ThermalDataOrigins.HC_INFERRED}:Garmin"
            "oura" in joined -> "${ThermalDataOrigins.HC_INFERRED}:Oura"
            else -> ThermalDataOrigins.HC_INFERRED
        }
    }
}
