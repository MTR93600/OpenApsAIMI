package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * Personal nocturnal skin-temperature baseline learned from wearable deltas.
 */
internal object ThermalBaselineStore {

    private const val MAX_NIGHTLY_POINTS = 21
    private val nightlyMediansC = mutableListOf<Double>()
    private val lock = AapsLock()

    fun observeSamples(samples: List<ThermalSampleMTR>) {
        if (samples.isEmpty()) return
        val nocturnal = samples.filter { sample ->
            val hour = Instant.fromEpochMilliseconds(sample.timestampMs)
                .toLocalDateTime(TimeZone.currentSystemDefault()).hour
            hour in 2..5
        }
        if (nocturnal.size < 3) return
        val median = nocturnal.map { it.deltaCelsius }.sorted().let { sorted ->
            sorted[sorted.size / 2]
        }
        lock.withLock {
            if (nightlyMediansC.isEmpty() || abs(nightlyMediansC.last() - median) > 0.03) {
                nightlyMediansC.add(median)
                while (nightlyMediansC.size > MAX_NIGHTLY_POINTS) {
                    nightlyMediansC.removeAt(0)
                }
            }
        }
    }

    fun personalBaselineDeltaC(): Double? = lock.withLock {
        if (nightlyMediansC.isEmpty()) return@withLock null
        val sorted = nightlyMediansC.sorted()
        sorted[sorted.size / 2]
    }

    fun resetForTests() {
        lock.withLock {
            nightlyMediansC.clear()
        }
    }
}
