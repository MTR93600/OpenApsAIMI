package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.PreferenceKey

internal object TpoPreferenceKeys {
    private val byKey: Map<String, PreferenceKey> = buildMap {
        put(DoubleKey.OApsAIMIMaxSMB.key, DoubleKey.OApsAIMIMaxSMB)
        put(DoubleKey.OApsAIMIHighBGMaxSMB.key, DoubleKey.OApsAIMIHighBGMaxSMB)
        put(DoubleKey.OApsAIMIPriorityMaxIobFactor.key, DoubleKey.OApsAIMIPriorityMaxIobFactor)
        put(DoubleKey.OApsAIMIPriorityMaxIobExtraU.key, DoubleKey.OApsAIMIPriorityMaxIobExtraU)
        put(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor.key, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor)
        put(DoubleKey.OApsAIMIRedCarpetRestoreThreshold.key, DoubleKey.OApsAIMIRedCarpetRestoreThreshold)
        put(DoubleKey.OApsAIMISmbTailDamping.key, DoubleKey.OApsAIMISmbTailDamping)
        put(DoubleKey.OApsAIMISmbExerciseDamping.key, DoubleKey.OApsAIMISmbExerciseDamping)
        put(DoubleKey.OApsAIMISmbLateFatDamping.key, DoubleKey.OApsAIMISmbLateFatDamping)
        put(DoubleKey.OApsAIMILunchFactor.key, DoubleKey.OApsAIMILunchFactor)
        put(DoubleKey.OApsAIMIDinnerFactor.key, DoubleKey.OApsAIMIDinnerFactor)
        put(DoubleKey.AimiTubeAggressiveness.key, DoubleKey.AimiTubeAggressiveness)
        put(DoubleKey.AimiTubeHypoFloorMgdl.key, DoubleKey.AimiTubeHypoFloorMgdl)
        put(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)
        put(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled.key, BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)
    }

    fun fromKey(key: String): PreferenceKey? = byKey[key]

    fun isWhitelisted(key: String): Boolean = key in byKey
}
