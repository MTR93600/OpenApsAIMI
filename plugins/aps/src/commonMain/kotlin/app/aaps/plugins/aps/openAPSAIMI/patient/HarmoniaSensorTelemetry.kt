package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlin.math.max

/**
 * Maps loop CGM telemetry into Harmonia simulation sensor blockers.
 */
object HarmoniaSensorTelemetry {

    data class Reading(
        val sensorAgeMin: Int,
        val sensorNoise: Double,
    )

    fun resolve(
        cgmNoiseRaw: Double,
        sensorInsertionMs: Long?,
        nowMs: Long,
    ): Reading {
        val noise = (cgmNoiseRaw.coerceIn(0.0, 3.0) / 3.0).coerceIn(0.0, 1.0)
        val ageMin = if (sensorInsertionMs != null && sensorInsertionMs > 0L && sensorInsertionMs <= nowMs) {
            max(0L, (nowMs - sensorInsertionMs) / 60_000L).toInt()
        } else {
            0
        }
        return Reading(sensorAgeMin = ageMin, sensorNoise = noise)
    }
}
