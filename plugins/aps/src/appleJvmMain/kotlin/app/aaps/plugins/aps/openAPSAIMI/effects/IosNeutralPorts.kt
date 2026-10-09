package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.ui.AlarmSound
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aps.openAPSAIMI.AsyncDataState
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.MealAggressionContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdBolusSample
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.DoubleComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.physio.EndogenousPhaseHysteresis
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseHysteresis
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternHysteresis
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.safety.resolveSafetyStart
import app.aaps.plugins.aps.openAPSAIMI.scenario.InsulinSlopePreserveHysteresis
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.StateFlow

/**
 * Mode lines of the approved iOS-neutral ports. Each port writes its own line. Nothing here
 * catches an exception.
 */
object IosNeutralLog {
    /** Default. The process singletons survive the tick, as on Android. */
    const val HYSTERESIS = "IOS_NEUTRAL hysteresis=android-lifecycle"

    /** Test option only. `evaluate` does not write this line. */
    const val HYSTERESIS_RESET_TEST = "IOS_NEUTRAL hysteresis=reset"
    const val EFFORT = "IOS_NEUTRAL effortSmbFactor=1.0"
    const val VETO = "IOS_NEUTRAL effortVeto=false"
    const val PATIENT = "IOS_NEUTRAL patientRuntime=skipped"
    const val TPO = "IOS_NEUTRAL tpo=skipped"
    const val WEARABLE = "IOS_NEUTRAL wearable snapshot empty"
    const val PKPD = "IOS_NEUTRAL pkpdFloor stored doseReread=false"

    fun earlyScratch(writes: Int): String = "IOS_NEUTRAL earlyScratch writes=$writes"
}

/** Thirty minutes, the duration locked on the meal, sport, night and low-prediction scenes. */
internal const val IOS_NEUTRAL_TBR_DURATION_MS: Long = 30L * 60L * 1000L

/**
 * Assignments in `DetermineBasalAIMI2` `resetEarlyScratch` on this branch.
 * The memo said 29. The adapter has these 27. The decision is this list.
 */
internal const val IOS_EARLY_SCRATCH_WRITE_COUNT: Int = 27

/**
 * Same writes as the Android early-scratch adapter. Does not reset hysteresis singletons.
 * Android `DetermineBasalAIMI2` is not this class and is not edited.
 */
class IosEarlyTickScratch {
    var exerciseInsulinLockoutActive: Boolean = true
    var exerciseHyperBasalOverrideActive: Boolean = true
    var aimiContextActivityActive: Boolean = true
    var pkpdAbsorptionGuardAppliedThisTick: Boolean = true
    var criticalSafetyZeroedThisTick: Boolean = true
    var cachedRiskEnvelopeEarly: Any? = STALE
    var cachedRiskEnvelopeDecision: Any? = STALE
    var lastSafetyRiskExport: Any? = STALE
    var lastScenarioProjection: Any? = STALE
    var lastPredDivergenceExport: Any? = STALE
    var lastDecisionPredictionAuthority: Any? = STALE
    var lastIntelligenceSnapshot: Any? = STALE
    var lastPredictionAuthorityApplyResult: Any? = STALE
    var lastDoseTerminalSnapshot: Any? = STALE
    var lastPkpdSoftFloorTelemetry: PkpdSoftFloorTelemetry? = staleFloor()
    var tubeDoseBaseline: Any? = STALE
    var tubeAppliedFromDoseSnapshotThisTick: Boolean = true
    var isConfirmedHighRiseThisTick: Boolean = true
    var correctionAggressionDecision: Any? = STALE
    var mealAdvisorOneShotThisTick: Boolean = true
    var lastTubeAdvisorSmbCapScale: Any? = STALE
    var lastTubeAdvisorTrace: Any? = STALE
    var lastInflammationResult: Any? = STALE
    var tickInsulinActionState: Any? = STALE
    var tickEffectiveDiaHours: Double = -1.0
    var tickEffectivePeakMinutes: Double = -1.0
    var lastLoopCgmNoise: Int = -1

