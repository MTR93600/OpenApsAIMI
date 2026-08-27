package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform

/**
 * Single authority for basal-rate caps implied by [CorrectionAggressionGate].
 * Apply at every TBR write path (Autodrive V3/V2 direct, meal overlay merge, final basal engine).
 */
object CorrectionAggressionBasalCap {

    const val LOG_PREFIX = "CORRECTION_AGGRESSION_CAP"

    data class Result(
        val cappedRateUph: Double,
        val wasCapped: Boolean,
        val maxAllowedUph: Double?,
    )

    fun apply(
        requestedRateUph: Double,
        profileBasalUph: Double,
        gate: CorrectionAggressionGate.Decision?,
    ): Result {
        if (!requestedRateUph.isFinite() || requestedRateUph <= 0.0) {
            return Result(requestedRateUph, wasCapped = false, maxAllowedUph = null)
        }
        if (gate == null || gate.allowRocketBasalScale) {
            return Result(requestedRateUph, wasCapped = false, maxAllowedUph = null)
        }
        val maxAllowed = profileBasalUph * gate.maxBasalScaleCap
        val capped = requestedRateUph.coerceAtMost(maxAllowed)
        return Result(
            cappedRateUph = capped,
            wasCapped = capped < requestedRateUph - 1e-6,
            maxAllowedUph = maxAllowed,
        )
    }

    fun mergeEngineAndRtRates(
        engineRateUph: Double,
        rtRateUph: Double?,
        gate: CorrectionAggressionGate.Decision?,
    ): Double {
        if (rtRateUph == null) return engineRateUph
        return if (gate != null && !gate.allowRocketBasalScale) {
            minOf(engineRateUph, rtRateUph)
        } else {
            maxOf(engineRateUph, rtRateUph)
        }
    }

    fun formatLogLine(
        source: String,
        requestedUph: Double,
        result: Result,
        tier: CorrectionAggressionGate.Tier?,
    ): String =
        buildString {
            append(LOG_PREFIX)
            append(": source=").append(source)
            append(" tier=").append(tier?.name ?: "n/a")
            append(" ").append(fmt2(requestedUph))
            append("→").append(fmt2(result.cappedRateUph))
            append(" U/h (max=").append(
                result.maxAllowedUph?.let { fmt2(it) } ?: "n/a",
            )
            append(")")
        }

    private fun fmt2(value: Double): String =
        NumberFormat.DECIMAL_2.format(value, NumberFormatPlatform.SEPARATOR_DOT)
}
