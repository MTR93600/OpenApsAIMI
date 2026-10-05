package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.UndeclaredCobEstimator
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot

/**
 * Android `estimateUndeclaredVirtualCob`.
 *
 * The preference and an already declared COB return 0 g and do not read the snapshot.
 * Otherwise the estimator runs on the snapshot and the preference cap, and the tick logs
 * `VIRTUAL_COB`. The grams feed prediction only. They are not a SMB.
 */
internal fun decideEstimateUndeclaredVirtualCob(
    enabled: Boolean,
    declaredOrAdvisorCob: Double,
    consoleLog: MutableList<String>,
    input: () -> UndeclaredCobEstimator.Input,
): Double {
    if (!enabled) return 0.0
    if (declaredOrAdvisorCob > 0.0) return 0.0
    val result = UndeclaredCobEstimator.estimate(input())
    consoleLog.add("🍽️ VIRTUAL_COB: ${result.toLogString()}")
    return result.grams
}

/**
 * Snapshot fields Android copies into [UndeclaredCobEstimator.Input].
 * Heart-rate inflammation is `hrNow > 0`, a resting rate, and an elevation of at least 15 bpm.
 * Activity is the context flag or a snapshot state other than `IDLE`.
 */
/**
 * Signals the iOS tick does not yet take from a glucose sample.
 * Defaults match an empty Android tick: no Ra, meal probability 0, so the estimator gates.
 */
internal data class VirtualCobTickSignals(
    val estimatedRaMgdlPerMin: Double = 0.0,
    val isfMgdlPerU: Double = 40.0,
    val carbRatioGPerU: Double = 10.0,
    val bgMgdl: Double = 150.0,
    val deltaMgdl5m: Double = 0.0,
    val slopeFromMinDeviation: Double = 0.0,
    val tdd24hU: Double? = null,
    val mealProb: Double = 0.0,
    val activityContextActive: Boolean = false,
    val falseMealSuppression: Boolean = false,
    val exerciseLockoutActive: Boolean = false,
    val postHypoActive: Boolean = false,
)

internal fun undeclaredVirtualCobInput(
    snapshot: HealthContextSnapshot,
    estimatedRaMgdlPerMin: Double,
    isfMgdlPerU: Double,
    carbRatioGPerU: Double,
    bgMgdl: Double,
    deltaMgdl5m: Double,
    slopeFromMinDeviation: Double,
    patientWeightKg: Double,
    tdd24hU: Double?,
    activityContextActive: Boolean,
    mealProb: Double,
    falseMealSuppression: Boolean,
    exerciseLockoutActive: Boolean,
    postHypoActive: Boolean,
    cfrdExacerbationActive: Boolean,
    maxGramsPref: Double,
): UndeclaredCobEstimator.Input {
    val hrElevation = snapshot.hrNow - snapshot.rhrResting
    val hrInflammationElevated = snapshot.hrNow > 0 && snapshot.rhrResting > 0 && hrElevation >= 15
    return UndeclaredCobEstimator.Input(
        estimatedRaMgdlPerMin = estimatedRaMgdlPerMin,
        isfMgdlPerU = isfMgdlPerU,
        carbRatioGPerU = carbRatioGPerU,
        bgMgdl = bgMgdl,
        deltaMgdl5m = deltaMgdl5m,
        slopeFromMinDeviation = slopeFromMinDeviation,
        patientWeightKg = patientWeightKg,
        tdd24hU = tdd24hU,
        stepsLast5m = snapshot.stepsLast5m,
        stepsLast15m = snapshot.stepsLast15m,
        activityDetected = activityContextActive || snapshot.activityState != "IDLE",
        mealProb = mealProb,
        falseMealSuppression = falseMealSuppression,
        exerciseLockoutActive = exerciseLockoutActive,
        postHypoActive = postHypoActive,
        cfrdExacerbationActive = cfrdExacerbationActive,
        hrInflammationElevated = hrInflammationElevated,
        maxGramsPref = maxGramsPref,
    )
}