    fun reset(effectiveDiaHours: Double, effectivePeakMinutes: Double, noise: Int): Int {
        var writes = 0
        exerciseInsulinLockoutActive = false
        writes += 1
        exerciseHyperBasalOverrideActive = false
        writes += 1
        aimiContextActivityActive = false
        writes += 1
        pkpdAbsorptionGuardAppliedThisTick = false
        writes += 1
        criticalSafetyZeroedThisTick = false
        writes += 1
        cachedRiskEnvelopeEarly = null
        writes += 1
        cachedRiskEnvelopeDecision = null
        writes += 1
        lastSafetyRiskExport = null
        writes += 1
        lastScenarioProjection = null
        writes += 1
        lastPredDivergenceExport = null
        writes += 1
        lastDecisionPredictionAuthority = null
        writes += 1
        lastIntelligenceSnapshot = null
        writes += 1
        lastPredictionAuthorityApplyResult = null
        writes += 1
        lastDoseTerminalSnapshot = null
        writes += 1
        lastPkpdSoftFloorTelemetry = null
        writes += 1
        tubeDoseBaseline = null
        writes += 1
        tubeAppliedFromDoseSnapshotThisTick = false
        writes += 1
        isConfirmedHighRiseThisTick = false
        writes += 1
        correctionAggressionDecision = null
        writes += 1
        mealAdvisorOneShotThisTick = false
        writes += 1
        lastTubeAdvisorSmbCapScale = null
        writes += 1
        lastTubeAdvisorTrace = null
        writes += 1
        lastInflammationResult = null
        writes += 1
        tickInsulinActionState = null
        writes += 1
        tickEffectiveDiaHours = effectiveDiaHours
        writes += 1
        tickEffectivePeakMinutes = effectivePeakMinutes
        writes += 1
        lastLoopCgmNoise = noise
        writes += 1
        check(writes == IOS_EARLY_SCRATCH_WRITE_COUNT) {
            "IOS_NEUTRAL earlyScratch wrote $writes, expected $IOS_EARLY_SCRATCH_WRITE_COUNT"
        }
        return writes
    }

    private companion object {
        val STALE = Any()

        fun staleFloor(): PkpdSoftFloorTelemetry = PkpdSoftFloorTelemetry(
            rawPathMinMgdl = 1.0,
            softPathMinMgdl = 1.0,
            hybridTerminalMgdl = 1.0,
            hitNumericFloor = false,
            applied = true,
            endogenousReversionEnabled = true,
            suppressedByFallingTrend = true,
            reason = "stale",
        )
    }
}

/**
 * iOS tick start. Reproduces the Android lifecycle: the five hysteresis singletons are not reset.
 * A hold set by the previous tick is still there.
 */
fun iosNeutralTickStart(log: MutableList<String>) {
    log += IosNeutralLog.HYSTERESIS
}

/**
 * Test-only reset of the five process singletons.
 * Not called from [iosNeutralTickStart] and not called from [IosNeutralAimiEngine.evaluate].
 */
fun iosNeutralResetHysteresisForTest(log: MutableList<String>) {
    MealAbsorptionPhaseHysteresis.reset()
    MealAbsorptionMemory.reset()
    EndogenousPhaseHysteresis.reset()
    PhysiologicalPatternHysteresis.reset()
    InsulinSlopePreserveHysteresis.reset()
    log += IosNeutralLog.HYSTERESIS_RESET_TEST
}

fun iosNeutralEffortSmbFactor(
    log: MutableList<String>,
    snapshot: HealthContextSnapshot = HealthContextSnapshot(),
): Double {
    val refresh = decideRefreshEffortActivityBelief(
        protectionEnabled = false,
        t3cEnabled = false,
        snapshot = snapshot,
        nowMs = aimiWallClockMs(),
        stressResistanceProb = 0.0,
        prior = EffortActivityBelief.Memory(),
    )
    val factor = refresh.smbFactor
    check(refresh.assessment == null)
    check(factor == 1.0)
    log += IosNeutralLog.EFFORT
    return factor
}

fun iosNeutralEffortVeto(log: MutableList<String>): Boolean {
    val veto = false
    log += IosNeutralLog.VETO
    return veto
}

fun iosNeutralPatientRuntimeSkipped(log: MutableList<String>) {
    log += IosNeutralLog.PATIENT
}

fun iosNeutralTpoSkipped(log: MutableList<String>) {
    log += IosNeutralLog.TPO
}

fun iosNeutralEmptyWearable(log: MutableList<String>): HealthContextSnapshot {
    val snapshot = HealthContextSnapshot()
    log += IosNeutralLog.WEARABLE
    return snapshot
}

/**
 * Same write as the Android shell: the tick field and the log line.
 * The returned object is not a dose. Callers must not pass it into [iosNeutralLowPredictionTbrUph].
 */
fun iosNeutralStorePkpdFloor(
    curves: AdvancedPredictionCurves,
    memory: IosPkpdFloorMemory,
    scratch: IosEarlyTickScratch,
    log: MutableList<String>,
): PkpdSoftFloorTelemetry {
    return decideRecordPkpdSoftFloor(
        curves = curves,
        endogenousReversionEnabled = false,
        calls = object : AimiPkpdSoftFloorWrite {
            override fun writeTelemetryAndLog(telemetry: PkpdSoftFloorTelemetry) {
                memory.telemetry = telemetry
                scratch.lastPkpdSoftFloorTelemetry = telemetry
                log += PkpdSoftFloorPathMin.formatLogLine(telemetry)
                log += IosNeutralLog.PKPD
            }
        },
    )
}

