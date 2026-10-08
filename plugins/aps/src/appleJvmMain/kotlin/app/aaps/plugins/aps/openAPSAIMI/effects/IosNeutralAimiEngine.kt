package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult
import app.aaps.plugins.aimiengine.AimiEngine
import app.aaps.plugins.aimiengine.HoldAimiEngine
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPersistence
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.DirectoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.aimiLocalFiles
import app.aaps.plugins.aps.openAPSAIMI.utils.iosTickAimiRoot

/**
 * Scenes the user approved on 2026-10-05 (MEAL, SPORT, NIGHT, LOW_PREDICTION) and
 * 2026-10-08 (FASTING, first of the five iOS scenes). The iOS loop does not construct this while
 * `IosClientConfig.APS` is false. [AimiCommonEngineSwitch][app.aaps.plugins.aimiengine.AimiCommonEngineSwitch]
 * is a second switch, also off by default.
 */
enum class IosNeutralScene {
    MEAL,
    SPORT,
    NIGHT,
    LOW_PREDICTION,
    FASTING,
    SENSOR_GAP,
}

/**
 * Common engine behind [HoldAimiEngine]. Each neutral port logs its mode on [portLog].
 * Hysteresis follows the Android process singleton. `reset()` is not called here.
 */
class IosNeutralAimiEngine(
    private val scene: IosNeutralScene,
    private val therapy: AimiTherapyReads,
    private val tpoStorage: AimiStorage,
    private val tpoPreferences: Preferences,
) : AimiEngine {

    /**
     * [tpoStorage] and a preference file loaded from it. Tests that pass their own storage do not
     * share the process directory.
     */
    constructor(
        scene: IosNeutralScene,
        therapy: AimiTherapyReads = iosTickTherapyReads,
        tpoStorage: AimiStorage = iosTickTpoStorage,
    ) : this(scene, therapy, tpoStorage, JsonBackedPreferences(tpoStorage))

    val portLog: List<String> get() = log
    val scratch: IosEarlyTickScratch = IosEarlyTickScratch()
    val pkpdFloor: IosPkpdFloorMemory = IosPkpdFloorMemory()
    internal var therapyCaches: TherapyReadCaches = TherapyReadCaches.EMPTY
        private set
    var mealOnset: Boolean? = null
        private set

    /** Grams from [decideEstimateUndeclaredVirtualCob]. Zero when the preference is off. */
    var virtualCobGrams: Double = 0.0
        private set

    /** Null reads the platform cache. A test passes the snapshot the tick would have cached. */
    internal var wearableSnapshot: HealthContextSnapshot? = null

    /** Empty Android tick: no Ra and meal probability 0, so the estimator gates. */
    internal var virtualCobSignals: VirtualCobTickSignals = VirtualCobTickSignals()

    /** Already declared carbs. The neutral scenes have none. */
    internal var declaredCobG: Double = 0.0

    /**
     * Kinematics of the locked sport scene: delta +5, acceleration 0, so onset stays false.
     * A test that sets acceleration above 1.2 can make the common onset function return true.
     */
    internal var onsetDelta: Float = 5f
    internal var onsetPredictedDelta: Float = 5f
    internal var onsetAcceleration: Float = 0f
    internal var onsetPredictedBg: Float = 180f
    internal var onsetTargetBg: Float = 100f
    internal var onsetAssessment: EffortActivityBelief.Assessment? = null
    internal var onsetDeclaredMeal: Boolean = false
    internal var onsetCobG: Double = 0.0

    /** SMB ceiling after the session check. Null until [evaluate] runs. */
    var tpoMaxSmb: Double? = null
        private set

    /**
     * Insulin request of the locked activity scene when a session is still active.
     * Null when there is no session. Night and sport commands do not read it.
     */
    var tpoInsulinReqU: Double? = null
        private set

    private val log = mutableListOf<String>()
    private val learnerPersistence = LearnerColdPersistence()
    private val learnerClock = LearnerColdClock()
    private val learnerLog = LearnerColdLogger()

    override fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult {
        log.clear()
        iosNeutralTickStart(log)
        val writes = scratch.reset(effectiveDiaHours = 5.0, effectivePeakMinutes = 75.0, noise = 0)
        log += IosNeutralLog.earlyScratch(writes)
        val wearable = wearableSnapshot?.also {
            if (!it.isValid) log += IosNeutralLog.WEARABLE
        } ?: iosPlatformWearable(log)
        val curvePublished = publishVirtualCobCurve(wearable)
        therapyCaches = readTherapyCaches(
            reads = therapy,
            nowMs = aimiWallClockMs(),
            bolusFromMs = 0L,
            bolusAscending = true,
            consoleLog = log,
        )
        val effortFactor = iosNeutralEffortSmbFactor(log, wearable)
        val veto = iosNeutralEffortVeto(log)
        when (scene) {
            // Locked scene glucose. The path-39 curve stays on the low prediction, after the floor.
            IosNeutralScene.MEAL -> log += scenePatientLog(
                snapshot = wearable,
                nowMs = aimiWallClockMs(),
                bgMgdl = 160.0,
                deltaMgdl = 2.0,
            )
            IosNeutralScene.SPORT -> log += scenePatientLog(
                snapshot = wearable,
                nowMs = aimiWallClockMs(),
                bgMgdl = 180.0,
                deltaMgdl = 5.0,
            )
            IosNeutralScene.NIGHT -> log += scenePatientLog(
                snapshot = wearable,
                nowMs = aimiWallClockMs(),
                bgMgdl = 180.0,
                deltaMgdl = 0.0,
            )
            IosNeutralScene.FASTING -> log += scenePatientLog(
                snapshot = wearable,
                nowMs = aimiWallClockMs(),
                bgMgdl = 110.0,
                deltaMgdl = 2.0,
            )
            IosNeutralScene.LOW_PREDICTION -> Unit
            IosNeutralScene.SENSOR_GAP -> log += scenePatientLog(
                snapshot = wearable,
                nowMs = aimiWallClockMs(),
                bgMgdl = 110.0,
                deltaMgdl = 0.0,
            )
        }
        val ceiling = decideTpoSessionAtTickStart(
            nowMs = aimiWallClockMs(),
            preferences = tpoPreferences,
            persistence = TpoPersistence(tpoStorage),
        )
        tpoMaxSmb = ceiling.maxSmb
        val session = TpoPersistence(tpoStorage).loadSession()
        if (session?.status == TpoSessionStatus.ACTIVE) {
            tpoInsulinReqU = activityProtectionInsulinReq(ceiling.maxSmb, log)
        }
        val cobPreferenceOff = !tpoPreferences.get(BooleanKey.OApsAIMIUndeclaredCobEnabled) || declaredCobG > 0.0
        check(!cobPreferenceOff || virtualCobGrams == 0.0)
        check(effortFactor == 1.0)
        if (onsetAssessment == null) check(!veto)
        check(!wearable.isValid)
        if (scene == IosNeutralScene.LOW_PREDICTION) {
            // Android records the floor inside advanced predictions, after the wearable read,
            // then the patient runtime, then safety returns. Meal onset is not reached.
            val stored = iosNeutralStorePkpdFloor(iosNeutralFloorCurves(), pkpdFloor, scratch, log)
            check(stored.rawPathMinMgdl == 39.0)
            check(scratch.lastPkpdSoftFloorTelemetry == stored)
            log += lowPredictionPatientLog(snapshot = wearable, nowMs = aimiWallClockMs())
            return temp(state, iosNeutralLowPredictionTbrUph(), "LOW_PREDICTION_TBR")
        }
        if (!curvePublished) check(scratch.lastPkpdSoftFloorTelemetry == null)
        mealOnset = decideMealOnsetBehindEffortVeto(
            delta = onsetDelta,
            predictedDelta = onsetPredictedDelta,
            acceleration = onsetAcceleration,
            predictedBg = onsetPredictedBg,
            targetBg = onsetTargetBg,
            assessment = onsetAssessment,
            declaredMeal = onsetDeclaredMeal,
            cobG = onsetCobG,
        )
        return when (scene) {
            IosNeutralScene.MEAL -> meal(state, effortFactor)
            IosNeutralScene.SPORT -> iosNeutralSportBasal(
                state = state,
                preferences = tpoPreferences,
                consoleLog = log,
                onsetDelta = onsetDelta,
                onsetAcceleration = onsetAcceleration,
                onsetPredictedBg = onsetPredictedBg,
                onsetTargetBg = onsetTargetBg,
                onsetAssessment = onsetAssessment,
                onsetDeclaredMeal = onsetDeclaredMeal,
                onsetCobG = onsetCobG,
            )
            IosNeutralScene.NIGHT -> {
                log += coldLearnerNightLines(
                    storage = tpoStorage,
                    preferences = tpoPreferences,
                    persistence = learnerPersistence,
                    dateUtil = learnerClock,
                    log = learnerLog,
                )
                temp(state, iosNeutralNightTbrUph(), "NIGHT_TBR")
            }
            IosNeutralScene.FASTING -> {
                val outcome = iosNeutralFastingMealHyper(log)
                val rate = (outcome as? AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate)?.rate
                    ?: error("IOS_NEUTRAL fasting did not return an optional rate")
                temp(state, rate, "FASTING_TBR")
            }
            IosNeutralScene.LOW_PREDICTION -> error("LOW_PREDICTION returns at the floor, before meal onset")
            IosNeutralScene.SENSOR_GAP -> {
                val outcome = iosNeutralSensorGapSignal(log)
                check(outcome is AimiSignalPrepPkpd.StaleAbort) {
                    "IOS_NEUTRAL sensor-gap did not abort on stale data"
                }
                hold(state, "STALE_DATA")
            }
        }
    }

    /**
     * Preference on and no declared carbs: the estimator runs inside [decideApplyAdvancedPredictions],
     * so the grams are the curve's `cobG`. Preference off, or carbs already declared: 0 g, no curve,
     * and the low-prediction floor stays the only `PKPD_SOFT_FLOOR` on the other scenes.
     */
    private fun publishVirtualCobCurve(wearable: HealthContextSnapshot): Boolean {
        val enabled = tpoPreferences.get(BooleanKey.OApsAIMIUndeclaredCobEnabled)
        if (!enabled || declaredCobG > 0.0) {
            virtualCobGrams = estimateVirtualCob(wearable, declaredCobG)
            return false
        }
        val signals = virtualCobSignals
        val profile = virtualCobCurveProfile(signals.carbRatioGPerU)
        val meal = MealData(mealCOB = 0.0).also { it.slopeFromMinDeviation = signals.slopeFromMinDeviation }
        val rT = RT(runningDynamicIsf = false)
        decideApplyAdvancedPredictions(
            bg = signals.bgMgdl,
            delta = signals.deltaMgdl5m.toFloat(),
            sens = signals.isfMgdlPerU,
            iobDataArray = arrayOf(IobTotal(time = aimiWallClockMs(), iob = 0.0, activity = 0.0)),
            mealData = meal,
            profile = profile,
            rT = rT,
            preferences = tpoPreferences,
            consoleLog = log,
            calls = object : AimiAdvancedPredictionCalls {
                override fun nowMs() = aimiWallClockMs()
                override fun virtualCob(
                    bg: Double,
                    delta: Float,
                    sens: Double,
                    profile: OapsProfileAimi,
                    mealData: MealData,
                    declaredOrAdvisorCob: Double,
                ) = estimateVirtualCob(wearable, declaredOrAdvisorCob, bg, delta, sens, profile, mealData)
                    .also { virtualCobGrams = it }

                override fun recordSoftFloor(curves: AdvancedPredictionCurves): PkpdSoftFloorTelemetry =
                    decideRecordPkpdSoftFloor(
                        curves = curves,
                        endogenousReversionEnabled = tpoPreferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
                        calls = object : AimiPkpdSoftFloorWrite {
                            override fun writeTelemetryAndLog(telemetry: PkpdSoftFloorTelemetry) {
                                scratch.lastPkpdSoftFloorTelemetry = telemetry
                                log += PkpdSoftFloorPathMin.formatLogLine(telemetry)
                            }
                        },
                    )

                override fun writeCurves(curves: AdvancedPredictionCurves) = Unit
                override fun writePredictionSize(size: Int) = Unit
                override fun writePredictionAvailable(available: Boolean) = Unit
                override fun writeEventualSnapshot(value: Double) = Unit
                override fun writePredictedBg(value: Float) = Unit
                override fun logError(message: String) = Unit
            },
        )
        return true
    }

    private fun estimateVirtualCob(
        wearable: HealthContextSnapshot,
        declaredOrAdvisorCob: Double,
        bg: Double = virtualCobSignals.bgMgdl,
        delta: Float = virtualCobSignals.deltaMgdl5m.toFloat(),
        sens: Double = virtualCobSignals.isfMgdlPerU,
        profile: OapsProfileAimi = virtualCobCurveProfile(virtualCobSignals.carbRatioGPerU),
        mealData: MealData = MealData(mealCOB = 0.0).also {
            it.slopeFromMinDeviation = virtualCobSignals.slopeFromMinDeviation
        },
    ): Double {
        val signals = virtualCobSignals
        return decideEstimateUndeclaredVirtualCob(
            enabled = tpoPreferences.get(BooleanKey.OApsAIMIUndeclaredCobEnabled),
            declaredOrAdvisorCob = declaredOrAdvisorCob,
            consoleLog = log,
        ) {
            undeclaredVirtualCobInput(
                snapshot = wearable,
                estimatedRaMgdlPerMin = signals.estimatedRaMgdlPerMin,
                isfMgdlPerU = sens,
                carbRatioGPerU = profile.carb_ratio,
                bgMgdl = bg,
                deltaMgdl5m = delta.toDouble(),
                slopeFromMinDeviation = mealData.slopeFromMinDeviation,
                patientWeightKg = tpoPreferences.get(DoubleKey.OApsAIMIweight),
                tdd24hU = signals.tdd24hU,
                activityContextActive = signals.activityContextActive,
                mealProb = signals.mealProb,
                falseMealSuppression = signals.falseMealSuppression,
                exerciseLockoutActive = signals.exerciseLockoutActive,
                postHypoActive = signals.postHypoActive,
                cfrdExacerbationActive =
                    tpoPreferences.get(BooleanKey.OApsAIMIT3cCfrdMode) &&
                        tpoPreferences.get(BooleanKey.OApsAIMIT3cCfrdExacerbationMode),
                maxGramsPref = tpoPreferences.get(DoubleKey.OApsAIMIUndeclaredCobMaxG),
            )
        }
    }

    private fun meal(state: AimiEngineState, effortFactor: Double): AimiTickResult {
        val applied = iosNeutralMealAdvisor(effortFactor, log)
        val smb = applied.bolusU ?: error("IOS_NEUTRAL meal SMB missing")
        val tbr = applied.tbrUph ?: error("IOS_NEUTRAL meal TBR missing")
        val minutes = applied.tbrMin ?: error("IOS_NEUTRAL meal TBR duration missing")
        return AimiTickResult(
            command = AimiTherapyCommand.Smb(smb),
            nextState = state,
            trainingEvents = emptyList(),
            persistenceEvents = emptyList(),
            telemetry = AimiDecisionTrace("MEAL_ADVISOR"),
            safety = AimiSafetyReport(holdReasonCode = null),
            pairedCommand = AimiTherapyCommand.TempBasal(
                rateUPerHour = tbr,
                durationMs = minutes.toLong() * 60L * 1000L,
            ),
        )
    }

    private fun temp(state: AimiEngineState, rateUph: Double, reason: String): AimiTickResult {
        return AimiTickResult(
            command = AimiTherapyCommand.TempBasal(rateUph, IOS_NEUTRAL_TBR_DURATION_MS),
            nextState = state,
            trainingEvents = emptyList(),
            persistenceEvents = emptyList(),
            telemetry = AimiDecisionTrace(reason),
            safety = AimiSafetyReport(holdReasonCode = null),
        )
    }

    private fun hold(state: AimiEngineState, reason: String): AimiTickResult {
        return AimiTickResult(
            command = AimiTherapyCommand.Hold(reason),
            nextState = state,
            trainingEvents = emptyList(),
            persistenceEvents = emptyList(),
            telemetry = AimiDecisionTrace(reason),
            safety = AimiSafetyReport(holdReasonCode = reason),
        )
    }
}

/**
 * One Room file for the iOS tick. Tests that pass their own [AimiTherapyReads] do not open it.
 * The DAOs stay `suspend`. [IosNeutralAimiEngine.evaluate] stays a normal function.
 */
private val iosTickTherapyReads: AimiTherapyReads by lazy { openIosTickTherapyReads() }

/** One AIMI directory for the iOS tick. `tpo/tpo_session.json` lives here. */
private val iosTickTpoStorage: AimiStorage by lazy {
    DirectoryAimiStorage(iosTickAimiRoot(), aimiLocalFiles())
}

/** [HoldAimiEngine] wired to [IosNeutralAimiEngine]. The switch still decides whether it runs. */
fun holdAimiEngineWired(
    scene: IosNeutralScene,
    therapy: AimiTherapyReads = iosTickTherapyReads,
    tpoStorage: AimiStorage = iosTickTpoStorage,
): Pair<HoldAimiEngine, IosNeutralAimiEngine> {
    val neutral = IosNeutralAimiEngine(scene, therapy, tpoStorage)
    return HoldAimiEngine(neutral) to neutral
}
