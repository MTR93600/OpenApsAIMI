package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
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
 * The snapshot carries glucose deltas, the full profile and IOB history.
 * Remaining gaps are documented in [GAPS].
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
            delta = input.glucose.delta.valueOrNull ?: 0.0,
            shortAvgDelta = input.glucose.shortAvgDelta.valueOrNull ?: 0.0,
            longAvgDelta = input.glucose.longAvgDelta.valueOrNull ?: 0.0,
            noise = input.glucose.noise.valueOrNull ?: 0.0,
            date = input.meta.wallClockEpochMs,
        )
        val currentTemp = CurrentTemp(
            duration = ((input.pump.tempBasalRemainingMs ?: 0L) / 60_000L).toInt(),
            rate = input.pump.tempBasalUPerHour.valueOrNull ?: 0.0,
            minutesrunning = null,
        )
        // Full IOB history when the shell captured it; single current point otherwise.
        val iobData = if (input.insulin.iobHistory.isNotEmpty()) {
            input.insulin.iobHistory.toTypedArray()
        } else {
            arrayOf(
                IobTotal(
                    time = input.meta.wallClockEpochMs,
                    iob = input.insulin.iobU.valueOrNull ?: 0.0,
                    activity = input.insulin.activityUPerHour.valueOrNull ?: 0.0,
                ),
            )
        }
        // Full profile comes from the snapshot now; no neutral defaults.
        val profile = input.profile.profile
        // Autosens ratio from the snapshot. Falls back to 1.0 (neutral) ONLY when
        // the snapshot marks it Missing (autosens did not run this tick). This is
        // the same default AutosensResult itself declares ("autosens not available").
        val autosensData = AutosensResult(
            ratio = input.autosens.ratio.valueOrNull ?: 1.0,
        )
        val mealData = MealData(
            mealCOB = input.meal.cobG.valueOrNull ?: 0.0,
            slopeFromMaxDeviation = input.meal.slopeFromMaxDeviation.valueOrNull ?: 0.0,
            slopeFromMinDeviation = input.meal.slopeFromMinDeviation.valueOrNull ?: 999.0,
            lastCarbTime = input.meal.lastCarbTimeMs ?: 0L,
            lastBolusTime = input.meal.lastBolusTimeMs ?: 0L,
        )
        // PKPD IOB array: pass only when the shell computed learned kinetics.
        // Null means "not available": the engine falls back to iob_data_array.
        val pkpdIobData: Array<IobTotal>? =
            input.insulin.pkpdIobHistory.toTypedArray().takeIf { it.isNotEmpty() }

        val rt: RT = plugin.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobData,
            profile = profile,
            autosens_data = autosensData,
            mealData = mealData,
            microBolusAllowed = input.pump.pumpCanSmb,
            currentTime = input.meta.wallClockEpochMs,
            flatBGsDetected = input.bgQuality.flatBGsDetected,
            dynIsfMode = input.dynIsfMode,
            uiInteraction = SilentUiInteraction,
            pkpd_iob_data_array = pkpdIobData,
            effective_dia_hours = input.kinetics.effectiveDiaHours,
            effective_peak_minutes = input.kinetics.effectivePeakMinutes,
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
         * Closed: glucose deltas, full OapsProfileAimi, IOB history,
         * MealData slopes, lastCarbTime and lastBolusTime, 102 config keys,
         * autosens ratio, flatBGsDetected, dynIsfMode, PKPD IOB array,
         * effective DIA hours and peak minutes.
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

/**
 * Maps the APS result onto the engine contract.
 *
 * Internal so the Android parity capture hook can reuse the exact same
 * mapping when recording the expected result.
 */
internal fun RT.toAimiTickResult(state: AimiEngineState): AimiTickResult {
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
