package app.aaps.plugins.aps.openAPSAIMI.safety

import java.util.Locale
import kotlin.math.abs

/**
 * Single authority for basal-rate caps implied by [CorrectionAggressionGate].
 * Apply at every TBR write path (Autodrive V3/V2 direct, meal overlay merge, final basal engine).
 */
object CorrectionAggressionBasalCap {

    const val LOG_PREFIX = "CORRECTION_AGGRESSION_CAP"

    /** Two rates count as the same value below this gap, in U/h. Reporting only. */
    private const val MERGE_EPSILON_UPH = 1e-9

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

    /**
     * Where one meal-absorption basal boost stands against its tier ceiling. Measurement only.
     *
     * Nothing uses these numbers for a dose. The rate this branch produces is capped later anyway, by
     * the `FINAL_BASAL_MERGE` call on the merged rate, with the same gate and the same ceilings. This
     * record exists to count how often the branch asks for more than its tier allows at the moment it
     * asks, which the export cannot show today.
     *
     * [cappedUph] is never above [requestedUph], because [apply] only ever uses `coerceAtMost`.
     */
    data class MealBoostCapRecord(
        val requestedUph: Double,
        val tier: CorrectionAggressionGate.Tier?,
        val maxAllowedUph: Double?,
        val cappedUph: Double,
        val wouldBind: Boolean,
    )

    /**
     * Runs [apply] on a meal-absorption boost rate and writes the result down. Pure: it reads nothing and
     * writes nothing, and the caller must not use [MealBoostCapRecord.cappedUph] for a dose.
     */
    fun evaluateMealBoostCap(
        requestedRateUph: Double,
        profileBasalUph: Double,
        gate: CorrectionAggressionGate.Decision?,
    ): MealBoostCapRecord {
        val result = apply(
            requestedRateUph = requestedRateUph,
            profileBasalUph = profileBasalUph,
            gate = gate,
        )
        return MealBoostCapRecord(
            requestedUph = requestedRateUph,
            tier = gate?.tier,
            maxAllowedUph = result.maxAllowedUph,
            cappedUph = result.cappedRateUph,
            wouldBind = result.wasCapped,
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

    /**
     * `min` or `max`: which side of [mergeEngineAndRtRates] runs for this gate. Reporting only.
     */
    fun mergeMode(gate: CorrectionAggressionGate.Decision?): String =
        if (gate != null && !gate.allowRocketBasalScale) "min" else "max"

    /**
     * `engine`, `rt` or `equal`: which input [mergeEngineAndRtRates] returned. Reporting only.
     *
     * A null [rtRateUph] means nothing wrote `rT.rate` this tick, so the merge returned the engine rate
     * without comparing anything. `unknown` is returned for a non-finite input: `minOf` and `maxOf` hand
     * back NaN there and every comparison below would be false, so no winner can be named.
     */
    fun mergeWinner(engineRateUph: Double, rtRateUph: Double?, mergedRateUph: Double): String =
        when {
            !engineRateUph.isFinite() || !mergedRateUph.isFinite() -> "unknown"
            rtRateUph == null -> "engine"
            !rtRateUph.isFinite() -> "unknown"
            abs(engineRateUph - rtRateUph) <= MERGE_EPSILON_UPH -> "equal"
            abs(mergedRateUph - engineRateUph) <= MERGE_EPSILON_UPH -> "engine"
            else -> "rt"
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
            append(" ").append(String.format(Locale.US, "%.2f", requestedUph))
            append("→").append(String.format(Locale.US, "%.2f", result.cappedRateUph))
            append(" U/h (max=").append(
                result.maxAllowedUph?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a",
            )
            append(")")
        }
}
