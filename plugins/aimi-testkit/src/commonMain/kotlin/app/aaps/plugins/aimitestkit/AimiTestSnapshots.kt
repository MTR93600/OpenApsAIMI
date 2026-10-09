package app.aaps.plugins.aimitestkit

import app.aaps.core.interfaces.aps.OapsProfileAimi
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
                delta = missingDouble(),
                shortAvgDelta = missingDouble(),
                longAvgDelta = missingDouble(),
                noise = missingDouble(),
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
                profile = testProfile(),
            ),
            insulin = AimiInsulinSnapshot(
                iobU = missingDouble(),
                activityUPerHour = missingDouble(),
                iobHistory = emptyList(),
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

    /**
     * Default test profile. Neutral values, not clinical.
     */
    fun testProfile(
        targetBg: Double = 100.0,
        isf: Double = 50.0,
        carbRatio: Double = 10.0,
        diaHours: Double = 5.0,
    ): OapsProfileAimi = OapsProfileAimi(
        dia = diaHours,
        min_5m_carbimpact = 8.0,
        max_iob = 3.0,
        max_daily_basal = 1.0,
        max_basal = 3.0,
        min_bg = targetBg - 10.0,
        max_bg = targetBg + 70.0,
        target_bg = targetBg,
        carb_ratio = carbRatio,
        sens = isf,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 3.0,
        current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = true,
        resistance_lowers_target = true,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 90,
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
        carbsReqThreshold = 0,
        current_basal = 1.0,
        temptargetSet = false,
        autosens_max = 1.2,
        out_units = "mg/dL",
        lgsThreshold = null,
        variable_sens = isf,
        insulinDivisor = 1,
        TDD = 50.0,
        peakTime = 75.0,
        futureActivity = 0.0,
        sensorLagActivity = 0.0,
        historicActivity = 0.0,
        currentActivity = 0.0,
    )

    fun emptyState(generation: Long = 0L): AimiEngineState {
        return AimiEngineState(schemaVersion = 1, generation = generation)
    }

    fun emptyModels(): AimiModelBundle {
        return AimiModelBundle(uamSchemaId = "uam-v1", uamSha256 = null)
    }
}
