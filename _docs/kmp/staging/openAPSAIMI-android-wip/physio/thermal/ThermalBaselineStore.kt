package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Personal nocturnal skin-temperature baseline learned from wearable deltas.
 */
internal object ThermalBaselineStore {

    private const val MAX_NIGHTLY_POINTS = 21
    private val nightlyMediansC = CopyOnWriteArrayList<Double>()

    fun observeSamples(samples: List<ThermalSampleMTR>) {
        if (samples.isEmpty()) return
        val calendar = Calendar.getInstance(Locale.US)
        val nocturnal = samples.filter { sample ->
            calendar.timeInMillis = sample.timestampMs
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hour in 2..5
        }
        if (nocturnal.size < 3) return
        val median = nocturnal.map { it.deltaCelsius }.sorted().let { sorted ->
            sorted[sorted.size / 2]
        }
        if (nightlyMediansC.isEmpty() || abs(nightlyMediansC.last() - median) > 0.03) {
            nightlyMediansC.add(median)
            while (nightlyMediansC.size > MAX_NIGHTLY_POINTS) {
                nightlyMediansC.removeAt(0)
            }
        }
    }

    fun personalBaselineDeltaC(): Double? {
        if (nightlyMediansC.isEmpty()) return null
        val sorted = nightlyMediansC.sorted()
        return sorted[sorted.size / 2]
    }

    fun resetForTests() {
        nightlyMediansC.clear()
    }
}
