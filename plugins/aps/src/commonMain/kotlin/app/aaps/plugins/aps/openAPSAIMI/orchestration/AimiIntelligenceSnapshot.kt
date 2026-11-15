package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.plugins.aps.openAPSAIMI.pkpd.DiaGovernorResult
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdParams
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningDiagnostics
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningTrace
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TapPeakGovernorResult

data class EffectiveKinetics(
    val diaHours: Double,
    val peakMinutes: Double,
    val profileDiaHours: Double,
    val profilePeakMinutes: Int,
)

data class InsulinActivityView(
    val tailFraction: Double,
    val stage: String,
    val relativeActivity: Double,
)

data class InsulinKineticsView(
    val accountingIobArray: Array<IobTotal>,
    val predictionIobArray: Array<IobTotal>,
    /**
     * Learned PK/PD parameters. In the snapshot these are the ones of this tick, after the learning
     * inside `determine_basal`; in the plugin they are the ones from before that learning.
     */
    val structural: PkPdParams,
    /**
     * The DIA and peak that dose this tick, with the causal context of the previous tick. After
     * `DosingKineticsRateLimiter` this is the limited value; the value before the limit is
     * [targetEffective].
     */
    val effective: EffectiveKinetics,
    /**
     * The DIA and peak the governors asked for this tick, before the rate limit. The same as
     * [effective] when nothing was limited. Only for the log, never for dosing.
     */
    val targetEffective: EffectiveKinetics = effective,
    /** The same resolution without the causal shifts. Only for the log, never for dosing. */
    val noContextEffective: EffectiveKinetics,
    /**
     * Why the causal context of the PREVIOUS tick shifted the kinetics that dosed, or `no_posterior`.
     * Not the same as [CausalContextView.causalModulationReason], which is about this tick.
     */
    val contextReason: String,
    val learning: PkpdLearningDiagnostics,
    val activity: InsulinActivityView,
    val peakGovernor: TapPeakGovernorResult,
    val diaGovernor: DiaGovernorResult,
    val predictionUsesLearnedKinetics: Boolean,
    /**
     * False when [peakGovernor] and [diaGovernor] are not what dosed: the caller gave only the dosing
     * numbers (see `InsulinKineticsAuthority.fromDosingValues`). The export then leaves them out.
     */
    val governorsDosed: Boolean = true,
    /** Numbers about the DIA learning loop; null when PK/PD is off. */
    val learningTrace: PkpdLearningTrace? = null,
) {
    fun effectiveDiaHours(): Double = effective.diaHours

    fun effectivePeakMinutes(): Double = effective.peakMinutes
}

data class CausalContextView(
    val learningQuality: Double,
    val learningGatePass: Boolean,
    val dominant: String,
    val dominantConfidence: Double,
    val causalModulationReason: String,
)

data class IsfAuthorityView(
    val fusedMgdlPerU: Double,
    val profileMgdlPerU: Double,
    val tddMgdlPerU: Double,
    val pkpdScale: Double,
    val fusionFactor: Double,
)

data class PredictionAuthorityView(
    val predTerminalMgdl: Double,
    val eventualTerminalMgdl: Double,
    val pkpdEventualMgdl: Double?,
    val scenarioFloorMgdl: Double?,
    val scenarioBestMgdl: Double?,
    val source: String?,
    val scenarioUpliftApplied: Boolean = false,
    val falseMealSuppression: Boolean = false,
    val reason: String? = null,
)

data class SmbPolicyContextView(
    val tailFraction: Double,
    val activityStage: String,
    val tailDampingEnabled: Boolean,
)

data class MlFeatureSliceView(
    val fusedIsf: Double,
    val effectiveDiaHours: Double,
    val effectivePeakMinutes: Double,
    val tailFraction: Double,
    val learningQuality: Double,
    val pkpdScale: Double,
)

data class SnapshotMeta(
    val version: Int = AimiIntelligenceSnapshot.SNAPSHOT_VERSION,
    val timestampMs: Long,
    val buildTag: String = "intelligence_snapshot_v1",
)

data class AimiIntelligenceSnapshot(
    val meta: SnapshotMeta,
    /** Causal posterior of THIS tick. It doses only from the next tick on, through the kinetics. */
    val causal: CausalContextView,
    /** The kinetics that dosed this tick, resolved with the causal posterior of the previous tick. */
    val kinetics: InsulinKineticsView,
    val isf: IsfAuthorityView,
    val predictions: PredictionAuthorityView,
    val smbPolicy: SmbPolicyContextView,
    val mlFeatures: MlFeatureSliceView,
) {
    companion object {
        const val SNAPSHOT_VERSION = 1
    }
}

fun InsulinActivityState.toView(tailFraction: Double): InsulinActivityView =
    InsulinActivityView(
        tailFraction = tailFraction,
        stage = stage.name,
        relativeActivity = relativeActivity,
    )
