package app.aaps.plugins.aps.openAPSAIMI.ISF

import android.content.Context
import java.util.Calendar

/**
 * Keeps the closed windows of [ObservedSensitivityMeter] in a small preferences file, so that the
 * measured sensitivity is still there after the app restarts. Only the Assistant screen reads it.
 * No dosing code may read it, the same rule as for the meter.
 */
object ObservedIsfStore {

    private const val PREFS = "aimi_isf_obs"
    // v2 (9 Nov): windows after meals without entered carbs and windows that counted not delivered SMB made the
    // measured ISF too low (1.5-2.2 mmol/L per U against a real 3.3-3.9). The old list is not read any more.
    private const val KEY = "windows_v2"
    private const val MAX_WINDOWS = 256
    private const val KEEP_MS = 7L * 24 * 3_600_000L
    private const val MIN_FOR_MEDIAN = 3

    /** Measured sensitivity over the kept windows. Values are mg/dL per unit; null when too few windows. */
    data class Summary(
        val medianMgdlPerU: Double?,
        val nightMedianMgdlPerU: Double?,
        val dayMedianMgdlPerU: Double?,
        val count: Int,
        val nightCount: Int,
        val dayCount: Int,
    )

    /** Read-only hourly evidence for the Assistant. Dosing code must not consume this. */
    data class HourlySummary(val hour: Int, val medianMgdlPerU: Double?, val count: Int)

    @Synchronized
    fun add(context: Context, window: ObservedSensitivityMeter.Window) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val night = if (window.stratum == ObservedSensitivityMeter.Stratum.NIGHT) "N" else "D"
        val entry = "${window.endMs},${window.isfMgdlPerU},$night"
        val old = sp.getString(KEY, "").orEmpty().split(';').filter { it.isNotBlank() }
        val oldest = window.endMs - KEEP_MS
        val kept = (old + entry).filter { (it.substringBefore(',').toLongOrNull() ?: 0L) >= oldest }.takeLast(MAX_WINDOWS)
        sp.edit().putString(KEY, kept.joinToString(";")).apply()
    }

    @Synchronized
    fun read(context: Context, nowMs: Long): Summary {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val night = ArrayList<Double>()
        val day = ArrayList<Double>()
        for (item in sp.getString(KEY, "").orEmpty().split(';')) {
            val p = item.split(',')
            if (p.size < 3) continue
            val end = p[0].toLongOrNull() ?: continue
            val isf = p[1].toDoubleOrNull() ?: continue
            if (end < nowMs - KEEP_MS) continue
            if (p[2] == "N") night.add(isf) else day.add(isf)
        }
        val all = night + day
        return Summary(median(all), median(night), median(day), all.size, night.size, day.size)
    }

    @Synchronized
    fun readHourly(context: Context, nowMs: Long): List<HourlySummary> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val byHour = Array(24) { ArrayList<Double>() }
        for (item in sp.getString(KEY, "").orEmpty().split(';')) {
            val p = item.split(',')
            if (p.size < 2) continue
            val end = p[0].toLongOrNull() ?: continue
            val isf = p[1].toDoubleOrNull() ?: continue
            if (end < nowMs - KEEP_MS || !isf.isFinite() || isf <= 0.0) continue
            val hour = Calendar.getInstance().apply { timeInMillis = end }.get(Calendar.HOUR_OF_DAY)
            byHour[hour].add(isf)
        }
        return byHour.mapIndexed { hour, values -> HourlySummary(hour, median(values), values.size) }
    }

    private fun median(values: List<Double>): Double? {
        if (values.size < MIN_FOR_MEDIAN) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}
