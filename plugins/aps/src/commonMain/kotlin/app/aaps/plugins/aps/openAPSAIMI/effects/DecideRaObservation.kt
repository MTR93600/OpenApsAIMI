package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime

/**
 * Android reads of the Ra observation.
 * Each method is the call that already existed at that line.
 * A failed build keeps the null fallback and logs the type. `runCatching` stays `Throwable`.
 */
internal interface AimiRaObservationCalls {
    fun variableSensitivity(): Float
    fun mealTime(): Boolean
    fun bfastTime(): Boolean
    fun lunchTime(): Boolean
    fun dinnerTime(): Boolean
    fun highCarbTime(): Boolean
    fun snackTime(): Boolean
    fun stressMask(): DoubleArray
    fun hourOfDay(): Int
    fun stepsLast15m(): Int
    fun uamConfidence(): Double
    fun postHypoRecoveryActive(): Boolean
    fun extendedDawnGuard(): Boolean
    fun logObservationFailed(typeName: String?, message: String?)
}

/**
 * `buildRaObservationState`.
 *
 * Estimated SI is the working ISF divided by 10 000. That number is what the estimator
 * uses to size insulin. A non-finite or non-positive sensitivity returns null before the build.
 */
internal fun decideBuildRaObservationState(
    ctx: AimiTickContext,
    combinedDelta: Float,
    shortAvgDeltaAdj: Float,
    pkpdRuntime: PkPdRuntime?,
    hasRecentMealEstimate: Boolean,
    preferences: Preferences,
    calls: AimiRaObservationCalls,
): AutoDriveState? {
    val bgNow = ctx.glucoseStatus.glucose
    if (!bgNow.isFinite() || bgNow <= 0.0) return null
    val velocity = shortAvgDeltaAdj.toDouble() / 5.0
    if (!velocity.isFinite()) return null

    val canonicalSI = if (pkpdRuntime != null) pkpdRuntime.fusedIsf / 10000.0
    else calls.variableSensitivity().toDouble() / 10000.0
    if (!canonicalSI.isFinite() || canonicalSI <= 0.0) return null

    val mealSignals = calls.mealTime() || calls.bfastTime() || calls.lunchTime() || calls.dinnerTime() ||
        calls.highCarbTime() || calls.snackTime() ||
        ctx.mealData.mealCOB >= 0.1 || hasRecentMealEstimate

    return runCatching {
        AutoDriveState.createSafe(
            bg = bgNow,
            bgVelocity = velocity,
            iob = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
            cob = ctx.mealData.mealCOB,
            // No online-learner factor: the engaged path stopped applying it too, so the estimator
            // sees the same sensitivity on every tick. See the note in AutodriveEngine.tick.
            estimatedSI = canonicalSI,
            patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
            physiologicalStressMask = calls.stressMask(),
            hour = calls.hourOfDay(),
            steps = calls.stepsLast15m(),
            sourceSensor = ctx.glucoseStatus.sourceSensor,
            combinedDelta = combinedDelta.toDouble(),
            uamConfidence = calls.uamConfidence(),
            applyHypoRecoveryRaDampening = calls.postHypoRecoveryActive() && !mealSignals,
            physioExtendedDawnGuard = calls.extendedDawnGuard(),
        )
    }.onFailure { t ->
        calls.logObservationFailed(t::class.simpleName, t.message)
    }.getOrNull()
}
