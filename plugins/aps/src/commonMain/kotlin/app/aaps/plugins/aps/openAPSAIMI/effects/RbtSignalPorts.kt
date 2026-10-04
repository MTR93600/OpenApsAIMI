package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.plugins.aps.openAPSAIMI.NGRConfig
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioDecisionTraceMTR

/** `glucoseStatusCalculatorAimi.getRecentGlucose`, au moment où la référence le lit. */
internal fun interface AimiRecentGlucose {
    fun getRecentGlucose(): List<Float>
}

/** `classifyPostHypoState`. L'implémentation Android met à jour `lastHypoBelow70At`. */
internal fun interface AimiPostHypoClassification {
    fun classifyPostHypoState(
        recentBGs: List<Float>,
        cob: Double,
        explicitMealMode: Boolean,
        shortAvgDelta: Float,
        delta: Float,
        slopeFromMinDeviation: Double,
        estimatedCarbs: Double,
        estimatedCarbsAgeMs: Long,
        localHour: Int,
        reason: StringBuilder,
    ): PostHypoState
}

/** `buildNightGrowthResistanceConfig`. Les lectures de préférences restent dans la coquille. */
internal fun interface AimiNightGrowthConfig {
    fun buildNightGrowthResistanceConfig(
        profile: OapsProfileAimi,
        autosens: AutosensResult,
        glucoseStatus: GlucoseStatusAIMI?,
        targetBg: Double,
    ): NGRConfig
}

/** Les trois lectures de `physioAdapter` dans `buildRbtExtendedSignals`. */
internal interface AimiPhysioTick {
    fun getLastDecisionTrace(): PhysioDecisionTraceMTR?
    fun getEffectiveContext(): PhysioContextMTR
    fun getLatestSnapshot(): HealthContextSnapshot
}

/** Écritures d'état du tick, à la ligne où la référence les fait. */
internal interface AimiRbtTickWrites {
    fun setLastPostHypoOrdinal(ordinal: Int)
    fun setLastNgrBasalMultiplier(multiplier: Double)
}