class IosPkpdFloorMemory {
    var telemetry: PkpdSoftFloorTelemetry? = null
}

/** Locked low-prediction scene: BG 100, pred 39, eventual ~43.78, basal 1 U/h, threshold 70. */
fun iosNeutralLowPredictionTbrUph(): Double {
    val resolution = resolveSafetyStart(
        bg = 100.0,
        delta = 0f,
        noise = 0,
        predBg = 39.0,
        eventualBg = 43.78040816326531,
        currentBasalUph = 1.0,
        lgsThreshold = 70,
    )
    val applied = resolution.decision as? DecisionResult.Applied
        ?: error("IOS_NEUTRAL low prediction did not apply: ${resolution.decision.reason}")
    return applied.tbrUph ?: error("IOS_NEUTRAL low prediction has no TBR")
}

/** Locked sport scene: profile basal 1 U/h times 1.3, safety not overridden. */
fun iosNeutralSportTbrUph(): Double {
    return decideCalculateRate(
        basal = 1.0,
        currentBasal = 1.0,
        multiplier = 1.3,
        reason = "SportTime",
        currentTemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = 0),
        rT = RT(runningDynamicIsf = false),
        overrideSafety = false,
    )
}

/** Locked night scene: profile basal 1 U/h times 1. The enacted dose is only this TBR. */
fun iosNeutralNightTbrUph(): Double {
    return decideCalculateRate(
        basal = 1.0,
        currentBasal = 1.0,
        multiplier = 1.0,
        reason = "Night",
        currentTemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = 0),
        rT = RT(runningDynamicIsf = false),
        overrideSafety = false,
    )
}

/**
 * Locked fasting scene: fastingTime, BG 110, delta +2, target 100, COB 0.
 * Same inputs as the Android `ShellDecisionTraceTest.captureFasting`, which locks
 * `tbrRate=4000000000000000` (2.0 U/h) with no duration and no SMB.
 */
fun iosNeutralFastingMealHyper(log: MutableList<String>): AimiMealHyperBasalBoostOutcome {
    val preferences = IosNeutralMealPreferences().apply {
        doubles[DoubleKey.meal_modes_MaxBasal.key] = 0.0
    }
    return decideMealHyperBasalBoost(
        profile = iosNeutralProfile(),
        rT = RT(runningDynamicIsf = false),
        basal = 1.0,
        profileCurrentBasal = 1.0,
        isMealAdvisorOneShot = false,
        targetBg = 100.0,
        timeSinceEstimateMin = Double.MAX_VALUE,
        estimatedCarbs = 0.0,
        currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
        preferences = preferences,
        consoleLog = log,
        fields = object : AimiMealHyperFields {
            override fun snackTime() = false
            override fun snackRunTime() = 0L
            override fun delta() = 2.0f
            override fun mealTime() = false
            override fun lunchTime() = false
            override fun dinnerTime() = false
            override fun highCarbTime() = false
            override fun bfastTime() = false
            override fun mealRuntime() = 0L
            override fun lunchRuntime() = 0L
            override fun dinnerRuntime() = 0L
            override fun highCarbRunTime() = 0L
            override fun bfastRuntime() = 0L
            override fun bg() = 110.0
            override fun shortAvgDelta() = 0.0
            override fun mealAbsorption() = null
            override fun cob() = 0.0f
            override fun phase() = null
            override fun hyperReleaseActive() = false
            override fun aggression() = null
            override fun basalFirstActive() = false
            override fun fragileBg() = false
            override fun fastingTime() = true
        },
        basalCap = AimiBasalCap { _, _, _ ->
            error("IOS_NEUTRAL fasting: unexpected basal cap")
        },
        tempBasal = AimiMealHyperTempBasal { _, _, _, _, _, _, _ ->
            error("IOS_NEUTRAL fasting: unexpected setTempBasal")
        },
        clock = AimiMealHyperClock {
            error("IOS_NEUTRAL fasting: unexpected clock read")
        },
    )
}

/**
 * Locked meal advisor scene: 40 g, IC 10, IOB 1.0, max basal 2.0.
 * [effortFactor] is the neutral port (1.0). It scales the SMB and does not invent a second formula.
 */
