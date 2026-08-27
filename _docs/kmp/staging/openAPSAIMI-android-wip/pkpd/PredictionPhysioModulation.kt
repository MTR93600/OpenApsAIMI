package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import java.util.Locale
import kotlin.math.max

data class PredictionPhysioModulation(
    val effectiveSensitivityMgdlPerU: Double = Double.NaN,
    val insulinImpactFactor: Double = 1.0,
    val carbImpactFactor: Double = 1.0,
    val uamMomentumFactor: Double = 1.0,
    val hybridMomentumFactor: Double = 1.0,
    val momentumDecayFactor: Double = 1.0,
    val mealSignal: Double = 0.0,
    val nonMealSignal: Double = 0.0,
    val falseMealSuppression: Boolean = false,
    val source: String = "neutral",
) {
    fun isNeutral(): Boolean =
        !falseMealSuppression &&
            !effectiveSensitivityMgdlPerU.isFinite() &&
            approx(insulinImpactFactor, 1.0) &&
            approx(carbImpactFactor, 1.0) &&
            approx(uamMomentumFactor, 1.0) &&
            approx(hybridMomentumFactor, 1.0) &&
            approx(momentumDecayFactor, 1.0)

    private fun approx(actual: Double, expected: Double): Boolean = kotlin.math.abs(actual - expected) <= 1e-6
}

object PredictionPhysioModulationResolver {

    fun resolve(
        fallbackSensitivityMgdlPerU: Double,
        pkpdRuntime: PkPdRuntime?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        hypothesisState: UamHypothesisState?,
        latentState: PhysioLatentState?,
        uamConfidence: Double,
    ): PredictionPhysioModulation {
        val effectiveSensitivity = pkpdRuntime?.fusedIsf
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: fallbackSensitivityMgdlPerU

        val mealSignal = buildMealSignal(
            mealAbsorptionOutput = mealAbsorptionOutput,
            hypothesisState = hypothesisState,
            latentState = latentState,
            uamConfidence = uamConfidence,
        )
        val nonMealSignal = buildNonMealSignal(hypothesisState, latentState)
        val falseMealSuppression =
            hypothesisState?.suppressMealInterpretation == true ||
                latentState?.falseMealSuppression == true
        val dominance = (mealSignal - nonMealSignal).coerceIn(-1.0, 1.0)
        val mealPriorityBoost = if (mealAbsorptionOutput?.mealDeliveryPriority == true) 0.04 else 0.0
        val insulinImpactFactor = pkpdRuntime
            ?.let { (it.weightKineticFactor * it.physioAbsorptionFactor).coerceIn(0.88, 1.12) }
            ?: 1.0

        val carbImpactFactor = when {
            falseMealSuppression -> 0.84
            dominance >= 0.0 -> 1.0 + dominance * 0.18 + mealPriorityBoost
            else -> 1.0 + dominance * 0.14
        }.coerceIn(0.82, 1.20)

        val uamMealBoost = if (mealSignal >= 0.45) {
            uamConfidence.coerceIn(0.0, 1.0) * 0.10
        } else {
            0.0
        }
        val uamMomentumFactor = when {
            falseMealSuppression -> (0.18 + mealSignal * 0.18).coerceIn(0.18, 0.38)
            dominance >= 0.0 -> (1.0 + dominance * 0.28 + uamMealBoost).coerceIn(0.75, 1.20)
            else -> (1.0 + dominance * 0.72).coerceIn(0.28, 1.0)
        }
        val hybridMomentumFactor = when {
            falseMealSuppression -> 0.32
            dominance >= 0.0 -> (0.96 + dominance * 0.18 + uamMealBoost * 0.5).coerceIn(0.84, 1.14)
            else -> (1.0 + dominance * 0.55).coerceIn(0.42, 1.0)
        }
        val momentumDecayFactor = when {
            falseMealSuppression -> 0.88
            dominance >= 0.0 -> 1.03
            else -> (1.0 + dominance * 0.08).coerceIn(0.90, 1.0)
        }

        val source = buildString {
            append(if (pkpdRuntime != null) "runtime" else "fallback")
            when {
                falseMealSuppression -> append("+false_meal_guard")
                mealSignal >= 0.45 -> append("+meal_signal")
                nonMealSignal >= 0.45 -> append("+non_meal_signal")
            }
        }

        return PredictionPhysioModulation(
            effectiveSensitivityMgdlPerU = effectiveSensitivity,
            insulinImpactFactor = insulinImpactFactor,
            carbImpactFactor = carbImpactFactor,
            uamMomentumFactor = uamMomentumFactor,
            hybridMomentumFactor = hybridMomentumFactor,
            momentumDecayFactor = momentumDecayFactor,
            mealSignal = mealSignal,
            nonMealSignal = nonMealSignal,
            falseMealSuppression = falseMealSuppression,
            source = source,
        )
    }

    fun formatLogLine(modulation: PredictionPhysioModulation): String =
        "PKPD_PRED_MOD: src=${modulation.source} sens=${fmt(modulation.effectiveSensitivityMgdlPerU)} " +
            "ins=${fmt(modulation.insulinImpactFactor)} carb=${fmt(modulation.carbImpactFactor)} " +
            "uam=${fmt(modulation.uamMomentumFactor)} hyb=${fmt(modulation.hybridMomentumFactor)} " +
            "decay=${fmt(modulation.momentumDecayFactor)} meal=${fmt(modulation.mealSignal)} " +
            "nonMeal=${fmt(modulation.nonMealSignal)} suppress=${modulation.falseMealSuppression}"

    private fun buildMealSignal(
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        hypothesisState: UamHypothesisState?,
        latentState: PhysioLatentState?,
        uamConfidence: Double,
    ): Double {
        val absorptionSignal = when {
            mealAbsorptionOutput == null -> 0.0
            mealAbsorptionOutput.phase.isActive -> max(0.35, mealAbsorptionOutput.belief)
            mealAbsorptionOutput.mealDeliveryPriority -> max(0.32, mealAbsorptionOutput.belief * 0.85)
            else -> 0.0
        }
        val hypothesisMeal = hypothesisState?.mealCompatibleProb() ?: 0.0
        val latentMeal = latentState?.mealProb ?: 0.0
        val uamSupport = if (max(absorptionSignal, max(hypothesisMeal, latentMeal)) >= 0.45) {
            uamConfidence.coerceIn(0.0, 1.0) * 0.20
        } else {
            0.0
        }
        return combineSignals(
            hypothesisMeal,
            latentMeal * 0.90,
            absorptionSignal,
            uamSupport,
        )
    }

    private fun buildNonMealSignal(
        hypothesisState: UamHypothesisState?,
        latentState: PhysioLatentState?,
    ): Double =
        combineSignals(
            hypothesisState?.competingNonMealProb() ?: 0.0,
            latentState?.endogenousGlucoseDrive ?: 0.0,
            (latentState?.autonomicStress ?: 0.0) * 0.85,
            latentState?.postHypoReboundProb ?: 0.0,
            (latentState?.transientResistanceProb ?: 0.0) * 0.55,
            if (latentState?.falseMealSuppression == true) 0.65 else 0.0,
        )

    private fun combineSignals(vararg values: Double): Double {
        if (values.isEmpty()) return 0.0
        var remainingNeutral = 1.0
        values.forEach { value ->
            remainingNeutral *= 1.0 - value.coerceIn(0.0, 1.0)
        }
        return (1.0 - remainingNeutral).coerceIn(0.0, 1.0)
    }

    private fun fmt(value: Double): String =
        if (!value.isFinite()) "-" else String.format(Locale.US, "%.2f", value)
}
