package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalChannelSafetyGuards

/** Field reads of `basalFirstAdaptiveMultiplier`, each at the line that uses it. */
internal interface AimiBasalFirstAdaptiveState {
    fun adaptiveMult(): Double
    fun mealTime(): Boolean
    fun lunchTime(): Boolean
    fun dinnerTime(): Boolean
    fun snackTime(): Boolean
    fun highCarbTime(): Boolean
    fun bfastTime(): Boolean
}

/**
 * `basalFirstAdaptiveMultiplier`.
 *
 * Keeps a reduction below 1.0 when the basal-channel guards are on and no manual meal mode is
 * active. Amplification above 1.0 still collapses to 1.0 inside [BasalChannelSafetyGuards].
 * This function has no swallowed [Exception].
 */
internal fun decideBasalFirstAdaptiveMultiplier(
    preferences: Preferences,
    consoleLog: MutableList<String>,
    state: AimiBasalFirstAdaptiveState,
): Double {
    val kept = BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(
        guardsEnabled = preferences.get(BooleanKey.OApsAIMIBasalChannelSafetyGuards),
        adaptiveMult = state.adaptiveMult(),
        mealModeActive = state.mealTime() || state.lunchTime() || state.dinnerTime() ||
            state.snackTime() || state.highCarbTime() || state.bfastTime(),
    )
    if (kept < 1.0) {
        consoleLog.add(
            "🛡️ BASAL_FIRST_GOV: adaptiveMult conservé à ${aimiFmt2(kept)}x (legacy forçait 1.00x)",
        )
    }
    return kept
}
