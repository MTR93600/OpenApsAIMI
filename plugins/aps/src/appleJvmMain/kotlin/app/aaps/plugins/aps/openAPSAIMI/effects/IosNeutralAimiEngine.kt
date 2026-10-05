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
) : AimiEngine {

    val portLog: List<String> get() = log
    val scratch: IosEarlyTickScratch = IosEarlyTickScratch()
    val pkpdFloor: IosPkpdFloorMemory = IosPkpdFloorMemory()
    var mealOnset: Boolean? = null
        private set

    private val log = mutableListOf<String>()

    override fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult {
        log.clear()
        iosNeutralTickStart(log)
        val writes = scratch.reset(effectiveDiaHours = 5.0, effectivePeakMinutes = 75.0, noise = 0)
        log += IosNeutralLog.earlyScratch(writes)
        val virtualCobG = iosNeutralVirtualCobG(log)
        val effortFactor = iosNeutralEffortSmbFactor(log)
        val veto = iosNeutralEffortVeto(log)
        iosNeutralPatientRuntimeSkipped(log)
        iosNeutralTpoSkipped(log)
        val wearable = iosNeutralEmptyWearable(log)
        val stored = iosNeutralStorePkpdFloor(iosNeutralFloorCurves(), pkpdFloor, log)
        check(virtualCobG == 0.0)
        check(effortFactor == 1.0)
        check(!veto)
        check(!wearable.isValid)
        check(stored.rawPathMinMgdl == 39.0)
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
            IosNeutralScene.NIGHT -> temp(state, iosNeutralNightTbrUph(), "NIGHT_TBR")
            IosNeutralScene.LOW_PREDICTION -> temp(state, iosNeutralLowPredictionTbrUph(), "LOW_PREDICTION_TBR")
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

/** [HoldAimiEngine] wired to [IosNeutralAimiEngine]. The switch still decides whether it runs. */
fun holdAimiEngineWired(scene: IosNeutralScene): Pair<HoldAimiEngine, IosNeutralAimiEngine> {
    val neutral = IosNeutralAimiEngine(scene)
    return HoldAimiEngine(neutral) to neutral
}
