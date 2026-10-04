package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt3
import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CausalKineticsModulator
import app.aaps.plugins.aps.openAPSAIMI.pkpd.DiaGovernor
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime

/** `activePlugin.activeBgSource` class name. Throws on failure; the caller logs and treats it as not G6. */
internal fun interface AimiT9G6Source {
    fun isDexcomG6(): Boolean
}

internal fun interface AimiT9G6Lead {
    fun compensate(rawVelocityMgdlMin: Double, isG6: Boolean): Double
}

/** `physioAdapter.getMultipliers`. The Android body keeps its logger and returns neutral on failure. */
internal fun interface AimiT9PhysioMultipliers {
    fun multipliers(bg: Double, delta: Double): PhysioMultipliersMTR
}

/** Detailed physio log. The Android body keeps the consoleError line on failure. */
internal fun interface AimiT9PhysioDetail {
    fun log()
}

/** Early PKPD runtime. Throws; the caller keeps the null fallback and the existing consoleError line. */
internal fun interface AimiT9EarlyRuntime {
    fun compute(earlySens: Double, iobTotal: Double, allowLearning: Boolean): PkPdRuntime?
}

internal fun interface AimiT9Predictions {
    fun eventual(earlySens: Double, runtime: PkPdRuntime?): Double
}

internal fun interface AimiT9Inflammation {
    fun adjustments(): InflammationAdjuster.InflammationResult
}

internal fun interface AimiT9PumpAge {
    fun days(): Float
}

internal fun interface AimiT9ConsoleError {
    fun add(line: String)
}

internal interface AimiT9State {
    fun setBaseMultipliers(value: PhysioMultipliersMTR)
    fun setPkpdRuntime(value: PkPdRuntime?)
    fun pkpdRuntime(): PkPdRuntime?
    fun setEventualBg(value: Double)
    fun setPredictedBg(value: Float)
    fun setVariableSensitivity(value: Float)
    fun maxSmb(): Double
    fun setMaxSmb(value: Double)
    fun maxSmbHb(): Double
    fun setMaxSmbHb(value: Double)
    fun setInflammation(result: InflammationAdjuster.InflammationResult)
    fun causalPosterior(): CausalStatePosterior?
}

internal data class AimiT9PhysioPkpdTubeBootstrap(
    val pumpAgeDays: Float,
    val physioMultipliers: PhysioMultipliersMTR,
)

/**
 * `runT9PhysioEarlyPkpdAndTubeBootstrap`.
 *
 * The G6 class-name lookup used to swallow [Exception] and return false with no line.
 * It still returns false. The failure is `RBT g6Sensor failed …`.
 */
