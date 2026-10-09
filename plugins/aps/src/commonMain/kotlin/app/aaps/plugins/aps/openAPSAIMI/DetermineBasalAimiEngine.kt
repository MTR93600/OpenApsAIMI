package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult
import app.aaps.plugins.aimiengine.AimiEngine

/**
 * Wires [AimiEngine.evaluate] to [DetermineBasalaimiSMB2.determine_basal].
 *
 * Lives in `:plugins:aps` (not `:plugins:aimi-engine`) because the dependency
 * direction is `aps -> aimi-engine`: putting it in aimi-engine would create a
 * cycle.
 *
 * The W5 [AimiInputSnapshot] is a simplified envelope. Fields without a snapshot
 * equivalent use neutral documented defaults (see [GAPS]). This adapter is a
 * functional bridge, not a complete clinical mapping: the snapshot schema must
 * grow before this can drive therapy.
 */
class DetermineBasalAimiEngine(
    private val plugin: DetermineBasalaimiSMB2,
) : AimiEngine {

    override fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult {
        val glucoseStatus = GlucoseStatusAIMI(
            glucose = input.glucose.glucoseMgdl.valueOrNull ?: 0.0,
            // GAP: snapshot has no delta / shortAvgDelta / longAvgDelta / noise.
            delta = 0.0,
            shortAvgDelta = 0.0,
            longAvgDelta = 0.0,
            noise = 0.0,
            date = input.meta.wallClockEpochMs,
        )
        val currentTemp = CurrentTemp(
            duration = ((input.pump.tempBasalRemainingMs ?: 0L) / 60_000L).toInt(),
            rate = input.pump.tempBasalUPerHour.valueOrNull ?: 0.0,
            minutesrunning = null,
        )
        val iobData = arrayOf(
            IobTotal(
                time = input.meta.wallClockEpochMs,
                iob = input.insulin.iobU.valueOrNull ?: 0.0,
                activity = input.insulin.activityUPerHour.valueOrNull ?: 0.0,
            ),
        )
        val profile = OapsProfileAimi(
            dia = (input.profile.diaMs ?: 0L) / 3_600_000.0,
            // GAP: snapshot carries only 6 profile fields; the rest are neutral defaults.
            min_5m_carbimpact = 8.0,
            max_iob = 3.0,
            max_daily_basal = 1.0,
            max_basal = 3.0,
            min_bg = input.profile.memberTargetBgMgdl - 10.0,
            max_bg = input.profile.memberTargetBgMgdl + 70.0,
            target_bg = input.profile.memberTargetBgMgdl,
            carb_ratio = 10.0,
            sens = input.profile.isfMgdlPerU.valueOrNull ?: 50.0,
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
            variable_sens = input.profile.isfMgdlPerU.valueOrNull ?: 50.0,
            insulinDivisor = 1,
            TDD = 50.0,
            peakTime = 75.0,
            futureActivity = 0.0,
            sensorLagActivity = 0.0,
            historicActivity = 0.0,
            currentActivity = 0.0,
        )
        val autosensData = AutosensResult(ratio = 1.0)
        val mealData = MealData(
            mealCOB = input.meal.cobG.valueOrNull ?: 0.0,
            slopeFromMaxDeviation = input.meal.slopeFromMaxDeviation.valueOrNull ?: 0.0,
            slopeFromMinDeviation = input.meal.slopeFromMinDeviation.valueOrNull ?: 999.0,
            lastCarbTime = input.meal.lastCarbTimeMs ?: 0L,
            lastBolusTime = input.meal.lastBolusTimeMs ?: 0L,
        )

        val rt: RT = plugin.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobData,
            profile = profile,
            autosens_data = autosensData,
            mealData = mealData,
            microBolusAllowed = input.pump.pumpCanSmb,
            currentTime = input.meta.wallClockEpochMs,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = SilentUiInteraction,
        )
        return rt.toAimiTickResult(state)
    }

    companion object {
        /**
         * Snapshot fields with no determine_basal equivalent (documented gaps):
         * - duraISF / parabola features (in AimiAdvancedFeatures, not yet consumed)
         * - TDD windows, TIR quality, physio windows (partially in snapshot)
         * - 7 persist keys live in AimiCausalState (transitional mirrors, see syncFromLegacyPrebolus)
         *
         * Closed in schema v2/v3: glucose deltas, full OapsProfileAimi,
         * IOB history, MealData slopes, lastCarbTime and lastBolusTime,
         * 102 config keys in AimiConfigValues.
         */
        const val GAPS = "see KDoc"
    }
}

/** No-op UiInteraction: the engine path never touches the UI. */
private object SilentUiInteraction : UiInteraction {
    override val mainActivity: kotlin.reflect.KClass<*> = Unit::class
    override val errorHelperActivity: kotlin.reflect.KClass<*> = Unit::class
    override fun runAlarm(status: String, title: String, sound: app.aaps.core.interfaces.notifications.AlarmSound?) = Unit
    override fun stopAlarm(reason: String) = Unit
}

/** Maps the APS result onto the engine contract. */
private fun RT.toAimiTickResult(state: AimiEngineState): AimiTickResult {
    val command: AimiTherapyCommand = when {
        units != null && (units ?: 0.0) > 0.0 -> AimiTherapyCommand.Smb(units ?: 0.0)
        rate != null && duration != null -> AimiTherapyCommand.TempBasal(
            rateUPerHour = rate ?: 0.0,
            durationMs = (duration ?: 0) * 60_000L,
        )
        else -> AimiTherapyCommand.Hold(reasonCode = "NO_COMMAND")
    }
    return AimiTickResult(
        command = command,
        nextState = state.copy(generation = state.generation + 1),
        trainingEvents = emptyList(),
        persistenceEvents = emptyList(),
        telemetry = AimiDecisionTrace(reasonCode = reason.toString().take(200)),
        safety = AimiSafetyReport(holdReasonCode = null),
        pairedCommand = null,
    )
}
