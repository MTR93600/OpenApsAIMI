package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime

/**
 * Android reads of the trajectory, context, ISF and microbolus prep.
 * Each method is the call that already existed at that line.
 * Trajectory analysis, the spiral bridge, the context module and the ISF fusion stay Android shells.
 */
internal data class AimiTrajectoryContextIsfOutcome(
    val sens: Double,
    val baseSensitivity: Double,
    val contextTargetOverride: Double?,
    val dynamicPbolusLarge: Double,
    val dynamicPbolusSmall: Double,
)

internal interface AimiTrajectoryContextPrepCalls {
    fun bg(): Double
    fun delta(): Float
    fun bgacc(): Double
    fun iobActivityNow(): Double
    fun iob(): Float
    fun lastBolusAgeMinutes(): Double
    fun cob(): Float
    fun targetBg(): Float
    fun mealWindow(): Boolean
    fun autosensRatio(): Double
    fun analyzeTrajectory(
        currentTime: Long,
        bg: Double,
        delta: Double,
        bgacc: Double,
        iobActivityNow: Double,
        iob: Float,
        insulinActionState: InsulinActionState,
        lastBolusAgeMinutes: Double,
        cob: Float,
        targetBg: Double,
        profile: OapsProfileAimi,
        rT: RT,
        uiInteraction: UiInteraction,
        relevanceScore: Double,
    )
    fun spiralBridge(
        profile: OapsProfileAimi,
        rT: RT,
        iobData: IobTotal,
        bg: Double,
        delta: Float,
        cob: Float,
        physioMultipliers: PhysioMultipliersMTR,
        tdd24Hrs: Float,
        isExplicitUserAction: Boolean,
        mealClockActiveForSpiralRelax: Boolean,
    )
    fun applyContext(bg: Double, iob: Double, cob: Double, rT: RT): Double?
    fun fuseIsf(
        profile: OapsProfileAimi,
        tdd7Days: Double,
        tdd7P: Double,
        tdd24Hrs: Float,
        pkpdRuntime: PkPdRuntime?,
    ): Double
}

/**
 * `runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep`.
 *
 * A tight spiral defers a lower basal. A zero incoming microbolus is sized from the fused ISF.
 * This head swallows no exception.
 */
internal fun decideTrajectoryContextModuleTddIsfAndDynamicPbolusPrep(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    iobData: IobTotal,
    physioMultipliers: PhysioMultipliersMTR,
    insulinActionState: InsulinActionState,
    pkpdRuntime: PkPdRuntime?,
    tdd7Days: Double,
    tdd7P: Double,
    tdd24Hrs: Float,
    pbolusA: Double,
    pbolusAS: Double,
    reason: StringBuilder,
    isExplicitAdvisorRun: Boolean,
    calls: AimiTrajectoryContextPrepCalls,
): AimiTrajectoryContextIsfOutcome {
    calls.analyzeTrajectory(
        currentTime = ctx.currentTime,
        bg = calls.bg(),
        delta = calls.delta().toDouble(),
        bgacc = calls.bgacc(),
        iobActivityNow = calls.iobActivityNow(),
        iob = calls.iob(),
        insulinActionState = insulinActionState,
        lastBolusAgeMinutes = calls.lastBolusAgeMinutes(),
        cob = calls.cob(),
        targetBg = calls.targetBg().toDouble(),
        profile = profile,
        rT = rT,
        uiInteraction = ctx.uiInteraction,
        relevanceScore = physioMultipliers.trajectoryRelevanceScore,
    )
    calls.spiralBridge(
        profile = profile,
        rT = rT,
        iobData = iobData,
        bg = calls.bg(),
        delta = calls.delta(),
        cob = calls.cob(),
        physioMultipliers = physioMultipliers,
        tdd24Hrs = tdd24Hrs,
        isExplicitUserAction = isExplicitAdvisorRun,
        mealClockActiveForSpiralRelax = calls.mealWindow(),
    )
    val contextTargetOverride = calls.applyContext(
        bg = calls.bg(),
        iob = iobData.iob,
        cob = calls.cob().toDouble(),
        rT = rT,
    )
    val sens = calls.fuseIsf(
        profile = profile,
        tdd7Days = tdd7Days,
        tdd7P = tdd7P,
        tdd24Hrs = tdd24Hrs,
        pkpdRuntime = pkpdRuntime,
    )
    val baseSensitivity = pkpdRuntime?.fusedIsf ?: profile.sens
    val effectiveISF = sens * calls.autosensRatio()
    val dynamicPbolusLarge = if (pbolusA > 0.0) pbolusA else AimiTickPolicyMath.calculateDynamicMicroBolus(effectiveISF, 25.0, reason)
    val dynamicPbolusSmall = if (pbolusAS > 0.0) pbolusAS else AimiTickPolicyMath.calculateDynamicMicroBolus(effectiveISF, 15.0, reason)
    return AimiTrajectoryContextIsfOutcome(
        sens = sens,
        baseSensitivity = baseSensitivity,
        contextTargetOverride = contextTargetOverride,
        dynamicPbolusLarge = dynamicPbolusLarge,
        dynamicPbolusSmall = dynamicPbolusSmall,
    )
}
