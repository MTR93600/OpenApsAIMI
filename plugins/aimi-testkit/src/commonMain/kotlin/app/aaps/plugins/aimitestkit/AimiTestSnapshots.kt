package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.AimiCapabilitySnapshot
import app.aaps.plugins.aimicontracts.AimiConfigSnapshot
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiGlucoseSnapshot
import app.aaps.plugins.aimicontracts.AimiGlucoseWarmup
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiInsulinSnapshot
import app.aaps.plugins.aimicontracts.AimiMealSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiPhysiologySnapshot
import app.aaps.plugins.aimicontracts.AimiProfileSnapshot
import app.aaps.plugins.aimicontracts.AimiPumpSnapshot
import app.aaps.plugins.aimicontracts.AimiTickMeta
import app.aaps.plugins.aimicontracts.AimiTickTrigger
import app.aaps.plugins.aimicontracts.TimedValue

/**
 * Small fixtures for engine tests. Missing values stay Missing, never 0.
 */
object AimiTestSnapshots {

    fun missingDouble(reason: String = "not collected"): TimedValue<Double> =
        TimedValue.Missing(reason)

    fun missingInt(reason: String = "not collected"): TimedValue<Int> =
        TimedValue.Missing(reason)

    fun emptyInput(
        tickId: Long = 1L,
        sourceId: String? = "AAPS-DexcomOnePlus",
        loopEligible: Boolean = false,
        closedLoopAllowed: Boolean = false,
    ): AimiInputSnapshot {
        return AimiInputSnapshot(
            meta = AimiTickMeta(
                schemaVersion = 1,
                tickId = tickId,
                wallClockEpochMs = 1L,
                monotonicMs = 1L,
                timezoneOffsetMinutes = 0,
                trigger = AimiTickTrigger.Cgm,
            ),
            glucose = AimiGlucoseSnapshot(
                glucoseMgdl = missingDouble(),
                sourceId = sourceId,
                warmup = AimiGlucoseWarmup.None,
                loopEligible = loopEligible,
            ),
            pump = AimiPumpSnapshot(
                profileBasalUPerHour = missingDouble(),
                tempBasalUPerHour = missingDouble(),
                tempBasalRemainingMs = null,
                maxBolusU = null,
                maxBasalUPerHour = null,
                pumpCanSmb = false,
                pumpCanTempBasal = false,
            ),
            profile = AimiProfileSnapshot(
                memberTargetBgMgdl = 100.0,
                scheduleTargetBgMgdl = 110.0,
                isfMgdlPerU = missingDouble("not captured"),
                icGPerU = missingDouble("not captured"),
                diaMs = null,
                peakMs = null,
            ),
            insulin = AimiInsulinSnapshot(
                iobU = missingDouble(),
                activityUPerHour = missingDouble(),
            ),
            meal = AimiMealSnapshot(
                cobG = missingDouble(),
                lastCarbsG = missingDouble(),
            ),
            physiology = AimiPhysiologySnapshot(
                heartRateBpm = missingDouble(),
                steps = missingInt(),
                hrvRmssdMs = missingDouble(),
                hrvSdnnMs = missingDouble(),
            ),
            config = AimiConfigSnapshot(schemaVersion = 1),
            capabilities = AimiCapabilitySnapshot(closedLoopAllowed = closedLoopAllowed),
        )
    }

    fun emptyState(generation: Long = 0L): AimiEngineState {
        return AimiEngineState(schemaVersion = 1, generation = generation)
    }

    fun emptyModels(): AimiModelBundle {
        return AimiModelBundle(uamSchemaId = "uam-v1", uamSha256 = null)
    }
}
