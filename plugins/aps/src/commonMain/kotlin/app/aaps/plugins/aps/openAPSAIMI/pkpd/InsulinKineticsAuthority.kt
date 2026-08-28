package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.orchestration.CausalContextView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.EffectiveKinetics
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinActivityView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinKineticsView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.toView
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior

/**
 * Single façade for insulin kinetics per tick — accounting vs prediction arrays,
 * structural learned params, and effective DIA/peak after governors + causal modulation.
 */
object InsulinKineticsAuthority {

    data class ResolveInput(
        val accountingIobArray: Array<IobTotal>,
        val profileDiaHours: Double,
        val profilePeakMinutes: Double,
        val effectiveProfile: Profile,
        val pkpdRuntime: PkPdRuntime?,
        val peakGovernor: TapPeakGovernorResult?,
        val causalPosterior: CausalStatePosterior?,
        val physioPeakShiftMinutes: Int = 0,
        val sitePeakShiftMinutes: Double = 0.0,
        val trajectoryPeakNudgeMinutes: Double = 0.0,
        val preferences: Preferences,
        val learningDiagnostics: PkpdLearningDiagnostics,
        /** Pre-built learned-kinetics IOB array (suspend-built by plugin); null → accounting fallback. */
        val predictionIobArray: Array<IobTotal>? = null,
    )

    fun resolve(input: ResolveInput): InsulinKineticsView {
        val pkpdEnabled = input.preferences.get(BooleanKey.OApsAIMIPkpdEnabled)
        val structural = input.pkpdRuntime?.params
            ?: PkPdParams(
                diaHrs = input.profileDiaHours,
                peakMin = input.profilePeakMinutes,
            )
        val causalMod = CausalKineticsModulator.modulate(input.causalPosterior)
        val profileDia = input.profileDiaHours
        val profilePeakMinutes = input.effectiveProfile.iCfg?.peak
            ?: input.profilePeakMinutes.toInt()
        val diaBoundsMin = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
        val diaBoundsMax = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
        val diaGovernor = DiaGovernor.resolve(
            profileDiaHours = profileDia,
            contextualDiaShiftHours = causalMod.diaShiftHours,
            pkpdLearnedDiaHours = if (pkpdEnabled) structural.diaHrs else null,
            pkpdEnabled = pkpdEnabled,
            governorEnabled = input.preferences.get(BooleanKey.OApsAIMIDiaGovernorEnabled),
            diaMinBound = diaBoundsMin,
            diaMaxBound = diaBoundsMax,
            learnedBlendWeight = input.preferences.get(DoubleKey.OApsAIMIDiaGovernorLearnedWeight),
        )
        val peakGovernor = input.peakGovernor ?: TapPeakGovernor.resolve(
            insulinPeakMinutes = profilePeakMinutes,
            physioPeakShiftMinutes = input.physioPeakShiftMinutes,
            sitePeakShiftMinutes = input.sitePeakShiftMinutes,
            pkpdLearnedPeak = if (pkpdEnabled) structural.peakMin else null,
            pkpdEnabled = pkpdEnabled,
            governorEnabled = input.preferences.get(BooleanKey.OApsAIMIPeakGovernorEnabled),
            peakMinBound = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
            peakMaxBound = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
            learnedBlendWeight = input.preferences.get(DoubleKey.OApsAIMIPeakGovernorLearnedWeight),
            trajectoryMinutesNudge = input.trajectoryPeakNudgeMinutes + causalMod.peakShiftMinutes,
        )
        val effective = EffectiveKinetics(
            diaHours = diaGovernor.effectiveDiaHours,
            peakMinutes = peakGovernor.effectivePeakMinutes,
            profileDiaHours = profileDia,
            profilePeakMinutes = profilePeakMinutes,
        )
        val predictionIobArray = input.predictionIobArray ?: input.accountingIobArray
        val runtime = input.pkpdRuntime
        val activityView = if (runtime != null) {
            runtime.activity.toView(runtime.tailFraction * causalMod.tailScale.coerceIn(0.85, 1.25))
        } else {
            InsulinActivityView(
                tailFraction = 0.0,
                stage = ActivityStage.TAIL.name,
                relativeActivity = 0.0,
            )
        }
        return InsulinKineticsView(
            accountingIobArray = input.accountingIobArray,
            predictionIobArray = predictionIobArray,
            structural = structural,
            effective = effective,
            learning = input.learningDiagnostics,
            activity = activityView,
            peakGovernor = peakGovernor,
            diaGovernor = diaGovernor,
            learningTrace = runtime?.learningTrace,
            predictionUsesLearnedKinetics = input.predictionIobArray != null &&
                input.predictionIobArray !== input.accountingIobArray,
        )
    }

    fun buildCausalContext(causalPosterior: CausalStatePosterior?): CausalContextView {
        val mod = CausalKineticsModulator.modulate(causalPosterior)
        return CausalContextView(
            learningQuality = causalPosterior?.learningQuality ?: 1.0,
            learningGatePass = causalPosterior?.learningContextClean() ?: true,
            dominant = causalPosterior?.dominant?.name ?: "UNKNOWN",
            dominantConfidence = causalPosterior?.dominantConfidence ?: 0.0,
            causalModulationReason = mod.reason,
        )
    }
}
