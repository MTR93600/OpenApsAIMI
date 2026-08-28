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
    val structural: PkPdParams,
    val effective: EffectiveKinetics,
    val learning: PkpdLearningDiagnostics,
    val activity: InsulinActivityView,
    val peakGovernor: TapPeakGovernorResult?,
    val diaGovernor: DiaGovernorResult?,
    val predictionUsesLearnedKinetics: Boolean,
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
    val causal: CausalContextView,
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
