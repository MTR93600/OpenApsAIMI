package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaSmbAuthorityDecision

enum class ReleaseAuthority {
    NONE,
    SOFT,
    HARD,
}

enum class HypoGuardMode {
    FULL,
    PARTIAL,
    IGNORE_MINPRED,
}

enum class AutodriveModeHint {
    V3,
    V2,
    SKIP,
}

enum class MealChannelHint {
    PRIORITY,
    NORMAL,
    SUPPRESS,
}

enum class BasalFirstChannel {
    NONE,
    T3C_BASAL_FIRST,
    HARMONIA_PRODUCTION_BASAL_FIRST,
}

data class BeliefLeafReading(
    val id: BeliefLeafId,
    val signal: Double,
    val weight: Double,
    val credibility: Double,
    val rawSummary: String,
)

data class ScaleTension(
    val parentTauMin: Int,
    val childTauMin: Int,
    val magnitude: Double,
    val dominantParadoxId: BeliefParadoxId?,
)

data class BeliefScaleNode(
    val horizonMinutes: Int,
    val belief: Double,
    val terminalMgdl: Double,
    val urgency: Double,
    val leaves: List<BeliefLeafReading>,
)

data class BeliefParadox(
    val id: BeliefParadoxId,
    val suppressed: Boolean,
    val resolution: String,
)

data class LoadGovernorExport(
    val tier: String,
    val multiplierG: Double,
    val rawMultiplierG: Double,
    val smbTickCapU: Double,
    val physBudgetU: Double,
    val stackScore: Double,
    val riseScore: Double,
    val deltaDecelScore: Double,
    val smbDemandBeforeU: Double,
    val smbDemandAfterU: Double,
    val applied: Boolean,
    val reasonCodes: List<String>,
    val summary: String,
)

data class T3cBasalFirstResolution(
    val active: Boolean,
    val eligible: Boolean,
    val basalDemandRateUph: Double,
    val boundedRateUph: Double,
    val maxBasalCapUph: Double,
    val anticipationStrength: Double,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val dominantBlocker: String?,
    val governanceBasalFloorUph: Double? = null,
    val governanceAggressivenessFloor: Double? = null,
    val reasonCodes: List<String> = emptyList(),
    val selectedForProduction: Boolean = false,
    val historicalBypassNeutralized: Boolean = false,
    val appliedRateUph: Double? = null,
    val appliedDurationMin: Int? = null,
    val runtimeBlocker: String? = null,
)

data class HarmoniaBasalFirstResolution(
    val active: Boolean,
    val eligible: Boolean,
    val sourceAction: String?,
    val branch: String?,
    val basalDemandRateUph: Double,
    val boundedRateUph: Double,
    val maxBasalCapUph: Double,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val dominantBlocker: String?,
    val reasonCodes: List<String> = emptyList(),
    val selectedForProduction: Boolean = false,
    val appliedRateUph: Double? = null,
    val appliedDurationMin: Int? = null,
    val runtimeBlocker: String? = null,
)

data class HarmoniaSmbResolution(
    val active: Boolean,
    val eligible: Boolean,
    val sourceAction: String?,
    val branch: String?,
    val targetSmbU: Double,
    val boundedSmbU: Double,
    val maxSmbCapU: Double,
    val demandBeforeU: Double,
    val demandAfterU: Double,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val dominantBlocker: String?,
    val reasonCodes: List<String> = emptyList(),
    val appliedToRbtDemand: Boolean = false,
    val reducesRbtDemand: Boolean = false,
    val authorityMode: String? = null,
    val addsSmbAuthority: Boolean = false,
    val insulinIntent: String? = null,
    /**
     * The arbiter decision this resolution was built from, carried out unchanged so the export can
     * write the real record instead of rebuilding one. The rebuilt record reads `demand_before_u`
     * after the lift was already applied, so a lift always looks like zero. This field keeps the
     * pre-lift demand, the MPC demand, the envelope and the arbiter reasons. Null when no arbiter
     * ran on the tick. Nothing on the dose path reads it.
     */
    val authorityDecision: HarmoniaSmbAuthorityDecision? = null,
)

data class DoseChannelResolution(
    val smbDemandU: Double,
    val smbDemandBeforeLoadGovernorU: Double = smbDemandU,
    val tbrDemandFraction: Double,
    val waitBias: Double,
    val dominantScaleMinutes: Int,
    val releaseAuthority: ReleaseAuthority,
    val hypoGuardMode: HypoGuardMode,
    val autodriveModeHint: AutodriveModeHint,
    val mealChannel: MealChannelHint,
    val suppressTrajBasalShift: Boolean,
    val hypoMinPredIgnored: Boolean,
    val reasonCodes: List<String>,
    val basalFirstChannel: BasalFirstChannel = BasalFirstChannel.NONE,
    val t3cBasalFirst: T3cBasalFirstResolution? = null,
    val harmoniaBasalFirst: HarmoniaBasalFirstResolution? = null,
    val harmoniaSmb: HarmoniaSmbResolution? = null,
    val loadGovernorExport: LoadGovernorExport? = null,
)

