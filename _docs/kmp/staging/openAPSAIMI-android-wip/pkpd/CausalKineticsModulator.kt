package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import kotlin.math.abs

/**
 * Lot 2 — maps [CausalStatePosterior] dominant state to non-persisted kinetics shifts.
 * Structural learned DIA/peak are not modified here; only effective runtime shifts.
 *
 * The DIA shifts are given in hours and are scaled by the dominant confidence. They used to be
 * so small (0.05 to 0.2 h at full confidence) that the context channel could never matter next
 * to the learned DIA. They now reach about 1 h, which is a real change of the insulin tail.
 * [DiaGovernor] still keeps the result inside the configured DIA bounds.
 */
data class CausalKineticsModulation(
    val peakShiftMinutes: Double,
    val diaShiftHours: Double,
    val tailScale: Double,
    val learningAllowed: Boolean,
    val dominant: CausalStateId,
    val reason: String,
)

object CausalKineticsModulator {

    /** Stress makes insulin work slower, so the tail gets clearly longer. */
    const val DIA_SHIFT_STRESS_H = 1.0

    /** After a hypo the body keeps reacting to insulin for longer. */
    const val DIA_SHIFT_POST_HYPO_H = 0.9

    /** Illness or inflammation slows insulin down. */
    const val DIA_SHIFT_INFLAMMATORY_H = 0.8

    /** Exercise afterburn keeps insulin active for longer. */
    const val DIA_SHIFT_AFTERBURN_H = 0.8

    /** A meal in progress asks for a shorter, sharper action. */
    const val DIA_SHIFT_MEAL_H = -0.8

    fun modulate(causalStatePosterior: CausalStatePosterior?): CausalKineticsModulation {
        val posterior = causalStatePosterior ?: return CausalKineticsModulation(
            peakShiftMinutes = 0.0,
            diaShiftHours = 0.0,
            tailScale = 1.0,
            learningAllowed = true,
            dominant = CausalStateId.UNKNOWN,
            reason = "no_posterior",
        )
        val dominant = posterior.dominant
        val conf = posterior.dominantConfidence.coerceIn(0.0, 1.0)
        val learningAllowed = posterior.learningContextClean()
        return when (dominant) {
            CausalStateId.POST_HYPO_RECOVERY -> CausalKineticsModulation(
                peakShiftMinutes = 0.0,
                diaShiftHours = DIA_SHIFT_POST_HYPO_H * conf,
                tailScale = 1.0 + 0.12 * conf,
                learningAllowed = learningAllowed,
                dominant = dominant,
                reason = "post_hypo_tail_extension",
            )
            CausalStateId.STRESS_RESISTANCE -> CausalKineticsModulation(
                peakShiftMinutes = 8.0 * conf,
                diaShiftHours = DIA_SHIFT_STRESS_H * conf,
                tailScale = 1.0,
                learningAllowed = learningAllowed,
                dominant = dominant,
                reason = "stress_delayed_peak",
            )
            CausalStateId.INFLAMMATORY_DRIFT -> CausalKineticsModulation(
                peakShiftMinutes = 5.0 * conf,
                diaShiftHours = DIA_SHIFT_INFLAMMATORY_H * conf,
                tailScale = 1.0 + 0.08 * conf,
                learningAllowed = learningAllowed,
                dominant = dominant,
                reason = "inflammatory_slower_action",
            )
            CausalStateId.ABSORPTION_UNCERTAIN -> CausalKineticsModulation(
                peakShiftMinutes = 0.0,
                diaShiftHours = 0.0,
                tailScale = 1.0,
                learningAllowed = learningAllowed,
                dominant = dominant,
                reason = "absorption_uncertain_learn_blocked",
            )
            CausalStateId.FAST_MEAL, CausalStateId.PROLONGED_MEAL -> CausalKineticsModulation(
                peakShiftMinutes = -3.0 * conf,
                diaShiftHours = DIA_SHIFT_MEAL_H * conf,
                tailScale = 1.0,
                learningAllowed = false,
                dominant = dominant,
                reason = "meal_context_no_structural_learn",
            )
            CausalStateId.EXERCISE_AFTERBURN -> CausalKineticsModulation(
                peakShiftMinutes = 4.0 * conf,
                diaShiftHours = DIA_SHIFT_AFTERBURN_H * conf,
                tailScale = 1.0 + 0.1 * conf,
                learningAllowed = false,
                dominant = dominant,
                reason = "exercise_afterburn",
            )
            else -> CausalKineticsModulation(
                peakShiftMinutes = 0.0,
                diaShiftHours = 0.0,
                tailScale = 1.0,
                learningAllowed = learningAllowed,
                dominant = dominant,
                reason = if (learningAllowed) "neutral_clean" else "neutral_gated",
            )
        }.let { base ->
            if (abs(base.peakShiftMinutes) < 0.01 && abs(base.diaShiftHours) < 0.001) base
            else base.copy(reason = "${base.reason}(conf=${"%.2f".format(conf)})")
        }
    }
}
