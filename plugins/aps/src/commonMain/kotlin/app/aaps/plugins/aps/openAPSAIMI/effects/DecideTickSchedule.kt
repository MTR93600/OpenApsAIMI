package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps

/**
 * From [buildGlobalAimiBasalScheduleBootstrap] through the already-extracted PKPD guard.
 * Field reads that can move during the slice (`basalaimi`, `variableSensitivity`, `intervalsmb`,
 * meal clocks) stay inside the port, at the call. `sens` is threaded: PAI sees the entry value,
 * PKPD and the SMB stage see the value after the ISF floor.
 */
internal interface AimiTickScheduleCalls<TSmb> {
    fun bootstrap(): AimiScheduleBootstrap
    fun setWorkingTargetMgdl(targetBg: Double)
    fun activityVitals(): AimiScheduleVitals
    fun pai(profileCurrentBasal: Double, paiBaseSensitivity: Double): AimiSchedulePai
    fun endoAndActivity()
    fun isfAfterEndo(): Double
    fun auditorTarget(workingTargetRawMgdl: Double)
    fun tightSpiralCapIfNeeded()
    fun pkpdTargets(
        sens: Double,
        minDelta: Double,
        minAvgDelta: Double,
        minBg: Double,
        targetBg: Double,
        maxBg: Double,
    ): AimiSchedulePkpdTargets

    fun uam(targetBg: Double, postHypoState: PostHypoState): Float
    fun smb(
        targetBg: Double,
        basal: Double,
        sens: Double,
        pumpCaps: PumpCaps,
        profileCurrentBasal: Double,
        modelcal: Float,
    ): AimiScheduleSmb<TSmb>

    fun applySmb(execution: TSmb, assignBasal: (Double) -> Unit): Float
    fun pkpdGuard(
        execution: TSmb,
        isMealAdvisorOneShot: Boolean,
        smbToGive: Float,
        targetBg: Double,
    ): AimiSchedulePkpdGuard
}

internal data class AimiScheduleBootstrap(
    val pumpCaps: PumpCaps,
    val profileCurrentBasal: Double,
    val basal: Double,
    val targetBg: Double,
    val minBg: Double,
    val maxBg: Double,
    val sensitivityRatio: Double,
    val deliverAt: Long,
    val maxIobLimit: Double,
)

internal data class AimiScheduleVitals(
    val tick: String,
    val minDelta: Double,
    val minAvgDelta: Double,
)

internal data class AimiSchedulePai(
    val timenowHour: Int,
    val sixAmHour: Int,
    val pregnancyEnable: Boolean,
)

internal data class AimiSchedulePkpdTargets(
    val bgi: Double,
    val deviation: Int,
    val minBg: Double,
    val targetBg: Double,
    val maxBg: Double,
)

internal data class AimiScheduleSmb<TSmb>(
    val execution: TSmb,
    val isMealAdvisorOneShot: Boolean,
)

internal data class AimiSchedulePkpdGuard(
    val smbToGive: Float,
    val intervalSmb: Int,
)

internal data class AimiTickScheduleOutcome(
    val pumpCaps: PumpCaps,
    val profileCurrentBasal: Double,
    val basal: Double,
    val targetBg: Double,
    val minBg: Double,
    val maxBg: Double,
    val sensitivityRatio: Double,
    val deliverAt: Long,
    val maxIobLimit: Double,
    val tick: String,
    val minDelta: Double,
    val minAvgDelta: Double,
    val timenowHour: Int,
    val sixAmHour: Int,
    val pregnancyEnable: Boolean,
    val sens: Double,
    val bgi: Double,
    val deviation: Int,
    val isMealAdvisorOneShot: Boolean,
    val smbToGive: Float,
    val intervalSmb: Int,
)

/**
 * Schedule bootstrap, PAI, endo/activity, PKPD targets, UAM predicted SMB, SMB instruction,
 * then the PKPD absorption guard. The guard itself stays the already-extracted function,
 * called here as a port.
 */
internal fun <TSmb> decideDetermineBasalTickSchedule(
    initialSens: Double,
    postHypoState: PostHypoState,
    calls: AimiTickScheduleCalls<TSmb>,
): AimiTickScheduleOutcome {
    val bootstrap = calls.bootstrap()
    var basal = bootstrap.basal
    val targetAtBootstrap = bootstrap.targetBg
    // The target the engine really works with, before any later adjustment. A tick that ends
    // earlier never reaches this line. The ISF floor is decided below; this capture is still
    // the pre-adjustment value.
    calls.setWorkingTargetMgdl(targetAtBootstrap)

    val vitals = calls.activityVitals()
    val pai = calls.pai(
        profileCurrentBasal = bootstrap.profileCurrentBasal,
        paiBaseSensitivity = initialSens,
    )
    calls.endoAndActivity()
    val sens = calls.isfAfterEndo()
    calls.auditorTarget(workingTargetRawMgdl = targetAtBootstrap)
    calls.tightSpiralCapIfNeeded()

    val pkpd = calls.pkpdTargets(
        sens = sens,
        minDelta = vitals.minDelta,
        minAvgDelta = vitals.minAvgDelta,
        minBg = bootstrap.minBg,
        targetBg = targetAtBootstrap,
        maxBg = bootstrap.maxBg,
    )
    val modelcal = calls.uam(
        targetBg = pkpd.targetBg,
        postHypoState = postHypoState,
    )
    val smb = calls.smb(
        targetBg = pkpd.targetBg,
        basal = basal,
        sens = sens,
        pumpCaps = bootstrap.pumpCaps,
        profileCurrentBasal = bootstrap.profileCurrentBasal,
        modelcal = modelcal,
    )
    val smbToGive = calls.applySmb(smb.execution) { basal = it }
    val guarded = calls.pkpdGuard(
        execution = smb.execution,
        isMealAdvisorOneShot = smb.isMealAdvisorOneShot,
        smbToGive = smbToGive,
        targetBg = pkpd.targetBg,
    )
    return AimiTickScheduleOutcome(
        pumpCaps = bootstrap.pumpCaps,
        profileCurrentBasal = bootstrap.profileCurrentBasal,
        basal = basal,
        targetBg = pkpd.targetBg,
        minBg = pkpd.minBg,
        maxBg = pkpd.maxBg,
        sensitivityRatio = bootstrap.sensitivityRatio,
        deliverAt = bootstrap.deliverAt,
        maxIobLimit = bootstrap.maxIobLimit,
        tick = vitals.tick,
        minDelta = vitals.minDelta,
        minAvgDelta = vitals.minAvgDelta,
        timenowHour = pai.timenowHour,
        sixAmHour = pai.sixAmHour,
        pregnancyEnable = pai.pregnancyEnable,
        sens = sens,
        bgi = pkpd.bgi,
        deviation = pkpd.deviation,
        isMealAdvisorOneShot = smb.isMealAdvisorOneShot,
        smbToGive = guarded.smbToGive,
        intervalSmb = guarded.intervalSmb,
    )
}