data class RecursiveBeliefSnapshot(
    val scales: List<BeliefScaleNode>,
    val tensions: List<ScaleTension>,
    val paradoxes: List<BeliefParadox>,
    val resolutions: DoseChannelResolution,
    val loadGovernor: LoadGovernorExport? = null,
    val mr7Trace: List<String>,
    val waveletBands: WaveletBelief.Bands? = null,
)

data class RecursiveBeliefExport(
    val version: Int,
    val shadowOnly: Boolean,
    val authorityApplied: Boolean,
    val authorityGate: AuthorityGateExport? = null,
    val waveletBands: WaveletExport?,
    val scales: List<ScaleExport>,
    val tensions: List<TensionExport>,
    val paradoxes: List<ParadoxExport>,
    val resolution: ResolutionExport,
    val loadGovernor: LoadGovernorExport?,
    val mr7Trace: List<String>,
)

data class AuthorityGateExport(
    val requestedAuthority: String,
    val maxAllowedAuthority: String,
    val effectiveAuthority: String,
    val readinessScore: Double,
    val liftBlend: Double,
    val shadowOnly: Boolean,
    val softLimited: Boolean,
    val reasonCodes: List<String>,
)

data class ScaleExport(
    val tauMin: Int,
    val belief: Double,
    val terminalMgdl: Double,
    val urgency: Double,
    val leaves: List<LeafExport>,
)

data class LeafExport(
    val id: String,
    val signal: Double,
    val weight: Double,
    val credibility: Double,
    val summary: String,
)

data class TensionExport(
    val parentTau: Int,
    val childTau: Int,
    val magnitude: Double,
    val dominant: String?,
)

data class ParadoxExport(
    val id: String,
    val suppressed: Boolean,
    val resolution: String,
)

data class ResolutionExport(
    val smbDemandU: Double,
    val tbrDemandFraction: Double,
    val waitBias: Double,
    val dominantScaleMin: Int,
    val releaseAuthority: String,
    val hypoGuardMode: String,
    val suppressTrajBasalShift: Boolean,
    val hypoMinPredIgnored: Boolean,
    val reasonCodes: List<String>,
    val basalFirstChannel: String,
    val t3cBasalFirst: T3cBasalFirstExport? = null,
    val harmoniaBasalFirst: HarmoniaBasalFirstExport? = null,
    val harmoniaSmb: HarmoniaSmbExport? = null,
)

data class WaveletExport(
    val high: Double,
    val mid: Double,
    val low: Double,
)

data class T3cBasalFirstExport(
    val active: Boolean,
    val eligible: Boolean,
    val basalDemandRateUph: Double,
    val boundedRateUph: Double,
    val maxBasalCapUph: Double,
    val anticipationStrength: Double,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val dominantBlocker: String?,
    val governanceBasalFloorUph: Double? = null,
    val governanceAggressivenessFloor: Double? = null,
    val reasonCodes: List<String> = emptyList(),
    val selectedForProduction: Boolean = false,
    val historicalBypassNeutralized: Boolean = false,
    val appliedRateUph: Double? = null,
    val appliedDurationMin: Int? = null,
    val runtimeBlocker: String? = null,
)

data class HarmoniaBasalFirstExport(
    val active: Boolean,
    val eligible: Boolean,
    val sourceAction: String?,
    val branch: String?,
    val basalDemandRateUph: Double,
    val boundedRateUph: Double,
    val maxBasalCapUph: Double,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val dominantBlocker: String?,
    val reasonCodes: List<String> = emptyList(),
    val selectedForProduction: Boolean = false,
    val appliedRateUph: Double? = null,
    val appliedDurationMin: Int? = null,
    val runtimeBlocker: String? = null,
)

data class HarmoniaSmbExport(
    val active: Boolean,
    val eligible: Boolean,
    val sourceAction: String?,
    val branch: String?,
    val targetSmbU: Double,
    val boundedSmbU: Double,
    val maxSmbCapU: Double,
    val demandBeforeU: Double,
    val demandAfterU: Double,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val dominantBlocker: String?,
    val reasonCodes: List<String> = emptyList(),
    val appliedToRbtDemand: Boolean = false,
    val reducesRbtDemand: Boolean = false,
    val authorityMode: String? = null,
    val addsSmbAuthority: Boolean = false,
    val insulinIntent: String? = null,
)
