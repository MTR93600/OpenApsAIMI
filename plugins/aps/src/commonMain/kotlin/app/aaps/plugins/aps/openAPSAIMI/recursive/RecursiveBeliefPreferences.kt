package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences

data class RecursiveBeliefPreferences(
    val shadowEnabled: Boolean,
    val authorityEnabled: Boolean,
    val waveletEnabled: Boolean,
    val mealHyperBypassEnabled: Boolean,
    val treeMealRiseFrontLoadEnabled: Boolean,
) {
    companion object {
        fun from(preferences: Preferences): RecursiveBeliefPreferences =
            RecursiveBeliefPreferences(
                shadowEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow),
                authorityEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority),
                waveletEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefWavelet),
                mealHyperBypassEnabled = preferences.get(BooleanKey.OApsAIMIMealHyperBypassEnabled),
                treeMealRiseFrontLoadEnabled = preferences.get(BooleanKey.OApsAIMITreeMealRiseFrontLoad),
            )

        /** Active when shadow (JSONL + SHADOW leaves) or live authority is on. */
        fun isActive(prefs: RecursiveBeliefPreferences): Boolean =
            prefs.shadowEnabled || prefs.authorityEnabled

        /** JSONL unfold + SHADOW_* belief leaves (a posteriori analysis). */
        fun exportEnabled(prefs: RecursiveBeliefPreferences): Boolean = prefs.shadowEnabled
    }
}
