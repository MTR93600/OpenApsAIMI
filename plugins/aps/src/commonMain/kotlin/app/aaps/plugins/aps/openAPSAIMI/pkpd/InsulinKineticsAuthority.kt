package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.keys.BooleanKey
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
 *
 * There is one resolution per tick, done by `OpenAPSAIMIPlugin` before `determine_basal`. The same
 * view doses and is written to the log; the snapshot does not resolve again. The causal posterior
 * it uses is the one of the previous tick (see [CausalKineticsContextMemory]), because the posterior
 * of this tick is only computed inside `determine_basal`.
 *
 * Two guards on the causal context (step P1b):
 * - `OApsAIMIDiaGovernorEnabled` and `OApsAIMIPeakGovernorEnabled` are the field off switch of the
 *   context: switch off = no causal DIA (or peak) shift. See [DiaGovernor] and [TapPeakGovernor].
 * - The dosing path limits the change over time with [DosingKineticsRateLimiter]. [resolve] itself
 *   does not limit; it returns the target.
 */
object InsulinKineticsAuthority {

    /**
     * Fixed envelope of the DIA and peak governors: the old defaults of the removed
     * `OApsAIMIPkpdBounds*` keys. Only the PK/PD learner uses the bounds around the profile insulin
     * ([PkpdProfileInsulinBounds]); moving the governors too would change dosing with PK/PD off, so
     * the owner decided to keep this fixed envelope (PLAN.md, step D1). Fixed values also keep a floor
     * when the profile DIA or peak is zero or not a number.
     */
    const val GOVERNOR_DIA_MIN_H = 4.0
    const val GOVERNOR_DIA_MAX_H = 24.0
    const val GOVERNOR_PEAK_MIN_MIN = 30.0
    const val GOVERNOR_PEAK_MAX_MIN = 240.0

    /**
     * Weight of the learned DIA in the DIA governor blend (0 = profile only, 1 = learned only).
     * It was the key `OApsAIMIDiaGovernorLearnedWeight` until step P3; this is its old default.
     */
    const val DIA_LEARNED_BLEND_WEIGHT = 0.45

    /**
     * Weight of the learned peak in the peak governor blend (0 = insulin prior only, 1 = learned
     * only). It was the key `OApsAIMIPeakGovernorLearnedWeight` until step P3; this is its old default.
     */
    const val PEAK_LEARNED_BLEND_WEIGHT = 0.55

    data class ResolveInput(
        val accountingIobArray: Array<IobTotal>,
        val profileDiaHours: Double,
        /** Peak of the insulin in the running profile (`iCfg.peak`). */
        val profilePeakMinutes: Int,
        val pkpdRuntime: PkPdRuntime?,
        /** Causal posterior of the previous tick; null means no context (first tick, or too old). */
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
                peakMin = input.profilePeakMinutes.toDouble(),
            )
        val causalMod = CausalKineticsModulator.modulate(input.causalPosterior)
        val withContext = governors(input, structural, pkpdEnabled, causalMod.diaShiftHours, causalMod.peakShiftMinutes)
        // Same resolution without the causal shifts, only for the log, so the owner can compare.
        val noContext = governors(input, structural, pkpdEnabled, diaShiftHours = 0.0, peakShiftMinutes = 0.0)
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
            effective = withContext.effective,
            noContextEffective = noContext.effective,
            contextReason = causalMod.reason,
            learning = input.learningDiagnostics,
            activity = activityView,
            peakGovernor = withContext.peak,
            diaGovernor = withContext.dia,
            learningTrace = runtime?.learningTrace,
            predictionUsesLearnedKinetics = input.predictionIobArray != null &&
                input.predictionIobArray !== input.accountingIobArray,
        )
    }

    private class Governed(
        val dia: DiaGovernorResult,
        val peak: TapPeakGovernorResult,
        val effective: EffectiveKinetics,
    )

    private fun governors(
        input: ResolveInput,
        structural: PkPdParams,
        pkpdEnabled: Boolean,
        diaShiftHours: Double,
        peakShiftMinutes: Double,
    ): Governed {
        val preferences = input.preferences
        val dia = DiaGovernor.resolve(
            profileDiaHours = input.profileDiaHours,
            contextualDiaShiftHours = diaShiftHours,
            pkpdLearnedDiaHours = if (pkpdEnabled) structural.diaHrs else null,
            pkpdEnabled = pkpdEnabled,
            governorEnabled = preferences.get(BooleanKey.OApsAIMIDiaGovernorEnabled),
            diaMinBound = GOVERNOR_DIA_MIN_H,
            diaMaxBound = GOVERNOR_DIA_MAX_H,
            learnedBlendWeight = DIA_LEARNED_BLEND_WEIGHT,
        )
        val peak = TapPeakGovernor.resolve(
            insulinPeakMinutes = input.profilePeakMinutes,
            physioPeakShiftMinutes = input.physioPeakShiftMinutes,
            sitePeakShiftMinutes = input.sitePeakShiftMinutes,
            // Only a real learned peak; with no runtime there is nothing learned to blend.
            pkpdLearnedPeak = if (pkpdEnabled) input.pkpdRuntime?.params?.peakMin else null,
            pkpdEnabled = pkpdEnabled,
            governorEnabled = preferences.get(BooleanKey.OApsAIMIPeakGovernorEnabled),
            peakMinBound = GOVERNOR_PEAK_MIN_MIN,
            peakMaxBound = GOVERNOR_PEAK_MAX_MIN,
            learnedBlendWeight = PEAK_LEARNED_BLEND_WEIGHT,
            trajectoryMinutesNudge = input.trajectoryPeakNudgeMinutes,
            contextMinutesShift = peakShiftMinutes,
        )
        return Governed(
            dia = dia,
            peak = peak,
            effective = EffectiveKinetics(
                diaHours = dia.effectiveDiaHours,
                peakMinutes = peak.effectivePeakMinutes,
                profileDiaHours = input.profileDiaHours,
                profilePeakMinutes = input.profilePeakMinutes,
            ),
        )
    }

    /**
     * For callers that give `determine_basal` only the dosing DIA and peak, not the whole view (replay
     * engine, simulator). The log must show the numbers that dosed, so [diaHours] and [peakMinutes]
     * replace the effective values of [resolved]. The governor results of [resolved] did not dose, so
     * the view is marked with `governorsDosed = false` and the export leaves them out.
     */
    fun fromDosingValues(resolved: InsulinKineticsView, diaHours: Double, peakMinutes: Double): InsulinKineticsView {
        val dosed = resolved.effective.copy(diaHours = diaHours, peakMinutes = peakMinutes)
        return resolved.copy(
            effective = dosed,
            targetEffective = dosed,
            noContextEffective = dosed,
            contextReason = DOSING_VALUES_ONLY,
            governorsDosed = false,
        )
    }

    /** `contextReason` of a view built by [fromDosingValues]. */
    const val DOSING_VALUES_ONLY = "dosing_values_only"

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