fun iosNeutralMealAdvisor(effortFactor: Double, log: MutableList<String>): DecisionResult.Applied {
    val preferences = IosNeutralMealPreferences().apply {
        doubles[DoubleKey.OApsAIMILastEstimatedCarbs.key] = 40.0
        doubles[DoubleKey.OApsAIMILastEstimatedCarbTime.key] = aimiWallClockMs().toDouble()
        doubles[DoubleKey.meal_modes_MaxBasal.key] = 2.0
    }
    val result = decideTryMealAdvisor(
        bg = 180.0,
        delta = 0f,
        iobData = IobTotal(time = 0L, iob = 1.0),
        profile = iosNeutralProfile(),
        lastBolusTime = 0L,
        modesCondition = true,
        isExplicitTrigger = true,
        hasRecentBolus45m = false,
        preferences = preferences,
        consoleLog = log,
        logger = PortLogLogger(log),
    )
    val applied = result as? DecisionResult.Applied
        ?: error("IOS_NEUTRAL meal advisor did not apply: ${result.reason}")
    val bolus = applied.bolusU ?: error("IOS_NEUTRAL meal advisor has no SMB")
    return applied.copy(bolusU = bolus * effortFactor)
}

/** Curves that publish the locked floor: raw 39, soft 39, hybrid terminal 39, reversion off. */
fun iosNeutralFloorCurves(): AdvancedPredictionCurves = AdvancedPredictionCurves(
    iob = listOf(100.0, 39.0),
    cob = listOf(39.0),
    uam = listOf(39.0),
    zt = listOf(39.0),
    hybrid = listOf(39.0),
    insulinPathMinRawMgdl = 39.0,
)

internal fun iosNeutralProfile(): OapsProfileAimi = OapsProfileAimi(
    dia = 5.0,
    min_5m_carbimpact = 0.0,
    max_iob = 10.0,
    max_daily_basal = 1.0,
    max_basal = 2.0,
    min_bg = 90.0,
    max_bg = 150.0,
    target_bg = 100.0,
    carb_ratio = 10.0,
    sens = 50.0,
    autosens_adjust_targets = false,
    max_daily_safety_multiplier = 1.0,
    current_basal_safety_multiplier = 1.0,
    high_temptarget_raises_sensitivity = false,
    low_temptarget_lowers_sensitivity = false,
    sensitivity_raises_target = false,
    resistance_lowers_target = false,
    adv_target_adjustments = false,
    exercise_mode = false,
    half_basal_exercise_target = 160,
    maxCOB = 120,
    skip_neutral_temps = false,
    remainingCarbsCap = 0,
    enableUAM = false,
    A52_risk_enable = false,
    SMBInterval = 3,
    enableSMB_with_COB = false,
    enableSMB_with_temptarget = false,
    allowSMB_with_high_temptarget = false,
    enableSMB_always = false,
    enableSMB_after_carbs = false,
    maxSMBBasalMinutes = 30,
    maxUAMSMBBasalMinutes = 30,
    bolus_increment = 0.1,
    carbsReqThreshold = 1,
    current_basal = 1.0,
    temptargetSet = false,
    autosens_max = 1.2,
    out_units = "mg/dl",
    lgsThreshold = 70,
    variable_sens = 50.0,
    insulinDivisor = 1,
    TDD = 40.0,
    peakTime = 75.0,
    futureActivity = 0.0,
    sensorLagActivity = 0.0,
    historicActivity = 0.0,
    currentActivity = 0.0,
)

internal class IosNeutralMealPreferences : Preferences {
    val doubles: MutableMap<String, Double> = mutableMapOf()

    override val simpleMode: Boolean = false
    override val apsMode: Boolean = true
    override val nsclientMode: Boolean = false
    override val pumpControlMode: Boolean = false

    override fun get(key: DoublePreferenceKey): Double = requireDouble(key.key)
    override fun get(key: DoubleNonPreferenceKey): Double = requireDouble(key.key)
    override fun put(key: DoubleNonPreferenceKey, value: Double) {
        doubles[key.key] = value
    }
    override fun put(key: BooleanNonPreferenceKey, value: Boolean) = Unit

