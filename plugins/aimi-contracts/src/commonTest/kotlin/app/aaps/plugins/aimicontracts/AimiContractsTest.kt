package app.aaps.plugins.aimicontracts

import app.aaps.core.interfaces.aps.OapsProfileAimi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimiContractsTest {

    @Test
    fun hello_is_a_fixed_string_with_no_aimi_logic() {
        assertEquals("aimi-contracts", AimiContracts.hello())
    }

    @Test
    fun missing_timed_value_is_not_zero() {
        val missing: TimedValue<Double> = TimedValue.Missing("not collected")
        assertNull(missing.valueOrNull)
        assertTrue(missing is TimedValue.Missing)
    }

    @Test
    fun denied_and_unsupported_are_not_missing_and_not_zero() {
        val denied: TimedValue<Double> = TimedValue.Denied("healthkit.heart_rate")
        val unsupported: TimedValue<Double> = TimedValue.Unsupported("healthkit.hrv")
        assertNull(denied.valueOrNull)
        assertNull(unsupported.valueOrNull)
        assertTrue(denied is TimedValue.Denied)
        assertTrue(unsupported is TimedValue.Unsupported)
    }

    @Test
    fun profile_snapshot_carries_full_oaps_profile() {
        val oaps = testOapsProfile(targetBg = 100.0)
        val profile = AimiProfileSnapshot(profile = oaps)
        assertEquals(100.0, profile.profile.target_bg)
        assertEquals(50.0, profile.profile.sens)
    }

    @Test
    fun rmssd_and_sdnn_are_separate_fields() {
        val physio = AimiPhysiologySnapshot(
            heartRateBpm = TimedValue.Missing("not collected"),
            steps = TimedValue.Missing("not collected"),
            hrvRmssdMs = TimedValue.Fresh(value = 42.0, capturedAtEpochMs = 1L, ageMs = 0L),
            hrvSdnnMs = TimedValue.Fresh(value = 80.0, capturedAtEpochMs = 1L, ageMs = 0L),
        )
        assertEquals(42.0, physio.hrvRmssdMs.valueOrNull)
        assertEquals(80.0, physio.hrvSdnnMs.valueOrNull)
        assertNotEquals(physio.hrvRmssdMs.valueOrNull, physio.hrvSdnnMs.valueOrNull)
    }

    @Test
    fun snapshot_round_trip_copy_is_equal() {
        val missingBg: TimedValue<Double> = TimedValue.Missing("not collected")
        val snap = AimiInputSnapshot(
            meta = AimiTickMeta(
                schemaVersion = 1,
                tickId = 1L,
                wallClockEpochMs = 1L,
                monotonicMs = 1L,
                timezoneOffsetMinutes = 0,
                trigger = AimiTickTrigger.Cgm,
            ),
            glucose = AimiGlucoseSnapshot(
                glucoseMgdl = missingBg,
                sourceId = "AAPS-Libre3",
                warmup = AimiGlucoseWarmup.None,
                loopEligible = false,
            ),
            pump = AimiPumpSnapshot(
                profileBasalUPerHour = TimedValue.Missing("not collected"),
                tempBasalUPerHour = TimedValue.Missing("not collected"),
                tempBasalRemainingMs = null,
                maxBolusU = null,
                maxBasalUPerHour = null,
                pumpCanSmb = false,
                pumpCanTempBasal = false,
            ),
            profile = AimiProfileSnapshot(
                profile = testOapsProfile(targetBg = 100.0),
            ),
            insulin = AimiInsulinSnapshot(
                iobU = TimedValue.Missing("not collected"),
                activityUPerHour = TimedValue.Missing("not collected"),
            ),
            meal = AimiMealSnapshot(
                cobG = TimedValue.Missing("not collected"),
                lastCarbsG = TimedValue.Missing("not collected"),
            ),
            physiology = AimiPhysiologySnapshot(
                heartRateBpm = TimedValue.Missing("not collected"),
                steps = TimedValue.Missing("not collected"),
                hrvRmssdMs = TimedValue.Missing("not collected"),
                hrvSdnnMs = TimedValue.Missing("not collected"),
            ),
            config = AimiConfigSnapshot(schemaVersion = 1),
            capabilities = AimiCapabilitySnapshot(closedLoopAllowed = false),
        )
        assertEquals(snap, snap.copy())
    }

    @Test
    fun hold_command_carries_stable_reason() {
        val hold = AimiTherapyCommand.Hold("ENGINE_NOT_EXTRACTED")
        assertEquals("ENGINE_NOT_EXTRACTED", hold.reasonCode)
    }

    private fun testOapsProfile(targetBg: Double = 100.0): OapsProfileAimi =
        OapsProfileAimi(
            dia = 5.0,
            min_5m_carbimpact = 8.0,
            max_iob = 3.0,
            max_daily_basal = 1.0,
            max_basal = 3.0,
            min_bg = targetBg - 10.0,
            max_bg = targetBg + 70.0,
            target_bg = targetBg,
            carb_ratio = 10.0,
            sens = 50.0,
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
            variable_sens = 50.0,
            insulinDivisor = 1,
            TDD = 50.0,
            peakTime = 75.0,
            futureActivity = 0.0,
            sensorLagActivity = 0.0,
            historicActivity = 0.0,
            currentActivity = 0.0,
        )
}