internal fun decideT9PhysioEarlyPkpd(
    profile: OapsProfileAimi,
    glucoseStatus: GlucoseStatusAIMI,
    rT: RT,
    iobTotal: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    consoleError: AimiT9ConsoleError,
    state: AimiT9State,
    g6: AimiT9G6Source,
    lead: AimiT9G6Lead,
    physioMultipliers: AimiT9PhysioMultipliers,
    physioDetail: AimiT9PhysioDetail,
    runtime: AimiT9EarlyRuntime,
    predictions: AimiT9Predictions,
    inflammation: AimiT9Inflammation,
    pumpAge: AimiT9PumpAge,
): AimiT9PhysioPkpdTubeBootstrap {
    val isG6Sensor = readRbtOptional(
        source = "g6Sensor",
        consoleLog = consoleLog,
    ) { g6.isDexcomG6() }.valueOrNull() ?: false
    val rawVelocityMgdlMin = glucoseStatus.delta / 5.0
    val correctedVelocityMgdlMin = lead.compensate(rawVelocityMgdlMin, isG6Sensor)
    val correctedDelta = correctedVelocityMgdlMin * 5.0
    if (isG6Sensor && correctedDelta != glucoseStatus.delta.toDouble()) {
        consoleLog.add(
            "🌐 T9 G6-Lead: delta_raw=${aimiFmt1(glucoseStatus.delta)} → delta_corr=${aimiFmt1(correctedDelta)} mg/dL/5min",
        )
    }
    val multipliers = if (preferences.get(BooleanKey.AimiPhysioAssistantEnable)) {
        physioMultipliers.multipliers(glucoseStatus.glucose, glucoseStatus.delta)
    } else {
        PhysioMultipliersMTR.NEUTRAL
    }
    state.setBaseMultipliers(multipliers)
    if (!multipliers.isNeutral()) {
        consoleLog.add(
            "🏥 PHYSIO: ISF×${aimiFmt3(multipliers.isfFactor)} " +
                "Basal×${aimiFmt3(multipliers.basalFactor)} " +
                "SMB×${aimiFmt3(multipliers.smbFactor)} " +
                "Conf=${(multipliers.confidence * 100).toInt()}%",
        )
    }
    physioDetail.log()
    val earlySens = profile.sens / 1.0
    val singleLearnPath = preferences.get(BooleanKey.OApsAIMIIntelligenceSingleLearnPath)
    val pkpdRuntime = try {
        runtime.compute(earlySens, iobTotal, allowLearning = !singleLearnPath)
    } catch (e: Exception) {
        consoleError.add("❌ Early PKPD Runtime init failed: ${e.message}")
        null
    }
    state.setPkpdRuntime(pkpdRuntime)
    val eventual = predictions.eventual(earlySens, state.pkpdRuntime())
    state.setEventualBg(eventual)
    state.setPredictedBg(eventual.toFloat())
    rT.eventualBG = eventual
    if (!multipliers.isNeutral()) {
        state.setVariableSensitivity((earlySens * multipliers.isfFactor).toFloat())
        profile.max_daily_basal = profile.max_daily_basal * multipliers.basalFactor
        state.setMaxSmb((state.maxSmb() * multipliers.smbFactor).coerceAtLeast(0.1))
        consoleLog.add("🏥 PHYSIO APPLIED: MaxSMB=${aimiFmt2(state.maxSmb())} MaxBasal=${aimiFmt2(profile.max_daily_basal)}")
    }
    val inflamResult = inflammation.adjustments()
    state.setInflammation(inflamResult)
    if (inflamResult.basalMultiplier != 1.0 || inflamResult.smbMultiplier != 1.0) {
        profile.current_basal = profile.current_basal * inflamResult.basalMultiplier
        profile.max_daily_basal = profile.max_daily_basal * inflamResult.basalMultiplier
        val prevMaxSMB = state.maxSmb()
        state.setMaxSmb((state.maxSmb() * inflamResult.smbMultiplier).coerceAtLeast(0.1))
        state.setMaxSmbHb((state.maxSmbHb() * inflamResult.smbMultiplier).coerceAtLeast(0.1))
        consoleLog.add(
            "${inflamResult.reason} -> Basal×${aimiFmt2(inflamResult.basalMultiplier)} SMB: ${aimiFmt2(prevMaxSMB)}->${aimiFmt2(state.maxSmb())}U",
        )
    }
    val pumpAgeDays = pumpAge.days()
    val causalMod = CausalKineticsModulator.modulate(state.causalPosterior())
    val pkpdNow = state.pkpdRuntime()
    if (preferences.get(BooleanKey.OApsAIMIDiaGovernorEnabled)) {
        DiaGovernor.resolve(
            profileDiaHours = profile.dia,
            contextualDiaShiftHours = causalMod.diaShiftHours,
            pkpdLearnedDiaHours = pkpdNow?.params?.diaHrs,
            pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
            governorEnabled = true,
            diaMinBound = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
            diaMaxBound = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
            learnedBlendWeight = preferences.get(DoubleKey.OApsAIMIDiaGovernorLearnedWeight),
        )
    } else {
        pkpdNow?.params?.diaHrs ?: profile.dia
    }
    val peakGovLine = preferences.get(AimiStringKey.OApsAIMIPkpdLastPeakGovLogLine)
    if (peakGovLine.isNotBlank()) {
        val alreadyEchoed = preferences.get(AimiStringKey.OApsAIMIPkpdLastPeakGovConsoleEchoed)
        if (peakGovLine != alreadyEchoed) {
            val clipped = if (peakGovLine.length > 220) peakGovLine.substring(0, 220) + "..." else peakGovLine
            consoleLog.add(clipped)
            preferences.put(AimiStringKey.OApsAIMIPkpdLastPeakGovConsoleEchoed, peakGovLine)
        }
    }
    return AimiT9PhysioPkpdTubeBootstrap(pumpAgeDays, multipliers)
}
