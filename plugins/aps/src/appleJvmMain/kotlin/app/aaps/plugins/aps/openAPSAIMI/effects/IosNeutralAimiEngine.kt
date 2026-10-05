package app.aaps.plugins.aps.openAPSAIMI.effects

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
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPersistence
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.DirectoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.aimiLocalFiles
import app.aaps.plugins.aps.openAPSAIMI.utils.iosTickAimiRoot

/**
 * Scenes the user approved on 2026-10-05. The iOS loop does not construct this while
 * `IosClientConfig.APS` is false. [AimiCommonEngineSwitch][app.aaps.plugins.aimiengine.AimiCommonEngineSwitch]
 * is a second switch, also off by default.
 */
enum class IosNeutralScene {
    MEAL,
    SPORT,
    NIGHT,
    LOW_PREDICTION,
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
        val virtualCobG = decideEstimateUndeclaredVirtualCob(
            enabled = tpoPreferences.get(BooleanKey.OApsAIMIUndeclaredCobEnabled),
            declaredOrAdvisorCob = declaredCobG,
            consoleLog = log,
        ) {
            val signals = virtualCobSignals
            undeclaredVirtualCobInput(
                snapshot = wearable,
                estimatedRaMgdlPerMin = signals.estimatedRaMgdlPerMin,
                isfMgdlPerU = signals.isfMgdlPerU,
                carbRatioGPerU = signals.carbRatioGPerU,
                bgMgdl = signals.bgMgdl,
                deltaMgdl5m = signals.deltaMgdl5m,
                slopeFromMinDeviation = signals.slopeFromMinDeviation,
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
        virtualCobGrams = virtualCobG
        therapyCaches = readTherapyCaches(
            reads = therapy,
            nowMs = aimiWallClockMs(),
            bolusFromMs = 0L,
            bolusAscending = true,
            consoleLog = log,
        )
        val effortFactor = iosNeutralEffortSmbFactor(log, wearable)
        val veto = iosNeutralEffortVeto(log)
        if (scene != IosNeutralScene.LOW_PREDICTION) {
            iosNeutralPatientRuntimeSkipped(log)
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
        check(!cobPreferenceOff || virtualCobG == 0.0)
        check(effortFactor == 1.0)
        check(!veto)
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
        check(scratch.lastPkpdSoftFloorTelemetry == null)
        mealOnset = decideDetectMealOnset(
            delta = 5f,
            predictedDelta = 5f,
            acceleration = 0f,
            predictedBg = 180f,
            targetBg = 100f,
            effortSuppressesUndeclaredMeal = veto,
        )
        return when (scene) {
            IosNeutralScene.MEAL -> meal(state, effortFactor)
            IosNeutralScene.SPORT -> temp(state, iosNeutralSportTbrUph(), "SPORT_TBR")
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
            IosNeutralScene.LOW_PREDICTION -> error("LOW_PREDICTION returns at the floor, before meal onset")
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
