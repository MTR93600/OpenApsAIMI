package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinKineticsAuthority
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningDiagnostics
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority

/**
 * Builds the intelligence snapshot of a tick. The kinetics are not resolved here: the snapshot logs
 * the view that dosed (see [InsulinKineticsAuthority]).
 */
object AimiIntelligenceSnapshotBuilder {

    data class BuildInput(
        val timestampMs: Long,
        /** The kinetics that dosed this tick, as resolved once by the plugin. */
        val dosingKinetics: InsulinKineticsView,
        val profile: OapsProfileAimi,
        val pkpdRuntime: PkPdRuntime?,
        /**
         * Causal posterior of THIS tick, for the causal block of the log. The kinetics that dosed used
         * the posterior of the previous tick; see [InsulinKineticsView.contextReason].
         */
        val causalPosterior: CausalStatePosterior?,
        val learningDiagnostics: PkpdLearningDiagnostics,
        val predictionAuthority: DecisionPredictionAuthority? = null,
        val pkpdEventualMgdl: Double? = null,
        val exerciseFlag: Boolean = false,
    )

    fun build(input: BuildInput): AimiIntelligenceSnapshot {
        // The learning blocks and the learned (structural) parameters describe this tick, after the
        // learning inside determine_basal, which runs after the plugin resolved the kinetics. The
        // effective DIA and peak, the governors and the activity stay the ones that dosed.
        val kinetics = input.dosingKinetics.copy(
            structural = input.pkpdRuntime?.params ?: input.dosingKinetics.structural,
            learning = input.learningDiagnostics,
            learningTrace = input.pkpdRuntime?.learningTrace ?: input.dosingKinetics.learningTrace,
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
