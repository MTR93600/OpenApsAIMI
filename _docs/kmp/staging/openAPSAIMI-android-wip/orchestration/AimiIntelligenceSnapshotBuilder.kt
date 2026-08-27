package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.orchestration.PredictionAuthorityApplier
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinKineticsAuthority
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningDiagnostics
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TapPeakGovernorResult
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import kotlin.math.abs

object AimiIntelligenceSnapshotBuilder {

    data class BuildInput(
        val timestampMs: Long,
        val accountingIobArray: Array<IobTotal>,
        val profile: OapsProfileAimi,
        val effectiveProfile: Profile,
        val pkpdRuntime: PkPdRuntime?,
        val peakGovernor: TapPeakGovernorResult?,
        val causalPosterior: CausalStatePosterior?,
        val physioPeakShiftMinutes: Int = 0,
        val sitePeakShiftMinutes: Double = 0.0,
        val trajectoryPeakNudgeMinutes: Double = 0.0,
        val preferences: Preferences,
        val iobCobCalculator: IobCobCalculator,
        val pkpdPredictionIobArray: Array<IobTotal>? = null,
        val learningDiagnostics: PkpdLearningDiagnostics,
        val predictionAuthority: DecisionPredictionAuthority? = null,
        val pkpdEventualMgdl: Double? = null,
        val exerciseFlag: Boolean = false,
    )

    fun build(input: BuildInput): AimiIntelligenceSnapshot {
        val kinetics = InsulinKineticsAuthority.resolve(
            InsulinKineticsAuthority.ResolveInput(
                accountingIobArray = input.accountingIobArray,
                profileDiaHours = input.profile.dia,
                profilePeakMinutes = input.profile.peakTime,
                effectiveProfile = input.effectiveProfile,
                pkpdRuntime = input.pkpdRuntime,
                peakGovernor = input.peakGovernor,
                causalPosterior = input.causalPosterior,
                physioPeakShiftMinutes = input.physioPeakShiftMinutes,
                sitePeakShiftMinutes = input.sitePeakShiftMinutes,
                trajectoryPeakNudgeMinutes = input.trajectoryPeakNudgeMinutes,
                preferences = input.preferences,
                learningDiagnostics = input.learningDiagnostics,
                predictionIobArray = input.pkpdPredictionIobArray,
            ),
        )
        val runtime = input.pkpdRuntime
        val profileIsf = input.profile.sens
        val fusedIsf = runtime?.fusedIsf ?: profileIsf
        val tddIsf = runtime?.tddIsf ?: fusedIsf
        val pkpdScale = runtime?.pkpdScale ?: 1.0
        val fusionFactor = if (profileIsf > 0.0) fusedIsf / profileIsf else 1.0
        val predAuth = input.predictionAuthority
        val predictions = if (predAuth != null) {
            PredictionAuthorityApplier.fromAuthority(predAuth)
        } else {
            val pkpdEv = input.pkpdEventualMgdl
            PredictionAuthorityView(
                predTerminalMgdl = pkpdEv ?: profileIsf,
                eventualTerminalMgdl = pkpdEv ?: profileIsf,
                pkpdEventualMgdl = pkpdEv,
                scenarioFloorMgdl = null,
                scenarioBestMgdl = null,
                source = null,
            )
        }
        return AimiIntelligenceSnapshot(
            meta = SnapshotMeta(timestampMs = input.timestampMs),
            causal = InsulinKineticsAuthority.buildCausalContext(input.causalPosterior),
            kinetics = kinetics,
            isf = IsfAuthorityView(
                fusedMgdlPerU = fusedIsf,
                profileMgdlPerU = profileIsf,
                tddMgdlPerU = tddIsf,
                pkpdScale = pkpdScale,
                fusionFactor = fusionFactor,
            ),
            predictions = predictions,
            smbPolicy = SmbPolicyContextView(
                tailFraction = kinetics.activity.tailFraction,
                activityStage = kinetics.activity.stage,
                tailDampingEnabled = kinetics.activity.tailFraction >= 0.35,
            ),
            mlFeatures = MlFeatureSliceView(
                fusedIsf = fusedIsf,
                effectiveDiaHours = kinetics.effective.diaHours,
                effectivePeakMinutes = kinetics.effective.peakMinutes,
                tailFraction = kinetics.activity.tailFraction,
                learningQuality = kinetics.learning.learningQuality,
                pkpdScale = pkpdScale,
            ),
        )
    }
}