    override fun get(key: BooleanNonPreferenceKey): Boolean = unexpected(key.key)
    override fun getIfExists(key: BooleanNonPreferenceKey): Boolean? = unexpected(key.key)
    override fun observe(key: BooleanNonPreferenceKey): StateFlow<Boolean> = unexpected(key.key)
    override fun get(key: BooleanPreferenceKey): Boolean = unexpected(key.key)
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = unexpected(key.key)
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, defaultValue: Boolean): Boolean =
        unexpected(key.key)
    override fun getIfExists(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean? = unexpected(key.key)
    override fun put(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, value: Boolean) = unexpected(key.key)
    override fun observe(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Boolean> =
        unexpected(key.key)
    override fun remove(key: ComposedKey, vararg arguments: Any) = unexpected(key.key)
    override fun get(key: StringNonPreferenceKey): String = unexpected(key.key)
    override fun getIfExists(key: StringNonPreferenceKey): String? = unexpected(key.key)
    override fun put(key: StringNonPreferenceKey, value: String) = unexpected(key.key)
    override fun observe(key: StringNonPreferenceKey): StateFlow<String> = unexpected(key.key)
    override fun get(key: StringPreferenceKey): String = unexpected(key.key)
    override fun get(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = unexpected(key.key)
    override fun getIfExists(key: StringComposedNonPreferenceKey, vararg arguments: Any): String? = unexpected(key.key)
    override fun put(key: StringComposedNonPreferenceKey, vararg arguments: Any, value: String) = unexpected(key.key)
    override fun observe(key: StringComposedNonPreferenceKey, vararg arguments: Any): StateFlow<String> =
        unexpected(key.key)
    override fun getIfExists(key: DoublePreferenceKey): Double? = unexpected(key.key)
    override fun observe(key: DoubleNonPreferenceKey): StateFlow<Double> = unexpected(key.key)
    override fun get(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = unexpected(key.key)
    override fun getIfExists(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double? = unexpected(key.key)
    override fun put(key: DoubleComposedNonPreferenceKey, vararg arguments: Any, value: Double) = unexpected(key.key)
    override fun observe(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Double> =
        unexpected(key.key)
    override fun get(key: UnitDoublePreferenceKey): Double = unexpected(key.key)
    override fun getIfExists(key: UnitDoublePreferenceKey): Double? = unexpected(key.key)
    override fun put(key: UnitDoublePreferenceKey, value: Double) = unexpected(key.key)
    override fun observe(key: UnitDoublePreferenceKey): StateFlow<Double> = unexpected(key.key)
    override fun get(key: IntNonPreferenceKey): Int = unexpected(key.key)
    override fun getIfExists(key: IntNonPreferenceKey): Int? = unexpected(key.key)
    override fun put(key: IntComposedNonPreferenceKey, vararg arguments: Any, value: Int) = unexpected(key.key)
    override fun put(key: IntNonPreferenceKey, value: Int) = unexpected(key.key)
    override fun observe(key: IntNonPreferenceKey): StateFlow<Int> = unexpected(key.key)
    override fun inc(key: IntNonPreferenceKey) = unexpected(key.key)
    override fun get(key: IntComposedNonPreferenceKey, vararg arguments: Any): Int = unexpected(key.key)
    override fun observe(key: IntComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Int> = unexpected(key.key)
    override fun get(key: IntPreferenceKey): Int = unexpected(key.key)
    override fun get(key: LongNonPreferenceKey): Long = unexpected(key.key)
    override fun getIfExists(key: LongNonPreferenceKey): Long? = unexpected(key.key)
    override fun put(key: LongNonPreferenceKey, value: Long) = unexpected(key.key)
    override fun observe(key: LongNonPreferenceKey): StateFlow<Long> = unexpected(key.key)
    override fun get(key: LongPreferenceKey): Long = unexpected(key.key)
    override fun inc(key: LongNonPreferenceKey) = unexpected(key.key)
    override fun get(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = unexpected(key.key)
    override fun getIfExists(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long? = unexpected(key.key)
    override fun put(key: LongComposedNonPreferenceKey, vararg arguments: Any, value: Long) = unexpected(key.key)
    override fun observe(key: LongComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Long> = unexpected(key.key)
    override fun remove(key: NonPreferenceKey) = unexpected(key.key)
    override fun isUnitDependent(key: String): Boolean = unexpected(key)
    override fun get(key: String): NonPreferenceKey? = unexpected(key)
    override fun getIfExists(key: String): NonPreferenceKey? = unexpected(key)
    override fun registerPreferences(keys: List<NonPreferenceKey>) = unexpected("register")
    override fun allMatchingStrings(key: ComposedKey): List<String> = unexpected(key.key)
    override fun allMatchingInts(key: ComposedKey): List<Int> = unexpected(key.key)
    override fun isExportableKey(key: String): Boolean = unexpected(key)
    override fun getAllPreferenceKeys(): List<PreferenceKey> = unexpected("all")

    private fun requireDouble(key: String): Double =
        doubles[key] ?: error("IOS_NEUTRAL preferences: missing $key")

    private fun unexpected(key: String): Nothing =
        error("IOS_NEUTRAL preferences: unexpected $key")
}

private class PortLogLogger(private val log: MutableList<String>) : AAPSLogger {
    override fun debug(tag: LTag, message: String) {
        log += message
    }

    override fun debug(message: String) = Unit
    override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, accessor: () -> String) = Unit
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun warn(tag: LTag, message: String) = Unit
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun info(tag: LTag, message: String) = Unit
    override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(tag: LTag, message: String) = Unit
    override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
    override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(message: String) = Unit
    override fun error(message: String, throwable: Throwable) = Unit
    override fun error(format: String, vararg arguments: Any?) = Unit
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
}

/** Pinned clock for the sensor-gap scene, the same `now` as the Android trace test. */
internal const val IOS_NEUTRAL_SENSOR_GAP_NOW_MS: Long = 1_700_000_000_000L

/**
 * Locked sensor-gap scene: glucose 110 mg/dL stamped 25 min old.
 * Same inputs as the Android `ShellDecisionTraceTest.captureSensorGap`, which locks
 * `tbrRate=absent`, `smb=absent`, `eventual=110.0` — the tick aborts on stale data
 * (`minAgo=25.0 > 12.0`).
 */
fun iosNeutralSensorGapSignal(log: MutableList<String>): AimiSignalPrepPkpd {
    val nowMs = IOS_NEUTRAL_SENSOR_GAP_NOW_MS
    val preferences = IosNeutralMealPreferences().apply {
        doubles[DoubleKey.OApsAIMIautodrivesmallPrebolus.key] = 0.0
        doubles[DoubleKey.OApsAIMIautodrivePrebolus.key] = 0.0
    }
    val ctx = AimiTickContext(
        glucoseStatus = GlucoseStatusAIMI(
            glucose = 110.0,
            delta = 0.0,
            shortAvgDelta = 0.0,
            longAvgDelta = 0.0,
            date = nowMs - 25L * 60L * 1000L,
        ),
        currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
        iobDataArray = arrayOf(IobTotal(time = nowMs, iob = 1.0, lastBolusTime = 0L)),
        profile = iosNeutralProfile(),
        // The Android test leaves the AutosensResult mock unstubbed, so ratio reads 0.0.
        // Not read on the abort path; kept for faithfulness.
        autosensData = AutosensResult().also { it.ratio = 0.0 },
        mealData = MealData(mealCOB = 0.0),
        microBolusAllowed = true,
        currentTime = nowMs,
        flatBGsDetected = false,
        dynIsfMode = false,
        uiInteraction = SensorGapSilentUi,
        extraDebug = "",
    )
    return decideSignalPreparationPkpdRuntime(
        ctx = ctx,
        profile = iosNeutralProfile(),
        rT = RT(runningDynamicIsf = false),
        glucoseStatus = ctx.glucoseStatus,
        combinedDelta = 0.0f,
        // Only feeds the tdd24Hrs fallback; the abort does not read it.
        tdd7P = 40.0,
        isExplicitAdvisorRun = false,
        isConfirmedHighRiseLocal = false,
        pkpdRuntimeIn = null,
        // Never touched on the abort path; a neutral instance keeps the call honest.
        pkpdIntegration = PkPdIntegration(
            preferences,
            PkPdLearnedState(),
            object : AimiBehaviorProfileSource {
                override fun read(preferences: Preferences): AimiBehaviorRuntimeProfile =
                    AimiBehaviorRuntimeProfile(
                        protectionLevel = 0,
                        mealCaptureLevel = 0,
                        stabilityLevel = 0,
                        physioLevel = 0,
                        autonomyMode = AimiAutonomyMode.Observation,
                    )
            },
        ),
        preferences = preferences,
        consoleLog = log,
        calls = object : AimiSignalPrepPkpdCalls {
            override fun mealTime() = false
            override fun mealRuntime() = 0L
            override fun lunchTime() = false
            override fun lunchRuntime() = 0L
            override fun bfastTime() = false
            override fun bfastRuntime() = 0L
            override fun dinnerTime() = false
            override fun dinnerRuntime() = 0L
            override fun sportTime() = false
            override fun snackTime() = false
            override fun snackRuntime() = 0L
            override fun highCarbTime() = false
            override fun highCarbRuntime() = 0L
            override fun sleepTime() = false
            override fun lowCarbTime() = false
            override fun recentBgs(): List<Float> = emptyList()
            override fun nowMs() = nowMs
            override fun bolusesSince(startMs: Long, ascending: Boolean): List<BS> = emptyList()
            override fun calculateBgTrend(recentBGs: List<Float>, reason: StringBuilder) = Unit
            override fun studyExporter(): HormonitorStudyExporter? = null
            override fun bg() = 110.0
            override fun predictedBg() = 110.0f
            override fun delta() = 0.0f
            override fun shortAvgDelta() = 0.0f
            override fun longAvgDelta() = 0.0f
            override fun iob() = 1.0f
            override fun cob() = 0.0f
            override fun maxSmb() = 0.0
            override fun targetBg() = 100.0f
            override fun lateFatProteinRise(
                bg: Double,
                predictedBg: Double,
                delta: Double,
                shortAvgDelta: Double,
                longAvgDelta: Double,
                iob: Double,
                cob: Double,
                maxSmb: Double,
                lastBolusTimeMs: Long?,
                mealTime: Boolean,
                bfastTime: Boolean,
                lunchTime: Boolean,
                dinnerTime: Boolean,
                highCarbTime: Boolean,
            ) = false
            override fun setLateFatRiseFlag(value: Boolean) = Unit
            override fun tdd24hState(): AsyncDataState<Double> =
                AsyncDataState.Missing("ios-neutral")
            override fun noteStaleData(minAgo: Double) {
                log += "IOS_NEUTRAL sensor-gap stale ${minAgo}m"
            }
            override fun logDecisionFinal(tag: String, rT: RT, bg: Double, delta: Float) {
                log += "IOS_NEUTRAL sensor-gap $tag bg=$bg"
            }
            override fun ensurePredictionFallback(rT: RT, bg: Double) {
                if (rT.predBGs == null) {
                    val safeBg = bg.roundToInt()
                    rT.predBGs = Predictions().apply {
                        IOB = listOf(safeBg)
                        COB = listOf(safeBg)
                        ZT = listOf(safeBg)
                        UAM = listOf(safeBg)
                    }
                }
                if (rT.eventualBG == null) {
                    rT.eventualBG = bg
                }
            }
            override fun markFinalLoopDecision(rT: RT) = Unit
            // Everything below runs after the staleness check; the abort never reaches it.
            override fun internalLastSmbMillis(): Long =
                error("IOS_NEUTRAL sensor-gap: unexpected internalLastSmbMillis")
            override fun setLastBolusAgeMinutes(minutes: Double) =
                error("IOS_NEUTRAL sensor-gap: unexpected setLastBolusAgeMinutes")
            override fun pkpdMealContext(
                mealData: MealData,
                predictedBgMgdl: Double,
                targetBgMgdl: Double,
            ): MealAggressionContext =
                error("IOS_NEUTRAL sensor-gap: unexpected pkpdMealContext")
            override fun recentPkpdBolusSamples(
                nowMillis: Long,
                fallbackWindowMin: Int,
            ): List<PkpdBolusSample> =
                error("IOS_NEUTRAL sensor-gap: unexpected recentPkpdBolusSamples")
            override fun uamConfidence(): Double =
                error("IOS_NEUTRAL sensor-gap: unexpected uamConfidence")
            override fun physioLatentState(): PhysioLatentState? =
                error("IOS_NEUTRAL sensor-gap: unexpected physioLatentState")
            override fun lastRa(): Double =
                error("IOS_NEUTRAL sensor-gap: unexpected lastRa")
            override fun causalPosterior(): CausalStatePosterior? =
                error("IOS_NEUTRAL sensor-gap: unexpected causalPosterior")
            override fun eventMemory(): PatientEventMemory? =
                error("IOS_NEUTRAL sensor-gap: unexpected eventMemory")
            override fun logPkpdRuntimeFailure(error: Exception) =
                error("IOS_NEUTRAL sensor-gap: unexpected logPkpdRuntimeFailure")
            override fun setCachedPkpdRuntime(runtime: PkPdRuntime) =
                error("IOS_NEUTRAL sensor-gap: unexpected setCachedPkpdRuntime")
            override fun applyBasalFirst(
                bg: Double,
                delta: Float,
                combinedDelta: Float,
                mealData: MealData,
                autosens: AutosensResult,
                isMealAdvisorOneShot: Boolean,
                targetBg: Double,
                rT: RT,
                isConfirmedHighRise: Boolean,
            ) = error("IOS_NEUTRAL sensor-gap: unexpected applyBasalFirst")
        },
    )
}

private object SensorGapSilentUi : UiInteraction {
    override val mainActivity: KClass<*> = SensorGapSilentUi::class
    override val errorHelperActivity: KClass<*> = SensorGapSilentUi::class
    override fun runAlarm(status: String, title: String, sound: AlarmSound?) = Unit
    override fun stopAlarm(reason: String) = Unit
}

/**
 * Locked hypo-rebound scene: BG 180, delta 0, autodrive on, maxSMB 0.40, last bolus 180 min ago.
 * Same inputs as the Android `ShellDecisionTraceTest.captureHypoRebound`, which locks
 * `smb=3fc70a3d80000000` (0.18000000715255737 U = float(0.18)) with no TBR and eventual 180.0.
 *
 * The 0.18 is derived from the fixture (the upstream ISF fusion is not replayed here);
 * the float noise comes from the `.toFloat()` in `decideFinalizeAndCapSmb` (FinalizeAndCapSmb.kt).
 */
data class IosNeutralHypoReboundOutcome(
    val smbU: Double,
    val rT: RT,
)

fun iosNeutralHypoReboundSmb(log: MutableList<String>): IosNeutralHypoReboundOutcome {
    val preferences = IosNeutralMealPreferences().apply {
        doubles[DoubleKey.OApsAIMIMaxSMB.key] = 0.40
        doubles[DoubleKey.OApsAIMIHighBGMaxSMB.key] = 0.40
        booleans[BooleanKey.OApsAIMIautoDriveActive.key] = true
    }
    val rT = RT(runningDynamicIsf = false)
    val reason = StringBuilder()
    var smbU: Double? = null
    val calls = object : AimiPostHypoDriftCalls {
        override fun compression(delta: Float, reason: StringBuilder): Boolean = false
        override fun drift(
            bg: Float,
            targetBg: Float,
            delta: Float,
            avgDelta: Float,
            combinedDelta: Float,
            minDeviation: Double,
            lastBolusVolume: Double,
            reason: StringBuilder,
        ): Boolean = decideDriftTerminatorCondition(
            bg, targetBg, delta, avgDelta, combinedDelta, minDeviation, lastBolusVolume, reason,
        )
        override fun maxSmb(): Double = 0.40
        override fun writeMaxSmb(value: Double): Unit =
            error("IOS_NEUTRAL hypo-rebound: unexpected writeMaxSmb")
        override fun finalize(
            rT: RT,
            proposedUnits: Double,
            reasonHeader: String,
            mealData: MealData,
            hypoThreshold: Double,
            isExplicitUserAction: Boolean,
            decisionSource: String,
            isMealActive: Boolean,
            hyperReleaseFloorU: Double,
            bypassSmbRefractory: Boolean,
        ) {
            // Replicates the Float cast in decideFinalizeAndCapSmb
            // (smbToGiveParam = (proposedUnits * 1.0).toFloat()).
            smbU = proposedUnits.toFloat().toDouble()
            rT.units = smbU
        }
        override fun logFinal(tag: String, rT: RT, bg: Double, delta: Float) = Unit
        override fun markFinal(rT: RT, currentTemp: CurrentTemp?) = Unit
    }
    val ctx = AimiTickContext(
        glucoseStatus = GlucoseStatusAIMI(
            glucose = 180.0,
            delta = 0.0,
            shortAvgDelta = 0.0,
            longAvgDelta = 0.0,
            date = IOS_NEUTRAL_HYPO_REBOUND_NOW_MS,
        ),
        currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
        iobDataArray = arrayOf(IobTotal(time = IOS_NEUTRAL_HYPO_REBOUND_NOW_MS, iob = 1.0, lastBolusTime = 0L)),
        profile = iosNeutralProfile(),
        autosensData = AutosensResult(ratio = 1.0),
        mealData = MealData(mealCOB = 0.0),
        microBolusAllowed = true,
        currentTime = IOS_NEUTRAL_HYPO_REBOUND_NOW_MS,
        flatBGsDetected = false,
        dynIsfMode = false,
        uiInteraction = IosNeutralUiInteraction(),
        extraDebug = "",
    )
    val result = decidePostHypoCompressionAndDriftTerminatorOrReturn(
        ctx = ctx,
        rT = rT,
        bg = 180.0,
        delta = 0.0f,
        threshold = 70.0,
        combinedDelta = 0.0f,
        shortAvgDeltaRawForDrift = 0.0f,
        targetBgMgdl = 100.0f,
        postHypoState = PostHypoState.None,
        autosensRatio = 1.0,
        nightbis = false,
        autodriveEnabledPref = true,
        modesCondition = true,
        hasRecentBolus45m = false,
        totalBolusLastHour = 0.0,
        dynamicPbolusSmall = 0.18,
        exerciseInsulinLockoutActive = false,
        reason = reason,
        preferences = preferences,
        consoleLog = log,
        calls = calls,
    ) ?: error("IOS_NEUTRAL hypo-rebound: drift terminator did not fire")
    val units = smbU ?: error("IOS_NEUTRAL hypo-rebound: SMB not finalized")
    // Scripted from the fixture — the prediction pipeline is not run in this scene.
    rT.eventualBG = 180.0
    return IosNeutralHypoReboundOutcome(smbU = units, rT = rT)
}

/** Pinned clock for the hypo-rebound scene, same `now` as the Android test. */
internal const val IOS_NEUTRAL_HYPO_REBOUND_NOW_MS = 1_700_000_000_000L

/** Silent [UiInteraction] for scenes: the drift path never touches the UI. */
internal class IosNeutralUiInteraction : app.aaps.core.interfaces.ui.UiInteraction {
    override val mainActivity: kotlin.reflect.KClass<*> = Unit::class
    override val errorHelperActivity: kotlin.reflect.KClass<*> = Unit::class
    override fun runAlarm(status: String, title: String, sound: app.aaps.core.interfaces.notifications.AlarmSound?) = Unit
    override fun stopAlarm(reason: String) = Unit
}
